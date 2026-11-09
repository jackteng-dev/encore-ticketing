#!/usr/bin/env python3
"""Generate isolated private demo files, or add Redis without replacing existing DB/staff credentials."""
from pathlib import Path
import argparse,secrets,datetime,re
p=argparse.ArgumentParser();p.add_argument('--loopback-http',action='store_true');p.add_argument('--redis-only',action='store_true');p.add_argument('--env',default='work/runtime.env');args=p.parse_args()
root=Path(__file__).resolve().parents[1];work=(root/'work').resolve();work.mkdir(mode=0o700,exist_ok=True)
env=(root/args.env).resolve();assert env.is_relative_to(work),'Private env must be inside repository work/'
env.parent.mkdir(mode=0o700,parents=True,exist_ok=True);redis_config=env.parent/'redis.conf'
if redis_config.exists():raise SystemExit('Private Redis configuration exists; preserved unchanged.')
if args.redis_only:
 if not env.is_file():raise SystemExit('--redis-only requires an existing private env.')
 data=dict(l.split('=',1) for l in env.read_text().splitlines() if l and not l.startswith('#'))
else:
 if env.exists():raise SystemExit('Private env exists; use --redis-only to add missing Redis settings without replacing credentials.')
 data={
  'COMPOSE_PROJECT_NAME':'ticket-demo-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S'),
  'TICKET_PG_PORT':'25432','TICKET_DB_USER':'ticket_demo','TICKET_DB_PASSWORD':secrets.token_urlsafe(32),
  'TICKET_DB_URL':'jdbc:postgresql://127.0.0.1:25432/ticket_demo','TICKET_PORT':'19110',
  'TICKET_COOKIE_SECURE':'false' if args.loopback_http else 'true','TICKET_DEMO_BOOTSTRAP':'true',
  'TICKET_STAFF_USERNAME':'manager','TICKET_STAFF_PASSWORD':secrets.token_urlsafe(24),
 }
extras={'TICKET_REDIS_HOST':'127.0.0.1','TICKET_REDIS_PORT':'26379','TICKET_REDIS_PASSWORD':secrets.token_urlsafe(32),'TICKET_REDIS_NAMESPACE':'encore-demo-local','TICKET_REDIS_CONFIG':str(redis_config),'TICKET_ADMISSION_ENABLED':'false'}
missing={k:v for k,v in extras.items() if not data.get(k)};data.update(missing)
if not re.fullmatch(r'[A-Za-z0-9_-]{16,128}',data['TICKET_REDIS_PASSWORD']):raise SystemExit('Use an isolated URL-safe Redis password of 16–128 characters.')
with redis_config.open('x') as f:
 redis_config.chmod(0o600);f.write('bind 0.0.0.0\nprotected-mode yes\nrequirepass '+data['TICKET_REDIS_PASSWORD']+'\nappendonly yes\nappendfsync everysec\nmaxmemory 128mb\nmaxmemory-policy noeviction\n')
with env.open('a' if args.redis_only else 'x') as f:
 env.chmod(0o600);f.write(('\n' if args.redis_only else '')+''.join(k+'='+v+'\n' for k,v in (missing if args.redis_only else data).items()))
print('Private demo configuration prepared inside work/; credentials not printed or replaced.')
