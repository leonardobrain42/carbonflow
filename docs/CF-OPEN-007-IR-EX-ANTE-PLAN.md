# CF-OPEN-007 — Planejamento ex ante de IR

Continuação de #96 e #239. O plano pode ser registrado na jornada Linha de base > Premissas antes de existir qualquer período MR. Não é uma avaliação de parâmetros nem uma prova de atualização anual.

## Contrato
- Quatro conjuntos obrigatórios: EVR, AFEC, MPG e EFfuel, cada qual com fonte prevista, responsável previsto e procedimento de atualização anual, mais justificativa da abordagem.
- GET/POST /v1/projects/{project}/technical/open-ir-ex-ante-plan. POST usa If-Match e Idempotency-Key. Histórico imutável em V94, hash do conteúdo e contexto. Reutiliza autorização de guia da linha de base (titular de rascunho ou colaborador com atividade do guia; gravação também exige SAVE_GUIDE). Sem permissão nova nem atribuição de papel jurídico.
- Contexto canônico de triagem, intake e aplicabilidade. VM0038 candidata com recorte ainda desconhecido admite plano preparatório; incompatibilidade explícita bloqueia novos planos. Mudança de contexto ou versão torna o plano anterior obsoleto, sem apagá-lo. A configuração pode ser confirmada depois.
- Não exige calendário de crédito, dossiê, ED/EC, frota selecionada, ratings ou pinos de fatores. Não grava valores numéricos de IR ou resultado E8. Não determina se a fonte prevista existe ou se foi admitida.

## Limites e próximo passo
O plano de V94 é independente do parecer inicial V93 e não é copiado automaticamente para ele nem para o PD. A próxima fatia deve vincular o plano vigente por ID/versão/hash ao parecer inicial independente e exigir reconciliação entre o que foi planejado e as referências efetivamente selecionadas; preservar histórico, segregação, revogação e bloqueio de E8. Ainda faltam o controle temporal da atualização observada e a homologação positiva de dois anos. Nenhuma alteração nas migrações publicadas.

## Testes
Classificação de contexto, teste de integração sem monitoramento (permissão, entrada inválida, versões, idempotência e obsolescência), jornada Angular com mock e restauração de fixture V79. CI deve confirmar execução.
