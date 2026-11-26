#!/usr/bin/env python3
"""Gera docs/CF-DATA-010-ROTEIRO-REVISAO.md a partir de docs/data/fuel-catalog-br-seed.json (sem transcrição manual).

O roteiro orienta a revisão interna do responsável (OWNER_INTERNAL): o que abrir, onde conferir e o que decidir em cada registro.
Não simula, não decide e não registra revisão. Uso: build_fuel_review_checklist.py [--check]
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "docs" / "data" / "fuel-catalog-br-seed.json"
OUT = ROOT / "docs" / "CF-DATA-010-ROTEIRO-REVISAO.md"
SHORT = {"EPE": "EPE NT 2025/03", "ANP": "ANP fatores 2025", "CNPE25": "CNPE 9/2025", "CNPE": "CNPE 9/2026", "CNPE8": "CNPE 8/2025"}


def doc_key(entry, documents):
    for key, d in documents.items():
        if d["title"] == entry["evidenceSource"]:
            return key
    raise KeyError(entry["evidenceSource"])


def describe(e):
    if e["kind"] == "COMPOSITION":
        what = f"composição {e['componentId']} " + ("(resto da mistura)" if e.get("remainder") else f"{float(e['nominal']) * 100:g}% em volume")
        return what, ""
    gas = f" {e['gas']}" if e.get("gas") else ""
    return f"{e['parameter']}{gas} = **{e['value']} {e['unit']}**", e.get("emissionScope") or ""


def todo(e):
    if e["evidenceLocator"].startswith("DERIVADO"):
        return "Refazer a conta do localizador com os valores já revisados (não há número publicado para comparar). Conferir a vigência."
    if e["kind"] == "COMPOSITION":
        return "Conferir o artigo, o percentual e a data de vigência no ato; o resto da mistura é por diferença."
    parts = ["Achar a linha e a coluna do localizador e conferir o número e a unidade"]
    if "ipccValue" in e:
        parts.append(f"conferir a referência IPCC ({e['ipccValue']} {e['ipccUnit']}) e o desvio mostrado na tela")
    if e.get("gas") == "CO2_BIOGENIC":
        parts.append("confirmar que fica fora de `CO2_ONLY`")
    return "; ".join(parts) + "."


def build():
    seed = json.loads(SEED.read_text(encoding="utf-8"))
    docs = seed["documents"]
    lines = ["# CF-DATA-010 — Roteiro de revisão interna do catálogo (OWNER_INTERNAL)", "",
             "> Gerado por `scripts/build_fuel_review_checklist.py` a partir de `docs/data/fuel-catalog-br-seed.json`. **Não registra nem simula revisão:** a decisão é sua. A revisão interna **não** é revisão independente e **não** admite nada.", "",
             "## Antes de começar",
             "1. Registre os dados no sandbox (só registra; não revisa): `python3 scripts/register_fuel_catalog.py`.",
             "2. Entre em `/workspace/platform/fuel-catalog` com a conta de responsável (`admin@carbonflow.test` no sandbox) e abra a sessão com MFA (`python3 scripts/admin_sandbox.py code`).",
             "3. Para cada linha abaixo: abra o documento na página indicada, confira, e registre **base `OWNER_INTERNAL`** com resultado `REVIEWED_OK`, `NEEDS_INFORMATION` ou `REJECTED` e um motivo de ao menos 10 caracteres (cite a página). Uma rejeição posterior em qualquer base torna obsoletos os pinos que usam a entrada.",
             "4. Revise os **dados de base antes dos derivados**; a ordem abaixo já segue isso. Um valor derivado só vale o que valem os insumos.", "",
             "## Documentos (SHA-256 completo no JSON)", "", "| Sigla | Documento | SHA-256 (início) | Estado |", "|---|---|---|---|"]
    for k, d in docs.items():
        lines.append(f"| {SHORT.get(k, k)} | {d['title']} | `{d['sha256'][:12]}…` | {d['readState']} |")
    lines += ["", "## Registros", "", "| # | Combustível | Registro | Vigência | Fonte e localizador | O que conferir |", "|---|---|---|---|---|---|"]
    entries = seed["entries"]
    base = [e for e in entries if not e["evidenceLocator"].startswith("DERIVADO")]
    derived = [e for e in entries if e["evidenceLocator"].startswith("DERIVADO")]
    for n, e in enumerate(base + derived, 1):
        what, _ = describe(e)
        window = f"{e['validFrom']} a {e.get('validTo') or 'sem fim'}"
        loc = e["evidenceLocator"].replace("|", "/")
        lines.append(f"| R{n:02d} | {e['fuelId']} | {what} | {window} | {SHORT[doc_key(e, docs)]}: {loc} | {todo(e)} |")
    lines += ["", "## O que a sua revisão significa e o que não significa",
              "- `REVIEWED_OK` interna permite que o catálogo **resolva** o fator em `CO2_ONLY` e que um pino de projeto o referencie. Tudo continua `NOT_ADMITTED` e `usableForE8=false`.",
              "- Não é revisão independente nem aprovação normativa. A base `INDEPENDENT` exige outra pessoa, que não tenha registrado o dado.",
              "- A comparação com o IPCC não é validação independente: a fonte nacional parte dos mesmos fatores ANP/RenovaCalc e o IPCC é a referência comum.",
              "- O fator de gasolina C e de diesel B são **derivados por mistura ideal em volume** (sem tolerância de ±1 p.p. do teor). Se você não aceita esse método, use `NEEDS_INFORMATION` e descreva.",
              "- A janela do E32 (até 27/01/2027) é a mínima, sem prorrogação lida. Nada aqui estende vigência.", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    text = build()
    if "--check" in sys.argv:
        sys.exit(0 if OUT.exists() and OUT.read_text(encoding="utf-8") == text else 1)
    OUT.write_text(text, encoding="utf-8")
    print(f"{OUT} gerado")
