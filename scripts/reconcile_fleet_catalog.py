#!/usr/bin/env python3
"""Evidence dossiers and a classification plan for the canonical model catalog (D19 slice 3, #94/#95).

Offline. Writes nothing to the application and sends nothing. It joins, per ACCEPTED alias candidate:
  - the Senatran literal name (alias key, observation period, registered vehicles of the sample),
  - the PBEV line the reviewer chose (file SHA-256, line, category, propulsion AS PRINTED),
  - a manufacturer reconciliation between the Senatran brand token and the PBEV brand.
and reports how much of the sample's registered vehicles sits in each status.

What it does NOT do: decide the vehicle class. The PBEV prints brand, model, version, category and propulsion; it does
not say LDV, circulation or DCFC compatibility. Class and propulsion enter the plan only from a decisions file filled in
by people, with their own reason and, for a known class, the evidence of the CLASS. Contradictions with what the PBEV
prints, unreconciled manufacturers, missing evidence and unaccepted candidates are refused with the reason.

--decisions: {"decisions": [{"candidateId": "...", "vehicleClass": "LDV|HDV|OTHER|UNKNOWN",
                             "propulsion": "BEV|PHEV|OTHER|UNKNOWN", "reason": "...", "classEvidence": "..."}]}
--manufacturers (optional, written by people): {"BRAND TOKEN AS IN SENATRAN": "BRAND AS IN PBEV"}
"""
import argparse
import json
import re
import sys
import unicodedata
import uuid
from pathlib import Path

CLASSES = {"LDV", "HDV", "OTHER", "UNKNOWN"}
PROPULSIONS = {"BEV", "PHEV", "OTHER", "UNKNOWN"}
PRINTED_ELECTRIC = {"ELETRICO", "ELÉTRICO"}
NAMESPACE = uuid.UUID("5d0c7a1e-3f0a-4b6e-9a55-1c2e4d6f8a22")
LIMITS = ["Dossiê de evidência, não admissão: nada aqui cria modelo, alias ou classificação.",
          "A participação sobre os veículos registrados vale só para os nomes da amostra e não é cobertura nacional.",
          "Veículo registrado não prova circulação, LDV nem compatibilidade DCFC."]


def compact(text):
    folded = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^A-Z0-9]", "", folded.upper())


def brand_token(literal):
    """First word after the import marker 'I/'. Only a token to compare, never a decision."""
    core = re.sub(r"^\s*I/", "", literal.strip(), flags=re.I)
    return re.split(r"[\s/]+", core, maxsplit=1)[0] if core else ""


def reconcile_manufacturer(token, pbev_brand, mapping):
    if compact(token) and compact(token) == compact(pbev_brand):
        return {"state": "EXACT", "senatranToken": token, "pbevBrand": pbev_brand}
    mapped = {compact(k): v for k, v in (mapping or {}).items()}.get(compact(token))
    if mapped is not None and compact(mapped) == compact(pbev_brand):
        return {"state": "MAPPED_BY_PEOPLE", "senatranToken": token, "pbevBrand": pbev_brand}
    return {"state": "UNRECONCILED", "senatranToken": token, "pbevBrand": pbev_brand}


def evidence_text(d):
    parts = [f"PBEV {d['pbev']['fileSha256'][:12]} linha {d['pbev']['line']}: categoria '{d['pbev']['category']}', "
             f"propulsão impressa '{d['pbev']['propulsionPrinted']}'",
             f"Senatran {d['period']}: nome literal '{d['literalName']}' (chave {d['aliasKey']})",
             f"fabricante {d['manufacturer']['state']}"]
    return "; ".join(parts)


def dossiers(candidates, pbev, stock, mapping):
    lines = {row["line"]: row for row in pbev["rows"]}
    counts = {name: info.get("registered_count") for name, info in (stock or {}).get("selected_literal_models", {}).items()}
    out, refused = [], []
    for cand in sorted(candidates, key=lambda c: (c["observationPeriod"], c["literalName"])):
        if cand.get("state") != "ACCEPTED":
            continue
        why = None
        if cand.get("pbevSourceSha256") != pbev["source_sha256"]:
            why = "o candidato foi gerado de outro arquivo do PBEV; reimporte com o arquivo atual"
        row = lines.get(cand.get("selectedPbevLine"))
        if why is None and row is None:
            why = "a linha escolhida não existe no PBEV informado"
        if why:
            refused.append({"candidateId": cand["id"], "literalName": cand["literalName"], "reason": why})
            continue
        manufacturer = reconcile_manufacturer(brand_token(cand["literalName"]), row["brand"], mapping)
        out.append({"candidateId": cand["id"], "decisionBasis": cand.get("decisionBasis"),
                    "literalName": cand["literalName"], "aliasKey": cand["recordKey"].strip().upper(),
                    "period": cand["observationPeriod"], "registeredVehicles": counts.get(cand["literalName"]),
                    "pbev": {"fileSha256": pbev["source_sha256"], "line": row["line"], "category": row["category"],
                             "brand": row["brand"], "model": row["model"], "version": row["version"],
                             "propulsionPrinted": row["propulsion"]},
                    "manufacturer": manufacturer,
                    "suggestion": {"propulsion": "BEV" if row["propulsion"].upper() in PRINTED_ELECTRIC else None,
                                   "vehicleClass": None,
                                   "note": "O PBEV imprime a propulsão, mas não a classe; a classe fica com quem decide."}})
    return out, refused


def summary(candidates, stock, dossier_list):
    by_name = {c["literalName"]: c for c in candidates}
    rows = {"ACCEPTED": [], "PENDING": [], "REJECTED": [], "NO_CANDIDATE": []}
    for name, info in (stock or {}).get("selected_literal_models", {}).items():
        state = by_name[name]["state"] if name in by_name else "NO_CANDIDATE"
        rows[state].append((name, info.get("registered_count") or 0))
    total = sum(n for group in rows.values() for _, n in group)
    result = {"sampleNames": sum(len(g) for g in rows.values()), "sampleRegisteredVehicles": total, "byStatus": {}}
    for state, group in rows.items():
        n = sum(count for _, count in group)
        result["byStatus"][state] = {"names": len(group), "registeredVehicles": n,
                                     "shareOfSample": round(n / total, 4) if total else None}
    result["manufacturerStates"] = {s: sum(1 for d in dossier_list if d["manufacturer"]["state"] == s)
                                    for s in ("EXACT", "MAPPED_BY_PEOPLE", "UNRECONCILED")}
    return result


def classification_plan(dossier_list, decisions, org=None):
    by_id = {d["candidateId"]: d for d in dossier_list}
    plan, refused, seen = [], [], {}
    for decision in decisions.get("decisions", []):
        dossier = by_id.get(decision.get("candidateId"))
        name = dossier["literalName"] if dossier else decision.get("candidateId")
        def refuse(reason):
            refused.append({"candidateId": decision.get("candidateId"), "literalName": name, "reason": reason})
        if dossier is None:
            refuse("candidato sem dossiê (não aceito, de outro arquivo do PBEV ou inexistente)"); continue
        cls, prop = decision.get("vehicleClass"), decision.get("propulsion")
        if cls not in CLASSES or prop not in PROPULSIONS:
            refuse("classe ou propulsão fora dos valores aceitos (nada é presumido)"); continue
        if (cls == "UNKNOWN") != (prop == "UNKNOWN"):
            refuse("classe e propulsão desconhecidas só valem juntas"); continue
        reason = (decision.get("reason") or "").strip()
        if not 10 <= len(reason) <= 1000:
            refuse("o motivo precisa ter entre 10 e 1000 caracteres"); continue
        unknown = cls == "UNKNOWN"
        if not unknown:
            if dossier["manufacturer"]["state"] == "UNRECONCILED":
                refuse("fabricante não conciliado entre a Senatran e o PBEV; informe o mapeamento em --manufacturers"); continue
            if dossier["pbev"]["propulsionPrinted"].upper() in PRINTED_ELECTRIC and prop != "BEV":
                refuse("a propulsão declarada contradiz o que o PBEV imprime (elétrico)"); continue
            if len((decision.get("classEvidence") or "").strip()) < 3:
                refuse("classe conhecida exige a evidência da classe (o PBEV não diz LDV)"); continue
        identity = (dossier["pbev"]["brand"], dossier["pbev"]["model"], dossier["pbev"]["version"])
        if identity in seen and seen[identity]["declared"] != (cls, prop):
            refuse("o mesmo modelo recebeu classificações diferentes em outro candidato"); continue
        if identity in seen:
            seen[identity]["item"]["supportingAliases"].append({"literalName": dossier["literalName"], "aliasKey": dossier["aliasKey"],
                                                                "period": dossier["period"], "candidateId": dossier["candidateId"]})
            continue
        evidence = None
        if not unknown:
            evidence = evidence_text(dossier) + "; classe: " + decision["classEvidence"].strip()
            if len(evidence) > 1000:
                refuse("a evidência composta passa de 1000 caracteres; resuma a evidência da classe"); continue
        item = {"candidateId": dossier["candidateId"], "decisionBasis": dossier["decisionBasis"],
                "model": {"manufacturer": identity[0], "modelName": identity[1], "variant": identity[2]},
                "idempotencyKey": "CLS-" + uuid.uuid5(NAMESPACE, "|".join(identity) + f"|{cls}|{prop}|{org}").hex,
                "body": {"vehicleClass": cls, "propulsion": prop, "reason": reason, "evidence": evidence},
                "supportingAliases": [], "note": "postar em /models/{modelId}/classification depois de o modelo existir"}
        seen[identity] = {"declared": (cls, prop), "item": item}
        plan.append(item)
    return plan, refused


def run(candidates, pbev, stock=None, mapping=None, decisions=None, org=None):
    dossier_list, dossier_refused = dossiers(candidates, pbev, stock, mapping)
    plan, plan_refused = classification_plan(dossier_list, decisions or {}, org)
    return {"dossiers": dossier_list, "classificationPlan": plan, "refused": dossier_refused + plan_refused,
            "summary": summary(candidates, stock, dossier_list), "limitations": LIMITS, "usableForE8": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--pbev-json", type=Path, required=True)
    parser.add_argument("--senatran-report", type=Path)
    parser.add_argument("--manufacturers", type=Path)
    parser.add_argument("--decisions", type=Path)
    parser.add_argument("--organization-id")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    load = lambda p: json.loads(p.read_text(encoding="utf-8")) if p else None
    result = run(load(args.candidates), load(args.pbev_json), load(args.senatran_report), load(args.manufacturers),
                 load(args.decisions), args.organization_id)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(result['dossiers'])} dossiês, {len(result['classificationPlan'])} classificações planejadas, "
          f"{len(result['refused'])} recusados; saída em {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
