# CF-TESTE-UI — Roteiros de teste manual pela interface

**Natureza:** roteiro de verificação humana no navegador. Complementa, não substitui, os testes automatizados (`frontend/test/*.test.mjs`, JUnit e CI). Não é evidência de homologação, ensaio moderado com iniciante (ENTRY-T08, UX-T17) nem aceite normativo.
**Pertence a:** plataforma e projeto (interface). Não fixa método, edição, jurisdição, fórmula ou ato. Onde aparece VM0038, é só porque a primeira fatia OPEN o usa como candidato. Nenhum passo admite fonte, habilita cálculo ou emite crédito.
**Impacto em regra ou contrato:** nenhum. É só documentação de teste; nenhuma regra, contrato, migração ou guarda foi alterado.

Os rótulos de tela abaixo vieram dos templates em `frontend/src` e dos contratos em `docs/`. Se um rótulo mudar, vale o template. Onde um comportamento só estava descrito em contrato, o resultado esperado cita a fonte. Divergências devem virar issue, não ajuste do roteiro.

## 0. Preparação

### 0.1 Ambientes

| Ambiente | Subir | URL | Uso |
|---|---|---|---|
| Piloto sintético | `docker compose up --build -d --wait` | `http://127.0.0.1:8080/pilot` | Roteiros P (contas `SIM-*`) |
| Espaço de contas (demo) | `python3 scripts/demo_sandbox.py start` | `http://127.0.0.1:18088/workspace` | Roteiros E, C, W, T, K, G, X |
| Admin local | `python3 scripts/admin_sandbox.py setup` | `http://127.0.0.1:18088/workspace/platform/sources` | Roteiros A |

Diagnóstico: `python3 scripts/demo_sandbox.py doctor`. Se a 8080 estiver ocupada, use `CARBONFLOW_PORT=18080`. Para os totais de referência do piloto (6,00 e 5,30 tCO₂e), use volume limpo (`CARBONFLOW_PORT=18086 docker compose -p carbonflow-ensaio up --build -d --wait`).

### 0.2 Contas e segredos

- Demo: `demo@carbonflow.test`; a senha aparece no terminal (`python3 scripts/demo_sandbox.py show`). Segunda conta: `python3 scripts/demo_sandbox.py add-user --email demo2@carbonflow.test`. Ela serve aos testes de isolamento e convite.
- Admin: `admin@carbonflow.test`; senha por `demo_sandbox.py show --email admin@carbonflow.test`; código MFA por `python3 scripts/admin_sandbox.py code` (renova a cada 30 s).
- Piloto: `SIM-PREP/piloto-prep`, `SIM-REV/piloto-rev`, `SIM-PP/piloto-pp`, `SIM-VVB/piloto-vvb`, `SIM-VVB-B/piloto-vvb-b`, `SIM-OP/piloto-op`, `SIM-PREP-OUTRA/piloto-prep-outra`.
- Não cole senha, código MFA nem semente em issue, PR ou evidência. Cole só capturas sem esses campos.

### 0.3 Convenções

- Use janela anônima por perfil, ou perfis separados, para não misturar cookies.
- Viewports de referência: **1440×900** e **390×844** (CF-IMP-006).
- Cada caso tem **ID**, **passos** e **esperado**. Registre **OK / FALHA / BLOQUEADO** e a evidência (captura, hora, versão do commit exibida pelo `git rev-parse --short HEAD`).
- Uma FALHA vira issue com ID do caso, passos, esperado × observado e captura.
- **Regra geral de leitura:** nenhuma tela pode afirmar que algo foi admitido, aprovado, calculado como oficial ou emitido se o roteiro não executou esse ato. Afirmações assim são FALHA, mesmo que o resto funcione.

---

## E. Entrada (`/`) — CF-UX-ENTRY-001

| ID | Passos | Esperado |
|---|---|---|
| E-01 | Abrir `/` sem sessão. | Título "Comece um projeto de carbono com orientação técnica" e três caminhos: **Iniciar meu projeto**, **Acessar meus projetos**, **Explorar piloto sintético**. Link "Pular para o conteúdo" funcional por Tab. |
| E-02 | Clicar **Explorar piloto sintético**. | Vai para `/pilot` com "Entrar no piloto". Dados fictícios identificados como tal. |
| E-03 | Voltar e clicar **Acessar meus projetos**. | Tela "Entre na sua conta" (e-mail e senha). Sem acesso a projetos sem login. |
| E-04 | Clicar **Iniciar meu projeto**. | Tela de conta com intenção de novo projeto. Após login, a conta cai em `/workspace/projects/new`. |
| E-05 | Abrir `/workspace` direto, sem sessão. | Tela de entrada de conta. Nada de projeto aparece. |
| E-06 | Abrir `/pilot` com sessão de conta ativa e `/workspace` com sessão SIM ativa. | Cookies separados: uma sessão não autentica a outra área. |

## C. Conta — cadastro, confirmação, recuperação, sessão

A caixa de e-mail é a `SandboxAccountMailbox` local. **Não há entrega SMTP.** A UI não pode dizer que enviou e-mail real. Os códigos ficam na pasta privada do demo (`.carbonflow-demo`, ver `docs/CF-IMP-006-DEMO-SANDBOX.md`).

| ID | Passos | Esperado |
|---|---|---|
| C-01 | **Criar conta** → preencher **Seu nome** e **E-mail da conta** (domínio `.test`) → **Solicitar cadastro**. | Resposta genérica, sem revelar se o e-mail já existia. Botão bloqueado durante o envio (sem duplo envio). |
| C-02 | Repetir C-01 com o mesmo e-mail. | Mesma resposta genérica, sem enumeração de contas. |
| C-03 | Recuperar o código na caixa do sandbox → **Já tenho código de confirmação** → informar o código, **Nova senha** (12 a 128 caracteres) e repetir → **Confirmar conta**. | Conta confirmada; volta para entrar. |
| C-04 | Confirmar com senha de 11 caracteres, e depois com senhas diferentes. | Erro 422 claro, sem limpar o código sem motivo. |
| C-05 | Confirmar com código errado e depois com código já usado. | Erro tratado, sem tela quebrada nem detalhe técnico. |
| C-06 | **Entrar na conta** com credenciais corretas. | Entra em `/workspace`. |
| C-07 | Entrar com senha errada, e com e-mail inexistente. | Mesma mensagem (401) nos dois casos. |
| C-08 | Disparar muitas tentativas seguidas de login ou cadastro. | Mensagem de limite (429) clara; a tela continua usável depois. |
| C-09 | **Esqueci minha senha** → **Solicitar recuperação** → código → nova senha → **Redefinir senha**. | Senha trocada; a antiga deixa de funcionar. |
| C-10 | Inspecionar a barra de endereço e as ferramentas do navegador (Application → Local Storage) durante C-01 a C-09. | Senha, código e token **não** aparecem em URL, histórico ou `localStorage`. Cookie de sessão é `HttpOnly` e `SameSite=Strict`. |
| C-11 | **Sair**, e depois voltar com o botão "voltar" do navegador. | Não mostra dados da conta em cache; pede login. |
| C-12 | Em uma aba, sair; em outra, aberta antes, atualizar a lista. | A segunda aba recebe pedido de login sem expor projetos. |
| C-13 | Parar o container (`demo_sandbox.py stop`) e tentar entrar. | Aviso de indisponibilidade (503), não falha silenciosa. |

## W. Espaço de trabalho — organização e projeto (`/workspace`)

Conta nova, sem projetos.

| ID | Passos | Esperado |
|---|---|---|
| W-01 | Entrar em `/workspace` com conta nova. | "Você ainda não tem projetos" e botão **Criar meu primeiro projeto**. Nada selecionado automaticamente. |
| W-02 | Clicar o botão. | `/workspace/projects/new`, "Preparar meu primeiro projeto", "Crie o espaço da organização". |
| W-03 | **Nome de trabalho da organização** → **Criar espaço privado**. | "Espaço organizacional criado". Dá para criar de novo? Não deve duplicar (idempotência). Nenhuma informação de poder de PP é concedida. |
| W-04 | Recarregar a página (F5). | Mostra o espaço existente, sem recriar. |
| W-05 | "Descreva seu projeto": escolher **Organização responsável**, preencher **Nome de trabalho do projeto** e **Descrição inicial (opcional)** → **Salvar rascunho**. | "Projeto criado no sandbox". Nenhuma metodologia aparece como escolhida. |
| W-06 | Salvar com nome vazio. | Erro de validação no campo, sem criar projeto. |
| W-07 | Voltar a `/workspace`. | O projeto aparece em "Meus projetos". Com mais de um, nenhum vem selecionado: o usuário escolhe em **Selecionar projeto**. |
| W-08 | Selecionar o projeto, alterar **Nome de trabalho** e **Descrição inicial** → **Salvar alterações**. | Dados atualizados e persistidos após F5. |
| W-09 | **Atualizar lista**. | Lista atual preservada; a seleção só some se o projeto sumiu ou foi revogado. |
| W-10 | Com a conta `demo2`, abrir `/workspace`. | **Não** vê projetos nem organização de `demo`. |
| W-11 | Colar na conta `demo2` a URL `/workspace/projects/<id de demo>/overview`. | Acesso negado ou "não encontrado", sem vazar título nem dados. |
| W-12 | Menu (`Navegação do CarbonFlow`): conferir itens. | Conta comum **não** vê Conformidade, Fontes, Licenças, Séries da rede. |

## T. Triagem e visão geral

Pré-requisito: projeto criado em W-05.

| ID | Passos | Esperado |
|---|---|---|
| T-01 | Abrir `…/projects/{id}/triage` ("Conheça seu projeto"). | Etapas numeradas, sem pergunta técnica de medição. |
| T-02 | "O que você quer alcançar?" → "Qual atividade descreve melhor sua ideia?" → "Como a atividade funcionará?". Para recarga de veículos elétricos, escolher a atividade correspondente. | Segue por etapas. O resultado é tratado como **hipótese inicial** (CF-SPEC-D16), nunca como elegibilidade. |
| T-03 | Preencher "O que acontece hoje, antes do projeto?" e "Qual benefício ambiental…" (opcionais) e deixar em branco. | Aceita os dois. Não bloqueia. |
| T-04 | **Escolher metodologia**: escolher uma candidata e depois **Outra** com **Código da outra metodologia**. | A outra metodologia mantém rascunho e decisão de escopo; **não** mostra campos, gates ou equações de recarga VM0038 (D17/AGN-01). |
| T-05 | Informar **País** (2 letras), **Estado ou região**, **Cidade/localidade** e **Data de referência pretendida**. Testar país com 3 letras e data inválida. | Validação por campo. Datas aceitas guardadas após F5. |
| T-06 | **Salvar e continuar depois**, sair, entrar, reabrir. | Respostas preservadas. "Voltar de onde parei" na visão geral leva a esta etapa. |
| T-07 | Ver **Diagnóstico preliminar** e "O que será pedido na preparação técnica". | Lista clara do que será pedido; linguagem diz hipótese, não aprovação. |
| T-08 | Em projeto com pino de metodologia antigo (se houver), usar **Migrar para a versão atual** → **Cancelar**, depois → **Confirmar migração**. | Cancelar não altera nada. Confirmar registra a migração explícita (V106); versões antigas permanecem consultáveis. Sem projeto assim: marcar BLOQUEADO. |
| T-09 | Abrir `…/overview`. | Título do projeto, "X de Y tarefas com registro concluído", **Próxima ação**, "Pendências do diagnóstico", "O que está impedindo o avanço?". |
| T-10 | **Buscar tarefa**, usar filtros e **Limpar filtros**. Atualizar a página. | Filtros ficam na URL (`?…`) e restauram. |
| T-11 | **Atualizar visão geral**. Com a API fora do ar, usar **Tentar novamente**. | Estado de erro com saída; sem tela vazia silenciosa. |
| T-12 | Qualquer tela de triagem ou visão geral: ler textos. | Nada afirma que o projeto "é elegível", "tem crédito" ou "foi validado". |

## K. Preparação técnica (`…/technical`)

Navegação por `?secao=` (ver `docs/CF-UX-TECH-001-MODULE-NAVIGATION.md`). Quatro grupos: Começar, Fundamentação, Medição, Documentação.

| ID | Passos | Esperado |
|---|---|---|
| K-01 | Abrir `…/technical`. | Painel de trabalho (`?secao=inicio`) e módulos agrupados. Link "Pular para a preparação técnica". |
| K-02 | Percorrer `?secao=` `inicio`, `preparar`, `analisar`, `rede`, `direitos`, `linha-base`, `adicionalidade`. | Cada uma abre, destaca o módulo ativo e conserva o botão "voltar" do navegador. |
| K-03 | Percorrer `?secao=monitorar&etapa=` `inventario`, `leituras`, `qa`, `dossies`. | Idem. Etapas que dependem de dados ausentes mostram o motivo, não ficam vazias. |
| K-04 | Em "Analisar", ler **Próxima ação** (duas ações no roteiro UX-T17: estações e carregadores; comprovante da declaração). Clicar a primeira. | Abre **Estações e fronteira**. |
| K-05 | **Nova estação**: registrar `ST-01`, "Estação Norte", Brasília, estado operacional **planejado** → **Salvar versão da estação**. | Nova revisão confirmada. Texto avisa que localização e situação são declarações pendentes de comprovação. |
| K-06 | **Novo carregador** → **Salvar carregador**. Em seguida **Recarregar**. | Carregador listado; dados persistem. Estação planejada não produz energia medida. |
| K-07 | Declaração de aplicabilidade: **Registrar titular** ("Operador hipotético") e **Registrar contrato** ("Contrato local"); selecionar os dois; anexar um PDF pequeno no contrato. | Arquivo "recebido e aguardando revisão", **sem** uso para o E8. Titular e contrato **não** criam PP jurídico. |
| K-08 | Em "Como estes campos entrariam no PD". | A resposta tipada não é copiada e o PD **não** aparece como pronto. |
| K-09 | Tentar anexar arquivo grande, vazio e com extensão não aceita. | Mensagem de erro por caso; nada anexado em silêncio. |
| K-10 | Ver o diagnóstico de prontidão de cálculo (**Atualizar diagnóstico**). | "Não liberado enquanto faltarem fontes". Em projeto sem VM0038, só aparece a lacuna de metodologia (AGN-01). |
| K-11 | **Usar respostas da triagem**, **Alterar na triagem**, **Revisar metodologia na triagem**. | Cada botão leva à etapa correta, preserva o que foi preenchido e não perde rascunhos. |
| K-12 | Abrir **Consultar metodologia na Verra** e **Abrir guias da metodologia**. | Link externo abre em nova aba, sem enviar dados do projeto na URL. |
| K-13 | Guias: **Recarregar guias** → **Salvar guia** → **Consultar revisão** → **Abrir seção correspondente do PD**. | Cada guia salva revisão nova; a revisão anterior continua legível. |
| K-14 | Requisitos (`project-requirements`): abrir e usar **Tentar novamente** quando a API falha. | Lista de requisitos coerente com a visão geral; erro com saída. |
| K-15 | Abrir o mesmo projeto com `demo2`. | Sem acesso (ver W-11). |

## G. Equipe, preferências, convites e poderes

| ID | Passos | Esperado |
|---|---|---|
| G-01 | `…/experience`: "Preferências e equipe" → **Responsáveis planejados** → **Salvar preferências**. | Salvo e restaurado. Responsável planejado **não** concede poder. |
| G-02 | `…/invitation-plans`: **Novo plano de convite** → "Dados do plano" → **Salvar plano**. | Plano em rascunho; pode voltar com **Voltar aos planos**. |
| G-03 | **Enviar convite** a `demo2@carbonflow.test`. | Aparece em "Convites enviados". Sem e-mail real afirmado. |
| G-04 | Entrar como `demo2` e abrir `/workspace/invitations`. | "Convites recebidos" mostra o convite. |
| G-05 | `demo2` usa **Aceitar convite**. Depois **Abrir quantificação**, **Visão geral do projeto**, **Abrir preparação técnica**. | Acesso só ao escopo concedido. Aceitar não concede PP, PREP, REV nem VVB. |
| G-06 | Como titular, **Revogar vínculo**. Em `demo2`, atualizar. | O acesso some imediatamente; URL direta do projeto passa a ser negada. |
| G-07 | `…/authority-requests`: **Nova solicitação** → **Registrar solicitação**. | Solicitação registrada. Texto deixa claro que comprovação de poderes não é automática. |
| G-08 | **Anexar comprovante** e depois **Baixar comprovante …**. | O arquivo baixado tem o mesmo conteúdo. |
| G-09 | **Retirar solicitação?** → **Voltar**; depois → **Confirmar retirada**. | Voltar não altera. Confirmar retira, mantendo histórico. |
| G-10 | Enviar convite a e-mail inexistente e duplicado. | Erro claro, sem duplicar. |

## A. Administração da plataforma (conta admin)

Todas as telas exigem "Verificação da equipe interna" (MFA). Sem a conta admin, abra as mesmas URLs com `demo` e confirme só A-01.

| ID | Passos | Esperado |
|---|---|---|
| A-01 | Com conta **comum**, abrir `/workspace/platform/sources`, `/platform/licenses`, `/platform/grid-series`, `/platform/alias-candidates`, `/platform/fuel-catalog`, `/platform/methodologies`, `/compliance`. | "Acesso interno indisponível", ou negado. Nenhum dado de plataforma. Itens não aparecem no menu. |
| A-02 | Com conta **admin**, abrir `/compliance`. | "Confirme seu acesso" e **Desbloquear fila**. |
| A-03 | Informar código MFA errado, depois correto. | Erro tratado para o errado; liberação com o correto. Código já usado ou vencido é recusado. |
| A-04 | Com MFA liberado, navegar pelo menu: Conformidade, Fontes e prontidão, Metodologias, Licenças, Candidatos de alias, Catálogo de combustíveis, Séries da rede, **Voltar à conta**. | Todas abrem e mantêm o contexto. |
| A-05 | **Fontes** (`/platform/sources`): "Nova submissão de metadados" → preencher necessidade, edição/ano, categoria, título oficial, detentor, órgão, URL **HTTPS**, localizador, justificativa. Testar URL `http://`. | URL sem HTTPS é recusada. Submissão válida aparece em "Submissões do cenário". |
| A-06 | A mesma conta admin tenta decidir a **própria** submissão em "Decisão sobre os metadados". | **Recusado** (segregação de funções). Texto não permite aprovar a própria submissão ou arquivo. |
| A-07 | **Licenças**: "Registrar autorização" com fonte, edição, finalidade, referência, SHA-256 (64 hex) e vigência. Testar SHA com 63 caracteres e fim antes do início. | Validação por campo. Registro válido não admite a fonte nem libera cálculo. |
| A-08 | **Licenças**: "Declarar licença pública (responsável pelo produto)" com URL https, data ISO-8601, SHA-256 da captura e do texto jurídico, critério de partida e justificativa (≥ 10 caracteres). | Declaração registrada com trilha. MCTI aparece como licença pública CC BY-ND 3.0 só se declarada; nada vem preenchido por padrão. |
| A-09 | **Séries da rede**: "Importar série" com **Código da fonte**, **Edição da fonte** e JSON de importador → **Importar série candidata**. Testar sem licença registrada. | Sem licença válida, a importação é barrada (guarda de licença). Com licença, aparece em "Séries importadas" como candidata. |
| A-10 | **Séries da rede**: "Base da revisão", **Resultado**, **Motivo** (mín. 10) → **Registrar revisão**. | Revisão registrada. Motivo curto é recusado. Série revisada não vira fonte admitida automaticamente. |
| A-11 | **Candidatos de alias**: "Importar lote" com JSON válido, depois inválido. | Válido entra na fila; inválido dá erro legível. |
| A-12 | Filtrar por **Situação**, aceitar e rejeitar um candidato com **Motivo** (≥ 10) e **Cancelar** em outro. | Aceitar **não** cria modelo nem alias (contrato CF-OPEN-005). Cancelar não altera. |
| A-13 | **Catálogo de combustíveis** e **Metodologias**: abrir, ler e abrir **Fontes de …** de uma metodologia. | Conteúdo somente-leitura quando o contrato diz isso. Nenhum segundo pacote real prometido. |
| A-14 | Esperar a expiração da verificação e agir de novo. | Pede MFA de novo; não deixa ação pendente passar. |

## P. Piloto sintético (`/pilot`, README)

Dados 100 % fictícios. Totais de referência (M1 = 6,00 tCO₂e, M2 = 5,30 tCO₂e) são regressão sintética, não crédito nem fator brasileiro.

| ID | Conta | Passos | Esperado |
|---|---|---|---|
| P-01 | `SIM-PREP` | Entrar e **Executar cenário** / **Preparar amostra**. | Amostra `E8-002@1`, variantes pública e reservada, duas versões cada. |
| P-02 | `SIM-PREP` | **Ver amostras anteriores**, buscar por título. | Lista completa e busca funcional. |
| P-03 | `SIM-PREP` | Tentar **Aprovar versão** da própria amostra. | Recusado (autoaprovação). |
| P-04 | `SIM-REV` | Aprovar variante pública, versão 1, do PREP. | Aprovada. |
| P-05 | `SIM-PP` | Escolher validade e **Conceder acesso**; **Capturar recibo simulado**. | Concessão e recibo no histórico, com emitente e responsável corretos. |
| P-06 | `SIM-VVB` | **Buscar**, **Ver prévia**, **Baixar**. | Só versões com concessão vigente. |
| P-07 | `SIM-PP` | Revogar. Em `SIM-VVB`, repetir busca e abrir o endereço direto. | Bloqueado. PP continua vendo histórico e recibo. |
| P-08 | `SIM-VVB-B` | Ensaiar rotação de organismo em M2. | Conforme README; sem acesso fora do vínculo. |
| P-09 | `SIM-OP` | Tentar abrir artefatos de revisão. | Separado: OP não acessa. |
| P-10 | `SIM-PREP-OUTRA` | Abrir projeto de outra organização e URLs do piloto principal. | Recusa de URLs do piloto principal. |
| P-11 | `SIM-PREP` | **Enquadramento e inventário**: registrar triagem em Planaltina (rede aberta, infraestrutura adicional, rota de 92,3 %). | Justificativa fora do recorte exigida. |
| P-12 | `SIM-PREP` | Engajamento, reclamações e ESG com **30/06/2027**; repetir com **16/07/2027**. | 30/06 aceito; 16/07 barra por anterioridade. |
| P-13 | `SIM-PP`, `SIM-REV`, `SIM-OP` | Seguir os passos 3 a 5 do README (titularidade, compromisso 15/07/2027, medidores `SIM-MED-01/02/03`, **Pré-checar leitura**, **Consultar versões da ficha**). | Intervalos antes, durante e depois da troca de medidor; versões anteriores consultáveis. |
| P-14 | `SIM-OP` | **Importação ED/EC** → M1 → **Importar amostra em M1/M2**. Abrir **Original** e **Baixar bruto**. | "11 de 11 linhas". Bruto idêntico ao CSV. Reimportar recupera o mesmo lote. |
| P-15 | `SIM-PREP` e `SIM-REV` | Linha 3: propor e admitir (EC = 30 kWh). Linha 11: propor correção `ID@versão` e admitir (passa a 25 kWh). **Guardar seleção atual**. | Versão ativa 25 kWh; retrato salvo antes mantém 30 kWh. |
| P-16 | `SIM-PREP` e `SIM-REV` | **Cálculo das leituras admitidas**: **Propor cálculo**; REV confere **Entradas fixadas em JSON**, preenche **Motivo da revisão** e **Confirmar e calcular**. **Baixar snapshot** e **Reexecutar snapshot**. | Memória por vintage; reexecução compara com o snapshot e não diverge. |
| P-17 | `SIM-OP`, `SIM-PREP`, `SIM-PP`, `SIM-REV` | **Períodos de monitoramento** (README, passos 1 a 6) até **Fechar período**. | **6,00 tCO₂e em M1** e **5,30 tCO₂e em M2** apenas em volume limpo. |
| P-18 | `SIM-REV` | Reabrir M1 com motivo e consultar fechamento anterior e dependentes. | Fechamento anterior preservado; novo fechamento exige nova seleção e novo cálculo. |
| P-19 | `SIM-OP`, `SIM-REV` | Omitir uma linha do CSV; **Registrar lacunas**; entregar CSV de recuperação; **Comprovar recuperação**. | Lacuna aparece e depois é resolvida com o identificador do bruto. |
| P-20 | `SIM-PREP`, `SIM-REV`, `SIM-PP`, `SIM-VVB` | **Dossiês PD e MR**: preparar, salvar rascunho, comparar prévias pública e reservada, enviar para revisão, congelar, registrar exportação, baixar ZIP, conceder acesso e baixar como VVB. | ZIP com DOCX simulado e manifesto SHA-256. Parte reservada só na exposição reservada. Acesso revogado bloqueia novo download. |
| P-21 | Todas | Em qualquer ato, procurar texto que afirme registro, validação ou crédito reais. | Tudo se declara simulado. Qualquer afirmação real é FALHA. |

## X. Transversais (aplicar a cada tela visitada)

| ID | Verificação | Esperado |
|---|---|---|
| X-01 | **Teclado**: percorrer a tela só com Tab, Shift+Tab, Enter, Espaço e Esc. | Ordem lógica, foco visível, nenhum elemento inalcançável. Diálogos (Confirmar retirada, Confirmar migração) prendem e devolvem o foco. |
| X-02 | **Leitor de tela** (ou inspeção de rótulos): campos têm rótulo, erros são anunciados, botões têm nome. | Sem campo sem rótulo. Mensagem de erro associada ao campo. |
| X-03 | **Mobile 390×844**. | Sem rolagem horizontal, botões tocáveis, tabelas legíveis ou roláveis dentro de si mesmas. |
| X-04 | **Desktop 1440×900** e zoom de 200 %. | Layout íntegro, sem sobreposição. |
| X-05 | **Tema escuro** e contraste de texto, estados e erros. | Legível, consistente com o shell. |
| X-06 | **Estados de carregamento, vazio e erro**: simular API lenta e fora do ar (DevTools → Network → Offline). | Cada um tem mensagem e saída. Nenhuma tela em branco ou com `{{ ... }}`. |
| X-07 | **Duplo clique** em botões de gravação. | Um único registro. |
| X-08 | **Atualizar (F5)** no meio de um formulário e depois voltar. | Sem perda silenciosa de dados já salvos; o que não foi salvo é avisado. |
| X-09 | **Concorrência**: editar o mesmo registro em duas abas e salvar nas duas. | A segunda recebe conflito de versão, não sobrescreve. |
| X-10 | **Sessão expirada**: apagar o cookie e agir. | Volta ao login com mensagem. Nenhum dado fica visível. |
| X-11 | **Isolamento**: sessão de conta × sessão SIM × conta admin; URL de outro projeto. | Cada sessão só vê o seu. Nenhum dado `SIM-*` aparece em projeto dinâmico. |
| X-12 | **Console do navegador** e aba Network em todo o roteiro. | Sem erro JS não tratado. Nenhum segredo em URL, corpo de log ou resposta de erro. |
| X-13 | **Links**: rodar todos os links e botões de navegação de cada tela. | Nenhum 404 nem SPA em branco. Rotas diretas (`/workspace/...`) carregam com F5. |
| X-14 | **Idioma e linguagem**: português consistente; siglas (PD, MR, IR, EC, ED) explicadas pelo glossário. | Sem termo técnico sem explicação em tela de iniciante. |

## Registro de execução

Copie a tabela, uma por rodada.

| Campo | Valor |
|---|---|
| Data / testador | |
| Commit (`git rev-parse --short HEAD`) | |
| Navegador e versão | |
| Ambiente e porta | |
| Volume limpo? | sim / não |

| ID | OK / FALHA / BLOQUEADO | Evidência (captura, hora) | Issue |
|---|---|---|---|
| | | | |

## Limites

- Este roteiro é humano e não substitui CI. Não reporte "CI aprovado" a partir dele.
- Itens marcados BLOQUEADO (por exemplo T-08 sem projeto com pino antigo, ou A-* sem conta admin) devem ficar visíveis no relatório, não ser omitidos.
- Entrega real de e-mail, sessão moderada com iniciante (ENTRY-T08, UX-T17), homologação independente e dados reais autorizados seguem **fora** do escopo e continuam pendentes.
- Os resultados não alteram o estado em `specification/ESTADO_DO_TRABALHO.md` sem decisão registrada.
