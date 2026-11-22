#!/usr/bin/env python3
"""Explicit, fail-closed V71 checksum reconciliation for the isolated local demo.

Only reconciles historical checksum 167374965 to the published -1979807539
after PostgreSQL catalogue checks; never disables Flyway validation or touches
project data. The old migration SQL bytes are unavailable, so this is NOT a
proof of exact historical SQL equivalence.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import zlib
import demo_sandbox as demo

OLD = 167374965
NEW = -1979807539
V69 = -1778044537
V69_1 = 1930605372
LEGACY_FILENAME_SCRIPT = "db/migration/V71__asset_evidence_submitted_filename_backfill.sql"
LEGACY_FILENAME_DESCRIPTION = "asset evidence submitted filename backfill"
CURRENT_FILENAME_SCRIPT = "db/migration/V69_1__open_asset_evidence_submitted_filename.sql"
CURRENT_FILENAME_DESCRIPTION = "open asset evidence submitted filename"
SQL_DIR = demo.ROOT / "backend/src/main/resources/db/migration"
RUN = {
    "id":"uuid", "project_id":"text", "organization_id":"uuid", "environment":"text",
    "period_code":"text", "version":"int4", "predecessor_id":"uuid",
    "energy_binding_id":"uuid", "fleet_selection_id":"uuid",
    "fuel_pin_id":"uuid", "ir_pin_id":"uuid", "dy_pin_id":"uuid",
    "input_sha256":"bytea",
    "state":"text", "can_issue_vcu":"bool", "engine":"text", "afec":"text",
    "mpg":"text", "be_tco2e":"text", "pe_tco2e":"text", "le_tco2e":"text",
    "dy":"text", "er_tco2e":"text", "er_display":"text", "trace_json":"jsonb",
    "gate_codes":"_text", "created_by":"uuid", "created_at":"timestamptz"
}
COMMAND = {"actor_id":"uuid", "command_key":"text", "project_id":"text",
           "run_id":"uuid", "fingerprint":"bytea"}
QUERY = """
SELECT json_build_object(
 'history',(SELECT coalesce(json_agg(json_build_object('version',version,'checksum',checksum,
   'success',success,'script',script,'description',description,'installed_rank',installed_rank) ORDER BY installed_rank),'[]'::json) FROM flyway_schema_history
   WHERE version IN ('69','69.1','70','71')),
 'filename',(SELECT json_build_object('udt_name',udt_name,'nullable',is_nullable)
   FROM information_schema.columns WHERE table_schema='public'
     AND table_name='app_open_asset_evidence' AND column_name='submitted_file_name'),
 'filename_constraint',(SELECT pg_get_constraintdef(oid) FROM pg_constraint
   WHERE conrelid=to_regclass('public.app_open_asset_evidence')
     AND conname='app_open_asset_evidence_submitted_file_name_check'),
 'filename_trigger',(SELECT tgenabled FROM pg_trigger
   WHERE tgrelid=to_regclass('public.app_open_asset_evidence')
     AND tgname='app_open_asset_evidence_immutable'),
 'run',(SELECT coalesce(json_object_agg(column_name,udt_name),'{}'::json)
   FROM information_schema.columns WHERE table_schema='public' AND table_name='app_open_e8_run'),
 'command',(SELECT coalesce(json_object_agg(column_name,udt_name),'{}'::json)
   FROM information_schema.columns WHERE table_schema='public' AND table_name='app_open_e8_run_command'),
 'constraints',(SELECT coalesce(string_agg(pg_get_constraintdef(oid),'; '),'')
   FROM pg_constraint WHERE conrelid=to_regclass('public.app_open_e8_run')),
 'command_constraints',(SELECT coalesce(string_agg(pg_get_constraintdef(oid),'; '),'')
   FROM pg_constraint WHERE conrelid=to_regclass('public.app_open_e8_run_command')),
 'triggers',(SELECT coalesce(json_object_agg(tgname,tgenabled),'{}'::json)
   FROM pg_trigger WHERE tgrelid IN (to_regclass('public.app_open_e8_run'),
   to_regclass('public.app_open_e8_run_command')) AND NOT tgisinternal),
 'indexes',(SELECT coalesce(json_agg(indexname),'[]'::json) FROM pg_indexes
   WHERE schemaname='public' AND tablename='app_open_e8_run'),
 'actions',(SELECT pg_get_constraintdef(oid) FROM pg_constraint
   WHERE conrelid=to_regclass('public.app_project_technical_access_event')
   AND conname='app_project_technical_access_event_action_check')
);
"""

def checksum(path: Path) -> int:
    value = zlib.crc32("".join(path.read_text(encoding="utf-8").splitlines()).encode())
    return value if value < 2**31 else value-2**32

def sql(query: str) -> str:
    return demo.compose("exec","-T","db","psql","-X","-q","-t","-A",
                        "-v","ON_ERROR_STOP=1","-U","carbonflow","-d","carbonflow",
                        "-c",query,capture=True).strip()

def inspect() -> dict:
    return json.loads(sql(QUERY))

def problems(state: dict) -> list[str]:
    hist = {r["version"]:r for r in state.get("history",[])}
    errors = []
    if hist.get("69",{}).get("checksum") != V69:
        errors.append("V69 não tem o checksum publicado")
    if not hist.get("70",{}).get("success") or not hist.get("71",{}).get("success"):
        errors.append("V70/V71 não estão aplicadas com sucesso")
    if hist.get("71",{}).get("checksum") != OLD:
        errors.append("V71 não é o caso legado conhecido")
    if any(not r["success"] for r in hist.values()):
        errors.append("há migração sem sucesso")
    if state.get("run") != RUN or state.get("command") != COMMAND:
        errors.append("estrutura/colunas V71 diferentes do main; inspeção manual obrigatória")
    clauses = (state.get("constraints") or "").lower()
    command = (state.get("command_constraints") or "").lower()
    for term in ("primary key","unique", "app_project", "app_identity",
                 "app_open_e8_input_binding_candidate","app_open_e8_fleet_selection",
                 "can_issue_vcu", "false", "gate_codes", "cardinality", "reviewed_internal",
                 "blocked", "trace_json", "energy_binding_id", "fleet_selection_id", "input_sha256"):
        if term not in clauses:
            errors.append("constraint V71 ausente: "+term)
    for term in ("primary key","app_open_e8_run","app_identity","fingerprint","command_key"):
        if term not in command:
            errors.append("constraint comando V71 ausente: "+term)
    for trigger in ("app_open_e8_run_immutable","app_open_e8_run_command_immutable"):
        if state.get("triggers",{}).get(trigger) != "O":
            errors.append("trigger de imutabilidade ausente/inativo: "+trigger)
    if "app_open_e8_run_history" not in state.get("indexes",[]):
        errors.append("índice histórico V71 ausente")
    for action in ("VIEW_OPEN_E8_RUN","RECORD_OPEN_E8_RUN"):
        if action not in (state.get("actions") or ""):
            errors.append("ação de acesso V71 ausente: "+action)
    return errors

def legacy_filename_problems(state: dict) -> list[str]:
    """V71 in this historical sandbox is *not* the E8 migration.

    Reclassify it as V69.1 only if the resulting data/schema meet the
    published V69.1 contract. Its original SQL cannot be reconstructed.
    """
    hist={r["version"]:r for r in state.get("history",[])}
    errors=[]
    if hist.get("69",{}).get("checksum") != V69:
        errors.append("V69 base não corresponde ao arquivo publicado")
    if not hist.get("70",{}).get("success"):
        errors.append("V70 ausente ou com falha")
    old=hist.get("71",{})
    if (old.get("checksum") != OLD or not old.get("success") or
        old.get("script") != LEGACY_FILENAME_SCRIPT or
        old.get("description") != LEGACY_FILENAME_DESCRIPTION):
        errors.append("V71 não corresponde à migração histórica de filename identificada")
    if "69.1" in hist:
        errors.append("V69.1 já existe; NÃO reclassificar outra migração")
    if any(not row.get("success") for row in hist.values()):
        errors.append("histórico contém migração com falha")
    if state.get("run") or state.get("command"):
        errors.append("V71 E8 criou tabelas; este caso precisa de outra análise")
    if state.get("filename") != {"udt_name":"text","nullable":"NO"}:
        errors.append("submitted_file_name não é text NOT NULL")
    guard=state.get("filename_trigger")
    if guard != "O":
        errors.append("trigger imutável de evidência não está habilitado")
    definition=(state.get("filename_constraint") or "").lower()
    # Validate the published V69.1 CHECK semantics, not just the name.
    for required in ("submitted_file_name","pdf","a-za-z0-9","%..%"):
        if required not in definition:
            errors.append("CHECK do nome PDF incompatível: "+required)
    # Rows are checked separately with a SELECT and never touched by --apply.
    return errors


def legacy_filename_data_problems() -> list[str]:
    invalid = sql("""
SELECT count(*) FROM app_open_asset_evidence link
LEFT JOIN app_project_evidence_blob blob
  ON blob.project_id=link.project_id AND blob.id=link.evidence_blob_id
WHERE link.submitted_file_name IS NULL
   OR link.submitted_file_name !~ '^[A-Za-z0-9][A-Za-z0-9._-]{4,115}[.]pdf$'
   OR link.submitted_file_name LIKE '%..%'
   OR blob.id IS NULL OR blob.mime_type<>'application/pdf';
""")
    return [] if invalid=="0" else ["evidências PDF fora do contrato V69.1: "+invalid]


def reconcile_legacy_filename() -> None:
    """Move the *metadata entry*, never rename a table or falsify a run.

    A private audit copy of the exact old Flyway entry is saved before this
    controlled rewrite. The current V71 E8 remains PENDING and runs normally
    at next boot; old filename state becomes V69.1 only after verification.
    """
    sql(f"""
BEGIN;
LOCK TABLE flyway_schema_history IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
 IF (SELECT count(*) FROM flyway_schema_history
     WHERE version='71' AND description='{LEGACY_FILENAME_DESCRIPTION}'
       AND script='{LEGACY_FILENAME_SCRIPT}' AND checksum={OLD}
       AND success) != 1
   OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='69.1')
   OR to_regclass('public.app_open_e8_run') IS NOT NULL
   OR to_regclass('public.app_open_e8_run_command') IS NOT NULL
 THEN RAISE EXCEPTION 'Flyway history or E8 schema changed concurrently';
 END IF;
END $$;
UPDATE flyway_schema_history
 SET version='69.1',description='{CURRENT_FILENAME_DESCRIPTION}',
     script='{CURRENT_FILENAME_SCRIPT}', checksum={V69_1}
 WHERE version='71' AND description='{LEGACY_FILENAME_DESCRIPTION}'
   AND script='{LEGACY_FILENAME_SCRIPT}' AND checksum={OLD} AND success;
COMMIT;
""")


def main() -> None:
    parser = argparse.ArgumentParser(description="Verificar V71 no sandbox; --apply reconcilia só metadados.")
    parser.add_argument("--apply",action="store_true",help="Requer backup prévio e esquema compatível.")
    args = parser.parse_args()
    demo.validate()
    demo.runtime_check()
    if (checksum(SQL_DIR/"V69__open_asset_evidence_links.sql") != V69 or
        checksum(SQL_DIR/"V69_1__open_asset_evidence_submitted_filename.sql") != V69_1 or
        checksum(SQL_DIR/"V71__open_e8_project_run.sql") != NEW):
        raise RuntimeError("Arquivos de migração locais não correspondem às versões publicadas.")
    state = inspect()
    hist = {r["version"]:r for r in state["history"]}
    applied = hist.get("71",{}).get("checksum")
    print("Sandbox: V71="+str(applied)+", V69.1="+("aplicada" if
          hist.get("69.1",{}).get("success") else "pendente"))
    if applied == NEW:
        print("V71 E8 já aplicada e compatível; nenhuma alteração.")
        return
    legacy_filename = hist.get("71",{}).get("script") == LEGACY_FILENAME_SCRIPT
    violations = legacy_filename_problems(state) if legacy_filename else problems(state)
    if legacy_filename and not violations:
        violations += legacy_filename_data_problems()
    if violations:
        raise RuntimeError("Reparo recusado: "+"; ".join(violations))
    if not args.apply:
        print("Estrutura compatível com os testes de catálogo. Faça backup antes de --apply.")
        return
    demo.private_dir(demo.SECRET_DIR)
    os.umask(0o077)  # Audit receipt stays 0600; never publish user DB metadata.
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    path = demo.SECRET_DIR / ("flyway-v71-inspection-"+stamp+".json")
    with path.open("x",encoding="utf-8") as file:
        json.dump({"at":stamp,"project":demo.PROJECT,"state":state},file,indent=2)
        file.write("\n")
    if legacy_filename:
        reconcile_legacy_filename()
        after = inspect()
        history = {row["version"]:row for row in after["history"]}
        if ("71" in history or history.get("69.1",{}).get("checksum") != V69_1 or
            after.get("run") or after.get("command")):
            raise RuntimeError("Reconciliação da V71 histórica não foi confirmada.")
        print("V71 histórica de filename reclassificada como V69.1 após validações.")
        print("V71 E8 está PENDENTE e será executada pelo Flyway no próximo boot.")
        print("Registro original guardado em: "+str(path))
        return
    sql(f"""
BEGIN;
LOCK TABLE flyway_schema_history IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
 IF (SELECT count(*) FROM flyway_schema_history
     WHERE version='71' AND success AND checksum={OLD}) != 1 THEN
   RAISE EXCEPTION 'Migration V71 changed concurrently';
 END IF;
END $$;
UPDATE flyway_schema_history SET checksum={NEW}
 WHERE version='71' AND success AND checksum={OLD};
COMMIT;
""")
    after = inspect()
    if next((r["checksum"] for r in after["history"] if r["version"]=="71"),None) != NEW:
        raise RuntimeError("V71 não foi reconciliada; investigar sem executar repair genérico.")
    print("V71 reconciliada; dados preservados. Auditoria local privada: "+str(path))
    print("Inicie agora com python3 scripts/demo_sandbox.py start para aplicar V69.1.")

if __name__=="__main__":
    try:
        main()
    except Exception as error:
        print("Compatibilidade V71: "+str(error),file=sys.stderr)
        sys.exit(1)
