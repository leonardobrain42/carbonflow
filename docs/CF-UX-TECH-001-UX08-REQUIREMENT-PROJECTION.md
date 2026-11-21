# CF-UX-TECH-001 — UX-08A/B: diagnóstico de requisitos na tela Analisar

Issue [#124](https://github.com/leonardobrain42/carbonflow/issues/124). Esta fatia entrega o contrato de projeção e a tela que o lê. Não encerra a #124, a #93, a #97 nem a #99.

## O que a tela passa a fazer

`GET /v1/projects/{project}/technical/requirements` monta, no Quarkus, uma projeção somente leitura. A fonte continua sendo a declaração de aplicabilidade (`OpenApplicability`) e o pré-cálculo já existente (`OpenCalculationReadiness`). Não há tabela nova, tarefa nova, prova nova nem parecer novo.

A conta precisa de `VIEW_JOURNEY`. Itens de aplicabilidade só entram com `VIEW_OPEN_APPLICABILITY`. Itens de cálculo só entram com `VIEW_OPEN_CALCULATION`. Destino de navegação só é devolvido quando a mesma conta pode abrir aquela etapa. Sem grant, a resposta é 404 opaco. Revogação vale na leitura seguinte.

Cada item traz identificador estável, estado, motivo, papel previsto, próximo passo e grupo:

- **Ações desta conta** — só o que esta conta pode registrar agora.
- **Aguardando fontes** — teste de mercado e parâmetros ainda não admitidos. O texto visível é o estado público. Não há responsável interno, licença nem dado de catálogo.
- **Aguardando revisão** — lista positiva, excedente regulatório, direitos e parecer. Papel previsto não é uma pessoa designada e não cria grant.
- **Fora do recorte** — ramo não coberto, ou ED/EC, que não condicionam o documento ex ante.

`canRunE8`, `canIssueVcu` e `exAnteRequiresMeasuredEnergy` saem sempre falsos. A resposta não tem BE, PE, LE, ER nem identificador do piloto sintético.

## As quatro mensagens que deixam de ser tarefa eterna

A ficha de aplicabilidade ainda registra `VMD0049_ROUTE`, `MARKET_ROUTE`, `REGULATORY_SURPLUS` e `ED_EC` para o dono daquele módulo. No diagnóstico eles não são conclusão nem tarefa aberta para sempre:

- lista positiva, mercado e excedente ficam **aguardando a equipe**, sem campo para o cliente preencher no lugar da avaliação;
- mercado da VM0038 §4 não é rota de adicionalidade;
- ED/EC ficam **não incidentes** neste ato. A falta de medição não vale zero e não bloqueia o documento ex ante;
- incluir a prova de divulgação tira só `DRIVER_DISCLOSURE`. As outras três não mudam de grupo por causa disso;
- uma fonte marcada como pronta no pré-cálculo continua `NOT_ADMITTED` e não libera E8.

Metodologia diferente de VM0038 recebe um único limite explícito. Os requisitos OPEN/DCFC não são aplicados e o rascunho não é reprovado por eles.

## Interface

Em **Analisar**, o painel fica antes da declaração. O detalhe das equações saiu desta seção e permanece em **Quantificação**, com o título **Prontidão para quantificação**. Nenhuma fórmula é calculada no Angular. O painel recusa uma resposta que diga que o cálculo ou o crédito está liberado.

## Visão geral

`GET /journey` inclui o mesmo objeto `diagnosis` do endpoint de requisitos. O percentual de tarefas não soma esses itens. A primeira ação que a conta pode registrar aponta para a mesma tela, e `PUT /journey/resume` aceita esse destino. `analisar` fica disponível para quem tem a declaração ou as informações iniciais. Um passo inventado nesse destino continua recusado. Quem só vê o cálculo não recebe a pendência da declaração nem consegue gravar a retomada em Analisar.

## O que continua fora

- UX-08D: incidência vinda dos avaliadores #93/#99/#92/#98/#97, com `RECHECK_REQUIRED` por dependência real de prova.
- UX-08E: homologação #101, ensaio moderado e auditoria de acessibilidade além deste roteiro automatizado.

A ficha OPEN, os CHECK `usable_for_e8=false` e o piloto sintético não mudam de regra.
