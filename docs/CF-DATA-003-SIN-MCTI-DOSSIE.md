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

> **Atualização de 25/11/2026 (releitura):** a nota técnica do MCTI de junho/2025 foi lida (cópia hospedada por terceiro, [NT FE jun25](https://cenarioenergia.com.br/wp-content/uploads/2025/06/NT_FE_jun25.pdf); cabeçalho do MCTI, **autenticidade não verificável por este caminho**). Ela confirma, em texto, os itens acima: fatores de **CO₂** da Margem de Operação; ampliação da base do ONS a partir de janeiro de 2025 (conjuntos eólicos com ponto de inserção compartilhado, solares e biomassa) com metodologia inalterada; Margem de Operação por Análise de Despacho e fator médio **mensais**; Margem de Construção e Simples Ajustado **anuais**. **A página oficial do MCTI/SIRENE continua não lida** (a URL tentada deu 404 e a busca não localizou a página dos arquivos).

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
- **Resolução horária da Análise de Despacho:** TOOL07 par. 63–64 e Eq. (12): EFgrid,OM-DD,y = Σh (EGPJ,h × EFEL,DD,h) / EGPJ,y, com h as horas do ano em que o projeto desloca eletricidade; "not applicable to historical data"; frequência de monitoramento "Hourly". **Um fator mensal único não tem a forma da Eq. (12).** Se o método de Despacho for o escolhido, é preciso o fator **por hora** (EFEL,DD,h), ponderado pela energia deslocada em cada hora. Isso casa com a série horária V60 do produto. **Se a série mensal do MCTI satisfaz essa exigência não está no texto** e é pergunta para a revisão normativa; **esta é uma inferência minha**, não afirmação da ferramenta.
- **Importações:** VT0011 par. 25 (substitui a da TOOL07): para importações líquidas de sistemas conectados, a opção a2 é "1.3 t CO2/MWh for projects increasing the consumption of electricity from the grid". O par. 50 (A.2.ii) manda usar o combustível de **maior** fator e o A.3.ii admite 1,3 tCO2/MWh quando só há dado de geração. **São opções do texto, não aplicadas pelo produto** (nenhum default é usado).
- **Fatores nacionais:** nas partes lidas, "Source of data: Utility or government records or official publications" (parâmetros de OM). Isso admite fonte oficial como origem, **não** admite um fator publicado sem as condições acima.

## 3. Matriz de lacunas (fonte × exigência)
| Ponto | Situação | Quem fecha |
|---|---|---|
| A Análise de Despacho é permitida sob a VT0011 para um projeto VM0038? | **Permitida pelo texto** (VT0011 par. 39), com a restrição do par. 41 (sem usinas off-grid no sistema). Resta demonstrar a escolha e que a série usada tem a forma da Eq. (12) da TOOL07 | Revisão normativa (Q03) |
| Fator médio do SIN (inventário) como EFel | **Não admissível por si**: não é OM; só a fonte e a escolha demonstrada decidem | Revisão normativa |
| Gás: fonte em CO₂; EFel do CarbonFlow é CO₂e | Conversão/justificativa ausente; a prévia do caderno V61 já avisa que CO₂ não é CO₂e | Revisão normativa |
| Resolução temporal: fonte mensal; a Eq. (12) da TOOL07 pede fator **por hora** e a série V60 exige uma linha por hora UTC do EC medido | O texto não autoriza repetir o valor mensal em cada hora; nada é repetido por padrão. Para o Simple OM a vintage é anual, mas há a prova de LCMR <50% | Q03 / revisão |
| Vintage: ex ante (3 anos) × ex post, e a **quebra de base de 2025** | Escolha e tratamento da quebra não decididos; o TOOL07 A1 do repositório cobre só um ano `EX_POST` | Q03 |
| Licença e termos de uso (MCTI e ONS), titular, redistribuição | **Desconhecidos**; a tela de licenças existe, mas nenhuma licença foi registrada | Titular da fonte / produto |
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

## 6. Não coberto
- A **página primária do MCTI/SIRENE** e seus termos: não lidas (a URL tentada deu 404). A nota de junho/2025 foi lida só por cópia de terceiro.
- **Licença do ONS:** o portal de dados abertos do ONS ([dados.ons.org.br](https://dados.ons.org.br/about)) declara "Livres de Licença" e, no conjunto `carga-energia`, a licença "Creative Commons Atribuição" (CC-BY), com crédito ao ONS e dados "como estão". **Só esse conjunto foi lido**; não se sabe quais conjuntos do ONS alimentam o fator do MCTI nem se os de geração/despacho têm o mesmo aviso. A licença do MCTI sobre o fator publicado segue desconhecida.
- Textos da TOOL07 além dos pontos da seção 2.1 (por exemplo as demais opções do OM e o BM); valores numéricos do SIN (deliberadamente não reproduzidos); competidores de créditos (CF-DATA-004).
