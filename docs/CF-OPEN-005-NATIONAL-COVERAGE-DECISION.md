# CF-OPEN-005 / #94 — Decisão de cobertura do estoque BEV nacional

Esta fatia não importa a série da Senatran, não calcula um percentual nacional e não admite o retrato para o E8. A issue #94 continua aberta.

## O que a decisão grava

Um revisor com MFA, diferente de quem importou o retrato de estoque em circulação no Brasil, registra uma versão imutável:

- `NOT_COVERED` declara que o retrato não cobre o estoque BEV nacional. Um total oficial citado, se houver, precisa ser um inteiro maior que o denominador do retrato. A resposta guarda esse inteiro e a diferença em veículos, nunca uma fração nacional;
- `NO_OFFICIAL_TOTAL` declara que não há total oficial admissível para comparar. Nesse caso não há número citado;
- ausência continua sem linha. O retrato permanece `OFFICIAL_TOTAL_NOT_VERIFIED`.

`usableForE8` fica falso. A repetição da mesma chave devolve a mesma versão. `If-Match` aponta a versão corrente. Um retrato posterior do mesmo ano torna a leitura `STALE` e recusa outra decisão na versão antiga. Vendas não recebem esta decisão. A revogação da licença apaga a decisão junto com os números derivados.

## API

`POST /v1/platform/fleet/imports/{ingestion}/coverage` com `Idempotency-Key` e `If-Match`.

**`If-Match`:** obrigatório, com a versão da decisão corrente **entre aspas** (ex.: `If-Match: "0"`). Quando o retrato ainda não tem decisão de cobertura, a versão corrente é `0`, então a **primeira decisão usa `"0"`**; cada nova decisão usa a versão devolvida na anterior (cabeçalho `ETag` da resposta). Sem o cabeçalho a resposta é `428 PRECONDICAO_VERSIONADA_AUSENTE`; fora do formato, `400 PEDIDO_INVALIDO`; versão desatualizada, conflito. Corpo: `{"outcome":"NO_OFFICIAL_TOTAL"|"NOT_COVERED","reason":"20–500 caracteres","citedTotal":<inteiro positivo, só em NOT_COVERED>}`. Quem decide não pode ser quem importou o retrato.

A leitura do import inclui `coverageDecision`. O estado de cobertura do retrato não muda.
