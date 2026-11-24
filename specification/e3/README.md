# E3 — Fluxos, papéis e autorizações
## Papéis
| Papel | Responsabilidade | Limite |
|---|---|---|
| PP | Proponente/representante com poderes comprovados | Nome declarado não concede representação |
| PREP | Preparação técnica, dados, narrativa e cálculos | Não autoaprova a própria versão |
| REV | Conferência/revisão interna por competência e escopo | Não substitui VVB/Verra |
| OP | Operação, medição, evidências | Acesso restrito à finalidade concedida |
| VVB | Organismo externo de validação/verificação | Ato externo, prova, independência e escopo próprios |
| Staff de fontes | Aquisição, avaliação e gestão de fontes | MFA, escopo organizacional; revisão segregada; não atribui PP/PREP/REV/VVB por conveniência |

Papéis planejados, grants, poder jurídico e competência são distintos. Regras de sessão, tenant e ACL no backend. Revogação afeta novas leituras e comandos conforme contrato; não reescreve autoria histórica. [#24](https://github.com/leonardobrain42/carbonflow/issues/24), [CF-IMP-008-ADAPTERS-PROJETO-DINAMICO](../../docs/CF-IMP-008-ADAPTERS-PROJETO-DINAMICO.md), [CF-IMP-013-CALCULATION-GRANT](../../docs/CF-IMP-013-CALCULATION-GRANT.md), [CF-ADMIN-LOCAL-SANDBOX](../../docs/CF-ADMIN-LOCAL-SANDBOX.md).

## Fluxo conceitual
Conta/organização → rascunho/triagem → guias e aplicabilidade → preparação de linha de base, adicionalidade, monitoramento e evidências em paralelo → cálculo/PD conforme requisitos → revisão interna → atos externos conforme regime. Monitoramento de um período pode prosseguir enquanto outro está em avaliação/emissão; M1/M2 piloto demonstra isso. Não usar sequência de telas como máquina de aprovação.

## Estados e atos
Rascunho, candidato, revisão, bloqueado, rechecagem, congelamento interno, registro de ato externo são distintos. PD não é MR; listagem, validação, registro, verificação e emissão têm autoria, dependências e prova próprias. Evidências históricas [E8-007](../../evidencias/e8-007/VERIFICACAO.md), [E8-008](../../evidencias/e8-008/VERIFICACAO.md), [E8-009](../../evidencias/e8-009/VERIFICACAO.md), [E8-010](../../evidencias/e8-010/VERIFICACAO.md). O fluxo OPEN continua condicionado a #93/#99/#101/#122. A matriz original de transições e eventos VCS não foi recuperada; não usar ensaio sintético como aprovação externa.
