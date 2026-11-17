#!/usr/bin/env python3
"""Limites de inventário, admissões e bytes na instância isolada."""
import argparse
import copy
import hashlib
from datetime import datetime, timedelta, timezone
from pathlib import Path

from common import CONTROL_PROJECT, IMPORT_PROJECT, PROJECT, Compose, Session, save
from measure import HEADER, enqueue, fact, upload, wait_for

ISOLATION = 'SIM-PROJETO-ISOLAMENTO'


def intake(client, candidate, version, status=200):
    return client.call('/intake/' + client.project + '/actions',
                       {'transitionId': 'TX-E3-01', 'details': {'candidate': candidate}}, version=version, expected=status)


def inventory(client):
    candidate = client.call('/intake/examples')['candidate']
    original = candidate['inventory']
    suffix = lambda number: f'{number:02}'
    maxima = {
        'sites': ['SIM-PATIO-' + suffix(i) for i in range(1, 4)],
        'stations': [{**original['stations'][0], 'id': 'SIM-DCFC-' + suffix(i)} for i in range(1, 11)],
        'models': [{**original['models'][0], 'id': 'SIM-MODELO-' + suffix(i)} for i in range(1, 6)],
        'vehicles': [{**original['vehicles'][0], 'id': 'SIM-BEV-' + suffix(i)} for i in range(1, 101)],
        'ecCircuits': [{'id': 'SIM-EC-' + suffix(i), 'station': 'SIM-DCFC-' + suffix(i)} for i in range(1, 11)],
    }
    candidate['inventory'] = maxima
    accepted = intake(client, candidate, 0)
    before = client.call('')
    assert accepted['snapshot']['intake']['state'] == 'TRIAGEM'
    limits = {}
    for dimension, items in maxima.items():
        excess = copy.deepcopy(candidate)
        over = 'SIM-E812-EXCEDENTE'
        extra = over if dimension == 'sites' else {**items[0], 'id': over}
        excess['inventory'][dimension].append(extra)
        denied = intake(client, excess, 1, 422)
        assert denied['code'] == 'LIMITE_DE_CAPACIDADE', denied
        limits[dimension] = {'accepted': len(items), 'rejected': len(items) + 1, 'message': denied['message']}
    assert client.call('')['snapshot'] == before['snapshot']
    return limits


def period_facts(db, op, prep, rev):
    start = datetime(2028, 10, 2, 3, tzinfo=timezone.utc)
    # Duas admissões de cada classe completam as 100.000 entradas no ano civil de 2028.
    # Uma terceira e uma do ano seguinte verificam o primeiro excesso e a divisão anual.
    changes = []
    for kind in ('ED', 'EC'):
        rows = []
        for i in range(4):
            t = start + timedelta(hours=i) if i < 3 else start.replace(year=2029, month=1, day=2)
            rows.append(fact(kind, f'SIM-E812-FRONTEIRA-{kind}-{i}', 1, t, t + timedelta(hours=1)))
        data = (HEADER + '\n' + '\n'.join(rows) + '\n').encode()
        file = upload(op, f'e812-fronteira-{kind}.csv', data)['artifact']
        job = enqueue(op, file['id'], 'M2')['jobId']
        report = wait_for(op, '/jobs/' + job, {'CONCLUIDO', 'RECUSADO', 'FALHA_RECUPERAVEL'})
        assert report['classifications'] == {'CANDIDATA': 4}, report
        preview = prep.call('/imports/' + job + '/rows')['lines']
        for i, line in enumerate(preview):
            path = '/observations/' + line['id'] + '/actions'
            proposal = prep.call(path, {'transitionId': 'PROPOR', 'reason': 'Ensaio anual do envelope'}, version=line['version'])['line']
            result = rev.call(path, {'transitionId': 'ADMITIR', 'reason': 'Ensaio anual do envelope'},
                              version=proposal['version'], expected=422 if i == 2 else 200)
            assert (result['code'] == 'LIMITE_DE_CAPACIDADE') if i == 2 else (result['line']['decision'] == 'ADMITIDA'), result
            changes.append({'kind': kind, 'year': 2028 if i < 3 else 2029,
                            'observed': result.get('code', 'ADMITIDA'), 'line': line['line']})
    totals = db.sql("""
        SELECT kind,local_year,count(*) FROM pilot_observation_head h JOIN pilot_observation_revision r
        ON (r.project_id,r.id,r.version)=(h.project_id,h.id,h.version)
        WHERE h.project_id='PIL-SYN-BR-001' AND local_year>=2028 GROUP BY 1,2 ORDER BY 1,2;
    """).splitlines()
    assert totals == ['EC|2028|100000', 'EC|2029|1', 'ED|2028|100000', 'ED|2029|1']
    return {'changes': changes, 'current': totals}


def raw_limits(client):
    exact = b'X' * 10_000_000
    saved = upload(client, 'e812-bytes.csv', exact)['artifact']
    too_big = upload(client, 'e812-primeiro-byte-excedente.csv', exact + b'X', expected=413)
    assert saved['bytes'] == 10_000_000 and saved['sha256'] == hashlib.sha256(exact).hexdigest()
    assert too_big['code'] == 'ARQUIVO_MUITO_GRANDE', too_big
    lines = (HEADER + '\n' + '\n'.join(
        fact('EC', f'SIM-E812-FRONTEIRA-LINHAS-{i}', 1,
             datetime(2029, 8, 1, 3, tzinfo=timezone.utc) + timedelta(seconds=i),
             datetime(2029, 8, 1, 3, tzinfo=timezone.utc) + timedelta(seconds=i+1))
        for i in range(10001)) + '\n').encode()
    file = upload(client, 'e812-primeira-linha-excedente.csv', lines)['artifact']
    job = enqueue(client, file['id'], 'M2')['jobId']
    report = wait_for(client, '/jobs/' + job, {'CONCLUIDO', 'RECUSADO', 'FALHA_RECUPERAVEL'})
    assert report['state'] == 'RECUSADO' and report['total'] == 10001
    assert report['classifications'] == {'RECUSADAS_LOTE': 10001}
    assert client.call('/imports/' + job + '/rows')['lines'] == []
    return {'acceptedUploadBytes': saved['bytes'], 'rejectedUploadBytes': 10_000_001, 'bytesError': too_big,
            'rejectedLines': report['total'], 'persistedPreviewRows': 0, 'linesMessage': report['message']}


def quota(db, client):
    bytes_now = client.call('/retention')['storage']['usedBytes']
    assert bytes_now == 0, bytes_now
    # Carga lógica exata. O padrão compressível permite reproduzir o teste de cota no ambiente local.
    db.sql("""
        INSERT INTO pilot_import_file (project_id,id,sha256,original_name,source_id,authorization_id,received_by,bytes)
        SELECT 'SIM-PROJETO-ISOLAMENTO',gen_random_uuid(),encode(sha256(content),'hex'),
               'cota-e812-'||slot||'.csv','SIM-OP-01','SIM-AUT-ED-EC','SIM-PREP-OUTRA',content
        FROM generate_series(1,200) slot
        CROSS JOIN LATERAL (SELECT convert_to(lpad(slot::text,4,'0') ||
            repeat('Q',9999996-CASE WHEN slot=200 THEN 1 ELSE 0 END),'UTF8') AS content) c;
    """)
    near = client.call('/retention')['storage']
    assert near['usedBytes'] == 1_999_999_999 and near['limitBytes'] == 2_000_000_000
    accepted = upload(client, 'e812-fecha-cota.csv', b'A')['artifact']
    denied = upload(client, 'e812-excede-cota.csv', b'B', expected=409)
    assert denied['code'] == 'LIMITE_DE_CAPACIDADE'
    current = client.call('/retention')['storage']
    assert current['usedBytes'] == 2_000_000_000
    assert client.call('/imports/files/' + accepted['id'] + '/versions/1/content') == b'A'
    return {'kind': 'pré-carga compressível', 'before': near, 'atLimit': current, 'rejectedExtraBytes': 1,
            'message': denied['message'], 'preservedSha256': accepted['sha256']}


def access_limits(db, url):
    sessions = [Session(url, actor).login() for actor in ('OP', 'PREP', 'REV')]
    try:
        output = [client.call('/v1/session', scope=False)['identity']['id'] for client in sessions]
        existing = db.sql("""
            SELECT count(DISTINCT m.identity_id)
            FROM pilot_membership m JOIN pilot_project p ON p.id=m.project_id AND p.organization_id='ORG-PILOTO';
        """)
        scopes = sessions[1].call('/v1/projects', scope=False)
        assert {item['project'] for item in scopes} == {PROJECT, IMPORT_PROJECT, CONTROL_PROJECT}
        assert len(output) == 3
        return {'projectsVisible': 3, 'fourthProjectApi': 'API de diretório expõe apenas GET',
                'internalNamedIdentities': int(existing), 'sessionsUsableWhenThirdLoggedIn': output,
                'status': 'guardas de provisionamento e simultaneidade não demonstradas'}
    finally:
        for client in sessions:
            client.close()


def run(args):
    db = Compose(args.compose)
    isolated = Session(args.url, 'PREP-OUTRA', ISOLATION).login()
    prep, rev, op = (Session(args.url, name).login() for name in ('PREP', 'REV', 'OP'))
    result = {'inventory': inventory(isolated)}
    save(args.output, result)
    result['facts'] = period_facts(db, op, prep, rev)
    op.project = IMPORT_PROJECT
    result['upload'] = raw_limits(op)
    save(args.output, result)
    result['access'] = access_limits(db, args.url)
    result['quota'] = quota(db, isolated)
    save(args.output, result)
    for client in (isolated, prep, rev, op):
        client.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose', required=True)
    parser.add_argument('--url', default='http://127.0.0.1:18096')
    parser.add_argument('--output', type=Path, required=True)
    run(parser.parse_args())
