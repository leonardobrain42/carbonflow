# Pendências históricas e atuais
## PEN do kit 0.2.14
Fonte verificável: [E8-013 — pendências e efeito no backlog](../evidencias/e8-013/VERIFICACAO.md) e [E8-014 — preparação da homologação](../evidencias/e8-014/VERIFICACAO.md). A tabela reproduz a **associação agrupada** da evidência histórica, não inventa títulos ou status individuais. Sem kit e decisão posterior verificada, todas ficam **a reconciliar**; não declarar resolvidas.

| Referência histórica | Prova solicitada na evidência | Efeito |
|---|---|---|
| PEN-002/007/012 | PP/revisão: datas, edição, meio, conta, poderes, recibo e dados por ato/vintage | Atos E8-008/009/010/014 |
| PEN-003/005 | OP/PREP/revisão: amostra ED/EC autorizada, diagrama, fuso, unidades, calibração, fronteira, cobertura, par e OM/EF | Importação, cálculo e período |
| PEN-004/010 | OP/PP/especialista: direitos, sobreposição, desconto, excedente, requisitos locais e licenças | Aplicabilidade, cálculo, documentos e atos |
| PEN-006 | Designação, competência, independência e parecer assinado; tratamento dos achados | E8-013/014, CT-E5-35 |
| PEN-008 | Métricas em ambiente representativo, metas, hospedagem e responsabilidades | E8-012/014 |
| PEN-009 | Texto autorizado ISO 14065:2020 e conferência setorial no roteiro E8-013 | Revisão setorial dependente; obter acesso sem redistribuição indevida |
| PEN-011 | Representação, geodados, V5#89, escopo VVB, posição/frações e variantes incidentes | Guardas por ato e versão |
| PEN-001 | Não localizado no trecho histórico disponível | Recuperar definição original |

## Lacunas contemporâneas (IDs novos, não PEN do kit)
| ID | Lacuna | Próxima ação | Issue dona |
|---|---|---|---|
| CF-SPEC-G01 | Kit original ausente | Recuperar ZIP, conferir manifesto, mapear IDs e divergências | Governança documental |
| CF-SPEC-G02 | CI #573 Quarkus não executado (HTTP 429) | **Superada pela evidência de CI** (Quarkus verde na main nos merges #242/#243); manter registro histórico | #96/#97 |
| CF-SPEC-G03 | EVR nacional e comparáveis não admitidos | Avaliar cobertura, classificação, fonte e licença | #92/#94/#95 |
| CF-SPEC-G04 | EFfuel/EFel SIN e Dy não plenamente admitidos | Resolver regra, dados, unidades, vigência, provas e integração. Dy: política D14 (ano 1 por ausência comprovada, rateio por EVs bloqueado até G03) implementada em parte; totais acumulados declarados (V99) e itemização do período anterior à janela anual (V101) implementados, fora do cálculo; o executor consome o Dy estruturado do ano de projeto desde a V102 (só ensaio interno, sem admissão); faltam a matriz de cobertura nacional para o período anterior, o uso do acumulado no cálculo e a tentativa numérica integrada com dados reais Dossiês: [CF-DATA-003](../docs/CF-DATA-003-SIN-MCTI-DOSSIE.md) (SIN/MCTI) e [CF-DATA-004](../docs/CF-DATA-004-CREDITOS-CONCORRENTES-MATRIZ.md) (créditos concorrentes) | #96/#97 |
| CF-SPEC-G05 | IR: homologação | Reuso intra-ano (D12) e ano 1 ex ante (D13) implementados; E2E com dados canônicos de dois períodos e do ano 1, reexecução e revogação; ano 1 com várias janelas. **Parcial (25/11/2026):** `OpenIrTwoPeriodIntegrationTest` cobre, com dados sintéticos, ano 1, dois períodos do ano 2, reexecução e revogação ([CF-OPEN-007](../docs/CF-OPEN-007-IR-INTRA-YEAR-REUSE.md)); também sintéticos: validade que não cobre um período posterior e ano 1 com duas janelas (a revisão da 2ª janela torna a 1ª não vigente; comportamento observado, **a decidir** se é o desejado); **segue aberta**: falta prova com dados reais autorizados (PEN-003/005) | #96/#97/#101 |
| CF-SPEC-G06 | ED/EC real e alternativa de eficiência DCFC | Evidenciar amostra, QA, origem e rota derivada | #98/#101 |
| CF-SPEC-G07 | PD/MR interno completo e atos | Projeção, autoria, exportação e manifesto, sem alegar aprovação | #43/#122/#128 |
| CF-SPEC-G08 | Papéis, UX, autorização e jornada real | Confrontar gates e usuários representativos | #13/#19/#24/#124/#101 |
| CF-SPEC-G09 | E2E e validação externa | Provas por cenário, ambiente e ato; decisão humana independente. **Parcial (25/11/2026):** primeiro E2E numérico sintético do ano 2 até `REVIEWED_INTERNAL` com Dy ≠ 1 (`OpenNumericRunE2eIntegrationTest`); **seguem abertos** E2E negativos por ato, ano inteiro coberto, amostra real autorizada, parecer independente e decisão de liberação | #101 |

A atualização de estado exige link de prova, commit/versão e responsável. Não fechar PEN por existir código ou teste sintético.

## Lacunas D17
- CF-SPEC-G10: contratos de pacote e projeção da jornada agnóstica; acoplamentos A02–A07/A09–A12 da [auditoria](CF-JOURNEY-001-AUDITORIA.md); #16/#90/#10/#13/#19/#124/#43/#42. Provar AGN-01–05/08 por fatia, sem liberar segundo método real. **Progresso:** AGN-01 provado para triagem, prontidão de cálculo, requisitos e `open-applicability` (`AgnosticDraftIntakeTest`, rc.35/36); falta o catálogo persistido e governado de pacotes (rc.39 entrega só o catálogo versionado em código; sem autoria, publicação nem licença) e o exemplo de preenchimento da etapa 1 (A03; backend rc.37, tela rc.38) e seções/template do PD por pacote (A04). Estratégia de registro, fontes e código por metodologia: D18 em [CF-PACKAGES-001](CF-PACKAGES-001-ESTRATEGIA-DE-PACOTES.md); fatia 1 (registro de metodologias) entregue por API em rc.41 e com tela de administração em rc.42; fatia 2 (fontes por metodologia) entregue por API em rc.43 e com tela em rc.44; fatia 3 (capacidade efetiva e fixação de versão) entregue em rc.45, com tela em rc.48 e migração explícita de versão em rc.49; fatias 2–5 entregues (fontes por metodologia rc.43/44; contrato de pacote rc.46; A04 rc.47/50; prova do caminho positivo de `SOURCE_REFERENCE` rc.51; A05 rc.52). O que resta do G10 não tem acoplamento de código seguro sem segundo pacote real: ver a situação por linha em [CF-JOURNEY-001-AUDITORIA](CF-JOURNEY-001-AUDITORIA.md).
- CF-SPEC-G11: cobertura atômica VCS, metodologia, ferramentas, regras de transição e templates autorizados (incluindo J04), com requisitos/evidências/saídas/testes por ato; #10/#41/#43/#122/#101, AGN-06/07. Não declarar conformidade por mapa de grupos.
