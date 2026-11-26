# CF-DATA-009 — Vínculo do fator fóssil do projeto ao catálogo de combustíveis (vertical BE)

> **Estado:** estrutura e ensaio com **dado sintético**. Não existe ainda fator fóssil brasileiro real no catálogo (etapa 5 do roteiro: EPE, Inventário MCTI, ANP). Nada é admitido: `usableForE8=false`, pino `PINNED_NOT_ADMITTED`. Nível D17: projeto (reutiliza o pino `EF_FUEL` e o executor E8; sem motor paralelo).

## O que existe
- `POST /v1/platform/open-parameter-bindings` aceita, para `EF_FUEL`, `catalog: {fuelId, boundary, scope, date}` no lugar de `candidateId`. Número digitado junto é recusado.
- Só `CO2_ONLY` (VM0038 v1.1, Tabela 1): uma entrada de CO2 fóssil do catálogo (V111), válida na data, com revisão `REVIEWED_OK` (interna ou independente), convertida pelas regras do catálogo para tCO2/USgal. `EF_CO2e = EF_CO2 × GWP(CO2)`, GWP = 1 registrado. Fronteira com CH4/N2O é recusada: faltam fatores e GWP vinculados.
- V116 aditiva: `value_kind = CATALOG_REFERENCE` e `app_open_efuel_catalog_pin` (append-only): entrada, fronteira, escopo, data, valor e unidade originais, valor normalizado, passos de conversão e base da revisão. O catálogo não é copiado; o hash da entrada vai no pino.
- O pino fica **obsoleto** (`STALE`) se a revisão mais recente da entrada deixar de ser `REVIEWED_OK` em alguma base.
- O E8 lê o fator do pino (`fuelValue`), o IR e a consolidação anual comparam a identidade do fator pela entrada do catálogo, e as referências de revisão do IR citam a evidência da entrada.

## Decisões e limites
- O contrato do E8 guarda no máximo 12 casas decimais; o valor convertido é **arredondado de forma explícita** (HALF_EVEN, 12 casas), e isso consta da fórmula do pino. O valor original permanece na entrada do catálogo.
- O IR exige fator fóssil distinto em cada ano (atualização anual real): no catálogo, uma entrada com vigência e vintage próprias por ano.
- Biogênico e misturas (etanol na gasolina C, biodiesel no diesel B): **não** tratados; o pino exige CO2 fóssil explícito e não presume que CO2 sem origem seja fóssil.

## Ensaio (sintético)
`OpenSeriesBoundRunIntegrationTest`: BE = ED × EF × 100 × IR / (AFEC × MPG) = 2700 kWh × (2,25 kgCO2/L → 0,008517176514 tCO2e/USgal) × 100 × 1 / (20 × 30) = **3,8327294313 tCO2e**; PE = 1,6317 tCO2e (série); tentativa `REVIEWED_INTERNAL`, nada admitido.
