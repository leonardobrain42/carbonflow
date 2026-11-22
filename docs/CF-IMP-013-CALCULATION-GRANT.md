# CF-IMP-013 / #24 — Grant de cálculo para uma segunda conta

Esta fatia não comprova poder de proponente, não envia e-mail real e não cria VVB. A issue #24 continua aberta.

## O que a segunda conta recebe

Um grant ativo com a atividade `CALCULATION` coloca o mesmo rascunho na sessão da segunda conta, com acesso `CALCULATION`. O titular continua `OWNER`. A segunda conta lê o diagnóstico de quantificação e o título do projeto. Ela não altera os metadados, não abre o monitoramento e não emite crédito: `canRunE8` e `canIssueVcu` permanecem falsos.

Um grant só de monitoramento não lista o projeto. Conta desativada, grant revogado ou grant fora da vigência também não lista. A revogação tira o projeto da sessão e a leitura seguinte do diagnóstico responde como recurso indisponível.

O convite do sandbox continua sendo a via de aceite. A caixa do sandbox não é entrega de e-mail.
