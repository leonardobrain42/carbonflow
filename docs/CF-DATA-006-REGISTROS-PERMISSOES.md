# CF-DATA-006 · Permissões por operação dos registros de carbono (rc.76, 25/11/2026)
**Nível D17:** dados/programa (nem método nem núcleo). Dados: `docs/data/registry-permissions.json`; validador e testes: `scripts/registry_permissions.py`. **Acesso público não é licença.** Não é parecer jurídico.
Operações: consultar, guardar, extrair manualmente, extrair automaticamente, processar, exportar, redistribuir, uso comercial.
| Registro | Termos lidos | Consultar | Guardar | Extr. manual | Extr. automática | Processar | Exportar | Redistribuir | Comercial |
|---|---|---|---|---|---|---|---|---|---|
| ACR / ICE GreenTrace | sim (primário) | permitido (fins informativos) | indeterminado | condicional (citar, exatidão) | **proibido** | condicional | indeterminado | **proibido** | **proibido** |
| Gold Standard | sim (primário) | indeterminado | indeterminado | indeterminado | **proibido** | indeterminado | indeterminado | indeterminado | indeterminado |
| Verra | **não** (HTTP 403) | indeterminado em tudo | | | | | | | |
| UNFCCC/CDM | **não** (bloqueio) | indeterminado em tudo | | | | | | | |
| CAR | **não** (texto oficial não localizado; só um PDF de 2023 em site de terceiro) | indeterminado em tudo | | | | | | | |
| CAD Trust | **não** (termos não recuperados) | indeterminado em tudo | | | | | | | |
**Consequências (bloqueio só da operação sem autorização):** ingestão **automatizada** de ACR e Gold Standard é vedada pelo texto; a coleta para a matriz do Dy nesses registros deve ser **manual, com citação**, e a ACR exige consentimento escrito para manter cópia; para os quatro registros não lidos, **qualquer operação além de consultar a página manualmente fica sem base** até ler os termos ou obter permissão por escrito. Há uma contradição interna na ACR ("não armazenar" × "reproduzir dados públicos citados"), por isso `STORE` é indeterminado. Fontes secundárias (buscas) não valem como fundamento.
**Pendência:** abrir os termos de Verra, UNFCCC, CAR e CAD Trust por navegador autorizado ou pedir permissão por escrito; releitura periódica, pois os termos mudam (Verra lista versões de 2021, out/2024 e jul/2026).
