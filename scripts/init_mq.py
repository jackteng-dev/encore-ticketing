#!/usr/bin/env python3
"""Append MQ settings to an existing private env, preserving all previous credentials."""
import argparse,secrets
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--env',default='work/runtime.env');args=p.parse_args()
root=Path(__file__).resolve().parents[1];env=(root/args.env).resolve()
if not env.is_relative_to(root/'work') or not env.is_file():raise SystemExit('Requires an existing private env inside work/.')
data=dict(l.split('=',1) for l in env.read_text().splitlines() if l and not l.startswith('#'))
extras={'TICKET_MQ_HOST':'127.0.0.1','TICKET_MQ_PORT':'25672','TICKET_MQ_USER':'ticket_demo','TICKET_MQ_PASSWORD':secrets.token_urlsafe(32),'TICKET_MQ_VHOST':'/ticket-demo','TICKET_MQ_NAMESPACE':'encore-demo-local','TICKET_DELIVERY_ENABLED':'false','TICKET_PUBLISHER_ENABLED':'false','TICKET_CONSUMER_ENABLED':'false'}
missing={k:v for k,v in extras.items() if not data.get(k)}
if missing:
 with env.open('a') as f:env.chmod(0o600);f.write('\n'+''.join(k+'='+v+'\n' for k,v in missing.items()))
print('Private MQ settings prepared; existing credentials preserved, delivery disabled by default.')
