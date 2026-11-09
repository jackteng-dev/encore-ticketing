#!/usr/bin/env python3
"""Verify Secure=true response flags on loopback. This is not a TLS deployment test."""
import argparse,json,uuid,urllib.request,urllib.parse,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:19111/api');p.add_argument('--output',default='work/customer-cookie.json');a=p.parse_args()
assert urllib.parse.urlparse(a.base_url).hostname in ('localhost','127.0.0.1'),'Loopback only'
c=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def csrf():
 with c.open(a.base_url+'/customer/auth/csrf',timeout=5) as r:return json.load(r),r.headers.get_all('Set-Cookie')
for _ in range(30):
 try:token,cookies=csrf();break
 except OSError:time.sleep(.5)
else:raise RuntimeError('Secure-config API not ready')
assert len(cookies)==1 and all(k in cookies[0] for k in ['Secure','HttpOnly','SameSite=Strict','Path=/api/customer'])
headers={'Content-Type':'application/json',token['headerName']:token['token'],'Cookie':cookies[0].split(';',1)[0]}
body={'handle':'secure-'+uuid.uuid4().hex,'displayName':'合成顧客','password':uuid.uuid4().hex}
with c.open(urllib.request.Request(a.base_url+'/customer/auth/register',data=json.dumps(body).encode(),headers=headers)) as r:
 json.load(r);cookies=r.headers.get_all('Set-Cookie')
session=next(s for s in cookies if s.startswith('TICKET_CUSTOMER_SESSION='))
assert all(k in session for k in ['Secure','HttpOnly','SameSite=Strict','Path=/api/customer','Max-Age=1800'])
token,cookies=csrf();headers={token['headerName']:token['token'],'Content-Type':'application/json','Cookie':cookies[0].split(';',1)[0]+'; '+session.split(';',1)[0]}
with c.open(urllib.request.Request(a.base_url+'/customer/auth/logout',data=b'{}',headers=headers)) as r:assert json.load(r)=={'authenticated':False}
Path(a.output).write_text(json.dumps({'secureConfigVerified':True,'csrfCookieSecure':True,'sessionCookieSecure':True,'httpOnly':True,'sameSite':'Strict','path':'/api/customer','actualHTTPS':False},indent=2)+'\n')
print('Secure=true cookie flags verified; loopback response-header test, not HTTPS deployment.')
