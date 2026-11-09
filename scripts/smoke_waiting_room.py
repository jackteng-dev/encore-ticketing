#!/usr/bin/env python3
"""Synthetic waiting-room and operator-controlled Redis outage verification on loopback."""
import argparse,json,uuid,time,os,urllib.request,urllib.parse,urllib.error,http.cookiejar
from datetime import datetime,timedelta,timezone
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--env',default='work/runtime.env');p.add_argument('--base-url',default='http://127.0.0.1:19110/api');p.add_argument('--output',default='work/http-waiting-room.json');p.add_argument('--fixture',default='work/waiting-fault.json');p.add_argument('--phase',choices=['normal','prepare-fault','while-down','after-restart','state-lost'],default='normal');a=p.parse_args();assert urllib.parse.urlparse(a.base_url).hostname in ('localhost','127.0.0.1')
cfg=dict(l.split('=',1) for l in Path(a.env).read_text().splitlines() if l and not l.startswith('#'));checks=[]
assert cfg.get('TICKET_ADMISSION_ACTIVE')=='1','Use isolated waiting-room smoke profile: TICKET_ADMISSION_ACTIVE=1'
def client():
 jar=http.cookiejar.CookieJar();return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(jar)),jar
def req(c,path,body=None,expected=200,staff=False,csrf=True):
 h={};data=None
 if body is not None:
  if csrf:
   token=req(c,'/auth/csrf' if staff else '/customer/auth/csrf')[0];h[token['headerName']]=token['token']
  form=path=='/auth/login';h['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json';data=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
 try:
  with c.open(urllib.request.Request(a.base_url+path,data=data,headers=h),timeout=20) as r:code=r.status;raw=r.read();headers=r.headers
 except urllib.error.HTTPError as e:code=e.code;raw=e.read();headers=e.headers
 assert code in (expected if isinstance(expected,tuple) else (expected,)),f'{path}: expected {expected}, got {code}'
 return json.loads(raw),code,headers
staff,_=client();c,jar=client();other,_=client();third,_=client();public,_=client()
def register(actor):return req(actor,'/customer/auth/register',dict(handle='queue-'+uuid.uuid4().hex,displayName='合成顧客',password=uuid.uuid4().hex))[0]
def command(**kw):return {'requestId':str(uuid.uuid4()),'expectedVersion':0,**kw}
def ticket():
 e=req(staff,'/admin/events',command(slug='waiting-'+uuid.uuid4().hex,title='等候室HTTP演出',description='合成展示',venue='隔離場地'),staff=True)[0];now=datetime.now(timezone.utc)
 s=req(staff,'/admin/events/'+e['id']+'/sessions',command(name='合成場次',startsAt=(now+timedelta(days=2)).isoformat(),salesStart=(now-timedelta(minutes=1)).isoformat(),salesEnd=(now+timedelta(hours=1)).isoformat()),staff=True)[0];t=req(staff,'/admin/sessions/'+s['id']+'/tickets',command(name='一般票',priceTwd=1200,capacity=10,maxPerCustomer=2),staff=True)[0];req(staff,'/admin/events/'+e['id']+'/publish',command(expectedVersion=1),staff=True);return e,t
room=lambda t:'/customer/waiting-room/'+t['id']
place=lambda t:dict(requestId=str(uuid.uuid4()),ticketId=t['id'],quantity=1,expectedTicketVersion=1,expectedTotalTwd=1200)
join=lambda actor,t,body=None:req(actor,room(t)+'/join',body or dict(requestId=str(uuid.uuid4())))[0]
check=lambda n:checks.append(n)
for _ in range(120):
 try:req(public,'/public/events');break
 except OSError:time.sleep(.5)
else:raise RuntimeError('API not ready')
if a.phase in ['while-down','after-restart','state-lost']:
 f=json.loads(Path(a.fixture).read_text());jar.set_cookie(http.cookiejar.Cookie(0,'TICKET_CUSTOMER_SESSION',f['token'],None,False,'127.0.0.1',False,False,'/api/customer',True,False,None,True,None,None,{}));t=f['ticket'];o=f['order']
 if a.phase=='while-down':
  req(c,room(t),expected=503);check('actual stopped Redis makes waiting-room unavailable')
  req(c,'/customer/orders',f['newBody'],expected=503);check('actual stopped Redis rejects fresh hold without fail-open fallback')
  assert req(c,'/customer/orders',f['body'])[0]['id']==o['id'];check('known original PLACE replay succeeds while Redis is down')
  assert req(c,'/customer/orders/'+o['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=o['version']))[0]['status']=='CANCELLED';check('cancel and stock release work while Redis is down')
  started=f['payment'];paid=req(c,'/customer/orders/'+started['id']+'/payments/'+started['payment']['id']+'/reconcile',dict(requestId=str(uuid.uuid4())))[0];assert paid['status']=='PAID';check('existing payment reconciliation works while Redis is down')
  req(c,'/customer/orders');check('owned order history works while Redis is down')
 elif a.phase=='after-restart':
  assert req(c,room(t))[0]['state']=='EXPIRED';check('AOF restart after original Redis deadline does not extend admission')
  assert join(c,t,f['join'])['state']=='EXPIRED';check('original queue UUID remains expired after actual Redis restart')
  req(c,'/customer/orders',f['newBody'],expected=409);check('unknown fresh PLACE cannot bypass expired restored qualification')
  assert req(c,'/customer/orders',f['body'])[0]['status']=='CANCELLED';check('original committed PG order remains cancelled after Redis restart')
 else:
  assert req(c,room(t))[0]['state']=='NOT_JOINED';check('loss of only fixture room keys requires explicit join and does not auto-grant')
  req(c,'/customer/orders',f['newBody'],expected=409);check('lost room state rejects fresh hold without changing PG inventory')
  assert req(c,'/customer/orders',f['body'])[0]['status']=='CANCELLED';check('PG command history survives complete fixture room state loss')
else:
 req(staff,'/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']),staff=True);register(c);e,t=ticket();body=place(t);j=dict(requestId=str(uuid.uuid4()))
 if a.phase=='prepare-fault':
  state=join(c,t,j);o=req(c,'/customer/orders',body)[0];_,second=ticket();join(c,second);pending=req(c,'/customer/orders',place(second))[0];payment=req(c,'/customer/orders/'+pending['id']+'/payments',dict(requestId=str(uuid.uuid4()),expectedVersion=1,expectedTotalTwd=1200,scenario='SUCCESS'))[0]
  fd=os.open(a.fixture,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
  with os.fdopen(fd,'w') as out:json.dump(dict(ticket=t,order=o,body=body,newBody=place(t),join=j,deadline=state['expiresAt'],payment=payment,token=next(k.value for k in jar if k.name=='TICKET_CUSTOMER_SESSION')),out)
  check('live admission, original order and unresolved mock payment stored privately before Redis fault')
 else:
  register(other);register(third);req(public,room(t),expected=401);check('anonymous queue read denied')
  req(c,room(t)+'/join',j,expected=403,csrf=False);req(c,room(t)+'/join',{**j,'customerId':str(uuid.uuid4())},expected=400);check('queue join requires customer CSRF and rejects owner spoof')
  assert req(c,room(t))[0]['state']=='NOT_JOINED';req(c,'/customer/orders',body,expected=409);check('poll without explicit join does not grant or reserve')
  first=join(c,t,j);assert first['state']=='ADMITTED';assert join(c,t,j)['expiresAt']==first['expiresAt'];check('first member admitted and original UUID never extends deadline')
  o=req(c,'/customer/orders',body)[0];check('qualified customer can reserve through authoritative PG')
  assert join(other,t)['position']==1 and join(third,t)['position']==2;check('other members wait in accepted FIFO order')
  req(other,'/customer/orders',place(t),expected=409);check('waiting customer cannot bypass admission')
  _,unqualified=ticket();req(c,'/customer/orders',place(unqualified),expected=409);check('qualification cannot cross ticket rooms')
  while time.time()*1000<=first['expiresAt']+100:
   req(other,room(t));req(third,room(t));time.sleep(2)
  assert req(other,room(t))[0]['state']=='ADMITTED' and req(third,room(t))[0]['state']=='WAITING';check('real fixed deadline promotes oldest live waiting member')
  assert join(c,t,j)['state']=='EXPIRED';req(c,'/customer/orders',place(t),expected=409);check('expired original admission cannot renew or create a fresh hold')
  assert req(c,'/customer/orders',body)[0]['id']==o['id'];check('committed PLACE replay works after admission expiry')
  assert join(c,t)['state']=='WAITING';check('explicit new join returns to queue tail')
  # Burst the third member only; never silently retry or bypass Retry-After.
  for _ in range(40):
   _,code,h=req(third,room(t),expected=(200,429))
   if code==429:assert int(h['Retry-After'])>0;break
  else:raise AssertionError('Per-owner queue limiter did not activate')
  check('real owner token bucket returns 429 with Retry-After')
 req(staff,'/auth/logout',{},staff=True)
Path(a.output).write_text(json.dumps(dict(transport='production JAR loopback HTTP + real Redis',phase=a.phase,passed=len(checks),checks=checks,redisInventory=False,realPayments=False),indent=2)+'\n');print(f'Waiting room HTTP {a.phase}: {len(checks)} checks passed')
