# CF-OPEN-007 — IR no ano 1 como estimativa ex ante (Q04, opção B; V97)

Continuação de #96/#97 e do reuso intra-ano ([CF-OPEN-007-IR-INTRA-YEAR-REUSE](CF-OPEN-007-IR-INTRA-YEAR-REUSE.md)). Recorte: VM0038 v1.1, OPEN BR LDV BEV DCFC SIN GRID_ONLY, ensaio interno. **Não admite metodologia, parâmetro ou crédito e não torna nada monitorado.**

## Decisão
Em 24/11/2026 o responsável pelo produto decidiu a **opção B** do documento de decisão Q04/Q05 (PR #247): no ano 1 do projeto o IR pode seguir como candidato interno **somente** pela rota de atualização anual, com plano ex ante conferido, e apenas como **estimativa ex ante**. É uma **política interna reversível** (CF-SPEC-D13), não interpretação autorizada da VM0038; consulta formal a Verra/VVB não foi registrada.

Base no texto (VM0038 v1.1 §10.1, pp.20–21): quando a linha de base usa parâmetros atualizados anualmente, IRᵢ = 1. A Eq. (1) usa expoente y−1; no ano 1 o expoente é zero, de modo que BE do ano 1 não depende do valor de IR. O risco da escolha está nos anos seguintes, cobertos pela prova anual V96. Não há default de IR para o Brasil.

## Contrato
O contexto de IR de um período do ano 1 passa de `FIRST_YEAR_REVIEW_REQUIRED` para **`FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED`** somente se **todas** as condições valerem:
1. Calendário resolvido e ano do projeto = 1.
2. Existe avaliação estruturada do ano 1/ano da fonte, com referências vigentes e **exatamente as do período** (hash de calendário/janela, frota, fator fóssil). Não há reuso entre períodos no ano 1.
3. Rota **anual específica**: frequência ANNUAL e base PROJECT_SPECIFIC (já imposta pelo núcleo de avaliação; outra rota nunca gera candidato 1).
4. Parecer inicial independente `INITIAL_BASELINE_CHECKED` (revisor distinto do autor da avaliação e do plano), com `PLAN_ONLY`.
5. Vínculo `LINKED_CURRENT` ao plano ex ante V94 vigente (ID, versão, hash); revisão do plano ou mudança de contexto torna o vínculo obsoleto.

Qualquer condição ausente mantém `FIRST_YEAR_REVIEW_REQUIRED`, como antes. O estado não abre os estados dos anos seguintes: `CHECKED_INTERNAL_NOT_ADMITTED` continua exigindo atualização observada e prova V96.

## Efeitos
- O ensaio interno do período recebe IR = 1 somente com esse estado e uma avaliação; o hash de contexto inclui o estado e o vínculo ao plano.
- Segregação inalterada: autor e revisor da avaliação não executam o run.
- Consolidação anual: o resultado ganha `calculationBasis`. Vale `EX_ANTE_ESTIMATE` se alguma janela calculada usa a base do ano 1 e `INTERNAL_REHEARSAL` nos demais casos; o valor integra o manifesto. `usableForE8=false` e `canIssueVcu=false` permanecem.
- Migração aditiva **V97** apenas amplia as restrições de `app_open_e8_run` para aceitar o novo estado; linhas V92 continuam válidas. O CI de recuperação V79 passa a reverter e reaplicar também V97.
- Frontend: o estado é aceito pela validação estrita da seleção e recebe rótulo; nenhum cálculo no Angular.

## O que continua bloqueando o ano 1
- **Dy:** sem decisão do Q05 o ano 1 não tem avaliação de Dy aplicável (`FIRST_YEAR_REVIEW_REQUIRED` em Dy). **O cálculo numérico do ano 1 continua bloqueado por Dy**, mesmo com IR liberado.
- Dados reais admitidos de EVR, ratings, EFfuel, EFel e ED/EC (lacunas G03/G04/G06).
- Consolidação anual do ano 1 com várias janelas: como o ano 1 não reutiliza a avaliação entre períodos, ela só fecha com uma única janela anual ou após decisão adicional.
- Admissão normativa, revisão externa e qualquer ato (VCU, VVB, Verra).

## Limites
Plano e parecer são declarações humanas, não prova de atualização nem de existência das fontes. A estimativa ex ante não comprova atualização anual; o compromisso é verificado a partir do ano 2. Se Verra/VVB interpretar de outra forma, o estado pode ser desligado sem apagar histórico.

## Testes
`OpenE8IrContextTest` (estado só com plano vinculado, avaliação vigente, referências exatas e candidato 1; cada ausência mantém o bloqueio; ano 2 inalterado; valor 1 só com avaliação), `OpenE8AnnualMathTest` (base ex ante no resultado e no manifesto; estado ausente bloqueia), `OpenIrAssessmentPersistenceTest` (V97: restrições validadas e ampliadas, FK preservada). Não há teste integrado de um run do ano 1 ponta a ponta (depende também do Dy e da cadeia completa; ver CF-SPEC-G05/G09, #101).
