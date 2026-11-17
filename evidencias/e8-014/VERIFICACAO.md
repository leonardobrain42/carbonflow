# E8-014 · Preparação da homologação

**PIL-SYN-BR-001 · Kit 0.2.14 · 16/11/2026.** O roteiro está preparado. O aceite da implementação e as decisões de uso real aguardam as respectivas provas. DEC-012 permite avançar com os preparativos no recorte sintético.

1. **OP** usa o [roteiro de amostra e conciliação](AMOSTRA_ED_EC.md) para reunir os 30 dias de ED/EC autorizados, as evidências de medição e a documentação da origem. **REV** prepara o controle independente e homologa o escopo exercitado no ambiente identificado.
2. **PP** preenche o [checklist e o registro por ato](PRONTIDAO_POR_ATO.md) com edição, poderes, conta, canal e evidências. A entidade que emite cada retorno conserva sua autoria.
3. **Leonardo** registra as decisões de PEN-008, designa o revisor da [E8-013](../e8-013/VERIFICACAO.md) e decide a liberação aplicável. REV vincula o parecer, as resoluções impeditivas e a conferência setorial ao relatório.

## Ensaios IMP e provas para o aceite

Plano de execução conforme `03_especificacao/e7/ROTEIRO_HOMOLOGACAO.md` e `03_especificacao/e5/ACEITE_E_SAIDA.md` do kit. As evidências locais indicam o ponto de partida de cada ensaio no ambiente de homologação.

| Ensaio | Evidência já disponível | O que registrar ou completar para o aceite | Responsável |
|---|---|---|---|
| IMP-01 · Implantação e migração | [E8-011](../e8-011/VERIFICACAO.md) exercitou migração com rollback. [E8-012](../e8-012/VERIFICACAO.md) registrou o build e as regressões da V12. O preparo subiu V12 com banco novo. | Fixar build, imagem, migrações e configuração de destino. Migrar cópia com as duas janelas, testar indisponibilidade e rollback e comprovar integridade de estados, concessões, hashes e quatro parcelas. | Engenharia e REV |
| IMP-02 · Autorização e versões | [E8-002](../e8-002/VERIFICACAO.md), [E8-007](../e8-007/VERIFICACAO.md) e [E8-009](../e8-009/VERIFICACAO.md) exercitaram segregação, versões e autoria. | Conferir os papéis configurados, dois projetos e outra organização de teste. Registrar recusas por acesso revogado, autoaprovação e autoria imprópria, além da revisão das variantes públicas e reservadas. | Engenharia, REV e PP |
| IMP-03 · Importação e dados | [E8-004](../e8-004/VERIFICACAO.md), [E8-006](../e8-006/VERIFICACAO.md) e ensaio de 30 dias abaixo, todos sintéticos. | Obter a amostra autorizada, processar CT-E5-29 e conciliar cada linha com a referência independente. Conferir perfil, inventário, origem, metrologia, reenvio e cobertura da janela se houver fechamento. | OP e REV |
| IMP-04 · Corridas, falhas e atos | [E8-010](../e8-010/VERIFICACAO.md) e [E8-011](../e8-011/VERIFICACAO.md) exercitaram reserva, reenvio, perda de resposta e retomada com dados fictícios. | Exercitar dois operadores e falhas nas fronteiras de confirmação do ambiente de aceite. Reconciliar com a execução serial, provar efeito único e verificar M2 em coleta durante V1/I1. | Engenharia, OP, PP e REV |
| IMP-05 · Desempenho e capacidade | [Série de carga E8-012](../e8-012/VERIFICACAO.md) com importações e fechamentos em Compose local. | Medir no envelope e na topologia de aceite. Guardar amostra, métricas, erros, p95, tempos de recebimento e processamento, e comparação com as metas decididas. Completar os limites e cenários de sessão ainda pendentes em PEN-008. | Engenharia e Leonardo |
| IMP-06 · Cópia e restauração | [E8-011](../e8-011/VERIFICACAO.md) restaurou dois ciclos em Compose. [E8-012](../e8-012/VERIFICACAO.md) conservou os limites de volume e topologia. | Repetir com volume representativo e domínio de falha independente. Conferir ponto íntegro, RPO/RTO, inventário, permissões e conciliação do intervalo antes de liberar marcos dependentes. | Engenharia e REV |
| IMP-07 · Disponibilidade, alertas e jornada | [E8-012](../e8-012/VERIFICACAO.md) fez ensaio curto de disponibilidade e alertas locais, mais sessões automatizadas de teclado. | Medir a janela útil por um mês, validar a entrega de alertas e executar cinco sessões moderadas com PREP, REV e PP. Guardar resultados, impedimentos e evidências de acessibilidade no ambiente de aceite. | Engenharia, operação e moderador |
| IMP-08 · Custo e comparação | [Comparação E8-012](../e8-012/CUSTO.md) com premissas declaradas. | Confirmar carga, cotações, margem, escopo de cópia e suporte e decisão de Leonardo sobre hospedagem, metas e responsáveis. | Engenharia e Leonardo |

Os casos CT-E5-02, CT-E5-03, CT-E5-04, CT-E5-07, CT-E5-09, CT-E5-10, CT-E5-15, CT-E5-19, CT-E5-20, CT-E5-21, CT-E5-23 e CT-E5-24 integram a verificação da jornada e das guardas pertinentes. CT-E5-29 cobre a amostra, CT-E5-31 cobre o restauro, CT-E5-35 exige o parecer independente, CT-E5-37 cobre custo e CT-E5-38 cobre as sessões moderadas.

### Registro de execução a preencher

Para cada caso, identifique a build/imagem, esquema, motor, perfil de importação e versão de modelo que incidirem. Registre configuração/topologia, relógio e zona, identidade e origem autorizada da massa, hashes, estado inicial e responsável pelo ensaio. Preserve logs e artefatos de prova com as referências no relatório.

| Caso e escopo | Ambiente/build, massa e origem | Esperado e observado | Resultado, prova e responsável | Pendência, condição de repetição e efeito no aceite |
|---|---|---|---|---|
| | | | | |

REV registra resultado por caso como aprovado, reprovado, impedido ou não aplicável com justificativa. O parecer de E8-013, a decisão de PEN-008 e o registro de PP por ato têm evidências e responsáveis próprios. A decisão de Leonardo indica qual escopo foi liberado.

## Ensaio de preparação executado

Angular e Quarkus no Compose descartável `carbonflow-e8-014-preparo`, PostgreSQL 16, Flyway V12, inventário simulado na revisão 16. O Compose reutilizou as camadas de build. O teste da E8-003 preparou e comissionou o inventário pela interface. Em seguida, o ensaio HTTP usou as identidades sintéticas OP, PREP e REV para importação, proposta, admissão e seleção. Os IDs e hashes estão no [registro do ensaio](ENSAIO_SINTETICO.json).

| Recorte da amostra sintética em M1 · America/Sao_Paulo | ED esperado e observado | EC esperado e observado |
|---|---:|---:|
| [17/12/2027, 01/01/2028) · 15 dias | 180 kWh | 225 kWh |
| [01/01/2028, 16/01/2028) · 15 dias | 210 kWh | 255 kWh |
| **30 dias** | **390 kWh** | **480 kWh** |

A massa contém uma linha de cada classe em cada dia, 12.000 Wh de ED e 0,015 MWh de EC nos primeiros 15 dias e 14.000 Wh de ED e 0,017 MWh de EC nos 15 seguintes. Uma linha adicional sem deslocamento de fuso foi inválida. Todas as 61 linhas foram conferidas com o bruto original. As 60 leituras válidas foram comparadas por chave, instantes e conversão decimal e admitidas na seleção. O reenvio recuperou o mesmo lote. Um segundo CSV com cabeçalho inválido foi preservado e recusado sem acrescentar leituras à seleção.

Passaram dois testes no Chrome 157, de comissionamento e do cenário de referência, mais os oito grupos E7 e a comparação Decimal do kit com o snapshot do Quarkus. O cenário de referência reproduziu **M1 = 6,00 tCO₂e** e **M2 = 5,30 tCO₂e** com EF-SYN-v1 e manteve M2 coletando enquanto V1/I1 aguardavam retorno. A amostra de 30 dias exercitou importação e conciliação sintéticas. A suíte Java de 26 testes e a compilação completa constam da [E8-012](../e8-012/VERIFICACAO.md). O ambiente descartável foi removido e a instância principal respondeu HTTP 204 em `/health/ready`.

## Condições em aberto

| Pendências | Prova ou decisão para avançar |
|---|---|
| PEN-005 · Amostra e metrologia | OP disponibiliza ED e EC autorizados, diagrama, inventário, calibração, fuso, unidades e referência de origem. Engenharia habilita a origem e o perfil no ambiente de homologação. REV executa CT-E5-29. A cobertura integral fica vinculada ao fechamento dependente. |
| PEN-003/004/010 · Parâmetros e projeto | OP e PP comprovam fronteira, baseline e OM/EF, direitos, ausência de sobreposição, Dy, licenças e excedente conforme incidência e com revisão competente. |
| PEN-002/007/011/012 · Atos e vintages | PP e revisão pertinente comprovam edição/data, canal, conta, poderes e posição quando aplicável. Confirmam representação, geodados, V5#89, condições do VVB, frações e dados por vintage. |
| PEN-006/009 · E8-013 | Leonardo designa, o revisor assina e REV trata achados impeditivos. Conferir as cláusulas incidentes da versão integral aplicável da ISO 14065:2020. |
| PEN-008 · E8-012 | A equipe completa os ensaios representativos. Leonardo registra as decisões de metas, responsabilidades e hospedagem. |
