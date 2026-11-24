# CF-OPEN-007 — Vínculo do planejamento ex ante ao parecer inicial (V95)

Continuação de #96/#97 e #240. O parecer inicial V93 agora exige o plano V94 vigente, com recorte OPEN confirmado, identificado por ID, versão e SHA-256. A referência não é um valor de IR.

## Contrato
- A avaliação do primeiro ano continua presa às referências efetivas de frota, ratings e fator fóssil. O servidor resolve o plano mais recente, contexto confirmado e metadados das fontes efetivas EVR, AFEC, MPG e EFfuel. O formulário de equipe autorizada apresenta fonte, responsável e procedimento previstos, IDs canônicos efetivos e título/edição/ano das respectivas fontes. Não exibe valores licenciados.
- O revisor declara MATCH ou JUSTIFIED_DEVIATION e uma justificativa específica para cada parâmetro. Os campos initialPlan da requisição V93 passam a conter essas conciliações classificadas. Também envia exAntePlanId, exAntePlanVersion, exAntePlanSha256. O servidor valida referência, versão, hash, escopo, atualidade, idempotência, autorização, identidade diferente do autor da avaliação E do autor do plano; ausência de fonte efetiva impede parecer.
- V95 registra FK composta do plano e seu autor, vínculo ao projeto da avaliação e guarda de inserção; a imutabilidade de V93 permanece. Hash do parecer incorpora plano e conciliações. Leitura do projeto mostra somente metadados congelados do vínculo e situação, não os textos da equipe.
- A nova revisão do plano ou mudança de contexto invalida a vigência do parecer vinculado. Parecer V93 legado sem vínculo permanece no histórico com INITIAL_LINK_RECHECK_REQUIRED. Replays históricos com mesma chave e conteúdo preservam o registro, sem criar novo parecer legado.
- Para continuidade do ano 1 ao 2, exige-se parecer inicial com vínculo vigente, e o digest da prova anual inclui o hash do parecer e do plano. Provas antigas do ano 2 sem esse vínculo passam a exigir rechecagem; não são reescritas. O estado do vínculo inicial participa da identidade do contexto E8.

## Limites
A conciliação é humana: a declaração de correspondência ou divergência justificada não é certificação de fonte, comprovação de prazo ou admissão normativa. Não altera FIRST_YEAR_REVIEW_REQUIRED nem autoriza VCU. Não fecha #96/#97. Ainda faltam prova temporal observada, consistência de referências dentro do ano, integração positiva em PostgreSQL dos dois anos, proveniência PD de IR estruturado e homologação.

Testes de regras e constraints V95, jornada de conciliação simulada e fixture histórica V79. CI deve validar a execução. CI #569 do PR anterior teve quatro jobs aprovados e falha de download Maven Central (HTTP 429) antes dos testes.
