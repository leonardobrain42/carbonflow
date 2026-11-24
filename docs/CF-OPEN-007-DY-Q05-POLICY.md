# CF-OPEN-007 — Dy: política interna do Q05 (D14), primeira fatia (V98)

Continuação de #96/#97 e do documento de decisão [CF-OPEN-007-DECISION-Q04-Q05](CF-OPEN-007-DECISION-Q04-Q05.md). Recorte: VM0038 v1.1, OPEN BR LDV BEV DCFC SIN GRID_ONLY, ensaio interno. **Nada aqui admite metodologia, parâmetro, fonte ou crédito; `usableForE8=false` e o gate `E8_ADMISSION` permanecem.**

## Decisão
Em 24/11/2026 o responsável pelo produto aprovou as quatro recomendações do Q05 como **política interna reversível (CF-SPEC-D14)**, sem consulta formal a Verra/VVB registrada. Base no texto (v1.1): Eq. (8) e §9.4 (pp.19–20), §10.2 (pp.32–33). O texto define ERC/ERF para o ano y−1, mas descreve a medição como total do ano 1 a y−1, não trata o ano 1 do projeto e fixa recorte nacional para DCFC.

| Sub-decisão | Política D14 | Estado nesta fatia |
|---|---|---|
| Q05.1 janela | Registrar a totalização anual e a acumulada; leitura vigente = anual (y−1) | **Anual vigente (já implementada). O registro da totalização acumulada NÃO está implementado** (exige itens anteriores à janela anual e matriz de busca estendida); fica como próxima fatia |
| Q05.2 ano 1 | Janela exploratória de 12 meses antes do início de creditação; Dy = 1 somente com ausência comprovada e parecer independente | **Implementada** (abaixo) |
| Q05.3 rateio | Base = EVs em circulação; bloqueado até o denominador nacional de EVR ser admitido (G03) | **Implementada** (base restrita + gate) |
| Q05.4 inconclusivo | Bloqueia o cálculo; sem faixa de sensibilidade | **Mantida** (testada) |

## Ano 1: ausência comprovada na janela exploratória
- O ano 1 aceita `NO_FLEET_CREDITS_DECLARED` somente se: início de creditação e recorte OPEN resolvidos (contexto V88 sem gate); **matriz de cobertura nacional completa** (NATIONAL_BR, ERC e ERF por programa, `NO_RESULTS`, janela igual à exploratória, consulta posterior ao seu fim) com licenças exatas vigentes; **ERC = 0, ERF = 0 e nenhuma emissão individualizada**. `FLEET_CREDITS_FOUND` no ano 1 é recusado (exige decisão específica). Pesquisa inconclusiva continua permitida e bloqueada.
- A avaliação grava `calendar_gate = FIRST_YEAR_EXPLORATORY_WINDOW`, janela canônica nula (a exploratória **não** vira ano anterior normativo) e Dy prévio 1. Nova avaliação por versão, hash, idempotência e histórico como as demais; o contexto exploratório integra o hash. Obsolescência por mudança de início, triagem ou licença segue as regras existentes.
- **V98, aditiva:** amplia a restrição de resultado de `app_open_dy_assessment` para esse caso (nunca estreita) e cria uma *constraint trigger* adiada que exige, no commit, o contexto exploratório resolvido.
- Prontidão: o gate `CALENDAR` do ano 1 passa a `SATISFIED_INTERNAL` apenas com a ausência declarada; com parecer interno independente da versão exata, `internalChecksComplete` pode ser verdadeiro. **O estado continua `BLOCKED`**: `NORMATIVE_WINDOW`, `EXTERNAL_EVIDENCE` e `E8_ADMISSION` permanecem bloqueados (o texto do primeiro cita a D14 e a falta de confirmação externa). Ausência declarada não é prova externa.

## Rateio por EVs em circulação
- `DOCUMENTED_SHARE` exige `basisUnit = EV_IN_CIRCULATION`; qualquer outra base é recusada (decisão específica). `DIRECT_BR` (1/1) não é afetada. Registros históricos permanecem imutáveis.
- Havendo rateio, `GEOGRAPHIC_ALLOCATION` fica `BLOCKED` com a razão "denominador nacional de EVR ainda não admitido (G03)", **sem estimar** a fração. A contagem de rateios é feita em SQL, de modo que equipe e conta do projeto veem o mesmo diagnóstico agregado, sem conteúdo.
- UI: apenas um *placeholder* na base declarada; nenhum cálculo no Angular.

## Limites e o que continua bloqueando
- O ano 1 não ganha número: o executor E8 ainda não consome a avaliação versionada de Dy (usa o pino legado) e faltam dados reais admitidos (EVR, ratings, EFfuel, EFel, ED/EC: G03/G04/G06).
- A ausência é declarada e revisada internamente; não prova exaustividade dos programas nem existência de registros oficiais.
- Se Verra/VVB interpretar de outra forma, a política pode ser desligada sem apagar histórico.

## Testes
`OpenDyReadinessTest` (ano 1: calendário só com ausência, inconclusivo e sem avaliação bloqueiam, anos posteriores inalterados, E8 nunca admitido; rateio bloqueia, atribuição direta não), `OpenDyAllocationsTest` (base única do rateio), `OpenIrAssessmentPersistenceTest` (V98: restrição única e ampliada, gatilho adiado, sem registros órfãos) e a integração Quarkus/PostgreSQL `OpenProjectParameterBindingsIntegrationTest` (ano 1: sem matriz, com créditos, ERC positivo, matriz parcial/janela curta/incompleta → 422; sucesso, replay, prontidão, parecer por revisor distinto, equivalência entre staff e conta do projeto; inconclusivo segue bloqueado; rateio mantém a atribuição bloqueada). O build Angular e os testes de navegador dependem do CI.
