# CF-IMP-003 · Cadastro e sessão de conta no sandbox

A V14 acrescenta credenciais, desafios, sessões, limitação de tentativas e auditoria para as identidades do núcleo. As contas começam em `PENDING_VERIFICATION`. Após confirmar o endereço e escolher uma senha, a conta pode entrar com seu próprio cookie. Uma conta nova recebe `projects: []`. A sessão apenas projeta rascunhos já autorizados pelo repositório de projetos. Organização inicial e criação de rascunhos chegam em CF-IMP-004 e CF-IMP-007.

## Operação de teste

O cadastro fica desabilitado por padrão. Para um ambiente de teste autorizado, configure `CARBONFLOW_ACCOUNTS_SANDBOX_INBOX` com um diretório privado, gravável pelo UID da aplicação e com modo `0700`. O serviço grava mensagens JSON locais com destinatário, finalidade, token e expiração. A caixa tem acesso de operador e deve ficar fora do repositório e de diretórios publicados por HTTP. Use endereços fictícios e remova as mensagens ao encerrar o ensaio. O serviço usa exclusivamente a caixa local e opera sem provedor de e-mail externo.

O primeiro pedido recebe nome e e-mail. A senha é definida junto com a prova de acesso à caixa no passo de confirmação. Assim, uma pessoa que iniciou o cadastro para um endereço alheio não pode pré-definir a credencial da futura conta. O armazenamento usa PBKDF2-HMAC-SHA-256 com salt aleatório e 600 mil iterações para contas novas. Desafios de uso único expiram em 20 minutos. Um novo pedido com outra chave invalida o desafio anterior da mesma finalidade. O banco guarda os hashes de tokens. A senha e os tokens não entram no log de auditoria.

| Método e rota | Dados e resultado |
|---|---|
| `POST /v1/accounts` | `{email,name}`. Retorna `202` e solicita a confirmação, inclusive quando a conta já existe. |
| `POST /v1/accounts/email-verification` | `{email}`. Retorna `202` e, para conta pendente, gera um desafio novo. |
| `POST /v1/accounts/email-confirmations` | `{token,password}`. Retorna `204` ao confirmar endereço e definir a senha. |
| `POST /v1/accounts/password-recovery` | `{email}`. Retorna `202` para endereços válidos, com desafio gerado para conta ativa. |
| `POST /v1/accounts/password-resets` | `{token,password}`. Retorna `204`, troca a senha e invalida as sessões de conta existentes. |
| `POST /v1/account-session` | `{email,password}`. Retorna `204` com cookie `carbonflow_account_session`, `HttpOnly`, `SameSite=Strict` e `Secure` em HTTPS. |
| `GET /v1/account-session` | Retorna `{identity,projects}`. A consulta exige cookie de conta e valida conta, expiração e escopo do diretório no servidor. |
| `DELETE /v1/account-session` | Invalida a sessão e limpa o cookie. Retorna `204` em reenvio. |

Use `Content-Type: application/json` nos POSTs e `Idempotency-Key` em todas as rotas sob `/v1/accounts`. A chave de emissão é vinculada à conta e à finalidade. Reenvio de conclusão com o mesmo token, chave e conteúdo devolve o resultado original. Usar a chave de conclusão com senha diferente retorna 409. Token vencido, substituído ou reapresentado com outra chave retorna 422. Login e logout mantêm as semânticas de transporte de sessão. As mutações usam o filtro de origem E8.

**Limites de sandbox.** A janela é de cinco minutos, com até 30 tentativas por origem de rede e operação e até oito por endereço/conta e operação quando o endereço for informado. O servidor usa o endereço da conexão. O cadastro aceita e-mail ASCII normalizado e senha de 12 a 128 caracteres medida em unidades UTF-16. Sessões duram oito horas. O limite provisório DEC-011 de duas sessões de conta simultâneas é aplicado à instância do sandbox de forma atômica. As sessões fictícias do piloto ficam no seu próprio espaço. Ao introduzir organizações, revisar a unidade de contagem com CF-DEC-03.

A auditoria de conta registra cadastro, emissão de desafio, confirmação, login, logout e troca de senha por identidade, com horário. Recuperação usa o mesmo controle de caixa da confirmação. O backup inclui identidade, credencial e auditoria. No destino restaurado é necessário autenticar e solicitar novamente desafios em andamento, pois essas credenciais transitórias são excluídas da cópia. Habilitar cadastro em uso real ainda depende da decisão de provedor, políticas de privacidade e abuso e dos gates de liberação. O contrato `/v1/session` e as credenciais `SIM-*` seguem disponíveis na demonstração original.

## Testes

- CF-CT-006 valida por HTTP cadastro, confirmação, reenvio, workspace vazio, cookie e logout.
- CF-CT-007 cobre separação dos cookies e o login legado `SIM-PREP`. A suíte E8 verifica o diretório e as ações existentes.
- CF-CT-008 cobre origem inválida, rate limit, concorrência de sessões, expiração, consumo único, recuperação, revogação e replay.

O ensaio usou Docker Compose 5.0.2, PostgreSQL 16.16, Java 21 e Chrome 157. O build aplicou V1–V14 a um banco vazio. Passaram 31 testes Java e 12 jornadas de regressão no Chrome, incluindo os quatro recortes, os totais sintéticos M1/M2 de 6,00 e 5,30 tCO₂e e o bloqueio sem EC. A caixa local do teste HTTP foi montada em memória para o contêiner do sandbox. Conferidos 404 sem configuração, cadastro com caixa habilitada, envio local, confirmação, sessão vazia, 401 ao acessar a API do piloto com cookie de conta e logout. O backup oficial foi restaurado em outro banco de ensaio e preservou uma identidade, uma credencial e cinco eventos, com zero sessões e desafios nas tabelas transitórias.

Para repetir as regressões, suba `CARBONFLOW_PORT=0 docker compose -p cf-imp-003-test up --build -d --wait`, aponte `CARBONFLOW_TEST_URL` à porta publicada e rode `node --test test/*.test.mjs` em `frontend/`. Execute `./mvnw -B clean verify` com `CARBONFLOW_TEST_DB_URL` apontando para outro banco descartável. A suíte de conta habilita e remove sua própria caixa local. A issue entrega API e persistência. O shell e as telas são acompanhados em issues próprias.
