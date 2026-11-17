#!/usr/bin/env python3
"""Amostra leitura e escrita autorizadas e apura minutos da janela útil."""
import argparse
import json
from datetime import date, datetime, time as civil_time, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from common import CONTROL_PROJECT, Compose, Failure, Session, save
from measure import HEADER, T0, enqueue, fact, upload, wait_for

ZONE = ZoneInfo('America/Sao_Paulo')
MINUTE = timedelta(minutes=1)


def probe(url, fixture, slot):
    start = datetime.now(timezone.utc)
    row = {'minute': slot.astimezone(timezone.utc).isoformat(), 'sampledAt': start.isoformat()}
    client = Session(url, 'OP', CONTROL_PROJECT)
    try:
        client.login()
        before = client.call('/jobs/' + fixture['jobId'])
        again = enqueue(client, fixture['fileId'], 'M2', 'e812-sonda-' + slot.strftime('%Y%m%dT%H%MZ'))
        after = client.call('/jobs/' + again['jobId'])
        if before['state'] != 'CONCLUIDO' or after['id'] != fixture['jobId'] or before != after or not again['repeated']:
            raise RuntimeError('Sonda retornou estado incompatível.')
        row['status'] = 'OK'
    except (OSError, Failure, RuntimeError, ValueError, KeyError) as error:
        row['status'] = 'INDISPONIVEL'
        row['reason'] = type(error).__name__
        if isinstance(error, Failure):
            row['httpStatus'] = error.status
    finally:
        client.close()
    row['completedAt'] = datetime.now(timezone.utc).isoformat()
    row['elapsedMs'] = round((datetime.now(timezone.utc) - start).total_seconds() * 1000, 3)
    return row


def minute_filter(value):
    minute = datetime.fromisoformat(value)
    if minute.tzinfo is None or minute.second or minute.microsecond:
        raise ValueError('Use um instante com fuso e precisão de minuto.')
    return minute.astimezone(timezone.utc)


def rollup(rows, start, end, holidays):
    if end <= start:
        raise ValueError('O fim exclusivo deve suceder o início.')
    observed = {}
    for row in rows:
        minute = minute_filter(row['minute'])
        ok = row['status'] == 'OK'
        observed[minute] = observed.get(minute, True) and ok
    minutes = {'available': 0, 'unavailable': 0, 'missing': 0}
    next_minute = start
    while next_minute < end:
        civil = next_minute.astimezone(ZONE)
        if civil.weekday() < 5 and civil.date() not in holidays and civil_time(8) <= civil.time() < civil_time(18):
            result = observed.get(next_minute)
            minutes['available' if result is True else 'unavailable' if result is False else 'missing'] += 1
        next_minute += MINUTE
    total = sum(minutes.values())
    return {'start': start.isoformat(), 'endExclusive': end.isoformat(), 'zone': ZONE.key,
            'weekdays': '08:00–18:00', 'holidays': sorted(str(day) for day in holidays), **minutes,
            'denominator': total, 'coveragePercent': round((total - minutes['missing']) * 100 / total, 4) if total else None,
            'availabilityPercent': round(minutes['available'] * 100 / total, 4) if total else None}


def seed(url):
    client = Session(url, 'OP', CONTROL_PROJECT).login()
    try:
        data = (HEADER + '\n'
                + fact('ED', 'SIM-E812-SONDA', 1, T0, T0 + timedelta(seconds=20)) + '\n').encode()
        file_id = upload(client, 'e812-sonda.csv', data)['artifact']['id']
        job = enqueue(client, file_id, 'M2')['jobId']
        value = wait_for(client, '/jobs/' + job, {'CONCLUIDO', 'RECUSADO', 'FALHA_RECUPERAVEL'})
        if value['classifications'] != {'CANDIDATA': 1}:
            raise RuntimeError('A sonda requer um lote de controle válido.')
        return {'fileId': file_id, 'jobId': job}
    finally:
        client.close()


def simulate(args):
    db = Compose(args.compose, args.override)
    fixture = seed(args.url)
    start = minute_filter('2026-11-16T08:00:00-03:00')
    rows = [probe(args.url, fixture, start)]
    db.command('stop', 'app')
    try:
        rows.append(probe(args.url, fixture, start + MINUTE))
        rows[-1]['maintenance'] = True
    finally:
        db.command('up', '-d', '--wait', 'app')
    # A marca t+2 foi deliberadamente omitida para verificar a regra de observação ausente.
    rows.extend(probe(args.url, fixture, start + MINUTE * slot) for slot in (3, 4))
    result = rollup(rows, start, start + MINUTE * 5, set())
    if [result[key] for key in ('available', 'unavailable', 'missing')] != [3, 1, 1]:
        raise RuntimeError(f'Resultado inesperado: {result}')
    save(args.output, {'clock': 'slots simulados; relógio dos acessos em sampledAt/completedAt', 'samples': rows,
                       'window': result, 'fixture': fixture})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    setup = commands.add_parser('seed')
    check = commands.add_parser('probe')
    check.add_argument('--fixture', type=Path, required=True)
    simulate_cmd = commands.add_parser('simulate')
    simulate_cmd.add_argument('--compose', required=True)
    simulate_cmd.add_argument('--override', type=Path, action='append', default=[])
    aggregate = commands.add_parser('rollup')
    aggregate.add_argument('--log', type=Path, required=True)
    aggregate.add_argument('--start', type=minute_filter, required=True)
    aggregate.add_argument('--end', type=minute_filter, required=True)
    aggregate.add_argument('--holiday', type=date.fromisoformat, action='append', default=[])
    for command in (setup, check, simulate_cmd):
        command.add_argument('--url', default='http://127.0.0.1:18096')
    for command in (setup, simulate_cmd, aggregate):
        command.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'seed':
        save(args.output, seed(args.url))
    elif args.command == 'probe':
        minute = datetime.now(timezone.utc).replace(second=0, microsecond=0)
        print(json.dumps(probe(args.url, json.loads(args.fixture.read_text()), minute), ensure_ascii=False), flush=True)
    elif args.command == 'rollup':
        rows = [json.loads(line) for line in args.log.read_text().splitlines() if line.strip()]
        save(args.output, rollup(rows, args.start, args.end, set(args.holiday)))
    else:
        simulate(args)
