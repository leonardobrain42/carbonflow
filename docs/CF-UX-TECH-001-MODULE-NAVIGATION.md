# CF-UX-TECH-001 — Preparação técnica como espaço de trabalho

## O que mudou

A antiga barra horizontal de seções passou a ser um **navegador persistente de módulos** com quatro agrupamentos (Começar, Fundamentação, Medição, Documentação) e um **Painel de trabalho** inicial. Os links existentes por `?secao=` são mantidos, e todas as opções são verificadas pelas permissões existentes em `/v1/projects/{id}/technical/capabilities`.

A navegação estável não inventa uma nova autorização: a UI não cria grants; a API continua restringindo todos os comandos por projeto, papel, escopo, ambiente e origem do dado.

### Como testar com uma conta que já tem projeto

No navegador, substitua `PROJECT_ID` pelo identificador do seu projeto:

| Tela | Link a partir de `/workspace/projects/PROJECT_ID/technical` |
|---|---|
| Painel e próximos passos | `?secao=inicio` (também é o padrão para titulares com acesso a intake) |
| Dados iniciais | `?secao=preparar` |
| Situação do projeto (leitura; atualizada em [APPLICABILITY-SCREENS](CF-UX-TECH-001-APPLICABILITY-SCREENS.md)) | `?secao=analisar` |
| Acesso à rede | `?secao=rede` |
| Direitos do projeto | `?secao=direitos` |
| Guia de linha de base | `?secao=linha-base` |
| Guia de adicionalidade | `?secao=adicionalidade` |
| Inventário de carregadores/medidores | `?secao=monitorar&etapa=inventario` |
| Lotes, leituras e ocorrências | `?secao=monitorar&etapa=leituras` |
| Reconciliação, QA e evidências | `?secao=monitorar&etapa=qa` |
| Relatório de monitoramento, fator elétrico, VT0011 e TOOL07 | `?secao=monitorar&etapa=dossies` |
| Evidências de snapshots, com acesso rápido ao PD | `?secao=evidencias` |
| Equações, requisitos e gates para BE/PE/LE/ER | `?secao=quantificar` |
| Fontes ambientais e fatores com orientação sobre perfis staff | `?secao=fontes` |
| Guias, histórico e coleta de fontes | `?secao=guias` |
| Preparação do documento do projeto | `?secao=redigir` |
| Revisão documental | `?secao=revisar` |

Não há rotas ocultas: os valores são parâmetros da rota já existente, servidos pela mesma aplicação Angular. Ao mudar `?secao` ou `?etapa`, dados do projeto e revisão permanecem no backend. URLs legadas `?secao=entender/preparar/analisar/monitorar/guias/redigir/revisar` continuam válidas.

### O que o usuário encontrará

- **Painel inicial:** estágio do cadastro técnico, metodologia candidata, número de requisitos e acesso direto às frentes autorizadas.
- **Linha de base:** guia canônico `baseline`, referências, decisões e evidências; acesso ao diagnóstico das dependências e ao MR.
- **Adicionalidade:** guia canônico `additionality`, com evidências e cronologia; sem atribuir aprovação de adicionalidade ao preenchimento.
- **Monitoramento dividido em quatro telas:** mesma instância do componente e mesmas APIs anteriores, mas cada etapa apresenta apenas o conteúdo necessário. A vista `all` continua como compatibilidade para as áreas de acesso restrito que já usam o componente.
- **Evidências:** vista de QA e comprovantes dos snapshots, sem substituir conferência de direitos, revisão normativa ou VVB.
- **Quantificação:** plano das equações e gates de admissão, distinguindo preparado, obsoleto e admitido; não chama execução E8.
- **Fontes:** explica que a seleção EVR/PBEV, fatores fósseis EFfuel e o catálogo de evidências nacionais pertencem ao painel administrativo com MFA. A URL staff é mostrada explicitamente, mas a autenticação não é contornada.
- **Documento e revisão:** componentes PD existentes, com seus próprios versionamentos.

## Decisões de segurança e compatibilidade

1. **Sem permissões ampliadas.** `sections()` apresenta apenas módulos cujo grant já consta no objeto de `capabilities`. Ação do cliente nunca substitui validação da API.
2. **Metodologia.** Os guias de baseline e adicionalidade específicos são renderizados apenas para projeto com candidata VM0038. Para as demais metodologias, mensagens explícitas de indisponibilidade são mostradas.
3. **Nada de admissão fictícia.** `PREPARATORY_NOT_ADMITTED` e dados sintéticos não geram BE, PE, LE, ER ou VCU. A interface informa isso nos pontos de decisão. O menu chama o piloto de exemplo separado. Quantificação e monitoramento do projeto não apontam a memória nem os lotes sintéticos como se fossem o dado da etapa.
4. **Dados persistidos.** A mudança da aba usa apenas query params; não altera respostas, banco, migrações ou regras de revisão. Documentos e snapshots continuam rastreáveis pela API original.
5. **Modo responsivo.** Navegador de módulos com foco visível e `aria-current`; a barra de etapas de monitoramento também funciona por teclado e pode ser copiada como URL. Em celulares, a navegação é compacta e rolável.
6. **Rascunhos não salvos.** A proteção `canLeaveGuides()` continua ativa quando o usuário navega entre módulos para evitar perda silenciosa de dados.

## Critérios de aceite

- Abrir o projeto conduz ao painel inicial; as sete seções legadas continuam navegáveis e compatíveis com links salvos.
- A navegação é estável ao alternar projeto/guia/etapa, sem o efeito de menus que mudam arbitrariamente.
- Links para `?secao=monitorar&etapa=leituras` e `...&etapa=qa` sobrevivem a reload, expõem apenas o bloco apropriado e mantêm `aria-current` coerente.
- Quem não tem grant continua sem acesso ao módulo ou comando; quem tem acesso de monitoramento consegue consultar a API existente, não dados de outro projeto.
- A página de fontes administrativas é identificada claramente; o acesso requer as credenciais específicas já existentes.
- Angular build, testes de navegador, backend Quarkus e sandbox aprovados.

## Próximas fatias de #124, não cobertas por esta PR

A entrega realiza **navegação, organização e descoberta**. Ainda faltam: schema de formulário tipado e versionado por metodologia, editor de estações/ativos totalmente guiado, vínculo de papéis PP a contas e poderes, prova contextual por campo, composição do PD a partir dos campos canônicos e UX de progressão condicional com estados de review. Isso não deve ser apresentado como concluído com a simples mudança de menu.

## Atualização (24/11/2026): menu em cinco grupos
- Grupos: **Começar** (Painel de trabalho, Preparar informações), **Aplicabilidade** (Situação do projeto, Acesso à rede, Direitos do projeto, Estações e fronteira), **Fundamentação** (Linha de base, Adicionalidade, Guias técnicos), **Medição** (Monitorar energia, Quantificação) e **Documentação** (Redigir, Revisar).
- **Entender**, **Evidências** e **Fontes e parâmetros** deixam de ocupar o menu, mas continuam com URL própria (`?secao=entender|evidencias|fontes`) e com acesso pela tela-mãe: o Painel de trabalho tem o cartão "Entender a metodologia"; Evidências é a mesma vista do Monitorar, etapa 3 (QA e evidências); Quantificação tem o botão "Fontes e parâmetros nacionais". Com uma delas aberta, o item da tela-mãe fica destacado no menu.
- Se a conta não tiver acesso à tela-mãe, a tela continua no menu (nenhum acesso é perdido). Permissões, ponto de retomada e destinos de pendências não mudam.
