# E8-005 · Verificação local · 16/11/2026

**SIMULAÇÃO** · PIL-SYN-BR-001 · Flyway V5 · kit 0.2.14 · projeção `e8-005@1` · motor `vm0038-piloto-selecao@1`.

A proposta do PREP identifica um retrato ED/EC da E8-004. O cálculo fixa a ficha comissionada da E8-003, linha de base, fator, prova de Dy, período e fuso. Um REV distinto confirma a proposta. O job percorre as versões admitidas em ordem estável, guarda a contagem e o SHA-256 da projeção das contribuições e calcula os agregados por vintage. A contribuição inclui a versão da observação, configuração, decisão de admissão, linha original, hash do bruto, origem e mapeamento. A seleção da E8-004 continua consultável pela referência gravada. A reexecução lê os bytes salvos no snapshot e usa os valores que foram fixados.

A [massa E6](../../backend/src/main/resources/fixtures/e6-exemplos.json) é a origem da linha de base e da hipótese EF-SYN-v1. Conta 12/8 veículos, AFEC 20 kWh/100 mi, MPG 30 mi/gal US, EF fóssil de 0,006 tCO₂/gal US e IR de 1. O fator SIN de 0,30 tCO₂/MWh foi convertido para 0,00030 tCO₂/kWh. A massa não informa séries anuais para essa hipótese sintética. A prova de Dy vincula a regra da massa à evidência de sobreposição revisada da ficha. Candidatos adicionais têm fonte, anos, unidade, rede e vigência identificáveis e permanecem fora da seleção fixada.

## Resultados conferidos

| Período / recorte | ED kWh | EC kWh | BE tCO₂e | PE tCO₂e | LE tCO₂e | ER tCO₂e |
|---|---:|---:|---:|---:|---:|---:|
| M1 · 2027/10 a 2028/01 | 2.700 | 3.000 | 2,70 | 0,90 | 0 | 1,80 |
| M1 · 2028/01 a 2028/10 | 6.300 | 7.000 | 6,30 | 2,10 | 0 | 4,20 |
| **M1** | | | **9,00** | **3,00** | **0** | **6,00** |
| M2 · 2028/10 a 2029/01 | 1.800 | 2.200 | 1,80 | 0,66 | 0 | 1,14 |
| M2 · 2029/01 a 2029/10 | 6.200 | 6.800 | 6,20 | 2,04 | 0 | 4,16 |
| **M2** | | | **8,00** | **2,70** | **0** | **5,30** |

Os valores vieram de CSVs sintéticos admitidos pela API. Os agregados reproduzem a referência do kit e não comprovam a cobertura temporal de uma janela operacional. A [memória exportada](MEMORIAS.json) inclui as duas execuções, uma memória parcial do Chrome e a verificação de reexecução. Os bytes dos [snapshots de M1](M1-snapshot.json), [M2](M2-snapshot.json) e [M1 parcial](M1-parcial-snapshot.json) conferem com os respectivos SHA-256. A comparação decimal de BE, PE e ER nos quatro recortes coincidiu com o oráculo E2.

## Testes e limites

| Referência | Resultado observado |
|---|---|
| CT-E5-10/28 | Registradas duas versões de um fator sintético declarado publicado e de outra rede. A exclusão ficou vinculada à versão revisada. A tentativa de selecionar o candidato foi recusada. A reexecução preservou EF-SYN-v1, a memória e os bytes do snapshot. |
| CT-E5-11 | EVR total zero e denominador inválido bloqueados na suíte do motor. A proposta sem prova de Dy retornou 422. Uma seleção com EC ausente produziu BE onde havia ED e manteve PE e ER daquele recorte ausentes. ER de −0,005 tCO₂e conservou seu sinal. O comparativo com estimativa zero retornou só a diferença absoluta. Zero medido confirmado continua distinguível de ausência pela soma e pela contagem. |
| CT-E5-13/28, recorte de cálculo | M1 e M2 reproduzidos em quatro recortes, com os recortes de 2028 separados. A reexecução retornou a mesma memória dos dois períodos. A autorização e a sequência sintética de M2 da E8-001 também foram testadas novamente. |
| CT-E5-38, recorte de interface | PREP propôs o retrato com EC, REV consultou a ficha e as provas fixadas, confirmou o job e conferiu a memória parcial por vintage. A reexecução visível retornou resultado idêntico. |

Build Angular, build Docker e Maven `clean verify` passaram. Foram **16 testes Java** e **cinco jornadas no Chrome**, incluindo as regressões das issues anteriores. Também passaram `e2/conferir_calculos.py`, `e2/conferir_temporal.py`, `e6/conferir_e6.py` e `e7/conferir_e7.py` do kit.

- **PEN-003/011/012.** OP, PP e revisão técnica precisam comprovar linha de base real, a série OM/EF, a dispensa de Dy e a vigência aplicável. O ramo geral ERC/ERF, combustível múltiplo e a reconstrução operacional da OM continuam condicionados.
- **PEN-006.** Leonardo designa revisor independente para o parecer e o tratamento das ressalvas.
- **PEN-008.** Engenharia e Leonardo ainda precisam medir volume, latência e recuperação e definir as metas. Esta rodada não executou os ensaios integrados de carga ou de recuperação do motor em escala.
- A cobertura integral e o fechamento ficam na E8-006. A homologação com amostra autorizada e as provas para uso real continuam pendentes.
