# Quantificação do PD — consulta contextual da consolidação anual

Continuação das PRs #215 e #216–#222. Refs #89 (P0.4), #97, #128, #122 e #124 (R4). Não encerra nenhuma issue ampla.

A ação **Conferir consolidação anual nesta seção** consulta o endpoint existente
GET /v1/projects/{project}/technical/open-calculation-years/{year}, com o ano
relativo do projeto registrado na tentativa exata. Sem ano registrado não há
consulta; o cliente não deduz ano de M1/M2 nem do vintage civil.

A projeção anual representa os períodos vigentes, não um snapshot anual
congelado. Antes de mostrar qualquer resultado, verifica escopo, ano, engine,
manifesto, estado sem admissão/emissão e uma única referência ao mesmo período,
ID, versão e SHA de entradas da tentativa selecionada. Se a versão histórica
não integra a consulta atual, não mostra resultados anuais nem troca a memória
selecionada. Se o ano estiver bloqueado e a tentativa ainda integra a projeção,
mostra causas canônicas e nunca zero. Se a referência estiver ausente (inclusive
calendário não resolvido), orienta consultar a versão vigente, sem vincular o ano
à tentativa histórica.

Com cobertura integral e estado interno, apresenta BE/PE/LE/ER bruto e ER
apresentado exatamente como registrados pelo servidor, janelas com fim exclusivo,
referências de períodos e digest da consulta. Confere a memória do membro
selecionado contra a prévia do PD. O navegador não soma valores nem calcula Dy;
não transforma a projeção anual em ClaimSupport, manifesto documental ou export.
Não apresenta ratings/fatores nem promove admissão. A narrativa permanece intacta.

Todas as consultas de detalhe compartilham a invalidação por request: nova
consulta, recarregar seções, trocar seção ou destruir o componente descartam
respostas atrasadas. Acesso continua sendo autorizado pelo endpoint canônico
VIEW_OPEN_E8_RUN. Nenhuma permissão ou persistência nova.

## Verificação

Ampliação de frontend/test/pd-calculation-note.test.mjs com projeção anual
autoral: leitura explícita pelo ano de projeto, valores exatos e referências,
bloqueio por lacuna, tentativa substituída, versão/hash divergentes, duplicata,
outro projeto/ano, stale, admissão/emissão indevida, inconsistência entre
membro e prévia, 404, resposta atrasada, ano ausente, teclado/mobile e narrativa
preservada. O cálculo e a consolidação permanecem cobertos pelos testes Java
existentes; os novos cenários de UI são mocks de contrato, não uma prova de
homologação de dados reais. CI precisa executar build, navegador e regressões.

Ainda faltam P0.2/P0.3 (admissão de entradas e avaliação versionada de Dy com
evidências), homologação real e persistência congelada de suporte documental.
Nenhuma migration ou regra normativa alterada.
