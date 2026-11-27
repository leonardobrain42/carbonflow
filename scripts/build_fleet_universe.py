#!/usr/bin/env python3
"""BL-DATA-01A+B: streaming universe of every literal model in a SENATRAN ZIP, plus automatic PBEV candidates (#94/#95/#92).

Offline preparation tool. It reads the WHOLE aggregate (no hand-typed model list), keeps one record per literal
"Marca Modelo" key with its provenance, and proposes CANDIDATES for the canonical catalog's review queue using only the
printed electric rows of the Inmetro PBEV table. It never writes application tables, never decides propulsion, class,
circulation, comparability or coverage, never approves an alias and never produces an admitted EVR, weight or E8 input.

What the numbers mean (do not blur them):
  * Quantities are REGISTERED vehicles at the observation period (RENAVAM), not vehicles in circulation, not BEVs.
  * The source has no propulsion key. A model is a BEV CANDIDATE only when the PBEV electric table has a row for it, and
    that evidence is a name match: it can include a combustion model sharing the name. Everything else stays UNKNOWN,
    never excluded, never "not electric".
  * Unknown labels (UF "Não Identificado", "Sem Informação", unknown manufacture year) are preserved and counted.
  * Distinct literals that would collapse to one parser record_key are REFUSED from the stock CSV and listed (never merged).

Outputs go to a local directory outside version control; only aggregate counts are meant for the repository.
"""
import argparse
import csv
import gzip
import hashlib
import io
import json
import re
import sys
import time
import zipfile
from collections import Counter
from pathlib import Path

from build_evr_stock_csv import COLUMNS, KEY_PATTERN, MAX_ROWS, key_for
from inspect_senatran_stock_sample import HEADERS, MONTHS, quantity
from match_senatran_pbev import compact, senatran_core

TOOL_VERSION = 1
MAX_IMPORT_ITEMS = 500  # FleetAliasCandidates.MAX_ITEMS
DEFAULT_MAX_EXPANDED = 2 * 1024 ** 3
MAX_RATIO = 100
YEAR = re.compile(r"\d{4}")
UNKNOWN_UF_LABELS = {"Não Identificado", "Não se Aplica", "Sem Informação"}


class SourceError(ValueError):
    """The source does not match what was declared; nothing is produced."""


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


class Counting(io.RawIOBase):
    """Counts the real expanded bytes so a header that understates the size cannot slip past the guard."""

    def __init__(self, stream, limit):
        super().__init__()
        self.stream, self.limit, self.read_bytes = stream, limit, 0

    def readable(self):
        return True

    def readinto(self, buffer):
        block = self.stream.read(len(buffer))
        buffer[:len(block)] = block
        self.read_bytes += len(block)
        if self.read_bytes > self.limit:
            raise SourceError("Conteúdo expandido acima do limite declarado: ZIP recusado.")
        return len(block)


def check_member(archive, period, max_expanded):
    members = [entry for entry in archive.infolist() if not entry.is_dir()]
    if len(members) != 1 or not members[0].filename.lower().endswith(".txt"):
        raise SourceError("Esperado exatamente um TXT agregado no ZIP.")
    member = members[0]
    name = member.filename
    if name.startswith("/") or ".." in Path(name).parts or "\\" in name:
        raise SourceError("Nome de membro inseguro no ZIP.")
    if member.file_size > max_expanded:
        raise SourceError(f"TXT expandido ({member.file_size} bytes) acima do limite de {max_expanded}.")
    if member.compress_size and member.file_size / member.compress_size > MAX_RATIO:
        raise SourceError("Razão de compressão suspeita (possível ZIP bomb).")
    parts = Path(name).stem.rsplit("_", 2)
    if len(parts) != 3 or period != parts[-1] + "-" + MONTHS.get(parts[-2].lower(), ""):
        raise SourceError("Período divergente do mês/ano do arquivo publicado.")
    return member


def stream_universe(zip_path, period, max_expanded=DEFAULT_MAX_EXPANDED, progress=None):
    """One pass over the TXT. Raises on any malformed row: nothing is skipped silently."""
    if not re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])", period):
        raise SourceError("Período de observação deve ser YYYY-MM.")
    models, uf_totals, year_labels = {}, Counter(), Counter()
    uf_index, rows, registered = {}, 0, 0
    with zipfile.ZipFile(zip_path) as archive:
        member = check_member(archive, period, max_expanded)
        with archive.open(member) as raw:
            counted = Counting(raw, max_expanded)
            text = io.TextIOWrapper(io.BufferedReader(counted, 1024 * 1024), encoding="utf-8-sig", newline="")
            reader = csv.reader(text, delimiter=";")
            if next(reader, None) != HEADERS:
                raise SourceError("Schema não reconhecido: cabeçalho diferente do esperado.")
            for line, row in enumerate(reader, 2):
                if len(row) != len(HEADERS):
                    raise SourceError(f"Linha {line}: número de colunas divergente.")
                uf, _municipality, literal, year, raw_count = row
                count = quantity(raw_count, line)
                if not literal.strip():
                    raise SourceError(f"Linha {line}: marca/modelo vazio.")
                rows += 1
                registered += count
                uf_totals[uf] += count
                if not YEAR.fullmatch(year.strip()):
                    year_labels[year.strip() or "(vazio)"] += count
                bit = uf_index.setdefault(uf, len(uf_index))
                entry = models.get(literal)
                if entry is None:
                    entry = models[literal] = [0, 0, 0, {}]
                entry[0] += count
                entry[1] += 1
                entry[2] |= 1 << bit
                entry[3][year] = entry[3].get(year, 0) + count
                if progress and rows % 2_000_000 == 0:
                    progress(rows, len(models))
            expanded = counted.read_bytes
    return {"models": models, "uf_totals": dict(uf_totals), "uf_labels": sorted(uf_index, key=uf_index.get),
            "non_numeric_year_registered": dict(year_labels), "rows": rows, "registered": registered,
            "expanded_bytes": expanded, "member": member.filename}


class PbevIndex:
    """Same decision rules as match_senatran_pbev.match (EXACT, then longest MODEL_ONLY, ambiguity listed), indexed so that
    hundreds of thousands of literals do not recompact every PBEV row."""

    def __init__(self, rows):
        self.exact, self.stems = {}, {}
        for row in rows:
            self.exact.setdefault(compact(row["brand"] + row["model"] + (row["version"] or "")), []).append(row)
            stem = compact(row["brand"] + row["model"])
            if len(stem) >= 5:
                self.stems.setdefault(stem, []).append(row)

    @staticmethod
    def key(row):
        return (row["brand"], row["model"], row["version"])

    @staticmethod
    def evidence(row):
        return {"brand": row["brand"], "model": row["model"], "version": row["version"],
                "category": row["category"], "pbev_line": row["line"]}

    def match(self, literal):
        core = senatran_core(literal)
        base = {"literal_name": literal, "compact_name": core, "requires_review": True, "approved": False}
        exact = self.exact.get(core)
        if exact:
            distinct = sorted({self.key(r) for r in exact}, key=lambda k: tuple(map(str, k)))
            return {**base, "status": "AMBIGUOUS" if len(distinct) > 1 else "EXACT", "level": "EXACT",
                    "candidates": [self.evidence(r) for r in exact],
                    "reason": "Marca, modelo e versão do PBEV, compactados, igualam o nome da Senatran."}
        best, best_len = [], 0
        for stem, rows in self.stems.items():
            if core.startswith(stem) and len(stem) >= best_len:
                if len(stem) > best_len:
                    best, best_len = [], len(stem)
                best.extend(rows)
        if best:
            models = sorted({(r["brand"], r["model"]) for r in best})
            return {**base, "status": "AMBIGUOUS" if len(models) > 1 else "MODEL_ONLY", "level": "MODEL_ONLY",
                    "candidates": [self.evidence(r) for r in best],
                    "reason": "Só marca e modelo coincidem; a versão do nome da Senatran não existe no PBEV "
                              "e fica indecidida para o revisor."}
        return {**base, "status": "NONE", "level": "NONE", "candidates": [],
                "reason": "Nenhuma linha elétrica do PBEV coincide; o nome continua sem candidato."}


CLASSIFICATION = {"EXACT": "PBEV_EXACT_CANDIDATE", "MODEL_ONLY": "PBEV_MODEL_ONLY_CANDIDATE",
                  "AMBIGUOUS": "PBEV_AMBIGUOUS_CANDIDATE", "NONE": "UNKNOWN_NO_PBEV_MATCH"}


def build_universe(universe, pbev_rows, period):
    """One record per literal key, heaviest first (ties by name) so the output is deterministic."""
    index = PbevIndex(pbev_rows) if pbev_rows is not None else None
    ordered = sorted(universe["models"].items(), key=lambda item: (-item[1][0], item[0]))
    records, key_owner, collisions = [], {}, {}
    for literal, (count, source_rows, ufmask, by_year) in ordered:
        try:
            record_key, key_error = key_for(literal), None
        except ValueError as error:
            record_key, key_error = None, str(error)
        if record_key is not None:
            if record_key in key_owner:
                collisions.setdefault(record_key, [key_owner[record_key]]).append(literal)
            else:
                key_owner[record_key] = literal
        found = index.match(literal) if index else None
        records.append({
            "literal_name": literal, "record_key": record_key, "key_error": key_error,
            "registered_count": count, "source_rows": source_rows, "uf_labels_count": bin(ufmask).count("1"),
            "by_manufacture_year": dict(sorted(by_year.items())),
            "propulsion": "UNKNOWN_IN_SOURCE",
            "classification": CLASSIFICATION[found["status"]] if found else "UNKNOWN_PBEV_NOT_PROVIDED",
            "match": found and {"status": found["status"], "level": found["level"], "reason": found["reason"],
                                "candidates": found["candidates"]},
            "usableForE8": False, "approved": False,
        })
    return records, collisions


def refused_keys(collisions):
    return {literal for literals in collisions.values() for literal in literals}


def summarize(universe, records, collisions, source, expected):
    classes = Counter(r["classification"] for r in records)
    registered = Counter()
    for r in records:
        registered[r["classification"]] += r["registered_count"]
    # None = not checked (no expected value given); False = divergent; True = reconciled.
    reconciled = {"rows_match": None if expected.get("rows") is None else expected["rows"] == universe["rows"],
                  "registered_match": None if expected.get("registered") is None
                  else expected["registered"] == universe["registered"]}
    return {
        "tool": "build_fleet_universe", "tool_version": TOOL_VERSION,
        "source": source,
        "totals": {"rows": universe["rows"], "registered_vehicles": universe["registered"],
                   "distinct_literal_models": len(records), "expanded_bytes": universe["expanded_bytes"]},
        "expected": expected, "reconciliation": reconciled,
        "locations": {"uf_labels": len(universe["uf_labels"]),
                      "unknown_labels_preserved": {k: v for k, v in sorted(universe["uf_totals"].items())
                                                   if k in UNKNOWN_UF_LABELS}},
        "manufacture_year": {"non_numeric_labels_registered": universe["non_numeric_year_registered"]},
        "classification": {"literal_models": dict(sorted(classes.items())),
                           "registered_vehicles_in_candidate_keys": dict(sorted(registered.items()))},
        "record_keys": {"collisions": len(collisions), "literals_refused_from_csv": len(refused_keys(collisions)),
                        "invalid_keys": sum(1 for r in records if r["key_error"])},
        "usableForE8": False, "evr_total": None, "weights": None,
        "limitations": [
            "Quantidades são veículos REGISTRADOS em 2026-09; não provam circulação, propulsão BEV, classe LDV, "
            "compatibilidade DCFC nem cobertura da população metodológica.",
            "A fonte não tem chave de propulsão: candidato BEV é só correspondência de nome com o PBEV elétrico e pode "
            "incluir veículo a combustão de mesmo nome. UNKNOWN não significa que o veículo não seja elétrico.",
            "A soma dos registrados nas chaves candidatas NÃO é um denominador EVR nem um total de BEVs.",
            "Nenhum alias, classificação, peso ou cobertura foi decidido ou admitido.",
        ],
    }


def write_outputs(out, records, collisions, summary, period):
    out.mkdir(parents=True, exist_ok=True)
    universe_path = out / "universe.jsonl.gz"
    with gzip.GzipFile(universe_path, "wb", mtime=0) as raw, io.TextIOWrapper(raw, encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    refused = refused_keys(collisions)
    candidates = [r for r in records if r["match"] and r["match"]["status"] != "NONE"]
    sendable = [r for r in candidates if r["record_key"] and r["literal_name"] not in refused]
    (out / "candidates.json").write_text(json.dumps(
        {"observation_period": period, "count": len(candidates), "approved_aliases": [], "usableForE8": False,
         "candidates": candidates, "refused_key_collisions": collisions}, ensure_ascii=False, indent=1) + "\n",
        encoding="utf-8")
    imports = []
    pbev_sha = summary["pbev"]["source_sha256"] if summary.get("pbev") else None
    for number, start in enumerate(range(0, len(sendable), MAX_IMPORT_ITEMS), 1):
        batch = sendable[start:start + MAX_IMPORT_ITEMS]
        body = {"observationPeriod": period, "pbevSourceSha256": pbev_sha, "items": [
            {"literalName": r["literal_name"], "recordKey": r["record_key"], "level": r["match"]["level"],
             "reason": r["match"]["reason"],
             "matches": [{"brand": c["brand"], "model": c["model"], "version": c["version"],
                          "category": c["category"], "pbevLine": c["pbev_line"]} for c in r["match"]["candidates"]]}
            for r in batch]}
        path = out / f"alias-candidates-import-{number:03d}.json"
        path.write_text(json.dumps(body, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        imports.append({"file": path.name, "items": len(batch), "sha256": sha256_file(path)})
    csvs = []
    for number, start in enumerate(range(0, len(sendable), MAX_ROWS), 1):
        chunk = sendable[start:start + MAX_ROWS]
        path = out / f"stock-candidates-{number:03d}.csv"
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.writer(stream, lineterminator="\n")
            writer.writerow(COLUMNS)
            for r in chunk:
                writer.writerow([r["record_key"], period[:4], "stock", r["registered_count"], "vehicles", "BR"])
        csvs.append({"file": path.name, "rows": len(chunk), "sha256": sha256_file(path)})
    summary["artifacts"] = {
        "universe": {"file": universe_path.name, "records": len(records), "sha256": sha256_file(universe_path)},
        "candidates": {"file": "candidates.json", "count": len(candidates), "sendable": len(sendable)},
        "alias_candidate_batches": imports, "stock_csv_chunks": csvs,
        "stock_csv_scope": "CANDIDATES_ONLY: parcial, não é o universo nem um snapshot completo; "
                           "chunks ausentes não formam cobertura (BD-T07).",
    }
    (out / "universe-manifest.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                                                encoding="utf-8")
    return summary


def expected_from(manifest_path, file_name):
    """Declared SHA-256, size and totals of the source, from the versioned evidence manifest."""
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    download = next((d for d in manifest["downloads"] if d["file"] == file_name), None)
    if download is None:
        raise SourceError(f"Arquivo {file_name!r} não consta do manifesto.")
    return {"sha256": download["sha256"], "bytes": download["bytes"],
            "registered": manifest.get("fuel_cross_check", {}).get("total_all_fuels"),
            "url": download.get("url"), "captured_at": download.get("captured_at")}


def run(zip_path, period, manifest_path, manifest_file, pbev_json, out, expected_rows=None,
        max_expanded=DEFAULT_MAX_EXPANDED, progress=None):
    declared = expected_from(manifest_path, manifest_file)
    actual_bytes = Path(zip_path).stat().st_size
    actual_sha = sha256_file(zip_path)
    if actual_sha != declared["sha256"] or actual_bytes != declared["bytes"]:
        raise SourceError(f"SHA-256 ou tamanho divergem do manifesto: {actual_sha} ({actual_bytes} bytes).")
    pbev, pbev_rows = None, None
    if pbev_json:
        loaded = json.loads(Path(pbev_json).read_text(encoding="utf-8"))
        if not loaded.get("count_matches_header"):
            raise SourceError("A extração do PBEV não confere com a contagem do cabeçalho.")
        pbev_rows = loaded["rows"]
        pbev = {"source_sha256": loaded["source_sha256"], "electric_rows": len(pbev_rows)}
    started = time.time()
    universe = stream_universe(zip_path, period, max_expanded, progress)
    expected = {"registered": declared["registered"], "rows": expected_rows}
    records, collisions = build_universe(universe, pbev_rows, period)
    source = {"file": manifest_file, "sha256": actual_sha, "bytes": actual_bytes, "sha256_matches_manifest": True,
              "member": universe["member"], "observation_period": period, "declared_url": declared["url"],
              "declared_captured_at": declared["captured_at"], "license_declared": "Outra (Domínio Público)"}
    summary = summarize(universe, records, collisions, source, expected)
    summary["pbev"] = pbev
    summary["elapsed_seconds"] = round(time.time() - started, 1)
    write_outputs(Path(out), records, collisions, summary, period)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--zip", type=Path, required=True)
    parser.add_argument("--period", required=True, help="período de observação YYYY-MM (deve bater com o nome do arquivo)")
    parser.add_argument("--manifest", type=Path, required=True, help="source-manifest.json versionado")
    parser.add_argument("--manifest-file", default="renavam-2026-09.zip")
    parser.add_argument("--pbev-json", type=Path, help="saída de extract_pbev_electric.py")
    parser.add_argument("--expect-rows", type=int, help="linhas de dados esperadas (ex.: 22849046)")
    parser.add_argument("--max-expanded-bytes", type=int, default=DEFAULT_MAX_EXPANDED)
    parser.add_argument("--output-dir", type=Path, required=True, help="diretório local FORA do versionamento")
    args = parser.parse_args()
    try:
        summary = run(args.zip, args.period, args.manifest, args.manifest_file, args.pbev_json, args.output_dir,
                      args.expect_rows, args.max_expanded_bytes,
                      lambda rows, models: print(f"  {rows:,} linhas; {models:,} modelos", file=sys.stderr))
    except (SourceError, ValueError, zipfile.BadZipFile) as error:
        print(f"ERRO: {error}", file=sys.stderr)
        return 2
    totals, rec = summary["totals"], summary["reconciliation"]
    print(f"{totals['rows']:,} linhas; {totals['registered_vehicles']:,} veículos registrados; "
          f"{totals['distinct_literal_models']:,} modelos literais; classes {summary['classification']['literal_models']}")
    print(f"reconciliação com o manifesto: {rec}; saída em {args.output_dir}")
    return 3 if any(value is False for value in rec.values()) else 0


if __name__ == "__main__":
    raise SystemExit(main())
