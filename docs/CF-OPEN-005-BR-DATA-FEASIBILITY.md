# Viabilidade dos dados brasileiros de frota e ratings

Verificação de 23/11/2026 para #94, com interfaces em #95 e #92.
Base de código examinada em `10def4e`.

## Resultado

Foi obtida uma amostra nacional real por marca/modelo e ano de fabricação.
O período de observação é setembro de 2026. O catálogo RENAVAM identifica
a licença do conjunto e do recurso como **Outra (Domínio Público)**.
A publicação mede veículos registrados. Permanecem sem prova a circulação
ativa, a classificação BEV/LDV/DCFC de todas as chaves e a cobertura integral
da população metodológica. O denominador EVR admitido continua ausente.
[Catálogo](https://dados.transportes.gov.br/dataset/registro-nacional-de-veiculos-automotores-renavam)
e [recurso inspecionado](https://dados.transportes.gov.br/dataset/registro-nacional-de-veiculos-automotores-renavam/resource/4408da67-7821-4abf-96db-58acdf1497da).

O arquivo completo tem 22.849.046 linhas de dados e soma 136.424.856 veículos.
A planilha independente por combustível soma os mesmos 136.424.856.
Essa reconciliação verifica a extração do agregado registrado. Sua igualdade
não estabelece o total nacional de BEVs leves em circulação.
O arquivo contém as 27 UFs e também três rótulos de localização desconhecida
ou não aplicável, preservados no relatório.
[Publicação mensal](https://www.gov.br/transportes/pt-br/assuntos/transito/conteudo-Senatran/frota-de-veiculos-2026).

## Artefatos e proveniência

- [Amostra reproduzível](evidence/CF-OPEN-005-2026-09/registered-sample.json) com
  contagens nacionais por chave literal, distribuição por ano de fabricação,
  rótulos de UF e três linhas originais com localizador por chave.
- [Manifesto](evidence/CF-OPEN-005-2026-09/source-manifest.json) com URLs,
  identificadores CKAN, licença publicada, datas de captura, MIME, tamanho,
  SHA-256 dos quatro arquivos e totais por combustível.
- [Inspeção offline](../scripts/inspect_senatran_stock_sample.py), sem acesso
  ao banco ou à rede, que lê o ZIP em streaming e valida cabeçalhos, período
  do nome publicado e quantidades inteiras não negativas.

O ZIP tem 136.926.215 bytes e SHA-256
`005963d4c677c50354bcdb094bf55119beacc25dc644f1d7bfe3611c26fe40a8`.
Seu TXT interno tem 1.219.605.816 bytes. A amostra é uma seleção deliberada
para examinar ligação e lacunas. A seleção não foi projetada para estimar
participação de mercado ou cobrir todos os BEVs.

## Granularidade efetivamente verificada

| Publicação de setembro de 2026 | Dimensões presentes | Lacuna para EVR |
| --- | --- | --- |
| RENAVAM marca/modelo | UF, município, marca/modelo, ano de fabricação CRV, quantidade | Sem combustível, categoria, situação ativa ou recarga |
| Planilha combustível | UF, município, combustível, quantidade | Sem marca/modelo ou ano de fabricação |
| Planilha fabricação/modelo | UF, município, ano-modelo, ano de fabricação CRV, quantidade | Sem identidade comercial ou combustível |

As duas planilhas XLSX não fornecem uma chave para ligar combustível a cada
marca/modelo. Ano-modelo, ano de fabricação e período de observação ficam
registrados como conceitos distintos. Uma junção por município multiplicaria
combinações e não demonstraria a propulsão de uma linha.
[Índice oficial e arquivos vinculados](https://www.gov.br/transportes/pt-br/assuntos/transito/conteudo-Senatran/frota-de-veiculos-2026).

## Amostra e ligação candidata ao PBEV

As quantidades abaixo são agregações do TXT público, mantendo as chaves
originais separadas. Os números da coluna de fabricação desconhecida já
integram o total da respectiva linha.

| Chave literal RENAVAM | Registrados | Fabricação desconhecida | Relação com a edição PBEV examinada |
| --- | ---: | ---: | --- |
| I/BYD DOLPHIN GS 180EV | 69.235 | 7.121 | Candidato localizado na página 1 |
| I/BYD DOLPHIN MINI GS5EV | 43.839 | 271 | Candidato localizado na página 1 |
| BYD/DOLPHIN MINI GS5EV | 47.994 | 4.992 | Mesma versão candidata da linha anterior, alias ainda exige decisão |
| I/GWM ORA 03 BEV58 C | 1.494 | 50 | Candidato localizado na página 1 |
| I/NISSAN LEAF TEKNA | 1.359 | 0 | Versão não localizada nesta edição de 2026 |
| I/RENAULT E-KWID TECHNO | 836 | 2 | Versão não localizada nesta edição de 2026 |

As seis chaves somam 164.757 registros, dos quais 12.436 têm fabricação
desconhecida. Essa soma descreve somente a seleção acima. Quatro chaves têm
ligação textual candidata com três linhas de eficiência. Não houve criação
de alias canônico nem aprovação de rating.

A edição PBEV consultada apresenta na página 1 consumos energéticos de
0,42 MJ/km, 0,41 MJ/km e 0,51 MJ/km para as três versões candidatas,
respectivamente. Esses valores são referências para conferência de unidade.
As colunas de modo elétrico em km/L equivalente exigem distinção em relação
ao consumo de um fóssil. A tabela e seu período não demonstram quantidade
de veículos em circulação ou compatibilidade com DCFC.
[PDF oficial consultado](https://www.gov.br/inmetro/pt-br/assuntos/regulamentacao/avaliacao-da-conformidade/programa-brasileiro-de-etiquetagem/tabelas-de-eficiencia-energetica/veiculos-automotivos-pbe-veicular/mascara-pbev-2026_19_jan-rev01.pdf/@@download/file).

O índice identifica a edição 2026 atualizada em 31/08/2026. O PDF tem revisão
impressa em 14/08/2026 e metadado de criação em 25/08/2026. O caminho contém
`19_jan`, portanto o nome da URL não determina sua edição. O manifesto
preserva essas datas e o hash do conteúdo obtido.
[Índice Inmetro](https://www.gov.br/inmetro/pt-br/assuntos/regulamentacao/avaliacao-da-conformidade/programa-brasileiro-de-etiquetagem/tabelas-de-eficiencia-energetica/veiculos-automotivos-pbe-veicular).

Como controle de seleção, o arquivo também contém 128 registros de
`AGRALE/MAXIBUS DOLPHIN` e 6.827 de `RENAULT/KWID EVOLUTION`.
Semelhança de palavras não prova propulsão ou comparabilidade. Essas duas
chaves ficam identificadas na amostra como controles de ambiguidade.

## Comparáveis e direitos de uso

A página 1 do PBEV fornece, por exemplo, um candidato a gasolina da categoria
média com consumo combinado de 11,1 km/L, localizado pela marca Audi e versão
Performance. Mesma categoria e presença de rating são insuficientes para
aprovar o par. Capacidade, assentos, serviço, mercado e incidência temporal
precisam de prova e revisão independente. Nenhum comparável foi aprovado.
Modelos flex e misturas continuam sujeitos à rota de combustível apropriada.
[PDF oficial, página 1](https://www.gov.br/inmetro/pt-br/assuntos/regulamentacao/avaliacao-da-conformidade/programa-brasileiro-de-etiquetagem/tabelas-de-eficiencia-energetica/veiculos-automotivos-pbe-veicular/mascara-pbev-2026_19_jan-rev01.pdf/@@download/file).

O catálogo RENAVAM declara domínio público para a amostra registrada.
O rodapé do Inmetro aponta CC BY-ND 3.0, que permite compartilhar com
atribuição e limita a distribuição de material adaptado. Nesta entrega,
o PDF permanece como referência da análise e não integra o repositório.
O manifesto registra a licença publicada e a ausência de autorização
operacional e de avaliação técnica cadastradas no CarbonFlow.
O uso pelo pipeline exige a decisão por finalidade já prevista em #92.
[Rodapé Inmetro](https://www.gov.br/inmetro/pt-br/assuntos/regulamentacao/avaliacao-da-conformidade/programa-brasileiro-de-etiquetagem/tabelas-de-eficiencia-energetica/veiculos-automotivos-pbe-veicular)
e [condições CC BY-ND](https://creativecommons.org/licenses/by-nd/3.0/).

## Lacunas e respectivos owners

| Lacuna comprovada | Evidência necessária | Owner |
| --- | --- | --- |
| Registro versus circulação ativa | Definição estatística, baixas e situação cadastral da população publicada | #94/#92 |
| Propulsão, classe e DCFC ausentes do TXT | Classificação versionada e prova por modelo/versão, incluindo todos os modelos da população | #94/#93 |
| Denominador BEV LDV nacional ausente | Série reconciliada na mesma população, geografia e período | #94 |
| Aliases importados e nacionais | Decisão explícita com prova, mantendo as quantidades originais recuperáveis | #94 |
| Duas versões sem rating de 2026 localizado | Edições históricas ou fonte admissível, com decisão de compatibilidade temporal | #95 |
| Capacidade e comparabilidade não demonstradas | Fichas de assentos/capacidade/serviço e parecer independente por par | #95 |
| Operações de fonte ainda não autorizadas no produto | Registro de fonte/edição, direitos por finalidade, revisão e seleção | #92/#10 |

O cadastro canônico existente deve receber uma futura importação por
`PlatformSourceIngestion`, `FleetCompositionService`, `VehicleRatingService`
e `OpenE8FleetSelections`. A inspeção não cria outra representação operacional
de modelos, ratings ou quantificação. `evr_total` e `weights` ficam nulos
na amostra e `usableForE8=false`.

## Reprodução e verificação

Depois de obter o ZIP pela URL do manifesto e conferir seu SHA-256:

```sh
python3 -m unittest discover -s scripts -p 'test_inspect_senatran_stock_sample.py' -v
python3 scripts/inspect_senatran_stock_sample.py /caminho/renavam-2026-09.zip \
  --period 2026-09 \
  --model 'I/BYD DOLPHIN GS 180EV' \
  --model 'I/BYD DOLPHIN MINI GS5EV' \
  --model 'BYD/DOLPHIN MINI GS5EV' \
  --model 'I/GWM ORA 03 BEV58 C' \
  --model 'I/NISSAN LEAF TEKNA' \
  --model 'I/RENAULT E-KWID TECHNO' \
  --model 'AGRALE/MAXIBUS DOLPHIN' \
  --model 'RENAULT/KWID EVOLUTION' \
  --output /tmp/registered-sample.json
cmp /tmp/registered-sample.json docs/evidence/CF-OPEN-005-2026-09/registered-sample.json
```

Os testes verificam seleção literal, preservação de aliases distintos,
ausência versus zero, ano desconhecido, rejeição de quantidades fracionárias
ou inválidas, schema diferente e período incompatível com o arquivo.
A conciliação completa do arquivo real foi conferida contra a planilha
de combustível. A CI executa esses testes no gate existente de sandbox.

Esta entrega conclui a verificação da amostra e documenta a insuficiência
para admissão nacional. #94 e #95 continuam abertas pelas lacunas da matriz.
