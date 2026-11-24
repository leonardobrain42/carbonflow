# E1 — Fontes, escopo e admissibilidade
## Referências de projeto, não cópias normativas
| Fonte/necessidade | Uso e revisão necessária | Contrato |
|---|---|---|
| VM0038 v1.1 | Aplicabilidade, linha de base, quantificação e monitoramento; regime por ato | [#10](https://github.com/leonardobrain42/carbonflow/issues/10), [#89](https://github.com/leonardobrain42/carbonflow/issues/89), [#93](https://github.com/leonardobrain42/carbonflow/issues/93), [Verra](https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/) |
| VMD0049 v1.1 / VT0008 quando aplicável | Rota de adicionalidade e lista positiva não substituem teste de mercado | [#93](https://github.com/leonardobrain42/carbonflow/issues/93), [#99](https://github.com/leonardobrain42/carbonflow/issues/99) |
| VT0011 v1.0 / TOOL07 v7.0 | Fator elétrico regional; opção e elegibilidade justificadas | [CF-OPEN-007-VT0011-CASE2-WORKBOOK](../../docs/CF-OPEN-007-VT0011-CASE2-WORKBOOK.md), [CF-OPEN-007-TOOL07-SIMPLE-OM-A1-PRECHECK](../../docs/CF-OPEN-007-TOOL07-SIMPLE-OM-A1-PRECHECK.md) |
| VCS, PD/MR, ISO | Edição/ato e acesso autorizado, parecer competente | [#10](https://github.com/leonardobrain42/carbonflow/issues/10), [#122](https://github.com/leonardobrain42/carbonflow/issues/122), [E8-013](../../evidencias/e8-013/VERIFICACAO.md) |
| Senatran/RENAVAM | Estoque registrado, não automaticamente BEVs em circulação | [CF-OPEN-005-BR-DATA-FEASIBILITY](../../docs/CF-OPEN-005-BR-DATA-FEASIBILITY.md), [CF-OPEN-005-NATIONAL-COVERAGE-DECISION](../../docs/CF-OPEN-005-NATIONAL-COVERAGE-DECISION.md) |
| Inmetro/PBEV | Ratings, não estoque; comparabilidade independente | [CF-OPEN-006-PBEV-RATINGS](../../docs/CF-OPEN-006-PBEV-RATINGS.md) |
| MCTI/ONS, combustíveis | Candidatos para fatores; gases, unidade, vintage, cobertura, licença | [CF-OPEN-007-EFFUEL-SOURCE-REVIEW](../../docs/CF-OPEN-007-EFFUEL-SOURCE-REVIEW.md), [CF-OPEN-007-HOURLY-EFEL-SOURCE-CANDIDATES](../../docs/CF-OPEN-007-HOURLY-EFEL-SOURCE-CANDIDATES.md) |

Amostra RENAVAM setembro/2026 e manifesto de origem estão em [evidência](../../docs/evidence/CF-OPEN-005-2026-09/source-manifest.json); não fecham o denominador nacional. Dados PBEV não são contagem de frota. Fator elétrico médio corporativo não é automaticamente OM; Case 2 VT0011 usa peso OM=1/BM=0, não BM=0 como fator. Não inventar defaults para Brasil.

## Fluxo de fonte
Descoberta → metadados → direito por operação → quarentena autorizada → extração tipada → candidato → revisão técnica por finalidade → vinculação versionada → rechecagem/revogação. Hash não prova direito nem autenticidade. Fontes de acesso restrito podem permanecer metadata-only; IA/RAG exige autorização específica. Contratos: [ECA-001-POLITICA-DE-FONTES](../../docs/ECA-001-POLITICA-DE-FONTES.md), [CF-OPEN-001-READINESS-CONTEXTS](../../docs/CF-OPEN-001-READINESS-CONTEXTS.md), [CF-OPEN-003-SOURCE-METADATA-INTAKE](../../docs/CF-OPEN-003-SOURCE-METADATA-INTAKE.md), [CF-OPEN-003-TABULAR-QUARANTINE](../../docs/CF-OPEN-003-TABULAR-QUARANTINE.md). Issues #92/#94/#95/#96 permanecem abertas.
## Lacuna do kit
CATALOGO_FONTES, INVENTARIO_FONTES, MATRIZ_DE_VIGENCIA, DBR-001/adendo ONS não recuperados. Não reconstruir seus IDs, conclusões ou vigências por memória. Registrar fonte, título, data de publicação/referência/captura, versão, geografia, unidade/gás, escopo, locator, licença, hash e parecer, antes de qualquer admissão.
