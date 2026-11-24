# CF-OPEN-007 — Parecer da linha de base inicial (V93)

Continuação de #96/#97 e #236/#238. Referência: VM0038 v1.1 §9.1 Eq. 1 (p.15) e §10.1 (p.20), https://verra.org/wp-content/uploads/2026/08/VM0038-Electric-Vehicle-Charging-Systems-v1.1.pdf . IR é parâmetro disponível na validação; a rota com atualização anual utiliza 1. A conferência de um plano futuro não prova que houve atualização anual. A data de acompanhamento aqui é uma política interna do produto, não um prazo Verra.

## Contrato
Na avaliação vigente do primeiro ano e da rota anual específica, o revisor autorizado distinto do autor pode registrar INITIAL_BASELINE_CHECKED, exclusivamente com PLAN_ONLY, sem predecessor, com quatro textos (fonte, responsável, procedimento de atualização para EVR, AFEC, MPG, EFfuel), localizador e fundamentação. O servidor deriva a data de acompanhamento do início do ano 2 no calendário brasileiro confirmado e vincula o SHA-256 à avaliação, calendário, plano e parecer. Não recebe a data do cliente. O registro é imutável; idempotência e versão continuam no contrato anterior. V93 valida o formato e impede parecer inicial em outro ano ou rota; mantém FK de avaliação/hash/autor e segregação. A leitura do projeto recebe data e hash, não os textos técnicos protegidos.

A avaliação inicial conferida pode servir de referência anterior em uma análise de continuidade de ano 2, desde que as referências ainda estejam vigentes e as regras existentes sejam satisfeitas. Não é uma atualização observada e não satisfaz por si a continuidade. O contrato CHECKED_INTERNAL/OBSERVED_UPDATE permanece reservado aos anos seguintes com avaliação anterior.

## Limites
Esta fatia registra a fundamentação inicial **quando já existem referências canônicas do primeiro período**; não oferece ainda registro ex ante independente do monitoramento. Não comprova tempestividade nem que os textos correspondem a documentos examinados externamente. A prova anual anterior ainda não contém o hash separado do plano inicial. O E8 mantém FIRST_YEAR_REVIEW_REQUIRED, sem número e sem VCU; o contexto muda quando o parecer é registrado. Não introduz defaults brasileiros, nem muda Dy. A homologação positiva em PostgreSQL com dois anos e o consumo da proveniência estruturada no PD permanecem pendentes.

Testes: regra de decisão e campos obrigatórios, catálogo e trigger V93, restauração de fixture V79, jornada Angular simulada do plano inicial e parecer anual. O CI deve confirmar a suíte.
