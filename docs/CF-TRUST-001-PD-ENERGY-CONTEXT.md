# Quantificação do PD — ED e EC do vínculo canônico

Continuação de #219 para #128/#43/#122/#98. Sem novo contrato ou cálculo backend.

A referência ED oferece “Conferir energia ED/EC nesta seção”. A consulta usa o ID
pinado no snapshot e o período da tentativa, no endpoint canônico de vínculos E8.
Antes de mostrar dados, confere projeto, período, ID, versão e usableForE8=false.
Referência sem grant não oferece a ação; a API continua autorizando a leitura.

O painel mostra a identidade e versão do vínculo, situação atual, contagens registradas
e leituras por hora, distinguindo ED entregue de EC consumida. Quantidades são strings
normalizadas em kWh devolvidas pelo owner, sem parsing, soma, dedução ou conversão no
Angular. Zero armazenado é exibido como zero; campo ausente é “Não registrada”.
Resposta sem horas informa ausência, sem inventar leitura.

A tabela apresenta até as primeiras 24 horas, explicitando o limite e o acesso ao
vínculo completo pelo link canônico ED. Não é exportação nem inspeção completa de
um ano. O fator elétrico numérico presente no contrato autorizado não é renderizado
neste painel. Nenhum valor de BE, PE, LE ou ER é produzido a partir da tabela.

Vínculo stale permanece histórico e exige rechecagem, sem admissão ou prontidão.
Pinos e energia compartilham o controle de contexto: consultar outro detalhe, atualizar,
mudar de seção ou destruir o componente invalida a resposta anterior. Acesso negado
remove o detalhe; resposta de outro período/projeto/versão é recusada sem fallback.

Esta fatia não muda grants, licenças, catálogo, QA, snapshot, narrativa, claims ou gates.
Não fixa suporte documental e não cria manifesto, DOCX/PDF ou emissão de crédito.
A licença e a atualidade continuam sendo verificadas pelos owners canônicos.

## Verificação

Fixtures autorais de navegador: ED=2700 e EC=3000 distintos; zero versus campo ausente;
resposta vazia; limite de 24 horas; estado stale; 404; período incorreto; resposta de
energia atrasada após consultar um pino; ausência do fator elétrico no DOM; teclado,
celular e narrativa inalterada. Regressões do painel de pinos e navegação preservadas.

Validação local: build Angular aprovado; 9 jornadas de navegador aprovadas,
sem falhas ou skips; `git diff --check` aprovado. Backend/SQL não alterados e
suíte Java não repetida nesta fatia. CI remoto será executado no PR.

A execução 45320333194 da main falhou antes dos testes Java por HTTP 429 no download
do maven-clean-plugin, mesmo após os retries. Os demais jobs passaram. Foi solicitada
nova tentativa do job; esta falha externa não motivou alteração do código da aplicação.
Base: `70d3d0d` após #219.
