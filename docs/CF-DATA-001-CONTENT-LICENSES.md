# CF-DATA-001 — Administração de licenças de fontes (API, rc.54)

**Nível D17:** plataforma (CF-IMP-029, D05, D18). Ambiente `SANDBOX`. Nenhum código de cálculo, regra, método ou migração de tabela alterados; a V107 só amplia a lista de ações de auditoria.

## Lacuna que fecha
As tabelas `app_content_license`, `app_content_license_decision` e `app_content_license_capability` (V37) e a leitura (`ContentLicensePolicy`, capacidade efetiva, fatias de fontes) já existiam, mas **não havia caminho de escrita**: licenças só entravam por SQL de teste. Esta fatia entrega a escrita administrativa, sem alterar a leitura.

## API (staff, MFA, rosters)
| Método | Rota | Quem | Efeito |
|---|---|---|---|
| GET | `/v1/platform/source-licenses` | submetente ou aprovador | lista as licenças da plataforma com a última decisão e as operações |
| POST | `/v1/platform/source-licenses` | submetente | registra autorização declarada (fonte e edição já registradas, finalidade, referência, SHA-256 do comprovante, início e fim); fica `PENDING`, sem operações |
| POST | `/v1/platform/source-licenses/{id}/decision` | aprovador | `APPROVED` (com operações) ou `DENIED`; `If-Match` = versão da decisão (0 sem decisão) |
| POST | `/v1/platform/source-licenses/{id}/revoke` | aprovador | decisão `REVOKED` (nova versão, sem operações); só de licença aprovada |

- **Privilégios:** `carbonflow.platform.content-licenses.submitters` e `.approvers`, listas de identidades de staff por configuração (como os demais recursos de staff). Vazio = indisponível (404). **Quem entra em cada lista é decisão de vocês; nada foi presumido.**
- **Dupla custódia:** quem submeteu não aprova (403 `DUPLA_CUSTODIA`), mesmo se estiver nas duas listas. A consulta de capacidade já exigia `submitted_by <> decided_by`; agora o servidor também recusa na escrita. A revogação pode ser feita por qualquer aprovador, inclusive o submetente, pois só fecha.
- **Final:** `APPROVED`, `DENIED` e `REVOKED` encerram a licença (409 `LICENCA_ENCERRADA`); para outra decisão, nova submissão. Concorrência por `If-Match` (428 sem, 412 desatualizado).
- **Efeito imediato:** a revogação cria a decisão mais recente, e todos os consumidores já leem a última decisão; as operações da versão aprovada permanecem no histórico.
- Toda operação grava auditoria (`VIEW_CONTENT_LICENSES`, `SUBMIT_CONTENT_LICENSE`, `DECIDE_CONTENT_LICENSE`); tentativa recusada reverte, sem evento.

## O que NÃO faz (limites explícitos)
- **O comprovante é declarado.** O servidor recebe o SHA-256 e **não verifica o documento nem guarda seus bytes**; não calcula o hash. A integridade do arquivo, o cofre do anexo e o upload protegido (CF-IMP-029) seguem pendentes.
- Só licenças de plataforma (sem organização, projeto ou ator). Licenças com escopo mantêm seus fluxos.
- A tela (rc.55) é só a interface da API; ela não conhece o papel de quem a usa (o servidor decide e responde 404/403).
- As tabelas de licença continuam sem gatilho de imutabilidade (fixtures de testes as limpam); a aplicação só acrescenta linhas.
- Uma licença aprovada **não habilita cálculo, admissão de dados nem atos externos**, e uma autorização de uma fonte não vale para outra.

## Testes
`ContentLicensesIntegrationTest`: 401/403/404 por perfil; validações; 428/412; dupla custódia; aprovação independente; decisão final; revogação com histórico preservado; negação; auditoria (7 eventos); limpeza das fixtures.

## Tela (rc.55)
`/workspace/platform/licenses` (item "Licenças" no menu administrativo). MFA, formulário de registro (fonte, edição, finalidade, referência, SHA-256, datas), lista com o estado derivado pelo servidor, "Decidir" (operações em caixas de seleção, motivo, aprovar ou negar) e "Revogar" em dois passos. Mostra que o comprovante é declarado e que a licença não habilita cálculo, admissão nem atos. Mensagens específicas para dupla custódia (403), versão desatualizada (412), decisão já encerrada (409) e perfil sem privilégio (404, sem formulário). A data escolhida vira o início do dia em UTC. Rota SPA registrada em `WorkspaceSpaRouteResource` e coberta por teste de integração.
Testes: `platform-content-licenses.test.mjs` (2 casos, Chromium real, API mockada) e `ContentLicensesIntegrationTest` (inclui o deep link).
