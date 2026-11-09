#!/usr/bin/env python3
"""Synthetic durable-request checks against production JAR on loopback only."""
import argparse,json,uuid,time,os,urllib.request,urllib.parse,urllib.error,http.cookiejar
from pathlib import Path
from datetime import datetime,timedelta,timezone
from concurrent.futures import ThreadPoolExecutor
p=argparse.ArgumentParser();p.add_argument('--env',default='work/runtime.env');p.add_argument('--base-url',default='http://127.0.0.1:19110/api');p.add_argument('--phase',choices=['normal','seed-upgrade','verify-upgrade','prepare-restart','verify-restart','feature-off','admission','prepare-redis','while-redis-down'],default='normal');p.add_argument('--fixture',default='work/purchase-recovery.json');p.add_argument('--output',default='work/http-purchases.json');a=p.parse_args();assert urllib.parse.urlparse(a.base_url).hostname in ('localhost','127.0.0.1')
cfg=dict(l.split('=',1) for l in Path(a.env).read_text().splitlines() if l and not l.startswith('#'));checks=[]
def client(token=None):
 jar=http.cookiejar.CookieJar()
 if token:jar.set_cookie(http.cookiejar.Cookie(0,'TICKET_CUSTOMER_SESSION',token,None,False,'127.0.0.1',False,False,'/api/customer',True,False,None,True,None,None,{}))
 return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(jar)),jar
def req(c,path,body=None,expected=200,staff=False,csrf=True):
 h={};data=None
 if body is not None:
  if csrf:
   t=req(c,'/auth/csrf' if staff else '/customer/auth/csrf')[0];h[t['headerName']]=t['token']
  form=path=='/auth/login';h['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json';data=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
 try:
  with c.open(urllib.request.Request(a.base_url+path,data=data,headers=h),timeout=20) as r:code=r.status;raw=r.read();headers=r.headers
 except urllib.error.HTTPError as e:code=e.code;raw=e.read();headers=e.headers
 assert code in (expected if isinstance(expected,tuple) else (expected,)),f'{path}: expected {expected}, got {code}'
 return json.loads(raw),headers
staff,_=client();c,jar=client();public,_=client();other,_=client()
def check(n):checks.append(n)
def register(actor):req(actor,'/customer/auth/register',dict(handle='p-'+uuid.uuid4().hex,displayName='合成顧客',password=uuid.uuid4().hex))
def command(**x):return {'requestId':str(uuid.uuid4()),'expectedVersion':0,**x}
def ticket(seconds=3600):
 e=req(staff,'/admin/events',command(slug='purchase-'+uuid.uuid4().hex,title='持久購票HTTP演出',description='合成，不售票',venue='隔離場地'),staff=True)[0];now=datetime.now(timezone.utc)
 s=req(staff,'/admin/events/'+e['id']+'/sessions',command(name='合成场次',startsAt=(now+timedelta(days=2)).isoformat(),salesStart=(now-timedelta(minutes=1)).isoformat(),salesEnd=(now+timedelta(seconds=seconds)).isoformat()),staff=True)[0];t=req(staff,'/admin/sessions/'+s['id']+'/tickets',command(name='一般票',priceTwd=1200,capacity=10,maxPerCustomer=2),staff=True)[0];req(staff,'/admin/events/'+e['id']+'/publish',command(expectedVersion=1),staff=True);return e,t
body=lambda t:dict(requestId=str(uuid.uuid4()),ticketId=t['id'],quantity=1,expectedTicketVersion=1,expectedTotalTwd=1200)
def private(v):
 fd=os.open(a.fixture,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
 with os.fdopen(fd,'w') as f:json.dump(v,f)
for _ in range(100):
 try:req(public,'/public/events');break
 except OSError:time.sleep(.5)
else:raise RuntimeError('API not ready')
if a.phase in ['verify-upgrade','verify-restart','while-redis-down']:
 f=json.loads(Path(a.fixture).read_text());c,_=client(f['token'])
 if a.phase=='verify-upgrade':
  for original in f['orders']:assert req(c,'/customer/orders/'+original['id'])[0]==original
  assert req(public,'/public/events/'+f['event']['id'])[0]==f['detail'];check('retained baseline pending and paid orders and public stock projection survive schema upgrade unchanged')
 elif a.phase=='while-redis-down':
  original=f['purchase'];current=req(c,'/customer/purchases/'+original['id'])[0];check('owned durable request remains readable while actual Redis is stopped')
  req(c,'/customer/purchases',body(f['ticket']),expected=503);check('actual Redis outage rejects fresh acceptance without fail-open')
  assert req(c,'/customer/purchases',f['body'],expected=(200,202))[0]['id']==original['id'];check('known original acceptance UUID replays without Redis')
  current=req(c,'/customer/purchases/'+original['id'])[0];closed=req(c,'/customer/purchases/'+original['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=current['version']))[0];assert closed['status'] in ['CANCELLED','EXPIRED'];check('request cancellation or expiry remains safe while Redis is stopped')
  assert req(c,'/customer/orders')[0]['total']==0;check('outage neither creates an order nor needs a Redis stock compensation')
 else:
  p0=f['purchase'];deadline=datetime.fromisoformat(p0['deadlineAt'].replace('Z','+00:00')).timestamp()
  assert time.time()>deadline
  for _ in range(50):
   result=req(c,'/customer/purchases/'+p0['id'])[0]
   if result['status']=='EXPIRED':break
   time.sleep(.25)
  assert result['status']=='EXPIRED' and result['version']==2 and result['orderId'] is None;check('real restart expiry sweeper terminates durable acceptance after its original deadline')
  assert req(c,'/customer/purchases',f['body'])[0]==result;check('feature-off original UUID replays expired request without recreating outbox or hold')
  assert req(c,'/customer/orders')[0]['total']==0;check('persistent customer session survives restart and expired acceptance creates no order')
  req(c,'/customer/purchases',body(f['ticket']),expected=409);check('feature-off refuses fresh acceptance while existing queries and replay still work')
else:
 req(staff,'/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']),staff=True);register(c);e,t=ticket();b=body(t)
 if a.phase=='seed-upgrade':
  o=req(c,'/customer/orders',b)[0];second=req(c,'/customer/orders',body(t))[0];paid=req(c,'/customer/orders/'+second['id']+'/payments',dict(requestId=str(uuid.uuid4()),expectedVersion=1,expectedTotalTwd=1200,scenario='SUCCESS'))[0];paid=req(c,'/customer/orders/'+paid['id']+'/payments/'+paid['payment']['id']+'/reconcile',dict(requestId=str(uuid.uuid4())))[0];private(dict(token=next(k.value for k in jar if k.name=='TICKET_CUSTOMER_SESSION'),orders=[o,paid],event=e,detail=req(public,'/public/events/'+e['id'])[0]));check('actual retained baseline JAR created one pending and one paid order before schema upgrade')
 elif a.phase=='feature-off':req(c,'/customer/purchases',b,expected=409);check('default disabled purchase feature does not accept new requests')
 elif a.phase=='admission':
  req(c,'/customer/purchases',b,expected=409);check('enabled admission rejects fresh request without matching ticket qualification')
  req(c,'/customer/waiting-room/'+t['id']+'/join',dict(requestId=str(uuid.uuid4())))
  result=req(c,'/customer/purchases',b,expected=202)[0];check('qualified acceptance persists PROCESSING without taking PG stock')
  # Replaying accepted request does not require a new permit or consume new admission quota.
  cancel=req(c,'/customer/purchases/'+result['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=1))[0];assert cancel['status']=='CANCELLED';assert req(c,'/customer/purchases',b)[0]==cancel;check('terminal accepted request replays without another qualification or order')
 elif a.phase in ['prepare-restart','prepare-redis']:
  if a.phase=='prepare-redis':req(c,'/customer/waiting-room/'+t['id']+'/join',dict(requestId=str(uuid.uuid4())))
  result=req(c,'/customer/purchases',b,expected=202)[0];private(dict(token=next(k.value for k in jar if k.name=='TICKET_CUSTOMER_SESSION'),purchase=result,body=b,ticket=t));check('original acceptance and authenticated recovery fixture saved privately before operator-controlled fault')
 else:
  register(other);req(public,'/customer/purchases',expected=401);check('anonymous purchase history denied')
  req(c,'/customer/purchases',b,expected=403,csrf=False);check('acceptance requires customer CSRF')
  req(c,'/customer/purchases',{**b,'customerId':str(uuid.uuid4())},expected=400);check('client owner injection rejected')
  req(c,'/customer/purchases',{**b,'expectedTotalTwd':1},expected=409);check('altered quote does not create intent')
  result,h=req(c,'/customer/purchases',b,expected=202);assert result['status']=='PROCESSING' and result['orderId'] is None and h['Location'].endswith(result['id']);assert not {'sessionHash','customerId','sessionVersion','fingerprint'}&result.keys();check('202 and Location expose only safe PROCESSING state, not a reservation')
  assert req(c,'/customer/orders')[0]['total']==0 and req(public,'/public/events/'+e['id'])[0]['sessions'][0]['tickets'][0]['available']==10;check('acceptance changes neither PG availability nor order history')
  assert req(c,'/customer/purchases',b,expected=202)[0]==result;check('original UUID replay returns same receipt and deadline')
  req(c,'/customer/purchases',{**b,'quantity':2,'expectedTotalTwd':2400},expected=409);req(c,'/customer/orders',b,expected=409);check('changed body and synchronous bypass using pending UUID denied')
  req(other,'/customer/purchases/'+result['id'],expected=404);req(other,'/customer/purchases/'+result['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=1),expected=404);assert req(other,'/customer/purchases')[0]['total']==0;check('other member cannot read cancel or list owned receipt')
  token=next(k.value for k in jar if k.name=='TICKET_CUSTOMER_SESSION');bb=body(t)
  def submit(_):return req(client(token)[0],'/customer/purchases',bb,expected=202)[0]['id']
  with ThreadPoolExecutor(max_workers=6) as pool:assert len(set(pool.map(submit,range(6))))==1
  check('six actual concurrent HTTP retries return one durable purchase')
  cancel=dict(requestId=str(uuid.uuid4()),expectedVersion=1);closed=req(c,'/customer/purchases/'+result['id']+'/cancel',cancel)[0];assert closed['status']=='CANCELLED';assert req(c,'/customer/purchases/'+result['id']+'/cancel',cancel)[0]==closed;assert req(c,'/customer/purchases',b)[0]==closed;check('cancel and original submit replay never revive intent')
  req(c,'/customer/purchases?page=1&size=21',expected=400);check('purchase history pagination bounded')
  _,short=ticket(seconds=4);due=req(c,'/customer/purchases',body(short),expected=202)[0]
  for _ in range(80):
   state=req(c,'/customer/purchases/'+due['id'])[0]
   if state['status']=='EXPIRED':break
   time.sleep(.25)
  assert state['status']=='EXPIRED' and state['orderId'] is None;check('actual DB deadline and running sweeper expire intent without a hold')
 req(staff,'/auth/logout',{},staff=True)
Path(a.output).write_text(json.dumps(dict(transport='production JAR loopback HTTP + real PostgreSQL',phase=a.phase,passed=len(checks),checks=checks,rabbitMQ=False,realPayments=False),indent=2)+'\n');print(f'Purchases HTTP {a.phase}: {len(checks)} checks passed')
