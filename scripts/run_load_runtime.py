#!/usr/bin/env python3
"""Bounded k6/real HTTP demonstration; fresh owned PG/Redis/MQ, two APIs/two Workers.

No provider, deadline changes, debug endpoints, direct SQL seed or other-project cleanup.
Detailed identities, settings, logs and fixtures are private. Only sanitized reports may be committed.
"""
import argparse
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def private(path):
    result = (ROOT / path).resolve()
    require(result.is_relative_to(ROOT / 'work'), 'Private path must remain in repository work/')
    return result


def save(path, value):
    require(not path.exists(), 'Preserve existing evidence')
    with path.open('x') as output:
        path.chmod(0o600)
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write('\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--k6', required=True)
    parser.add_argument('--compose-override', help='Optional private, image-only owned VFS flattening overlay')
    parser.add_argument('--buyers', type=int, default=60)
    parser.add_argument('--capacity', type=int, default=12)
    parser.add_argument('--vus', type=int, default=8)
    args = parser.parse_args()
    require(2 <= args.buyers <= 100 and 1 <= args.capacity < args.buyers and 1 <= args.vus <= 16, 'Workload bound exceeded')
    scope = private(args.output)
    require(not scope.exists(), 'Use a new scope; never overwrite prior failure evidence')
    scope.mkdir(parents=True, mode=0o700)
    project = 'ticket-k6-runtime-' + datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S') + '-' + uuid.uuid4().hex[:6]
    envfile = scope / 'runtime.env'
    processes = []
    started = False
    report = None
    env = os.environ.copy()
    for key in ['DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']:
        env.pop(key, None)
    docker = ['docker', '--host=unix:///var/run/docker.sock']

    def command(command_args, name, timeout=180, extra=None):
        with (scope / (name + '.log')).open('x') as output:
            Path(output.name).chmod(0o600)
            return subprocess.run(command_args, cwd=ROOT, env=extra or env, stdout=output,
                                  stderr=subprocess.STDOUT, check=True, timeout=timeout, umask=0o077)

    def dock(command_args):
        return subprocess.check_output(docker + command_args, env=env, text=True, timeout=30).strip()

    queries = {'containers': ['ps', '-aq'], 'volumes': ['volume', 'ls', '-q'], 'networks': ['network', 'ls', '-q']}

    def owned(kind):
        return dock(queries[kind] + ['--filter', 'label=com.docker.compose.project=' + project]).splitlines()

    def cid(service):
        ids = dock(['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project,
                    '--filter', 'label=com.docker.compose.service=' + service]).splitlines()
        require(len(ids) == 1, 'Exactly one owned data service required')
        return ids[0]

    def sql(statement):
        result = subprocess.run(docker + ['exec', '-i', cid('postgres'), 'psql', '-U', cfg['TICKET_DB_USER'],
                                          '-d', 'ticket_demo', '-XAt', '-v', 'ON_ERROR_STOP=1'], env=env,
                                input='BEGIN READ ONLY;\n' + statement + '\nCOMMIT;', text=True,
                                capture_output=True, check=True, timeout=20).stdout
        return '\n'.join(line for line in result.splitlines() if line not in ['BEGIN', 'COMMIT']).strip()

    def until(predicate, seconds=70):
        end = time.monotonic() + seconds
        while not predicate():
            require(time.monotonic() < end, 'Bounded observation deadline exceeded; retain failure')
            time.sleep(0.25)

    def client():
        jar = http.cookiejar.CookieJar()
        return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar)), jar

    def request(actor, path, body=None, staff=False, expected=(200,), base=None):
        headers = {}
        raw = None
        if body is not None:
            token = request(actor, '/auth/csrf' if staff else '/customer/auth/csrf', base=base)
            headers[token['headerName']] = token['token']
            form = path == '/auth/login'
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            raw = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        try:
            with actor.open(urllib.request.Request((base or bases[0]) + path, data=raw, headers=headers), timeout=15) as response:
                status, payload = response.status, response.read()
        except urllib.error.HTTPError as response:
            status, payload = response.code, response.read()
        require(status in expected, 'HTTP preparation/reconciliation returned unexpected status ' + str(status))
        return json.loads(payload)

    def launch(name, command_args, extra):
        output = (scope / (name + '.log')).open('x')
        Path(output.name).chmod(0o600)
        process = subprocess.Popen(command_args, cwd=ROOT, env=extra, stdout=output, stderr=subprocess.STDOUT)
        processes.append((name, process, output))
        return process

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)

    def ready(base):
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(base + '/public/readiness', timeout=2) as response:
                return response.status == 200
        except (urllib.error.URLError, TimeoutError):
            return False

    def inventory(ticket):
        return json.loads(sql("SELECT json_build_object('capacity',capacity,'held',held,'sold',sold,"
                              "'orders',(SELECT count(*) FROM ticket_order WHERE ticket_id=t.id),"
                              "'purchases',(SELECT COALESCE(json_object_agg(status,n),'{}'::json) FROM "
                              "(SELECT status,count(*) n FROM purchase_request WHERE ticket_id=t.id GROUP BY status) q),"
                              "'gate',(SELECT state FROM stock_projection WHERE ticket_id=t.id),"
                              "'unresolvedIntents',(SELECT count(*) FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id "
                              "WHERE i.ticket_id=t.id AND p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=i.id))) "
                              "FROM ticket_type t WHERE id='" + ticket['id'] + "';"))

    def invariants():
        combined = {}
        for name in ['payment', 'purchase', 'delivery', 'stock_purchase', 'stock_recovery']:
            raw = sql((ROOT / ('scripts/verify_' + name + '_invariants.sql')).read_text())
            if name == 'delivery':
                values = {key: int(count) for key, count in (line.split('|') for line in raw.splitlines())}
            else:
                values = json.loads(raw)
            combined.update({name + '.' + key: value for key, value in values.items()})
        require(all(value == 0 for value in combined.values()), 'Authoritative invariant violation')
        return combined

    try:
        require(not any(owned(kind) for kind in queries), 'Existing owned scope forbidden')
        for port in [25432, 26379, 25672, 19110, 19111]:
            with socket.socket() as sock:
                require(sock.connect_ex(('127.0.0.1', port)) != 0, 'Port already occupied; do not stop another service')
        command(['python', 'scripts/init_demo.py', '--loopback-http', '--env', str(envfile)], 'init-demo')
        command(['python', 'scripts/init_mq.py', '--env', str(envfile)], 'init-mq')
        cfg = dict(line.split('=', 1) for line in envfile.read_text().splitlines() if line and not line.startswith('#'))
        cfg.update(COMPOSE_PROJECT_NAME=project, TICKET_REDIS_NAMESPACE='encore-demo-' + project,
                   TICKET_MQ_NAMESPACE='encore-demo-' + project)
        envfile.write_text(''.join(key + '=' + value + '\n' for key, value in cfg.items()))
        envfile.chmod(0o600)
        env.update(cfg)
        env.update(TICKET_ORDERS_ENABLED='true', TICKET_PURCHASES_ENABLED='true', TICKET_DELIVERY_ENABLED='true',
                   TICKET_PUBLISHER_ENABLED='true', TICKET_CONSUMER_ENABLED='true', TICKET_EXPIRY_ENABLED='true',
                   TICKET_PREDEBIT_ENABLED='true', TICKET_STOCK_RECOVERY_ENABLED='true',
                   TICKET_STOCK_MAINTENANCE_ENABLED='true', TICKET_MONITORING_ENABLED='true')
        compose = docker + ['compose', '--env-file', str(envfile), '-f', str(ROOT / 'infra/compose.yml'),
                            '-f', str(ROOT / 'infra/compose.mq.yml')]
        if args.compose_override:
            overlay = private(args.compose_override)
            # Image-only override is permitted; original volumes/networks/ports and healthchecks stay intact.
            text = overlay.read_text()
            require(all(line.strip() in ['', 'services:', 'postgres:', 'rabbitmq:'] or
                        re.fullmatch(r'image: ticket-k6-flat-(?:pg|mq):[a-z0-9._-]+', line.strip())
                        for line in text.splitlines()), 'Only owned flat image override allowed')
            compose += ['-f', str(overlay)]
        started = True
        command(compose + ['up', '-d', '--wait'], 'start')
        require(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';") == '0', 'Fresh empty PG required')
        java = Path(args.java_home).resolve() / 'bin/java'
        jar = ROOT / 'backend/target/ticket-platform-0.1.0.jar'
        require(java.is_file() and jar.is_file(), 'Verified production package and installed JDK21 required')
        protected = [jar, ROOT / 'scripts/load_ticketing.js', Path(__file__).resolve(),
                     *sorted((ROOT / 'backend/src/main/resources/db/migration').glob('*.sql'))]
        def hashes():
            return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in protected}
        frozen = hashes()
        jvm = [str(java), '-Xmx256m', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=2', '-jar', str(jar)]
        bases = ['http://127.0.0.1:19110/api', 'http://127.0.0.1:19111/api']
        api_a = launch('api-a', jvm + ['--spring.profiles.active=api'], env)
        until(lambda: ready(bases[0]))
        second = env.copy()
        second['TICKET_DEMO_BOOTSTRAP'] = 'false'
        api_b = launch('api-b', jvm + ['--spring.profiles.active=api', '--server.port=19111'], second)
        until(lambda: ready(bases[1]))
        require(sql('SELECT count(*) FROM flyway_schema_history WHERE success;') == '24', 'V24 required')
        worker_env = second.copy()
        workers = [launch('worker-' + str(n), jvm + ['--spring.profiles.active=worker'], worker_env) for n in range(2)]
        until(lambda: sql("SELECT count(*) FROM runtime_heartbeat WHERE role='WORKER' AND last_seen>=clock_timestamp()-interval '15 seconds';") == '2')
        staff, _ = client()
        request(staff, '/auth/login', {'username': cfg['TICKET_STAFF_USERNAME'], 'password': cfg['TICKET_STAFF_PASSWORD']}, staff=True)
        actors = []
        handles = []
        for _ in range(args.buyers):
            actor, cookies = client()
            profile = request(actor, '/customer/auth/register', {'handle': 'load-' + uuid.uuid4().hex,
                              'displayName': '合成負載顧客', 'password': uuid.uuid4().hex})
            csrf = request(actor, '/customer/auth/csrf')
            token = next(cookie.value for cookie in cookies if cookie.name == 'TICKET_CUSTOMER_SESSION')
            cookie = next(cookie for cookie in cookies if 'CSRF' in cookie.name.upper())
            actors.append({'token': token, 'csrf': csrf, 'csrfCookie': {'name': cookie.name, 'value': cookie.value, 'path': cookie.path},
                           'commands': {mode: str(uuid.uuid4()) for mode in ['sync', 'async', 'predebit', 'waiting']}})
            handles.append((actor, profile))
        cases = {}
        def body(**values):
            return {'requestId': str(uuid.uuid4()), 'expectedVersion': 0, **values}
        for mode in ['read', 'sync', 'async', 'predebit', 'waiting']:
            event = request(staff, '/admin/events', body(slug='load-' + uuid.uuid4().hex, title='合成負載 ' + mode,
                            description='受控驗收，不是真實售票', venue='合成場地'), staff=True)
            now = datetime.now(timezone.utc)
            session = request(staff, '/admin/events/' + event['id'] + '/sessions', body(name='合成場次',
                              startsAt=(now + timedelta(days=2)).isoformat(), salesStart=(now - timedelta(minutes=1)).isoformat(),
                              salesEnd=(now + timedelta(days=1)).isoformat()), staff=True)
            ticket = request(staff, '/admin/sessions/' + session['id'] + '/tickets', body(name='一般票',
                             priceTwd=1200, capacity=args.capacity, maxPerCustomer=1), staff=True)
            request(staff, '/admin/events/' + event['id'] + '/publish', body(expectedVersion=1), staff=True)
            cases[mode] = dict(ticket, eventId=event['id'])
        projection = request(staff, '/admin/stock/tickets/' + cases['predebit']['id'])
        rebuilt = request(staff, '/admin/stock/tickets/' + cases['predebit']['id'] + '/rebuilds',
                          {'requestId': str(uuid.uuid4()), 'expectedSourceRevision': projection['sourceRevision']}, staff=True, expected=(200, 202))
        require(rebuilt['state'] == 'READY', 'Explicit pre-debit READY setup required')
        save(scope / 'fixture.json', {'base': bases[0], 'bases': bases, 'actors': actors, 'cases': cases})
        until(lambda: request(staff, '/admin/monitoring')['broker'].get('queues', {}).get('main', {}).get('consumers') == 2)
        before_monitoring = request(staff, '/admin/monitoring')
        save(scope / 'monitor-before.json', before_monitoring)
        phases = {}
        for mode in ['read', 'sync', 'async', 'predebit']:
            phase_started = time.monotonic()
            load_env = env.copy()
            load_env.update(TICKET_LOAD_FIXTURE=str(scope / 'fixture.json'), TICKET_LOAD_MODE=mode,
                            TICKET_LOAD_VUS=str(args.vus), TICKET_LOAD_SUMMARY=str(scope / (mode + '-summary.json')))
            command([str(Path(args.k6).resolve()), 'run', '--quiet', 'scripts/load_ticketing.js'], mode + '-k6', timeout=90, extra=load_env)
            until(lambda: sql("SELECT count(*) FROM purchase_request WHERE ticket_id='" + cases[mode]['id'] + "' AND status='PROCESSING';") == '0')
            if mode == 'predebit':
                until(lambda: sql("SELECT count(*) FROM stock_hold_acceptance a JOIN stock_hold_intent i ON i.id=a.intent_id "
                                   "WHERE i.ticket_id='" + cases[mode]['id'] + "' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack k WHERE k.intent_id=i.id);") == '0')
            state = inventory(cases[mode])
            require(state['held'] + state['sold'] <= args.capacity, 'No overselling')
            if mode in ['sync', 'async']:
                require(state['orders'] == args.capacity and state['held'] == args.capacity, 'Expected final held orders, not just HTTP acceptance')
            if mode == 'predebit':
                require(0 < state['orders'] <= args.capacity, 'Must demonstrate real pre-debit reservation')
            phases[mode] = {'k6': json.loads((scope / (mode + '-summary.json')).read_text()), 'postgres': state,
                            'loadAndDrainSeconds': time.monotonic() - phase_started}
            print('PASS bounded ' + mode + ': orders=' + str(state['orders']), flush=True)
        # A confirmed successful UUID must reject changed content, even across the other API.
        conflict_checks = 0
        for mode in ['sync', 'async', 'predebit']:
            for index, actor in enumerate(actors):
                original = actor['commands'][mode]
                if sql("SELECT count(*) FROM ticket_order WHERE request_id='" + original + "';") != '1':
                    continue
                path = {'sync': '/customer/orders', 'async': '/customer/purchases', 'predebit': '/customer/purchase-attempts'}[mode]
                changed = dict(requestId=original, ticketId=cases[mode]['id'], quantity=2,
                               expectedTicketVersion=cases[mode]['version'], expectedTotalTwd=2400)
                answer = request(handles[index][0], path, changed, expected=(409,), base=bases[1])
                require(answer['code'] == 'REQUEST_CONFLICT', 'Changed original content must be fenced')
                conflict_checks += 1
                break
        require(conflict_checks == 3, 'Each purchasing path exercised')
        # Same artifact, admission enabled only for the separate waiting-room scenario.
        stop(api_a)
        stop(api_b)
        waiting_env = second.copy()
        waiting_env['TICKET_ADMISSION_ENABLED'] = 'true'
        api_a = launch('api-a-waiting', jvm + ['--spring.profiles.active=api'], waiting_env)
        api_b = launch('api-b-waiting', jvm + ['--spring.profiles.active=api', '--server.port=19111'], waiting_env)
        until(lambda: all(ready(base) for base in bases))
        load_env = waiting_env.copy()
        load_env.update(TICKET_LOAD_FIXTURE=str(scope / 'fixture.json'), TICKET_LOAD_MODE='waiting',
                        TICKET_LOAD_VUS=str(args.vus), TICKET_LOAD_SUMMARY=str(scope / 'waiting-summary.json'))
        command([str(Path(args.k6).resolve()), 'run', '--quiet', 'scripts/load_ticketing.js'], 'waiting-k6', timeout=90, extra=load_env)
        require(inventory(cases['waiting'])['orders'] == 0, 'Joining queue does not reserve tickets')
        phases['waiting'] = {'k6': json.loads((scope / 'waiting-summary.json').read_text()), 'postgres': inventory(cases['waiting'])}
        final_invariants = invariants()
        final_monitoring = request(staff, '/admin/monitoring')
        save(scope / 'monitor-after.json', final_monitoring)
        require(hashes() == frozen, 'Runtime artifact or workload changed during measurement')
        for name, _, _ in processes:
            if name.startswith('worker-'):
                require('Tomcat' not in (scope / (name + '.log')).read_text(), 'Workers must not open HTTP')
        k6_version = subprocess.check_output([str(Path(args.k6).resolve()), 'version'], text=True).strip()
        report = {'milestone': 'M6B', 'date': datetime.now(timezone.utc).isoformat(), 'buyersPerCase': args.buyers,
                  'capacityPerCase': args.capacity, 'vus': args.vus,
                  'loadModel': 'read:20 arrivals/s for10s; purchases/waiting:closed shared-iterations, one actor per iteration, exact original replay',
                  'transport': 'two direct loopback API host JVMs, two nonweb Worker host JVMs, real isolated PG/Redis/single RabbitMQ broker',
                  'admissionDuringPurchasing': False, 'waitingRoomSeparate': True, 'businessDeadlinesChanged': False,
                  'preparationIncludedInLoad': False, 'monitoringDuringLoad': False, 'k6Version': k6_version,
                  'cpuMax': Path('/sys/fs/cgroup/cpu.max').read_text().strip(), 'memoryMax': Path('/sys/fs/cgroup/memory.max').read_text().strip(),
                  'artifactSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'loadScriptSha256': hashlib.sha256((ROOT/'scripts/load_ticketing.js').read_bytes()).hexdigest(),
                  'runtimeSourceHashes': frozen,
                  'postgresVersion': sql('SHOW server_version;'),
                  'dataImages': {name: dock(['inspect', '--format', '{{.Image}}', cid(name)]) for name in ['postgres', 'redis', 'rabbitmq']},
                  'dataImagesFlattenedForVfs': bool(args.compose_override),
                  'phases': phases, 'changedOriginalBodyRejected': conflict_checks, 'invariants': final_invariants,
                  'monitoringSamples': {name: {'enabled': value['enabled'], 'counts': value['postgres']['counts'],
                                              'redis': value['redis'], 'broker': value['broker']['state']}
                                        for name, value in [('before', before_monitoring), ('after', final_monitoring)]},
                  'limitations': ['Read has bounded open arrival rate; purchasing is a small closed burst, not peak capacity or SLA evidence.',
                                  'No Nginx/TLS, Cluster, multi-broker quorum, cross-host HA or real payment in this run.',
                                  'Read/preparation/poll/replay and reservation latency are distinct;202 is not a held order.',
                                  'Pre-debit fresh single generation is a diagnostic; DIRTY/REVIEW can stop continuous admission.',
                                  'Read-only monitoring sampled before/after, not benchmark traffic.']}
        save(scope / 'result.json', report)
    finally:
        for _, process, output in reversed(processes):
            stop(process)
            output.close()
        if started:
            command(compose + ['down', '-v', '--remove-orphans'], 'dispose')
        remaining = {kind: len(owned(kind)) for kind in queries}
        require(not any(remaining.values()), 'Owned cleanup incomplete')
        closed = {}
        for port in [25432, 26379, 25672, 19110, 19111]:
            with socket.socket() as sock:
                closed[str(port)] = sock.connect_ex(('127.0.0.1', port)) != 0
        require(all(closed.values()), 'Owned port remains open')
        save(scope / 'cleanup.json', {'remainingOwnedResources': remaining, 'allOwnedProcessesStopped': all(p.poll() is not None for _, p, _ in processes), 'portsClosed': closed})
    require(report is not None, 'No completed load report')
    print('M6B completed; own processes and data scope disposed', flush=True)


if __name__ == '__main__':
    main()
