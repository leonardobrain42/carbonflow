# CF-OPEN-005 / #94 — Frota BEV nacional em circulação por modelo e ano (EVR)

**Escopo da primeira fatia (PR #110):** contrato, identidade de modelos, aliases observacionais, validação, proveniência licenciada, snapshots imutáveis e UX de diagnóstico. O pacote **não contém uma série brasileira efetivamente admitida** e não altera o piloto sintético. [Issue #94](https://github.com/leonardobrain42/carbonflow/issues/94) permanece aberta até a qualidade/cobertura das séries, ratings, direitos e aplicação em projeto serem comprovados.

## Fontes públicas verificadas e lacuna real

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
