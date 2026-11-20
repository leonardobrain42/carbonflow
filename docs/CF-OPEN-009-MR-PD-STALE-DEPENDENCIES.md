# CF-OPEN-009 / #98 — Propagação de STALE ao MR, PD e pré-cálculo (fatia 6)

**Estado: infraestrutura de dependências internas, NÃO admissão metodológica ou emissão.**

## Distinção PD vs MR
- **PD (Project Description):** o projeto e seu plano podem ser redigidos **antes** de haver energia medida. Não exigir um MR futuro como pré-condição para todo PD.
- **MR (Monitoring Report):** depende de período/snapshot real, leitura ED/EC, proveniência, revisão e documentação. Nesta fatia só há um **rascunho interno de vínculo MR**, nunca um relatório aprovado ou emitido.
- **E8 dinâmico:** continua `canRunE8=false`; não absorve dados do piloto `PIL-SYN-BR-001` nem transforma QA em `BE/PE/ER`.

## Modelagem V57
- `app_open_mr_dossier_revision`: uma trilha append-only por projeto e código de período (ex.: M1/M2), com versão, ID/hash do snapshot, justificativa e autor. FK ao `app_open_period_qa_snapshot` do **mesmo projeto**, sem duplicar medições. Só aceita vincular snapshot que ainda esteja coerente com QA, mesmo que haja documentos pendentes. Qualquer mudança gera nova versão; não substitui o passado.
- `app_pd_monitoring_dependency`: para versões das seções **monitoring** e **quantification** do PD, preserva o SHA-256 do manifesto de versões MR existente na redação. O manifesto contém código, versão, snapshot, hash e projeto; é determinístico, sem fator sintético.
- Triggers de imutabilidade nas duas tabelas. Não há alteração das tabelas legadas de períodos SIM nem dos snapshots QA-only.

## Estados propagados (calculados a cada leitura)
- MR **PENDING_REQUIREMENTS**: snapshot não obsoleto, mas evidências/calibração/direitos ainda não conferidos internamente.
- MR **READY_FOR_NORMATIVE_REVIEW**: QA coerente e evidências conferidas internamente. **Ainda não é admissão normativa, VVB nem aptidão ao E8.**
- MR **STALE**: medidor, leitura, ocorrência, parecer ou outro insumo do snapshot ficou obsoleto. O histórico do MR e do snapshot permanece; a versão atual requer novo vínculo.
- PD **STALE**: manifesto de versões MR divergiu do que foi fixado na versão da seção PD. É preciso conferir as mudanças e gerar **nova versão da seção**.
- PD **BLOCKED_MONITORING_REQUIREMENTS**: o manifesto ainda é o mesmo, mas um ou mais MR vinculados estão com pendências ou `STALE`. As ações de submissão/aprovação interna/congelamento da seção afetada são removidas. Reabertura permanece disponível, preservando os pareceres históricos.
- PD **READY_FOR_INTERNAL_PD_REVIEW**: os vínculos estão coerentes e os documentos internamente conferidos; não implica admissão do MR, cálculo E8 ou aprovação por Verra.
- Sem nenhum MR vinculado, o PD inicial não fica bloqueado por medições futuras. Se um vínculo MR for criado depois, a seção PD anterior passa a estar desatualizada até nova versão.

## APIs e segurança
- `GET /v1/projects/{project}/technical/open-monitoring/dossiers`: lista a versão mais recente de cada MR e o estado recalculado das dependências. Requer VIEW_OPEN_MONITORING, sessão e escopo de projeto.
- `PUT /v1/projects/{project}/technical/open-monitoring/dossiers/{period}`: corpo `{snapshotId,note}` e header `If-Match: "N"`. Só DRAFT_OWNER ou PREP autorizado com `MONITORING` pode criar nova versão; OP ou REV não fazem reatribuição. Não aceita snapshot de outro projeto nem snapshot já STALE. Retentativa idêntica com a versão anterior é idempotente para o mesmo autor.
- `GET /technical/pd-sections` e `GET /technical/pd-reviews` passam a incluir `monitoringDependency` nas seções relevantes. Uma mudança de MR invalida a versão do PD redigida anteriormente; `PUT /technical/pd-sections/{id}` fixa o novo manifesto na nova revisão.
- `GET /technical/open-calculation-readiness` passa a exibir somente estados agregados do MR para responsáveis de CALCULATION. Sem expor valores ED/EC, arquivos de prova ou contratos, mantém `canRunE8=false` e BE/PE/ER nulos.
- Respostas marcadas `private, no-store`. Histórico imutável, auditoria de leitura e escrita por `DynamicProjectScope`.

## UX
- Seção fixa **Monitorar ED/EC → Dossiês de monitoramento (MR)**: vínculos M1/M2, versões, estados, bloqueios, formulário de associação.
- Editor PD e tela de revisão passam a indicar `STALE`/pendências do MR, com motivos. O usuário pode revisar conteúdo e criar outra versão; não há submenu dinâmico.

## Aceite e não-escopo
Testes de integração: MR sem documento pendente; MR com documentação conferida; PD redigido com manifesto; reatribuição MR invalida versão PD; nova versão PD reconcilia o manifesto; incidente posterior bloqueia MR e impede progressão da aprovação PD; ausência de MR não bloqueia o PD inicial; cross-tenant e OP sem SAVE não vinculam; preflight E8 continua bloqueado.

**Pendente da issue #98 e #97:** fluxo real de MR com peças documentais e evidências admitidas, QA de telemetria/relógio, direitos e calibração substantivos, admissão metodológica pela edição vigente, EFel horário, motor BE/PE/ER real, revisão VVB e eventual emissão de VCU. Nenhuma dessas etapas é entregue por um estado READY_FOR_NORMATIVE_REVIEW.
