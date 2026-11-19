# CF-OPEN — Primeira fatia: console consultivo de necessidades documentais

## O que entrega
- Migration `V42__open_network_source_needs.sql`: taxonomia **catalogada em PostgreSQL**, sem inserir números de fatores nem texto protegido, com 17 necessidades de VM0038 v1.1 para `OPEN_LDV_DCFC_SIN_BR`. Separação explícita de norma, dataset e prova específica do projeto. Fontes opcionais são **condicionais**.
- API `GET /v1/platform/source-readiness/VM0038`: agrega origem cadastrada em `app_source_version`, modalidade de autorização de referência e lista de necessidades. Usa conta de staff + MFA existente, audita leitura, `private,no-store`; recusa metodologia não catalogada. `app_norm_pack` atual só traz pacotes **SINTÉTICOS**, portanto `normativeRuleStatus` não pode ser READY.
- Página Angular `/workspace/platform/sources`: autenticador MFA, contagem de lacunas obrigatórias, listas filtráveis por etapa/situação, próxima ação contextual e links oficiais quando informados; view-only e responsiva. Link disponibilizado na navegação de conformidade apenas após MFA.
- Testes básicos JUnit e Playwright com autenticação simulada, filtro e 390×844.

## Significado dos estados
- `MISSING_SOURCE_METADATA`: edição candidata ainda não está cadastrada em `app_source_version`.
- `METADATA_ONLY`: fonte tem metadados, mas **não há autorização de referência reconhecida pelo agregador**; não significa que o PDF já foi ingerido, nem que o conteúdo foi licenciado.
- `REFERENCE_AUTHORIZED_NOT_ADMITTED`: há referência com permissão específica consultável; **ainda não existe parecer de admissibilidade** e não se afirma que pode alimentar o cálculo.
- `PROJECT_SPECIFIC_EVIDENCE`: a prova só pode ser obtida do proponente/operador de cada projeto, não de um catálogo global.

## Decisões e limites
A primeira fatia **não** implementa uploader/coletores, upload de documentos, revisão de fatores, datasets de estoque por modelo, regra normativa real, AI Gateway, decisão administrativa de fonte, cálculo ou PD. Essas partes estão explicitadas em #92–#101 e #6/#10/#16/#22. O texto protegido da Verra não deve ser copiado, extraído, incorporado em RAG ou disponibilizado a IA sem direito adequado, mesmo que exista um link público ou metadados.

O console é **interno** e reutiliza o autenticador MFA de `ComplianceStaff` apenas para **leitura de metadados globais**. Não fornece documentos privados de clientes. O controle posterior por privilégios independentes `VIEW_SOURCE_READINESS` e `MANAGE_NORMATIVE_SOURCES` é parte dos contratos de #90/#91/#22. Se a equipe de conformidade não estiver configurada, o recurso fica indisponível.

A fonte VM0038 1.1 atualmente cadastrada na V37 não está licenciada para extração/indexação e aparecerá como `METADATA_ONLY`. Os pacotes fictícios CF-DEMO-1/2 **não** satisfazem o lançamento de regras reais.

## Verificação antes do merge
1. Subir PostgreSQL conforme compose/documentação e backend Quarkus; aplicar V42. Confirmar 17 necessidades e que são exatamente as do seed.
2. Rodar testes Quarkus/Java e build Angular (com node 24), seguido de `npm test` com Chromium. Documentar resultados no PR; não declarar executados sem CI.
3. Testar como cliente normal: API e tela **não** acessam inventário, inclusive com URL direta.
4. Testar como staff MFA: desbloqueia, vê "Só metadados" para VM0038; ausente para Senatran/PBEV sem registro, e as evidências por projeto distinguem-se das fontes globais.
5. Confirmar que nova edição ou alteração de fonte no PG reflete na consulta; nenhuma contagem equivale a pronta para certificação.
6. Checar mobile 390×844 e desktop 1440×900, estado erro, refresh, navegação e limites de sessão.
7. Validar que PIL-SYN-BR-001, M1/M2, E8-005 e hashes permanecem idênticos.

## Próximos PRs
- #90: inventário por metodologia/edição/cenário com evaluator real tipado, versionamento, autorização operativa e paginação.
- #91: tabs Documentos/Bases/Admissibilidade/Alimentação/Histórico/Impactos + ações administrativas.
- #92: ingestão e revisão de licença e dados oficiais.
- #94–#97: frota por modelo, ratings, fatores e engine real.
- #99: VMD0049/regulatory surplus/direitos.
- #100: assistência IA e fallback sem autorização para decisões.
