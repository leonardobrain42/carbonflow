# CF-PACKAGES-001 — Fatia 1: registro de metodologias (D18)
Camada D17: **plataforma** (registro de metadados); não pertence a programa, método, jurisdição nem projeto. Jornada: J01 (seleção metodológica). Owner: #90. Estratégia e sequência: [CF-PACKAGES-001](../specification/CF-PACKAGES-001-ESTRATEGIA-DE-PACOTES.md).

## O que entrega
- Migração aditiva `V103__methodology_registry.sql`: `app_methodology_registry`, **somente de acréscimo** (gatilho recusa `UPDATE`/`DELETE`; correção é nova versão da mesma chave `programa + metodologia + edição`). Guarda metadados: programa, metodologia, edição, título, titular dos direitos, URL oficial `https`, origem (`SEED` ou `ADMIN`), autor, data e `digest` SHA-256. Não guarda texto nem bytes de fontes. Semeia o VM0038 1.1 (programa VCS) como versão 1, a partir do que a V37 já cadastra. Acrescenta `VIEW_METHODOLOGY_REGISTRY` e `REGISTER_METHODOLOGY` à auditoria de staff.
- API `GET /v1/platform/methodology-registry` (última versão de cada chave, com `hasPackage`) e `POST` (registrar). Exige conta de staff com MFA **e** o privilégio próprio `carbonflow.platform.methodology-registry.managers` (variável `CARBONFLOW_PLATFORM_METHODOLOGY_REGISTRY_MANAGERS`, UUIDs de identidade separados por vírgula). **Padrão seguro: ninguém.** Staff sem o privilégio recebe 404; sem MFA, 403; cliente e anônimo não acessam. Toda leitura e escrita é auditada; `private, no-store`.
- Escrita: chave nova exige `If-Match: "0"`; nova versão exige a versão atual (412 se obsoleta, 428 se ausente). Mesmo conteúdo da última versão não gera versão nova (200). Escritores da mesma chave são serializados. Campos inválidos retornam 422.
- Triagem e intake: a lista de metodologias passa a ser **pacotes em código + metodologias registradas sem pacote**, sem duplicar a que tem pacote. Escolher uma registrada sem pacote grava o candidato `CÓDIGO V<edição>`, que continua resolvendo ao perfil genérico (lacuna explícita, sem fallback). A visão da triagem ganha `candidateStatus` derivado (`PACKAGE`, `REGISTERED_NO_PACKAGE`, `UNREGISTERED`, `NONE`) e a tela mostra o aviso de que a metodologia está registrada, ainda sem pacote executável.

## O que não faz
Registrar **não** cria pacote, não habilita capacidade, não admite dado, não concede licença nem executa cálculo, regra ou ato externo (a prontidão de cálculo de um projeto com candidata registrada devolve só a lacuna de metodologia). Não anexa fontes (fatia 2), não calcula capacidade efetiva nem fixa versão no projeto (fatia 3), não define o contrato de pacote em código (fatia 4). A tela de administração é a entrega seguinte (abaixo). Regime sandbox, como as licenças atuais.

## Tela de administração
Página `/workspace/platform/methodologies` (componente `PlatformMethodologyRegistryComponent`), com o mesmo shell e a mesma porta de MFA da página de fontes e link em "Metodologias" nas duas telas administrativas. Mostra, para cada metodologia, programa, código, edição, versão, titular, endereço oficial, origem (cadastro inicial ou administração), assinatura abreviada e o **estado vindo do servidor** (`Pacote em código` ou `Registrada, sem pacote executável`); a tela não deduz estado nem capacidade. Formulário de registro (`If-Match "0"`) e de nova versão (chave travada, `If-Match` com a versão atual); repetir o mesmo conteúdo informa que não houve alteração; conflito e validação têm mensagens claras. Se o servidor negar o registro (staff sem o privilégio, 404), a tela mostra só o aviso, sem formulário nem lista. Rótulos associados, foco visível herdado do painel, sem texto protegido (aviso no formulário).

## Fatia 2: fontes por metodologia
API de administração (mesma equipe, MFA e privilégio do registro; o privilégio de consulta de fontes é distinto e não concede escrita):
- `GET /v1/platform/methodology-registry/{methodology}/{edition}/sources`: contextos da metodologia com `needCount` e `registeredSourceCount` (derivados; uma fonte só conta quando a ingestão já publicou seus metadados).
- `POST .../sources/contexts`: cria um contexto (`scenario`, `title`, `country`; `networkMode`, `vehicleCategory`, `chargerType`, `energySystem` opcionais). Só cria; repetição idêntica devolve 200; conteúdo diferente para o mesmo cenário, 409.
- `POST .../sources/contexts/{scenario}/needs`: cria uma necessidade de fonte (metadados apenas: etapa, tipo, título, finalidade, `sourceId`/`sourceEdition`, endereço oficial https, capacidade bloqueada, política de edição, responsável, prioridade). O código é global; repetição idêntica 200, conteúdo diferente 409. Evidência de projeto não tem fonte; política YEAR/LATEST exige a edição correspondente.
- Metodologia não registrada: 404. Validação: 422.
Migração V104: os atributos do contexto, antes presos à recarga de veículos, passam a ser opcionais (relaxamento aditivo) e entram as três ações de auditoria. O console de prontidão existente lê os contextos novos sem alteração; o estado de cada necessidade continua derivado da ingestão e das licenças, nunca declarado aqui. Cadastrar necessidade não registra fonte, não concede licença, não admite dado e não habilita cálculo. Teste: `MethodologySourcesIntegrationTest`.

### Tela das fontes
Página `/workspace/platform/methodologies/{methodology}/{edition}/sources` (`PlatformMethodologySourcesComponent`), aberta pelo link "Fontes" de cada metodologia no registro (que agora mostra `sourceNeeds` e `registeredSources`). Mesma porta de MFA; lista os contextos com as contagens derivadas; formulário de contexto (atributos de recarga opcionais) e de necessidade por contexto. Para evidência do projeto o formulário não pede fonte; nas políticas "do ano" e "mais recente" a edição vai como o token correspondente. Conflito (409), validação (422) e 404 têm mensagens próprias; com 404 na lista só o aviso aparece. A tela não mostra estado de admissão: remete ao console de Fontes e prontidão. Teste de navegador: `platform-methodology-sources.test.mjs` (API mockada).

## Fatia 3: capacidade efetiva e fixação de versão
Leitura do projeto (já protegida pelo acesso ao projeto): `GET /v1/projects/{id}/triage` e as respostas de salvamento passam a trazer `methodologyPin` e `capabilities`.

**Fixação (`app_project_methodology_pin`, V105, só acréscimo, trigger de imutabilidade).** Ao salvar uma revisão da triagem, se a candidata, a versão do registro ou o digest do pacote mudou, grava-se uma linha com: revisão da triagem, metodologia/edição, versão e digest do registro, `packageId`, versão e digest do pacote, versão do catálogo, autor e data. Candidata removida também é registrada (pin sem metodologia). Salvar sem mudança não grava. Uma versão nova do registro ou do pacote **não altera** o projeto: a leitura só levanta `newerRegistryVersion` e `packageChanged`. Migrar para a versão nova é operação explícita e versionada, ainda não implementada. A fixação registra contra o que o projeto foi preparado; não admite dados, não licencia, não habilita cálculo nem confirma elegibilidade.

**Capacidade efetiva (derivada a cada leitura, nunca gravada nem editável).** Por finalidade, com os eixos código, dados e licença (`MET`, `NOT_MET` ou `NOT_REQUIRED`) e um estado efetivo (`ENABLED` ou `NOT_ENABLED`) com motivo:
- `TRIAGE`: exige código (pacote com schema de triagem). Registrada sem pacote fica `NOT_ENABLED` com a lacuna explícita.
- `SOURCE_REFERENCE`: exige código, metadados publicados de **todas** as fontes obrigatórias (não evidência de projeto) e decisão de licença `REFERENCE_REQUIREMENT` vigente, aprovada por quem não submeteu, com prova, no ambiente de testes (mesmas regras do console de prontidão). Sem necessidades cadastradas, dados é `NOT_MET`.
- `CALCULATION_ADMISSION` e `EXTERNAL_ACTS`: sempre `NOT_ENABLED`; são gates próprios e não derivam de código, dados ou licença.
A visão do cliente traz só estados e motivos: sem nomes de fontes, contagens ou licenças (o inventário administrativo segue restrito à equipe).

Teste: `MethodologyPinIntegrationTest` (pin v1 do VM0038, sem nova linha ao repetir, pin sem pacote, deriva sem alterar o projeto, candidata removida, imutabilidade, isolamento, ausência de inventário). **Não coberto por teste:** o caminho positivo de `SOURCE_REFERENCE` (fontes com metadados e licença aprovada); só o negativo foi exercitado. Sem tela nesta fatia.

## Fatia 4: contrato de pacote e kit de conformidade
`MethodologyPackageContract` (código de produção) fixa o que todo pacote cumpre, em regras executáveis. O catálogo é montado por `requireProduction`: um pacote que viola o contrato **não chega ao catálogo** e a versão não inicia, de modo que o erro aparece no CI e não para o cliente. O kit verifica estrutura, vocabulário, isolamento e neutralidade; não avalia se as regras de uma metodologia estão certas, não admite dados, não licencia e não habilita cálculo.

| Regra | O que exige |
|---|---|
| `PKG-ID`, `PKG-KEY`, `PKG-VERSION`, `PKG-STATUS`, `PKG-TEXT` | `packageId`, programa, metodologia e edição no formato do registro; versão ≥ 1; título e descrição |
| `PKG-ACTIVITY` | ao menos uma atividade, todas do vocabulário da plataforma |
| `PKG-QUESTION` | chaves válidas, únicas no pacote e que não redefinem chaves da plataforma; rótulo; tipo `choice` ou `number`; escolha com 2+ opções únicas |
| `PKG-CAPABILITY` | exatamente `triageSchema`, `calculationAdmission`, `externalActs`; as duas últimas sempre `NOT_ENABLED` (gates próprios, fora do pacote) |
| `PKG-EVALUATOR`, `PKG-NEUTRAL` | avaliador presente; com respostas vazias, malformadas ou de outra metodologia responde sem falhar e diz onde está |
| `PKG-PRESENTATION` | apresentação presente |
| `PKG-DIGEST` | o digest muda com programa, versão, status e capacidades (assim a fixação do projeto enxerga a mudança) |
| `PKG-DUPLICATE`, `PKG-ISOLATION` (entre pacotes) | identidade única; nenhuma chave de resposta compartilhada; o texto visível de um pacote não cita o código nem o id de outro (AGN-01) |
| `PKG-TEST-ONLY` | pacote de teste (`TEST_ONLY*`, `TEST-ONLY`) nunca entra no catálogo de produção (AGN-02) |

**Como adicionar um pacote (por PR):** (1) registrar a metodologia/edição pela administração, ou deixar para depois: um pacote sem registro e um registro sem pacote são estados derivados, não erro; (2) criar o perfil de triagem em `ProjectJourney` e incluí-lo em `CONFIGURED`; (3) declarar programa, versão, status e capacidades em `MethodologyPackages.META` (sem habilitar cálculo ou atos); (4) rodar `MethodologyPackageContractTest`: o pacote de produção passa pelo kit e a suíte falha se violar uma regra; (5) atualizar especificação, matriz e CHANGELOG no mesmo PR. Alterar um pacote muda o digest; subir a versão do pacote e do catálogo quando mudar o comportamento. Pacotes de teste vivem só em testes.

Teste: `MethodologyPackageContractTest` (8 casos: catálogo de produção conforme; pacote de teste conforme mas barrado em produção; um pacote construído para violar cada grupo de regras). **Limites:** a neutralidade usa quatro amostras fixas de respostas, não prova o comportamento para qualquer entrada; só há um pacote de produção, então o isolamento entre pacotes de produção ainda só é exercitado contra o pacote de teste. Nada disso exige migração, API ou tela.

## Fatia 5: plano do documento do projeto (PD) por pacote (A04)
Nível **método/edição**. O dossiê preliminar deixa de ter template, edição, seções e bloqueios reais fixos para o VM0038 dentro de `DynamicProjectTechnical`. Cada pacote declara um `PdDescriptor` no seu perfil (`ProjectJourney.Profile.pd`, fora do JSON da API): identificador do template, edição do template, seções e **bloqueios próprios** (somados aos bloqueios padrão da plataforma). Sem pacote, vale o plano neutro (`A_DEFINIR_APOS_TRIAGEM`, `INDEFINIDA`, as oito seções propostas pela plataforma, só os bloqueios padrão). `DynamicProjectTechnical.pdPlan(profile)` é a única leitura.
- **Sem mudança de comportamento para o VM0038:** mesmo template (`SRC-007_PD_v5.0B.docx`), edição `5.0B`, oito seções e os mesmos bloqueios; os testes existentes do dossiê continuam verdes.
- **Contrato (`PKG-PD`):** template e edição são identificadores (nunca texto protegido) e ambos neutros ou ambos definidos; de 1 a 12 seções únicas de até 80 caracteres; bloqueios completos; plano neutro sem bloqueios próprios. O texto do plano entra na checagem de isolamento entre pacotes (não pode citar outra metodologia).
- **Digest e versão:** o plano passa a compor o digest do pacote. Por isso o pacote VM0038 vai à versão **2** e o catálogo à **2**. Projetos já fixados (fatia 3) passam a mostrar `packageChanged = true`: é o sinal previsto de que o pacote mudou, sem alterar o projeto; migrar continua sendo operação explícita, ainda não implementada.
- Teste: `MethodologyPackageContractTest` (plano do VM0038 idêntico ao anterior; plano neutro; plano próprio sem os bloqueios do VM0038; violações de `PKG-PD`; texto que cita outra metodologia; digest sensível ao plano).

**Residuais da A04, não resolvidos nesta fatia (guardas mantidos, conforme a auditoria):**
1. `PdSections` (rascunhos de seção do PD) ainda fixa o template e a edição do VM0038 ao gravar o artefato; só roda no fluxo OPEN, já protegido por ações de escopo.
2. `ProjectRequirements` ainda calcula `open` por `"VM0038".equals(methodology)` para decidir quais projeções ler.
3. `project-technical.ts` mantém um perfil de VM0038 reconstruído no cliente como fallback de clientes legados com escopo só de guias.
4. Os textos do PD continuam vindo de documentos do VM0038; não existe ainda um segundo pacote que prove o contrato com um template real.

## Escolhas reversíveis (aprovadas em D18, §8)
Privilégio próprio; somente acréscimo; sem segundo aprovador no cadastro; sandbox.

## Verificação
`MethodologyRegistryIntegrationTest` (acesso por perfil e MFA, seed, versões, repetição idempotente, 412/428/422, imutabilidade no banco, auditoria, triagem com candidata registrada, prontidão neutra, cliente sem acesso) `project-triage.test.mjs` (aviso de registrada sem pacote) e `platform-methodology-registry.test.mjs` (MFA, registro novo, nova versão, conflito, repetição sem alteração e staff sem privilégio; API mockada). O ensaio de reversão da V79 no CI passou a conhecer a tabela e a versão 103.
