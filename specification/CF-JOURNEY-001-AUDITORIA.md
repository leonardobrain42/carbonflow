# CF-JOURNEY-001 — Auditoria de alinhamento e cobertura normativa
Corte: main `abf2674acaaefecdde2e914845187d05c41079b1`, 25/11/2026. Revisão documental e estática dirigida, não execução de software nem homologação normativa. Inventário REST: 65 issues (44 abertas, 21 encerradas), 93 Markdown em docs; PR #277 aberto (parecer de adicionalidade), fora do corte. Foram examinados títulos/corpos retornados pelo inventário de issues (triagem geral, leitura dirigida das cláusulas relevantes), árvore de arquivos, README/AGENTS, especificação viva, E6/E9, ordem das entregas, contratos de adaptação E8 e navegação/schema, e amostra dirigida de 15 arquivos de código. Não é inspeção linha a linha de todos os arquivos, comentários/PRs ou templates oficiais. Nenhum requisito novo foi declarado implementado.

## A. Achados e tratamento
| ID | Evidência observada | Classificação / ação | Owner |
|---|---|---|---|
| A01 | README raiz apresenta PIL-SYN no título; E6 já exige adaptador específico e evitar condicionais globais; #4/#89 delimitam prioridade OPEN | Ambiguidade de apresentação, não mudar o recorte. D17 e link canônico no README/AGENTS/E6 | #4 |
| A02 | #19 objetivo literal “oito trilhas VM0038”; E9 descreve navegação por módulos; contrato MODULE-NAVIGATION agrupa preparação em cinco grupos atuais | Potencial conflito se número fixo for contrato de plataforma; sete macroetapas são projeção estável, tarefas/trilhas por pack. Preservar links e histórico | #19/#124/#7/#27 |
| A03 | ProjectTriageWizard.java (45–57, 147, 265–305), ProjectTriageDiagnosis.java (22,62), project-triage.ts (58–66,129–131,228): perfis GENERAL e VM0038, campos e listas estáticas | Limitação de primeiro adaptador; manter D16, projetar seleção/schema por pack; desconhecido não pode herdar VM0038; teste AGN-01/02 | #90/#124/#16/#19 |
| A04 | DynamicProjectTechnical.java (49–54,166–168,192–206) PD_SECTIONS e template condicional VM0038; project-technical.ts (304–307) catálogo reconstruído no cliente; ProjectRequirements.java (35) bool open VM0038 | Acoplamento real a delimitar. Resolver metadata, requisitos, rotas e mapa por contexto de servidor; sem catálogo duplicado cliente. Não retirar guardas até contrato/testes | #10/#90/#13/#124/#43 |
| A05 | TypedProjectGuides.java declara VM0038 OPEN; ProjectGuides/ProjectGuidesResource e esquema UX02A mantêm Map<String,String> compatível; #124 fala estações e tipos específicos | Tipos de domínio válidos no pack OPEN, não schema obrigatório. Persistir entidades nos owners; continuar migrações aditivas | #124/#33/#98 |
| A06 | CarbonCalculator.java (13,68–69) fixa VCS 5.0, PD/MR 5.0B, VM0038@1.1; DocumentRules.java enum Act; RequirementResolver.java (21) Act PD/MR; #16/#97 adaptadores E8 | Calculadora piloto isolada é correta e não deve ser generalizada apagando guardas. Fronteira extensível deve encapsular atos, outputs e regimes; não impor enum ao produto todo | #16/#97/#10/#43/#42 |
| A07 | #10 já prevê resolver método/edição/ato, AST tipada; #90 expõe contexto, seed VM0038; #91 painel sem arrays hardcoded; RequirementGraph e PlatformSourceReadiness inspecionados | Direção compatível; explicitar programa/cenário/ato como dimensões, segundo pack de teste e dados não autorizados bloqueados | #10/#90/#91/#92/#13 |
| A08 | #41 concentra ESG/direitos/engajamento/monitoramento; #30/#31 entregas iniciais declarativas; #43/#122 exigem PD, provas e templates | Lacuna de profundidade e rastreabilidade normativa: J04/J05, ODS, consultas, mitigação, dupla reivindicação e complementares com campos/evidências/atos; detalhar na issue dona, sem reabrir entregas históricas | #41/#10/#13/#35/#43/#122 |
| A09 | #43 antigo “documento oficial montado pelo E8”; #122 já diz compositor owner #43, E8 #97 cálculo; TC-PD-14 prevê segundo método | Clarificar E8-007 fluxo histórico, compositor genérico consome output/adaptador, não depende de fórmula VM0038 global. Não chamar documento interno de ato externo | #43/#122/#128 |
| A10 | #42 M1/M2 e estados VVB/REG/VCU; #101 E2E OPEN e #49 benchmark; #145 entrada explica escopo MVP | Cenários válidos, não sequência/cardinalidade global. Projeção por ato/pack; testar extensibilidade sem prometer outro método real. Separar prévia de autorização | #42/#101/#49/#145 |
| A11 | #3/#89/#100 orientam IA por VM0038, #34/#44/#47 direitos/evals | Preservar assistência contextual via pack, nunca autoridade, escopo primeiro OPEN; manual obrigatório | #3/#100/#34/#44/#47 |
| A12 | #15/#24/#27 papéis VCS, #22 console, #33/#35/#46 evidências/revisão | Papéis operacionais e atos de programa mapeados a capacidades/grants, sem fixar PP/VVB para qualquer programa; autoridade real comprovada; reutilizar owners | #15/#24/#27/#22/#33/#35/#46 |

### Inventário de todas as issues (agrupamento de revisão, sem alterar status)
- Abertas transversais: #3, #4, #7, #10, #13, #15, #16, #19, #22, #24, #27, #33, #34, #35, #39, #43, #44, #45, #46, #47, #48, #49, #122, #124, #128, #145. Aplicar D17 aos contratos genéricos, conservar owners.
- Abertas de domínio/primeiro recorte: #38, #40, #41, #42, #89, #90, #91, #92, #93, #94, #95, #96, #97, #98, #99, #100, #101, #103. #90–92 e #41/#42 têm partes reutilizáveis; não transformar dados/regras de OPEN em contrato global. #38/#40 preservam workbench e primeira regra.
- Encerradas: #5, #6, #8, #9, #11, #12, #14, #17, #18, #20, #21, #23, #25, #26, #28, #29, #30, #31, #32, #36, #37. Preservar aceites históricos e encaminhar extensões ao owner aberto; não reabrir automaticamente.
- Não identificado outro épico explícito determinando exclusividade de setor. Foram identificados acoplamentos concretos e termos de escopo ambíguos acima; não afirmar ausência de outros conflitos sem auditoria integral. Cada PR declara se regra é genérica, de programa, de método, de jurisdição ou de projeto.

## B. Matriz preliminar VCS Standard v5.0, capítulo 3
Base: [documentos oficiais VCS](https://verra.org/programs/verified-carbon-standard/vcs-program-details/), VCS Standard v5.0 capítulo 3; referência ao índice e interpretação preliminar, não reprodução de norma. Conferir seção/edição/vigência e texto licenciado na auditoria atômica. A tabela não certifica cobertura e não transforma requisitos de VCS em universais. Aplicabilidade OPEN indicativa: A = avaliar obrigação aplicável; C = condicional; F = fora do primeiro recorte (não fora da plataforma). A coluna saída indica artefato candidato, sujeito ao template/ato.

| § / assunto | Etapa | OPEN | Campo/evidência a mapear | Saída candidata | Issue dona / teste |
|---|---|---|---|---|---|
| 3.1 requisitos gerais | J01–J07 | A | contexto, escopo e requisitos | PD/MR/gates | #10/#13/#101 AGN-06 |
| 3.2 AFOLU | J02–J05 | F | terra, pools, permanência, riscos conforme pack futuro | template aplicável | #10/#90 AGN-02 |
| 3.3 GCS | J02–J05 | F | armazenamento, monitoramento conforme pack futuro | template aplicável | #10/#90 AGN-02 |
| 3.4 documentação | J06 | A | versão/idioma/manifesto/template | PD/MR | #43/#122 TC-PD-09/10 |
| 3.5 desenho | J02/J03 | A | atividade, configuração, instâncias se agrupado | PD | #93/#124 AGN-06 |
| 3.6 direitos de operar e reduções | J02/J04 | A | titular, contrato, escopo, provas | PD/declarações | #24/#93/#99 TC-PD-08 |
| 3.7 início | J02/J04 | A | eventos/datas/provas e cronologia | PD | #93/#99 AGN-06 |
| 3.8 período de crédito/prazos | J02/J07 | A | janela, regime, atos | PD/linha do tempo | #10/#97/#42 AGN-03 |
| 3.9 escala | J02/J03 | C | classificação/limites aplicáveis | PD | #93/#10 AGN-06 |
| 3.10 localização | J02 | A | coordenadas/fronteira, versão | PD/geolocalização | #124/#93 AGN-06 |
| 3.11 fronteira | J02/J03 | A | ativos/fontes, inclusões/exclusões | PD | #93/#98 AGN-06 |
| 3.12 baseline | J03 | A | cenário, comparáveis, fontes e parecer | PD | #40/#94/#95/#96 TC-PD-02 |
| 3.13 adicionalidade | J03 | A | rota, regulatory surplus, cronologia, provas | PD | #38/#99/#103 AGN-06 |
| 3.14 quantificação | J03/J07 | A | parâmetros, snapshot, resultados, memória | PD/MR | #97/#128 TC-PD-03/05 |
| 3.15 monitoramento | J05/J07 | A | plano, medições, QA e revisão | PD/MR | #41/#98 TC-PD-17 |
| 3.16 desenvolvimento sustentável | J04/J05/J07 | A | ODS/meta/indicador, baseline, medição/prova | PD/MR | #41 AGN-07 |
| 3.17 engajamento | J04/J02 | A | grupos, plano, consulta, queixa, datas | SEP/PD/MR | #41 TC-PD-08/AGN-07 |
| 3.18 salvaguardas ESG | J04/J05/J07 | A | risco, mitigação, responsável, monitoramento | ESG Risk Assessment/Monitoring | #41 AGN-07 |
| 3.19 desvios da metodologia | J03/J06 | C | justificativa, versão, aceite externo quando necessário | PD/MR | #10/#35/#43 AGN-06 |
| 3.20 desvios do PD | J06/J07 | C | alteração, impacto e prova | PD/MR | #46/#42 TC-PD-04 |
| 3.21 dupla contagem/programas | J04/J07 | A | registros, períodos, instrumentos e conflitos | PD/MR/declarações | #41/#93/#42 AGN-07 |
| 3.22 dupla reivindicação/outros créditos/escopo 3 | J04/J07 | C | titular, reivindicação, instrumento, prova | PD/MR | #41/#93/#42 AGN-07 |
| 3.23 rótulos de VCUs | J07 | C | pedido, condições e ato | registro/recibo | #42/#10 AGN-06 |
| 3.24 registros/informações | J06/J07 | A | retenção, versões, acesso, prova | manifesto/ledger | #33/#43/#45 TC-PD-10/11 |

**Estado de todas as linhas:** mapeamento de grupo, NÃO verificado requisito por requisito; F não é N/A automático. Próximo passo obrigatório para #122/#101: decompor cláusulas em IDs atômicos com fonte/edição/vigência/ato/condição, owner, schema, evidência/locator, decisão, dependências, saída/posição exata no template e teste PASS/FAIL/BLOCKED. Confrontar Program Guide, Registration and Issuance, VM0038, VMD0049, VT0008, VT0011, TOOL07 e demais fontes incidentes e transição. Template PD/MR e complementares exatos ainda não inspecionados integralmente neste corte; catálogo de requisitos #10 mantém esta lacuna explícita. Sem fonte/licença, não publicar pack nem declarar PD completo. O kit original também não foi recuperado (G01).

## C. Decisão de execução
Manter #4/#89 e E8 vigente; D17 não promove agnosticidade implementada nem inicia segundo método real. Reusar #10/#90/#16/#19/#124/#43 e seus testes. Adicionar critérios J04 ao #41, cobertura #122/#101, sem segundo épico de implementação. Reconciliar novas PRs antes de cada fatia, inclusive #277; não fechar issues pelo documento. BNDES: validação de mercado, piloto autorizado e impacto em paralelo; nenhuma necessidade de reduzir escopo metodológico futuro.
