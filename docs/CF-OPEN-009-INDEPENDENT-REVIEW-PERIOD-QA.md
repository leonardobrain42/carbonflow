# CF-OPEN-009 / #98 — Revisão de leituras e reconciliação horária (fatia 3)

**Natureza da entrega:** QA técnico INTERNO. Não é validação VVB, não é crédito emitido, não é promoção de fator ou medição para execução E8. A issue #98 permanece aberta.

## Contexto
A main já contém V52 (carregadores e configurações) e V53 (CSV original AES-GCM, candidatos e quarentena), com as PRs #114, #115 e #116 integradas. A presente fatia usa esse mesmo ledger; não cria outra ingestão bruta.

## Modelo V54
- Parecer por linha `app_open_measurement_review` com `version`, resultado `ACCEPTED_FOR_QA` ou `REJECTED`, motivo, pessoa revisora e horário. Histórico append-only. Um parecer posterior pode alterar a decisão corrente sem apagar a versão anterior.
- Trigger independente recusa parecer da própria conta que submeteu o lote; lote em QUARANTINED jamais é elegível a ACEITO para QA, mesmo se uma linha isolada parecer adequada. Não existe decisão automática.
- `app_open_period_qa_snapshot` + `app_open_period_qa_snapshot_line`: snapshot com ED e EC separados, intervalo e SHA-256 determinístico baseado nas linhas e versões de pareceres, preservando referências a blob, medidor e revisão. O banco impõe `status=INTERNAL_QA_ONLY` e `usable_for_e8=false`.
- Snapshots não são alteráveis nem apagáveis; se uma decisão muda, o preview recalcula novos bloqueios, e o snapshot antigo permanece apenas como registro histórico. Ainda não existe ação de publicação para E8.

## Permissões
- **REV com grant MONITORING ativo** é o único papel que pode decidir linha e congelar snapshot interno. Um DRAFT_OWNER não recebe permissão implícita de revisor. OP/PREP enviam e organizam dados, porém não revisam.
- Todos podem consultar apenas os dados de seus projetos conforme escopo e grants de monitoramento.
- Revogação de grant tem efeito imediato; acessos indevidos têm resposta opaca. Parecer e snapshot são auditados por `DynamicProjectScope`.

## APIs
- GET /v1/projects/{project}/technical/open-monitoring/batches/{id}/reviews — últimos pareceres por linha
- POST /v1/projects/{project}/technical/open-monitoring/batches/{id}/rows/{line}/reviews — corpo `{outcome,reason}`, header `If-Match: "0"` inicial; próxima versão `"1"`, etc. Uma repetição idêntica do próprio parecer é idempotente; conflito real bloqueia.
- GET /v1/projects/{project}/technical/open-monitoring/period-qa?from=...&until=... — prévia de QA, sem alterações.
- POST /v1/projects/{project}/technical/open-monitoring/period-qa/snapshots — `{from,until}` somente REV, quando todas as verificações de QA estiverem satisfeitas.
- GET /v1/projects/{project}/technical/open-monitoring/period-qa/snapshots — histórico.
- Respostas HTTP privadas e `no-store` (dados não públicos).

## QA por período
- Requer janelas UTC horárias completas entre 1 e 744 horas; não divide automaticamente leituras maiores.
- Itera todos os carregadores, requer um medidor ED (downstream) e um EC (upstream) por carregador; exige exatamente uma leitura de cada por hora. Sem dados, retorna BLOQUEADO, nunca `ED=0`.
- Bloqueia medições sem parecer vigente `ACCEPTED_FOR_QA`, quarentena não resolvida, duplicatas/sobreposição horária, lacunas, configuração/certificado fora de vigência, versão de medidor ou carregador desatualizada e topologia não suportada (solar, armazenamento, V2G, outras redes).
- Se `EC<ED` para uma hora da mesma estação, sinaliza anomalia e impede fechamento; não aplica desconto/fator automaticamente.
- ED=2700 e EC=3000 em um fixture são valores separados de QA; não os confunde nem os transforma em BE/PE/ER.
- Se qualquer gate falha, a API omite os totais para impedir interpretações de dados parciais como resultado.
- Mesmo com todos os gates de QA verde, `canRunE8=false`. O snapshot congela apenas a reconciliação sintática/operacional interna, não a verdade do certificado de calibração.

## Limites e pendências para fechar #98
1. Avaliação técnica de autenticidade/custódia de certificado de medidor (a existência de um identificador não valida o documento) e contrato de fornecimento pela fonte OP.
2. Reconciliação de telemetria em casos de interrupção, relógio descalibrado, medidor agregado e várias estações na mesma fronteira; tratamento de zero real com evidência, correção de dados já aceitos e motivos de exclusão com reabertura do período.
3. Modelagem e aprovação de `PeriodEnergySnapshot` **admitido** por revisão técnica e metodológica, com regras verificáveis sob VM0038 §9.2, parâmetros horário EFel por fonte/vintage e atribuição de direitos.
4. Integração com o grafo de requisitos (#13/#35), alertas STALE e cálculos do projeto real (#97), com teste E2E de ambos os lados.
5. Convites de OP de ponta a ponta, contrato da operadora, licenças de dados e eventual retenção/expurgo de dados de conteúdo de terceiros.
6. QA de múltiplas instalações, locais e clocks, interface completa e revisão de segurança independente.

**Regra de projeto:** RECONCILIADO_PARA_QA não significa DADO_ADMITIDO, nem crédito.
