# CF-OPEN-008 / #97 — Pré-cálculo E8 por projeto dinâmico, primeira fatia

**Situação:** diagnóstico implementado, cálculo real NÃO implementado; a issue #97 permanece aberta. Esta fatia não promove candidatos de fontes, números do piloto, pareceres internos ou declarações a um fator apto para emissão.

## Contexto e por que começar com preflight

O núcleo E8 original (CarbonCalculator, SelectedCalculation) depende do PIL-SYN-BR-001, BASE-SYN-LDV, EF-SYN-v1 e SIM-DY-REDE-01. Não deve ser reutilizado como executor de projetos criados na UI.

As issues #94, #95 e #96 já possuem componentes preparatórios de EVR, ratings e candidatos de fatores. Entretanto, ainda não há ProjectParameterBinding que considere admissibilidade por finalidade, vintage, papel, fonte e direitos. A #98 também ainda não entrega períodos ED/EC admitidos. Portanto, executar BE/PE/ER automaticamente seria incorreto.

## Entregue neste PR

- Motor puro OpenCalculationPreflight: interpreta metodologia escolhida, declaração OPEN, revisão da triagem e topologia. Identifica rede CLOSED, PHEV, outras redes, energia local, armazenamento e V2G fora do ramo inicial, e não converte campos desconhecidos em false.
- Retorno com estados BLOCKED_MISSING_DATA e BLOCKED_NOT_ADMISSIBLE, lista de gates tipados, dono da ação e próxima providência, sem sucesso normativo implícito.
- API GET /v1/projects/{project}/technical/open-calculation-readiness: autorização por DynamicProjectScope, DRAFT_OWNER ou grant real CALCULATION, auditoria, 401/404 opacos e Cache-Control: private, no-store. Não consulta origens internas por tenant nem expõe números dos candidatos.
- Emissões BE/PE/LE/ER permanecem NULL. Ausência, zero matemático, candidato, estimativa, medição e crédito emitido são conceitos distintos. Mesmo uma declaração OPEN completa não libera motor real.
- Integração Angular em Preparação técnica → Analisar com atualização explícita, sem botões de executar cálculo fictício.
- Flyway V51 acrescenta evento de auditoria VIEW_OPEN_CALCULATION. Não cria snapshots sintéticos ou tabelas de resultados.
- Testes puros e de integração PostgreSQL para ausência, escopo CLOSED, configuração incompleta, stale, não contaminação SIM, acesso cross-org, grant e revogação. CI geral também verifica os oráculos E8 sintéticos preexistentes.

## Contrato de resposta

O estado REQUEST_DATA representa preparação permitida. A API sempre devolve canRunE8=false e canIssueVcu=false nesta versão. Emissions.* é NULL e não ER=0. Cada gate expõe code, state, reason, nextAction e owner. A resposta é diagnóstico, não parecer de VVB ou Verra.

## Pendente para encerrar #97

1. Seleção imutável por projeto/organização com snapshot da regra/edição/data: EVR (#94), AFEC/MPG (#95), EFfuel/EFel/IR/Dy (#96) e proveniência admitida/parecer independente (#10/#35).
2. Medição real ED e EC com QAs, calibração, janelas, licença e fronteira (#98). ED NÃO é EC. Ausência de qualquer dado bloqueia resultado.
3. Adaptadores de entrada tipados e isolados; função matemática pura com fórmulas VM0038 eqs 1-7 aplicáveis e testes dimensionais, precisão, arredondamento, vigência e versões.
4. Cálculo não sintético BE/PE/LE/ER, apenas com todos os gates normativos satisfatórios, sem infraestrutura associada (eq. 4). Para solar/bateria/V2G, bloquear até projeto de ramo adequado.
5. Runs imutáveis com If-Match, Idempotency-Key, snapshot de SHA e unidade, replay/diff/audit, propagação STALE e decisão de revisão humana. Sem inferir VCU emitida.
6. UI de memória por modelo/linha, seleção de insumos, erros explicáveis e período/vintage, com controle por PP/PREP/REV/OP e acessibilidade/E2E.
7. Regressão PIL-SYN-BR-001 M1 6,00/M2 5,30 e hashes originais: não alterar código ou tabelas do piloto.

**Nenhuma fonte oficial brasileira foi admitida por este PR.** Para subir de fase, é necessária revisão de admissibilidade por finalidade e vínculo efetivo com projeto; um candidato global ou um pacote CF-DEMO nunca satisfaz o gate.
