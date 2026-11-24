# E4 — Dados, evidências e documentos
## Domínios e identidade
| Domínio | Referências a conservar | Dono |
|---|---|---|
| Projeto e contexto | organization/project/environment, triagem/intake/edição, janela e revisão | #16/#93 |
| Fonte/versão/direito | source ID, edição, hash, locator, operação, licença vigente e avaliação | #92/#10 |
| EVR e ratings | modelo/alias, estoque vs venda, vintage, snapshot e pares | #94/#95 |
| Parâmetros | espécie, unidade, gás, vintage/ano, região, fonte, pino/avaliação, parecer e hash | #96 |
| ED/EC | medidor, configuração, calibração, intervalo/UTC/fuso, bruto, lote, QA e snapshot | #98 |
| Memória | projeto, janela, ano relativo/fonte, operandos imutáveis, hash de seleção, engine/versão, estado e resultados | #97 |
| PD/MR e ato | seção, template/edição, claim, referência de fato/regra/memória, revisão e manifesto, autoridade e recibo | #43/#122/#128 |

Não criar planilhas/entidades paralelas para o mesmo dado. Ausência ≠ zero; dedupe por identidade de origem e versão; corrigir em nova revisão e marcar dependentes obsoletos. Valores e texto protegido têm política de acesso/purge; imutabilidade do histórico não autoriza conservar bytes ou dados licenciados após revogação.

## Contratos de dados
[CF-OPEN-009-METERING-CONFIGURATION](../../docs/CF-OPEN-009-METERING-CONFIGURATION.md), [CF-OPEN-009-MEASUREMENT-CSV-INGESTION](../../docs/CF-OPEN-009-MEASUREMENT-CSV-INGESTION.md), [CF-OPEN-009-PERIOD-BATCH-ADMISSION](../../docs/CF-OPEN-009-PERIOD-BATCH-ADMISSION.md), [CF-OPEN-008-E8-PROJECT-INPUT-BINDING-CANDIDATE](../../docs/CF-OPEN-008-E8-PROJECT-INPUT-BINDING-CANDIDATE.md), [CF-OPEN-008-E8-PROJECT-RUN](../../docs/CF-OPEN-008-E8-PROJECT-RUN.md), [CF-TRUST-001-PD-QUANTIFICATION-PROVENANCE](../../docs/CF-TRUST-001-PD-QUANTIFICATION-PROVENANCE.md). O [marco PD #122](https://github.com/leonardobrain42/carbonflow/issues/122) define DocumentContext, DocumentSourceRef, DocumentBlock e ClaimSupport como contratos lógicos, não assumir que todos já existem. Dados derivados read-only no compositor; ausência/bloqueio/stale visíveis. PD ex ante e MR por período não se confundem.
O dicionário CAM-E4, telas e mapa PD/MR históricos precisam ser recuperados; esta tabela não atribui IDs originais.
