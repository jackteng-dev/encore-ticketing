#!/usr/bin/env python3
"""Owned empty PG/Redis and verified JAR/dist: real API, Chromium, automatic recovery. No public service."""
import argparse,hashlib,json,os,socket,subprocess,time,zipfile
from pathlib import Path
import urllib.request,urllib.error
from run_deployment_runtime import ROOT,private,require,migration_version

def main():
 p=argparse.ArgumentParser();p.add_argument('--env',required=True);p.add_argument('--baseline-jar',required=True);p.add_argument('--java-home',required=True);p.add_argument('--output',required=True);a=p.parse_args()
 envfile=private(a.env);baseline=private(a.baseline_jar);scope=private(a.output);require(not scope.exists(),'Preserve earlier evidence');scope.mkdir(mode=0o700)
 require(hashlib.sha256(baseline.read_bytes()).hexdigest()=='f5d9cc294c114ffd19c107c86985c64a061a66e6b227cd55d399aa146729e7a0','Verified V22 seed artifact required')
 cfg=dict(l.split('=',1)for l in envfile.read_text().splitlines()if l and not l.startswith('#'));project=cfg['COMPOSE_PROJECT_NAME']
 require(project.startswith('ticket-discovery-ui-runtime-')and cfg['TICKET_DB_URL']=='jdbc:postgresql://127.0.0.1:25432/ticket_demo'and cfg['TICKET_REDIS_HOST']=='127.0.0.1'and cfg['TICKET_REDIS_PORT']=='26379'and cfg['TICKET_COOKIE_SECURE']=='false','Owned explicit loopback fixture required')
 env=os.environ.copy();env.update(cfg)
 for key in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH']:env.pop(key,None)
 env.update(TICKET_DEMO_BOOTSTRAP='false',TICKET_ORDERS_ENABLED='true',TICKET_PURCHASES_ENABLED='true',TICKET_DELIVERY_ENABLED='false',TICKET_EXPIRY_ENABLED='false',TICKET_STOCK_RETURN_ENABLED='true',TICKET_STOCK_RETURN_RECOVERY_ENABLED='true',TICKET_STOCK_RETURN_DISCOVERY_ENABLED='true',TICKET_STOCK_MAINTENANCE_ENABLED='true',TICKET_PREDEBIT_ENABLED='false',TICKET_RUNTIME_ENV=str(envfile),TICKET_DISCOVERY_UI_FIXTURE=str(scope/'fixture.json'),TICKET_FRONTEND_URL='http://127.0.0.1:15180')
 docker=['docker','--host=unix:///var/run/docker.sock'];compose=docker+['compose','--env-file',str(envfile),'-f',str(ROOT/'infra/compose.yml')]
 def dock(args):return subprocess.run(docker+args,env=env,text=True,capture_output=True,check=True,timeout=30).stdout.strip()
 queries={'containers':['ps','-aq'],'volumes':['volume','ls','-q'],'networks':['network','ls','-q']}
 def owned(kind):return dock(queries[kind]+['--filter','label=com.docker.compose.project='+project]).splitlines()
 require(not any(owned(k)for k in queries),'Existing scope forbidden')
 for port in [25432,26379,19110,15180]:
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',port))!=0,'Own port occupied')
 def cid(name):
  ids=dock(['ps','-aq','--filter','label=com.docker.compose.project='+project,'--filter','label=com.docker.compose.service='+name]).splitlines();require(len(ids)==1,'Exactly one owned service required');return ids[0]
 def sql(query):
  out=subprocess.run(docker+['exec','-i',cid('postgres'),'psql','-U',cfg['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=env,input='BEGIN READ ONLY;\n'+query+'\nCOMMIT;',text=True,capture_output=True,check=True,timeout=20).stdout
  return '\n'.join(l for l in out.splitlines()if l not in ['BEGIN','COMMIT']).strip()
 def run(args,name,timeout=180,extra=None):
  with(scope/(name+'.log')).open('x')as f:Path(f.name).chmod(0o600);subprocess.run(args,cwd=ROOT,env=extra or env,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=timeout)
 def until(fn,seconds=60):
  end=time.monotonic()+seconds
  while not fn():require(time.monotonic()<end,'Runtime deadline exceeded');time.sleep(.25)
 def save(name,data):
  path=scope/name;require(not path.exists(),'Preserve metadata');path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');path.chmod(0o600)
 final=ROOT/'backend/target/ticket-platform-0.1.0.jar';require(migration_version(final)==23,'Full current package required')
 probe=ROOT/'backend/target/test-classes/dev/elpsy/ticket/inventory/ReturnDiscoverySeedProbe.class';require(probe.is_file(),'Full test compilation required');archive=scope/'seed-probe.jar'
 with zipfile.ZipFile(archive,'x')as z:z.writestr('dev/elpsy/ticket/inventory/ReturnDiscoverySeedProbe.class',probe.read_bytes())
 archive.chmod(0o600)
 java=Path(a.java_home).resolve()/'bin/java';require(java.is_file(),'Installed complete JDK21 required');jvm=[str(java),'-Xmx256m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=2','-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
 artifacts=[final,probe,Path(__file__).resolve(),ROOT/'scripts/smoke_return_discovery_ui_browser.cjs',*sorted((ROOT/'frontend/dist').rglob('*')),*sorted((ROOT/'backend/src/main/resources/db/migration').glob('V*.sql'))]
 def hashes():return{str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest()for x in artifacts if x.is_file()}
 frozen=hashes();processes=[];started=False;results=[]
 def launch(name,args,extra=None):
  f=(scope/(name+'.log')).open('x');Path(f.name).chmod(0o600);proc=subprocess.Popen(args,cwd=ROOT,env=extra or env,stdout=f,stderr=subprocess.STDOUT);processes.append((name,proc,f));return proc
 def stop(proc):
  if proc.poll()is None:
   proc.terminate()
   try:proc.wait(timeout=30)
   except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=10)
 def ready(url):
  try:
   with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url,timeout=2)as r:return r.status==200
  except (urllib.error.URLError,TimeoutError):return False
 def browser(phase):
  output=scope/(phase+'.json');e=env.copy();e['TICKET_DISCOVERY_UI_OUTPUT']=str(output);run(['node','scripts/smoke_return_discovery_ui_browser.cjs',phase],'browser-'+phase,180,e);result=json.loads(output.read_text());require(result['passed']>0,'Actual browser checks required');results.append(result);print('PASS browser '+phase+': '+str(result['passed'])+' groups',flush=True)
 try:
  started=True;run(compose+['up','-d','--wait'],'start');require(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';")=='0','Empty owned PG required')
  run(jvm+['-Dloader.path='+str(archive),'-Dloader.main=dev.elpsy.ticket.inventory.ReturnDiscoverySeedProbe','-cp',str(baseline),'org.springframework.boot.loader.launch.PropertiesLauncher','seed',str(scope/'fixture.json')],'seed')
  api=launch('api',jvm+['-jar',str(final),'--spring.profiles.active=api']);until(lambda:ready('http://127.0.0.1:19110/api/public/readiness'))
  launch('preview',['node',str(ROOT/'frontend/node_modules/vite/bin/vite.js'),'preview','--host','127.0.0.1','--port','15180','--strictPort',str(ROOT/'frontend')]);until(lambda:ready('http://127.0.0.1:15180'))
  browser('prepare')
  fsync=json.loads(dock(['exec',cid('redis'),'redis-cli','--json','WAITAOF','1','0','5000']));require(fsync[0]==1,'Local AOF fsync required');run(compose+['stop','-t','10','redis'],'redis-stop')
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',26379))!=0,'Redis TCP must close')
  run(jvm+['-Dloader.path='+str(archive),'-Dloader.main=dev.elpsy.ticket.inventory.ReturnDiscoverySeedProbe','-cp',str(final),'org.springframework.boot.loader.launch.PropertiesLauncher','release',str(scope/'fixture.json')],'release')
  for name in ['worker-a','worker-b']:launch(name,jvm+['-jar',str(final),'--spring.profiles.active=worker'])
  until(lambda:sql("SELECT count(*) FROM stock_return_recovery_job WHERE state='WAITING' AND last_code='REDIS_UNKNOWN' AND attempts BETWEEN 1 AND 5;")=='1');browser('offline')
  run(compose+['start','redis'],'redis-start');until(lambda:sql("SELECT count(*) FROM stock_return_recovery_job WHERE state='DONE';")=='1');browser('drain')
  stop(api);off=env.copy();off['TICKET_STOCK_RETURN_DISCOVERY_ENABLED']='false';launch('api-flag-off',jvm+['-jar',str(final),'--spring.profiles.active=api'],off);until(lambda:ready('http://127.0.0.1:19110/api/public/readiness'));browser('flag-off')
  invariants={}
  for name in ['verify_stock_return_invariants.sql','verify_stock_return_recovery_invariants.sql','verify_stock_origin_invariants.sql','verify_stock_return_discovery_invariants.sql']:
   for line in sql((ROOT/'scripts'/name).read_text()).splitlines():
    kind,count=line.split('|');invariants[kind]=int(count)
    if kind!='unattributed_revisions':require(count=='0','Invariant '+kind)
  require(hashes()==frozen,'Verified artifacts changed');save('result.json',{'milestone':'M5E','browser':results,'passed':sum(r['passed']for r in results),'readonlyInvariants':invariants,'productionArtifactSha256':frozen,'actualRedisTcpStop':True,'sameAofRestart':True,'actualWorkers':2,'workerContainers':False,'apiFlagOffRestart':True,'publicTls':False,'loadBenchmark':False,'crossHostHa':False,'publicPredebitCheckout':False})
 finally:
  state=[]
  for name,proc,f in reversed(processes):stop(proc);state.append({'role':name,'exitCode':proc.returncode});f.close()
  save('process-disposal.json',state)
  if started:run(compose+['down','-v','--remove-orphans'],'dispose')
  remaining={k:len(owned(k))for k in queries};require(not any(remaining.values()),'Own cleanup incomplete');save('cleanup.json',{'remainingOwnedResources':remaining,'allOwnedProcessesStopped':all(p.poll()is not None for _,p,_ in processes)})
if __name__=='__main__':main()
