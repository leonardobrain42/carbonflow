# Sandbox persistente: colisão V79 Calendar / Timing

A branch histórica `feat/persistir-vinculo` (commit `8220ce0fab6f0542ab669392a09c61c062b16d21`) executou V79 Calendar com CRC32 Flyway -898586753. A main usa V79 Timing (1403819066) e V80 Hourly (1264111112). As duas V79 não são equivalentes. V81 Calendar contém o SQL original, sem alteração de checksum.

A V81 preserva `app_open_e8_run.temporal_context` e `app_open_e8_run_command.request_sha256`. O contexto antigo NÃO é convertido automaticamente em timing admitido: não há evidência suficiente para afirmar equivalência entre os modelos. Os registros originais permanecem intactos. Instalações novas executam V79, V80 e V81 normalmente.

## Recuperação local (somente demo)

Na raiz do repositório, após integrar a PR e atualizar o código, mantenha o mesmo volume, configuração e credenciais:

    docker compose -p carbonflow-demo -f compose.yaml -f compose.demo.yaml stop app
    python3 scripts/demo_flyway_v79_compat.py

Se retornar LEGACY, use seu backup anterior à alteração, criado com pg_dump -Fc e permissões privadas:

    python3 scripts/demo_flyway_v79_compat.py --apply --backup .carbonflow-demo/backups/SEU-ARQUIVO.dump
    python3 scripts/demo_sandbox.py start
    python3 scripts/demo_flyway_v79_compat.py

O último comando deve retornar COMPLETE. NORMAL significa V79 atual já aplicada e V81 ainda pendente; PENDING significa reclassificação feita, mas ainda faltam migrations oficiais.

O script valida checksums dos arquivos, histórico, tipos, ausência de defaults, CHECKs validados, triggers imutáveis, ausência da tabela Timing, V78 e estado do app. Exige backup privado legível por pg_restore -l, grava recibo privado com hash do backup e histórico original; não comprova restauração nem atualidade do dump. Antes de atualizar, repete as condições dentro de uma transação com locks. Só muda version/script da entrada histórica; mantém checksum, rank, data e conteúdo. O Flyway executa V79/V80 fora de ordem no demo, com validação ativa.

Se REFUSED ou erro: pare. Não altere checksums manualmente, não execute repair, clean, down -v nem desabilite a validação. Não usar em produção. Não iniciar outro processo de migração durante a manutenção. Preserve também a configuração e as chaves locais necessárias aos dados criptografados.

## Prevenção

A CI rejeita versões Flyway numericamente duplicadas e alterações/remoções de migrations existentes na base da PR. A recuperação é uma exceção explícita para o demo histórico, não uma licença para reescrever migrations. Atualize a branch antes de publicar novas versões.
