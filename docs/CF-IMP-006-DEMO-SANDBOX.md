# CF-IMP-006 — Sandbox local pronto para entrar no /workspace

Este é um **ambiente opt-in** para o desenvolvedor/dono do produto testar a home antes da CF-IMP-007. Não há credenciais pré-cadastradas no banco normal, tampouco usuários ou senhas fixos na imagem Java.

## Início em um comando

Pré-requisitos: Python 3.10+ e Docker Engine + Docker Compose. Na raiz do repositório, com a branch `feat/empty-workspace-project-selector` (ou após merge):

```bash
python3 scripts/demo_sandbox.py start
```

O comando inicia a aplicação em um Compose **isolado do piloto padrão**, escuta somente em `127.0.0.1:18088`, gera uma senha aleatória no seu computador, faz o cadastro, captura o token da caixa de testes **privada dentro do contêiner**, confirma o endereço fictício e testa login real. Ao terminar, imprime as credenciais locais e a URL. A criação de rascunhos de projetos já está habilitada na área /workspace, sem poderes PP ou aprovação metodológica.

- **Endereço:** http://127.0.0.1:18088/workspace
- **E-mail padrão:** `demo@carbonflow.test`
- **Senha:** gerada aleatoriamente durante o comando e guardada em `.carbonflow-demo/accounts/<hash>.json` com permissão `0600`; **não consta no Git**.

No primeiro acesso, a home mostra **Você ainda não tem projetos**. Clique em **Criar meu primeiro projeto** e informe o nome de trabalho da organização. A criação desse espaço é real, salva no PostgreSQL, mas os projetos serão criados somente a partir da CF-IMP-007.

Para rever as credenciais, sem trocar a senha:

```bash
python3 scripts/demo_sandbox.py show
```

Para adicionar outra conta de teste (outra identidade, mesmo banco demo, sem pertencer à organização da primeira):

```bash
python3 scripts/demo_sandbox.py add-user --email demo2@carbonflow.test --name "Segundo usuário"
python3 scripts/demo_sandbox.py show --email demo2@carbonflow.test
```

A primeira conta poderá criar **um** espaço inicial privado; a segunda também poderá criar o seu. As sessões e vínculos são isolados e os projetos alheios não devem aparecer.

Para validar o ambiente usando **requisições HTTP reais** ao backend:

```bash
python3 scripts/demo_sandbox.py smoke
```

Para parar somente o ambiente demo, **preservando banco e credenciais**, execute:

```bash
python3 scripts/demo_sandbox.py stop
```

Para apagar **exclusivamente** os dados do demo (destrutivo; não executar sobre ambientes compartilhados), primeiro pare o ambiente e então:

```bash
docker compose -p carbonflow-demo -f compose.yaml -f compose.demo.yaml down -v --remove-orphans
rm -rf .carbonflow-demo
```

O script aceita `CARBONFLOW_PORT=18089` para modificar a porta do demo e `CARBONFLOW_DEMO_PROJECT=carbonflow-demo-lab` para outro namespace, apenas com nomes que iniciem em `carbonflow-demo` ou `cf-demo`.


## Diagnóstico e recuperação local sem apagar dados

Para verificar **sem modificar nada** se o daemon Docker está ativo, se o plugin Compose está disponível, quais serviços existem no projeto de demonstração, sua porta e a saúde da API, execute na raiz do repositório:

```bash
python3 scripts/demo_sandbox.py doctor
```

A demonstração de contas e projetos é aberta em `http://127.0.0.1:18088/workspace`; o Compose padrão abre o piloto sintético em `http://127.0.0.1:8080/pilot`. Eles usam bancos e contas separados. Se a porta 18088 estiver ocupada, use:

```bash
CARBONFLOW_PORT=18089 python3 scripts/demo_sandbox.py start
CARBONFLOW_PORT=18089 python3 scripts/demo_sandbox.py doctor
```

Para consultar logs locais dos serviços sem destruir o volume:

```bash
docker compose -p carbonflow-demo -f compose.yaml -f compose.demo.yaml ps
docker compose -p carbonflow-demo -f compose.yaml -f compose.demo.yaml logs --tail 80 app db
```

Se o download de dependências Maven falhar (ex.: HTTP 429), o Dockerfile tenta um mirror alternativo mantendo as mesmas versões. O inicializador usa a saúde exigida pelo Compose e confirma o acesso pelas APIs reais de cadastro e sessão. A consulta auxiliar de `/health/ready` fica no comando `doctor`, sem bloquear o provisionamento. Se **já existe uma imagem construída anteriormente** e é necessário somente voltar a trabalhar, você pode usar:

```bash
python3 scripts/demo_sandbox.py start --reuse-image
```

Isso evita compilar novamente, mas **não inclui mudanças recentes do código**. Para aplicar UX-07, execute o comando de `start` normal assim que o acesso às dependências estiver disponível.

O `doctor` não apaga bancos, volumes, contas ou senhas. Não use `docker compose down -v` nem remova `.carbonflow-demo` como tentativa genérica de reparo. Se houver problemas de autenticação após restabelecer o banco, confira a senha do PostgreSQL configurada na criação do volume e os arquivos locais de credenciais. Ao compartilhar logs, retire segredos e tokens.

### Flyway: checksum da migração V69 divergente

Esse erro foi identificado pelos checksums históricos:

| Versão da migração V69 | Checksum |
|---|---:|
| Original, aplicada em sandboxes existentes | -1778044537 |
| Alterada indevidamente em um commit posterior | -639127919 |

**Não execute `flyway repair`, não altere manualmente `flyway_schema_history` e não remova os volumes.**

A correção restaura a V69 original sem modificar seu checksum. A migração **V69.1** adiciona o campo `submitted_file_name` aos vínculos de provas e preenche os registros existentes com o nome do arquivo já registrado no repositório de PDFs criptografados. Como a V69 bloqueia UPDATEs nos vínculos, a V69.1 suspende somente o gatilho de imutabilidade durante o backfill transacional e o reativa antes de concluir. A V70 permanece reservada ao ponto de retomada da UX-07.

Após atualizar o código com o PR #143 (no próprio branch de teste ou depois do merge), use o fluxo normal do sandbox:

```bash
python3 scripts/demo_sandbox.py start
```

Se o ambiente for o Compose padrão, e não o sandbox de contas:

```bash
docker compose up -d --build --wait
```

Para confirmar o histórico Flyway mesmo que o aplicativo não suba, no Compose de demonstração padrão:

```bash
docker compose -p carbonflow-demo -f compose.yaml -f compose.demo.yaml up -d db
docker compose -p carbonflow-demo -f compose.yaml -f compose.demo.yaml exec -T db psql -U carbonflow -d carbonflow -c "SELECT version, description, checksum, success FROM flyway_schema_history WHERE version IN ('69','70','71') ORDER BY installed_rank;"
```

O resultado esperado é a **V69 com checksum -1778044537**, a V70 quando a UX-07 estiver instalada, e a **V69.1 aplicada com sucesso**. Se você utiliza outro `CARBONFLOW_DEMO_PROJECT`, substitua `carbonflow-demo` pelo nome configurado. Para o Compose padrão, utilize `docker compose exec -T db ...` sem `-p/-f`.

Se um banco apresentar um checksum V69 diferente de -1778044537, interrompa a tentativa e inspecione o esquema e um backup daquele banco; não execute reparação automática do histórico.

## O que não é simulado

O cadastro usa **as rotas reais** `POST /v1/accounts`, `POST /v1/accounts/email-verification`, `POST /v1/accounts/email-confirmations` e `POST /v1/account-session`, com senhas PBKDF2 gerenciadas pelo backend. A conta nasce `ACTIVE` após confirmação do token, com sessão HttpOnly e limite de tentativas da CF-IMP-003. Não há usuário administrativo especial, grants PP/REV/VVB nem permissão para aprovar documentos.

O e-mail nunca é enviado para a internet: a caixa de teste é o `SandboxAccountMailbox` em um volume **tmpfs do contêiner** `/run/carbonflow-demo-inbox`, com permissão `0700` para o usuário da aplicação. Somente endereços fictícios `.test` podem ser provisionados pelo script. Tokens não são impressos nem gravados no repositório.

O arquivo de senhas locais está no `.gitignore`. A execução `--quiet` não exibe senha no terminal (uso recomendado para CI). Nunca exponha a porta pela internet, suba em hospedagem pública ou reutilize esta configuração em produção; trata-se de ensaio **local**.

## Homologação da entrega

- `python3 -m unittest discover -s scripts -p 'test_demo_sandbox.py' -v`: emails fictícios, isolamento do namespace, segredo aleatório, arquivo 0600/0700, estabilidade em reinício.
- Job `Demo sandbox / provisionamento real de contas` do GitHub Actions: provisiona primeira conta e segunda conta; usa API e cookies reais; repete o provisionamento sem mudar senha; destrói somente o Compose isolado.
- Job `Quarkus / integration tests`: regressões de cadastro/confirmação/organização.
- Job `Angular / browser journeys`: home vazia, criação da organização, troca de seleção e login da conta.
- Modo padrão `docker compose up` continua com cadastro **desabilitado**. Para testar o piloto antigo, use `/pilot` e `SIM-PREP` / `piloto-prep` no ambiente padrão.

## Limites atuais

A CF-IMP-006 não adiciona formulário público de inscrição. A preparação local é feita pelo script. O cadastro de produção ainda necessita de provedor de e-mail, controles antiabuso e infraestrutura adequados. A CF-IMP-007 entregará a criação persistente do projeto e vinculação ao DRAFT_OWNER. A interface continua no sandbox com dados sintéticos para qualquer parte E8.
