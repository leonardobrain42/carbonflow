# CF-IMP-002 · Migração e compatibilidade com o piloto

**17/11/2026 · PASS.** Mudança limitada ao ADR M1 aprovado. A V13 cria quatro tabelas sem seeds, credenciais ou registros técnicos. `SANDBOX` é o único ambiente aceito pelo esquema de cadastro desta fatia. O autor tem FK composta para o vínculo da própria organização, que é preservado após revogação. Projetos novos usam UUID serializado canonicamente como `text`. IDs `PIL-*` e `SIM-*` continuam na origem legada.

`ProjectRepository` consulta cada origem por um caminho explícito. O piloto continua a ser autorizado por `PilotAccess`, com o mesmo `Scope[]` e as mesmas políticas de artefatos. A consulta interna de rascunhos exige proprietário, organização, vínculo válido e conta ativa. Ela devolve somente metadados de contexto. O cadastro, autenticação de conta e gravação de projetos chegam em 003/004/007.

## Evidências

| Caso | Ensaio | Resultado |
|---|---|---|
| CF-CT-003 | Banco vazio e duas restaurações de V12 populada pela suíte E8 a partir de `61b472b`. | V13 aplicada com checksum Flyway `611875189`. Checksums V1–V12 iguais. Comparação de inventário por SHA-256 preservou as 50 tabelas E8 conferidas (sessões transitórias omitidas), com 188 objetos, incluindo 7 fechamentos, 11 memórias, 82 artefatos e 54 manifestos. Preservados 10 grants, 1.075 eventos de acesso e 589 chaves de comando. As quatro tabelas novas começaram vazias em ambas as cópias. |
| CF-CT-003 | No segundo clone, criar deliberadamente `app_project` antes do boot para falhar no quarto `CREATE TABLE`. | Flyway reverteu as criações anteriores da V13 e manteve o histórico em V12. Ao remover a tabela deliberada, o inventário voltou a coincidir byte a byte e o mesmo artefato aplicou V13 normalmente. |
| CF-CT-004 | Duas origens na mesma base de testes Quarkus. | Conta de teste com zero projetos. Rascunho visível apenas para o proprietário, no escopo e vigência corretos. Conta desabilitada, colega da organização e vínculo revogado/expirado/futuro recusados. Tentativas de duplicar ID, usar ID piloto ou violar ambiente ou vínculo deram erro de banco. Sessão do piloto recebeu 404 ao abrir o ID do rascunho pela rota técnica. |
| CF-CT-004 | `./mvnw clean verify` e `node --test test/*.test.mjs` no Compose V13. | 28/28 testes Java, 12/12 no Chrome. Cobertos 401/403/404, escopo, artefatos por versão e revogação, autoaprovação, 412/428, replay, 422 sem EC e os quatro recortes com M1=6,00/M2=5,30 tCO₂e e M2 coletando durante V1/I1. O build Angular reaproveitou cache; nenhum fonte Angular mudou. |
| CF-CT-005 | Subir binário V12 sobre banco V13 populado. | Binário V12 original recusado na validação Flyway (`Detected applied migration not resolved locally: 13`). Imagem com código V12 e arquivo V13 idêntico iniciou em 4 s, respondeu 204, autenticou o piloto e devolveu o mesmo diretório JSON. A V13 continuou aplicada. |

## Ambiente e reprodução

- Docker Engine 29.2.1, Compose 5.0.2, PostgreSQL 16.16, Java 21, Maven Wrapper 3.9.12 e Chrome 157. Para preparar a fonte, execute `docker compose -p cf-imp-002-v12 -f "$V12_SRC/compose.yaml" up --build -d --wait` com `V12_SRC` extraído via `git archive 61b472b`. Execute a suíte Java apontada explicitamente para o banco descartável. Desligue o app antes da cópia para estabilizar o inventário.
- Inclua `backend/src/test/` num estágio temporário baseado em `quarkus-build` do [Dockerfile](../Dockerfile), execute `./mvnw -B clean verify` com `CARBONFLOW_TEST_DB_URL=jdbc:postgresql://db:5432/carbonflow_test_final` numa rede Compose exclusiva e crie esse banco antes. Suba `CARBONFLOW_PORT=0 docker compose -p cf-imp-002-v13 up --build -d --wait` e use a porta retornada por `docker compose -p cf-imp-002-v13 port app 8080` em `CARBONFLOW_TEST_URL` para o teste do navegador.
- No PostgreSQL de origem, `pg_dump -Fc --no-owner --exclude-table-data=public.pilot_session`. Restaure com `pg_restore --no-owner --single-transaction --exit-on-error` em dois bancos vazios do Compose destino. Envolva [inventory.sql](../scripts/recovery/inventory.sql) em uma transação de leitura com isolamento repeatable read; compare as linhas E8 e valide o histórico Flyway à parte. Não houve perda no ponto do dump com a origem parada. Prontidão após boot de V13 em 2 s no clone limpo e 3 s após remover a falha, medições locais de ensaio, sem meta de homologação.
- A imagem de rollback foi construída a partir de `git archive 61b472b` com apenas a [V13](../backend/src/main/resources/db/migration/V13__workspace_identity_and_projects.sql) copiada para as migrations.

## Rollback operacional

Antes de implantar V13, prepare e valide a imagem de rollback com código V12 mais V13 incluída, reserve a imagem candidata e tire cópia com inventário e hashes. Em falha da aplicação, volte para a imagem preparada e mantenha V13 e seus dados inativos. Se banco ou inventário divergirem, restaure a cópia em destino isolado e concilie efeitos posteriores conforme E8-011 antes de redirecionar. Após o núcleo receber gravações, inclua essas tabelas no inventário e na conciliação. E8-013/014 e PEN aplicáveis continuam como gates de uso real.
