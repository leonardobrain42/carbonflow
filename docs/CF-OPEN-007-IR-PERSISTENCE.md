# CF-OPEN-007 / #96 — Persistência preparatória de IR

Continuação do PR #232. V89 adiciona revisão imutável, com escopo de projeto/organização/ambiente, ano de projeto, ano da fonte, período de referência, triagem, fingerprint temporal, versão de frota/ratings e vínculo EFfuel. Nenhum valor licenciado é copiado. A decisão continua preparatória, usableForE8=false, sem efeito sobre o E8 ou o piloto.

## Contrato API
- POST /v1/platform/open-ir-assessments/{project}: staff gestor autenticado por MFA e organização, Idempotency-Key e If-Match (versão atual no par ano de projeto/ano fonte, zero inicial). Corpo: period, frequency (ANNUAL/NON_ANNUAL/UNKNOWN), basis (PROJECT_SPECIFIC/DEFAULT_VALUES/UNKNOWN), rationale (15–2500 caracteres).
- GET /v1/platform/open-ir-assessments/{project}/{year}/{sourceYear}: histórico staff gestor.
- GET /v1/projects/{project}/technical/open-ir-assessments/{year}/{sourceYear}: histórico autorizado pelo grant de parâmetros existente, sem justificativa reservada.
- Cache-Control private, no-store; replays identificados pelo ator, chave e fingerprint da solicitação. Nova versão não sobrescreve anterior.

O backend resolve a configuração OPEN LDV BEV DCFC SIN BR, a seleção atual de frota e ratings para o período e o calendário existente de E8, e verifica o vínculo de EFfuel no ano da fonte. O conjunto deve existir antes de gravar (409 se ausente/incompatível); não se aceita enviar números, hashes ou ano diretamente como prova. A mesma referência canônica de seleção de frota contém EVR, AFEC e MPG. ANNUAL + PROJECT_SPECIFIC produz candidato 1 e PENDING_REVIEW; as outras rotas permanecem PENDING_METHOD sem candidato. O digest externo vincula também a justificativa, triagem, contexto e revisão. Histórico supersedido ou com referência indisponível/incompatível/obsoleta é sinalizado STALE_NOT_ADMITTED, sem candidato exibido; o registro original permanece intacto.

## Limites e próxima fatia
- A frequência anual é uma declaração; não foi comprovada a sequência de atualizações efetivas nem admitidos os dados metodologicamente. O parecer independente deverá vincular a revisão persistida exata, validar competência/roster e registrar provas de frequência e fundamentação.
- Nesta etapa o recorte de evidência é o período selecionado, com revisionamento por par ano de projeto/ano fonte. A avaliação ainda depende de um handoff por período e não serve como estimativa ex ante sem insumos de monitoramento; evolução de uma referência anual independente do período deverá ocorrer antes da homologação integrada.
- A etapa não cria tela Angular, não concede permissões novas a revisores, não substitui os pins antigos nem muda as execuções E8.
- Testes adicionados: API com ausência de conjunto canônico, controle de acesso, If-Match obrigatório e histórico vazio; aplicação da V89, FKs e gatilho, digest. Ainda é necessária prova positiva ponta a ponta de gravação, replay, atualização/revogação de fontes e revisão independente.
