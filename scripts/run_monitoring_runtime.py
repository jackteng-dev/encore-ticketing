#!/usr/bin/env python3
"""Own scope only: actual V23 upgrade, two API/two nonweb JVMs, Chromium and dependency faults."""
import argparse,hashlib,json,os,socket,subprocess,time,zipfile
from pathlib import Path
import urllib.request,urllib.error
from run_deployment_runtime import ROOT,private,require,migration_version

def main():
 p=argparse.ArgumentParser();p.add_argument('--env',required=True);p.add_argument('--seed-jar',required=True);p.add_argument('--baseline-jar',required=True);p.add_argument('--java-home',required=True);p.add_argument('--output',required=True);a=p.parse_args()
 envfile=private(a.env);seed=private(a.seed_jar);baseline=private(a.baseline_jar);scope=private(a.output);require(not scope.exists(),'Preserve prior evidence');scope.mkdir(mode=0o700)
 require(hashlib.sha256(seed.read_bytes()).hexdigest()=='f5d9cc294c114ffd19c107c86985c64a061a66e6b227cd55d399aa146729e7a0'and hashlib.sha256(baseline.read_bytes()).hexdigest()=='f5010bf77fd9797de2c7ea9994f15fc0fda56e44226ef9f5ce3fc8eeadada852','Actual verified V22 seed and V23 baseline required')
 cfg=dict(l.split('=',1)for l in envfile.read_text().splitlines()if l and not l.startswith('#'));project=cfg['COMPOSE_PROJECT_NAME'];require(project.startswith('ticket-monitor-runtime-')and cfg['TICKET_DB_URL']=='jdbc:postgresql://127.0.0.1:25432/ticket_demo'and cfg['TICKET_COOKIE_SECURE']=='false','Own loopback fixture required')
 env=os.environ.copy();env.update(cfg)
 for k in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH']:env.pop(k,None)
 env.update(TICKET_DEMO_BOOTSTRAP='false',TICKET_ORDERS_ENABLED='true',TICKET_PURCHASES_ENABLED='true',TICKET_DELIVERY_ENABLED='true',TICKET_PUBLISHER_ENABLED='true',TICKET_CONSUMER_ENABLED='true',TICKET_EXPIRY_ENABLED='false',TICKET_STOCK_RETURN_ENABLED='true',TICKET_STOCK_RETURN_RECOVERY_ENABLED='true',TICKET_STOCK_RETURN_DISCOVERY_ENABLED='true',TICKET_MONITORING_ENABLED='true',TICKET_MONITORING_EXPECTED_APIS='2',TICKET_MONITORING_EXPECTED_WORKERS='2',TICKET_MONITORING_STALE_SECONDS='15',TICKET_RUNTIME_ENV=str(envfile),TICKET_MONITOR_FIXTURE=str(scope/'fixture.json'),TICKET_FRONTEND_URL='http://127.0.0.1:15180')
 docker=['docker','--host=unix:///var/run/docker.sock'];compose=docker+['compose','--env-file',str(envfile),'-f',str(ROOT/'infra/compose.yml'),'-f',str(ROOT/'infra/compose.mq.yml')]
 def dock(args):return subprocess.run(docker+args,env=env,text=True,capture_output=True,check=True,timeout=30).stdout.strip()
 queries={'containers':['ps','-aq'],'volumes':['volume','ls','-q'],'networks':['network','ls','-q']}
 def owned(k):return dock(queries[k]+['--filter','label=com.docker.compose.project='+project]).splitlines()
 require(not any(owned(k)for k in queries),'Existing scope forbidden')
 for port in [25432,26379,25672,19110,19111,15180]:
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',port))!=0,'Own port occupied')
 def cid(name):
  ids=dock(['ps','-aq','--filter','label=com.docker.compose.project='+project,'--filter','label=com.docker.compose.service='+name]).splitlines();require(len(ids)==1,'Exactly one owned service');return ids[0]
 def sql(query):
  r=subprocess.run(docker+['exec','-i',cid('postgres'),'psql','-U',cfg['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=env,input='BEGIN READ ONLY;\n'+query+'\nCOMMIT;',capture_output=True,text=True,check=True,timeout=20).stdout
  return '\n'.join(l for l in r.splitlines()if l not in ['BEGIN','COMMIT']).strip()
 def until(fn,seconds=60):
  end=time.monotonic()+seconds
  while not fn():require(time.monotonic()<end,'Runtime deadline exceeded');time.sleep(.25)
 def save(name,data):
  f=scope/name;require(not f.exists(),'Preserve metadata');f.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');f.chmod(0o600)
 def run(args,name,extra=None,timeout=180):
  with(scope/(name+'.log')).open('x')as f:Path(f.name).chmod(0o600);subprocess.run(args,cwd=ROOT,env=extra or env,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=timeout)
 java=Path(a.java_home).resolve()/'bin/java';require(java.is_file(),'Installed complete JDK21 required');jvm=[str(java),'-Xmx256m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=2','-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'];final=ROOT/'backend/target/ticket-platform-0.1.0.jar';require(migration_version(final)==24,'Full verified V24 required')
 probe=ROOT/'backend/target/test-classes/dev/elpsy/ticket/inventory/ReturnDiscoverySeedProbe.class';archive=scope/'seed-probe.jar'
 with zipfile.ZipFile(archive,'x')as z:z.writestr('dev/elpsy/ticket/inventory/ReturnDiscoverySeedProbe.class',probe.read_bytes())
 archive.chmod(0o600)
 paths=[final,probe,Path(__file__).resolve(),ROOT/'scripts/smoke_monitoring_browser.cjs',*sorted((ROOT/'frontend/dist').rglob('*')),*sorted((ROOT/'backend/src/main/resources/db/migration').glob('V*.sql'))]
 def hashes():return{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()for p in paths if p.is_file()}
 frozen=hashes();processes=[];started=False;results=[];extraChecks=[]
 def launch(name,args,extra=None):
  f=(scope/(name+'.log')).open('x');Path(f.name).chmod(0o600);proc=subprocess.Popen(args,cwd=ROOT,env=extra or env,stdout=f,stderr=subprocess.STDOUT);processes.append((name,proc,f));return proc
 def stop(proc):
  if proc.poll()is None:
   proc.terminate()
   try:proc.wait(timeout=30)
   except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=10)
 def ready(port=19110):
  try:
   with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(f'http://127.0.0.1:{port}/api/public/readiness',timeout=2)as r:return r.status==200
  except (urllib.error.URLError,TimeoutError):return False
 def api(name,jar,port=19110,extra=None):
  proc=launch(name,jvm+['-jar',str(jar),'--spring.profiles.active=api','--server.port='+str(port)],extra);until(lambda:ready(port));return proc
 def browser(phase):
  e=env.copy();e['TICKET_MONITOR_OUTPUT']=str(scope/(phase+'.json'));run(['node','scripts/smoke_monitoring_browser.cjs',phase],'browser-'+phase,e);r=json.loads((scope/(phase+'.json')).read_text());require(r['passed']>0,'Browser proof required');results.append(r);print('PASS '+phase+': '+str(r['passed'])+' groups',flush=True)
 try:
  started=True;run(compose+['up','-d','--wait'],'start');require(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';")=='0','Empty PG required')
  e=env.copy();e['TICKET_MONITORING_ENABLED']='false';run(jvm+['-Dloader.path='+str(archive),'-Dloader.main=dev.elpsy.ticket.inventory.ReturnDiscoverySeedProbe','-cp',str(seed),'org.springframework.boot.loader.launch.PropertiesLauncher','seed',str(scope/'fixture.json')],'seed',e)
  old=api('api-v23',baseline,extra=e);require(sql('SELECT max(version::int) FROM flyway_schema_history;')=='23','Actual V23 upgrade boundary required')
  tables=sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename;").splitlines()
  def rows():return{t:hashlib.sha256(sql('SELECT row_to_json(x)::text FROM "'+t+'" x ORDER BY row_to_json(x)::text;').encode()).hexdigest()for t in tables}
  before=rows();stop(old);a1=api('api-a',final);a2=api('api-b',final,19111);require(rows()==before and sql('SELECT max(version::int) FROM flyway_schema_history;')=='24','V23 to V24 changed business data');extraChecks.append('actual V23 to V24 leaves all old public rows unchanged; only operational heartbeat rows added')
  launch('preview',['node',str(ROOT/'frontend/node_modules/vite/bin/vite.js'),'preview','--host','127.0.0.1','--port','15180','--strictPort',str(ROOT/'frontend')]);until(lambda:ready())
  # Preview health is an HTTP root, not the API readiness function.
  def preview_ready():
   try:
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:15180',timeout=2)as r:return r.status==200
   except (urllib.error.URLError,TimeoutError):return False
  until(preview_ready);browser('prepare')
  run(jvm+['-Dloader.path='+str(archive),'-Dloader.main=dev.elpsy.ticket.inventory.ReturnDiscoverySeedProbe','-cp',str(final),'org.springframework.boot.loader.launch.PropertiesLauncher','release',str(scope/'fixture.json')],'release',e);browser('advance')
  workerEnv=env.copy();workerEnv['TICKET_PUBLISHER_ENABLED']='false'
  w1=launch('worker-a',jvm+['-jar',str(final),'--spring.profiles.active=worker'],workerEnv);w2=launch('worker-b',jvm+['-jar',str(final),'--spring.profiles.active=worker'],workerEnv)
  until(lambda:sql("SELECT count(*) FROM runtime_heartbeat WHERE role='WORKER' AND last_seen>=clock_timestamp()-interval '15 seconds';")=='2'and sql("SELECT count(*) FROM stock_return_recovery_job WHERE state='REVIEW';")=='1');browser('healthy')
  w1.kill();w1.wait(timeout=10);require(w1.returncode==-9,'Actual SIGKILL required');until(lambda:sql("SELECT count(*) FROM runtime_heartbeat WHERE role='WORKER' AND last_seen>=clock_timestamp()-interval '15 seconds';")=='1',30);browser('worker-stale')
  w1=launch('worker-a-restart',jvm+['-jar',str(final),'--spring.profiles.active=worker'],workerEnv);until(lambda:sql("SELECT count(*) FROM runtime_heartbeat WHERE role='WORKER' AND last_seen>=clock_timestamp()-interval '15 seconds';")=='2');browser('worker-restored')
  fsync=json.loads(dock(['exec',cid('redis'),'redis-cli','--json','WAITAOF','1','0','5000']));require(fsync[0]==1,'Redis AOF fsync required');run(compose+['stop','-t','10','redis'],'redis-stop');run(compose+['stop','-t','15','rabbitmq'],'mq-stop')
  for port in [26379,25672]:
   with socket.socket()as s:require(s.connect_ex(('127.0.0.1',port))!=0,'Actual dependency TCP stop required')
  browser('dependencies-off');run(compose+['start','redis','rabbitmq'],'dependencies-start');until(lambda:all(dock(['inspect','--format','{{.State.Health.Status}}',cid(s)])=='healthy'for s in ['redis','rabbitmq']));browser('dependencies-restored')
  # This browser phase performs real PG stop/start while retaining its loaded UI/cookie.
  browser('pg-fault')
  stop(a1);off=env.copy();off['TICKET_MONITORING_ENABLED']='false';api('api-monitor-off',final,extra=off);browser('monitor-off')
  require(hashes()==frozen,'Verified runtime artifacts changed');require(all('Tomcat'not in(scope/(n+'.log')).read_text()for n in ['worker-a','worker-b','worker-a-restart']),'Worker HTTP forbidden')
  save('result.json',{'milestone':'M6A','browser':results,'passed':sum(r['passed']for r in results),'extraChecks':extraChecks,'oldPublicTablesUnchanged':len(tables),'actualApis':2,'actualWorkers':2,'workerContainers':False,'actualWorkerSigkill':True,'actualRedisMqTcpStop':True,'sameAofRestart':True,'actualPgStopRestart':True,'productionArtifactSha256':frozen,'publicTls':False,'loadBenchmark':False,'crossHostHa':False})
 finally:
  state=[]
  for n,proc,f in reversed(processes):stop(proc);state.append({'role':n,'exitCode':proc.returncode});f.close()
  save('process-disposal.json',state)
  if started:run(compose+['down','-v','--remove-orphans'],'dispose')
  remaining={k:len(owned(k))for k in queries};require(not any(remaining.values()),'Owned cleanup incomplete');save('cleanup.json',{'remainingOwnedResources':remaining,'allOwnedProcessesStopped':all(p.poll()is not None for _,p,_ in processes)})
if __name__=='__main__':main()
