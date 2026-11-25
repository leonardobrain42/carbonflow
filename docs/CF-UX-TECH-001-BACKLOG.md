# CF-UX-TECH-001 — Backlog oficial de UX (triagem e preparação técnica)

Mapa levantado em 24/11/2026 a partir do código (`frontend/src`, `ProjectTriageWizard`, `ProjectJourney`, `OpenApplicability`). Base para as próximas fatias; cada fatia atualiza este arquivo.

## 1. Duplicações entre triagem e preparação técnica
| Informação | Triagem | Preparação técnica | Situação no código |
|---|---|---|---|
| Atividade | Etapa 1 (tipo) e etapa 2 (descrição) | Preparar informações | Sem pré-preenchimento |
| País, estado, cidade, data de referência | Etapa 4 | Preparar informações | Digitados de novo |
| Metodologia | Etapa 1 | Preparar informações | Já reaproveitada |
| Propulsão, recarga, origem da energia, infraestrutura associada | Etapa 3 | Acesso à rede | Sem pré-preenchimento, textos diferentes |
| Rede fechada / acesso | Etapa 3 | Acesso à rede | Formulações diferentes |
| Direitos e titularidade | Etapa 4 | Direitos do projeto e ficha do guia de aplicabilidade | Três lugares |
| Compromisso de investimento, receita de carbono | Etapa 4 | Adicionalidade (cronologia) | Duplicada |
| Nº de carregadores / veículos / medição | Etapa 3 | Estações, Linha de base (frota), Monitorar | Números sem ligação com o inventário |

## 2. Duplicações nas demais telas
- Pendências na Visão geral e em Situação do projeto.
- Três listas em Situação do projeto (pendências, achados da ficha, "Requisitos do rascunho").
- "Próximo passo" no Painel, na Visão geral e nas ações das pendências; cartões do Painel repetem o menu.
- Gates do E8 em Quantificação e nas pendências.
- Dois glossários (tela Entender e `plainTerm`).

## 3. Decisões do responsável pelo produto (24/11/2026)
- **D15 — Desatualização relevante (implementada).** A declaração de aplicabilidade só fica desatualizada quando muda uma resposta da triagem de que depende: etapa 1 (`methodologyCandidate`, `activityType`), etapa 2 (`installsChargers`), etapa 3 inteira (escopo da metodologia) e etapa 4 (`country`, `rightsStatus`, `rightsJustification`). Mover o cursor e editar textos narrativos não a invalidam. Histórico desconhecido continua conservador (desatualizada). **Não altera** as outras guardas que comparam versão da triagem (início E8, IR, Dy, plano ex ante).
- **D16 — Triagem como hipótese inicial (a implementar).** A triagem fica com a hipótese (objetivo, atividade, metodologia, local); detalhes técnicos, direitos e adicionalidade passam a viver só nas telas técnicas. Exige preservar revisões antigas, definir o destino das respostas já gravadas e rever D15. Não começar sem desenho aprovado.

## 4. Fatias
| # | Fatia | Tipo | Estado |
|---|---|---|---|
| 1 | Proteção contra perda de alterações em Acesso à rede, Direitos e Datas | Correção | **Feita** (este PR) |
| 2 | Pré-preenchimento sugerido a partir da triagem | Duplicação | **Feita**: Preparar informações e Acesso à rede listam as respostas da triagem e oferecem "Usar respostas da triagem", que preenche só campos vazios e não salva. Só para o titular (a triagem é privada dele). Mapeamento apenas por equivalência exata; frota a combustão, quantidades, medição e direitos não são sugeridos |
| 3 | Uma lista só em Situação do projeto; Visão geral mostra só o resumo | Duplicação | **Feita**: a ficha não repete os achados (todos já são pendências); "Requisitos do rascunho" vira "Dados iniciais pendentes" só quando houver item, com atalho; a Visão geral mostra até 3 ações (nome, situação, ação) e um link para a lista completa |
| 4 | Contador de pendências no menu | Melhoria | **Feita**: cada item do menu mostra quantas ações desta conta (grupo MINE, ação do cliente, destino técnico) apontam para a tela; telas fora do menu somam na tela-mãe; leitura nova ao salvar; resposta antiga não substitui a nova. O cartão duplicado "Evidências" saiu do Painel. Os demais cartões ficam (há teste de jornada que navega por eles); reduzi-los fica para a revisão do Painel |
| 5 | Desatualização só por resposta relevante (D15) | Regra | **Feita** (este PR) |
| 6 | Triagem como hipótese inicial (D16) | Estrutura | Decidida; **desenho proposto** em [CF-UX-TECH-001-TRIAGE-HYPOTHESIS](CF-UX-TECH-001-TRIAGE-HYPOTHESIS.md), aprovado (Q1–Q7). **6a feita** (cópia no servidor, declaração prevalece, diagnóstico v3); **6b feita** (nova triagem, recusa de respostas antigas, bloco só leitura e resumo com o que será pedido); 6c (especificação) e Q7 (pré-cálculo com D15) pendentes |
| 7 | "Por que está bloqueado?" em Quantificação (IR, Dy, gates) | Linguagem | **Feita**: bloco no topo da Quantificação lista todos os gates do servidor que impedem o resultado (da última tentativa, se houver, senão do diagnóstico), na ordem falta de dado → revisão → demais, com estado, próxima ação e papel previsto; nada é recalculado na tela e o texto lembra que resolver tudo não emite crédito |
| 8 | Siglas explicadas e glossário único | Linguagem | **Feita**: `acronyms.ts` é a lista única de siglas; códigos de cálculo (BE, PE, IR, Dy, EVR…) tomam o nome dos mesmos dicionários das telas, sem segundo nome. O glossário completo (Entender) ganha "Siglas usadas nas telas", sem repetir termos já definidos; o das guias continua filtrado e curto. `carbonflow-abbr` expande a sigla (title + texto para leitor de tela), já aplicado na linha de recorte da Quantificação. Apoio de leitura, não definição normativa. Aplicar `carbonflow-abbr` nas demais telas fica para quando cada tela for revisitada |
| 9 | Barra de salvar fixa e botões curtos | Uso | **Feita**: Preparar informações, Acesso à rede, Direitos do projeto e Estações e fronteira (datas) têm barra fixa no rodapé com o estado ("Alterações não salvas" / "Sem alterações pendentes") e o botão curto ("Salvar acesso à rede", "Salvar direitos", "Salvar datas"); o aviso de que salvar não aprova nada fica em texto de apoio, não no rótulo. Em telas estreitas o botão ocupa a largura toda |
| 10 | Roteiro de validação com uso | Validação | Por último |
