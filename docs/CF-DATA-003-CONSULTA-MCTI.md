# CF-DATA-003 — Minuta de consulta ao MCTI sobre uso dos fatores de emissão do SIN

**Estado: RASCUNHO. Não foi enviada.** Quem envia, por qual canal e em nome de quem é decisão do responsável do produto; o canal oficial não foi confirmado (a página de fatores não traz contato). A minuta é genérica de propósito: não identifica pessoas, clientes nem projetos. Uma resposta do MCTI, se vier, **informa** a revisão normativa e jurídica; **não** admite a fonte por si (isso segue a trilha V37/V60/V61). Contexto: [CF-DATA-003-SIN-MCTI-DOSSIE](CF-DATA-003-SIN-MCTI-DOSSIE.md), seções 2.2 e 3.

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

Agradecemos a atenção. Ficamos à disposição para esclarecer o propósito do uso.

[Assinatura e contato: a definir pelo responsável do produto]

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
