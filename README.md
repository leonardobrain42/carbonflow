# CarbonFlow · PIL-SYN-BR-001

PIL-SYN-BR-001 · demonstração local com a massa sintética do kit 0.2.14. As E8-001/002 cobrem cálculo de referência e autorização. A E8-003 registra o enquadramento e o inventário. A E8-004 importa e revisa ED/EC. A E8-005 calcula BE, PE, LE e ER a partir de uma seleção salva. A E8-006 opera M1 e M2, confere cobertura e conserva fechamentos e reaberturas. A E8-007 gera, congela e exporta dossiês PD/MR por ato. A E8-008 acompanha listagens, canal manual e consulta pública simulada. A E8-009 registra auditorias do VVB e pedidos REG, V1 e V2. O Angular e a API usam a mesma origem e o PostgreSQL guarda as execuções e a trilha.

## Especificação viva e contribuições

Comece pela [especificação viva](specification/README.md), [estado do trabalho](specification/ESTADO_DO_TRABALHO.md), [matriz de rastreabilidade](specification/MATRIZ_RASTREABILIDADE.md) e [governança](specification/GOVERNANCA.md). O README abaixo preserva o roteiro do piloto sintético; não descreve sozinho o produto dinâmico OPEN. O kit histórico 0.2.14 não foi recuperado integralmente; ver [E0](specification/e0/README.md). A especificação documental deve evoluir no mesmo PR de alterações funcionais, sem substituir contratos, fontes ou evidências.

## Executar com Docker Compose

Com o Docker Engine ativo e o plugin Docker Compose instalado, execute na raiz do projeto. Para problemas de inicialização do sandbox de contas, execute `python3 scripts/demo_sandbox.py doctor` e consulte o [guia de diagnóstico](docs/CF-IMP-006-DEMO-SANDBOX.md#diagnóstico-e-recuperação-local-sem-apagar-dados).

```sh
docker compose up --build -d --wait
```

Abra [a demonstração local](http://127.0.0.1:8080/), entre como `SIM-PREP` e clique em **Executar cenário**. O Compose compila o Angular e o Quarkus, aguarda o PostgreSQL e publica a interface e a API no mesmo endereço. O banco usa um volume persistente e fica acessível pela rede interna do Compose.

```sh
docker compose logs -f app
docker compose down
```

`docker compose down` mantém o volume e as execuções. Se a porta 8080 estiver em uso, inicie com `CARBONFLOW_PORT=18080 docker compose up --build -d --wait` e abra [a porta alternativa](http://127.0.0.1:18080/). Para escolher outra senha local, defina `CARBONFLOW_DB_PASSWORD` antes da primeira inicialização do volume.



## Demo local: entrar no /workspace sem configurar e-mail real

A **CF-IMP-006** possui um inicializador de demo que cria uma conta por **cadastro e confirmação reais da API**, com senha **aleatória local** e banco separado do piloto. Este comando não muda a autenticação nem embute credenciais no código:

```sh
python3 scripts/demo_sandbox.py start
```

Abra **http://127.0.0.1:18088/workspace** e use o e-mail `demo@carbonflow.test` e a senha exibida no terminal. Para consultar novamente: `python3 scripts/demo_sandbox.py show`. Para criar outra conta fictícia: `python3 scripts/demo_sandbox.py add-user --email demo2@carbonflow.test`. Para parar mantendo dados: `python3 scripts/demo_sandbox.py stop`.

Este modo usa `compose.demo.yaml`, é acessível apenas em `127.0.0.1`, guarda senhas localmente fora do Git e não concede poderes PP/REV/VVB. A conta pode criar rascunhos de projetos no `/workspace`; não cria créditos. Veja [a documentação de segurança e diagnóstico](docs/CF-IMP-006-DEMO-SANDBOX.md).

## Admin local de sandbox (conta separada do projeto SIM)

Para criar uma conta local `admin@carbonflow.test`, com senha aleatória e MFA TOTP,
execute `python3 scripts/admin_sandbox.py setup`. O comando usa cadastro real e
recria apenas o container da aplicação com permissões internas de fontes, sem
desligar o PostgreSQL. Consulte a senha no terminal com
`python3 scripts/demo_sandbox.py show --email admin@carbonflow.test`
e gere o código de MFA com `python3 scripts/admin_sandbox.py code`.
Painel: `http://127.0.0.1:18088/workspace/platform/sources`.

Esta conta é **local**, não existe automaticamente no deployment remoto,
não recebe PP/PREP/REV/VVB e não pode revisar suas próprias submissões.
Detalhes: [docs/CF-ADMIN-LOCAL-SANDBOX.md](docs/CF-ADMIN-LOCAL-SANDBOX.md).

## Demonstrar autorização

As credenciais abaixo pertencem às contas fictícias da demonstração local.

| Identidade | Senha de demonstração | Ação no ensaio |
|---|---|---|
| `SIM-PREP` | `piloto-prep` | Executar o cenário e preparar uma amostra de artefatos. |
| `SIM-REV` | `piloto-rev` | Aprovar versões preparadas por outra pessoa e consultar a trilha. O vínculo também permite preparar e testar a guarda de autoaprovação. |
| `SIM-PP` | `piloto-pp` | Conceder à equipe VVB uma versão aprovada até a data escolhida. Capturar recibo simulado, consultar o histórico e revogar. |
| `SIM-VVB` | `piloto-vvb` | Buscar, abrir a prévia e baixar as versões com concessão vigente. |
| `SIM-VVB-B` | `piloto-vvb-b` | Ensaiar a rotação de organismo em M2. |
| `SIM-OP` | `piloto-op` | Verificar a separação entre operação e acesso aos artefatos de revisão. |
| `SIM-PREP-OUTRA` | `piloto-prep-outra` | Ensaiar um projeto de outra organização e a recusa de URLs do piloto principal. |

1. Entre como PREP e clique em **Preparar amostra**. Cada execução da amostra `E8-002@1` cria variantes pública e reservada, cada uma com duas versões sintéticas para testar o escopo da concessão. A lista inicial mostra a amostra mais recente visível ao seu papel. Use **Ver amostras anteriores** ou a busca por título para acessar as demais. A prévia abre junto à versão selecionada.
2. Saia e entre como REV. Na variante pública de versão 1 preparada pelo PREP, clique em **Aprovar versão**.
3. Entre como PP. Escolha a validade e clique em **Conceder acesso** na versão aprovada. Na concessão, **Capturar recibo simulado** registra SIM-VVB-A como emitente e SIM-PP como responsável pela captura.
4. Entre como VVB e use **Buscar**, **Ver prévia** e **Baixar**. Após o PP revogar a concessão, repetir a busca ou abrir diretamente o mesmo endereço como VVB confirma o bloqueio. O PP conserva a concessão e o recibo no histórico.

As operações usam cookie de sessão `HttpOnly` com `SameSite=Strict`. A API consulta os vínculos vigentes no servidor. Para usar a API fora do navegador, autentique-se com `POST /v1/session`, enviando JSON com `identity` e `password`, e conserve o cookie nas requisições seguintes. A utilidade de prontidão do Compose é `GET /health/ready`.

## Demonstrar enquadramento e inventário

Abra **Enquadramento e inventário** com as mesmas contas fictícias. O roteiro começa em uma ficha sem triagem.

1. Como **PREP**, selecione **Planaltina · recorte do piloto** e registre a triagem. Rede aberta, infraestrutura adicional e a rota de 92,3% permitem exercitar a justificativa fora do recorte.
2. Como **PREP**, registre engajamento, mecanismo de reclamações e ESG com data de 30/06/2027. Registre também a incidência justificada de FPIC e repartição. As opções de 16/07/2027 permitem testar a guarda de anterioridade.
3. Como **PP**, registre titularidade e operação, excedente regulatório e pesquisa de sobreposição. Entre como **REV** para revisar a triagem e conferir as edições usadas para o ato sintético.
4. Como **PP**, registre o compromisso significativo de 15/07/2027. Como **OP**, registre os medidores `SIM-MED-01` e `SIM-MED-02` com calibração e confirme a operação e o início do crédito em 01/10/2027.
5. Como **OP**, registre `SIM-MED-03` para substituir `SIM-MED-01` em 01/04/2028. Em **Pré-checar leitura**, compare os intervalos antes, durante e após a troca e a leitura após o fim da calibração. **Consultar versões da ficha** abre as versões anteriores.

A massa `E8-003@1` fixa as provas sintéticas e a configuração de Planaltina do kit. A API permite registrar candidatos dentro do envelope. O enquadramento só avança com prova que sustente o contorno da amostra. A pré-checagem preserva intervalos e kWh e verifica cobertura. A admissão de observações e a importação operacional seguem na E8-004. As pendências de uso real aparecem na própria ficha.

O estado corrente está em `GET /v1/projects/{project}`. Use `/intake/examples` para consultar a massa, `/intake/history` para listar revisões e `/intake/versions/{version}` para recuperar uma ficha. As alterações exigem `Idempotency-Key` e a versão corrente em `If-Match`, começando por `"0"`. As pré-checagens EC registram observações de origem independentes e vinculam a versão da ficha consultada.

## Demonstrar importação e revisão ED/EC

1. Conclua o comissionamento da E8-003. Como **OP**, abra **Importação ED/EC**, selecione **M1** e clique em **Importar amostra em M1/M2**. Confira **11 de 11 linhas**. Abrir **Original** exibe a linha recebida. **Baixar bruto** devolve os bytes do CSV.
2. Como **PREP**, abra **Lotes recentes**, selecione o lote e use **Revisar linha 3**. Escolha **Propor admissão**, registre o motivo e confirme. Como **REV**, abra a mesma linha, selecione **Admitir proposta** e informe o motivo. A observação inicial de EC vale 30 kWh. **Guardar seleção atual** guarda a versão admitida naquele momento.
3. Como **PREP**, revise a linha 11 e proponha a correção selecionando ou digitando a referência `ID@versão` da observação anterior. Como **REV**, admita a proposta. A versão ativa passa a 25 kWh, e o retrato anteriormente salvo continua com 30 kWh. Reimportar a amostra recupera o mesmo lote.

Use apenas dados sintéticos. O mapeamento v1 usa o cabeçalho da E6, vírgula, UTF-8, um registro por linha e deslocamento de fuso explícito. Aceita Wh, kWh e MWh com normalização decimal em kWh e preserva os campos originais. A coluna opcional `coverageEvidence` permite referenciar prova de zero da amostra.

Na API, `POST /artifacts` recebe os bytes CSV com `X-File-Name` codificado para URL, `X-Source-Id` e `X-Source-Authorization`. `POST /imports` recebe referências versionadas ao artefato, ao mapeamento e ao período. Os relatórios e a prévia paginada estão em `GET /imports/{id}` e `GET /imports/{id}/rows`. As ações do PREP e do REV usam `POST /observations/{id}/actions` com a versão da linha em `If-Match`. Todos os caminhos ficam sob `/v1/projects/{project}`. A seleção versionada alimenta o cálculo e a revisão de cobertura.

## Demonstrar cálculo das leituras admitidas

1. Na **Importação ED/EC**, como **REV**, use **Guardar seleção atual** no período escolhido depois de admitir as leituras.
2. Entre como **PREP**. Em **Cálculo das leituras admitidas**, selecione o período e o retrato salvo e clique em **Propor cálculo**. A lista pode ser recarregada com **Atualizar cálculos e seleções**.
3. Entre como **REV**, selecione a proposta e abra **Entradas fixadas em JSON** para conferir a ficha, o fator EF-SYN-v1, a linha de base e a prova de Dy. Preencha **Motivo da revisão** e clique em **Confirmar e calcular**.
4. Compare a memória por vintage, consulte **Baixar snapshot** ou use **Reexecutar snapshot**. O roteiro da E8-004 salva um retrato com EC e sem ED. Ele mostra PE onde há consumo e mantém ER em aberto. Uma seleção com ED e EC em cada vintage produz a memória do que foi selecionado. Confira a cobertura integral e registre o fechamento no painel **Períodos de monitoramento**.

Em **Fatores e versões**, PREP pode cadastrar candidatos sintéticos com origem, rede e anos-fonte, e REV pode registrar a exclusão de uma versão. O catálogo mantém o EF-SYN-v1 fixado. A fonte sintética do kit não tem séries anuais de origem. Candidatos não alteram execuções anteriores.

Na API, `POST /runs` recebe `proposal` com `period`, referências versionadas de `selection`, `baseline`, `factor` e `dy`. `POST /runs/{id}/actions` confirma a proposta com `If-Match` e agenda o cálculo. `POST /runs/{id}/reexecute` compara a memória salva com o cálculo do snapshot. Todos os caminhos ficam sob `/v1/projects/{project}`.

## Demonstrar períodos de monitoramento

Para reproduzir os totais de referência de ponta a ponta, use um volume de ensaio limpo. Os CSVs de cobertura ocupam toda a janela por canal e, por isso, podem sobrepor leituras de outros roteiros. Para abrir uma instância independente, execute `CARBONFLOW_PORT=18086 docker compose -p carbonflow-ensaio up --build -d --wait` e abra [o ensaio separado](http://127.0.0.1:18086/).

1. Conclua o [comissionamento](#demonstrar-enquadramento-e-inventário). Como **OP**, registre também **SIM-MED-04** com calibração, vigente a partir de 01/10/2028 para o circuito **SIM-EC-01** em M2. Faça isso antes de gerar as memórias para fechamento.
2. Como **PREP** ou **PP**, em **Períodos de monitoramento**, selecione M1 e clique em **Propor M1**. Repita com M2.
3. Como **OP**, selecione M1, confira a ação **Abrir coleta** e registre a transição em 01/10/2027 às 00:00. No corte de 01/10/2028 às 00:00, registre **Encerrar coleta** em M1 e **Abrir coleta** em M2. M1 fica em conferência enquanto M2 coleta. A interface preenche os instantes do cenário com deslocamento de Brasília.
4. Em cada janela, baixe **CSV de cobertura M1/M2**. Como **OP**, importe o arquivo no período correspondente em **Importação ED/EC**. O **PREP** propõe cada linha e o **REV** admite e guarda a seleção. Gere e revise a [memória de cálculo](#demonstrar-cálculo-das-leituras-admitidas). Em M2, o OP encerra a coleta no corte de 01/10/2029.
5. Como **REV**, em **Períodos de monitoramento**, selecione a memória revisada. Clique em **Conferir cobertura**, depois escolha **Fechar período** e registre o motivo. A massa cobre os dois carregadores ED e os dois circuitos EC durante as respectivas janelas. Os totais congelados devem ser **6,00 tCO₂e em M1** e **5,30 tCO₂e em M2**.
6. Com M1 fechado e M2 coletando, o **PP** pode abrir **Vincular dependente simulado** e registrar **V1 em revisão** e **I1 aguardando depósito**. Para ensaiar divergência, vincule também a prova simulada de aprovação de V1. Como **REV**, reabra M1 com motivo e consulte o fechamento anterior, os dependentes e, quando houver prova, a referência de divergência. O fechamento sucessor exige nova seleção e novo cálculo.

Para ensaiar uma lacuna, omita uma linha do CSV e confira a cobertura do recorte. O REV pode usar **Registrar lacunas**. Depois que o OP entregar o CSV de recuperação e a linha for admitida, o PREP usa **Comprovar recuperação** com o identificador do bruto. O identificador aparece em **Identidade do bruto**, na prévia da importação.

Uma leitura que cruza uma fronteira fica pendente. O OP pode fornecer linhas medidas nos recortes correspondentes e usar **Vincular segmentos medidos**, com as referências `ID@versão` exibidas em **Original** na prévia de cada linha. O servidor confere contorno, contiguidade e soma em kWh. PREP e REV seguem com a admissão de cada segmento.

O histórico e a elegibilidade do fechamento são consultáveis via `GET /periods/{period}/history` e `GET /periods/{period}/closures/{revision}`, sob `/v1/projects/{project}`. A resposta do fechamento traz `X-Closure-SHA256` e `X-Eligible-For-New-Use`. A transição usa `Idempotency-Key` e `If-Match`. As [evidências da E8-006](evidencias/e8-006/VERIFICACAO.md) registram os testes e as pendências de homologação.

## Demonstrar dossiês PD e MR

Abra **Dossiês PD e MR**. A tela usa o enquadramento já revisado, o compromisso e as evidências da ficha. Para PD completo ou MR, conclua também o comissionamento e registre **ODS** em **Evidência sintética** na ficha, como **PREP**.

1. Como **PREP**, clique em **Preparar dossiê**. Para o primeiro ensaio, mantenha **PD indicativo · desenvolvimento** com data de **02/08/2027**. Preencha as incidências de acordo de comunicação, tradução e representação de listagem e justifique cada decisão. É possível selecionar as peças sintéticas identificadas no checklist. Na hipótese simples do ensaio, a atuação é direta e o documento é gerado em inglês, com justificativa registrada. A incidência de representações em atos reais segue pendente em PEN-011.
2. Salve o rascunho. Compare **Ver prévia pública** com **Ver prévia reservada**. Para exercitar a separação, inclua uma nota fictícia na parte reservada do rascunho. Na seção de revisão, escolha **Enviar para revisão** e informe o motivo. Cada item pendente aparece na ficha do dossiê e bloqueia essa etapa. Use **Preparar sucessora** para corrigir o rascunho.
3. Entre como **REV**. Selecione a versão recebida e escolha **Congelar dossiê**. A devolução exige item, responsável e correção esperada. Uma revisão após devolução ou ajuste vincula a anterior e exige motivo e peça de resposta da rodada. A nova revisão gera versões limpas e comparadas por exposição.
4. Entre como **PP**. Escolha **PUBLICA · LIMPA** para o registro simulado. Para entrega ao VVB simulado, escolha a exposição e a edição adequadas. Clique em **Registrar exportação** e depois baixe o ZIP em **Exportações do dossiê**. Para ensaiar o recibo, use código e prova com prefixo `SIM-` e uma data coerente com o ato. A tela vincula a exportação selecionada, a versão, o destino e o SHA-256 do ZIP.
5. Para PD completo, repita o roteiro escolhendo **listagem em validação** ou **registro**. O checklist muda e pede mapa, peças de representação, contrato ou parecer conforme o ato. Para MR, congele primeiro o PD de registro com as peças simuladas de validação e conclua o [fechamento do período](#demonstrar-períodos-de-monitoramento). Escolha o PD congelado e o M1 ou M2. A variante reservada do MR conserva os bytes do fechamento e do snapshot do cálculo.
6. Para conferir acesso seletivo, use **Acesso a artefatos** como **PP**, atualize a lista e conceda acesso ao artefato da exposição e edição pretendidas. Entre como **VVB** para baixar aquela variante. A revogação impede novo acesso via link. O histórico de exportação permanece acessível ao PP.

Os ZIPs incluem DOCX de simulação, manifesto SHA-256 por arquivo e anexos conforme o ato. O modelo de saída é um mapeamento sintético de seções dos modelos SRC-007 PD/MR 5.0B. Recibos e peças atribuídas ao PP ou ao VVB são de ensaio. A E8-008 trata da conferência de canal e dos atos de listagem; a E8-009 trata das auditorias e dos pareceres. Consulte a [verificação da E8-007](evidencias/e8-007/VERIFICACAO.md).

A API usa `GET /dossiers/checklist`, `POST /dossiers`, `POST /dossiers/{id}/actions`, `POST /dossiers/{id}/exports` e `POST /dossiers/{id}/receipts`, sob `/v1/projects/{project}`. O pedido de criação usa `document` no corpo e `If-Match: "0"`. A leitura de manifestos fica em `/dossiers/{id}/versions/{revision}/manifest/{exposure}/{edition}`.

## Demonstrar listagens e consulta pública

Use o painel **Listagens e consulta pública**. As ações abaixo simulam o trabalho de captura depois da conferência em cada canal.

1. Congele um PD indicativo para desenvolvimento e um PD completo para listagem em validação. No [roteiro de dossiês](#demonstrar-dossiês-pd-e-mr), gere a versão **PUBLICA · LIMPA** e vincule um recibo por ato. Escolha **Registro simulado** para a rota Arquivo ou **Hub simulado** para Preenchimento humano. Use **02/08/2027** e **01/12/2027**, respectivamente, como datas de protocolo do ensaio.
2. Como **PP**, clique em **Atualizar listagens**, depois em **Preparar tentativa de listagem**. Selecione o tipo e o dossiê. Em **Conferir canal e poder**, escolha Arquivo ou Preenchimento humano, informe a data da conferência anterior ao protocolo e vincule o poder simulado com sua vigência. A rota **Indeterminado** permite guardar a conferência e impede confirmar o protocolo.
3. Em desenvolvimento, selecione **Vincular protocolo de desenvolvimento**, atribua um identificador sintético à tentativa, por exemplo `SIM-EXTERNO-DEV-01`, e vincule o recibo correspondente. Registre **Boleto**, **Quitação** e **Comprovar publicação**, nessa ordem, com identificadores de ato distintos. A publicação de desenvolvimento mantém a consulta sem janela aberta.
4. Na tentativa em validação, confira canal e poder e vincule o segundo recibo. Para reproduzir a janela do kit, registre **Comprovar publicação** em **05/12/2027 às 10:00 BRT**, com a página da tentativa sugerida na tela. A janela vai até **04/01/2028 às 10:00 BRT**, com fim exclusivo. A quitação de desenvolvimento pode ser aproveitada nas condições do ensaio.
5. Em **Capturar manifestação do Hub**, use identificador sintético, data, assunto, conteúdo e contato fictício. Para manifestação dentro da janela, o encaminhamento comprovado é registrado ao fim dos 30 dias. Marque o vínculo com **SIM-VVB** quando aplicável. Como **PP**, registre a resposta. Como **VVB**, consulte a manifestação atribuída e registre uma avaliação suficiente ou um achado. **Ver resumo público** mostra somente estado, datas e contagens.
6. O encerramento exige o ato comprovado e o resumo correspondente. A data nominal sozinha mantém a consulta aberta. Reclamações do projeto ficam em **Enquadramento e inventário**. Para retorno incerto, use **Registrar retorno incerto** e depois vincule o recibo encontrado ou **Comprovar conferência sem recibo** antes de repetir.

O prazo de uma exigência é consultável em **Conferir situação na data**. A inativação, suspensão, retomada e rejeição precisam da prova do ato. Em reativação ou resposta a exigência, prepare a revisão sucessora do pacote, confirme o canal para ela e use o recibo dessa revisão. Consulte as [evidências da E8-008](evidencias/e8-008/VERIFICACAO.md).

Na API, use `/listings`, `/listings/{id}/channel-checks`, `/listings/{id}/actions`, `/consultations/{id}/actions`, `/consultations/{id}/comments` e `/comments/{id}/actions`, sob `/v1/projects/{project}`. O PP registra a captura com sua identidade. A autoria do ato acompanha a prova, e o VVB registra a avaliação na sua própria sessão.

## Demonstrar auditorias e pedidos

Abra **Auditorias e pedidos REG, V1 e V2**. Comece com uma **listagem em validação publicada**, a respectiva consulta e um **PD de registro congelado**. Para M1 e M2, use os MRs vinculados aos fechamentos vigentes.

1. Como **PP**, em **Contratar VVB**, escolha VAL, a consulta, o PD de registro e o VVB A. Registre escopo, data e vigência da acreditação simulada. Em **Acesso a artefatos**, selecione a variante reservada limpa do pacote, conceda acesso a **SIM-VVB** e capture o recibo da concessão. Copie o ID exibido. Na auditoria, use **Vincular concessão com recibo**.
2. Entre como **SIM-VVB**. Use **Abrir auditoria** a partir da publicação em validação. Registre a visita e, depois do encerramento comprovado e das avaliações aplicáveis da consulta, emita o parecer para essa revisão do dossiê. A decisão e o arquivo ficam vinculados ao pacote citado. Um parecer negativo exige correção e retomada pelo mesmo organismo.
3. Para ensaiar um achado, registre critério e questão como **VVB**. Como **PP**, prepare uma revisão sucessora, obtenha revisão interna, conceda o acesso ao novo pacote e capture o recibo do ZIP reservado entregue ao VVB. Em **Entregar resposta ao VVB**, informe o ID da exportação com recibo. O VVB decide se a resposta atende ao achado.
4. Com parecer positivo, como **PP**, abra **Preparar pedido REG, V1 ou V2**. Confira canal e poder para o pacote exato. Em **Dossiês PD e MR**, exporte a variante pública limpa no destino escolhido e capture o recibo. Use o ID da exportação em **Vincular protocolo do pedido**. Registre os atos simulados da Verra e, para REG, a confirmação de registro efetivo.
5. Para M1, registre a visita. Se ela anteceder o fim do período, vincule a cobertura complementar pelo SHA-256 do fechamento. Para M2, use **SIM-VVB-B** e conceda acesso também ao pacote histórico auditado de M1. O VVB B registra o risco e a justificativa de dispensa quando a visita puder ser dispensada. A aprovação de V1 ou V2 deixa a emissão aguardando prova de ativação própria.
6. Em uma rodada da Verra, prepare versão sucessora limpa e comparada. O VVB responde com a revisão conferida e o recibo vinculado. A consulta do prazo é informativa. Após devolução, registre a nova tentativa com a antecessora. O reaproveitamento de uma revisão até 180 dias exige prova expressa.

As provas de contratação, acreditação, visitas, pareceres e decisões são sintéticas. Consulte [os ensaios e pendências da E8-009](evidencias/e8-009/VERIFICACAO.md).

## Emissão e conciliação · E8-010

Em **Emissão e conciliação**, selecione V1 ou V2 depois de comprovar a aprovação em **Auditorias e pedidos REG, V1 e V2**. Use **Atualizar emissão** se a aprovação acabou de ser registrada.

1. **PP** registra a ativação no registro e o extrato oficial do vintage escolhido. No ensaio de V1, use o recorte de 01/01/2028 a 01/10/2028 (fim exclusivo), saldo 10 e pendência de 2 marcada como **A deduzir**. A disponibilidade exibida deve ser 8. Use o recorte de 01/10/2028 a 01/01/2029 para a posição separada de V2 com saldo 30.
2. **PP** prepara o pedido de 8, confere canal e poder e vincula o recibo. Registra a cobrança e a quitação. Confirme **Aguardando depósito** e líquido zero. Depois registre um depósito parcial de 5 vinculado ao mesmo protocolo e conta.
3. **PP** registra eventual encerramento do remanescente e o ajuste de −1 vinculado ao depósito. Registra um extrato posterior com saldo 4 e a tentativa com quantidade 4 marcada como **Já refletida**. Use datas dos atos em ordem, com o extrato de conciliação posterior à liberação do remanescente.
4. **PREP** registra a comparação. **REV** abre a mesma tentativa e concilia após conferir as provas e o extrato. Use **Consultar trilha da tentativa** e **Consultar histórico de posições** para conferir os efeitos.

O exemplo usa contas, identificadores e provas sintéticos. Use um identificador por ato, como `SIM-ATIVA-V1-01` para a ativação e `SIM-EXTRATO-V1-01` para a primeira posição. Em um reenvio, informe o identificador do ato original. As evidências, resultados e ressalvas estão na [verificação da E8-010](evidencias/e8-010/VERIFICACAO.md).

## Trabalhos, retenção e recuperação · E8-011

Em **Trabalhos, retenção e recuperação**, filtre os trabalhos por M1 ou M2 e abra **Ver tentativas** para conferir gerações, versões e motivos de falha. OP ou PREP retoma importações. REV retoma cálculos. A retomada continua da última etapa gravada.

Em **Retenção e cota de evidências**, consulte o estado da guarda em uma data de ensaio. O piso de 30/09/2034 inclui esse dia para o fim de crédito de 30/09/2032. REV pode corrigir o último fim com motivo e a prova sintética `SIM-FIM-CREDITO@1`. A cota soma brutos, artefatos e manifestos. Cópias entregues, documentos referenciados e brutos já recebidos são preservados.

### Cópia e restauro isolado

Use um diretório privado em armazenamento destinado a cópias. O comando registra a versão do PostgreSQL, a migração Flyway, o identificador da imagem quando presente e os hashes do dump e do inventário. A cópia inclui as versões, concessões, identidades e chaves de idempotência. As sessões são renovadas no ambiente recuperado. Preserve a imagem ou o checkout correspondente e mantenha as credenciais do ambiente na configuração operacional.

```sh
python3 scripts/carbonflow-recovery.py backup --output /caminho/privado/copia-001
```

Depois do incidente, registre seu instante com fuso e confronte o intervalo a partir da fonte preservada. O comando `observe` usa a base sobrevivente para identificar recibos, atos, extratos, brutos e fatos posteriores ao ponto copiado. Quando a base original estiver indisponível, reúna esse inventário a partir dos comprovantes preservados. O arquivo precisa conter `format: 1`, `backupId`, `manifestSha256`, `observedThrough` com fuso e uma lista `effects`. Cada efeito traz `project`, `effect`, `source`, `namespace`, `reference`, `sha256` e o vínculo do pedido (`binding`) quando for um protocolo de emissão. O OP registra como conferiu o intervalo.

```sh
python3 scripts/carbonflow-recovery.py observe \
  --backup /caminho/privado/copia-001 --output /caminho/privado/intervalo-001.json
python3 scripts/carbonflow-recovery.py restore --backup /caminho/privado/copia-001 \
  --gap /caminho/privado/intervalo-001.json --incident-at "$INSTANTE_DO_INCIDENTE" \
  --target carbonflow-recovery-001 --port 18092 --run-app
```

O destino usa rede e volume próprios. Após restaurar e comparar o inventário, a aplicação sobe em [127.0.0.1:18092](http://127.0.0.1:18092/) com a guarda de emissão ativa. PREP reexecuta as memórias fechadas. OP e PREP recompõem brutos e fatos com revisão do REV. PP captura as provas esperadas e, se necessário, recria uma tentativa pelo recibo em **Recompor protocolo observado**. PREP compara e REV concilia depósitos e ajustes. OP atesta o intervalo e REV decide a liberação. O painel registra RPO e RTO observados.

Consulte [a verificação da E8-011](evidencias/e8-011/VERIFICACAO.md) para a massa, o ensaio de falha e as pendências de homologação.

## Medições do piloto · E8-012

O [relatório da E8-012](evidencias/e8-012/VERIFICACAO.md) registra a carga, os limites observados, os ensaios de disponibilidade e as pendências de PEN-008. A [comparação mensal](evidencias/e8-012/CUSTO.md) usa tarifas públicas e premissas de consumo, câmbio e tributos explicitadas.

A carga usa um projeto Compose exclusivo, porque grava e repõe prévias de ensaio. O perfil `hourly` monta 200.000 fatos em M1 com EC horária. O perfil `stress` usa EC sub-horária sintética para isolar a guarda de 100.000 fatos por classe e ano civil. As pré-cargas são geradas por SQL com origem e hash. As amostras cronometradas passam pelo upload, fila e API da aplicação.

```sh
export CARBONFLOW_PORT=18096
docker compose -f compose.yaml -f scripts/envelope/host.compose.yaml -p carbonflow-e8-012-teste up --build -d --wait
python3 scripts/envelope/prepare.py --compose carbonflow-e8-012-teste --facts-profile hourly --output /tmp/carbonflow-fixture.json
python3 scripts/envelope/measure.py --compose carbonflow-e8-012-teste --profile host --output /tmp/carbonflow-medicoes.json
```

`--imports` e `--closures` definem a quantidade de repetições medidas, além do aquecimento. Com autorização de operação no ambiente de teste, execute `availability.py seed` uma vez e `probe` em cada minuto da janela, guardando as saídas em JSONL. `rollup` recebe início, fim exclusivo e feriados anunciados. `simulate` exercita a regra com parada controlada do contêiner e minuto omitido. Use a [série e os limites do ensaio](evidencias/e8-012/VERIFICACAO.md) para interpretar as métricas.

Depois de guardar os resultados, remova somente o projeto descartável.

```sh
docker compose -f compose.yaml -f scripts/envelope/host.compose.yaml -p carbonflow-e8-012-teste down -v
```

## Parecer independente · E8-013

O [dossiê de revisão](evidencias/e8-013/VERIFICACAO.md) reúne o roteiro CT-E5-35, as evidências sintéticas e o modelo de parecer e tratamento de achados. A issue aguarda designação, parecer assinado e conferência setorial aplicável da ISO 14065:2020.

## Preparação da homologação · E8-014

O [roteiro de homologação](evidencias/e8-014/VERIFICACAO.md) reúne os requisitos da amostra autorizada de ED/EC, o procedimento de conciliação, os ensaios IMP-01 a IMP-08 e o checklist de prontidão para cada ato. O ensaio de preparação usa 30 dias de dados sintéticos.

## Executar os builds e testes localmente

Use JDK 21, Node.js 24 com npm, PostgreSQL 16 e, para o teste de navegador, Chrome ou Chromium. Em Linux, tenha também `unzip` para o Maven Wrapper extrair a distribuição verificada do Maven 3.9.12. O backend usa Quarkus 3.33.4 e as dependências do Angular estão fixadas no lockfile.

Prepare dois bancos PostgreSQL dedicados à demonstração e aos testes. A configuração local usa o usuário `carbonflow` e, por padrão, as URLs `jdbc:postgresql://127.0.0.1:55432/carbonflow` e `jdbc:postgresql://127.0.0.1:55432/carbonflow_test`. Ajuste com `CARBONFLOW_DB_URL`, `CARBONFLOW_TEST_DB_URL`, `CARBONFLOW_DB_USER` e `CARBONFLOW_DB_PASSWORD`.

Com o servidor PostgreSQL acessível, execute a partir da raiz:

```sh
npm --prefix frontend ci
npm --prefix frontend run build
(cd backend && ./mvnw clean verify)
java -jar backend/target/quarkus-app/quarkus-run.jar
```

O Flyway cria ou migra o esquema. Entre como `SIM-PREP` na [demonstração local](http://127.0.0.1:8080/) e execute o cenário. **Testar massa sem EC** exercita a rejeição e mantém visível a memória anterior. Os testes de navegador usam o mesmo pacote em execução.

```sh
npm --prefix frontend test
```

Use `CARBONFLOW_CHROME` para indicar o executável do navegador e `CARBONFLOW_TEST_URL` para alterar a URL usada pelo teste.

Os testes de navegador gravam execuções e amostras sintéticas e a jornada da E8-003 parte de uma ficha vazia. Use um projeto Compose separado com volume de testes novo.

```sh
CARBONFLOW_PORT=18081 docker compose -p carbonflow-ux-test up --build -d --wait
CARBONFLOW_TEST_URL=http://127.0.0.1:18081 npm --prefix frontend test
docker compose -p carbonflow-ux-test down -v
```

### Banco local descartável

Caso precise subir um servidor PostgreSQL 16 já instalado, execute em um terminal como usuário comum. Use uma senha para os dois bancos da demonstração e exporte-a também no terminal que iniciar a aplicação.

```sh
PG_BIN="$(pg_config --bindir)"
PG_PILOT_DIR="$(mktemp -d)"
export CARBONFLOW_DB_PASSWORD=carbonflow
printf '%s\n' "$CARBONFLOW_DB_PASSWORD" > "$PG_PILOT_DIR/password"
"$PG_BIN/initdb" -D "$PG_PILOT_DIR/data" -U carbonflow --locale=C.UTF-8 \
  -A scram-sha-256 --pwfile="$PG_PILOT_DIR/password"
rm "$PG_PILOT_DIR/password"
"$PG_BIN/pg_ctl" -D "$PG_PILOT_DIR/data" -l "$PG_PILOT_DIR/postgres.log" \
  -o "-h 127.0.0.1 -p 55432 -k $PG_PILOT_DIR" start
PGPASSWORD="$CARBONFLOW_DB_PASSWORD" "$PG_BIN/createdb" -h 127.0.0.1 -p 55432 -U carbonflow carbonflow
PGPASSWORD="$CARBONFLOW_DB_PASSWORD" "$PG_BIN/createdb" -h 127.0.0.1 -p 55432 -U carbonflow carbonflow_test
```

Ao encerrar, use `"$PG_BIN/pg_ctl" -D "$PG_PILOT_DIR/data" stop`.

## Contrato desta fatia

- `POST /v1/projects/PIL-SYN-BR-001/runs` executa os dois períodos de uma massa do catálogo local. Envie `Idempotency-Key` e `{"fixture":{"id":"E6-EXEMPLOS","version":1}}`. Sucesso retorna 201 com o endereço da execução imutável. A repetição do mesmo comando com a mesma chave recupera essa execução.
- `GET /v1/projects/PIL-SYN-BR-001/runs/latest` recupera o último relatório, ou 204 antes da primeira execução. `GET /v1/projects/PIL-SYN-BR-001/runs/{id}` permite consultar qualquer execução por ID. Acrescente `/snapshot` para obter os bytes referenciados pelo SHA-256 do relatório.
- `E6-EXEMPLOS-SEM-EC` v1 deriva da massa original, com o EC do primeiro recorte removido. A API retorna 422 `LACUNA_OBRIGATORIA` e nenhum fechamento simulado é persistido para essa tentativa.
- Os decimais da API são strings com ponto. Os intervalos usam fim exclusivo e calendário de `America/Sao_Paulo`. A tela mostra o fim inclusivo. O fator candidato EF-SYN-v2 consta do snapshot e o cálculo usa EF-SYN-v1.
- O ensaio mostra M2 abrindo quando M1 entra em conferência e seguindo em coleta durante a revisão de V1 e a espera de depósito de I1. A memória de M2 usa todos os agregados da janela sintética. Seu total e os estados de coleta são observações de tempos distintos do cenário, e a demonstração mantém M2 aberto.

A E8-001 recorta OP-E6-04/07 em uma execução local síncrona e imutável com marcos simulados. A E8-002 exige sessão para relatórios e snapshots e aplica OP-E6-03 ao compartilhamento de versões sintéticas para auditoria V1. O preparo e a revisão das amostras exercitam a autorização de OP-E6-08. A migração preserva a autoria e os bytes das execuções anteriores. A porta publicada fica restrita ao loopback.

[Verificação da E8-001](evidencias/e8-001/VERIFICACAO.md) · [Verificação da E8-002](evidencias/e8-002/VERIFICACAO.md) · [Verificação da E8-003](evidencias/e8-003/VERIFICACAO.md) · [Verificação da E8-004](evidencias/e8-004/VERIFICACAO.md) · [Verificação da E8-005](evidencias/e8-005/VERIFICACAO.md)

## Direção de produto (D17)
CarbonFlow é uma plataforma de projetos de carbono com núcleo metodológico agnóstico; VM0038 v1.1 OPEN é o primeiro recorte e PIL-SYN o ensaio histórico. Consulte a [jornada canônica de sete etapas](specification/CF-JOURNEY-001.md) e a [auditoria de alinhamento](specification/CF-JOURNEY-001-AUDITORIA.md). Outros métodos/áreas podem ser candidatos; execução real requer pack autorizado e homologado. Esta decisão não altera o roteiro Docker acima.
