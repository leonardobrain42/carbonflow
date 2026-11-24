# E2 — Regras metodológicas e parâmetros
**Recorte:** VM0038 v1.1, OPEN BR LDV BEV DCFC SIN GRID_ONLY (#89). Este índice não substitui matriz normativa ou leitura integral da edição aplicável.

| Conceito | Significado operacional | Fonte de contrato |
|---|---|---|
| EVR | Estoque BEV por modelo na população aplicável em circulação; separar vendas | [#94](https://github.com/leonardobrain42/carbonflow/issues/94), [CF-OPEN-005-EVR-STOCK-BR](../../docs/CF-OPEN-005-EVR-STOCK-BR.md) |
| AFEC | Consumo energético médio ponderado de BEV, rating e unidade/ciclo rastreáveis | [#95](https://github.com/leonardobrain42/carbonflow/issues/95), [CF-OPEN-006-PBEV-RATINGS](../../docs/CF-OPEN-006-PBEV-RATINGS.md) |
| MPG | Eficiência ponderada de comparáveis fósseis em mi/US gal, sem misturar ciclos | [#95](https://github.com/leonardobrain42/carbonflow/issues/95) |
| EFfuel | Fator fóssil por combustível/unidade/ano/gases; candidato não admitido | [#96](https://github.com/leonardobrain42/carbonflow/issues/96), [CF-OPEN-007-EFFUEL-SOURCE-REVIEW](../../docs/CF-OPEN-007-EFFUEL-SOURCE-REVIEW.md) |
| EFel | Fator elétrico regional por abordagem VT0011/TOOL07; rota horário/uniforme | [#96](https://github.com/leonardobrain42/carbonflow/issues/96), [CF-OPEN-007-VT0011-CASE2-WORKBOOK](../../docs/CF-OPEN-007-VT0011-CASE2-WORKBOOK.md) |
| IR | Melhoria tecnológica; rota anual específica, candidato 1 com fundamentos e revisão | [CF-OPEN-008-STRUCTURED-IR](../../docs/CF-OPEN-008-STRUCTURED-IR.md), [CF-OPEN-007-IR-ANNUAL-TEMPORAL](../../docs/CF-OPEN-007-IR-ANNUAL-TEMPORAL.md) |
| Dy | Desconto por concorrência/possível sobreposição; Eq.8 ERC/(ERC+ERF), ausência de frota somente comprovada | [CF-OPEN-007-DY-ASSESSMENT](../../docs/CF-OPEN-007-DY-ASSESSMENT.md) |
| ED / EC | Energia entregue / consumida, distinção de fronteira, origem e qualidade | [#98](https://github.com/leonardobrain42/carbonflow/issues/98), [CF-OPEN-009-INDEPENDENT-REVIEW-PERIOD-QA](../../docs/CF-OPEN-009-INDEPENDENT-REVIEW-PERIOD-QA.md) |
| BE / PE / LE / ER | Emissões baseline / projeto / fugas / redução; memória canônica por equação, unidade e período | [#97](https://github.com/leonardobrain42/carbonflow/issues/97), [CF-OPEN-008-OPEN-GRID-ARITHMETIC-TRACE](../../docs/CF-OPEN-008-OPEN-GRID-ARITHMETIC-TRACE.md) |

Não recalcular em Angular ou PD. PE horário soma EC_h × EFel_h de pares válidos; LE zero apenas no ramo simples documentado; IR/Dy seguem incidência metodológica e referências, não defaults. Consolidação soma resultados canônicos de janelas sem lacunas ou sobreposição, ER após Dy de cada execução; arredondamento apenas na apresentação indicada.

## Temporalidade e IR
Ano relativo deriva do início confirmado e aniversários em America/Sao_Paulo; ano fonte segue UTC; janela fim exclusivo. M1/M2 são códigos, não ano. IR V89–V96 exige referências por avaliação, revisão independente, plano ex ante, parecer inicial, conciliação e prova de quatro datas. No cálculo atual exige coincidência de período e referências; reuso intra-ano ainda não entregue. V96 data observada dentro do ano, não futura ao parecer, validade cobre período; controle interno, não prazo Verra. Ano 1 segue FIRST_YEAR_REVIEW_REQUIRED, sem assumir 1 por expoente zero. Ver [documento V96](../../docs/CF-OPEN-007-IR-ANNUAL-TEMPORAL.md).

## Limites
Pendente: matriz normativa original (MATRIZ_REGRAS, MAPA_PD_MR, DESCONTO_E_VINTAGES, REGISTRO_BASELINE), aprovação real de insumos, primeiro ano/ex ante independente de MR para quantificação, ERC/ERF consumidos no E8, consistência intra-ano, EC alternativa quando aplicável e E2E. Infraestrutura associada (eqs.5/6), AC/HDV/PHEV, outros ramos fora deste recorte. Não declarar não aplicabilidade geral.
