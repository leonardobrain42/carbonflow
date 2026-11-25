# CarbonFlow — especificação viva
**Linha de base documental:** CF-SPEC 0.3.0-rc.1 (proposta, não versão de software nem nova edição normativa). Inventário reconciliado com main `507bf268d6955cd0c317d2ff404a905009985907` (merge #242), 24/11/2026.

Esta árvore organiza a especificação **existente**, os contratos atuais e as lacunas. Não é transcrição do kit histórico 0.2.14, cujos ZIPs não estão acessíveis nesta sessão. Não certifica conformidade e não encerra issues. Os documentos técnicos de entrega continuam em [docs/](../docs/); as evidências históricas em [evidencias/](../evidencias/). A fonte de verdade tem camadas: norma oficial e licença aplicável para obrigação externa; decisão/requisito aprovado para intenção; serviço/teste/migração para comportamento observado; issue para trabalho e aceite; parecer e prova para liberação.

## Comece aqui
1. [ESTADO_DO_TRABALHO](ESTADO_DO_TRABALHO.md) — recortes, limites, última validação e próximos passos.
2. [DECISOES_E_DUVIDAS](DECISOES_E_DUVIDAS.md) — decisões documentadas, políticas internas e questões abertas.
3. [MATRIZ_RASTREABILIDADE](MATRIZ_RASTREABILIDADE.md) — requisito de síntese → issue proprietária → contrato → teste/evidência → lacuna.
4. [PENDENCIAS](PENDENCIAS.md) — PEN históricas e lacunas atuais, sem presumir encerramento.
5. [GOVERNANCA](GOVERNANCA.md) — como decidir e atualizar a especificação.
6. [CATALOGO_DOCUMENTAL](CATALOGO_DOCUMENTAL.md) — índice dos arquivos em docs (86 no commit de referência + contratos posteriores).
7. [CHANGELOG](CHANGELOG.md) — alterações da especificação.

## Organização
| Área | Conteúdo |
|---|---|
| [E0](e0/README.md) | origem, recuperação e integridade do kit |
| [E1](e1/README.md) | fontes, vigência e admissibilidade |
| [E2](e2/README.md) | método, parâmetros, quantificação |
| [E3](e3/README.md) | fluxos, estados e papéis |
| [E4](e4/README.md) | dados, evidências e documentos |
| [E5](e5/README.md) | requisitos e critérios verificáveis |
| [E6](e6/README.md) | arquitetura e fronteiras |
| [E7](e7/README.md) | testes, validação e homologação |
| [E8](e8/README.md) | backlog e priorização |
| [E9](e9/README.md) | jornada e UX (nova organização, não etapa original) |
| [E10](e10/README.md) | release e evolução (nova organização, não etapa original) |

E0–E8 são rótulos históricos de organização, não fases novamente executadas. E9/E10 são índices adicionais. IDs CF-SPEC-* são síntese nova e **não substituem** RF/CT/CAM/OP/PEN do kit original. Não criar outro motor ou catálogo de regras para satisfazer esta documentação.

## Direção transversal aprovada — D17
- [CF-JOURNEY-001 — Jornada de sete etapas e núcleo metodológico agnóstico](CF-JOURNEY-001.md): contrato de produto e extensão, sem impor VM0038/VCS ao núcleo.
- [CF-JOURNEY-001-AUDITORIA — alinhamento do repositório e matriz normativa preliminar](CF-JOURNEY-001-AUDITORIA.md): achados, owners, escopo da revisão e lacunas. A primeira fatia OPEN e a ordem técnica E8 permanecem.
