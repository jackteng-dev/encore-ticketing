#!/usr/bin/env python3
"""Own empty PG/Redis: real V22 upgrade, API authorization, two nonweb JVMs, same-AOF recovery.
Uses the actual JAR core for synthetic formal cancellations; no production debug endpoints.
"""
import argparse,hashlib,http.cookiejar,json,os,re,socket,subprocess,time,zipfile
import urllib.request,urllib.error,urllib.parse
from pathlib import Path
from uuid import uuid4
from run_deployment_runtime import ROOT,private,require,migration_version

def main():
 p=argparse.ArgumentParser();p.add_argument('--env',required=True);p.add_argument('--baseline-jar',required=True);p.add_argument('--java-home',required=True);p.add_argument('--output',required=True);a=p.parse_args()
 envfile=private(a.env);baseline=private(a.baseline_jar);java=Path(a.java_home).resolve()/'bin/java';require(java.is_file(),'Installed complete JDK21 required')
 require(hashlib.sha256(baseline.read_bytes()).hexdigest()=='f5d9cc294c114ffd19c107c86985c64a061a66e6b227cd55d399aa146729e7a0','Verified original V22 artifact required')
 cfg=dict(l.split('=',1)for l in envfile.read_text().splitlines()if l and not l.startswith('#'));project=cfg['COMPOSE_PROJECT_NAME']
 require(re.fullmatch(r'ticket-discovery-runtime-[0-9]{14}',project),'Owned runtime scope required')
 require(cfg['TICKET_DB_URL']=='jdbc:postgresql://127.0.0.1:25432/ticket_demo'and cfg['TICKET_REDIS_HOST']=='127.0.0.1'and cfg['TICKET_REDIS_PORT']=='26379'and cfg['TICKET_COOKIE_SECURE']=='false','Explicit isolated loopback fixture required')
 scope=private(a.output);require(not scope.exists(),'Preserve evidence');scope.mkdir(mode=0o700)
 env=os.environ.copy();env.update(cfg)
 for key in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH']:env.pop(key,None)
 env.update(TICKET_DEMO_BOOTSTRAP='false',TICKET_ORDERS_ENABLED='true',TICKET_PURCHASES_ENABLED='true',TICKET_DELIVERY_ENABLED='false',TICKET_EXPIRY_ENABLED='false',TICKET_STOCK_RETURN_ENABLED='true',TICKET_STOCK_RETURN_RECOVERY_ENABLED='true',TICKET_STOCK_RETURN_DISCOVERY_ENABLED='true',TICKET_PREDEBIT_ENABLED='false')
 docker=['docker','--host=unix:///var/run/docker.sock'];compose=docker+['compose','--env-file',str(envfile),'-f',str(ROOT/'infra/compose.yml')]
 def dock(args):return subprocess.run(docker+args,env=env,text=True,capture_output=True,check=True,timeout=30).stdout.strip()
 queries={'containers':['ps','-aq'],'volumes':['volume','ls','-q'],'networks':['network','ls','-q']}
 def owned(kind):return dock(queries[kind]+['--filter','label=com.docker.compose.project='+project]).splitlines()
 require(not any(owned(k)for k in queries),'Existing project resources forbidden')
 for port in [25432,26379,19110]:
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',port))!=0,'Owned loopback port occupied')
 def service(name):
  ids=dock(['ps','-aq','--filter','label=com.docker.compose.project='+project,'--filter','label=com.docker.compose.service='+name]).splitlines();require(len(ids)==1,'Exactly one service required');return ids[0]
 def run(args,name,timeout=180):
  path=scope/(name+'.log');require(not path.exists(),'Preserve log')
  with path.open('x')as f:path.chmod(0o600);subprocess.run(args,cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=timeout)
 def sql(query):
  out=subprocess.run(docker+['exec','-i',service('postgres'),'psql','-U',cfg['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=env,input='BEGIN READ ONLY;\n'+query+'\nCOMMIT;',text=True,capture_output=True,check=True,timeout=20).stdout
  return '\n'.join(l for l in out.splitlines()if l not in ['BEGIN','COMMIT']).strip()
 def until(fn,seconds=60):
  end=time.monotonic()+seconds
  while not fn():require(time.monotonic()<end,'Runtime deadline exceeded');time.sleep(.25)
 def snapshots(tables):
  return{t:hashlib.sha256(sql('SELECT row_to_json(t)::text FROM "'+t+'" t ORDER BY row_to_json(t)::text;').encode()).hexdigest()for t in tables}
 def ledger(ticket,generation=1):
  raw=json.loads(dock(['exec',service('redis'),'redis-cli','--json','HGETALL',cfg['TICKET_REDIS_NAMESPACE']+':stock:{'+ticket+'}:ledger:g:'+str(generation)]))
  return dict(zip(raw[::2],raw[1::2]))if isinstance(raw,list)else raw
 def save(name,data):
  path=scope/name;require(not path.exists(),'Preserve metadata');path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');path.chmod(0o600)
 probe=ROOT/'backend/target/test-classes/dev/elpsy/ticket/inventory/ReturnDiscoverySeedProbe.class';require(probe.is_file(),'Full Maven package required')
 archive=scope/'seed-probe.jar'
 with zipfile.ZipFile(archive,'x')as z:z.writestr(zipfile.ZipInfo('dev/elpsy/ticket/inventory/ReturnDiscoverySeedProbe.class'),probe.read_bytes())
 archive.chmod(0o600);fixture=scope/'fixture.json';final=ROOT/'backend/target/ticket-platform-0.1.0.jar'
 require(migration_version(baseline)==22 and migration_version(final)==23,'Exact verified upgrade artifacts required')
 artifacts=[final,baseline,probe,Path(__file__).resolve(),ROOT/'backend/src/main/resources/db/migration/V23__stock_return_discovery.sql',*sorted((ROOT/'scripts').glob('verify_stock*invariants.sql')),*sorted((ROOT/'frontend/dist').rglob('*'))]
 def hashes():return{str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest()for x in artifacts if x.is_file()}
 frozen=hashes();jvm=[str(java),'-Xmx256m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=2','-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
 def helper(jar,phase):run(jvm+['-Dloader.path='+str(archive),'-Dloader.main=dev.elpsy.ticket.inventory.ReturnDiscoverySeedProbe','-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher',phase,str(fixture)],'helper-'+phase,180)
 processes=[];started=False;checks=[]
 def stop_process(proc):
  if proc.poll()is not None:return
  proc.terminate()
  try:proc.wait(timeout=30)
  except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=10)
 def check(name):checks.append(name);print('PASS '+name,flush=True)
 def launch(name,profile):
  log=(scope/(name+'.log')).open('x');Path(log.name).chmod(0o600)
  proc=subprocess.Popen(jvm+['-jar',str(final),'--spring.profiles.active='+profile],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT);processes.append((name,proc,log));return proc
 base='http://127.0.0.1:19110'
 def ready():
  try:
   with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(base+'/api/public/readiness',timeout=3)as r:return r.status==200
  except (urllib.error.URLError,TimeoutError):return False
 def client():return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def req(c,path,body=None,expected=200):
  headers={};data=None
  if body is not None:
   csrf=req(c,'/api/auth/csrf');headers[csrf['headerName']]=csrf['token'];form=path=='/api/auth/login';headers['Content-Type']='application/x-www-form-urlencoded'if form else'application/json';data=(urllib.parse.urlencode(body)if form else json.dumps(body)).encode()
  try:
   with c.open(urllib.request.Request(base+path,data=data,headers=headers),timeout=15)as r:status=r.status;raw=r.read()
  except urllib.error.HTTPError as e:status=e.code;raw=e.read()
  require(status==expected,path+': unexpected status '+str(status));return json.loads(raw)if raw else None
 try:
  started=True;run(compose+['up','-d','--wait'],'start')
  require(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';")=='0','Empty PG required')
  helper(baseline,'seed');data=json.loads(fixture.read_text());require(sql('SELECT max(version::int) FROM flyway_schema_history;')=='22','Actual V22 seed required')
  tables=sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename;").splitlines();before=snapshots(tables);old={name:ledger(row['ticket'])for name,row in data.items()};save('v22-snapshot.json',{'hashes':before,'ledger':old})
  check('verified V22 JAR creates historical release and two original committed pending orders in empty owned PG Redis')
  launch('api','api');until(ready)
  require(sql('SELECT max(version::int) FROM flyway_schema_history;')=='23'and snapshots(tables)==before,'Upgrade changed original public rows')
  require(all(ledger(row['ticket'])==old[name]for name,row in data.items()),'Upgrade changed old ledger')
  require(sql('SELECT (SELECT count(*) FROM stock_return_discovery_policy)+(SELECT count(*) FROM stock_return_discovery_source);')=='0','No backfill allowed');check('actual V22 to V23 upgrade leaves all old public rows and three raw epoch hashes unchanged')
  staff=client();req(staff,'/api/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']))
  policies={}
  for name,row in data.items():policies[name]=req(staff,'/api/admin/stock/return-discovery/tickets/'+row['ticket']+'/policies',dict(requestId=str(uuid4()),maxSources=1))
  require(policies['history']['startRevision']==3 and policies['future']['startRevision']==2,'Correct authorization boundary')
  stop=dict(requestId=str(uuid4()),expectedVersion=1);path='/api/admin/stock/return-discovery/policies/'+policies['stopped']['requestId']+'/stop'
  require(req(staff,path,stop)['state']=='STOPPED'and req(staff,path,stop)['version']==2,'Exact stop replay');check('real shared-session CSRF API enrolls future-only finite policies and irreversibly stops original authorization')
  fsync=json.loads(dock(['exec',service('redis'),'redis-cli','--json','WAITAOF','1','0','5000']));require(fsync[0]==1,'Actual local AOF fsync required')
  run(compose+['stop','-t','10','redis'],'stop-redis')
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',26379))!=0,'Redis TCP must close')
  helper(final,'release');launch('worker-a','worker');launch('worker-b','worker')
  until(lambda:sql("SELECT count(*) FROM stock_return_recovery_job WHERE state='WAITING' AND last_code='REDIS_UNKNOWN' AND attempts BETWEEN 1 AND 5;")=='1')
  source=req(staff,'/api/admin/stock/return-discovery/policies/'+policies['future']['requestId']+'/sources')['items'];require(len(source)==1 and source[0]['disposition']=='ENROLLED','One future source')
  request=source[0]['returnRequestId'];require(sql("SELECT generation||'|'||state FROM stock_projection WHERE ticket_id='"+data['future']['ticket']+"';")=='2|CLOSED','Unknown Redis must retain closed PG gate')
  require(req(staff,'/api/admin/stock/returns/'+request)['state']=='CLOSED','Original staff progress retained');check('two nonweb workers discover one future source atomically; actual Redis outage retains original UUID budget and CLOSED gate')
  run(compose+['start','redis'],'start-redis');until(lambda:sql("SELECT count(*) FROM stock_return_recovery_job WHERE request_id='"+request+"' AND state='DONE';")=='1')
  require(sql("SELECT generation||'|'||state FROM stock_projection WHERE ticket_id='"+data['future']['ticket']+"';")=='2|READY','Recovered PG ready required')
  require(all(ledger(row['ticket'])==old[name]for name,row in data.items()),'Old committed epoch was changed')
  current=ledger(data['future']['ticket'],2);require(current['_baseline']=='4'and current['_available']=='4'and current['_held']=='0'and current['_committed']=='0','Fresh whole PG snapshot required')
  require(sql('SELECT count(*) FROM stock_return_discovery_source;')=='1','History or stopped scope discovered')
  require(req(staff,'/api/admin/stock/return-discovery/policies/'+policies['future']['requestId'])['state']=='EXHAUSTED','Finite source budget exhausted')
  require(req(staff,'/api/admin/stock/returns/'+request)['state']=='APPLIED','Original return applied')
  check('same-AOF recovery resumes the same job to APPLIED READY; old COMMITTED hashes stay unchanged and historical/stopped policies produce no jobs')
  # True worker process restart does not allocate new original identities or attempts for DONE.
  until(lambda:all('Started TicketApplication'in(scope/(name+'.log')).read_text()for name in ['worker-a','worker-b']))
  require(all(proc.poll()is None for name,proc,log in processes),'All original roles must be alive before restart')
  counts=sql('SELECT request_id||\'|\'||attempts FROM stock_return_recovery_job;')
  for name,proc,log in processes[1:]:stop_process(proc);require(proc.returncode in(0,143),'Worker clean stop')
  launch('worker-a-restart','worker');launch('worker-b-restart','worker')
  until(lambda:all('Started TicketApplication'in(scope/(name+'.log')).read_text()for name in ['worker-a-restart','worker-b-restart']));time.sleep(6)
  require(sql('SELECT request_id||\'|\'||attempts FROM stock_return_recovery_job;')==counts,'Restart reset identity or budget')
  require(all(proc.poll()is None for name,proc,log in processes if name in ['api','worker-a-restart','worker-b-restart']),'Live final roles required')
  require(all('Tomcat'not in(scope/(name+'.log')).read_text()for name in ['worker-a','worker-b','worker-a-restart','worker-b-restart']),'Worker HTTP forbidden')
  invariants=[]
  for name in ['verify_stock_return_invariants.sql','verify_stock_return_recovery_invariants.sql','verify_stock_origin_invariants.sql','verify_stock_return_discovery_invariants.sql']:
   for line in sql((ROOT/'scripts'/name).read_text()).splitlines():
    kind,count=line.split('|')
    if kind=='unattributed_revisions':continue
    require(count=='0','Invariant '+kind);invariants.append(kind)
  require(hashes()==frozen,'Artifacts changed');check('actual two-worker restart preserves completed source UUID and attempts; immutable original return audits and stock evidence reconcile')
  save('result.json',{'milestone':'M5D','checks':checks,'passed':len(checks),'oldPublicTablesUnchanged':len(tables),'originalLedgerHashesUnchanged':3,'actualRedisTcpStop':True,'sameAofRestart':True,'actualTwoWorkerProcessRestart':True,'sources':1,'originalRecoveryIdentityAndAttempts':counts,'freshEpochBaseline':4,'zeroViolationChecks':invariants,'artifactSha256':frozen,'transport':'actual verified JAR API and two nonweb JVM processes, real owned PG Redis; core seed/release helper test classpath only','workerContainers':False,'publicTls':False,'loadBenchmark':False,'crossHostHa':False,'publicPredebitCheckout':False})
 finally:
  state=[]
  for name,proc,log in reversed(processes):
   stop_process(proc)
   state.append({'role':name,'exitCode':proc.returncode});log.close()
  save('dispose-processes.json',state)
  if started:run(compose+['down','-v','--remove-orphans'],'dispose')
  remaining={k:len(owned(k))for k in queries};require(not any(remaining.values()),'Owned cleanup incomplete')
  save('cleanup.json',{'remainingOwnedResources':remaining,'allOwnedJavaProcessesStopped':all(proc.poll()is not None for name,proc,log in processes)})

if __name__=='__main__':main()
