#!/usr/bin/env python3
"""Own V21 -> V22 upgrade, two final JARs and dist; no production proxy/HA claim."""
import argparse,hashlib,http.client,http.server,json,os,re,signal,socket,subprocess,threading,time,urllib.request,zipfile
from pathlib import Path
from run_stock_commit_experiment import ROOT,private_path,require

def main():
 p=argparse.ArgumentParser();p.add_argument('--env',required=True);p.add_argument('--baseline-jar',required=True);p.add_argument('--output',required=True);p.add_argument('--java',default='java');a=p.parse_args()
 cfg=dict(l.split('=',1)for l in private_path(a.env).read_text().splitlines()if l and not l.startswith('#'));project=cfg['COMPOSE_PROJECT_NAME'];require(re.fullmatch(r'ticket-staff-runtime-m5a[a-z]?',project),'Own scope required');require(cfg['TICKET_DB_URL']=='jdbc:postgresql://127.0.0.1:25434/ticket_demo' and cfg['TICKET_REDIS_HOST']=='127.0.0.1' and cfg['TICKET_REDIS_PORT']=='26381','Own loopback required')
 scope=private_path(a.output);require(not scope.exists(),'Preserve prior evidence');scope.mkdir(mode=0o700);env=os.environ.copy();env.update(cfg);env['JAVA_TOOL_OPTIONS']=(env.get('JAVA_TOOL_OPTIONS','')+' -XX:ActiveProcessorCount=2').strip();denv=env.copy()
 for k in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'):denv.pop(k,None)
 docker=['docker','--host=unix:///var/run/docker.sock'];pg=project+'-postgres-1';redis=project+'-redis-1'
 def dock(args):return subprocess.run(docker+args,env=denv,check=True,text=True,capture_output=True,timeout=60).stdout.strip()
 for name,internal,port in [(pg,'5432/tcp','25434'),(redis,'6379/tcp','26381')]:
  require(dock(['inspect','--format','{{ index .Config.Labels "com.docker.compose.project" }}',name])==project,'Ownership mismatch');require(json.loads(dock(['inspect','--format','{{json .NetworkSettings.Ports}}',name]))[internal]==[{'HostIp':'127.0.0.1','HostPort':port}],'Binding mismatch')
 def sql(q):
  out=subprocess.run(docker+['exec','-i',pg,'psql','-U',cfg['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=denv,input='BEGIN READ ONLY;\n'+q+'\nCOMMIT;',text=True,capture_output=True,check=True,timeout=30).stdout
  return '\n'.join(l for l in out.splitlines()if l not in ('BEGIN','COMMIT')).strip()
 require(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';")=='0','New empty owned PG required')
 baseline=private_path(a.baseline_jar);final=ROOT/'backend/target/ticket-platform-0.1.0.jar';dist=ROOT/'frontend/dist'
 for jar,version in [(baseline,21),(final,22)]:
  with zipfile.ZipFile(jar)as z:require(sum(n.startswith('BOOT-INF/classes/db/migration/V')for n in z.namelist())==version,'Artifact version mismatch');require(not any('RuntimeSeed'in n or'RuntimeVerify'in n or'/experiments/'in n for n in z.namelist()),'Test-only leakage')
 def artifacts():return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()for p in [baseline,final,ROOT/'scripts/smoke_staff_session_browser.cjs',*sorted(dist.rglob('*'))]if p.is_file()}
 frozen=artifacts();require((dist/'index.html').is_file(),'Production dist required')
 route=scope/'route.txt';route.write_text('a');route.chmod(0o600);fixture=scope/'original.json';apis={};logs=[];preview=None;results=[];redis_stopped=False;pg_stopped=False;counts={'a':0,'b':0};guard=threading.Lock();cursor=0
 class Proxy(http.server.BaseHTTPRequestHandler):
  def log_message(self,*args):pass
  def forward(self):
   nonlocal cursor
   if not self.path.startswith('/api/'):self.send_error(404);return
   with guard:
    mode=route.read_text().strip();target=mode if mode in ('a','b')else ('a'if cursor%2==0 else'b');cursor+=1;counts[target]+=1
   conn=http.client.HTTPConnection('127.0.0.1',19112 if target=='a'else 19113,timeout=20)
   try:
    body=self.rfile.read(int(self.headers.get('Content-Length',0)));headers={k:v for k,v in self.headers.items()if k.lower()not in ('host','connection','transfer-encoding')};headers['Connection']='close';conn.request(self.command,self.path,body,headers);r=conn.getresponse();data=r.read();self.send_response(r.status)
    for k,v in r.getheaders():
     if k.lower()not in ('connection','transfer-encoding','content-length','keep-alive'):self.send_header(k,v)
    self.send_header('Content-Length',str(len(data)));self.send_header('Connection','close');self.end_headers();self.wfile.write(data)
   except (OSError,http.client.HTTPException):self.send_response(502);self.send_header('Content-Length','0');self.end_headers()
   finally:conn.close()
  do_GET=forward;do_POST=forward;do_PUT=forward;do_DELETE=forward
 proxy=http.server.ThreadingHTTPServer(('127.0.0.1',19114),Proxy);thread=threading.Thread(target=proxy.serve_forever,daemon=True);thread.start()
 def stop(key):
  proc=apis.pop(key,None)
  if proc is not None and proc.poll()is None:proc.terminate();proc.wait(timeout=30)
 def start(key,jar,name,bootstrap=False):
  stop(key);port=19112 if key=='a'else 19113;log=(scope/(name+'.log')).open('x');Path(log.name).chmod(0o600);logs.append(log);e=env.copy();e.update(TICKET_PORT=str(port),TICKET_DEMO_BOOTSTRAP=str(bootstrap).lower(),TICKET_ORDERS_ENABLED='false',TICKET_EXPIRY_ENABLED='false');proc=subprocess.Popen([a.java,'-Xmx256m','-XX:+UseSerialGC','-jar',str(jar)],cwd=ROOT,env=e,stdout=log,stderr=subprocess.STDOUT);apis[key]=proc;deadline=time.monotonic()+60
  while True:
   require(proc.poll()is None,'API exited; inspect private log')
   try:
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:'+str(port)+'/api/auth/csrf',timeout=1)as r:
     if r.status==200:break
   except Exception:pass
   require(time.monotonic()<deadline,'API startup deadline');time.sleep(.2)
 def phase(name):
  require(artifacts()==frozen,'Artifacts changed during runtime');out=scope/(name+'.json');e=env.copy();e.update(TICKET_RUNTIME_ENV=str(private_path(a.env)),TICKET_SESSION_FIXTURE=str(fixture),TICKET_SESSION_OUTPUT=str(out),TICKET_SESSION_ROUTE=str(route),TICKET_FRONTEND_URL='http://127.0.0.1:15182')
  with (scope/(name+'.log')).open('x')as log:Path(log.name).chmod(0o600);subprocess.run(['node','scripts/smoke_staff_session_browser.cjs',name],cwd=ROOT,env=e,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
  require(artifacts()==frozen,'Artifacts changed during runtime');r=json.loads(out.read_text());results.append(r);print(name+': '+str(r['passed'])+' groups passed',flush=True)
 try:
  start('a',baseline,'api-v21',True);log=(scope/'preview.log').open('x');Path(log.name).chmod(0o600);logs.append(log);e=env.copy();e['TICKET_API_TARGET']='http://127.0.0.1:19114';preview=subprocess.Popen(['npm','run','preview','--','--port','15182'],cwd=ROOT/'frontend',env=e,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);deadline=time.monotonic()+30
  while True:
   try:
    with socket.create_connection(('127.0.0.1',15182),timeout=.5):break
   except OSError:pass
   require(time.monotonic()<deadline,'Preview startup deadline');time.sleep(.2)
  phase('seed');require(sql('SELECT count(*) FROM flyway_schema_history WHERE success;')=='21','True V21 schema required');stop('a')
  tables=sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename;").splitlines()
  def data(upgraded=False):return {t:json.loads(sql("SELECT coalesce(jsonb_agg("+("to_jsonb(x)-'session_version'"if upgraded and t=='staff_account'else'to_jsonb(x)')+" ORDER BY to_jsonb(x)::text),'[]'::jsonb) FROM "+t+' x;'))for t in tables}
  old=data();raw=scope/'old-public-rows.json';raw.write_text(json.dumps(old));raw.chmod(0o600);start('a',final,'api-a-prepare');start('b',final,'api-b-prepare');require(sql('SELECT count(*) FROM flyway_schema_history WHERE success;')=='22','Final schema required');require(data(True)==old,'Upgrade changed original columns');require(sql('SELECT count(*) FROM staff_session;')=='0','No memory session import');require(sql('SELECT count(*) FROM staff_account WHERE session_version<>1;')=='0','Initial version mismatch');phase('prepare');stop('a');stop('b');dock(['stop','--time','10',redis]);redis_stopped=True
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',26381))!=0,'Redis TCP not closed')
  start('a',final,'api-a-resume');start('b',final,'api-b-resume');phase('resume')
  dock(['stop','--time','10',pg]);pg_stopped=True
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',25434))!=0,'PG TCP not closed')
  phase('pg-off');dock(['start',pg]);pg_stopped=False;deadline=time.monotonic()+30
  while dock(['inspect','--format','{{.State.Health.Status}}',pg])!='healthy':require(time.monotonic()<deadline,'PG restart deadline');time.sleep(.2)
  phase('finish');stop('a');stop('b');checks=[]
  for name in ('verify_stock_origin_invariants.sql','verify_ticket_invariants.sql','verify_ticket_recovery_invariants.sql','verify_admission_window_invariants.sql'):
   for line in sql((ROOT/'scripts'/name).read_text()).splitlines():kind,count=line.split('|');require(count=='0','Invariant violation: '+kind);checks.append(kind)
  require(sql("SELECT count(*) FROM staff_session s JOIN staff_account a ON a.id=s.staff_id WHERE NOT a.enabled OR s.session_version<>a.session_version OR s.role_snapshot<>a.role OR s.expires_at<>s.created_at+interval '30 minutes';")=='0','Session identity invariant');checks.append('shared_staff_current_identity_and_fixed_deadline')
  result={'actualV21JarToV22Upgrade':True,'oldPublicTablesOriginalColumnsUnchanged':len(tables),'oldPublicRowsOriginalColumnsUnchanged':sum(map(len,old.values())),'noMemorySessionImport':True,'twoFinalJarApiProcesses':True,'actualBothApisStopRestart':True,'actualRedisStopAndTcpClosed':True,'actualPgStopAndTcpClosed':True,'actualPgRestartSameVolume':True,'artifactsUnchangedThroughoutRuntime':True,'requestsToApis':counts,'groups':sum(r['passed']for r in results),'phases':results,'zeroViolationChecks':checks,'baselineJarSha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),'finalJarSha256':hashlib.sha256(final.read_bytes()).hexdigest(),'frontendAssetsSha256':{k:v for k,v in frozen.items()if k.startswith('frontend/dist/')},'realPayments':False,'publicDeployment':False,'loadOrCrossHostHa':False,'tcpCommitAckDropOrKill9':False};out=scope/'result.json';out.write_text(json.dumps(result,indent=2)+'\n');out.chmod(0o600);print('Shared staff upgrade/two API/restart/PG outage proof complete',flush=True)
 finally:
  if pg_stopped:dock(['start',pg])
  if redis_stopped:dock(['start',redis])
  for key in list(apis):stop(key)
  if preview is not None:
   try:os.killpg(preview.pid,signal.SIGTERM)
   except ProcessLookupError:pass
   preview.wait(timeout=30)
  proxy.shutdown();proxy.server_close();thread.join(timeout=5)
  for log in logs:log.close()
if __name__=='__main__':main()
