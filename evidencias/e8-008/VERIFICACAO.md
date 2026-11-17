# E8-008 · Listagens, consulta pública e canal manual

PIL-SYN-BR-001 · Kit 0.2.14 · 16/11/2026 · migração Flyway V8. Todos os atos, fontes, recibos, contatos e poderes do ensaio são sintéticos. A referência vem dos exemplos `channelCheck` e `publicationCapture` de E6, com os cenários de repetição e prova de CT-E7-04/05.

| Caso exercitado na aplicação | Resultado observado |
|---|---|
| Canal, edição e poderes · CT-E5-19 | Indeterminado, vigência vencida, recibo de versão incompatível, ator sem papel e tentativa em modo real foram recusados. Arquivo simulado usou Registry e preenchimento humano simulado usou Hub. O recibo fixou destino, data, versão e hash do dossiê. |
| Publicação e janela · CT-E5-17 e CT-E7-05 | Protocolo, boleto, quitação, data nominal e publicação em desenvolvimento não abriram a consulta. A publicação em validação às 10:00 BRT de 05/12/2027 abriu a janela até 10:00 BRT de 04/01/2028. O encerramento antecipado foi recusado. O encerramento na fronteira exigiu ato e resumo coerentes. |
| Manifestações · CT-E5-18 | Recebimento dentro da janela e recebimento exatamente no fim exclusivo foram classificados separadamente. O PP capturou o encaminhamento e registrou a resposta. O VVB atribuído abriu um achado e julgou suficiente a segunda resposta. O contato ficou acessível ao PP. O resumo público mostrou datas e contagens. Uma reclamação registrada na ficha seguiu em trilha própria. |
| Identidade e repetição · CT-E7-04 e CT-E5-21 | Repetição do recibo e recaptura da publicação em outro meio reutilizaram o efeito. Alteração do conteúdo oficial, mudança do boleto vinculado ou uso em outra tentativa receberam recusa. O histórico identificou o emissor e a pessoa que capturou cada evento. |
| Retorno incerto, exigência e sucessão · CT-E5-17/21 | Retorno incerto orientou conferir no canal antes de repetir. A confirmação exigiu prova de conferência ou recibo vinculado. Exigência vencida manteve estado pendente até notificação. A reativação preservou a mesma tentativa e exigiu pacote atualizado e quitação. Suspensão, retomada e rejeição foram registradas por ato. Após rejeição, uma nova tentativa vinculou a antecessora e o pacote sucessor. |

Os builds Angular, Quarkus e Docker Compose passaram. A suíte `mvn clean verify` executou 22 testes com Java 21 e PostgreSQL 16.16. Os nove cenários automatizados no Chrome passaram, incluindo desenvolvimento sem abertura de consulta e a jornada de manifestação entre PP e VVB. Os scripts `e7/conferir_e7.py` e `e6/conferir_e6.py` passaram nas respectivas verificações de referência do kit.

- [Publicação, janela, manifestações e trilha](LISTAGENS-E-CONSULTA.json)
- [Exigência, retomadas e tentativa sucessora](RETORNO-E-TENTATIVAS.json)

## Pendências

- **PEN-002/007/011/012** continuam abertas para confirmação de edição, disponibilidade e obrigatoriedade digital V5#89, poderes, conta, canal, incidência da Listing Representation, geodados e vintages. PP e REV conferem por ato.
- **PEN-004/010** continuam abertas para prova de direitos, sobreposição, Dy, licenças, requisitos locais e excedente regulatório. OP e PP entregam as provas e REV confere.
- A autenticação e o envio em Registry ou Project Hub oficiais, publicação real, revisão independente da especificação e homologação operacional autorizada permanecem pendentes. A E8-009 trata dos achados formais de auditoria, contratação e pedidos REG, V1 e V2.
