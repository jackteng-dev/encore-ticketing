#!/usr/bin/env python3
"""Test-only, final-JAR domain proof with an actual outage of OWN isolated Redis.

Requires a completed mvn package and an already running disposable Compose scope.
Never downloads tools, starts a production writer or disposes any resources.
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

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'work'


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def private_path(argument):
    path = (ROOT / argument).resolve()
    require(path.is_relative_to(WORK.resolve()), 'Private paths must be inside repository work/.')
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env', required=True)
    parser.add_argument('--output', required=True, help='New private output directory')
    parser.add_argument('--java', default='java')
    parser.add_argument('--experiment', choices=('commit', 'multi'), default='commit')
    args = parser.parse_args()
    config = dict(line.split('=', 1) for line in private_path(args.env).read_text().splitlines()
                  if line and not line.startswith('#'))
    project = config.get('COMPOSE_PROJECT_NAME', '')
    prefix = 'increment' if args.experiment == 'commit' else 'multi'
    runner = 'StockCommitCursorExperimentRunner' if args.experiment == 'commit' else 'StockMultiCursorExperimentRunner'
    resource_prefix = 'commit' if args.experiment == 'commit' else 'multi'
    resources = tuple(resource_prefix + '-cursor' + suffix for suffix in ('.lua', '-schema.sql'))
    require(re.fullmatch(r'ticket-' + prefix + r'-[a-z0-9-]{1,32}', project), 'Requires own matching experiment project.')
    require(re.fullmatch(r'encore-demo-increment-[a-z0-9-]{1,28}', config.get('TICKET_REDIS_NAMESPACE', '')),
            'Requires isolated experiment namespace.')
    require(config.get('TICKET_REDIS_HOST') == '127.0.0.1'
            and re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:[0-9]+/ticket_demo', config.get('TICKET_DB_URL', '')),
            'Own loopback ticket_demo / Redis required.')
    port = int(config['TICKET_REDIS_PORT'])
    require(1024 <= port <= 65535, 'Invalid private Redis port.')
    scope = private_path(args.output)
    require(not scope.exists(), 'Existing experiment output preserved; choose a new private directory.')
    scope.mkdir(mode=0o700, parents=True)
    env = os.environ.copy()
    env.update(config)  # Keep inherited proxy and CA trust.
    docker_env = env.copy()
    for name in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'):
        docker_env.pop(name, None)
    docker = ['docker', '--host=unix:///var/run/docker.sock']
    container = project + '-redis-1'

    def docker_run(arguments):
        return subprocess.run(docker + arguments, env=docker_env, cwd=ROOT, text=True,
                              capture_output=True, check=True, timeout=60).stdout.strip()

    label = docker_run(['inspect', '--format', '{{ index .Config.Labels "com.docker.compose.project" }}', container])
    require(label == project, 'Redis container ownership mismatch.')
    require(docker_run(['inspect', '--format', '{{.State.Running}}', container]) == 'true', 'Own Redis must be running.')
    pg_port = int(re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:([0-9]+)/ticket_demo', config['TICKET_DB_URL']).group(1))
    for name, internal, expected in ((container, '6379/tcp', port), (project+'-postgres-1', '5432/tcp', pg_port)):
        require(docker_run(['inspect', '--format', '{{ index .Config.Labels "com.docker.compose.project" }}', name]) == project,
                'Database container ownership mismatch.')
        bindings = json.loads(docker_run(['inspect', '--format', '{{json .NetworkSettings.Ports}}', name]))
        require(bindings.get(internal) == [{'HostIp': '127.0.0.1', 'HostPort': str(expected)}],
                'Private endpoint does not match its own loopback container binding.')
    jar = ROOT / 'backend/target/ticket-platform-0.1.0.jar'
    tests = ROOT / 'backend/target/test-classes'
    require(jar.is_file() and (tests / ('dev/jackteng/encore/' + runner + '.class')).is_file(),
            'Run full mvn package before the experiment.')
    for name in resources:
        require((tests / 'experiments' / name).read_bytes()
                == (ROOT / 'backend/src/test/resources/experiments' / name).read_bytes(), 'Test resources are stale.')
    extracted = scope / 'jar'
    with zipfile.ZipFile(jar) as archive:
        require(not any('CursorExperiment' in name or '/experiments/' in name for name in archive.namelist()),
                'Test-only prototype unexpectedly packaged in production JAR.')
        for name in archive.namelist():
            if name.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')) and not name.endswith('/'):
                target = extracted / name
                require(target.resolve().is_relative_to(extracted.resolve()), 'Unsafe JAR entry.')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
    classpath = os.pathsep.join(map(str, [extracted / 'BOOT-INF/classes', extracted / 'BOOT-INF/lib/*', tests]))
    fixture = scope / 'original.json'

    def phase(name):
        log = scope / (name + '.log')
        with log.open('x') as stream:
            log.chmod(0o600)
            subprocess.run([args.java, '-Xmx384m', '-cp', classpath, 'dev.jackteng.encore.' + runner,
                            name, str(fixture), str(scope / (name + '.json'))],
                           env=env, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=90)
        result = json.loads((scope / (name + '.json')).read_text())
        print(f'{name}: {result["passed"]} checks passed; private details in work/.', flush=True)
        return result

    results = [phase('prepare')]
    aof = docker_run(['exec', container, 'redis-cli', '--raw', 'WAITAOF', '1', '0', '5000']).splitlines()
    require(aof == ['1', '0'], 'Local AOF flush was not confirmed.')
    stopped = False
    try:
        docker_run(['stop', '--time', '10', container])
        stopped = True
        with socket.socket() as check:
            require(check.connect_ex(('127.0.0.1', port)) != 0, 'Own Redis TCP still open after stop.')
        results.append(phase('offline'))
    finally:
        if stopped:
            docker_run(['start', container])  # Same container and volume, no reseed.
            deadline = time.monotonic() + 30
            while docker_run(['inspect', '--format', '{{.State.Health.Status}}', container]) != 'healthy':
                require(time.monotonic() < deadline, 'Own Redis did not become healthy after restart.')
                time.sleep(0.2)
    results.append(phase('verify'))
    report = {
        'passed': sum(result['passed'] for result in results), 'phases': results,
        'experiment': args.experiment,
        'actualRedisStopAndSameAofRestart': True, 'localWaitaofConfirmed': True,
        'redisTcpClosedDuringOutage': True, 'testClasspathOnly': True,
        'productionJarHasNoExperimentClassesOrResources': True,
        'productionJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'testResourceSha256': {name: hashlib.sha256((tests / 'experiments' / name).read_bytes()).hexdigest()
                               for name in resources},
        'pgTcpCommitAckDropInjected': False, 'redisTcpSuccessfulAckDropInjected': False,
        'clusterLoadOrHaClaim': False,
    }
    report_path = scope / 'result.json'
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    report_path.chmod(0o600)
    print(f'Experiment completed: {report["passed"]} checks. Own resources preserved for read-only verification.')


if __name__ == '__main__':
    main()
