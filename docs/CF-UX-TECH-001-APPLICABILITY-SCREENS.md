# CF-UX-TECH-001 — Aplicabilidade em telas próprias

## Problema
A seção **Analisar** misturava duas funções: mostrar a situação do projeto e coletar dados (acesso aos carregadores, titular, contrato, divulgação, datas). Eram cerca de 25 campos, ações imediatas ("Registrar titular") e um botão final de salvar na mesma página, com a lista de pendências abaixo do formulário.

## O que mudou (somente interface; nenhuma regra, gate ou política)
| Tela (`?secao=`) | Conteúdo |
|---|---|
| `analisar` — **Situação do projeto** | Somente leitura: pendências, estado da declaração, teste de mercado e achados. Atalhos para preencher. Nenhum campo, nenhum botão de salvar. |
| `rede` — **Acesso à rede** | Perguntas sobre o acesso aos carregadores (BEV compatível, restrição a veículos identificados, terreno, tecnologia, veículos, energia, infraestrutura associada, V2G). |
| `direitos` — **Direitos do projeto** | Titular, contrato e arquivo, divulgação aos motoristas e evidência, risco de dupla reivindicação, observações. Mostra a prévia do PD e a origem do titular. |
| `estacoes` — **Estações e fronteira** | Passa a incluir as datas: início operacional da estação e início de crédito VCS (eventos diferentes). |

- **Um só registro.** As três telas de preenchimento usam o mesmo componente e a mesma API (`open-applicability`). Cada salvamento envia a declaração completa e cria uma revisão versionada, com `If-Match` e `Idempotency-Key` como antes. Salvar uma tela não apaga campos da outra.
- **Compatibilidade.** `?secao=analisar` continua válido; as pendências do backend ainda apontam para `analisar`, que agora é o ponto de partida com atalhos. O rótulo "Analisar" some do menu; o painel inicial e a visão geral usam o mesmo nome novo.
- **Retomada.** A lista de telas aceitas pelo ponto de retomada (`ProjectWork`) ganhou `rede` e `direitos`, com a mesma permissão de `analisar`. Nenhuma permissão nova.
- Removidos da tela de situação: o bloco estático "Preparação / Evidências: A confirmar".

## Limites conhecidos
- Pendências que exigem preencher algo ainda levam à tela de situação, que oferece os atalhos; levar direto à tela certa exigiria mudar o destino devolvido pelo backend (próxima fatia).
- A proteção contra resposta antiga da lista de pendências continua no componente, mas o cenário "salvar com a lista aberta" deixou de existir na interface.
- Outros textos de ajuda da aplicabilidade e o vocabulário de "não informado" (cinco variações) não foram unificados aqui.

## Testes
`open-applicability.test.mjs` (três telas, leitura sem formulário), `usability-t17.test.mjs`, `project-technical.test.mjs` e os dois roteiros com backend real (`requirements-journey*.integration.mjs`) foram ajustados; `ProjectJourneyCompassIntegrationTest` valida `rede` e `direitos` no ponto de retomada. Os testes de navegador rodam no CI.
