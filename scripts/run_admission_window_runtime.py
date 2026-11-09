#!/usr/bin/env python3
"""Own empty V20 -> V21 final JAR/dist ticket HTTP, real restart, Redis-down proof.

Credentials/codes/cookies/screenshots remain in private ignored work. No downloads,
schema clean/repair/baseline or cleanup of other scopes. Full package/build required.
"""
import argparse,hashlib,json,os,re,socket,subprocess,time,urllib.request,zipfile
from pathlib import Path
from run_stock_commit_experiment import ROOT,private_path,require

def main():
 p=argparse.ArgumentParser();p.add_argument('--env',required=True);p.add_argument('--baseline-jar',required=True);p.add_argument('--output',required=True);p.add_argument('--java',default='java');a=p.parse_args()
 config=dict(l.split('=',1)for l in private_path(a.env).read_text().splitlines()if l and not l.startswith('#'));project=config['COMPOSE_PROJECT_NAME'];require(re.fullmatch(r'ticket-window-runtime-e4[a-z]?',project),'Own scope required');require(config['TICKET_DB_URL']=='jdbc:postgresql://127.0.0.1:25433/ticket_demo' and config['TICKET_REDIS_HOST']=='127.0.0.1' and config['TICKET_PORT']=='19111','Own loopback required')
 scope=private_path(a.output);require(not scope.exists(),'Preserve prior evidence');scope.mkdir(mode=0o700);env=os.environ.copy();env.update(config);env['JAVA_TOOL_OPTIONS']=(env.get('JAVA_TOOL_OPTIONS','')+' -XX:ActiveProcessorCount=2').strip();denv=env.copy()
 for k in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'):denv.pop(k,None)
 docker=['docker','--host=unix:///var/run/docker.sock'];pg=project+'-postgres-1';redis=project+'-redis-1'
 def dock(args):return subprocess.run(docker+args,env=denv,check=True,text=True,capture_output=True,timeout=60).stdout.strip()
 for name,port,external in [(pg,'5432/tcp','25433'),(redis,'6379/tcp','26380')]:
  require(dock(['inspect','--format','{{ index .Config.Labels "com.docker.compose.project" }}',name])==project,'Ownership mismatch');require(json.loads(dock(['inspect','--format','{{json .NetworkSettings.Ports}}',name]))[port]==[{'HostIp':'127.0.0.1','HostPort':external}],'Loopback binding mismatch')
 def sql(q):
  out=subprocess.run(docker+['exec','-i',pg,'psql','-U',config['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=denv,input='BEGIN READ ONLY;\n'+q+'\nCOMMIT;',text=True,capture_output=True,check=True,timeout=30).stdout
  return '\n'.join(l for l in out.splitlines()if l not in ('BEGIN','COMMIT')).strip()
 require(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';")=='0','NEW EMPTY PG required')
 baseline=private_path(a.baseline_jar);final=ROOT/'backend/target/ticket-platform-0.1.0.jar'
 for jar,version in [(baseline,20),(final,21)]:
  with zipfile.ZipFile(jar)as z:require(sum(n.startswith('BOOT-INF/classes/db/migration/V')for n in z.namelist())==version,'Artifact version mismatch');require(not any('RuntimeSeed'in n or'RuntimeVerify'in n or'/experiments/'in n for n in z.namelist()),'Test-only production leakage')
 fixture=scope/'original.json';api=None;preview=None;handles=[];stopped=False;results=[]
 dist=ROOT/'frontend/dist'
 def artifacts():return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()for p in [baseline,final,ROOT/'scripts/smoke_admission_window_browser.cjs',*sorted(dist.rglob('*'))]if p.is_file()}
 frozen=artifacts()
 require((dist/'index.html').is_file(),'Final production dist required')
 def start(jar,name,enabled,bootstrap=False):
  nonlocal api
  if api is not None:api.terminate();api.wait(timeout=30)
  f=(scope/(name+'.log')).open('x');Path(f.name).chmod(0o600);handles.append(f);e=env.copy();e.update(TICKET_ORDERS_ENABLED='true',TICKET_MOCK_PAYMENTS_ENABLED='true',TICKET_EXPIRY_ENABLED='false',TICKET_ISSUANCE_ENABLED=str(enabled).lower(),TICKET_CHECK_IN_ENABLED=str(enabled).lower(),TICKET_ADMISSION_WINDOW_ENABLED=str(enabled).lower(),TICKET_DEMO_BOOTSTRAP=str(bootstrap).lower());api=subprocess.Popen([a.java,'-Xmx384m','-XX:+UseSerialGC','-jar',str(jar)],cwd=ROOT,env=e,stdout=f,stderr=subprocess.STDOUT);limit=time.monotonic()+60
  while True:
   require(api.poll()is None,'API exited; inspect private log')
   try:
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:19111/api/auth/csrf',timeout=1)as r:
     if r.status==200:break
   except Exception:pass
   require(time.monotonic()<limit,'API startup deadline');time.sleep(.2)
 def phase(name,base='http://127.0.0.1:15181'):
  require(artifacts()==frozen,'Artifacts changed during runtime; preserve evidence and rerun in a new empty scope')
  out=scope/(name+'.json');e=env.copy();e.update(TICKET_RUNTIME_ENV=str(private_path(a.env)),TICKET_TICKET_FIXTURE=str(fixture),TICKET_TICKET_OUTPUT=str(out),TICKET_FRONTEND_URL=base)
  with (scope/(name+'.log')).open('x')as log:Path(log.name).chmod(0o600);subprocess.run(['node','scripts/smoke_admission_window_browser.cjs',name],cwd=ROOT,env=e,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
  require(artifacts()==frozen,'Artifacts changed during runtime; preserve evidence and rerun in a new empty scope')
  result=json.loads(out.read_text());results.append(result);print(name+': '+str(result['passed'])+' groups passed',flush=True)
 try:
  start(baseline,'api-v20',True,True);phase('seed','http://127.0.0.1:19111');require(sql('SELECT count(*) FROM flyway_schema_history WHERE success;')=='20','True old schema required');api.terminate();api.wait(timeout=30);api=None
  tables=sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename;").splitlines()
  def data():return {t:json.loads(sql("SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb) FROM "+t+' x;'))for t in tables}
  old=data();raw=scope/'old-public-rows.json';raw.write_text(json.dumps(old));raw.chmod(0o600);start(final,'api-prepare',True);require(sql('SELECT count(*) FROM flyway_schema_history WHERE success;')=='21','Final schema required');require(data()==old,'Upgrade changed original rows');require(all(sql('SELECT count(*) FROM '+t+';')=='0'for t in ('ticket_window_command','ticket_window_policy','ticket_window_audit','ticket_window_proof')),'Window proof or settings backfill forbidden')
  require(sql("SELECT count(*) FROM ticket_window_legacy;")=='1','Exact historical classification required');require(sql("SELECT count(*) FROM ticket_window_legacy l JOIN ticket_admission_command c USING(request_id) WHERE c.action='CHECK_IN';")=='1','Registry mismatch')
  f=(scope/'preview.log').open('x');Path(f.name).chmod(0o600);handles.append(f);e=env.copy();e['TICKET_API_TARGET']='http://127.0.0.1:19111';preview=subprocess.Popen(['npm','run','preview','--','--port','15181'],cwd=ROOT/'frontend',env=e,stdout=f,stderr=subprocess.STDOUT,start_new_session=True);limit=time.monotonic()+30
  while True:
   try:
    with socket.create_connection(('127.0.0.1',15181),timeout=.5):break
   except OSError:pass
   require(time.monotonic()<limit,'Preview startup deadline');time.sleep(.2)
  phase('prepare');api.terminate();api.wait(timeout=30);api=None;dock(['stop','--time','10',redis]);stopped=True
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',26380))!=0,'Redis TCP not closed')
  start(final,'api-off',False);phase('resume');start(final,'api-final',True);phase('finish');api.terminate();api.wait(timeout=30);api=None
  checks=[]
  for file in ('verify_stock_origin_invariants.sql','verify_ticket_invariants.sql','verify_ticket_recovery_invariants.sql','verify_admission_window_invariants.sql'):
   for l in sql((ROOT/'scripts'/file).read_text()).splitlines():
    name,count=l.split('|');require(count=='0','Readonly invariant violation: '+name);checks.append(name)
  result={'artifactsUnchangedThroughoutRuntime':True,'frontendAssetsSha256':{k:v for k,v in frozen.items()if k.startswith('frontend/dist/')},'actualV20JarToV21Upgrade':True,'oldPublicTablesUnchangedAtUpgrade':len(tables),'oldPublicRowsUnchangedAtUpgrade':sum(map(len,old.values())),'noWindowPolicyOrAdmissionProofBackfill':True,'groups':sum(r['passed']for r in results),'phases':results,'actualApiStopRestart':True,'flagsOffReplayVerified':True,'actualRedisStopAndTcpClosed':True,'redisDownForResumeAndFinish':True,'zeroViolationChecks':checks,'legacyCheckinsClassified':int(sql('SELECT count(*) FROM ticket_window_legacy;')),'windowPolicies':int(sql('SELECT count(*) FROM ticket_window_policy;')),'timeProofs':int(sql('SELECT count(*) FROM ticket_window_proof;')),'windowCommands':int(sql('SELECT count(*) FROM ticket_window_command;')),'issuanceBatches':int(sql('SELECT count(*) FROM ticket_issuance;')),'issuedUnits':int(sql('SELECT count(*) FROM issued_ticket;')),'checkedIn':int(sql("SELECT count(*) FROM issued_ticket WHERE state='CHECKED_IN';")),'revoked':int(sql("SELECT count(*) FROM issued_ticket WHERE state='REVOKED';")),'baselineJarSha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),'finalJarSha256':hashlib.sha256(final.read_bytes()).hexdigest(),'realPayments':False,'publicDeployment':False,'kill9OrTcpCommitAckDrop':False};out=scope/'result.json';out.write_text(json.dumps(result,indent=2)+'\n');out.chmod(0o600);print('Admission window upgrade/browser/restart/Redis-down proof complete',flush=True)
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
