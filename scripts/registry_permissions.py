#!/usr/bin/env python3
"""Valida a matriz de permissões por operação dos registros (docs/data/registry-permissions.json).

Regra: ALLOWED/CONDITIONAL/PROHIBITED só valem com texto primário lido (readState PRIMARY_READ), URL, data de leitura
e fundamento; registro NOT_READ não pode trazer permissões; o que não se sabe é UNDETERMINED e bloqueia só aquela operação.
Uso: registry_permissions.py ARQUIVO.json [--blocked REGISTRO OPERACAO]
"""
import json
import sys

OPERATIONS = ["CONSULT", "STORE", "EXTRACT_MANUAL", "EXTRACT_AUTOMATED", "PROCESS", "EXPORT", "REDISTRIBUTE", "COMMERCIAL_USE"]
STATUSES = {"ALLOWED", "CONDITIONAL", "PROHIBITED", "UNDETERMINED"}


def validate(doc):
    problems = []
    if doc.get("schema") != "cf.registry-permissions/1":
        problems.append("schema inesperado")
    if doc.get("operations") != OPERATIONS:
        problems.append("lista de operações diferente da canônica")
    seen = set()
    for reg in doc.get("registries", []):
        rid = reg.get("id", "?")
        if rid in seen:
            problems.append(f"{rid}: duplicado")
        seen.add(rid)
        state = reg.get("readState")
        if state not in {"PRIMARY_READ", "NOT_READ"}:
            problems.append(f"{rid}: readState inválido")
            continue
        for key in ("name", "termsUrl", "retrievedOn", "termsDate"):
            if not reg.get(key):
                problems.append(f"{rid}: falta {key}")
        perms = reg.get("permissions", {})
        if state == "NOT_READ":
            if perms:
                problems.append(f"{rid}: registro não lido não pode ter permissões")
            if not reg.get("unreadReason"):
                problems.append(f"{rid}: falta unreadReason")
            continue
        if set(perms) != set(OPERATIONS):
            problems.append(f"{rid}: as permissões devem cobrir exatamente as operações canônicas")
        for op, p in perms.items():
            if p.get("status") not in STATUSES:
                problems.append(f"{rid}.{op}: status inválido")
            if not p.get("basis"):
                problems.append(f"{rid}.{op}: falta fundamento")
            if p.get("status") == "CONDITIONAL" and not p.get("conditions"):
                problems.append(f"{rid}.{op}: CONDITIONAL sem condições")
    return problems


def status(doc, registry, operation):
    """Estado de uma operação; registro ausente ou não lido é UNDETERMINED (nunca permitido por omissão)."""
    for reg in doc.get("registries", []):
        if reg["id"] == registry:
            return reg.get("permissions", {}).get(operation, {}).get("status", "UNDETERMINED")
    return "UNDETERMINED"


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    doc = json.load(open(argv[1], encoding="utf-8"))
    problems = validate(doc)
    for p in problems:
        print("ERRO:", p)
    if problems:
        return 1
    if len(argv) == 5 and argv[2] == "--blocked":
        s = status(doc, argv[3], argv[4])
        print(s)
        return 0 if s in ("ALLOWED", "CONDITIONAL") else 3
    print(f"OK: {len(doc['registries'])} registros, {sum(1 for r in doc['registries'] if r['readState']=='PRIMARY_READ')} com termos lidos")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
