# E8-010 · Emissão, posição e conciliação

PIL-SYN-BR-001 · Kit 0.2.14 · PostgreSQL 16, Quarkus e Angular. A [trilha do ensaio](trajetoria.json) foi gerada pelos testes HTTP e inclui o identificador do projeto efêmero, atos, versões, posições e responsáveis.

| Ensaio | Resultado observado |
| --- | --- |
| CT-E7-04 · Identidade oficial | Depósito de 5 reapresentado como 5,00, em outro meio e com o mesmo instante em outro fuso, continuou aplicado uma vez. Alterar o valor para 6 ou usar a mesma identidade em outra tentativa registrou divergência e preservou o depósito original. |
| CT-E7-05 · Aprovação, ativação e espera | Aprovação de V1 sem ativação e ativação com identificador errado bloquearam o pedido. Ativação sem posição também bloqueou. A quitação deixou o depósito em zero e a tentativa aguardando enquanto M2 avançou até aprovação de V2. |
| CT-E7-07 · Saldo compartilhado | V1 no corte de 01/01/2028 a 01/10/2028 teve 10 oficiais, 2 a deduzir e 8 disponíveis. Saldo ou pendência negativa, reflexo incerto e prova de outro registro bloquearam. Os dois protocolos simultâneos de 8, em contas diferentes, tiveram um único vencedor. O pedido de 9 foi recusado. O corte de V2 de 01/10/2028 a 01/01/2029 recebeu posição independente de 30. |
| CT-E7-07 · Depósito parcial e ajuste | V1 recebeu 5 das 8 solicitadas. O encerramento oficial liberou as 3 remanescentes com atualização da posição. O ajuste de −1 levou o depositado líquido a 4. No novo extrato oficial de 4, o efeito refletido deixou 0 a deduzir para essa tentativa. A reemissão foi criada em outra tentativa com vínculo à anterior. |
| CT-E7-08 · Ordem do extrato | Extrato anterior entrou no histórico sem substituir a posição vigente. Dois extratos divergentes no mesmo instante e sem ordem conclusiva bloquearam disponibilidade. Extrato posterior permitiu a comparação. |
| Dois ciclos e reabertura | PREP comparou a posição e REV conciliou V1 e V2. Um PP foi recusado na comparação e o PREP foi recusado na decisão. V2 terminou com depósito de 2 e saldo de 28. Reabrir M1 vinculou o impacto à divergência de V1, manteve o depósito líquido de 4, bloqueou novos pedidos naquela revisão e preservou a conciliação de V2. |
| Cálculo e coleta | As regressões mantiveram M1 = 6,00 tCO₂e e M2 = 5,30 tCO₂e com EF-SYN-v1. O ensaio do kit confirmou as quatro parcelas e a coleta de M2 enquanto V1/I1 aguarda. |

A posição usa o corte da revisão congelada da verificação, com fim exclusivo. A reserva passa a valer no protocolo e é serializada em PostgreSQL junto da conferência da posição. Quando há saldo oficial e reserva para o mesmo efeito, só a parcela ainda ausente é deduzida. O extrato informa explicitamente o reflexo. Ajustes só se aplicam a uma linha de depósito identificada. A aprovação não habilita uso real.

A suíte Java passou com 23 testes e o script de referência do kit passou com oito grupos. O build gerou o Angular servido pelo Quarkus no Compose. No Chrome passaram 11 regressões em banco novo e um ensaio integrado adicional no segundo recorte de V2. Nesse ensaio, PP registrou posição de 10 com 2 pendentes, protocolou 8, comprovou cobrança, quitação e depósito e atualizou o extrato. PREP comparou e REV conciliou pela interface. A concorrência HTTP cobriu a disputa pelo saldo em IMP-04. A injeção de interrupções em todas as fronteiras de commit e resposta e o restauro completo de IMP-06 ficam para a E8-011.

## Pendências de homologação

- **PEN-004** · OP e PP comprovam titularidade, ausência de sobreposição e fundamento para Dy.
- **PEN-006** · Leonardo designa revisão independente e o revisor documenta posição e ressalvas.
- **PEN-007** · PP comprova canal, poderes, conta e escopo de integração para o ato real.
- **PEN-008** · A equipe mede o ambiente homologável e Leonardo decide metas, responsáveis e hospedagem.
- **PEN-011** · PP e REV confirmam as regras aplicáveis a representação, geodados, V5#89, frações, posição, VVB e metodologia.
- **PEN-012** · PP e REV revalidam versões, canal e dados quando existirem vintages reais.

Os saldos e depósitos usam `UNIDADE_FICTICIA`. Arredondamento, seriais e provas oficiais dependem de homologação.
