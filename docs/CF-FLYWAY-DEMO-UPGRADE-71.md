# Sandbox persistente: Flyway V71 / V69.1

O banco local registrou V71 checksum 167374965, mas a V71 atualmente publicada tem
checksum -1979807539. A única V71 publicada no histórico do repositório é
a versão atual: não é possível recuperar de GitHub os bytes que foram
executados no banco local. Portanto NÃO desligar validação, NÃO aplicar repair
automático em todo o banco, NÃO executar down -v.

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

O script confere checksums conhecidos das V69/V71, versões de migração,
colunas e tipos, restrições, vínculos, índice, triggers imutáveis e ações E8.
Se houver diferença ele recusa, solicitando comparação manual; essa
verificação não prova equivalência perfeita de SQL histórico indisponível.
Em --apply salva inspeção privada em .carbonflow-demo e altera apenas o checksum
da linha V71 dentro de transação. Nenhum dado técnico é regravado. Se a
V69.1 encontrar evidência legada incompatível, ela falhará sem converter
valores arbitrários. Não remova o volume nem ignore a exceção.

## Por que o GitHub CI não detectou

Os jobs anteriores criam Postgres efêmero e executam as migrations
sequencialmente no primeiro boot, depois executam down -v. O histórico
de um banco que recebeu V71 e só depois soube da V69.1 não era testado.

O job demo agora simula um banco já migrado, retira a V69.1 do histórico e
a coluna que ela acrescenta, configura somente nesse CI o checksum legado
da V71, executa a reconciliação explícita, reinicia o Quarkus e verifica
V69.1, V71 e as contas reais provisionadas antes da atualização. Isso
exercita a migração incremental, mas não reconstrói o SQL legado original.
