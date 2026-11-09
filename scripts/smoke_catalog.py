#!/usr/bin/env python3
"""Only synthetic data against a loopback API; uses private work/runtime.env."""
import argparse,json,uuid,urllib.request,urllib.error,http.cookiejar
from datetime import datetime,timedelta,timezone
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--base-url',default='http://127.0.0.1:19110/api');parser.add_argument('--env',default='work/runtime.env');parser.add_argument('--output',default='work/http-catalog.json');args=parser.parse_args()
assert urllib.parse.urlparse(args.base_url).hostname in ('127.0.0.1','localhost'), 'Loopback only'
config=dict(line.split('=',1) for line in Path(args.env).read_text().splitlines() if line and not line.startswith('#'))
def client():return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
staff=client();public=client();checks=[];owned=[]
def req(c,path,method='GET',body=None,expected=200,csrf=True,form=False):
 headers={};data=None
 if method!='GET' and csrf:
  token=req(c,'/auth/csrf');headers[token['headerName']]=token['token']
 if body is not None:
  headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
  data=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
 try:
  with c.open(urllib.request.Request(args.base_url+path,data=data,headers=headers,method=method),timeout=10) as r:code=r.status;raw=r.read()
 except urllib.error.HTTPError as e:code=e.code;raw=e.read()
 assert code==expected,f'{method} {path}: expected {expected}, got {code}'
 return json.loads(raw) if raw else None
def check(name):checks.append(name)
def command(**kw):return {'requestId':str(uuid.uuid4()),'expectedVersion':0,**kw}
try:
 req(public,'/admin/events',expected=401);check('anonymous staff endpoint rejected')
 req(staff,'/auth/login','POST',{'username':config['TICKET_STAFF_USERNAME'],'password':'wrong-private-password'},expected=401,form=True);check('invalid credentials rejected')
 req(staff,'/auth/login','POST',{'username':config['TICKET_STAFF_USERNAME'],'password':config['TICKET_STAFF_PASSWORD']},form=True)
 assert req(staff,'/auth/me')['canWrite'];check('real session login and manager capability')
 body=command(slug='smoke-'+uuid.uuid4().hex,title='HTTP合成演出',description='<script>not executable</script>',venue='合成場地')
 req(staff,'/admin/events','POST',body,expected=403,csrf=False);check('write without csrf rejected')
 e=req(staff,'/admin/events','POST',body);owned.append(e['id']);check('event create')
 assert req(staff,'/admin/events','POST',body)==e;check('same command replay same snapshot')
 req(staff,'/admin/events','POST',{**body,'title':'different'},expected=409);check('command content conflict')
 req(staff,'/admin/events','POST',{**command(),**{k:v for k,v in body.items() if k not in ('requestId','expectedVersion')},'actorId':'spoof'},expected=400);check('unknown actor field rejected')
 req(public,'/public/events/'+e['id'],expected=404);check('draft hidden publicly')
 req(staff,'/admin/events/'+e['id']+'/publish','POST',command(expectedVersion=1),expected=409);check('incomplete publish rejected')
 updated=req(staff,'/admin/events/'+e['id'],'PUT',{**body,'requestId':str(uuid.uuid4()),'expectedVersion':1,'title':'HTTP更新演出'})
 assert updated['version']==2;check('versioned event update')
 req(staff,'/admin/events/'+e['id'],'PUT',{**body,'requestId':str(uuid.uuid4()),'expectedVersion':1},expected=409);check('stale event rejected')
 now=datetime.now(timezone.utc)
 sessionBody=command(name='合成晚場',startsAt=(now+timedelta(days=4)).isoformat(),salesStart=(now+timedelta(days=1)).isoformat(),salesEnd=(now+timedelta(days=3)).isoformat())
 s=req(staff,'/admin/events/'+e['id']+'/sessions','POST',sessionBody);check('session create')
 req(staff,'/admin/events/'+e['id']+'/sessions','POST',{**sessionBody,'requestId':str(uuid.uuid4()),'salesEnd':sessionBody['salesStart']},expected=400);check('invalid sales interval rejected')
 t=req(staff,'/admin/sessions/'+s['id']+'/tickets','POST',command(name='一般票',priceTwd=1200,capacity=50,maxPerCustomer=2));check('ticket type create')
 req(staff,'/admin/tickets/'+t['id'],'PUT',command(expectedVersion=1,name='無效票',priceTwd=0,capacity=50,maxPerCustomer=2),expected=400);check('invalid price rejected')
 s=req(staff,'/admin/sessions/'+s['id'],'PUT',{**sessionBody,'requestId':str(uuid.uuid4()),'expectedVersion':1,'name':'更新場次'});assert s['version']==2;check('session update')
 t=req(staff,'/admin/tickets/'+t['id'],'PUT',command(expectedVersion=1,name='更新票種',priceTwd=1500,capacity=60,maxPerCustomer=3));assert t['version']==2;check('ticket update')
 e=req(staff,'/admin/events/'+e['id']+'/publish','POST',command(expectedVersion=2));check('publish')
 detail=req(public,'/public/events/'+e['id']);assert not detail['orderingEnabled'];assert detail['sessions'][0]['tickets'][0]['priceTwd']==1500;check('public detail real price and ordering disabled')
 assert not any(k in json.dumps(detail) for k in ('password_hash','fingerprint','actor_id'));check('public projection no staff secrets')
 req(staff,'/admin/tickets/'+t['id'],'PUT',command(expectedVersion=2,name='改價',priceTwd=1700,capacity=60,maxPerCustomer=3),expected=409);check('published conditions immutable')
 req(public,'/public/events?size=100',expected=400);check('bounded pagination')
 assert req(staff,'/admin/audit')['total']>0;check('durable audit read')
 req(staff,'/admin/events/'+e['id']+'/archive','POST',command(expectedVersion=3));check('archive')
 req(public,'/public/events/'+e['id'],expected=404);check('archived hidden publicly')
 req(staff,'/auth/logout','POST',{});req(staff,'/auth/me',expected=401);check('logout revokes session')
finally:
 # No deletes/audit cleanup; archive only own fixture if a preceding assertion failed.
 try:
  req(staff,'/auth/login','POST',{'username':config['TICKET_STAFF_USERNAME'],'password':config['TICKET_STAFF_PASSWORD']},form=True)
  for id in owned:
   e=req(staff,'/admin/events/'+id)['event']
   if e['status']!='ARCHIVED':req(staff,'/admin/events/'+id+'/archive','POST',command(expectedVersion=e['version']))
  req(staff,'/auth/logout','POST',{})
 except Exception: pass
Path(args.output).write_text(json.dumps({'transport':'real loopback HTTP','passed':len(checks),'checks':checks,'ownFixturesArchived':len(owned),'realPayment':False},ensure_ascii=False,indent=2)+'\n')
print(f'HTTP catalog checks passed: {len(checks)}')
