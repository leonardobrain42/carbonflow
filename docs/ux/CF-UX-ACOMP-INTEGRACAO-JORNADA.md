# CF-UX-ACOMP — Integração da experiência de acompanhamento à jornada existente

**Atualização de implementação do backlog (17/11/2026):** a análise deste documento foi incorporada à [matriz de rastreabilidade por issue](./CF-UX-ACOMP-RASTREABILIDADE-ISSUES.md); os critérios de UX são adendos nas issues existentes, não novos épicos. O PR #55 que introduziu esta proposta já foi mesclado. As instruções abaixo sobre «decisão a tomar» refletem o registro histórico da proposta.

**Status:** proposta para revisão · **data da auditoria:** 17/11/2026 · **repositório:** leonardobrain42/carbonflow · **alvo:** Angular + Quarkus + PostgreSQL · **piloto:** PIL-SYN-BR-001 (sintético, VM0038).

## 1. Decisão proposta

**Não abrir oito épicos de implementação para acompanhamento.** O roadmap CF-IMP já atribui responsabilidades às issues de shell, criação de projeto, jornada, dashboards, quantificação, monitoramento, documentos e homologação. A entrega CF-UX-ACOMP deve ser tratada como requisito transversal de experiência, incorporado aos critérios de aceite desses itens, com um único documento de contrato de apresentação e rastreabilidade. Não duplicar APIs, tabelas de cálculo, motor de tarefas ou ledger de evidências.

A principal integração é:
- Entrada e primeira visão após criar um projeto: #12 (CF-IMP-006), #14 (CF-IMP-007), #17 (CF-IMP-009), #18 (CF-IMP-010).
- Estrutura e navegação coerentes: #7 (CF-IMP-005).
- Progresso, próxima ação e visões por papel: #19 (CF-IMP-014), #27 (CF-IMP-016); dados de prontidão pertencem a #13 (ECA-003), não ao painel.
- Glossário, ajuda e status de fontes: #23 (CF-IMP-015).
- Resultados e explicações BE/PE/LE/ER: #32 (CF-IMP-020) e motor E8-005; fatos e proveniência: #33 (ECA-005).
- Monitoramento ED/EC e períodos: #31 (CF-IMP-021), #42 (CF-IMP-025), E8-004/006.
- Dossiês, evidências e revisão: #36 (CF-IMP-023), #37 (CF-IMP-024), E8-007 e ECA-007 (#35).
- Não regressão, acessibilidade, autorização: #45 (CF-IMP-026) e homologação #48 (CF-IMP-027).

## 2. Evidência inspecionada no repositório

Arquivos verificados em main nesta data:
- [README.md](https://github.com/leonardobrain42/carbonflow/blob/main/README.md): piloto E8 com dois períodos M1/M2, cálculos, monitoramento, dossiês e atos simulados; resultados congelados **6,00 tCO2e (M1)** e **5,30 tCO2e (M2)** pertencem à massa sintética, não a créditos efetivamente emitidos.
- [frontend/src/main.ts](https://github.com/leonardobrain42/carbonflow/blob/main/frontend/src/main.ts): PilotComponent possui leitura da execução, BE/PE/LE/ER, períodos, recortes por vintage, checkpoints e proveniência básica. A seleção de projeto piloto ainda usa projects[0] no componente legado.
- [frontend/src/pilot.html](https://github.com/leonardobrain42/carbonflow/blob/main/frontend/src/pilot.html): há navegação lateral e uma longa página com âncoras para enquadramento, ED/EC, cálculo, monitoramento, PD/MR, listagens, auditorias, emissão, operação e acessos; cartões de períodos e tabela técnica de memória coexistem na mesma experiência.
- [docs/CF-IMP-005-ANGULAR-SHELL.md](https://github.com/leonardobrain42/carbonflow/blob/main/docs/CF-IMP-005-ANGULAR-SHELL.md): a primeira fatia do shell é deliberadamente parcial e preserva o piloto E8; não entrega ainda área de projetos reais.

Estado de GitHub na auditoria: #5, #8, #9 e #11 fechadas; #7 ainda aberta apesar do PR de shell #53 constar mesclado; #12 aberta e PR #54 aberto para home vazia e seletor. #4 mantém checklist desatualizado de #11. Conferir o estado mais recente antes de alterar as issues.

**Limite:** esta auditoria conferiu especificações das issues e os arquivos citados, não executou teste de usabilidade nem validou visualmente todas as telas num navegador. Nada aqui deve ser apresentado como medição de satisfação de usuários.

## 3. Mapa de ownership e prioridade

| Faixa | Issues existentes | Acrescentar aos critérios de aceite | Não duplicar |
|---|---|---|---|
| Agora: shell e conta sem projeto (M1) | #7, #12 | Identidade clara de ambiente; home sem projeto com explicação e CTA; troca de projeto limpa o contexto; acessibilidade e navegação previsível | Outro shell e outro seletor |
| Agora: criação/resultado da triagem (M1) | #14, #17, #18 | Após salvar, confirmação com ID e status de rascunho; próximo passo com destino; rótulos de pendência e de informação desconhecida; nenhuma estimativa inventada | Outra máquina de estado da triagem |
| Jornada e progresso (M2/M3) | #20, #19, #27, #13 | Visão geral da etapa atual, responsável, bloqueios, tarefas e ação permitida; modo guiado/técnico não concede grants; projetar status canônico por papel | Segundo motor DAG, porcentagens arbitrárias |
| Fontes e assistência (M3) | #23, #10 | Conceitos técnicos explicados e fonte/versão acessíveis no detalhe; estados de licença e incerteza respeitados | Cópia de texto normativo sem direitos |
| Resultados e monitoramento (M4/M5) | #32, #31, #42 | BE/PE/LE/ER por período/recorte, unidade, base temporal, status, memória e proveniência; métricas ED/EC em área própria; comparar apenas escopos compatíveis | Recalcular emissões em Angular |
| Evidências e revisão (M4/M5) | #33, #35, #36, #37 | Da pendência chegar a prova/fonte/versão/parecer; revisão interna distinta de VVB/Verra; nenhuma emissão inferida | Repositório documental paralelo |
| Qualidade/release (M6) | #45, #48 | Cenários por papel, estados vazio/erro/carregamento, telas mobile, segurança de filtros e projeto, teste real de compreensão | Checklist cosmético sem execução |

**Nota de dependência:** CF-IMP-016 (#27) depende de convites/grants e tarefas reais. Entretanto a home/retorno da criação deve funcionar ANTES de #27, com um estado mínimo de rascunho e um CTA de continuação definido por #12/#14/#17/#18. Não condicionar o pós-cadastro à existência de todo o motor de jornada, e não simular progresso técnico que ainda não existe.

## 4. Divisão por fatias verificáveis

### Fatia A — Continuidade imediata após a criação (prioridade P0; #12/#14/#17/#18)

**Caso:** usuário sem projetos entra, cria projeto próprio e conclui ou interrompe a triagem.

Aceite adicional:
1. Uma conta vazia mostra “Você ainda não tem projetos” e CTA de criação. Não carrega PIL-SYN-BR-001 por padrão.
2. Ao criar, mostra nome/identificador, ambiente (rascunho/sandbox), estado real e uma ação “Continuar informações do projeto”.
3. Ao pausar, a home apresenta o projeto e o último passo salvo. “Não iniciado”, “não informado”, “a revisar” e “não aplicável” são diferentes.
4. Não mostra ER=0, “créditos emitidos”, percentuais de conclusão ou validação externa sem base de dados/estado canônico.
5. O usuário não ganha poderes de proponente por ser proprietário do rascunho.

### Fatia B — Visão geral e trabalho por papel (#19/#27)

Aceite adicional:
1. Cards de resumo respondem: onde estou, o que falta, o que posso fazer agora e quem é responsável.
2. “Próxima ação” contém motivo, destino, permissão necessária, bloqueio quando houver e link estável para o trabalho.
3. O mesmo requisito/tarefa tem um identificador e um estado canônico; PP, PREP, REV e OP recebem projeções autorizadas.
4. Pendência normativa ou de qualidade nunca se transforma em aprovação externa.
5. Mudança de papel e de projeto invalida contexto e cache; negação de acesso ocorre no servidor.

### Fatia C — Resultados e monitoramento (#32/#31/#42)

Aceite adicional:
1. Resultado sempre identifica **ambiente**, **período**, **unidade**, **natureza** (simulado/estimado/monitorado/revisado/verificado, conforme evidência), **momento da atualização** e **snapshot**.
2. Resumo de BE, PE, LE e ER permite ir à memória por período e vintage, sem mudar cálculo ou arredondamento oficial.
3. Dados ausentes, inadmissíveis, parciais e resultados zero são situações distintas; não inventar ER.
4. “Monitorado” não significa “verificado” ou “emitido”; status externo requer prova e ator legítimo.
5. Toda tela de gráfico possui alternativa tabular acessível com exatamente o mesmo recorte temporal.

### Fatia D — Documentos, auditoria e testes (#36/#37/#45/#48)

Aceite adicional:
1. A partir de um KPI ou pendência, um usuário autorizado alcança fonte, evidência, versão, autor e decisão correspondente.
2. A interface distingue preparação, revisão interna REV, avaliação independente VVB e atos Verra.
3. Testes de regressão validam M1=6,00 e M2=5,30 tCO2e no cenário original congelado, sem confundir fechamento simulado com emissão.
4. Cenários de permissão negada, troca de tenant, ausência de dados, API falha e teclado/mobile aprovados.
5. Teste moderado com usuários-alvo reais ou representativos mede se localizam “situação”, “próxima ação”, “origem do resultado” e “diferença entre redução e crédito”; não usar personagens fictícios como evidência.

## 5. Pontos de conflito a resolver

1. **Duas home pages** — #12 é home da conta/seleção; #27 é dashboard contextual de projeto. Definir transição explícita conta -> projeto -> etapa. Não repetir a home em cada módulo.
2. **Jornada x prontidão normativa** — #19 persiste tarefas; #13 define grafo de requisitos/readiness; #27 apresenta projeção. Nunca calcular prontidão por contagem de checkboxes visuais.
3. **Painel x cálculo** — Angular só apresenta o snapshot e leitura do E8-005 via #32; recortes e agregações precisam respeitar vintage/unidade/datas sem criar lógica metodológica concorrente.
4. **Papéis x visualização** — usar permissões reais, não “modo executivo” como papel de segurança; PP não equivale a REV ou VVB.
5. **Dois ambientes** — manter /pilot demonstrativo e área de projetos reais/sandbox separadas; não migrar dados sintéticos para conta nova silenciosamente.
6. **Ciclo externo** — nunca rotular validação interna ou redução simulada como VCU/VCU emitida, ou resultado de VVB, sem registro e prova correspondentes.
7. **Propriedade da IA** — tutoria opcional #44 não é pré-requisito para ajuda determinística #23; respeitar política de fontes/licenças.

## 6. Recomendação de governança do backlog

- Não criar “UX-ACOMP-01..08” como épicos novos: colidiriam com #7, #19, #27, #31, #32, #36, #42 e #45.
- Manter **CF-UX-ACOMP como especificação transversal**; adicionar links e critérios incrementais às issues donas somente após revisão deste PR, sem substituir contratos já aceitos.
- Se for necessário rastrear QA de ponta a ponta fora dos donos, abrir **no máximo uma issue de integração de aceitação UX**, dependente de #12/#14/#17/#19/#27/#32/#31/#36/#42/#45, sem serviços ou tabelas próprios.
- Atualizar checklist de #4 com cuidado: #11 está fechada, enquanto #7 ainda exige fechamento formal dos critérios visuais; não fechar automaticamente.
- Não fixar prazos de sprints sem capacidade da equipe; priorizar pelas dependências funcionais.

## 7. Referências para revisão

- [Roadmap matriz #4](https://github.com/leonardobrain42/carbonflow/issues/4)
- [Dashboard #27](https://github.com/leonardobrain42/carbonflow/issues/27)
- [Motor de jornadas #19](https://github.com/leonardobrain42/carbonflow/issues/19)
- [Home sem projetos #12](https://github.com/leonardobrain42/carbonflow/issues/12)
- [Visão do piloto atual no README](https://github.com/leonardobrain42/carbonflow/blob/main/README.md)
- [Especificação operacional de acompanhamento](./CF-UX-ACOMP-REQUISITOS.md)

**Decisão a tomar no PR:** aprovar esta matriz de ownership e usar CF-UX-ACOMP como critérios transversais de qualidade; não iniciar uma implementação concorrente de dashboard ou monitoramento.
