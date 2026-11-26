#!/usr/bin/env python3
"""Builds docs/data/fuel-catalog-br-seed.json (etapa 5 do roteiro #124): Brazilian fossil-fuel data for the fuel catalog.

Every number comes from a document READ in full for the cited table (hashes below). The ONLY derived value is the
CO2 fóssil of gasolina C per litre, computed here with exact Decimal arithmetic from three published inputs and registered
as its own reviewable entry (the catalog does not derive blends automatically on purpose):
    EF_gasolinaC [gCO2/L] = fracao_volumetrica_gasolinaA x densidade_gasolinaA [kg/L] x EF_gasolinaA [gCO2/kg]
The ethanol share carries no FOSSIL CO2 (Tabela A1: 0,0); its biogenic CO2 is registered but stays outside CO2_ONLY.
Nothing here is reviewed or admitted; review is a separate human act.
Uso: build_fuel_catalog_seed.py [--check]   (--check falha se o arquivo versionado difere do gerado)
"""
import json
import sys
from decimal import Decimal
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "docs" / "data" / "fuel-catalog-br-seed.json"
RETRIEVED = "2026-11-26"
DOCS = {
    "AEA": {"title": "AEA, Nota Técnica: intensidade de carbono dos energéticos para veículos pesados (relatório MDIC, v15.12.25); reproduz EPE NT/DPG/SDB/2025/03 e a RenovaCalc",
            "url": "https://www.gov.br/mdic/pt-br/assuntos/sdic/setor-automotivo/Relatorio_Intensidade_de_carbono_AEA_2025_v15.12.25.pdf",
            "sha256": "1ce894f7f6566bd445ee10ff55e005bf5133e1b167e0df61781881c096f0e462", "readState": "SECONDARY"},
    "ANP": {"title": "ANP, Fatores de conversão, densidades e poderes caloríficos inferiores, valores médios de 2025 (fonte: EPE/Balanço Energético Nacional)",
            "url": "https://www.gov.br/anp/pt-br/centrais-de-conteudo/publicacoes/anuario-estatistico/arquivos-anuario-estatistico-2026/outras-pecas-documentais/fatores-de-conversao-2026.pdf",
            "sha256": "cf7c9a5ba2b10ab703628150f93c8504713ed4b69678da970524c1ce19594854", "readState": "PRIMARY_READ"},
    "CNPE": {"title": "Resolução CNPE nº 9, de 14/07/2026 (DOU 30/07/2026, Seção 1 Extra B, p. 12)",
             "url": "https://www.gov.br/mme/pt-br/assuntos/conselhos-e-comites/cnpe/resolucoes-do-cnpe/2026/resolucao-9-dou.pdf",
             "sha256": "56c8440c3e95f9e6aa0766c55c5fc7acd41e724491d32c5a2906ae8e2be741fd", "readState": "PRIMARY_READ"},
}
# Published inputs (strings exactly as printed; Brazilian decimal comma written with a point).
EF_GASOLINE_A = "2981.1"      # g CO2 fóssil por kg, AEA Apêndice Tabela A1 (EPE 2025)
EF_GASOLINE_A_CH4 = "1.089"   # g CH4 fóssil por kg, idem
EF_GASOLINE_A_N2O = "0.348"   # g N2O por kg, idem
EF_ETHANOL_BIO = "1929.2"     # g CO2 biogênico por kg de etanol anidro, idem
LHV_GASOLINE_A = "43.54"      # MJ/kg, AEA Tabela 1 (EPE/ANP: 10.400 kcal/kg)
DENSITY_GASOLINE_A = "0.742"  # t/m3 = kg/L, ANP
DENSITY_ETHANOL_AN = "0.791"  # t/m3, ANP
ETHANOL_SHARE = Decimal("0.32")  # Resolução CNPE 9/2026, art. 1º
VALID_FROM = "2026-08-01"        # art. 4º
VALID_TO = "2027-01-27"          # 180 dias a partir de 01/08/2026, inclusive; prorrogável uma vez (não lida)


def entry(fuel, parameter, gas, scope, value, unit, vintage, valid_from, valid_to, rule, doc, locator, kind="DATUM"):
    d = DOCS[doc]
    out = {"kind": kind, "fuelId": fuel, "parameter": parameter, "gas": gas, "emissionScope": scope, "value": value, "unit": unit,
           "vintageYear": vintage, "validFrom": valid_from, "validTo": valid_to, "validityRule": rule,
           "evidenceSource": d["title"], "evidenceLocator": locator, "readState": d["readState"],
           "evidenceSha256": d["sha256"], "retrievedOn": RETRIEVED, "evidenceUrl": d["url"]}
    return {k: v for k, v in out.items() if v is not None or k == "validTo"}


def derived_gasoline_c():
    fraction_a = Decimal(1) - ETHANOL_SHARE
    value = fraction_a * Decimal(DENSITY_GASOLINE_A) * Decimal(EF_GASOLINE_A)
    return value, fraction_a


def build():
    value_c, fraction_a = derived_gasoline_c()
    e = []
    rule_epe = "Edição EPE 2025 (RenovaCalc); confirmar a edição seguinte a cada ano"
    e.append(entry("GASOLINE_A", "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", EF_GASOLINE_A, "gCO2/kg", 2025, "2025-01-01", None, rule_epe, "AEA",
                   "Apêndice, Tabela A1 (fatores de emissão no uso, RenovaCalc), linha Gasolina A, coluna CO2 Fóssil, g/kg. A nota técnica reproduz a EPE; o texto da EPE (NT/DPG/SDB/2025/03) não foi lido."))
    e.append(entry("GASOLINE_A", "EMISSION_FACTOR", "CH4", "TTW", EF_GASOLINE_A_CH4, "gCH4/kg", 2025, "2025-01-01", None, rule_epe, "AEA",
                   "Apêndice, Tabela A1, linha Gasolina A, coluna CH4 Fóssil, g/kg."))
    e.append(entry("GASOLINE_A", "EMISSION_FACTOR", "N2O", "TTW", EF_GASOLINE_A_N2O, "gN2O/kg", 2025, "2025-01-01", None, rule_epe, "AEA",
                   "Apêndice, Tabela A1, linha Gasolina A, coluna N2O, g/kg."))
    e.append(entry("GASOLINE_A", "LHV", None, None, LHV_GASOLINE_A, "MJ/kg", 2025, "2025-01-01", None, "Média de 2025 (EPE/BEN)", "AEA",
                   "Tabela 1, linha Gasolina A, coluna PCI (MJ/kg); a ANP publica 10.400 kcal/kg."))
    e.append(entry("GASOLINE_A", "DENSITY", None, None, DENSITY_GASOLINE_A, "t/m3", 2025, "2025-01-01", None, "Média de 2025 (EPE/BEN), a 20 °C e 1 atm", "ANP",
                   "Tabela única, linha Gasolina A, coluna Densidade (t/m3); 1 t/m3 = 1 kg/L."))
    e.append(entry("ETHANOL_ANHYDROUS", "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", "0", "gCO2/kg", 2025, "2025-01-01", None, rule_epe, "AEA",
                   "Apêndice, Tabela A1, linha Etanol Anidro, coluna CO2 Fóssil = 0,0 g/kg."))
    e.append(entry("ETHANOL_ANHYDROUS", "EMISSION_FACTOR", "CO2_BIOGENIC", "TTW", EF_ETHANOL_BIO, "gCO2/kg", 2025, "2025-01-01", None, rule_epe, "AEA",
                   "Apêndice, Tabela A1, linha Etanol Anidro, coluna CO2 biogênico, g/kg. Registrado, mas fora da fronteira CO2_ONLY."))
    e.append(entry("ETHANOL_ANHYDROUS", "DENSITY", None, None, DENSITY_ETHANOL_AN, "t/m3", 2025, "2025-01-01", None, "Média de 2025 (EPE/BEN), a 20 °C e 1 atm", "ANP",
                   "Tabela única, linha Etanol anidro, coluna Densidade (t/m3)."))
    comp_rule = "180 dias a partir de 01/08/2026 (art. 1º, parágrafo único e art. 4º), prorrogável uma única vez por ato do Presidente do CNPE (prorrogação não lida); tolerância de 1 ponto percentual é metrológica (art. 3º)"
    comp = {"kind": "COMPOSITION", "fuelId": "GASOLINE_C", "componentId": "ETHANOL_ANHYDROUS", "compositionBasis": "VOLUME_FRACTION",
            "nominal": "0.32", "remainder": False, "validFrom": VALID_FROM, "validTo": VALID_TO, "validityRule": comp_rule,
            "evidenceSource": DOCS["CNPE"]["title"], "evidenceLocator": "Art. 1º: teor obrigatório de 32% de etanol anidro na Gasolina Comum, em caráter excepcional e temporário",
            "readState": "PRIMARY_READ", "evidenceSha256": DOCS["CNPE"]["sha256"], "retrievedOn": RETRIEVED, "evidenceUrl": DOCS["CNPE"]["url"]}
    e.append(comp)
    rest = dict(comp, componentId="GASOLINE_A", nominal=None, remainder=True,
                evidenceLocator="Resto da mistura (68%): gasolina A, por diferença do art. 1º")
    rest = {k: v for k, v in rest.items() if v is not None or k == "validTo"}
    e.append(rest)
    derived_locator = (f"DERIVADO: {fraction_a} (1-0,32, CNPE 9/2026 art. 1) x {DENSITY_GASOLINE_A} kg/L (ANP) x {EF_GASOLINE_A} gCO2 fóssil/kg "
                       f"(EPE 2025, AEA Tab. A1) = {value_c} gCO2/L; etanol: CO2 fóssil 0; mistura ideal por volume")
    assert len(derived_locator) <= 300
    e.append(entry("GASOLINE_C", "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", format(value_c, "f"), "gCO2/L", 2026, VALID_FROM, VALID_TO, comp_rule, "AEA", derived_locator))
    e.append(entry("GASOLINE_C", "DENSITY", None, None, "0.754", "t/m3", 2025, "2025-01-01", None, "Média de 2025 (EPE/BEN), a 20 °C e 1 atm", "ANP",
                   "Tabela única, linha Gasolina C, coluna Densidade (t/m3), valor publicado com o teor vigente em 2025."))
    return {"schema": "cf.fuel-catalog-seed/1", "generatedBy": "scripts/build_fuel_catalog_seed.py", "retrievedOn": RETRIEVED,
            "notice": "Nada aqui está revisado nem admitido; o valor de GASOLINE_C é derivado (ver locator). Revisão e admissão são atos humanos à parte.",
            "documents": DOCS, "entries": e}


if __name__ == "__main__":
    text = json.dumps(build(), ensure_ascii=False, indent=1) + "\n"
    if "--check" in sys.argv:
        sys.exit(0 if OUT.exists() and OUT.read_text(encoding="utf-8") == text else 1)
    OUT.write_text(text, encoding="utf-8")
    print(f"{OUT} gerado")
