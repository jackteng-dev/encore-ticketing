#!/usr/bin/env python3
"""M4D1 standalone Lua primitive against own Redis, using script bytes from the final JAR.
Operator actually stops/restarts own Redis between prepare and verify-restart.
Not a checkout/PG orphan-reconciliation test. No automatic compensation or Redis flush.
"""
import argparse,hashlib,json,os,re,subprocess,uuid,zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--env',required=True);p.add_argument('--phase',choices=['prepare','verify-restart'],required=True);p.add_argument('--fixture',required=True);p.add_argument('--output',required=True);args=p.parse_args()
def private_path(value):
 path=(root/value).resolve();assert path.is_relative_to(root/'work');return path
env_path,fixture,output=map(private_path,[args.env,args.fixture,args.output])
cfg=dict(line.split('=',1) for line in env_path.read_text().splitlines() if line and not line.startswith('#'))
project=cfg['COMPOSE_PROJECT_NAME'];assert re.fullmatch(r'ticket-lua-foundation-[a-z0-9-]+',project)
namespace=cfg['TICKET_REDIS_NAMESPACE'];assert re.fullmatch(r'encore-demo-[a-z0-9-]{1,48}',namespace)
env=os.environ.copy()
for name in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH']:env.pop(name,None)
env['REDISCLI_AUTH']=cfg['TICKET_REDIS_PASSWORD'];docker=['docker','--host=unix:///var/run/docker.sock'];container=project+'-redis-1'
label=subprocess.check_output(docker+['inspect','--format','{{index .Config.Labels "com.docker.compose.project"}}',container],env=env,text=True).strip();assert label==project
def cli(*command,input=None):
 result=subprocess.run(docker+['exec','-i','-e','REDISCLI_AUTH',container,'redis-cli','--raw',*map(str,command)],env=env,input=input,text=True,capture_output=True,check=True)
 text=result.stdout.strip();assert not text.startswith(('ERR','WRONGTYPE','NOSCRIPT')), 'Redis command failed';return text
jar=root/'backend/target/ticket-platform-0.1.0.jar'
with zipfile.ZipFile(jar) as z:script=z.read('BOOT-INF/classes/redis/ticket-ledger.lua')
digest=hashlib.sha1(script).hexdigest();assert cli('-x','SCRIPT','LOAD',input=script.decode())==digest
checks=[]
def check(name):checks.append(name);print('PASS '+name)
def call(key,mode,g,*values):return json.loads(cli('EVALSHA',digest,1,key,mode,g,*values))
def write(path,value):
 path.parent.mkdir(mode=0o700,parents=True,exist_ok=True);path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');path.chmod(0o600)
if args.phase=='prepare':
 assert not fixture.exists(),'Do not overwrite an existing restart fixture'
 ticket=str(uuid.uuid4());key=namespace+':stock:{'+ticket+'}:ledger';assert cli('EXISTS',key)=='0'
 assert call(key,'INIT',41,2,8)['code']=='OK';assert call(key,'OPEN',41)['code']=='OK'
 seconds,micros=map(int,cli('TIME').splitlines());deadline=seconds*1000+micros//1000+30000
 claim=[str(uuid.uuid4()),str(uuid.uuid4()),1,deadline,hashlib.sha256(b'synthetic original ledger command').hexdigest()]
 accepted=call(key,'HOLD',41,*claim);assert accepted['code']=='OK' and accepted['snapshot']['available']==1
 check('final-JAR Lua stores one original HELD receipt and one pre-debit in real Redis')
 assert cli('PTTL',key)=='-1';fsynced=cli('WAITAOF',1,0,5000).splitlines();assert fsynced==['1','0']
 check('own non-expiring ledger has a local AOF fsync acknowledgement before graceful restart')
 write(fixture,{'ticket':ticket,'key':key,'claim':claim,'generation':41,'accepted':accepted,'scriptSha256':hashlib.sha256(script).hexdigest(),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'scope':project})
else:
 saved=json.loads(fixture.read_text());assert saved['scope']==project and saved['scriptSha256']==hashlib.sha256(script).hexdigest() and saved['jarSha256']==hashlib.sha256(jar.read_bytes()).hexdigest()
 key=saved['key'];assert key==namespace+':stock:{'+str(uuid.UUID(saved['ticket']))+'}:ledger'
 claim=saved['claim'];state=call(key,'LOOKUP',41,*claim)
 assert state['code']=='OK' and state['serverNow']>claim[3] and state['receipt']==saved['accepted']['receipt'] and state['snapshot']==saved['accepted']['snapshot']
 check('after actual restart and original deadline, AOF retains the exact HELD receipt and no automatic credit')
 replay=call(key,'HOLD',41,*claim);assert replay['code']=='OK' and replay['snapshot']['available']==1 and replay['snapshot']['entries']==1
 check('original expired UUID replay is a read of the original hold, never another pre-debit')
 assert call(key,'CLOSE',41)['code']=='OK';assert call(key,'OPEN',41)['code']=='CLOSED';assert call(key,'ROTATE',41,2)['code']=='ACTIVE_HOLDS'
 check('frozen generation cannot reopen or rebuild over an unresolved hold')
 assert call(key,'RELEASE',41,*claim)['snapshot']['available']==2;assert call(key,'RELEASE',41,*claim)['snapshot']['available']==2
 check('explicit synthetic release credits once; this is not automatic PG orphan reconciliation')
 assert call(key,'ROTATE',41,2)['snapshot']['generation']==42;assert call(key,'OPEN',41)['code']=='FENCED';assert call(key,'RELEASE',41,*claim)['code']=='FENCED';assert call(key,'OPEN',42)['code']=='OK';assert call(key,'HOLD',42,*claim)['code']=='CONFLICT'
 check('new generation fences old commands and retains prior request tombstones')
 assert cli('DEL',key)=='1';assert call(key,'HOLD',42,*claim)['code']=='MISSING' and cli('EXISTS',key)=='0'
 check('controlled logical key loss fails closed without reseeding or inventing available stock')
write(output,{'milestone':'M4D1 standalone ledger','phase':args.phase,'passed':len(checks),'checks':checks,'scriptSha256':hashlib.sha256(script).hexdigest(),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'checkoutIntegration':False,'pgReconciliation':False,'realPayments':False})
