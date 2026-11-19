# CF-OPEN-003 — segunda fatia: arquivo licenciado, quarentena e candidatos dimensionais

> PR dependente de #106. Mesclar #106 antes desta segunda fatia. Nenhuma aprovação de escopo desta entrega habilita diretamente BE/PE/ER ou IA.

## Contrato de segurança e armazenagem

- **Antes do upload**, a fonte deve ter metadados independentemente aceitos no fluxo #106 (V45), categoria DATASET e uma licença de conteúdo `app_content_license` vigente para **a organização do staff autenticado**, `SANDBOX`, propósito `AUTHORIZED_PROCESSING`, com prova SHA-256, autor e aprovador distintos, última decisão `APPROVED` e capacidade `STORE_ORIGINAL`. A lista de organizações vem do cadastro de staff MFA, nunca de um ID fornecido pelo cliente.
- Sem licença STORE ativa, **nenhum byte é persistido**. Sem chave AES-256-GCM configurada, o servidor retorna 503; não grava texto bruto como fallback.
- `POST /v1/platform/source-files/submissions/{submissionId}` consome `application/octet-stream` com headers `X-CarbonFlow-File-Request: 1`, `X-Source-Filename` e `X-Source-Format: CSV|XLSX`. O arquivo é limitado a 2 MiB e cinco originais ativos por organização/submissão.
- O serviço calcula SHA-256 **sobre os bytes reais** (diferente do fingerprint dos metadados). Se houver hash original declarado na submissão, ele deve coincidir exatamente. Formato, extensão e magic header XLSX são conferidos.
- Cada original é lacrado com nonce aleatório e AES-256-GCM, AAD vinculando organização, submissão, ID e SHA. O PostgreSQL recebe apenas criptograma, nonce, digest e metadados. Não há endpoint de download dos bytes.
- Deduplicação por `(submission, organization, digest)` entre originais retidos: reupload idêntico retorna o mesmo objeto, sem gerar segundo parâmetro. Reimportação após revogação/purge exige **nova licença** e pode gerar um novo objeto.

## Parser em duas fases

1. `POST /v1/platform/source-files/objects/{objectId}/parse` exige que o mesmo registro de licença autorize **EXTRACT_TEXT** de forma vigente. Até então o arquivo permanece em `QUARANTINED`.
2. Parser CSV RFC4180 UTF-8 estrito (sem NUL) ou XLSX OOXML de **uma única aba**. Fórmulas, mais de 2.000 linhas, arquivos malformados e colunas faltantes são rejeitados. XLSX usa Apache POI e as defesas ZIP disponíveis na biblioteca; nenhuma macro é executada.
3. O esquema genérico obrigatório nesta fatia é:

```csv
record_key,period_year,metric,value,unit,geography
BEV_MODEL_A,2024,stock,1000,vehicles,BR
BEV_MODEL_B,2024,stock,850,vehicles,BR
```

Os valores ilustram apenas o **schema** e não são dados oficiais. As colunas têm semântica explícita. `record_key` distingue linhas, `period_year` não é inferido do upload, `metric` identifica grandeza declarada, `value` é decimal exato com ponto, `unit` é whitelisted e `geography` identifica o recorte. Se a fonte declara `referenceYear`, cada linha deve corresponder a esse ano. Mistura dimensional, duplicatas e invalidade em qualquer linha impedem a criação de qualquer candidato (sem importação parcial silenciosa).
4. Candidatos válidos são `numeric(30,12)` no PostgreSQL com localizador por linha, parser versionado `TABULAR-1`; **nenhuma conversão** `MJ/km → kWh/100mi`, `km/L → mi/USgal` ou `tCO2/MWh → tCO2/kWh` ocorre sem avaliação futura do método.

Estados: `QUARANTINED`, `SCHEMA_REJECTED`, `NORMALIZED_CANDIDATE`, `TECHNICAL_REVIEW_REQUIRED`, `APPROVED_FOR_SCOPE`, `REJECTED_FOR_SCOPE`, `SUSPENDED`. O estado de conteúdo é ortogonal a `app_content_license` e à `METADATA_ACCEPTED`.

## Avaliação técnica independente por finalidade

- O cadastro `carbonflow.platform.source-technical.reviewers` é **diferente** dos grants de gerente, leitor e revisor de metadados. O revisor precisa estar em staff+MFA+VIEW, na organização da licença, e não pode ser o autor dos metadados nem do upload.
- `GET /v1/platform/source-files/objects/{id}/candidates` exige `EXTRACT_TEXT` **e** `SHOW_EXCERPTS` do mesmo registro licenciado. Retorna no máximo 50 linhas de prévia, sem original. Sem autorização nem a prévia é disponibilizada.
- `POST /v1/platform/source-files/objects/{id}/assess` registra parecer para metodologia, edição e cenário **derivados do catálogo**, capacidade bloqueada, métrica, ano, geografia e unidade que precisam coincidir com ao menos uma linha candidata. Exige localizador e justificativa. Aprovação exige ainda `SHOW_EXCERPTS`. `NEEDS_INFORMATION` e `REJECTED_FOR_SCOPE` não abrem as portas do cálculo.
- A avaliação não grava `ProjectSelection`, não altera `app_norm_pack` e não alimenta `E8`; toda resposta de arquivo contém `usableForE8=false`. O trabalho posterior exigirá comparar tecnologia, vintage, regra, ciclo de ensaio, licenciamento para uso pelo PP e instantâneo imutável.

## Revogação, retenção e exceções

A migration `V46__source_file_quarantine_and_candidates.sql` cria trigger em **nova decisão de licença REVOKED ou DENIED**. Ele apaga imediatamente os candidatos normalizados vinculados, zera criptograma e nonce, marca `SUSPENDED` e registra `purged_at`. Mantém digest, proveniência e auditoria da decisão sem os dados brutos. Expiração por data, sem decisão revogatória, **bloqueia dinamicamente** leitura, parsing e parecer; a limpeza física na data de vencimento ainda requer rotina de retenção a implementar. Não declarar purge automático por expiração nesta fatia.

Os direitos podem ser diferentes por organização. Cadastro de um PP/OP, grants de projeto ou poder de representação não concedem acesso ao console de fontes. Regras de direitos não são inferidas da URL, do domínio do órgão público nem do simples caráter público de uma base.

## Configuração para o administrador

- `CARBONFLOW_COMPLIANCE_STAFF`: roster MFA.
- `CARBONFLOW_PLATFORM_SOURCE_READINESS_VIEWERS`: leitura.
- `CARBONFLOW_PLATFORM_SOURCE_WORK_MANAGERS`: upload e parsing.
- `CARBONFLOW_PLATFORM_SOURCE_INGESTION_REVIEWERS`: revisão dos metadados #106.
- `CARBONFLOW_PLATFORM_SOURCE_TECHNICAL_REVIEWERS`: avaliação técnica independente.
- `CARBONFLOW_PLATFORM_SOURCE_FILES_ENCRYPTION_KEY`: 32 bytes em Base64URL sem padding, mantidos em secret store e com rotação controlada. Nunca compartilhar chaves em issues, logs ou commits.

Todas as configurações de chaves/permissões têm **default fechado**; o Compose repassa as variáveis sem fornecer segredos. Criar/revisar licenças de processamento envolve processo jurídico específico, não implementado por esse PR.

## QA

- Parser unitário: CSV válido, duplicatas, campos obrigatórios, vintages divergentes, unidades desconhecidas, UTF-8 inválido, XLSX válido e fórmula maliciosa.
- Quarkus+PostgreSQL: MFA e roles, upload não autorizado, SHA real, ciphertext sem plaintext, dedupe, parse explícito, métricas por linha, preview limitada, revisor independente, `APPROVED_FOR_SCOPE` sem E8, revogação com purge.
- Browser 390px: conta gerenciadora envia CSV e valida, conta revisora consulta amostras e registra parecer, navegação adaptável.
- CI sandbox verifica imagem Maven versionada e aplicação Quarkus.

## Pendências (issue #92 permanece aberta)

Conectores oficiais PBEV/Senatran/MCTI/ONS, tabelas e unidades metodológicas específicas, importação PDF autorizada, extratores especializados de ciclo EVR/AFEC/MPG/IR/Dy, seleção por projeto, admissibilidade em produção, purge periódico em expiração sem evento e grafo de impactos/STALE em BE/PE/ER/PD. Não considerar o piloto real pronto enquanto faltarem essas etapas.
