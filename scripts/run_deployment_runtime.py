#!/usr/bin/env python3
"""Disposable own-scope M5B containers; actual Nginx, two APIs, one worker, PG/MQ.
No public TLS, cross-host HA or throughput claim. Never delete a pre-existing scope.
"""
import argparse, hashlib, http.cookiejar, json, os, re, socket, subprocess, time, zipfile
import urllib.request, urllib.error, urllib.parse
from pathlib import Path
from datetime import datetime, timedelta, timezone
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from uuid import uuid4

ROOT=Path(__file__).resolve().parents[1]
def require(ok,message):
 if not ok:raise AssertionError(message)
def private(value):
 p=(ROOT/value).resolve();require(p.is_relative_to(ROOT/'work'),'Private files belong in repository work/');return p

def migration_version(jar):
 with zipfile.ZipFile(jar)as archive:
  versions=sorted(int(m.group(1))for name in archive.namelist()if(m:=re.fullmatch(r'BOOT-INF/classes/db/migration/V([0-9]+)__.+\.sql',name)))
 require(len(versions)>=22 and versions==list(range(1,len(versions)+1)),'Verified artifact must contain contiguous published migrations')
 return len(versions)

def main():
 p=argparse.ArgumentParser();p.add_argument('--env',required=True);p.add_argument('--output',required=True);a=p.parse_args()
 envfile=private(a.env);cfg=dict(l.split('=',1)for l in envfile.read_text().splitlines()if l and not l.startswith('#'))
 project=cfg['COMPOSE_PROJECT_NAME'];require(re.fullmatch(r'ticket-deploy-[0-9]{14}(?:-[a-f0-9]{8})?',project),'Generated isolated scope required')
 require(cfg['TICKET_GATEWAY_PORT']=='15184'and cfg['TICKET_COOKIE_SECURE']=='false','Explicit loopback HTTP fixture required')
 scope=private(a.output);require(not scope.exists(),'Preserve prior evidence');scope.mkdir(mode=0o700)
 env=os.environ.copy();env.update(cfg)
 for k in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'):env.pop(k,None)
 docker=['docker','--host=unix:///var/run/docker.sock'];compose=docker+['compose','--env-file',str(envfile),'-f',str(ROOT/'infra/compose.deploy.yml')]
 def dock(args,timeout=60):return subprocess.run(docker+args,env=env,text=True,capture_output=True,check=True,timeout=timeout).stdout.strip()
 require(not dock(['ps','-aq','--filter','label=com.docker.compose.project='+project]),'Existing containers forbidden')
 require(not dock(['volume','ls','-q','--filter','label=com.docker.compose.project='+project]),'Existing volumes forbidden')
 require(not dock(['network','ls','-q','--filter','label=com.docker.compose.project='+project]),'Existing network forbidden')
 with socket.socket()as s:require(s.connect_ex(('127.0.0.1',15184))!=0,'Gateway port already in use')
 def run(args,name,timeout=180):
  log=scope/(name+'.log');require(not log.exists(),'Preserve logs')
  with log.open('x')as f:log.chmod(0o600);subprocess.run(compose+args,cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=timeout)
 def cid(service):
  name=dock(['ps','-aq','--filter','label=com.docker.compose.project='+project,'--filter','label=com.docker.compose.service='+service]);require(name and '\n'not in name,'Exactly one owned service required');return name
 def sql(q):
  result=subprocess.run(docker+['exec','-i',cid('postgres'),'psql','-U',cfg['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=env,input='BEGIN READ ONLY;\n'+q+'\nCOMMIT;',text=True,capture_output=True,check=True,timeout=30).stdout
  return '\n'.join(l for l in result.splitlines()if l not in ('BEGIN','COMMIT')).strip()
 def until(check,seconds=60):
  deadline=time.monotonic()+seconds
  while not check():require(time.monotonic()<deadline,'Runtime deadline exceeded');time.sleep(.25)
 def healthy(service):return dock(['inspect','--format','{{.State.Health.Status}}',cid(service)])=='healthy'
 def artifact_hashes():
  paths=[ROOT/'backend/target/ticket-platform-0.1.0.jar',ROOT/'infra/nginx.conf',ROOT/'scripts/smoke_deployment_browser.cjs',*sorted((ROOT/'frontend/dist').rglob('*'))]
  return{str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest()for x in paths if x.is_file()}
 frozen=artifact_hashes();require(len(frozen)>1,'Verified JAR and production dist required');checks=[]
 def check(name):checks.append(name);print('PASS '+name,flush=True)
 base='http://127.0.0.1:15184'
 def client():return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def req(c,path,body=None,expected=200,staff=False,csrf=True,headers=None):
  h=dict(headers or {});data=None
  if body is not None:
   if csrf:
    t=req(c,'/api/auth/csrf'if staff else'/api/customer/auth/csrf');h[t['headerName']]=t['token']
   form=path=='/api/auth/login';h['Content-Type']='application/x-www-form-urlencoded'if form else'application/json';data=(urllib.parse.urlencode(body)if form else json.dumps(body)).encode()
  try:
   with c.open(urllib.request.Request(base+path,data=data,headers=h),timeout=25)as r:status=r.status;raw=r.read()
  except urllib.error.HTTPError as e:status=e.code;raw=e.read()
  require(status in(expected if isinstance(expected,tuple)else(expected,)),path+': unexpected status '+str(status))
  if status in(502,504):return status
  return json.loads(raw)if raw else None
 def browser(phase):
  e=env.copy();e.update(TICKET_RUNTIME_ENV=str(envfile),TICKET_DEPLOY_FIXTURE=str(scope/'browser-fixture.json'),TICKET_DEPLOY_BROWSER_OUTPUT=str(scope/('browser-'+phase+'.json')))
  with(scope/('browser-'+phase+'.log')).open('x')as f:Path(f.name).chmod(0o600);subprocess.run(['node','scripts/smoke_deployment_browser.cjs',phase],cwd=ROOT,env=e,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=120)
  result=json.loads((scope/('browser-'+phase+'.json')).read_text());require(result['passed']>0,'Browser proof required');return result
 started=False;browser_results=[]
 try:
  started=True;run(['up','-d','--no-build','--wait','postgres','redis','rabbitmq','api-a','api-b','gateway'],'start',240)
  schema=migration_version(ROOT/'backend/target/ticket-platform-0.1.0.jar')
  require(sql('SELECT count(*) FROM flyway_schema_history WHERE success;')==str(schema),'Schema must match verified artifact');check('isolated V'+str(schema)+' schema in deployment network')
  owner,mode=dock(['exec',cid('rabbitmq'),'stat','-c','%u %a','/var/lib/rabbitmq/.erlang.cookie']).split()
  require(owner=='100'and int(mode,8)&0o077==0,'Broker cookie must be owned by rabbitmq and private')
  health=json.loads(dock(['inspect','--format','{{json .Config.Healthcheck.Test}}',cid('rabbitmq')]))
  require(health[:4]==['CMD','su-exec','rabbitmq','rabbitmq-diagnostics'],'Healthcheck must use broker user')
  check('broker healthcheck runs as rabbitmq and Erlang cookie owner is private UID100')
  for service in ['api-a','api-b']:
   require(dock(['exec',cid(service),'sha256sum','/app/ticket.jar']).split()[0]==frozen['backend/target/ticket-platform-0.1.0.jar'],'Deployed JAR differs from verified build')
  require(dock(['exec',cid('gateway'),'sha256sum','/etc/nginx/nginx.conf']).split()[0]==frozen['infra/nginx.conf'],'Gateway config differs')
  for asset in sorted((ROOT/'frontend/dist').rglob('*')):
   if asset.is_file():require(dock(['exec',cid('gateway'),'sha256sum','/usr/share/nginx/html/'+str(asset.relative_to(ROOT/'frontend/dist'))]).split()[0]==frozen[str(asset.relative_to(ROOT))],'Gateway dist differs')
  check('actual image JAR nginx config and frontend bytes match verified artifacts')
  for s in ['postgres','redis','rabbitmq','api-a','api-b','gateway']:
   ports=json.loads(dock(['inspect','--format','{{json .HostConfig.PortBindings}}',cid(s)]))or{}
   require(ports==({'8080/tcp':[{'HostIp':'127.0.0.1','HostPort':'15184'}]}if s=='gateway'else{}),'Only loopback gateway may publish ports')
  for s in ['api-a','api-b','gateway']:
   info=json.loads(dock(['inspect',cid(s)]))[0];require(info['HostConfig']['ReadonlyRootfs']and info['Config']['User']not in('','0','root'),'Hardened nonroot runtime required')
  check('no host DB Redis MQ API ports; nonroot read-only application and gateway')
  require(json.loads(dock(['inspect','--format','{{json .Config.Labels}}',cid('api-a')]))['com.docker.compose.project']==project,'Ownership')
  staff=client();req(staff,'/api/auth/login',{'username':cfg['TICKET_STAFF_USERNAME'],'password':cfg['TICKET_STAFF_PASSWORD']},staff=True)
  for _ in range(20):require(req(staff,'/api/auth/me')['canWrite'],'Manager identity')
  ips={s:dock(['inspect','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',cid(s)])for s in ['api-a','api-b']}
  logs=dock(['logs',cid('gateway')]);require(all(ip+':8080'in logs for ip in ips.values()),'Both backend routes must be observed');check('real Nginx routes authenticated requests to both APIs')
  require(req(staff,'/api/auth/me',headers={'X-Forwarded-Proto':'https','Forwarded':'for=spoof'})['canWrite'],'Forward header identity')
  req(client(),'/api/admin/events',expected=401,headers={'Forwarded':'for=spoof','X-Forwarded-For':'127.0.0.1'})
  require(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(base+'/staff').status==200,'SPA deep link');check('production SPA deep link and forwarded headers do not supply identity')
  browser_results.append(browser('seed'));run(['stop','-t','20','api-a'],'stop-api-a')
  require(dock(['inspect','--format','{{.State.Running}}',cid('api-a')])=='false','Actual API stop')
  # Dynamic DNS/passive failures may expose a transient read 502/504. Record
  # every probe; require bounded convergence, then 20 strict successes. No silent retry.
  read_probes=[];recovery_started=time.monotonic();read_deadline=recovery_started+8
  while True:
   response=req(staff,'/api/auth/me',expected=(200,502,504))
   status=response if isinstance(response,int)else 200;read_probes.append(status)
   require(time.monotonic()<=read_deadline,'Single-node route did not recover within 8 seconds')
   if status==200:require(response['canWrite'],'Recovered manager identity');break
   time.sleep(.25)
  recovery_seconds=round(time.monotonic()-recovery_started,3)
  for _ in range(20):require(req(staff,'/api/auth/me')['canWrite'],'Stable single node route after convergence')
  browser_results.append(browser('resume'));check('actual API-a stop; original browser pending and cookie replay through API-b')
  f=json.loads((scope/'browser-fixture.json').read_text());b=json.loads(f['originalBody'])
  require(sql("SELECT count(*) FROM catalog_command WHERE request_id='"+b['requestId']+"';")=='1','One original catalog command')
  run(['start','api-a'],'start-api-a');until(lambda:healthy('api-a'));run(['exec','-T','gateway','nginx','-s','reload'],'reload-gateway');time.sleep(.5)
  for _ in range(20):require(req(staff,'/api/auth/me')['canWrite'],'Returned node auth')
  check('API-a rejoins after explicit Nginx reload without container restart and same staff identity')
  # No worker exists: acceptance must persist without either API running the publisher/consumer.
  customer=client();req(customer,'/api/customer/auth/register',dict(handle='dep-'+uuid4().hex[:24],displayName='部署合成顧客',password=uuid4().hex))
  def command(**kw):return {'requestId':str(uuid4()),'expectedVersion':0,**kw}
  event=req(staff,'/api/admin/events',command(slug='deploy-'+uuid4().hex,title='部署合成演出',description='不收款',venue='隔離'),staff=True)
  now=datetime.now(timezone.utc);session=req(staff,'/api/admin/events/'+event['id']+'/sessions',command(name='合成場次',startsAt=(now+timedelta(days=2)).isoformat(),salesStart=(now-timedelta(minutes=2)).isoformat(),salesEnd=(now+timedelta(days=1)).isoformat()),staff=True)
  ticket=req(staff,'/api/admin/sessions/'+session['id']+'/tickets',command(name='一般票',priceTwd=1200,capacity=10,maxPerCustomer=3),staff=True)
  req(staff,'/api/admin/events/'+event['id']+'/publish',command(expectedVersion=1),staff=True)
  def purchase_body():return dict(requestId=str(uuid4()),ticketId=ticket['id'],quantity=1,expectedTicketVersion=1,expectedTotalTwd=1200)
  body=purchase_body();purchase=req(customer,'/api/customer/purchases',body,expected=202);time.sleep(6)
  require(req(customer,'/api/customer/purchases/'+purchase['id'])['status']=='PROCESSING','APIs must not consume')
  require(sql('SELECT count(*) FROM ticket_order;')=='0'and sql('SELECT count(*) FROM outbox_publication WHERE published_at IS NOT NULL;')=='0','No API background effect');check('two API profiles accept durably but never publish consume or reserve without worker')
  run(['up','-d','--no-build','worker'],'start-worker');until(lambda:req(customer,'/api/customer/purchases/'+purchase['id'])['status']=='RESERVED')
  require(dock(['inspect','--format','{{.RestartCount}}',cid('worker')])=='0'and dock(['inspect','--format','{{.RestartCount}}',cid('rabbitmq')])=='0','No startup crash restart may hide failure');require(req(customer,'/api/customer/purchases',body)['id']==purchase['id'],'Original purchase replay');require(sql('SELECT count(*) FROM ticket_order;')=='1','One order')
  require('Tomcat' not in dock(['logs',cid('worker')]),'Worker must have no HTTP server')
  require(dock(['exec',cid('worker'),'sha256sum','/app/ticket.jar']).split()[0]==frozen['backend/target/ticket-platform-0.1.0.jar'],'Worker must run identical JAR')
  info=json.loads(dock(['inspect',cid('worker')]))[0];require(info['Config']['User']=='10001:10001'and info['HostConfig']['ReadonlyRootfs'],'Worker hardening')
  # Check actual container network socket, not merely absence of published ports.
  result=subprocess.run(docker+['exec',cid('worker'),'wget','-Y','off','-q','-O','/dev/null','http://127.0.0.1:8080/api/public/readiness'],env=env,capture_output=True,timeout=5)
  require(result.returncode!=0,'Worker HTTP port must be closed');check('one nonweb worker publishes consumes and commits exactly one reservation effect')
  run(['stop','-t','20','worker'],'stop-worker');body2=purchase_body();p2=req(customer,'/api/customer/purchases',body2,expected=202);time.sleep(2)
  require(req(customer,'/api/customer/purchases/'+p2['id'])['status']=='PROCESSING','No API takeover')
  run(['start','worker'],'restart-worker');until(lambda:req(customer,'/api/customer/purchases/'+p2['id'])['status']=='RESERVED');require(sql('SELECT count(*) FROM ticket_order;')=='2','Second original order');require(sql("SELECT held||'|'||sold FROM ticket_type WHERE id='"+ticket['id']+"';")=='2|0','Actual two reserved units and no sold inventory');check('actual worker stop and same-volume restart drains pending original request')
  # Real upstream timeout after write transmission: Nginx must not retry unknown writes.
  token=req(staff,'/api/auth/csrf');unknown=command(slug='timeout-'+uuid4().hex,title='原操作逾時',description='合成',venue='隔離')
  locklog=(scope/'pg-lock.log').open('x');Path(locklog.name).chmod(0o600)
  lock=subprocess.Popen(docker+['exec','-i',cid('postgres'),'psql','-U',cfg['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=env,stdin=subprocess.PIPE,stdout=locklog,stderr=subprocess.STDOUT,text=True)
  lock.stdin.write("BEGIN; SET application_name='ticket_m5b_timeout_lock'; SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE; SELECT pg_sleep(18); COMMIT;\n");lock.stdin.close()
  until(lambda:sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='ticket_m5b_timeout_lock' AND wait_event='PgSleep';")=='1',10)
  try:
   status=req(staff,'/api/admin/events',unknown,expected=504,staff=True,csrf=False,headers={token['headerName']:token['token']});require(status==504,'Actual gateway timeout')
   lock.wait(timeout=10);require(lock.returncode==0,'Lock fixture commit');until(lambda:sql("SELECT count(*) FROM catalog_command WHERE request_id='"+unknown['requestId']+"';")=='1',10)
   response=req(staff,'/api/admin/events',unknown,staff=True);require(response['title']==unknown['title'],'Exact original timeout retry');require(sql("SELECT count(*) FROM catalog_command WHERE request_id='"+unknown['requestId']+"';")=='1','Timeout replay one command')
   timeout_lines=[l for l in dock(['logs',cid('gateway')]).splitlines()if l.startswith('POST 504 ')];require(len(timeout_lines)==1 and ','not in timeout_lines[0],'POST timeout must have exactly one upstream attempt');check('real 15-second upstream timeout does not auto-resend POST; original UUID later resolves once')
  finally:
   if lock.poll()is None:lock.terminate();lock.wait(timeout=5)
   locklog.close()
  run(['stop','-t','10','postgres'],'stop-pg');require(req(client(),'/api/public/readiness',expected=503)=={'status':'UNAVAILABLE'},'DB readiness closed');require(req(staff,'/api/auth/me',expected=503)['code']=='AUTH_UNAVAILABLE','No memory auth fallback');check('actual PG stop returns readiness and authenticated API 503')
  run(['start','postgres'],'start-pg');until(lambda:healthy('postgres'));until(lambda:healthy('api-a')and healthy('api-b'));require(req(staff,'/api/auth/me')['canWrite'],'Same cookie after PG recovery');check('same-volume PG recovery restores shared session')
  # Actual Spring-managed services in two containers retain the shared account lock.
  barrier=Barrier(6)
  def parallel_login(_):
   c=client();barrier.wait(timeout=10)
   req(c,'/api/auth/login',{'username':cfg['TICKET_STAFF_USERNAME'],'password':cfg['TICKET_STAFF_PASSWORD']},staff=True)
   return c
  with ThreadPoolExecutor(max_workers=6)as pool:login_clients=list(pool.map(parallel_login,range(6)))
  require(sql("SELECT count(*) FROM staff_session s JOIN staff_account a ON a.id=s.staff_id WHERE a.username='manager';")=='5','Global cross-API login cap')
  alive=[req(c,'/api/auth/me',expected=(200,401))for c in login_clients]
  require(sum(bool(x.get('canWrite'))for x in alive)==5,'Exactly five issued sessions remain valid')
  req(staff,'/api/auth/me',expected=401);check('six simultaneous HTTP logins across real API containers retain exactly five valid PG sessions')
  invariants=[];signals={}
  for name in ['verify_delivery_invariants.sql','verify_stock_origin_invariants.sql','verify_ticket_invariants.sql','verify_ticket_recovery_invariants.sql','verify_admission_window_invariants.sql']:
   for line in sql((ROOT/'scripts'/name).read_text()).splitlines():
    kind,count=line.split('|')
    if kind=='unattributed_revisions':signals[kind]=int(count);continue
    require(count=='0','Invariant failed: '+kind);invariants.append(kind)
  require(artifact_hashes()==frozen,'Build artifacts changed during runtime');check('read-only business invariants and unchanged verified build artifacts')
  logs=dock(['logs',cid('gateway')]);(scope/'gateway-routing.log').write_text(logs);(scope/'gateway-routing.log').chmod(0o600)
  result={'milestone':'M5B','passed':len(checks),'checks':checks,'browser':browser_results,'zeroViolationChecks':invariants,'operationalSignals':signals,'artifactSha256':frozen,'backendRequestsObserved':{s:sum(ip+':8080'in l for l in logs.splitlines())for s,ip in ips.items()},'actualApiStop':True,'singleNodeReadProbeStatuses':read_probes,'singleNodeReadRecoverySeconds':recovery_seconds,'singleNodeRecoveryBoundSeconds':8,'strictReadsAfterRecovery':20,'transparentReadFailover':False,'actualWorkerRestart':True,'actualPostReadTimeoutSeconds':15,'actualPgStopRestart':True,'publicTls':False,'crossHostHa':False,'loadBenchmark':False,'multipleWorkers':False,'realPayment':False}
  out=scope/'result.json';out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');out.chmod(0o600)
 finally:
  if started:
   try:
    state=[]
    container_ids=dock(['ps','-aq','--filter','label=com.docker.compose.project='+project]).splitlines()
    inspected=json.loads(dock(['inspect',*container_ids]))if container_ids else[]
    for info in inspected:
     state.append({'service':info['Config']['Labels']['com.docker.compose.service'],'oomKilled':info['State']['OOMKilled'],'restartCount':info['RestartCount'],'exitCode':info['State']['ExitCode']})
    (scope/'dispose-state.json').write_text(json.dumps(state)+'\n');(scope/'dispose-state.json').chmod(0o600)
    for service in ['postgres','redis','rabbitmq','api-a','api-b','worker','gateway']:
     ids=dock(['ps','-aq','--filter','label=com.docker.compose.project='+project,'--filter','label=com.docker.compose.service='+service])
     if ids and '\n'not in ids:
      with(scope/(service+'-dispose.log')).open('x')as log:
       Path(log.name).chmod(0o600);subprocess.run(docker+['logs',ids],env=env,stdout=log,stderr=subprocess.STDOUT,timeout=15,check=False)
   finally:run(['down','-v','--remove-orphans'],'dispose',180)
  remaining={kind:dock(args)for kind,args in [('containers',['ps','-aq','--filter','label=com.docker.compose.project='+project]),('volumes',['volume','ls','-q','--filter','label=com.docker.compose.project='+project]),('networks',['network','ls','-q','--filter','label=com.docker.compose.project='+project])]}
  require(not any(remaining.values()),'Owned cleanup incomplete')
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',15184))!=0,'Gateway port must be closed')
  (scope/'cleanup.json').write_text(json.dumps({'remainingOwnedResources':{k:0 for k in remaining},'gatewayPortClosed':True})+'\n');(scope/'cleanup.json').chmod(0o600)
if __name__=='__main__':main()
