# CF-UX-ACOMP — Contrato funcional da experiência de acompanhamento

**Tipo:** especificação transversal de UX, produto e integração; **status:** proposta; **data:** 17/11/2026. A implementação é distribuída entre issues existentes, conforme [mapa de integração](./CF-UX-ACOMP-INTEGRACAO-JORNADA.md).

## 1. Problema e resultado esperado

A interface técnica do piloto já oferece processos, memória de cálculo, registros e atos simulados, mas distribui todas as informações numa superfície muito extensa. O usuário que acabou de criar um projeto precisa descobrir **onde está, o que falta, por que falta e o próximo passo**. O proprietário que acompanha um projeto em curso precisa distinguir **progresso da elaboração, operação, redução estimada/monitorada, revisão interna, avaliação externa e emissão** sem confundir esses estados.

O objetivo não é introduzir outro processo de carbono, outro cálculo ou outra fonte de verdade, mas tornar **as mesmas informações auditáveis compreensíveis e acionáveis** para PP, PREP, REV e OP, com acesso externo VVB quando houver autorização.

### Perguntas que a UI deve responder

1. Qual projeto e ambiente estou consultando?
2. Em que etapa ou período ele se encontra e quais ações são efetivamente permitidas?
3. Quais dados existem, faltam ou precisam de revisão, por quem e em qual prazo aplicável?
4. O que significam os números, em qual unidade, período, escopo e fonte?
5. Como chego da síntese à memória de cálculo, evidências e histórico sem perder contexto?

## 2. Arquitetura de informação e navegação

**Nível conta:** Home (#12) apresenta lista de projetos autorizados, rascunhos, última retomada e CTA de criação. Não deve carregar implicitamente o primeiro projeto.

**Nível projeto:** Visão geral (#27) mostra identidade, ambiente, estado canônico e próxima ação, sem exigir que todos os módulos posteriores estejam prontos.

**Áreas contextuais ao projeto:** 
- **Visão geral** — etapa, requisitos bloqueantes, responsáveis, próxima ação, resumo do período e indicadores disponíveis.
- **Desenvolvimento** — trilhas de preparação do projeto e dependências reais, organizadas a partir de #19 e #13; acesso às guias #25/#29/#28/#32/#31/#30/#36.
- **Emissões e resultados** — comparação BE/PE/LE/ER da memória canônica, seleção de período e vintage, detalhamento de dados/versão/fator via #32/E8-005.
- **Monitoramento operacional** — instrumentos, plano, eventos ED/EC, qualidade, cobertura e ciclos M1/M2 via #31/#42/E8-004/006.
- **Documentos e auditoria** — PD/MR, materiais, fontes, revisões, logs e atos externos, sempre com autoria/versão via #36/#37/E8.

Esses nomes são categorias de navegação, **não cinco novos módulos de backend**. No MVP podem ser agrupados por rotas/painéis existentes, preservando o link profundo para cada módulo. A experiência /pilot continua identificada como simulação.

### Dois níveis de profundidade

- **Leitura resumida:** os quatro tópicos centrais são situação, resultado disponível, pendência e próxima ação.
- **Leitura técnica:** abre parâmetros, fatores, fontes, snapshot, equações, linhas ED/EC e decisões registradas.
- Modo de leitura não é papel de autorização. A capacidade de agir vem exclusivamente do backend e dos grants.
- A mesma métrica pode ter um resumo em múltiplas telas, mas existe um único dado canônico e uma única cadeia de evidência.

## 3. Vocabulário e estados

### Estados de projeto e tarefa

Usar valores canônicos existentes (sem inventar validação externa) e rótulos claros: rascunho, em preparação, pendente de dados, necessita revisão, bloqueado, pronto para ato **interno**, e estados externos somente com prova. Mostrar razões para bloqueio e última alteração.

Não converter automaticamente N tarefas concluídas em “N% do projeto certificado”. Se houver indicador de progresso, definir explicitamente universo, pesos, incidência metodológica, versões e tratamento de N/A; caso contrário mostrar etapas concluídas sobre total **aplicável** sem percentual.

### Natureza dos indicadores

Separar **ambiente** (sintético/sandbox/produção), **fonte** (modelada/medida/importada), **estado de processamento** (rascunho/revisado/congelado) e **estado de auditoria externa** (não submetido/em avaliação/verificado quando comprovado). Esses eixos não são uma única enumeração linear.

- **BE (baseline emissions)** — emissões de referência correspondentes ao cenário comparável, em escopo e vintage definidos.
- **PE (project emissions)** — emissões do projeto, na mesma fronteira de cálculo.
- **LE (leakage emissions)** — fugas conforme a metodologia e a versão efetivamente aplicada.
- **ER (emission reductions)** — resultado do motor metodológico, com ajustes incidentes. **Não implementar fórmula genérica simplificada no frontend.**
- **VCU / crédito emitido** — somente quando registro externo autoritativo e provas forem vinculados; **não é sinônimo de ER monitorada, revisão interna ou verificação**.

As labels devem explicitar que o ensaio PIL-SYN-BR-001 é **sintético**. Os totais do cenário original preservado são M1=6,00 e M2=5,30 tCO2e, conforme README. Não aplicar esses números a novos projetos.

## 4. Contrato de apresentação de um KPI

Todo KPI visível exige, quando aplicável:
- título de linguagem clara e nome técnico expandível;
- valor e unidade (nunca valor implícito);
- período de observação com início/fim e fuso;
- projeto, ambiente, status da origem e da revisão;
- snapshot/run/closure referenciado;
- data de atualização, e aviso de obsolescência;
- fonte(s) e versões, com link de detalhe permitido;
- estado “sem dados” separado do número zero;
- texto curto “o que significa” e “por que mudou” quando a explicação é suportada.

**Ordem de tratamento da ausência:** sem permissão -> 403/estado seguro; ainda não iniciado -> ação de iniciar; dados inexistentes -> solicitar dados; dado parcial -> cobertura/pendência; dado inválido -> erro/incidência; dado suficiente -> exibir valor. Nunca substituir falha ou ausência por zero.

**Comparações:** só comparar números com mesma fronteira, unidade, competência, tipo de resultado e vintage compatíveis; divergências precisam ser sinalizadas, sem gráfico enganoso.

## 5. Fluxos prioritários

### Fluxo 1 — Pessoa cria o primeiro projeto (M1; #12/#14/#17/#18)

Home vazia -> Criar projeto -> Projeto rascunho persistido -> Triagem com retomada -> Resultado preliminar **sem afirmar elegibilidade** -> Visão do projeto com status e próximo passo.

Quando a triagem estiver incompleta, a ação mais importante será “Continuar triagem”, não “Calcular redução” ou “Emitir créditos”.

### Fluxo 2 — Preparador acompanha prontidão (M3; #19/#27)

Abrir projeto -> Ver próxima tarefa derivada do grafo -> Entrar na etapa -> Consultar critério, dados/evidências e responsável -> Anexar/preparar via módulo proprietário -> Salvar -> Voltar com estado atualizado. Bloqueio de adicionalidade não bloqueia trabalho independente de plano de monitoramento.

### Fluxo 3 — Proponente interpreta resultado (M4; #32)

Abrir projeto -> Resultado de emissões -> Selecionar período/vintage -> Ler BE/PE/LE/ER, status e comparação -> “Ver como foi calculado” -> Memória e origem -> Retornar ao mesmo período com contexto preservado.

### Fluxo 4 — Revisão e ciclo posterior (M5; #31/#36/#37/#42)

Operação e medição -> Conferência da cobertura -> Cálculo versionado -> Revisão interna -> Dossiê e ato externo quando aplicável. O dashboard não antecipa conclusões do VVB nem inventa recibo de registro/emissão.

## 6. Contratos técnicos e ownership

**Reutilizar primeiro:** GET /v1/projects/{project}/runs/latest e endpoints de histórico/snapshot já documentados; APIs E8 de períodos, imports, dossier e atos; rotas de criação/membros do roadmap. Evitar novo cálculo em Angular.

**Leitura/aggregação opcional:** se #27 demandar uma visão combinada, criar adaptador de leitura no backend (ex.: GET /v1/projects/{project}/overview), dono #27/#16, sem nova fonte de verdade. DTO conceitual:
- project: id, label, environment, methodologicalPackageVersion;
- currentStage: code, label, reason, canonicalStatus;
- nextAction: taskId, title, route, responsibleRole, blockedReason;
- summary: period, periodState, reductionValueOrNull, unit, resultNature, snapshotRef, lastUpdated;
- alerts: code, severity, sourceRef, targetRoute;
- permissionProjection: capabilities realmente concedidas.

A interface técnica busca detalhes diretamente de #32/#31/#36; **não** preencher todos os campos do DTO com valores derivados de apresentação. Contratos efetivos precisam ser versionados e validados contra o código antes de implementar.

**Autorização:** os filtros de organização, projeto, ambiente, papel e exposição ficam no Quarkus. A UI pode esconder controles por ergonomia, mas isso nunca substitui 401/403/404, escopos ou isolamento. Mudança de projeto descarta caches e requisições pendentes.

**Performance:** medir com a massa de teste e estabelecer orçamento por rota antes de fixar SLO. Lazy-loading dos detalhes técnicos, paginação de registros ED/EC e não descarregar todo o histórico ao montar a home.

**Observabilidade:** registrar erros de renderização/carregamento e eventos de navegação de forma minimizada, sem capturar documentos ou dados sensíveis. Auditoria de atos e mudanças permanece no backend canônico.

## 7. Componentes reutilizáveis Angular propostos

Componentes de apresentação, integrados ao shell #7:
- ProjectIdentityHeader — projeto, ambiente, metodologia e estado;
- NextActionCard — tarefa canônica, motivo e link de ação;
- ProjectProgressView — trilhas por incidência, sem % artificial;
- MetricCard — valor, unidade, período, estado, vazio explícito e botão de explicação;
- MetricProvenancePanel — origem, versão, snapshot e fonte permitida;
- PeriodScopeFilter — intervalo/vintage refletidos no URL;
- AccessibleEmissionsComparison — gráfico com alternativa tabular e resumo;
- EvidenceTrailLink — navegação entre requisito, prova, cálculo, parecer e documento;
- EmptyPartialErrorState — ausência de dados, cobertura insuficiente e falha técnica diferenciadas.

Evitar gerenciar estado de prontidão ou aplicar fórmulas nesses componentes. O shell atual deve continuar funcionando com a rota /pilot até concluir migração e regressões.

## 8. Requisitos de acessibilidade e linguagem

Meta: **WCAG 2.2 AA**, com verificação manual e automatizada, sem alegar conformidade por executar apenas Lighthouse/axe.
- Sem significado exclusivo por cor; status também em texto.
- Navegação por teclado, foco visível, títulos e landmarks semânticos.
- Gráficos com sumário e tabela com cabeçalhos corretos; mesmas unidades e filtros.
- Estados de carregamento comunicados sem bloquear navegação; erros acionáveis e recuperáveis.
- Responsividade em 390×844 e 1440×900, zoom/reflow, alvos e contraste; verificar com telas reais.
- Idioma inicial pt-BR; traduzir siglas no primeiro uso e manter glossário contextual. Datas/horas com fuso indicado e números formatados sem alterar precisão no backend.

Fonte: [W3C — WCAG 2.2](https://www.w3.org/TR/WCAG22/). A metodologia e as regras normativas devem usar versão e vigência do catálogo aprovado; referência de origem: [Verra — VM0038 v1.1](https://verra.org/documents/vm0038-electric-vehicle-charging-systems-v1-1/).

## 9. Aceite executável (cenários Gherkin em português)

### CF-UX-01 — Conta vazia e separação do piloto (#12)

**Dado** usuário autenticado sem projetos autorizados; **quando** abre a home; **então** vê estado vazio e CTA de criação, **e** não vê dados de PIL-SYN-BR-001.

### CF-UX-02 — Primeiro retorno após salvar (#14/#17)

**Dado** um rascunho salvo com triagem incompleta; **quando** sai e retorna; **então** o projeto é selecionável e a próxima ação é continuar a etapa correta, sem apresentar reduções fictícias.

### CF-UX-03 — Informação ausente não é zero (#18/#32)

**Dado** dados de energia ou fontes de cálculo ausentes; **quando** abre indicadores; **então** vê “Dados insuficientes” com motivo e origem, **e não** ER=0 ou indicação de crédito emitido.

### CF-UX-04 — Acesso por papel (#19/#27)

**Dado** dois usuários com grants distintos; **quando** acessam o mesmo projeto; **então** veem ações autorizadas e estado canônico coerente, sem links, contadores ou payloads restritos de outro papel.

### CF-UX-05 — Identidade metodológica do resultado (#32)

**Dado** um snapshot congelado de M1 do piloto; **quando** abre a redução; **então** vê a unidade, período, referência de snapshot, ambiente sintético e natureza do resultado, **e** consegue acessar a memória permitida preservando o período.

### CF-UX-06 — Cenário duplo M1/M2 (#31/#42)

**Dado** o cenário sintético original com M1=6,00 e M2=5,30 tCO2e; **quando** percorre o resumo de períodos; **então** vê os dois totais sem misturá-los com estimativa de outro projeto, sem somar duas vezes recortes e sem transformar revisão de V1/I1 em emissão.

### CF-UX-07 — Evidência e autoridade (#36/#37)

**Dado** um resultado internamente revisado mas não verificado externamente; **quando** consulta seu detalhe; **então** lê “revisão interna”, alcança a prova permitida e **não** encontra selo de verificação ou emissão externa.

### CF-UX-08 — Falha e mudança de contexto (#27/#45)

**Dado** API indisponível ou sessão com troca de projeto; **quando** o usuário atualiza o painel; **então** vê erro recuperável ou estado do projeto recém-selecionado; **e** nenhum dado do projeto anterior aparece no novo contexto.

### CF-UX-09 — Leitura sem visão e sem mouse (#27/#32/#45)

**Dado** leitor de tela e teclado; **quando** usuário percorre cards, filtros e gráfico; **então** acessa todo indicador e sua tabela equivalente, com foco, unidades e status anunciados.

## 10. Plano de validação e métricas

Medir ao longo das fatias, sem atribuir números empíricos ainda inexistentes:
- taxa de conclusão do fluxo “abrir primeiro projeto -> retomar etapa”;
- tempo e taxa de acerto ao localizar próxima tarefa;
- taxa de acerto ao diferenciar ER calculada, redução monitorada, verificação externa e VCU emitida;
- taxa de acerto ao identificar fonte, período, unidade e snapshot do KPI;
- erros 4xx/5xx, regressão de seleção de projeto e vazamentos de cache/tenancy;
- cobertura de acessibilidade, E2E por papel e viewport.

Conduzir teste moderado com perfis representativos (responsável iniciante, preparador, revisor/operador), registro de tarefas e respostas; **não** considerar personas fictícias ou teste automatizado como evidência de compreensão humana.

## 11. Fora do escopo desta demanda

- Recriar baseline, equações VM0038, admissão de fontes ou cálculos E8.
- Novo motor de workflow, motor RAG ou upload paralelo.
- Automação de submissão/registro/emissão no programa VCS.
- Multi-tenant enterprise adicional não previsto.
- Alteração de status normativo ou autoridade em razão de desenho de tela.
- Remover imediatamente a interface do piloto antes do teste de equivalência.

**Ponto de decisão:** aprovar esta especificação transversal e distribuir os cenários CF-UX-01..09 pelas issues donas; revisar também o checklist de #4 e os critérios de #7/#12 após o PR de implementação correspondente.
