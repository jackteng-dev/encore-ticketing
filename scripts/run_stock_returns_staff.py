#!/usr/bin/env python3
"""Own empty V14 -> V15 runtime, final JAR/dist HTTP/browser and same-AOF outage.

Requires successful full package/build, historical V14 artifact and own empty
PG/Redis Compose scope. No downloads, schema repair/clean, or disposal. Private
credentials, exact UUIDs, screenshots and raw logs stay in ignored work/.
"""
import argparse,hashlib,json,os,re,socket,subprocess,time,urllib.request,zipfile
from pathlib import Path
from run_stock_commit_experiment import ROOT,private_path,require

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--env',required=True);p.add_argument('--baseline-jar',required=True);p.add_argument('--output',required=True);p.add_argument('--java',default='java');a=p.parse_args()
 config=dict(l.split('=',1)for l in private_path(a.env).read_text().splitlines()if l and not l.startswith('#'));project=config.get('COMPOSE_PROJECT_NAME','');require(re.fullmatch(r'ticket-return-staff-runtime-[a-z0-9-]{1,24}',project),'Own runtime project required');require(re.fullmatch(r'encore-demo-increment-[a-z0-9-]{1,28}',config.get('TICKET_REDIS_NAMESPACE','')),'Own namespace required')
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
 oldcp=extract(baseline,'baseline',14);newcp=extract(final,'final',15)
 def run(name,args,extra=None,cwd=ROOT,timeout=120):
  log=scope/(name+'.log')
  with log.open('x')as f:
   log.chmod(0o600);e=env.copy();e.update(extra or {});subprocess.run(args,env=e,cwd=cwd,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=timeout)
 run('seed',[a.java,'-Xmx384m','-XX:+UseSerialGC','-cp',oldcp,'dev.elpsy.ticket.StockReturnStaffRuntimeSeed',str(fixture)])
 require(json.loads(fixture.read_text())['baselineSchema']==14,'True historical V14 seed required');tables=sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename;").splitlines()
 def data():
  value={}
  for t in tables:
   require(re.fullmatch('[a-z_]+',t),'Invalid table name');value[t]=json.loads(sql("SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb) FROM public."+t+' x;'))
  return value
 old=data();private=scope/'old-public-rows.json';private.write_text(json.dumps(old));private.chmod(0o600)
 api=None;preview=None;handles=[];stopped=False;results=[]
 def start_api(enabled,name):
  nonlocal api
  if api is not None:api.terminate();api.wait(timeout=30)
  f=(scope/(name+'.log')).open('x');os.chmod(f.name,0o600);handles.append(f);e=env.copy();e.update(TICKET_STOCK_RETURN_ENABLED='true'if enabled else'false',TICKET_DEMO_BOOTSTRAP='false');api=subprocess.Popen([a.java,'-Xmx384m','-XX:+UseSerialGC','-jar',str(final)],cwd=ROOT,env=e,stdout=f,stderr=subprocess.STDOUT)
  deadline=time.monotonic()+60
  while True:
   require(api.poll()is None,'Production API exited; inspect private log')
   try:
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:19111/api/auth/csrf',timeout=1)as r:
     if r.status==200:break
   except Exception:pass
   require(time.monotonic()<deadline,'Production API startup timeout');time.sleep(.2)
 def phase(name):
  out=scope/(name+'.json');run(name,['node','scripts/smoke_stock_returns_browser.cjs',name],{'TICKET_RUNTIME_ENV':str(private_path(a.env)),'TICKET_RETURN_FIXTURE':str(fixture),'TICKET_RETURN_OUTPUT':str(out),'TICKET_FRONTEND_URL':'http://127.0.0.1:15181'},timeout=180);r=json.loads(out.read_text());results.append(r);print(name+': '+str(r['passed'])+' production browser groups passed',flush=True)
 try:
  start_api(True,'api-prepare');require(data()==old,'V15 changed original business rows');require(all(sql('SELECT count(*) FROM '+t+';')=='0'for t in ('stock_return_staff_command','stock_return_staff_progress','stock_return_staff_audit')),'No backfill required')
  f=(scope/'preview.log').open('x');os.chmod(f.name,0o600);handles.append(f);e=env.copy();e['TICKET_API_TARGET']='http://127.0.0.1:19111';preview=subprocess.Popen(['npm','run','preview','--','--port','15181'],cwd=ROOT/'frontend',env=e,stdout=f,stderr=subprocess.STDOUT,start_new_session=True);deadline=time.monotonic()+30
  while True:
   try:
    with socket.create_connection(('127.0.0.1',15181),timeout=.5):break
   except OSError:pass
   require(time.monotonic()<deadline,'Preview startup timeout');time.sleep(.2)
  phase('prepare');require(dock(['exec',redis,'redis-cli','--raw','WAITAOF','1','0','5000']).splitlines()==['1','0'],'AOF confirmation required');dock(['stop','--time','10',redis]);stopped=True
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',int(config['TICKET_REDIS_PORT'])))!=0,'Redis TCP remains open')
  phase('offline');start_api(False,'api-flag-off');phase('flag-off');dock(['start',redis]);stopped=False;deadline=time.monotonic()+30
  while dock(['inspect','--format','{{.State.Health.Status}}',redis])!='healthy':require(time.monotonic()<deadline,'Redis restart timeout');time.sleep(.2)
  start_api(True,'api-resume');phase('resume');api.terminate();api.wait(timeout=30);api=None
  run('core-verify',[a.java,'-Xmx384m','-XX:+UseSerialGC','-cp',newcp,'dev.elpsy.ticket.StockReturnStaffRuntimeVerify',str(fixture),str(scope/'core.json')]);core=json.loads((scope/'core.json').read_text())
  invariants=[];operational={}
  for file in ('verify_stock_origin_invariants.sql','verify_stock_return_invariants.sql','verify_stock_return_staff_invariants.sql'):
   lines=sql((ROOT/'scripts'/file).read_text()).splitlines()
   for line in lines:
    name,count=line.split('|')
    if name=='unattributed_revisions':operational[name]=int(count);continue
    require(count=='0',file+': '+name+' violation');invariants.append(name)
  report={'actualV14JarToV15Upgrade':True,'oldPublicTablesUnchangedAtUpgrade':len(tables),'oldPublicRowsUnchangedAtUpgrade':sum(map(len,old.values())),'noStaffReturnBackfill':True,'browserGroups':sum(r['passed']for r in results),'phases':results,'coreVerification':core,'zeroViolationChecks':invariants,'operationalSignals':operational,'actualRedisStopSameAofRestart':True,'localWaitaofConfirmed':True,'redisTcpClosedDuringOutage':True,'baselineJarSha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),'finalJarSha256':hashlib.sha256(final.read_bytes()).hexdigest(),'realPayments':False,'publicDeployment':False,'tcpAckDropOrKill9':False}
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
