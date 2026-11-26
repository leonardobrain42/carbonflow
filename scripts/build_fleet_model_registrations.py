#!/usr/bin/env python3
"""Turns ACCEPTED alias candidates into request bodies for the existing catalog routes (CF-OPEN-005, #94/#95).

This script decides nothing. Class and propulsion come only from a decisions file filled in by people: a candidate
without a decision is refused, never defaulted (no LDV, no BEV). Manufacturer, model and variant come from the PBEV
line the reviewer chose. Nothing is sent: the output is a plan to be posted by a platform fleet manager.

Input 1 (--candidates): the JSON of GET /v1/platform/fleet/alias-candidates?state=ACCEPTED.
Input 2 (--decisions): {"organizationId": "<uuid>", "decisions": [{"candidateId": "...", "vehicleClass": "LDV|HDV|UNKNOWN",
  "propulsion": "BEV|PHEV|OTHER|UNKNOWN"}]}
A decisão de aceitar traz a base (`decisionBasis`: INDEPENDENT ou OWNER_INTERNAL); o plano a repassa sem convertê-la.
Output: {"registrations": [...POST /models bodies with an Idempotency-Key and the first alias...],
         "additionalAliases": [...aliases of an already planned model, to be posted after it exists...],
         "refused": [{"candidateId", "literalName", "reason"}]}
"""
import argparse
import json
import re
import sys
import uuid
from pathlib import Path

CLASSES = {"LDV", "HDV", "UNKNOWN"}
PROPULSIONS = {"BEV", "PHEV", "OTHER", "UNKNOWN"}
KEY = re.compile(r"[A-Z0-9][A-Z0-9._-]{0,99}")
NAMESPACE = uuid.UUID("3b1c1a2e-6a0e-4c0b-9d57-0f2a4b6f6a11")


def plan(candidates, decisions):
    org = decisions.get("organizationId")
    try:
        uuid.UUID(org)
    except (TypeError, ValueError):
        raise ValueError("organizationId inválido no arquivo de decisões")
    by_id = {d.get("candidateId"): d for d in decisions.get("decisions", [])}
    registrations, extra, refused = [], [], []
    models = {}  # identity -> (class, propulsion, planned registration)
    aliases = {}  # (year, key) -> candidate id
    for cand in sorted(candidates, key=lambda c: (c["observationPeriod"], c["literalName"])):
        def refuse(reason):
            refused.append({"candidateId": cand["id"], "literalName": cand["literalName"], "reason": reason})
        if cand.get("state") != "ACCEPTED":
            refuse("candidato não está aceito"); continue
        decision = by_id.get(cand["id"])
        if decision is None:
            refuse("sem decisão de classe e propulsão no arquivo"); continue
        if decision.get("vehicleClass") not in CLASSES or decision.get("propulsion") not in PROPULSIONS:
            refuse("classe ou propulsão fora dos valores aceitos (nada é presumido)"); continue
        pick = next((m for m in cand["matches"] if m["pbevLine"] == cand.get("selectedPbevLine")), None)
        if pick is None:
            refuse("a linha do PBEV escolhida não está entre as candidatas"); continue
        if not (pick.get("brand") or "").strip() or not (pick.get("model") or "").strip() \
                or not (pick.get("version") or "").strip():
            refuse("a linha do PBEV não tem fabricante, modelo e versão impressos; o cadastro exige os três"); continue
        key = cand["recordKey"].strip().upper()
        if not KEY.fullmatch(key):
            refuse("chave do alias inválida após normalização"); continue
        year = int(cand["observationPeriod"][:4])
        if (year, key) in aliases:
            refuse(f"alias {key}/{year} já planejado para outro candidato ({aliases[(year, key)]})"); continue
        identity = (pick["brand"].strip(), pick["model"].strip(), pick["version"].strip())
        declared = (decision["vehicleClass"], decision["propulsion"])
        if identity in models and models[identity][0] != declared:
            refuse("o mesmo modelo recebeu classe/propulsão diferentes em outro candidato"); continue
        aliases[(year, key)] = cand["id"]
        alias = {"organizationId": org, "year": year, "sourceRecordKey": key}
        if identity not in models:
            body = {"organizationId": org, "manufacturer": identity[0], "modelName": identity[1], "variant": identity[2],
                    "vehicleClass": declared[0], "propulsion": declared[1], "aliasYear": year, "sourceRecordKey": key}
            item = {"candidateId": cand["id"], "decisionBasis": cand.get("decisionBasis"),
                    "idempotencyKey": "REG-" + uuid.uuid5(NAMESPACE, "|".join(identity) + org).hex, "body": body}
            models[identity] = (declared, item)
            registrations.append(item)
        else:
            item = {"candidateId": cand["id"], "model": {"manufacturer": identity[0], "modelName": identity[1],
                    "variant": identity[2]}, "idempotencyKey": "ALI-" + uuid.uuid5(NAMESPACE, f"{identity}|{year}|{key}|{org}").hex,
                    "body": alias, "note": "postar em /models/{modelId}/aliases depois de o modelo existir"}
            extra.append(item)
    return {"registrations": registrations, "additionalAliases": extra, "refused": refused}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--decisions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = plan(json.loads(args.candidates.read_text(encoding="utf-8")),
                  json.loads(args.decisions.read_text(encoding="utf-8")))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(result['registrations'])} cadastros, {len(result['additionalAliases'])} aliases adicionais, "
          f"{len(result['refused'])} recusados; saída em {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
