#!/usr/bin/env python3
"""Cross literal SENATRAN model names with the electric rows of the Inmetro PBEV table (#94/#95).

Offline research tool. It produces CANDIDATES for the canonical catalog's review queue, each with the evidence of why
it matched; it never approves an alias, never decides class, comparability or circulation, never writes application
tables and never picks one candidate when there is more than one. Absence of a match is a result, not an error:
a registered name without an electric PBEV row stays unmatched (e.g. a model the table does not list).

Levels (strongest first), computed on names compacted to letters and digits:
  EXACT       brand+model+version of one PBEV row equals the whole Senatran name (after the import prefix 'I/').
  MODEL_ONLY  no EXACT; the Senatran name starts with brand+model of PBEV rows. The longest such model wins, and every
              version of it is listed: the version stays UNDECIDED for the reviewer.
  NONE        nothing above.
A name that matches several distinct PBEV keys at its level is AMBIGUOUS and lists all of them.
"""
import argparse
import json
import re
import unicodedata
from pathlib import Path


def compact(text):
    folded = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^A-Z0-9]", "", folded.upper())


def senatran_core(literal):
    """Drops the import marker; every other '/' is just a separator, compacted away."""
    return compact(re.sub(r"^\s*I/", "", literal.strip(), flags=re.I))


def key(row):
    return (row["brand"], row["model"], row["version"])


def candidates(row):
    return {"brand": row["brand"], "model": row["model"], "version": row["version"],
            "category": row["category"], "pbev_line": row["line"]}


def match(literal, rows):
    core = senatran_core(literal)
    result = {"literal_name": literal, "compact_name": core, "imported_marker": bool(re.match(r"\s*I/", literal, re.I)),
              "requires_review": True, "approved": False}
    exact = [r for r in rows if compact(r["brand"] + r["model"] + (r["version"] or "")) == core]
    if exact:
        distinct = sorted({key(r) for r in exact}, key=lambda k: tuple(map(str, k)))
        result.update(status="AMBIGUOUS" if len(distinct) > 1 else "EXACT", level="EXACT",
                      candidates=[candidates(r) for r in exact],
                      reason="Marca, modelo e versão do PBEV, compactados, igualam o nome da Senatran.")
        return result
    best, best_len = [], 0
    for row in rows:
        stem = compact(row["brand"] + row["model"])
        if len(stem) >= 5 and core.startswith(stem) and len(stem) >= best_len:
            if len(stem) > best_len:
                best, best_len = [], len(stem)
            best.append(row)
    if best:
        models = sorted({(r["brand"], r["model"]) for r in best})
        result.update(status="AMBIGUOUS" if len(models) > 1 else "MODEL_ONLY", level="MODEL_ONLY",
                      candidates=[candidates(r) for r in best],
                      reason="Só marca e modelo coincidem; a versão do nome da Senatran não existe no PBEV "
                             "e fica indecidida para o revisor.")
        return result
    result.update(status="NONE", level="NONE", candidates=[],
                  reason="Nenhuma linha elétrica do PBEV coincide; o nome continua sem candidato.")
    return result


def cross(names, pbev):
    rows = pbev["rows"]
    matches = [match(name, rows) for name in names]
    counts = {}
    for item in matches:
        counts[item["status"]] = counts.get(item["status"], 0) + 1
    return {
        "matcher_version": 1,
        "pbev_source_sha256": pbev["source_sha256"],
        "pbev_count_matches_header": pbev["count_matches_header"],
        "status_counts": dict(sorted(counts.items())),
        "matches": matches,
        "approved_aliases": [],
        "usableForE8": False,
        "limitations": [
            "Candidato não é alias: o catálogo canônico decide, com prova e revisão independente.",
            "Estar no PBEV não prova circulação, classe LDV, comparabilidade nem compatibilidade DCFC.",
            "Edições distintas do PBEV e do ano de fabricação da Senatran são conceitos diferentes.",
            "O nome da Senatran sem candidato não significa que o veículo não seja elétrico: só que o PBEV extraído não o lista.",
        ],
    }


def names_from(args):
    if args.senatran_report:
        report = json.loads(Path(args.senatran_report).read_text(encoding="utf-8"))
        return list(report["selected_literal_models"])
    return args.name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pbev-json", type=Path, required=True, help="saída de extract_pbev_electric.py")
    parser.add_argument("--senatran-report", type=Path, help="saída de inspect_senatran_stock_sample.py")
    parser.add_argument("--name", action="append", default=[], help="nome literal da Senatran; repita")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    names = names_from(args)
    if not names:
        parser.error("informe --senatran-report ou ao menos um --name")
    result = cross(names, json.loads(args.pbev_json.read_text(encoding="utf-8")))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(names)} nomes; {result['status_counts']}; saída em {args.output}")


if __name__ == "__main__":
    main()
