# Quantificação do PD — consulta de tentativa específica

Continuação de #216 para #128/#43/#122. A origem canônica permanece E8 #97.

O link da memória para o PD transporta `run` (UUID) e `period`. A seção repassa ambos
para `GET /v1/projects/{project}/technical/pd-sections?period=M1&run={id}`.
Atualizar seções mantém a mesma seleção. Sem parâmetros, mantém-se o comportamento
anterior de mostrar a tentativa mais recente do projeto.

O backend reutiliza a mesma consulta exata do detalhe E8, filtrada por projeto,
período e ID, dentro da transação autorizada da seção. Parâmetros incompletos retornam
400. Tentativa inexistente, de outro projeto/período ou sem grant de cálculo retorna
404, sem fallback para a tentativa mais recente. `VIEW_DOSSIER` e `VIEW_OPEN_E8_RUN`
são necessários; a leitura das origens continua sujeita aos grants e licenças atuais.

A tentativa anterior bloqueada continua sem números mesmo se uma nova tentativa já
possuir memória. Licença expirada ou referência desatualizada também preserva o bloqueio.
A interface identifica a consulta específica, inclusive histórica, e informa que ela
não fixa suporte na narrativa nem aprova o documento.

Esta fatia substitui a limitação de navegação para a tentativa mais recente descrita
no registro de #216. Não cria SectionSnapshot/ClaimSupport congelado, seleção persistida
da narrativa, manifesto, exportação ou nova fórmula. Escolher uma tentativa na URL é
somente leitura; `marksDocumentReady` e `usableForE8` continuam falsos.

## Verificação

API/PostgreSQL: leitura da tentativa exata; tentativa anterior bloqueada depois da nova
memória; período errado, UUID inexistente, parâmetros incompletos, conta externa,
retirada do grant e licença expirada. Regressões de memória/idempotência preservadas.
Navegador: ID/período no link, grant documental, propagação à API e seleção conservada
após atualização, inclusive em viewport móvel, sem alteração da narrativa.

Validação local: build Angular aprovado; 8 testes Java de nota/memória/API aprovados
com PostgreSQL 16 descartável; 9 jornadas de navegador aprovadas, incluindo viewport
móvel e manutenção da seleção após atualizar. `git diff --check` aprovado.
CI remoto será executado no PR. Não foi repetida a suíte completa nesta fatia.
