# Consulta anual de quantificação — #97 / #98

Esta fatia continua o PE horário do PR #214. A aba Quantificação permite consultar
um ano do projeto e recebe do servidor a consolidação de BE, PE, LE e ER dos
ensaios internos. O ano usa o início de crédito confirmado, o calendário civil
de São Paulo e aniversários com fim exclusivo, como o executor por período.

## Contrato

`GET /v1/projects/{project}/technical/open-calculation-years/{year}` exige a
mesma permissão `VIEW_OPEN_E8_RUN` das memórias de período. A resposta é privada,
`no-store`, auditada e limitada ao projeto. Não há comando de publicação anual,
novo ledger, migração ou alteração de uma memória existente.

O serviço escolhe **uma única tentativa, a mais recente, por período**, sem o
limite de 20 revisões da listagem histórica. Usa também as janelas dos dossiês
de MR vigentes: uma janela sem tentativa, ou substituída depois do cálculo,
não desaparece da avaliação. As mesmas verificações de vigência do executor
identificam fontes, vínculos, QA, declaração e parâmetros desatualizados.

Uma consolidação numérica exige simultaneamente:

- início de crédito confirmado com os eventos e a triagem vigentes;
- cobertura completa do intervalo anual, sem lacunas ou sobreposições;
- janelas contidas no ano e memórias que registrem o mesmo ano do projeto;
- tentativas atuais `REVIEWED_INTERNAL`, com resultados presentes;
- nenhuma janela de período desconhecida que possa sobrepor o ano;
- **a mesma avaliação estruturada de IR em todas as janelas calculadas** (id, versão e hash), conforme #97 P0.1: dois períodos do mesmo ano usam o mesmo expoente. Memória sem IR registrado gera `IR_REFERENCE_MISSING`; avaliações diferentes geram `IR_REFERENCE_MISMATCH`. A referência faz parte de `members[]` e do `manifestSha256`. Apenas janelas calculadas e atuais entram na comparação; as demais já bloqueiam por `RUN_NOT_CALCULATED`/`RUN_STALE`. Combina com o reuso de [CF-OPEN-007-IR-INTRA-YEAR-REUSE](CF-OPEN-007-IR-INTRA-YEAR-REUSE.md); sem migração, API nova ou alteração de memória existente.

Sem esses requisitos, `totals=null` e `issues[]` descreve o bloqueio e seus
intervalos exatos. Os membros podem conservar números históricos, claramente
separados do total anual indisponível. Zero só aparece como resultado presente.

## Consistência de frota e fator entre janelas
Política **interna** (não é regra da Verra), a mesma ideia do reuso intra-ano de IR: os parâmetros anuais formam um conjunto por ano do projeto. Cada membro traz `canonical` (snapshot de frota, snapshot de ratings, ciclo e candidato do fator fóssil), lido da linha imutável da tentativa pela mesma identidade canônica usada no reuso de IR (`OpenIrIntraYearReuse.Canonical`). Para as janelas calculadas e atuais: frota, ratings ou ciclo diferentes geram `FLEET_REFERENCE_MISMATCH`; candidatos de fator diferentes, `FUEL_REFERENCE_MISMATCH`; referência ausente ou incompleta, `FLEET_FUEL_REFERENCE_MISSING`. Qualquer um deixa `totals=null`. As referências entram em `members[]` e no `manifestSha256`; como a consulta é uma projeção não persistida, o hash de consultas antigas não é recalculado nem comparável. Efeito: duas janelas do mesmo ano que usem seleções de frota ou fatores diferentes exigem nova tentativa antes do total anual. **Hipótese a confirmar:** se o produto quiser aceitar conjuntos diferentes por janela quando a fonte do ano for a mesma, essa política precisa ser decidida e registrada.

## Matemática e referências

Cada total é a soma decimal exata dos respectivos valores canônicos das
memórias de janela. ER soma os resultados de cada janela **após o Dy próprio
daquela execução**; não aplica um Dy médio ao saldo anual. O total ER apresentado
é arredondado uma única vez a duas casas, `HALF_EVEN`, após a soma de `erTco2e`.
Não soma `erDisplay`, não agrega ratings e não refaz as equações de janela.

`members[]` registra período, UUID/versão da tentativa, hash das entradas,
janela, estado e valores. `manifestSha256` identifica deterministicamente o
contexto, as referências, os estados de vigência e o resultado desta consulta,
independentemente da ordem de leitura. Uma mudança de versão ou de vigência
altera esse hash. As tentativas originais continuam imutáveis e consultáveis
pelos endpoints existentes.

Esta é uma **projeção atual**, não um fechamento anual persistido nem um
manifesto documental aprovado. Para reproduzir uma consulta no futuro é
necessário conservar sua resposta e suas referências; consultar novamente
o ano utiliza os dados vigentes. Vínculo de fechamento anual ao PD/MR e
publicação de manifesto congelado continuam com #97/#122. A admissão normativa
das entradas e a avaliação completa de ERC/ERF para Dy permanecem com #96/#97.
`usableForE8=false` e `canIssueVcu=false` em todos os resultados desta fatia.

## Verificação

- `OpenE8AnnualMathTest`: cobertura, lacunas de início/meio/fim, sobreposições
  inclusive janelas contidas, duplicatas, fronteiras exclusivas, ano incorreto,
  ausências, versões desatualizadas, decimais inválidos, ER negativa, precisão,
  Dy diferente por janela e manifesto determinístico/versionado.
- `OpenE8AnnualMathTest` (IR): mesma avaliação em todas as janelas; versão, hash ou id diferentes bloqueiam; IR ausente/incompleto bloqueia; a referência altera o manifesto; janela não calculada não gera divergência artificial.
- `OpenE8AnnualMathTest` (frota/fator): mesma frota, mesmos ratings e mesmo ciclo em todas as janelas calculadas (`FLEET_REFERENCE_MISMATCH`), mesmo candidato de fator fóssil (`FUEL_REFERENCE_MISMATCH`), referência ausente ou incompleta bloqueia (`FLEET_FUEL_REFERENCE_MISSING`), janela não calculada não é comparada e a divergência altera o manifesto.
- `OpenE8AnnualCalendarTest`: aniversário brasileiro, 365/366 dias e início
  em 29 de fevereiro, com limites de ano.
- `OpenPeriodSupportIntegrationTest`: a consulta real devolve, por membro, as quatro referências canônicas (snapshots de frota e ratings, candidato do fator e ciclo); ensaio real em Quarkus/PostgreSQL de duas
  horas não vira ano completo; apenas a última revisão entra; consulta repetida
  é estável; parâmetro de outro ano não altera o hash; revisão aplicável exige
  rechecagem e mantém os valores históricos; isolamento por projeto e papel.
- `OpenE8ProjectRunIntegrationTest`: autenticação, ano inválido, projeto de
  terceiros, revogação e ausência de início de crédito; piloto inacessível.
- `frontend/test/open-e8-run.test.mjs`: total ausente e consolidado, referências,
  seleção/validação do ano e viewport de 390 px, junto às regressões do período.

O resultado desta entrega permanece um ensaio interno. Não encerra as issues
amplas #97/#98 nem os gates de homologação #101.
