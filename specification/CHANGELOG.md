# Changelog da especificação viva
## 0.3.0-rc.2 — 24/11/2026 (proposta)
- Registra reuso intra-ano de IR (CF-SPEC-D12, contrato CF-OPEN-007-IR-INTRA-YEAR-REUSE), atualiza S08, G02, G05, E7/E8 e estado. Backend: `OpenIrIntraYearReuse` e integração em `OpenIrAssessments.forRun`; sem migração, API ou UI. CI #242/#243 da main registrado como Quarkus verde.
## 0.3.0-rc.1 — 24/11/2026 (proposta)
- Corte de referência: main `507bf268d6955cd0c317d2ff404a905009985907` após #242.
- Introduz E0–E8 como índices de continuidade do kit histórico; E9/E10 para UX/evolução.
- Consolida recortes, matriz de rastreabilidade de síntese, registro de decisões documentadas e PEN agrupadas, critérios de validação e ordem de entrega.
- Indexa contratos incrementais existentes, preserva arquivos e evidências. Adiciona guia de contribuição para agentes e checklist de PR.
- Registra que os ZIPs 0.2.14 e E8 não estão disponíveis para recuperação neste corte e que CI #573 teve falha de infraestrutura Maven 429.
- Sem alteração de regra de negócio, banco, API, UI, autorização ou aceite externo. CF-SPEC não substitui a distribuição original.
