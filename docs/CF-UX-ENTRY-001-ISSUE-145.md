# CF-UX-ENTRY-001 — Issue #145 · Entrada e ativação de projeto próprio

## Contrato entregue
- A rota `/` apresenta três caminhos explícitos: iniciar projeto próprio, acessar projetos e explorar `/pilot`. O piloto sintético conserva identidade, dados e URL separados.
- `AccountEntryComponent` substitui o login isolado da área privada e usa somente as APIs existentes `/v1/accounts/*` e `/v1/account-session`. Não introduz usuário, concessão de poderes, identidade ou banco paralelos.
- Cadastro, confirmação, reenvio, recuperação e redefinição têm feedback genérico, erros 401/404/409/422/429/503, bloqueio de duplo envio e chave idempotente; segredos ficam nos inputs, não em URL ou localStorage.
- O backend existente só entrega desafios pela `SandboxAccountMailbox` privada. **Não há entrega SMTP/externa**: a UI não afirma que foi enviado e-mail real; falta de configuração resulta em aviso de indisponibilidade, não fallback inseguro.
- Após login em `/workspace/projects/new`, a conta retorna ao fluxo real de organização → rascunho → triagem/visão geral. A visão geral com checkpoint/ações vem da PR #143 já mesclada. Nome de espaço de trabalho não comprova PP, poderes nem elegibilidade.
- Metodologias/ramificações não suportadas mantêm rascunho e decisão de escopo na triagem/visão geral. Nenhum valor `SIM-*` é transferido.

## Limites e pendências
- A ativação de **usuário público com e-mail real** depende de uma futura camada de entrega e políticas operacionais (privacidade, abuso, suporte). Não confundir o sandbox de contas testável com cadastro comercial.
- A verificação de representação e grants permanece nas issues #15/#24. Não é concedida pela jornada de entrada.
- ENTRY-T08 (ensaio moderado com pessoa iniciante, tempo/erros/abandono) requer execução humana; testes de navegador não substituem essa evidência.
- Em produção, revisar política de rate limit, proteção anti-abuso, cadeia do provedor de e-mail, retenção e autenticação de dados pessoais antes da liberação.

## Testes
- `frontend/test/entry.test.mjs` cobre porta de entrada, CTA, cadastro+confirmação, fluxo sem mailbox, recuperação/erro 422, intent de novo projeto, isolamento dos segredos e mobile.
- `frontend/test/shell.test.mjs` foi ajustado para esperar a nova homepage mantendo a URL `/pilot` funcional.
- A suíte anterior de espaço/contas e a regressão PIL-SYN permanecem na CI. Verificar resultado final da PR no GitHub Actions.
