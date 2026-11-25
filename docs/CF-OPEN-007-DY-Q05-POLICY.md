# CF-OPEN-007 — Dy: política interna do Q05 (D14), primeira fatia (V98) e totais acumulados (V99)

Continuação de #96/#97 e do documento de decisão [CF-OPEN-007-DECISION-Q04-Q05](CF-OPEN-007-DECISION-Q04-Q05.md). Recorte: VM0038 v1.1, OPEN BR LDV BEV DCFC SIN GRID_ONLY, ensaio interno. **Nada aqui admite metodologia, parâmetro, fonte ou crédito; `usableForE8=false` e o gate `E8_ADMISSION` permanecem.**

## Decisão
Em 24/11/2026 o responsável pelo produto aprovou as quatro recomendações do Q05 como **política interna reversível (CF-SPEC-D14)**, sem consulta formal a Verra/VVB registrada. Base no texto (v1.1): Eq. (8) e §9.4 (pp.19–20), §10.2 (pp.32–33). O texto define ERC/ERF para o ano y−1, mas descreve a medição como total do ano 1 a y−1, não trata o ano 1 do projeto e fixa recorte nacional para DCFC.

| Sub-decisão | Política D14 | Estado nesta fatia |
|---|---|---|
| Q05.1 janela | Registrar a totalização anual e a acumulada; leitura vigente = anual (y−1) | **Anual vigente (já implementada). Totais acumulados declarados (V99): implementados como registro opcional, sem itens**; a versão itemizada, com emissões anteriores à janela anual e matriz de busca estendida, **não está implementada** e fica como próxima fatia |
| Q05.2 ano 1 | Janela exploratória de 12 meses antes do início de creditação; Dy = 1 somente com ausência comprovada e parecer independente | **Implementada** (abaixo) |
| Q05.3 rateio | Base = EVs em circulação; bloqueado até o denominador nacional de EVR ser admitido (G03) | **Implementada** (base restrita + gate) |
| Q05.4 inconclusivo | Bloqueia o cálculo; sem faixa de sensibilidade | **Mantida** (testada) |

## Ano 1: ausência comprovada na janela exploratória
- O ano 1 aceita `NO_FLEET_CREDITS_DECLARED` somente se: início de creditação e recorte OPEN resolvidos (contexto V88 sem gate); **matriz de cobertura nacional completa** (NATIONAL_BR, ERC e ERF por programa, `NO_RESULTS`, janela igual à exploratória, consulta posterior ao seu fim) com licenças exatas vigentes; **ERC = 0, ERF = 0 e nenhuma emissão individualizada**. `FLEET_CREDITS_FOUND` no ano 1 é recusado (exige decisão específica). Pesquisa inconclusiva continua permitida e bloqueada.
- A avaliação grava `calendar_gate = FIRST_YEAR_EXPLORATORY_WINDOW`, janela canônica nula (a exploratória **não** vira ano anterior normativo) e Dy prévio 1. Nova avaliação por versão, hash, idempotência e histórico como as demais; o contexto exploratório integra o hash. Obsolescência por mudança de início, triagem ou licença segue as regras existentes.
- **V98, aditiva:** amplia a restrição de resultado de `app_open_dy_assessment` para esse caso (nunca estreita) e cria uma *constraint trigger* adiada que exige, no commit, o contexto exploratório resolvido.
- Prontidão: o gate `CALENDAR` do ano 1 passa a `SATISFIED_INTERNAL` apenas com a ausência declarada; com parecer interno independente da versão exata, `internalChecksComplete` pode ser verdadeiro. **O estado continua `BLOCKED`**: `NORMATIVE_WINDOW`, `EXTERNAL_EVIDENCE` e `E8_ADMISSION` permanecem bloqueados (o texto do primeiro cita a D14 e a falta de confirmação externa). Ausência declarada não é prova externa.

## Totais acumulados declarados (Q05.1, V99)
- Campos opcionais `cumulativeErc` e `cumulativeErf` na avaliação: totais declarados em tCO2e do **início da creditação até o fim do ano y−1**, incluindo a janela anual. Vêm em par, exigem ano 2 ou posterior e pesquisa não inconclusiva, **não podem ser menores que ERC/ERF anuais**, e totais zerados só valem com ausência declarada no ano anterior.
- A resposta traz `cumulative` com estado `CUMULATIVE_DECLARED_UNITEMIZED_NOT_ADMITTED`, `reading = ANNUAL_PRIOR_YEAR`, os totais e uma prévia ERC/(ERC+ERF). **A leitura vigente continua sendo a anual**: `dyPreview`, o gate de prontidão e o executor não usam o acumulado, e `usableForE8` segue falso.
- Migração V99 aditiva (três colunas anuláveis e uma restrição). O acumulado entra no hash do comando e no da avaliação **somente quando informado**, então hashes, idempotência e histórico anteriores não mudam; uma nova versão sem acumulado não herda o da anterior. Registros imutáveis, como os demais.
- **Não faz:** não itemiza emissões anteriores à janela anual, não estende a matriz de cobertura nacional a essa janela e não prova os totais. Também não decide a interpretação (anual versus acumulada), que continua dependendo de confirmação externa (Q05). Interface administrativa: os campos existem na API; o formulário do Angular ainda não os expõe.

## Itens do acumulado (Q05.1, V101)
- Campo opcional `cumulativeIssuances` (mesma forma de `issuances`), aceito só com `cumulativeErc/Erf`. Cada item fica entre o início da creditação (inclusive) e o início da janela anual (exclusivo); não pode repetir uma emissão anual (programa, projeto e emissão, sem diferenciar caixa). Na prática só há esse intervalo do ano 3 em diante, porque no ano 2 a janela anual começa no próprio início da creditação.
- Conciliação exata: ERC/ERF anuais declarados + soma dos itens = acumulados declarados. Cada fonte (edição com URI oficial cadastrada) exige licença organizacional vigente, como nas demais emissões; revogação bloqueia números e detalhes.
- **V101, aditiva:** tabela imutável `app_open_dy_cumulative_item`. Os itens entram no hash do comando só quando informados, então replays e hashes anteriores não mudam. A resposta passa a `CUMULATIVE_ITEMIZED_NOT_ADMITTED` com `itemCount` (e itens só para quem vê detalhes); a leitura vigente continua anual e `usableForE8` falso.
- **Não faz:** a matriz de cobertura nacional não é estendida a esse período, então os itens não provam exaustividade; o executor E8 não consome o acumulado; nada é admitido.

## Consumo pelo executor E8 (V102, #97)
- **Fonte do Dy:** a última versão da avaliação do **ano de projeto** da tentativa (a janela lida é a do ano y−1, D14). O pino escalar legado não alimenta mais tentativas novas, como já ocorre com o IR (D07). Tentativas anteriores à V102 não têm contexto de Dy: ficam com o gate `RUN_DY_LEGACY` e obsoletas; o histórico permanece.
- **Quando é utilizável (`CHECKED_INTERNAL_NOT_ADMITTED`):** a avaliação existe, não está obsoleta (calendário, triagem, licença vigente), é conclusiva e aritmeticamente consistente, tem parecer `CHECKED_INTERNAL` da **versão e do hash exatos**, não tem rateio por EVs (`ALLOCATION_BLOCKED`, G03) e, se positiva (`FLEET_CREDITS_FOUND`), tem emissões itemizadas conciliadas. Ano 1: só ausência comprovada na janela exploratória (`FIRST_YEAR_ABSENCE_CHECKED_NOT_ADMITTED`). Outros estados: `TIMING_UNRESOLVED`, `FIRST_YEAR_REVIEW_REQUIRED`, `MISSING_FOR_PROJECT_YEAR`, `STALE`, `REVIEW_REQUIRED`.
- **Valor:** `Dy = ERC/(ERC+ERF)` das totalizações **anuais declaradas** (Eq. 8), ou 1 com ausência comprovada. **O acumulado (V99/V101) não entra no cálculo**; a leitura vigente continua anual.
- **V102, aditiva:** colunas `dy_assessment_*` e `dy_context_*` em `app_open_e8_run`, chave estrangeira à avaliação exata e restrição de forma; o estado `REVIEWED_INTERNAL` aceita Dy por pino (legado) ou por contexto checado. O hash da tentativa passa a `E8-RUN-SET-V6-STRUCTURED-IR-DY` e inclui o hash do contexto, que muda com a avaliação, o parecer, os direitos e o calendário.
- **Consolidação anual:** todas as janelas calculadas do mesmo ano precisam ter lido a mesma avaliação (`DY_REFERENCE_MISSING`, `DY_REFERENCE_MISMATCH`), como no IR.
- **Independência:** quem escreveu ou revisou a avaliação não pode ser o ator da tentativa.
- **Não faz:** não admite parâmetro, não substitui a confirmação externa (anual versus acumulada) nem o denominador nacional de EVR; ainda não há tentativa numérica integrada com Dy real (o cálculo com Dy diferente de 1 está só nos testes unitários do kernel e do contexto).

## Rateio por EVs em circulação
- `DOCUMENTED_SHARE` exige `basisUnit = EV_IN_CIRCULATION`; qualquer outra base é recusada (decisão específica). `DIRECT_BR` (1/1) não é afetada. Registros históricos permanecem imutáveis.
- Havendo rateio, `GEOGRAPHIC_ALLOCATION` fica `BLOCKED` com a razão "denominador nacional de EVR ainda não admitido (G03)", **sem estimar** a fração. A contagem de rateios é feita em SQL, de modo que equipe e conta do projeto veem o mesmo diagnóstico agregado, sem conteúdo.
- UI: apenas um *placeholder* na base declarada; nenhum cálculo no Angular.

## Limites e o que continua bloqueando
- O executor E8 consome a avaliação versionada de Dy desde a V102 (seção abaixo), mas só em ensaio interno; faltam dados reais admitidos (EVR, ratings, EFfuel, EFel, ED/EC: G03/G04/G06).
- A ausência é declarada e revisada internamente; não prova exaustividade dos programas nem existência de registros oficiais.
- Se Verra/VVB interpretar de outra forma, a política pode ser desligada sem apagar histórico.

## Testes
`OpenDyAssessmentRulesTest` (acumulados: par, inclusão da janela anual, ano 1, inconclusivo, zero só com ausência) e a integração `OpenProjectParameterBindingsIntegrationTest` (422 nos casos inválidos, 201 com `cumulative`, replay 200, conflito 409, versão sem acumulado não herda e hash diferente), além de `OpenDyReadinessTest` (ano 1: calendário só com ausência, inconclusivo e sem avaliação bloqueiam, anos posteriores inalterados, E8 nunca admitido; rateio bloqueia, atribuição direta não), `OpenDyAllocationsTest` (base única do rateio), `OpenIrAssessmentPersistenceTest` (V98: restrição única e ampliada, gatilho adiado, sem registros órfãos) e a integração Quarkus/PostgreSQL `OpenProjectParameterBindingsIntegrationTest` (ano 1: sem matriz, com créditos, ERC positivo, matriz parcial/janela curta/incompleta → 422; sucesso, replay, prontidão, parecer por revisor distinto, equivalência entre staff e conta do projeto; inconclusivo segue bloqueado; rateio mantém a atribuição bloqueada). O build Angular e os testes de navegador dependem do CI.
