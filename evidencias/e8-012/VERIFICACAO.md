# E8-012 · Capacidade, experiência, disponibilidade e custo

PIL-SYN-BR-001 · Kit 0.2.14. Os ensaios usaram Docker Compose descartável, Java 21, Quarkus 3.33.4, PostgreSQL 16.16 e Angular 21.2.26. O host local tem Intel Core i5-12450H, 12 CPUs lógicas e 7,5 GiB de RAM. Aplicação e banco ficaram no mesmo host, com perfis de CPU controlados. A aplicação usou 1,25 GiB de memória e o banco 1,50 GiB nos dois perfis. O perfil host reservou 1,25 CPU para a aplicação e 0,75 para o banco. O perfil separado usou 2 e 1. A rede HTTP foi loopback. As séries registram identificação das imagens, schema, contagens, hashes, CPU, memória, leituras, escrita, tráfego e atividade do banco.

## Desempenho e correção

O p95 é o valor no posto `ceil(0,95 × n)` da amostra ordenada. O aquecimento é registrado e fica fora dos percentis. O recebimento por HTTP inclui gravação do bruto. O processamento mede do enfileiramento ao relatório disponível. O fechamento mede da criação da seleção até a leitura dos bytes do fechamento. Os estados e hashes são conferidos antes de usar a amostra. Uma segunda sessão consulta períodos, dossiês e emissões, alternando pelos três projetos.

| Medida | Amostra local | p95 | Hipótese do kit |
| --- | ---: | ---: | ---: |
| Recebimento de ED | 20 CSVs válidos de 10.000 linhas | 21,71 ms | Medir à parte |
| Processamento de ED | 20 CSVs de 10.000 linhas | **13,31 s** | 60 s |
| Recebimento de EC | 20 CSVs horários válidos de 10.000 linhas | 39,24 ms | Medir à parte |
| Processamento de EC | 20 CSVs horários de 10.000 linhas | **13,64 s** | 60 s |
| Consulta durante importação | 675 consultas válidas | **6,88 ms** | 2 s |
| Fechamento técnico no perfil host | 20 ciclos de 200.000 fatos com EC sub-horária | **13,93 s** | 120 s |
| Consulta durante o fechamento técnico | 342 consultas válidas | **16,71 ms** | 2 s |
| Fechamento com EC horária no perfil separado | 20 ciclos de 200.000 fatos | **13,64 s** | 120 s |

A pré-carga técnica tinha 200.000 fatos admitidos no projeto principal e 190.000 no projeto de importação. As prévias do lote cronometrado usam a API. Depois de comprovar as 10.000 candidatas e o reenvio idempotente, o harness limpa apenas a prévia de teste para reaplicar a mesma condição inicial. Os arquivos mediram entre 1,32 e 1,43 MB. A guarda de tamanho do arquivo foi exercitada à parte. O teste técnico de fechamento usou 100.000 fatos de cada classe e manteve o mesmo digest das observações entre revisões. As duas primeiras tentativas de aquecimento de importação evidenciaram identificadores inválidos e uma chave repetida após corrigir o payload. A série publicada usa identificadores válidos e chaves novas por amostra. Uma rodada diagnóstica também expôs um plano de execução lento.

O fechamento com EC horária usou **182.432 sessões ED** e **17.568 leituras EC horárias** em dois canais. Como M1 atravessa 31/12, a massa distribui ED entre os dois anos civis e conserva os limites anuais. Cada ciclo conferiu 18.243,20 kWh de ED, 35.136 kWh de EC, BE de 18,2432, PE de 10,5408 e ER de 7,7024 tCO₂e com EF-SYN-v1, além do hash e dos bytes de fechamento. O cenário de referência do kit tem seus próprios resultados de M1 e M2.

Uma busca de sobreposição sem resultado passou de 1.309,21 ms para 2,83 ms no diagnóstico local. A V12 adiciona um índice GiST de intervalo com fim exclusivo. A consulta restringe primeiro pelo intervalo e depois seleciona a linha histórica pela mesma ordem de lote e linha. A consulta conserva a ordem histórica e usa intervalos de fim exclusivo para preservar as fronteiras de sobreposição.

## Recuperação

A [E8-011](../e8-011/VERIFICACAO.md) mediu RPO de **1 s** e RTO de **16 s** num ensaio local, com dump de cerca de 1,08 MB, comparação de 51 tabelas, duas memórias reexecutadas e cinco efeitos do intervalo recompostos. Os valores propostos de 24 h e 8 h exigem ensaio representativo de até 6 GB com metadados, em domínio de falha separado. A periodicidade mensal e o responsável de plantão seguem para PEN-008.

## Custo e decisão

As duas opções usam a mesma [memória de custo](CUSTO.md). Com as hipóteses descritas, o host único com cópia separada ficou em **R$ 242,88/mês** e o banco gerenciado padrão em **R$ 470,58/mês**, incluindo a provisão e a margem de 20%. O teto de R$ 500 segue como proposta. A comparação usa tabela pública e a carga local como referência, com teste de nuvem, cobrança efetiva e horas de operação pendentes. VVB, Verra e taxas ficam fora do custo de infraestrutura.

## Comparação com a mesma carga

Os primeiros três fechamentos do perfil separado foram comparados com três repetições no perfil host, sobre o mesmo conjunto horário de fatos e com o mesmo digest. Cada perfil também enfileirou três CSVs de cada classe com hashes idênticos por repetição. O segundo perfil na ordem de execução herdou o histórico de fechamentos anterior. As medianas são exploratórias.

| Operação da comparação | Host local | Separado local |
| --- | ---: | ---: |
| Fechamento horário com 200.000 fatos | 13,68 s | 13,25 s |
| Processamento de ED | 12,30 s | 12,63 s |
| Processamento de EC | 11,96 s | 12,54 s |

As séries localmente cronometradas registraram mais de 50 consultas válidas em cada cenário. Os picos amostrados pelo Docker nos dois ensaios principais ficaram abaixo de 200 MiB na aplicação e de 300 MiB no PostgreSQL, segundo a memória de trabalho apresentada por `docker stats`. O banco alcançou cerca de 2,21 GB em disco no perfil host. Os dados brutos incluem I/O e arquivos temporários do banco. Memória atribuída a cache, tráfego, latência de rede e armazenamento de uma instância de nuvem precisam de nova medição.

## Limites e integridade

| Limite | Resultado |
| --- | --- |
| Inventário | Triagem aceitou três pátios, dez DCFCs, dez canais EC, cem BEVs e cinco modelos. A primeira unidade de excesso de cada dimensão foi recusada com mensagem e ficha preservada. |
| Fatos por ano civil | Na base técnica, ED e EC admitiram o centésimo milésimo fato em 2028 e recusaram o seguinte. A primeira entrada de 2029 foi aceita nas duas classes. A massa usada para isolar o contador de EC incluía leituras sub-horárias. |
| CSV | Séries válidas de 10.000 linhas concluíram com a classificação esperada. Um CSV com 10.001 linhas foi recusado integralmente e gravou zero linhas de prévia. O bruto de 10.000.000 bytes foi recebido. O byte seguinte retornou HTTP 413 com código e limite explícitos da API. |
| Cota de evidências | Com pré-carga sintética compressível, o último byte completou 2.000.000.000 bytes lógicos. O byte adicional foi recusado. O arquivo aceito continuou acessível e com o mesmo hash. |
| Projetos e acesso | O diretório retornou três projetos da organização em ensaio. O aplicativo ainda tem diretório somente leitura, seis identidades nomeadas na simulação e admitiu a terceira sessão. As duas anteriores permaneceram utilizáveis. O bloqueio de novos cadastros acima dos limites e a guarda de simultaneidade seguem pendentes. |
| Anexo de 25 MB | A rota de entrada disponível é a de CSV. A recepção de anexos de 25 MB e o primeiro excedente ficam pendentes. |

## Disponibilidade e alertas

Em cinco minutos nominais da janela de Brasília, uma amostra real por leitura e gravação autorizadas deu três minutos bons, um durante parada controlada e um deliberadamente ausente. O denominador ficou em cinco, a cobertura em 80% e a disponibilidade em 60% nesse ensaio de contagem. A janela mensal de operação segue por medir. A espera de resposta externa fica em uma classe própria, fora do indicador interno.

O registro local detectou a recusa do arquivo em **405 ms**, a falha recuperável de cálculo em **801 ms** e a falha da cópia em **93 ms**. A cota esgotada foi confirmada no ensaio de limites. O replay da trilha E8-010 preservou a espera por depósito e a divergência para PP/REV. O teste mede a detecção e o registro local. Entrega ao plantão e cobertura mensal dependem do canal e do responsável definidos no aceite.

## Navegação por teclado

Cinco sessões automatizadas em Chrome desktop, com papéis PREP, REV e PP, concluíram as três tarefas em cada repetição. O roteiro importou uma amostra válida, propôs e admitiu uma leitura e registrou um comprovante simulado de concessão. Também verificou foco de entrada e saída, navegação por teclado nas cinco superfícies, indicação de aprovação pendente e HTTP 403 quando PREP tentou admitir a própria linha.

O foco do painel de importação passa à mensagem depois da ação ou recusa. Uma primeira rodada leu o foco antes da renderização do Angular em quatro sessões. O roteiro final passou a aguardar a renderização. Participantes humanos, seleção de arquivo via diálogo nativo, tecnologias assistivas e tempos com tarefas reais seguem pendentes de homologação.

## Evidência e condições de aceite

As [séries brutas e ocorrências](medicoes.json) guardam quatro séries de medição, as massas e hashes, testes de limites, falhas observadas, resultados das jornadas e diagnósticos do PostgreSQL. Passaram os builds Angular e Quarkus, 26 testes Java, 12 regressões de navegador e oito grupos do oráculo E7. A regressão preservou M1 = 6,00 tCO₂e e M2 = 5,30 tCO₂e com EF-SYN-v1, com coleta de M2 durante a espera do I1. A migração V12 foi aplicada ao Compose principal com prontidão HTTP 204 e os hashes anteriores preservados. A decisão de PEN-008 segue condicionada à medição no ambiente escolhido, a um mês da janela definida, ao restauro representativo, aos limites de acesso e arquivo pendentes e à validação dos responsáveis. Engenharia conduz os ensaios técnicos, PREP/REV/PP validam a jornada e Leonardo decide metas, responsabilidades e hospedagem.
