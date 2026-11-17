#!/usr/bin/env python3
"""Série de carga local do kit, com cronometrias e validação das respostas."""
import argparse
import hashlib
import json
import math
import platform
import subprocess
import time
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from threading import Event, Thread
from uuid import UUID, uuid4

from common import CONTROL_PROJECT, IMPORT_PROJECT, PROJECT, PROJECTS, Compose, Failure, Session, quote, save

HEADER = 'kind,sourceFactId,start,endExclusive,zone,siteId,chargerId,vehicleId,circuitId,meterId,quantity,unit'
SOURCE = 'SIM-OP-01'
REASON = 'Massa controlada E8-012'
REFERENCE = lambda identifier: {'id': identifier, 'version': 1}
T0 = datetime(2029, 1, 1, 3, tzinfo=timezone.utc)


def now():
    return datetime.now(timezone.utc).isoformat()


def elapsed(start):
    return round((time.perf_counter() - start) * 1000, 3)


def instant(at):
    return at.isoformat().replace('+00:00', 'Z')


def fact(kind, ref, channel, start, end):
    if kind == 'ED':
        track = (f'SIM-DCFC-0{channel}', f'SIM-BEV-0{channel}', '', f'SIM-ED-0{channel}', '0.1')
    else:
        meter = '02' if channel == 2 else '04' if start >= datetime(2028, 10, 1, 3, tzinfo=timezone.utc) else '03'
        track = ('', '', f'SIM-EC-0{channel}', 'SIM-MED-' + meter, '0.12')
    return ','.join((kind, ref, instant(start), instant(end), 'America/Sao_Paulo', 'SIM-PATIO-01', *track, 'kWh'))


def batch(kind, number, prefix):
    duration = timedelta(hours=1) if kind == 'EC' else timedelta(seconds=30)
    return (HEADER + '\n' + '\n'.join(
        fact(kind, f'SIM-E812-{prefix.upper()}-{kind}-{number}-{i}', i % 2 + 1,
             T0 + (i // 2) * duration, T0 + ((i // 2) + 1) * duration)
        for i in range(10_000)
    ) + '\n').encode()


def upload(client, filename, data, expected=201):
    headers = {'Content-Type': 'text/csv', 'X-File-Name': filename,
               'X-Source-Id': SOURCE, 'X-Source-Authorization': 'SIM-AUT-ED-EC'}
    return client.call('/artifacts', data, expected=expected, headers=headers)


def enqueue(client, file_id, period, key=None):
    return client.call('/imports', {'artifact': REFERENCE(file_id),
        'mappingProfile': REFERENCE('SIM-CSV-UTF8-ED-EC'), 'sourceId': SOURCE,
        'period': REFERENCE(period)}, expected=202, key=key)


def wait_for(client, path, terminal, seconds=180):
    started = time.monotonic()
    while True:
        result = client.call(path)
        if result['state'] in terminal:
            return result
        if time.monotonic() - started > seconds:
            raise TimeoutError(f'Prazo de leitura do estado {path}: {result["state"]}')
        time.sleep(0.35)


def clear_preview(db, project, job):
    UUID(job)
    # Remove somente as prévias com prefixo de ensaio recém-medidas, mantendo os brutos e a pré-carga.
    db.sql(f"""
        BEGIN;
        CREATE TEMP TABLE measured ON COMMIT DROP AS
        SELECT j.project_id,j.id FROM pilot_import_job j JOIN pilot_import_file f
          ON (f.project_id,f.id)=(j.project_id,j.file_id)
        WHERE j.project_id={quote(project)} AND j.id={quote(job)}::uuid
          AND f.original_name LIKE 'e812-medicao-%' AND j.state='CONCLUIDO';
        DELETE FROM pilot_job_attempt a USING measured m
          WHERE (a.project_id,a.job_id)=(m.project_id,m.id) AND a.kind='IMPORTACAO';
        DELETE FROM pilot_import_row r USING measured m WHERE (r.project_id,r.job_id)=(m.project_id,m.id);
        DELETE FROM pilot_import_job j USING measured m WHERE (j.project_id,j.id)=(m.project_id,m.id);
        COMMIT;
    """)


def import_sample(db, client, kind, number, prefix, phase):
    data = batch(kind, number, prefix)
    name = f'e812-medicao-{prefix}-{kind}-{number}.csv'
    sample = {'phase': phase, 'class': kind, 'iteration': number, 'rows': 10000,
              'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'startedAt': now()}
    start = time.perf_counter()
    receipt = upload(client, name, data)['artifact']
    sample['uploadMs'] = elapsed(start)
    assert receipt['sha256'] == sample['sha256'] and receipt['bytes'] == len(data)
    start = time.perf_counter()
    key = 'e812-' + uuid4().hex
    submitted = enqueue(client, receipt['id'], 'M2', key)
    job = submitted['jobId']
    result = wait_for(client, '/jobs/' + job, {'CONCLUIDO', 'RECUSADO', 'FALHA_RECUPERAVEL'})
    sample['processingMs'] = elapsed(start)
    sample.update({'state': result['state'], 'processed': result['processed'], 'classifications': result['classifications'],
                   'job': job, 'endedAt': now()})
    assert result['state'] == 'CONCLUIDO' and result['processed'] == 10000 and result['classifications'] == {'CANDIDATA': 10000}, sample
    assert enqueue(client, receipt['id'], 'M2', key) == submitted
    clear_preview(db, client.project, job)
    return sample


def period_action(client, period, version, transition, effective_at, run=None):
    params = {'transitionId': transition, 'effectiveAt': effective_at, 'reason': REASON}
    if run is not None:
        params['run'] = {'id': run['id'], 'version': run['version']}
    return client.call('/periods/' + period + '/actions', params, version=version)


def plan(client, operator, period, end_collection):
    years = {'M1': (2027, 2028), 'M2': (2028, 2029)}
    first, last = years[period]
    start, end = f'{first}-10-01', f'{last}-10-01'
    current = next((item for item in client.call('/periods') if item['id'] == period), None)
    if current is None:
        current = client.call('/periods', {'id': period, 'start': start, 'endExclusive': end, 'reason': REASON}, version=0, expected=201)
    if current['state'] == 'PREVISTO':
        current = period_action(operator, period, current['version'], 'TX-E3-41', start + 'T03:00:00Z')
    if end_collection and current['state'] == 'COLETANDO':
        current = period_action(operator, period, current['version'], 'TX-E3-42', end + 'T03:00:00Z')
    return current


def propose(client, period, selection):
    return client.call('/runs', {'proposal': {'period': period, 'selection': REFERENCE(selection['id']),
        'baseline': REFERENCE('BASE-SYN-LDV'), 'factor': REFERENCE('EF-SYN-v1'), 'dy': REFERENCE('SIM-DY-REDE-01')}},
        expected=201)


def close_sample(prep, rev, period_state, number, phase, facts_profile):
    at = '2028-10-01T03:00:00Z'
    if period_state['state'] == 'FECHADO':
        period_state = period_action(rev, 'M1', period_state['version'], 'TX-E3-46', at)
    sample = {'phase': phase, 'iteration': number, 'startedAt': now()}
    start = time.perf_counter()
    selection = rev.call('/periods/M1/selections', b'', expected=201)
    sample['selectionMs'] = elapsed(start)
    assert selection['observations'] == 200000, selection
    run = propose(prep, 'M1', selection)
    calc_started = time.perf_counter()
    rev.call('/runs/' + run['id'] + '/actions', {'transitionId': 'CONFIRMAR', 'reason': REASON},
             version=run['version'], expected=202)
    done = wait_for(rev, '/runs/' + run['id'], {'CALCULADA', 'COM_LACUNAS', 'FALHA_RECUPERAVEL'})
    sample['calculationMs'] = elapsed(calc_started)
    assert done['state'] == 'CALCULADA', done
    close_started = time.perf_counter()
    period_state = period_action(rev, 'M1', period_state['version'], 'TX-E3-45', at, done)
    sample['decisionMs'] = elapsed(close_started)
    raw, headers = rev.request('/periods/M1/closures/' + str(period_state['revision']), decode=False)
    sample['closureMs'] = elapsed(start)
    assert period_state['state'] == 'FECHADO' and hashlib.sha256(raw).hexdigest() == headers['X-Closure-SHA256']
    snapshot, _ = rev.request('/runs/' + run['id'] + '/snapshot', decode=False)
    snapshot = json.loads(snapshot)
    assert snapshot['observations'] == 200000 and snapshot['inputs']['factor']['reference']['id'] == 'EF-SYN-v1'
    ed_count, ec_count = (182432, 17568) if facts_profile == 'hourly' else (100000, 100000)
    assert sum(cut['edCount'] for cut in snapshot['cuts']) == ed_count
    assert sum(cut['ecCount'] for cut in snapshot['cuts']) == ec_count
    expected_ed = ed_count * Decimal('0.1')
    expected_ec = ec_count * (Decimal('2') if facts_profile == 'hourly' else Decimal('0.12'))
    assert sum(Decimal(cut['edKwh']) for cut in snapshot['cuts']) == expected_ed
    assert sum(Decimal(cut['ecKwh']) for cut in snapshot['cuts']) == expected_ec
    # CAL-E2 e a mesma frota de referência do kit fixam as parcelas de M1.
    totals = done['result']['totals']
    expected_be, expected_pe = expected_ed * Decimal('0.001'), expected_ec * Decimal('0.0003')
    assert Decimal(totals['be']) == expected_be and Decimal(totals['pe']) == expected_pe
    assert Decimal(totals['er']) == expected_be - expected_pe

    sample.update({'observations': snapshot['observations'], 'observationsByClass': {'ED': ed_count, 'EC': ec_count}, 'observationsSha256': snapshot['observationsSha256'],
                   'result': done['result']['totals'], 'revision': period_state['revision'],
                   'closureSha256': headers['X-Closure-SHA256'], 'endedAt': now()})
    return period_state, sample


def percentile(values, proportion=0.95):
    ordered = sorted(values)
    return ordered[math.ceil(proportion * len(ordered)) - 1] if ordered else None


def inspect(db):
    ids = db.container_ids()
    result = subprocess.run(['docker', 'inspect', *ids.values()], text=True, capture_output=True, check=True)
    containers = []
    for item in json.loads(result.stdout):
        containers.append({'id': item['Id'][:12], 'service': item['Config']['Labels']['com.docker.compose.service'],
                           'imageId': item['Image'], 'memoryBytes': item['HostConfig']['Memory'],
                           'nanoCPUs': item['HostConfig']['NanoCpus']})
    schema = db.sql('SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;')
    versions = db.sql("SELECT current_setting('server_version');")
    return {'containers': containers, 'schema': schema, 'postgresql': versions, 'architecture': platform.machine(),
            'kernel': platform.release(), 'logicalHostCpus': subprocess.check_output(['nproc'], text=True).strip(),
            'transfer': 'HTTP loopback', 'percentile': 'nearest rank ceil(0.95*n)'}


def traffic(url, stop, results):
    peer = Session(url, 'PP').login()
    routes = ('/periods', '/dossiers', '/issuances')
    i = 0
    while not stop.is_set():
        peer.project = PROJECTS[i % 3]
        route = routes[(i // 3) % 3]
        tick = time.perf_counter()
        row = {'at': now(), 'project': peer.project, 'path': route}
        try:
            peer.call(route)
            row['status'] = 'OK'
        except (RuntimeError, OSError) as error:
            row['status'] = type(error).__name__
        row['ms'] = elapsed(tick)
        results.append(row)
        stop.wait(0.85)
        i += 1
    peer.close()


def resources(db, stop, results):
    ids = list(db.container_ids().values())
    while not stop.is_set():
        command = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids],
                                 text=True, capture_output=True, timeout=20)
        if command.returncode == 0:
            results.append({'at': now(), 'containers': [json.loads(row) for row in command.stdout.splitlines()]})
        stop.wait(4)


def counts(db):
    return db.sql("""
        SELECT json_build_object('databaseBytes',pg_database_size(current_database()),'tempBytes',temp_bytes,
          'deadlocks',deadlocks,'blocksRead',blks_read,'blocksHit',blks_hit,'transactions',xact_commit,
          'rollbacks',xact_rollback) FROM pg_stat_database WHERE datname=current_database();
    """)


def run(args):
    db = Compose(args.compose)
    if args.imports:
        # Índice do ensaio para a checagem da FK ao repor a pré-carga entre amostras.
        db.sql("CREATE INDEX IF NOT EXISTS envelope_reset_related_fk ON pilot_import_row(project_id,related_row_id);")
    result = {'kit': '0.2.14', 'profile': args.profile, 'factsProfile': args.facts_profile, 'startedAt': now(), 'environment': inspect(db),
              'before': json.loads(counts(db)), 'imports': [], 'closures': [], 'queries': [], 'resources': [], 'failures': []}
    save(args.output, result)
    op = Session(args.url, 'OP').login()
    prep = Session(args.url, 'PREP').login()
    rev = Session(args.url, 'REV').login()
    for project in PROJECTS:
        op.project = prep.project = project
        for period in ('M1', 'M2'):
            plan(prep, op, period, project == PROJECT and period == 'M1' and args.closures > 0)
    op.project = prep.project = PROJECT
    period_state = next(item for item in rev.call('/periods') if item['id'] == 'M1')
    stop = Event()
    others = [Thread(target=traffic, args=(args.url, stop, result['queries']), daemon=True),
              Thread(target=resources, args=(db, stop, result['resources']), daemon=True)]
    for thread in others:
        thread.start()
    try:
        for number in range(args.closures + 1) if args.closures else ():
            period_state, sample = close_sample(prep, rev, period_state, number, 'warmup' if number == 0 else 'measured', args.facts_profile)
            result['closures'].append(sample)
            print(f"Fechamento {number}: {sample['closureMs']} ms.", flush=True)
            save(args.output, result)
        op.project = IMPORT_PROJECT
        for number in range(args.imports + 1) if args.imports else ():
            for kind in ('ED', 'EC'):
                sample = import_sample(db, op, kind, number, args.payload_tag or args.profile, 'warmup' if number == 0 else 'measured')
                result['imports'].append(sample)
                print(f"{kind} {number}: {sample['processingMs']} ms.", flush=True)
                save(args.output, result)
    except (RuntimeError, OSError, AssertionError, TypeError, KeyError) as error:
        result['failures'].append({'description': str(error), 'at': now()})
        raise
    finally:
        stop.set()
        for thread in others:
            thread.join()
        result['completedAt'] = now()
        result['after'] = json.loads(counts(db))
        result['p95Ms'] = {
            **{kind: {name: percentile([item[name] for item in result['imports']
                    if item['class'] == kind and item['phase'] == 'measured'])
                for name in ('uploadMs', 'processingMs')} for kind in ('ED', 'EC')},
            **{key: percentile([item[key] for item in result['closures'] if item['phase'] == 'measured'])
               for key in ('selectionMs', 'calculationMs', 'decisionMs', 'closureMs')},
            'queries': percentile([item['ms'] for item in result['queries'] if item['status'] == 'OK']),
        }
        save(args.output, result)
        for client in (op, prep, rev):
            client.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose', required=True)
    parser.add_argument('--url', default='http://127.0.0.1:18096')
    parser.add_argument('--profile', choices=('host', 'separado'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--facts-profile', choices=('stress', 'hourly'), default='hourly')
    parser.add_argument('--payload-tag', help='Repete a identidade dos CSVs em outro perfil.')
    parser.add_argument('--imports', type=int, default=20)
    parser.add_argument('--closures', type=int, default=20)
    run(parser.parse_args())
