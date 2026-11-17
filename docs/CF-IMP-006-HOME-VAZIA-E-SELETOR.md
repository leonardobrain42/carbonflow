# CF-IMP-006 — Home vazia, seleção explícita e ponte para o primeiro projeto

**Fatia:** Angular + Quarkus. **Branch:** `feat/empty-workspace-project-selector`. **Issue:** #12. **Dependências:** CF-IMP-003, CF-IMP-004, CF-IMP-005. **Próxima:** CF-IMP-007 (#14).

## Escopo entregue

1. Nova rota `/workspace` com sessão de **conta sandbox** via `GET /v1/account-session`. Credenciais fictícias `SIM-*` continuam em `/pilot` com cookie separado. No login de conta, usa email/senha reais da caixa sandbox habilitada, não inventa credenciais.
2. **Zero projetos:** mostra mensagem acionável, sem tentar acessar `projects[0]`. Botão **Criar meu primeiro projeto** abre `/workspace/projects/new`.
3. **Preparação do primeiro projeto:** se a conta não possui espaço inicial, usa o backend existente `POST /v1/organizations` com `Idempotency-Key`, criando organização `PRIVATE_DRAFT` e vínculo `DRAFT_OWNER` sem PP/ORG_ACCESS_ADMIN. Se já possui, exibe o espaço existente.
4. **CF-IMP-007 ainda pendente:** a tela não cria projeto, não fabrica ID, nem afirma que há metodologia selecionada. Exibe próximo passo explicitamente indisponível até implementação da criação persistente.
5. **Lista e seleção:** caso o backend entregue `projects`, opções e detalhes vêm apenas de `GET /v1/account-session`; nenhum projeto é selecionado automaticamente. Troca e atualização validam ID na lista atual e apagam seleção perdida/revogada.
6. **API de organizações:** `GET /v1/organizations` retorna só vínculos DRAFT_OWNER atuais, autorizados pelo cookie `carbonflow_account_session`, negando sessões de piloto e ocultando organizações revogadas ou de outros tenants.
7. Rotas `/workspace` e `/workspace/projects/new` servem a SPA para carregamento direto; API permanece protegida no Quarkus. Frontend não reaproveita o motor E8 para projetos dinâmicos antes da CF-IMP-008.
8. Tema escuro consistente com o shell; foco de teclado, rotas, rótulos e largura móvel 390px.

## Contratos

| Endpoint | Sessão | Resultado |
|---|---|---|
| `GET /v1/account-session` | Conta, cookie separado | `{identity,projects}` autorizados (existente) |
| `POST /v1/account-session` | Credenciais de conta | Cookie HttpOnly de conta (existente) |
| `DELETE /v1/account-session` | Conta | Invalida cookie (existente) |
| `GET /v1/organizations` | Conta válida | Lista `Workspace` ativa por identidade, 401 sem conta |
| `POST /v1/organizations` | Conta válida + Idempotency-Key | Organizaçao privada, DRAFT_OWNER (existente) |

## Testes

- JUnit `AccountAccessTest`: lista vazia inicial, piloto → 401, lista do titular, isolamento de outro usuário, revogação → lista vazia.
- Playwright `workspace.test.mjs`: tela vazia → preparação → criar organização → atualização; seleção de projeto explícita → lista alterada remove seleção; login/logout próprios da conta e separação SIM; responsividade.
- CI executa `npm ci`, build Angular, `mvn clean verify` PostgreSQL descartável e browser E2E com Compose isolado.

## Limites, riscos e aceites pendentes

- Criação efetiva/edição de projetos e vínculo no banco: **CF-IMP-007**.
- Listagem multi-org e concessões amplas: futuras issues; o MVP mantém um espaço inicial por conta sandbox e acesso DRAFT_OWNER.
- Guard de frontend é conveniência; **o servidor** continua decidindo as permissões para cada recurso.
- Administração de contas/cadastro front-end e-mail/serviço real está fora deste escopo.
- A CI valida funcionalmente a UI. Comparação visual 1440×900/390×844 com referência aprovada é homologação humana complementar.

## Testar manualmente em ambiente descartável

Após configurar `CARBONFLOW_ACCOUNTS_SANDBOX_INBOX` para a pasta privada e um usuário verificado, iniciar:

```sh
CARBONFLOW_PORT=18087 docker compose -p cf-imp-006-test up --build -d --wait
```

Acessar `http://127.0.0.1:18087/workspace` e autenticar com e-mail/senha da conta sandbox verificada. O piloto fica em `/pilot`.

## Extensão: sandbox local provisionado

A conta de demonstração pode ser criada com `python3 scripts/demo_sandbox.py start`. A senha é aleatória e privada, não há uma credencial padrão no código; o script realiza cadastro e confirmação reais e permite criar contas adicionais `.test`. A aplicação abre em `http://127.0.0.1:18088/workspace`, isolada do Docker Compose padrão. Testes adicionais incluem um job de inicialização/reencontro de contas, sem mock de autenticação. Consulte [CF-IMP-006-DEMO-SANDBOX.md](CF-IMP-006-DEMO-SANDBOX.md).
