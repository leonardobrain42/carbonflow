# Changelog da especificação viva
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
