# CF-OPEN-008 / CF-TRUST-001 — Estado real dos vínculos E8 no pré-cálculo

## Objetivo da fatia

A jornada do projeto passou a receber, pela API de diagnóstico de cálculo, o estado **efetivo e por período** de dois vínculos anteriormente entregues:

1. **V63** — medições ED/EC e série de fator elétrico EFel, com revisão interna e SHA versionados.
2. **V64** — referências de frota EVR brasileira e ratings PBEV/pares comparáveis, publicadas por gestor autorizado, com licenças ainda válidas.

Antes, a área de diagnóstico exibia apenas gates genéricos `EVR=MISSING_BINDING`, `AFEC_MPG=MISSING_BINDING`, `ED/EC=PENDING_MEASUREMENT`, `EF_GRID=MISSING_BINDING`, inclusive quando o projeto já havia preparado esses vínculos. **Esses gates seguem corretos para a admissão definitiva**, mas precisam de uma camada distinta de progresso preparatório para não induzir erro.

## Contrato seguro

Na resposta já existente de:

`GET /v1/projects/{project}/technical/open-calculation-readiness`

são acrescentados para cada MR com período:

- `E8_INPUT_BINDING_{period}` — `MISSING`, `NOT_ADMITTED`, `STALE` ou `REVIEW_REQUIRED`;
- `E8_FLEET_SELECTION_{period}` — os mesmos estados com próximos passos próprios.

Os estados são lidos dos **últimos registros do projeto** com as mesmas regras de frescor e direitos das APIs originais. Não há no novo endpoint consulta a catálogos privados de outras organizações. O diagnóstico **não retorna** IDs de snapshots, SHA das fontes, ratings, pesos EVR, valores ou textos licenciados. Mostra apenas código do gate, estado, explicação, ação e papel responsável.

| Estado | Significado |
| --- | --- |
| MISSING | Nunca existiu vínculo para o projeto/período |
| NOT_ADMITTED | Vínculo V63 ou V64 preparatório vigente; ainda sem admissão normativa |
| STALE | Versão vinculada tornou-se obsoleta por MR, QA, fonte, revisão ou licença |
| REVIEW_REQUIRED | Estado inesperado ou não reconhecido; bloqueio conservador |

Não existem transições automáticas para `ADMITTED`. A mudança de V64 para o estado preparatório nunca transforma `BE, PE, LE, ER` em números. `canRunE8=false`, `canIssueVcu=false` e emissões nulas permanecem.

## UX

Na seção **Prontidão para quantificação → Entradas já preparadas no projeto** a pessoa recebe duas linhas por período para energia/fator e frota/ratings. Cada linha mostra a situação, o motivo e a ação. As demais dependências usam o nome da pendência e o papel previsto; o código estável fica na referência. A fonte oficial da VM0038 permanece no painel de equações, e os dados licenciados ficam exclusivamente nos fluxos de origem com permissões próprias.

## Aceite de testes

- Teste puro de estados: ausência, candidato ainda não admitido, revisão obsoleta e estado desconhecido sempre fecham o gate.
- Quarkus/PostgreSQL E2E: estado MISSING antes da V63, NOT_ADMITTED após vínculo ED/EC–EFel, seleção EVR/PBEV MISSING antes da publicação V64 e NOT_ADMITTED depois; edição posterior do MR torna **ambos STALE**.
- Segurança E2E: resposta de diagnóstico não expõe SHA EVR, UUID PBEV, valores de BE/PE/ER ou emissões sintéticas; acesso continua sob `VIEW_OPEN_CALCULATION` e `Cache-Control: no-store`.
- CI Angular, Quarkus/PostgreSQL, navegador e sandbox.

## Limitações não resolvidas

Esta é uma fatia **de transparência de progresso e detecção de obsolescência**, não o executor E8. **Não conclui #97 ou #128.** Mantém dependentes os parâmetros EFfuel, IR, Dy, validação de origem nacional EVR, revisão normativa da série SIN e direitos de carbono. Não altera `CarbonCalculator`, `SelectedCalculation`, piloto PIL-SYN-BR-001, migrações ou números históricos.

**Próxima implementação:** pacote versionado e específico da fonte fóssil EFfuel, incluindo combustível, gás, unidade, período, licença e revisor. Só depois agregar IR/Dy e admission layer ao runner real.
