# CF-OPEN-007 #96 — TOOL07 v7.0 Simple OM Option A1: apuração preliminar (fatia 11)

> **Status:** cálculo preliminar auditável, NÃO admitido em VM0038/E8, não é estimativa oficial do SIN, não valida fonte ou categoria LCMR, não substitui VT0011 e não gera BE/PE/ER/VCUs.

## Fontes e escolhas delimitadas

Os controles desta fatia seguem **TOOL07 v7.0**, consultada na documentação oficial:

- [TOOL07 v7.0 (PDF UNFCCC)](https://cdm.unfccc.int/methodologies/PAmethodologies/tools/am-tool-07-v7.0.pdf), §6.3, parágrafo 40 e equações 1/2: para a via de participação LCMR em cinco anos, o indicador selecionado precisa ser **estritamente inferior a 50%**. A equação 1 é a média das participações anuais; a equação 2 é a razão entre os totais agregados. Podem produzir conclusões diferentes. O TOOL07 oferece **outras rotas de elegibilidade** (média da carga LCMR versus LASL, média hidrelétrica de longo prazo), que não estão implementadas nesta fatia; portanto, falhar este teste não comprova inelegibilidade definitiva.
- TOOL07 §6.4.1, parágrafos 46–49, equações 3 e 4: **Simple OM Option A**, média dos fatores de CO₂ ponderada por geração líquida, sem unidades low-cost/must-run. **A1** utiliza consumo de combustível, poder calorífico e fator de CO₂ para obter o fator de cada unidade. Não aceitar o fator unitário arbitrário sem insumos identificados.
- [VT0011 v1.0, Verra](https://verra.org/methodologies/vt0011-electricity-system-emission-factors-v1-0/): extensão da TOOL07 para projetos que aumentam consumo da rede. A ponderação de Case 2 de OM/BM da V61 continua separada do cálculo da OM; não converter CO₂ em CO₂e automaticamente.

## Núcleo matemático puro

`OpenTool07SimpleOmMath` recebe, sem valores implícitos:
1. **Cinco anos contíguos (y−4 a y)**: total MWh da rede, LCMR MWh de cada ano. Exige total positivo, LCMR não negativo e não maior que total. Computa e apresenta Eq1 e Eq2; o usuário seleciona explicitamente uma delas. O resultado preserva precisão e indica `fiveYearLcmrScreenPassed`; nada é aprovado automaticamente.
2. **Unidades geradoras não LCMR do ano y**: código único, geração líquida MWh positiva; por unidade, uma ou mais linhas de combustível com código único, consumo **em toneladas**, NCV **GJ/tonelada**, fator de **tCO₂/GJ** e SHA-256 *declarado* da evidência. Multiplica cada FC × NCV × EF, soma emissões da unidade (tCO₂), divide pela geração líquida para obter EF unitário, e calcula OM = Σemissões/Σgeração (tCO₂/MWh). Também expressa aritmeticamente tCO₂/kWh (÷1000). Rejeita unidade ou combustível duplicado, valor negativo, origem sem hash e casas decimais além do limite estabelecido.
3. **Reconciliação**: geração das unidades não LCMR = total MWh da rede menos LCMR MWh em y. Bloqueia sem fechamento exato. Nenhuma tolerância silenciosa ou substituição de uma usina ausente por zero.

Este algoritmo é **somente A1 com consumo em massa (t)**, abrangendo um único ano no modo `EX_POST`; não executa vintages ex ante com três anos, opções A2/A3, Option B, Simple adjusted OM, Dispatch data OM ou Average OM. Outras unidades de FC (por exemplo m³ de gás) não podem ser inseridas como se fossem toneladas; sua extensão exige conversão rastreada e fontes.

**Atenção ao sinal de elegibilidade:** a triagem quinquenal passar não demonstra que todas as usinas do SIN foram corretamente inventariadas, que sua classe LCMR é legítima, que as importações, o sistema de referência ou a seleção de vintage foram resolvidos. O campo `usableForE8` fica **falso**, inclusive se as equações fecharem. Fator CO₂ não é valor horário CO₂e sem tratamento normativo.

## V62 — persistência e segurança

- `app_open_tool07_simple_om_run`: organização/projeto/ambiente, MR M1/M2, FK do caderno V61, **versão de parecer REV e SHA do caderno** fixados, método Simple OM A1, ano, escolha da Eq1/Eq2, fonte inventário, SHA reivindicado, fonte e SHA quinquenal, justificativas de FC/NCV/EF, fronteira e importações, indicadores, CO₂/geração e fator, digest de entrada canônica e autor. **CHECK `state=PRELIMINARY_NOT_ADMITTED` e `usable_for_e8=false`.**
- `app_open_tool07_simple_om_lcmr`, `app_open_tool07_simple_om_plant`, `app_open_tool07_simple_om_fuel`: séries e insumos de cada unidade/fonte com FKs de projeto e snapshot imutável, sem duplicar CSV do sistema.
- `app_open_tool07_simple_om_command`: idempotência transacional por ator/chave/fingerprint. Todas as tabelas com triggers de imutabilidade.

A API de preparação exige o caderno **Simple OM / EX_POST**, revisão interna `CHECKED_INTERNAL_NOT_ADMITTED`, fonte EFel/MR ainda vigentes, versão exata do parecer e `If-Match` com número do último run. Após mudança de MR, EFel, caderno ou parecer, a consulta histórica marca **STALE_NOT_ADMITTED**. Revisão posterior do run pela mesma origem não altera histórico (novo run e versão; anterior SUPERSEDED).

## APIs e experiência

- `GET /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/tool07-simple-om-a1/{workbook}/runs`;
- `POST` no mesmo endereço: `{workbookId,workbookReviewVersion,inventorySourceReference,inventorySha256,fiveYearSourceReference,fiveYearSha256,fuelSourceExplanation,systemBoundaryNote,importsExplanation,calculation:{dataYear,shareApproach,shares:[{year,totalGridMwh,lcmrMwh}],plants:[{plantCode,netGenerationMwh,fuels:[{fuelCode,consumptionTonnes,ncvGjPerTonne,efTco2PerGj,sourceSha256}]}]}}` e headers `If-Match` e `Idempotency-Key`.

DRAFT_OWNER e PREP MONITORING podem criar; REV MONITORING somente consulta; OP e membros CALCULATION-only não leem dados brutos de usinas. `DynamicProjectScope` confere projeto/organização. Saídas com `Cache-Control: private, no-store`. Pré-cálculo E8 mostra apenas `E8_TOOL07_SIMPLE_OM_M1/M2` e o status resumido; nenhum valor bruto vaza para CALC-only.

O formulário fica **Monitoramento → MR → EFel → caderno VT0011 → Simple OM Option A1**, sem novos submenus dinâmicos. O usuário cola dados quinquenais e planta/combustível em linhas CSV e informa fonte e SHA *declarados*; não existe fator numérico padrão.

## Testes e próximo passo

Oráculo **inteiramente sintético**: cada um de 2022–2026 tem 1.000 MWh de geração de rede e 300 MWh LCMR (30%). Duas usinas não LCMR com 400 + 300 MWh; combustíveis com 20 + 40 tCO₂ total; OM ilustrativa = 60/700 = 0,085714285714285714 tCO₂/MWh. Não é fator do SIN nem dado MCTI/ONS. Testes da lógica de LCMR comprovam divergência possível entre Eq1 e Eq2 e impedem o caso ≥50%; JUnit/Quarkus garantem série, idempotência, multi-tenant, FK de origem, revisão e STALE após MR v2, sem alterar o piloto `PIL-SYN`.

**Pendente #96/#97/#98:** autenticar os dados de usinas e combustíveis, comprovar abrangência e classificação LCMR, regras de importação/interconexão e exceções da TOOL07/VT0011, inclusão de fontes em diferentes unidades e combustíveis, dados de 3 anos ex ante e seleção de métodos alternativos, CO₂ versus CO₂e horário, avaliação independente substantiva, admissão normativa por finalidade e operação real do motor E8. Sem esses passos não há fator admissível nem resultado de redução de emissões.


## CF-TRUST-001 #128 — Memória auditável por ano, usina e combustível (fatia TR-01)

O retorno da API `OpenTool07SimpleOmRuns.View.preview` acrescenta **sem alterar
as equações existentes** duas projeções derivadas exclusivamente dos insumos
imobilizados em V62:

- `shareCalculations[]`: ano, geração total (MWh), geração LCMR (MWh) e
  razão anual LCMR/total arredondada para até 24 casas decimais pelo algoritmo.
  As cinco parcelas alimentam a Eq. 1; a Eq. 2 utiliza os totais quinquenais
  já armazenados. Isso explica a diferença entre média anual e razão agregada.
- `plantCalculations[].fuelCalculations[]`: código do combustível,
  FC (toneladas), NCV (GJ/t), EF (tCO₂/GJ), contribuição `FC×NCV×EF`
  (tCO₂), referência à Eq. 4 e SHA-256 **declarado** da fonte. Os
  combustíveis são ordenados por código para replay determinístico;
  emissões da unidade e fator unitário continuam na projeção preexistente.

Valores representados por `NUMERIC(30,12)` no PostgreSQL são normalizados
**somente na apresentação**, removendo zeros finais não significativos; nunca
se altera um dígito material ou se preenche valores ausentes. A validação
contra excesso de precisão significativa permanece. A tabela e a memória
JSON exibem os mesmos resultados do motor Java; o Angular **não recalcula**.

### Consulta individual e acesso

`GET /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/tool07-simple-om-a1/{workbook}/runs/{runId}`

A rota recupera inclusive versões fora do limite de 20 resultados da listagem,
sob o mesmo `DynamicProjectScope` e ação `VIEW_OPEN_SIMPLE_OM_A1`.
Verifica **projeto, período, caderno e ID**; recursos inexistentes/de outro
escopo retornam 404; resposta JSON usa `Cache-Control: private, no-store`.
A tela oferece acesso à memória JSON da versão selecionada e alerta sobre
`STALE_NOT_ADMITTED` preservando o conteúdo histórico.

A origem de cada SHA, inventário e série quinquenal permanece uma
**declaração**, não uma verificação criptográfica do arquivo original, nem
decisão de admissão técnica. O resultado continua marcado `usableForE8=false`
e não representa EFel horário, tCO₂e ou reduções de emissões/VCUs. O futuro
explorador #128 deve ligar essas referências ao ledger #33/#92 e ao E8 #97
**somente quando esses dados forem materialmente admitidos**, com permissão e
licença de acesso. Não se criou catálogo ou segundo motor de cálculo.

### Cobertura de QA

- JUnit: cinco participações anuais, soma de duas linhas de combustível,
  unidades e referência de equação, ordem determinística, SHA declarada,
  equivalência antes/depois do roundtrip `NUMERIC(30,12)`.
- Quarkus/PostgreSQL: GET individual por projeto/caderno/versão, negação
  OP e usuário externo, resultado estável no armazenamento, imutabilidade
  de valores históricos após revisão de MR, `STALE` com acesso autorizado.
- Angular/CI: templates compilam e continuam a usar as mesmas rotas da
  jornada de monitoramento. Nenhuma mudança de layout do menu principal.

**Não conclui #128:** matriz completa de rastreabilidade ao PD, seleção
de fontes admissíveis, motor E8 real, export documental e verificação normativa
continuam nas issues #33/#43/#92/#97/#122.
