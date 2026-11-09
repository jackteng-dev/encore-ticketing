#!/usr/bin/env python3
"""Production HTTP proof around operator-controlled API/worker/RabbitMQ faults.

Only loopback synthetic data. Prepare saves an original UUID/body/session privately;
verification never patches deadlines and never exposes processing endpoints.
"""
import argparse,datetime,http.cookiejar,json,os,time,urllib.error,urllib.parse,urllib.request,uuid
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
p=argparse.ArgumentParser();p.add_argument('phase',choices=['prepare','prepare-cancelled','verify-reserved','verify-expired','verify-cancelled'])
p.add_argument('--env',default='work/runtime.env');p.add_argument('--fixture',default='work/delivery-private.json');p.add_argument('--output',default='work/delivery-http.json');p.add_argument('--base-url',default='http://127.0.0.1:19110/api');a=p.parse_args()
root=Path(__file__).resolve().parents[1]
assert urllib.parse.urlparse(a.base_url).hostname in ('127.0.0.1','localhost'),'Loopback synthetic proof only'
fixture=Path(a.fixture).resolve();assert fixture.is_relative_to(root/'work'),'Private fixture must remain in repository work/'
cfg=dict(l.split('=',1) for l in Path(a.env).read_text().splitlines() if l and not l.startswith('#'));checks=[]
def client(token=None):
 jar=http.cookiejar.CookieJar()
 if token:jar.set_cookie(http.cookiejar.Cookie(0,'TICKET_CUSTOMER_SESSION',token,None,False,'127.0.0.1',False,False,'/api/customer',True,False,None,True,None,None,{}))
 return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(jar)),jar
public,_=client();staff,_=client();c,jar=client()
def req(op,path,body=None,expected=(200,),staff_login=False):
 headers={};data=None
 if body is not None:
  csrf=req(op,'/auth/csrf' if staff_login else '/customer/auth/csrf')[0];headers[csrf['headerName']]=csrf['token']
  form=staff_login and path=='/auth/login'
  data=urllib.parse.urlencode(body).encode() if form else json.dumps(body).encode();headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
 try:r=op.open(urllib.request.Request(a.base_url+path,data=data,headers=headers),timeout=10)
 except urllib.error.HTTPError as e:r=e
 with r:
  assert r.status in expected,f'{path}: status {r.status}, wanted {expected}'
  return json.load(r),r.headers
def check(text):checks.append(text)
def register(op):req(op,'/customer/auth/register',dict(handle='dq-'+uuid.uuid4().hex[:28],displayName='合成傳遞會員',password=uuid.uuid4().hex))
if a.phase.startswith('prepare'):
 req(staff,'/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']),staff_login=True)
 register(c);now=datetime.datetime.now(datetime.timezone.utc)
 def command(**kw):return {"requestId":str(uuid.uuid4()),"expectedVersion":0,**kw}
 e=req(staff,'/admin/events',command(slug='delivery-'+uuid.uuid4().hex,title='合成 MQ 演出',description='隔離故障測試',venue='隔離場地'),staff_login=True)[0]
 s=req(staff,'/admin/events/'+e['id']+'/sessions',command(name='合成場次',startsAt=(now+datetime.timedelta(days=2)).isoformat(),salesStart=(now-datetime.timedelta(hours=1)).isoformat(),salesEnd=(now+datetime.timedelta(hours=1)).isoformat()),staff_login=True)[0]
 t=req(staff,'/admin/sessions/'+s['id']+'/tickets',command(name='合成票',priceTwd=1200,capacity=2,maxPerCustomer=2),staff_login=True)[0]
 req(staff,'/admin/events/'+e['id']+'/publish',command(expectedVersion=1),staff_login=True)
 b=dict(requestId=str(uuid.uuid4()),ticketId=t['id'],quantity=1,expectedTicketVersion=1,expectedTotalTwd=1200)
 receipt,h=req(c,'/customer/purchases',b,expected=(202,));assert receipt['status']=='PROCESSING' and receipt['orderId'] is None and h['Location'].endswith(receipt['id']);check('real production API accepts 202 with owned durable receipt before worker processing')
 assert req(c,'/customer/orders')[0]['total']==0;assert req(public,'/public/events/'+e['id'])[0]['sessions'][0]['tickets'][0]['available']==2;check('accepted backlog has no stock hold or payment deadline')
 token=next(cookie.value for cookie in jar if cookie.name=='TICKET_CUSTOMER_SESSION')
 def replay(_):return req(client(token)[0],'/customer/purchases',b,expected=(200,202))[0]['id']
 with ThreadPoolExecutor(max_workers=6) as pool:assert set(pool.map(replay,range(6)))=={receipt['id']}
 check('six actual HTTP retries preserve original request UUID and one purchase receipt')
 if a.phase=='prepare-cancelled':
  receipt=req(c,'/customer/purchases/'+receipt['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=1))[0];assert receipt['status']=='CANCELLED';check('owned cancellation commits while durable message is waiting for a worker')
 fd=os.open(fixture,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
 with os.fdopen(fd,'w') as f:json.dump(dict(token=token,event=e,ticket=t,body=b,purchase=receipt,preparedAtUtcEpoch=time.time()),f)
 req(staff,'/auth/logout',{},staff_login=True)
else:
 saved=json.loads(fixture.read_text());c,_=client(saved['token']);original=saved['purchase'];wanted=a.phase.removeprefix('verify-').upper()
 for _ in range(200):
  receipt=req(c,'/customer/purchases/'+original['id'])[0]
  if receipt['status']==wanted:break
  time.sleep(.2)
 assert receipt['status']==wanted,f'Worker result {receipt["status"]}, expected {wanted}'
 check('persistent customer session can read original receipt after operator-controlled service recovery')
 assert req(c,'/customer/purchases',saved['body'])[0]==receipt;check('original HTTP UUID/body replays terminal result without creating another intent')
 available=req(public,'/public/events/'+saved['event']['id'])[0]['sessions'][0]['tickets'][0]['available'];orders=req(c,'/customer/orders')[0]
 if wanted=='RESERVED':
  assert receipt['version']==2 and receipt['orderId'];order=req(c,'/customer/orders/'+receipt['orderId'])[0]
  assert orders['total']==1 and order['requestId']==saved['body']['requestId'] and order['quantity']==1 and order['status']=='PENDING_PAYMENT' and available==1
  created=datetime.datetime.fromisoformat(order['createdAt'].replace('Z','+00:00'));expires=datetime.datetime.fromisoformat(order['expiresAt'].replace('Z','+00:00'));assert expires-created==datetime.timedelta(minutes=10)
  check('actual worker creates one PG order and starts payment deadline at committed reservation time')
  rejected=req(c,'/customer/purchases/'+receipt['id']+'/cancel',dict(requestId=str(uuid.uuid4()),expectedVersion=2),expected=(409,))[0];assert rejected['code']=='ORDER_CREATED';check('purchase receipt cancellation cannot release an existing order implicitly')
 else:
  assert receipt['orderId'] is None and orders['total']==0 and available==2;check('cancelled or expired durable backlog creates no order and leaves authoritative stock unchanged')
  if wanted=='EXPIRED':assert time.time()>=datetime.datetime.fromisoformat(original['deadlineAt'].replace('Z','+00:00')).timestamp();check('actual recovery is after original DB deadline; no fixture deadline was patched')
 other,_=client();register(other);req(other,'/customer/purchases/'+original['id'],expected=(404,));check('another customer cannot read recovered purchase receipt')
Path(a.output).write_text(json.dumps(dict(transport='production JAR HTTP + real PG/RabbitMQ, operator-controlled services',phase=a.phase,passed=len(checks),checks=checks,realPayments=False),indent=2)+'\n')
print(f'Delivery HTTP {a.phase}: {len(checks)} checks passed')
