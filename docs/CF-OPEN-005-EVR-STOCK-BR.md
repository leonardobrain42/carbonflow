# CF-OPEN-005 / #94 — Frota BEV nacional em circulação por modelo e ano (EVR)

**Escopo da primeira fatia (PR #110):** contrato, identidade de modelos, aliases observacionais, validação, proveniência licenciada, snapshots imutáveis e UX de diagnóstico. O pacote **não contém uma série brasileira efetivamente admitida** e não altera o piloto sintético. [Issue #94](https://github.com/leonardobrain42/carbonflow/issues/94) permanece aberta até a qualidade/cobertura das séries, ratings, direitos e aplicação em projeto serem comprovados.

## Fontes públicas verificadas e lacuna real

**Atualização de 23/11/2026.** A amostra real do RENAVAM de setembro de 2026 foi
obtida e conferida com a publicação por combustível. O relatório de
[viabilidade e ligação aos ratings](CF-OPEN-005-BR-DATA-FEASIBILITY.md) registra
hashes, cabeçalhos, quantidades por chave literal e ano de fabricação, candidatos
PBEV e as lacunas que impedem admissão. As notas abaixo descrevem o diagnóstico
anterior à leitura dessa amostra.

O índice da **Senatran, Frota de Veículos — 2026** (<https://www.gov.br/transportes/pt-br/assuntos/transito/conteudo-Senatran/frota-de-veiculos-2026>, consultado em 19/11/2026) lista recursos por **UF, município, ano de fabricação e modelo** e recursos separados por **marca/modelo** e por **combustível**. O portal de dados abertos **RENAVAM** (<https://dados.transportes.gov.br/dataset/registro-nacional-de-veiculos-automotores-renavam>) descreve dados agregados por localidade, marca/modelo, ano e quantidade.

Isso **não demonstra** que já existe uma publicação que identifique, numa única série reconciliável, os BEVs leves DCFC por **modelo, ano de observação e estoque em circulação no Brasil**, com aliases e correção temporal. Em particular:
- ano de fabricação **não** é automaticamente ano de observação da frota;
- agrupamento por combustível **não** prova a propulsão de cada modelo/versão sem reconciliação fundamentada;
- PBEV/Inmetro contém atributos de eficiência e ciclo, **não** o denominador de estoque em circulação;
- vendas novas no ano e frota acumulada em circulação são grandezas distintas;
- a frota de veículos usuários de uma rede aberta não precisa ser nominada.

Uma eventual junção oficial Senatran + classificação de propulsão + versões exige exame da **granularidade efetiva** dos arquivos, documentação dos modelos/aliases e controles de cobertura. **Nenhum dado de mercado oficial foi importado automaticamente nesta fatia.**

## Contratos tipados de entrada

As fontes são registradas no fluxo #92 `app_platform_source_submission`, com digest real do arquivo em `app_platform_source_object` e dados tipados em `app_platform_source_candidate`.

O arquivo CSV/XLSX, **quando autorizado**, deve passar pelo parser existente #92 e por avaliação humana `APPROVED_FOR_SCOPE` por objeto, ano, métrica, unidade, geografia e metodologia. Esquema genérico de demonstração de teste — **não são números reais de BEVs**:

```csv
record_key,period_year,metric,value,unit,geography
MODEL_A,2024,stock,25,vehicles,BR
MODEL_B,2024,stock,75,vehicles,BR
```

Para o estoque EVR o `need_code` é `OPEN-EVR` e a métrica é `stock`, com valor inteiro e unidade `vehicles`. O candidato `OPEN-EV-SALES` com métrica `sales` é mantido na tabela **separada** `app_fleet_sales_observation` e **nunca** gera composição EVR.

É necessária licença `AUTHORIZED_PROCESSING` em nome da **organização de staff** para a fonte/edição e as operações específicas:
- `EXTRACT_TEXT` e `SHOW_EXCERPTS` para visualizar linhas;
- `EXPORT_DERIVATIVES` para persistir snapshots/observações derivados;
- a licença do objeto original permanece vinculada por FK e verificada no acesso;
- não é exigido `RAG_RETRIEVE` e nenhuma operação IA é executada;
- `GET /v1/platform/fleet/board` mostra estado e metadados autorizados; a API não envia valores ao visitante ou projeto.

## Modelo canônico e invariantes

A migração `V48__fleet_evr_canonical_and_snapshots.sql` cria:
- `app_fleet_model`: fabricante, modelo, variante, LDV/HDV/UNKNOWN e BEV/PHEV/OTHER/UNKNOWN com autor e organização;
- `app_fleet_model_alias`: chave literal `record_key` de origem, ano de **observação**, organização e `model_id` inequívocos; PK impede mapeamento simultâneo divergente do mesmo alias naquele ano;
- `app_fleet_ingestion`: objeto da fonte/edição, licença, SHA-256 da origem, ano, geografia BR e tipo separado `STOCK_IN_CIRCULATION` / `NEW_SALES`;
- `app_fleet_stock_observation` e `app_fleet_sales_observation` em tabelas distintas, `BIGINT` não negativo, row locator e modelo canônico;
- `app_fleet_composition_snapshot` / `app_fleet_composition_weight` — somente para estoque, com denominador, peso decimal `numeric(22,18)`, hash do arquivo, ano, geografia, linha e data de construção;
- `app_fleet_command` — recibos idempotentes em comandos de escrita, com ator, chave e fingerprint, rejeitando reutilização para outro payload.

A função `FleetCompositionRules.evaluate` exige um arquivo inteiro que respeite **o mesmo ano/geografia/métrica/unidade**, exclusivamente **LDV BEV** para a primeira fatia, aliases resolvidos **sem fusão silenciosa**, `EVR_i ≥ 0` inteiro e soma `> 0` em estoque. Arquivo com linha faltante/inválida falha integralmente; zero não representa ausência. Divide cada `EVR_i` pelo total em escala 18 e ajusta o último peso para garantir soma exatamente igual a 1. Se a precisão não suporta um peso positivo, recusa a composição.

**Critérios ainda não provados pela validação dimensional:** classificação BEV comprovada por evidência externa, completude frente a total oficial nacional, presença de todos os modelos em circulação, comparabilidade e cobertura de ratings (AFEC/MPG). Portanto, até as etapas seguintes:
- `coverageState = OFFICIAL_TOTAL_NOT_VERIFIED`;
- `ratingState = RATINGS_NOT_LINKED`;
- `usableForE8 = false` para toda resposta, inclusive se a fonte obtiver parecer interno;
- o sistema distingue snapshot `PREPARATORY`, `STALE_REFERENCE` (há importação posterior no mesmo recorte) e `RIGHTS_UNAVAILABLE` (licença não vigente/expurgada).

## Segurança, retenção e atualização

O trabalho só aparece a equipes **staff MFA** configuradas em `CARBONFLOW_PLATFORM_SOURCE_READINESS_VIEWERS` e os comandos requerem gestor de fontes. O `organization_id` vem do roster de staff, e chaves compostas FK impedem associar modelo, fonte e snapshot de tenants diferentes. As operações são `Cache-Control: private, no-store`.

A revogação/negação de licença cria um evento na origem e a trigger adicional `app_fleet_license_revoked` apaga **observações e pesos derivados**, limpa o denominador do snapshot e registra `SUSPENDED`, antes do expurgo do original pela trigger da #92. Digest e metadados auditáveis permanecem. **Expiração simples** de licença bloqueia acesso dinamicamente, mas o expurgo físico agendado por vencimento depende da #92.

Snapshots anteriores não são reescritos ao publicar nova série; tornam-se `STALE_REFERENCE` na projeção de leitura. Uma nova edição/material de origem usa novo objeto SHA e produz outro snapshot.

## Interface e API

Em **Admin CarbonFlow → Fontes e prontidão → Frota EVR**:
1. visualizar a lacuna canônica `OPEN-EVR`, sem valores inventados;
2. cadastrar identidades e associar aliases com ano;
3. selecionar uma fonte que já passou pela ingestão #92;
4. pré-validar todas as linhas com pendências de mapeamento/ano, direitos e classificação;
5. apenas depois de parecer interno e direitos específicos, guardar snapshot PREPARATORY;
6. inspecionar pesos e localizadores ou uma série de vendas separada, conforme licença.

Rotas:
- `GET /v1/platform/fleet/board`
- `POST /v1/platform/fleet/models` (Idempotency-Key)
- `POST /v1/platform/fleet/models/{id}/aliases` (Idempotency-Key)
- `GET /v1/platform/fleet/sources/{sourceObjectId}/preview?year=2024`
- `POST /v1/platform/fleet/imports` (Idempotency-Key)
- `GET /v1/platform/fleet/imports/{id}`

A API não aceita inserção arbitrária de quantidades em objeto de projeto, não cria `ProjectSelection`, não envia EVR/AFEC/MPG ao E8 e não oferece fallback numérico para fontes ausentes.

## Ponte da Senatran para o CSV de importação (rc.56)
`scripts/build_evr_stock_csv.py` lê o ZIP autorizado da Senatran e **uma lista explícita de nomes literais** (a mesma seleção de `inspect_senatran_stock_sample.py`) e grava o CSV no formato do parser (`record_key,period_year,metric,value,unit,geography`) mais um manifesto. Regras:
- **Quem escolhe os modelos é quem chama**, com a sua evidência de propulsão/classe; o script não classifica nem faz correspondência automática.
- `record_key` do parser só aceita `[A-Za-z0-9._-]` (até 100); os nomes literais têm `/` e espaços, então a chave é o nome com os caracteres inválidos trocados por `_`, e **duas chaves iguais são recusadas** (nada é fundido). O manifesto guarda chave → nome literal.
- `period_year` é o **ano de observação** do arquivo (não o ano de fabricação); `metric=stock`, `unit=vehicles`, `geography=BR`; a quantidade é de veículos **registrados** (a limitação fica no manifesto).
- Modelo sem linha continua **ausente** (nunca vira zero); zero literal continua zero. Limite de 2000 linhas do parser respeitado.
- O manifesto traz o SHA-256 do arquivo, o total registrado e `usableForE8=false`, `evr_total=null`, `weights=null`.
- O CSV segue pela quarentena, aliases, direitos e revisão existentes; nada é pulado. Não houve execução sobre o arquivo real nesta fatia (o ZIP não está no repositório): os testes usam ZIP sintético, e um teste do parser Java confirma que o formato é aceito e que o nome literal cru é recusado.

## Fila de candidatos de alias (rc.59)
**Correção de um texto meu anterior:** não existia fila de revisão no catálogo canônico. `app_fleet_model` e `app_fleet_model_alias` são gravações diretas e finais (o alias tem chave primária por organização, ano e chave e não é refeito). Por isso esta fatia cria a fila, sem tocar no catálogo.
- **V108 (aditiva):** `app_fleet_alias_candidate` (nome literal, período, chave, nível `EXACT|MODEL_ONLY|AMBIGUOUS`, SHA-256 do PBEV, linhas candidatas do PBEV em `jsonb`, motivo, quem importou) e `app_fleet_alias_candidate_decision` (uma decisão final por candidato: `ACCEPTED` com a **linha do PBEV escolhida** ou `REJECTED`, motivo, quem decidiu). Ambas só de acréscimo (gatilho). Três ações de auditoria.
- **API** `/v1/platform/fleet/alias-candidates` (staff, MFA): `POST` importa um lote (até 500, idempotente por nome, período e arquivo do PBEV), `GET ?state=PENDING|ACCEPTED|REJECTED` lista com a evidência, `POST {id}/decision` decide. Privilégios por duas listas de configuração, `...fleet-alias-candidates.importers` e `.reviewers` (vazio = indisponível); **quem importa não decide** (403 `DUPLA_CUSTODIA`, mesmo estando nas duas listas). **Quem entra em cada lista é decisão de vocês.**
- **Aceitar não cria modelo nem alias**, não classifica o veículo (LDV/BEV), não admite dados nem habilita cálculo; o cadastro do modelo e do alias segue pelas rotas atuais, com a classe e a propulsão que o revisor decidir. Aceitar exige nomear a linha do PBEV (em `MODEL_ONLY`, qual versão).
- O script `match_senatran_pbev.py --emit-import` gera o corpo do lote a partir do cruzamento (nomes sem candidato não entram; a chave é a mesma do CSV da ponte). Sobre a amostra real versionada gera 5 candidatos (4 `EXACT` e 1 `MODEL_ONLY`).
- **Tela (rc.60):** `/workspace/platform/alias-candidates` (MFA, mesmo padrão da tela de licenças): importar lote colando o JSON, filtrar por situação (padrão: pendentes), ver a evidência (linhas do PBEV) e aceitar com a linha escolhida ou recusar com motivo. Aceitar avisa que nenhum modelo nem alias foi criado; a dupla custódia (403) é explicada; perfil fora das listas vê só a mensagem de acesso. O servidor decide todos os estados.
- **Cadastro dos aceitos (rc.61):** as rotas `POST /v1/platform/fleet/models` e `/models/{id}/aliases` já existiam e exigem que um gestor declare classe e propulsão; o PBEV não diz LDV. `scripts/build_fleet_model_registrations.py` lê os candidatos aceitos e um **arquivo de decisões preenchido por pessoas** (organização, e por candidato `vehicleClass` e `propulsion`) e gera o plano: `registrations` (corpo do `POST /models` com a chave idempotente e o primeiro alias), `additionalAliases` (mais um alias para um modelo já planejado, a postar depois de ele existir) e `refused`. Fabricante, modelo e variante vêm da linha do PBEV escolhida. **Nada é presumido:** candidato sem decisão, valor fora do vocabulário, linha sem versão impressa, chaves que colidem ao ficar maiúsculas e o mesmo modelo com declarações diferentes são recusados com o motivo. Nada é enviado; o plano é postado por um gestor da plataforma, e o alias é final.
- **Base da decisão (D19, V109 aditiva, rc.68):** cada decisão grava a **base**: `INDEPENDENT` (revisor da lista de revisores, **diferente de quem importou**; é o padrão quando o pedido não informa base, como antes) ou `OWNER_INTERNAL` (decisão técnica interna do responsável, só para quem está na lista `...fleet-alias-candidates.owners`, **inclusive sobre um candidato que ele mesmo importou**). Quem não está na lista da base escolhida recebe 403 `BASE_NAO_AUTORIZADA`; quem importou e decide como `INDEPENDENT` recebe 403 `DUPLA_CUSTODIA`. Os responsáveis também podem importar e listar. A base aparece na listagem e na tela, com o texto de que a decisão interna **não é revisão independente e não admite nada**; nenhuma base se converte em outra e `OWNER_INTERNAL` não satisfaz exigência de revisão independente (o plano de cadastro repassa `decisionBasis` sem alterá-la). As decisões anteriores à V109 ficam `INDEPENDENT`, porque exigiam importador diferente.
- **Catálogo de modelos canônicos: classificação e gestão (D19 fatia 2, V110 aditiva, rc.69).** A organização administrativa (gestores da plataforma) mantém o catálogo. O recorte inicial segue Brasil, LDV, BEV, rede aberta, DCFC, SIN GRID_ONLY.
  - **Classificação com histórico:** `POST /v1/platform/fleet/models/{id}/classification` (Idempotency-Key) acrescenta uma versão imutável (`app_fleet_model_classification`) com classe, propulsão, motivo (10 a 1000) e evidência. **Classe e propulsão conhecidas exigem evidência**; `UNKNOWN`+`UNKNOWN` (juntas) deixam o modelo **explicitamente pendente** e dispensam evidência; informar uma só delas como desconhecida é 422; repetir a classificação vigente é 409. A visão `app_fleet_model_effective` expõe a classificação vigente (a última versão, ou a declarada no cadastro) e passa a alimentar a prévia/snapshot e os ratings; **snapshots já criados não mudam**, e o registro original permanece.
  - **Outros tipos de veículo:** a classe `OTHER` passou a ser classificável (além de LDV, HDV e UNKNOWN). Ela **não** é admitida no recorte: `FleetCompositionRules` continua exigindo LDV e BEV para pesos.
  - **Rótulo de recorte (servidor):** `CANDIDATE_INITIAL_SCOPE` (LDV+BEV, **candidato, não admitido**), `OUTSIDE_INITIAL_SCOPE`, `PENDING_CLASSIFICATION`. É só rótulo; nada é admitido por ele.
  - **Tela:** catálogo com filtro por recorte e formulário de classificação (na seção Frota EVR da página de fontes), com os avisos acima.
  - **Não feito:** o pipeline de classificação e conciliação automática (fatia 3); a classe `OTHER` não detalha o tipo (moto, ônibus etc.), por não haver taxonomia decidida.
- **Não feito:** reabrir uma decisão; importar em ambiente real (não há ambiente neste fluxo; o lote foi só gerado e validado localmente contra o contrato).
- Testes: `FleetAliasCandidatesIntegrationTest` (401/403/404 por perfil, validações, idempotência, dupla custódia, decisão final, catálogo intocado, append-only, auditoria) e `test_match_senatran_pbev.py`.

## Checklist futuro da #94

- [ ] Adquirir amostra real da Senatran e demonstrar granularidade por modelo/ano de observação/propulsão.
- [ ] Identificar todos os modelos LDV BEV comparáveis e documentar aliases/categorias/versões com prova.
- [x] Registrar a decisão de cobertura do estoque BEV nacional sem admitir a série. A ausência continua sem linha, o total citado maior que o retrato guarda só a diferença inteira, e `usableForE8` permanece falso. Ver [decisão de cobertura](CF-OPEN-005-NATIONAL-COVERAGE-DECISION.md).
- [ ] Reconciliar a série nacional real, o histórico e o total oficial admissível. A decisão desta fatia não substitui essa prova.
- [ ] Registrar resolução de lacunas, modelos com ratings ausentes e incertezas por linha.
- [ ] Reavaliar referências afetadas e propagação STALE no `RequirementGraph` e snapshots de projeto.
- [ ] Integrar AFEC/MPG da #95 e `ProjectSelection` da #92 / E8 real da #97.
- [ ] QA com fonte não licenciada, modelo desconhecido, vendas como estoque, duplicatas, troca de edição, denominador zero, quantidade fracionária, revogação e cross-org.

**Validade regulatória:** snapshot PREPARATORY nunca é certificação, validação VVB, aprovação Verra, registro ou direito de emissão de VCU.
