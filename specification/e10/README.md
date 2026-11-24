# E10 — Evolução e critérios de release (índice novo)
Não confundir “funcionalidade implementada” com “requisito satisfeito”, “dado admissível”, “homologação”, “ato externo”. [#101](https://github.com/leonardobrain42/carbonflow/issues/101) define SANDBOX_ONLY, PREPARATION_ASSISTED, INTERNAL_CALCULATION_REVIEWED, EXTERNAL_ACT_VERIFIED; gates G0..G5 por ato. [#122](https://github.com/leonardobrain42/carbonflow/issues/122) define marco PD interno completo, ainda aberto, sem exigir simular atos reais. Sem nova decisão de release nesta reorganização.

## Saída exigida
- Pacote metodológico/edição/regime e escopo definidos e autorizados.
- Conjunto positivo com fontes/entradas canônicas, cálculos reproduzíveis e revisão independente; cenários bloqueados provados.
- Documentos com template, manifesto, claims, autoria, revisão, variantes e acesso aplicável; PD e MR separados.
- Teste de revogação, cross-tenant, recuperação, idempotência e migração; avaliação UX/acessibilidade, operação e custos representativos.
- Relatório com commit/build, resultados, dados/licenças, provas e pendências; responsável competente decide escopo de liberação. VVB/Verra/VCUs somente com retorno externo autoritativo.

## Evolução multi-metodologia
Antes de acrescentar uma metodologia: comparar conceitos e fontes, identificar requisitos e dados específicos, adapters, estados e contratos compartilhados; evitar generalizar a VM0038 por cópia. Validação do método/edição e pacote de testes independente. Manter núcleo determinístico, IA opcional e governada. Revisar [decisões](../DECISOES_E_DUVIDAS.md) e [pendências](../PENDENCIAS.md).
