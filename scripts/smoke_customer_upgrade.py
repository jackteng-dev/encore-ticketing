#!/usr/bin/env python3
"""Seed a retained baseline JAR, then compare catalog and audit after migration."""
import argparse,json,uuid,time,urllib.request,urllib.parse,http.cookiejar
from datetime import datetime,timedelta,timezone
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('phase',choices=['seed','verify'])
p.add_argument('--env',default='work/runtime.env');p.add_argument('--base-url',default='http://127.0.0.1:19110/api')
p.add_argument('--fixture',default='work/upgrade-v1.json');p.add_argument('--output',default='work/customer-upgrade.json');a=p.parse_args()
assert urllib.parse.urlparse(a.base_url).hostname in ('localhost','127.0.0.1'),'Loopback only'
cfg=dict(l.split('=',1) for l in Path(a.env).read_text().splitlines() if l and not l.startswith('#'))
c=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def req(path,body=None,form=False):
 h={};data=None
 if body is not None:
  csrf=req('/auth/csrf');h[csrf['headerName']]=csrf['token'];h['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
  data=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
 with c.open(urllib.request.Request(a.base_url+path,data=data,headers=h),timeout=10) as r:return json.load(r)
for _ in range(30):
 try:req('/public/events');break
 except OSError:time.sleep(.5)
else:raise RuntimeError('API not ready')
req('/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']),True)
try:
 def command(**kw):return {'requestId':str(uuid.uuid4()),'expectedVersion':0,**kw}
 if a.phase=='seed':
  e=req('/admin/events',command(slug='upgrade-'+uuid.uuid4().hex,title='V1升級保留演出',description='合成資料',venue='隔離場地'))
  now=datetime.now(timezone.utc)
  s=req('/admin/events/'+e['id']+'/sessions',command(name='升級晚場',startsAt=(now+timedelta(days=5)).isoformat(),salesStart=(now+timedelta(days=1)).isoformat(),salesEnd=(now+timedelta(days=4)).isoformat()))
  t=req('/admin/sessions/'+s['id']+'/tickets',command(name='升級票種',priceTwd=1800,capacity=10,maxPerCustomer=2))
  req('/admin/events/'+e['id']+'/publish',command(expectedVersion=1))
  ids={e['id'],s['id'],t['id']};audit=[x for x in req('/admin/audit')['items'] if x['resourceId'] in ids];assert len(audit)==4
  Path(a.fixture).write_text(json.dumps(dict(detail=req('/public/events/'+e['id']),audit=audit),ensure_ascii=False,indent=2)+'\n')
  print('Retained JAR upgrade fixture seeded: published event, session, ticket and four audit records.')
 else:
  saved=json.loads(Path(a.fixture).read_text());had_availability=all('available' in t for s in saved['detail']['sessions'] for t in s['tickets']);current=req('/public/events/'+saved['detail']['event']['id'])
  new_waiting_flag=current.get('waitingRoomEnabled',False)
  if 'waitingRoomEnabled' not in saved['detail']:
   assert current.pop('waitingRoomEnabled',False) is False,'Verify legacy upgrade with new admission flag disabled'
  for session,original_session in zip(current['sessions'],saved['detail']['sessions'],strict=True):
   for ticket,original_ticket in zip(session['tickets'],original_session['tickets'],strict=True):
    expected=original_ticket.pop('available',original_ticket['capacity'])
    assert ticket.pop('available',ticket['capacity'])==expected
  assert current==saved['detail']
  wanted={x['requestId'] for x in saved['audit']};found=[];page=1
  while True:
   data=req('/admin/audit?page='+str(page)+'&size=20');found.extend(x for x in data['items'] if x['requestId'] in wanted)
   if page*20>=data['total']:break
   page+=1
  assert sorted(found,key=lambda x:x['requestId'])==sorted(saved['audit'],key=lambda x:x['requestId'])
  Path(a.output).write_text(json.dumps(dict(legacyCatalogProjectionPreserved=True,availableFieldInitializedToCapacity=not had_availability,availabilityPreserved=had_availability,catalogAuditRecordsPreserved=len(found),staffLoginStillValid=True,orderingEnabled=current['orderingEnabled'],waitingRoomEnabled=new_waiting_flag),indent=2)+'\n')
  print('Actual retained-JAR upgrade verified: legacy projection, availability, four audit records and staff login retained.')
finally:req('/auth/logout',{})
