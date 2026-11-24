# Quantificação do PD — memória da execução no contexto

Continua #216–#221 em #128/#43/#122/#97. Sem mudança backend/SQL ou motor.

“Conferir memória nesta seção” lê GET do run canônico por ID e período. Antes de
mostrar a memória, confere projeto, período, versão, SHA do conjunto de entradas,
engine e resultados BE/PE/LE/ER apresentados na prévia. Rejeita discrepância, snapshot
diferente, crédito habilitado ou usableForE8. Tentativa stale, bloqueada ou sem memória
não exibe valores no painel e pede atualização das seções.

O painel mostra as etapas BE/PE/LE/ER efetivamente registradas, referência e unidade,
ER bruto e ER apresentado pelo motor. Se houver memória horária de PE, mostra janela,
região, PE total armazenado e até as primeiras 24 parcelas registradas. Não soma,
reconstrói equação, faz arredondamento ou deduz valores. Sem memória horária ou sem
parcelas, informa a ausência; não transforma total em série nem ausência em zero.

Somente resultados de emissões são renderizados. AFEC/MPG, modelos, fatores e operandos
licenciados permanecem fora desse painel, mesmo que o contrato autorizado os contenha.
O endpoint e o grant VIEW_OPEN_E8_RUN já existiam; fonte e detalhe continuam sujeitos
às políticas canônicas atuais. O link para a memória completa é preservado.

Memória, pinos, energia e frota compartilham a invalidação de contexto. Outra consulta,
atualização, troca de seção ou destruição impede que respostas atrasadas reponham um
painel anterior. Acesso negado apaga o detalhe e mostra mensagem genérica.

A consulta não fixa suporte na narrativa, não cria ClaimSupport/manifesto ou export,
não altera gates e não declara admissão normativa ou crédito. O PD não fica pronto.
A tabela de 24 horas é uma prévia; não é uma memória completa exportável nem replay.

## Verificação

Fixtures autorais no navegador: resultados brutos/apresentados sem transformação,
parcelas de PE e total armazenado, ausência horária/vazio, limite de 24 horas, stale,
404, SHA divergente, saída divergente da prévia, admissão indevida, memória atrasada
após consulta de energia, ausência de ratings/fatores no DOM, teclado/celular e narrativa
preservada. Regressões de pinos, energia, frota, guias e navegação mantidas.

Validação local: build Angular aprovado; 9 jornadas de navegador aprovadas,
sem falhas ou skips; `git diff --check` aprovado. Backend/SQL/motor não alterados
e suíte Java não repetida nesta fatia. CI remoto será executado no PR.
Base: `049e964` após #221.
