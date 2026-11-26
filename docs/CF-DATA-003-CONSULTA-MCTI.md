# CF-DATA-003 — Minutas de consulta ao MCTI e ao ONS sobre os fatores de emissão do SIN

**Estado: RASCUNHO. Não foi enviada.** Quem envia, por qual canal e em nome de quem é decisão do responsável do produto; o canal oficial não foi confirmado (a página de fatores não traz contato). A minuta é genérica de propósito: não identifica pessoas, clientes nem projetos. Esta versão (26/11/2026) incorpora as perguntas abertas na [matriz VT0011/TOOL07](CF-DATA-008-MATRIZ-VT0011-TOOL07.md) e acrescenta um texto para o ONS. Uma resposta do MCTI, se vier, **informa** a revisão normativa e jurídica; **não** admite a fonte por si (isso segue a trilha V37/V60/V61). Contexto: [CF-DATA-003-SIN-MCTI-DOSSIE](CF-DATA-003-SIN-MCTI-DOSSIE.md), seções 2.2 e 3.

## Texto proposto

**Assunto:** Fatores de emissão do SIN (SIRENE): condições de reuso e esclarecimentos sobre a tabela de análise de despacho

Prezados,

Desenvolvemos uma ferramenta de apoio à preparação de projetos de crédito de carbono no Brasil e consultamos a página "Fatores de emissão" do SIRENE (https://www.gov.br/mcti/pt-br/acompanhe-o-mcti/sirene/dados-e-ferramentas/fatores-de-emissao). Antes de qualquer uso, gostaríamos de esclarecer os pontos abaixo. Não pedimos autorização para uso específico de nenhum projeto; queremos apenas entender as condições de reuso.

**A. Licença e reuso**
1. O rodapé do site informa que todo o conteúdo está sob a licença Creative Commons Atribuição-SemDerivações 3.0 Não Adaptada. Essa licença vale também para os arquivos XLSX de fatores? Existe termo de uso ou formato de citação próprio para eles?
2. Converter unidades ou o fuso horário e reorganizar a tabela horária, preservando os valores, sem alterá-los, é considerado "derivação" para essa licença? Citar a fonte, a edição do arquivo e o endereço, e exibir os valores originais ao usuário, é permitido?
3. A página diz que o fator da margem de operação por análise de despacho "se aplica, exclusivamente, para estimar as reduções certificadas de emissões (RCEs) dos projetos de MDL". Esse fator pode ser usado como dado de origem oficial em projetos de outros programas de crédito (por exemplo, programas voluntários que adotam a TOOL07 por meio de ferramentas próprias), ou o uso é restrito ao MDL? Em caso de restrição, existe alternativa oficial para esses programas?

**B. Tabela horária (análise de despacho)**
4. Na planilha do ano-base 2026 (aba "MDL"), a tabela horária tem as colunas "Dia", "Hora" e os doze meses. Qual é o fuso horário e a convenção das horas (por exemplo, hora de Brasília, hora cheia iniciando em 00h)? Há tratamento específico para o horário de verão em anos em que ele vigorou e para os dias inexistentes (31 em meses curtos)?
5. O fator horário é calculado a partir de dados do ONS por hora do próprio ano-base, ou há defasagem? Qual é a data prevista de fechamento de cada mês?

**C. Versões e correções**
6. Alguns arquivos têm versões "corrigidas". Há um registro público das alterações (o que mudou, quando) e uma forma de identificar a edição vigente de forma inequívoca (por exemplo, um código de versão ou hash)?
7. Os arquivos antigos já publicados podem ser substituídos sem aviso? Como usuários devem tratar uma correção posterior ao uso do valor?

**D. Periodicidade e margem de construção**
8. Qual é a periodicidade oficial de publicação da tabela de análise de despacho (a nota técnica de junho de 2025 fala em divulgação mensal)? A margem de construção de 2026, que a planilha informa que será disponibilizada no início de 2027, terá data prevista?

**E. Gás**
9. Os fatores são de CO₂. O MCTI publica ou recomenda fatores de CH₄ e N₂O (ou de CO₂ equivalente) para a eletricidade do SIN, e por qual fonte?

**F. Definição do fator horário (pergunta central)**
10. A tabela se intitula "Fator de Emissão Médio (tCO2/MWh) - HORÁRIO". O valor de cada hora é o fator das usinas que estão no topo da ordem de despacho naquela hora (a margem de operação por análise de despacho, em que se consideram as usinas marginais, com, no mínimo, 10% da geração da hora), ou é uma média de emissão do sistema naquela hora? Em qualquer dos casos, qual documento descreve a fórmula (por exemplo, a nota técnica) e quais usinas e combustíveis entram?
11. O fator inclui as importações líquidas de sistemas conectados? Como são tratadas, e as exportações são subtraídas ou não da geração usada?
12. A amostra inclui usinas registradas em programas de crédito de carbono, e há eletricidade fornecida por acordo de transmissão dedicada (PBWA) que deva ser excluída?
13. Qual é a delimitação do sistema elétrico que o MCTI adota para o SIN neste fator (todo o SIN, subsistemas) e com que base?

Agradecemos a atenção. Ficamos à disposição para esclarecer o propósito do uso.

[Assinatura e contato: a definir pelo responsável do produto]

---

## Minuta ao ONS (rascunho, não enviada)

**Assunto:** Dados abertos de despacho horário do SIN: formato, fuso, ordem de mérito e condições de uso

Prezados,

Preparamos uma ferramenta de apoio a projetos de crédito de carbono e usamos, como referência, a série horária de fator de emissão do SIN publicada pelo MCTI, cuja metodologia depende de dados do ONS. Gostaríamos de esclarecer:

1. **Dados disponíveis:** o ONS publica, em dados abertos, a geração horária por usina (ou por unidade geradora), por combustível/fonte e por subsistema, para todo o SIN? Com qual defasagem, e em que formato (CSV, API)?
2. **Ordem de mérito:** o ONS divulga a ordem de despacho por mérito (ou o custo variável unitário) de cada usina, por hora, que permita identificar as usinas no topo do despacho? Se não, qual dado público é o mais próximo?
3. **Fuso e convenção da hora:** nos conjuntos horários, qual é o fuso dos carimbos de tempo (por exemplo, horário de Brasília ou UTC), e o campo de hora marca o início ou o fim do intervalo? Isso vale para os anos anteriores a 2019, em que houve horário de verão?
4. **Importações e exportações:** como os intercâmbios com outros países aparecem nos dados horários?
5. **Combustível por usina:** há dado público de consumo de combustível por usina e hora, ou apenas de geração?
6. **Condições de uso:** qual é a licença dos conjuntos de dados abertos, e eles podem ser usados para calcular e publicar um fator de emissão derivado, com citação da fonte e da versão? Há restrição quanto ao uso em projetos de programas de crédito de carbono privados?
7. **Relação com o MCTI:** o ONS fornece ao MCTI uma base específica para o fator de despacho? Ela é pública?

Agradecemos a atenção.

[Assinatura e contato: a definir pelo responsável do produto]

### Mapeamento ONS × matriz VT0011/TOOL07
| Pergunta | Linha da matriz |
|---|---|
| 1, 2, 5 | R19 (dados primários de despacho), R11 |
| 3 | R15 (fuso e convenção) |
| 4 | R05, R06 |
| 6, 7 | R18 (direito de uso), R19 |

## Como usar
1. O responsável decide se envia e confirma o canal oficial (SIRENE ou ouvidoria/fale conosco do MCTI); a minuta não presume um.
2. Registrar data de envio, canal e resposta em `specification/DECISOES_E_DUVIDAS.md` (ou na issue dona) e, **se** houver resposta escrita, anexar o texto sem dados pessoais.
3. Uma resposta informal não substitui o parecer jurídico e a revisão normativa (Q03); ela só reduz dúvidas.

## Mapeamento pergunta × lacuna do dossiê
| Pergunta | Lacuna em CF-DATA-003 §3 |
|---|---|
| 1 a 3 | Licença e termos de uso (CC BY-ND; "exclusivamente MDL") |
| 4 a 5 | Resolução temporal (fuso das horas) |
| 6 a 7 | Autenticidade e edição exata (arquivos "corrigidos") |
| 8 | Vintage e margem de construção |
| 9 | CO₂ × CO₂e |
| 10 | R11 (topo do despacho × média) |
| 11 | R05, R06 (importações e exportações) |
| 12 | R12, R13 (usinas de programas de carbono, PBWA) |
| 13 | R04 (delimitação do sistema) |
