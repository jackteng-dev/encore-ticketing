#!/usr/bin/env python3
"""Own empty V17 -> V18 runtime, final JAR/dist HTTP/browser and same-AOF outage.

Requires successful full package/build, historical V17 artifact and own empty
PG/Redis Compose scope. No downloads, schema repair/clean, or disposal. Private
credentials, exact UUIDs, screenshots and raw logs stay in ignored work/.
"""
import argparse,hashlib,json,os,re,socket,subprocess,time,urllib.request,zipfile
from pathlib import Path
from run_stock_commit_experiment import ROOT,private_path,require

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--env',required=True);p.add_argument('--baseline-jar',required=True);p.add_argument('--output',required=True);p.add_argument('--java',default='java');a=p.parse_args()
 config=dict(l.split('=',1)for l in private_path(a.env).read_text().splitlines()if l and not l.startswith('#'));project=config.get('COMPOSE_PROJECT_NAME','');require(re.fullmatch(r'ticket-return-runtime-[a-z0-9-]{1,24}',project),'Own runtime project required');require(re.fullmatch(r'encore-demo-increment-[a-z0-9-]{1,28}',config.get('TICKET_REDIS_NAMESPACE','')),'Own namespace required')
 require(config['TICKET_DB_URL']==f"jdbc:postgresql://127.0.0.1:{config['TICKET_PG_PORT']}/ticket_demo" and config['TICKET_REDIS_HOST']=='127.0.0.1','Loopback required');require(config['TICKET_PORT']=='19111','Dedicated API port required')
 scope=private_path(a.output);require(not scope.exists(),'Prior evidence preserved');scope.mkdir(parents=True,mode=0o700);env=os.environ.copy();env.update(config);denv=env.copy()
 for k in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'):denv.pop(k,None)
 docker=['docker','--host=unix:///var/run/docker.sock'];pg=project+'-postgres-1';redis=project+'-redis-1'
 def dock(cmd):return subprocess.run(docker+cmd,env=denv,check=True,text=True,capture_output=True,timeout=60).stdout.strip()
 for name,port,external in ((pg,'5432/tcp',config['TICKET_PG_PORT']),(redis,'6379/tcp',config['TICKET_REDIS_PORT'])):
  require(dock(['inspect','--format','{{ index .Config.Labels "com.docker.compose.project" }}',name])==project,'Ownership mismatch');require(json.loads(dock(['inspect','--format','{{json .NetworkSettings.Ports}}',name])).get(port)==[{'HostIp':'127.0.0.1','HostPort':external}],'Port binding mismatch')
 def sql(q):
  out=subprocess.run(docker+['exec','-i',pg,'psql','-U',config['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=denv,input='BEGIN READ ONLY;\n'+q+'\nCOMMIT;',text=True,capture_output=True,check=True,timeout=30).stdout
  return '\n'.join(l for l in out.splitlines()if l not in ('BEGIN','COMMIT')).strip()
 require(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';")=='0','NEW EMPTY PG required')
 final=ROOT/'backend/target/ticket-platform-0.1.0.jar';baseline=private_path(a.baseline_jar);tests=ROOT/'backend/target/test-classes';fixture=scope/'original.json'
 def extract(jar,name,version):
  target=scope/name
  with zipfile.ZipFile(jar)as z:
   require(sum(n.startswith('BOOT-INF/classes/db/migration/V')for n in z.namelist())==version,'Artifact migration mismatch');require(not any('RuntimeSeed' in n or 'RuntimeVerify' in n or '/experiments/'in n for n in z.namelist()),'Test-only production leakage')
   for n in z.namelist():
    if n.startswith(('BOOT-INF/classes/','BOOT-INF/lib/'))and not n.endswith('/'):
     out=target/n;require(out.resolve().is_relative_to(target.resolve()),'Unsafe JAR entry');out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(n))
  return os.pathsep.join(map(str,[target/'BOOT-INF/classes',target/'BOOT-INF/lib/*',tests]))
 oldcp=extract(baseline,'baseline',17);newcp=extract(final,'final',18)
 def run(name,args,extra=None,cwd=ROOT,timeout=120):
  log=scope/(name+'.log')
  with log.open('x')as f:
   log.chmod(0o600);e=env.copy();e.update(extra or {});subprocess.run(args,env=e,cwd=cwd,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=timeout)
 run('seed',[a.java,'-Xmx384m','-XX:+UseSerialGC','-cp',oldcp,'dev.jackteng.encore.StockReturnRecoveryRuntimeSeed',str(fixture)])
 require(json.loads(fixture.read_text())['baselineSchema']==17,'True historical V17 seed required');tables=sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename;").splitlines()
 def data():
  value={}
  for t in tables:
   require(re.fullmatch('[a-z_]+',t),'Invalid table name');value[t]=json.loads(sql("SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb) FROM public."+t+' x;'))
  return value
 old=data();private=scope/'old-public-rows.json';private.write_text(json.dumps(old));private.chmod(0o600)
 api=None;preview=None;handles=[];stopped=False;results=[]
 def start_api(enabled,name,initial=1000):
  nonlocal api
  if api is not None:api.terminate();api.wait(timeout=30)
  f=(scope/(name+'.log')).open('x');os.chmod(f.name,0o600);handles.append(f);e=env.copy();e.update(TICKET_STOCK_RETURN_RECOVERY_ENABLED='true'if enabled else'false',TICKET_DEMO_BOOTSTRAP='false');api=subprocess.Popen([a.java,'-Xmx384m','-XX:+UseSerialGC','-jar',str(final),'--ticket.stock-return-recovery.initial-delay-ms='+str(initial)],cwd=ROOT,env=e,stdout=f,stderr=subprocess.STDOUT)
  deadline=time.monotonic()+60
  while True:
   require(api.poll()is None,'Production API exited; inspect private log')
   try:
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:19111/api/auth/csrf',timeout=1)as r:
     if r.status==200:break
   except Exception:pass
   require(time.monotonic()<deadline,'Production API startup timeout');time.sleep(.2)
 def phase(name):
  out=scope/(name+'.json');run(name,['node','scripts/smoke_stock_return_worker_browser.cjs',name],{'TICKET_RUNTIME_ENV':str(private_path(a.env)),'TICKET_RETURN_FIXTURE':str(fixture),'TICKET_RETURN_OUTPUT':str(out),'TICKET_FRONTEND_URL':'http://127.0.0.1:15181'},timeout=180);r=json.loads(out.read_text());results.append(r);print(name+': '+str(r['passed'])+' production browser groups passed',flush=True)
 try:
  start_api(True,'api-prepare',3600000);require(data()==old,'V18 changed original business rows');require(all(sql('SELECT count(*) FROM '+t+';')=='0'for t in ('stock_return_recovery_job','stock_return_recovery_claim','stock_return_recovery_audit')),'No recovery backfill required')
  f=(scope/'preview.log').open('x');os.chmod(f.name,0o600);handles.append(f);e=env.copy();e['TICKET_API_TARGET']='http://127.0.0.1:19111';preview=subprocess.Popen(['npm','run','preview','--','--port','15181'],cwd=ROOT/'frontend',env=e,stdout=f,stderr=subprocess.STDOUT,start_new_session=True);deadline=time.monotonic()+30
  while True:
   try:
    with socket.create_connection(('127.0.0.1',15181),timeout=.5):break
   except OSError:pass
   require(time.monotonic()<deadline,'Preview startup timeout');time.sleep(.2)
  phase('prepare');api.terminate();api.wait(timeout=30);api=None;require(dock(['exec',redis,'redis-cli','--raw','WAITAOF','1','0','5000']).splitlines()==['1','0'],'AOF confirmation required');dock(['stop','--time','10',redis]);stopped=True
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',int(config['TICKET_REDIS_PORT'])))!=0,'Redis TCP remains open')
  start_api(True,'api-offline');phase('offline');start_api(False,'api-flag-off');phase('flag-off');dock(['start',redis]);stopped=False;deadline=time.monotonic()+30
  while dock(['inspect','--format','{{.State.Health.Status}}',redis])!='healthy':require(time.monotonic()<deadline,'Redis restart timeout');time.sleep(.2)
  start_api(True,'api-drain');phase('drain');start_api(True,'api-before-interruption',20000);phase('crash-enroll')
  original=json.loads(fixture.read_text())['fixtures']['restart']['requestId'];require(re.fullmatch('[0-9a-f-]{36}',original),'Original UUID required')
  # Hold only our original progress in a separate real PG connection. Scheduler
  # claims its durable job, then blocks BEFORE any domain effect.
  lock=subprocess.Popen(docker+['exec','-i',pg,'psql','-U',config['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=denv,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
  try:
   lock.stdin.write("BEGIN; SELECT request_id FROM stock_return_staff_progress WHERE request_id='"+original+"' FOR UPDATE; SELECT 'LOCKED';\n");lock.stdin.flush()
   import select
   deadline=time.monotonic()+10;received=b''
   while b'LOCKED' not in received:
    require(time.monotonic()<deadline,'PG lock startup deadline');ready,_,_=select.select([lock.stdout],[],[],.2)
    if ready:received+=os.read(lock.stdout.fileno(),4096)
   deadline=time.monotonic()+30
   while sql("SELECT state FROM stock_return_recovery_job WHERE request_id='"+original+"';")!='LEASED':require(time.monotonic()<deadline,'Actual scheduler claim deadline');time.sleep(.2)
   require(sql("SELECT attempts FROM stock_return_recovery_job WHERE request_id='"+original+"';")=='1','First actual claim required')
   time.sleep(5.2);api.terminate();api.wait(timeout=30);api=None
   require(sql("SELECT state||':'||attempts FROM stock_return_recovery_job WHERE request_id='"+original+"';")=='LEASED:1','Interrupted lease and budget must remain')
   require(sql("SELECT count(*) FROM stock_return_ack WHERE request_id='"+original+"';")=='0','No effect before blocked phase')
  finally:
   lock.stdin.write('ROLLBACK;\n');lock.stdin.close();lock.wait(timeout=10)
  start_api(True,'api-resume');phase('resume');api.terminate();api.wait(timeout=30);api=None
  core=json.loads((scope/'core.json').read_text())
  invariants=[];operational={}
  for file in ('verify_stock_origin_invariants.sql','verify_stock_return_invariants.sql','verify_stock_return_staff_invariants.sql','verify_stock_return_resolution_invariants.sql','verify_stock_return_resolution_recovery_invariants.sql','verify_stock_return_recovery_invariants.sql'):
   lines=sql((ROOT/'scripts'/file).read_text()).splitlines()
   for line in lines:
    name,count=line.split('|')
    if name=='unattributed_revisions':operational[name]=int(count);continue
    require(count=='0',file+': '+name+' violation');invariants.append(name)
  report={'actualV17JarToV18Upgrade':True,'oldPublicTablesUnchangedAtUpgrade':len(tables),'oldPublicRowsUnchangedAtUpgrade':sum(map(len,old.values())),'noRecoveryBackfill':True,'browserGroups':sum(r['passed']for r in results),'phases':results,'coreVerification':core,'zeroViolationChecks':invariants,'operationalSignals':operational,'actualApiStopDuringClaimedBlockedPhase':True,'expiredLeaseResumeKeptBudget':True,'actualRedisStopSameAofRestart':True,'localWaitaofConfirmed':True,'redisTcpClosedDuringOutage':True,'baselineJarSha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),'finalJarSha256':hashlib.sha256(final.read_bytes()).hexdigest(),'realPayments':False,'publicDeployment':False,'tcpAckDropOrKill9':False}
  result=scope/'result.json';result.write_text(json.dumps(report,indent=2)+'\n');result.chmod(0o600);print('Upgrade/browser/outage/core proof complete; only own resources remain for cleanup',flush=True)
 finally:
  if stopped:dock(['start',redis])
  if api is not None and api.poll()is None:api.terminate();api.wait(timeout=30)
  if preview is not None:
   import signal
   try:os.killpg(preview.pid,signal.SIGTERM)
   except ProcessLookupError:pass
   preview.wait(timeout=30)
  for f in handles:f.close()
if __name__=='__main__':main()
