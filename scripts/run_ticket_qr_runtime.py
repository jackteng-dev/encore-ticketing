#!/usr/bin/env python3
"""Run the final JAR/dist against a NEW, explicitly owned loopback QR scope.

Private credentials, bearer QR images, logs and fixtures remain in ignored work.
The caller creates/disposes only this scope. No public service or real payment.
"""
import argparse, hashlib, json, os, signal, socket, subprocess, time, urllib.request, zipfile
from pathlib import Path
from run_stock_commit_experiment import ROOT, private_path, require

def main():
    parser = argparse.ArgumentParser()
    for name in ('env', 'output'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    config = dict(line.split('=', 1) for line in private_path(args.env).read_text().splitlines() if line and not line.startswith('#'))
    project = config['COMPOSE_PROJECT_NAME']
    require(project == 'ticket-qr-runtime-e3', 'Own QR scope required')
    require(config['TICKET_DB_URL'] == 'jdbc:postgresql://127.0.0.1:25433/ticket_demo' and config['TICKET_PORT'] == '19111' and config['TICKET_REDIS_HOST'] == '127.0.0.1', 'Own loopback required')
    scope = private_path(args.output)
    require(not scope.exists(), 'Preserve prior evidence')
    scope.mkdir(mode=0o700)
    env = os.environ.copy()
    env.update(config)
    denv = env.copy()
    for key in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'):
        denv.pop(key, None)
    docker = ['docker', '--host=unix:///var/run/docker.sock']
    def dock(arguments):
        return subprocess.check_output(docker+arguments, env=denv, text=True, timeout=60).strip()
    for name, port, hostport in [(project+'-postgres-1', '5432/tcp', '25433'), (project+'-redis-1', '6379/tcp', '26380')]:
        require(dock(['inspect', '--format', '{{ index .Config.Labels "com.docker.compose.project" }}', name]) == project, 'Ownership mismatch')
        require(json.loads(dock(['inspect', '--format', '{{json .NetworkSettings.Ports}}', name]))[port] == [{'HostIp': '127.0.0.1', 'HostPort': hostport}], 'Loopback binding mismatch')
    def sql(query):
        result = subprocess.run(docker+['exec', '-i', project+'-postgres-1', 'psql', '-U', config['TICKET_DB_USER'], '-d', 'ticket_demo', '-XAt', '-v', 'ON_ERROR_STOP=1'], env=denv, input='BEGIN READ ONLY;\n'+query+'\nCOMMIT;', text=True, capture_output=True, check=True, timeout=30)
        return '\n'.join(line for line in result.stdout.splitlines() if line not in ('BEGIN', 'COMMIT')).strip()
    require(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';") == '0', 'NEW EMPTY PG required')
    jar = ROOT/'backend/target/ticket-platform-0.1.0.jar'
    with zipfile.ZipFile(jar) as artifact:
        migrations = [name for name in artifact.namelist() if name.startswith('BOOT-INF/classes/db/migration/V')]
        require(len(migrations) == 20, 'Expected unchanged V20 artifact')
        for name in migrations:
            require(artifact.read(name) == (ROOT/'backend/src/main/resources'/name.removeprefix('BOOT-INF/classes/')).read_bytes(), 'Migration artifact mismatch')
    api = preview = None
    handles, results = [], []
    def log(name):
        handle = (scope/name).open('x')
        Path(handle.name).chmod(0o600)
        handles.append(handle)
        return handle
    def start(enabled, name):
        nonlocal api
        e = env.copy()
        e.update(TICKET_ORDERS_ENABLED='true', TICKET_MOCK_PAYMENTS_ENABLED='true', TICKET_ISSUANCE_ENABLED=str(enabled).lower(), TICKET_CHECK_IN_ENABLED=str(enabled).lower(), TICKET_DEMO_BOOTSTRAP=str(enabled).lower())
        api = subprocess.Popen([args.java, '-Xmx384m', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=2', '-jar', str(jar)], cwd=ROOT, env=e, stdout=log(name), stderr=subprocess.STDOUT)
        deadline = time.monotonic()+60
        while True:
            require(api.poll() is None, 'API exited; inspect private log')
            try:
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:19111/api/auth/csrf', timeout=1) as response:
                    if response.status == 200:
                        return
            except Exception:
                pass
            require(time.monotonic() < deadline, 'API startup deadline')
            time.sleep(.2)
    def phase(name):
        e = env.copy()
        e.update(TICKET_RUNTIME_ENV=str(private_path(args.env)), TICKET_TICKET_FIXTURE=str(scope/'original.json'), TICKET_TICKET_OUTPUT=str(scope/(name+'.json')), TICKET_FRONTEND_URL='http://127.0.0.1:15181')
        subprocess.run(['node', 'scripts/smoke_ticket_qr_browser.cjs', name], cwd=ROOT, env=e, stdout=log(name+'.log'), stderr=subprocess.STDOUT, check=True, timeout=180)
        result = json.loads((scope/(name+'.json')).read_text())
        results.append(result)
        print(name+': '+str(result['passed'])+' groups passed', flush=True)
    try:
        start(True, 'api-on.log')
        e = env.copy()
        e['TICKET_API_TARGET'] = 'http://127.0.0.1:19111'
        preview = subprocess.Popen(['npm', 'run', 'preview', '--', '--port', '15181'], cwd=ROOT/'frontend', env=e, stdout=log('preview.log'), stderr=subprocess.STDOUT, start_new_session=True)
        deadline = time.monotonic()+30
        while True:
            try:
                with socket.create_connection(('127.0.0.1', 15181), timeout=.5):
                    break
            except OSError:
                pass
            require(time.monotonic() < deadline, 'Preview startup deadline')
            time.sleep(.2)
        phase('prepare')
        api.terminate()
        api.wait(timeout=30)
        api = None
        start(False, 'api-off.log')
        phase('resume')
        checks = []
        for file in ('verify_stock_origin_invariants.sql', 'verify_ticket_invariants.sql', 'verify_ticket_recovery_invariants.sql'):
            for line in sql((ROOT/'scripts'/file).read_text()).splitlines():
                name, count = line.split('|')
                require(count == '0', 'Readonly invariant violation: '+name)
                checks.append(name)
        result = {'groups': sum(r['passed'] for r in results), 'phases': results, 'finalJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'migrations': len(migrations), 'actualApiRestartWithFlagsOff': True, 'zeroViolationChecks': checks, 'physicalCameraOrPhoneVerified': False, 'admissionTimeWindowImplemented': False, 'realPayment': False, 'publicDeployment': False}
        out = scope/'result.json'
        out.write_text(json.dumps(result, indent=2)+'\n')
        out.chmod(0o600)
        print('Final QR browser/restart proof complete', flush=True)
    finally:
        if api is not None and api.poll() is None:
            api.terminate()
            api.wait(timeout=30)
        if preview is not None:
            try:
                os.killpg(preview.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            preview.wait(timeout=30)
        for handle in handles:
            handle.close()

if __name__ == '__main__':
    main()
