# CF-OPEN-008 / #97 — Tentativa de cálculo do projeto aberto

O projeto grava tentativas imutáveis. Um conjunto incompleto gera `BLOCKED` com pendências e o plano das equações. ED, EC, um único EFel, frota, EFfuel, IR e Dy precisam estar vinculados para outro ator registrar um ensaio `REVIEWED_INTERNAL`. A seleção temporal faz parte desse conjunto. O ensaio permanece com `usableForE8=false` e `canIssueVcu=false`.

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

Preparação técnica mostra a última tentativa do período e, para quem pode registrar, o botão correspondente. O navegador não envia número e não contém fórmula. O botão de reexecução do piloto não é reutilizado. O motivo da tentativa nomeia a grandeza em português. O código estável fica na referência, inclusive quando as horas não compartilham um único fator elétrico, quando quem registra é autor dos vínculos, ou quando as versões deixaram de ser as vigentes.

## O que continua pendente para encerrar #97

Seleção admitida de EVR, ratings, EFfuel, IR e Dy; medição ED/EC admitida; execução normativa com `canIssueVcu` ainda falso até decisão própria de crédito; memória no PD (`#43`, `#122`, `#128`). Nenhuma fonte oficial brasileira foi admitida por esta fatia.
