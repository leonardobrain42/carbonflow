# CF-OPEN-007 #96 — Candidatos horários EFel para projetos OPEN (fatia 9)

**Estado: valores e fontes DECLARADOS, revisão somente INTERNA e nenhuma admissão normativa, execução E8 ou geração de VCU.**

## Fonte normativa e escopo

A Verra publicou VM0038 v1.1 em 19 agosto 2026. A edição adota a ferramenta VT0011 para obtenção do fator de emissão da eletricidade. Fontes oficiais:
- https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/
- https://verra.org/verra-publishes-revision-to-electrical-vehicle-charging-methodology-vm0038/

O CarbonFlow **não implementa VT0011** nesta fatia e não autentica conteúdo ou direitos de URLs externas. Informar publicação, vintage e hash declarado não equivale a provar origem, licitude, aplicação de opção técnica nem admissibilidade perante Verra/VVB. Não transformar média corporativa MCTI/SIRENE em OM automático.

## Flyway V60

As tabelas append-only app_open_efel_hourly_candidate, app_open_efel_hourly_candidate_value, app_open_efel_hourly_review e app_open_efel_hourly_command preservam a origem, a edição declarada, o período, a unidade, o gás CO2e, a série hora a hora, o hash canônico, o autor, a decisão independente e recibos de idempotência.

O candidato referencia pelo mesmo projeto o pacote de energia ED/EC imutável; não duplica originais CSV. A série cobre exatamente cada hora UTC distinta do EC medido (sem lacuna, duplicata ou hora fora do período), até 744 horas. Original e convertido usam BigDecimal e a transformação de unidades explícita já testada: tCO2e/MWh, tCO2e/kWh ou kgCO2e/kWh para tCO2e/kWh. Fator zero somente quando explicitamente informado; nunca usar zero como preenchimento.

O banco impede qualquer promoção acidental: state=CANDIDATE_NOT_ADMITTED e usable_for_e8=false. Todas as tabelas têm triggers de imutabilidade. O PostgreSQL proíbe a pessoa que declarou a série de revisar a mesma fonte.

## API e segurança

GET e POST /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/efel-candidates, e POST .../{candidate}/reviews. Submissão com If-Match e Idempotency-Key; revisão com ETag próprio e chave idempotente. DRAFT_OWNER/PREP com MONITORING submetem, REV com MONITORING lê e revisa, OP não lê. A API limita escopo de organização/projeto com DynamicProjectScope, e usa private,no-store.

O estado visível é PENDING_INTERNAL_REVIEW_NOT_ADMITTED, RETURNED_NOT_ADMITTED, CHECKED_INTERNAL_NOT_ADMITTED, SUPERSEDED_NOT_ADMITTED ou STALE_NOT_ADMITTED conforme revisão, versão e dependências. O pré-cálculo só expõe um gate agregado E8_EFEL_M1 etc. para usuários CALCULATION-only; nenhuma magnitude bruta ou contrato. Sempre canRunE8=false.

## Experiência

No monitoramento, dentro do pacote ED/EC, há um painel de fatores por hora, origem, edição, direitos declarados, checksum do documento, procedimento reivindicado, data e vintage. O formulário apresenta somente os horários encontrados; os valores permanecem vazios. O usuário cola as horas e os fatores em linhas horaISO,valor; o sistema compara todos os horários e valida precisão, sem preencher ou interpolar. O revisor vê as versões e pode registrar parecer histórico interno. A UI mostra as primeiras 48 linhas; API conserva todas.

## Testes

O fixture técnico contém uma hora de ED=2700/EC=3000 kWh e fator **inteiramente sintético** de 0,5 tCO2e/MWh, cujo oráculo numérico equivale a 0,0005 tCO2e/kWh. Não é fator do SIN nem dado MCTI/VT0011 real. Testar lacunas/extras, idempotência, versões, acesso não autorizado/OP, revisão independente, fonte obsoleta após novo MR, nenhuma execução E8 e regressão do piloto.

## Fora do escopo (pendências #96, #97 e #98)

Autenticação do arquivo de origem e direitos, demonstração normativa da opção VT0011/TOOL07 escolhida e suas fórmulas, vintage e ponderação SIN, EFel realmente admitido, combustível, AFEC/MPG, IR/Dy, leakage, redução BE/PE/ER, VVB, créditos VCU. Estas issues seguem abertas.
