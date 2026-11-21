# CF-UX-TECH-001 — Integrações UX-BL-01, UX-ADD-01 e UX-PD-01

**Issues:** #124 (navegação/UX), #40 (owner do workbench baseline), #38/#99 (owners de adicionalidade), #43/#122 (owner documental), #94/#95/#96/#97 (owners EVR, ratings, fatores e motor), #93 (aplicabilidade).
**Recorte:** VM0038 v1.1 OPEN BR LDV BEV DCFC SIN em projetos SANDBOX, sem fallbacks PIL-SYN.

## Arquitetura

Esta mudança **não cria tabelas ou novas fontes canônicas**. O objetivo explícito da #124 é reutilizar a arquitetura implementada. O componente `DecisionWorkbenchComponent` substitui as duas telas de `ProjectGuidesComponent` genérico por **passos estáveis** e preserva a navegação por `?secao=linha-base|adicionalidade&etapa=...`. O conteúdo não muda o menu à medida que o usuário avança. Os dados são consultados exclusivamente em APIs já existentes com cookie de sessão, escopo de projeto e grants verificados pelo backend. Nenhum dado das APIs `/v1/platform/*` é requisitado pelo cliente.

**Linha de base:** cenário/enquadramento → EVR/ratings reais por período MR → fatores/diagnóstico E8 → premissas tipadas → revisão/lacunas → origem para PD. Consome `GET /technical/open-monitoring/dossiers`, `GET /technical/open-monitoring/dossiers/{period}/e8-fleet-selections` quando o grant permitir, `GET /technical/open-calculation-readiness`, `GET /technical/open-applicability` e `GET /technical/typed-guides`. A seleção de frota vem do owner #94/#95/#98 por vínculo #64 e NÃO publica os dados de fonte numéricos licenciados. Ausência de MR/frota/fator é uma lacuna, jamais zero ou BE calculado.

**Adicionalidade:** enquadramento VMD0049 candidato + regulatory surplus (findings reais de `OpenApplicability`), cronologia declarativa do guia tipado, titularidade/disclosure/double-claiming como DECLARAÇÃO (não concessão de poderes/contrato provado), revisão e prévia do PD. Não produz parecer normativo, altera direitos, concede PP ou reinterpreta datas como atos jurídicos. As fases jurídicas canônicas completas continuam sob #38/#99/#24.

**PD:** `PdTraceComponent` consulta `GET /technical/pd-sections` via fonte autorizada e correlaciona `guideId`, revisão textual, versão de artefato, SHA-256 e entradas tipadas reais quando visíveis, com link ao editor já existente. A mesma prévia aparece na tela de guias do guia aberto, somente quando a conta já tem `VIEW_DOSSIER`. Quem não tem essa permissão não dispara a consulta. Trocar de guia mostra a seção correspondente, sem gravar narrativa nem copiar a resposta tipada para o documento. **Não altera a versão congelada do PD** e marca explicitamente que a API de autoria existente fixa revisões do guia textual, não transforma respostas tipadas em conteúdo normativo. O mapeamento normativo por template e reivindicação→prova é owner exclusivo #43/#122. Nenhuma alegação de documento pronto.

## UX/segurança

- Formulário editável apenas pelo `TypedGuideComponent` existente: validação, ETag, idempotência e edição. Em Premissas e Cronologia a ação principal é salvar nessa mesma etapa. Uma recusa mantém o rascunho na tela. Notas textuais antigas continuam como histórico, sem botão que leve a outra seção para concluir a tarefa.
- Navegação por botões com `aria-current=step`, links persistentes e proteção de navegação se o editor possuir dados não salvos.
- Cada fonte consulta sua API **somente se** o capability do projeto permitir. Erros/revogação mostram estado `restricted`/erro, **não** ausência factual. O backend controla a segurança efetiva e recusa chamadas forjadas.
- O hash da seleção de frota e o hash da versão do PD aparecem como declaração de integridade: detectam alteração e não autenticam a fonte, não assinam o documento e não admitem o cálculo.
- A tela mostra situação, motivo, próxima ação e papel previsto de gates reais. O nome da pendência está em português e o código estável fica na referência. A seleção de frota descreve cada ressalva em português, com a referência do código ao lado. Situações de rede, direitos, parecer e tipo de medição usam o mesmo texto curto, e o código permanece na referência. Identificadores estáveis, como PEN-004 e códigos de carregador, continuam visíveis. Estoque, AFEC/MPG, contratos e arquivos continuam fora desta tela sem o respectivo grant.
- A prévia de PD difere claramente `DECLARED`, `PENDING_EVIDENCE`, `REVIEW_REQUIRED`, `STALE` e `DOCUMENT_VERSION`, sem conceder aprovação Verra/VVB.

## Testes

`frontend/test/decision-workbench.test.mjs` cobre: mobile 390×844 e desktop 1440×900, consulta a vínculos EVR por período/estado, diagnóstico de fatores e E8 bloqueado, histórico de versões do PD e divergência entre guia tipado e legado, linha temporal de compromisso anterior à consideração de carbono sem reprovação automática, titularidade/termos como declaração, acesso sem grants (nenhuma solicitação a APIs restritas), link profundo e recarga preservada.

**Limitações explícitas:** Esta é integração de **experiência e consulta** das APIs canônicas entregues; não encerra #40/#38/#43/#99. Os owners ainda precisam de seleção/decisão versionada, timeline com provas canônicas, regra normativa aplicável, análise humana e mapa de blocos template→PD para gerar documentos finais E8. Nenhum cálculo ou documento jurídico foi simulado.

A UX-03B #141 (arquivos de estações) é PR independente e não é dependência para esta experiência.
