# CF-DATA-010 — Roteiro de revisão interna do catálogo (OWNER_INTERNAL)

> Gerado por `scripts/build_fuel_review_checklist.py` a partir de `docs/data/fuel-catalog-br-seed.json`. **Não registra nem simula revisão:** a decisão é sua. A revisão interna **não** é revisão independente e **não** admite nada.

## Antes de começar
1. Registre os dados no sandbox (só registra; não revisa): `python3 scripts/register_fuel_catalog.py`.
2. Entre em `/workspace/platform/fuel-catalog` com a conta de responsável (`admin@carbonflow.test` no sandbox) e abra a sessão com MFA (`python3 scripts/admin_sandbox.py code`).
3. Para cada linha abaixo: abra o documento na página indicada, confira, e registre **base `OWNER_INTERNAL`** com resultado `REVIEWED_OK`, `NEEDS_INFORMATION` ou `REJECTED` e um motivo de ao menos 10 caracteres (cite a página). Uma rejeição posterior em qualquer base torna obsoletos os pinos que usam a entrada.
4. Revise os **dados de base antes dos derivados**; a ordem abaixo já segue isso. Um valor derivado só vale o que valem os insumos.

## Documentos (SHA-256 completo no JSON)

| Sigla | Documento | SHA-256 (início) | Estado |
|---|---|---|---|
| EPE NT 2025/03 | EPE, Nota Técnica EPE/DPG/SDB/2025/03: intensidade de carbono das fontes de energia do transporte rodoviário (tabelas A 2 e 2 reproduzem ANP 2018/2024, EPE 2025a e RenovaCalc) | `e51133b4dd5b…` | PRIMARY_READ |
| ANP fatores 2025 | ANP, Fatores de conversão, densidades e poderes caloríficos inferiores, valores médios de 2025 (fonte: EPE/Balanço Energético Nacional) | `cf7c9a5ba2b1…` | PRIMARY_READ |
| CNPE 9/2025 | Resolução CNPE nº 9, de 25/06/2025 (DOU 02/07/2025, Seção 1, p. 11): E30 na gasolina comum desde 01/08/2025 | `acf21a0cc599…` | PRIMARY_READ |
| CNPE 8/2025 | Resolução CNPE nº 8, de 25/06/2025 (DOU 02/07/2025, Seção 1, nº 122, p. 10; epígrafe retificada no DOU de 03/07/2025, p. 3): B15 no diesel comercial | `afc2f82f451e…` | PRIMARY_READ |
| CNPE 9/2026 | Resolução CNPE nº 9, de 14/07/2026 (DOU 30/07/2026, Seção 1 Extra B, p. 12) | `56c8440c3e95…` | PRIMARY_READ |

## Registros

| # | Combustível | Registro | Vigência | Fonte e localizador | O que conferir |
|---|---|---|---|---|---|
| R01 | GASOLINE_A | EMISSION_FACTOR CO2_FOSSIL = **2981.1 gCO2/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF; fatores de uso, RenovaCalc), linha Gasolina A, coluna CO2 Fóssil, g/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade; conferir a referência IPCC (3069.99 gCO2/kg) e o desvio mostrado na tela. |
| R02 | GASOLINE_A | EMISSION_FACTOR CH4 = **1.089 gCH4/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF), linha Gasolina A, coluna CH4 Fóssil, g/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R03 | GASOLINE_A | EMISSION_FACTOR N2O = **0.348 gN2O/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF), linha Gasolina A, coluna N2O, g/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R04 | GASOLINE_A | LHV = **43.54 MJ/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Tabela 2 (p. 19 do PDF), linha Gasolina A, coluna PCI (MJ/kg); 10.400 kcal/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R05 | GASOLINE_A | DENSITY = **0.742 t/m3** | 2025-01-01 a sem fim | ANP fatores 2025: Tabela única, linha Gasolina A, coluna Densidade (t/m3); 1 t/m3 = 1 kg/L. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R06 | ETHANOL_ANHYDROUS | EMISSION_FACTOR CO2_FOSSIL = **0 gCO2/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF), linha Etanol Anidro, coluna CO2 Fóssil = 0,0 g/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R07 | ETHANOL_ANHYDROUS | EMISSION_FACTOR CO2_BIOGENIC = **1929.2 gCO2/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF), linha Etanol Anidro, coluna CO2 biogênico, g/kg. Registrado, mas fora da fronteira CO2_ONLY. | Achar a linha e a coluna do localizador e conferir o número e a unidade; confirmar que fica fora de `CO2_ONLY`. |
| R08 | ETHANOL_ANHYDROUS | DENSITY = **0.791 t/m3** | 2025-01-01 a sem fim | ANP fatores 2025: Tabela única, linha Etanol anidro, coluna Densidade (t/m3). | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R09 | GASOLINE_C | composição ETHANOL_ANHYDROUS 32% em volume | 2026-08-01 a 2027-01-27 | CNPE 9/2026: Art. 1º: teor obrigatório de 32% de etanol anidro na Gasolina Comum, em caráter excepcional e temporário | Conferir o artigo, o percentual e a data de vigência no ato; o resto da mistura é por diferença. |
| R10 | GASOLINE_C | composição GASOLINE_A (resto da mistura) | 2026-08-01 a 2027-01-27 | CNPE 9/2026: Resto da mistura (68%): gasolina A, por diferença do art. 1º | Conferir o artigo, o percentual e a data de vigência no ato; o resto da mistura é por diferença. |
| R11 | GASOLINE_C | DENSITY = **0.754 t/m3** | 2025-01-01 a sem fim | ANP fatores 2025: Tabela única, linha Gasolina C, coluna Densidade (t/m3), valor publicado com o teor vigente em 2025. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R12 | GASOLINE_C | composição ETHANOL_ANHYDROUS 30% em volume | 2025-08-01 a 2026-07-31 | CNPE 9/2025: Art. 1º, I: 30% de etanol anidro na Gasolina Comum; art. 3º: vigência em 01/08/2025 | Conferir o artigo, o percentual e a data de vigência no ato; o resto da mistura é por diferença. |
| R13 | GASOLINE_C | composição GASOLINE_A (resto da mistura) | 2025-08-01 a 2026-07-31 | CNPE 9/2025: Resto da mistura (70%): gasolina A, por diferença do art. 1º, I | Conferir o artigo, o percentual e a data de vigência no ato; o resto da mistura é por diferença. |
| R14 | DIESEL_A | EMISSION_FACTOR CO2_FOSSIL = **3098.8 gCO2/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF), linha Diesel A (B0), coluna CO2 Fóssil, g/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade; conferir a referência IPCC (3186.3 gCO2/kg) e o desvio mostrado na tela. |
| R15 | DIESEL_A | EMISSION_FACTOR CH4 = **0.165 gCH4/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF), linha Diesel A (B0), coluna CH4 Fóssil, g/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R16 | DIESEL_A | EMISSION_FACTOR N2O = **0.165 gN2O/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF), linha Diesel A (B0), coluna N2O, g/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R17 | DIESEL_A | LHV = **42.29 MJ/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Tabela 2 (p. 19 do PDF), linha Diesel A, coluna PCI (MJ/kg); 10.100 kcal/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R18 | DIESEL_A | DENSITY = **0.84 kg/L** | 2025-01-01 a sem fim | EPE NT 2025/03: Tabela 2 (p. 19 do PDF), linha Diesel A, coluna Massa Específica (kg/litro). | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R19 | BIODIESEL_B100 | EMISSION_FACTOR CO2_FOSSIL = **0 gCO2/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF), linha Biodiesel (B100), coluna CO2 Fóssil = 0,0 g/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R20 | BIODIESEL_B100 | EMISSION_FACTOR CO2_BIOGENIC = **2762.5 gCO2/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Apêndice, Tabela A 2 (p. 38 do PDF), linha Biodiesel (B100), coluna CO2 biogênico, g/kg. Registrado, mas fora da fronteira CO2_ONLY. | Achar a linha e a coluna do localizador e conferir o número e a unidade; confirmar que fica fora de `CO2_ONLY`. |
| R21 | BIODIESEL_B100 | DENSITY = **0.88 kg/L** | 2025-01-01 a sem fim | EPE NT 2025/03: Tabela 2 (p. 19 do PDF), linha Biodiesel, coluna Massa Específica (kg/litro). | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R22 | BIODIESEL_B100 | LHV = **37.68 MJ/kg** | 2025-01-01 a sem fim | EPE NT 2025/03: Tabela 2 (p. 19 do PDF), linha Biodiesel, coluna PCI (MJ/kg); 9.000 kcal/kg. | Achar a linha e a coluna do localizador e conferir o número e a unidade. |
| R23 | DIESEL_B | composição BIODIESEL_B100 15% em volume | 2025-08-01 a sem fim | CNPE 8/2025: Art. 1º: 15% de biodiesel, em volume, no óleo diesel comercial; art. 2º revoga o art. 1º da Res. 6/2025 (B14); art. 3º vigência em 01/08/2025 | Conferir o artigo, o percentual e a data de vigência no ato; o resto da mistura é por diferença. |
| R24 | DIESEL_B | composição DIESEL_A (resto da mistura) | 2025-08-01 a sem fim | CNPE 8/2025: Resto da mistura (85%): diesel A, por diferença do art. 1º | Conferir o artigo, o percentual e a data de vigência no ato; o resto da mistura é por diferença. |
| R25 | GASOLINE_C | EMISSION_FACTOR CO2_FOSSIL = **1504.143816 gCO2/L** | 2026-08-01 a 2027-01-27 | EPE NT 2025/03: DERIVADO: 0.68 (1-0,32, CNPE 9/2026 art. 1) x 0.742 kg/L (ANP) x 2981.1 gCO2 fóssil/kg (EPE 2025, EPE Tab. A 2) = 1504.143816 gCO2/L; etanol: CO2 fóssil 0; mistura ideal por volume | Refazer a conta do localizador com os valores já revisados (não há número publicado para comparar). Conferir a vigência. |
| R26 | GASOLINE_C | EMISSION_FACTOR CO2_FOSSIL = **1548.383340 gCO2/L** | 2025-08-01 a 2026-07-31 | EPE NT 2025/03: DERIVADO: 0.70 (1-0,30, CNPE 9/2025 art. 1) x 0.742 kg/L (ANP) x 2981.1 gCO2 fóssil/kg (EPE Tab. A 2) = 1548.383340 gCO2/L; etanol: CO2 fóssil 0; mistura ideal por volume | Refazer a conta do localizador com os valores já revisados (não há número publicado para comparar). Conferir a vigência. |
| R27 | DIESEL_B | EMISSION_FACTOR CO2_FOSSIL = **2212.54320 gCO2/L** | 2025-08-01 a sem fim | EPE NT 2025/03: DERIVADO: 0.85 (1-0,15, CNPE 8/2025 art. 1) x 0.84 kg/L (EPE Tab. 2) x 3098.8 gCO2 fóssil/kg (EPE Tab. A 2) = 2212.54320 gCO2/L; biodiesel: CO2 fóssil 0; mistura ideal por volume | Refazer a conta do localizador com os valores já revisados (não há número publicado para comparar). Conferir a vigência. |

## O que a sua revisão significa e o que não significa
- `REVIEWED_OK` interna permite que o catálogo **resolva** o fator em `CO2_ONLY` e que um pino de projeto o referencie. Tudo continua `NOT_ADMITTED` e `usableForE8=false`.
- Não é revisão independente nem aprovação normativa. A base `INDEPENDENT` exige outra pessoa, que não tenha registrado o dado.
- A comparação com o IPCC não é validação independente: a fonte nacional parte dos mesmos fatores ANP/RenovaCalc e o IPCC é a referência comum.
- O fator de gasolina C e de diesel B são **derivados por mistura ideal em volume** (sem tolerância de ±1 p.p. do teor). Se você não aceita esse método, use `NEEDS_INFORMATION` e descreva.
- A janela do E32 (até 27/01/2027) é a mínima, sem prorrogação lida. Nada aqui estende vigência.
