# ECA-001 · Política de fontes

## Decisão de arquitetura

A jornada manual e o motor determinístico usam regras próprias, fontes identificadas e decisões versionadas. A trilha de evidências do projeto mantém escopo de organização e projeto. Um futuro orquestrador de IA deverá consultar a política antes de obter conteúdo, enviar dados a um provedor e entregar o resultado.

A decisão depende da versão exata da fonte, operação, finalidade, ambiente, organização, projeto, conta e provedor, conforme os limites da autorização. A decisão vigente e o SHA-256 da autorização escrita sustentam a auditoria. A revogação na trilha bloqueia novas consultas. O horário usado para a licença é o da consulta, inclusive em reprocessamento de snapshots históricos.

As referências de metadados podem identificar fonte e titular. A fixture original em `fixtures/eca-source-v1.txt` autoriza somente a referência às regras sintéticas em sandbox. `carbonflow.normative-ai.disabled` inicia habilitado. A ferramenta de preparação manual segue disponível nesse modo.

## Questões para revisão jurídica

| Titular ou origem | Questões a verificar | Situação |
| --- | --- | --- |
| Verra e titulares das dependências da metodologia | Direito de guardar, exibir, extrair, indexar, enviar a provedor de IA, gerar derivados e ofertar em SaaS comercial | Autorização a comprovar por operação e edição |
| ISO e respectivas distribuidoras | Usuários cobertos pelo contrato, cópias, uso em SaaS e limites de processamento | Autorização a comprovar por publicação |
| Fontes brasileiras, incluindo dados oficiais | Titular, termos da edição, atribuição, API, redistribuição e uso comercial | Verificar individualmente |
| Evidência de cliente | Finalidade, destinatários, retenção, residência e provedor permitido | Verificar por projeto e contrato |

O registro e a análise de permissões neste corte são mantidos nas tabelas de fontes e decisões. A interface administrativa, a custódia da prova, a eventual solicitação externa e o descarte de índices quando existir ingestão fazem parte das etapas posteriores.
