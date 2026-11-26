# CF-DIRETRIZES-D19 — Fatias, bloqueios e evidência por pendência

Complementa [CF-SPEC-D19](DECISOES_E_DUVIDAS.md). **Nível D17:** plataforma, com fatias por método (VM0038 v1.1 / VT0011 / TOOL07), jurisdição (BR) e dados externos. Não é norma, não admite fonte e não altera comportamento.

## Princípio: dois tipos de bloqueio
| Tipo | Significado | Efeito |
|---|---|---|
| **Desenvolvimento** | Falta algo que impede construir o contrato, a validação, a interface ou o teste | Para a fatia |
| **Utilização operacional** | O código existe e é testado com dado sintético, mas o dado real só entra quando a informação externa existir | O caminho real fica bloqueado **por um motivo nomeado** (licença, fuso, fonte, confirmação), nunca por "admissão pendente" genérica |

## Estados de decisão (o que a operação individual precisa)
Hoje a fila de candidatos de alias recusa quem importou ao decidir (`DUPLA_CUSTODIA`). Para o responsável operar sozinho sem fingir independência, cada decisão passa a levar **a base** em que foi tomada:
| Base | Quem pode | O que significa | O que **não** significa |
|---|---|---|---|
| `OWNER_INTERNAL` | O responsável da organização administrativa, inclusive sendo quem importou | Decisão técnica interna do dono do produto, registrada com autor e motivo | Não é revisão independente, não é parecer, não é admissão |
| `INDEPENDENT` | Pessoa diferente de quem preparou (regra atual) | Revisão independente de fato | Não é admissão normativa nem verificação externa |
| Admissão definitiva | Só com confirmação externa/normativa quando a regra pedir | Habilita o uso operacional | Não existe nesta fase |
Regras: a base é gravada na decisão e exibida; `OWNER_INTERNAL` **nunca** satisfaz uma exigência que peça `INDEPENDENT` (por exemplo o parecer independente de uma versão exata do Dy ou do IR); nada converte uma base em outra; a mudança de base é uma nova decisão. As listas de importadores e revisores (Q08) passam a poder conter o responsável nas duas funções, **com a base registrada**.

## Fatias, em ordem
| # | Fatia | Bloqueio de desenvolvimento | Bloqueio de utilização operacional |
|---|---|---|---|
| 1 | Estados de decisão interna (base `OWNER_INTERNAL`) na fila de candidatos; tela | Nenhum | Nenhum para ensaio; a base fica visível |
| 2 | Tela de cadastro e gestão dos modelos canônicos (lista, cadastro, alias, classificação, pendente) | Nenhum | Admissão do modelo no recorte inicial exige classe LDV, propulsão BEV e evidência; `UNKNOWN` fica pendente |
| 3 | Pipeline de classificação e conciliação Senatran/RENAVAM, PBEV e fabricantes, com evidência por modelo, variante e alias | Nenhum | Dado real: licença e arquivo oficial (Senatran/PBEV), denominador nacional (G03) |
| 4 | Importador das planilhas horárias do MCTI (original preservado, localizadores, normalização parametrizada, testes sintéticos) | Nenhum | **Fuso e convenção das horas** (parâmetro obrigatório sem padrão), **licença/uso** (CC BY-ND; "exclusivamente MDL"), CO₂ × CO₂e, elegibilidade VT0011/TOOL07 |
| 5 | Catálogo extensível de combustíveis brasileiros (gases, unidades, composição, vigência, vintage, evidência, revisão) | Pesquisa de fontes oficiais confrontadas com a metodologia | Cada fator exige fonte e fundamento; sem valor inventado |
| 6 | Dy: pesquisas de créditos concorrentes, cobertura anual e acumulada, importação de evidências, conciliação das emissões | Nenhum (D14 vigente) | Universo de programas e termos dos registros; confirmação anual × acumulada |

## Evidência necessária por questão pendente
| Questão | Evidência que a fecha | Onde registrar | Fatia |
|---|---|---|---|
| Q02 estratégia do EVR nacional | Fonte do denominador e cobertura documentadas; decisão do responsável com base nelas | DECISOES, CF-OPEN-005 | 3 |
| Q03 método OM, vintage, gases | Demonstração de elegibilidade (TOOL07 par. 40/41, VT0011 par. 39); decisão de fuso/hora; parecer sobre CO₂ × CO₂e | CF-DATA-003, caderno V61 | 4 |
| Q05 Dy anual × acumulada | Confirmação externa (Verra/VVB) ou decisão registrada do responsável com a D14 | CF-OPEN-007-DY-Q05-POLICY | 6 |
| Q08 listas da fila | Configuração (responsável nas duas listas) e a base registrada em cada decisão | CF-OPEN-005-EVR-STOCK-BR | 1 |
| Q09 classe/propulsão/organização | Declaração do responsável por modelo, com evidência (PBEV/ficha); `UNKNOWN` fica pendente | catálogo | 2, 3 |
| Q10 licença do MCTI | Resposta da consulta (**enviada pelo responsável**) e/ou parecer jurídico; licença registrada na V37 | CF-DATA-003-CONSULTA-MCTI | 4 |
| Q11 universo de programas e termos dos registros | Lista decidida, URL dos termos e como filtrar cada registro | CF-DATA-004 | 6 |
| Q12 (nova) EFfuel: fontes oficiais por combustível | Fonte, edição, gás, unidade, composição (por exemplo mistura anidro/gasolina), vigência e confronto com a metodologia | catálogo de combustíveis | 5 |

## Regras de execução (D19-6)
- Cada fatia é um PR com testes executados e relato do que não foi executado.
- Informação externa ausente: contrato, validação, interface e testes sintéticos entram; o caminho real fica bloqueado pelo item nomeado.
- Nunca presumir: fuso, convenção de hora, autorização de processamento, CO₂ = CO₂e, valor de combustível, classe do veículo.
- Dado licenciado ou restrito não é versionado; fixtures são sintéticos e rotulados.
