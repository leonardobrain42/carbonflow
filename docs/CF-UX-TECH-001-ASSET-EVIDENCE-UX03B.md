# CF-UX-TECH-001 — UX-03B: PDF contextual em estações e DCFC

**Base:** UX-03A #139, UX-02B #140. **Issue:** #124. Owners documental #33 e armazenamento de projeto #98 (reutilizado); metadados de fontes públicas #92 não equivalem a PDF privado de estação.

## Implementação
- Flyway `V69`: liga arquivos do **mesmo** `app_project_evidence_blob` criptografado e deduplicado (#98) à versão imutável de uma estação `app_open_site_revision` ou carregador `app_open_charger_revision`. Sem armazenagem paralela de bytes. FK por organização/projeto/ambiente + alvo canônico; hash SHA256 e MIME existentes. Relação projetada `app_open_asset_evidence`, revisão segregada e histórico imutável.
- API: `GET /v1/projects/{project}/technical/open-sites/evidence/{SITE|CHARGER}/{code}`; `POST .../{type}/{code}/revisions/{version}` com corpo `application/pdf` e headers `Idempotency-Key`, `X-Evidence-Purpose`, `X-Evidence-Reference`, `X-Evidence-Locator`, `X-File-Name`; `POST .../evidence/documents/{id}/review` com `If-Match` e decisão `DOCUMENT_CHECKED_INTERNAL` ou `REJECTED`. Provas são PDFs privados ≤ 2 MB (envelope PDF, nome seguro); nenhum endpoint baixa o original neste recorte.
- Permissões **em toda operação**: leitura sob `VIEW_OPEN_MONITORING`, upload sob `SAVE_OPEN_MONITORING`; revisão somente papel **REV** independente, nunca dono/preparador/OP. Mesmas barreiras de escopo aplicam-se a API, upload e parecer, inclusive após revogação do grant.
- Estado ortogonal: `PENDING_REVIEW`, `CHECKED_INTERNAL`, `REJECTED`, `STALE`; mudança de versão do ativo sinaliza prova antiga para rechecagem, preservando histórico. `usableForE8=false` em qualquer estado, inclusive após parecer interno. `sourceReference` textual em estação/equipamento continua apenas declaração e nunca vira arquivo nem comprovação automática.
- Angular: botão **Anexar ou consultar provas** no cartão de cada estação e carregador, seletor de finalidade, localizador, escolha de PDF, timeline com hash e situação, parecer independente visível só ao revisor. Mobile e teclado; confirmação antes de abandonar formulário de prova.
- Teste Postgres: duas estações e um carregador, hash/deduplicação, criptografia, permissões, grants revogados, recusas de tipo e versão, revisão segregada e impacto STALE. E2E mobile: anexos distintos, atualização da lista, status separado, parecer pelo REV.
- **Sem efeito normativo:** prova recebida não significa `PROOF_ADMITTED`, não avalia direitos PP ou cronologia VCS, não libera E8 ou VVB e não altera nenhum PDF/PD congelado.

## Acompanhamento da issue #124
Esta é a fatia UX-03B, não o encerramento de UX-05. Em próxima etapa, integrar localizadores, requisito normativo e revisão/claim do #33/#43; controle de licença/privacidade do documento, upload para atos e contratos de #24/#38/#99; ligação do PD sem aprovação fictícia. Ainda não há antimalware externo nem preview de PDF original; reforçar scanner e política de retenção antes de produção.
