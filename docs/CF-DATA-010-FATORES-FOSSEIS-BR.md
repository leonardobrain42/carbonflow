# CF-DATA-010 — Fatores fósseis brasileiros no catálogo (etapa 5 do roteiro #124)

**Nível D17:** jurisdição (Brasil) e projeto (consumo). Nada é admitido: toda entrada nasce `NOT_ADMITTED`, sem revisão. Os dados estão em `docs/data/fuel-catalog-br-seed.json`, gerado por `scripts/build_fuel_catalog_seed.py` (teste: `scripts/test_fuel_catalog_seed.py`) e registrados no sandbox por `scripts/register_fuel_catalog.py`.

## O que foi lido (SHA-256 no JSON; `readState` = `PRIMARY_READ` em todas as entradas)
| Documento | Uso |
|---|---|
| EPE, NT/DPG/SDB/2025/03 (PDF p. 19, Tabela 2; p. 38, Tabela A 2) | fatores de uso (g/kg) CO₂ fóssil, CH₄, N₂O da gasolina A; CO₂ biogênico do etanol; PCI. **Substitui a AEA**, que só reproduzia esta nota |
| ANP, fatores de conversão 2025/2026 | densidades (t/m³ = kg/L) de gasolina A, C e etanol anidro |
| Resolução CNPE 9/2025 (DOU 02/07/2025, p. 11; lida como imagem) | **E30**, gasolina comum, vigência 01/08/2025 |
| Resolução CNPE 9/2026 (DOU 30/07/2026, p. 12; imagem) | **E32**, vigência 01/08/2026, 180 dias, prorrogável uma vez; suspende o art. 1º, I, da Res. 9/2025 durante a vigência |
| IPCC 2006 Vol. 2 Cap. 3, Tabela 3.2.1 (PDF p. 16, impressa 3.16) | referência: gasolina 69.300 kgCO₂/TJ (67.500–73.000) |
| IPCC 2019 Refinement, página do Vol. 2 | Cap. 3 consta como "(No Refinement)"; Cap. 1 (lido) tem seções "No refinement" |

## Valores registrados (15 entradas)
- Gasolina A: CO₂ fóssil 2.981,1 g/kg (**com comparação IPCC**), CH₄ 1,089, N₂O 0,348 g/kg; PCI 43,54 MJ/kg; densidade 0,742 kg/L.
- Etanol anidro: CO₂ fóssil 0; CO₂ biogênico 1.929,2 g/kg (fora de `CO2_ONLY`); densidade 0,791 kg/L.
- Gasolina C: **E30** 70/30 de 2025-08-01 a 2026-07-31 e **E32** 68/32 de 2026-08-01 a 2027-01-27 (janelas disjuntas); densidade 0,754 kg/L (ANP, 2025).
- Derivados (únicos, com a conta no localizador, aritmética exata): CO₂ fóssil da gasolina C = 0,70 × 0,742 × 2.981,1 = **1.548,38334 gCO₂/L** (E30) e 0,68 × 0,742 × 2.981,1 = **1.504,143816 gCO₂/L** (E32); mistura ideal por volume.

## QA/QC com o IPCC (executado)
O catálogo compara só na mesma unidade e recusa duas entradas na mesma série. A referência IPCC foi posta em gCO₂/kg: 69.300 kg/TJ × 44,3 TJ/Gg (PCI do IPCC) = **3.069,99 g/kg**; o valor brasileiro 2.981,1 g/kg dá **−2,9%** (`COMPARED_SAME_UNIT`). Em base energética (2.981,1 / 43,54) são 68.468 kgCO₂/TJ, **−1,2%** e dentro da faixa 67.500–73.000. A diferença vem do PCI brasileiro (43,54) ser menor que o do IPCC (44,3). **Não é validação independente:** a EPE parte de fatores ANP/RenovaCalc, e o IPCC é a referência comum.
IPCC 2019: o Cap. 3 (combustão móvel) não foi refinado, então a Tabela 3.2.1 de 2006 é a referência vigente. Registrada como "IPCC 2006, cap. 3 sem refinamento em 2019", sem atribuir a tabela a 2019. A evidência do "No refinement" do Cap. 3 é a página HTML do IPCC (SHA-256 `10f401a8…50e3`; confirmada por captura de tela, `beecaa48…43db`); o PDF do Cap. 3 de 2019 não existe.

## Inventário MMA/MT e relatório de referência da 3ª Comunicação, conferidos, não registrados
- Inventário MMA/MT (ano-base 2024), Tabela 4 (PDF p. 26): gasolina automotiva 69.300, etanol 70.800, diesel e biodiesel 74.100, GNV 56.100 kgCO₂/TJ; Tabela 6 (p. 27): gasolina 2,23 kgCO₂/L, conferido (69.300 × 0,04187 × 0,770 = 2,234). Diz seguir o Relatório de Referência (BRASIL, 2015).
- **Relatório de Referência, Setor Energia, Transporte Rodoviário (MCTI 2015; SHA-256 `80c32873…bdb8`)**, Tabela 14 (PDF p. 39): **Gasolina A 69.300**, diesel 74.070, etanol 70.770, GNV 56.100 kgCO₂/TJ, "Fonte: IPCC (2006)", constantes no tempo. Tabela II (p. 13): o CO₂ de biomassa (etanol, biodiesel) é informado, mas "não contabilizado no total do setor de Energia". Isso resolve a dúvida A/C: o 69.300 nacional é de **gasolina A** (fóssil) e o biogênico é separado por método. A leitura do rótulo "gasolina automotiva" do inventário como gasolina A é inferência a partir de ele declarar seguir esse relatório.
- **Por que não foi cadastrado:** o fator nacional é o padrão do IPCC (69.300), então não acrescenta valor independente; e o catálogo recusa uma segunda entrada na mesma série. Há ainda pequenas divergências entre os dois documentos (diesel 74.070 × 74.100; etanol 70.770 × 70.800). Serve de conferência: o 2.981,1 g/kg da EPE equivale a 68.468 kgCO₂/TJ, −1,2% do valor nacional/IPCC.
- NIR/MCTI 2024 (642 p.; SHA-256 `4108b131…9496`): buscado por B15, Resolução CNPE e fatores rodoviários, sem resultado útil. **Não lido por inteiro.**

## Mudança de código
`t/m3` é aceito como unidade de densidade (equivale a kg/L; é como a ANP publica). Sem outra mudança de regra.

## Lacunas que continuam
1. **Diesel B (B15) não registrado.** (Segunda pasta: o arquivo `RetifeResCNPE82025.pdf` é a mesma retificação, mesmo hash; o texto da Res. 8 continua ausente.) A Res. CNPE 6/2025 (lida) fixa B14 e diz que o art. 1º foi revogado pela Res. 8, de 25/06/2025; do texto da Res. 8 só foi lida a retificação da data. O B15 desde 01/08/2025 é afirmação não verificada em documento lido. Também faltam fatores de uso do diesel A no seed (a Tabela A 2 os traz: 3.098,8 g/kg etc.), então o diesel B aguarda a Res. 8.
2. **Licença da EPE:** CC BY 4.0 verificada **como aviso do portal** (captura de tela do rodapé da página da própria nota técnica, SHA-256 `2c849e78…83d0`, registrada em `documents.EPE.license`). O PDF não traz o aviso; condições específicas do documento não verificadas. A atribuição à EPE consta em `evidenceSource`.
3. **Prorrogação do E32:** nenhum ato lido; vigência registrada = 180 dias. Quando houver ato, cadastrar nova janela.
4. Tolerância de ±1 p.p. no teor (art. 3º) e CH₄/N₂O da gasolina C não aplicados; `CO2_CH4_N2O` segue não resolvida (teste cobre).
5. **Revisão humana `OWNER_INTERNAL`** pendente (ato seu, na tela do catálogo); não há revisão independente.
6. Os fatores da EPE citam ANP (2018) como origem do CO₂ fóssil; a edição de 2025 do ANP/RenovaCalc não foi lida.
