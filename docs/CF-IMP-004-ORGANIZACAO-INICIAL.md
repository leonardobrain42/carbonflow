# CF-IMP-004 · Organização inicial privada

A conta de sandbox com e-mail confirmado pode criar seu primeiro espaço. A V15 registra a identidade da conta, a organização inicial, a chave do comando, o hash do pedido e o evento de criação. Organização `PRIVATE_DRAFT`, vínculo `DRAFT_OWNER` e evento são gravados na mesma transação.

## Contrato HTTP

| Operação | Guarda e resultado |
|---|---|
| `POST /v1/organizations` | Sessão de conta válida, `Idempotency-Key` e JSON `{"name":"Nome de trabalho"}`. Retorna `201`, `Location` e ETag `"1"`. |
| `GET /v1/organizations/{id}/access` | Sessão válida e vínculo vigente com a organização. Retorna `200` com a organização e o papel no espaço, mais sua ETag. |

A resposta contém `id`, `name`, `environment`, `status`, `version` e `role`. O backend usa `SANDBOX`, `PRIVATE_DRAFT` e `DRAFT_OWNER` no bootstrap. O nome é de trabalho e não comprova representação. O papel inicial permite acessar o próprio espaço privado. Planejamento de colaboração, verificação de poderes e convites ativos são entregas de CF-IMP-012/028/013. CF-IMP-007 cria os projetos no espaço autorizado.

- A identidade e o ambiente vêm da sessão e do núcleo. A sessão de demonstração legado e a de conta usam cookies distintos. O filtro existente valida a origem das mutações.
- A criação bloqueia a conta, revalida a sessão e serializa o primeiro espaço por conta. O recibo possui unicidade por identidade. A mesma chave e o mesmo nome normalizado devolvem a resposta original enquanto o vínculo continuar autorizado.
- A mesma chave com outro nome retorna `409 CHAVE_REUSADA`. Outra chave após a criação retorna `409 ORGANIZACAO_INICIAL_EXISTENTE`. Sessão ausente ou inválida retorna `401` e organização fora do escopo ou vínculo revogado retorna `404` nas consultas e no replay.
- O limite de um espaço inicial privado é por conta de sandbox. Ele não altera o envelope de organizações operadoras do piloto nem libera operação real.

## Verificação

Os casos CF-CT-009/010 são exercitados por HTTP em `AccountAccessTest`. Cobrem conta verificada com zero projetos, cookie do piloto, tentativa de indicar poderes no JSON, reenvio concorrente, chaves concorrentes, conflito de conteúdo, isolamento entre organizações, revogação e rollback com falha injetada no insert de auditoria. Após remover a falha, o mesmo comando cria o espaço com sucesso.

O ensaio isolado aplicou V1–V15 em PostgreSQL 16.16. Passaram 33 testes Java e 12 jornadas no Chrome, incluindo os quatro recortes e o oráculo M1 = 6,00/M2 = 5,30 tCO₂e de `PIL-SYN-BR-001`. Os hashes históricos E8 foram conferidos. O build Angular reaproveitou o cache do Compose.

O ensaio de backup e restauração do banco de teste recuperou os três eventos de bootstrap, suas organizações e vínculos, incluindo vínculos revogados no conjunto de testes. A cópia restaurada ficou com zero sessões de conta.

Para reproduzir, habilite a caixa de sandbox conforme [CF-IMP-003](CF-IMP-003-CADASTRO-E-SESSAO.md) ou rode `./mvnw -B clean verify` com `CARBONFLOW_TEST_DB_URL` apontando para um banco descartável. A suíte prepara uma caixa temporária. Suba a aplicação em um Compose isolado e execute `node --test test/*.test.mjs` a partir de `frontend/` com `CARBONFLOW_TEST_URL` na porta do ensaio.
