# #97 — Vínculo imutável de entradas E8 em preparação (CF-OPEN-008)

## Resultado entregue

A API e a jornada de monitoramento podem agora **vincular duas fontes reais cadastradas
no mesmo projeto**: pacote horário de energia ED/EC de QA interno (V59) e
série candidata de fator elétrico EFel com revisão independente (V60).
O vínculo é um **snapshot candidato**, versionado por projeto/período, com
checksum e memória de horas. Não é uma permissão de cálculo E8.

O recorte continua VM0038 v1.1, rede OPEN, Brasil, BEV LDV, DCFC/SIN sem
infraestrutura associada. O cadastro da fonte não substitui análise de licenças,
regra normativa nem comprovação jurídica do direito às reduções.

## Fluxo humano

1. OP/PREP submetem leituras ED/EC à revisão e o REV realiza QA interno.
2. PREP prepara pacote energético e o MR vincula seu snapshot, com parecer
   metodológico independente para permitir **apenas a preparação técnica**.
3. PREP submete série EFel horária com edição, instituição, URL oficial,
   justificativa, SHA declarado, período e direitos. Um REV diferente examina
   seu conteúdo e registra CHECKED_INTERNAL. A série permanece **NOT_ADMITTED**.
4. Na tela de EFel o PREP seleciona a fonte revisada e aciona
   **Vincular versões ED/EC e EFel**. Quarkus confirma que ambas pertencem ao
   mesmo projeto, ambiente e período, que a EFel referencia precisamente o
   energyCandidateId vigente, que **todas as horas** possuem EC, ED e fator,
   que a soma horária ED/EC bate com a referência imutável e que não há
   revisão ou fonte obsoleta.
5. O resultado é uma entrada vN com sourceSha/versões, hourCount/slotCount,
   inputSha256 derivado das referências e do conteúdo e horas imutáveis.
   Nenhuma emissão numérica é gerada. O bloco da UI mostra horas e gates restantes.

## API

- GET `/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-input-bindings`
  lista até 20 versões recentes.
- POST no mesmo recurso: JSON
  `{"energyCandidateId":"UUID","efelCandidateId":"UUID"}`, header
  `Idempotency-Key` único e `If-Match: "0"` ou ETag da última versão.
  Resposta 201 com binding versionada e ETag.
- GET `.../e8-input-bindings/{bindingId}`: recupera a memória histórica,
  inclusive versões fora da listagem paginada.

Toda consulta usa DynamicProjectScope com ações explícitas:
`VIEW_OPEN_E8_BINDING_CANDIDATE` para owner e PREP/REV habilitados em
monitoramento; `PREPARE_OPEN_E8_BINDING_CANDIDATE` somente para owner e
PREP autorizado. OP, estranho ao projeto e REV sem grants não recebem
dados detalhados. Respostas usam `private, no-store`.

## Persistência V63

- `app_open_e8_input_binding_candidate`: registro imutável por
  projeto/período/versão com FK a V59/V60 + revisão EFel, hashes e contagens,
  gravado em transação protegida por lock de projeto;
- `app_open_e8_input_binding_hour`: uma linha por hora UTC com soma de ED/EC
  entre carregadores e EFel normalizado tCO2e/kWh; 1..744 horas, no máximo
  100 carregadores/hora, sem preenchimento automático de lacunas;
- `app_open_e8_input_binding_command`: idempotência por ator/chave,
  hash do pedido. Nova origem ou revisão cria versão sucessora; duas chaves
  diferentes para o mesmo par não geram vínculos duplicados.

Triggers rejeitam UPDATE/DELETE. O estado salvo em DB é sempre
`CANDIDATE_NOT_ADMITTED` e `usable_for_e8=false`.
Na leitura, backend reavalia se a série, o QA ou o parecer mudaram;
se mudaram retorna `STALE_NOT_ADMITTED`, preservando o histórico.
A assinatura SHA-256 demonstra integridade do snapshot interno;
**não prova autenticidade da fonte externa nem é assinatura digital**.

## Controles de segurança e lacunas

É proibido reutilizar piloto PIL-SYN ou fatores SIM, substituir ausência por
zero, inferir competência de REV ou permitir entrada de outro tenant.
A execução `OpenGridCalculationMath` da fatia anterior **não é chamada**
pelo binding. Os números de ED/EC e EFel são insumos em preparação,
não resultados de PE e não constituem documentação de emissão.

**Pendências obrigatórias antes da execução:**
- EVR nacional comparável e pareamento BEV/combustão com fontes admissíveis
  #94/#95, EFfuel, IR, Dy e direitos #96/#99;
- normas e licenças publicadas #10/#92, revisão substantiva e QA #33/#35;
- origem e licença de EFel conforme VT0011/TOOL07 e fator CO2e correto por
  hora e vintage; seleção de dados de outra série não se torna válida por vínculo;
- runner E8 com períodos, snapshots de todos os parâmetros, equivalência
  hora-a-hora e funções transacionais de run/replay/diff/stale #97;
- matriz de origem e documentação PD/MR #43/#122/#128.

**Definição de pronto desta fatia:** vínculo correto e recuperável entre
as duas entradas reais já existentes, sem números BE/PE/ER, testado em
PostgreSQL, isolamento tenant e evidência de que mudança de MR gera STALE.
A issue #97 permanece aberta.
