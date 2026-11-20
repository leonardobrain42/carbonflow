# UX-07 · Bússola da jornada e retomada do trabalho

Issue principal: [#124](https://github.com/leonardobrain42/carbonflow/issues/124).
Objetivo: **a primeira tela do projeto deve responder “onde estou, o que posso fazer, o que está pendente, quem deve agir e para onde retorno”**, sem exigir vocabulário VCS e sem confundir preenchimento com aprovação normativa.

## Entrega

1. **Visão geral redesenhada**, com prioridade visual para a próxima ação (deduzida de tarefas *efetivamente acionáveis* da API), retomada pessoal, pendências, filtros, busca, grupos com responsáveis e status.
2. **Progresso honesto e separado**: número de tarefas visíveis cujo registro foi concluído, pendências de dados/rechecagem, revisões em andamento e ações disponíveis. O percentual é de registros de tarefas do usuário, **nunca de conformidade VCS, evidência admitida, crédito ou PD pronto**. As métricas usam o mesmo estado derivado de revisões reais do `ProjectWork`, sem cópia de ledger ou novos critérios normativos no Angular.
3. **Priorização do servidor**: devoluções antes de rechecagens, antes de pendências de dados, antes de novas tarefas. Somente atividades autorizadas e acionáveis entram na fila da conta. Uma revisão documental não é considerada tarefa editável por quem não pode executá-la.
4. **Links profundos** para os novos workbenches: baseline leva à etapa `premissas`, adicionalidade à `cronologia`; as demais tarefas conservam suas rotas de origem. O menu principal continua estável.
5. **Retomada multi-sessão**: Flyway `V70` cria `app_project_journey_checkpoint` por `(project_id,actor_id)`, com `PUT /v1/projects/{project}/journey/resume` e leitura no `GET /journey`. O Quarkus valida área, seção, guia, etapa, metodologia e grants; checkpoints anteriores à revogação ficam ocultos, sem alterar fatos, direitos, E8, PD, status ou concessões. A UI registra ao navegar na preparação e ao abrir a triagem. Sem localStorage e sem compartilhar a posição entre usuários.
6. **Retomada responsável**: resume aponta para a etapa visitada, mas não armazena formulários. Formulários atuais continuam sujeitos aos próprios salvamentos versionados/If-Match; ao sair com mudanças no intake/triagem, há confirmação de descarte. Ninguém promete autosave inexistente.
7. **Onboarding**: a criação de projeto oferece entrada direta no plano guiado, com alternativa para ir imediatamente à triagem. O painel inicial da preparação mostra a próxima ação da mesma fonte, substituindo estatísticas desconectadas.
8. **Acessibilidade**: hierarquia de headings, skip links, barra de progresso nomeada com ARIA, botões de filtro com `aria-pressed`, leitor de tela, busca com label, alvo de toque >=40px e responsividade 390×844/desktop. Redução de movimento respeitada.

## Segurança e não regressão

- Para cada `PUT` o contexto `DynamicProjectScope` verifica sessão, projeto, papel e grant; navegação arbitrária, passos não previstos, outra metodologia, guia não visível e triagem por não titular são rejeitados.
- Requisições de GET nunca retornam posição pertencente a outra conta. Revogação oculta o recurso e impede novos checkpoints. O campo `resume` é recalculado sob grant vigente; registro histórico privado não é autorização.
- Nenhuma rota acessa `PIL-SYN-BR-001` ou APIs de `/platform` para construir progresso de projeto real. Sem alteração de hashes ou resultados E8.
- `Task.state=COMPLETED` significa que **o registro daquela tarefa** concluiu; não afirma que o PD ou a metodologia foram aprovados.

## Testes/aceite

- JUnit PostgreSQL: estados reais de tarefa, retomada por ator, isolamento entre projetos, tentativa cross-tenant, rotas fora do ramo, role PREP, revogação de grant, retorno após recarga e nenhuma alteração canônica.
- Playwright: 390×844 e 1440×900, foco de próxima ação, contador sem falsa aprovação, busca/filtros, links profundos, retomada, navegação técnica, erro/revogação de sessão, ausência de vazamento ao piloto.
- A homologação humana de UX-T17 é posterior: observar preparador e PP em um projeto de teste, registrar tempo para encontrar a próxima tarefa e explicar por que dados/PD ainda estão pendentes.

## Limites

Esta é uma fatia **UX-07** da #124; não substitui as operações jurídicas #38/#99, avaliação de baseline #40, admissão de fontes #94-#96, cálculo real #97 ou composição final de PD #43/#122. Não duplica tarefas, contratos ou provas. Continuam pendentes UX-08 e UX-06.
