#!/usr/bin/env python3
"""New private deployment scope only; never replace credentials or existing volumes."""
import argparse, datetime, re, secrets, subprocess, sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--env',default='work/deployment/runtime.env')
p.add_argument('--loopback-http',action='store_true')
p.add_argument('--demo-bootstrap',action='store_true')
p.add_argument('--enable-async-checkout',action='store_true')
p.add_argument('--port',type=int,default=15184)
a=p.parse_args()
env=(ROOT/a.env).resolve()
if not env.is_relative_to(ROOT/'work') or env.exists() or not 1024<=a.port<=65535:
 raise SystemExit('Requires a new private env in work/ and a nonprivileged loopback port.')
subprocess.run([sys.executable,str(ROOT/'scripts/init_demo.py'),'--env',str(env),*(['--loopback-http']if a.loopback_http else [])],check=True)
subprocess.run([sys.executable,str(ROOT/'scripts/init_mq.py'),'--env',str(env)],check=True)
cfg=dict(l.split('=',1)for l in env.read_text().splitlines()if l and not l.startswith('#'))
suffix=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(4)
cfg.update(COMPOSE_PROJECT_NAME='ticket-deploy-'+suffix,TICKET_GATEWAY_PORT=str(a.port),TICKET_DEMO_BOOTSTRAP=str(a.demo_bootstrap).lower(),TICKET_REDIS_NAMESPACE='encore-demo-deploy-'+suffix,TICKET_MQ_NAMESPACE='encore-deploy-'+suffix,TICKET_APP_IMAGE='ticket-platform-app:'+suffix,TICKET_GATEWAY_IMAGE='ticket-platform-gateway:'+suffix)
for key in ['TICKET_ORDERS_ENABLED','TICKET_PURCHASES_ENABLED','TICKET_DELIVERY_ENABLED','TICKET_PUBLISHER_ENABLED','TICKET_CONSUMER_ENABLED']:
 cfg[key]=str(a.enable_async_checkout).lower()
cfg.update(TICKET_EXPIRY_ENABLED='true',TICKET_PREDEBIT_ENABLED='false')
assert re.fullmatch(r'ticket-deploy-[0-9]{14}-[a-f0-9]{8}',cfg['COMPOSE_PROJECT_NAME'])
env.write_text(''.join(k+'='+v+'\n'for k,v in cfg.items()));env.chmod(0o600)
print('Independent deployment prepared; only gateway is exposed on loopback. Credentials remain private.')
