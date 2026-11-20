# UX-03A — Estações, carregadores canônicos e fronteira versionada

**Issue:** #124, com dados canônicos do inventário #98. Continuação de UX-02A/#138, sem fechar UX-03.

## Jornada entregue

Acesso na área técnica: `?secao=estacoes`, navegação estável entre Estações → Carregadores DCFC → Fronteira.

- **Estação (V67):** identificador estável `siteCode`, nome, município/UF, endereço opcional, estado operacional declarado, previsão, início operacional declarado, referência documental e revisões imutáveis. Início operacional **não** é automaticamente início VCS.
- **Carregador:** cadastro e edição pela **API e tabela canônicas V52** `/technical/open-monitoring/chargers/{code}`. Não se cria uma segunda entidade para equipamentos. Nesta UI guiada o ramo inicial é DCFC / SIN / GRID_ONLY; outros ramos continuam visíveis como não suportados. Medidores/ED/EC seguem na tela Monitoramento.
- **Fronteira:** `PUT /technical/open-sites/boundary` grava seleções `chargerCode+chargerVersion+siteCode+siteVersion` com FKs, conjunto versionado, idempotência, If-Match e gate de ramo. Pode salvar lista vazia e retirar um carregador em **nova revisão**, sem excluir nenhum registro anterior.
- **STALE:** atualização de estação/charger ou mudança de associação sinaliza que a seleção ficou obsoleta. Nenhum status resulta em elegibilidade, VCU, prova, E8, PP ou aprovação.
- **Segurança:** usa grants existentes `VIEW_OPEN_MONITORING`/`SAVE_OPEN_MONITORING`; revogação e segregação entre projetos permanecem obrigatórias. GET não retorna dados de outra conta.
- **UX:** 3 superfícies de tarefa, formulário de localização, equipamento ligado à estação cadastrada, seleção múltipla explícita, proteção de rascunho, erro de concorrência e mensagens de próximos passos.

## Restrições explícitas

`sourceReference` é **metadado declarativo**, não upload, prova admitida ou contrato autenticado. Endereço não inclui coordenadas ainda. `PLANNED` é permitido sem leituras, e `ACTIVE` informado não comprova medição. Exige novos PRs para upload/vínculo de evidências por estação e equipamentos, revisões de direitos, inclusão/exclusão lógica da estação, geolocalização, vínculo documental PD e impacto seletivo no motor E8. O histórico é append-only.

## Aceite

- Teste Java com duas estações, carregadores e fonte distintas, comando idempotente, CAS, STALE e recuperação, impedimento de ramo AC, segregação entre contas e grants revogados.
- Playwright mobile testando criação de duas estações/chargers, seleção persistente e reload.
- CI Quarkus/PostgreSQL, Angular, navegador e demo sandbox verdes antes de merge.
