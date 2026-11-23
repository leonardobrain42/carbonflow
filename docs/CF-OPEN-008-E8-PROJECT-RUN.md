# CF-OPEN-008 / #97 — Tentativa de cálculo do projeto aberto

O projeto grava tentativas imutáveis. Um conjunto incompleto gera `BLOCKED` com pendências e o plano das equações. Pares horários ED/EC/EFel, frota, EFfuel, IR e Dy precisam estar vinculados para outro ator registrar um ensaio `REVIEWED_INTERNAL`. A seleção temporal faz parte desse conjunto. O ensaio permanece com `usableForE8=false` e `canIssueVcu=false`.

## Ano do projeto e vigência no recorte inicial

- A janela com fim exclusivo vem do snapshot de QA vinculado ao pacote horário de ED, EC e EFel. O código do dossiê identifica a janela.
- Para aplicar a §5.2 da VM0038 v1.1 no ensaio, a declaração vigente deve vincular as datas de início operacional do primeiro sistema de recarga e início de crédito, com a mesma data. Eventos ausentes ou divergentes geram pendência. Alterar a data de um evento requer reconfirmar a declaração. Os eventos são declarações preparatórias.
- O ano do projeto segue os aniversários dessa data no calendário de `America/Sao_Paulo`. O ano de referência das fontes usa UTC, conforme o contrato atual da frota e das séries horárias. Esses dois anos podem ser diferentes. Janelas anteriores ao início ou que atravessam um dos dois limites ficam bloqueadas. A divisão da janela fica a cargo da preparação de monitoramento.
- Para EFfuel, IR e Dy, o executor seleciona a última versão do ano de referência correspondente. O pino de Dy representa o valor aplicável à janela. O levantamento de ERC e ERF do ano de projeto anterior e a execução com Dy diferente de 1 seguem na próxima fatia de #96/#97. EFel continua vindo da série horária vinculada e a frota deve corresponder ao vínculo e ao ano das fontes.
- A tentativa congela a janela, os dois anos, a impressão digital do calendário e as referências usadas. Uma publicação de outro ano preserva a validade histórica e uma mudança da versão aplicável pede rechecagem. Tentativas anteriores à migração V79 continuam consultáveis, com vigência a revalidar.

## API

- `GET/POST /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-runs`
- `GET` do run por id
- POST exige `Idempotency-Key` e `If-Match`. O JSON aceita no máximo `energyBindingId` e `fleetSelectionId`. Sem eles, o servidor usa a versão vigente do período ou registra a ausência.
- A mesma chave retoma a tentativa original, incluindo seus vínculos. Com uma nova chave, o mesmo conjunto devolve a versão atual e um conjunto diferente cria uma sucessora.
- Os registros mais recentes incluem `timing` com `windowStart`, `windowEndExclusive`, `projectYear` e `sourceYear`. Campos ainda não resolvidos ficam nulos.
- Na leitura, vínculo que deixou de ser o vigente responde `STALE` sem reescrever a linha.
- `GET /technical/open-calculation-readiness` continua com `canRunE8=false` e emissões nulas. O run não entra nesse diagnóstico.

Autorização por `DynamicProjectScope`: `VIEW_OPEN_E8_RUN` para o dono do rascunho, para `CALCULATION` e para monitoramento que não seja `OP`; `RECORD_OPEN_E8_RUN` para o dono e para `PREP` com cálculo ou monitoramento. Respostas com `Cache-Control: private, no-store`.

## Interface

Preparação técnica mostra a última tentativa do período e, para quem pode registrar, o botão correspondente. O navegador não envia número e não contém fórmula. O botão de reexecução do piloto não é reutilizado. O motivo da tentativa nomeia a grandeza em português. O código estável acompanha cada motivo de bloqueio, incluindo cobertura horária incompleta, autoria dos vínculos e versões que exigem rechecagem.

## O que continua pendente para encerrar #97

Seleção admitida de EVR, ratings, EFfuel, IR e Dy; medição ED/EC admitida; execução normativa com `canIssueVcu` ainda falso até decisão própria de crédito; memória no PD (`#43`, `#122`, `#128`). Nenhuma fonte oficial brasileira foi admitida por esta fatia.

## PE por hora

O ensaio usa o pacote horário de energia e fator já vinculado ao período. Para cada hora calcula EC_h × EFel_h e soma os produtos decimais exatos. O resultado do exemplo com 1.000 kWh × 0,0005 tCO2e/kWh e 2.000 kWh × 0,0004 tCO2e/kWh é 1,3 tCO2e. O núcleo também aceita a rota uniforme usada por seus chamadores existentes.

A janela tem início e fim exclusivo em horas UTC inteiras, até 744 horas conforme o contrato atual de staging. A série precisa cobrir cada hora uma única vez. Região SIN, unidade tCO2e/kWh, base CO2e, contagem de horas e totais ED/EC precisam coincidir com as referências do pacote e do QA. Cobertura inválida grava tentativa bloqueada com a causa HOURLY_COVERAGE_INVALID.

A memória contém hourlyPe com janela, região, totais e os operandos e o resultado de cada hora. O motor identifica essa rota como vm0038-open-grid-arithmetic@2-hourly. O JSON congelado continua legível após troca de versões. O leitor aceita também o formato de lista das sete etapas gravado pelos motores anteriores. A tela apresenta a tabela horária dentro da memória da tentativa selecionada.

Esta entrega cobre o cálculo horário do ensaio interno. Admissão normativa das entradas, Dy com avaliação ERC/ERF e fechamento anual entre janelas continuam no backlog #96, #97 e #98.
