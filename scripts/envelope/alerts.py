#!/usr/bin/env python3
"""Exercita classificação dos eventos de operação e registro de alerta local."""
import argparse
import json
import os
import subprocess
import time
from datetime import timedelta
from pathlib import Path
from uuid import UUID

from common import CONTROL_PROJECT, Compose, ROOT, Session, quote, save
from measure import HEADER, T0, elapsed, enqueue, fact, now, propose, upload, wait_for


def entry(category, disposition, status, target, audience, at, timer, basis='injetado neste ensaio'):
    return {'category': category, 'disposition': disposition, 'observedStatus': status,
            'reference': target, 'responsibleRole': audience, 'basis': basis,
            'startedAt': at, 'recordedAt': now(), 'detectedMs': elapsed(timer) if timer is not None else None}


def invalid_file(url):
    op = Session(url, 'OP', CONTROL_PROJECT).login()
    try:
        at, timer = now(), time.perf_counter()
        item = upload(op, 'entrada-invalida-e812.csv', b'cabecalho,invalido\n1,2\n')['artifact']
        job = enqueue(op, item['id'], 'M2')['jobId']
        report = wait_for(op, '/jobs/' + job, {'CONCLUIDO', 'RECUSADO', 'FALHA_RECUPERAVEL'})
        assert report['state'] == 'RECUSADO' and report['message']
        return entry('IMPORTACAO', 'FALHA_DE_DADO', report['state'], job, 'OP', at, timer)
    finally:
        op.close()


def uninterrupted_fact(url):
    op, prep, rev = (Session(url, role, CONTROL_PROJECT).login() for role in ('OP', 'PREP', 'REV'))
    try:
        start = T0 + timedelta(days=250)
        raw = (HEADER + '\n' + fact('ED', 'SIM-E812-ALERTA', 1, start, start + timedelta(minutes=30)) + '\n').encode()
        item = upload(op, 'entrada-calculo-e812.csv', raw)['artifact']
        job = enqueue(op, item['id'], 'M2')['jobId']
        report = wait_for(op, '/jobs/' + job, {'CONCLUIDO', 'RECUSADO', 'FALHA_RECUPERAVEL'})
        assert report['state'] == 'CONCLUIDO' and report['classifications'] == {'CANDIDATA': 1}
        row = prep.call('/imports/' + job + '/rows')['lines'][0]
        path = '/observations/' + row['id'] + '/actions'
        prepared = prep.call(path, {'transitionId': 'PROPOR', 'reason': 'Ensaio sintético de alerta.'}, version=row['version'])['line']
        reviewed = rev.call(path, {'transitionId': 'ADMITIR', 'reason': 'Ensaio sintético de alerta.'}, version=prepared['version'])['line']
        assert reviewed['decision'] == 'ADMITIDA'
        snapshot = rev.call('/periods/M2/selections', b'', expected=201)
        return propose(prep, 'M2', snapshot)
    finally:
        for client in (op, prep, rev):
            client.close()


def failed_calc(db, url):
    run = uninterrupted_fact(url)
    run_id = str(UUID(run['id']))
    rev = Session(url, 'REV', CONTROL_PROJECT).login()
    db.sql(f"""
        ALTER TABLE pilot_selected_run ADD CONSTRAINT envelope_fault
        CHECK (id<> {quote(run_id)}::uuid OR state NOT IN ('CALCULADA','COM_LACUNAS')) NOT VALID;
    """)
    try:
        at, timer = now(), time.perf_counter()
        rev.call('/runs/' + run_id + '/actions', {'transitionId': 'CONFIRMAR', 'reason': 'Ensaio sintético de alerta.'},
                 version=run['version'], expected=202)
        failed = wait_for(rev, '/jobs/' + run_id, {'CALCULADA', 'COM_LACUNAS', 'FALHA_RECUPERAVEL'})
        assert failed['state'] == 'FALHA_RECUPERAVEL'
        attempt = rev.call('/jobs/' + run_id + '/attempts')[0]
        events = rev.call('/events')
        match = next(item for item in events if item['action'] == 'FALHA_CALCULO_SELECAO')
        assert attempt['state'] == 'FALHA_RECUPERAVEL' and match['outcome'] == 'NEGADO'
        result = entry('CALCULO', 'ALERTA_INTERNO', failed['state'], run_id, 'Operação técnica', at, timer)
        result['event'] = match['id']
    finally:
        db.sql('ALTER TABLE pilot_selected_run DROP CONSTRAINT envelope_fault;')
        rev.close()
    return result


def bad_backup(db, output):
    directory = output.parent / ('e812-destino-ausente-' + str(os.getpid())) / 'copia'
    at, timer = now(), time.perf_counter()
    invocation = subprocess.run(['python3', str(ROOT / 'scripts' / 'carbonflow-recovery.py'),
        'backup', '--source', db.project, '--output', str(directory)], capture_output=True, text=True, check=False)
    assert invocation.returncode == 1 and not directory.exists()
    return entry('COPIA', 'ALERTA_INTERNO', 'FALHA_CRIAR_COPIA', 'DESTINO_TEMPORARIO_AUSENTE', 'Operação técnica', at, timer)


def test(args):
    db = Compose(args.compose)
    journal = [invalid_file(args.url), failed_calc(db, args.url), bad_backup(db, args.output)]
    capacity = json.loads(args.boundaries.read_text())['quota']
    assert capacity['atLimit']['usedBytes'] == 2_000_000_000 and capacity['rejectedExtraBytes'] == 1
    journal.append(entry('ARMAZENAMENTO', 'LIMITE_COM_EVIDENCIA_PRESERVADA', 'COTA_ESGOTADA',
                         capacity['preservedSha256'], 'Operação técnica', None, None, 'recusa de cota nesta instância'))
    trajectory = json.loads((ROOT / 'evidencias' / 'e8-010' / 'trajetoria.json').read_text())
    waiting = trajectory['v1Waiting']['attempt']
    divergent = trajectory['v1AfterReopen']['attempt']
    assert waiting['state'] == 'AGUARDANDO_DEPOSITO' and divergent['state'] == 'COM_DIVERGENCIA'
    journal.append(entry('ESPERA_EXTERNA', 'PENDENCIA_EXTERNA', waiting['state'],
                         waiting['id'], 'PP', None, None, 'replay da trilha E8-010'))
    journal.append(entry('DIVERGENCIA', 'REVISAO_DE_PROVA', divergent['state'],
                         divergent['id'], 'PP/REV', None, None, 'replay da trilha E8-010'))
    save(args.output, {'sink': 'registro local', 'externalDelivery': 'não executada', 'events': journal})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose', required=True)
    parser.add_argument('--url', default='http://127.0.0.1:18096')
    parser.add_argument('--boundaries', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    test(parser.parse_args())
