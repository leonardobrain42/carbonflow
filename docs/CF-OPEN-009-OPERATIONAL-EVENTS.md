# CF-OPEN-009 / #98 — Eventos operacionais e QA horário (fatia 5)

**Estado:** nova camada de rastreabilidade das interrupções e manutenção. A issue #98 permanece aberta, sem emissão de créditos e sem admissão externa dos dados.

## Escopo

A V56 acrescenta um ledger genérico de ocorrências por projeto, sem criar outra ingestão ED/EC ou alterar o piloto sintético:

- `app_open_operational_event`: ocorrência imutável, tipo, origem/medidor/carregador, intervalo UTC com entrada ISO 8601 explícita, referência de incidente, descrição e autor; limite de 31 dias por ocorrência e vínculo a `app_project`/organização.
- Tipos: `SOURCE_OUTAGE` (origem), `METER_MAINTENANCE` (medidor), `CLOCK_DRIFT` (medidor), `DATA_GAP` (medidor, carregador ou origem).
- `app_open_operational_event_review`: decisões `CONFIRMED_BLOCKING` e `CLEARED_FOR_INTERNAL_QA`, com motivo, referência probatória, revisor e versão. Histórico append-only; trigger impede autoavaliação. Essa decisão não verifica automaticamente o conteúdo probatório, mas explicita a autoria e a fundamentação.
- `app_open_operational_event_command`: prevenção de repetição por conta e `Idempotency-Key`, com hash do payload e tratamento de replay/conflito.

## Segurança

Somente **OP/PREP** autorizados com grant `MONITORING` ou DRAFT_OWNER registram ocorrências. Só **REV independente** com esse grant registra ou altera pareceres. O papel REV não recebe direito de alterar inventário, dados brutos nem documentos financeiros de adicionalidade. Identidades externas e sem grant recebem acesso opaco; ações são auditadas via `DynamicProjectScope`.

APIs: `GET /v1/projects/{project}/technical/open-monitoring/events`, `POST .../events` (`Idempotency-Key`), `POST .../events/{id}/reviews` (`If-Match`). Todas as respostas são `private, no-store`; registros e revisões não são editados retroativamente.

## Regras operacionais

- Qualquer evento **sobreposto** ao período bloqueia o QA se não tiver último parecer `CLEARED_FOR_INTERNAL_QA`. Um evento parcialmente sobreposto (por exemplo, 00:20–00:40) bloqueia integralmente a tentativa de fechar a hora 00:00–01:00, sem fracionar kWh artificialmente.
- A liberação por REV remove **apenas o gate dessa ocorrência**: continuam obrigatórias as duas séries ED e EC positivas e distintas, hora completa, calibração, fronteira SIN/DCFC simples, revisões de linha e ausência de duplicatas.
- A assinatura do snapshot incorpora a identidade do evento, período e versão/resultado do parecer, inclusive quando a ocorrência foi liberada. Um snapshot anterior ao evento ou a nova revisão continua `BLOCKED_STALE`; é necessário congelar **outro snapshot**, sem apagar o histórico.
- Para snapshots **anteriores à V56 que nunca tiveram eventos**, a função SHA permanece idêntica ao formato original. Não há invalidação em massa indevida de históricos só por instalar a migração.
- A seleção de ocorrências é por período e projeto: M1 pode permanecer bloqueado enquanto M2, com leitura real completa e sem ocorrências sobrepostas, continua elegível **somente ao QA interno**.
- `canRunE8` e `usableForE8` permanecem `false`; nenhum evento é prova de redução de emissões ou VCU.

## Testes

JUnit puro de interrupção pendente, bloqueio confirmado, liberação interna, sobreposição em bordas, SHA determinístico e M2 independente. Integração PostgreSQL/Quarkus de upload idempotente, rejeição de chave reutilizada, grant OP versus REV, QA bloqueado e snapshot antigo `STALE` após ocorrência, preservação do bloqueio histórico após liberação, e isolamento multi-tenant. Angular exibe ocorrências e pareceres na seção fixa **Monitorar ED/EC**, sem criar submenus novos.

## Pendências de #98

1. Recepção dos logs reais de manutenção, falhas de aquisição, qualidade do relógio, comprovantes assinados e validação substantiva de evidências.
2. Análise normativa com controle de metodologia/versionamento e licença de dados, sem confundir revisão interna com aprovação por VVB/Verra.
3. Propagação de dependências STALE a MR/PD e parâmetros dinâmicos do E8 (#97).
4. Convites OP, controle jurídico de papéis PP e prova operacional ponta a ponta com vários medidores, horas e períodos M1/M2.
5. Conciliação avançada de estações e topologias não simples e decisão sobre intervalos de zero real.
