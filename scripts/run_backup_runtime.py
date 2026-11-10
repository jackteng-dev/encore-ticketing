#!/usr/bin/env python3
"""Real isolated backup/restore and original-identity recovery; no source overwrites."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from backup_restore import Database, ROOT, private, require, save


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--compose-override')
    args = parser.parse_args()
    scope = private(args.output)
    require(not scope.exists(), 'Preserve old evidence')
    scope.mkdir(parents=True, mode=0o700)
    stem = 'ticket-backup-' + datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S') + '-' + uuid.uuid4().hex[:6]
    env = os.environ.copy()
    for key in ['DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']:
        env.pop(key, None)
    docker = ['docker', '--host=unix:///var/run/docker.sock']
    configurations = {}
    started = []
    processes = []
    checks = []

    def command(argv, name, extra=None, expected=0, timeout=180):
        with (scope / (name + '.log')).open('x') as output:
            Path(output.name).chmod(0o600)
            result = subprocess.run(argv, cwd=ROOT, env=extra or env, stdout=output,
                                    stderr=subprocess.STDOUT, timeout=timeout, umask=0o077)
        require(result.returncode == expected, 'Runtime command failed: ' + name)
        return result

    def dock(argv):
        return subprocess.check_output(docker + argv, env=env, text=True, timeout=30).strip()

    queries = {'containers': ['ps', '-aq'], 'volumes': ['volume', 'ls', '-q'], 'networks': ['network', 'ls', '-q']}
    def owned(project, kind):
        return dock(queries[kind] + ['--filter', 'label=com.docker.compose.project=' + project]).splitlines()

    def source_dependencies():
        cfg = configurations['source']
        def service(name):
            ids = dock(['ps','-q','--filter','label=com.docker.compose.project=' + cfg['COMPOSE_PROJECT_NAME'],
                        '--filter','label=com.docker.compose.service=' + name]).splitlines()
            require(len(ids)==1, 'One owned source dependency required')
            return ids[0]
        redis = service('redis')
        keys = subprocess.check_output(docker + ['exec',redis,'redis-cli','--raw','--scan','--pattern',cfg['TICKET_REDIS_NAMESPACE'] + ':*'],env=env).decode().splitlines()
        require(len(keys)<=100, 'Bounded source Redis key check')
        digests = {}
        for key in sorted(keys):
            kind = json.loads(dock(['exec',redis,'redis-cli','--json','TYPE',key]))
            require(kind=='hash', 'Source fixture only has persistent ledger hashes')
            fields = json.loads(dock(['exec',redis,'redis-cli','--json','HGETALL',key]))
            if isinstance(fields,list):
                fields = dict(zip(fields[::2],fields[1::2]))
            require(dock(['exec',redis,'redis-cli','--raw','PTTL',key])=='-1', 'Source ledger must have noTTL')
            digests[key] = hashlib.sha256(json.dumps(fields,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        probe_env = env.copy()
        probe_env.update(cfg)
        broker = subprocess.check_output([str(java),'-Dloader.path=' + str(ROOT/'backend/target/test-classes'),
                 '-Dloader.main=dev.jackteng.encore.backup.QueueProbe','-cp',str(jar),
                 'org.springframework.boot.loader.launch.PropertiesLauncher'],env=probe_env,text=True,timeout=15)
        rows = sorted(line for line in broker.splitlines() if line.startswith(('purchase|','retry|','dead|')))
        require(len(rows)==3, 'Original three source queues required')
        return {'redis':digests,'broker':rows}

    def until(predicate, seconds=55):
        deadline = time.monotonic() + seconds
        while not predicate():
            require(time.monotonic() < deadline, 'Runtime observation deadline exceeded; preserve failure')
            time.sleep(0.25)

    def client():
        jar = http.cookiejar.CookieJar()
        return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar)), jar

    def request(actor, base, path, body=None, staff=False, expected=(200,)):
        headers = {}
        data = None
        if body is not None:
            token = request(actor, base, '/auth/csrf' if staff else '/customer/auth/csrf')
            headers[token['headerName']] = token['token']
            form = path == '/auth/login'
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            data = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        try:
            with actor.open(urllib.request.Request(base + path, data=data, headers=headers), timeout=15) as response:
                code, raw = response.status, response.read()
        except urllib.error.HTTPError as response:
            code, raw = response.code, response.read()
        require(code in expected, 'Unexpected real HTTP status: ' + str(code))
        return json.loads(raw)

    def launch(name, extra, role='api', port=19110):
        output = (scope / (name + '.log')).open('x')
        Path(output.name).chmod(0o600)
        argv = jvm + ['--spring.profiles.active=' + role]
        if role == 'api':
            argv += ['--server.port=' + str(port)]
        process = subprocess.Popen(argv, cwd=ROOT, env=extra, stdout=output, stderr=subprocess.STDOUT)
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

    def check(name):
        checks.append(name)
        print('PASS ' + name, flush=True)

    def stamp(**values):
        return {'requestId': str(uuid.uuid4()), 'expectedVersion': 0, **values}

    def tool(action, database, name, *options, expected=0):
        return command(['python', 'scripts/backup_restore.py', action, '--env', str(database.file), *options], name, expected=expected)

    try:
        for port in [25432, 26379, 25672, 26432, 27379, 26672, 27432, 19110, 19210]:
            with socket.socket() as sock:
                require(sock.connect_ex(('127.0.0.1', port)) != 0, 'Required own port occupied')
        for role, pg, redis, mq, api in [('source', 25432, 26379, 25672, 19110), ('target', 26432, 27379, 26672, 19210), ('failure',27432,28379,27672,19310)]:
            envfile = scope / (role + '.env')
            # Each generator gets its own directory because redis.conf cannot be overwritten.
            envfile = scope / role / 'runtime.env'
            command(['python', 'scripts/init_demo.py', '--loopback-http', '--env', str(envfile)], 'init-' + role)
            command(['python', 'scripts/init_mq.py', '--env', str(envfile)], 'init-mq-' + role)
            cfg = dict(line.split('=', 1) for line in envfile.read_text().splitlines() if line and not line.startswith('#'))
            project = stem + '-' + {'source':'src','target':'dst','failure':'bad'}[role]
            require(not any(owned(project, kind) for kind in queries), 'Existing scope refused')
            cfg.update(COMPOSE_PROJECT_NAME=project, TICKET_PG_PORT=str(pg), TICKET_DB_URL='jdbc:postgresql://127.0.0.1:' + str(pg) + '/ticket_demo',
                       TICKET_REDIS_PORT=str(redis), TICKET_MQ_PORT=str(mq), TICKET_PORT=str(api),
                       TICKET_REDIS_NAMESPACE='encore-demo-' + project, TICKET_MQ_NAMESPACE='encore-demo-' + project)
            envfile.write_text(''.join(key + '=' + value + '\n' for key, value in cfg.items()))
            envfile.chmod(0o600)
            compose = docker + ['compose', '--env-file', str(envfile), '-f', str(ROOT/'infra/compose.yml'), '-f', str(ROOT/'infra/compose.mq.yml')]
            if args.compose_override:
                overlay = private(args.compose_override)
                require(all(line.strip() in ['', 'services:', 'postgres:', 'rabbitmq:'] or
                            __import__('re').fullmatch(r'image: ticket-k6-flat-(?:pg|mq):[a-z0-9._-]+', line.strip())
                            for line in overlay.read_text().splitlines()), 'Owned image-only overlay required')
                compose += ['-f', str(overlay)]
            started.append((role, project, compose))
            command(compose + ['up', '-d', '--wait'] + (['postgres'] if role == 'failure' else []), 'start-' + role)
            configurations[role] = cfg
        source = Database(scope/'source/runtime.env')
        target = Database(scope/'target/runtime.env')
        failure_target = Database(scope/'failure/runtime.env')
        source.empty()
        target.empty()
        java = Path(args.java_home).resolve()/'bin/java'
        jar = ROOT/'backend/target/ticket-platform-0.1.0.jar'
        require(java.is_file() and jar.is_file(), 'New verified package and installed JDK required')
        hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in
                  [jar, Path(__file__).resolve(), ROOT/'scripts/backup_restore.py', *sorted((ROOT/'backend/src/main/resources/db/migration').glob('*.sql'))]}
        jvm = [str(java), '-Xmx256m', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=2', '-jar', str(jar)]
        source_env = env.copy()
        source_env.update(configurations['source'])
        source_env.update(TICKET_ORDERS_ENABLED='true', TICKET_PURCHASES_ENABLED='true', TICKET_MOCK_PAYMENTS_ENABLED='true',
                          TICKET_ISSUANCE_ENABLED='true', TICKET_CHECK_IN_ENABLED='true', TICKET_ADMISSION_WINDOW_ENABLED='true',
                          TICKET_DELIVERY_ENABLED='true', TICKET_PUBLISHER_ENABLED='true', TICKET_CONSUMER_ENABLED='false',
                          TICKET_PREDEBIT_ENABLED='true', TICKET_STOCK_MAINTENANCE_ENABLED='true', TICKET_STOCK_RECOVERY_ENABLED='true')
        src_api = launch('source-api', source_env)
        src_base = 'http://127.0.0.1:19110/api'
        dst_base = 'http://127.0.0.1:19210/api'
        until(lambda: ready(src_base))
        staff, staff_cookies = client()
        request(staff, src_base, '/auth/login', {'username': source.cfg['TICKET_STAFF_USERNAME'], 'password': source.cfg['TICKET_STAFF_PASSWORD']}, staff=True)
        buyer, buyer_cookies = client()
        request(buyer, src_base, '/customer/auth/register', {'handle': 'backup-' + uuid.uuid4().hex, 'displayName': '還原中文顧客', 'password': uuid.uuid4().hex})

        def event(name, seconds=3600, capacity=10):
            e = request(staff, src_base, '/admin/events', stamp(slug='backup-' + uuid.uuid4().hex, title='中文備份活動 ' + name,
                        description='真實核心／合成資料，不收款', venue='合成場地'), staff=True)
            now = datetime.now(timezone.utc)
            s = request(staff, src_base, '/admin/events/' + e['id'] + '/sessions', stamp(name='合成場次',
                        startsAt=(now + timedelta(days=2)).isoformat(), salesStart=(now - timedelta(minutes=1)).isoformat(),
                        salesEnd=(now + timedelta(seconds=seconds)).isoformat()), staff=True)
            t = request(staff, src_base, '/admin/sessions/' + s['id'] + '/tickets', stamp(name='一般票',priceTwd=1200,
                        capacity=capacity,maxPerCustomer=4), staff=True)
            request(staff, src_base, '/admin/events/' + e['id'] + '/publish', stamp(expectedVersion=1), staff=True)
            return e, s, t

        def place(ticket, quantity=1):
            return {'requestId': str(uuid.uuid4()), 'ticketId': ticket['id'], 'quantity': quantity,
                    'expectedTicketVersion': ticket['version'], 'expectedTotalTwd': ticket['priceTwd'] * quantity}

        _, paid_session, paid_ticket = event('票券')
        paid_body = place(paid_ticket, 2)
        paid = request(buyer, src_base, '/customer/orders', paid_body)
        payment_body = {'requestId': str(uuid.uuid4()), 'expectedVersion': paid['version'], 'expectedTotalTwd': paid['totalTwd'], 'scenario': 'SUCCESS'}
        paid = request(buyer, src_base, '/customer/orders/' + paid['id'] + '/payments', payment_body)
        paid = request(buyer, src_base, '/customer/orders/' + paid['id'] + '/payments/' + paid['payment']['id'] + '/reconcile', {'requestId': str(uuid.uuid4())})
        issue_body = {'requestId': str(uuid.uuid4()), 'expectedVersion': paid['version']}
        tickets = request(buyer, src_base, '/customer/orders/' + paid['id'] + '/tickets', issue_body)
        now = datetime.now(timezone.utc).replace(microsecond=0)
        request(staff, src_base, '/admin/admissions/windows/' + paid_session['id'], stamp(opensAt=(now-timedelta(minutes=1)).strftime('%Y-%m-%dT%H:%M:%SZ'),
                closesAt=(now+timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ')), staff=True)
        checkin_body = {'requestId': str(uuid.uuid4()), 'code': tickets['tickets'][0]['code'], 'expectedSessionId': paid_session['id'], 'expectedVersion': 1}
        checked = request(staff, src_base, '/admin/admissions/check-ins', checkin_body, staff=True)
        tickets = request(buyer, src_base, '/customer/orders/' + paid['id'] + '/tickets')
        _, _, normal_ticket = event('長保票')
        hold_body = place(normal_ticket)
        hold = request(buyer, src_base, '/customer/orders', hold_body)
        cancel = request(buyer, src_base, '/customer/orders', place(normal_ticket))
        cancel_body = {'requestId': str(uuid.uuid4()), 'expectedVersion': cancel['version']}
        request(buyer, src_base, '/customer/orders/' + cancel['id'] + '/cancel', cancel_body)
        _, _, published_ticket = event('已發布未處理')
        _, _, unpublished_ticket = event('未發布')
        _, _, stock_ticket = event('預扣')
        projection = request(staff, src_base, '/admin/stock/tickets/' + stock_ticket['id'])
        rebuild = request(staff, src_base, '/admin/stock/tickets/' + stock_ticket['id'] + '/rebuilds',
                          {'requestId': str(uuid.uuid4()), 'expectedSourceRevision': projection['sourceRevision']}, staff=True, expected=(200,202))
        require(rebuild['state'] == 'READY', 'Source original Redis generation must be real READY')
        publisher_env = source_env.copy()
        publisher_env.update(TICKET_DEMO_BOOTSTRAP='false', TICKET_STOCK_RECOVERY_ENABLED='false')
        publisher = launch('source-publisher', publisher_env, role='worker')
        published_body = place(published_ticket)
        published = request(buyer, src_base, '/customer/purchases', published_body, expected=(202,))
        until(lambda: source.sql("SELECT count(*) FROM outbox_publication d JOIN purchase_outbox o ON o.id=d.event_id WHERE o.purchase_id='" + published['id'] + "' AND d.published_at IS NOT NULL;") == '1')
        stop(publisher)
        stock_body = place(stock_ticket)
        stock = request(buyer, src_base, '/customer/purchase-attempts', stock_body, expected=(200,202))
        require(stock['purchase'] is not None and stock['purchase']['status'] == 'PROCESSING', 'Real linked stock pending required')
        unpublished_body = place(unpublished_ticket)
        unpublished = request(buyer, src_base, '/customer/purchases', unpublished_body, expected=(202,))
        _, _, short_ticket = event('停機跨期限', seconds=20)
        short_body = place(short_ticket)
        short = request(buyer, src_base, '/customer/orders', short_body)
        save(scope/'fixture.json', {'paid':paid,'paidBody':paid_body,'issueBody':issue_body,'tickets':tickets,'checkInBody':checkin_body,
             'checkInReceipt':checked,'hold':hold,'holdBody':hold_body,'cancel':cancel,'cancelBody':cancel_body,
             'published':published,'publishedBody':published_body,'unpublished':unpublished,'unpublishedBody':unpublished_body,
             'stock':stock,'stockBody':stock_body,'short':short,'shortBody':short_body,
             'staffCookies':[{key:getattr(cookie,key)for key in ['name','value','domain','path']}for cookie in staff_cookies],
             'buyerCookies':[{key:getattr(cookie,key)for key in ['name','value','domain','path']}for cookie in buyer_cookies]})
        stop(src_api)
        source.offline()
        before = source.fingerprint()
        original_dependencies = source_dependencies()
        save(scope/'source-dependencies-before.json',original_dependencies)
        check('source real paid tickets/check-in/cancel/held/published and unpublished purchases/linked stock pending')
        tool('backup', source, 'backup', '--output', str(scope/'backup'))
        require(source.fingerprint() == before, 'Backup source changed')
        check('quiescent custom backup preserves all source rows and sequences')
        # Recover live pending work first; failure guards run afterwards without deadline changes.
        tool('restore', target, 'restore', '--backup', str(scope/'backup'), '--receipt', str(scope/'restored.json'))
        require(target.fingerprint() == before, 'Restore fingerprint differs')
        check('new isolated target exact rows/sequences/functions and migration data restored')
        for attempt in [1,2]:
            tool('replay', target, 'replay-' + str(attempt), '--receipt', str(scope/'restored.json'), '--java-home', str(args.java_home),
                 '--output', str(scope/('replay-' + str(attempt) + '.json')))
            result = json.loads((scope/('replay-' + str(attempt) + '.json')).read_text())
            require(result['confirmed'] == 1 and result['unknown'] == 0, 'Original confirmed pending must be replayed twice')
        require(target.fingerprint() == before, 'Replay changed original PostgreSQL evidence')
        check('empty new broker receives two confirmed copies of one original event without PG receipt changes')
        target_env = env.copy()
        target_env.update(configurations['target'])
        target_env.update(TICKET_DEMO_BOOTSTRAP='false', TICKET_MONITORING_ENABLED='false', TICKET_DELIVERY_ENABLED='true',
                          TICKET_PUBLISHER_ENABLED='true', TICKET_CONSUMER_ENABLED='true', TICKET_STOCK_RECOVERY_ENABLED='true',
                          TICKET_EXPIRY_ENABLED='true', TICKET_ORDERS_ENABLED='false', TICKET_PURCHASES_ENABLED='false', TICKET_PREDEBIT_ENABLED='false')
        dst_api = launch('target-api', target_env, port=19210)
        until(lambda: ready(dst_base))
        require(request(buyer, dst_base, '/customer/orders/' + paid['id'])['status'] == 'PAID', 'Restored owner session/order failed')
        restored_tickets = request(buyer, dst_base, '/customer/orders/' + paid['id'] + '/tickets')
        require(restored_tickets['tickets'] == tickets['tickets'], 'Original ticket IDs/codes/states not preserved')
        require(request(staff, dst_base, '/admin/admissions/check-ins', checkin_body, staff=True) == checked, 'Original admission receipt not preserved')
        request(buyer, dst_base, '/customer/orders', place(normal_ticket), expected=(409,))
        request(buyer, dst_base, '/customer/purchases', place(normal_ticket), expected=(409,))
        request(buyer, dst_base, '/customer/purchase-attempts', place(stock_ticket), expected=(409,))
        check('restored owner/ticket/admission evidence works while all new ordering gates stay disabled')
        worker = launch('target-worker', target_env, role='worker')
        pending = [published, unpublished, stock['purchase']]
        until(lambda: target.sql("SELECT count(*) FROM purchase_request WHERE status='PROCESSING';") == '0')
        for item in pending:
            restored = request(buyer, dst_base, '/customer/purchases/' + item['id'])
            require(restored['status'] == 'RESERVED', 'Original live request expired or failed during restore; preserve failure')
            require(target.sql("SELECT count(*) FROM ticket_order WHERE request_id='" + item['requestId'] + "';") == '1', 'Duplicate order from replay')
        check('published duplicate/unpublished Outbox/linked stock each recover exactly one original order')
        until(lambda: target.sql("SELECT count(*) FROM stock_hold_ack WHERE resolution='KEY_MISSING';") == '1')
        require(target.sql("SELECT state FROM stock_projection WHERE ticket_id='" + stock_ticket['id'] + "';") == 'DIRTY', 'Missing Redis must remain fenced')
        check('missing original Redis generation gets KEY_MISSING proof and DIRTY gate, no blind reseed or added stock')
        until(lambda: request(buyer, dst_base, '/customer/orders/' + short['id'])['status'] == 'EXPIRED')
        require(request(buyer, dst_base, '/customer/orders', short_body)['status'] == 'EXPIRED', 'Expired original replay revived order')
        require(target.sql("SELECT held FROM ticket_type WHERE id='" + short_ticket['id'] + "';") == '0', 'Overdue hold not released')
        require(request(buyer, dst_base, '/customer/orders', paid_body)['id'] == paid['id'], 'Paid original replay lost')
        require(request(buyer, dst_base, '/customer/orders', hold_body)['id'] == hold['id'], 'Live hold replay lost')
        require(request(buyer, dst_base, '/customer/orders/' + cancel['id'] + '/cancel', cancel_body)['status'] == 'CANCELLED', 'Original cancelled replay lost')
        check('real stop across original20s sales deadline expires once; paid/live/cancelled UUIDs remain original')
        stop(worker)
        stop(dst_api)
        after = target.fingerprint()
        # Nonempty and source targets must be refused before any pg_restore writes.
        tool('restore', target, 'refuse-nonempty', '--backup', str(scope/'backup'), '--receipt', str(scope/'nonempty.json'), expected=1)
        tool('restore', source, 'refuse-source', '--backup', str(scope/'backup'), '--receipt', str(scope/'source-refused.json'), expected=1)
        require(target.fingerprint() == after and source.fingerprint() == before, 'Refused restore changed data')
        check('restore onto source or nonempty target refused without changing either database')
        # A third independent empty PostgreSQL exercises real mid-restore rollback.
        failures = []
        for name in ['checksum', 'payload']:
            directory = scope/name
            directory.mkdir(mode=0o700)
            manifest = json.loads((scope/'backup/manifest.json').read_text())
            original = (scope/'backup/database.dump').read_bytes()
            damaged = original[:-64]
            archive = directory/'database.dump'
            archive.write_bytes(damaged)
            archive.chmod(0o600)
            if name == 'payload':
                manifest['archiveSha256'] = hashlib.sha256(damaged).hexdigest()
            save(directory/'manifest.json', manifest)
            tool('restore', failure_target, 'reject-' + name, '--backup', str(directory), '--receipt', str(scope/('bad-' + name + '.json')), expected=1)
            failure_target.empty()
            if name == 'payload':
                require('transaction rolled back and target remains empty' in (scope/'reject-payload.log').read_text(),
                        'Must verify a genuine pg_restore failure, not only checksum/TOC preflight')
            failures.append(name + ' rejected with empty target')
        require(source.fingerprint() == before and target.fingerprint() == after, 'Corruption guard changed existing data')
        observed_dependencies = source_dependencies()
        save(scope/'source-dependencies-after.json',observed_dependencies)
        require(observed_dependencies == original_dependencies, 'Original source Redis or MQ changed during isolated restore')
        check('checksum corruption rejected and truncated payload genuinely rolls back; third target remains empty')
        invariants = {}
        for name in ['payment','purchase','delivery','stock_purchase','stock_recovery','ticket','admission_window']:
            raw = target.sql((ROOT/('scripts/verify_' + name + '_invariants.sql')).read_text())
            values = json.loads(raw) if raw.startswith('{') else {key:int(value)for key,value in (line.split('|')for line in raw.splitlines())}
            invariants.update({name+'.'+key:value for key,value in values.items()})
        require(all(value == 0 for value in invariants.values()), 'Restored invariant violation')
        require(all(hashlib.sha256((ROOT/path).read_bytes()).hexdigest() == digest for path,digest in hashes.items()), 'Runtime artifacts changed')
        save(scope/'result.json', {'milestone':'M6C','passed':len(checks),'checks':checks,'sourceTables':len(before['tables']),
            'sourceRows':sum(table['rows']for table in before['tables'].values()),'sequenceCount':len(before['sequences']),
            'sourceUnchanged':source.fingerprint()==before,'sourceRedisMqUnchanged':True,'initialRestoreExact':True,'confirmedEventReplayedTwice':True,
            'pendingRequestsRecovered':len(pending),'newOrderingDisabled':True,'originalDeadlinesChanged':False,
            'missingRedisDecision':'KEY_MISSING','invariants':invariants,'artifactHashes':hashes,
            'atomicFailureRuntimeVerified':True,'realPayments':False,'publicTls':False,'crossHostHa':False,
            'transport':'production JAR host API/Worker and separate real PG/Redis/single MQ scopes; true HTTP business seed'})
    finally:
        for name,process,output in reversed(processes):
            stop(process)
            output.close()
        remaining = {}
        for role,project,compose in reversed(started):
            command(compose + ['down','-v','--remove-orphans'], 'dispose-' + role)
            remaining[role] = {kind:len(owned(project,kind))for kind in queries}
        require(all(not any(values.values())for values in remaining.values()), 'Owned cleanup incomplete')
        save(scope/'cleanup.json', {'scopes':remaining,'allOwnedProcessesStopped':all(process.poll()is not None for _,process,_ in processes)})


if __name__ == '__main__':
    main()
