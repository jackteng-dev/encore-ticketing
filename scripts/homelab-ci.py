#!/usr/bin/env python3
"""Hosted-only original-project verification with disposable data fixtures.

Distributed by prepare-showcase-ci.py. No host deployment, registry credentials,
production database credentials, skipped Maven tests, or persistent volumes.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET

REPOSITORIES = {'portfolio': 'developer-portfolio', 'camp': 'fadaiting-camp',
                'shop': 'shop_bak_ap', 'shop-web': 'Shop_back', 'encore': 'encore-ticketing',
                'atlas': 'ai-knowledge-assistant'}


def guard(project, environment):
    if project not in REPOSITORIES or environment.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Disposable GitHub-hosted job required; do not run on swaggyserver')
    owner = environment.get('GITHUB_REPOSITORY_OWNER', '')
    if not owner or environment.get('GITHUB_REPOSITORY') != owner + '/' + REPOSITORIES[project]:
        raise ValueError('Original repository/project binding differs')
    if environment.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise ValueError('Privileged self-hosted runners are not allowed')


class Fixture:
    def __init__(self, images, root):
        self.images = images; self.root = root; self.ids = []
        self.network = 'homelab-ci-' + secrets.token_hex(8)
        self.env = dict(os.environ)
        self.secret = {}
        for key in ['db', 'root', 'redis', 'jwt', 'admin', 'staff', 'mq', 'access', 's3', 'webhook']:
            self.secret[key] = secrets.token_hex(24)
            print('::add-mask::' + self.secret[key], flush=True)

    def command(self, *args, env=None, capture=True, timeout=300):
        return subprocess.run(list(args), check=True, env=env or self.env, capture_output=capture, text=True, timeout=timeout)

    def start(self, key, ports, variables=None, mounts=None, args=None):
        command = ['docker', 'run', '--detach', '--network', self.network, '--label', 'homelab.ci=' + self.network,
                   '--memory', '1g', '--cpus', '2', '--pids-limit', '512']
        if key == 'seaweed':
            command += ['--hostname', 'seaweed', '--network-alias', 'seaweed']
        environment = dict(self.env)
        for port in ports:
            command += ['--publish', '127.0.0.1::' + str(port)]
        for name, value in (variables or {}).items():
            environment[name] = value; command += ['--env', name]
        for host, container in (mounts or []):
            command += ['--mount', 'type=bind,src=' + str(host.resolve()) + ',dst=' + container + ',readonly']
        command += [self.images[key]['image']] + (args or [])
        cid = self.command(*command, env=environment).stdout.strip()
        if len(cid) != 64 or any(c not in '0123456789abcdef' for c in cid):
            raise RuntimeError('Unexpected disposable container ID')
        self.ids.append(cid)
        state = json.loads(self.command('docker', 'inspect', '--format', '{{json .State}}', cid).stdout)
        if not state.get('Running'):
            raise RuntimeError('Disposable ' + key + ' exited before port mapping; inspect fixture configuration')
        mapping = json.loads(self.command('docker', 'inspect', '--format', '{{json .NetworkSettings.Ports}}', cid).stdout)
        result = {port: int(mapping[str(port) + '/tcp'][0]['HostPort']) for port in ports}
        return cid, result

    def ready(self, cid, *command, env=None):
        for _ in range(120):
            inherited = [item for name in (env or {}) for item in ['--env', name]]
            result = subprocess.run(['docker', 'exec', *inherited, cid, *command],
                                    env={**self.env, **(env or {})}, capture_output=True, timeout=20)
            if result.returncode == 0:
                return
            time.sleep(2)
        raise RuntimeError('Disposable fixture not ready; production untouched')

    def s3_canary(self, port):
        """Signed real object I/O in this new fixture; credentials stay out of argv."""
        config = self.root/'curl-private.conf'
        config.write_text('user = "' + self.secret['access'] + ':' + self.secret['s3'] + '"\n')
        payload = self.root/'canary.bin'; payload.write_bytes(b'homelab-disposable-s3-canary')
        response = self.root/'canary-response'
        bucket = '/elpsy-shop-images-demo'; key = bucket + '/ci-canary-' + secrets.token_hex(8)
        for method, target, body, allowed in [('PUT', bucket, None, [200]), ('PUT', key, payload, [200]),
                                               ('GET', key, None, [200]), ('DELETE', key, None, [204])]:
            args = ['curl', '--silent', '--show-error', '--noproxy', '*', '--connect-timeout', '3', '--max-time', '15',
                    '--aws-sigv4', 'aws:amz:us-east-1:s3', '--config', str(config), '--request', method,
                    '--output', str(response), '--write-out', '%{http_code}']
            if body:
                args += ['--data-binary', '@' + str(body)]
            result = self.command(*args, 'http://127.0.0.1:' + str(port) + target)
            status = result.stdout.strip()
            if not status.isdigit() or int(status) not in allowed:
                # Only provider error enum/status, never XML body/headers/keys.
                code = 'UNKNOWN'
                try:
                    value = ET.fromstring(response.read_bytes()).findtext('Code', '')
                    if value.isascii() and value.isalpha() and len(value) < 80:
                        code = value
                except ET.ParseError:
                    pass
                raise RuntimeError('Disposable S3 canary ' + method + ' HTTP=' + status + ' code=' + code)
            if method == 'GET' and response.read_bytes() != payload.read_bytes():
                raise RuntimeError('Disposable S3 canary data differs')

    def setup(self, project):
        self.command('docker', 'network', 'create', self.network)
        env = {}; password = self.secret['db']
        if project in ['camp', 'encore']:
            database, user = ('fadaiting_camp', 'camp') if project == 'camp' else ('ticket_demo', 'ticket_demo')
            mounts = [(Path('db/init'), '/docker-entrypoint-initdb.d')] if project == 'camp' else []
            cid, ports = self.start('pg18' if project == 'camp' else 'pg17', [5432],
                                    {'POSTGRES_DB': database, 'POSTGRES_USER': user, 'POSTGRES_PASSWORD': password}, mounts)
            self.ready(cid, 'psql', '-U', user, '-h', '127.0.0.1', '-d', database, '-c', 'SELECT 1', env={'PGPASSWORD': password})
            url = 'jdbc:postgresql://127.0.0.1:' + str(ports[5432]) + '/' + database
            if project == 'camp':
                env.update(SPRING_DATASOURCE_URL=url, SPRING_DATASOURCE_USERNAME=user, SPRING_DATASOURCE_PASSWORD=password,
                           JWT_SECRET=self.secret['jwt'], CAMP_SECURITY_INTEGRATION='true', CAMP_NOTIFICATION_INTEGRATION='true')
            else:
                env.update(TICKET_DB_URL=url, TICKET_DB_USER=user, TICKET_DB_PASSWORD=password)
        if project == 'shop':
            cid, ports = self.start('mysql', [3306], {'MYSQL_DATABASE': 'elpsy_shop_demo', 'MYSQL_USER': 'shop_demo',
                                     'MYSQL_PASSWORD': password, 'MYSQL_ROOT_PASSWORD': self.secret['root'], 'TZ': 'Asia/Taipei'})
            for _ in range(120):
                result = subprocess.run(['docker', 'exec', '--env', 'MYSQL_PWD', cid, 'mysql', '-h', '127.0.0.1',
                                         '-u', 'shop_demo', '-Nse', 'SELECT 1', 'elpsy_shop_demo'],
                                        env={**self.env, 'MYSQL_PWD': password}, capture_output=True, timeout=15)
                if result.returncode == 0:
                    break
                time.sleep(2)
            else:
                raise RuntimeError('Disposable MySQL not ready')
            env.update(DB_URL='jdbc:mysql://127.0.0.1:' + str(ports[3306]) + '/elpsy_shop_demo?serverTimezone=Asia/Taipei',
                       DB_USERNAME='shop_demo', DB_PASSWORD=password, SHOP_MYSQL_INTEGRATION='true', JWT_SECRET=self.secret['jwt'],
                       SPRING_PROFILES_ACTIVE='demo', DEMO_ADMIN_PASSWORD=self.secret['admin'], DEMO_STAFF_PASSWORD=self.secret['staff'],
                       MOCK_PAYMENT_WEBHOOK_SECRET=self.secret['webhook'], TZ='Asia/Taipei')
            for flag in ['PAYMENTS', 'REFUNDS', 'FULFILLMENT', 'RETURNS', 'NOTIFICATIONS']:
                env['MOCK_' + flag + '_ENABLED'] = 'true'
            s3 = self.root/'s3.json'
            s3.write_text(json.dumps({'identities': [{'name': 'ci-only', 'credentials': [{'accessKey': self.secret['access'],
                 'secretKey': self.secret['s3']}], 'actions': [a + ':elpsy-shop-images-demo' for a in ['Admin', 'Read', 'Write', 'List']]}]}))
            s3.chmod(0o444)  # Only the private 0700 parent is accessible on the runner.
            cid, ports = self.start('seaweed', [8333, 8888], mounts=[(s3, '/etc/seaweedfs/s3.json'),
                (Path('config/seaweed-filer.toml'), '/etc/seaweedfs/filer.toml')], args=['server', '-dir=/data', '-ip=seaweed', '-ip.bind=0.0.0.0',
                 '-volume.max=12', '-master.volumeSizeLimitMB=128', '-filer', '-s3', '-s3.config=/etc/seaweedfs/s3.json'])
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for _ in range(120):
                try:
                    with opener.open('http://127.0.0.1:' + str(ports[8888]) + '/healthz', timeout=5) as response:
                        if response.status == 200:
                            break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(2)
            else:
                raise RuntimeError('Disposable SeaweedFS not ready')
            self.s3_canary(ports[8333])
            env.update(SHOP_IMAGES_ENABLED='true', SHOP_IMAGES_INTEGRATION='true', SHOP_IMAGES_ENDPOINT='http://127.0.0.1:' + str(ports[8333]),
                       SHOP_IMAGES_ACCESS_KEY=self.secret['access'], SHOP_IMAGES_SECRET_KEY=self.secret['s3'],
                       SHOP_IMAGES_BUCKET='elpsy-shop-images-demo', SHOP_IMAGES_INIT_BUCKET='true')
        if project in ['camp', 'shop', 'encore']:
            args = ['redis-server', '--save', '', '--appendonly', 'no']
            if project != 'shop':
                # Redis config in a disposable private file, not a password in argv.
                redis = self.root/'redis.conf'; redis.write_text('bind 0.0.0.0\nsave ""\nappendonly no\nrequirepass ' + self.secret['redis'] + '\n')
                redis.chmod(0o444)  # Container drops to redis UID; host parent stays 0700.
                args = ['redis-server', '/run/redis.conf']; mounts = [(redis, '/run/redis.conf')]
            else:
                mounts = []; args += ['--protected-mode', 'no']  # Isolated loopback-only CI fixture; upstream DB15 tests require no AUTH.
            cid, ports = self.start('redis8' if project == 'camp' else 'redis7', [6379], mounts=mounts, args=args)
            self.ready(cid, 'redis-cli', 'ping', env={'REDISCLI_AUTH': self.secret['redis']} if project != 'shop' else None)
            prefix = 'SPRING_DATA_REDIS_' if project == 'camp' else 'TICKET_REDIS_' if project == 'encore' else 'REDIS_'
            env.update({prefix + 'HOST': '127.0.0.1', prefix + 'PORT': str(ports[6379]), prefix + 'PASSWORD': self.secret['redis'] if project != 'shop' else ''})
            if project == 'shop':
                env.update(SHOP_REDIS_INTEGRATION='true', SHOP_REDIS_TEST_PORT=str(ports[6379]))
        if project == 'encore':
            cid, ports = self.start('rabbitmq', [5672], {'RABBITMQ_DEFAULT_USER': 'ticket_demo',
                 'RABBITMQ_DEFAULT_PASS': self.secret['mq'], 'RABBITMQ_DEFAULT_VHOST': '/ticket-demo',
                 'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS': '+S 2:2 +SDcpu 1 +SDio 1'})
            self.ready(cid, 'su-exec', 'rabbitmq', 'rabbitmq-diagnostics', '-q', 'check_running')
            env.update(TICKET_MQ_HOST='127.0.0.1', TICKET_MQ_PORT=str(ports[5672]), TICKET_MQ_USER='ticket_demo',
                       TICKET_MQ_PASSWORD=self.secret['mq'], TICKET_MQ_VHOST='/ticket-demo', TICKET_MQ_NAMESPACE='encore-demo-' + self.network,
                       TICKET_REDIS_NAMESPACE='encore-demo-' + self.network, TICKET_STAFF_USERNAME='manager', TICKET_STAFF_PASSWORD=self.secret['staff'],
                       TICKET_COOKIE_SECURE='false', TICKET_DEMO_BOOTSTRAP='true')
        return env

    def cleanup(self):
        # Only IDs returned by THIS process; never prune / delete persistent volumes.
        for cid in reversed(self.ids):
            label = self.command('docker', 'inspect', '--format', '{{index .Config.Labels "homelab.ci"}}', cid).stdout.strip()
            if label != self.network:
                raise RuntimeError('Fixture ownership changed; refuse removal')
            self.command('docker', 'rm', '--force', cid)
        subprocess.run(['docker', 'network', 'rm', self.network], capture_output=True, timeout=30)


def main():
    os.umask(0o077)
    project = json.loads(Path('.homelab/ci-project.json').read_text())['project']
    guard(project, os.environ)
    root = Path.cwd()
    frontend = root/('frontend' if project in ['camp', 'encore', 'atlas'] else '.')
    if project != 'shop':
        subprocess.run(['npm', 'ci'], cwd=frontend, check=True, timeout=600)
        subprocess.run(['npm', 'run', 'build'], cwd=frontend, check=True, timeout=600)
    if project == 'shop-web':
        return
    if project == 'portfolio':
        # Keep the repository's own migration/roles/container/browser contract.
        subprocess.run(['npx', 'playwright', 'install', '--with-deps', 'chromium'], check=True, timeout=600)
        chromium = subprocess.check_output(['node', '--input-type=module', '-e',
             'import { chromium } from "@playwright/test"; process.stdout.write(chromium.executablePath())'], text=True).strip()
        try:
            subprocess.run(['python3', 'scripts/run_ci.py', 'verify', '--output', 'work/ci', '--chromium', chromium], check=True, timeout=2400)
        finally:
            subprocess.run(['python3', 'scripts/run_ci.py', 'cleanup', '--output', 'work/ci'], check=True, timeout=180)
        return
    with tempfile.TemporaryDirectory(prefix='homelab-ci-only-') as tmp:
        fixture = Fixture(json.loads(Path('.homelab/images.lock.json').read_text())['images'], Path(tmp))
        try:
            env = {**os.environ, **fixture.setup(project), 'JAVA_TOOL_OPTIONS': '-XX:+UseSerialGC -XX:ActiveProcessorCount=2', 'MAVEN_OPTS': '-Xmx256m'}
            command = ['mvn', '-B', '-ntp', '-f', 'pom.xml' if project == 'shop' else 'backend/pom.xml', 'verify']
            if project == 'encore':
                command += ['-DforkCount=1', '-DreuseForks=false', '-DargLine=-Xmx384m -XX:+UseSerialGC -XX:ActiveProcessorCount=2']
            subprocess.run(command, env=env, check=True, timeout=3600)
            suites = list(root.glob('**/target/surefire-reports/TEST-*.xml'))
            totals = {k: 0 for k in ['tests', 'errors', 'failures', 'skipped']}
            for suite in suites:
                data = ET.parse(suite).getroot()
                for key in totals:
                    totals[key] += int(data.attrib.get(key, 0))
            if not totals['tests'] or totals['failures'] or totals['errors']:
                raise RuntimeError('Original complete Maven test gate did not pass')
            if totals['skipped'] > (2 if project == 'shop' else 0):
                raise RuntimeError('Unexpected skipped integration tests; do not publish')
            if project == 'atlas':
                subprocess.run(['node', '--test', 'scripts/evaluation.test.mjs'], check=True, timeout=300)
            out = root/'work/homelab-ci'; out.mkdir(parents=True, exist_ok=True)
            (out/'summary.json').write_text(json.dumps({'project': project, 'status': 'PASS', 'maven': totals,
                 'scope': 'original full test suite in disposable fixtures; Atlas tests are offline contracts, not new real-model inference'}, indent=2))
        finally:
            fixture.cleanup()


if __name__ == '__main__':
    main()
