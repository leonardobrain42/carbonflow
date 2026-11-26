#!/usr/bin/env python3
"""Contract and validator of the extensible Brazilian fuel catalog (D19 slice 5, Q12, #96).

Offline. Validates a catalog file; writes nothing to the application. The catalog holds fuel identities, blend
compositions and data points (properties and emission factors). It never supplies a number by itself: every data point
needs a source with a locator and a read state, and NOTHING here admits a factor (`admission` must be NOT_ADMITTED;
admission is a separate normative act with independent review).

Vocabulary aligned with the API (FuelCatalog.java, V111): gas in CO2_FOSSIL|CO2_BIOGENIC|CO2_UNSPECIFIED|CH4|N2O|CO2E_AGGREGATE,
scope TTW|WTW|UNSPECIFIED, gwpSet like AR5_100. The API is the owner of the persisted catalog; this is an offline pre-check.
Contract (JSON):
  fuels[]        {id, name, family, role: PRODUCT|BLEND, phase: LIQUID|GAS, pending[]: what evidence is still missing}
  compositions[] {blendId, componentId, basis: VOLUME_FRACTION|MASS_FRACTION|ENERGY_FRACTION, nominal | remainder:true,
                  validFrom, validTo|null, validityRule?, evidence, state}
  data[]         {fuelId, parameter EMISSION_FACTOR|DENSITY|LHV, gas?, emissionScope?, gwpSet?, value, unit, vintageYear, validFrom, validTo|null,
                  evidence, review:{basis: NONE|OWNER_INTERNAL|INDEPENDENT, registeredBy?, reviewedBy?}, admission}
  evidence       {source, locator, readState: PRIMARY_READ|SECONDARY|PRIMARY_NOT_READ, sha256?, retrievedOn?}
Rules: gas is required for emission parameters; CO2E needs an explicit gwpSet (never implicit); the unit must belong to
the parameter's dimension; values are non-negative decimal strings; a value needs PRIMARY_READ or SECONDARY evidence
(PRIMARY_NOT_READ carries no value); windows of the same series must not overlap; blend components of one window must
sum to exactly 1 (a single `remainder` component completes it); INDEPENDENT review needs a reviewer different from the
registrant and OWNER_INTERNAL never stands for INDEPENDENT.
"""
import argparse
import json
import re
import sys
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

FAMILIES = {"GASOLINE", "ETHANOL", "DIESEL", "BIODIESEL", "NATURAL_GAS"}
BASES = {"VOLUME_FRACTION", "MASS_FRACTION", "ENERGY_FRACTION"}
READ = {"PRIMARY_READ", "SECONDARY", "PRIMARY_NOT_READ"}
REVIEW = {"NONE", "OWNER_INTERNAL", "INDEPENDENT"}
GASES = {"CO2_FOSSIL", "CO2_BIOGENIC", "CO2_UNSPECIFIED", "CH4", "N2O", "CO2E_AGGREGATE"}
SCOPES = {"TTW", "WTW", "UNSPECIFIED"}
GWP = re.compile(r"^AR[4-9]_[0-9]{2,3}$")
EMISSION = {"EMISSION_FACTOR"}
UNITS = {  # parameter -> accepted units; a unit never converts between dimensions
    "EMISSION_FACTOR": {"tCO2e/MJ", "gCO2e/MJ", "kgCO2e/L", "tCO2e/L", "tCO2e/USgal", "kgCO2e/kg", "kgCO2e/m3",
                        "kgCO2/L", "gCO2/MJ", "kgCO2/kg", "kg/TJ", "kg/L", "t/TJ", "g/MJ"},
    "DENSITY": {"kg/L", "kg/m3"},
    "LHV": {"MJ/kg", "MJ/L", "MJ/m3", "kcal/kg", "kcal/m3"},
}


def decimal(text):
    try:
        value = Decimal(text) if isinstance(text, str) else None
    except InvalidOperation:
        return None
    return value if value is not None and value.is_finite() else None


def day(text):
    try:
        return date.fromisoformat(text)
    except (TypeError, ValueError):
        return None


def check_evidence(ev, where, errors, carries_value):
    if not isinstance(ev, dict):
        errors.append(f"{where}: evidência ausente"); return None
    if not (ev.get("source") or "").strip() or not (ev.get("locator") or "").strip():
        errors.append(f"{where}: evidência exige fonte e localizador")
    state = ev.get("readState")
    if state not in READ:
        errors.append(f"{where}: readState inválido")
    elif state == "PRIMARY_NOT_READ" and carries_value:
        errors.append(f"{where}: fonte primária não lida não sustenta valor")
    return state


def window(entry, where, errors):
    start, end = day(entry.get("validFrom")), (day(entry["validTo"]) if entry.get("validTo") else None)
    if start is None:
        errors.append(f"{where}: validFrom ausente ou inválido")
    elif entry.get("validTo") and (end is None or end < start):
        errors.append(f"{where}: validTo inválido ou anterior a validFrom")
    return start, end


def overlap(a, b):
    (s1, e1), (s2, e2) = a, b
    return (e1 is None or s2 <= e1) and (e2 is None or s1 <= e2)


def validate(catalog):
    errors = []
    fuels = {f.get("id"): f for f in catalog.get("fuels", [])}
    if len(fuels) != len(catalog.get("fuels", [])):
        errors.append("fuels: identificadores repetidos")
    for fid, f in fuels.items():
        if f.get("family") not in FAMILIES or f.get("role") not in {"PRODUCT", "BLEND"} or f.get("phase") not in {"LIQUID", "GAS"}:
            errors.append(f"fuel {fid}: família, papel ou fase inválidos")
    groups = {}
    for i, c in enumerate(catalog.get("compositions", [])):
        where = f"composition[{i}]"
        if fuels.get(c.get("blendId"), {}).get("role") != "BLEND" or c.get("componentId") not in fuels:
            errors.append(f"{where}: mistura inexistente (ou não é BLEND) ou componente inexistente")
        if c.get("basis") not in BASES:
            errors.append(f"{where}: base inválida")
        nominal = decimal(c.get("nominal")) if not c.get("remainder") else None
        if not c.get("remainder") and (nominal is None or not 0 <= nominal <= 1):
            errors.append(f"{where}: nominal deve ser decimal em [0,1] (ou remainder)")
        check_evidence(c.get("evidence"), where, errors, True)
        w = window(c, where, errors)
        if w[0]:
            groups.setdefault((c.get("blendId"), c.get("basis"), c.get("validFrom"), c.get("validTo")), []).append((nominal, bool(c.get("remainder"))))
    for key, parts in groups.items():
        remainders = [p for p in parts if p[1]]
        fixed = sum((p[0] for p in parts if not p[1] and p[0] is not None), Decimal(0))
        if len(remainders) > 1:
            errors.append(f"composição {key[0]}: mais de um remainder")
        elif remainders and fixed > 1:
            errors.append(f"composição {key[0]}: soma acima de 1")
        elif not remainders and fixed != 1:
            errors.append(f"composição {key[0]}: a soma dos componentes é {fixed}, não 1")
    series = {}
    for i, d in enumerate(catalog.get("data", [])):
        where = f"data[{i}]"
        param = d.get("parameter")
        if d.get("fuelId") not in fuels:
            errors.append(f"{where}: combustível inexistente")
        if param not in UNITS:
            errors.append(f"{where}: parâmetro desconhecido"); continue
        if d.get("unit") not in UNITS[param]:
            errors.append(f"{where}: unidade {d.get('unit')} não pertence à dimensão de {param}")
        value = decimal(d.get("value"))
        if value is None or value < 0:
            errors.append(f"{where}: valor deve ser decimal não negativo em texto")
        if param in EMISSION:
            if d.get("gas") not in GASES:
                errors.append(f"{where}: gás obrigatório para fator de emissão")
            if d.get("emissionScope") not in SCOPES:
                errors.append(f"{where}: escopo (TTW, WTW ou UNSPECIFIED) obrigatório para fator de emissão")
            if d.get("gas") == "CO2E_AGGREGATE" and not GWP.match(str(d.get("gwpSet") or "")):
                errors.append(f"{where}: CO2E exige gwpSet explícito")
            if d.get("gas") != "CO2E_AGGREGATE" and d.get("gwpSet"):
                errors.append(f"{where}: gwpSet só vale para CO2E")
        elif d.get("gas"):
            errors.append(f"{where}: gás só vale para fator de emissão")
        if not isinstance(d.get("vintageYear"), int) or not 1990 <= d["vintageYear"] <= 2100:
            errors.append(f"{where}: ano de vintage inválido")
        check_evidence(d.get("evidence"), where, errors, True)
        w = window(d, where, errors)
        if d.get("admission") != "NOT_ADMITTED":
            errors.append(f"{where}: o catálogo não admite fatores; admission deve ser NOT_ADMITTED")
        review = d.get("review") or {}
        basis = review.get("basis")
        if basis not in REVIEW:
            errors.append(f"{where}: base da revisão inválida")
        elif basis == "INDEPENDENT" and (not review.get("reviewedBy") or review.get("reviewedBy") == review.get("registeredBy")):
            errors.append(f"{where}: revisão independente exige revisor diferente de quem registrou")
        elif basis != "NONE" and not review.get("reviewedBy"):
            errors.append(f"{where}: revisão sem revisor")
        if w[0] and value is not None:
            series.setdefault((d.get("fuelId"), param, d.get("gas"), d.get("emissionScope"), d.get("gwpSet")), []).append((w, value, where))
    for key, items in series.items():
        for a in range(len(items)):
            for b in range(a + 1, len(items)):
                if overlap(items[a][0], items[b][0]):
                    errors.append(f"{items[a][2]} e {items[b][2]}: janelas de validade se sobrepõem na série {key}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("catalog", type=Path)
    args = parser.parse_args()
    errors = validate(json.loads(args.catalog.read_text(encoding="utf-8")))
    for error in errors:
        print("ERRO:", error)
    print("catálogo válido" if not errors else f"{len(errors)} erros")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
