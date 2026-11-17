#!/usr/bin/env python3
"""Ensaio destrutivo apenas nos projetos descartáveis da E8-011."""

import argparse
import csv
import hashlib
import io
import json
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[2]
SOURCE = 'carbonflow-e8-011-test'
DATABASE = 'carbonflow_test_e8_011'
RECOVERY = ROOT / 'scripts' / 'carbonflow-recovery.py'


class Pilot:
    def __init__(self, url, project):
        self.url = url
        self.project = project
        self.sessions = {}

    def call(self, actor, route, *, data=None, version=None, status=200, method=None, headers=None, key=None):
        session = self.sessions.get(actor)
        if session is None:
            session = build_opener(HTTPCookieProcessor(CookieJar()))
            login = Request(self.url + '/v1/session', method='POST', headers={'Content-Type': 'application/json'},
                            data=json.dumps({'identity': 'SIM-' + actor, 'password': 'piloto-' + actor.lower()}).encode())
            with session.open(login, timeout=10) as response:
                assert response.status == 204
            self.sessions[actor] = session
        request_headers = dict(headers or {})
        if data is not None:
            request_headers['Idempotency-Key'] = key or str(uuid4())
        if version is not None:
            request_headers['If-Match'] = f'"{version}"'
        if isinstance(data, dict):
            request_headers['Content-Type'] = 'application/json'
            data = json.dumps(data).encode()
        request = Request(self.url + '/v1/projects/' + self.project + route, data=data, method=method, headers=request_headers)
        try:
            with session.open(request, timeout=15) as response:
                observed, contents, response_headers = response.status, response.read(), response.headers
        except HTTPError as response:
            observed, contents, response_headers = response.code, response.read(), response.headers
        assert status == observed, (actor, route, status, observed, contents[:900])
        if response_headers.get_content_type().endswith('json') and contents:
            return json.loads(contents)
        return contents

    def effect(self, attempt, data):
        return self.call('PP', '/issuances/' + attempt['id'] + '/actions', data=data, version=attempt['version'])

    def upload(self, csv_bytes):
        return self.call('OP', '/artifacts', data=csv_bytes, status=201, headers={'Content-Type': 'text/csv',
                         'X-File-Name': 'recuperacao-m1.csv', 'X-Source-Id': 'SIM-OP-01', 'X-Source-Authorization': 'SIM-AUT-ED-EC'})['artifact']


def command(*args, status=0):
    result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, text=True, timeout=180)
    assert result.returncode == status, (args[:4], result.returncode, result.stdout)
    return result.stdout.strip()


def cli(*args, status=0):
    return command(sys.executable, str(RECOVERY), *map(str, args), status=status)


def job_input(file_id, period):
    return {'artifact': {'id': file_id, 'version': 1}, 'mappingProfile': {'id': 'SIM-CSV-UTF8-ED-EC', 'version': 1},
            'sourceId': 'SIM-OP-01', 'period': {'id': period, 'version': 1}}


def recover_fact(pilot, csv_bytes, previous):
    raw = pilot.upload(csv_bytes)
    queued = pilot.call('OP', '/imports', data=job_input(raw['id'], 'M1'), status=202)
    route = '/jobs/' + queued['jobId']
    for _ in range(70):
        report = pilot.call('OP', route)
        if report['state'] == 'CONCLUIDO':
            break
        assert report['state'] in ('AGUARDANDO', 'PROCESSANDO'), report
        time.sleep(0.2)
    else:
        raise AssertionError('Prazo aguardando o lote de recuperação.')
    row = pilot.call('PREP', '/imports/' + queued['jobId'] + '/rows')['lines'][0]
    assert row['classification'] == 'CONFLITO_ID', row
    path = '/observations/' + row['id'] + '/actions'
    proposal = pilot.call('PREP', path, data={'transitionId': 'PROPOR', 'reason': 'Retorno corrigido da fonte sintética.',
                                             'predecessor': previous}, version=row['version'])['line']
    admitted = pilot.call('REV', path, data={'transitionId': 'ADMITIR', 'reason': 'Conferência independente do retorno.'},
                          version=proposal['version'])['line']
    assert admitted['decision'] == 'ADMITIDA'
    return admitted['resolution']['observation']


def proof(code, target, source):
    return {'officialId': code, 'target': target, 'evidence': {'id': source, 'version': 1}}


def finish_adjustment(pilot, adjusted):
    attempt = adjusted['attempt']
    route = '/issuances/' + attempt['id'] + '/actions'
    compared = pilot.call('PREP', route, data={'transitionId': 'COMPARAR', 'mode': 'SIMULACAO',
                                               'effectiveAt': '2029-12-07T14:00:00Z'}, version=attempt['version'])['attempt']
    approved = pilot.call('REV', route, data={'transitionId': 'TX-E3-53', 'mode': 'SIMULACAO',
                  'effectiveAt': '2029-12-07T15:00:00Z', 'comparison': compared['comparison']['id']}, version=compared['version'])
    assert approved['attempt']['state'] == 'CONCILIADO'
    return approved


def capture_protocol(pilot, verification, vintage, account, target, receipt, restore=False):
    entry = next(item for item in pilot.call('PP', '/verifications') if item['id'] == verification)
    balance = next(item for item in entry['balances'] if item['position'] and item['position']['vintage'] == vintage)
    draft = {'verification': verification, 'account': account, 'unit': 'UNIDADE_FICTICIA',
             'effectiveAt': '2029-12-07T13:15:00Z',
             'lines': [{'vintage': vintage, 'position': balance['position']['id'], 'amount': 1}]}
    if restore:
        wrong = {**draft, 'lines': [{**draft['lines'][0], 'amount': 2}], 'recoveryProtocol': receipt}
        assert pilot.call('PP', '/issuances', data=wrong, status=409)['code'] == 'PEDIDO_INTERVALO_DIVERGENTE'
        draft['recoveryProtocol'] = receipt
    attempt = pilot.call('PP', '/issuances', data=draft, status=201)['attempt']
    base = '/issuances/' + attempt['id']
    checked = pilot.call('PP', base + '/channel-checks', version=attempt['version'],
                         data={'route': 'ARQUIVO', 'checkedAt': '2029-12-07T13:20:00Z',
                               'powerUntilExclusive': '2031-01-01T00:00:00Z', 'source': {'id': 'SIM-CANAL-ARQUIVO', 'version': 1},
                               'power': {'id': 'SIM-PODER-EMISSAO', 'version': 1}})['attempt']
    return pilot.effect(checked, {'transitionId': 'TX-E3-49', 'mode': 'SIMULACAO', 'effectiveAt': '2029-12-07T13:30:00Z',
                                 'account': account, 'target': target, 'proof': proof(receipt, target, 'SIM-RECIBO-EMISSAO')})['attempt']


def grant_for(pilot, purpose, recipient):
    for grant in pilot.call('PP', '/grants'):
        if grant['purpose'] == purpose and grant['recipient'] == 'SIM-' + recipient and not grant['revokedAt']:
            ref = grant['artifact']
            return grant, f"/artifacts/{ref['id']}/versions/{ref['version']}/content"
    raise AssertionError('Concessão sintética ausente.')


def run(args):
    trajectory = json.loads(args.fixture.read_text())
    project = trajectory['project']
    if not project.startswith('SIM-E8-004-'):
        raise ValueError('O ensaio precisa do projeto descartável criado pela suíte Java.')
    args.work.mkdir(mode=0o700)
    source = Pilot(args.source_url, project)
    before_jobs = source.call('OP', '/jobs?period=M2')
    existing = source.call('OP', '/imports/' + next(item['id'] for item in before_jobs if item['state'] == 'CONCLUIDO'))
    repeat_input = job_input(existing['artifact']['id'], 'M2')
    repeat_key = str(uuid4())
    submitted = source.call('OP', '/imports', data=repeat_input, status=202, key=repeat_key)
    original_grant, denied_path = grant_for(source, 'AUDITORIA_V2', 'VVB-B')
    content_hash = hashlib.sha256(source.call('VVB-B', denied_path)).hexdigest()
    source.call('PP', '/grants/' + original_grant['id'] + '/revoke', data=b'', version=original_grant['version'])
    source.call('VVB-B', denied_path, status=404)
    _, allowed_path = grant_for(source, 'AUDITORIA_V1', 'VVB')
    allowed_before = source.call('VVB', allowed_path)

    source_args = ('--source', SOURCE, '--source-db', DATABASE)
    archive = args.work / 'backup'
    gap = args.work / 'gap.json'
    capture = json.loads(cli('backup', *source_args, '--output', archive))

    csv_sample = source.call('OP', '/imports/example/monitoring/M1').decode()
    header, first_line, *_ = list(csv.reader(io.StringIO(csv_sample)))
    previous = next(item for item in source.call('PREP', '/observations?period=M1')
                    if item['fact']['sourceFactId'] == first_line[1])['ref']
    first_line[-2] = str(int(first_line[-2]) + 1)
    late_csv = io.StringIO()
    csv.writer(late_csv, lineterminator='\n').writerows((header, first_line))
    csv_bytes = late_csv.getvalue().encode()
    late_fact = recover_fact(source, csv_bytes, previous)

    v2 = trajectory['v2Reconciled']['attempt']
    vintage = v2['lines'][0]['vintage']
    at = '2029-12-07T13:00:00Z'
    suffix = uuid4().hex[:8].upper()
    official = 'SIM-REC-AJUSTE-' + suffix
    position = {'vintage': vintage, 'unit': 'UNIDADE_FICTICIA', 'official': 29,
                'pending': [{'issuance': v2['id'], 'amount': 1, 'status': 'REFLETIDA'}], 'asOf': at,
                'proof': proof('SIM-REC-POS-' + suffix, trajectory['v2InitialPosition']['position']['capture']['proof']['target'],
                               'SIM-PROVA-POSICAO')}
    adjustment = {'transitionId': 'AJUSTE', 'mode': 'SIMULACAO', 'effectiveAt': at, 'account': v2['account'],
                  'referenceAct': v2['deposits'][0]['capture']['proof']['officialId'], 'lines': [{'vintage': vintage, 'amount': -1}],
                  'proof': proof(official, v2['externalTarget'], 'SIM-PROVA-AJUSTE-01'), 'newPosition': position}
    after_source = source.effect(v2, adjustment)
    assert after_source['netDeposited'][0]['amount'] == 1
    target, receipt = 'SIM-EMISSAO-REC-' + suffix, 'SIM-RECIBO-REC-' + suffix
    late_protocol = capture_protocol(source, v2['verification'], vintage, v2['account'], target, receipt)
    incident = datetime.now(timezone.utc).isoformat()
    observed = json.loads(cli('observe', *source_args, '--backup', archive, '--output', gap))
    assert observed['effects'] == 5, observed
    command('docker', 'compose', '-p', SOURCE, 'stop', 'app', 'db')

    # Sem o inventário, nem o banco de destino é iniciado.
    broken = args.work / 'incomplete'
    shutil.copytree(archive, broken, ignore=shutil.ignore_patterns('inventory.jsonl'))
    cli('restore', '--backup', broken, '--gap', gap, '--target', args.target, '--incident-at', incident, '--port', args.port, status=1)

    target_restore = json.loads(cli('restore', '--backup', archive, '--gap', gap, '--target', args.target,
                                    '--incident-at', incident, '--port', args.port, '--run-app'))
    restored = Pilot(f'http://127.0.0.1:{args.port}', project)
    restored.call('VVB-B', denied_path, status=404)
    assert restored.call('VVB', allowed_path) == allowed_before
    assert restored.call('OP', '/jobs?period=M2') == before_jobs
    repeated = restored.call('OP', '/imports', data=repeat_input, status=202, key=repeat_key)
    assert repeated == submitted

    state = restored.call('OP', '/recovery')
    check = '/recovery/' + state['recovery']['id']
    assertion = {'gapSha': state['recovery']['gapSha'], 'reason': 'Inventário e retorno sintéticos conferidos no ambiente isolado.'}
    next_vintage = trajectory['v2InitialPosition']['position']['vintage']
    new_attempt = {'verification': v2['verification'], 'account': v2['account'], 'unit': 'UNIDADE_FICTICIA',
                   'effectiveAt': '2029-12-08T13:00:00Z',
                   'lines': [{'vintage': next_vintage, 'position': '00000000-0000-0000-0000-000000000000', 'amount': 1}]}
    assert restored.call('PP', '/issuances', data=new_attempt, status=409)['code'] == 'RECUPERACAO_PENDENTE'
    assert restored.call('OP', check + '/check', data=assertion, version=1, status=409)['code'] == 'REEXECUCAO_PENDENTE'
    assert len(state['runs']) == 2
    calculations = {}
    for run in state['runs']:
        replay = restored.call('PREP', '/runs/' + run['id'] + '/reexecute', data={})
        assert replay['matches'] and replay['snapshotSha256'] == run['sha256']
        calculations[run['period']] = {'sha256': replay['snapshotSha256'], 'snapshotMatches': replay['matches'], 'result': replay['result']}
    assert restored.call('OP', check + '/check', data=assertion, version=1, status=409)['code'] == 'INTERVALO_PENDENTE'

    assert recover_fact(restored, csv_bytes, previous) == late_fact
    wrong_adjustment = {**adjustment, 'lines': [{'vintage': vintage, 'amount': -0.5}]}
    assert restored.call('PP', '/issuances/' + v2['id'] + '/actions', data=wrong_adjustment,
                         version=v2['version'], status=409)['code'] == 'PROVA_INTERVALO_DIVERGENTE'
    after_restore = restored.effect(v2, adjustment)
    assert after_restore['netDeposited'] == after_source['netDeposited']
    assert restored.effect(v2, adjustment) == after_restore
    assert restored.call('OP', check + '/check', data=assertion, version=1, status=409)['code'] == 'INTERVALO_PENDENTE'
    recreated = capture_protocol(restored, v2['verification'], vintage, v2['account'], target, receipt, restore=True)
    assert recreated['id'] == late_protocol['id'] and recreated['state'] == 'PROTOCOLADO'
    finished = finish_adjustment(restored, after_restore)
    state = restored.call('PP', '/recovery')
    assert all(effect['status'] == 'CONFERIDO' for effect in state['effects'])
    assert restored.call('PP', check + '/check', data=assertion, version=1, status=403)['status'] == 403
    assert restored.call('OP', check + '/check', data=assertion, version=1)['version'] == 2
    assert restored.call('OP', check + '/release', data=assertion, version=2, status=403)['status'] == 403
    released = restored.call('REV', check + '/release', data=assertion, version=2)
    assert released['version'] == 3 and released['rtoSeconds'] > 0
    verification = next(item for item in restored.call('PP', '/verifications') if item['kind'] == 'V2')
    balance = next(item for item in verification['balances'] if item['position'] and item['position']['vintage'] == next_vintage)
    assert balance['available'] == 28
    new_attempt['lines'][0]['position'] = balance['position']['id']
    assert restored.call('PP', '/issuances', data=new_attempt, status=201)['attempt']['state'] == 'EM_PREPARO'

    result = {'fixture': project, 'kit': '0.2.14', 'backup': capture, 'interval': observed, 'isolatedTarget': target_restore,
              'verifiedCalculations': calculations, 'retainedRevocation': {'contentSha256': content_hash, 'afterRestoreStatus': 404},
              'activeGrantSha256': hashlib.sha256(allowed_before).hexdigest(), 'idempotentJobId': repeated['jobId'],
              'recoveredFact': late_fact, 'recoveredProtocol': recreated['id'], 'reconciledDeposit': finished['netDeposited'], 'afterReleaseAvailable': balance['available'],
              'unavailableInventoryRejected': True, 'recovery': released}
    (args.work / 'ensaio.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--source-url', default='http://127.0.0.1:18091')
    parser.add_argument('--target', default='carbonflow-recovery-e8-011')
    parser.add_argument('--port', type=int, default=18092)
    run(parser.parse_args())
