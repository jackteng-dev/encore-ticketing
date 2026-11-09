#!/usr/bin/env python3
"""Quiescent ticket_demo backup and isolated empty-target restore. Never clean/overwrite.

Only own repository private env/archives and a local Docker socket are accepted.
Dump contains credentials/session hashes/bearer ticket codes: keep private and encrypt off-host separately.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from run_load_runtime import ROOT, private, require, save


class Database:
    def __init__(self, envfile):
        self.file = private(envfile)
        require(self.file.is_file(), 'Private env missing')
        self.cfg = dict(line.split('=', 1) for line in self.file.read_text().splitlines() if line and not line.startswith('#'))
        self.project = self.cfg.get('COMPOSE_PROJECT_NAME', '')
        require(re.fullmatch(r'ticket-[a-z0-9-]{1,80}', self.project), 'Explicit own ticket scope required')
        self.env = os.environ.copy()
        for key in ['DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']:
            self.env.pop(key, None)
        self.docker = ['docker', '--host=unix:///var/run/docker.sock']
        ids = subprocess.check_output(self.docker + ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + self.project,
                                      '--filter', 'label=com.docker.compose.service=postgres'], env=self.env, text=True, timeout=20).splitlines()
        require(len(ids) == 1, 'Exactly one running owned PostgreSQL required')
        self.cid = ids[0]
        require(self.sql('SELECT current_database();') == 'ticket_demo', 'Only isolated ticket_demo supported')

    def pg(self, program, *args, input=None, stdout=subprocess.PIPE):
        return subprocess.run(self.docker + ['exec', '-i', self.cid, program, '-U', self.cfg['TICKET_DB_USER'], *args],
                              env=self.env, input=input, stdout=stdout, stderr=subprocess.PIPE, timeout=120, check=True)

    def sql(self, statement):
        result = self.pg('psql', '-d', 'ticket_demo', '-XAt', '-v', 'ON_ERROR_STOP=1',
                         input=('BEGIN READ ONLY;\n' + statement + '\nCOMMIT;').encode()).stdout.decode()
        return '\n'.join(line for line in result.splitlines() if line not in ['BEGIN', 'COMMIT']).strip()

    def offline(self):
        require(self.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                         "AND backend_type='client backend' AND pid<>pg_backend_pid();") == '0',
                'Stop source/target applications and other database clients first')

    def empty(self):
        count = self.sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                         "WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema' AND c.relkind IN ('r','p','v','m','S');")
        require(count == '0', 'Target contains user relations; restore refused without writes')
        require(self.sql("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                         "WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema';") == '0',
                'Target contains user functions; restore refused')

    def fingerprint(self):
        tables = self.sql("SELECT quote_ident(n.nspname)||'.'||quote_ident(c.relname) FROM pg_class c "
                          "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' "
                          "AND n.nspname<>'information_schema' AND c.relkind IN ('r','p') ORDER BY 1;").splitlines()
        # Sequence state must survive too: matching rows alone is not a complete logical restore.
        sequences = self.sql("SELECT quote_ident(n.nspname)||'.'||quote_ident(c.relname) FROM pg_class c "
                             "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' "
                             "AND n.nspname<>'information_schema' AND c.relkind='S' ORDER BY 1;").splitlines()
        require(len(tables) <= 100 and len(sequences) <= 100, 'Bounded demonstration schema exceeded')
        def literal(value):
            return "'" + value.replace("'", "''") + "'"
        # All row digests/counts and sequence values share one read-only snapshot.
        # Built-in PostgreSQL SHA256 avoids a pgcrypto extension and per-row Docker RPCs.
        table_parts = []
        for table in tables:
            table_parts.append('SELECT ' + literal(table) + " name,(SELECT json_build_object('rows',count(*),'sha256',encode(sha256(convert_to("
                               "COALESCE(string_agg(row_to_json(x)::text,E'\\n' ORDER BY row_to_json(x)::text),''),'UTF8')),'hex')) FROM " + table + ' x) proof')
        sequence_parts = []
        for name in sequences:
            sequence_parts.append('SELECT ' + literal(name) + ' name,(SELECT last_value::text||\'|\'||is_called::text FROM ' + name + ') proof')
        def object_query(parts):
            return "(SELECT json_object_agg(name,proof) FROM (" + ' UNION ALL '.join(parts) + ") entries)" if parts else "'{}'::json"
        statement = "SELECT json_build_object('tables'," + object_query(table_parts) + ",'sequences'," + object_query(sequence_parts) + ",'schema',(SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public'));"
        return json.loads(self.sql(statement))


def bundle(path):
    directory = private(path)
    require(directory.is_dir(), 'Backup bundle missing')
    manifest = json.loads((directory / 'manifest.json').read_text())
    archive = directory / 'database.dump'
    require(archive.is_file() and archive.stat().st_size <= 64 * 1024 * 1024, 'Bounded64MiB archive required')
    require(archive.read_bytes()[:5] == b'PGDMP', 'PostgreSQL custom archive required')
    require(hashlib.sha256(archive.read_bytes()).hexdigest() == manifest['archiveSha256'], 'Archive checksum mismatch')
    require(manifest['format'] == 1 and manifest['database'] == 'ticket_demo' and manifest['quiescent'], 'Supported offline backup required')
    return directory, manifest, archive


def backup(database, output):
    directory = private(output)
    require(not directory.exists(), 'Preserve prior backup; use a new directory')
    database.offline()
    before = database.fingerprint()
    require(int(database.sql('SELECT max(version::int) FROM flyway_schema_history WHERE success;')) == 24, 'Verified V24 source required')
    directory.mkdir(parents=True, mode=0o700)
    archive = directory / 'database.dump'
    with archive.open('xb') as stream:
        archive.chmod(0o600)
        database.pg('pg_dump', '-d', 'ticket_demo', '--format=custom', '--no-owner', '--no-privileges', stdout=stream)
    require(archive.stat().st_size <= 64 * 1024 * 1024, 'Bounded backup exceeded64MiB; preserve evidence')
    database.offline()
    require(database.fingerprint() == before, 'Source changed during backup; do not use this archive')
    save(directory / 'manifest.json', {'format': 1, 'database': 'ticket_demo', 'quiescent': True,
         'sourceProject': database.project, 'sourceContainer': database.cid,
         'postgresVersion': database.sql('SHOW server_version;'), 'archiveSha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
         'fingerprint': before, 'migrations': database.sql('SELECT version,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank;')})
    return {'state': 'BACKED_UP', 'tables': len(before['tables']), 'rows': sum(value['rows'] for value in before['tables'].values())}


def restore(database, source, receipt):
    _, manifest, archive = bundle(source)
    require(database.cid != manifest['sourceContainer'] and database.project != manifest['sourceProject'], 'Cannot restore onto source')
    destination = private(receipt)
    require(not destination.exists(), 'Preserve prior restore receipt')
    destination.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    database.offline()
    database.empty()
    version = database.sql('SHOW server_version;')
    require(version.split('.')[0] == manifest['postgresVersion'].split('.')[0], 'Use matching PostgreSQL major version')
    payload = archive.read_bytes()
    # TOC validation does not prove all compressed data are intact; restore itself stays atomic.
    database.pg('pg_restore', '--list', input=payload)
    try:
        database.pg('pg_restore', '-d', 'ticket_demo', '--single-transaction', '--exit-on-error', '--no-owner', '--no-privileges', input=payload)
    except subprocess.CalledProcessError:
        database.empty()
        raise RuntimeError('Restore failed; transaction rolled back and target remains empty') from None
    database.offline()
    require(database.fingerprint() == manifest['fingerprint'], 'Restored rows/sequences differ; keep applications stopped')
    save(destination, {'state': 'RESTORED', 'targetProject': database.project, 'targetContainer': database.cid,
         'sourceProject': manifest['sourceProject'], 'sourceContainer': manifest['sourceContainer'],
         'archiveSha256': manifest['archiveSha256'], 'verifiedFingerprint': manifest['fingerprint']})
    return {'state': 'RESTORED', 'tables': len(manifest['fingerprint']['tables'])}


def replay(database, receipt, java_home, jar, output):
    proof = json.loads(private(receipt).read_text())
    require(proof['state'] == 'RESTORED' and proof['targetContainer'] == database.cid and proof['targetProject'] == database.project,
            'Original verified restore receipt must match target')
    require(proof['sourceContainer'] != database.cid and proof['sourceProject'] != database.project, 'Never replay on source')
    # The JVM uses TCP, unlike pg_dump/restore's local socket. Verify that its
    # configured PG/MQ endpoints actually belong to this restored target scope.
    for service, port, config_key in [('postgres','5432/tcp','TICKET_PG_PORT'),('rabbitmq','5672/tcp','TICKET_MQ_PORT')]:
        ids = subprocess.check_output(database.docker + ['ps','-q','--filter','label=com.docker.compose.project=' + database.project,
                                       '--filter','label=com.docker.compose.service=' + service], env=database.env,text=True,timeout=20).splitlines()
        require(len(ids)==1, 'One owned target service required for replay')
        mapping = json.loads(subprocess.check_output(database.docker + ['inspect','--format','{{json .NetworkSettings.Ports}}',ids[0]],
                            env=database.env,text=True,timeout=20))[port]
        require(mapping == [{'HostIp':'127.0.0.1','HostPort':database.cfg[config_key]}], 'Replay endpoint must map to owned loopback target')
    require(database.cfg['TICKET_DB_URL']=='jdbc:postgresql://127.0.0.1:' + database.cfg['TICKET_PG_PORT'] + '/ticket_demo'
            and database.cfg['TICKET_MQ_HOST']=='127.0.0.1', 'Target JVM routing does not match restore scope')
    database.offline()
    require(database.fingerprint() == proof['verifiedFingerprint'], 'Offline target changed since restore; reconcile explicitly')
    destination = private(output)
    require(not destination.exists(), 'Preserve replay receipt')
    destination.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    env = database.env.copy()
    env.update(database.cfg)
    env['TICKET_OFFLINE_RECOVERY'] = 'true'
    java = Path(java_home).resolve() / 'bin/java'
    artifact = (ROOT / jar).resolve()
    require(java.is_file() and artifact.is_file() and artifact.is_relative_to(ROOT), 'Installed JDK21 and own package required')
    log = destination.with_name(destination.name + '.process.log')
    require(not log.exists(), 'Preserve replay log before any broker side effect')
    result = subprocess.run([str(java), '-Xmx256m', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=2',
              '-Dloader.main=dev.elpsy.ticket.operations.PendingPurchaseReplay', '-cp', str(artifact),
              'org.springframework.boot.loader.launch.PropertiesLauncher', 'replay-pending'], cwd=ROOT, env=env,
              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    log.write_bytes(result.stdout)
    log.chmod(0o600)
    require(result.returncode == 0, 'Offline replay incomplete; see private log, retain original event identities')
    summary = json.loads(next(line.removeprefix('RECOVERY_SUMMARY ') for line in result.stdout.decode().splitlines() if line.startswith('RECOVERY_SUMMARY ')))
    require(summary['unknown'] == 0 and database.fingerprint() == proof['verifiedFingerprint'], 'Replay must not change original PostgreSQL evidence')
    save(destination, summary)
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['backup', 'restore', 'replay'])
    parser.add_argument('--env', required=True)
    parser.add_argument('--output')
    parser.add_argument('--backup')
    parser.add_argument('--receipt')
    parser.add_argument('--java-home')
    parser.add_argument('--jar', default='backend/target/ticket-platform-0.1.0.jar')
    args = parser.parse_args()
    database = Database(args.env)
    if args.action == 'backup':
        require(args.output is not None, 'Backup output required')
        result = backup(database, args.output)
    elif args.action == 'restore':
        require(args.backup is not None and args.receipt is not None, 'Backup and new receipt required')
        result = restore(database, args.backup, args.receipt)
    else:
        require(args.receipt and args.java_home and args.output, 'Verified receipt, JDK and new output required')
        result = replay(database, args.receipt, args.java_home, args.jar, args.output)
    print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        print('Backup/recovery refused or failed: ' + (str(error) if not isinstance(error, subprocess.SubprocessError) else type(error).__name__), file=sys.stderr)
        raise SystemExit(1)
