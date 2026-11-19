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
