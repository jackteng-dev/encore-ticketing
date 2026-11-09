#!/usr/bin/env python3
"""Own disposable V13 -> V14 upgrade + explicit return handoff / actual Redis outage.

Requires completed full package, historical V13 JAR and a NEW empty own Compose
PG/Redis scope. No downloads, schema clean/repair, production background writer
or resource disposal. Raw fixtures / original UUIDs remain private work/ only.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import zipfile
from run_stock_commit_experiment import ROOT, private_path, require


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--env', required=True)
    p.add_argument('--baseline-jar', required=True)
    p.add_argument('--output', required=True)
    p.add_argument('--java', default='java')
    args = p.parse_args()
    config = dict(line.split('=', 1) for line in private_path(args.env).read_text().splitlines()
                  if line and not line.startswith('#'))
    project = config.get('COMPOSE_PROJECT_NAME', '')
    require(re.fullmatch(r'ticket-return-upgrade-[a-z0-9-]{1,24}', project), 'Own upgrade project required.')
    require(re.fullmatch(r'encore-demo-increment-[a-z0-9-]{1,28}', config.get('TICKET_REDIS_NAMESPACE', '')),
            'Own isolated fixture namespace required.')
    match = re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:([0-9]+)/ticket_demo', config.get('TICKET_DB_URL', ''))
    require(match and config.get('TICKET_REDIS_HOST') == '127.0.0.1', 'Own loopback PG/Redis required.')
    redis_port = int(config['TICKET_REDIS_PORT'])
    require(1024 <= redis_port <= 65535, 'Invalid own Redis port.')
    scope = private_path(args.output)
    require(not scope.exists(), 'Existing proof preserved; choose a new private output directory.')
    scope.mkdir(mode=0o700, parents=True)
    env = os.environ.copy(); env.update(config)  # Preserve proxy and CA.
    docker_env = env.copy()
    for key in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'):
        docker_env.pop(key, None)
    docker = ['docker', '--host=unix:///var/run/docker.sock']
    pg, redis = project+'-postgres-1', project+'-redis-1'

    def dock(cmd):
        return subprocess.run(docker+cmd, env=docker_env, cwd=ROOT, check=True,
                              capture_output=True, text=True, timeout=60).stdout.strip()

    for name, port, external in ((pg, '5432/tcp', int(match.group(1))), (redis, '6379/tcp', redis_port)):
        require(dock(['inspect', '--format', '{{ index .Config.Labels "com.docker.compose.project" }}', name]) == project,
                'Container ownership mismatch.')
        bindings = json.loads(dock(['inspect', '--format', '{{json .NetworkSettings.Ports}}', name]))
        require(bindings.get(port) == [{'HostIp': '127.0.0.1', 'HostPort': str(external)}], 'Endpoint ownership mismatch.')
        require(dock(['inspect', '--format', '{{.State.Running}}', name]) == 'true', 'Own containers must be running.')

    def sql(query):
        result = subprocess.run(docker+['exec', '-i', pg, 'psql', '-U', config['TICKET_DB_USER'], '-d',
            'ticket_demo', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1'], env=docker_env, cwd=ROOT,
            input='BEGIN READ ONLY;\n'+query+'\nCOMMIT;', check=True, text=True, capture_output=True, timeout=30)
        return '\n'.join(line for line in result.stdout.splitlines() if line not in ('BEGIN', 'COMMIT')).strip()

    require(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';") == '0', 'Own PG must be empty; never clean/reuse data.')
    tests = ROOT/'backend/target/test-classes'
    final = ROOT/'backend/target/ticket-platform-0.1.0.jar'
    baseline = private_path(args.baseline_jar)
    require(final.is_file() and baseline.is_file(), 'Final package and historical JAR required.')
    require((tests/'dev/elpsy/ticket/StockReturnHandoffRunner.class').is_file(), 'Compile test runners with full package first.')

    def extract(jar, name):
        target = scope/name
        with zipfile.ZipFile(jar) as z:
            migrations = [n for n in z.namelist() if n.startswith('BOOT-INF/classes/db/migration/V')]
            require(len(migrations) == (13 if name == 'baseline' else 14), 'Unexpected artifact schema version.')
            require(not any('/experiments/' in n or 'BaselineSeed' in n or 'HandoffRunner' in n for n in z.namelist()),
                    'Test-only code packaged in production JAR.')
            for n in z.namelist():
                if n.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')) and not n.endswith('/'):
                    out = target/n
                    require(out.resolve().is_relative_to(target.resolve()), 'Unsafe JAR entry.')
                    out.parent.mkdir(parents=True, exist_ok=True); out.write_bytes(z.read(n))
        return os.pathsep.join(map(str, [target/'BOOT-INF/classes', target/'BOOT-INF/lib/*', tests]))

    baseline_cp, final_cp = extract(baseline, 'baseline'), extract(final, 'final')
    original = scope/'original.json'

    def run(name, cp, runner, arguments):
        log = scope/(name+'.log')
        with log.open('x') as output:
            log.chmod(0o600)
            subprocess.run([args.java, '-Xmx384m', '-cp', cp, 'dev.elpsy.ticket.'+runner]+arguments,
                           cwd=ROOT, env=env, check=True, stdout=output, stderr=subprocess.STDOUT, timeout=90)

    run('baseline', baseline_cp, 'StockReturnBaselineSeed', [str(original)])
    tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename;").splitlines()

    def data():
        value = {}
        for table in tables:
            require(re.fullmatch(r'[a-z_]+', table), 'Unexpected baseline table identifier.')
            value[table] = json.loads(sql("SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb) FROM public."+table+' x;'))
        return value

    old = data()
    saved = scope/'old-public-rows.json'; saved.write_text(json.dumps(old)); saved.chmod(0o600)
    def canonical_hash(raw):
        value = json.loads(raw)
        return dict(zip(value[::2], value[1::2])) if isinstance(value, list) else value

    old_hash = canonical_hash(dock(['exec', redis, 'redis-cli', '--json', 'HGETALL',
        config['TICKET_REDIS_NAMESPACE']+':stock:{'+json.loads(original.read_text())['fixture']['ticket']+'}:ledger:g:1']))

    def phase(name):
        run(name, final_cp, 'StockReturnHandoffRunner', [name, str(original), str(scope/(name+'.json'))])
        result = json.loads((scope/(name+'.json')).read_text())
        print(f'{name}: {result["passed"]} checks passed; private details in work/.', flush=True)
        return result

    results = [phase('migrate')]
    require(data() == old, 'V14 migration changed historical public rows.')
    new_hash = canonical_hash(dock(['exec', redis, 'redis-cli', '--json', 'HGETALL',
        config['TICKET_REDIS_NAMESPACE']+':stock:{'+json.loads(original.read_text())['fixture']['ticket']+'}:ledger:g:1']))
    require(new_hash == old_hash, 'Migration changed original ledger.')
    results.append(phase('prepare'))
    require(dock(['exec', redis, 'redis-cli', '--raw', 'WAITAOF', '1', '0', '5000']).splitlines() == ['1', '0'], 'Local AOF not confirmed.')
    stopped = False
    try:
        dock(['stop', '--time', '10', redis]); stopped = True
        with socket.socket() as s:
            require(s.connect_ex(('127.0.0.1', redis_port)) != 0, 'Redis TCP still open.')
        results.append(phase('offline'))
    finally:
        if stopped:
            dock(['start', redis])  # SAME container/AOF volume, no seed.
            deadline = time.monotonic()+30
            while dock(['inspect', '--format', '{{.State.Health.Status}}', redis]) != 'healthy':
                require(time.monotonic() < deadline, 'Own Redis not healthy after restart.')
                time.sleep(0.2)
    results.append(phase('verify'))
    report = {'passed': sum(r['passed'] for r in results), 'phases': results,
        'actualV13JarToV14Migration': True, 'oldPublicTablesExcludeFlywayHistory': True,
        'oldPublicTablesUnchanged': len(tables),
        'oldPublicRowsUnchanged': sum(len(v) for v in old.values()), 'oldLedgerRawValuesUnchangedAtMigration': True,
        'returnTablesEmptyAfterUpgradeNoBackfill': True, 'productionStockReturnServiceExercised': True,
        'newGenerationActualCustomerHoldSucceeded': True, 'actualRedisStopAndSameAofRestart': True,
        'localWaitaofConfirmed': True, 'ownRedisTcpClosedDuringOutage': True,
        'productionJarsExcludeTestFixturesRunnersAndExperiments': True,
        'baselineJarSha256': hashlib.sha256(baseline.read_bytes()).hexdigest(),
        'finalJarSha256': hashlib.sha256(final.read_bytes()).hexdigest(),
        'tcpSuccessfulAckDropOrKill9Injected': False, 'automaticConsumerOrCrossHostHaClaim': False}
    report_path = scope/'result.json'; report_path.write_text(json.dumps(report, indent=2)+'\n'); report_path.chmod(0o600)
    print(f'Return handoff proof completed: {report["passed"]} checks; own resources preserved for verification.')


if __name__ == '__main__':
    main()
