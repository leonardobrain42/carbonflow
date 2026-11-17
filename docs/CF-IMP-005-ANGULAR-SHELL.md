# CF-IMP-005 — Angular workspace shell / primeira fatia

**Status:** PR de implementação para revisão. Preserva o piloto E8: não implementa ainda criação de projetos nem novos papéis.

## Entrega deste PR

- `@angular/router` 21.2.26 (rotas `/` e `/pilot`, fallback para `/pilot`); componente root com `router-outlet`.
- Layout base responsivo escuro inspirado na referência visual aprovada, com sidebar para sessão do piloto, topbar, cartões e CTA verde.
- Navegação acessível entre as áreas existentes: inventário, ED/EC, cálculo, monitoramento, dossiês, listagens, auditorias, emissão, operação e acessos.
- Passos visuais de navegação em 5 etapas, **não** uma declaração de conclusão metodológica.
- Ambiente `SIMULAÇÃO` explicitado; sem autorização automática ou status Verra.
- Teste browser `frontend/test/shell.test.mjs`: login, navegação, smartphone, saída e rota desconhecida não autenticada.

## Compatibilidade

- `PilotComponent` e suas APIs E8 foram mantidos. O shell só aparece após sessão autenticada, e permissões seguem validadas no backend.
- A tela de usuário sem projeto continua mostrando `Nenhum vínculo de projeto vigente`, a implementação real de CTA e criação pertence às CF-IMP-006/007.
- Não introduzir acesso a dados com alteração apenas de URL; a autenticação/authorization efetiva permanece no backend.

## Aceite e pendências

- [ ] CI Angular / Quarkus / E2E verde no PR.
- [ ] Capturas visuais e inspeção humana em 1440×900 e 390×844 confirmam fidelidade; pendente nesta entrega.
- [x] Estrutura dark de sidebar/topbar/steps, roteamento piloto e teste browser incluídos no código.
- [ ] Padrão de gestão de rotas privadas para futuras áreas `/workspace` e `/platform`: tratar nas CF-IMP-006/013/028, com guards de UX e autorização Quarkus.
- [ ] Corrigir estilos de painéis internos antigos que não herdam tokens escuros completamente após QA visual.

## Como testar

```sh
npm --prefix frontend ci
npm --prefix frontend run build
CARBONFLOW_PORT=18087 docker compose -p carbonflow-shell-test up --build -d --wait
CARBONFLOW_TEST_URL=http://127.0.0.1:18087 CARBONFLOW_CHROME=/caminho/chromium \
  node --test frontend/test/shell.test.mjs
CARBONFLOW_PORT=18087 docker compose -p carbonflow-shell-test down -v
```

Executar em Compose de ensaio, nunca sobre dados do piloto principal. Esta implementação é deliberadamente parcial em relação à conclusão de CF-IMP-005.
