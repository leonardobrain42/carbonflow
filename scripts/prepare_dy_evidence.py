#!/usr/bin/env python3
"""Preparation of competing-credit (Dy) searches and evidence for the existing assessment API (D19 slice 6, Q05, #96).

Offline. Writes nothing to the application and consults no registry: the consultations and issuances are supplied by
PEOPLE who read the registries under their own terms. It reuses the shapes of the existing API (searchCoverage, issuances,
cumulativeIssuances) and does NOT compute Dy (the engine owns that; D14 reads the ANNUAL window, the cumulative one stays
informational until the external confirmation, Q05).
  --universe   decision of the programme universe, written by people: {decisionRef, decidedBy, decidedOn, programs:[{program}]}
               There is no default universe: without it nothing is prepared. The tool cannot prove the universe is exhaustive.
  --evidence   {consultations:[{program, creditKind ERC|ERF, window ANNUAL|CUMULATIVE, territory, result, searchedFrom,
               searchedUntilExclusive, searchedOn, sourceId, sourceEdition, locator, limitations, captureSha256?, filters?}],
               issuances:[{program, registryProject, issuanceId, creditKind, issuedOn, geography, creditsTco2e, sourceId,
               sourceEdition, locator, captureSha256?}]}
  --credit-start, --annual-from, --annual-until-exclusive  (dates, required): annual window [from, until);
               prior items lie in [credit-start, annual-from).
  --declared-totals (optional) {"annual":{"erc","erf"},"cumulative":{"erc","erf"}}: only DECLARED totals reach the payload;
               itemized sums are reported and compared, never substituted.
"""
import argparse
import json
import re
import sys
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

KINDS = ("ERC", "ERF")
SHA = re.compile(r"^[0-9a-f]{64}$")
LIMITS = ["Preparação de evidência, não admissão: nada aqui calcula Dy nem habilita o cálculo.",
          "O universo de programas é decisão de pessoas e a ferramenta não prova que esteja completo.",
          "Resultado negativo vale só como não encontrado na consulta declarada.",
          "A matriz da API cobre a janela anual; a cobertura da janela acumulada é informativa (Q05 sem confirmação externa).",
          "Nenhum registro foi consultado por esta ferramenta e os termos de uso de cada registro seguem por confirmar."]


def day(text):
    try:
        return date.fromisoformat(text)
    except (TypeError, ValueError):
        return None


def amount(text):
    try:
        value = Decimal(text) if isinstance(text, str) else None
    except InvalidOperation:
        return None
    return value if value is not None and value.is_finite() else None


def fmt(value):
    return format(value.normalize(), "f") if value else "0"


def cell_state(c, window):
    if c is None:
        return "MISSING"
    if c.get("territory") != "NATIONAL_BR":
        return "NOT_NATIONAL"
    if c.get("result") == "INCOMPLETE":
        return "INCOMPLETE_RESULT"
    s, u, on = day(c.get("searchedFrom")), day(c.get("searchedUntilExclusive")), day(c.get("searchedOn"))
    if not (s and u and on) or u <= s or on < u:
        return "INCOHERENT_DATES"
    if (s, u) == window:
        return "COVERED_EXACT"
    return "COVERS_WIDER_THAN_WINDOW" if s <= window[0] and u >= window[1] else "PARTIAL_WINDOW"


def prepare(universe, evidence, credit_start, annual_from, annual_until, declared=None):
    windows = {"ANNUAL": (annual_from, annual_until), "CUMULATIVE": (credit_start, annual_from)}
    blockers, refused = [], []
    if not (credit_start < annual_from < annual_until):
        raise ValueError("as datas devem seguir credit-start < annual-from < annual-until-exclusive")
    programs = [p.get("program", "").strip() for p in universe.get("programs", [])]
    if not all(universe.get(k) for k in ("decisionRef", "decidedBy", "decidedOn")) or not programs or not all(programs):
        raise ValueError("o universo exige decisionRef, decidedBy, decidedOn e ao menos um programa")
    known = {p.lower(): p for p in programs}
    cells = {}
    for c in evidence.get("consultations", []):
        name, kind, window = known.get((c.get("program") or "").strip().lower()), c.get("creditKind"), c.get("window")
        label = f"{c.get('program')}/{kind}/{window}"
        if name is None:
            refused.append({"item": label, "reason": "OUTSIDE_UNIVERSE: programa fora do universo decidido"}); continue
        if kind not in KINDS or window not in windows:
            refused.append({"item": label, "reason": "categoria ou janela inválida (ERC/ERF, ANNUAL/CUMULATIVE)"}); continue
        if (name, kind, window) in cells:
            refused.append({"item": label, "reason": "consulta duplicada para o mesmo programa, categoria e janela"}); continue
        cells[(name, kind, window)] = c
    issued = {}
    items = {"ANNUAL": [], "PRIOR": []}
    seen = set()
    for i in evidence.get("issuances", []):
        name = known.get((i.get("program") or "").strip().lower())
        label = f"{i.get('program')}/{i.get('registryProject')}/{i.get('issuanceId')}"
        on, value = day(i.get("issuedOn")), amount(i.get("creditsTco2e"))
        key = (name, (i.get("registryProject") or "").lower(), (i.get("issuanceId") or "").lower())
        reason = ("OUTSIDE_UNIVERSE: programa fora do universo decidido" if name is None else
                  "categoria deve ser ERC ou ERF" if i.get("creditKind") not in KINDS else
                  "recorte deve ser BR" if i.get("geography") != "BR" else
                  "quantidade deve ser decimal positivo em texto" if value is None or value <= 0 else
                  "data de emissão inválida" if on is None else
                  "emissão duplicada" if key in seen else
                  "emissão fora de [início da creditação, fim da janela anual)" if not credit_start <= on < annual_until else
                  "locator ausente ou curto" if len((i.get("locator") or "").strip()) < 3 else
                  "fonte e edição são obrigatórias" if not (i.get("sourceId") and i.get("sourceEdition")) else None)
        if reason:
            refused.append({"item": label, "reason": reason}); continue
        seen.add(key)
        part = "ANNUAL" if on >= annual_from else "PRIOR"
        items[part].append(i)
        issued.setdefault((name, i["creditKind"], "ANNUAL" if part == "ANNUAL" else "CUMULATIVE"), []).append(i)
    matrix, capture_gaps = [], []
    for program in programs:
        for kind in KINDS:
            for window in windows:
                c = cells.get((program, kind, window))
                state = cell_state(c, windows[window])
                count = len(issued.get((program, kind, window), []))
                note = None
                if c and state not in ("MISSING", "INCOMPLETE_RESULT", "NOT_NATIONAL", "INCOHERENT_DATES"):
                    if c.get("result") == "FOUND" and count == 0:
                        state, note = "FOUND_WITHOUT_ITEMS", "resultado positivo exige emissão individualizada"
                    elif c.get("result") == "NO_RESULTS" and count:
                        state, note = "NO_RESULTS_WITH_ITEMS", "resultado negativo não pode conter emissão"
                if c and not SHA.match(str(c.get("captureSha256") or "")):
                    capture_gaps.append(f"{program}/{kind}/{window}")
                matrix.append({"program": program, "creditKind": kind, "window": window, "state": state,
                               "result": c.get("result") if c else None, "itemCount": count,
                               "captureSha256": c.get("captureSha256") if c and SHA.match(str(c.get("captureSha256") or "")) else None,
                               "note": note})
    annual_complete = all(m["state"] == "COVERED_EXACT" for m in matrix if m["window"] == "ANNUAL")
    cumulative_complete = all(m["state"] in ("COVERED_EXACT", "COVERS_WIDER_THAN_WINDOW") for m in matrix if m["window"] == "CUMULATIVE")
    for m in matrix:
        if m["window"] == "ANNUAL" and m["state"] != "COVERED_EXACT":
            blockers.append(f"janela anual: {m['program']}/{m['creditKind']} está {m['state']}" + (f" ({m['note']})" if m["note"] else ""))
    if refused:
        blockers.append(f"{len(refused)} itens recusados")
    sums = {}
    for part, rows in items.items():
        for kind in KINDS:
            sums[(part, kind)] = sum((Decimal(r["creditsTco2e"]) for r in rows if r["creditKind"] == kind), Decimal(0))
    recon = {"itemized": {"annual": {k.lower(): fmt(sums[("ANNUAL", k)]) for k in KINDS},
                          "prior": {k.lower(): fmt(sums[("PRIOR", k)]) for k in KINDS},
                          "cumulative": {k.lower(): fmt(sums[("ANNUAL", k)] + sums[("PRIOR", k)]) for k in KINDS}},
             "declared": declared, "checks": []}
    if declared:
        for scope, part in (("annual", ("ANNUAL",)), ("cumulative", ("ANNUAL", "PRIOR"))):
            for k in KINDS:
                want, have = amount((declared.get(scope) or {}).get(k.lower())), sum((sums[(p, k)] for p in part), Decimal(0))
                ok = want is not None and want == have
                recon["checks"].append({"scope": scope, "kind": k, "declared": fmt(want) if want is not None else None,
                                        "itemized": fmt(have), "match": ok})
                if not ok:
                    blockers.append(f"totais {scope} {k}: declarado {fmt(want) if want is not None else 'ausente'} difere dos itens {fmt(have)}")
    payload = None
    if not blockers and declared:
        def compose(c):
            extra = f"; captura sha256 {c['captureSha256']}" if SHA.match(str(c.get("captureSha256") or "")) else ""
            extra += f"; filtros: {c['filters']}" if c.get("filters") else ""
            return (c["locator"] + extra)[:1000] if len(c["locator"] + extra) <= 1000 else None
        coverage = []
        for (program, kind, window), c in sorted(cells.items()):
            if window != "ANNUAL":
                continue
            loc = compose(c)
            if loc is None:
                blockers.append(f"locator composto de {program}/{kind} passa de 1000 caracteres")
            coverage.append({k: c[k] for k in ("territory", "result", "searchedFrom", "searchedUntilExclusive", "searchedOn",
                                              "sourceId", "sourceEdition", "limitations")} | {"program": program, "creditKind": kind, "locator": loc})
        strip = lambda rows: [{k: r[k] for k in ("program", "registryProject", "issuanceId", "creditKind", "issuedOn", "geography",
                                                 "creditsTco2e", "sourceId", "sourceEdition", "locator")} for r in rows]
        if not blockers:
            payload = {"searchCoverage": coverage, "issuances": strip(items["ANNUAL"]), "cumulativeIssuances": strip(items["PRIOR"]),
                       "erc": declared["annual"]["erc"], "erf": declared["annual"]["erf"],
                       "cumulativeErc": declared["cumulative"]["erc"], "cumulativeErf": declared["cumulative"]["erf"]}
    return {"universe": {k: universe[k] for k in ("decisionRef", "decidedBy", "decidedOn")} | {"programs": programs,
                         "exhaustive": "NOT_PROVEN"},
            "windows": {"annual": [annual_from.isoformat(), annual_until.isoformat()],
                        "prior": [credit_start.isoformat(), annual_from.isoformat()]},
            "coverageMatrix": matrix, "annualCoverageComplete": annual_complete,
            "cumulativeCoverageInformational": {"complete": cumulative_complete, "carriedByApi": False},
            "captureGaps": sorted(set(capture_gaps)), "reconciliation": recon, "refused": refused,
            "blockers": blockers, "payload": payload, "policy": "D14: a janela anual é a vigente; o acumulado não entra no cálculo",
            "usableForE8": False, "limitations": LIMITS}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("universe", "evidence", "output"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--declared-totals", type=Path)
    for name in ("credit-start", "annual-from", "annual-until-exclusive"):
        p.add_argument("--" + name, required=True)
    a = p.parse_args()
    dates = [day(getattr(a, n)) for n in ("credit_start", "annual_from", "annual_until_exclusive")]
    if None in dates:
        print("datas inválidas (AAAA-MM-DD)", file=sys.stderr); return 2
    load = lambda f: json.loads(f.read_text(encoding="utf-8")) if f else None
    try:
        out = prepare(load(a.universe), load(a.evidence), *dates, declared=load(a.declared_totals))
    except ValueError as error:
        print(f"preparação interrompida: {error}", file=sys.stderr); return 2
    a.output.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"anual completo: {out['annualCoverageComplete']}; {len(out['blockers'])} bloqueios; payload: {'sim' if out['payload'] else 'não'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
