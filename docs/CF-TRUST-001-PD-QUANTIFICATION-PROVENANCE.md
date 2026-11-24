# Quantificação do PD — transparência da memória canônica

Fatia de leitura de #128, com o mapa documental de #43 e os limites de release de #122.
Não conclui essas issues nem declara a seção pronta.

## Escolha e sobreposição

Foram consultadas as issues [#128](https://github.com/leonardobrain42/carbonflow/issues/128),
[#122](https://github.com/leonardobrain42/carbonflow/issues/122) e
[#43](https://github.com/leonardobrain42/carbonflow/issues/43), além das PRs recentes.
A [#205](https://github.com/leonardobrain42/carbonflow/pull/205) já nomeia memória ou bloqueio
no PD; a [#201](https://github.com/leonardobrain42/carbonflow/pull/201) apresenta os resultados
na Quantificação. Esta entrega acrescenta a explicação da origem a essa mesma projeção.
Reutiliza o editor existente. Não recria o trabalho de calendário, seleção de período,
PE horário ou consolidação anual das PRs #212, #213, #214 e #215.

Base do PR: `be335e8` (main após #215). A fatia foi adaptada ao contrato canônico
`Attempt.timing` de V79 e à memória horária de #214. Alterações locais não publicadas
de outro calendário ficaram fora do PR.

## Correção do job da main

A execução [45313995337](https://github.com/leonardobrain42/carbonflow/actions/runs/45313995337)
falhou em Quarkus/PostgreSQL: o ensaio horário de `OpenPeriodSupportIntegrationTest`
retornou 500 ao violar `app_open_e8_run_check2`. O motor `@2-hourly` já era gravado,
mas o CHECK de V71 aceitava apenas `@1`. V80 amplia somente essa lista, preservando
os checks de memória completa e de tentativa bloqueada. V71 permanece imutável.
O cenário existente ED=2700, PE=1,3 e ER=2,75 verifica a escrita real do motor horário.

## Contrato entregue

`GET /v1/projects/{project}/technical/pd-sections` mantém `calculation` e acrescenta
`calculation.provenance` somente para uma memória de ensaio interno atual e autorizada.
Mapeamento: `OPEN-PD-CALCULATION-PROVENANCE@1`; seção: `quantification`.

- Tentativa, versão e SHA do conjunto de entradas vêm de `OpenE8ProjectRuns.Attempt`.
- Método/edição correspondem ao contrato VM0038 v1.1 do executor existente; motor e versão
  são a identificação exata armazenada na memória, sem usar o build atual como substituto.
- Janela, fuso, ano civil, ano do projeto e datas da janela vêm de `Attempt.timing`
  da tentativa. Vintage da frota e dos parâmetros vem das referências pinadas.
- ED, EC e EFel apontam ao vínculo imutável de energia do período; EVR, AFEC e MPG à
  seleção de frota/ratings. Fóssil, IR e Dy apontam aos pinos exatos da tentativa.
  As consultas usam projeto, período quando aplicável e ID, nunca a última versão como substituta.
- As unidades de energia e ratings são as normalizadas do contrato E8; a unidade fóssil
  vem do pino. IR e Dy são adimensionais por seu tipo, mesmo quando o pino legado não
  possui `normalized_unit`. Não há cópia dos valores licenciados dos operandos.
- Referências de equações são as etapas efetivamente registradas em `memory.trace`.
  O plano de equações previsto não é apresentado como uma execução realizada.
- `REVIEWED_INTERNAL` descreve o ensaio. O estado do kernel também é apresentado.
  Não representa revisão editorial, admissão normativa ou ato de VVB/Verra.

A seção oferece o link à API da memória por ID exato e links às origens com endpoint
de detalhe já existente. A tela da memória oferece acesso à seção atual do PD somente
com `VIEW_DOSSIER`, exibindo tentativa, versão e período para conferência.
O retorno abre a prévia da tentativa mais recente do projeto; uma tentativa histórica
ou outro período não é apresentado como suporte congelado da narrativa.

## Grants e licenças

`VIEW_DOSSIER` não substitui `VIEW_OPEN_E8_RUN`. Sem esse segundo grant, a seção recebe
uma explicação protegida, sem número, período, ID, digest ou link da execução.
O catálogo de guias continua restringindo as seções visíveis por atividade.
Cada origem exige ainda o grant do próprio owner: `VIEW_OPEN_E8_BINDING_CANDIDATE`,
`VIEW_OPEN_E8_FLEET_SELECTION` ou `VIEW_OPEN_PARAMETER_BINDING`.
Sem acesso, a origem recebe rótulo genérico, sem unidade, vintage, ID, versão ou URL.

A leitura canônica da tentativa revalida atualidade e licenças de frota/ratings e fator
fóssil nos owners existentes. Expiração ou retirada do direito requerido deixa a tentativa
desatualizada e retira números e proveniência desta prévia. Não se altera o registro histórico.
A projeção não consulta nem exporta bytes, trechos, nomes privados, locators, hashes de fonte,
valores de ratings/fatores ou identidades dos revisores. Nenhuma nova permissão de conteúdo
é deduzida de uma licença de referência/metadados. As respostas mantêm `private, no-store`.

## Limites

É uma prévia atual de leitura, sem cópia para narrativa, artifact salvo ou manifesto congelado.
`usableForE8` e `marksDocumentReady` permanecem falsos; estados ausente, bloqueado e stale
continuam sem resultados substitutos. Não há fórmula no Angular nem alteração do kernel.

A origem cobre vínculos e pinos canônicos, não o catálogo completo de documentos originais.
Títulos, unidades originais, conversões, locators e revisão por fonte continuam com seus owners.
Pinos ainda não têm endpoint imutável de detalhe; exibem ID/versão/vintage, sem link enganoso
para uma listagem atual. Não há DAG completo, claims, comparação de runs, seleção de suporte
por versão da narrativa, DOCX/PDF, export público, kit de transparência ou admissão real do E8.
Não se atribui revisão a uma pessoa não registrada pelo contrato atual.

## Verificação

- `OpenPdCalculationNoteTest`: memória, ausência, bloqueio, memória hipoteticamente admitida,
  stale e restrição; nenhuma promoção de prontidão.
- `OpenPeriodSupportIntegrationTest`: API/Quarkus com PostgreSQL 16 descartável; igualdade
  entre tentativa e projeção, IDs/versões, janela, unidades e vintages; ausência de conteúdo
  licenciado; grant parcial, retirada do grant de cálculo, isolamento e licença expirada;
  memória histórica preservada e remoção da prévia após mudança do MR.
- `pd-calculation-note.test.mjs`: detalhes, links exatos, teclado, viewport de 390 px,
  rótulo de referência protegida, bloqueio e restrição, narrativa sem alterações e nenhuma
  escrita na API de seções.
- `open-e8-run.test.mjs`: link memória → PD condicionado ao grant documental, com aviso
  sobre a prévia atual e identificação da tentativa.

Resultado local: build Angular aprovado com Node 24.22.3 (dois avisos preexistentes
em `platform-ratings.html`); **238 testes Java aprovados**, sem falhas ou skips,
incluindo as integrações em PostgreSQL 16.16 separado da demonstração;
**16 jornadas de navegador aprovadas**, sem falhas ou skips, com o build local.
`git diff --check` aprovado. Suíte Java completa executada; CI remoto do PR ainda será executado.

Reprodução Java, com um PostgreSQL exclusivamente de teste configurado nas variáveis
`CARBONFLOW_TEST_DB_URL`, `CARBONFLOW_DB_USER` e `CARBONFLOW_DB_PASSWORD`, a partir de `backend`:

```sh
mvn -Dtest=OpenPdCalculationNoteTest,OpenPdAdditionalityNoteTest,OpenPdBaselineNoteTest,OpenPdApplicabilityNoteTest,OpenPeriodSupportIntegrationTest,OpenE8ProjectRunIntegrationTest,TypedProjectGuidesIntegrationTest test
```

Após compilar e servir o frontend, a partir de `frontend`:

```sh
node --test --test-concurrency=1 test/pd-calculation-note.test.mjs test/open-e8-run.test.mjs test/pd-additionality-note.test.mjs test/pd-applicability-note.test.mjs test/pd-baseline-note.test.mjs test/project-guides.test.mjs test/decision-workbench.test.mjs
```

`CARBONFLOW_TEST_URL` identifica a aplicação isolada e `CARBONFLOW_CHROME` o navegador.
As fixtures de teste são autorais e não constituem evidência oficial, homologação humana
ou comprovação de um PD completo.
