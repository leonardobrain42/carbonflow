# CF-OPEN-009 / #98 — CSV ED/EC com evidência cifrada e candidatas (fatia 2)

**Status: candidato/quarentena.** Este PR não encerra a issue #98, não cria PeriodEnergySnapshot, não confirma calibração, não faz revisão independente e nunca habilita quantificação E8.

## Escopo implementado

A PR #114 foi mesclada em main com V52 e os contratos de inventário de DCFC e medidores. Esta entrega parte dessa base.

- V53 cria app_project_evidence_blob, um cofre **genérico por projeto**, com bytes originais de CSV em AES-256-GCM, nonce aleatório, SHA-256, nome e uploader, escopo de organização e projeto. O sistema não cria uma segunda tabela de bruto ED/EC em paralelo à E8-004; as linhas tabulares são apenas candidatos referenciando o blob.
- app_open_measurement_batch e app_open_measurement_candidate registram source_system, parser_version, hash por linha, data/hora, unidade normalizada kWh, medidor e versão, estado CANDIDATE/QUARANTINED e códigos de problema por linha, sempre imutáveis.
- POST /v1/projects/{project}/technical/open-monitoring/batches recebe Content-Type text/csv, X-Source-Id, X-File-Name e Idempotency-Key. Resposta HTTP 202 inclui identificador, hash, estado e QA das linhas, jamais dados de chave/ciphertext.
- GET .../batches e GET .../batches/{id} listam candidatos apenas para usuários autorizados do mesmo projeto. No-store e 404 opaco cross-tenant.
- Parser v1 exige CSV UTF-8 de 1 a 2.000 linhas, até 2 MB; cabeçalho exato meterCode,sequence,kind,start,endExclusive,zone,quantity,unit; variável ED ou EC; unidade Wh/kWh/MWh; instante ISO 8601 com offset; intervalos de até 1h; fuso UTC ou America/Sao_Paulo; fonte da leitura igual a source_system cadastrado no medidor; configuração e calibração temporalmente cobrindo os instantes; topologia DCFC/SIN sem bateria/solar/V2G.
- Ausência, zero sem evidência, negativo, intervalo inválido, fonte divergente, medidor ausente, calibração expirada, charger stale e duplicatas fonte+medidor+intervalo+sequência são quarentenadas. O blob original permanece cifrado para auditoria; uma correção gera novo lote e nunca reescreve evidência anterior.
- Idempotência serializada pelo escopo do projeto e vinculada à conta, origem, nome, hash do conteúdo; novo envio idêntico devolve o mesmo lote.
- OP com grant MONITORING pode enviar candidatos e ler seu escopo, mas não alterar inventário nem ver o diagnóstico do cálculo. REV lê, mas não faz upload. DRAFT_OWNER e PREP com grant MONITORING podem preparar envios; nenhuma dessas ações constitui validação.
- Interface Angular: upload de CSV com origem e lista de lotes/linhas com causas de quarentena. Os dados ainda não entram no cálculo.
- Chave separada CARBONFLOW_PROJECT_EVIDENCE_KEY: base64url de 32 bytes, obrigatória para upload em runtime, nunca fixada em produção. Somente testes usam fixture própria no perfil Quarkus de teste. Em ambiente Compose, configure no serviço app; sem a chave, upload falha com 503 (fail closed).

## O que permanece aberto

1. Garantir documentação/evidência de origem e contratos por fonte; habilitar convite operacional OP de ponta a ponta, mantendo restrições sobre dados financeiros.
2. QA/QC do período: integridade de horas, eventos de falha de fonte, relógio e gap, comparação contextual EC<ED, fronteira, perdas e configuração mista, com revisão distinta.
3. Aprovar/rejeitar registros em ledger independente, preservar histórico de correção, provas de calibração validadas e consentimentos aplicáveis.
4. PeriodEnergySnapshot por vintage, revisão e medidor, com reprocessamento/reopen, propagação STALE, conformidade com EFel horário (§9.2 VM0038) e fluxo MR/PD.
5. Integração somente de períodos admitidos ao cálculo E8 do projeto (#97), sem números do PIL-SYN-BR-001 e sem créditos emitidos artificialmente.
6. UI de fechamento/revisão e testes E2E de upload, reaplicação, calibração vencida, duplicidade, período parcial, falha, cross-tenant e revisão humana.

**Critérios normativos:** candidato, preliminar, admitido para cálculo, validado pelo VVB e emitido pela Verra são estados distintos. Esta PR só cria os dois primeiros estados de ingestão (CANDIDATE/QUARANTINED), jamais os demais.
