# E9 — Jornada e experiência (índice novo)
A especificação de acompanhamento [RF-1..RF-9](../../docs/ux/CF-UX-ACOMP-REQUISITOS.md), [integração](../../docs/ux/CF-UX-ACOMP-INTEGRACAO-JORNADA.md) e [rastreabilidade](../../docs/ux/CF-UX-ACOMP-RASTREABILIDADE-ISSUES.md) já define o contrato de experiência e issues donas. Preservar esses IDs; não recriar epic paralela.

## Entrada
A raiz `/` oferece iniciar projeto próprio, acessar projetos ou explorar o piloto sintético; cadastro, confirmação e recuperação usam as APIs existentes de conta, sem alegar e-mail real ([CF-UX-ENTRY-001-ISSUE-145](../../docs/CF-UX-ENTRY-001-ISSUE-145.md); matriz CF-SPEC-S16). Nome de espaço de trabalho não concede PP, representação nem grants.

## Navegação
Conta → projetos autorizados/retomada → visão geral do projeto → preparação por metodologia → aplicabilidade, linha de base, adicionalidade, quantificação, monitoramento, salvaguardas, PD e revisão. A jornada mostra próxima tarefa e motivo do bloqueio, sem transformar tarefa em aprovação. Trilhas em paralelo quando possível.
- [Guia de navegação](../../docs/CF-UX-TECH-001-MODULE-NAVIGATION.md), [formulários tipados](../../docs/CF-UX-TECH-001-TYPED-FORMS-UX02B.md), [baseline/adicionalidade/PD](../../docs/CF-UX-TECH-001-BASELINE-ADDITIONALITY-PD-INTEGRATIONS.md).
- [Bússola](../../docs/CF-UX-TECH-001-UX07-JOURNEY-COMPASS.md), [projeção de requisitos](../../docs/CF-UX-TECH-001-UX08-REQUIREMENT-PROJECTION.md), [roadmap UX](../../docs/CF-UX-TECH-001-UX08-P0-ROADMAP-STABLE.md).
- [IR](../../docs/CF-OPEN-007-IR-JOURNEY.md): planejamento ex ante na linha de base/premissas, parecer de staff na área de fontes, histórico do projeto em leitura e prova anual datada.

Distinguir papel previsto, pessoa, poder e grant; estado ausente vs zero; informação técnica vs normativa; resultado ensaio vs estimativa vs medido vs ato externo. Evitar menu instável e perda de contexto. Testar 390x844 e desktop, teclado, erro, retomada, permissão e revisão de versão; validar com pessoas representativas. Não anunciar porcentagem de certificação ou quantidade de recargas sem dado correspondente. Issues #19/#27/#24/#124/#101 e #45 para aceite, sem assumir encerramento.

## D17 — Jornada de referência
A [jornada canônica J01–J07](../CF-JOURNEY-001.md) passa a orientar navegação, schemas, tarefas, documentos e critérios de aceite. As telas e grupos atuais são projeções do recorte OPEN; não substituem as sete macroetapas nem se tornam obrigatórios em outros métodos. Preserve D16, deep links, owners e dependências paralelas. #19/#124/#27/#7 fazem transição incremental; #41 aprofunda J04/J05.

## Linha de base ex ante (P0, 26/11/2026)
- [Especificação detalhada e mockups](../../docs/CF-UX-TECH-001-FROTA-BASELINE-EX-ANTE.md): etapa EVR/comparáveis contextual, seleção ex ante sem MR, cobertura e estados, autorização e handoff monitorado; complementa UX-08D/#124 e #40. Não implementado por este adendo.

- [Premissas, adicionalidade, direitos, revisão e composição documental](../../docs/CF-UX-TECH-001-PREMISSAS-ADICIONALIDADE-PD.md): complemento à seleção de frota ex ante; PT-01–08, PT-QA e 18 aceites planejados.

- [Consolidação de guias, fronteira, sustentabilidade e planejamento](../../docs/CF-UX-TECH-001-GUIAS-FRONTEIRA-J04-J05.md): complemento P0 da #124; nove figuras e PT-T19–33; sem auditoria integral das telas operacionais/documentais.

- [Monitoramento, quantificação e revisão](../../docs/CF-UX-TECH-001-MONITORAMENTO-QUANTIFICACAO-REVISAO.md): detalha PT-13–16, PT-17–26; PT-24 editor e insumos reservados. QA visual ilegível, análise do código; sem implementação.

- [PT-24 — autoria fundamentada do PD](../../docs/CF-UX-TECH-001-REDIGIR-PD-AUTORIA-FUNDAMENTADA.md): editor híbrido, biblioteca contextual, blocos/claims, consistência, diffs, IA opcional, 11 figuras e 28 aceites planejados.
