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
| 3 | Uma lista só em Situação do projeto; Visão geral mostra só o resumo | Duplicação | A fazer |
| 4 | Contador de pendências no menu, substituindo os cartões do Painel | Melhoria | A fazer |
| 5 | Desatualização só por resposta relevante (D15) | Regra | **Feita** (este PR) |
| 6 | Triagem como hipótese inicial (D16) | Estrutura | Decidida; desenho a aprovar |
| 7 | "Por que está bloqueado?" em Quantificação (IR, Dy, gates) | Linguagem | A fazer |
| 8 | Siglas explicadas e glossário único | Linguagem | A fazer |
| 9 | Barra de salvar fixa e botões curtos | Uso | A fazer |
| 10 | Roteiro de validação com uso | Validação | Por último |
