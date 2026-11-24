# E7 — Validação e homologação
## Evidência e verificação
- [E8-013](../../evidencias/e8-013/VERIFICACAO.md): identidade do kit, oráculo, E7, regressão sintética M1 6,00/M2 5,30 tCO₂e; CT-E5-35 (parecer independente) pendente naquele registro.
- [E8-014](../../evidencias/e8-014/VERIFICACAO.md): IMP-01..08, amostra autorizada 30 dias, ambiente, autorizações, rollback, carga, recuperação, UX e custo. Ensaio de preparação não equivale a homologação.
- [CI](../../.github/workflows/ci.yml): Angular build/browser, Quarkus/PostgreSQL, Flyway e demo. [Estado do CI no corte](../ESTADO_DO_TRABALHO.md). Falha de infraestrutura precisa de nova execução; não converter em verde.
- [Testes Java](../../backend/src/test/java/br/carbonflow/pilot/) e [browser](../../frontend/test/) — localizar por contrato e versão, não inventar resultado.
- [#101](https://github.com/leonardobrain42/carbonflow/issues/101): cenário positivo sintético OPEN com oráculo independente, positivo real condicional, negativos de dados, fontes, escopo, temporalidade, segurança, UX e operação.

## Próximos testes indispensáveis de IR
Duas avaliações canônicas em anos consecutivos com V94/V95/V96, parecer de autor distinto, evidência datada para cada parâmetro, seleção por contexto, execução e reexecução com memória, recusa de ano 1 e de período incompatível, ano 1 ex ante (com plano vinculado, sem plano, plano obsoleto, revisor igual ao autor), reuso da mesma avaliação em dois períodos do mesmo ano (e recusa quando uma referência, a janela ou a vigência diverge), licença revogada, troca de referência/plano, replay e isolamento. Verificar efeito no PD e consolidação. Testes de núcleo, catálogo SQL e mocks não substituem este cenário integrado. Depois, amostra real autorizada e revisão normativa independente.
## Registro mínimo de ensaio
Identificador de caso/requisito, SHA de build, edição/metodologia, ambiente, massa/origem/licença, janela, atores e papéis, precondição, esperado/observado, unidade/hash, logs/provas, resultado aprovado/reprovado/impedido/N/A com justificativa, responsável e efeito no release. Guardar resultado do CI por run/commit; não publicar conteúdo restrito.
