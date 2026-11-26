# CF-DATA-003 — Dossiê de admissibilidade: fatores do SIN (MCTI/ONS) para o EFel do VM0038 v1.1

**Nível D17:** plataforma/dados (fonte externa) com consequência para o método VM0038 v1.1 / VT0011 v1.0 / TOOL07 v7.0. **Estado: dossiê de leitura. Não admite fonte, não habilita cálculo, não escolhe método OM, não cria dado, migração nem código.** Redigido em 25/11/2026.

## O que este dossiê é
Um registro do que se sabe, do que **não** se sabe e de quem decide, antes de qualquer fator do SIN entrar como EFel. O CarbonFlow já tem os donos técnicos (V60 série EFel horária candidata, V61 caderno VT0011 caso 2, V62 Simple OM A1, V37 licenças de conteúdo); este documento não duplica nenhum deles.

## 1. O que a fonte publica (confirmação primária pendente)
**Origem das informações abaixo: reportagens e uma nota técnica reproduzida por terceiros ([NT FE jun25](https://cenarioenergia.com.br/wp-content/uploads/2025/06/NT_FE_jun25.pdf), [Cenário Energia](https://cenarioenergia.com.br/2025/06/13/brasil-reduz-intensidade-de-carbono-na-geracao-eletrica-com-inclusao-de-novas-renovaveis-na-base-de-calculo-oficial/), [Canal Solar](https://canalsolar.com.br/emissoes-de-co₂-geracao-eletrica-mcti/)). A página do MCTI/SIRENE e os termos de uso do ONS **não foram consultados**; nenhuma linha desta seção vale como citação normativa até isso ser feito.**
- O MCTI divulga **mensalmente** a Margem de Operação pelo método de **Análise de Despacho** (usado no MDL) e o **fator médio** do SIN (usado em inventários).
- A **Margem de Construção** e o **Método Simples Ajustado** seguem **anuais**.
- A partir de **janeiro de 2025** a base do ONS passou a incluir conjuntos de eólicas que compartilham um ponto de inserção e usinas solares e de biomassa; segundo a nota, a metodologia não mudou, mas os valores podem cair. **Há, portanto, uma quebra de base na série em 2025.**
- Os fatores são de **CO₂**, não de CO₂e (a ser confirmado na fonte primária).
- A origem histórica da Análise de Despacho no Brasil (sistema único para o SIN, cálculo pelo ONS desde 2006) vem de documento da CIMGC; é contexto, não regra do VM0038.

> **Atualização de 25/11/2026 (releitura):** a nota técnica do MCTI de junho/2025 foi lida (cópia hospedada por terceiro, [NT FE jun25](https://cenarioenergia.com.br/wp-content/uploads/2025/06/NT_FE_jun25.pdf); cabeçalho do MCTI, **autenticidade não verificável por este caminho**). Ela confirma, em texto, os itens acima: fatores de **CO₂** da Margem de Operação; ampliação da base do ONS a partir de janeiro de 2025 (conjuntos eólicos com ponto de inserção compartilhado, solares e biomassa) com metodologia inalterada; Margem de Operação por Análise de Despacho e fator médio **mensais**; Margem de Construção e Simples Ajustado **anuais**. **A página oficial do MCTI/SIRENE foi lida depois** (seção 2.2); a nota de junho/2025 também foi baixada do próprio gov.br e é **byte a byte do mesmo tamanho (128.193 bytes) e texto idêntico** à cópia de terceiro, o que confirma a autenticidade do texto.

## 2. O que o método exige (já registrado no repositório)
Conforme [CF-OPEN-007-VT0011-CASE2-WORKBOOK](CF-OPEN-007-VT0011-CASE2-WORKBOOK.md) e [CF-OPEN-007-TOOL07-SIMPLE-OM-A1-PRECHECK](CF-OPEN-007-TOOL07-SIMPLE-OM-A1-PRECHECK.md):
- VT0011 §5, caso 2: pesos OM = 1,0 e BM = 0,0 (o valor de BM não é presumido zero; o peso é nulo).
- TOOL07 §6.3: quatro métodos OM (Simple, Simple adjusted, Dispatch data analysis, Average). **A escolha tem de ser demonstrada com as condições de elegibilidade; não é automática.**
- O fator MCTI de inventário corporativo **não vira** OM metodológica ([PARAMETER-FOUNDATION](CF-OPEN-007-PARAMETER-FOUNDATION.md)).

### 2.1 Releitura do texto normativo (25/11/2026): o que ele diz de fato
Lidos integralmente a [VT0011 v1.0](https://verra.org/documents/vt0011-electricity-system-emission-factors-v1-0/) (10 páginas) e, nos pontos abaixo, a [TOOL07 v7.0](https://cdm.unfccc.int/methodologies/PAmethodologies/tools/am-tool-07-v7.0.pdf) (60 páginas). Citações curtas; confira no original.
- **Gás:** a VT0011 §1 e §4 a definem como ferramenta para o fator de emissão de **CO₂** do sistema elétrico. Nenhuma das duas trata de CO₂e. **A lacuna CO₂ × CO₂e é real e continua aberta.**
- **Uso conjunto:** a VT0011 "must be used in conjunction with the most recent version of CDM TOOL07" e os parágrafos dela substituem os correspondentes da TOOL07.
- **Escolha do método OM:** VT0011 par. 39: "Any of the OM methods may be selected, with consideration of the restrictions mentioned in paragraphs 40 (simple OM) and 41 (dispatch data analysis)". **O texto não exclui a Análise de Despacho**; resolve a primeira dúvida da matriz no sentido "permitida, com restrições". A escolha continua a ser demonstrada, não automática.
- **Restrições:** TOOL07 par. 40: o Simple OM só vale se os recursos de baixo custo/must-run forem **inferiores a 50%** da geração (média dos cinco anos mais recentes, Abordagem 1 ou 2, ou média de longo prazo de hidrelétricas, mínimo de 15 anos) **ou** se a carga média LCMR dos três anos for menor que a média da menor carga anual do sistema. Par. 41: a Análise de Despacho "cannot be used if off-grid power plants are included in the project electricity system".
- **Caso 2:** VT0011 par. 86: "For all crediting periods: wOM = 1.0 and wBM = 0.0" para projetos que aumentam o consumo da rede (confirma o caderno V61).
- **Vintage:** TOOL07 par. 42 (Simple, Simple ajustado, Average): ex ante (média ponderada de 3 anos, definida uma vez na validação) ou ex post (ano y, com y−1 ou y−2 se o dado costuma sair com atraso de mais de 6 ou 18 meses); par. 43 (Despacho): usar o ano em que o projeto desloca eletricidade e **atualizar anualmente**; par. 44: a vintage escolhida não muda no período de creditação.
- **Resolução horária da Análise de Despacho:** TOOL07 par. 63–64 e Eq. (12): EFgrid,OM-DD,y = Σh (EGPJ,h × EFEL,DD,h) / EGPJ,y, com h as horas do ano em que o projeto desloca eletricidade; "not applicable to historical data"; frequência de monitoramento "Hourly". Se o método de Despacho for o escolhido, é preciso o fator **por hora** (EFEL,DD,h), ponderado pela energia deslocada em cada hora, o que casa com a série horária V60. **Um fator mensal único não teria essa forma; mas a fonte do MCTI publica também a tabela horária (seção 2.2),** então essa incompatibilidade, que eu havia inferido, **não se sustenta**.
- **Importações:** VT0011 par. 25 (substitui a da TOOL07): para importações líquidas de sistemas conectados, a opção a2 é "1.3 t CO2/MWh for projects increasing the consumption of electricity from the grid". O par. 50 (A.2.ii) manda usar o combustível de **maior** fator e o A.3.ii admite 1,3 tCO2/MWh quando só há dado de geração. **São opções do texto, não aplicadas pelo produto** (nenhum default é usado).
- **Fatores nacionais:** nas partes lidas, "Source of data: Utility or government records or official publications" (parâmetros de OM). Isso admite fonte oficial como origem, **não** admite um fator publicado sem as condições acima.

### 2.2 Releitura da página do MCTI/SIRENE (25/11/2026)
Lida a [página oficial de fatores de emissão](https://www.gov.br/mcti/pt-br/acompanhe-o-mcti/sirene/dados-e-ferramentas/fatores-de-emissao) e inspecionada a **estrutura** (cabeçalhos, sem reproduzir valores) da planilha de Despacho do ano-base 2026. Todos os arquivos são XLSX.
- **O que a página publica:** fator médio para inventários (arquivo de 2026); fator da Margem de Operação pelo método da análise de despacho (um arquivo por ano-base, 2006 a 2026); método simples ajustado (arquivo de 2025); margem de construção (aparece em planilhas; a de 2026 diz que "será disponibilizado no início de 2027").
- **Resolução temporal da Análise de Despacho:** a planilha do ano-base 2026 traz, na aba `MDL`, as tabelas "MARGEM DE OPERAÇÃO: Fator de Emissão Médio (tCO2/MWh)" **MENSAL, DIÁRIO e HORÁRIO** (a horária tem colunas `Dia`, `Hora` e os 12 meses). Portanto **há fator por hora**, no formato que a Eq. (12) da TOOL07 precisa. Para a inventário, a planilha tem fator médio mensal e anual. A página não declara a periodicidade de publicação (correções de arquivos antigos falam de fatores "horários" e "diários").
- **Gás:** a página escreve "CO2"/"CO₂"; não menciona CO₂e.
- **Metodologia declarada:** "calculados de acordo com a ferramenta metodológica aprovada pelo Conselho Executivo do MDL" (TOOL07 versão 07, com link ao PDF da UNFCCC).
- **Mudança de base de 2025:** a seção "ATUALIZAÇÃO" diz que, a partir de janeiro de 2025, a Margem de Operação incorpora "um aprimoramento na base de dados do ONS, com a inclusão de novas usinas", com metodologia inalterada (e remete à nota NT_FE_jun25, já lida).
- **Licença e uso (relevante):**
  1. O rodapé do site diz: "Todo o conteúdo deste site está publicado sob a licença Creative Commons Atribuição-SemDerivações 3.0 Não Adaptada" (CC BY-ND 3.0). É a licença do **site**; a página não traz licença própria para os arquivos nem formato de citação.
  2. Sobre o fator de Despacho: "A sua utilidade está associada a projetos de MDL e se aplica, exclusivamente, para estimar as reduções certificadas de emissões (RCEs) dos projetos de MDL." Já sobre o fator médio: "Ele serve, portanto, para inventários em geral, corporativos ou de outra natureza."
- **Ainda sem resposta nesta releitura:** fuso horário das horas da tabela (a V60 exige hora UTC; a planilha só diz `Dia`/`Hora`); tratamento do dia 31 e meses curtos; correções em arquivos de anos anteriores (há versões "corrigidas"), o que exige guardar a edição exata e o hash; a planilha de 2025 e as demais não foram abertas.

## 3. Matriz de lacunas (fonte × exigência)
| Ponto | Situação | Quem fecha |
|---|---|---|
| A Análise de Despacho é permitida sob a VT0011 para um projeto VM0038? | **Permitida pelo texto** (VT0011 par. 39), com a restrição do par. 41 (sem usinas off-grid no sistema). Resta demonstrar a escolha e que a série usada tem a forma da Eq. (12) da TOOL07 | Revisão normativa (Q03) |
| Fator médio do SIN (inventário) como EFel | **Não admissível por si**: não é OM; só a fonte e a escolha demonstrada decidem | Revisão normativa |
| Gás: fonte em CO₂; EFel do CarbonFlow é CO₂e | Conversão/justificativa ausente; a prévia do caderno V61 já avisa que CO₂ não é CO₂e | Revisão normativa |
| Resolução temporal: a Eq. (12) pede fator **por hora**; a fonte **publica tabela horária** (além da mensal e da diária); a série V60 exige uma linha por hora UTC | Compatível em forma. Faltam o fuso das horas da planilha, a edição/correção exata do arquivo e a conferência de que a hora cobre o EC medido. Nada é repetido nem interpolado por padrão | Q03 / revisão |
| Vintage: ex ante (3 anos) × ex post, e a **quebra de base de 2025** | Escolha e tratamento da quebra não decididos; o TOOL07 A1 do repositório cobre só um ano `EX_POST` | Q03 |
| Licença e termos de uso (MCTI e ONS), titular, redistribuição | **MCTI:** o site está sob **CC BY-ND 3.0** (Atribuição-SemDerivações), e o fator de Despacho "se aplica, exclusivamente" ao MDL segundo a página. **Dúvidas jurídicas, não resolvidas aqui:** (a) se converter e normalizar a tabela (unidades, hora UTC) é uma "derivação"; (b) se a restrição "exclusivamente MDL" impede o uso num projeto VCS/VM0038 (a VT0011 manda ler "CDM" como "projetos no VCS e outros programas"); (c) se a licença do site vale para os arquivos. **ONS:** CC-BY em um conjunto lido. Nenhuma licença foi registrada na tela de licenças | Jurídico / titular da fonte |
| Autenticidade: arquivo oficial, URL, data de captura, SHA-256 | Não capturado; o SHA é hoje apenas declarado | Quem importar |
| Cobertura e Margem de Construção | Irrelevante no caso 2 (peso 0), mas a premissa do caso 2 depende da fronteira DCFC/SIN GRID_ONLY | Revisão normativa |
| Importações / fronteira do sistema | Tratamento exigido pela VT0011; não definido | Q03 |

## 4. Consequências para o produto (sem alterar nada agora)
1. **Nada do SIN/MCTI entra como EFel admitido.** A trilha existente (candidato → caderno → revisão independente) segue como único caminho e termina em `NOT_ADMITTED`.
2. **Sem default.** Nenhum valor, nem mensal nem anual, é sugerido ou pré-preenchido.
3. Quando houver licença (V37) e a escolha do método, a importação do arquivo oficial deve seguir o mesmo padrão das demais: guardar original, SHA-256 capturado, data, URL e edição; **não** versionar o dado se a licença não permitir.
4. Um eventual conector MCTI/ONS só faz sentido depois de 1 a 3; hoje seria dado sem regra.

## 5. Decisões que não são minhas
- **Q03 (OM/TOOL07):** qual método OM e qual vintage, e se a Análise de Despacho do MCTI é aceitável; como tratar a quebra de 2025 e a resolução mensal × horária.
- **Licença:** quem consulta o MCTI/ONS e registra a licença (V37).
- **Gás:** como tratar CO₂ × CO₂e.

## 5.1 Importador da estrutura horária (D19 fatia 4, rc.71)
`scripts/import_mcti_hourly.py` (offline, biblioteca padrão; não grava na aplicação, não envia nem versiona o arquivo) lê a tabela **HORÁRIO** da planilha de despacho e **preserva o original**: SHA-256 e tamanho do arquivo, aba, célula do título, linha do cabeçalho, ano, unidade como no título, e, por registro, a **célula de origem**, o mês, o dia, a **hora como rotulada** e o **texto do valor como armazenado**. A visão normalizada só existe com parâmetros **obrigatórios e sem padrão**, escritos por pessoas:
- `--timezone` (nome IANA) e `--hour-convention` (`HOUR_ENDING_1_24` ou `HOUR_START_LABEL_1_24`): a planilha não declara nenhum dos dois. Horários locais inexistentes ou ambíguos no fuso são **marcados** e ficam **sem instante UTC**.
- `--unit` precisa **igualar a unidade do título**, senão a importação para; `--target-unit` faz só conversões exatas definidas (tCO2/MWh, kgCO2/MWh, gCO2/kWh).
- O gás é CO2 como declarado; **CO2e não é presumido**. A saída diz sempre `usableForE8=false`, `rights=UNCONFIRMED`, `eligibilityVT0011Tool07=NOT_EVALUATED`.
- Para (não adivinha) se a sequência dia/hora quebra, se o ano difere do informado ou se o marcador da tabela não é único. Valores em dia inexistente, não numéricos ou negativos viram anomalias listadas, nunca importadas. Meses sem valores saem `NOT_PUBLISHED`; incompletos, `PARTIAL`.
- **Verificação em dado real, sem versionar nada:** a edição 2026 baixada localmente tem a aba `MDL`, 52 linhas de cabeçalho, 304 dias completos (jan a out; 7296 registros), 0 anomalias, nov e dez `NOT_PUBLISHED`. É só leitura estrutural: **não** valida valores, fuso nem convenção; essas respostas dependem da consulta ao MCTI (CF-DATA-003-CONSULTA-MCTI).

## 5.2 Registro da autorização do MCTI no módulo de licenças (roteiro #124, PR 1; rc.76)
**Declaração do responsável (rodada 2 da D19):** existe autorização para usar dados do MCTI. **Autorização de uso não é admissibilidade metodológica**: `usableForE8` continua falso até os gates.
O módulo de licenças (V107, `/platform/licenses`) já comporta o registro **sem mudança de código ou de vocabulário**: fonte e edição em `app_source_version` (metadados, sem bytes), submissão por quem está no rol de submissores e **decisão por outra pessoa** do rol de aprovadores (dupla custódia). O mapeamento das operações pretendidas para o vocabulário existente:
| Pretendido | Operação do módulo | Finalidade |
|---|---|---|
| baixar e guardar as planilhas | `STORE_ORIGINAL` | `AUTHORIZED_PROCESSING` |
| importar a série horária e persistir no PostgreSQL | `EXTRACT_TEXT` (extração do conteúdo tabular) | `AUTHORIZED_PROCESSING` |
| exibir nas telas administrativas | `SHOW_EXCERPTS` | `AUTHORIZED_PROCESSING` |
| derivados normalizados para o catálogo elétrico e o E8 | `EXPORT_DERIVATIVES` | `AUTHORIZED_PROCESSING` |
**Faltam, e só o responsável tem** (não invento nenhum): (1) a identificação do ato de autorização (`authorizationRef`, mín. 3 caracteres); (2) o **SHA-256 do comprovante** (o arquivo em si não é guardado); (3) a **vigência** (início e, se houver, fim); (4) a **edição** da fonte (o nome do conjunto e o ano, por exemplo o da planilha de 2026 que o importador leu); (5) quais das operações acima a autorização cobre. Com isso o registro é feito pela tela de licenças em minutos. **Bloqueio, por tipo:** dados/processamento (ingestão persistente da série, PR 2) fica bloqueado **só** até esse registro; desenvolvimento não está bloqueado.

## 5.3 Ingestão persistente (roteiro #124 PR 2, rc.78)
Série **de plataforma** (V113), não da V60: a V60 é por projeto, até 744 horas, com candidato de energia e `gas='CO2e'`, e a planilha é nacional, anual e de CO2. A API `/v1/platform/grid-series` recebe a saída do importador (rc.71), confere o SHA-256 do arquivo com o da edição registrada (`MCTI-SIN-DESPACHO`, V112), exige licença efetiva para `EXTRACT_TEXT` ([CF-DATA-007](CF-DATA-007-LICENCA-PUBLICA-MCTI.md): a declaração interna do responsável basta para o processamento interno, e o registro grava que **não** é aprovação independente), guarda o literal de cada valor e os instantes UTC e marca as horas sem instante. **Fuso e convenção:** procurei documentação oficial (página do MCTI, nota técnica de jun/2025, textos sobre os dados abertos do ONS) e **não encontrei** definição; continuam parâmetros obrigatórios declarados por pessoas, sem valor padrão. Vinculação à V60 e elegibilidade VT0011/TOOL07: **não feitas** (PR 3).

## 6. Não coberto
- Das planilhas do MCTI, só a **estrutura** da de Despacho 2026 foi inspecionada; os demais anos, o fuso e a validade das correções não. A margem de construção de 2026 ainda não foi publicada pela própria planilha.
- **Licença do ONS:** o portal de dados abertos do ONS ([dados.ons.org.br](https://dados.ons.org.br/about)) declara "Livres de Licença" e, no conjunto `carga-energia`, a licença "Creative Commons Atribuição" (CC-BY), com crédito ao ONS e dados "como estão". **Só esse conjunto foi lido**; não se sabe quais conjuntos do ONS alimentam o fator do MCTI nem se os de geração/despacho têm o mesmo aviso. A licença do MCTI sobre o fator publicado segue desconhecida.
- Textos da TOOL07 além dos pontos da seção 2.1 (por exemplo as demais opções do OM e o BM); valores numéricos do SIN (deliberadamente não reproduzidos); competidores de créditos (CF-DATA-004).

## 5.4 Ensaio de hipóteses de fuso e convenção da hora (roteiro #124, PR 5; matriz R15)

A planilha não declara o fuso nem a convenção, e a TOOL07/VT0011 não tratam do assunto. `scripts/grid_time_hypotheses.py` lê a mesma planilha sob várias hipóteses (padrão: `America/Sao_Paulo` e `UTC`, cada uma com `HOUR_ENDING_1_24` e `HOUR_START_LABEL_1_24`), mantém todas rotuladas como "declaradas por pessoas" e mede, no **mesmo instante UTC**, quanto o fator muda entre elas. Não aponta nenhuma como correta. Com `--write-series DIR` cada hipótese vira um JSON do importador, importável como edição própria em "Séries da rede" (nada é apagado), e o vínculo de PE usa a edição escolhida pelo humano.

Medição no arquivo real (2026-03-02 UTC, 24 instantes): as seis comparações par a par diferem em **todos** os instantes; a diferença máxima vai de 0,0578 (convenção) a 0,1518 tCO2/MWh (zona e convenção). A escolha importa: sem resposta do MCTI/ONS, qualquer PE calculado fica condicionado à hipótese. Hora local inexistente ou ambígua continua sem instante (nunca adivinhada); o Brasil não tem horário de verão desde 2019, então nenhuma hora de 2026 é bloqueada por isso nas hipóteses de `America/Sao_Paulo`.
