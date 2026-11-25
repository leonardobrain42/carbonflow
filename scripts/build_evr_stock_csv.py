#!/usr/bin/env python3
"""Turn an authorized SENATRAN ZIP plus an EXPLICIT list of literal model names into the platform import CSV (#94).

Bridge only. It never decides which models are BEV/LDV/comparable (the caller names them, with their own evidence),
never matches names automatically, never writes application tables and never produces an admitted EVR, weights or
inputs for E8. Rows are REGISTERED vehicles at the observation period, not proof of circulation. The CSV goes through
the existing quarantine, alias, rights and review steps (CF-OPEN-005); nothing here skips them.
"""
import argparse
import csv
import json
import re
from pathlib import Path

from inspect_senatran_stock_sample import inspect

KEY_PATTERN = re.compile(r"[A-Za-z0-9._-]{1,100}")
MAX_ROWS = 2000  # TabularCandidateParser.MAX_ROWS
COLUMNS = ["record_key", "period_year", "metric", "value", "unit", "geography"]


def key_for(literal):
    """Deterministic and reversible through the manifest: characters the parser rejects become '_'."""
    key = re.sub(r"[^A-Za-z0-9._-]", "_", literal.strip())
    if not KEY_PATTERN.fullmatch(key):
        raise ValueError(f"Nome literal sem chave válida (vazio ou acima de 100 caracteres): {literal!r}")
    return key


def build(zip_file, period, models):
    report = inspect(zip_file, period, models)
    year = period[:4]
    rows, manifest, absent = [], {}, []
    for literal in models:
        key = key_for(literal)
        if key in manifest:
            raise ValueError(f"Nomes literais distintos geram a mesma chave {key!r}: {manifest[key]!r} e {literal!r}")
        manifest[key] = literal
        count = report["selected_literal_models"][literal]["registered_count"]
        if count is None:
            absent.append(literal)  # absence stays absence: no row, never a zero
            continue
        rows.append([key, year, "stock", str(count), "vehicles", "BR"])
    if len(rows) > MAX_ROWS:
        raise ValueError(f"{len(rows)} linhas excedem o limite de {MAX_ROWS} do parser de importação")
    return rows, {
        "bridge_version": 1,
        "observation_period": period,
        "source_sha256": report["source_sha256"],
        "source_rows": report["source_rows"],
        "all_registered_count": report["all_registered_count"],
        "metric_meaning": "REGISTERED_VEHICLES",
        "key_to_literal_name": manifest,
        "literal_names_without_rows": absent,
        "evr_total": None,
        "weights": None,
        "usableForE8": False,
        "limitations": report["limitations"] + [
            "A chave do CSV é só o nome literal com caracteres inválidos trocados por '_'; alias e identidade do modelo "
            "continuam a decidir nos módulos canônicos, com prova.",
            "A quantidade é de veículos registrados no período; não é estoque em circulação nem total nacional de BEVs.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_file", type=Path)
    parser.add_argument("--period", required=True, help="YYYY-MM, igual ao mês do arquivo")
    parser.add_argument("--model", action="append", required=True, help="nome literal; repita para cada um")
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    rows, manifest = build(args.zip_file, args.period, args.model)
    with args.csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(COLUMNS)
        writer.writerows(rows)
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(rows)} linhas; {len(manifest['literal_names_without_rows'])} nomes sem linha; "
          f"CSV em {args.csv}, manifesto em {args.manifest}")


if __name__ == "__main__":
    main()
