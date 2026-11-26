#!/usr/bin/env python3
"""Valida docs/data/vt0011-tool07-conformance.json.

Regras: nenhuma linha pode afirmar conformidade/admissão; toda linha cita documento, trecho e evidência; GAP/CANNOT_DETERMINE/
NOT_EVALUATED/PARTIAL descrevem a lacuna; CANNOT_DETERMINE e GAP dizem o que resolver e o que bloqueia.
Uso: vt0011_tool07_conformance.py ARQUIVO.json
"""
import json
import re
import sys

STATUSES = {"IMPLEMENTED", "PARTIAL", "GAP", "NOT_EVALUATED", "CANNOT_DETERMINE"}
FORBIDDEN = re.compile(r"\b(CONFORM[EA]?|ADMITIDO|ADMITTED_OK|APROVADO)\b", re.I)


def validate(doc):
    problems = []
    if doc.get("schema") != "cf.vt0011-tool07-conformance/1":
        problems.append("schema inesperado")
    if set(doc.get("statuses", [])) != STATUSES:
        problems.append("vocabulário de status diferente do canônico")
    sources = doc.get("sources", {})
    for key, source in sources.items():
        if not re.fullmatch(r"[0-9a-f]{64}", source.get("sha256", "")):
            problems.append(f"fonte {key}: sha256 inválido")
    seen = set()
    for row in doc.get("requirements", []):
        rid = row.get("id", "?")
        if rid in seen:
            problems.append(f"{rid}: duplicado")
        seen.add(rid)
        if row.get("platformState") not in STATUSES:
            problems.append(f"{rid}: status inválido")
        if row.get("source", {}).get("document") not in sources or not row.get("source", {}).get("locator"):
            problems.append(f"{rid}: fonte ou trecho ausente")
        if not row.get("requirement") or not row.get("evidence"):
            problems.append(f"{rid}: requisito ou evidência ausente")
        if FORBIDDEN.search(row.get("requirement", "") + row.get("evidence", "") + row.get("gap", "")):
            problems.append(f"{rid}: afirma conformidade/admissão")
        if row.get("platformState") != "IMPLEMENTED" and not row.get("gap"):
            problems.append(f"{rid}: falta a lacuna")
        if row.get("platformState") in {"GAP", "CANNOT_DETERMINE"} and not (row.get("toResolve") and row.get("blocks")):
            problems.append(f"{rid}: falta toResolve ou blocks")
    return problems


if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as handle:
        found = validate(json.load(handle))
    for problem in found:
        print("ERRO:", problem)
    print("matriz VT0011/TOOL07: " + ("inválida" if found else "ok"))
    sys.exit(1 if found else 0)
