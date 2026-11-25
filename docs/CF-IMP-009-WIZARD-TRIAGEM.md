# CF-IMP-009 — Jornada de triagem: cinco etapas, salvamento e retomada

**Issue:** [#17](https://github.com/leonardobrain42/carbonflow/issues/17) · **Dependências integradas:** CF-IMP-007 (cadastro de projetos, PR #57) e primeira fatia CF-IMP-008 (contexto técnico, PR #58).

## Objetivo e experiência

Após criar um projeto sandbox, o usuário escolhe **Iniciar triagem guiada** (ou **Iniciar ou retomar triagem** na home). As etapas são:

1. **Planejamento** registra objetivo, atividade e metodologia candidata. O catálogo oferece a jornada VM0038 v1.1 e permite registrar outra hipótese ou continuar sem indicação.
2. **Atividade** descreve a operação e seu cenário sem o projeto. Na VM0038, pergunta também se o projeto inclui a instalação de sistemas de recarga.
3. **Detalhes da metodologia** apresenta frota, recarga, rede, acesso, suprimento, infraestrutura associada e medição para VM0038. A jornada inicial usa esta etapa para registrar escopo e dúvidas. Justificativas registradas em versões anteriores continuam legíveis.
4. **Local, dados e direitos** registra localização, data de referência, situação dos dados e declarações sobre titularidade.
5. **Resumo da triagem** permite editar qualquer etapa, consultar condições metodológicas a revisar ou declarações fora do recorte do piloto e seguir para a preparação técnica. A avaliação metodológica continua pendente.

O perfil da jornada vem da API e é selecionado pelo código da edição configurada. O valor histórico `VM0038` abre o perfil v1.1 sem definir a edição vigente para nenhum ato externo. A [VM0038 v1.1, seção 4](https://verra.org/documents/vm0038-electric-vehicle-charging-systems-v1-1/) contempla, sob condições, recarga AC, determinadas combinações com híbridos plug-in, ônibus e caminhões médios/pesados e infraestrutura associada com medição rastreável. O piloto continua limitado pelo recorte implementado em `IntakeRules`. A triagem registra cenários fora desse recorte para revisão e mantém desconhecidos em aberto.

O usuário pode ir a qualquer etapa; a navegação guarda o cursor e salva as alterações da etapa com controle de versão. O botão **Salvar e continuar depois** persiste sem avançar. Recarregar a página restaura cursor e respostas. Não há percentual de certificação, nenhuma elegibilidade deduzida da seleção de metodologia e nenhum fator ou ER calculado. Campos vazios e escolhas \`DESCONHECIDO\` continuam **pendentes**, não viram zero. O status \`PREENCHIDO\` indica apenas respostas completas naquele formulário, não conformidade metodológica.

A interface foi desenhada para o usuário comum e preserva a possibilidade de revisão técnica separada via CF-IMP-008. Essa persistência de respostas **não cria uma segunda máquina de tarefas ECA** nem escreve automaticamente no intake técnico do E8. A preparação técnica usa a metodologia candidata da triagem para orientar as telas. Vincular a indicação à revisão técnica exige salvar. A análise futura deve verificar admissibilidade e vigência antes de usar as demais respostas como dados técnicos.

## Contratos

| Método | Rota | Proteções | Resultado |
|---|---|---|---|
| GET | \`/v1/projects/{id}/triage\` | Conta autenticada, \`DRAFT_OWNER\` originário e membership ativo no sandbox | Revisão, cursor, respostas por etapa, campos pendentes, situação e próxima ação. \`ETag\` |
| PUT | \`.../triage/steps/{1..4}\` | \`Idempotency-Key\` e \`If-Match: "N"\` | Corpo \`{"answers":{...},"nextStep":1..5}\`. Substitui somente o conjunto de respostas dessa etapa, preservando as demais. Nova revisão imutável |
| PATCH | \`.../triage/cursor\` | Mesmos headers | \`{"nextStep":1..5}\`. Persiste escolha de etapa sem alterar respostas |
| POST | \`.../triage/examples/bev-df-2027\` | Mesmos headers; uso explícito no SANDBOX | Substitui conjunto de respostas por **dados ilustrativos**, grava revisão e abre o resumo |

Replays da mesma chave/mesmo comando são determinísticos (inclusive em concorrência); outra carga sob a mesma chave retorna 409. If-Match ausente retorna 428, divergente 412. Uma tentativa fora do escopo não revela existência do projeto (404) e grava evento de negação com digest do alvo; sessão ausente retorna 401. Todas as operações têm vínculo por \`project_id\`, \`organization_id\` e \`environment=SANDBOX\`. Revisões antigas não são apagadas e \`app_project.version\` não muda apenas por navegar na triagem.

### Exemplo manual CF-CT-023

O botão **Preencher exemplo de 25 BEVs** preenche por código de produto (sem carregar massa do piloto):

- atividade candidata: mobilidade elétrica e hipótese VM0038;
- frota: **25 BEVs**, carregador candidato DCFC, 1 unidade hipotética;
- localidade: **Brasília/DF, Brasil**;
- data de referência: **27/09/2027**;
- dados/evidências/direitos: **DESCONHECIDO**, permanecendo pendentes.

Esse exemplo **não** reproduz tCO₂e, BE/PE/LE/ER, cenário baseline admissível ou VCUs do piloto. O objetivo é reproduzir **a entrada do cenário e a jornada**, sem fixture nem cópia de resultados. Um projeto real precisará informar número verdadeiro de carregadores, infraestrutura, evidências, direitos, fontes de energia e metodologia apropriada; esses dados não são inferidos de 25 veículos.

## Verificação

- **CF-CT-021:** 5 etapas navegáveis, salvamento revisável, GET preserva cursor e respostas; ETag/If-Match e Idempotency-Key corretos.
- **CF-CT-022:** desconhecido, incompleto, não aplicável justificado e resposta declarada permanecem estados distintos; troca de etapa não transforma lacuna em zero.
- **CF-CT-023:** cenário de 25 BEVs Brasília/DF em 27/09/2027, sem \`PIL-SYN-BR-001\` nem hash do piloto; campos desconhecidos permanecem pendentes.
- **CF-UX-02/12:** próximos passos compreensíveis, retorno do usuário, estados vazios e alertas de versão obsoleta.
- Segurança: cross-org, mesmo-org de autor distinto, revogação, credencial SIM-* e leitura por conta não autorizada; trilha de negação.
- Regressões: Quarkus/PostgreSQL, Angular build e jornadas E2E existentes no Compose isolado.

## Limites

A CF-IMP-009 não emite diagnóstico técnico ou deferimento de metodologia; isso pertence à **CF-IMP-010 (#18)** e aos gates ECA/REG correspondentes. A triagem persiste intenção, atividade, frota e direitos **declarados**, mas não os reconhece como evidências admitidas. Fluxos E8, atos externos, créditos e monitoramento permanecem desabilitados sem controles próprios.

A implementação não escreve em \`pilot_*\`, não modifica hashes históricos e não seleciona o piloto automaticamente. O código da etapa 5 é um **resumo**, não motor de regras metodológicas.

## Adendo D16 (24/11/2026) — triagem como hipótese inicial
Ver [CF-UX-TECH-001-TRIAGE-HYPOTHESIS](CF-UX-TECH-001-TRIAGE-HYPOTHESIS.md). Desde a fatia 6a: campos que saem da triagem (lista em `ProjectJourney.RETIRED`) são copiados sem alteração quando uma etapa é salva sem eles; o diagnóstico (`REG-E2 / CF-IMP-010 v3`) lê acesso, rede, energia e infraestrutura da declaração de Acesso à rede quando ela existe e não pede mais investimento, direitos nem situação dos dados na triagem. O formato `answers`, as revisões e o histórico continuam os mesmos.
