# CF-ROTEIRO-124 · Sequência de entregas adaptada às 18 etapas da issue #124 (25/11/2026)

**Origem:** a análise do responsável (18 etapas em 4 fases, 30 a 45 PRs, maturidade de 65% a 75%). Os números e percentuais são **estimativas dele, não verificadas por mim**. Este roteiro só reordena o que falta e registra o estado com base no que existe no repositório. Substitui, como guia de prioridade, a "ordem das próximas entregas" da segunda rodada da D19 (os itens dela continuam, agora encaixados nas etapas).

## Estado por etapa (fase 1: infraestrutura e dados nacionais)
| Etapa | Issue | Estado verificado no repositório | Falta |
|---|---|---|---|
| 1. Licenças e operação individual | #92 | D19 e decisão interna do responsável (V109); licenças administráveis (V107); **matriz de permissões dos registros (rc.76, ACR e Gold Standard lidas)**; mapeamento do MCTI ao módulo de licenças | **Registro efetivo da autorização do MCTI** (faltam: ato, SHA-256 do comprovante, vigência, edição, operações cobertas); termos de Verra, UNFCCC, CAR e CAD Trust (leitura bloqueada ou não localizada) |
| 2. Catálogo de veículos e aliases | #94 | Fila de candidatos, decisão interna, classificação com histórico, tela, pipeline offline (rc.60 a rc.70) | Cadastro real a partir de decisões, quando houver arquivo |
| 3. EVR nacional | #94 | Estrutura e prévia; amostra Senatran | **Dado nacional e total oficial** (G03); depende de dado real |
| 4. Ratings PBEV e comparáveis | #95 | Base existente (V49) e extrator offline | Revisão de comparáveis; gasolina C como combustível comparável candidato |
| 5. Fatores fósseis brasileiros | #96 | **Catálogo persistente (V111), revisão em duas bases, conversão rastreável, fronteira `CO2_ONLY` (este PR)** | **Fatores reais** de fonte oficial (EPE, Inventário MCTI, ANP): pesquisa e preparação |
| 6. SIN/MCTI e VT0011 | #96 | Importador offline da série horária (rc.71); caderno VT0011/TOOL07 existente | **Ingestão persistente na V60**, fuso/convenção por documentação oficial, integração VT0011/TOOL07 |
| 7. IR, Dy e créditos concorrentes | #96 | Dy: estruturas V82 a V102 e preparação offline (rc.73); IR: avaliação | **Matriz anual e acumulada versionada e comparável**; evidências dos registros; rateio (G03 só para o que depende do EVR) |

Fases 2 a 4 (etapas 8 a 18, #93, #38/#99, #98, #92/#96, #97, #124, #33/#35, #41, #43/#128, #101/#45): **não foram tocadas nesta conversa**; o estado delas é o que o responsável descreveu e não foi reconferido.

## Sequência adaptada (próximos PRs, na ordem)
0. **(feito, rc.77) Licença pública e declaração interna (D20):** categoria `PUBLIC_LICENSE`, declaração do responsável inerte até aprovação independente, fonte `MCTI-SIN-DESPACHO` e payload pronto. **A declaração em si** (identidade do responsável com MFA) é um passo manual de minutos; não bloqueia os PRs seguintes.
1. **(etapa 1, substituído pelo item 0 quanto ao MCTI) Registro da autorização do MCTI no módulo de licenças** + **matriz de permissões por operação dos registros** (Verra, Gold Standard, UNFCCC, ACR, CAR, CAD Trust). Desbloqueia as operações da etapa 6 e 7. Pede só o que faltar do cadastro (comprovante, vigência, operações).
2. **(feito, rc.78) Ingestão persistente da série horária do MCTI no nível da plataforma** (a V60 não comporta: por projeto, 744 h, CO2e; a vinculação à V60 passa ao item 3). Original: **Ingestão persistente da série horária do MCTI na V60**, com o importador existente, original preservado e revisão em duas bases; fuso e convenção investigados em documentação oficial MCTI/ONS (pendência restrita à normalização temporal).
3. **(etapa 6) Integração VT0011/TOOL07 ao fator elétrico** (consistente com `CO2_ONLY`). **Parte entregue (rc.79):** vínculo V113→V60 por referência, CO2_ONLY com GWP=1 explícito, sem admissão. **Matriz de conformidade entregue (rc.80, `CF-DATA-008`).** **Pendente:** hipóteses de fuso configuráveis nos ensaios, resposta à pergunta R11 (topo do despacho × média), restrição MDL, ensaio E8 integrado.
4. **(etapa 5) Fatores fósseis brasileiros**: leitura das fontes primárias (EPE, Inventário MCTI, ANP), preparação e registro no catálogo, com o confronto com o IPCC 2019.
5. **(etapa 7) Dy: matrizes anual e acumulada versionadas e comparáveis**, sem trocar a rota ativa (D14).
6. **(etapa 7) Evidências de créditos concorrentes**: pesquisa e persistência por operação licenciada.
7. **(etapa 11 e 12) Seleção versionada dos parâmetros e integração ao motor E8**, escolhendo a fronteira por projeto.
8. **(etapa 13 em diante)** requisitos dinâmicos nos formulários e o restante da fase 3, com a verificação do estado real das etapas 8 a 10 antes de começar.

**Regra de entrega mantida:** cada PR com base em `main`, migração aditiva, API, interface, testes, rastreabilidade e a pendência residual precisa; `NOT_ADMITTED` e `usableForE8=false` até os gates; bloqueios descritos por tipo (desenvolvimento, dados, processamento, admissão, uso operacional, certificação externa).
