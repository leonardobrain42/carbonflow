# CF-OPEN-001 — Inventário extensível por metodologia, edição e cenário

## O que está implementado
- `V43__methodology_source_contexts.sql` adiciona contextos dimensionados por metodologia/edição/cenário e classifica cada necessidade: etapa, capacidade bloqueada, edição (exata, anual ou mais recente), prioridade e responsável. O seed existente da VM0038/1.1 permanece intacto; outras metodologias e cenários podem entrar por SQL/migrations sem alterar componentes Angular.
- `GET /v1/platform/source-readiness/contexts` lista os contextos cadastrados.
- `GET /v1/platform/source-readiness/{methodology}?edition=<version>&scenario=<code>&stage=<stage>&status=<status>&offset=0&limit=25` consulta um contexto com filtros e paginação de até 100 itens por página. Se a metodologia tiver múltiplos contextos, especificar edição e cenário é obrigatório.
- A resposta mantém contagens sobre **todo o contexto**, enquanto `total`, `nextOffset` e `needs` descrevem apenas a seleção filtrada. Isto evita que filtrar a tabela altere artificialmente as lacunas gerais.
- O backend confronta cada necessidade com a edição presente em `app_source_version` e a autorização específica mais recente de `app_content_license`/decisões/capacidades, **somente nos escopos organizacionais explicitamente autorizados ao staff**.
- `YEAR` e `LATEST` são seletores, **não números de edição**. Mesmo quando uma versão candidata aparece, o estado é `VERSION_SELECTION_REQUIRED`; não há admissão automática de EF, rating ou frota.
- O serviço não consulta documentos de clientes nem expõe contratos, PII, parâmetros de projeto ou conteúdo de PDFs. Controles de IA bloqueados globalmente continuam prevalecendo sobre capacidades eventualmente listadas numa licença.
- O Angular apresenta opções retornadas pelo backend, sem fixar VM0038; filtros e paginação são server-side.

## Permissão de visualização exclusiva
Configurar `carbonflow.platform.source-readiness.viewers` (variável de ambiente equivalente `CARBONFLOW_PLATFORM_SOURCE_READINESS_VIEWERS`) com UUIDs de identidade de staff, separados por vírgula. **Padrão seguro: nenhuma identidade autorizada.**

O acesso exige cumulativamente:
1. Conta ativa e sessão;
2. Identidade presente no cadastro MFA de `carbonflow.compliance.staff`, com autenticação recente;
3. Identidade no cadastro `carbonflow.platform.source-readiness.viewers`.

Este último controle separa **VIEW_SOURCE_READINESS** da capacidade de decidir poderes de representação: uma identidade autorizada a conformidade não recebe leitura do inventário por consequência. A lista pode ser gerenciada via implantação/secret config até existir workflow interno de grants de plataforma (escopo futuro #91/#22).

`GET /contexts` e `GET /{methodology}` retornam `Cache-Control: private, no-store` e auditam ator/ação. A auditoria não grava o conteúdo do documento.

## Interpretação rigorosa dos estados
- `PROJECT_SPECIFIC_EVIDENCE`: prova de contrato, monitoramento ou PD que deve ser fornecida pelo PP/OP; **não é falta da biblioteca global**.
- `MISSING_SOURCE_METADATA`: fonte/edição não encontrada no catálogo.
- `METADATA_ONLY`: identificador/edição encontrados, sem direito de referência admissível no escopo consultado.
- `VERSION_SELECTION_REQUIRED`: uma fonte existe, mas a edição `YEAR` ou `LATEST` precisa ser selecionada e fundamentada por ano/ato antes de utilização.
- `SCOPED_REFERENCE_NOT_ADMITTED`: direito de referência conferido para uma organização do staff; **não** é direito global da plataforma, autorização para IA ou admissão técnica para cálculo.

`technicalAdmissionConfirmed` permanece `false` neste módulo porque a aprovação por finalidade/vintage e os parâmetros tipados pertencem ao pipeline #92 e à engenharia #94–#99. Regras do pacote `CF-DEMO` nunca indicam pacote Verra operacional.

## Contrato de integração futura
#92 introduzirá resultados de ingestão, licença/autorizações por operação e revisão técnica. #94–#98 fornecerão estados de EVR, comparáveis, fatores, medição e cálculo. Essas fontes serão incorporadas ao **mesmo** retorno do inventário, sem criar segundo grafo de prontidão.

## Como testar
1. Aplicar migrações no PostgreSQL e executar a suíte Quarkus, incluindo `PlatformSourceReadinessIntegrationTest` e `PlatformSourceReadinessTest`.
2. Configurar IDs de staff e leitor na inicialização. Com sessão sem MFA: 403; staff com MFA mas sem permissão VIEW_SOURCE_READINESS: 404; staff autorizado: 200. Nunca tratar client PP/PREP/REV/OP como staff.
3. Verificar `/contexts` contém VM0038/1.1/OPEN_LDV_DCFC_SIN_BR; registrar contexto `METHX` com suas necessidades no PostgreSQL e consultar sem alterar Angular.
4. Consultar com `stage=BASELINE&status=MISSING_SOURCE_METADATA&limit=1`; verificar contagem, `nextOffset` e página seguinte. Parâmetros inválidos: 400.
5. Cadastrar metadados para uma edição `YEAR` e confirmar que não viram dados admitidos. Cadastrar e depois revogar licença limitada de referência: status deve voltar a METADATA_ONLY e a permissão de RAG permanecer bloqueada.
6. Rodar `npm run build` e `npm test` no frontend com Chromium (incluindo viewport móvel).
7. Confirmar que o piloto sintético E8 não muda e que nenhum status de VCU, Verra ou VVB foi introduzido.

**Fora deste PR:** uploader/parser de fontes, aprovação técnica, licenças globais de conteúdo, publicação da VMD0049 como regra real e autorização de execução BE/PE/ER. Esses gates continuam em #92 e #94–#101.
