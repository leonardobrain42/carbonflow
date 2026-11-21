# CF-UX-TECH-001 — UX-02B · Formulários tipados por metodologia (VM0038)

**Issue:** #124; continua UX-02A (#138) e usa o cadastro canônico de estações/carregadores (#139).

## Contratos entregues

- **Referências versionadas:** cada revisão tipada persiste `reference_versions` de estação e carregador; atualização no inventário retorna `STALE_REFERENCE_REVIEW_REQUIRED` sem alterar o snapshot histórico. Uma mudança de DCFC para AC retira o equipamento da lista de opções, considerando **somente a versão mais recente**.
- **V68** adiciona `app_typed_guide_schema` (definição, edição, ramo e versão), `app_typed_guide_revision` (snapshot imutável da definição + respostas JSON tipadas) e `app_typed_guide_command` (idempotência). **Não modifica** `app_technical_guide_revision`, os hashes do piloto ou as respostas históricas.
- **API:** `GET /v1/projects/{project}/technical/typed-guides`; `PUT /.../typed-guides/{guide}` com `If-Match: "n"`, `X-Guide-Definition-Version: 1`, `Idempotency-Key`; `GET /.../typed-guides/{guide}/revisions/{version}`. Usa `VIEW_GUIDES` e `SAVE_GUIDE` com segregação entre projetos, e monitoramento quando seleciona station/charger.
- **Tipos:** TEXT/TEXTAREA (contexto), DATE, BOOLEAN JSON real, ENUM, YEAR inteiro 1900–2100, DECIMAL numérico com limites/unidade contextual, MULTI_SELECT como array sem duplicata, STATION_REF e EQUIPMENT_REF restritos à organização/projeto e capturados a partir do owner #98. `optionParents` limita equipamentos à estação selecionada no cliente, mas **o Quarkus também valida**.
- **Condições tipadas:** `showWhenField + showWhenValue`, `requiredWhenField + requiredWhenValue` com `SET`/igualdade (sem `eval`, scripts dinâmicos ou regra normativa implementada no Angular). Aplicabilidade "Não aplicável" pede justificativa; alteração remove campo oculto no cliente e recusa submissão malformada no servidor.
- **Definições por pacote:** seis guias `VM0038_V1_1`, edition `1.1`, branch `OPEN_BR_BEV_LDV_DCFC_SIN`. Outro método retorna apenas os próprios schemas disponíveis, sem herdar VM0038. Extensível por novas revisões de definição sem sobrescrever o schema antigo.
- **Preservação do legado:** `legacyAnswers` mostra o original, `legacyNeedsReview` impede coerção automática de texto em tipo/referência. Cada stream tem seu número de revisão. Read de revisão tipada usa `definition_snapshot` congelado.
- **UX:** novo editor de dados estruturados na tela de cada guia; renderer por tipo e ajuda contextual, visibilidade dinâmica, estado declarativo, validação de faltantes, navegação sem descarte involuntário, histórico, mobile. A tela antiga segue disponível como anotações textuais e não é promovida a fato canônico.

## Estados e limites

`NOT_STARTED`, `LEGACY_UNSTRUCTURED_REVIEW_REQUIRED`, `INCOMPLETE_DECLARED`, `FORM_COMPLETED_DECLARED` **nunca são aprovação**. `normativeApproved=false` sem exceções neste recorte. Dados de consumo ilustrativos não alimentam E8; refs de station/charger não constituem delimitação da fronteira nem provas admitidas. Não gera PP, atos VCS, ED/EC, PDF ou VCU.

## Testes

Integração PostgreSQL: catálogo/edição, read-only por escopo, legado preservado, rejeição de falso boolean, ano string, data inválida, número fora de precisão, multiselect duplicado, campo condicional invisível, referência canônica, chave idempotente, ETag obsoleto, limites de versão e separação E8.

Playwright 390×844: transição condicional da justificativa, estação e carregador do projeto, tipagem booleana efetiva, tentativas inválidas, salvamento, mudança de decisão, recarga e dados textuais legados intactos.

## Próximas integrações

- Criar `EVIDENCE_REF` e `DOCUMENT_REF` somente ao conectar owner documental #33/#92, com ACL, hash e review.
- Formalizar AST tipada de condições mais expressivas (`AND/OR`, dependências de atos), com validação de schema e suite de edição/ramo alternativo; este PR **não** afirma suporte genérico a qualquer regra metodológica.
- A tela de guias, quando o formulário tipado carrega, mostra o texto antigo só como histórico. Esse texto não salva de novo e não aprova a etapa. Sem formulário tipado, o editor textual continua sendo o único caminho.
- Ligar `outputToDocumentBlockMapping` do #43, direito/cronologia #24/#38/#99 e status específicos do PD. Não antecipar dados normativos ou VVB.
