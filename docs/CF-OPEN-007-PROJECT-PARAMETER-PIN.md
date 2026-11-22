# CF-OPEN-007 / #96 — Pino de projeto para EFfuel, EFel, IR e Dy

Esta fatia não admite fator brasileiro, não conclui VT0011 e não executa o E8. `usableForE8` permanece falso. A issue #96 continua aberta.

## O que o pino grava

Um gestor de fontes, com MFA e na mesma organização do rascunho VM0038, registra uma versão imutável:

- **EF_FUEL:** referência a um candidato fóssil já revisado internamente, gasolina, CO2e, unidade `tCO2e/USgal`, ano igual ao da fonte. O número licenciado não é copiado para a leitura do projeto.
- **EF_GRID:** referência a um candidato elétrico anual já revisado, SIN, CO2e, unidade `tCO2e/kWh`. A conversão `tCO2e/MWh` → `tCO2e/kWh` continua só na normalização do candidato. Uma série horária e um fator médio MCTI não se tornam este pino. Fator fóssil não entra no lugar do fator elétrico, e o contrário também não.
- **IR e Dy:** valor declarado com regra e localizador de prova. Campo vazio não grava pino. Zero gravado é `DECLARED_ZERO`, diferente de ausência. O número 1 só existe quando foi enviado, e o diagnóstico diz que não é default. Não há flag de default.

Troca de triagem, suspensão do candidato ou licença que deixa de autorizar a leitura marcam o pino como `STALE_NOT_ADMITTED` sem reescrever a versão. Novo conteúdo cria outra versão, com `If-Match` e `Idempotency-Key`.

## API

- `GET /v1/platform/open-parameter-bindings/options`
- `POST /v1/platform/open-parameter-bindings`
- `GET /v1/projects/{project}/technical/open-parameter-bindings`
- `GET/POST /v1/platform/open-grid-factors` registra o candidato anual, no mesmo contrato do fator fóssil, com a necessidade `OPEN-GRID-EF`.

O diagnóstico de quantificação troca `MISSING_BINDING` por `NOT_ADMITTED` ou `STALE` quando o pino existe. `canRunE8` e `canIssueVcu` seguem falsos. O piloto `EF-SYN-v1` não é lido.
