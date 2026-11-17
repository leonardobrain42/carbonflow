#!/usr/bin/env python3
"""Configuração dos três projetos de ensaio e pré-carga relacional da E8-012."""
import argparse
import hashlib
import json
from pathlib import Path
from uuid import uuid4

from common import Compose, CONTROL_PROJECT, PROJECT, PROJECTS, ROOT, quote, save


def snapshot():
    directory = ROOT / 'backend' / 'src' / 'main' / 'resources' / 'fixtures'
    base_path, extra_path = (directory / name for name in ('e8-003-enquadramento.json', 'e8-006-metrologia.json'))
    base = json.loads(base_path.read_text())
    extra = json.loads(extra_path.read_text())
    evidence = {item['ref']['id']: item['details'] for item in base['evidence'] + extra['evidence']}
    meter_versions = {}
    meters = []
    for meter in base['meters'] + extra['meters']:
        circuit = meter['circuit']
        lookup = evidence[meter['details']['calibration']['id']]
        previous = meter_versions.get(circuit, 0)
        meter_versions[circuit] = previous + 1
        meters.append({**meter, 'revision': previous + 1, 'predecessor': previous or None, 'recordedBy': 'SIM-OP',
                       'calibratedFrom': lookup['from_'], 'calibratedUntil': lookup['untilExclusive']})
    proof = str(uuid4())
    records = [{'id': proof, 'version': 1, 'recordedBy': 'SIM-PP',
                'input': {'type': 'SOBREPOSICAO', 'status': 'COMPROVADO_SIMULACAO', 'effectiveDate': '2027-06-30',
                          'justification': 'Prova sintética usada na E8-012', 'evidence': {'id': 'SIM-SOBREPOSICAO', 'version': 1}}}]
    configuration = {'version': 1, 'environment': 'SIMULACAO',
                     'intake': {'candidate': base['candidate'], 'state': 'ENQUADRADO', 'preparedBy': 'SIM-PREP',
                                'reviewedBy': 'SIM-REV', 'reasons': [], 'reviewedRecords': [proof]},
                     'records': records, 'meters': meters,
                     'operation': {'start': '2027-10-01', 'creditStart': '2027-10-01', 'creditEndInclusive': '2032-09-30',
                                   'evidence': {'id': 'SIM-COMISSIONAMENTO', 'version': 1},
                                   'recordedBy': 'SIM-OP', 'meterRevisions': ['SIM-EC-01@1', 'SIM-EC-02@1']}}
    return configuration, {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in (base_path, extra_path)}


def prepare(compose, profile):
    configuration, sources = snapshot()
    if compose.sql("SELECT count(*) FROM pilot_intake_revision;") != '0':
        raise ValueError('A preparação requer um banco descartável novo.')
    projects = ','.join(quote(project) for project in PROJECTS)
    compose.sql(f"""
        BEGIN;
        INSERT INTO pilot_project VALUES ({quote(CONTROL_PROJECT)},'ORG-PILOTO','SIMULACAO');
        INSERT INTO pilot_membership
        SELECT identity_id,p.id,roles,valid_from,valid_until,power_proof
        FROM pilot_membership m CROSS JOIN pilot_project p
        WHERE m.project_id={quote(PROJECT)} AND p.id IN ({projects}) AND p.id<>{quote(PROJECT)}
        ON CONFLICT DO NOTHING;
        WITH events AS (
            INSERT INTO pilot_access_event(id,project_id,actor_id,action,outcome,target,occurred_at,details)
            SELECT gen_random_uuid(),id,'SIM-OP','PREPARAR_E8_012','PERMITIDO',id,clock_timestamp(),{quote(json.dumps(sources))}::jsonb
            FROM pilot_project WHERE id IN ({projects}) RETURNING id,project_id
        )
        INSERT INTO pilot_intake_revision(project_id,version,event_id,snapshot)
        SELECT project_id,1,id,{quote(json.dumps(configuration))}::jsonb FROM events;
        COMMIT;
    """)
    seed = Path(__file__).with_name('seed.sql')
    compose.sql(seed.read_text().replace("BEGIN;", f"BEGIN;\nSET LOCAL carbonflow.envelope_profile = {quote(profile)};", 1))
    facts = compose.sql("""
        SELECT project_id,kind,period,local_year,count(*) FROM pilot_observation_revision GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;
    """)
    source_hashes = compose.sql("""
        SELECT project_id,original_name,sha256,octet_length(bytes) FROM pilot_import_file ORDER BY 1,2;
    """)
    return {'factsProfile': profile, 'sources': {**sources, 'seed.sql': hashlib.sha256(seed.read_bytes()).hexdigest()},
            'facts': [line.split('|') for line in facts.splitlines()],
            'files': [line.split('|') for line in source_hashes.splitlines()]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose', required=True)
    parser.add_argument('--facts-profile', choices=('stress','hourly'), default='hourly')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    save(args.output, prepare(Compose(args.compose), args.facts_profile))
