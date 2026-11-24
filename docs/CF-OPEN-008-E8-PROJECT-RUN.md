# CF-OPEN-008 / #97 — Tentativa de cálculo do projeto aberto

O projeto dinâmico grava uma tentativa imutável. A API retorna `BLOCKED` para conjuntos incompletos e `REVIEWED_INTERNAL` para um ensaio completo registrado por outro ator.

## Por que a tentativa visível bloqueia

`OpenGridCalculationMath` recebe ED, EC, um EFel uniforme, frota, EFfuel, IR e Dy aplicáveis à janela. Um conjunto incompleto produz `BLOCKED`, com os motivos e as dependências por equação em `equationPlan`. BE, PE, LE e ER ficam nulos.

Quando o período tem ED, EC, um único EFel, linhas de frota, fator fóssil vigente, IR positivo declarado e Dy declarado como 1 com prova de ausência de créditos de frota, um ator independente grava `REVIEWED_INTERNAL`. O autor dos vínculos recebe `BLOCKED` com `ACTOR_SEGREGATION`. O ensaio guarda a memória e as versões usadas, com `canIssueVcu=false` e `usableForE8=false`.

## Calendário e vigência

O início operacional da primeira estação, vinculado à declaração de rede aberta, fundamenta o início de crédito para o ensaio da VM0038 v1.1 §5.2. O ano civil desse início é `y = 1`. A virada de ano em `America/Sao_Paulo` incrementa `y`. A janela provém do snapshot de QA e usa fim exclusivo. Uma janela que inclui horas de dois anos exige divisão no monitoramento. Se houver evento de início VCS declarado, a data precisa coincidir com a operacional.

O ensaio seleciona a última versão de EFfuel, IR e Dy do ano civil da janela. O cadastro atual usa ano de referência igual ao da fonte para fatores anuais. O EFel continua vindo da série horária vinculada à mesma janela, com fator uniforme exigido nesta rota. A frota precisa ter o mesmo ano civil e estar vinculada ao pacote de energia usado. Quando só existem parâmetros anuais de outros anos, a tentativa indica vigência incompatível.

`temporalContext` congela o calendário, as datas vinculadas e a janela de QA com sua revisão. `parameterPins` aponta para as versões selecionadas de EFfuel, IR e Dy. Uma nova versão de outro ano fica fora da seleção dessa tentativa. A substituição de um vínculo aplicável marca a tentativa anterior como `STALE`, preservando a memória, as referências e o hash. Tentativas anteriores à adoção desse calendário ficam disponíveis como histórico `STALE`.

## API

- `GET/POST /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-runs`
- `GET` do run por id
- POST exige `Idempotency-Key` e `If-Match`. O JSON aceita no máximo `energyBindingId` e `fleetSelectionId`. Sem eles, o servidor usa a versão vigente do período ou registra a ausência.
- A mesma chave com a mesma solicitação devolve a tentativa original. Outra chave com o mesmo conjunto vigente também. Um conjunto diferente cria uma versão sucessora com referência à anterior.
- Na leitura, vínculo que deixou de ser o vigente responde `STALE` sem reescrever a linha.
- `GET /technical/open-calculation-readiness` continua com `canRunE8=false` e emissões nulas. O run não entra nesse diagnóstico.

Autorização por `DynamicProjectScope`: `VIEW_OPEN_E8_RUN` para o dono do rascunho, para `CALCULATION` e para monitoramento que não seja `OP`; `RECORD_OPEN_E8_RUN` para o dono e para `PREP` com cálculo ou monitoramento. Respostas com `Cache-Control: private, no-store`.

## Interface

Preparação técnica mostra a última tentativa do período e, para quem pode registrar, o botão correspondente. O navegador não envia número e não contém fórmula. O botão de reexecução do piloto não é reutilizado. O motivo da tentativa nomeia a grandeza em português. O código estável fica na referência, inclusive quando as horas não compartilham um único fator elétrico, quando quem registra é autor dos vínculos, ou quando as versões deixaram de ser as vigentes.

## O que continua pendente para encerrar #97

Seleção admitida de EVR, ratings, EFfuel, IR e Dy; medição ED/EC admitida; execução normativa com `canIssueVcu` ainda falso até decisão própria de crédito; memória no PD (`#43`, `#122`, `#128`). Nenhuma fonte oficial brasileira foi admitida por esta fatia.
