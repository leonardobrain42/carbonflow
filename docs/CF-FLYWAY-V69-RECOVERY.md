# Hotfix Flyway V69: preservar bancos locais e arquivos de evidência

## Sintoma

Ao iniciar o CarbonFlow local com um volume PostgreSQL já existente:

```text
Migration checksum mismatch for migration version 69
Applied to database : -1778044537
Resolved locally    : -639127919
```

A V69 inicial de UX-03B foi executada no banco com checksum `-1778044537` (commit `a212072`). Depois, a PR #141 incluiu a coluna `submitted_file_name` **dentro da própria V69**, alterando seu checksum para `-639127919` (commit `bb0f89c`). Flyway corretamente bloqueou o boot por diferença de histórico.

## Correção

- Restaurar `V69__open_asset_evidence_links.sql` **byte a byte** para o conteúdo inicialmente aplicado; seu checksum Flyway deve voltar a `-1778044537`.
- Criar `V69_1__open_asset_evidence_submitted_filename.sql` (versão Flyway 69.1), posterior à 69 e anterior à V70 da UX-07. A migração adiciona a coluna nova e preenche quaisquer vínculos já gravados com `app_project_evidence_blob.file_name`, cujo acesso está condicionado ao par `project_id/evidence_blob_id` e ao tipo `application/pdf`. Por fim aplica `NOT NULL` e a mesma validação de nome PDF.
- Arquivos, chaves, blobs criptografados, revisões e vínculos de projetos permanecem intactos. A V70 pode ser aplicada depois sem `outOfOrder`.
- **Limitação histórica:** se houver mais de um vínculo de uma mesma deduplicação PDF com nomes diferentes, o banco V69 antigo não registrou o nome individual; para esses vínculos o único nome recuperável é o nome do blob. Isso não muda o hash nem os bytes do PDF.

## Recuperação local (sem operações destrutivas)

1. Faça merge do hotfix e atualize a cópia local do repositório na branch correspondente.
2. Do diretório raiz, execute `python3 scripts/demo_sandbox.py start` para reconstruir e iniciar o sandbox, ou `python3 scripts/admin_sandbox.py setup` se estiver provisionando o staff administrativo.
3. Consulte o estado com `python3 scripts/demo_sandbox.py doctor` caso a CLI de UX-07 esteja disponível, e abra `http://127.0.0.1:18088/workspace`.

Para inspecionar o histórico Flyway sem modificá-lo (com o PostgreSQL em execução):

```bash
docker compose -p carbonflow-demo -f compose.yaml -f compose.demo.yaml exec -T db \
  psql -U carbonflow -d carbonflow -c \
  "SELECT installed_rank,version,description,checksum,success FROM flyway_schema_history WHERE version IN ('69','69.1','70') ORDER BY installed_rank;"
```

**Não use** `docker compose down -v`, `flyway repair` indiscriminado, nem atualize diretamente `flyway_schema_history`. Isso não corrigiria a divergência física do esquema e pode impedir auditoria.

Se algum outro banco já executou a V69 modificada (`-639127919`), ele é um caso de recuperação diferente: o campo já existe e a coluna de histórico corresponderá ao arquivo antigo; **não aplique este hotfix cegamente** nesse banco. Antes de qualquer ajuste de checksum, examine `flyway_schema_history` e a existência/constraints da coluna para planejar uma reconciliação controlada.

## Testes

`scripts/test_flyway_v69_compat.py` verifica checksum do arquivo original e presença do upgrade versionado e de suas garantias de integridade. A CI Quarkus/PostgreSQL valida a instalação de V69 e V69.1 em um banco limpo. O cenário de atualização do volume já existente deve ser homologado sem exclusão de dados.
