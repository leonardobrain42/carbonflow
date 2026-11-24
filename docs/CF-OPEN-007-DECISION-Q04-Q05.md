# CF-OPEN-007 — Documento de decisão: Q04 (IR no ano inicial) e Q05 (Dy)

**Finalidade:** apoiar a decisão humana registrada em [DECISOES_E_DUVIDAS](../specification/DECISOES_E_DUVIDAS.md) (Q04, Q05). **Este documento não decide, não altera comportamento e não é interpretação autorizada da VM0038.** Preparado em 24/11/2026 por Claude (assistente de engenharia). As recomendações são análise de engenharia e precisam de responsável humano; a interpretação metodológica final cabe a especialista competente, ao VVB ou à Verra.

**Fonte lida:** VM0038 v1.1 (19/ago/2026), Verra, PDF público; trechos conferidos no texto extraído do arquivo, com citações curtas e páginas impressas. Não se reproduz material normativo; consulte o original. Escopo: OPEN BR LDV BEV DCFC SIN GRID_ONLY, rede aberta.

---

## 1. O que o texto da metodologia diz (verificado)

### IR — Equação (1), §9.1 (p.15) e parâmetro IRᵢ, §10.1 (pp.20–21)
- A Eq. (1) eleva IRᵢ ao expoente **y−1** (ano do projeto menos um). No ano 1 o expoente é **zero**.
- §10.1, valor aplicado: “Where baselines are calculated using annually updated parameters in Equation (1), IRᵢ = 1”. A justificativa explica que, com estatísticas anuais da frota aplicável e da comparável, não há necessidade de taxa de melhoria.
- Valores *default* (tabelas da seção 10) existem para **EUA e Canadá**: LDV = 1; HDV = 0,99. **Não há default para o Brasil.**
- A frase “For project year 1, IRᵢ is equal to one” aparece na justificativa do **HDV com valores default** (p.21). Ela não é, no texto, uma regra geral explícita para LDV com dados próprios; aplicá-la ao nosso caso é **interpretação**.
- Fonte declarada do parâmetro: CDM AMS-III.C.

### Dy — Eq. (7) e (8), §9.4 (pp.19–20) e ERC/ERF, §10.2 (pp.32–33)
- ER = (BE − PE) × Dy; **Dy = ERC / (ERF + ERC)**.
- ERC e ERF são definidos como créditos emitidos “in project year y − 1”. Mas a descrição do método de medição diz “simple tallies … from project year 1 through year y − 1” (acumulado). **Os dois trechos não coincidem** (anual vs. acumulado).
- Regiões de projeto: cidade, estado, província ou país para AC; “**national level for DCFCs**” (p.20). Para DCFC no Brasil, o recorte é nacional.
- Se nenhum crédito foi emitido para projetos de frota na região, Dy = 1. Se os créditos de frota cobrem região **maior** que a do projeto, estima-se “a sensible pro-rata share” (exemplo do texto: proporção de EVs em circulação no estado vs. no país).
- Exceção de rede privada/fechada (Dy = 1): o próprio texto (nota 17) diz que normalmente **não é possível demonstrá-la para redes abertas**; nesse caso o proponente deve determinar se há desconto. Nosso recorte é rede aberta, logo a exceção **não é oferecida**.
- O texto **não** diz o que fazer para ERC/ERF quando y = 1 (não existe “ano do projeto 0” do próprio projeto).

---

## 2. Q04 — IR no primeiro ano e na linha de base ex ante

**Situação atual do produto.** `FIRST_YEAR_REVIEW_REQUIRED` bloqueia o cálculo no ano 1 (política D07: não se deduz IR=1 “pelo expoente zero”). Já existem: plano ex ante (V94), parecer inicial independente vinculado ao plano (V93/V95) e prova anual de atualização para os anos ≥ 2 (V96, reuso intra-ano #244). Falta definir **quando o ano 1 pode virar um candidato interno**.

**Fato útil:** no ano 1, BEᵧ **não depende do valor de IR** (IR⁰ = 1 para qualquer IR > 0). O risco real da decisão está nos **anos seguintes**: a escolha da rota (atualização anual) cria um compromisso que o projeto deve cumprir e provar a cada ano.

### Alternativas

| | A. Manter bloqueio até esclarecimento formal | B. Rota de atualização anual: IR = 1 declarado, condicionado a plano revisado | C. Ano 1 apenas pelo expoente zero |
|---|---|---|---|
| Regra | Nenhum número no ano 1 sem parecer metodológico externo | Candidato IR = 1 no ano 1 **somente** com a fundamentação da rota anual (§10.1) + plano ex ante vigente + parecer inicial independente (V93/V95), marcado como estimativa ex ante | IR⁰ = 1; sem exigir plano |
| Base no texto | Nenhuma lacuna a explorar | §10.1 (“annually updated parameters” ⇒ IR = 1); plano = compromisso de atualizar EVR, AFEC, MPG, EFfuel | Aritmética da Eq. (1) apenas |
| Risco metodológico | Mínimo | Moderado: depende de o revisor aceitar que o plano fundamenta a rota; ano 2 em diante já exige prova (V96) | Alto: contraria D07 e tira a rastreabilidade da rota |
| Efeito no usuário | Ano 1 fica sem número; nenhum ensaio de BE/ER antes de validar | Permite ensaio/estimativa do ano 1 com gates claros | Permite ensaio, mas sem trilha de compromisso |
| Mudança de código | Nenhuma | Novo estado (ex.: ex ante candidato) no `classify`/E8; reuso do V95; teste negativo sem plano/sem parecer | Remover guarda do ano 1 (não recomendado) |
| Reversibilidade | Total | Alta (estado novo, histórico preservado) | Baixa |

**Recomendação de engenharia: B**, com condições:
1. Ensaio e estimativa ex ante apenas; nunca “monitorado” nem VCU; `usableForE8=false` mantido.
2. Exigir plano V94 vigente + parecer V93/V95 com vínculo atual e revisor distinto do autor.
3. Rotular explicitamente que o valor não vem de default nacional (não existe) e que a rota é a de **atualização anual**; qualquer outra rota (IR ≠ 1) fica fora e continua bloqueada.
4. Se o plano ou as referências mudarem, o candidato volta a `RECHECK`/`STALE` sem apagar o histórico.
5. Ano 2 em diante: sem mudança (prova V96 + reuso #244).

Pergunta para formalizar com especialista/VVB: **“Para LDV com dados próprios, sem tabela default, o IR = 1 vale já no ano 1 com plano de atualização anual?”** Enquanto não houver resposta, B deve ser tratada como **política interna reversível**, como D09/D12.

**Quem decide e o que registrar:** responsável humano, data, opção escolhida, base consultada (VM0038 §10.1, pp.20–21) e se houve consulta formal.

---

## 3. Q05 — Dy (ERC/ERF)

Há quatro decisões independentes.

### 3.1 Janela: anual (y−1) ou acumulada (ano 1 a y−1)?
| Opção | Efeito | Observação |
|---|---|---|
| a) **Anual** (apenas o ano y−1) | ERC/ERF menores; Dy mais sensível ao ano | Segue a definição de ERC/ERF e é o que V82–V88 já implementam |
| b) **Acumulada** (ano 1…y−1) | ERC/ERF crescem; Dy tende a estabilizar | Segue a descrição do método de medição |
| c) **Ambas registradas**, cálculo só após decisão | Dados completos para qualquer leitura | Mais trabalho de coleta; não decide nada |

**Recomendação: c + manter (a) como leitura vigente até decisão externa.** Registrar as duas totalizações é barato (já há emissões individualizadas com data, V84) e evita refazer a pesquisa. O cálculo continua exigindo uma única interpretação escolhida e marcada na avaliação.

### 3.2 Ano 1 (y = 1)
| Opção | Efeito |
|---|---|
| a) **Janela exploratória** de 12 meses antes do início de creditação (V88, já implementada) | Permite pesquisa e matriz licenciada; sem ERC/ERF/Dy aprovados |
| b) **Acumulado até o início de creditação** | Cobre tudo que já foi emitido; amplia a pesquisa |
| c) **Dy = 1 por ausência comprovada** | Só vale com prova de que nenhum crédito de frota foi emitido na região; **zero não comprova ausência** (V86) |

**Recomendação: manter (a) como janela de pesquisa e aceitar (c) apenas com matriz de cobertura nacional completa e parecer independente.** O texto autoriza Dy = 1 quando *não há* créditos emitidos; a dificuldade é provar a ausência, não o valor. Nunca usar Dy = 1 como default.

### 3.3 Recorte e rateio (DCFC no Brasil)
O recorte é **nacional** (p.20). O rateio só é necessário se algum projeto de frota cobrir região **maior que o Brasil** (ex.: América Latina ou global). A base sugerida pelo texto é a proporção de EVs em circulação.

| Opção | Efeito |
|---|---|
| a) **Sem rateio** (só créditos de frota estritamente brasileiros) | Simples; descarta projetos multi-país |
| b) **Rateio por EVs em circulação** (exemplo do texto) | Alinhado ao texto; exige denominador de EVs por região |
| c) **Outra base** (área, população, frota total) | Não prevista; exige justificativa própria |

**Recomendação: b, condicionada à lacuna G03.** O denominador nacional de BEVs em circulação (EVR) **ainda não está admitido**; sem ele o rateio fica **bloqueado**, não estimado. Isto liga a decisão Q05 à ingestão de fontes que você já considera (RENAVAM/SENATRAN). V87 (atribuição geográfica declarada, BR × denominador) já comporta a fração.

### 3.4 Pesquisa inconclusiva
| Opção | Efeito |
|---|---|
| a) **Bloqueia** o cálculo (comportamento atual) | Seguro; nenhum Dy sem evidência |
| b) Permite **faixa de sensibilidade** (Dy entre 0 e 1) só para ensaio | Informativo; política interna, não prevista na metodologia |

**Recomendação: a.** Observação: se ERC = 0 e ERF > 0, Dy = 0 e ER = 0; se ERF = 0 e ERC > 0, Dy = 1; se ambos são 0, a fórmula é indefinida e vale a regra “sem créditos ⇒ Dy = 1”, que exige a prova de ausência do item 3.2(c). Um ensaio com faixa (b) pode ser útil à gestão, mas **não** deve alimentar o número do PD.

---

## 4. Resumo para decidir

| Questão | Recomendação | Reversível? | Exige evidência/dado novo? |
|---|---|---|---|
| Q04: IR no ano 1 | **B** (rota anual, plano revisado, estimativa ex ante) | Sim | Plano e parecer já existentes; consulta formal desejável |
| Q05.1: janela | Registrar anual e acumulada; leitura vigente = anual | Sim | Emissões individualizadas (V84) |
| Q05.2: ano 1 | Janela exploratória + ausência comprovada com parecer | Sim | Matriz de cobertura nacional (fontes licenciadas) |
| Q05.3: rateio | Por EVs em circulação, bloqueado até EVR nacional | Sim | **EVR nacional admitido (G03)** |
| Q05.4: inconclusivo | Bloquear | Sim | Nenhuma |

## 5. O que muda no código depois da decisão (estimativa, não compromisso)
- **Q04-B:** estado novo no contexto de IR do ano 1 (separado de `FIRST_YEAR_REVIEW_REQUIRED`), ligado ao V95; testes negativos: sem plano, plano obsoleto, revisor igual ao autor, rota diferente de anual. Sem migração obrigatória se o estado for derivado.
- **Q05.1:** campo de totalização (anual/acumulada) na avaliação; migração aditiva; sem reescrever avaliações antigas.
- **Q05.3:** consumo do denominador EVR pelo rateio, quando a fonte for admitida.
- Todos os casos: `usableForE8=false` até admissão; documentação e matriz atualizadas no mesmo PR.

## 6. Limites deste documento
- É análise, não parecer. Não substitui consulta a especialista/VVB/Verra.
- A frase do ano 1 do IR está num parágrafo sobre HDV; a leitura de que ela também vale para LDV com dados próprios é interpretação.
- A numeração de páginas é a impressa no PDF; confira no original antes de citar externamente.
- Não verifiquei registros de créditos reais (ERC/ERF), nem a existência de projetos de frota no Brasil.

## 7. Registro da decisão (preencher)
| Campo | Q04 | Q05 |
|---|---|---|
| Opção escolhida | **B** (rota de atualização anual, plano revisado, só estimativa ex ante) | _pendente_ |
| Responsável e competência | Responsável pelo produto (decisão em conversa de 24/11/2026); competência metodológica não declarada | |
| Data | 24/11/2026 | |
| Base consultada | Este documento; VM0038 v1.1 §10.1 (pp.20–21) | |
| Consulta formal (Verra/VVB), se houve | Não registrada | |
| Condições adicionais | As cinco condições da recomendação B (§2); ano 1 segue bloqueado por Dy | |
| PR que implementa | #248 | |
