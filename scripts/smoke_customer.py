#!/usr/bin/env python3
"""Real loopback HTTP identity checks. Synthetic secrets remain private under work/."""
import argparse,json,uuid,urllib.request,urllib.parse,urllib.error,http.cookiejar,os
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('--env',default='work/runtime.env')
parser.add_argument('--base-url',default='http://127.0.0.1:19110/api')
parser.add_argument('--output',default='work/http-customer.json')
parser.add_argument('--restart-fixture',default='work/customer-restart.json')
parser.add_argument('--verify-restart',action='store_true')
args=parser.parse_args()
assert urllib.parse.urlparse(args.base_url).hostname in ('127.0.0.1','localhost'), 'Loopback only'
cfg=dict(l.split('=',1) for l in Path(args.env).read_text().splitlines() if l and not l.startswith('#'))
checks=[]
def client():
 jar=http.cookiejar.CookieJar()
 return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(jar)),jar
def request(c,path,body=None,expected=200,csrf=True,staff=False,extra=None):
 headers=extra or {};data=None
 if body is not None:
  if csrf:
   t=request(c,'/auth/csrf' if staff else '/customer/auth/csrf');headers[t['headerName']]=t['token']
  headers['Content-Type']='application/x-www-form-urlencoded' if staff and path=='/auth/login' else 'application/json'
  data=(urllib.parse.urlencode(body) if headers['Content-Type'].endswith('urlencoded') else json.dumps(body)).encode()
 try:
  with c.open(urllib.request.Request(args.base_url+path,data=data,headers=headers),timeout=10) as r:status=r.status;raw=r.read();response_headers=r.headers
 except urllib.error.HTTPError as e:status=e.code;raw=e.read();response_headers=e.headers
 assert status==expected,f'{path}: expected {expected}, got {status}'
 return json.loads(raw) if raw else None
def check(name):checks.append(name)
c,jar=client();anonymous,_=client()
if args.verify_restart:
 fixture=json.loads(Path(args.restart_fixture).read_text())
 jar.set_cookie(http.cookiejar.Cookie(0,'TICKET_CUSTOMER_SESSION',fixture['token'],None,False,
  urllib.parse.urlparse(args.base_url).hostname,False,False,'/api/customer',True,False,None,True,None,None,{}))
 assert request(c,'/customer/auth/me')['id']==fixture['profile']['id'];check('database customer session survives actual JAR restart')
 request(c,'/customer/auth/logout',{})
 request(c,'/customer/auth/me',expected=401);check('persisted session logout revokes original token')
else:
 request(anonymous,'/customer/auth/me',expected=401);check('anonymous customer access rejected')
 handle='http-'+uuid.uuid4().hex;password=uuid.uuid4().hex;body=dict(handle=handle,displayName='<script>not executable</script>',password=password)
 for path in ('register','login','logout'):request(c,'/customer/auth/'+path,{},expected=403,csrf=False)
 check('registration login logout require customer CSRF')
 staffcsrf=request(c,'/auth/csrf')
 request(c,'/customer/auth/register',body,expected=403,csrf=False,extra={staffcsrf['headerName']:staffcsrf['token']});check('staff CSRF cannot authorize customer write')
 request(c,'/customer/auth/register',{**body,'customerId':str(uuid.uuid4())},expected=400);check('unknown customer owner field rejected')
 request(c,'/customer/auth/register',{**body,'password':'密'*25},expected=400);check('bcrypt UTF-8 byte bound enforced')
 profile=request(c,'/customer/auth/register',body);assert profile['handle']==handle and set(profile)=={'id','handle','displayName'};check('registration creates safe profile and authenticates')
 token=next(cookie.value for cookie in jar if cookie.name=='TICKET_CUSTOMER_SESSION')
 cookie=next(cookie for cookie in jar if cookie.name=='TICKET_CUSTOMER_SESSION');assert cookie.path=='/api/customer' and 'HttpOnly' in cookie._rest and cookie._rest.get('SameSite')=='Strict';check('customer cookie HttpOnly Strict and scoped path')
 assert request(c,'/customer/auth/me')==profile;check('authenticated profile')
 request(c,'/admin/events',expected=401);request(c,'/auth/me',expected=401);check('customer cannot access staff identity or admin')
 request(c,'/customer/auth/register',body,expected=409);check('duplicate handle registration rejected')
 unknown=request(c,'/customer/auth/login',dict(handle='unknown-'+uuid.uuid4().hex,password=password),expected=401)
 known=request(c,'/customer/auth/login',dict(handle=handle,password='wrong-'+uuid.uuid4().hex),expected=401)
 assert known==unknown;check('known and unknown invalid login give same response')
 request(c,'/customer/auth/login',dict(handle=handle,password=password));newtoken=next(k.value for k in jar if k.name=='TICKET_CUSTOMER_SESSION');assert newtoken!=token
 request(anonymous,'/customer/auth/me',expected=401,extra={'Cookie':'TICKET_CUSTOMER_SESSION='+token});check('successful login rotates and revokes previous browser token')
 request(c,'/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']),staff=True)
 assert request(c,'/auth/me')['canWrite'] and request(c,'/customer/auth/me')==profile;check('staff and customer coexist in one cookie jar')
 request(c,'/auth/logout',{},staff=True);assert request(c,'/customer/auth/me')==profile;request(c,'/auth/me',expected=401);check('staff logout preserves customer session')
 request(c,'/auth/login',dict(username=cfg['TICKET_STAFF_USERNAME'],password=cfg['TICKET_STAFF_PASSWORD']),staff=True)
 request(c,'/customer/auth/logout',{});request(c,'/customer/auth/me',expected=401);assert request(c,'/auth/me')['canWrite'];check('customer logout preserves staff session')
 request(c,'/customer/auth/logout',{});check('anonymous logout retry safely succeeds')
 request(c,'/auth/logout',{},staff=True)
 profile=request(c,'/customer/auth/login',dict(handle=handle,password=password))
 token=next(cookie.value for cookie in jar if cookie.name=='TICKET_CUSTOMER_SESSION')
 target=Path(args.restart_fixture);target.parent.mkdir(parents=True,exist_ok=True)
 fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
 with os.fdopen(fd,'w') as f:json.dump(dict(token=token,profile=profile),f)
 check('private persistence fixture saved for separate JAR restart verification')
Path(args.output).write_text(json.dumps(dict(transport='real loopback HTTP',passed=len(checks),checks=checks),ensure_ascii=False,indent=2)+'\n')
print(f'Customer HTTP checks passed: {len(checks)}')
