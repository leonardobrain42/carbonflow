# CF-OPEN-007 — Evidência temporal das atualizações anuais (V96)

Continuação de #96/#97 e #241. A VM0038 v1.1 §10.1 (pp.20–21) vincula IR=1 ao cálculo com parâmetros atualizados anualmente; §10.2 descreve frequência de monitoramento. Fonte: https://verra.org/documents/vm0038-electric-vehicle-charging-systems-v1-1/ . Não há nessa disposição um dia único fixado para atualização dos quatro parâmetros. A janela abaixo é uma política conservadora interna, não um prazo normativo adicional.

## Contrato
- Novo parecer CHECKED_INTERNAL / OBSERVED_UPDATE exige quatro evidências: EVR, AFEC, MPG, EF_FUEL. Cada uma contém data observada, vigência inicial e fim exclusivo, localizador documental. Sem valores numéricos introduzidos pelo cliente.
- O servidor deriva ano relativo e período do calendário e referências canônicas, em dias civis de Brasília; a data do parecer é capturada no PostgreSQL. Atualização deve ocorrer no ano do projeto e não após a data do parecer. Vigência declarada deve cobrir todos os dias tocados pelo período; não exige que o documento tenha sido produzido antes da coleta (revisão retrospectiva é possível). Não assume datas de publicação, início ou fim de validade a partir do vintage.
- A prova V96 é inserida atomicamente com parecer e evidência V91, com quatro referências distintas por métrica, hash de avaliação, ID canônico correspondente, janela congelada e hash composto. Verificação atual repete as regras e compara hash; para anos seguintes exige a cadeia anterior vigente. O primeiro ano permanece vinculado ao parecer inicial V95.
- Ausência de evidência datada em parecer legado exige rechecagem e nova avaliação, sem reescrever o histórico; comandos idênticos já registrados podem ser reproduzidos. Alteração do calendário, referências ou perda de vigência da prova anterior torna a prova obsoleta. Localizadores técnicos visíveis apenas à equipe autorizada; leitura do projeto mostra datas e hashes.
- A interface exige datas e localizador por métrica e mostra metadados de fontes efetivas. O backend valida novamente. Nenhuma modificação das migrações publicadas.

## Limites
As datas são declarações documentais examinadas pelo revisor independente da avaliação, não validação criptográfica de arquivos nem aferição automática da data real de publicação/atualização. A vigência declarada é diferente da vigência de licenças; a rechecagem de referências e licenças continua no contrato anterior. A regra de cobertura é aplicada ao período atual, não substitui a futura consolidação do ano inteiro nem garante que todos os períodos do ano usam a mesma linha de base. Primeiro ano segue FIRST_YEAR_REVIEW_REQUIRED. Nenhuma admissão normativa, VCU ou aprovação PD.

## Testes
Núcleo temporal: datas futuras, fronteiras do ano, lacunas, duplicatas, ordenação e hash; V96 FK/trigger, fixture histórica V79 e navegador simulado com parecer inicial e atualização anual. A homologação positiva com dados canônicos de dois anos, reexecução e revogação ainda precisa de teste integrado específico.

Próxima fatia: compartilhar a avaliação anual entre períodos compatíveis, distinguindo janela anual, ano da fonte, referências canônicas e vigência de cada período. Não reutilizar prova quando houver lacuna ou divergência de referência.
