#!/usr/bin/env python3
"""Synthetic loopback reservation HTTP and actual restart/expiry recovery checks."""
import argparse,json,uuid,time,os,urllib.request,urllib.parse,urllib.error,http.cookiejar
from datetime import datetime,timedelta,timezone
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--env',default='work/runtime.env');p.add_argument('--base-url',default='http://127.0.0.1:19110/api');p.add_argument('--output',default='work/http-orders.json');p.add_argument('--phase',choices=['normal','prepare-restart','verify-restart'],default='normal');p.add_argument('--fixture',default='work/order-restart.json');a=p.parse_args()
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
def check(name):checks.append(name)
def command(**kw):return {'requestId':str(uuid.uuid4()),'expectedVersion':0,**kw}
staff,_=client();c,jar=client();other,_=client();public,_=client()
def customer(c):return req(c,'/customer/auth/register',dict(handle='order-'+uuid.uuid4().hex,displayName='合成顧客',password=uuid.uuid4().hex))[0]
def event(capacity=3,limit=2,seconds=3600):
 e=req(staff,'/admin/events',command(slug='orders-http-'+uuid.uuid4().hex,title='HTTP保票演出',description='合成展示，不收款',venue='合成場地'),staff=True)[0]
 now=datetime.now(timezone.utc);end=now+timedelta(seconds=seconds)
 s=req(staff,'/admin/events/'+e['id']+'/sessions',command(name='合成晚場',startsAt=(end+timedelta(days=1)).isoformat(),salesStart=(now-timedelta(minutes=1)).isoformat(),salesEnd=end.isoformat()),staff=True)[0]
 t=req(staff,'/admin/sessions/'+s['id']+'/tickets',command(name='一般票',priceTwd=1200,capacity=capacity,maxPerCustomer=limit),staff=True)[0]
 req(staff,'/admin/events/'+e['id']+'/publish',command(expectedVersion=1),staff=True)
 return e,t
def place(t,q=1):return dict(requestId=str(uuid.uuid4()),ticketId=t['id'],quantity=q,expectedTicketVersion=t['version'],expectedTotalTwd=1200*q)
if a.phase=='verify-restart':
 f=json.loads(Path(a.fixture).read_text());jar.set_cookie(http.cookiejar.Cookie(0,'TICKET_CUSTOMER_SESSION',f['token'],None,False,urllib.parse.urlparse(a.base_url).hostname,False,False,'/api/customer',True,False,None,True,None,None,{}))
 for _ in range(40):
  o=req(c,'/customer/orders/'+f['order']['id'])[0]
  if o['status']=='EXPIRED':break
  time.sleep(.5)
 else:raise AssertionError('Overdue hold was not recovered after JAR restart')
 check('actual JAR outage across deadline and scheduled restart recovery')
 assert req(c,'/customer/orders',f['body'])[0]['status']=='EXPIRED';check('original request replay after restart returns expired order without new hold')
 req(c,'/customer/orders',{**f['body'],'requestId':str(uuid.uuid4())},expected=409);check('disabled placement flag rejects new hold while recovery remains active')
 d=req(public,'/public/events/'+f['event']['id'])[0];assert d['sessions'][0]['tickets'][0]['available']==1;check('restart recovery restores public ticket availability')
else:
 req(staff,'/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']),staff=True);customer(c)
 if a.phase=='prepare-restart':
  e,t=event(1,1,15);body=place(t);o=req(c,'/customer/orders',body)[0]
  assert datetime.now(timezone.utc)<datetime.fromisoformat(o['expiresAt']);token=next(k.value for k in jar if k.name=='TICKET_CUSTOMER_SESSION')
  fd=os.open(a.fixture,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
  with os.fdopen(fd,'w') as out:json.dump(dict(event=e,order=o,body=body,token=token),out)
  check('live hold and original command stored privately before stopping API')
 else:
  customer(other);e,t=event();body=place(t)
  req(public,'/customer/orders',expected=401);check('anonymous orders denied')
  req(c,'/customer/orders',body,expected=403,csrf=False);check('reservation requires customer CSRF')
  req(c,'/customer/orders',{**body,'customerId':str(uuid.uuid4())},expected=400);check('owner spoof field denied')
  req(c,'/customer/orders',{**body,'expectedTotalTwd':1},expected=409);check('server quote rejects client total')
  req(c,'/customer/orders',{**body,'quantity':0},expected=400);check('invalid quantity rejected')
  o=req(c,'/customer/orders',body)[0];assert o['totalTwd']==1200 and o['status']=='PENDING_PAYMENT';check('real hold and immutable priced order snapshot')
  assert req(c,'/customer/orders',body)[0]==o;check('same original command replays once')
  req(c,'/customer/orders',{**body,'quantity':2,'expectedTotalTwd':2400},expected=409);check('same request changed body rejected')
  d=req(public,'/public/events/'+e['id'])[0];assert d['orderingEnabled'] and d['sessions'][0]['tickets'][0]['available']==2;check('public remaining ticket availability reflects hold')
  req(other,'/customer/orders/'+o['id'],expected=404);req(other,'/customer/orders/'+o['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=1),expected=404);check('other customer cannot read or cancel order')
  req(c,'/customer/orders',place(t,2),expected=409);check('limit includes holds across orders')
  req(c,'/customer/orders/'+o['id']+'/cancel',dict(requestId=body['requestId'],expectedVersion=1),expected=409);check('request ID cannot cross place and cancel actions')
  cancel=dict(requestId=str(uuid.uuid4()),expectedVersion=1);closed=req(c,'/customer/orders/'+o['id']+'/cancel',cancel)[0];assert closed['status']=='CANCELLED';check('cancel releases held ticket')
  assert req(c,'/customer/orders/'+o['id']+'/cancel',cancel)[0]==closed;assert req(c,'/customer/orders',body)[0]==closed;check('cancel and place replay do not revive order')
  req(staff,'/admin/events/'+e['id']+'/archive',command(expectedVersion=2),staff=True);assert req(c,'/customer/orders/'+o['id'])[0]['eventTitle']=='HTTP保票演出';check('own history survives catalog archive')
  e,t=event(1,1);race=place(t)
  with ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda actor:req(actor,'/customer/orders',race,expected=(200,409)),[c,other]))
  assert sorted(code for _,code in results)==[200,409];check('last ticket over actual concurrent HTTP has one winner')
  winner=c if results[0][1]==200 else other;win=next(value for value,code in results if code==200);req(winner,'/customer/orders/'+win['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=1))
  e,t=event(1,1,6);expired=req(c,'/customer/orders',place(t))[0]
  for _ in range(30):
   current=req(c,'/customer/orders/'+expired['id'])[0]
   if current['status']=='EXPIRED':break
   time.sleep(.5)
  else:raise AssertionError('Scheduled real-time expiry failed')
  assert req(public,'/public/events/'+e['id'])[0]['sessions'][0]['tickets'][0]['available']==1;check('real sales deadline scheduler expires and releases exactly once')
  req(c,'/customer/orders?size=100',expected=400);check('own order pagination is bounded')
 req(staff,'/auth/logout',{},staff=True)
Path(a.output).write_text(json.dumps(dict(transport='real loopback JAR HTTP',phase=a.phase,passed=len(checks),checks=checks,realPayments=False),indent=2)+'\n')
print(f'Reservation HTTP {a.phase}: {len(checks)} checks passed')
