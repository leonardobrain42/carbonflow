# E6 — Arquitetura e contratos
Monólito modular observado no repositório: Angular, Quarkus, PostgreSQL, Flyway, Docker Compose, mesma origem HTTP. Não é novo ADR de microserviços. [README](../../README.md) contém comandos.

## Fronteiras
| Camada | Fonte canônica |
|---|---|
| Frontend | Navegação, formulários, projeção de states, tratamento de erro; não recalcula BE/PE/ER nem autoriza |
| Núcleo de conta/projeto | AccountAccess, DynamicProjectScope, contexto de organização/ambiente, grants |
| Piloto | PilotAccess e pilot_*; massa sintética isolada |
| Fonte/norma | catálogo contextual, direitos, revisões, candidatos e provenance |
| VM0038 OPEN | OpenApplicability, OpenE8Timing, OpenIrAssessments, OpenIrExAntePlans, OpenE8ProjectRuns; adaptador específico, não regra universal |
| Dados/calculadora | snapshots de frota, ratings, ED/EC, parâmetros e memória de cálculo, BigDecimal |
| Documentos | projeção de guias/fatos/rules/runs; compositor futuro dono #43; não duplicar o motor |
| Persistência | app_* e pilot_*, transações, FK/check/imutabilidade, migrações aditivas; políticas de revogação |

Interfaces concretas: [adapters](../../docs/CF-IMP-008-ADAPTERS-PROJETO-DINAMICO.md), [contextos metodológicos](../../docs/CF-OPEN-001-READINESS-CONTEXTS.md), [runner OPEN](../../docs/CF-OPEN-008-E8-PROJECT-RUN.md), [IR estruturado](../../docs/CF-OPEN-008-STRUCTURED-IR.md). Backend: [fonte](../../backend/src/main/java/br/carbonflow/pilot/), [migrações](../../backend/src/main/resources/db/migration/). APIs de escrita protegidas por versão/Idempotency-Key onde o contrato exige, não introduzir rota que ignore guarda. Autorização sempre no serviço.

## Extensão metodológica
Separar catálogo de método/edição/cenário, regra/entrada, cálculo, projeção e regime do ato; evitar condicionais globais VM0038 para todo projeto. Novo método exige pacote de regras, parâmetros, telas, gates, testes e validação próprios; o inventário extensível não comprova suporte completo. A matriz de ADR/OP-E6 original está pendente de recuperação; fronteiras registradas aqui não autorizam refatoração ampla não planejada.

## D17 — Contrato de plataforma
A fronteira acima é descrição do código observado, não arquitetura fixa por setor. A plataforma deve receber qualquer metodologia; programa, método, atividade, unidades, esquema, atos, regras e documentos são resolvidos por contexto e packs versionados. Preserve o E8 OPEN e PIL-SYN isolados. [Contratos lógicos e testes AGN](../CF-JOURNEY-001.md), [acoplamentos atuais](../CF-JOURNEY-001-AUDITORIA.md). Não iniciar refatoração geral nem criar três núcleos obrigatórios.
