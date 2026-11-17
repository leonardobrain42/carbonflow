# CF-UX-ACOMP — Matriz de harmonização das issues (17/11/2026)

**Estado:** proposta de integração aplicada às issues existentes; não altera funcionalidades ou resultados em produção.  
**Origem:** especificação de acompanhamento CF-UX-ACOMP anexada à conversa, especialmente RF-1..RF-9 e seções 2–11; documentos `docs/ux/CF-UX-ACOMP-REQUISITOS.md` e `docs/ux/CF-UX-ACOMP-INTEGRACAO-JORNADA.md`; backlog GitHub inspecionado em 17/11/2026.  
**Princípio:** critérios de aceite no *corpo da issue dona*, detalhes transversais neste documento, sem reconstruir E8/REG/ECA.

## 1. Rastreabilidade — RF da especificação de origem

| Origem | Necessidade | Issue dona (novos critérios) | Dependências/reuso | Cenários |
|---|---|---|---|---|
| RF-1 | Dashboard, resumo do estado, KPIs compreensíveis, próxima ação | [#27](https://github.com/leonardobrain42/carbonflow/issues/27) | #19, #32 e E8; primeiro acesso da conta é #12 (já concluída) e continuidade da criação é #14/#17/#18 | CF-UX-01, 02, 04, 05 |
| RF-2 | Filtros de período e escopo, consistência entre indicadores | [#32](https://github.com/leonardobrain42/carbonflow/issues/32), composição na #27 | consultas/snapshots E8-005 e período E8-006 | CF-UX-05, 06, 10 |
| RF-3 | Exploração do resumo ao cálculo e à fonte (*drill-down*) | [#27](https://github.com/leonardobrain42/carbonflow/issues/27), [#32](https://github.com/leonardobrain42/carbonflow/issues/32) | #23, #33 e APIs E8 | CF-UX-05, 11 |
| RF-4 | Tarefas, marcos, responsáveis, próximos passos e bloqueios | [#19](https://github.com/leonardobrain42/carbonflow/issues/19), projeção [#27](https://github.com/leonardobrain42/carbonflow/issues/27) | #13 ECA-003 detém prontidão normativa; #26 ECA-004 solicita dados | CF-UX-02, 04, 12 |
| RF-5 | BE/PE/LE/ER comparáveis, natureza e proveniência | [#32](https://github.com/leonardobrain42/carbonflow/issues/32) | E8-005 calcula; #33 rastreia origem | CF-UX-03, 05, 10 |
| RF-6 | Monitoramento ED/EC, cobertura, registros, alertas | [#31](https://github.com/leonardobrain42/carbonflow/issues/31), ciclo [#42](https://github.com/leonardobrain42/carbonflow/issues/42) | E8-004/006, #27 resumo | CF-UX-06, 13 |
| RF-7 | Documentos, evidências e versões, revisão e histórico | [#36](https://github.com/leonardobrain42/carbonflow/issues/36), [#37](https://github.com/leonardobrain42/carbonflow/issues/37) | E8-007, #33 e #35 ECA-007 | CF-UX-07, 11 |
| RF-8 | Acessibilidade WCAG 2.2 AA, contraste, teclado e gráficos | [#45](https://github.com/leonardobrain42/carbonflow/issues/45); estrutura [#7](https://github.com/leonardobrain42/carbonflow/issues/7) | #27/#32 para gráficos e tabelas | CF-UX-09, 14 |
| RF-9 | estados vazios, erro, carregamento, recuperação | [#27](https://github.com/leonardobrain42/carbonflow/issues/27), testes [#45](https://github.com/leonardobrain42/carbonflow/issues/45) | primeiro projeto já entregue em #12, criação #14 | CF-UX-03, 08 |

## 2. Requisitos não funcionais e itens transversais

| Requisito | Issue dona / verificação | Decisão |
|---|---|---|
| NFR-01 performance | #45, com instrumentação em #27/#32 | Não fixar 2 s/1 s como garantia sem orçamento validado de dataset, hardware e rede. Medir baseline e aprovar meta com regressão. |
| NFR-02 autorização e escopo | #27, #45; grants de #24 e sessão | Backend autoriza; modo “executivo/técnico” não é papel nem concede permissões; sem vazamento por contadores, URL, cache ou agregados. |
| NFR-03 idioma e números | #7/#27/#32, QA em #45 | pt-BR inicial, unidade, data, fuso e precisão; internacionalização preparada sem escopo artificial de inglês no MVP. |
| NFR-04 estabilidade/sem duplicação | #19/#27/#32, QA #45 | Um motor de jornada (#19), um motor de prontidão (#13), cálculo E8-005, evidência E8/#33. |
| NFR-05 telemetria / privacidade | #27/#45 | Somente eventos mínimos e sem conteúdo sensível; logs de ato continuam no backend. |
| NFR-06 usabilidade | #45 e aceite final #48 | Teste com pessoas reais/representativas, não “usuários fictícios”; tarefas de localizar estado, próxima ação, fonte e diferença entre cálculo, verificação e emissão. |
| NFR-07 continuidade pós-criação | #14/#17/#18 | Home concluída em #12 não deve ser reaberta para acrescentar dashboard; implementa-se no fluxo de novo rascunho e na página contextual #27. |

## 3. Contratos e propriedade das informações

1. A conta vazia é função da #12 (**fechada em 17/11/2026**). A #14 deve selecionar/mostrar rascunho persistido após criação; #17 retoma triagem; #18 exibe diagnóstico preliminar sem alegar elegibilidade. Não exigir #27 para continuar criando.
2. #13 (ECA-003) calcula prontidão por requisitos; #19 detém instâncias, tarefas, vínculos e rechecagens; #27 **somente projeta** essas informações de forma autorizada. Não construir outro DAG ou estado de conclusão.
3. #32 apresenta snapshots, recortes/vintage, BE/PE/LE/ER e memória E8-005; #27 exibe resumo do snapshot autorizado, nunca fórmula de cálculo no Angular.
4. #31 apresenta ED/EC, plano, equipamentos e cobertura E8-004/006; #42 orquestra períodos e acontecimentos posteriores, preservando M2 em coleta enquanto M1 é tratado.
5. #36 detém interface PD/MR e referências documentais; #37 detém revisão interna. Atos externos VVB/Verra requerem evidência e ator próprio, não se inferem por “revisado” ou “concluído”.
6. Um endpoint agregador de leitura (se performance/composição exigir) pertence à #27/#16, sob `/v1/projects/{id}`, usa respostas autorizadas dos serviços donos e **não altera a fonte canônica**. Não criar imediatamente cinco novas APIs `/api/projects` do relatório original.
7. Informações ausentes não viram zero. Só há KPIs quando suportados por dados admissíveis, status e escopo; sem dados, exibir estado vazio explicativo e ação aplicável.
8. Não impor metas de número mínimo de gráficos/indicadores/documentos se o projeto não possuir a informação; a UI deve ser responsiva ao dado real, não inventá-lo.

## 4. Correções deliberadas da especificação inicial (a validar com o dono do produto)

- **Percentual de projeto concluído:** somente se houver denominador aplicável e regra de ponderação definida; não usar “80%” baseado em checkboxes.
- **Métricas de monitoramento (“número de recargas”, “downtime” etc.):** só exibir se a origem e granularidade forem suportadas por E8. A existência de ED/EC não prova que cada registro equivale a uma recarga.
- **Filtros “recalculam”:** selecionar recortes e agregações consistentes com snapshots/metodologia no backend; frontend não implementa nova fórmula nem altera fechamento anterior. Mudança de período deve informar indisponibilidade/ausência.
- **Fórmula genérica com buffer:** não introduzir `baseline - projeto - leakage - buffer` como regra universal da VM0038; usar equações efetivamente aprovadas por versão e metodologia.
- **“Crédito verificado = crédito emitido”:** não; ER estimada/monitorada, resultado revisto, verificação externa e emissão de VCU são estados distintos.
- **“Revisão Verra” no botão:** separar revisão interna, atividade do VVB e decisão do programa, com comprovantes e autoridade.
- **Autorização “por token”:** reusar autenticação/sessão e grants reais do CarbonFlow; não pressupor arquitetura de token distinta.
- **Metas 2 s/1 s, 3 KPIs, 10 linhas e 3 documentos:** validar empiricamente os critérios de performance; dados mínimos de telas nunca autorizam fabricar registros nem bloquear projetos ainda em rascunho.
- **“Usuários fictícios” em teste de usabilidade:** substituídos por pessoas representativas; fixtures sintéticas testam exatidão, não compreensão humana.
- **Demonstração vs. produção:** `PIL-SYN-BR-001` e M1=6,00 / M2=5,30 tCO₂e referem-se à fixture sintética congelada; não representam créditos efetivamente emitidos nem se propagam para projetos novos.

## 5. Casos de aceite ligados ao backlog

Os cenários **CF-UX-01..09** estão detalhados em [CF-UX-ACOMP-REQUISITOS.md](./CF-UX-ACOMP-REQUISITOS.md). Complementos necessários:
- **CF-UX-10 — Filtro sem recálculo indevido:** Dado snapshot de cálculo autorizado com recortes; quando alterno período/vintage; então o resumo usa somente resultados/agrupamentos metodologicamente válidos, atualiza rótulo e unidade, mantém histórico original, e período sem suporte exibe indisponível, nunca zero.
- **CF-UX-11 — Rastreabilidade de ponta a ponta:** Dado indicador ou pendência visível; quando seleciono “ver origem”; então acesso versão, snapshot, evidências, requisito e parecer permitidos, e dados restritos de outro perfil não são expostos.
- **CF-UX-12 — Estado único das tarefas:** Dado tarefa cuja evidência mudou; quando a fonte é revisada; então a tarefa/requisito aparece para a persona correta como “rechecagem necessária”, mantendo versão anterior e bloqueio correspondente; não é possível marcar requisitos normativos concluídos só por ação visual.
- **CF-UX-13 — Monitoramento condicional:** Dado período ainda sem telemetria completa; quando abro monitoramento; então vejo cobertura/parcialidade por classe e instrumentos; métricas de recargas ou indisponibilidade aparecem apenas se forem deriváveis e auditáveis.
- **CF-UX-14 — Compreensão demonstrada:** Dado ambiente sintético e papéis representativos; quando pessoas reais/representativas executam tarefas de localizar status, próxima ação, fonte e natureza da redução; então respostas, dificuldades e ajustes ficam registrados, sem alegar aprovação com base em atores fictícios.

## 6. Ordem de integração recomendada

**M1 / imediato (não bloquear PRs concluídos):** #7 (finalizar aceite visual), #14 (retorno após criar), #17 (retomar triagem), #18 (resultado sem elegibilidade). A #12 está fechada: protegê-la contra regressões sem reabrir.

**M2–M3 / base do acompanhamento:** #20 (modo), #19 (motor de tarefas), #23 (ajuda), #27 (dashboard). A #27 consome estados de #19 e #13, e deve nascer capaz de mostrar contexto com dados ausentes.

**M4–M5 / detalhamento:** #32 (emissões e filtros), #31/#42 (operações/ciclos), #36/#37 (dossiês e pareceres), ECA de origem sem duplicação.

**M6 / gates finais:** #45 (funcional + acessibilidade + segurança + usabilidade), #48 (homologação). Testar sempre primeira jornada e piloto sintético em ambientes isolados.

## 7. Registro de integração

- Referência de roadmap: [issue #4](https://github.com/leonardobrain42/carbonflow/issues/4).
- Plano anterior: [CF-UX-ACOMP-INTEGRACAO-JORNADA.md](./CF-UX-ACOMP-INTEGRACAO-JORNADA.md).
- Requisitos e testes: [CF-UX-ACOMP-REQUISITOS.md](./CF-UX-ACOMP-REQUISITOS.md).
- Repositório: [CarbonFlow](https://github.com/leonardobrain42/carbonflow).

**Política de mudança:** os corpos das issues recebem adendos identificados por `CF-UX-ACOMP / integração`, conservando objetivos originais e critérios anteriores; commits de código são feitos depois, sob seus próprios PRs. Não encerrar issues sem evidência de execução.
