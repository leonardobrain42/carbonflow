# CF-UX-TECH-001 — Fatia 6: triagem como hipótese inicial (D16) — desenho para aprovação

**Estado: proposta, não implementada.** Nada deste documento muda comportamento até o responsável pelo produto aprovar as perguntas da seção 7. Levantamento feito no código em 24/11/2026 (`ProjectJourney`, `ProjectTriageWizard`, `ProjectTriageDiagnosis`, `OpenApplicability`, `OpenCalculationReadiness`, `OpenIrExAntePlans` e guardas de versão).

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
| 3 · `associatedInfrastructure` | — (só obrigatoriedade) | Acesso à rede | **Sai** |
| 3 · `metering` | — (só obrigatoriedade) | Monitorar (medidores ED/EC) | **Sai** |
| 3 · `vehicleCount`, `chargerCount` | — (números sem ligação) | Estações e inventário | **Sai** (Q5) |
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
- **D15 não precisa de código novo**, desde que a regra de cópia da seção 4 seja implementada. Os campos relevantes que saem (direitos, `network`/`access`/energia da etapa 3) deixam de mudar pela triagem e, copiados sem alteração, não disparam desatualização. Sem a cópia, salvar a etapa 3 deixaria a aplicabilidade desatualizada. A lista relevante passa a ser, na prática: `methodologyCandidate`, `activityType`, `installsChargers`, as 4 perguntas da etapa 3 e `country`. Atualizar o texto de D15 quando implementar.
- **Guardas de versão** (início E8, IR, Dy, plano ex ante, vínculos de parâmetro): continuam comparando a versão da triagem, como hoje.
- **Inconsistência encontrada (fora de D16):** o pré-cálculo (`OpenCalculationPreflight`, gate `OPEN_APPLICABILITY`/`STALE`) ainda compara só a versão da triagem, sem a regra de D15. Mover o cursor deixa a ficha "atual" mas o pré-cálculo "desatualizado". Ver Q7.

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
