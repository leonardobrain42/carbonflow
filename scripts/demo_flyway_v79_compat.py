#!/usr/bin/env python3
"""Explicit, demo-only compatibility for the two different V79 migrations."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

import demo_sandbox as demo
from demo_flyway_compat import checksum, sql, SQL_DIR

OLD = -898586753
TIMING = 1403819066
HOURLY = 1264111112
PINS = -701809868
LEGACY = "V79__open_e8_run_calendar.sql"
CALENDAR = "V81__open_e8_run_calendar.sql"
CURRENT = "V79__open_e8_run_timing.sql"
NEXT = "V80__open_e8_hourly_engine_constraint.sql"
DESCRIPTION = "open e8 run calendar"

# Validate the original DDL effects, not only column names. Both columns
# must be nullable, without defaults, and protected by their validated CHECK.
def column_guard(table: str, column: str, typ: str, expression: str) -> str:
    return f"""EXISTS (
      SELECT 1 FROM pg_attribute a
      JOIN pg_constraint c ON c.conrelid=a.attrelid
      WHERE a.attrelid=to_regclass('public.{table}')
        AND a.attname='{column}' AND a.attnum>0 AND NOT a.attisdropped
        AND a.atttypid='{typ}'::regtype AND NOT a.attnotnull
        AND NOT a.atthasdef AND a.attgenerated='' AND a.attidentity=''
        AND c.contype='c' AND c.convalidated
        AND c.conkey=ARRAY[a.attnum]::smallint[]
        AND regexp_replace(pg_get_expr(c.conbin,c.conrelid),
            '[[:space:]()]','','g')={expression}
    )"""

COLUMNS = (
    column_guard("app_open_e8_run", "temporal_context", "jsonb",
                 "'jsonb_typeoftemporal_context=''object''::text'")
    + " AND " +
    column_guard("app_open_e8_run_command", "request_sha256", "bytea",
                 "'octet_lengthrequest_sha256=32'")
)
# Also require the expected immutable guards to remain enabled.
TRIGGERS = """(SELECT count(*) FROM pg_trigger
 WHERE NOT tgisinternal AND tgenabled IN ('O','A') AND (
 (tgrelid=to_regclass('public.app_open_e8_run') AND tgname='app_open_e8_run_immutable')
 OR (tgrelid=to_regclass('public.app_open_e8_run_command') AND
     tgname='app_open_e8_run_command_immutable'))) = 2"""

def exact(version: str, filename: str, description: str, crc: int) -> str:
    return f"""(SELECT count(*) FROM public.flyway_schema_history
     WHERE version='{version}' AND success AND checksum={crc}
       AND script='db/migration/{filename}' AND description='{description}')=1"""

OLD_ROW = exact("79", LEGACY, DESCRIPTION, OLD)
NEW_ROW = exact("81", CALENDAR, DESCRIPTION, OLD)
V78_ROW = exact("78", "V78__open_e8_run_parameter_pins.sql",
                "open e8 run parameter pins", PINS)
V79_ROW = exact("79", CURRENT, "open e8 run timing", TIMING)
V80_ROW = exact("80", NEXT, "open e8 hourly engine constraint", HOURLY)
GUARD = f"""current_database()='carbonflow'
 AND {OLD_ROW} AND {V78_ROW}
 AND (SELECT count(*) FROM public.flyway_schema_history WHERE version='79')=1
 AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success)
 AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history
                 WHERE CASE WHEN version ~ '^[0-9]+([.][0-9]+)?$'
                            THEN version::numeric>79 ELSE false END)
 AND (SELECT installed_rank FROM public.flyway_schema_history WHERE version='79')
     = (SELECT max(installed_rank) FROM public.flyway_schema_history)
 AND to_regclass('public.app_open_e8_run_timing') IS NULL
 AND {COLUMNS} AND {TRIGGERS}"""

QUERY = f"""SELECT jsonb_build_object(
 'history',(SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY installed_rank),'[]'::jsonb)
            FROM public.flyway_schema_history h WHERE version IN ('78','79','80','81')),
 'legacy_ok',({GUARD}),
 'calendar_ok',({NEW_ROW} AND {COLUMNS} AND {TRIGGERS}),
 'timing_ok',({V79_ROW}),
 'hourly_ok',({V80_ROW}),
 'timing_table',to_regclass('public.app_open_e8_run_timing') IS NOT NULL,
 'failed',EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE NOT success)
);"""

def inspect() -> dict:
    return json.loads(sql(QUERY))

def state_kind(state: dict) -> str:
    hist = {r["version"]: r for r in state["history"]}
    if state["failed"] or len(hist) != len(state["history"]):
        return "REFUSED"
    if state["legacy_ok"]:
        return "LEGACY"
    if state["calendar_ok"]:
        if ("79" in hist) != state["timing_ok"] or ("80" in hist) != state["hourly_ok"]:
            return "REFUSED"
        if state["timing_ok"] != state["timing_table"] or (state["hourly_ok"] and not state["timing_ok"]):
            return "REFUSED"
        return "COMPLETE" if state["hourly_ok"] else "PENDING"
    if ("81" not in hist and state["timing_ok"] and state["timing_table"]
            and ("80" not in hist or state["hourly_ok"])):
        return "NORMAL"
    return "REFUSED"

def check_files() -> None:
    for name, expected in ((CALENDAR, OLD), (CURRENT, TIMING), (NEXT, HOURLY),
                           ("V78__open_e8_run_parameter_pins.sql", PINS)):
        if checksum(SQL_DIR / name) != expected:
            raise RuntimeError("Checksum local inesperado: " + name)

def require_stopped() -> None:
    if "app" in demo.compose("ps", "--status", "running", "--services", "app",
                             capture=True).splitlines():
        raise RuntimeError("Pare o serviço app antes da reconciliação.")

def verify_backup(path: Path) -> str:
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
        raise RuntimeError("Backup inexistente ou não privado; use arquivo pg_dump -Fc com chmod 600.")
    command = ["docker", "compose", "-p", demo.PROJECT, "-f",
               str(demo.ROOT / "compose.yaml"), "-f", str(demo.ROOT / "compose.demo.yaml"),
               "exec", "-T", "db", "pg_restore", "-l"]
    with path.open("rb") as stream:
        result = subprocess.run(command, stdin=stream, capture_output=True, check=True,
                                env={**os.environ, "CARBONFLOW_PORT": demo.PORT})
    listing = result.stdout.decode("utf-8", errors="replace")
    for table in ("flyway_schema_history", "app_open_e8_run", "app_open_e8_run_command"):
        if f"TABLE DATA public {table} " not in listing:
            raise RuntimeError("Backup não contém os dados esperados: " + table)
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def reconcile() -> None:
    # Repeat ALL predicates under locks. No project rows are updated and
    # the historical checksum, rank, installed_on and execution time survive.
    sql(f"""
BEGIN;
SET LOCAL lock_timeout='10s';
LOCK TABLE public.flyway_schema_history IN ACCESS EXCLUSIVE MODE;
LOCK TABLE public.app_open_e8_run, public.app_open_e8_run_command IN SHARE MODE;
DO $$
BEGIN
 IF NOT ({GUARD}) THEN
   RAISE EXCEPTION 'Legacy V79 state changed or is incompatible';
 END IF;
 UPDATE public.flyway_schema_history
 SET version='81',script='db/migration/{CALENDAR}'
 WHERE version='79' AND success AND checksum={OLD}
   AND script='db/migration/{LEGACY}' AND description='{DESCRIPTION}';
 IF NOT FOUND THEN RAISE EXCEPTION 'Legacy migration missing'; END IF;
END $$;
COMMIT;
""")

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--backup", type=Path, help="Backup privado pg_dump -Fc, obrigatório com --apply")
    args = parser.parse_args()
    demo.validate()
    demo.runtime_check()
    check_files()
    state = inspect()
    kind = state_kind(state)
    print("Sandbox V79/V81:", kind)
    if kind == "REFUSED":
        raise RuntimeError("Estado não reconhecido. Não execute repair; investigue o catálogo.")
    if kind != "LEGACY":
        print("Nenhuma alteração. NORMAL/PENDING: inicie o sandbox; COMPLETE: upgrade concluído.")
        return
    if not args.apply:
        print("V79 histórica confirmada; V79 Timing e V80 pendentes. Faça backup antes de --apply.")
        return
    if args.backup is None:
        raise RuntimeError("Informe --backup com o dump anterior à alteração.")
    require_stopped()
    digest = verify_backup(args.backup)
    demo.private_dir(demo.SECRET_DIR)
    os.umask(0o077)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    receipt = demo.SECRET_DIR / ("flyway-v79-inspection-" + stamp + ".json")
    with receipt.open("x", encoding="utf-8") as file:
        json.dump({"at": stamp, "project": demo.PROJECT, "backup_sha256": digest,
                   "state": state}, file, indent=2)
        file.write("\n")
    reconcile()
    if state_kind(inspect()) != "PENDING":
        raise RuntimeError("Reconciliação não confirmada; preserve backup e recibo.")
    print("V79 Calendar reclassificada como V81; V79 Timing e V80 PENDENTES.")
    print("Recibo privado:", receipt)
    print("Agora execute python3 scripts/demo_sandbox.py start.")

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Compatibilidade V79:", error, file=sys.stderr)
        sys.exit(1)
