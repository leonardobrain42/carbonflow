# CF-OPEN-009 / #98 — Inventário e configuração ED/EC, primeira fatia

**Status: preparação apenas.** Esta entrega não importa leituras reais, não verifica dados e não executa E8. A issue #98 permanecerá aberta.

## Contratos entregues
- Flyway V52: app_open_charger_revision, app_open_meter_configuration_revision e app_open_monitoring_command, com versões imutáveis, isolamento por projeto/organização e vínculo explícito à versão do carregador.
- GET /v1/projects/{project}/technical/open-monitoring, com from/until opcionais para avaliar cobertura da calibração e da configuração.
- PUT .../chargers/{charger} e PUT .../meters/{meter}, com If-Match, Idempotency-Key, resposta idempotente de versão original, auditoria e no-store.
- DRAFT_OWNER pode planejar; PREP com grant MONITORING pode planejar; REV com esse grant pode consultar. Sem grant, o backend responde 404 opaco.
- Contratos distintos para ED (a jusante, efetivamente entregue ao BEV) e EC (a montante, consumo da infraestrutura). Identificação, sistema de origem, evidência e validade da calibração, vigência operacional, potência, SIN e topologia com fuso explícito.
- QA acusa medidor ED/EC ausente, dupla atribuição, calibração não coberta, configuração obsoleta após nova versão de carregador, origem/fuso pendentes e solar/bateria/V2G fora deste ramo.
- A interface Angular inclui a seção Monitorar energia, com formulários, versões e causas específicas de pendência. Cada pendência abre pelo nome em português; o código estável fica na referência.
- Testes unitários e de integração para fronteiras, datas, versões, autorização, conflitos, calibração e ausência de contaminação sintética.

## Segurança da separação entre piloto e projetos novos
O importador E8-004 atual usa pilot_project e convenções SIM-*. Não se reutiliza uma leitura desse projeto como prova para um app_project. Esta fatia NÃO cria uma segunda tabela de observações brutas, NÃO mexe no piloto, NÃO traz valores de M1/M2 e NÃO cria valores de ED, EC ou ER. Os campos de energia permanecem NULL, nunca 0 por falta de dados. Declarações de calibração não são revisão técnica ou certificado aceito.

## Pendente para fechar a issue
1. Adaptar storage bruto e ledger de evidências E8-004/#33 a projetos dinâmicos, sem duplicar o pipeline de dados, com verificação de licença/direitos.
2. Ingestão CSV/XLSX/API por OP autorizado, hash e deduplicação source+meter+period+sequence, fuso UTC/local, bytes originais e reprocessamento imutável.
3. Medições reais ED e EC por intervalo, revisão independente REV, registros de calibração aceitos e períodos/vintages congelados.
4. QA/QC de dados reais: lacuna, anomalia EC<ED, energia negativa, relógio, medidor, calibração expirada, corretivos e horários compatíveis com EFel da VM0038 §9.2.
5. Ligação a ProjectParameterBinding/#97 e MR/PD com snapshots versionados, propagação STALE e testes E2E, sem herdar valores SIM.
6. Autorização formal do papel OP e integração com fluxo de convites próprio, respeitando compartimentação de dados financeiros.

A fatia atual é configuração observável e versionada; não torna o projeto apto a quantificação ou à emissão de VCUs.
