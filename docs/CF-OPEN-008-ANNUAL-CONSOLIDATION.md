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
- nenhuma janela de período desconhecida que possa sobrepor o ano.

Sem esses requisitos, `totals=null` e `issues[]` descreve o bloqueio e seus
intervalos exatos. Os membros podem conservar números históricos, claramente
separados do total anual indisponível. Zero só aparece como resultado presente.

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
- `OpenE8AnnualCalendarTest`: aniversário brasileiro, 365/366 dias e início
  em 29 de fevereiro, com limites de ano.
- `OpenPeriodSupportIntegrationTest`: ensaio real em Quarkus/PostgreSQL de duas
  horas não vira ano completo; apenas a última revisão entra; consulta repetida
  é estável; parâmetro de outro ano não altera o hash; revisão aplicável exige
  rechecagem e mantém os valores históricos; isolamento por projeto e papel.
- `OpenE8ProjectRunIntegrationTest`: autenticação, ano inválido, projeto de
  terceiros, revogação e ausência de início de crédito; piloto inacessível.
- `frontend/test/open-e8-run.test.mjs`: total ausente e consolidado, referências,
  seleção/validação do ano e viewport de 390 px, junto às regressões do período.

O resultado desta entrega permanece um ensaio interno. Não encerra as issues
amplas #97/#98 nem os gates de homologação #101.
