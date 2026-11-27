# CF-OPEN-005 — Matriz de direitos das fontes de frota (RENAVAM e PBEV)

Data: 27/11/2026 · Nível D17: **programa/jurisdição** (fontes da frota brasileira) sobre mecanismo de **plataforma** (licenças de conteúdo, D19). Dados legíveis por máquina: [`fleet-sources-rights-matrix.json`](data/fleet-sources-rights-matrix.json); teste: `scripts/test_fleet_sources_rights.py`.

**O que este documento é:** o registro fundamentado, por operação, do que cada fonte permite. **O que não é:** parecer jurídico, aprovação independente, admissão de dados, cobertura nacional ou habilitação do E8. A autorização do responsável (27/11/2026) cobre o registro dos direitos incontroversos e a elaboração desta matriz; o que não está suficientemente fundamentado **permanece bloqueado**.

## Como o registro funciona (reuso do D19)
1. O responsável abre a tela **Licenças** (`/workspace/platform/licenses`), cola o conteúdo de `docs/data/*-license-declaration.json` em "Carregar declaração preparada (JSON)", revisa os campos e clica em "Declarar licença pública" (a tela chama `POST /v1/platform/source-licenses/public`; carregar não envia nada). Resultado: `DECLARED_INTERNAL`, que **nenhum consumidor aceita**.
2. **Outra pessoa** aprova (`APPROVED`, dupla custódia). Numa licença pública, o aprovador só pode **restringir** ao que foi declarado, nunca ampliar (422 `OPERACAO_NAO_DECLARADA`); a tela lista e pré-marca apenas as operações declaradas. Só então o gate de importação da fila de candidatos (`EXTRACT_TEXT` nas duas fontes) libera.
3. As fontes constam do catálogo de metadados pela migração V119 (sem bytes nem valores); a edição inclui o prefixo do SHA-256.

## RENAVAM — setembro de 2026 (`SENATRAN-RENAVAM-FROTA`, edição `2026-09-005963d4`)
Fundamento: o portal de dados abertos declara o recurso como `other-pd` ("Outra (Domínio Público)"), metadado capturado e preservado por SHA-256 ([evidência](data/evidence/renavam-license-capture.json)). É a declaração oficial do portal, **não** um texto legal com cláusulas; o recurso não traz URL de licença. Início de vigência = captura (a data de modificação do recurso, 09/10/2026, não é usada).

| Operação | Estado | Operação da plataforma | Escopo e restrições |
|---|---|---|---|
| Referenciar fonte e edição | Liberada | `REFERENCE_REQUIREMENT` | — |
| Guardar o original | Liberada | `STORE_ORIGINAL` | Guarda local fora do Git; upload à plataforma segue o limite do #92 |
| Extração factual (marca/modelo, ano, UF, quantidade) | Liberada | `EXTRACT_TEXT` | Proveniência: edição, SHA-256, período, linha |
| Conversão de unidades | Não se aplica | — | Só há contagem de veículos |
| Cálculos derivados (somas, participações, reconciliação) | Liberada | `EXTRACT_TEXT` | Resultado nunca vira circulação, BEV elegível ou frota ativa |
| Exibição | Liberada | `SHOW_EXCERPTS` | Atribuição ao Ministério dos Transportes/Senatran e à edição |
| Exportação de agregados | Liberada | `EXPORT_DERIVATIVES` | Sem dados pessoais; com proveniência; escopo 2026-09 |
| IA (índice, embeddings, RAG, inferência, redação) | **Bloqueada** | — | Não solicitada |
| Cruzar município com combustível/fabricação | **Bloqueada** | — | Bloqueio metodológico (sem chave comum), não de direitos |

Não afasta direitos de terceiros nem restrições sobre dados pessoais (o arquivo é agregado). Outras edições exigem nova captura e nova declaração.

## PBEV 2026 (`INMETRO-PBEV`, edição `2026-rev01-43e928f8`)
Fundamento: rodapé do site do Inmetro declara o conteúdo sob **CC BY-ND 3.0** ([evidência](data/evidence/pbev-license-capture.json); texto jurídico com o mesmo hash já preservado para o MCTI). **Lei 9.610/1998, art. 7º, XIII e §2º:** a proteção de bases e compilações recai sobre a **seleção e a organização**; não abarca os **dados em si**. A cláusula ND impede redistribuir **adaptação** da obra. Consequência: extrair o fato linha a linha é defensável; reproduzir a organização da tabela ou exportar obra modificada não é.

| Operação | Estado | Operação da plataforma | Fundamento |
|---|---|---|---|
| Referenciar fonte e edição | Liberada | `REFERENCE_REQUIREMENT` | Cláusulas 3 e 4 |
| Guardar o PDF sem alteração | Liberada | `STORE_ORIGINAL` | Cláusula 3 (Reproduzir) |
| Exibir trechos sem alteração, com atribuição | Liberada | `SHOW_EXCERPTS` | Cláusulas 3 e 4(b) |
| Extração factual de identidade (categoria, marca, modelo, versão, propulsão) | **Interpretação declarada** | `EXTRACT_TEXT` | Art. 7º, XIII e §2º; vale só após aprovação independente |
| Extração dos valores de eficiência/consumo | **Bloqueada** | — | Fatia F (#95) não implementada; fundamento e revisão próprios |
| Conversão de unidades dos valores | **Bloqueada** | — | Sem parecer independente de que não é adaptação (ND) |
| Cálculos derivados (médias, pesos, comparações) | **Bloqueada** | — | Depende da extração de valores e de fundamento próprio |
| Exibir fatos extraídos reorganizados | **Bloqueada** | — | Risco de reproduzir a organização protegida |
| Exportar derivados | **Recusada** | `EXPORT_DERIVATIVES` | ND: a concessão não inclui Adaptações |
| IA | **Bloqueada** | — | Não solicitada |

## O que desbloquearia o que está bloqueado
Parecer/aprovação independente por operação (conversão, derivados, exibição reorganizada), autorização expressa do Inmetro (para exportação de derivados) e a implementação e revisão da fatia F. Nada disso é presumido aqui.
