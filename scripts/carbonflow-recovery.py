#!/usr/bin/env python3
"""Cópia consistente e restauração isolada do piloto em Docker Compose."""

import argparse
import csv
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from uuid import UUID, uuid4


ROOT = Path(__file__).resolve().parent.parent
SQL = ROOT / 'scripts' / 'recovery'
FILES = ('database.dump', 'inventory.jsonl')
EFFECT_FIELDS = ('project', 'effect', 'source', 'namespace', 'reference', 'sha256')
EFFECT_TYPES = {'ATO_EXTERNO', 'BRUTO', 'FATO_ED_EC', 'RECIBO'}
SHA256 = re.compile(r'[0-9a-f]{64}')


def digest(path):
    with path.open('rb') as data:
        return hashlib.file_digest(data, 'sha256').hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n').encode()


def write_new(path, contents):
    with path.open('xb') as output:
        output.write(contents)


def timestamp(value):
    at = datetime.fromisoformat(value)
    if at.utcoffset() is None:
        raise ValueError('Informe o fuso no instante do incidente ou da conferência.')
    return at


def safe_name(value):
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', value):
        raise argparse.ArgumentTypeError('Use um nome de projeto Compose em minúsculas, sem espaços.')
    return value


class Compose:
    def __init__(self, project, database='carbonflow', port=None):
        self.project = project
        self.database = database
        self.environment = os.environ.copy()
        if port is not None:
            self.environment['CARBONFLOW_PORT'] = str(port)

    def command(self, *args):
        return ['docker', 'compose', '--project-directory', str(ROOT), '-f', str(ROOT / 'compose.yaml'),
                '-p', self.project, *args]

    def run(self, *args, **kwargs):
        return subprocess.run(self.command(*args), cwd=ROOT, env=self.environment, check=True, **kwargs)

    def database_command(self, program, *args):
        return self.command('exec', '-T', 'db', program, '-U', 'carbonflow', '-d', self.database, *args)

    def psql(self, sql, *, output=subprocess.PIPE, variables=None):
        args = ['-X', '-qAt', '-v', 'ON_ERROR_STOP=1']
        for key, value in (variables or {}).items():
            args.extend(('-v', key + '=' + value))
        return subprocess.run(self.database_command('psql', *args), input=sql, text=True,
                              stdout=output, env=self.environment, check=True).stdout

    def image(self):
        container = self.run('ps', '-q', 'app', stdout=subprocess.PIPE, text=True).stdout.strip()
        if not container:
            return None
        return subprocess.check_output(['docker', 'inspect', '-f', '{{.Image}}', container], text=True).strip()

    @contextmanager
    def snapshot(self):
        # Uma transação viva exporta o snapshot para pg_dump e para a leitura dos metadados.
        with subprocess.Popen(self.database_command('psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'),
                              env=self.environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as exporter:
            exporter.stdin.write("""BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SELECT pg_export_snapshot() || '|' || transaction_timestamp()::text || '|' || current_setting('server_version_num');
""")
            exporter.stdin.flush()
            try:
                snapshot, at, version = exporter.stdout.readline().strip().split('|')
                if not re.fullmatch(r'[0-9A-Fa-f-]+', snapshot):
                    raise ValueError('O PostgreSQL não forneceu um snapshot válido.')
                yield snapshot, timestamp(at).isoformat(), version
            finally:
                exporter.stdin.close()
                exporter.wait(timeout=15)

    def query_at(self, script, snapshot=None, output=subprocess.PIPE):
        begin = 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
        if snapshot is not None:
            begin += f"SET TRANSACTION SNAPSHOT '{snapshot}';\n"
        return self.psql(begin + script.read_text() + '\nCOMMIT;\n', output=output)

    def require_new_destination(self):
        containers = self.run('ps', '-aq', stdout=subprocess.PIPE, text=True).stdout.strip()
        volumes = subprocess.check_output(['docker', 'volume', 'ls', '-q'], text=True).splitlines()
        if containers or self.project + '_pgdata' in volumes:
            raise ValueError('O projeto de destino já existe. Informe um novo destino isolado.')


def inventory_objects(path):
    with path.open() as rows:
        for row in rows:
            entry = json.loads(row)
            if entry['kind'] == 'DOSSIE_INCOMPLETO' or (
                    entry['kind'] == 'OBJETO' and entry.get('valid') is not True):
                raise ValueError(f"Evidência ausente ou divergente em {entry['project']} {entry['reference']}.")
            yield entry


def validate_backup(directory):
    manifest = directory / 'manifest.json'
    checksum = (directory / 'manifest.sha256').read_text().strip()
    if not SHA256.fullmatch(checksum) or digest(manifest) != checksum:
        raise ValueError('O SHA-256 do manifesto da cópia diverge.')
    data = json.loads(manifest.read_text())
    if data['format'] != 1 or set(data['files']) != set(FILES):
        raise ValueError('Formato de cópia incompatível.')
    UUID(data['backupId'])
    timestamp(data['checkpoint'])
    for name in FILES:
        path = directory / name
        record = data['files'][name]
        if not path.is_file() or path.stat().st_size != record['bytes'] or digest(path) != record['sha256']:
            raise ValueError(f'Arquivo ausente ou divergente na cópia: {name}.')
    return data, checksum


def backup(args):
    source = Compose(args.source, args.source_db)
    out = args.output.resolve()
    if out.exists():
        raise ValueError('O diretório de cópia já existe. Escolha um novo caminho.')
    with tempfile.TemporaryDirectory(prefix='.carbonflow-', dir=out.parent) as working:
        staging = Path(working) / 'backup'
        staging.mkdir()
        with source.snapshot() as (snapshot, checkpoint, pg_version):
            with (staging / FILES[0]).open('wb') as dump:
                subprocess.run(source.database_command('pg_dump', '-Fc', '--no-owner',
                               '--exclude-table-data=public.pilot_session',
                               '--exclude-table-data=public.app_account_session',
                               '--exclude-table-data=public.app_account_challenge', '--snapshot=' + snapshot),
                               stdout=dump, env=source.environment, check=True)
            with (staging / FILES[1]).open('w') as inventory:
                source.query_at(SQL / 'inventory.sql', snapshot, inventory)
            version = source.psql("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
                                  f"SET TRANSACTION SNAPSHOT '{snapshot}';\n"
                                  "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                                  "ORDER BY installed_rank DESC LIMIT 1;\nCOMMIT;").strip()
        counts = {'objects': 0, 'tables': 0, 'effects': 0}
        for record in inventory_objects(staging / FILES[1]):
            counts[{'OBJETO': 'objects', 'EFEITO': 'effects', 'TABELA': 'tables'}[record['kind']]] += 1
        data = {'format': 1, 'backupId': str(uuid4()), 'checkpoint': checkpoint, 'source': args.source,
                'postgresVersionNum': pg_version, 'schemaVersion': version, 'imageId': source.image(),
                'inventory': counts, 'files': {name: {'bytes': (staging / name).stat().st_size,
                'sha256': digest(staging / name)} for name in FILES}}
        write_new(staging / 'manifest.json', json_bytes(data))
        sha = digest(staging / 'manifest.json')
        write_new(staging / 'manifest.sha256', (sha + '\n').encode())
        staging.rename(out)
    print(json.dumps({'directory': str(out), 'manifestSha256': sha, 'checkpoint': checkpoint, **counts}))


def identity(entry):
    return tuple(entry[field] for field in EFFECT_FIELDS[:-1])


def effect_index(entries):
    index = {}
    for entry in entries:
        if set(EFFECT_FIELDS).difference(entry) or entry['effect'] not in EFFECT_TYPES                 or not isinstance(entry['sha256'], str) or not SHA256.fullmatch(entry['sha256'])                 or any(not isinstance(entry[field], str) or not entry[field] or len(entry[field]) > 500
                       or any(ord(char) < 32 for char in entry[field]) for field in EFFECT_FIELDS):
            raise ValueError('O inventário do intervalo contém uma referência inválida.')
        key = identity(entry)
        if key in index:
            raise ValueError('O inventário contém duas observações para a mesma identidade.')
        index[key] = entry
    return index


def observe(args):
    backup_record, manifest_sha = validate_backup(args.backup)
    source = Compose(args.source, args.source_db)
    before = effect_index(entry for entry in inventory_objects(args.backup / FILES[1]) if entry['kind'] == 'EFEITO')
    with source.snapshot() as (snapshot, observed, _):
        result = source.query_at(SQL / 'effects.sql', snapshot)
    if timestamp(observed) < timestamp(backup_record['checkpoint']):
        raise ValueError('A coleta do intervalo precede o ponto da cópia.')
    after = effect_index(json.loads(line) for line in result.splitlines())
    for key, entry in before.items():
        if key not in after or after[key] != entry:
            raise ValueError('Uma prova anterior desapareceu ou mudou. Confira o intervalo antes de restaurar.')
    late = [entry for key, entry in after.items() if key not in before]
    record = {'format': 1, 'backupId': backup_record['backupId'], 'manifestSha256': manifest_sha,
              'observedThrough': observed, 'source': args.source,
              'effects': [{**{field: entry[field] for field in EFFECT_FIELDS}, 'binding': entry.get('binding')}
                          for entry in late]}
    write_new(args.output, json_bytes(record))
    print(json.dumps({'gapSha256': digest(args.output), 'observedThrough': observed, 'effects': len(late)}))


def compare_inventory(expected_path, actual_path):
    from itertools import zip_longest

    for expected, actual in zip_longest(inventory_objects(expected_path), inventory_objects(actual_path)):
        if expected != actual:
            affected = expected or actual
            raise ValueError('O inventário restaurado diverge em ' + str(affected.get('reference', affected.get('name')))
                             + '. A aplicação recuperada permanece desligada.')


def install_gate(destination, record, manifest_sha, gap, gap_sha, incident):
    staged = io.StringIO()
    output = csv.writer(staged, lineterminator='\n', delimiter='\t')
    for entry in gap['effects']:
        binding = entry.get('binding')
        output.writerow([entry[name] for name in EFFECT_FIELDS] + [json.dumps(binding) if binding is not None else ''])
    sql = 'BEGIN;\n' + (SQL / 'install.sql').read_text()
    sql += "\\copy recovery_input FROM STDIN WITH (FORMAT csv, DELIMITER E'\\t')\n"
    sql += staged.getvalue() + '\\.\n' + (SQL / 'activate.sql').read_text() + '\nCOMMIT;\n'
    destination.psql(sql, variables={'backup': record['backupId'], 'manifest': manifest_sha,
                                    'checkpoint': record['checkpoint'], 'through': gap['observedThrough'],
                                    'gap': gap_sha, 'incident': incident}, output=subprocess.DEVNULL)


def restore(args):
    record, manifest_sha = validate_backup(args.backup)
    gap_sha = digest(args.gap)
    gap = json.loads(args.gap.read_text())
    if not args.target.startswith('carbonflow-recovery-') or args.target == record['source']:
        raise ValueError('O destino precisa ser um novo projeto Compose começando com carbonflow-recovery-.')
    if gap['format'] != 1 or gap['backupId'] != record['backupId'] or gap['manifestSha256'] != manifest_sha:
        raise ValueError('O inventário do intervalo pertence a outro ponto de recuperação.')
    if not timestamp(record['checkpoint']) <= timestamp(args.incident_at) <= timestamp(gap['observedThrough']):
        raise ValueError('A observação precisa cobrir o intervalo da cópia até o instante do incidente.')
    effect_index(gap['effects'])

    destination = Compose(args.target, port=args.port)
    destination.require_new_destination()
    destination.run('up', '-d', '--wait', 'db', stdout=sys.stderr)
    version = destination.psql("SHOW server_version_num;").strip()
    if version[:2] != record['postgresVersionNum'][:2]:
        raise ValueError('Use a mesma versão principal do PostgreSQL da cópia.')
    with (args.backup / FILES[0]).open('rb') as dump:
        subprocess.run(destination.database_command('pg_restore', '--no-owner', '--single-transaction', '--exit-on-error'),
                       stdin=dump, env=destination.environment, check=True)
    with tempfile.TemporaryDirectory(prefix='carbonflow-verify-') as working:
        actual = Path(working) / 'inventory.jsonl'
        with actual.open('w') as output:
            destination.query_at(SQL / 'inventory.sql', output=output)
        compare_inventory(args.backup / FILES[1], actual)
    install_gate(destination, record, manifest_sha, gap, gap_sha, args.incident_at)
    if args.run_app:
        destination.run('up', '--build', '-d', '--wait', 'app', stdout=sys.stderr)
    print(json.dumps({'project': args.target, 'port': args.port, 'manifestSha256': manifest_sha, 'gapSha256': gap_sha,
                      'inventory': record['inventory'], 'schemaVersion': record['schemaVersion'],
                      'rpoSeconds': (timestamp(args.incident_at) - timestamp(record['checkpoint'])).total_seconds(),
                      'status': 'AGUARDANDO_CONFERENCIA'}))


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    take = sub.add_parser('backup', help='Gerar cópia e inventário do mesmo snapshot')
    note = sub.add_parser('observe', help='Inventariar atos e fatos posteriores usando a fonte preservada')
    recover = sub.add_parser('restore', help='Restaurar sem substituir outro projeto Compose')
    for command in (take, note):
        command.add_argument('--source', type=safe_name, default='carbonflow')
        command.add_argument('--source-db', type=safe_name, default='carbonflow')
        command.add_argument('--output', type=Path, required=True)
    for command in (note, recover):
        command.add_argument('--backup', type=Path, required=True)
    recover.add_argument('--gap', type=Path, required=True)
    recover.add_argument('--target', type=safe_name, required=True)
    recover.add_argument('--port', type=int, default=18092)
    recover.add_argument('--incident-at', required=True)
    recover.add_argument('--run-app', action='store_true')
    args = parser.parse_args()
    try:
        {'backup': backup, 'observe': observe, 'restore': restore}[args.command](args)
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError, RuntimeError) as error:
        print(f'Falha na recuperação: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
