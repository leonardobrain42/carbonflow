# Sandbox persistente: Flyway V71 / V69.1

O banco local registrou **V71 de filename**, checksum `167374965`, com o script
`V71__asset_evidence_submitted_filename_backfill.sql`. O arquivo V71 atual do
GitHub é **outro**: `V71__open_e8_project_run.sql`, checksum `-1979807539`.
A V71 local criou/ajustou nome de PDF; **não criou as tabelas E8**.
Não basta modificar o checksum: isso registraria falsamente uma execução E8.

O repositório não contém o SQL exato que foi aplicado pelo usuário sob essa
V71 antiga. Há duas situações históricas distintas no mecanismo de recuperação:

- **Caso A — V71 E8 já foi aplicada com checksum legado:** reconciliar somente
  checksum se a estrutura E8 completa existir e for comprovadamente compatível.
- **Caso B — V71 era filename (caso real reportado):** exigir `version=71`,
  `description='asset evidence submitted filename backfill'`, script histórico
  exato, checksum `167374965`, V69 original válida, V70 válida e V69.1 ausente.
  Verificar `submitted_file_name text NOT NULL`, CHECK do PDF, trigger de
  imutabilidade ligado, registros vinculados ao blob PDF válido e ausência
  das tabelas E8. Se houver divergência, **recusar**.

No caso B, após backup e `--apply`, a ferramenta preserva no recibo privado os
metadados Flyway anteriores e reconcilia **apenas a linha de histórico legada**
para `version=69.1`, descrição/script/checksum da migração atual cujo
**resultado no esquema foi verificado**. A V71 do E8 fica **pendente** e é
executada normalmente pelo Flyway no próximo boot. O script não faz `CREATE TABLE`
por fora, não reexecuta a migration de filename já realizada, não altera
projetos, evidências nem credenciais. É uma **reclassificação explícita do
histórico**, não uma afirmação de que o SQL histórico desconhecido era idêntico.
Este procedimento é restrito ao sandbox demo e NÃO é apropriado sem análise
adicional em produção.

Portanto NÃO desligar a validação, NÃO aplicar repair genérico e NÃO executar
`down -v`.

A V69.1 foi introduzida tardiamente após alguns ambientes terem executado
a V70 ou V71. O compose.demo.yaml habilita somente nesse demo a opção oficial
do Quarkus QUARKUS_FLYWAY_OUT_OF_ORDER=true, conservando
validate-on-migrate=true e repair-at-start=false. Outros ambientes conservam
o padrão out-of-order=false. A V69.1 tem backfill e validação fail-closed.

## Procedimento preservando dados

1. Faça backup do banco PostgreSQL/volume de demonstração ANTES de modificar metadados.
2. Na raiz do repositório, atualize o código e execute somente o banco:
       docker compose -p carbonflow-demo -f compose.yaml -f compose.demo.yaml up -d --wait db
3. Execute diagnóstico read-only:
       python3 scripts/demo_flyway_compat.py
4. Se disser que a estrutura é compatível, após backup faça opt-in explícito:
       python3 scripts/demo_flyway_compat.py --apply
5. Agora inicie a aplicação:
       python3 scripts/demo_sandbox.py start

O verificador identifica o caso A ou B pelo nome do arquivo aplicado,
descrição, checksum, tabelas e consequências estruturais da migração. Em ambos
os casos, `--apply` grava uma inspeção privada com permissões 0600 em
`.carbonflow-demo` antes de qualquer alteração no histórico Flyway.
Se o estado do banco não corresponder estritamente a um desses cenários, o
script para e apresenta diferenças; **não force a execução**. O banco real
pode ter efeitos não visíveis no catálogo, por isso mantenha o backup completo.

## Por que o GitHub CI não detectou

Os jobs anteriores criam Postgres efêmero e executam as migrations
sequencialmente no primeiro boot, depois executam down -v. O histórico
de um banco que recebeu V71 e só depois soube da V69.1 não era testado.

O job demo agora testa **ambos os casos** no mesmo banco PostgreSQL descartável:
(A) checksum divergente com E8 já existente; (B) V71 com o script histórico
de filename, coluna PDF existente, **tabelas E8 ausentes**. Em B, exige
`LEGACY_RECLASSIFIED_OK`, depois reinstala a V71 E8 real e exige
`LEGACY_UPGRADE_OK`. Confere integridade de contas após upgrade. Isso
testa o mecanismo de reconciliação e a execução real de V71 E8, mas não
substitui a inspeção do volume local e não recupera os bytes da V71 antiga.
