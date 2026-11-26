# CF-DATA-009 — Vínculo do fator fóssil do projeto ao catálogo de combustíveis (vertical BE)

> **Estado:** estrutura e ensaio com **dado sintético** no E8; o catálogo já tem fatores brasileiros reais (`CF-DATA-010`), ainda sem revisão e sem ensaio E8 com eles. Nada é admitido: `usableForE8=false`, pino `PINNED_NOT_ADMITTED`. Nível D17: projeto (reutiliza o pino `EF_FUEL` e o executor E8; sem motor paralelo).

## O que existe
- `POST /v1/platform/open-parameter-bindings` aceita, para `EF_FUEL`, `catalog: {fuelId, boundary, scope, date}` no lugar de `candidateId`. Número digitado junto é recusado.
- Só `CO2_ONLY` (VM0038 v1.1, Tabela 1): uma entrada de CO2 fóssil do catálogo (V111), válida na data, com revisão `REVIEWED_OK` (interna ou independente), convertida pelas regras do catálogo para tCO2/USgal. `EF_CO2e = EF_CO2 × GWP(CO2)`, GWP = 1 registrado. Fronteira com CH4/N2O é recusada: faltam fatores e GWP vinculados.
- V116 aditiva: `value_kind = CATALOG_REFERENCE` e `app_open_efuel_catalog_pin` (append-only): entrada, fronteira, escopo, data, valor e unidade originais, valor normalizado, passos de conversão e base da revisão. O catálogo não é copiado; o hash da entrada vai no pino.
- O pino fica **obsoleto** (`STALE`) se a revisão mais recente da entrada deixar de ser `REVIEWED_OK` em alguma base.
- O E8 lê o fator do pino (`fuelValue`), o IR e a consolidação anual comparam a identidade do fator pela entrada do catálogo, e as referências de revisão do IR citam a evidência da entrada.

## Decisões e limites
- O contrato do E8 guarda no máximo 12 casas decimais; o valor convertido é **arredondado de forma explícita** (HALF_EVEN, 12 casas), e isso consta da fórmula do pino. O valor original permanece na entrada do catálogo.
- O IR exige fator fóssil distinto em cada ano (atualização anual real): no catálogo, uma entrada com vigência e vintage próprias por ano.
- Biogênico e misturas: o pino exige uma entrada de CO2 **fóssil** explícita e não presume que CO2 sem origem seja fóssil. Gasolina C e diesel B têm, no catálogo (`CF-DATA-010`), uma entrada de CO2 fóssil **derivada por mistura** com a conta no localizador, por vigência (E30, E32, B15); o CO2 biogênico fica fora de `CO2_ONLY`.

## Guarda de vigência no E8 (`EF_FUEL_WINDOW`)
O pino resolve o fator numa única `date`, mas o período de monitoramento tem uma janela. Com dado real a vigência muda no meio do ano (E30 até 31/07/2026, E32 desde 01/08/2026, diferença de 2,8% no CO2 fóssil por litro). Antes, o fator da janela da `date` seria aplicado ao período inteiro sem aviso. Agora o E8 exige que a vigência da entrada do catálogo **cubra toda a janela do período** (`valid_from` ≤ início e fim ≤ `valid_to`, ou sem fim). Se não cobre: gate `EF_FUEL_WINDOW` = `REVIEW_REQUIRED`, `EF_FUEL` sem valor utilizável, nenhum número e próxima ação "preparar janelas separadas na mudança de vigência". Só vale para pinos `CATALOG_REFERENCE`; os demais seguem as regras antigas.
- **Limite deliberado:** o motor **não divide** o período entre duas vigências nem faz média ponderada. Um período que atravessa 01/08/2026 precisa ser preparado em janelas separadas, cada uma com o fator da sua vigência. A divisão automática, se desejada, é decisão metodológica à parte.
- O pino por ano-fonte continua um por (projeto, tipo, ano): duas vigências no mesmo ano exigem janelas/períodos distintos e ainda não têm pino próprio por janela. **Limite conhecido.**

## Ensaio (sintético)
O ensaio cobre também o caso negativo da guarda: re-pino de 2024 para uma entrada válida só a partir de 2024-03-01, com a janela do período em 2024-02-01: sem números e com o gate `EF_FUEL_WINDOW`.

`OpenSeriesBoundRunIntegrationTest`: BE = ED × EF × 100 × IR / (AFEC × MPG) = 2700 kWh × (2,25 kgCO2/L → 0,008517176514 tCO2e/USgal) × 100 × 1 / (20 × 30) = **3,8327294313 tCO2e**; PE = 1,6317 tCO2e (série); tentativa `REVIEWED_INTERNAL`, nada admitido.

## Ensaio com os fatores reais do catálogo (rc.89)
`OpenRealFuelCatalogRunIntegrationTest` repete o ensaio E8 (PE pela série, BE pelo catálogo, IR, Dy) com os dois **valores reais** de gasolina C do seed (`docs/data/fuel-catalog-br-seed.json`, lidos do arquivo, não copiados). Todas as classes de teste compartilham um banco na CI e o catálogo é append-only; por isso o ensaio registra esses valores sob um **combustível próprio de teste** (id privado), sem tocar em `GASOLINE_C`, e o `FuelCatalogIntegrationTest` faz o mesmo com cópias privadas dos combustíveis do seed. Assim nenhum teste depende da ordem de execução. O projeto começa em **01/08/2025**, então o ano 1 do projeto é a janela do **E30** (até 31/07/2026) e o ano 2 é a do **E32** (desde 01/08/2026): cada ano tem uma entrada real distinta, como o IR exige, e a vigência cobre a janela de cada período.
- Ano 1: pino `1548.383340 gCO₂/L`; ano 2: pino `1504.143816 gCO₂/L` (a fórmula do pino cita o valor original de cada um).
- BE do ano 2 com o fator E32 convertido (≈ 0,005694 tCO₂e/USgal), conferido contra a fórmula `ED × EF × 100 × IR / (AFEC × MPG)` com tolerância de 1e-6, que distingue o E32 do E30 (≈ 2,9%).
- A guarda `EF_FUEL_WINDOW` continua: re-pino de 2026 para uma entrada válida só a partir de 2026-10-01 deixa a janela de 2026-09-01 sem números.
- **O que é sintético:** frota, energia, série, Dy e **as revisões** (inseridas pelo teste, marcadas como sintéticas); nada disso é a revisão `OWNER_INTERNAL` do responsável. Nada é admitido.
- **O que não cobre:** os demais fatores do seed (diesel B, CH4/N2O), ano de transição (Q13) e dados reais de frota e energia.

