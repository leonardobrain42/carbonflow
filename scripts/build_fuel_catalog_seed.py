#!/usr/bin/env python3
"""Builds docs/data/fuel-catalog-br-seed.json (etapa 5 do roteiro #124): Brazilian fossil-fuel data for the fuel catalog.

Every number comes from a document READ for the cited table (hashes below); the page is the PDF page. The ONLY derived values are the
CO2 fóssil of gasolina C per litre (E30 and E32) and the energy-basis gasolina A factor used for the IPCC comparison, computed here with exact Decimal arithmetic from three published inputs and registered
as its own reviewable entry (the catalog does not derive blends automatically on purpose):
    EF_gasolinaC [gCO2/L] = fracao_volumetrica_gasolinaA x densidade_gasolinaA [kg/L] x EF_gasolinaA [gCO2/kg]
The ethanol share carries no FOSSIL CO2 (Tabela A 2: 0,0); its biogenic CO2 is registered but stays outside CO2_ONLY.
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
    "EPE": {"title": "EPE, Nota Técnica EPE/DPG/SDB/2025/03: intensidade de carbono das fontes de energia do transporte rodoviário (tabelas A 2 e 2 reproduzem ANP 2018/2024, EPE 2025a e RenovaCalc)",
            "url": "https://www.epe.gov.br/sites-pt/publicacoes-dados-abertos/publicacoes/PublicacoesArquivos/publicacao-708/topico-770/NT-EPE-DPG-SDB-2025-03_Intensidade_de_Carbono_Transporte_Rodovi%C3%A1rio.pdf",
            "sha256": "e51133b4dd5b288923f2f1a6f6784718d6646ea7fa89aa7648182ebb2bc9455d", "readState": "PRIMARY_READ",
            "license": {"notice": "CC BY 4.0 (Atribuição 4.0 Internacional)", "scope": "aviso do portal da EPE, rodapé da página da própria publicação; condições específicas do PDF não verificadas",
                        "evidence": "captura de tela de 26/11/2026 fornecida pelo responsável", "evidenceSha256": "2c849e781ec7de3bcd1550aefe232d6189bb05322ce78800c5a8b217e2f683d0"}},
    "ANP": {"title": "ANP, Fatores de conversão, densidades e poderes caloríficos inferiores, valores médios de 2025 (fonte: EPE/Balanço Energético Nacional)",
            "url": "https://www.gov.br/anp/pt-br/centrais-de-conteudo/publicacoes/anuario-estatistico/arquivos-anuario-estatistico-2026/outras-pecas-documentais/fatores-de-conversao-2026.pdf",
            "sha256": "cf7c9a5ba2b10ab703628150f93c8504713ed4b69678da970524c1ce19594854", "readState": "PRIMARY_READ"},
    "CNPE25": {"title": "Resolução CNPE nº 9, de 25/06/2025 (DOU 02/07/2025, Seção 1, p. 11): E30 na gasolina comum desde 01/08/2025",
               "url": "https://www.gov.br/mme/pt-br/assuntos/conselhos-e-comites/cnpe/resolucoes-do-cnpe/2025/ResoluoCNPE9publicada.pdf",
               "sha256": "acf21a0cc599458e202af8383d11f880a0ef230f6c1be73f59f3262d9e77d654", "readState": "PRIMARY_READ"},
    "CNPE": {"title": "Resolução CNPE nº 9, de 14/07/2026 (DOU 30/07/2026, Seção 1 Extra B, p. 12)",
             "url": "https://www.gov.br/mme/pt-br/assuntos/conselhos-e-comites/cnpe/resolucoes-do-cnpe/2026/resolucao-9-dou.pdf",
             "sha256": "56c8440c3e95f9e6aa0766c55c5fc7acd41e724491d32c5a2906ae8e2be741fd", "readState": "PRIMARY_READ"},
}
# Published inputs (strings exactly as printed; Brazilian decimal comma written with a point).
EF_GASOLINE_A = "2981.1"      # g CO2 fóssil por kg, EPE NT 2025/03, Tabela A 2
EF_GASOLINE_A_CH4 = "1.089"   # g CH4 fóssil por kg, idem
EF_GASOLINE_A_N2O = "0.348"   # g N2O por kg, idem
EF_ETHANOL_BIO = "1929.2"     # g CO2 biogênico por kg de etanol anidro, idem
LHV_GASOLINE_A = "43.54"      # MJ/kg, EPE NT 2025/03, Tabela 2 (10.400 kcal/kg)
DENSITY_GASOLINE_A = "0.742"  # t/m3 = kg/L, ANP
DENSITY_ETHANOL_AN = "0.791"  # t/m3, ANP
IPCC_GASOLINE = Decimal("69300")   # kg CO2/TJ, IPCC 2006 Vol.2 Tab. 3.2.1 (Motor Gasoline; inferior 67500, superior 73000)
IPCC_NCV = Decimal("44.3")         # TJ/Gg = MJ/kg, IPCC 2006 Vol.2 Tab. 1.2/1.4 (Motor Gasoline)
IPCC_G_PER_KG = IPCC_GASOLINE * IPCC_NCV / 1000   # kg/TJ x MJ/kg / 1000 = g CO2 per kg of fuel
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
    primary = entry("GASOLINE_A", "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", EF_GASOLINE_A, "gCO2/kg", 2025, "2025-01-01", None, rule_epe, "EPE",
                    "Apêndice, Tabela A 2 (p. 38 do PDF; fatores de uso, RenovaCalc), linha Gasolina A, coluna CO2 Fóssil, g/kg.")
    primary.update({"ipccReference": "IPCC 2006 Vol.2 Cap.3 Tab.3.2.1 (pág. 3.16): 69300 kgCO2/TJ (faixa 67500-73000) x PCI 44,3 TJ/Gg; cap. 3 sem refinamento em 2019", "ipccValue": format(IPCC_G_PER_KG, "f"), "ipccUnit": "gCO2/kg"})
    e.append(primary)
    e.append(entry("GASOLINE_A", "EMISSION_FACTOR", "CH4", "TTW", EF_GASOLINE_A_CH4, "gCH4/kg", 2025, "2025-01-01", None, rule_epe, "EPE",
                   "Apêndice, Tabela A 2 (p. 38 do PDF), linha Gasolina A, coluna CH4 Fóssil, g/kg."))
    e.append(entry("GASOLINE_A", "EMISSION_FACTOR", "N2O", "TTW", EF_GASOLINE_A_N2O, "gN2O/kg", 2025, "2025-01-01", None, rule_epe, "EPE",
                   "Apêndice, Tabela A 2 (p. 38 do PDF), linha Gasolina A, coluna N2O, g/kg."))
    e.append(entry("GASOLINE_A", "LHV", None, None, LHV_GASOLINE_A, "MJ/kg", 2025, "2025-01-01", None, "Média de 2025 (EPE/BEN)", "EPE",
                   "Tabela 2 (p. 19 do PDF), linha Gasolina A, coluna PCI (MJ/kg); 10.400 kcal/kg."))
    e.append(entry("GASOLINE_A", "DENSITY", None, None, DENSITY_GASOLINE_A, "t/m3", 2025, "2025-01-01", None, "Média de 2025 (EPE/BEN), a 20 °C e 1 atm", "ANP",
                   "Tabela única, linha Gasolina A, coluna Densidade (t/m3); 1 t/m3 = 1 kg/L."))
    e.append(entry("ETHANOL_ANHYDROUS", "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", "0", "gCO2/kg", 2025, "2025-01-01", None, rule_epe, "EPE",
                   "Apêndice, Tabela A 2 (p. 38 do PDF), linha Etanol Anidro, coluna CO2 Fóssil = 0,0 g/kg."))
    e.append(entry("ETHANOL_ANHYDROUS", "EMISSION_FACTOR", "CO2_BIOGENIC", "TTW", EF_ETHANOL_BIO, "gCO2/kg", 2025, "2025-01-01", None, rule_epe, "EPE",
                   "Apêndice, Tabela A 2 (p. 38 do PDF), linha Etanol Anidro, coluna CO2 biogênico, g/kg. Registrado, mas fora da fronteira CO2_ONLY."))
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
                       f"(EPE 2025, EPE Tab. A 2) = {value_c} gCO2/L; etanol: CO2 fóssil 0; mistura ideal por volume")
    assert len(derived_locator) <= 300
    e.append(entry("GASOLINE_C", "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", format(value_c, "f"), "gCO2/L", 2026, VALID_FROM, VALID_TO, comp_rule, "EPE", derived_locator))
    e.append(entry("GASOLINE_C", "DENSITY", None, None, "0.754", "t/m3", 2025, "2025-01-01", None, "Média de 2025 (EPE/BEN), a 20 °C e 1 atm", "ANP",
                   "Tabela única, linha Gasolina C, coluna Densidade (t/m3), valor publicado com o teor vigente em 2025."))
    e30_rule = "Desde 01/08/2025 (Res. CNPE 9/2025, art. 3º); suspenso de 01/08/2026 pela Res. CNPE 9/2026 durante a vigência do E32"
    e30 = {"kind": "COMPOSITION", "fuelId": "GASOLINE_C", "componentId": "ETHANOL_ANHYDROUS", "compositionBasis": "VOLUME_FRACTION",
           "nominal": "0.30", "remainder": False, "validFrom": "2025-08-01", "validTo": "2026-07-31", "validityRule": e30_rule,
           "evidenceSource": DOCS["CNPE25"]["title"], "evidenceLocator": "Art. 1º, I: 30% de etanol anidro na Gasolina Comum; art. 3º: vigência em 01/08/2025",
           "readState": "PRIMARY_READ", "evidenceSha256": DOCS["CNPE25"]["sha256"], "retrievedOn": RETRIEVED, "evidenceUrl": DOCS["CNPE25"]["url"]}
    e.append(e30)
    e.append({k: v for k, v in dict(e30, componentId="GASOLINE_A", nominal=None, remainder=True, evidenceLocator="Resto da mistura (70%): gasolina A, por diferença do art. 1º, I").items() if v is not None or k == "validTo"})
    value_30 = (Decimal(1) - Decimal("0.30")) * Decimal(DENSITY_GASOLINE_A) * Decimal(EF_GASOLINE_A)
    e.append(entry("GASOLINE_C", "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", format(value_30, "f"), "gCO2/L", 2025, "2025-08-01", "2026-07-31", e30_rule, "EPE",
                   f"DERIVADO: 0.70 (1-0,30, CNPE 9/2025 art. 1) x {DENSITY_GASOLINE_A} kg/L (ANP) x {EF_GASOLINE_A} gCO2 fóssil/kg (EPE Tab. A 2) = {value_30} gCO2/L; etanol: CO2 fóssil 0; mistura ideal por volume"))
    return {"schema": "cf.fuel-catalog-seed/1", "generatedBy": "scripts/build_fuel_catalog_seed.py", "retrievedOn": RETRIEVED,
            "notice": "Nada aqui está revisado nem admitido; o valor de GASOLINE_C é derivado (ver locator). Revisão e admissão são atos humanos à parte.",
            "documents": DOCS, "entries": e}


if __name__ == "__main__":
    text = json.dumps(build(), ensure_ascii=False, indent=1) + "\n"
    if "--check" in sys.argv:
        sys.exit(0 if OUT.exists() and OUT.read_text(encoding="utf-8") == text else 1)
    OUT.write_text(text, encoding="utf-8")
    print(f"{OUT} gerado")
