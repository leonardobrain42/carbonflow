# E8-009 · Auditorias do VVB e pedidos REG, V1 e V2

PIL-SYN-BR-001 · Kit 0.2.14 · 16/11/2026 · Flyway V9. O ensaio usou dados sintéticos, com as guardas e transições do roteiro E3 e as operações OP-E6-10/11. As provas são referências de simulação, identificadas e vinculadas ao pacote e ao emitente aplicáveis. O parecer vem da sessão do VVB contratado. O PP captura os atos da Verra separando autor e capturador.

| Caso exercitado em PostgreSQL | Resultado observado |
|---|---|
| CT-E3-05 e CT-E5-18 | VAL abriu com listagem em validação e consulta comprovadas. Parecer positivo antes do encerramento foi recusado. O fluxo no Chrome incluiu uma manifestação já avaliada. |
| CT-E3-06/07 e CT-E5-20 | Achado recebeu revisão sucessora e recibo reservado. O envio manteve o achado em avaliação. A tentativa de protocolar com parecer negativo foi recusada. Outro organismo recebeu recusa na retomada. O VVB original retomou a revisão e o parecer negativo permaneceu recuperável. |
| CT-E3-09 e CT-E5-20 | M1 exigiu visita. Visita antecipada exigiu cobertura complementar com hash do fechamento. O VVB de M1 recebeu recusa ao ser proposto para M2. O VVB B recebeu o pacote histórico auditado de M1 e fundamentou a dispensa. A tentativa de dispensar visita marcada como obrigatória foi recusada. |
| CT-E3-08 e CT-E5-23 | O ato de aprovação de REG ficou separado do registro efetivo. M1 aguardou a prova de registro. V1 e V2 tiveram decisões próprias e a emissão ficou pendente de ativação. Repetição da aprovação de V2 recuperou a mesma decisão. |
| CT-E3-10 e CT-E5-21 | A passagem do prazo da rodada manteve o último estado comprovado. A devolução de REG veio por ato, permitiu preparar sucessora e conservou o vínculo entre tentativas. O reúso do pacote foi recusado após 180 dias e aceito dentro do prazo com prova expressa. |
| Pacote e autoria · CT-E5-16/28 | O protocolo guarda hash e recibo da variante pública limpa e referência ao parecer. Resposta de rodada exige revisão sucessora limpa e comparada, concessão e atuação do VVB. A decisão com pacote anterior foi recusada. Os MRs ligaram os vintages ao fechamento do período correspondente. |

- [Trajetória REG, V1 e V2 com provas e trilha](AUDITORIAS-E-PEDIDOS.json)
- Build Angular, compilação Quarkus e construção da imagem via Docker Compose. A suíte `mvn clean verify` executou 23 testes com Java 21 e PostgreSQL 16.16 em Docker. Dez cenários no Chrome passaram, incluindo contratação, concessão, visita e parecer na sessão do VVB.
- Os scripts locais `e6/conferir_e6.py` e `e7/conferir_e7.py` passaram. São verificações de referência do kit.

## Pendências e recorte

- **PEN-002/007/011/012** continuam abertas para vigência, canal, poderes, conta, competência, visita, representação, geodados e vintages aplicáveis. PP e VVB apresentam as provas pertinentes. REV e PP conferem os vínculos de cada ato dentro de seus papéis.
- **PEN-006/009** seguem para parecer independente e conferência setorial. A E8-013 trata do parecer e a E8-014 da homologação operacional e das condições de uso real.
- A rotação foi ensaiada com uma identidade fictícia adicional, **SIM-VVB-B**. O dimensionamento de usuários e a campanha de carga, restauro e acessibilidade permanecem nos marcos integrados.
- A ativação oficial de V1/V2, posição, emissão e conciliação pertencem à E8-010. Os atos de aprovação desta entrega deixam essas etapas pendentes.
