# CF-DATA-010 — Fatores fósseis brasileiros no catálogo (etapa 5 do roteiro #124)

**Nível D17:** jurisdição (Brasil) e projeto (consumo). Nada é admitido: toda entrada nasce `NOT_ADMITTED`, sem revisão. Os dados estão em `docs/data/fuel-catalog-br-seed.json`, gerado por `scripts/build_fuel_catalog_seed.py` (teste: `scripts/test_fuel_catalog_seed.py`) e registrados no sandbox por `scripts/register_fuel_catalog.py`.

## O que foi lido (SHA-256 no JSON)
| Documento | Estado | Uso |
|---|---|---|
| ANP, fatores de conversão 2025/2026 (fonte EPE/BEN) | PRIMARY_READ | densidades (t/m³ = kg/L) de gasolina A, C e etanol anidro |
| AEA/MDIC, NT intensidade de carbono (reproduz EPE NT/DPG/SDB/2025/03 e RenovaCalc) | **SECONDARY** | fatores de uso (g/kg) CO₂ fóssil, CH₄, N₂O da gasolina A; CO₂ biogênico do etanol; PCI |
| Resolução CNPE 9/2026 | PRIMARY_READ | E32, vigência 01/08/2026, 180 dias, prorrogável uma vez |

## Valores registrados
- Gasolina A: CO₂ fóssil 2.981,1 g/kg; CH₄ 1,089 g/kg; N₂O 0,348 g/kg; PCI 43,54 MJ/kg; densidade 0,742 kg/L.
- Etanol anidro: CO₂ fóssil 0; CO₂ biogênico 1.929,2 g/kg (fora de `CO2_ONLY`); densidade 0,791 kg/L.
- Gasolina C: composição 32% etanol anidro / 68% gasolina A (2026-08-01 a 2027-01-27); densidade 0,754 kg/L (ANP, teor de 2025).
- **Único valor derivado:** CO₂ fóssil da gasolina C = 0,68 × 0,742 × 2.981,1 = **1.504,143816 gCO₂/L** (mistura ideal por volume), com a conta no localizador e aritmética exata. O catálogo não deriva misturas sozinho, por escolha.

## Mudança de código
`t/m3` é aceito como unidade de densidade (equivale a kg/L; é como a ANP publica). Sem outra mudança de regra.

## Lacunas (não suprir por suposição)
1. **Comparação com IPCC 2019 não feita**: o Refinement 2019 não pôde ser obtido (arquivo baixado inválido); `ipcc*` ficam vazios (QA/QC `NOT_COMPARED`). O IPCC 2006 (Tabela 1.4: 69.300 kg CO₂/TJ, PCI 44,3 TJ/Gg) foi lido, mas é outra edição e não foi registrado como referência 2019.
2. Fonte do fator de uso é secundária (AEA reproduzindo a EPE); a NT EPE e o Inventário MCTI/MMA de rodoviário não foram obtidos. O mesmo vale para o diesel B, não registrado.
3. Prorrogação da Res. CNPE 9/2026 não lida; a vigência registrada é a mínima.
4. Tolerância de ±1 p.p. no teor (art. 3º) e CH₄/N₂O da gasolina C: não aplicados; a fronteira `CO2_CH4_N2O` segue não resolvida (teste cobre).
5. Revisão (`OWNER_INTERNAL`) e a independente são atos humanos pendentes; sem elas o pin do projeto não resolve.
