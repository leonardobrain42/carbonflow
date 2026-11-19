# CF-OPEN-002 — Console operacional de fontes (primeira fatia)

## Escopo
Admin CarbonFlow → `/workspace/platform/sources` utiliza o inventário PostgreSQL de #90 e acrescenta uma **fila real de solicitações de aquisição de fontes**. Esse módulo organiza a ação humana necessária para uma fonte ausente; **não** armazena ou processa bytes, não transforma fontes em admitidas para cálculo, não emite aprovação Verra/VVB e não altera projetos de clientes.

## Arquitetura e contratos
- `V44__platform_source_workflow.sql` adiciona `app_platform_source_work` e `app_platform_source_work_revision`. Cada necessidade central possui uma solicitação ativa por `need_code`, com versões imutáveis e auditoria de autor, responsável e justificativa.
- `GET /v1/platform/source-work?methodology&edition&scenario`: retorna `canManage`, demandas registradas e impacto por capacidade (número de requisitos catalogados, condicionais, evidências por projeto e solicitações abertas). Esses números NÃO são cálculos de reduções.
- `GET /v1/platform/source-work/assignees`: retorna apenas identidades ativas cadastradas como gestores de fontes; só gestores podem chamar.
- `GET /v1/platform/source-work/{needCode}`: traz estado e histórico imutável.
- `PUT /v1/platform/source-work/{needCode}` body `{note,ownerId}`: registra solicitação com versão 1; repetir o PUT é **idempotente**, não sobrescreve a justificativa original.
- `POST /v1/platform/source-work/{needCode}/revisions` com header `If-Match: 1` e body `{status,ownerId,note}`: revisa com controle de concorrência. Atualização obsoleta → 409, ausência de versão → 428, transição inválida → 422.
- Cache: `private,no-store`; eventos `VIEW_PLATFORM_SOURCE_WORK`, `CREATE_PLATFORM_SOURCE_WORK`, `UPDATE_PLATFORM_SOURCE_WORK`, `VIEW_PLATFORM_SOURCE_WORK_HISTORY` autorizados pela constraint V44.
- SQL e REST implementados em `PlatformSourceWork` / `PlatformSourceWorkResource`. O serviço reutiliza o gate de VIEW_SOURCE_READINESS do #90 e o MFA; não expõe texto normativo nem arquivos privados.

## Permissões de implantação
Além de `carbonflow.compliance.staff` (identidades staff com TOTP) e
`carbonflow.platform.source-readiness.viewers`, configurar
`carbonflow.platform.source-work.managers` (equivalente `CARBONFLOW_PLATFORM_SOURCE_WORK_MANAGERS`) com UUIDs da equipe interna autorizada a gerir demandas.

- Sem esta terceira configuração: a área pode ser consultada por viewers, mas **ninguém pode criar ou revisar demandas** (fail-closed).
- Apenas identidades simultaneamente autorizadas a visualizar e a gerir conseguem usar PUT e POST.
- Administrador técnico de fontes não recebe automaticamente autoridade de PP/REV ou acesso aos documentos do cliente.
- Proprietários do projeto e operadores não entram no painel por project grants.
- Assignees válidos: somente gestores ativos e verificados. Não há convite externo neste módulo.

## Estados de trabalho (não confundíveis com admissão de fonte)
- `REQUESTED` — solicitação de aquisição criada;
- `SOURCING` — investigação da origem;
- `WAITING_RIGHTS` — acesso/direitos ainda precisam ser esclarecidos;
- `READY_FOR_HANDOFF` — informação da solicitação pode ser encaminhada ao pipeline #92; **não** significa importação ou aprovação;
- `ON_HOLD` — impedimento ou espera documentados.

Nenhum desses estados modifica `app_source_version`, `app_content_license`, `app_norm_pack`, `technicalAdmissionConfirmed` ou snapshots E8. O estado do inventário permanece bloqueado até que #92 integre importação, direitos e avaliação técnica reais.

## Navegação e UX
A sidebar continua estável. No painel há oito abas: Visão geral, Documentos normativos, Bases de dados, Documentos do projeto, Admissibilidade, Alimentação e tarefas, Histórico e Impactos na jornada. As abas consomem os dados já retornados pelos endpoints existentes; a operação utiliza `PlatformSourceOperationsComponent`.
- Responsabilidade por projeto (PP/OP) aparece separada de fontes centrais CarbonFlow; não é possível abrir solicitação central para evidência privada.
- Quem possui VIEW sem MANAGE apenas lê os estados e histórico.
- Botões de origem oficial só abrem endereços explícitos do catálogo. Ainda não existem comandos de upload, aprovação, decisão jurídica ou publicação; **não** renderizar botões inertes ou fingir execução.
- Loading/erros, status de conflito, mobile e foco por teclado são tratados pelo componente.

## Testes
- JUnit Quarkus/PostgreSQL: `PlatformSourceWorkIntegrationTest`, `PlatformSourceWork.allowed`; acesso viewer vs manager, criação idempotente, atribuição válida, rejeição de escopo do projeto, transições, 409/428 e histórico.
- Playwright/Node: `platform-source-operations.test.mjs` navega via MFA, abre solicitação e consulta revisões/impactos no viewport 390×844.
- Executar a CI e só marcar esta primeira fatia como entregue após os jobs concluírem com sucesso.

## Dependências posteriores
**#92**: importação/coletores, armazenamento licenciado, admissibilidade por uso, versionamento de dados e publicação. **#90**: fonte/edição/necessidade dinâmica. **#94–#98**: efetiva prontidão de cálculo. **#100**: assistência IA autorizada. Reavaliar se #91 deve permanecer aberta até a integração das operações de importação/revisão e impactos downstream efetivos (conforme seus critérios originais).
