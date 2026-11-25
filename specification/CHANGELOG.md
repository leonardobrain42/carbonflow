# Changelog da especificação viva
## 0.3.0-rc.28 — 25/11/2026 (proposta)
- Linha 2, fatia Dy (D14/Q05.1): totais acumulados declarados de ERC/ERF (início da creditação até o fim do ano y−1) como registro opcional da avaliação (migração aditiva V99, API; hash só quando informado). A leitura vigente segue anual, nada entra no cálculo e `usableForE8` continua falso. Itemização, matriz de busca estendida, formulário do Angular e consumo pelo executor seguem pendentes; G03/G04 continuam abertas (EVR nacional, fontes e licenças são de terceiros).
## 0.3.0-rc.27 — 25/11/2026 (proposta)
- G05, restante sintético: o teste integrado de IR passa a cobrir validade temporal que não cobre um período posterior (`ANNUAL_EVIDENCE_RECHECK_REQUIRED`, execução bloqueada) e ano 1 com duas janelas (parecer inicial por período; a revisão da 2ª torna a 1ª não vigente, comportamento observado a decidir). G05 segue aberta só por falta de dados reais autorizados. Sem mudança de regra, contrato ou migração.
## 0.3.0-rc.26 — 25/11/2026 (proposta)
- Linha 1 do roteiro revisado: teste integrado do IR (`OpenIrTwoPeriodIntegrationTest`) com dados sintéticos: ano 1 (plano ex ante e parecer inicial), dois períodos do ano 2 (parecer anual com evidência datada e reuso), reexecução idempotente e revogação de licença que torna o IR `STALE` nos dois períodos sem afetar o ano 1. CF-SPEC-G05 passa a parcial e continua aberta (ano 1 com várias janelas e dados reais autorizados). Sem mudança de regra, contrato ou migração.
## 0.3.0-rc.25 — 25/11/2026 (proposta)
- Testes (preparação da linha 1 do roteiro revisado): o fluxo que leva um período E8 até a entrega de frota e ratings pela equipe foi extraído de `OpenPeriodSupportIntegrationTest` para `OpenE8TestFixture`, parametrizado por ano e mês, e um teste novo prova que ele prepara três períodos em dois anos-fonte no mesmo projeto. Nenhuma regra, contrato, migração ou comportamento de produção mudou; o teste integrado de IR em dois períodos (CF-SPEC-G05) continua pendente e não é dado como feito.
## 0.3.0-rc.24 — 25/11/2026 (proposta)
- Proposta de revisão da ordem das 10 entregas (CF-ROADMAP-001): Dy passa para a admissibilidade de fontes (D14/G03), issues repetidas com a parte de cada linha, aplicabilidade em paralelo, qualidade contínua e trilha de dependências de terceiros. Só documentação; não é decisão nem prazo.
## 0.3.0-rc.23 — 24/11/2026 (proposta)
- D16 Q7: o pré-cálculo (gate `OPEN_APPLICABILITY`/`STALE`) passa a usar a regra de CF-SPEC-D15, a mesma da ficha de aplicabilidade (`OpenApplicability.stale`); mover o cursor ou editar texto narrativo não o desatualiza mais. Sem migração; demais guardas de versão da triagem, estados `BLOCKED_*` e E8 inalterados.
## 0.3.0-rc.22 — 24/11/2026 (proposta)
- D16 fatia 6c (especificação): D16 implementada e consolidada; D15 com a lista relevante na prática depois de D16 e o limite do pré-cálculo (Q7); nova capacidade CF-SPEC-S17 na matriz; CF-IMP-009 e CF-OPEN-004 alinhados a D15/D16; catálogo e estado atualizados. Só documentação; nenhum comportamento alterado.
## 0.3.0-rc.21 — 24/11/2026 (proposta)
- D16 fatia 6b: triagem pergunta só a hipótese inicial (recorte com 4 perguntas na VM0038, local sem direitos, dados nem investimento; linha de base e benefício opcionais). Respostas antigas são copiadas, recusadas se alteradas (400 com a tela dona) e mostradas só para leitura (`retiredAnswers`); o resumo lista o que será pedido na preparação técnica. Sem migração; revisões gravadas, D15, diagnóstico v3 e guardas de versão inalterados.
## 0.3.0-rc.20 — 24/11/2026 (proposta)
- D16 fatia 6a: campos que saem da triagem copiados sem alteração ao salvar a etapa; diagnóstico `REG-E2 / CF-IMP-010 v3` lê a declaração de Acesso à rede quando existe e marca como "a confirmar na preparação técnica" o que não foi confirmado, sem chamar o recorte de compatível. Sem migração; revisões, D15 e guardas de versão inalterados.
## 0.3.0-rc.19 — 24/11/2026 (proposta)
- UX (fatia 6 do backlog): desenho proposto para D16 (triagem como hipótese inicial), com inventário de consumidores, regra de cópia das respostas antigas e perguntas Q1–Q7. Só documentação; nenhum comportamento alterado.
## 0.3.0-rc.18 — 24/11/2026 (proposta)
- UX (fatia 9 do backlog): barra de salvar fixa com estado de alterações e rótulos curtos nos formulários de aplicabilidade e de dados iniciais. Só interface; versões, If-Match e Idempotency-Key inalterados.
## 0.3.0-rc.17 — 24/11/2026 (proposta)
- UX (fatia 8 do backlog): lista única de siglas, glossário com "Siglas usadas nas telas" e componente de expansão de siglas na Quantificação. Só interface; textos de apoio, sem definição normativa.
## 0.3.0-rc.16 — 24/11/2026 (proposta)
- UX (fatia 7 do backlog): "Por que está bloqueado?" na Quantificação, com todas as pendências que impedem o resultado, próxima ação e papel previsto, lidas dos gates do servidor. Só interface; gates, estados e guardas E8 inalterados.
## 0.3.0-rc.15 — 24/11/2026 (proposta)
- UX (fatia 4 do backlog): contador de ações desta conta por tela no menu da preparação técnica, derivado da projeção de requisitos existente. Só interface.
## 0.3.0-rc.14 — 24/11/2026 (proposta)
- UX (fatia 3 do backlog): uma só lista de pendências em Situação do projeto e resumo na Visão geral. Só interface; projeção de requisitos, estados e regras inalterados.
## 0.3.0-rc.13 — 24/11/2026 (proposta)
- UX (fatia 2 do backlog): respostas da triagem sugeridas em Preparar informações e Acesso à rede, aplicadas só a campos vazios e por ação explícita; nada é salvo automaticamente. Só interface.
## 0.3.0-rc.12 — 24/11/2026 (proposta)
- Registra o backlog oficial de UX (CF-UX-TECH-001-BACKLOG) e as decisões D15 (desatualização da aplicabilidade só por resposta relevante da triagem, implementada) e D16 (triagem como hipótese inicial, a implementar). Telas de aplicabilidade passam a pedir confirmação antes de descartar respostas não salvas.
## 0.3.0-rc.11 — 24/11/2026 (proposta)
- UX: menu da preparação técnica em cinco grupos (Começar, Aplicabilidade, Fundamentação, Medição, Documentação); Entender, Evidências e Fontes saem do menu e seguem acessíveis por URL e pela tela-mãe. Atualiza CF-UX-TECH-001-MODULE-NAVIGATION. Sem mudança de permissão, regra ou backend.
## 0.3.0-rc.10 — 24/11/2026 (proposta)
- UX: vocabulário único para "não informado"/"a confirmar" na declaração de aplicabilidade e aviso normativo permanente no cabeçalho da preparação técnica, em lugar de frases repetidas. Só texto de interface; valores gravados, regras e permissões inalterados.
## 0.3.0-rc.9 — 24/11/2026 (proposta)
- UX: as pendências de preenchimento da declaração de rede aberta passam a apontar direto para "Acesso à rede" ou "Direitos do projeto" (destino e rótulo apenas; estado, grupo e regras inalterados). Atualiza CF-UX-TECH-001-APPLICABILITY-SCREENS.
## 0.3.0-rc.8 — 24/11/2026 (proposta)
- Documenta a reorganização de UX da preparação técnica (CF-UX-TECH-001-APPLICABILITY-SCREENS): "Analisar" vira "Situação do projeto" (somente leitura) e os formulários passam para "Acesso à rede", "Direitos do projeto" e, no caso das datas, "Estações e fronteira". Sem mudança de regra, gate, política ou migração; o ponto de retomada aceita as novas telas.
## 0.3.0-rc.7 — 24/11/2026 (proposta)
- Registra a decisão Q05 (D14, quatro recomendações) e o contrato CF-OPEN-007-DY-Q05-POLICY: ano 1 de Dy por ausência comprovada na janela exploratória (migração aditiva V98), base única do rateio (EVs em circulação) com gate bloqueado até o EVR nacional, texto do gate `NORMATIVE_WINDOW`. A totalização acumulada e o consumo pelo executor ficam pendentes. Atualiza decisões, pendências G04, matriz S07, estado, E8 e o quadro de decisão.
## 0.3.0-rc.6 — 24/11/2026 (proposta)
- Registra a decisão Q04 (opção B, D13) e o contrato CF-OPEN-007-IR-FIRST-YEAR-EX-ANTE: IR do ano 1 como estimativa ex ante; migração aditiva V97; `calculationBasis` na consolidação anual; CI de recuperação V79 inclui V97. Atualiza decisões, pendências G05, matriz S08, estado, E7 e E8. O ano 1 permanece bloqueado por Dy (Q05).
## 0.3.0-rc.5 — 24/11/2026 (proposta)
- Adiciona documento de decisão Q04/Q05 (IR no ano inicial e Dy) com texto verificado da VM0038 v1.1, alternativas, recomendação e registro de decisão a preencher. Somente documentação; nenhuma decisão tomada nem comportamento alterado.
## 0.3.0-rc.4 — 24/11/2026 (proposta)
- Reconcilia a entrada/conta (#145, já no código) com a matriz (CF-SPEC-S16), o estado e E9, citando contrato e testes existentes e os limites declarados. Somente documentação; sem alteração de regra, código, banco ou aceite.
## 0.3.0-rc.3 — 24/11/2026 (proposta)
- Consolidação anual exige a mesma avaliação de IR em todas as janelas calculadas (`IR_REFERENCE_MISSING`/`IR_REFERENCE_MISMATCH`); atualiza contrato anual, matriz S11, estado e E8 P0.4 (parcial). Sem migração, API ou UI.
## 0.3.0-rc.2 — 24/11/2026 (proposta)
- Registra reuso intra-ano de IR (CF-SPEC-D12, contrato CF-OPEN-007-IR-INTRA-YEAR-REUSE), atualiza S08, G02, G05, E7/E8 e estado. Backend: `OpenIrIntraYearReuse` e integração em `OpenIrAssessments.forRun`; sem migração, API ou UI. CI #242/#243 da main registrado como Quarkus verde.
## 0.3.0-rc.1 — 24/11/2026 (proposta)
- Corte de referência: main `507bf268d6955cd0c317d2ff404a905009985907` após #242.
- Introduz E0–E8 como índices de continuidade do kit histórico; E9/E10 para UX/evolução.
- Consolida recortes, matriz de rastreabilidade de síntese, registro de decisões documentadas e PEN agrupadas, critérios de validação e ordem de entrega.
- Indexa contratos incrementais existentes, preserva arquivos e evidências. Adiciona guia de contribuição para agentes e checklist de PR.
- Registra que os ZIPs 0.2.14 e E8 não estão disponíveis para recuperação neste corte e que CI #573 teve falha de infraestrutura Maven 429.
- Sem alteração de regra de negócio, banco, API, UI, autorização ou aceite externo. CF-SPEC não substitui a distribuição original.
