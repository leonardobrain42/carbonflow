# CF-OPEN-009 / #98 — Conferência metodológica interna VM0038 v1.1 (fatia 7)

> **Limite jurídico e técnico:** trata-se de avaliação documental interna de um MR preparatório com base nos critérios selecionados para a VM0038 v1.1. Não constitui admissão normativa definitiva, verificação ou validação da VVB, ato da Verra ou autorização para executar E8/emitir VCUs. O conteúdo e a autenticidade dos documentos continuam sujeitos a verificação substantiva.

## Contexto

V52–V57 já permitem DCFC/SIN, medidores ED/EC, ingestão imutável, pareceres QA, snapshots, PDFs cifrados e vínculos versionados MR→PD→pré-cálculo. A V58 acrescenta um **parecer separado do QA**, que não transforma automaticamente documentos conferidos em parâmetros admitidos.

## V58: registro imutável e independente

`app_open_method_review` guarda, por projeto, período e **versão específica do MR**, versão da revisão, snapshot, edição metodológica `VM0038/1.1`, decisão `CHECKED_INTERNAL` ou `RETURNED`, seis critérios com fundamentações e referências, hash da cadeia de provas, identidade do REV e data. Cada decisão é append-only e tem imutabilidade no PostgreSQL.

O banco exige que o REV não seja:
- autor da versão MR;
- criador do snapshot de QA;
- remetente das linhas de medição do snapshot;
- remetente dos documentos de suporte desse snapshot.

Além da segregação do banco, o backend valida `role=REV` com grant ativo `MONITORING`; DRAFT_OWNER/OP/PREP não podem aprovar a revisão. DRAFT_OWNER/PREP/REV podem consultar com grant adequado; OP não recebe acesso ao texto das avaliações. Acesso opaco por organização/projeto, sessões, `Cache-Control: private, no-store`, `If-Match`, `Idempotency-Key`, auditoria e comando imutável.

## Seis critérios versionados

1. `BOUNDARY_DCFC_SIN`: fronteira, tecnologia DCFC, SIN e ramo sem infraestrutura associada.
2. `METER_CALIBRATION`: instrumentos, calibração e período.
3. `SOURCE_RIGHTS`: uso das fontes, licença e titularidade documental.
4. `HOURLY_ED_EC`: ED/EC independentes e cobertura temporal exigida.
5. `CLOCK_GAPS_MAINTENANCE`: fuso/relógios, anomalias e manutenção.
6. `DOUBLE_CLAIMING`: riscos de dupla contagem e exclusividade das alegações.

Todo critério exige `finding` (`SUPPORTED_INTERNAL` ou `OPEN`), referência documental de 8–300 caracteres e fundamentação de 15–2000 caracteres. O backend não confunde uma referência de texto com a autenticidade comprovada. Para registrar `CHECKED_INTERNAL`, todos precisam estar internamente sustentados, o QA do snapshot deve continuar coerente e todos os PDFs obrigatórios devem estar conferidos independentemente. A decisão `RETURNED` preserva as pendências.

## Cadeia de evidências e STALE

A decisão fixa o hash SHA-256 da versão do MR + snapshot QA + edição VM0038 + documentos cifrados identificados por hash e referência + versão/resultado de cada parecer documental.

Toda leitura/ocorrência que invalide o snapshot ou alteração da evidência conferida faz `GET` passar a `BLOCKED_STALE`. O resultado histórico é preservado. Nova versão MR nunca herda o parecer da anterior, mesmo se referenciar o mesmo snapshot. A API permite ler o histórico de uma versão anterior.

Estados: `PENDING_INTERNAL_REVIEW`, `BLOCKED_DOCUMENTARY`, `RETURNED_FOR_CORRECTION`, `CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION`, `BLOCKED_STALE`; todas as respostas têm **`canUseForE8=false`**.

## Interfaces e dependências

- `GET /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/method-review`
- `GET .../method-review/versions/{dossierVersion}`
- `POST .../method-review` com `If-Match: "N"`, `Idempotency-Key` e corpo `{outcome,rationale,checks}`.

Angular: seção existente Monitorar ED/EC → dossiês MR → avaliar VM0038 v1.1, sem submenus mutantes. PREP consulta; REV fundamenta; OP não visualiza.

PD **quantification** com MR vinculado não pode avançar na aprovação interna enquanto não houver checklist vigente. O **PD inicial e o plano de monitoramento** continuam preparáveis antes de medições ou parecer. Pré-cálculo E8 exibe estado do parecer, **sem dados financeiros, kWh, BE, PE, LE ou ER**, com `canRunE8=false` e `canIssueVcu=false` invariáveis.

## Provas por teste

Integração Quarkus/PostgreSQL com medição ED=2700 kWh/EC=3000 kWh: documentos pendentes, seis requisitos e reviewer independente, veto DRAFT_OWNER/autor de QA/OP, conflito de chave, repetição idempotente, controle de versão, histórico, substituição do MR e estado pendente para nova versão. Testes existentes de incidentes e snapshots STALE, piloto sintético e Angular continuam na CI.

## Próximas dependências

- Verificação substantiva e com fontes oficiais de cada critério; licença, contratos e certificados autenticados; opinião jurídica quando apropriada.
- Vincular pacote normativo e edição vigentes com referência documental verificável, regras condicionais VM0038 e evidência de EFel horário.
- Admissão real de parâmetros por projeto e período, `PeriodEnergySnapshot` formal, validação independente exigida e integração ao cálculo #97.
- MR oficial, avaliação da VVB, Verra/VCU, controle de dupla contagem e rastreabilidade de emissões na metodologia aplicável.

A issue #98 permanece aberta; esta fatia constrói o **registro de avaliação humana**, não uma autorização normativa automática.
