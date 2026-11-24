# CF-OPEN-007 — Prova canônica de atualização anual de IR (V91)

Continuação do PR #233. A rota OPEN LDV DCFC SIN BR permanece preparatória.

## Contrato
Para novo parecer CHECKED_INTERNAL, POST /v1/platform/open-ir-assessments/{project}/{assessment}/review recebe também priorAssessmentId. O servidor resolve a avaliação anterior no mesmo projeto. Não aceita hashes ou números de frota, ratings ou fator fóssil fornecidos pelo cliente. A chave de idempotência inclui o ID anterior; replays legados preservam o fingerprint original, mas não ganham prova retroativa.

Requisitos conservadores: ano de projeto e ano de fonte consecutivos; avaliações em ordem de registro; rota anual específica; snapshots de frota, ratings e candidato EFfuel distintos; ciclo de ratings consistente; referências vigentes dos dois períodos; ausência de parecer anterior rejeitado ou solicitando complementação. Valores numéricos iguais não impedem atualização. O primeiro ano sem anterior e casos com calendário não consecutivo permanecem pendentes, não recebem uma aprovação artificial.

V91 persiste apenas metadados, IDs, hashes e política OPEN-IR-ANNUAL-EVIDENCE@1 vinculados ao parecer e às duas avaliações imutáveis. FKs compostas impedem vínculo cruzado de projetos, anos e hashes. GET de histórico retorna annualEvidence, com estados CONSECUTIVE_REFERENCES_NOT_ADMITTED, LEGACY_EVIDENCE_MISSING ou STALE_EVIDENCE_NOT_ADMITTED. Ausência ou obsolescência da prova suprime o candidato e sinaliza rechecagem. Não há cópia de números licenciados. Histórico e decisões antigas são preservados.

## Limites
- Dois conjuntos de anos consecutivos demonstram continuidade documental das referências, não comprovam que a atualização ocorreu no prazo metodológico, nem a atualização de todos os anos do período de crédito.
- O par ainda depende de handoffs de monitoramento e de um calendário consistente. Não cobre avaliação inicial ex ante ou a exceção de ano de crédito atravessando anos de fonte sem correspondência consecutiva. Revisão normativa e decisão de admissibilidade continuam necessárias.
- Não há integração Angular ou E8; usableForE8 continua false. Nenhum cálculo ou pin escalar legado é modificado.
- Testes unitários da comparação e teste de catálogo PostgreSQL V91 adicionados; falta ensaio positivo de API com dois conjuntos reais completos, replay e revogação. CI ainda deve confirmar execução.
