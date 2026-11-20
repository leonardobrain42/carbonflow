# CF-OPEN-008 #97 / CF-OPEN-009 #98 — Pacote horário ED/EC candidato ao E8 (fatia 8)

**Escopo:** transformar a linha de medição real de projetos OPEN em um contrato tipado, versionado e auditável, **sem** admissão normativa nem execução das equações E8. O status persistido é invariavelmente **`NOT_ADMITTED`**, mesmo quando o checklist interno `CHECKED_INTERNAL` foi realizado.

## Motivo da separação

As medições ED e EC (VM0038 v1.1, cenário DCFC/SIN `GRID_ONLY`) são magnitudes distintas: ED é eletricidade entregue ao veículo e EC é o consumo medido para a fronteira elétrica. A conferência interna de qualidade e documentação não prova por si só a aptidão normativa para uma emissão ou a disponibilidade de EFel, AFEC, MPG, fatores fósseis, IR, Dy e controles de fuga. Expor BE/PE/ER sem esses parâmetros seria incorreto.

## V59 — proveniência, não duplicação de brutos

`app_open_e8_energy_candidate` guarda por organização e projeto `app_project`, código e versão de MR, versão de `app_open_method_review`, snapshot de QA imutável, digest SHA-256 do pacote, ED e EC em `numeric(30,12)`, `hour_count`, `slot_count`, autor e data. Restrições SQL `status='NOT_ADMITTED'` e `usable_for_e8=false` impedem qualquer promoção por simples atualização. O histórico é append-only e os IDs apontam à cadeia real, nunca `PIL-SYN`, `SIM-*` ou `EF-SYN-*`.

`app_open_e8_energy_candidate_hour` fixa por carregador e hora UTC cada ED/EC e os identificadores da **linha de origem mais sua revisão QA**. Os dois FKs exigem que as revisões existam em `app_open_measurement_review`; as referências pertencem ao mesmo projeto. Não há novo armazenamento de CSV/raw; o pacote reaproveita `app_open_period_qa_snapshot_line`, `app_open_measurement_candidate`, `app_open_measurement_batch` e o hash da prova criptografada de origem.

`app_open_e8_energy_candidate_command` protege `Idempotency-Key` por identidade, escopo e fingerprint do conteúdo; reexecução com mesma fonte não duplica a entidade. Triggers impedem UPDATE e DELETE das três tabelas.

## Condições de preparação

- O MR corrente tem que coincidir exatamente com `dossierVersion` informado.
- O parecer interno deve estar em `CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION` e sua versão deve coincidir com `methodReviewVersion`. O header `If-Match` trava essa versão, evitando usar formulário antigo.
- O snapshot do mesmo projeto precisa estar vigente e com as provas documentais conferidas.
- O QA corrente precisa reconstruir exatamente o SHA congelado da versão do snapshot, incluindo incidentes operacionais.
- As leituras originais com revisão QA aprovada devem permitir **um ED e um EC por carregador/hora** e intervalos UTC integrais, com EC maior ou igual a ED, sem lacunas nem duplicatas. Somatórios em `BigDecimal` precisam coincidir com ED/EC do snapshot persistido; nenhuma correção numérica automática.
- O SHA canônico incorpora projeto, período/MR, revisão interna/edição, hash documental, hash da prova CSV, hash da linha original, origem, medidor, duração, versão QA e energia por slot. Um novo pacote pode ser criado quando uma nova revisão independente o justificar; históricos anteriores permanecem auditáveis.

## Estados vivos, papéis e interfaces

`GET /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-energy-candidates` mostra últimos 20 pacotes, linhas horárias e estado recalculado. `POST` nesse endpoint aceita `{dossierVersion,methodReviewVersion}`, `If-Match: "N"` e `Idempotency-Key` e só cria se todos os gates forem satisfeitos.

- DRAFT_OWNER e PREP com `MONITORING` podem preparar; REV com `MONITORING` consulta; OP não consulta, prepara ou altera pacotes E8. Todos os acessos usam `DynamicProjectScope`, sessão e escopo organização/projeto, respostas `private, no-store`.
- O estado visível é `NOT_ADMITTED` ou `STALE_NOT_ADMITTED`. Reedição do MR, novo parecer interno, mudanças de fonte, documentos, triagem, aplicabilidade ou incidentes tornam o pacote anterior obsoleto. O campo `usableForE8` é sempre falso.
- No Angular, **Monitorar ED/EC → MR** permite preparar e inspecionar pacotes, com ED e EC separados por hora (primeiros 48 slots exibidos; todos preservados na API). Não há novo submenu mutante.
- `GET /technical/open-calculation-readiness` só lê o status agregado `E8_ENERGY_INPUT_{period}`; sem repassar kWh ou referências documentais a usuários que tenham somente CALCULATION. `canRunE8=false`, BE/PE/ER nulos, nenhuma VCU.

## Testes

E2E Quarkus/PostgreSQL: fixture real de ED=2700/EC=3000 para uma hora; exatamente um slot DCFC-1 e suas fontes de origem, cópia e hash imutáveis; bloqueio por usuário externo, OP e REV; controle de versão; replay idempotente; MR v2 sem herdar a candidatura de v1; estado STALE de candidato v1 após mudança MR; preflight E8 apresenta gate, permanece bloqueado. As suítes preexistentes do piloto sintético continuam sendo executadas.

## Próximas tarefas #97 e #98

Esta fatia **não é admissão formal**. Continuam necessários: verificação de documentos de terceiros, fatores e metodologia vigente, contrato de admissão normativa por revisão independente, EFel horário admissível vinculado ao mesmo período, fator combustível e frota, cálculo puro E8 BE/PE/LE/ER, persistência do cálculo real, análise VVB, gestão de VCU e provas de não contaminação com `PIL-SYN`. Sem estes requisitos, não há números de reduções válidos.
