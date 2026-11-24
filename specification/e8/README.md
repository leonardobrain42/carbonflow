# E8 — Backlog, dependências e ordem de execução
A [issue #89](https://github.com/leonardobrain42/carbonflow/issues/89) é a epic de prioridade do ramo OPEN. As issues donas de requisito conservam critérios de aceite; esta ordem é uma **recomendação de execução**, não fechamento automático de issue. E8-001..014 históricos permanecem em [evidencias](../../evidencias/); não confundir código E8-005 com issue numérica #95.

| Prioridade | Próxima fatia | Dono | Critério de passagem |
|---|---|---|---|
| P0 imediata | Revalidar #242 Quarkus após HTTP 429 | #96/#97 | Suite completa executada; registrar resultado e corrigir falha real se houver |
| P0.1 | IR intra-ano: reuso de avaliação anual entre períodos compatíveis | #96/#97/#98 | Mesmo ano relativo e da fonte, mesmas referências canônicas, janela integralmente coberta, vínculo atual; divergiu/expirou → bloquear; hash e memória históricos preservados |
| P0.1/P0.2 | Primeiro ano/ex ante e prova documental de IR | #96/#97 | Caminho fundamentado e separado de monitoramento; não deduzir candidato pelo expoente zero |
| P0.2 | Admissão de EVR, ratings, EFfuel/EFel, ED/EC | #92/#94/#95/#96/#98 | Evidência real, unidade/ciclo/vintage, cobertura/licença e parecer pelo escopo; lacuna explícita |
| P0.3 | Dy ERC/ERF, janela anterior, nacional DCFC, exceções e consumo estruturado no E8 | #96/#97 | Pesquisa inconclusiva bloqueia; prova de valores e regra; integração versionada |
| P0.4 | Consistência anual de referências e cobertura; congelamento documental | #97/#98/#122/#128 | Sem sobreposição/lacuna e mesma linha de base aplicável; memória e manifesto |
| P0 transversal | Aplicabilidade/adicionalidade, poderes e DAG real; PD interno completo | #93/#99/#24/#13/#19/#43/#122 | Revisão por critério, representação comprovada, autoria e exportação rastreável |
| P0 | Homologação OPEN e regressão PIL-SYN | #101 | Positivo e negativos em ambiente isolado, prova real condicionada, release decision |
| P1 | EC derivado pela eficiência DCFC | #98 | Rota prevista na metodologia com evidência, unidade e QA; origem derivada explícita |
| Posterior | Infraestrutura associada, novos ramos e outras metodologias | Epic a confirmar | Especificação, regras e dados próprios, testes e autorização |

Paralelismo: fontes/admissibilidade e UX/jornada podem avançar sem liberar E8; seleção temporal coordena IR/Dy/monitoramento. Não adiar #101 para o fim de todas as features — ampliar a fixture integrada a cada contrato. Se uma issue mudou desde o corte, reconciliar com seu corpo e CI antes de implementar.
