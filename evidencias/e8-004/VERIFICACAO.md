# E8-004 · Verificação local · 16/11/2026

**SIMULAÇÃO** · PIL-SYN-BR-001 · kit 0.2.14 · Flyway V4. A E8-002 fornece sessão e escopo. O comissionamento, o inventário e as revisões da E8-003 delimitam os períodos, os ativos e as leituras.

A [massa CSV](../../backend/src/main/resources/fixtures/e8-004-ed-ec.csv) usa o cabeçalho físico da E6 e reproduz casos de identidade, duplicata, cobertura e correção dos ensaios E4 e E7. Foi recebida do ator SIM-OP, origem SIM-OP-01, autorização sintética SIM-AUT-ED-EC v1, mapeamento SIM-CSV-UTF8-ED-EC v1, período M1 v1 e inventário da ficha v16. O arquivo bruto do lote `48dbe791-b859-4585-adc1-7323d797ba62` voltou byte a byte com SHA-256 `10adb35d688e0ec6ff6dd7a04d1fcc9a562931bdacc46231d567159768b5aeaa`. O [relatório exportado](IMPORTACAO.json) inclui as linhas originais, as decisões e o retrato salvo.

## Casos executados

| Referência | Resultado observado |
|---|---|
| CT-E5-04/07/09/12 · prévia e metrologia | O relatório fechou em 11 linhas. Três candidatas, uma ausente, duas inválidas, duas pendentes, uma duplicata, um conflito de ID e uma sobreposição. ED de 12.500 Wh virou 12,5 kWh. A linha ausente continuou sem valor, zero sem prova ficou pendente e zero comprovado foi admitido no teste Java. Unidade inválida, ausência de fuso e leitura que atravessa a troca de medidor ficaram fora da admissão. |
| CT-E5-07 · origem e escopo | Autorização divergente foi recusada. O download foi negado para outra organização e para o papel VVB. A prévia concluída deixou a seleção vazia até uma decisão do REV distinto de PREP. |
| CT-E5-08/30 · reenvio e retomada | Reenviar os mesmos bytes sob outro nome preservou um artefato e um lote. O fato físico idêntico com outro ID de origem foi vinculado à versão existente. Uma falha de banco foi injetada no segundo trecho de um lote de 10.000 linhas. As 250 confirmadas permaneceram. A versão obsoleta foi recusada e a retomada concluiu as 10.000 sem repeti-las. |
| CT-E5-09/30 · sobreposição, correção e histórico | A tentativa de admitir outra linha sobreposta ou mudar o valor do mesmo ID sem predecessora foi recusada. A proposta vinculada, admitida por REV distinto, criou a versão 2 de 25 kWh. A versão 1 de 30 kWh e o retrato anterior continuaram recuperáveis com a mesma seleção. |
| CT-E5-27 · limites do lote | O recebimento aceitou 10.000.000 bytes e recusou o byte seguinte com HTTP 413. A prévia aceitou 10.000 linhas e recusou por inteiro o lote de 10.001, com o bruto recuperável e sem linhas candidatas. A paginação retornou as próximas linhas pelo cursor. |
| CT-E5-38, recorte de interface | O Chrome percorreu recebimento por OP, consulta da prévia, proposta por PREP, admissão por REV, reenvio, correção e retrato anterior. |

O build Angular e o pacote Compose passaram. O Maven `clean verify` executou **14 testes Java**. Quatro jornadas no Chrome passaram no Compose isolado, incluindo as regressões das três issues anteriores. Os scripts `e4/conferir_dados.py` e `e7/conferir_e7.py` também passaram como referências da especificação. [Testes Java](/home/leonardorodrigues/Documentos/ChatGPT/CarbonFLow/backend/src/test/java/br/carbonflow/pilot/EnergyImportsTest.java)

## Pendências e limites

- **PEN-003/005.** OP fornece amostra ED/EC autorizada de 30 dias, diagrama, metrologia e cobertura. REV e especialista validam formato operacional, fronteira, baseline, OM/EF e admissibilidade. O mapeamento atual usa CSV UTF-8 de intercâmbio com um registro por linha. XLSX, contadores acumulados e a prova agrupada para conciliar um total atravessando o corte com seus segmentos medidos seguem pendentes.
- **PEN-008.** Engenharia mede carga e recuperação representativas. Leonardo decide metas, responsabilidades e hospedagem. As guardas de 100.000 fatos por classe/ano e 2 GB de brutos por projeto estão implementadas. Os testes desta rodada exercitaram os limites por lote. A série de 20 lotes por classe, o teste da cota acumulada, os anexos de 25 MB e carga concorrente em escala não foram executados.
- **PEN-011.** PP e REV confirmam a incidência externa de representação, geodados, canal, VVB, frações e posição. A amostra e as provas desta entrega são sintéticas. A E8-005 consumirá a seleção versionada. A E8-006 fará o fechamento, a avaliação integral de cobertura e a reabertura de períodos. A homologação real fica na E8-014.
