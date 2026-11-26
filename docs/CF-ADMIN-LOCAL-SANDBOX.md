# Admin local de demonstração — CarbonFlow

Este comando cria **uma conta administrativa somente no sandbox local**, usando cadastro por API, confirmação na caixa temporária do sandbox, senha gerada aleatoriamente e MFA TOTP aleatório. O painel interno é restrito ao endereço loopback (127.0.0.1). Ele **não cria nenhum administrador no deploy remoto nem ignora autenticação/aceite do usuário**.

## Primeiro acesso
Na raiz do repositório:
```sh
python3 scripts/admin_sandbox.py setup
python3 scripts/demo_sandbox.py show --email admin@carbonflow.test
python3 scripts/admin_sandbox.py code
```
A interface de gestão fica em `http://127.0.0.1:18088/workspace/platform/sources`. Informe a senha obtida localmente ao fazer login; após entrar, informe o **código de seis dígitos** gerado por `admin_sandbox.py code` na verificação da equipe. Gere um novo código a cada janela de 30 segundos. Nunca coloque senha, código ou semente MFA em issue, PR ou registro de trabalho.

## Como funciona
- `scripts/demo_sandbox.py` provisiona `admin@carbonflow.test` **pelo fluxo normal**, nunca por INSERT direto de credenciais;
- cria uma organização do sandbox associada à identidade se ainda não existir;
- `admin_sandbox.py` gera chave TOTP de 20 bytes e armazena apenas em `.carbonflow-demo/admin-roster.json` (0600, já excluído do Git);
- `demo_sandbox.compose` repassa o roster e os IDs dos escopos internos de fontes via variáveis de ambiente apenas a este projeto Compose;
- recarrega apenas o container `app`, mantendo volume e contas.
- Segregação de funções continua em vigor: esse administrador pode **consultar**, **cadastrar** e ser **configurado como revisor**, mas **NÃO pode aprovar suas próprias submissões ou arquivos**. Um segundo staff com MFA e grants explícitos deve revisar.
- Nenhum acesso de PP/PREP/REV/VVB nem autorização de direitos reais de conteúdo é concedido. A conta não acessa projetos de outros titulares.

Se `.carbonflow-demo/admin-roster.json` existir, o provisionamento recusa mudar o ator/organização silenciosamente; não regrava a semente. Pare só o demo com `python3 scripts/demo_sandbox.py stop`. Para produção, use um provedor de identidade e política de convite/admin reais — **não use este fluxo de contas `.test`**.

O servidor **não é iniciado pelo ChatGPT**; o comando deve ser executado no terminal com Docker instalado. As credenciais não foram geradas na máquina remota nem podem ser entregues no chat com segurança.

## Papéis do responsável pelo produto (D19/D20)
A conta `admin@carbonflow.test` recebe, **só no sandbox local**, os papéis de **responsável**: declarar licença pública (`content-licenses.owners`), cadastrar e revisar de forma **interna** o catálogo de combustíveis (`fuel-catalog.managers|owners`) e importar e revisar de forma **interna** as séries da rede (`grid-series.managers|owners`). **Não** recebe papel de aprovador de licenças, submissor, revisor independente do catálogo nem das séries: a dupla custódia não é simulada, e a declaração interna nunca vale como aprovação independente.

Depois de `python3 scripts/admin_sandbox.py setup` (ou de reiniciar o `app` com o roster existente), para registrar a licença pública do MCTI:
1. Entre com a conta, desbloqueie com o código de `admin_sandbox.py code` e abra `/workspace/platform/licenses`.
2. Em "Declarar licença pública", preencha com os valores de `docs/data/mcti-license-declaration.json` (fonte `MCTI-SIN-DESPACHO`, versão `2026-janout-7f8e2292`, as duas SHA-256, a captura `2026-11-25T23:44:00Z`, início = captura, e as quatro operações com seus fundamentos). **Antes**, a fonte precisa existir: ela é semeada pela V112 em qualquer banco migrado.
3. Gere um novo código para cada nova verificação (o código não pode ser reutilizado).
