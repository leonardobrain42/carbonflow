#!/usr/bin/env python3
"""BL-DATA-01E: prepare the stock file for the existing quarantine/rights/review path (#92/#94) from the registered-vehicle
candidates of build_fleet_universe.py and a catalog export, and reconcile every vehicle. Offline: it never calls the platform,
creates no model/alias/classification, decides no coverage and produces no weight.

  stock candidates CSV (record_key,period_year,metric,value,unit,geography)  +  catalog export (GET /v1/platform/fleet/board)
    -> stock-ingestion-001.csv   one row per CANONICAL MODEL that is LDV+BEV in the catalog (several literal names of the same
                                 model are summed; the backend refuses two rows for one model), keyed by one of its aliases
    -> stock-reconciliation.json every input row accounted for (included / outside scope / pending classification / no alias),
                                 vehicles per bucket, the per-model composition, both SHA-256 and the open gaps.

The recorte is the catalog as it stands: keys without alias, models still UNKNOWN or outside LDV BEV stay OUT and are listed with
their vehicles. The result is NEVER a national denominator (coverage is unknown until an independent decision) and registered
vehicles are not circulating vehicles until the independent assessment says so. Summing literal names of one model is a derived
calculation of the RENAVAM aggregate (rights matrix, EXTRACT_TEXT).
"""
import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path

MAX_ROWS = 2000  # TabularCandidateParser.MAX_ROWS (also FleetCompositionRules: 2.000 observations)
COLUMNS = ["record_key", "period_year", "metric", "value", "unit", "geography"]
BUCKETS = ["INCLUDED", "OUTSIDE_SCOPE", "PENDING_CLASSIFICATION", "NO_ALIAS"]


class StockError(ValueError):
    pass


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def read_stock(path, year):
    with Path(path).open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != COLUMNS:
            raise StockError(f"Cabeçalho do estoque deve ser {','.join(COLUMNS)}.")
        rows, seen = [], set()
        for number, row in enumerate(reader, 2):
            key = row["record_key"].strip().upper()
            if not key or key in seen:
                raise StockError(f"linha {number}: chave vazia ou repetida ({row['record_key']!r}).")
            seen.add(key)
            if row["metric"] != "stock" or row["unit"] != "vehicles" or row["geography"] != "BR" or row["period_year"] != str(year):
                raise StockError(f"linha {number}: espere metric=stock, unit=vehicles, geography=BR e period_year={year}.")
            try:
                value = int(row["value"])
            except ValueError:
                raise StockError(f"linha {number}: quantidade inteira obrigatória; arredondar é proibido.") from None
            if value < 0:
                raise StockError(f"linha {number}: quantidade negativa.")
            rows.append({"key": key, "value": value})
    return rows


def reconcile(stock, catalog, organization, year):
    models = {m["id"]: m for m in catalog.get("models", [])}
    aliases = {a["recordKey"].upper(): a["modelId"] for a in catalog.get("aliases", [])
               if a["organizationId"] == organization and a["observationYear"] == year}
    buckets = {name: [] for name in BUCKETS}
    by_model = {}
    for row in stock:
        model_id = aliases.get(row["key"])
        model = models.get(model_id) if model_id else None
        if model is None:
            buckets["NO_ALIAS"].append(row)
        elif model["vehicleClass"] == "UNKNOWN" or model["propulsion"] == "UNKNOWN":
            buckets["PENDING_CLASSIFICATION"].append({**row, "model": model_id})
        elif model["vehicleClass"] != "LDV" or model["propulsion"] != "BEV":
            buckets["OUTSIDE_SCOPE"].append({**row, "model": model_id})
        else:
            buckets["INCLUDED"].append({**row, "model": model_id})
            by_model.setdefault(model_id, []).append(row)
    composition = []
    for model_id, rows in by_model.items():
        rows = sorted(rows, key=lambda r: (-r["value"], r["key"]))
        m = models[model_id]
        composition.append({"modelId": model_id, "name": f"{m['manufacturer']} {m['modelName']} {m['variant']}".strip(),
                            "recordKey": rows[0]["key"], "vehicles": sum(r["value"] for r in rows),
                            "sources": [{"key": r["key"], "vehicles": r["value"]} for r in rows]})
    composition.sort(key=lambda c: (-c["vehicles"], c["recordKey"]))
    if len(composition) > MAX_ROWS:
        raise StockError(f"O recorte tem {len(composition)} modelos; o contrato aceita {MAX_ROWS} por snapshot. "
                         "Segmentar exige evolução aditiva do backend e não é feito por este script.")
    totals = {name: {"rows": len(buckets[name]), "vehicles": sum(r["value"] for r in buckets[name])} for name in BUCKETS}
    if sum(t["rows"] for t in totals.values()) != len(stock) or \
            sum(t["vehicles"] for t in totals.values()) != sum(r["value"] for r in stock):
        raise StockError("Reconciliação falhou: linhas ou veículos não fecham com a entrada.")
    return buckets, composition, totals


def run(stock_path, catalog, organization, year, out):
    stock = read_stock(stock_path, year)
    buckets, composition, totals = reconcile(stock, catalog, organization, year)
    out.mkdir(parents=True, exist_ok=True)
    csv_path = out / "stock-ingestion-001.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(COLUMNS)
        for c in composition:
            writer.writerow([c["recordKey"], year, "stock", c["vehicles"], "vehicles", "BR"])
    gaps = []
    if totals["NO_ALIAS"]["rows"]:
        gaps.append(f"{totals['NO_ALIAS']['rows']} chave(s) sem alias no catálogo: candidatos ainda sem decisão aplicada.")
    if totals["PENDING_CLASSIFICATION"]["rows"]:
        gaps.append(f"{totals['PENDING_CLASSIFICATION']['rows']} chave(s) de modelos ainda sem classificação.")
    if not composition:
        gaps.append("Nenhum modelo LDV BEV no catálogo com estoque: não há linha a ingerir (ausência não equivale a zero).")
    report = {
        "organizationId": organization, "observationYear": year,
        "input": {"file": Path(stock_path).name, "sha256": sha256(stock_path), "rows": len(stock),
                  "vehicles": sum(r["value"] for r in stock)},
        "output": {"file": csv_path.name, "sha256": sha256(csv_path), "rows": len(composition),
                   "vehicles": sum(c["vehicles"] for c in composition)},
        "buckets": totals, "composition": composition,
        "outsideScopeKeys": [r["key"] for r in buckets["OUTSIDE_SCOPE"]],
        "pendingClassificationKeys": [r["key"] for r in buckets["PENDING_CLASSIFICATION"]],
        "noAliasKeys": [r["key"] for r in buckets["NO_ALIAS"]],
        "complete": False, "nationalDenominatorClaimed": False, "coverage": "UNKNOWN", "usableForE8": False,
        "gaps": gaps,
        "notes": ["Veículo registrado não prova circulação, LDV nem DCFC; vale como candidato até o parecer independente.",
                  "O recorte é o catálogo atual: não é denominador nacional, e a cobertura só se afirma por decisão independente.",
                  "Siga o caminho existente: fonte e comprovante, quarentena, parecer APPROVED_FOR_SCOPE, prévia e snapshot; "
                  "nada aqui cria snapshot, peso ou admissão."]}
    (out / "stock-reconciliation.json").write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stock", type=Path, required=True, help="stock-candidates-001.csv de build_fleet_universe.py")
    parser.add_argument("--catalog", type=Path, required=True, help="JSON de GET /v1/platform/fleet/board")
    parser.add_argument("--organization", required=True)
    parser.add_argument("--year", type=int, required=True)
    parser.add_argument("--output-dir", type=Path, required=True, help="diretório local FORA do versionamento")
    args = parser.parse_args()
    try:
        report = run(args.stock, json.loads(args.catalog.read_text(encoding="utf-8")), args.organization, args.year, args.output_dir)
    except (StockError, KeyError, json.JSONDecodeError, OSError) as error:
        print(f"ERRO: {error}", file=sys.stderr)
        return 2
    print(json.dumps({k: report[k] for k in ("input", "output", "buckets", "gaps", "coverage", "nationalDenominatorClaimed")},
                     ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
