# E8-006 · Monitoramento, fechamento e reabertura

PIL-SYN-BR-001 · Kit 0.2.14 · Simulação · 16/11/2026 · manifesto `e8-006@1` · migração Flyway V6.

Os [CSVs M1](../../backend/src/main/resources/fixtures/e8-006-m1.csv) e [M2](../../backend/src/main/resources/fixtures/e8-006-m2.csv) distribuem as energias por canal e por vigência dos medidores para reproduzir os totais da referência temporal E2/E6. O [suplemento metrológico](../../backend/src/main/resources/fixtures/e8-006-metrologia.json) introduz o certificado sintético do sucessor SIM-MED-04 em 01/10/2028. A matriz de cobertura do cenário verifica intervalos contínuos por estação ED e circuito EC. Os agregados desta massa representam registros medidos fictícios entregues por SIM-OP. PREP propõe as leituras e o cálculo, REV admite as leituras, revisa o cálculo e decide o fechamento. O fuso do cenário é America/Sao_Paulo e os dados preservam a representação UTC da origem.

| Revisão de referência | Janela no manifesto | Vintage | Redução em tCO₂e |
|---|---|---:|---:|
| M1 r1 | [01/10/2027, 01/01/2028) | 2027 | 1,80 |
| M1 r1 | [01/01/2028, 01/10/2028) | 2028 | 4,20 |
| M2 r1 | [01/10/2028, 01/01/2029) | 2028 | 1,14 |
| M2 r1 | [01/01/2029, 01/10/2029) | 2029 | 4,16 |

M1 fecha em **6,00 tCO₂e** e M2 fecha em **5,30 tCO₂e**, ambos com **EF-SYN-v1**, 0,30 tCO₂/MWh. O ensaio aplica uma correção sintética adicional em M1 r2, que fecha em 6,05 tCO₂e. M1 r1 continua recuperável com 6,00 tCO₂e e com a elegibilidade para novo uso desativada.

| Caso exercitado na aplicação | Resultado observado | Evidência |
|---|---|---|
| CT-E5-06/13 e parcela de CT-E7-01 | Às 00:00 de 01/10/2028, OP encerrou M1 e abriu M2. M2 recebeu ED/EC enquanto V1 estava em revisão e I1 aguardava depósito. A reabertura de M1 preservou M2 em coleta. REV fechou M2 após a sua fronteira. | [Fechamentos e histórico](FECHAMENTOS.json), [trilha](TRILHA.json) |
| Cobertura e recuperação TX-E3-43/44 | Uma memória com saldo e um circuito EC incompleto foi recusada no fechamento. REV registrou a lacuna. PREP retomou a conferência com o CSV entregue por OP e admitido após a classificação. A memória anterior continuou inelegível. | [Relatório da lacuna e revisão](FECHAMENTOS.json), [trilha](TRILHA.json) |
| CT-E5-12 e fronteiras de CT-E7-02 | Os agregados das viradas de 2027/28, M1/M2 e 2028/29 foram bloqueados. Também foram recusados o segmento sem vínculo, a soma incoerente e a confirmação por PREP. Segmentos de 2 e 6 kWh, vindos de medições identificadas, formaram 8 kWh com limite às 03:00 UTC. Os originais permaneceram recuperáveis. | [Vínculos dos segmentos](SEGMENTOS-UTC.json) |
| CT-E5-14/28 e preservação em CT-E7-03 | Dois fechamentos concorrentes produziram uma única decisão, com recusa da versão obsoleta. A reabertura preservou o pacote vinculado e os bytes do fechamento. A reexecução da memória foi idêntica. Novos usos do pacote anterior foram recusados. A prova simulada de V1 gerou uma referência pendente para E8-010. | [M1 r1 antes](M1-r1.json), [M1 r1 depois](M1-r1-apos-reabertura.json), [trilha](TRILHA.json) |

## Execuções

- `mvn clean verify` em Java 21 e PostgreSQL 16.16 isolados em Docker. 18 testes, zero falhas. A suíte incluiu regressões de reenvio, limites CSV, retomada, correção, proteção dos artefatos, fator fixado e cálculo.
- Build Angular e build do Compose completos. Seis cenários automatizados em Google Chrome. O cenário de períodos passou por planejamento, abertura, encerramento, recusa por lacuna, classificação e histórico de M2.
- `e7/conferir_e7.py` passou nos oito grupos, incluindo CT-E7-01 a 03. `e2/conferir_calculos.py`, `e2/conferir_temporal.py` e `e6/conferir_e6.py` passaram nas respectivas verificações de referência. Esses scripts exercitam o kit em memória. A prova da aplicação desta issue está nos testes Java, no navegador e nos JSONs.
- Os arquivos M1 r1 antes e depois da reabertura são byte a byte iguais. SHA-256 `9df516fa002d9ca7c35ec4e40820187d657c8df0f250f3020766f0b781ea5d73`.

## Pendências e escopo do ensaio

- **PEN-003** permanece aberta para comprovação do candidato real, fonte de OM/EF, fronteira e par comparável. Responsáveis pelo insumo e revisão técnica conforme backlog.
- **PEN-005** permanece aberta. OP precisa entregar ED/EC autorizado e comprovar o controle de sessões, diagrama, calibração e cobertura do período real.
- **PEN-011** permanece aberta. PP e REV confirmam incidência de poderes, representação, geodados, V5#89, frações, posição, VVB e ramos metodológicos.
- A E8-007 implementa a geração de PD/MR. O ensaio atual registra o vínculo de dependência com artefato aprovado e as referências sintéticas de V1/I1. A confirmação e a conciliação externa da divergência pertencem à E8-010.
- A campanha integrada de carga de 200.000 fatos, retenção e recuperação de ambiente de CT-E5-31/33/39 permanece pendente, assim como o parecer independente e a homologação com amostra autorizada.
