# CF-OPEN-009 / #98 — Admissão independente de um lote no snapshot do período

Esta fatia não admite a medição para o E8, não valida certificado nem emite crédito. A issue #98 continua aberta.

## O que a decisão grava

Um revisor com monitoramento, diferente de quem enviou o lote, de quem revisou as linhas congeladas e de quem criou o snapshot, registra uma versão imutável:

- `ADMITTED_FOR_PERIOD` admite o lote somente dentro daquele snapshot;
- `REJECTED` recusa o lote naquele snapshot;
- ausência continua sem linha de admissão.

Energia entregue e energia consumida do lote permanecem somas separadas. `usableForE8` fica falso. A repetição da mesma chave devolve a mesma versão. `If-Match` aponta a versão corrente.

Se o QA do período deixa de coincidir com o hash do snapshot, a leitura fica `STALE` e uma admissão nova é recusada. A versão antiga não é reescrita.

## API

- `GET /v1/projects/{project}/technical/open-monitoring/period-qa/snapshots/{snapshot}/batch-admissions`
- `POST` no mesmo caminho, com `Idempotency-Key` e `If-Match`

O parecer `ACCEPTED_FOR_QA` e o snapshot `INTERNAL_QA_ONLY` continuam sendo etapas anteriores. Nenhum dos dois substitui esta admissão.
