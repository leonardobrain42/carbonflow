# CF-IMP-029-E — Biblioteca administrativa de fontes e licenças

**Status:** especificação para implementação; sem upload nem indexação de conteúdo protegido até autorização verificável. **Prioridade:** P0 para o controle de ingestão e licenças; P1 para UX de administração. **Épica:** CF-ROADMAP-001 / CF-ECA-001. **Integra:** CF-IMP-029, CF-IMP-015, ECA-001, ECA-002, ECA-008, ECA-014, CF-IMP-026.

## Problema

O CarbonFlow precisa classificar, manter e utilizar fontes com versões e vigências diferentes para orientar a elaboração técnica e, se autorizado, alimentar um RAG. Upload manual por equipe administrativa **não é uma licença para indexar o conteúdo**. Não executar parsing, embeddings, prompts, exibição de trechos ou exportação baseados em documentos Verra sem permissão escrita para cada finalidade pertinente. Também verificar titulares de terceiros e licenças de fontes brasileiras.

## Experiência de produto

Rotas do backoffice, separadas da experiência PP/PREP/REV/OP:

- `/platform/sources`: filtros por programa, metodologia, módulo, ferramenta, template, fonte externa e documento do projeto; status de vigência, classificação e direito de uso; visão apenas de metadados para staffs sem permissão documental.
- `/platform/sources/new`: cadastro da fonte e titular; URI oficial, categoria e entidades relacionadas (ex. VM0038 v1.1 depende de VMD0049 e ferramenta específica).
- `/platform/sources/:id/versions`: versões imutáveis, checksum SHA-256, datas publicadas/vigentes, vigência para cada tipo de ato, supersede, incidentes, histórico de proveniência.
- `/platform/sources/:id/rights`: autorização/licença, escopo, ambiente, usuários autorizados, territórios, datas, proibições, termos, arquivo da licença e revisão humana.
- `/platform/sources/:id/ingestion`: status de processamento, índices derivados, modelos, chunks, reindexação e invalidação; botões bloqueados com causa explícita quando não existe licença.
- `/platform/sources/:id/audit`: trilha de acesso, alterações de metadados, concessões, revogações, downloads, indexação e exclusão.

Visual: design system CarbonFlow aprovado (sidebar escura, cartões, bordas discretas e CTA verde), tabelas acessíveis com busca, status textuais sem depender de cor; modal de confirmação antes de revogar.

## Classes de fonte

`PROGRAM_DOCUMENT` (VCS Guide/Standard), `METHODOLOGY` (VM0038), `MODULE` (VMD0049), `TOOL` (VT/TOOL), `TEMPLATE` (PD/MR), `EXTERNAL_SOURCE` (MCTI/ANEEL/IBGE etc.) e `PROJECT_DOCUMENT` (evidência do cliente). A fonte se vincula a um **titular dos direitos**, não apenas a uma URL. O catálogo admite relacionamento muitos-para-muitos com metodologias, versões, categorias, jurisdições e requisitos, sem tratar fontes externas como cláusulas Verra.

`PROJECT_DOCUMENT` pertence ao tenant e ao projeto, **não integra biblioteca normativa global nem RAG compartilhado**. Restrinja sua utilização à finalidade específica, contrato e consentimento aplicáveis.

## Metadados e entidades de referência

- `source_catalog`: ID, namespace, category, title, owner_id, canonical_uri, language, visibility, origin, created_by.
- `source_version`: ID, source_id, label, published_at, effective_from/to, checked_at, file_sha256, storage_key, status, immutable_created_at, supersedes_id.
- `source_relation`: from_version, to_version, relation_kind (`DEPENDS_ON`, `SUPERSEDES`, `IMPLEMENTS`, `SUPPORTS`), effective scope.
- `content_license`: grantor, written_authorization_reference, license_attachment_key, reviewed_by, reviewed_at, valid_from/to, environment, audience, deployment_mode, hosting constraints, origin, revocation_at, notes.
- `content_license_capability`: license_id + operation (`STORE_ORIGINAL`, `EXTRACT_TEXT`, `INDEX_SEARCH`, `CREATE_EMBEDDINGS`, `RAG_RETRIEVE`, `MODEL_INFERENCE`, `SHOW_EXCERPTS`, `DRAFT_CONTENT`, `EXPORT_DERIVATIVES`), enabled, conditions; default all false.
- `ingestion_job`: version_id, license_revision, model_version, state, checksum, started/finished, error_code and immutable operation audit; `derived_artifact` tracks index/embedding/cache identifiers for purge.
- `content_access_audit`: actor, purpose, tenant/project, scope, decision, record IDs, timestamp, request trace.

DB foreign keys, unique/version constraints, optimistic locking, append-only audit for authority decisions, indexes by category/version/license/vigência. Objects in restricted encrypted storage; no raw PDFs in application logs or Git. Data retention and revocation require explicit policy.

## Estados e política (fail closed)

Document status: `CATALOGED_METADATA_ONLY → UPLOADED_QUARANTINED → VERIFIED → PUBLISHED_METADATA`; rights independent: `UNREVIEWED → UNDER_REVIEW → ALLOWED_FOR_SCOPE | DENIED | EXPIRED | REVOKED`. Index state: `NOT_INDEXED → QUEUED → INDEXING → AVAILABLE | FAILED | PURGE_REQUIRED | PURGED`.

1. A criação de metadados é permitida a `SOURCE_CURATOR`. Submeter binário não autoriza parsing/preview/embeddings: o arquivo entra em quarentena criptografada com antimalware, limites de tipo/tamanho e sem índices.
2. Somente `CONTENT_LICENSE_REVIEWER`, diferente do upload/solicitante, registra permissão respaldada por evidência escrita específica. A plataforma pode consultar metadados públicos sem processar o arquivo.
3. `ContentLicensePolicy.canUse(sourceVersion, operation, actor, purpose, tenant, environment, time, provider)` deve avaliar uma operação **antes** de cada etapa e de cada requisição RAG; nenhuma permissão por herança implícita.
4. Uma licença pode permitir armazenamento e negar embeddings/RAG. Não mostrar botão ou mensagem de sucesso que implique direito não comprovado. `VIEW_PLATFORM_METRICS` ou `ORG_ACCESS_ADMIN` **não** concedem acesso documental.
5. Revogação, expiração, alteração de versão ou troca de provedor desabilitam consultas imediatamente, registram pendência de descarte e executam remoção dos vetores, chunks, caches e cópias derivadas sob a política aprovada; teste de não recuperação depois da purga.
6. `INDEXED` não implica norma vigente; `VALID` não implica autorização para IA; `COMPLIANT` não é decisão jurídica nem aprovação Verra.

## Contratos REST propostos (versionados)

| Operação | Endpoint | Capacidade / comportamento |
|---|---|---|
| GET | `/v1/platform/sources` | `VIEW_SOURCE_METADATA`, filtro/paginação, sem bytes |
| POST | `/v1/platform/sources` | `MANAGE_SOURCE_CATALOG`, registro sem ingestão |
| POST | `/v1/platform/sources/{id}/versions` | `MANAGE_SOURCE_VERSIONS`, cria metadados de versão |
| POST | `/v1/platform/sources/{id}/versions/{v}/upload` | Upload validado, storage privado, `QUARANTINED` |
| POST | `/v1/platform/source-licenses` | Registrar autorização em análise, anexo protegido |
| POST | `/v1/platform/source-licenses/{id}/decision` | `APPROVE_CONTENT_LICENSE`, dupla custódia, revisão por operação |
| POST | `/v1/platform/sources/{id}/versions/{v}/ingestion-jobs` | Exige permissão para TODAS as operações derivadas previstas e propósito |
| POST | `/v1/platform/source-licenses/{id}/revoke` | Suspensão imediata, evento e purge requisitada |
| GET | `/v1/platform/sources/{id}/audit` | Auditoria restrita e paginada |

Mutations com `Idempotency-Key`, `If-Match` quando houver versão e log de decisão; 403 para operação proibida, 409 para conflito de versão; evitar vazamento de existência cross-tenant. Atributos `permission`, `sourceVersion` e `licenseRevision` acompanham jobs, chunks e citações geradas.

## Testes de aceite

- CURATOR cria fonte VM0038 e dependência VMD0049 como metadados sem ingerir texto.
- Upload manual entra em quarentena, **zero embeddings/índices e zero chamada a LLM** enquanto licença está ausente.
- Licença permite somente `STORE_ORIGINAL`; todas as rotas de busca vetorial, IA e preview textual recusam.
- Aprovação pelo próprio solicitante ou sem documento de autorização é recusada; perfis de projeto (PP/PREP/REV/OP) não têm acesso ao backoffice.
- Revogação impede busca imediatamente e invalida derivados. Persistência de purge failures gera alerta e `PURGE_REQUIRED`, nunca deixa conteúdo recuperável.
- Fonte externa com licença distinta não herda licença do programa nem do pacote metodológico.
- Conteúdo de um cliente não aparece nas respostas de outro nem nos agregados públicos.
- Versionamento mantém auditabilidade de decisão/cálculo anterior sem reapresentar texto cujo uso foi revogado.
- RAG sobre fixture própria autorizada preserva citações corretas e abstém quando licença, versão ou conteúdo não existe.

## Decomposição e dependências

- **029-E1 (P0):** modelo `source_version/license/capability` e policy fail-closed + testes de negação. Depende de identidade de staff e base de autorização de CF-IMP-028 para liberar decisões reais; pode construir fixture sem ativação.
- **029-E2 (P1):** UI de catálogo por tipo, versão, dependência e documentos externos; somente metadados inicialmente.
- **029-E3 (P0 antes de IA):** upload protegido, quarentena e auditoria; sem indexação para fontes não licenciadas.
- **029-E4 (P0 antes de RAG):** integração à ECA-001/008 com controle de ingestão, cache e purge.
- **029-E5 (P1):** observabilidade por fonte/versão/status da licença, contagens agregadas por ambiente, sem expor documentos.

**Não bloqueia:** criação de usuários/projetos, trabalho técnico com dados do próprio cliente autorizados e motor determinístico sobre regras próprias/sintéticas. **Bloqueia:** indexação/IA sobre materiais Verra não autorizados.
