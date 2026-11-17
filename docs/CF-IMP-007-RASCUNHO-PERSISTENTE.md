# CF-IMP-007 — Projetos próprios em rascunho (SANDBOX)

**Fatia:** criação, consulta e edição versionadas de metadados de projeto com sessão de conta. **Não implementa** triagem CF-IMP-009, prontidão metodológica CF-IMP-014, baseline/quantificação, cadastro de papéis PP ou submissão VCS.

## Jornada do usuário

1. Entrar em `/workspace` com uma conta verificada no sandbox.
2. Na home vazia, escolher **Criar meu primeiro projeto**. Caso ainda não haja organização, criar seu espaço inicial (CF-IMP-004).
3. Informar organização, nome e descrição opcional. Salvar rascunho e receber UUID próprio, status `DRAFT`, ambiente `SANDBOX` e versão `1`.
4. Voltar à home, selecionar explicitamente o novo projeto e consultar/editar seus metadados; nunca selecionar o projeto piloto automaticamente.
5. Retomar a edição posteriormente. A futura jornada CF-IMP-009 continuará a partir dessa entidade sem converter o criador em representante legal ou titular de poderes PP.

## Contrato HTTP

| Método | Rota | Entrada/saída | Segurança |
|---|---|---|---|
| POST | `/v1/projects` | `{organizationId, title, description?}` → 201 `Metadata`, Location, ETag | cookie `carbonflow_account_session` e `Idempotency-Key`; vínculo `DRAFT_OWNER` ativo na org |
| GET | `/v1/projects/{id}/metadata` | 200 `Metadata`, ETag | titular original do rascunho e vínculo ativo com a organização |
| PATCH | `/v1/projects/{id}/metadata` | `{title?, description?}` → 200 `Metadata`, ETag | `Idempotency-Key`, `If-Match: "N"` e mesma autorização |

`Metadata = {projectId, organizationId, environment, state, version, title, description}`. POST sempre gera ID UUID distinto de `PIL-SYN-BR-001`; não há conversão implícita de projetos de simulação. `GET /v1/projects/{id}` da E8-003 **não** é modificado: os metadados do projeto de conta usam a rota `/metadata`.

### Estados de resposta

- 400: título, descrição, chave ou corpo inválidos.
- 401: sessão de conta ausente/expirada; cookie SIM-* não é aceito.
- 404: organização/projeto não visível no vínculo atual, inclusive depois de revogação.
- 409: reuso de Idempotency-Key com operação ou conteúdo diferente.
- 428: PATCH sem If-Match.
- 412: versão de edição obsoleta (a operação pode ser repetida com a **mesma** chave, se já tiver sido concluída antes).

`app_project_command` conserva o resultado original de cada comando para replay determinístico; `app_project_event` associa autor, versão e operação. Transação grava rascunho/mutação, evento e receipt ou não grava nenhum. Chave é isolada por `identity_id` e não cria um novo projeto em reenvios simultâneos. Títulos são normalizados, com 1–160 caracteres; descrição opcional tem até 2.000 caracteres. Atualizações incrementam a versão.

## Invariantes

- `DRAFT_OWNER` só edita rascunhos que criou em organização vinculada; não é PP, PREP ou VVB.
- Conta, organização, projeto e ambiente são reavaliados no servidor; as opções do frontend não autorizam.
- A sessão de conta não herda a sessão `SIM-*` do piloto; o banco `pilot_*` é preservado.
- A UI deixa explícito que ainda não há cálculo/ER/VCU ou validação externa.
- Dados não podem ser compartilhados entre organizações e agentes, mesmo quando alguém pertence à mesma organização.
- A edição retorna ETag para prevenir atualizações perdidas e uso de estado obsoleto.

## Verificação

O PR inclui `ProjectDraftResourceTest` (Quarkus + PostgreSQL, contrato HTTP, replay concorrente, idempotência, isolamento de titular, revogação, ausência de sessão e controle de versões) e `frontend/test/workspace.test.mjs` (navegação, formulários, retorno após salvar, edição, ausência de créditos e viewport móvel).

Em ambiente isolado, executar:

```sh
npm --prefix frontend ci
npm --prefix frontend run build
cd backend && ./mvnw -B -ntp verify
```

As execuções da CI usarão banco PostgreSQL descartável; não executar sobre volume da demonstração principal. A integração browser usa o fluxo `npm test` após subir a aplicação como definido em `.github/workflows/ci.yml`.

**Limites assumidos:** a persistência foi introduzida na V16, de modo aditivo. A tela de triagem e os módulos técnicos para projeto dinâmico são entregas futuras (#17 e #16). Criar um rascunho não implica que o mesmo esteja elegível para projetos de carbono ou que tenha reduções contabilizadas.
