# CF-UX-TECH-001 — Fatia 6: triagem como hipótese inicial (D16) — desenho para aprovação

**Estado: aprovado** pelo responsável pelo produto em 24/11/2026 (Q1–Q7 conforme as recomendações). **6a, 6b, 6c e Q7 implementadas** (seções 9 a 12). Levantamento feito no código em 24/11/2026 (`ProjectJourney`, `ProjectTriageWizard`, `ProjectTriageDiagnosis`, `OpenApplicability`, `OpenCalculationReadiness`, `OpenIrExAntePlans` e guardas de versão).

## 1. Objetivo e limites
- **D16:** a triagem registra a **hipótese inicial** (objetivo, atividade, metodologia candidata, local e o recorte mínimo para dizer se o projeto cabe no produto). Detalhes técnicos, direitos e adicionalidade vivem só nas telas técnicas, que já têm donos para eles.
- **Fica igual:** a tabela `app_project_triage_revision`, o formato `answers` (jsonb com chaves `"1"`..`"4"`), as revisões já gravadas, o cursor, a privacidade (GET só para o titular), D15 e todas as guardas de versão da triagem.
- **Sem migração de banco.** Nenhuma resposta gravada é apagada ou reescrita. A mudança é de quais perguntas a triagem **faz**, de quais ela **exige** e de onde os consumidores **leem** cada informação.
- Ensaio continua sem admissão; nenhum default novo é criado para campos que saem da triagem.

## 2. Inventário: cada resposta, quem lê hoje e destino proposto
| Etapa · campo | Quem lê hoje (backend) | Dono técnico existente | Destino proposto |
|---|---|---|---|
| 1 · `objective` | resumo da triagem | — | **Fica** |
| 1 · `activityType` | `ProjectJourney` (achado de método), D15 | — | **Fica** |
| 1 · `methodologyCandidate` | `ProjectJourney.candidate`, guias, D15 | Preparar informações (já reaproveita) | **Fica** |
| 2 · `activityDescription` | resumo; sugestão da fatia 2 | Preparar informações | **Fica** (curta) |
| 2 · `installsChargers` | achado de método, D15 | — | **Fica** |
| 2 · `baselinePractice` | obrigatório na VM0038 | guia Linha de base | **Pergunta Q3** |
| 2 · `expectedBenefit` | resumo | — | **Pergunta Q3** |
| 3 · `vehicleClass` | achado de método, limite do piloto, `OpenCalculationReadiness.vehicleClass`, `OpenIrExAntePlans` | **nenhum** | **Fica** (não há outro dono; guardas de cálculo dependem dele) |
| 3 · `propulsion` | achado de método (combustão, PHEV+DCFC), limite do piloto | Acesso à rede (`propulsion`) | **Fica** como hipótese (Q1) |
| 3 · `chargerType` | achado de método, limite do piloto | Acesso à rede (`chargerType`) | **Fica** como hipótese (Q1) |
| 3 · `design` (individual/agrupado) | limite do piloto | **nenhum** | **Fica** |
| 3 · `network`, `access` | limite do piloto | Acesso à rede (`restrictedToIdentifiedVehicles`, `unrestrictedBevAccess`) | **Sai**; limite passa a ler a declaração |
| 3 · `electricitySource` | limite do piloto | Acesso à rede | **Sai**; idem |
| 3 · `associatedInfrastructure` | limite do piloto | Acesso à rede | **Sai**; limite passa a ler a declaração |
| 3 · `metering` | limite do piloto | Monitorar (medidores ED/EC) | **Sai**; resposta antiga ainda vale, sem ela fica a confirmar |
| 3 · `vehicleCount`, `chargerCount` | limite do piloto (1 a N) | Estações e inventário | **Sai** (Q5); idem |
| 4 · `country`, `state`, `locality`, `referenceDate` | limite do piloto, D15 (`country`), sugestão da fatia 2 | Preparar informações | **Fica** |
| 4 · `evidenceStatus` | `ProjectTriageDiagnosis` | lista de pendências | **Sai** |
| 4 · `rightsStatus`, `rightsJustification` | achado de método (`NAO_APLICAVEL_JUSTIFICADO`), D15 | Direitos do projeto | **Sai** (Q2) |
| 4 · `investmentCommitted`, `carbonConsideredBeforeInvestment` | `ProjectTriageDiagnosis` | guia Adicionalidade (cronologia) | **Sai** (Q4) |

Resultado com as recomendações: a etapa 3 da VM0038 passa de 11 para 4 perguntas e a etapa 4 de 9 para 4.

## 3. Nova triagem (o que a pessoa vê)
As chaves de armazenamento continuam `"1"`..`"4"`; só mudam títulos e perguntas.
1. **Objetivo e metodologia** — objetivo, tipo de atividade, metodologia candidata.
2. **Atividade** — descrição curta e se instala recarga (mais o que Q3 decidir).
3. **Recorte inicial** — classe da frota, propulsão, tipo de recarga, individual/agrupado.
4. **Local** — país, estado, cidade, data de referência.
5. **Resumo** — a hipótese, o que está fora do recorte do piloto e **o que será pedido na preparação técnica** (Acesso à rede, Direitos do projeto, Adicionalidade, Estações, Monitorar), com links.

## 4. Respostas já gravadas
- **Preservadas como estão** nas revisões antigas. Atenção: hoje salvar uma etapa **substitui a etapa inteira** pelo que a tela envia (`ProjectTriageWizard`: `answers.set(step, normalized)`). Se a nova tela simplesmente deixar de enviar os campos removidos, eles **somem da revisão nova** e a etapa 3 muda na comparação canônica de D15, deixando a aplicabilidade desatualizada sem motivo.
- **Regra proposta:** ao salvar uma etapa, o servidor copia para a revisão nova, sem alteração, os campos que **saíram da triagem** e existiam na revisão anterior; esses campos deixam de ser aceitos no envio (edição recusada com erro de campo, não ignorada em silêncio). Assim não há perda nem falso "mudou" em D15. Exige teste de integração com projeto que tem respostas antigas.
- A triagem passa a mostrar, só leitura, um bloco "Respostas antigas que agora ficam nas telas técnicas", com link para a tela dona. Não são editáveis na triagem (Q6).
- Quando a tela técnica tem valor próprio, **ela vale**; a resposta antiga da triagem nunca substitui a declaração.
- A sugestão da fatia 2 continua lendo as respostas antigas para preencher campos vazios, sempre por ação explícita.

## 5. Consumidores que precisam mudar (antes de tirar as perguntas)
1. **Limites do piloto** (`ProjectJourney`): `network`, `access` e `electricitySource` passam a vir da declaração de Acesso à rede quando existir; sem declaração, o limite aparece como "a confirmar em Acesso à rede" (não como cumprido nem como violado). Resposta antiga da triagem só é usada se não houver declaração e marcada como "da triagem".
2. **Achado de titularidade** (`NAO_APLICAVEL_JUSTIFICADO`): deixa de nascer na triagem; a lacuna de direitos já é pendência de Direitos do projeto. Resposta antiga com esse valor continua gerando o achado até existir declaração de direitos.
3. **Diagnóstico da triagem** (`ProjectTriageDiagnosis`): investimento e prova de dados saem; o resumo aponta para Adicionalidade e para a lista de pendências.
4. **Obrigatoriedade** (`REQUIRED` e etapa 3 da VM0038): passa a exigir só as perguntas que ficam. Nenhum gate fora da triagem usa o estado "PREENCHIDO" (conferido no código).
5. **`vehicleClass`** continua lido da triagem por `OpenCalculationReadiness` e `OpenIrExAntePlans`; nada muda nessas guardas.

## 6. D15 e guardas de versão
- **D15 não precisa de código novo**, desde que a regra de cópia da seção 4 seja implementada. Os campos relevantes que saem (direitos, `network`/`access`/energia da etapa 3) deixam de mudar pela triagem e, copiados sem alteração, não disparam desatualização. Sem a cópia, salvar a etapa 3 deixaria a aplicabilidade desatualizada. A lista relevante passa a ser, na prática: `methodologyCandidate`, `activityType`, `installsChargers`, as 4 perguntas da etapa 3 e `country`. Texto de D15 atualizado na 6c; o código (`OpenApplicability.RELEVANT_TRIAGE`) não mudou e continua comparando direitos, que agora só mudam fora da triagem.
- **Guardas de versão** (início E8, IR, Dy, plano ex ante, vínculos de parâmetro): continuam comparando a versão da triagem, como hoje.
- **Inconsistência encontrada (fora de D16; resolvida na Q7, seção 12):** o pré-cálculo (`OpenCalculationPreflight`, gate `OPEN_APPLICABILITY`/`STALE`) ainda compara só a versão da triagem, sem a regra de D15. Mover o cursor deixa a ficha "atual" mas o pré-cálculo "desatualizado". Ver Q7.

## 7. Perguntas para aprovação
| # | Pergunta | Recomendação |
|---|---|---|
| Q1 | A etapa 3 fica com classe da frota, propulsão, tipo de recarga e individual/agrupado? | **Sim.** São o recorte mínimo; `vehicleClass` e `design` não têm outro dono. Propulsão e recarga ficam como hipótese e a declaração de Acesso à rede vale quando existir |
| Q2 | Direitos saem da triagem? | **Sim.** Direitos do projeto é o dono; respostas antigas continuam valendo até haver declaração |
| Q3 | `baselinePractice` e `expectedBenefit`: ficam opcionais na etapa 2 ou saem? | **Ficam opcionais**, como nota de hipótese; deixam de ser obrigatórios. A linha de base formal continua no guia |
| Q4 | Investimento e receita de carbono saem da triagem para a Adicionalidade? | **Sim** |
| Q5 | Quantidades estimadas de veículos e carregadores saem? | **Sim.** O inventário em Estações é a fonte |
| Q6 | Respostas antigas que saíram: só leitura na triagem, com link para a tela dona? | **Sim**, sem edição na triagem |
| Q7 | Alinhar o pré-cálculo à regra de D15 (fatia separada, com teste de integração)? | **Sim**, mas como fatia própria, porque é guarda de cálculo |

## 8. Fatias de implementação (após aprovação)
| Fatia | Conteúdo | Tipo |
|---|---|---|
| 6a | Cópia no servidor dos campos que saíram (seção 4) e consumidores sem depender deles (seção 5, itens 1 a 3), com testes de integração para projeto novo e projeto com respostas antigas, incluindo D15 continuar "atual" após salvar a etapa 3 | Backend |
| 6b | Triagem com as novas perguntas e obrigatoriedade, bloco de respostas antigas e resumo com "o que será pedido depois"; testes de navegador | Frontend + backend (lista de perguntas) |
| 6c | Atualizar D15, D16, matriz, contrato da triagem e changelog | Especificação |
| Q7 | Pré-cálculo com a regra de D15 | Backend, separado |

Cada fatia preserva revisões, hashes e histórico; nenhuma resposta gravada é alterada.

## 9. 6a — implementado
Correção do levantamento: `associatedInfrastructure`, `metering` e as quantidades também alimentam limites do piloto (a tabela da seção 2 foi corrigida).
- **Lista única** dos campos que saem: `ProjectJourney.RETIRED` (etapa 3: `network`, `access`, `electricitySource`, `associatedInfrastructure`, `metering`, `vehicleCount`, `chargerCount`; etapa 4: `evidenceStatus`, `rightsStatus`, `rightsJustification`, `investmentCommitted`, `carbonConsideredBeforeInvestment`).
- **Cópia no servidor** (`ProjectTriageWizard.carryRetired`, só VM0038; na 6b virou `keepRetired`, para todas as jornadas): ao salvar a etapa 3 ou 4, um campo da lista que **não veio** no envio é copiado sem alteração da revisão anterior. Um `null` explícito continua sendo gravado. Nesta fatia o envio desses campos ainda é aceito, porque a tela atual os pergunta; a recusa entra na 6b junto com a nova tela.
- **Declaração prevalece** (`ProjectTriageTechnicalScope`): o diagnóstico e os limites do piloto leem acesso, identificação da frota, energia e infraestrutura da última declaração de Acesso à rede quando ela existe, por equivalência exata (`restrictedToIdentifiedVehicles` → `network`, `unrestrictedBevAccess` → `access`, `UNKNOWN` → a confirmar). Direitos com titular declarado tiram o efeito de um "não aplicável" antigo. As respostas gravadas e a resposta da API (`answers`) não mudam.
- **Diagnóstico** (`REG-E2 / CF-IMP-010 v3`): saem `INVESTMENT_DECLARATION`, `PRIOR_CARBON_DECLARATION`, `RIGHTS_DECLARATION` e `EVIDENCE_DECLARATION`. `CONFIGURATION` cobre só o recorte inicial; o que saiu e não está confirmado vira `TECHNICAL_SCOPE_TO_CONFIRM` (técnico, com os campos) e **impede** "configuração compatível". Nenhum limite é afirmado a partir de resposta inexistente. Os achados técnicos de adicionalidade e direitos apontam para as telas donas; uma resposta antiga de investimento ainda gera `COMMITTED_INVESTMENT`.
- **Sem mudança:** banco, revisões, D15 (código), guardas de versão, completude das etapas (fica para a 6b).
- **Testes:** `ProjectTriageWizardTest` (cópia, `null` explícito, projeto novo sem campos antigos) e `OpenApplicabilityIntegrationTest` (salvar a etapa 3 só com o recorte não desatualiza a declaração; a declaração vence a resposta antiga nos limites).

## 10. 6b — implementado
- **Perguntas** (`ProjectJourney`): a etapa 3 da VM0038 tem só `vehicleClass`, `propulsion`, `chargerType` e `design` (Q1); títulos "Objetivo e metodologia", "Atividade", "Recorte inicial" e "Local". A etapa 4 pergunta país, estado, localidade e data.
- **Lista única com dono** (`ProjectJourney.RETIRED_ANSWERS`): cada campo que saiu tem rótulo, opções (para ler a resposta antiga) e tela dona: `network`, `access`, `electricitySource`, `associatedInfrastructure` → Acesso à rede; `metering` → Monitorar energia; `vehicleCount`, `chargerCount` → Estações e fronteira; `evidenceStatus` → Evidências; `rightsStatus`, `rightsJustification` → Direitos do projeto; `investmentCommitted`, `carbonConsideredBeforeInvestment` → Adicionalidade. `RETIRED` é derivado dela; o diagnóstico continua lendo as respostas antigas da etapa 3.
- **Recusa** (`ProjectTriageWizard.keepRetired`, todas as jornadas): enviar outro valor para um campo que saiu retorna 400 com o campo e a mensagem "Esta resposta agora é registrada em <tela>". Reenviar o valor gravado (ou branco para um campo nunca respondido) não é mudança e é aceito, para uma aba antiga não perder o trabalho; `null` sobre um valor gravado é mudança e é recusado (substitui a regra da 6a). Campo ausente continua copiado sem alteração.
- **Obrigatoriedade** (Q3): `baselinePractice` e `expectedBenefit` ficam opcionais; a etapa 4 exige só local e data; a etapa 3 da VM0038 exige as 4 perguntas do recorte.
- **API**: `GET` e respostas de comando trazem `retiredAnswers` (etapa, campo, rótulo, valor, rótulo do valor, `section` e título da tela dona), só para respostas gravadas não nulas. `answers` continua igual.
- **Tela**: etapas 3 e 4 mostram, só leitura, "Respostas antigas que agora ficam nas telas técnicas" com link para a tela dona; o resumo mostra "O que será pedido na preparação técnica" com links (VM0038: Acesso à rede, Estações e fronteira, Monitorar energia, Linha de base, Adicionalidade, Direitos do projeto; jornada inicial: Linha de base, Adicionalidade, Direitos do projeto) e as respostas antigas de cada etapa.
- **Exemplo de 25 BEVs**: passa a registrar só a hipótese inicial (sem quantidades, energia, direitos e dados de origem); "25 BEVs" fica na descrição da atividade e no marcador do exemplo. Revisões de exemplo já gravadas não mudam.
- **Sem mudança:** banco, revisões gravadas, D15, guardas de versão, diagnóstico (`v3`).
- **Testes:** `ProjectTriageWizardTest` (projeto antigo semeado como revisão anterior: cópia, recusa de mudança e de `null`, reenvio aceito, `retiredAnswers` com tela dona; projeto novo completo só com a hipótese; exemplo), `OpenApplicabilityIntegrationTest` (D15 continua "atual" com projeto antigo e recusa de mudança) e `frontend/test/project-triage.test.mjs` (bloco só leitura, campos antigos não enviados, etapa 4 sem direitos/dados/investimento, links do resumo, sem rolagem horizontal em 390 px).

## 11. 6c — especificação
- **D15** (`DECISOES_E_DUVIDAS`, backlog): lista relevante na prática depois de D16; limite do pré-cálculo (Q7) registrado. Sem mudança de código.
- **D16**: implementada; regra consolidada (hipótese inicial, tela técnica vale, respostas antigas preservadas e só leitura), PRs #264 e #265.
- **Matriz**: nova capacidade CF-SPEC-S17 (triagem como hipótese inicial), dona #17 (CF-IMP-009), com âncoras de teste de backend e navegador.
- **Contratos**: CF-IMP-009 (o que a triagem persiste, regra consolidada) e CF-OPEN-004 (desatualização só por resposta relevante, CF-SPEC-D15). Catálogo e estado do trabalho atualizados.
- **Pendente na época**: Q7 (feita depois, seção 12).

## 12. Q7 — pré-cálculo com a regra de D15
- **Um só dono da regra**: `OpenApplicability.stale(conexão, projeto, versão declarada, versão atual)` responde se uma resposta relevante mudou (CF-SPEC-D15). A ficha de aplicabilidade e o pré-cálculo (`OpenCalculationReadiness.diagnose`, gate `OPEN_APPLICABILITY`/`STALE`) leem essa mesma resposta; antes, o pré-cálculo comparava só a versão da triagem.
- **Contexto do pré-cálculo** (`OpenCalculationPreflight.Context`): ganha `applicabilityStale`. O construtor sem esse campo, usado em testes puros, continua conservador (qualquer versão nova da triagem = desatualizada).
- **Efeito**: mover o cursor, editar texto narrativo ou salvar uma etapa sem mudar o recorte não desatualiza mais o pré-cálculo; mudar uma resposta relevante continua desatualizando a ficha e o pré-cálculo juntos. Histórico desconhecido continua conservador. O estado do cálculo segue `BLOCKED_*`; nada é liberado para E8.
- **Sem mudança:** banco, as demais guardas de versão da triagem (datas do E8, IR, Dy, plano ex ante, vínculos de parâmetro) e o texto do gate.
- **Testes:** `OpenApplicabilityIntegrationTest` (o mesmo fluxo de D15 confere o gate do pré-cálculo: atual após cursor e texto, desatualizado após mudar a propulsão; o teste falha com a comparação antiga) e `OpenCalculationPreflightTest` (contexto com e sem a resposta de D15).
