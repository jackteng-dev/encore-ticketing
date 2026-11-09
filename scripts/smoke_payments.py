#!/usr/bin/env python3
"""Free simulator verification against a real loopback production JAR, using synthetic data."""
import argparse,json,uuid,time,os,urllib.request,urllib.parse,urllib.error,http.cookiejar
from datetime import datetime,timedelta,timezone
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--env',default='work/runtime.env');p.add_argument('--base-url',default='http://127.0.0.1:19110/api');p.add_argument('--output',default='work/http-payments.json');p.add_argument('--phase',choices=['normal','prepare-restart','verify-restart'],default='normal');p.add_argument('--fixture',default='work/payment-restart.json');a=p.parse_args()
assert urllib.parse.urlparse(a.base_url).hostname in ('127.0.0.1','localhost'),'Loopback only'
cfg=dict(l.split('=',1) for l in Path(a.env).read_text().splitlines() if l and not l.startswith('#'));checks=[]
def client():
 jar=http.cookiejar.CookieJar();return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(jar)),jar
def req(c,path,body=None,expected=200,staff=False,csrf=True):
 h={};data=None
 if body is not None:
  if csrf:
   token=req(c,'/auth/csrf' if staff else '/customer/auth/csrf')[0];h[token['headerName']]=token['token']
  form=path=='/auth/login';h['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json';data=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
 try:
  with c.open(urllib.request.Request(a.base_url+path,data=data,headers=h),timeout=20) as r:code=r.status;raw=r.read()
 except urllib.error.HTTPError as e:code=e.code;raw=e.read()
 assert code in (expected if isinstance(expected,tuple) else (expected,)),f'{path}: expected {expected}, got {code}'
 return json.loads(raw),code
def check(n):checks.append(n)
def command(**kw):return {'requestId':str(uuid.uuid4()),'expectedVersion':0,**kw}
def customer(c):return req(c,'/customer/auth/register',dict(handle='pay-'+uuid.uuid4().hex,displayName='合成顧客',password=uuid.uuid4().hex))[0]
staff,_=client();c,jar=client();other,_=client();public,_=client()
def fixture(seconds=3600):
 e=req(staff,'/admin/events',command(slug='payment-'+uuid.uuid4().hex,title='免費模擬付款演出',description='合成，不收款',venue='合成場地'),staff=True)[0];now=datetime.now(timezone.utc);end=now+timedelta(seconds=seconds)
 s=req(staff,'/admin/events/'+e['id']+'/sessions',command(name='模擬場次',startsAt=(end+timedelta(days=1)).isoformat(),salesStart=(now-timedelta(minutes=1)).isoformat(),salesEnd=end.isoformat()),staff=True)[0]
 t=req(staff,'/admin/sessions/'+s['id']+'/tickets',command(name='一般票',priceTwd=1200,capacity=3,maxPerCustomer=2),staff=True)[0];req(staff,'/admin/events/'+e['id']+'/publish',command(expectedVersion=1),staff=True)
 body=dict(requestId=str(uuid.uuid4()),ticketId=t['id'],quantity=1,expectedTicketVersion=1,expectedTotalTwd=1200);o=req(c,'/customer/orders',body)[0];return e,t,o
def pay(o,scenario='SUCCESS'):return dict(requestId=str(uuid.uuid4()),expectedVersion=o['version'],expectedTotalTwd=o['totalTwd'],scenario=scenario)
def start(o,body=None):return req(c,'/customer/orders/'+o['id']+'/payments',body or pay(o))[0]
def reconcile(o,body=None,actor=None):return req(actor or c,'/customer/orders/'+o['id']+'/payments/'+o['payment']['id']+'/reconcile',body or dict(requestId=str(uuid.uuid4())))[0]
for _ in range(120):
 try:req(public,'/public/events');break
 except OSError:time.sleep(.5)
else:raise RuntimeError('API not ready')
if a.phase=='verify-restart':
 f=json.loads(Path(a.fixture).read_text());jar.set_cookie(http.cookiejar.Cookie(0,'TICKET_CUSTOMER_SESSION',f['token'],None,False,urllib.parse.urlparse(a.base_url).hostname,False,False,'/api/customer',True,False,None,True,None,None,{}))
 paid=req(c,'/customer/orders/'+f['paid']['id'])[0];assert paid['status']=='PAID' and paid['payment']['status']=='SUCCEEDED';check('paid order and persistent customer session survive real JAR restart')
 for _ in range(40):
  pending=req(c,'/customer/orders/'+f['pending']['id'])[0]
  if pending['status']=='EXPIRED':break
  time.sleep(.5)
 else:raise AssertionError('Restart sweeper did not release overdue hold')
 assert pending['payment']['status']=='PENDING';check('restart sweeper releases overdue hold but preserves unresolved durable payment')
 original=req(c,'/customer/orders/'+pending['id']+'/payments',f['body'])[0];assert original==pending;check('feature-off original payment UUID replays same unresolved attempt')
 reviewed=reconcile(pending,f['reconcile']);assert reviewed['status']=='EXPIRED' and reviewed['payment']['status']=='REVIEW' and reviewed['payment']['reason']=='LATE_SUCCESS';check('durable mock receipt after restart becomes late-success review without revival')
 assert reconcile(pending,f['reconcile'])==reviewed;check('original reconcile UUID safely replays review after restart')
 assert req(public,'/public/events/'+f['event']['id'])[0]['sessions'][0]['tickets'][0]['available']==3;check('late restarted payment does not reclaim released availability')
 req(c,'/customer/orders/'+pending['id']+'/payments',pay(pending),expected=409);check('disabled mock feature blocks new payment while existing reconciliation works')
else:
 req(staff,'/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']),staff=True);customer(c)
 if a.phase=='prepare-restart':
  _,_,paid=fixture();paid=reconcile(start(paid));e,_,pending=fixture(20);body=pay(pending);pending=start(pending,body);token=next(k.value for k in jar if k.name=='TICKET_CUSTOMER_SESSION')
  fd=os.open(a.fixture,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
  with os.fdopen(fd,'w') as out:json.dump(dict(paid=paid,pending=pending,body=body,reconcile=dict(requestId=str(uuid.uuid4())),event=e,token=token),out)
  check('paid order and unresolved successful mock receipt stored privately before real outage')
 else:
  customer(other);e,t,o=fixture();path='/customer/orders/'+o['id']+'/payments';body=pay(o)
  req(public,path,body,expected=403,csrf=False);req(c,path,body,expected=403,csrf=False);check('mock payment requires customer CSRF')
  req(other,path,body,expected=404);check('other customer cannot initiate payment on owned order')
  req(c,path,{**body,'providerEventId':str(uuid.uuid4())},expected=400);check('client cannot inject provider event field')
  req(c,path,{**body,'expectedTotalTwd':1},expected=409);check('server rejects altered payment quote amount')
  pending=start(o,body);assert pending['payment']['status']=='PENDING' and pending['payment']['amountTwd']==1200 and pending['version']==2;check('initiation persists one unresolved attempt without selling')
  assert req(c,path,body)[0]==pending;check('original initiation UUID replays same payment')
  req(c,path,{**body,'scenario':'DECLINED'},expected=409);check('same UUID changed payment body conflicts')
  req(c,path,pay(pending),expected=409);check('new UUID cannot create another attempt')
  req(other,'/customer/orders/'+o['id']+'/payments/'+pending['payment']['id']+'/reconcile',dict(requestId=str(uuid.uuid4())),expected=404);check('other customer cannot reconcile payment')
  reconcile_body=dict(requestId=str(uuid.uuid4()));paid=reconcile(pending,reconcile_body);assert paid['status']=='PAID' and paid['payment']['status']=='SUCCEEDED';check('successful reconcile converts order to paid')
  assert reconcile(pending,reconcile_body)==paid and reconcile(pending)==paid;check('same and different command replay one provider event without reselling')
  req(c,'/customer/orders/'+o['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=paid['version']),expected=409);check('paid order refuses ordinary cancellation')
  req(c,'/customer/orders',dict(requestId=str(uuid.uuid4()),ticketId=t['id'],quantity=2,expectedTicketVersion=1,expectedTotalTwd=2400),expected=409);check('paid quantity remains in per-customer quota')
  assert req(public,'/public/events/'+e['id'])[0]['sessions'][0]['tickets'][0]['available']==2;check('paid quantity remains unavailable')
  _,_,declined=fixture();declined=reconcile(start(declined,pay(declined,'DECLINED')));assert declined['status']=='PENDING_PAYMENT' and declined['payment']['status']=='DECLINED';req(c,'/customer/orders/'+declined['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=declined['version']));check('declined mock does not sell and allows cancellation')
  e,_,cancelled=fixture();pending=start(cancelled);req(c,'/customer/orders/'+pending['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=pending['version']));reviewed=reconcile(pending);assert reviewed['status']=='CANCELLED' and reviewed['payment']['reason']=='LATE_SUCCESS';assert req(public,'/public/events/'+e['id'])[0]['sessions'][0]['tickets'][0]['available']==3;check('cancelled late success becomes review without reclaiming inventory')
  e,_,expiring=fixture(5);pending=start(expiring)
  for _ in range(40):
   current=req(c,'/customer/orders/'+pending['id'])[0]
   if current['status']=='EXPIRED':break
   time.sleep(.5)
  else:raise AssertionError('Actual deadline sweeper did not release')
  reviewed=reconcile(pending);assert reviewed['status']=='EXPIRED' and reviewed['payment']['status']=='REVIEW';check('real deadline expiry then delayed success stays expired and reviewed')
  _,_,racing=fixture();pending=start(racing)
  # Independent jars holding the same synthetic customer token avoid shared CookieJar races.
  token=next(k.value for k in jar if k.name=='TICKET_CUSTOMER_SESSION');actors=[]
  for _ in range(4):
   actor,cookies=client();cookies.set_cookie(http.cookiejar.Cookie(0,'TICKET_CUSTOMER_SESSION',token,None,False,'127.0.0.1',False,False,'/api/customer',True,False,None,True,None,None,{}));actors.append(actor)
  with ThreadPoolExecutor(4) as pool:results=list(pool.map(lambda actor:reconcile(pending,actor=actor),actors))
  assert all(x['status']=='PAID' and x['version']==3 for x in results);check('concurrent real HTTP reconciliation applies one provider event')
 req(staff,'/auth/logout',{},staff=True)
Path(a.output).write_text(json.dumps(dict(transport='production JAR loopback HTTP',phase=a.phase,passed=len(checks),checks=checks,realPayments=False),indent=2)+'\n');print(f'Payment HTTP {a.phase}: {len(checks)} checks passed')
