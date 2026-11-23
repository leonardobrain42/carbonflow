# P0 — Roadmap de integração UX-08 sem antecipar regras normativas

**Corte:** 21/11/2026. **Escopo:** primeira fatia VM0038 / OPEN / Brasil / LDV BEV / DCFC / SIN.  
**Backlog:** [#124](https://github.com/leonardobrain42/carbonflow/issues/124) (UX-08), [#93](https://github.com/leonardobrain42/carbonflow/issues/93) (aplicabilidade), [#99](https://github.com/leonardobrain42/carbonflow/issues/99) (adicionalidade), [#98](https://github.com/leonardobrain42/carbonflow/issues/98) (ED/EC), [#97](https://github.com/leonardobrain42/carbonflow/issues/97) (E8). Apoio: [#38](https://github.com/leonardobrain42/carbonflow/issues/38), [#92](https://github.com/leonardobrain42/carbonflow/issues/92), [#128](https://github.com/leonardobrain42/carbonflow/issues/128), [#101](https://github.com/leonardobrain42/carbonflow/issues/101).

## Decisão

**Sim, implementar agora a integração estrutural e a UX dos estados já disponíveis. Não fechar o P0 normativo ainda.** O contrato entre módulos deve continuar válido quando novas regras, fontes, pareceres e cálculos forem publicados. Não duplicar o motor de regras, o ledger de provas, o cálculo ou o armazenamento de tarefas na camada UX.

### Estado observado no `main`

| Frente | Evidência no repositório | Situação |
|---|---|---|
| UX-08A — projeção de requisitos | `ProjectRequirementProjection`, `ProjectRequirements`, `GET /v1/projects/{project}/technical/requirements`, testes Quarkus/PostgreSQL | **Base implementada**, read-only, sem novo ledger; ainda não é projeção normativa definitiva |
| UX-08B — Analisar | `project-requirements.ts/html`, grupos de ação/fonte/revisão/fora do recorte, testes de navegador | **Base implementada**; falta amadurecer estados reais conforme os owners |
| UX-08C — jornada e navegação | `ProjectWork.View.diagnosis`, `/journey`, `/journey/resume`, checkpoint validado no Quarkus | **Base implementada**; completar sincronização de atualização e navegação contextual |
| #93 — aplicabilidade | `OpenApplicability` guarda declaração versionada e distingue `MARKET_ROUTE` (VM0038 §4) de adicionalidade | **Parcial**; dados oficiais, incidência normativa e parecer continuam pendentes |
| #99/#38 — adicionalidade | Workbench, guias e cronologia/referências; lista positiva candidata e excedente separados | **Parcial**; sem veredito normativo/jurídico automático |
| #98 — monitoramento | Estações, instrumentos, períodos, QA e evidências já têm owners próprios; `EnergyPosture` alimenta a projeção | **Parcial**; completar incidência por período, QA e revisão admissível |
| #97 — quantificação | `OpenCalculationReadiness`, preflight, vínculos candidatos e ensaio E8 interno; painel em `quantificar` | **Parcial**; ensaio/aritmética não equivale a resultado E8 normativamente admitido |

A pesquisa exploratória anterior não deve ser usada para declarar o E8 inexistente: há código de ensaio e testes. O que falta é **autorização normativa e integração de todos os inputs admissíveis para execução real**. A existência de tabelas ou testes sintéticos não prova essa autorização.

## Contrato estável: o que pode ser feito hoje sem refazer depois

1. **Uma única projeção, nenhuma segunda fonte de verdade.** Continuar usando `ProjectRequirements.assemble` e `ProjectWork.View.diagnosis`. Cada item expõe ID estável, método/edição/cenário, regra de origem, estado, motivo, próximo passo, papel previsto, destino autorizado e outputs afetados. Quando os owners tiverem versões/refs de dependências, adicioná-las **de modo compatível**; não presumir versões inexistentes.
2. **Separar estado de dado, prova, revisão e decisão.** `ABSENT`, `NEEDS_EVIDENCE`, `ATTACHED_NOT_ADMITTED`, `AWAITING_NORMATIVE_EVALUATION`, `NOT_ADMITTED`, `RECHECK_REQUIRED/STALE`, `NOT_INCIDENT` e `OUT_OF_SCOPE` não são sinônimos de aprovação. `ProjectWork.Task.COMPLETED` mede tarefa registrada, nunca conformidade VCS/VVB.
3. **Incidência vem do owner e da fase/ato.** `MARKET_ROUTE` pertence à **aplicabilidade VM0038 §4** (#93); `VMD0049_ROUTE` e `REGULATORY_SURPLUS` pertencem à **adicionalidade** (#99/#38); ED/EC pertence ao **monitoramento por período** (#98); preflight e execução E8 pertencem à **quantificação** (#97). A projeção apenas apresenta o resultado recebido; na ausência de avaliador admissível, mostrar pendência da equipe, sem simular conclusão.
4. **PD ex ante não exige ED/EC medidos do futuro.** O MR e o cálculo medido exigirão dados e QA do período incidente. Ausência não vira zero. Não transformar status candidato de fonte em `usableForE8=true` nem `canRunE8=true`.
5. **Navegação e segurança são regras estáveis de produto.** Todo destino de tarefa deve ser permitido no Quarkus pelo método, grants e `ProjectWork.allowed`; a UI não concede permissão. `ownerRole` não é `assigneeId`. Contagens, metadados, documentos privados e URLs devem ser filtrados antes de montar a resposta. Não atribuir PP nem admitir direitos por digitação.
6. **Atualização da UI após persistência.** Após salvar declaração ou dado canônico, a tela deve reler a projeção autorizada para não exibir uma pendência obsoleta até recarregar. Não reexecutar regras no Angular, não marcar tarefa concluída no cliente, não alterar versões históricas.
7. **Rechecagem dirigida e auditável.** Alterações de fronteira, contrato, disclosure, fontes, medição, QA, regra ou edição invalidam apenas derivados dependentes, com versões antigas preservadas. A implementação completa dessa propagação depende dos owners, mas o contrato e os testes de não regressão podem ser fixados agora.

## Roadmap incremental e gates de aceite

| Ordem | Fatia / responsável | Entrega segura agora | Gate para considerar pronta |
|---|---|---|---|
| **R0 — agora** | UX-08A/B/C (#124) | Consolidar contrato read-only existente; atualizar diagnóstico após salvar a declaração; testes de navegação/estado compartilhado, ACL, 412 e dados ausentes | A mesma pendência aparece com o mesmo ID/estado em Analisar e jornada após nova leitura; save não concede aprovação; CI verde |
| **R1 — agora** | UX-08C/E (#124/#101) | Testar 1 e muitas estações, ausência de prova, revogação/cross-tenant, links permitidos, checkpoint, teclado, 390×844/1440×900; garantir estados distintos ex ante/MR | E2E com backend real e PostgreSQL além de mocks; links negados pelo backend; nenhum dado reservado em resposta |
| **R2 — quando #93/#99 publicarem decisões** | Owners normativos + adaptador UX-08D | Consumir *snapshots* versionados de incidência §4, lista positiva §8, regulatory surplus, direitos e parecer; eliminar mensagens fixas **apenas** quando houver owner real | Cada requisito incidente possui regra/edição/ato/fonte/decisão humana quando cabível; não incidente sai da fila sem apagar histórico; não há autoaprovação |
| **R3 — quando #98 publicar QA por período** | Owner ED/EC + UX-08D | Vincular estação, medidor, série, calibração, período, QA, revisão e estado do MR; deep link ao item permitido | ED distinto de EC; alteração afeta apenas períodos/outputs dependentes; PD ex ante não bloqueado por medição futura |
| **R4 — quando #97 publicar execução admissível** | Owner E8 + UX-08D/E | Exibir execução real, memória BE/PE/LE/ER, versão do motor, entradas admitidas, replay/diff e vínculo ao PD; nunca calcular no Angular | Valores reprodutíveis e rastreáveis por snapshot; nenhuma fonte candidata/sintética vira resultado admitido; revisão interna ≠ emissão de VCU |
| **R5 — homologação P0** | #124/#101 com #93/#99/#98/#97 | E2E PP/PREP/REV/OP, acessibilidade e revisão humana; evidência em API, banco, navegador e documento | UX08-T01…T12 aprovados nos cenários incidentes, sem mock como única evidência; owners normativos e E8 validados separadamente |

### Testes de contrato que devem permanecer invariantes

- **Sem avaliador:** `MARKET_ROUTE`, `VMD0049_ROUTE` e `REGULATORY_SURPLUS` são pendências distintas, sem falso `APPROVED`.
- **Sem medição:** ED/EC não bloqueia universalmente PD ex ante, mas MR medido não é admitido sem dados/QA.
- **Sem fonte:** EVR, AFEC/MPG, EFfuel, EFel, IR e Dy não viram zero ou fonte `SIM-*` em projeto real.
- **Após salvar:** diagnóstico atualizado é relido do backend; a resposta não é inventada localmente.
- **Sem grant:** nenhum CTA, contador privado, URL ou documento reservado é exposto; acesso direto e checkpoint indevido são negados.
- **Mudança de contrato/fonte/QA:** `STALE/RECHECK_REQUIRED` apenas nas dependências pertinentes; histórico anterior imutável.
- **Sem decisão competente:** lista positiva candidata, prova anexada, QA interno e ensaio matemático não promovem aprovação externa nem VCU.

## Não implementar antecipadamente

- Percentuais/limiares ou vereditos VM0038 §4 e VMD0049 §8 **no Angular ou na projeção transversal**.
- Seleção automática de rota de adicionalidade, regulatory surplus ou prova jurídica por presença de formulário.
- Geração de BE/PE/LE/ER com defaults, números de `PIL-SYN`, fontes não admitidas ou cálculos de frontend.
- Nova tabela de tarefas, nova entidade de estação/contrato/prova ou migração de histórico sem owner e contrato.
- Regras temporais/versões definitivas sem os snapshots e testes das issues normativas.
- Encerrar #124, #93, #99, #98 ou #97 só porque a navegação ou a projeção funcionou.

**Critério de pronto P0:** não é “quatro abas com dados”. É **mesma origem canônica + incidência normativa versionada + ações autorizadas + prova/revisão + monitoramento por período + cálculo E8 admissível + teste E2E real**, com bloqueio explícito quando qualquer requisito ainda faltar.

## Verificação integrada de R0 — PR #202

`frontend/test/requirements-journey.integration.mjs` verifica a atualização após salvar com
requisições reais ao Quarkus e persistência PostgreSQL. Não intercepta APIs nem insere
identidades, permissões ou diagnósticos no banco. Usa as contas de teste provisionadas
por `scripts/demo_sandbox.py` e cria rascunhos identificados como `TEST_ONLY`.

Em 390×844 e 1440×900, o roteiro cobre:

- declaração ausente → salvar na interface → reler a projeção → mostrar as pendências atuais;
- igualdade integral entre `/technical/requirements` e `journey.diagnosis`;
- retorno à visão geral, retomada de Analisar e abertura de Estações pelo teclado;
- escrita concorrente real: `If-Match` antigo retorna 412, preserva a revisão gravada e não emite sucesso;
- outra conta sem grant recebe 404 nas leituras, na declaração e no checkpoint, sem formulário privado;
- cálculo e crédito continuam desabilitados e medições futuras não se tornam requisito universal do PD ex ante.

Com o sandbox isolado e as duas contas preparados:

```sh
python3 scripts/demo_sandbox.py start --quiet
python3 scripts/demo_sandbox.py add-user --email segunda@carbonflow.test --name "Segunda conta demo" --quiet
cd frontend
CARBONFLOW_TEST_URL=http://127.0.0.1:18088 CARBONFLOW_CHROME=/caminho/do/chromium \
  node --test test/requirements-journey.integration.mjs
```

A CI executa esse roteiro no job `demo-sandbox`, após provisionamento e smoke das
contas. O sufixo `.integration.mjs` separa o roteiro, que exige sandbox de contas,
da suíte de navegador executada no Compose padrão. Senhas ficam nos arquivos locais
privados já geridos pelo provisionador; não são argumentos, fixtures versionadas ou logs.
Este aceite é de R0 e não encerra #124, #93, #99, #98 ou #97 nem substitui ensaio humano.
