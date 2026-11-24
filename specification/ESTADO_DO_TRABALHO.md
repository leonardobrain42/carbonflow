# Estado do trabalho — corte 24/11/2026
Referência: [commit main após #242](https://github.com/leonardobrain42/carbonflow/commit/507bf268d6955cd0c317d2ff404a905009985907). Estado observado/documentado neste corte, não inventário certificado de todas as classes.

## Recortes independentes
| Recorte | Estado | Limite |
|---|---|---|
| PIL-SYN-BR-001, frota leve fechada | Ensaio histórico sintético em M1/M2, docs e evidências preservadas | M1=6,00 e M2=5,30 tCO₂e são somente regressão sintética; não são créditos nem fatores brasileiros |
| Projeto dinâmico SANDBOX | Cadastro, organização, rascunho, preparação, grants e trilhas incrementais | Não herda dados, recibos, permissões ou aprovação do piloto |
| VM0038 v1.1 OPEN BR LDV BEV DCFC SIN, GRID_ONLY | Recorte alvo #89; fontes, aplicabilidade, frota/ratings, parâmetros e cálculo interno em implementação | Sem infraestrutura associada, AC/HDV/PHEV e outros ramos fora desta primeira fatia; sem admissão normativa ou emissão |
| Multi-metodologia | Contextos de fonte por metodologia/edição/cenário e fronteira de projeto dinâmica | Não confundir extensibilidade com execução completa de outra metodologia |

## Síntese por capacidade
| Capacidade | Evidência de implementação | O que impede aceite |
|---|---|---|
| Fontes e direitos | [#92](https://github.com/leonardobrain42/carbonflow/issues/92); inventário, metadados, quarentena, ingestão e revisão | Fonte real autorizada, cobertura, edição e aprovação por finalidade |
| Aplicabilidade/adicionalidade | [#93](https://github.com/leonardobrain42/carbonflow/issues/93), [#99](https://github.com/leonardobrain42/carbonflow/issues/99); declaração, gates e projeções | Provas de mercado, direitos, cronologia, excedente e revisão normativa |
| EVR/ratings | [#94](https://github.com/leonardobrain42/carbonflow/issues/94), [#95](https://github.com/leonardobrain42/carbonflow/issues/95); snapshots, comparáveis e amostra RENAVAM/PBEV | Denominador nacional de BEVs em circulação e pares/ratings admissíveis |
| EFfuel/EFel/Dy | [#96](https://github.com/leonardobrain42/carbonflow/issues/96); candidatos, caderno VT0011/TOOL07, pinos e avaliações | Admissão, base de gases, dados SIN/OM, rota ERC/ERF e consumo integrado de Dy |
| IR | V89–V96: avaliação, parecer, prova anual, contexto E8, plano inicial, ex ante, conciliação, datas; reuso intra-ano ([contrato](../docs/CF-OPEN-007-IR-INTRA-YEAR-REUSE.md), PR em revisão, sem migração) | Primeiro ano/ex ante do cálculo, prova documental, consolidação anual do manifesto e E2E positivo de dois períodos |
| ED/EC/QA | [#98](https://github.com/leonardobrain42/carbonflow/issues/98); configuração, lotes, snapshots e revisão interna | Dados reais admitidos e cobertura, EC derivado alternativo quando aplicável |
| Cálculo OPEN | [#97](https://github.com/leonardobrain42/carbonflow/issues/97); ensaio, PE horário, consulta anual (com guarda de mesma avaliação de IR por ano) e rastreabilidade | Entradas admitidas, Dy integrado, IR anual consistente, homologação; primeiro ano bloqueado |
| PD/MR | [#122](https://github.com/leonardobrain42/carbonflow/issues/122), [#128](https://github.com/leonardobrain42/carbonflow/issues/128), [#43](https://github.com/leonardobrain42/carbonflow/issues/43); editor/projeções e proveniência incremental | Composição DOCX/PDF completa com manifesto, autoria e revisão, IR estruturado, homologação |
| Jornada | [#13](https://github.com/leonardobrain42/carbonflow/issues/13), [#19](https://github.com/leonardobrain42/carbonflow/issues/19), [#24](https://github.com/leonardobrain42/carbonflow/issues/24), [#124](https://github.com/leonardobrain42/carbonflow/issues/124); guias e diagnóstico | DAG real completo, colaboradores/autoridade, UX moderada, acessibilidade e gates |
| Release | [#101](https://github.com/leonardobrain42/carbonflow/issues/101) aberta | E2E positivo e negativo, parecer independente, amostra real autorizada e decisão de liberação |

## Validação no corte
[CI #571](https://github.com/leonardobrain42/carbonflow/actions/runs/45435689544) do #241: cinco jobs aprovados. [CI #573](https://github.com/leonardobrain42/carbonflow/actions/runs/45436621468) do #242: Angular build, browser, Flyway e demo aprovados; Quarkus falhou antes dos testes por HTTP 429 de Maven Central ao baixar maven-clean-plugin 3.5.0. Merge não equivale a teste aprovado. Reexecutar a suíte quando o acesso estiver disponível, investigar regressões efetivas. Não flexibilizar testes.
A matriz completa do kit histórico ainda não foi recuperada. [E0](e0/README.md) registra o bloqueio e procedimento.

## Próximas decisões
CI do #242 confirmado: os cinco jobs da main (inclusive Quarkus/PostgreSQL) passaram nos pushes dos merges #242 ([run 45445796764](https://github.com/leonardobrain42/carbonflow/actions/runs/45445796764)) e #243 ([run 45449006684](https://github.com/leonardobrain42/carbonflow/actions/runs/45449006684)); o HTTP 429 do run #573 não se repetiu. Reuso de IR entre períodos do mesmo ano (mesma dupla ano relativo/ano fonte, referências canônicas equivalentes, vigência cobrindo cada janela) implementado nesta fatia; resta o ano 1/ex ante e a prova documental. Resolver a cadeia de vigência e segregação. Em paralelo avançar nas lacunas de admissão e Dy sem substituir dados reais por números de fixture. A sequência detalhada está em [E8](e8/README.md).
