#!/usr/bin/env python3
"""Own empty scope only: two unchanged production Workers, real MQ/PG fault boundaries.
Probe lives on test classpath, mounted read-only; no debug endpoints or production hooks.
"""
import argparse, hashlib, http.cookiejar, json, os, re, shutil, socket, subprocess, time, zipfile
import urllib.request, urllib.error, urllib.parse
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID, uuid4
from run_deployment_runtime import ROOT, private, require, migration_version


def main():
 p=argparse.ArgumentParser();p.add_argument('--env',required=True);p.add_argument('--output',required=True);a=p.parse_args()
 envfile=private(a.env);cfg=dict(l.split('=',1)for l in envfile.read_text().splitlines()if l and not l.startswith('#'))
 project=cfg['COMPOSE_PROJECT_NAME'];require(re.fullmatch(r'ticket-deploy-[0-9]{14}-[a-f0-9]{8}',project),'Generated scope required')
 require(cfg['TICKET_GATEWAY_PORT']=='15184'and cfg['TICKET_COOKIE_SECURE']=='false','Explicit loopback fixture required')
 require(all(cfg.get(k)=='true'for k in ['TICKET_ORDERS_ENABLED','TICKET_PURCHASES_ENABLED','TICKET_DELIVERY_ENABLED','TICKET_PUBLISHER_ENABLED','TICKET_CONSUMER_ENABLED']),'Explicit normal async fixture required')
 scope=private(a.output);require(not scope.exists(),'Preserve prior evidence');scope.mkdir(mode=0o700)
 probe_class=ROOT/'backend/target/test-classes/dev/jackteng/encore/delivery/MultiWorkerBrokerProbe.class'
 require(probe_class.is_file(),'Run full Maven package, including test compilation, first')
 # Maven output may be private to the host user. Expose only this secret-free
 # bytecode file, not the complete test directory, to the nonroot probe container.
 probe_archive=scope/'probe.jar'
 with zipfile.ZipFile(probe_archive,'x')as archive:
  entry=zipfile.ZipInfo('dev/jackteng/encore/delivery/MultiWorkerBrokerProbe.class')
  archive.writestr(entry,probe_class.read_bytes())
 probe_archive.chmod(0o444)
 env=os.environ.copy();env.update(cfg)
 for k in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH']:env.pop(k,None)
 docker=['docker','--host=unix:///var/run/docker.sock'];compose=docker+['compose','--env-file',str(envfile),'-f',str(ROOT/'infra/compose.deploy.yml')]
 def dock(args,timeout=30):return subprocess.run(docker+args,env=env,text=True,capture_output=True,check=True,timeout=timeout).stdout.strip()
 queries={'containers':['ps','-aq'],'volumes':['volume','ls','-q'],'networks':['network','ls','-q']}
 def owned(kind):return dock(queries[kind]+['--filter','label=com.docker.compose.project='+project]).splitlines()
 require(not any(owned(k)for k in queries),'Pre-existing project resources forbidden')
 with socket.socket()as s:require(s.connect_ex(('127.0.0.1',15184))!=0,'Gateway occupied')
 def save(name,data):
  path=scope/name;require(not path.exists(),'Preserve evidence '+name);path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');path.chmod(0o600)
 def run(args,name,timeout=180):
  path=scope/(name+'.log');require(not path.exists(),'Preserve log')
  with path.open('x')as f:path.chmod(0o600);subprocess.run(compose+args,cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=timeout)
 def service(name):
  ids=dock(['ps','-aq','--filter','label=com.docker.compose.project='+project,'--filter','label=com.docker.compose.service='+name]).splitlines()
  require(len(ids)==1,'Exactly one '+name+' required');return ids[0]
 def sql(query,write=False):
  out=subprocess.run(docker+['exec','-i',service('postgres'),'psql','-U',cfg['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=env,input=('BEGIN;\n'if write else'BEGIN READ ONLY;\n')+query+'\nCOMMIT;',text=True,capture_output=True,check=True,timeout=20).stdout
  return '\n'.join(l for l in out.splitlines()if l not in ['BEGIN','COMMIT']).strip()
 def until(fn,seconds=40):
  deadline=time.monotonic()+seconds
  while not fn():require(time.monotonic()<deadline,'Runtime deadline exceeded');time.sleep(.25)
 def ip(cid):return dock(['inspect','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',cid])
 checks=[];faults=[];holders=[];probe_number=0;worker_ids=[];started=False;disk=[];queue_samples=[]
 def check(name):checks.append(name);print('PASS '+name,flush=True)
 def channel_unacked():
  raw=dock(['exec',service('rabbitmq'),'su-exec','rabbitmq','rabbitmqctl','-q','--timeout','10','list_channels','--formatter=json','consumer_count','messages_unacknowledged'],20)
  return sum(row['messages_unacknowledged']for row in json.loads(raw)if row['consumer_count']>0)
 def queue_info():
  raw=dock(['exec',service('rabbitmq'),'su-exec','rabbitmq','rabbitmqctl','-q','--timeout','10','list_queues','-p',cfg['TICKET_MQ_VHOST'],'--formatter=json','name','consumers','messages_ready','messages_unacknowledged'],20)
  rows=json.loads(raw);row=next((r for r in rows if r['name']==cfg['TICKET_MQ_NAMESPACE']+'.purchase.q'),None)
  if row is not None:
   row['queueReportedUnacknowledged']=row['messages_unacknowledged']
   row['messages_unacknowledged']=channel_unacked()
   queue_samples.append({k:v for k,v in row.items()if k!='name'})
  return row
 def drained():
  raw=dock(['exec',service('rabbitmq'),'su-exec','rabbitmq','rabbitmqctl','-q','--timeout','10','list_queues','-p',cfg['TICKET_MQ_VHOST'],'--formatter=json','name','messages_ready','messages_unacknowledged'],20)
  rows=json.loads(raw);require(len(rows)==3,'Exactly three owned quorum queues required')
  return all(r['messages_ready']==0 and r['messages_unacknowledged']==0 for r in rows)and channel_unacked()==0
 probe_env=scope/'probe.env';probe_env.write_text(''.join(k+'='+cfg[k]+'\n'for k in ['TICKET_MQ_USER','TICKET_MQ_PASSWORD','TICKET_MQ_VHOST','TICKET_MQ_NAMESPACE']));probe_env.chmod(0o600)
 def probe(*args):
  nonlocal probe_number
  probe_number+=1;name=project+'-probe-'+str(probe_number)
  command=['run','--rm','--name',name,'--label','com.docker.compose.project='+project,'--label','ticket.private.probe=true','--network',project+'_default','--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=32m,mode=1777','--cap-drop=ALL','--security-opt=no-new-privileges:true','--memory=128m','--cpus=.5','--env-file',str(probe_env),'--mount','type=bind,src='+str(probe_archive)+',dst=/probe.jar,readonly','--entrypoint','java',cfg['TICKET_APP_IMAGE'],'-Xmx96m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=2','-Dloader.path=/probe.jar','-Dloader.main=dev.jackteng.encore.delivery.MultiWorkerBrokerProbe','-cp','/app/ticket.jar','org.springframework.boot.loader.launch.PropertiesLauncher',*args]
  path=scope/('probe-'+str(probe_number)+'.log')
  try:
   result=subprocess.run(docker+command,env=env,text=True,capture_output=True,timeout=30)
   path.write_text(result.stdout+'\n'+result.stderr);path.chmod(0o600)
   require(result.returncode==0,'Native probe failed; private log '+path.name)
   return json.loads(result.stdout)
  finally:
   remaining=dock(['ps','-aq','--filter','name=^/'+name+'$'])
   if remaining:dock(['rm','-f',remaining])
 def lock(query,label):
  path=scope/(label+'.log');f=path.open('x');path.chmod(0o600)
  proc=subprocess.Popen(docker+['exec','-i',service('postgres'),'psql','-U',cfg['TICKET_DB_USER'],'-d','ticket_demo','-XAt','-v','ON_ERROR_STOP=1'],env=env,stdin=subprocess.PIPE,stdout=f,stderr=subprocess.STDOUT,text=True)
  holders.append((proc,f));proc.stdin.write("BEGIN; SET application_name='"+label+"'; "+query+';\n');proc.stdin.flush()
  until(lambda:sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='"+label+"' AND state='idle in transaction' AND wait_event='ClientRead';")=='1',10)
  return proc,f
 def release(holder):
  proc,f=holder
  if proc.poll()is None:proc.stdin.write('COMMIT;\n');proc.stdin.close();proc.wait(timeout=10)
  require(proc.returncode==0,'Lock release failed');f.close();holders.remove(holder)
 base='http://127.0.0.1:15184'
 def client():return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def req(c,path,body=None,expected=200,staff=False):
  headers={};data=None
  if body is not None:
   csrf=req(c,'/api/auth/csrf'if staff else'/api/customer/auth/csrf');headers[csrf['headerName']]=csrf['token']
   form=path=='/api/auth/login';headers['Content-Type']='application/x-www-form-urlencoded'if form else'application/json'
   data=(urllib.parse.urlencode(body)if form else json.dumps(body)).encode()
  try:
   with c.open(urllib.request.Request(base+path,data=data,headers=headers),timeout=25)as r:status=r.status;raw=r.read()
  except urllib.error.HTTPError as e:status=e.code;raw=e.read()
  require(status==expected,path+': unexpected status '+str(status));return json.loads(raw)if raw else None
 def command(**kw):return dict(requestId=str(uuid4()),expectedVersion=0,**kw)
 artifacts=[ROOT/'backend/target/ticket-platform-0.1.0.jar',ROOT/'infra/nginx.conf',ROOT/'infra/compose.deploy.yml',Path(__file__).resolve(),ROOT/'backend/src/test/java/dev/jackteng/encore/delivery/MultiWorkerBrokerProbe.java',probe_class,*sorted((ROOT/'frontend/dist').rglob('*'))]
 def hashes():return{str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest()for x in artifacts if x.is_file()}
 frozen=hashes()
 def fixture(capacity):
  c=client();who=req(c,'/api/customer/auth/register',dict(handle='mw-'+uuid4().hex[:24],displayName='合成Worker顧客',password=uuid4().hex))
  event=req(staff,'/api/admin/events',command(slug='workers-'+uuid4().hex,title='多Worker合成演出',description='不收款',venue='隔離'),staff=True)
  now=datetime.now(timezone.utc)
  session=req(staff,'/api/admin/events/'+event['id']+'/sessions',command(name='合成',startsAt=(now+timedelta(days=2)).isoformat(),salesStart=(now-timedelta(minutes=2)).isoformat(),salesEnd=(now+timedelta(days=1)).isoformat()),staff=True)
  ticket=req(staff,'/api/admin/sessions/'+session['id']+'/tickets',command(name='一般票',priceTwd=1200,capacity=capacity,maxPerCustomer=2),staff=True)
  req(staff,'/api/admin/events/'+event['id']+'/publish',dict(requestId=str(uuid4()),expectedVersion=1),staff=True)
  return {'client':c,'owner':str(UUID(who['id'])),'ticket':ticket['id']}
 def submit(f):
  body=dict(requestId=str(uuid4()),ticketId=f['ticket'],quantity=1,expectedTicketVersion=1,expectedTotalTwd=1200)
  purchase=req(f['client'],'/api/customer/purchases',body,expected=202)
  f.update(body=body,purchase=purchase['id'],event=str(UUID(sql("SELECT id FROM purchase_outbox WHERE purchase_id='"+purchase['id']+"';"))))
  return purchase
 def status(f):return sql("SELECT status FROM purchase_request WHERE id='"+f['purchase']+"';")
 def one(f,replay=True):
  require(status(f)=='RESERVED','Original purchase not reserved')
  require(sql("SELECT count(*) FROM ticket_order WHERE customer_id='"+f['owner']+"' AND ticket_id='"+f['ticket']+"';")=='1','Duplicate order')
  require(sql("SELECT count(*) FROM order_inventory_event WHERE ticket_id='"+f['ticket']+"' AND phase='HOLD';")=='1','Duplicate inventory event')
  require(sql("SELECT held||'|'||sold FROM ticket_type WHERE id='"+f['ticket']+"';")=='1|0','Duplicate stock effect')
  if replay:require(req(f['client'],'/api/customer/purchases',f['body'])['id']==f['purchase'],'Original UUID replay changed identity')
 def waiting(cid,fragment):
  return int(sql("SELECT count(*) FROM pg_stat_activity WHERE client_addr='"+ip(cid)+"' AND wait_event_type='Lock' AND query LIKE '%"+fragment+"%';"))>0
 def kill(cid,label):
  dock(['update','--restart=no',cid]);dock(['kill','--signal=KILL',cid]);info=json.loads(dock(['inspect',cid]))[0]
  require(not info['State']['Running']and info['State']['ExitCode']==137 and not info['State']['OOMKilled'],'Actual deliberate kill required')
  faults.append({'boundary':label,'signal':'SIGKILL','exitCode':137,'oomKilled':False,'automaticRestartSuppressed':True})
 def restart(cid):
  dock(['start',cid]);dock(['update','--restart=unless-stopped',cid]);until(lambda:(queue_info()or{}).get('consumers')==2,60)
 try:
  disk.append({'phase':'before startup','workspaceFreeBytes':shutil.disk_usage(ROOT).free})
  started=True;run(['up','-d','--no-build','--wait','postgres','redis','rabbitmq','api-a','api-b','gateway'],'start',240)
  require(sql('SELECT count(*) FROM flyway_schema_history WHERE success;')==str(migration_version(ROOT/'backend/target/ticket-platform-0.1.0.jar')),'Schema must match verified artifact')
  run(['up','-d','--no-build','--scale','worker=2','worker'],'scale-workers')
  worker_ids=dock(['ps','-aq','--filter','label=com.docker.compose.project='+project,'--filter','label=com.docker.compose.service=worker']).splitlines();require(len(worker_ids)==2,'Two actual worker containers required')
  until(lambda:(queue_info()or{}).get('consumers')==2,60)
  disk.append({'phase':'eight containers with two consumers','workspaceFreeBytes':shutil.disk_usage(ROOT).free})
  for cid in [service('api-a'),service('api-b'),*worker_ids]:require(dock(['exec',cid,'sha256sum','/app/ticket.jar']).split()[0]==frozen['backend/target/ticket-platform-0.1.0.jar'],'Production JAR changed')
  require(dock(['exec',service('gateway'),'sha256sum','/etc/nginx/nginx.conf']).split()[0]==frozen['infra/nginx.conf'],'Gateway config changed')
  for asset in sorted((ROOT/'frontend/dist').rglob('*')):
   if asset.is_file():require(dock(['exec',service('gateway'),'sha256sum','/usr/share/nginx/html/'+str(asset.relative_to(ROOT/'frontend/dist'))]).split()[0]==frozen[str(asset.relative_to(ROOT))],'Gateway dist differs from build')
  for cid in worker_ids:
   info=json.loads(dock(['inspect',cid]))[0];require(info['Config']['User']=='10001:10001'and info['HostConfig']['ReadonlyRootfs']and not info['HostConfig']['PortBindings'],'Private hardened workers required')
   closed=subprocess.run(docker+['exec',cid,'wget','-Y','off','-q','-O','/dev/null','http://127.0.0.1:8080/api/public/readiness'],env=env,capture_output=True,timeout=5);require(closed.returncode!=0,'Nonweb worker required')
  require(probe('snapshot')['consumers']==2,'Actual native broker consumer count')
  check('two hardened nonweb containers run identical production JAR and both consume one quorum queue')
  staff=client();req(staff,'/api/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']),staff=True)
  # A bounded burst from distinct identities; both production publishers/consumers run.
  burst=fixture(5);buyers=[burst]
  for _ in range(11):
   c=client();who=req(c,'/api/customer/auth/register',dict(handle='mw-'+uuid4().hex[:24],displayName='合成競爭者',password=uuid4().hex));buyers.append({'client':c,'owner':str(UUID(who['id'])),'ticket':burst['ticket']})
  for f in buyers:submit(f)
  until(lambda:all(status(f)!='PROCESSING'for f in buyers))
  require(sql("SELECT status||'|'||count(*) FROM purchase_request WHERE ticket_id='"+burst['ticket']+"' GROUP BY status ORDER BY status;")=='REJECTED|7\nRESERVED|5','Exact finite capacity outcomes')
  require(sql("SELECT count(*) FROM purchase_request WHERE ticket_id='"+burst['ticket']+"' AND status='REJECTED' AND reason='SOLD_OUT';")=='7','Expected sold-out reason')
  require(sql("SELECT held||'|'||sold FROM ticket_type WHERE id='"+burst['ticket']+"';")=='5|0','Capacity exceeded')
  until(lambda:sql('SELECT count(*) FROM outbox_publication WHERE attempts<>1 OR published_at IS NULL;')=='0')
  until(drained);check('12 distinct purchase intents compete for five units: five reserved seven sold out and one publication attempt per intent')
  # Both real consumers block in the original service transaction on duplicate identity.
  dup=fixture(3);submit(dup);until(lambda:status(dup)=='RESERVED');until(drained)
  holder=lock("SELECT id FROM customer_account WHERE id='"+dup['owner']+"' FOR UPDATE",'m5c_duplicate_lock')
  require(probe('duplicate',dup['event'],dup['purchase'],'8')['confirmedCopies']==8,'Confirmed duplicates')
  until(lambda:all(waiting(cid,'SELECT id FROM customer_account')for cid in worker_ids),15)
  until(lambda:queue_info()['messages_unacknowledged']>=2,15);one(dup,False)
  release(holder);until(drained);one(dup);check('eight persistent original-ID duplicates reach both worker transactions; ACK waits for DB and only one stock/order effect remains')
  # Real AMQP confirm occurred, then block PG receipt only in this disposable DB.
  lost=fixture(3);victim,survivor=worker_ids;dock(['pause',survivor])
  sql("CREATE FUNCTION m5c_block_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.published_at IS NOT NULL THEN PERFORM pg_advisory_xact_lock(68493017); END IF; RETURN NEW; END $$; CREATE TRIGGER m5c_block_receipt BEFORE UPDATE ON outbox_publication FOR EACH ROW EXECUTE FUNCTION m5c_block_receipt();",True)
  holder=lock('SELECT pg_advisory_xact_lock(68493017)','m5c_receipt_lock');submit(lost)
  until(lambda:waiting(victim,'UPDATE outbox_publication SET published_at'),10)
  lease=sql("SELECT attempts||'|'||(published_at IS NULL)::text||'|'||(lease_until>clock_timestamp())::text FROM outbox_publication WHERE event_id='"+lost['event']+"';")
  save('lost-lease-before-kill.json',{'receipt':lease,'businessStatus':status(lost)})
  require(lease=='1|true|true','Durable original unexpired lease required')
  kill(victim,'after real broker confirm before PG publication receipt')
  # A blocked PG backend may notice the dead client only after its lock is released.
  # Release first so its uncommitted receipt rolls back before taking the DDL lock.
  release(holder)
  sql('DROP TRIGGER m5c_block_receipt ON outbox_publication; DROP FUNCTION m5c_block_receipt();',True)
  require(sql("SELECT count(*) FROM outbox_publication WHERE event_id='"+lost['event']+"' AND published_at IS NULL AND attempts=1;")=='1','Killed receipt transaction must roll back')
  dock(['unpause',survivor])
  until(lambda:sql("SELECT count(*) FROM outbox_publication WHERE event_id='"+lost['event']+"' AND published_at IS NOT NULL AND attempts=2;")=='1',25)
  until(drained);one(lost);restart(victim);check('SIGKILL after real confirm loses PG receipt; surviving worker waits original 15-second lease and republishes same event with one business effect')
  # Actual consumer crash before commit, same original broker delivery requeues.
  crashed=fixture(3)
  for cid in worker_ids:dock(['pause',cid])
  submit(crashed);holder=lock("SELECT id FROM customer_account WHERE id='"+crashed['owner']+"' FOR UPDATE",'m5c_consumer_lock')
  for cid in worker_ids:dock(['unpause',cid])
  until(lambda:any(waiting(cid,'SELECT id FROM customer_account')for cid in worker_ids),15)
  victim=next(cid for cid in worker_ids if waiting(cid,'SELECT id FROM customer_account'))
  survivor=next(cid for cid in worker_ids if cid!=victim)
  require(queue_info()['messages_unacknowledged']>=1 and status(crashed)=='PROCESSING','Original uncommitted delivery required')
  require(sql("SELECT count(*) FROM ticket_order WHERE customer_id='"+crashed['owner']+"';")=='0','Commit before lock released')
  until(lambda:sql("SELECT count(*) FROM outbox_publication WHERE event_id='"+crashed['event']+"' AND published_at IS NOT NULL AND attempts=1;")=='1',10)
  save('consumer-before-kill.json',{'selectedByActualPgTransaction':True,'originalPublicationConfirmed':True,'status':'PROCESSING','businessOrders':0})
  kill(victim,'consumer transaction blocked before commit')
  # An active peer can immediately receive the requeued delivery; a transient
  # ready count is not an observable boundary. Confirm its original DB work.
  until(lambda:waiting(survivor,'SELECT id FROM customer_account'),15)
  require(queue_info()['messages_unacknowledged']>=1 and status(crashed)=='PROCESSING','Survivor must take original unACKed delivery')
  release(holder);until(lambda:status(crashed)=='RESERVED',20);until(drained);one(crashed);restart(victim)
  check('SIGKILL of blocked consumer requeues original unACKed delivery; second container commits one reservation and first rejoins')
  require(sql('SELECT count(*) FROM ticket_order;')=='8'and sql('SELECT count(*) FROM purchase_outbox;')=='15','Exact original effects')
  require(sql('SELECT count(*) FROM pg_trigger WHERE tgname LIKE \'m5c_%\';')=='0','Temporary fault trigger remains')
  invariants=[];signals={}
  for name in ['verify_delivery_invariants.sql','verify_stock_origin_invariants.sql']:
   for line in sql((ROOT/'scripts'/name).read_text()).splitlines():
    kind,count=line.split('|')
    if kind=='unattributed_revisions':signals[kind]=int(count);continue
    require(count=='0','Invariant '+kind);invariants.append(kind)
  require(hashes()==frozen,'Artifacts changed during runtime');require((queue_info()or{})['consumers']==2,'Both workers must rejoin')
  check('all original intents publications orders stock origins and audits reconcile; no fixture trigger and unchanged build artifacts')
  save('result.json',{'milestone':'M5C','checks':checks,'passed':len(checks),'workers':2,'customersCompeting':12,'capacity':5,'reserved':5,'soldOut':7,'confirmedDuplicateCopies':8,'businessOrders':8,'immutableEvents':15,'lostReceiptPublicationAttempts':2,'leaseSecondsUnchanged':15,'faults':faults,'zeroViolationChecks':invariants,'operationalSignals':signals,'artifactSha256':frozen,'diskSamples':disk,'publicTls':False,'crossHostHa':False,'loadBenchmark':False,'stockPredebit':False,'multipleBrokers':False,'containerKillAfterBusinessCommitBeforeAck':False})
 finally:
  save('queue-samples.json',queue_samples)
  for proc,f in holders:
   if proc.poll()is None:
    try:proc.stdin.close();proc.wait(timeout=5)
    except Exception:proc.kill();proc.wait(timeout=5)
   f.close()
  if started:
   try:
    ids=owned('containers');infos=json.loads(dock(['inspect',*ids]))if ids else[]
    save('dispose-state.json',[{'name':x['Name'].removeprefix('/'),'service':x['Config']['Labels'].get('com.docker.compose.service'),'oomKilled':x['State']['OOMKilled'],'restartCount':x['RestartCount'],'exitCode':x['State']['ExitCode'],'paused':x['State']['Paused']}for x in infos])
    for x in infos:
     cid=x['Id']
     if x['State']['Paused']:dock(['unpause',cid])
     path=scope/(x['Name'].removeprefix('/')+'-dispose.log')
     with path.open('x')as f:path.chmod(0o600);subprocess.run(docker+['logs',cid],env=env,stdout=f,stderr=subprocess.STDOUT,timeout=15,check=False)
   finally:run(['down','-v','--remove-orphans'],'dispose',180)
  remaining={k:len(owned(k))for k in queries};require(not any(remaining.values()),'Owned cleanup incomplete')
  with socket.socket()as s:require(s.connect_ex(('127.0.0.1',15184))!=0,'Gateway must close')
  save('cleanup.json',{'remainingOwnedResources':remaining,'gatewayPortClosed':True})


if __name__=='__main__':main()
