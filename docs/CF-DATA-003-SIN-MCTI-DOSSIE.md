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

## 2. O que o método exige (já registrado no repositório)
Conforme [CF-OPEN-007-VT0011-CASE2-WORKBOOK](CF-OPEN-007-VT0011-CASE2-WORKBOOK.md) e [CF-OPEN-007-TOOL07-SIMPLE-OM-A1-PRECHECK](CF-OPEN-007-TOOL07-SIMPLE-OM-A1-PRECHECK.md):
- VT0011 §5, caso 2: pesos OM = 1,0 e BM = 0,0 (o valor de BM não é presumido zero; o peso é nulo).
- TOOL07 §6.3: quatro métodos OM (Simple, Simple adjusted, Dispatch data analysis, Average). **A escolha tem de ser demonstrada com as condições de elegibilidade; não é automática.**
- O fator MCTI de inventário corporativo **não vira** OM metodológica ([PARAMETER-FOUNDATION](CF-OPEN-007-PARAMETER-FOUNDATION.md)).

## 3. Matriz de lacunas (fonte × exigência)
| Ponto | Situação | Quem fecha |
|---|---|---|
| A Análise de Despacho do MCTI é aceita como o método "Dispatch data analysis OM" da TOOL07 sob a VT0011 para um projeto VM0038? | **Não verificado.** Só vale após leitura do texto da VT0011/TOOL07 e, se preciso, consulta à Verra | Revisão normativa (Q03) |
| Fator médio do SIN (inventário) como EFel | **Não admissível por si**: não é OM; só a fonte e a escolha demonstrada decidem | Revisão normativa |
| Gás: fonte em CO₂; EFel do CarbonFlow é CO₂e | Conversão/justificativa ausente; a prévia do caderno V61 já avisa que CO₂ não é CO₂e | Revisão normativa |
| Resolução temporal: fonte mensal; a série V60 exige **uma linha por hora UTC** do EC medido | Incompatível sem regra normativa que autorize repetir o valor mensal em cada hora; nada é repetido por padrão | Q03 / revisão |
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
Leitura da página primária do MCTI/SIRENE, dos termos do ONS, e do texto da VT0011/TOOL07 (referenciados, não relidos nesta fatia); valores numéricos do SIN (deliberadamente não reproduzidos); competidores de créditos (CF-DATA-004).
