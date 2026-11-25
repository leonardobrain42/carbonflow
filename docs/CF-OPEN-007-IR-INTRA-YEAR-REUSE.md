# CF-OPEN-007 — Reuso intra-ano da avaliação estruturada de IR (V97, sem migração)

Continuação de #96/#97/#98, após #242 (prova temporal V96). Recorte: VM0038 v1.1, OPEN BR LDV BEV DCFC SIN GRID_ONLY, ensaio interno. Não admite metodologia, parâmetro, crédito ou E8 normativo. A VM0038 §10.1 vincula IR=1 a parâmetros de linha de base atualizados anualmente; **a regra abaixo é política interna de engenharia**, não prazo ou procedimento Verra.

## Problema
Até V96 a avaliação de IR era associada a um período: o contexto de IR de qualquer outro período do mesmo ano caía em `PERIOD_REFERENCE_MISMATCH`, mesmo com mesmo ano relativo, mesmo ano da fonte e as mesmas referências canônicas. Dois períodos do mesmo ano precisam usar o mesmo expoente de IR (#97 P0.1), sem que cada janela exija nova avaliação.

## Contrato
Um período solicitado reaproveita a avaliação vigente do ano apenas se **todas** as condições valerem; a primeira que falhar mantém o bloqueio:
1. Mesmo projeto, mesmo ano relativo (≥ 2) e mesmo ano da fonte; o ano 1 continua `FIRST_YEAR_REVIEW_REQUIRED`.
2. Avaliação mais recente do par ano/fonte, com parecer `CHECKED_INTERNAL`, candidato 1 e prova anual atual (V96) — regras existentes, inalteradas.
3. O período solicitado tem seleção de frota e fator fóssil elegíveis (mesmas guardas de `references`), mesma triagem e mesmos vínculos que o executor usa.
4. Referências **canônicas equivalentes**, comparadas por snapshot e não por identificador de seleção: snapshot de frota (EVR), snapshot de ratings (AFEC/MPG), candidato fóssil (EF_FUEL) e ciclo de ratings. Qualquer divergência ou ausência → `PERIOD_REFERENCE_MISMATCH`.
5. O período está contido na janela anual congelada na prova V96 e, para **cada uma das quatro métricas** (EVR, AFEC, MPG, EF_FUEL), a vigência declarada cobre todos os dias do período (dias civis America/Sao_Paulo, fim exclusivo). Falha, métrica ausente ou duplicada → `ANNUAL_EVIDENCE_RECHECK_REQUIRED`.

Se uma condição não for satisfeita, não há fallback nem inferência de lacuna: o gate `IR_STRUCTURED` bloqueia o cálculo, como antes. Lacunas e sobreposições entre períodos do ano seguem para a consolidação anual (CF-OPEN-008-ANNUAL-CONSOLIDATION) e não são resolvidas aqui.

## Hash, memória e histórico
- Período idêntico ao avaliado: comportamento e hash de contexto **inalterados**.
- Período reutilizado: o hash do contexto passa a incorporar `OPEN-IR-INTRA-YEAR-REUSE@1`, período avaliado, janela do período solicitado, identificador e hash da avaliação. A memória do run mostra a avaliação reutilizada; o hash difere do de um período avaliado diretamente.
- Nenhuma migração, estado novo ou reescrita de avaliações, provas ou runs anteriores. `STALE` continua dominando: mudança de triagem, calendário, referências ou nova versão torna o contexto obsoleto e exige novo run.

## Limites
Não substitui a avaliação independente, a admissão de EVR/ratings/EFfuel, o ano 1/ex ante, a prova documental real nem a consolidação anual do manifesto. A equivalência é de referências canônicas, não prova de que a fonte é adequada. Datas e localizadores continuam declarações humanas, não verificação criptográfica.

## Testes
`OpenIrIntraYearReuseTest` (núcleo): reuso positivo; ano relativo/ano fonte diferentes; cada referência canônica divergente ou ausente; ano 1; período fora da janela anual e fronteira exclusiva; vigência que não cobre o período em cada métrica; evidência incompleta, duplicada ou ausente; hash de reuso separado do exato. `OpenIrTwoPeriodIntegrationTest` (HTTP, banco real, dados **sintéticos** de teste, início de crédito em 2023 para que o ano 2 esteja no passado): ano 1 com plano ex ante e parecer inicial independente que fixa versão e hash do plano; ano 2 com avaliação, parecer anual de atualização observada e evidência datada dos quatro parâmetros; o segundo período do ano 2 lê a mesma avaliação (mesmo id) como `CHECKED_INTERNAL_NOT_ADMITTED`, enquanto antes do parecer ele é `PERIOD_REFERENCE_MISMATCH`; reexecução com entradas iguais devolve a mesma tentativa (200, versão 1, mesmo hash de contexto); revogação da licença da frota do ano 2 torna o IR dos dois períodos `STALE`, recusa nova avaliação (409), gera nova tentativa `STALE` sem emissão e não afeta o ano 1. Nada é admitido em nenhum passo. Base de teste: `OpenE8TestFixture` (um período do lote ED/EC à entrega de frota e ratings, para qualquer ano e mês) e `OpenE8TestFixtureTest`. Segundo cenário do mesmo teste: evidência declarada válida só até abril cobre fevereiro mas não agosto, e o período de agosto fica `ANNUAL_EVIDENCE_RECHECK_REQUIRED` (não reutiliza; a execução continua bloqueada). Ano 1 com duas janelas: o parecer inicial é por período, não é reutilizado (a 2ª janela começa em `FIRST_YEAR_REVIEW_REQUIRED`); ao revisar a 2ª janela, a 1ª volta a `FIRST_YEAR_REVIEW_REQUIRED`, porque só a versão mais recente do par ano/ano-fonte é a vigente. **Esse último comportamento é o observado hoje, conservador, e não foi decidido como desejado:** se o produto quiser vários pareceres de ano 1 vigentes ao mesmo tempo, é decisão a registrar. **Não cobre:** dados reais autorizados (PEN-003/005).
