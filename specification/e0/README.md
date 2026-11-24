# E0 — Origem e integridade documental
**Estado:** índice de recuperação; original ainda não importado.

A evidência [E8-013](../../evidencias/e8-013/VERIFICACAO.md) identifica a distribuição **0.2.14**, em 16/11/2026, e contém instrução de verificar [KIT.sha256](../../evidencias/e8-013/KIT.sha256). O manifesto lista 29 caminhos/hashes utilizados na revisão, não necessariamente todos os arquivos do kit. A árvore original inclui 01_controle, 02_fontes, 03_especificacao e 04_entrega_codex. Não confundir o manifesto com o ZIP. A massa de exemplo embarcada foi comparada historicamente com 03_especificacao/e6/exemplos.json; consulte o relatório e [fixture](../../backend/src/main/resources/fixtures/e6-exemplos.json).

## Importação futura
1. Recuperar a cópia original autorizada de Kit_de_Especificacao_Plataforma_Carbono (18).zip e E8_Backlog_PIL-SYN-BR-001_Kit_0.2.14 (1).zip. Pesquisa dos arquivos não encontrou os ZIPs recuperáveis.
2. Extrair fora do repositório e inspecionar conteúdo, licenças, dados restritos e credenciais antes de incluir qualquer arquivo.
3. Na raiz do Git: `KIT=/caminho/para/kit` e `(cd "$KIT" && sha256sum -c -) < evidencias/e8-013/KIT.sha256`. Preservar relatório; divergência exige identificar versão, nunca "corrigir" manifesto histórico.
4. Guardar os textos autorais permitidos em uma árvore histórica identificada (proposta: specification/history/kit-0.2.14/); referenciar fontes protegidas por metadados e localização autorizada. Não incluir ZIP se misturar direitos ou conteúdo restrito. Não alterar original.
5. Reconciliar todos os RF/CT/CAM/OP/DEC/PEN e matrizes com a síntese nova, registrar mapeamento, divergência, omissões e responsabilidade. Atualizar [estado](../ESTADO_DO_TRABALHO.md) e changelog.
6. Se o ZIP não for recuperado, manter status de reconstrução e não atribuir a esta árvore equivalência ao kit original.

A especificação viva 0.3.0-rc.1 é derivada dos contratos e evidências do GitHub e do backlog, sem preenchimento fictício de IDs históricos.
