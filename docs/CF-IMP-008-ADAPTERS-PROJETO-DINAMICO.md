# CF-IMP-008 — Adapters E8 para projeto dinâmico: primeira fatia

**Issue:** [#16](https://github.com/leonardobrain42/carbonflow/issues/16) · **Dependência:** CF-IMP-007 ([PR #57](https://github.com/leonardobrain42/carbonflow/pull/57)) · **Ambiente:** SANDBOX · **Estado:** implementação incremental da fronteira técnica, **não** entrega integral de todos os módulos E8.

## Objetivo e decisão arquitetural

A implementação legada usa \`PilotAccess.Scope\` e tabelas \`pilot_*\` atreladas a credenciais \`SIM-*\`. Um projeto criado por conta não pode receber permissões PREP/PP/VVB apenas para aproveitar o mesmo serviço. Esta fatia introduz \`DynamicProjectScope\` e \`DynamicProjectTechnical\` como fronteira de leitura/escrita segregada, usa \`ProjectContext.Draft\`, reaproveita **metadados normativos** de \`DocumentRules\` e os **bloqueios de admissibilidade** \`IntakeRules.REAL_GATES\` quando a metodologia indicada for VM0038, mas **não reutiliza regras sintéticas de elegibilidade, fórmulas ou recibos do piloto em projeto real**.

A futura integração do E8 completo terá que passar por gates ECA/REG, dados públicos admissíveis, comprovação de autoridade, controle de versões normativas e validação humana. Os módulos desabilitados são mostrados explicitamente pela API de capacidades.

## Jornada disponível

Após selecionar o rascunho em **Meus projetos**, abra **Preparar informações técnicas**. Cada seção é uma tela acessível pelo parâmetro de URL `secao`. A navegação entre telas preserva os dados ainda em edição.

| Tela | O que o usuário faz |
|---|---|
| Entender | Consulta o escopo da metodologia candidata, sua referência e os tópicos a preparar. |
| Preparar informações | Registra atividade, local, data ou observações. Quando a triagem indicou uma metodologia, a revisão técnica passa a referenciá-la ao salvar. |
| Analisar | Consulta requisitos a comprovar e os estados de preparação, evidências, decisão metodológica e revisão. |
| Redigir | Gera uma prévia de dossiê vinculada à revisão técnica salva e consulta as seções a desenvolver. |
| Revisar | Lê as pendências da prévia e confere a revisão técnica à qual ela pertence. |

A prévia registra estrutura, versão e metadados de referência. Gerar outra prévia conserva as revisões anteriores. A metodologia indicada e os dados técnicos permanecem sujeitos a análise.

## Contratos HTTP

Todos sob \`/v1/projects/{id}/technical\`, aceitando **somente cookie \`carbonflow_account_session\`**:

| Método | Rota relativa | Contrato |
|---|---|---|
| GET | \`/capabilities\` | Status das capacidades intake/evidence/calculation/monitoring/projectDescription/externalActs |
| GET | \`/intake\` | \`IntakeView\`, versão 0 se não iniciado, ETag \`"N"\` |
| PUT | \`/intake\` | \`IntakeData\` JSON, \`Idempotency-Key\` e \`If-Match\`; retorna revisão imutável e ETag |
| GET | \`/preliminary-dossier\` | 204 se inexistente; 200 se existente |
| POST | \`/preliminary-dossier\` | \`Idempotency-Key\` e \`If-Match\` da revisão de intake; cria snapshot da prévia (201) |

O \`IntakeData\` contém \`activity\` (até 400 caracteres), \`country\` (ISO alfa-2 opcional), \`state\`, \`locality\`, \`candidateMethodology\`, \`referenceActDate\` e \`notes\`; o salvamento exige pelo menos um campo preenchido, inclusive apenas observações. **Metodologia candidata não é metodologia autorizada.**

As revisões de intake e dossiê usam FKs por **(projeto, organização, ambiente)**; o acesso é checado pelo projeto criado pela identidade, membership ativo, DRAFT_OWNER e status SANDBOX/DRAFT. As operações de escrita adquirem lock de identidade, projeto e membership no mesmo banco; key/idempotência por ator preserva resultado de retry. Reprovação de acesso retorna 404 e grava \`app_project_technical_access_event\` com **digest opaco do alvo**, sem revelar existência do projeto; ausência de sessão retorna 401. Sem \`If-Match\`: 428; versão obsoleta: 412; chave reutilizada com outro comando: 409. Uma prévia para a mesma versão de intake não é duplicada por chave diferente.

## Escopos e limites

- **Habilitado:** \`intake=PREPARATORY_ONLY\`, \`projectDescription=PRELIMINARY_ONLY\`.
- **Não habilitado:** \`evidence\`, \`calculation\`, \`monitoring\`, \`externalActs\` (\`NOT_ENABLED\`).
- O estado do \`app_project\` permanece \`DRAFT\`, enquanto intake tem sua própria revisão, independente da versão de metadados.
- O piloto permanece em \`pilot_*\`, com hashes e fixtures imutáveis. Nenhum \`PilotAccess.Scope\` é fabricado e não há fallback \`PIL-SYN-BR-001\`.
- A prévia não é um PD/MR pronto e não pode ser submetida, exportada como documento válido ou tomada como comprovação de VVB/Verra.
- A classificação VM0038 pelo piloto exige inventário e IDs sintéticos; **não** é aplicada ao rascunho dinâmico.
- Futuros módulos devem reutilizar \`DynamicProjectScope\`, introduzindo habilitação por papel/competência ECA-003/CF-IMP-028; não substituir checks de autoridade por \`DRAFT_OWNER\`.

## Verificação CF-CT-018/019/020

- **018:** Conta cria projeto via POST /v1/projects, registra/revisita intake e gera prévia vinculada por FK a projeto, org e revisão.
- **019:** Identidade de outra organização ou outro titular, ainda que membro do mesmo workspace, recebe 404 e gera trilha negada; conta expirada/ausente 401; revogação tem efeito imediato; nenhum dado do piloto é escrito.
- **020:** Repetição concorrente com mesmo comando não duplica revisão, versões \`If-Match\` reprovam 428/412 quando inválidas, app_project não muda de versão, piloto continua intacto, e prévia não alega registro ou emissão.

Execute os testes em CI (PostgreSQL e Docker isolados) via GitHub Actions, sem usar volumes da demonstração. A etapa browser valida navegação, retorno ao projeto, formulário, preparação e viewport móvel. Evidência de 100% das entregas E8 ainda requer implementação dos adapters de evidências, cálculo, monitoramento, PD/MR formal e atos em fatias posteriores.

**Sequência de revisão:** mesclar primeiro o PR #57 (CF-IMP-007), depois revalidar/rebasear este PR; fechar a issue #16 apenas quando o escopo integral e os testes CF-CT-018..020 estiverem aceitos.
