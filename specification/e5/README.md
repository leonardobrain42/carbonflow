# E5 — Requisitos e critérios verificáveis
A [matriz](../MATRIZ_RASTREABILIDADE.md) é uma síntese verificável por capacidade, não substitui RF-E5 ou CT-E5 originais. Os critérios completos permanecem nas issues proprietárias. Novos requisitos devem declarar ator, gatilho, precondição, insumo, regra, fonte/edição, permissão, exceção, evidência, auditoria, saída, teste e limite de aceite.

## Invariantes transversais
- Projeto e ambiente resolvidos no servidor; sem fallback a SIM e sem acesso cross-tenant.
- Princípio de menor privilégio, poderes/grants separados, segregação de autor/revisor, revogação imediata conforme finalidade.
- Versões e hashes congelados; nova revisão torna dependentes obsoletos, não reescreve registros.
- Ausência, zero, dado inválido, fonte não admitida, pendência normativa e recorte não suportado têm estados distintos.
- Regra/cálculo determinístico no backend, unidade e janela explícitas; UI e documentos projetam a referência canônica.
- Aprovação interna não equivale a admissão normativa, VVB, registro, verificação ou VCU.
- Preparação independente de lacunas; dependência bloqueia o ato aplicável, não toda a autoria.
- IA opcional e condicionada a direitos; não aprova parâmetro, norma, claim ou ato.

## Aceite por entrega
Uma fatia só se considera verificada quando resultado esperado/observado, teste, commit, ambiente e provas são identificados. Critério da issue dona permanece aberto até evidência de todos os cenários incidentes. Para UX, [contrato RF-1..RF-9](../../docs/ux/CF-UX-ACOMP-REQUISITOS.md) e [matriz de harmonização](../../docs/ux/CF-UX-ACOMP-RASTREABILIDADE-ISSUES.md) preservados. Para aceite integral, [#101](https://github.com/leonardobrain42/carbonflow/issues/101) e [#122](https://github.com/leonardobrain42/carbonflow/issues/122). Não declarar cobertura completa do kit original enquanto RF/CT não estiverem mapeados.
