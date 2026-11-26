# CF-OPEN-008 #97 — publicação administrativa de referências EVR/PBEV para projetos

**Entrega incremental:** catálogo nacional de frota e ratings continua **administrativo e preparatório**. Esta fatia incorpora uma referência explícita, imutável e rastreável a um projeto OPEN, sem copiar valores licenciados e sem habilitar E8.

## Antecedentes

- V48/#94 registra estoque EVR **em circulação**, nunca vendas do ano, por modelo brasileiro. O vetor tem origem, ano, território e SHA; cobertura nacional oficial ainda requer avaliação.
- V49/#95 registra ratings PBEV, pares fósseis GASOLINE e avaliação independente de categoria/capacidade/ciclo. Snapshot `PREPARATORY` não equivale a admissão Verra.
- V63/#97 associa ED/EC a EFel horário, sob QA e revisão interna; continua `CANDIDATE_NOT_ADMITTED`. O contexto de um mês/ano é derivado das horas UTC armazenadas, não digitado.
- Licenças são de organização e finalidade. Arquivos e valores derivados podem ser expurgados por revogação. **Números EVR, AFEC, MPG ou comparáveis não são copiados** para tabelas de projeto: apenas IDs e hashes.

## Jornada implementada

1. Equipe técnica do projeto cria o pacote de entrada horário ED/EC + EFel de V63, com fontes e parecer interno, sem resultados de emissões.
2. Administrador de fontes, com conta privilegiada e MFA, acessa **Admin CarbonFlow → Ratings PBEV → Publicar referências EVR/PBEV**.
3. O painel apresenta apenas projetos da(s) organização(ões) administradas, em SANDBOX/DRAFT e metodologia VM0038, com pacote V63 vigente, e o ano obtido de cada período (não há origem cruzando dois anos).
4. O gestor escolhe o projeto/período e um snapshot PBEV preparatório da **mesma organização e ano**. O servidor verifica o relacionamento entre o snapshot PBEV e a origem EVR em circulação, contagem exata de modelos, soma EVR, correspondência dos modelos, pares aprovados por REV e disponibilidade atual das licenças. Não há fallback internacional nem substituição de modelo sem par.
5. O sistema registra seleção vN em V64, com FK à versão ED/EC + EFel e snapshots V48/V49, SHA de cada fonte, período/ano, ciclo, assinatura SHA-256 interna e identidade do gestor.
6. O usuário com papel autorizado no projeto visualiza somente **versão, referências, hashes e impeditivos** na jornada E8. O catálogo bruto, valores licenciados e tabelas completas permanecem na área administrativa.

## Endpoints e autorizações

### Administração (somente gerente de fontes, MFA + organizações autorizadas)
- `GET /v1/platform/e8-fleet-selections/options` lista projetos/periodos com vínculo V63 atual e versão esperada do handoff, sem dados fonte.
- `POST /v1/platform/e8-fleet-selections` usa:
  `{"projectId":"...","period":"M1","energyBindingId":"UUID","ratingSnapshotId":"UUID"}`.
  Cabeçalhos `If-Match: "0"` (ou versão anterior) e `Idempotency-Key`.
  Resposta 201, ETag e seleção; 404 se organização/projeto/snapshot invisível, 409 se fontes incompatíveis, 412 se versão concorrente.
- Auditoria administrativa `ASSIGN_OPEN_E8_FLEET_TO_PROJECT` e `VIEW_OPEN_E8_FLEET_PROJECT_OPTIONS`; nunca usar o privilégio de colaborador para publicar diretamente dado do catálogo restrito.

### Leitura dentro do projeto
- `GET /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-fleet-selections`
- `GET .../e8-fleet-selections/{selectionId}`

Usam `DynamicProjectScope` com `VIEW_OPEN_E8_FLEET_SELECTION`, restrito a owner, PREP/REV habilitados em MONITORING. OP não possui o grant. Conteúdo é somente metadata, e o HTTP é `private, no-store`.

## Invariantes metodológicas, legais e de segurança

- V64 **nunca armazena fatores, pesos da frota, valores PBEV ou resultados de cálculo**. Preserva apenas identificadores e assinaturas, evitando contrariar os gatilhos de expurgo V48/V49.
- `state=PREPARATORY_NOT_ADMITTED` e `usable_for_e8=false` são invariantes do PostgreSQL; nenhum endpoint nesta fatia chama `OpenGridCalculationMath`. Não há BE/PE/ER/VCU produzido.
- `GET` revalida o vínculo V63 e o estado/fonte de V48/V49. Se MR, QA, EFel, revisão do pacote, fonte ou licença mudar, um vínculo anterior retorna `STALE_NOT_ADMITTED` sem sobrescrever o snapshot.
- O SHA interno protege a continuidade da referência armazenada, não prova autenticidade do documento externo. Rights podem proibir exibir ou exportar valor: todo número fica sob o catálogo/licença de origem.
- Apenas snapshot de frota `STOCK_IN_CIRCULATION` em `BR` e ratings da mesma organização, ano, modelos e GASOLINE com decisão aceita no projeto são elegíveis a um vínculo preliminar; a cobertura oficial `OFFICIAL_TOTAL_NOT_VERIFIED` e a vigência normativa continuam abertas.
- Um projeto que não possui snapshot nacional licenciado compatível vê explicitamente a pendência, sem percentuais artificiais, nem reaproveitar piloto `PIL-SYN-BR-001`.

## Limitações a resolver

A funcionalidade **não fecha #94/#95/#96/#97/#128**. Ainda faltam prova de cobertura/representatividade nacional e aplicabilidade ao mercado, permissão formal de redistribuir dados derivados, seleção e revisão real de EFfuel com combustível/ano/gases/unidades, regra e prova de IR e Dy, admissão normativa VT0011/TOOL07, runner E8 transacional com hash de todas as fontes, replay e stale, e projecção documental do PD. Quando isso existir, o executor E8 consumirá somente um `ProjectParameterBinding` admissível separado deste registro **candidato**.

## Testes

- JUnit para V64: recusa NEW_SALES, ano divergente, cobertura parcial, aliases/modelos divergentes, ausência de par aceito, denominador inconsistente, licença/fonte revogada, rating `STALE_REFERENCE`; massa inteiramente fictícia.
- Integração Quarkus/PostgreSQL: project GET vazio antes da publicação, isolamento OP/externo, inviabilidade de usuário de projeto publicar via endpoint administrativo e preservação dos requisitos V63.
- CI Angular, Quarkus/PostgreSQL/Flyway, navegador e sandbox; o PR não deve ser aprovado enquanto não estiver verde.

## Adendo de planejamento — finalidade PD ex ante (26/11/2026)
V64 continua sendo handoff monitorado; não tornar energia/período opcionais neste contrato. Para preparar PD sem MR, a #40 deve implementar seleção versionada por contexto e finalidade, consumindo V48/V49 e preservando as autorizações. Ver [especificação de UX e arquitetura](CF-UX-TECH-001-FROTA-BASELINE-EX-ANTE.md) e testes planejados BL-T01–17. Novo handoff não foi implementado neste documento.
