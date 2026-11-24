# CF-OPEN-007 — Dy: registro estruturado da pesquisa (P0.3, primeira fatia)

Base #89, #96, #97, #101. O motor já contém a aritmética de Eq. 8; os pinos
legados armazenam um escalar e não representam a pesquisa de créditos.

## Entrega
- V82 aditiva (sem alterar V79–V81): avaliações imutáveis por projeto e ano
  relativo, com ator, revisão, janela anterior, SHA de conteúdo, idempotência
  e precondição de versão. Nenhum registro do piloto ou pino legado é migrado.
- A janela vem de OpenE8Timing.calendar(year-1), com início operacional/VCS
  coerente e recorte OPEN DCFC/BEV/SIN candidato. Ano 1 exige decisão específica,
  não se presume ano civil anterior. Calendário ausente ou recorte pendente
  só permite pesquisa inconclusiva. Mudança de triagem/janela exige rechecagem.
- ERC e ERF são totais declarados de créditos emitidos (tCO2e); a pesquisa
  registra programas, projetos/categorias, localizador e cobertura/limites.
  Esses campos são metadados declarados, não uma prova verificada.
- Pesquisa inconclusiva não tem valores. Créditos de frota encontrados exigem
  ERF positivo, ERC explícito (inclusive zero); prévia ERC/(ERC+ERF) no núcleo
  existente. Ausência de frota declarada exige ERF=0 e ERC explícito; prévia
  condicional 1, nunca valor aprovado nem default. Todos os registros
  mantêm usableForE8=false.
- Admin > Fontes > Pinos do projeto: nova seção Avaliação de créditos
  concorrentes, consulta por projeto/ano, formulário e histórico. Pino Dy
  legado identificado separadamente. Nenhum cálculo numérico no Angular.
- APIs staff GET /v1/platform/open-dy-assessments/{project}/{year} e POST
  /v1/platform/open-dy-assessments/{project}; MFA/manager/organização,
  If-Match e Idempotency-Key. GET /v1/projects/{project}/technical/open-dy-assessments/{year}
  reutiliza VIEW_OPEN_PARAMETER_BINDING e oculta os localizadores e textos de
  pesquisa do staff. Leituras no-store. Histórico limitado às 50 versões
  recentes na consulta; todos os registros permanecem no banco.

## Limites
Não prova abrangência nacional ou titularidade, não ingere registros oficiais,
não resolve rateio, não concede parecer independente, não congela a avaliação
num pino E8 e não altera o executor nem os cálculos históricos. A vigência
de licenças e revisão de fontes é responsabilidade de #92/#96; não inserir
conteúdo protegido nos campos de metadados. A prévia histórica não é aprovação
e um texto de ausência não constitui prova. Próxima fatia: evidência por
programa/projeto/versão, direitos de leitura e parecer independente; integrar
o executor somente com a avaliação aplicável e revisada.

## Verificação
Testes unitários da aritmética e cenários ausentes/inconsistentes, teste
Quarkus/PostgreSQL na fixture de pinos (histórico, calendário, precondição,
replay, ACL, segregação do pino, rechecagem), teste de navegador com mocks
do contrato (registro/versão, ano relativo, inconclusivo, acesso negado).
A execução depende do CI, não constitui homologação normativa.

## Fatia seguinte — parecer interno independente (V83)

A revisão usa o mesmo roster técnico dos fatores, MFA e organização, e é
separada da autoria da avaliação. A API oferece opções autorizadas, consulta
para gestores/revisores e POST por projeto/ID de avaliação, com If-Match da
versão vigente, hash exato, idempotência e um parecer imutável por versão.
CHECKED_INTERNAL, NEEDS_INFORMATION ou REJECTED; pesquisa inconclusiva não
pode receber checagem positiva. Revisão positiva é somente checagem interna
da declaração, NÃO admissão de fontes ou comprovação da ausência. Revisão
técnica registra regra, referências consultadas e razão; textos permanecem
restritos a staff. Nova avaliação não herda parecer, avaliação stale não
recebe novo parecer. Nenhuma decisão alimenta E8 nesta fatia.

A V83 é aditiva. O CI de recuperação V79 retira apenas no banco descartável
as tabelas e registros de migrations V82/V83 para simular o estado histórico,
e exige reaplicação de ambas. A validação real não é afrouxada.

Interface: a lista de projetos Dy tem endpoint próprio, acessível a gestores e revisores. O formulário de parecer aparece para o revisor na versão atual, não desatualizada e sem parecer. A autoria, o hash e o controle concorrente são rechecados no servidor. O teste de navegador cobre a transição de papéis e o pedido de informação para uma avaliação inconclusiva.

## Fatia V84 — individualização de emissões declaradas

Uma avaliação pode registrar até 100 emissões com programa, ID do projeto
no registro, ID da emissão, categoria ERC/ERF, data de emissão, BR, quantidade
em tCO2e, ID/edição de fonte externa cadastrada e localizador. O servidor
exige janela do ano anterior (fim exclusivo), ausência de duplicatas e soma
exata por categoria igual aos totais declarados. A lista canônica integra o
hash da avaliação e do comando; avaliações anteriores mantêm o hash original.
O acesso do projeto recebe apenas contagem e estado de conciliação, não os
registros detalhados. A checagem interna não vira admissão normativa.

A fonte é referência de metadados (edição cadastrada com URI oficial), NÃO
importação de números licenciados, validação de registros ou licença de uso.
Os números são declarações manuais. Não registrar conteúdo de acesso restrito.
Ainda não há comprovação de cobertura, ingestão oficial, decisão de direitos
por emissão, rateio nem integração ao executor. Uma avaliação sem itens
permanece UNITEMIZED; zero não comprova ausência. Não há migração de dados
históricos. V84 aditiva; CI reconstitui banco histórico apenas no fixture.

## V85 — direitos de fontes individualizadas

Opcionalmente, o gestor vincula uma licença exata por edição de fonte presente
nas emissões. Lista vazia mantém o modo declaratório legado, explicitamente
NO_LICENSE_PIN; lista não vazia exige cobertura integral e sem duplicatas.
Reutiliza o gate organizacional de PlatformSourceFiles para AUTHORIZED_PROCESSING,
EXTRACT_TEXT, SHOW_EXCERPTS e EXPORT_DERIVATIVES na mesma decisão aprovada,
independente, vigente e com prova. A decisão exata integra o hash da avaliação;
o replay do comando mantém sua identidade. Não escolhe licença substituta.
A leitura reavalia os direitos: expiração, revogação, mudança de versão ou perda
de capacidade tornam a avaliação stale, ocultam números e referências e impedem
novo parecer. A conta do projeto recebe apenas status e metadados de integridade
para avaliações licenciadas, sem números ou conteúdo de staff. Os registros
originais e pareceres permanecem imutáveis. Nova decisão exige nova avaliação.

A licença vigente não verifica a emissão, a cobertura nacional, rateio ou a
admissibilidade metodológica. Não há acesso automático a fonte externa, nem
habilitação de E8. A V85 é aditiva; o CI de upgrade histórico é atualizado.
