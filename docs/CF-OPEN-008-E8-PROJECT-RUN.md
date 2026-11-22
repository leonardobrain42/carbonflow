# CF-OPEN-008 / #97 — Tentativa de cálculo do projeto aberto

**Situação:** o projeto dinâmico passa a gravar uma tentativa imutável. O resultado da API é `BLOCKED`. A issue #97 permanece aberta. Esta fatia não admite candidato, não altera `usable_for_e8` e não executa o piloto `PIL-SYN-BR-001`.

## Por que a tentativa visível bloqueia

`OpenGridCalculationMath` continua sendo aritmética pura, com `usableForE8=false`. O servidor só a chama quando o leitor entrega ED, EC, um único EFel, frota, EFfuel, IR e Dy, e o ator da tentativa não é o autor desses vínculos. No PostgreSQL atual, IR e Dy não têm valor normalizado (`V50`) e não há pino de EFfuel de projeto. O leitor de produção devolve conjunto incompleto. O POST grava `BLOCKED`, com os códigos de gate e o `equationPlan` já montado por `OpenCalculationTransparency`. BE, PE, LE e ER ficam nulos. `canIssueVcu` permanece falso por `CHECK`.

`usable_for_e8=false` nas tabelas `V59`–`V64` não é o motivo do bloqueio e não muda. O bloqueio é a ausência do conjunto. Quando o período tem ED, EC, um único EFel, linhas de frota, fator fóssil vigente, IR positivo declarado e Dy declarado como 1 com prova de ausência de créditos de frota, outro ator — que não publicou esses vínculos — grava `REVIEWED_INTERNAL`. O autor dos vínculos recebe `BLOCKED` com `ACTOR_SEGREGATION`. O ensaio guarda a memória e os pinos usados. `canIssueVcu` e `usableForE8` permanecem falsos. Um pino posterior deixa a leitura `STALE` sem reescrever a linha.

## API

- `GET/POST /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-runs`
- `GET` do run por id
- POST exige `Idempotency-Key` e `If-Match`. O JSON aceita no máximo `energyBindingId` e `fleetSelectionId`. Sem eles, o servidor usa a versão vigente do período ou registra a ausência.
- A mesma chave devolve o mesmo run. Outra chave com o mesmo conjunto também. Um conjunto diferente cria versão sucessora, apontando para a anterior, sem `UPDATE`.
- Na leitura, vínculo que deixou de ser o vigente responde `STALE` sem reescrever a linha.
- `GET /technical/open-calculation-readiness` continua com `canRunE8=false` e emissões nulas. O run não entra nesse diagnóstico.

Autorização por `DynamicProjectScope`: `VIEW_OPEN_E8_RUN` para o dono do rascunho, para `CALCULATION` e para monitoramento que não seja `OP`; `RECORD_OPEN_E8_RUN` para o dono e para `PREP` com cálculo ou monitoramento. Respostas com `Cache-Control: private, no-store`.

## Interface

Preparação técnica mostra a última tentativa do período e, para quem pode registrar, o botão correspondente. O navegador não envia número e não contém fórmula. O botão de reexecução do piloto não é reutilizado. O motivo da tentativa nomeia a grandeza em português. O código estável fica na referência, inclusive quando as horas não compartilham um único fator elétrico, quando quem registra é autor dos vínculos, ou quando as versões deixaram de ser as vigentes.

## O que continua pendente para encerrar #97

Seleção admitida de EVR, ratings, EFfuel, IR e Dy; medição ED/EC admitida; execução normativa com `canIssueVcu` ainda falso até decisão própria de crédito; memória no PD (`#43`, `#122`, `#128`). Nenhuma fonte oficial brasileira foi admitida por esta fatia.
