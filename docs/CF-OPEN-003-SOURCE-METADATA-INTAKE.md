# CF-OPEN-003 — Cadastro e revisão independente de metadados (primeira fatia)

## Objetivo e alcance

O Admin CarbonFlow já inventaria necessidades por metodologia/edição/cenário (#90, PR #104) e possui fila real de aquisição (#91, PR #105). Esta fatia adiciona a **ponte segura entre uma fonte identificada e a publicação dos seus metadados**, com revisão humana independente.

**Não há upload, leitura, OCR, download automático, cópia de documentos, indexação, embeddings, acesso à IA normativa, extração de valores, transformação dimensional nem parâmetro aprovado.** As fases seguintes da própria #92 implementarão esses recursos com storage licenciado, permissões por operação, versões e admitibilidade técnica.

## Estado e banco

Migração `V45__platform_source_metadata_intake.sql`:
- `app_platform_source_submission`: UUID, código da necessidade, identificador **canônico do catálogo** (não fornecido pelo cliente), edição concreta, categoria, título, detentor dos direitos, órgão publicador, URL HTTPS, data da publicação opcional, ano de referência opcional, localizador de evidência, SHA-256 original *declarado e não verificado*, impressão SHA-256 **dos metadados** (diferente do original), ator e datas.
- `app_platform_source_submission_event`: revisões imutáveis por versão, status, ator, motivo e data.
- Estados: `PENDING_REVIEW`, `METADATA_ACCEPTED`, `NEEDS_CORRECTION`, `REJECTED`. Decisões terminais não são reabertas silenciosamente; uma correção é outra submissão identificável.
- Deduplicação transacional por `(need_code,metadata_fingerprint)`. Repetir a mesma submissão retorna o registro original, sem duplicar evento ou fonte.
- Ao aceitar metadados: tentativa de registrar `app_source_version` com `origin='EXTERNAL'` e dados exatos. Se a edição já existir com valores divergentes, resposta **409** sem sobrescrever histórico. Um advisory transaction lock serializa a aceitação simultânea por `source_id/edition`.
- Nenhum dado é inserido em `app_content_license`, `app_norm_rule`, `app_norm_pack` ou em tabelas E8.

## Endpoints

Todos exigem staff+MFA, `VIEW_SOURCE_READINESS`, são `private,no-store` e registram leitura/ação em `app_compliance_access_event`.

| Operação | Rota | Requisito |
|---|---|---|
| Listar e ver capacidades | `GET /v1/platform/source-ingestion?methodology=VM0038&edition=1.1&scenario=OPEN_LDV_DCFC_SIN_BR` | VIEW |
| Consultar origem e decisões | `GET /v1/platform/source-ingestion/{id}` | VIEW |
| Submeter metadados | `POST /v1/platform/source-ingestion` | VIEW + MANAGE_SOURCE_WORK |
| Revisar metadados | `POST /v1/platform/source-ingestion/{id}/reviews` | VIEW + REVIEW_SOURCE_METADATA e outro ator |

POST submissão recebe:
```json
{
  "needCode": "OPEN-EVR",
  "edition": "2024",
  "category": "EXTERNAL_SOURCE",
  "title": "Título verificado na publicação",
  "rightsHolder": "Detentor dos direitos",
  "publisher": "Órgão editor",
  "officialUri": "https://example.org/edicao/2024",
  "publicationDate": "2024-12-31",
  "referenceYear": 2024,
  "locator": "Tabela/aba e localizador da evidência",
  "declaredOriginalSha256": null,
  "note": "Explicação da origem e conferências pendentes"
}
```
O exemplo é apenas um **contrato**, não atesta fonte de dados brasileira real.

POST revisão usa header `If-Match: 1` e payload:
```json
{"outcome": "ACCEPT_METADATA", "reason": "Edição e URL conferidas; direitos de processamento e uso não avaliados"}
```
`REQUEST_CORRECTION` e `REJECT` não registram a fonte no catálogo. Erros: 401 não autenticado; 403 MFA/segregação; 404 falta de papel ou recurso; 409 revisão obsoleta/metadados conflitantes; 422 dados inválidos; 428 ausência de If-Match. O ator original nunca aprova a própria submissão.

## Permissões na implantação

- `carbonflow.compliance.staff`: contas de equipe com MFA TOTP;
- `carbonflow.platform.source-readiness.viewers`: leitores internos do painel (#90);
- `carbonflow.platform.source-work.managers`: responsáveis por abrir solicitação e submeter metadados (#91);
- `carbonflow.platform.source-ingestion.reviewers`: revisores independentes da origem/edição desta fatia.

Todas as listas são configuradas por UUID de identidade separado por vírgula (variáveis de ambiente Quarkus equivalentes em uppercase/underscores). Lista ausente = ninguém autorizado. A aprovação requer **outra identidade** que não a responsável pelo cadastro. Revisão de metadados não equivale a poder de assinar como PP/REV/VVB nem autoriza acesso a documentos de projetos.

## Integração

A aba **Cadastro de fontes** do `/workspace/platform/sources` recebe necessidades reais da API do inventário, não valores de demonstração; exige edição concreta para `YEAR`/`LATEST`, permite cadastro e exibe quem revisou. Após aprovação, recarrega o inventário: a edição aparece como metadado disponível, **não** como versão admitida para BE/PE/ER. Documentos e dados por projeto continuam exclusivos das jornadas PP/OP.

## Critérios para avançar dentro da issue #92

1. Storage criptografado/objeto com ACL, limites de tamanho/mime, isolamento por organização/projeto e retenção.
2. Verificação real SHA-256 dos bytes, autorização `STORE_ORIGINAL` e `EXTRACT_TEXT`, quarentena sem permissão. Revogação faz purge de derivados permitidos.
3. Parser CSV/XLSX/PDF-texto e conectores oficiais PBEV/Senatran/MCTI/ONS; qualidade, faltas e locators por célula/linha/página.
4. `DatasetVersion` e `ParameterValue` tipados, conversões explícitas BigDecimal e revisão dimensional, vintage/ciclo/gases/geografia.
5. `TechnicalAssessment` independente por finalidade + `ProjectSelection` imutável e propagação de stale ao E8 e documentos, sem jamais tratar metadados como parâmetro aprovado.
6. Portal de histórico/diff, licenças por capacidade/tenant/finalidade e bloqueio no momento do uso.

## Testes

`PlatformSourceIngestionIntegrationTest` (Quarkus/PostgreSQL): sessão MFA, viewer/manager/reviewer, impedir autoaprovação, dedupe, edição anual e URL HTTPS, rejeição de evidência por projeto, concorrência/versionamento, conflito entre metadados da mesma edição, verificação de que nenhuma licença é criada.

`frontend/test/platform-source-ingestion.test.mjs` (Playwright): jornada com autenticação, submissão, revisão por ator distinto e viewport móvel 390px; nenhum READY fictício.

**Não encerrar #92 com este PR:** é a primeira entrega backend+UI de proveniência e governança documental, ainda sem bytes nem avaliação técnica de parâmetros.
