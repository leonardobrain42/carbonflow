# E8-003 · Verificação local · 16/11/2026

**SIMULAÇÃO** · PIL-SYN-BR-001 · kit 0.2.14 · Flyway V3. A E8-002 forneceu sessão, escopo de projeto, papéis, trilha e idempotência. As provas, o perfil de Planaltina e o cadastro inicial vêm da [massa sintética E8-003@1](../../backend/src/main/resources/fixtures/e8-003-enquadramento.json), derivada de `03_especificacao/01_ESCOPO_E_CASO_PILOTO.md`, do dicionário E4 e da matriz temporal do kit. O SHA-256 da massa conferido pelo cliente foi `0d0efd802ed1d3ba278434bb0cf7ec88141bf88b214bf4cdc7b6bd020e6bc461`.

## Prova no produto

- [Ficha v16 exportada da API no Compose de testes](ficha.json). PREP preparou o perfil individual de Planaltina, com rede privada fechada, SIN exclusivo, um pátio, dois DCFC de 50 kW, vinte BEVs, dois modelos e dois circuitos EC. PP registrou provas de direitos, excedente e pesquisa de sobreposição. REV distinto enquadrou o cenário e fixou a seleção temporal do kit para 01/03/2028.
- [Ficha v1 fora do recorte](fora-do-recorte.json). A rota de 92,3% retornou `FORA_DO_ESCOPO` com justificativa e seleção de método vazia. O evento `5c9e9efa-07a8-4c66-bb35-752d249a599e` e a ficha v1 continuaram consultáveis após o enquadramento da versão posterior.
- O compromisso significativo de 15/07/2027 e a primeira operação de 01/10/2027 ficaram em marcos distintos, com provas e atores próprios. A vigência inicial do crédito termina em 30/09/2032 no cenário.
- A troca de `SIM-MED-01` por `SIM-MED-03` em 01/04/2028 encerrou a vigência efetiva anterior. O histórico guarda a referência da predecessora, o circuito, o diagrama, a instalação, a calibração e as janelas semiabertas. A leitura que atravessou a troca ficou pendente. A API devolveu o intervalo com o deslocamento original `-03:00` e `12.50` kWh associados à ficha v16.

## Casos executados

| Referência | Esperado | Observado |
|---|---|---|
| CT-E5-01 e CT-E5-03 | Perfil incompatível com justificativa e sem seleção de método. Perfil válido avança após revisão, com lacunas explícitas. | Rede aberta, infraestrutura adicional e 92,3% ficaram fora do recorte. Sem direitos, excedente e sobreposição, revisão ficou pendente. O cenário aprovado continuou bloqueado para uso real. |
| CT-E5-02 | Engajamento, mecanismo e ESG anteriores ao compromisso. Evidências e incidência preservadas. | ESG em 16/07 bloqueou compromisso de 15/07. A entrada anterior permitiu a transição e a tardia permaneceu no histórico. Roteiro gravou FPIC/repartição por incidência, ODS e reclamação sintética. |
| CT-E5-04 | EC segregada e calibração aplicável exigidas no comissionamento e na cobertura da leitura. Troca versionada. | Comissionamento sem inventário válido bloqueado. Registro sem calibração preservado e corrigido por sucessora. Medidor anterior e novo consultáveis. A calibração vencida gerou pendência. |
| CT-E5-12 | Vetar travessia de recorte sem segmentação. Atribuir as leituras já separadas às suas janelas. | O teste de API usou UTC no exato limite da troca e no limite M1/M2, confirmou duas leituras completas em torno da troca e manteve as travessias pendentes com o valor de origem. |
| CT-E5-26, recorte de inventário | Aceitar o limite e recusar a primeira unidade em excesso sem truncamento. | Testados 100 BEVs aceitos na triagem e 101 recusados, com o estado anterior intacto. Os limites de pátios, DCFCs, modelos e canais EC também são validados pelo backend. |
| CT-E5-38, recorte de interface | Papéis internos completam as ações que lhes competem. | A jornada automatizada percorreu PREP, PP, REV e OP. O teste de integração também verificou 401 sem sessão, 403 para papel incompatível, 404 para outra organização, autoaprovação, repetição idempotente e versão obsoleta. |

O Maven `clean verify` passou com 12 testes Java, incluindo o [ensaio de integração da E8-003](../../backend/src/test/java/br/carbonflow/pilot/ProjectIntakeTest.java). A compilação Angular e o build da imagem Compose passaram. Três [jornadas automatizadas no Chrome](../../frontend/test/) passaram contra PostgreSQL 16.16 e a aplicação em um projeto Compose isolado, incluindo as regressões da E8-001 e E8-002.

## Pendências e limites

- **PEN-002/012.** PP e revisão técnica revalidam edições, data de corte, canal e vintages no ato real.
- **PEN-003/005.** OP autoriza a amostra ED/EC e fornece diagrama, certificados e cobertura. REV e especialista conferem fronteira, baseline, OM/EF e admissibilidade.
- **PEN-004/010.** Titular, PP e OP juntam direitos, pesquisa de sobreposição, fundamento do Dy, prova de excedente e requisitos e licenças do local.
- **PEN-011.** PP e revisor confirmam representação, geodados, V5#89, atuação do VVB, ramos, frações e posição. A prova geográfica da demonstração é a descrição sintética da fronteira. As coordenadas e a verificação local dependem da homologação.
- A pré-checagem é de EC com as provas da amostra. Importação operacional, segmentação comprovada e admissão de observações ficam na E8-004. As cotas de sessões, fatos anuais e arquivos dependem das fatias que os introduzem. CT-E5-35, as sessões observadas do CT-E5-38 e a comprovação para uso real seguem pendentes. Parecer independente e homologação pertencem às E8-013/014.
