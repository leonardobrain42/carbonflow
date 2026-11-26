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
| EFfuel/EFel/Dy | [#96](https://github.com/leonardobrain42/carbonflow/issues/96); candidatos, caderno VT0011/TOOL07, pinos e avaliações | Admissão, base de gases, dados SIN/OM, consumo do Dy estruturado pelo executor (V102, só ensaio interno), uso da totalização acumulada e matriz do período anterior; ano 1 de Dy por ausência comprovada e rateio por EVs seguem a [D14](../docs/CF-OPEN-007-DY-Q05-POLICY.md), sem admissão |
| IR | V89–V96: avaliação, parecer, prova anual, contexto E8, plano inicial, ex ante, conciliação, datas; reuso intra-ano ([contrato](../docs/CF-OPEN-007-IR-INTRA-YEAR-REUSE.md), PR em revisão, sem migração) | Dy do ano 1 (Q05), prova documental, consolidação anual do manifesto e E2E positivo de dois períodos/ano 1; ano 1 ex ante liberado só para IR ([D13](../docs/CF-OPEN-007-IR-FIRST-YEAR-EX-ANTE.md)) |
| ED/EC/QA | [#98](https://github.com/leonardobrain42/carbonflow/issues/98); configuração, lotes, snapshots e revisão interna | Dados reais admitidos e cobertura, EC derivado alternativo quando aplicável |
| Cálculo OPEN | [#97](https://github.com/leonardobrain42/carbonflow/issues/97); ensaio, PE horário, consulta anual (com guarda de mesma avaliação de IR por ano) e rastreabilidade | Entradas admitidas, homologação; primeiro ano bloqueado. Ensaio numérico sintético do ano 2 com IR e Dy estruturados já provado de ponta a ponta ([E2E](../backend/src/test/java/br/carbonflow/pilot/OpenNumericRunE2eIntegrationTest.java)), sem dados reais nem admissão |
| PD/MR | [#122](https://github.com/leonardobrain42/carbonflow/issues/122), [#128](https://github.com/leonardobrain42/carbonflow/issues/128), [#43](https://github.com/leonardobrain42/carbonflow/issues/43); editor/projeções e proveniência incremental | Composição DOCX/PDF completa com manifesto, autoria e revisão, IR estruturado, homologação |
| Entrada/conta | [#145](https://github.com/leonardobrain42/carbonflow/issues/145); rota `/` com três caminhos, cadastro/confirmação/recuperação sobre `/v1/accounts/*` ([contrato](../docs/CF-UX-ENTRY-001-ISSUE-145.md)); piloto `/pilot` preservado | Entrega de e-mail real (só mailbox sandbox), a11y/viewport e vazamento por cache sem teste nomeado, ensaio com iniciante (ENTRY-T08); issue segue aberta |
| Jornada | [#13](https://github.com/leonardobrain42/carbonflow/issues/13), [#19](https://github.com/leonardobrain42/carbonflow/issues/19), [#24](https://github.com/leonardobrain42/carbonflow/issues/24), [#124](https://github.com/leonardobrain42/carbonflow/issues/124); guias e diagnóstico; triagem como hipótese inicial (CF-SPEC-D16, #264/#265) | DAG real completo, colaboradores/autoridade, UX moderada, acessibilidade e gates |
| Release | [#101](https://github.com/leonardobrain42/carbonflow/issues/101) aberta | E2E positivo e negativo, parecer independente, amostra real autorizada e decisão de liberação |

## Validação no corte
[CI #571](https://github.com/leonardobrain42/carbonflow/actions/runs/45435689544) do #241: cinco jobs aprovados. [CI #573](https://github.com/leonardobrain42/carbonflow/actions/runs/45436621468) do #242: Angular build, browser, Flyway e demo aprovados; Quarkus falhou antes dos testes por HTTP 429 de Maven Central ao baixar maven-clean-plugin 3.5.0. Merge não equivale a teste aprovado. Reexecutar a suíte quando o acesso estiver disponível, investigar regressões efetivas. Não flexibilizar testes.
A matriz completa do kit histórico ainda não foi recuperada. [E0](e0/README.md) registra o bloqueio e procedimento.

## Próximas decisões
CI do #242 confirmado: os cinco jobs da main (inclusive Quarkus/PostgreSQL) passaram nos pushes dos merges #242 ([run 45445796764](https://github.com/leonardobrain42/carbonflow/actions/runs/45445796764)) e #243 ([run 45449006684](https://github.com/leonardobrain42/carbonflow/actions/runs/45449006684)); o HTTP 429 do run #573 não se repetiu. Reuso de IR entre períodos do mesmo ano (mesma dupla ano relativo/ano fonte, referências canônicas equivalentes, vigência cobrindo cada janela) implementado nesta fatia; o ano 1 passou a ter IR ex ante (D13, opção B do Q04) e segue bloqueado por Dy; resta a prova documental. Resolver a cadeia de vigência e segregação. Em paralelo avançar nas lacunas de admissão e Dy sem substituir dados reais por números de fixture. A sequência detalhada está em [E8](e8/README.md).

## D17 — Direção e lacunas de plataforma
Agnosticidade é decisão de produto aprovada, não comportamento integral demonstrado. A [auditoria dirigida de 25/11](CF-JOURNEY-001-AUDITORIA.md) identifica acoplamentos atuais de triagem, guias, requisitos, atos e compositor, e lacunas J04. [Jornada canônica](CF-JOURNEY-001.md); manter OPEN, D16 e gates sem regressão. Não confundir aceitação de candidato com pacote executável. AGN-01 já é verificado em parte: um rascunho sem VM0038 recebe só a lacuna de metodologia na prontidão de cálculo (rc.35), sem campos, gates ou equações de recarga.

## Complemento de 25/11/2026 (PRs #299 a #315)
O corte acima é anterior a estes PRs; esta seção só acrescenta o que eles entregaram. **Os PRs #243 a #298 não foram revistos nesta sincronização** (D18 e a administração de pacotes estão em [DECISOES_E_DUVIDAS](DECISOES_E_DUVIDAS.md)); um novo corte completo continua pendente. Todas as entregas abaixo são SANDBOX, nada admite fonte, habilita cálculo ou emite crédito.
| Entrega | Contrato | O que **não** faz |
|---|---|---|
| Migração explícita do pino de metodologia (V106), PD pelo pacote, teste de capacidades | [CF-PACKAGES-001](CF-PACKAGES-001-ESTRATEGIA-DE-PACOTES.md), [registro](../docs/CF-PACKAGES-001-METHODOLOGY-REGISTRY.md) | Não há segundo pacote real; AGN-03 depende de decisão de vigência |
| Licenças de conteúdo: API e tela (V107) | [CF-DATA-001](../docs/CF-DATA-001-CONTENT-LICENSES.md) | Nenhuma licença real registrada; o conteúdo licenciado não é versionado |
| Ferramentas offline do EVR: estoque Senatran em CSV, extração do PBEV elétrico, cruzamento por nome | [CF-OPEN-005-EVR-STOCK-BR](../docs/CF-OPEN-005-EVR-STOCK-BR.md), [CF-OPEN-006](../docs/CF-OPEN-006-PBEV-RATINGS.md) | O resultado é candidato; PDFs do PBEV não versionados; sem dado em ambiente real |
| Fila de candidatos de alias (V108), tela e plano de cadastro dos aceitos | [CF-OPEN-005-EVR-STOCK-BR](../docs/CF-OPEN-005-EVR-STOCK-BR.md) | Aceitar não cria modelo nem alias; listas de importadores e revisores sem responsáveis definidos; classe e propulsão ficam com pessoas |
| Dossiês SIN/MCTI e créditos concorrentes; minuta de consulta ao MCTI | [CF-DATA-003](../docs/CF-DATA-003-SIN-MCTI-DOSSIE.md), [CF-DATA-004](../docs/CF-DATA-004-CREDITOS-CONCORRENTES-MATRIZ.md), [consulta](../docs/CF-DATA-003-CONSULTA-MCTI.md) | Só leitura: nenhuma fonte admitida; **consulta não enviada**; registros (Verra, Gold Standard, MDL) não lidos |


## Adendo de diagnóstico UX (26/11/2026, corte cc25f64b85900fdba6be115bb0f8bf764f0e6d4b)
EVR/ratings: catálogo, aliases/classificação, snapshots e publicação V64 existem; V64 exige energia e período e a etapa EVR lê MR. Falta seleção ex ante independente, fonte nacional e ratings/pares reais admissíveis. O responsável informa revisão interna de combustível concluída localmente, independente pendente; não verificado no banco. Plano P0 [frota e baseline ex ante](../docs/CF-UX-TECH-001-FROTA-BASELINE-EX-ANTE.md) preserva V64 e gates. Status DOCUMENTADO/PLANEJADO, sem implementação ou homologação neste adendo.
