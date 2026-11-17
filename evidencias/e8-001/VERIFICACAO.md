# E8-001 · Verificação local · 15/11/2026

**SIMULAÇÃO** · PIL-SYN-BR-001 · aplicação 0.1.0 · kit 0.2.14 · fixture E6-EXEMPLOS v1 · motor vm0038-piloto@1. A [execução registrada](execucao.json) tem ID `5225e4d6-8e9e-4e06-aa9c-60058b0bf281` e ator simulado `SIM-ENG-LOCAL`. O [snapshot em bytes](snapshot.json) tem o SHA-256 registrado no relatório e fixa os agregados, a regra, o fator, a prova simulada de Dy, a zona e a versão tzdb. A massa original veio de `03_especificacao/e6/exemplos.json` e foi empacotada com bytes idênticos, SHA-256 `cd1dd2b7d9e72a989fc26fbfbf7682f6238ff47701524c25b3aa42d1cdb1e19b`. A [resposta de recusa](recusa-sem-ec.json) identifica o EC faltante.

## Resultado esperado e observado

EF-SYN-v1 = **0,30 tCO₂/MWh**, AFEC = 20 kWh/100 mi, MPG = 30 mi/gal US, Dy = 1 com prova simulada, LE = 0. O Java e o oráculo Decimal devolveram os mesmos valores por recorte e período.

| Período | Recorte em America/Sao_Paulo, fim exclusivo | BE | PE | LE | ER esperado | ER observado |
|---|---|---:|---:|---:|---:|---:|
| M1 · 2027 | [2027-10-01, 2028-01-01) | 2,70 | 0,90 | 0 | 1,80 | 1,80 |
| M1 · 2028 | [2028-01-01, 2028-10-01) | 6,30 | 2,10 | 0 | 4,20 | 4,20 |
| **M1 total** | | 9,00 | 3,00 | 0 | **6,00** | **6,00** |
| M2 · 2028 | [2028-10-01, 2029-01-01) | 1,80 | 0,66 | 0 | 1,14 | 1,14 |
| M2 · 2029 | [2029-01-01, 2029-10-01) | 6,20 | 2,04 | 0 | 4,16 | 4,16 |
| **M2 total** | | 8,00 | 2,70 | 0 | **5,30** | **5,30** |

Valores de emissões em tCO₂e. As duas parcelas de 2028 estão associadas ao seu próprio período. M2 registra `COLETANDO` a partir de 01/10/2028, enquanto M1 está em conferência, e novamente com V1 em revisão e com I1 em `AGUARDANDO_DEPOSITO`. O total de M2 usa a massa agregada da janela completa.

## Testes executados

| Verificação | Resultado |
|---|---|
| Ambiente local | JDK Temurin 21.0.10 com `javac`, Maven Wrapper 3.9.12, Quarkus 3.33.4, Node 24.13.0, npm 11.6.2, Angular 21.2.26 e PostgreSQL 16.15. O Node e o servidor PostgreSQL foram preparados em pasta temporária. O socket do Docker estava inacessível, e a validação usou PostgreSQL nativo em loopback com bancos distintos. |
| Builds | Instalação pelo lockfile e `npm run build` concluídos. `./mvnw clean verify` compilou o backend, empacotou o Angular no mesmo servidor e executou oito testes Java com sucesso. |
| Quarkus e PostgreSQL | Migração Flyway, gravação e leitura por ID, conferência do SHA-256 do snapshot, leitura histórica após nova execução, repetição e concorrência com mesma chave, conflito de chave, rejeição de massa fora do catálogo e recusa HTTP 422 por EC ausente. A consulta SQL encontrou a execução válida e zero registros para a chave recusada. Após reiniciar PostgreSQL e Quarkus, a API devolveu o relatório e o snapshot com bytes idênticos. |
| Motor e calendário | EVR total zero, prova de Dy ausente e tentativa de substituir EF-SYN-v1 pelo candidato foram recusados. Exercitados os três limites locais expressos como instantes UTC, intervalo invertido, falta de deslocamento, rejeição de travessia sem segmentação e atribuição dos dois segmentos exatamente uma vez. A abertura de M2 exigiu data e prova simulada de operação. |
| Navegador | Um teste ponta a ponta em Chrome 157 sobre o pacote Quarkus com PostgreSQL. Conferiu selo, fator, quatro parcelas, totais, estados de M2, distinção dos recortes de 2028, preservação após recusa, recarga da página e largura de 375 px. Revisão visual em desktop e celular. |
| Kit e comparação | Executados `e2/conferir_calculos.py`, `e2/conferir_temporal.py`, `e6/conferir_e6.py` e `e7/conferir_e7.py` do kit. O script [conferir-oraculo.py](../../scripts/conferir-oraculo.py) comparou a saída do Quarkus com o oráculo sobre os ED/EC da fixture original e confirmou BE, PE, LE e ER em decimal por recorte e agregado. |

A aplicação cobre o recorte de cálculo, fronteiras e continuidade de CT-E2-14–16 e CT-E7-01/02, com contribuições a CT-E5-06/11/12/13/28. Os testes do kit continuam sendo ensaios da especificação. Os roteiros integrados completos de E5, incluindo importação operacional e segmentação com prova, reabertura de dossiês e trilha de protocolo, permanecem nas entregas correspondentes.

```sh
python3 scripts/conferir-oraculo.py /caminho/para/kit-0.2.14 \
  evidencias/e8-001/execucao.json evidencias/e8-001/snapshot.json
```

## Pendências de homologação e uso real

| Referência | Prova pendente e responsável |
|---|---|
| PEN-003/005 · E8-014 | OP autoriza amostra ED/EC de 30 dias e fornece diagrama, metrologia, calibração e cobertura exigida da janela. Especialista e REV conferem admissibilidade de fonte, fronteira, OM/EF, combustível e par da linha de base. A equipe executa os IMPs no ambiente de homologação. |
| PEN-006/009 · E8-013 | Leonardo designa revisor competente e independente, confirma acesso integral à ISO 14065:2020 e registra parecer assinado e tratamento das ressalvas. O VVB responde pelos atos que lhe competem. |
| PEN-004/010 · E8-014 | Leonardo, titular, PP e OP comprovam titularidade, excedente, ausência de sobreposição de créditos e requisitos locais, com revisão pertinente. |
| PEN-002/007/011/012 · E8-014 | PP e revisor verificam vigência, edição, canal, representação e poderes, conta, geodados, V5#89, condições de VVB, ramos metodológicos, frações e posição ativa por verificação e vintage. Atos reais exigem provas atribuídas às autoridades pertinentes. |
| PEN-008 · E8-011/012 | Engenharia testa carga, cópia, restauração e operação em ambiente representativo. Leonardo decide hospedagem, responsabilidades, metas de disponibilidade e recuperação e custo. |

O build local usa somente dados e comprovantes simulados. A E8-001 produziu evidência de execução local. Parecer independente, aceite operacional e uso real continuam pendentes.
