# CF-OPEN-008 — Seleção de frota ex ante para o PD (BL-EX-01)

Implementa a fatia **BL-EX-01** do contrato [CF-UX-TECH-001-FROTA-BASELINE-EX-ANTE](CF-UX-TECH-001-FROTA-BASELINE-EX-ANTE.md). **Nível D17: projeto** (OPEN BR LDV BEV DCFC SIN GRID_ONLY, VM0038 v1.1); a regra de ano é política interna do projeto (D22), não regra do método. Migração aditiva **V118**. A seleção monitorada V64 **não foi alterada**. **Nada é admitido; `usableForE8=false`.**

## O que existe
Uma referência de frota **ex ante** do projeto: aponta para os snapshots EVR e de ratings da plataforma e registra a **data de referência declarada**, sem período de monitoramento, sem vínculo de energia e sem MR (critério BL-T01).

| Operação | Rota | Quem |
|---|---|---|
| Opções | `GET /v1/platform/open-baseline-fleet-selections/options` | staff com MFA e competência de gestor de fontes |
| Histórico do projeto | `GET /v1/platform/open-baseline-fleet-selections/{project}` | idem |
| Confirmar | `POST /v1/platform/open-baseline-fleet-selections` (`Idempotency-Key`, `If-Match`) | idem |

Corpo do POST: `projectId`, `referenceDate` (AAAA-MM-DD), `ratingSnapshotId`, `rationale` (15 a 1000 caracteres). Não há campo de valor, peso, AFEC ou MPG.

## Regras
- **Ano de observação (OPEN-BASELINE-FLEET-YEAR@1, D22):** o ano é o ano civil da **data de referência declarada**. O **início de creditação do projeto precisa estar resolvido** (calendário V88 sem gate), senão 409. O ano do snapshot precisa ser igual ao ano de observação (409). O ano nunca vem da data de hoje e nunca é convertido em ano relativo do projeto.
- **Validação reutilizada da V64** (`OpenE8FleetSelections.check/coverage/rightsCurrent`): estoque em circulação BR, estados preparatórios, denominador, mesma população de modelos, pares aceitos e licenças vigentes no momento do uso. Nenhum dado licenciado é copiado.
- **Versão e concorrência:** uma sequência por projeto (`If-Match` com a versão atual, 412 se obsoleta); a mesma chave com o mesmo corpo repete o registro, com outro corpo é 409; a mesma referência de novo é 409. Registros imutáveis (gatilho) e histórico preservado.
- **Estado derivado na leitura:** `STALE_NOT_ADMITTED` se o início de creditação mudou ou deixou de resolver, se os snapshots mudaram, foram suspensos ou perderam licença, ou se há versão posterior. Mesmo vigente, a resposta traz `EVR_AND_RATINGS_NOT_NORMATIVELY_ADMITTED` e `EX_ANTE_REFERENCE_IS_NOT_AN_E8_INPUT`.
- **Auditoria:** `VIEW_OPEN_BASELINE_FLEET_OPTIONS`, `VIEW_OPEN_BASELINE_FLEET_SELECTIONS`, `ASSIGN_OPEN_BASELINE_FLEET_EX_ANTE`.

## Critérios do contrato cobertos
BL-T01 (sem dossiê, energia ou MR; referência exata, sem admissão), BL-T06 (somente staff; dono do projeto e anônimo não gravam), BL-T07 (replay, chave divergente, concorrência, histórico imutável). Ano de observação coberto pelo teste `OpenBaselineFleetSelectionIntegrationTest`.

## Leitura pelo projeto e tela da etapa 2 (BL-EX-02, rc.99)
**Nível D17: projeto** (OPEN BR LDV BEV DCFC SIN GRID_ONLY). Migração aditiva **V120**: só amplia a lista de ações de auditoria de acesso técnico com `VIEW_OPEN_BASELINE_FLEET_CONTEXT`.

| Operação | Rota | Quem |
|---|---|---|
| Contexto de frota ex ante | `GET /v1/projects/{project}/technical/baseline/fleet-context` | dono do rascunho e colaborador com a atividade CALCULATION; `private, no-store`; auditado |

A resposta é só metadado: `state` (`NO_EX_ANTE_REFERENCE`, `PREPARATORY_NOT_ADMITTED` ou `STALE_NOT_ADMITTED`), início de creditação, versão vigente, histórico (até 20), `gaps` (código, responsável e próxima ação) e `notes`. Sem seleção, a tela explica a lacuna e **não mostra zero, percentual nem ano inventado**. `usableForE8` é sempre falso; quem não é membro recebe 404 (não confirma o projeto). A **confirmação** continua só de staff (BL-EX-01): não há POST de colaborador.

A etapa 2 (Linha de base) mostra primeiro a frota de referência ex ante e depois os vínculos por período monitorado; a ausência de períodos de monitoramento deixa de ser apresentada como pendência da etapa. Critérios cobertos: BL-T02 (estado vazio, sem snapshot nem percentual; próxima etapa acessível), BL-T06 (acesso), parte de BL-T14 (restrição sem dado stale). Não cobertos: BL-T13 (1440×900 e teclado), BL-T17 (teste humano).

## O que ainda não faz
- Uso da referência no **PD** e no diagnóstico por ato: BL-EX-03. A leitura atual do PD continua a monitorada, sem fallback.
- **Dados reais**: o teste usa os snapshots **sintéticos** da fixture. Sem estoque EVR e ratings reais importados e revisados (BL-DATA-01), não há o que selecionar no ambiente de trabalho.
- Não coberto por teste: início de creditação não resolvido (a fixture sempre o resolve); BL-T02 a BL-T05 e BL-T08 em diante dependem de dados e de interface.
