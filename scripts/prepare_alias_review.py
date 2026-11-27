#!/usr/bin/env python3
"""BL-DATA-01C/D: prepare the alias-candidate review from the output of build_fleet_universe.py, and turn the HUMAN decisions
into bulk bodies. Offline; it never calls the platform, never decides and never fills a decision.

  prepare    candidates.json + universe-manifest.json  ->  import batches (<= 500, with both source SHA-256) and a decision
             sheet whose decision columns are BLANK. Candidates whose record_key collides are left out and listed.
  decisions  filled sheet + queue export (GET /v1/platform/fleet/alias-candidates)  ->  bulk decision bodies (<= 100).
             Blank rows stay pending. An ACCEPTED row needs the chosen PBEV line and, for MODEL_ONLY/AMBIGUOUS, every risk
             code written in the sheet by the reviewer. Nothing is inferred from names.

Name matching is evidence for a reviewer, never a classification: MODEL_ONLY carries homonym, distinct-propulsion and
unconfirmed-version risks (same codes as FleetAliasCandidates.risksOf); registered vehicles are not circulating BEVs.
"""
import argparse
import csv
import json
import sys
from pathlib import Path

MAX_IMPORT = 500  # FleetAliasCandidates.MAX_ITEMS
MAX_BULK = 100    # FleetAliasCandidates.MAX_BULK
RISKS = {
    "EXACT": ["NAME_MATCH_ONLY"],
    "MODEL_ONLY": ["NAME_MATCH_ONLY", "HOMONYM", "DISTINCT_PROPULSION", "VERSION_UNCONFIRMED"],
    "AMBIGUOUS": ["NAME_MATCH_ONLY", "HOMONYM", "DISTINCT_PROPULSION", "VERSION_UNCONFIRMED", "MULTIPLE_CANDIDATES"],
}
COLUMNS = ["literal_name", "level", "registered_count", "risks_to_acknowledge", "pbev_options",
           "decision", "selected_pbev_line", "acknowledged_risks", "reason"]


class ReviewError(ValueError):
    pass


def options_text(match):
    return " | ".join(f"{c['pbev_line']}: {c['brand']} {c['model']} {c['version'] or ''}".strip() for c in match["candidates"])


def prepare(candidates, manifest, out):
    sha_pbev = (manifest.get("pbev") or {}).get("source_sha256")
    sha_sen = (manifest.get("source") or {}).get("sha256")
    if not sha_pbev or not sha_sen:
        raise ReviewError("O manifesto do universo precisa trazer o SHA-256 da Senatran e do PBEV.")
    period = candidates["observation_period"]
    refused = {literal for literals in candidates.get("refused_key_collisions", {}).values() for literal in literals}
    sendable, left_out = [], []
    for c in sorted(candidates["candidates"], key=lambda c: (-c["registered_count"], c["literal_name"])):
        (left_out if c["literal_name"] in refused or not c.get("record_key") else sendable).append(c)
    out.mkdir(parents=True, exist_ok=True)
    batches = []
    for number, start in enumerate(range(0, len(sendable), MAX_IMPORT), 1):
        body = {"observationPeriod": period, "pbevSourceSha256": sha_pbev, "senatranSourceSha256": sha_sen, "items": [
            {"literalName": c["literal_name"], "recordKey": c["record_key"], "level": c["match"]["level"],
             "reason": c["match"]["reason"],
             "matches": [{"brand": m["brand"], "model": m["model"], "version": m["version"], "category": m["category"],
                          "pbevLine": m["pbev_line"]} for m in c["match"]["candidates"]]}
            for c in sendable[start:start + MAX_IMPORT]]}
        path = out / f"alias-candidates-import-{number:03d}.json"
        path.write_text(json.dumps(body, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        batches.append({"file": path.name, "items": len(body["items"])})
    with (out / "decision-sheet.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(COLUMNS)
        for c in sendable:
            level = c["match"]["level"]
            writer.writerow([c["literal_name"], level, c["registered_count"], " ".join(RISKS[level]),
                             options_text(c["match"]), "", "", "", ""])
    summary = {"observation_period": period, "importable": len(sendable), "left_out_key_collisions": sorted(
        c["literal_name"] for c in left_out), "batches": batches, "decision_sheet": "decision-sheet.csv",
        "decisions_prefilled": 0, "usableForE8": False,
        "note": "Importar só depois que os direitos das duas fontes estiverem aprovados de forma independente na plataforma; "
                "use primeiro POST .../alias-candidates/dry-run."}
    (out / "review-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return summary


def decisions(sheet_rows, queue, basis, out):
    by_name = {}
    for item in queue:
        by_name.setdefault(item["literalName"], []).append(item)
    errors, items, pending = [], [], 0
    for number, row in enumerate(sheet_rows, 2):
        name, decision = row["literal_name"], (row.get("decision") or "").strip().upper()
        if not decision:
            pending += 1
            continue
        found = [q for q in by_name.get(name, []) if q["state"] == "PENDING"]
        if len(found) != 1:
            errors.append(f"linha {number}: {name!r} não tem exatamente um candidato PENDING na fila ({len(found)}).")
            continue
        candidate = found[0]
        reason = (row.get("reason") or "").strip()
        item = {"candidateId": candidate["id"], "outcome": decision, "reason": reason, "basis": basis}
        if decision not in ("ACCEPTED", "REJECTED"):
            errors.append(f"linha {number}: decisão {decision!r} inválida (ACCEPTED ou REJECTED).")
        elif len(reason) < 10:
            errors.append(f"linha {number}: motivo com pelo menos 10 caracteres é obrigatório.")
        elif decision == "REJECTED":
            if (row.get("selected_pbev_line") or "").strip():
                errors.append(f"linha {number}: REJECTED não leva linha do PBEV.")
            else:
                items.append(item)
        else:
            try:
                line = int((row.get("selected_pbev_line") or "").strip())
            except ValueError:
                errors.append(f"linha {number}: ACCEPTED exige a linha do PBEV escolhida.")
                continue
            if line not in {m["pbevLine"] for m in candidate["matches"]}:
                errors.append(f"linha {number}: linha {line} não é uma das opções do candidato.")
                continue
            acknowledged = sorted(set((row.get("acknowledged_risks") or "").replace(",", " ").split()))
            if candidate["level"] != "EXACT":
                missing = [r for r in RISKS[candidate["level"]] if r not in acknowledged]
                if missing:
                    errors.append(f"linha {number}: reconheça cada risco de {candidate['level']}: {' '.join(missing)}.")
                    continue
            item.update(selectedPbevLine=line, acknowledgedRisks=acknowledged)
            items.append(item)
    if errors:
        raise ReviewError("\n".join(errors))
    out.mkdir(parents=True, exist_ok=True)
    files = []
    for number, start in enumerate(range(0, len(items), MAX_BULK), 1):
        path = out / f"decisions-bulk-{number:03d}.json"
        path.write_text(json.dumps({"items": items[start:start + MAX_BULK]}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        files.append(path.name)
    return {"decisions": len(items), "still_pending": pending, "files": files,
            "note": "Rode primeiro POST .../alias-candidates/decisions/dry-run; aplicar é tudo-ou-nada por arquivo."}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--candidates", type=Path, required=True)
    p.add_argument("--universe-manifest", type=Path, required=True)
    p.add_argument("--output-dir", type=Path, required=True, help="diretório local FORA do versionamento")
    d = sub.add_parser("decisions")
    d.add_argument("--sheet", type=Path, required=True)
    d.add_argument("--queue", type=Path, required=True, help="JSON de GET /v1/platform/fleet/alias-candidates")
    d.add_argument("--basis", choices=["INDEPENDENT", "OWNER_INTERNAL"], required=True)
    d.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(json.loads(args.candidates.read_text(encoding="utf-8")),
                             json.loads(args.universe_manifest.read_text(encoding="utf-8")), args.output_dir)
        else:
            with args.sheet.open(encoding="utf-8", newline="") as stream:
                rows = list(csv.DictReader(stream))
            result = decisions(rows, json.loads(args.queue.read_text(encoding="utf-8")), args.basis, args.output_dir)
    except (ReviewError, KeyError, json.JSONDecodeError) as error:
        print(f"ERRO: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
