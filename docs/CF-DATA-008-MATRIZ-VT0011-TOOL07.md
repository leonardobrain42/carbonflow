# CF-DATA-008 — Matriz de conformidade VT0011 v1.0 / TOOL07 v7.0 para a série horária do SIN

> **Estado:** mapa de requisitos, evidências e lacunas. **Não admite a série**, não declara elegibilidade e não libera `usableForE8`. Nível D17: método/edição (VM0038 v1.1 + VT0011 v1.0 + TOOL07 v7.0), aplicado ao recorte OPEN BR LDV BEV DCFC SIN GRID_ONLY. Dado: [`docs/data/vt0011-tool07-conformance.json`](data/vt0011-tool07-conformance.json); validador: `scripts/vt0011_tool07_conformance.py` (gate no CI).

## Fontes lidas por inteiro nesta fatia (texto extraído dos PDFs, hashes no JSON)
- **VT0011 v1.0** (13/02/2025), todas as 10 páginas. Substitui parágrafos da TOOL07 (¶16, 25, 39, 45, 50, 72, 75, 79, 86, 90, 91), remove a condição de aplicabilidade nº 5 e lê "projetos do CDM" como "projetos do VCS e de outros programas, inclusive o CDM".
- **TOOL07 v7.0**, em especial ¶3–6 (aplicabilidade), ¶15–27 (sistema elétrico e importações), ¶38–45 (método e vintage), ¶63–71 (Dispatch data e Average OM).
- **VM0038 v1.1**, seção 9.2 (Eq. 4 e o texto do fator horário) e o parâmetro `EFeli,j,y` (seção 10).

## Resultado (19 requisitos)
| Estado | Linhas | Significado |
|---|---|---|
| IMPLEMENTED | R02, R14, R16, R17 | a plataforma tem a estrutura; **não** prova elegibilidade da fonte |
| PARTIAL | R01, R08 | parte feita (conversão de unidade, ano-base), parte aberta |
| NOT_EVALUATED | R10 | Eq. (12) pertence ao dono do cálculo E8; fora desta fatia |
| GAP | R03, R04, R07, R09, R19 | falta registro/decisão/dado |
| CANNOT_DETERMINE | R05, R06, R11, R12, R13, R15, R18 | os documentos lidos não permitem decidir |

## O que a leitura mudou
1. **A pergunta central é R11.** A TOOL07 define o fator horário do Dispatch data (¶65–69) sobre as usinas do **topo do despacho** (pelo menos 10% da geração da hora, ou a parcela deslocada, em ordem de mérito do centro de despacho). A tabela do arquivo do MCTI se intitula "Fator de Emissão Médio (tCO2/MWh) - HORÁRIO". O arquivo não diz se o valor é o do topo do despacho ou uma média do sistema (que seria o Average OM, ¶70). Isso não se resolve com os dados que temos; a série fica candidata até haver nota metodológica do MCTI ou dados primários do ONS.
2. **R17 e R18 são coisas diferentes.** O VT0011 permite, por texto, aplicar a TOOL07 a projetos fora do MDL (compatibilidade metodológica). A frase do MCTI de que o fator de despacho serve "exclusivamente" ao MDL é restrição do fornecedor sobre o dado, e o VT0011 não a afasta.
3. **Caso 2 não precisa de margem de construção:** o ¶86 fixa wOM=1,0 e wBM=0,0 (R14). Falta só registrar a decisão de que o projeto é Caso 2.
4. **Importações (R05):** para projeto que aumenta o consumo da rede, o VT0011 ¶25 traz a opção a2, 1,3 tCO2/MWh. Nenhum default foi aplicado: o arquivo não informa como trata importações.
5. **Fuso e convenção da hora (R15):** nem a TOOL07 nem o VT0011 tratam do assunto; segue como hipótese declarada por pessoas, e só os intervalos sem correspondência fundamentada ficam bloqueados.

## Como usar
Cada linha com `toResolve` é uma tarefa com dono: ou uma consulta ao MCTI/ONS, ou uma decisão registrada, ou um dado primário. Uma linha só muda de estado com evidência citável; o validador recusa texto que afirme conformidade ou admissão.
