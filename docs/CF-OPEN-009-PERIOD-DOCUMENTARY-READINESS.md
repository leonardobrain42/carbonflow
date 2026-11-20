# CF-OPEN-009 / #98 — Prova documental e invalidação de snapshots QA (fatia 4)

**Escopo:** auditoria dinâmica de vigência e preparação para revisão normativa. **NÃO** é admissão metodológica, nem validação de VVB/Verra, nem E8, nem crédito.

## Dependências e reutilização

A `main` já contém V52–V54 (inventário de DCFC, medidores ED/EC, CSV original cifrado, leitura candidata, parecer REV e snapshot interno). Esta fatia não cria novos brutos ED/EC nem altera tabelas ou cálculos PIL-SYN. O armazenamento de bytes PDF reutiliza `app_project_evidence_blob`, o cofre AES-256-GCM por projeto introduzido na V53; amplia apenas os tipos MIME aceitos (CSV e PDF). A chave continua `CARBONFLOW_PROJECT_EVIDENCE_KEY`, 32 bytes Base64URL, sem fallback de produção. Sem chave, falha fechada (503).

## V55

- `app_open_period_support_document`: PDF cifrado referenciado a `app_open_period_qa_snapshot`; `kind=METER_CALIBRATION` para cada medidor ED/EC de origem ou `kind=SOURCE_RIGHTS` para cada sistema provedor de dados. Nome, hash, autor, referência documental e vínculo são preservados imutavelmente.
- `app_open_period_support_review`: parecer documental `DOCUMENT_CHECKED_INTERNAL` ou `REJECTED`, por versão append-only, fundamentado. O banco impede que remetente da prova **ou criador do snapshot** avalie seu próprio conjunto de evidências. Análise documental independente requer outro colaborador com papel REV.
- Comando de upload com chave idempotente por conta, hash do arquivo e referência; reenvio idêntico não duplica o PDF. Documentos substitutos geram nova evidência, sem destruir o original, e requerem nova revisão.
- Triggers de imutabilidade evitam modificação de suporte, parecer ou comando. Auditoria e acesso por projeto/organização via `DynamicProjectScope`.

## APIs

- `GET /v1/projects/{project}/technical/open-monitoring/period-qa/snapshots/{snapshot}/admissibility`: devolve vigência, evidências necessárias, pareceres, causas de bloqueio e totais históricos separados de ED/EC.
- `POST .../snapshots/{snapshot}/support` (`application/pdf`): cabeçalhos `X-Evidence-Kind`, `X-Subject-Code`, `X-Evidence-Reference`, `X-File-Name`, `Idempotency-Key`; corpo PDF de até 2 MB. O cabeçalho `%PDF-` é apenas controle estrutural mínimo: **não comprova validade ou autenticidade do documento**.
- `POST .../period-qa/support/{document}/reviews`: corpo `{outcome,reason}` e `If-Match: "0"` para primeira revisão. Revisões seguintes criam nova versão; não editam anteriores.

Os endpoints não disponibilizam bytes originais publicamente e enviam `private, no-store`.

## Estados de prontidão

1. **BLOCKED_STALE:** reexecução do QA horário falha ou hash SHA-256 corrente, incluindo fontes/linhas/revisões, difere do hash congelado. Uma decisão posterior de reprovação, novo período ou reconfiguração de medidor/carregador invalida a prontidão; os snapshots históricos não são apagados.
2. **PENDING_DOCUMENTARY_REVIEW:** snapshot permanece coerente com o QA, mas há documento PDF ausente, parecer ausente ou documento rejeitado.
3. **READY_FOR_NORMATIVE_REVIEW:** snapshot de QA não obsoleto e documentos essenciais de medidores e fontes com parecer interno favorável. **Continua `usableForE8=false`**. Este estado não confirma veracidade jurídica de contratos, propriedade dos dados, adequação normativa ou emissão de créditos.

## Permissões e segregação

DRAFT_OWNER pode anexar evidências ao seu projeto, mas **não** revisar. OP/PREP com grant `MONITORING` podem anexar; REV com grant `MONITORING` pode avaliar, exceto evidência própria ou snapshot que produziu. As ações são auditadas. Usuários sem o grant ou de outro projeto recebem 404 opaco.

## Testes

Inclui cenário E2E API Quarkus/PostgreSQL de QA ED=2700 e EC=3000, upload de três PDFs protegidos, revisão por segundo REV, passagem a `READY_FOR_NORMATIVE_REVIEW`, verificação de `usableForE8=false`, rejeição posterior que produz `BLOCKED_STALE`, upload inválido e autorização. Angular mostra revisão documental e os bloqueios.

## Pendente (issue #98 continua aberta)

- Verificação de autenticidade e validade substantiva de certificados/metadados; correspondência de número de série da calibração ao equipamento, órgão, data, método e testes físicos de relógio, custódia e intervalos.
- Consentimento/contrato de licença e revisão jurídica vinculados à fonte, vigência e operações permitidas (não basta anexar PDF).
- Aprovação metodológica baseada nas seções correspondentes da VM0038, EFel horário, fronteiras múltiplas e emissões indiretas e de fuga.
- Regra de reabertura do período e propagação STALE a MR/PD, ferramentas e vínculos de parâmetros do E8 (#97).
- APIs, UX e testes E2E para vários períodos M1/M2, interrupção de fonte, eventos de manutenção, documentos expirados e vínculos reais a cálculo apenas após admissão.
