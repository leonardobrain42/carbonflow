#!/usr/bin/env python3
"""Rehearsal of the time-convention hypotheses of the MCTI hourly series (roteiro #124, PR 5; CF-DATA-008 R15).

The workbook does not say its time zone or hour convention, and neither TOOL07 nor VT0011 define them. This tool reads the
SAME workbook under several hypotheses (each = IANA zone + hour convention), keeps every one as a labelled hypothesis, and
measures how much the hourly factor at the SAME UTC instant changes between them. It never picks a winner and never marks a
hypothesis as correct: the output says so. Intervals that a hypothesis cannot place in UTC (non-existent or ambiguous local
time) keep no instant and are listed, never guessed. With --write-series DIR each hypothesis becomes an importer JSON that can
be loaded in "Séries da rede" (one separate edition per hypothesis; nothing is deleted).

Uso: grid_time_hypotheses.py --xlsx ARQ --unit tCO2/MWh [--hypothesis ZONA:CONVENCAO ...] [--window DE ATE] [--output R.json]
     [--write-series DIR]
"""
import argparse
import json
import sys
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import import_mcti_hourly as importer

DEFAULTS = ["America/Sao_Paulo:HOUR_ENDING_1_24", "America/Sao_Paulo:HOUR_START_LABEL_1_24",
            "UTC:HOUR_ENDING_1_24", "UTC:HOUR_START_LABEL_1_24"]
NOTICE = ("Hipóteses de ensaio: nenhuma é apontada como correta. A diferença medida mostra o quanto a escolha importa; "
          "a resposta do MCTI/ONS (CF-DATA-003) é o que fundamenta uma delas.")


def parse_instant(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)


def load(xlsx, unit, hypothesis):
    zone, convention = hypothesis.split(":", 1)
    return importer.importer(xlsx, timezone=zone, hour_convention=convention, unit=unit)


def values_by_instant(series, window):
    out = {}
    for record in series["records"]:
        if record["intervalStartUtc"] is None or record["timeIssue"]:
            continue
        start = parse_instant(record["intervalStartUtc"])
        if window and not (window[0] <= start < window[1]):
            continue
        out[start] = Decimal(record["value"])
    return out


def compare(first, second):
    common = sorted(set(first) & set(second))
    diffs = [abs(first[k] - second[k]) for k in common]
    if not diffs:
        return {"commonInstants": 0, "differing": 0, "maxAbsDifference": None, "meanAbsDifference": None}
    return {"commonInstants": len(diffs), "differing": sum(1 for d in diffs if d != 0),
            "maxAbsDifference": format(max(diffs), "f"), "meanAbsDifference": format(sum(diffs) / len(diffs), "f")}


def analyse(xlsx, unit, hypotheses, window=None):
    series = {h: load(xlsx, unit, h) for h in hypotheses}
    maps = {h: values_by_instant(s, window) for h, s in series.items()}
    result = {"notice": NOTICE, "window": [x.isoformat() for x in window] if window else None,
              "fileSha256": next(iter(series.values()))["original"]["fileSha256"], "hypotheses": [], "pairs": []}
    for h, s in series.items():
        instants = sorted(maps[h])
        result["hypotheses"].append({
            "hypothesis": h, "setByPeople": True, "records": s["quality"]["recordCount"],
            "timeIssues": s["quality"]["timeIssueCount"], "instantsInWindow": len(instants),
            "firstUtc": instants[0].isoformat() if instants else None, "lastUtc": instants[-1].isoformat() if instants else None})
    names = list(series)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            result["pairs"].append({"a": a, "b": b, **compare(maps[a], maps[b])})
    return result, series


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--xlsx", required=True)
    ap.add_argument("--unit", required=True)
    ap.add_argument("--hypothesis", action="append", help="ZONA:CONVENCAO (repita); padrão: 2 zonas x 2 convenções")
    ap.add_argument("--window", nargs=2, metavar=("DE", "ATE"), help="UTC ISO-8601, [DE, ATE)")
    ap.add_argument("--output")
    ap.add_argument("--write-series", metavar="DIR")
    a = ap.parse_args()
    window = (parse_instant(a.window[0]), parse_instant(a.window[1])) if a.window else None
    try:
        result, series = analyse(a.xlsx, a.unit, a.hypothesis or DEFAULTS, window)
    except importer.ImportErrorStop as error:
        print("ERRO:", error, file=sys.stderr)
        return 2
    for h in result["hypotheses"]:
        print(f"{h['hypothesis']}: {h['records']} registros, {h['timeIssues']} sem instante, {h['instantsInWindow']} na janela")
    for p in result["pairs"]:
        print(f"{p['a']} x {p['b']}: {p['differing']}/{p['commonInstants']} instantes diferem; máx {p['maxAbsDifference']}; média {p['meanAbsDifference']}")
    print(NOTICE)
    if a.output:
        Path(a.output).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    if a.write_series:
        Path(a.write_series).mkdir(parents=True, exist_ok=True)
        for h, s in series.items():
            Path(a.write_series, h.replace("/", "_").replace(":", "__") + ".json").write_text(json.dumps(s, ensure_ascii=False), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
