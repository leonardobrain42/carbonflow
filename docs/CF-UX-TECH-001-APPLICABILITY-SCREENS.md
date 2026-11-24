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
- Pendências de preenchimento levam direto à tela que as resolve: declaração ausente, acesso indefinido, configuração pendente, triagem alterada e rede fechada → `rede`; comprovante de contrato ou divulgação → `direitos`. Os rótulos viram "Abrir acesso à rede" e "Abrir direitos do projeto". Itens de espera (revisão, fonte, lacuna de datas, dependência de direitos) e as lacunas de dados iniciais seguem como antes (`analisar`/`preparar`), pois não há campo a preencher ou a tela depende de outro módulo. O backend só altera o destino e o rótulo: estado, grupo e regra não mudam.
- A proteção contra resposta antiga da lista de pendências continua no componente, mas o cenário "salvar com a lista aberta" deixou de existir na interface.

## Testes
`open-applicability.test.mjs` (três telas, leitura sem formulário), `usability-t17.test.mjs`, `project-technical.test.mjs` e os dois roteiros com backend real (`requirements-journey*.integration.mjs`) foram ajustados; `ProjectJourneyCompassIntegrationTest` valida `rede` e `direitos` no ponto de retomada. Os testes de navegador rodam no CI.

## Vocabulário e avisos (fatia seguinte)
- **Não respondido** é sempre "Não informado" nos campos da declaração (antes: "Ainda não sei", "Ainda não escolhido", "Ainda não foi informado"). **Declarado como incerto** é "A confirmar" (antes, na dupla reivindicação, "Não avaliado"). Os valores gravados (`''`, `NOT_DECLARED`, `UNKNOWN`) não mudam.
- Datas registradas sem prova: "sem prova admitida" (antes "referência não admitida").
- "PP jurídico" passa a "proponente do projeto (PP)".
- **Um aviso permanente** no cabeçalho da preparação técnica ("nada nesta área aprova metodologia, fonte, cálculo ou crédito; essa decisão é da Verra e da VVB") substitui as frases equivalentes do painel de trabalho e da lista de pendências. Avisos específicos de cada tela (evidência, quantificação, declaração) permanecem.
