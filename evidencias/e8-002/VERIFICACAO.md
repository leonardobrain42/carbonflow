# E8-002 · Verificação local · 15/11/2026

**SIMULAÇÃO** · PIL-SYN-BR-001 · kit 0.2.14 · amostra `E8-002@1` · esquema Flyway V2.

A E8-001 satisfez a dependência de engenharia. Esta fatia usa sessão com identidades fictícias e autorização no servidor. Os dados vêm das contas, vínculos e projetos da [migração V2](../../backend/src/main/resources/db/migration/V2__pilot_access.sql) e das variantes sintéticas do [catálogo da aplicação](../../backend/src/main/java/br/carbonflow/pilot/ProtectedArtifacts.java). Cada artefato tem identificador, versão, finalidade, classificação, autor, revisor e hash. Os recibos vinculam a concessão à versão e preservam emitente e pessoa responsável pela captura em campos próprios.

## Resultado esperado e observado

| Caso e escopo | Esperado | Observado |
|---|---|---|
| CT-E5-05 · organização, projeto e papel | Org. de isolamento só alcança seu projeto. OP não lê artefatos de revisão. Sessão ausente, origem divergente e vínculos fora da vigência são rejeitados. | Exercitados dois projetos de organizações distintas, um projeto alheio da mesma organização, OP, PREP, REV, PP e VVB. A API retornou HTTP 401, 403 ou 404 conforme o caso. A resposta a um ator externo ao projeto foi equivalente para artefato existente e inexistente. |
| CT-E5-05 · finalidade, versão e vigência | VVB busca, abre a prévia e baixa só a versão aprovada no escopo vigente. | Concessão de auditoria V1 autorizou a variante pública v1. Bloqueados o conteúdo da v2, a prévia reservada, a finalidade divergente e as leituras após expirar o vínculo ou a concessão. Respostas protegidas usam `Cache-Control: no-store`. |
| CT-E5-16 · autoria e variante | PREP prepara, outro REV aprova. A versão aprovada mantém os bytes e a variante pública preserva apenas os campos autorizados. | A tentativa da mesma identidade com ambos os papéis obteve HTTP 403 `AUTOAPROVACAO_NEGADA`. O REV distinto aprovou. O hash e os bytes da variante pública permaneceram iguais e o marcador reservado ficou restrito à variante própria. |
| CT-E5-05/16 · concessão e revogação | PP concede e revoga a versão exata. O histórico conserva recibo e leituras anteriores; novas buscas e URLs diretas deixam de retornar o artefato. | Busca, prévia e download funcionaram com concessão ativa. Após revogação, a busca omitiu a versão e ambas as URLs retornaram HTTP 404 ao VVB. A trilha manteve leituras permitidas, negações e recibo com emitente SIM-VVB-A e captura SIM-PP. |
| CT-E5-38 · recorte de teclado | Entrar e executar o fluxo de autorização com foco utilizável. | No Chrome, o roteiro usou Tab e Enter para entrar, preparar, aprovar, conceder, buscar, abrir prévia, baixar, capturar e revogar. Verificados foco no título após login, no campo de identidade após logout e no título após abrir a prévia. |
| Regressão E8-001 · migração e cálculo | Histórico consultável sob sessão e totais iguais. | Flyway aplicou V2 ao volume já usado no Compose. O relatório e o snapshot anteriores foram recuperados byte a byte e o mesmo endereço sem sessão retornou HTTP 401. A regressão recalculou M1 6,00 e M2 5,30 tCO₂e com EF-SYN-v1 e manteve M2 em coleta. |

[Trilha sintética extraída do PostgreSQL da demonstração](trilha.json).

## Execução

- `./mvnw clean verify` executou 11 testes Java. Quatro de cálculo e calendário, quatro de API e persistência do cenário anterior e três grupos de integração E8-002 em PostgreSQL. As suites estão no [backend](../../backend/src/test/java/br/carbonflow/pilot/).
- O build Angular e o build de imagem Compose passaram. Dois roteiros automatizados rodaram em Chrome sobre o pacote do Compose com PostgreSQL 16.16. Os [testes de navegador](../../frontend/test/) usam a origem local da aplicação.
- Os controles de concorrência desta fatia usam versões esperadas e chaves de idempotência. Testados reenvio, conflito de chave, versão obsoleta e recusa de uma segunda concessão vigente para a mesma pessoa, finalidade e versão. O exercício integrado de recuperação permanece no backlog da E8-011.

## Pendências

- **PEN-006.** Leonardo designa revisor competente e independente. O revisor documenta parecer, escopo e tratamento das ressalvas.
- **PEN-009.** A revisão técnica deve concluir a conferência setorial com acesso integral e autorizado à ISO 14065:2020 nas cláusulas aplicáveis.
- **PEN-008 e E8-014.** Provedor de identidade e recuperação de credenciais, jornadas integradas com cinco sessões de participantes, cinco esboços e ensaios de homologação permanecem pendentes. A navegação do recorte executada no Chrome é automatizada.
- A preparação usa variantes de amostra para auditoria V1. A geração de dossiês PD/MR e o fluxo de auditorias e pareceres ficam nas E8-007 e E8-009. As credenciais e o recibo deste ensaio pertencem à demonstração sintética.
