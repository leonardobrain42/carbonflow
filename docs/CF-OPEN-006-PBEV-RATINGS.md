# CF-OPEN-006 · Ratings e comparáveis PBEV brasileiros — primeira fatia (#95)

## Objetivo operacional

Associar, com proveniência e revisão humana, cada BEV da composição EVR da #94 a uma eficiência elétrica e a um veículo fóssil de serviço/capacidade comparável. **Não há seleção automática de comparáveis**, não há emissão de crédito ou `ProjectSelection`, e `usableForE8=false` em todo snapshot criado.

O cadastro não depende de que todas as edições nacionais estejam admitidas: a ausência de fontes deixa **AFEC e MPG nulos**, preserva o relatório de pendências e não bloqueia a edição de outras etapas do projeto.

## Fonte brasileira e limitações reais

O Inmetro mantém [tabelas anuais do PBE Veicular](https://www.gov.br/inmetro/pt-br/assuntos/regulamentacao/avaliacao-da-conformidade/programa-brasileiro-de-etiquetagem/tabelas-de-eficiencia-energetica/veiculos-automotivos-pbe-veicular): em novembro de 2026, seu catálogo lista a **Tabela PBEV 2026 (18º ciclo, atualização de 31/08/2026)**, além de arquivos de edições anteriores. O portal descreve dados de **veículos etiquetados**, não a frota BEV em circulação.

Esta fatia **não baixa nem extrai automaticamente arquivos PDF**, não pressupõe que um modelo registrado no PBEV esteja em circulação e não usa os defaults dos EUA/Canadá para o Brasil. O operador deve registrar primeiro uma edição PBEV real no fluxo #92, armazenar uma transcrição tabular com autorização e prová-la com SHA-256 e localizadores. A origem PDF, os direitos de derivação, o processo de transcrição/QA e o ciclo exato precisam ser avaliados. A automatização de extração/checagem fica para uma continuação da #92/#95.

## Formato da tabela intermediária (não é planilha oficial)

Após validação de direitos de `STORE_ORIGINAL` e `EXTRACT_TEXT`, o parser tabular existente suporta:

```csv
record_key,period_year,metric,value,unit,geography
EV_A_2024,2024,ev_energy,18,kWh/100mi,BR
ICE_A_2024,2024,fuel_economy,32,mi/USgal,BR
```

Exemplo **exclusivamente sintético para testes**. O cliente **não envia o valor numérico pelo endpoint de rating**: `sourceObjectId` e `sourceRow` resolvem a quantidade do banco, depois que a avaliação `APPROVED_FOR_SCOPE` específica para ano, métrica, unidade, BR e `BASELINE_BE` está registrada na fonte. Os candidatos preservam o valor e a unidade originais do PBEV. O registro separado do rating contém identidade canônica do veículo, assentos, combustível e referência verificável de ciclo declarada pelo operador. Ainda não equivale à verificação definitiva das fichas de ensaio.

Licenças exigidas para os dados: `EXTRACT_TEXT`, `SHOW_EXCERPTS` e `EXPORT_DERIVATIVES` para a organização da conta staff MFA, fonte/edição e mesma licença original da #92. Uso de valores em projeto e E8 exige verificações posteriores adicionais.

## Conversões em BigDecimal

- `MJ/km` para `kWh/100mi`: multiplicar por `160.9344 / 3.6`.
- `kWh/km` para `kWh/100mi`: multiplicar por `160.9344`.
- `kWh/100km` para `kWh/100mi`: multiplicar por `1.609344`.
- `km/L` para `mi/USgal`: multiplicar por `3.785411784 / 1.609344`; **galão americano** (3,785411784 litros), não galão imperial.
- `kWh/100mi` e `mi/USgal`: identidades apenas quando as unidades forem exatas.
- `MPGe`, mistura com km/galão imperial, câmbio entre ciclos de ensaio e qualquer origem energética não especificada são rejeitados.

Todos os resultados normalizados têm 12 casas decimais, arredondamento `HALF_EVEN`. Ciclos válidos: `URBAN`, `HIGHWAY`, `COMBINED`. O sistema **não escolhe** por conta própria entre urbano/estrada e misto.

## Pareamento e segregação

1. Equipe com staff MFA + `MANAGE_SOURCE_WORK` escolhe a linha PBEV. O rating usa o `model_id` já gravado em `app_fleet_model_alias` para a mesma organização, o ano da linha e a chave da fonte. Não há outra identidade de modelo, nem escolha livre de um cadastro paralelo. Sem alias, o registro é recusado. Um `modelId` diferente do alias devolve `409 MODELO_DIVERGENTE`. O par continua exigindo um BEV do vetor EVR e um fóssil distinto, com justificativa de categoria, uso e capacidade.
2. O par começa `PROPOSED` com versão 1. A revisão é feita **por outra identidade** no roster `carbonflow.platform.vehicle-ratings.reviewers`, também com staff MFA e leitura habilitada. A própria pessoa que cadastrou os ratings ou propôs o par **não pode aprovar**. A revisão usa `If-Match: "1"`.
3. A aprovação interna valida LDV x LDV, BEV x veículo a combustão, o mesmo ano, mesmo ciclo, assentos com diferença máxima de um lugar e combustível **GASOLINE** neste primeiro ramo. Combustíveis misturados/flex/etanol/diesel ficam pendentes de avaliação metodológica e fatores específicos.
4. Uma nova proposta aprovada pode substituir a referência corrente, marcando os snapshots dependentes do mesmo vetor `STALE_REFERENCE`. As decisões e versões anteriores permanecem auditáveis. `ACCEPTED` é **aceite interno de comparabilidade**; não equivale a emissão Verra/VVB.

## AFEC, MPG e cobertura

Para cada linha da composição EVR do mesmo ano:

```text
AFEC = soma(EVrating_kWh_100mi × EVR) / soma(EVR)
MPG  = soma(MPGrating_USgal × EVR) / soma(EVR)
```

Os modelos, ratings e pesos vêm do **mesmo vetor EVR**; se algum modelo estiver sem exatamente um par aceito, faltar licença vigente, ciclo/ano unívoco ou houver combustível incompatível, os dois resultados ficam **nulos**, com pendências por modelo. Não se agregam apenas os modelos presentes. Os campos `coverageState = OFFICIAL_TOTAL_NOT_VERIFIED` (série #94) e `ratingState = RATINGS_NOT_LINKED` **não são promovidos** por este módulo.

Apenas quando 100% das linhas tiver par válido pode-se criar um `FleetRatingSnapshot` **PREPARATORY**, com hash de linhagem imutável, pareamentos e vetor usados. Repetir a mesma linhagem retorna a edição já registrada, sem alterar os valores anteriores.

Exemplo da suíte sintética, **sem alterar dados reais**:

| Modelo | EVR | BEV (kWh/100mi) | Fóssil (mi/USgal) |
|---|---:|---:|---:|
| A | 12 | 18 | 32 |
| B | 8 | 23 | 27 |
| **Ponderado** | **20** | **AFEC 20** | **MPG 30** |

## Revogação, acesso e limites

A migração `V49__vehicle_ratings_comparables.sql` cria tabelas de rating, comparável, pareceres imutáveis, snapshots e linhas. Uma decisão de licença `REVOKED/DENIED` remove valores numéricos associados, exclui linhas dependentes e suspende snapshots. A expiração da vigência também bloqueia consultas e composição dinamicamente, mas o **expurgo programado por tempo** ainda pertence à #92. Não existe endpoint para exportação pública de parâmetros.

API interna `/v1/platform/vehicle-ratings`:
- `GET /board` — modelo, fontes, vetores EVR e decisões legíveis;
- `GET /sources/{id}/candidates` — linhas autorizadas;
- `POST /ratings` — registrar rating candidato com `Idempotency-Key`;
- `POST /pairs` — propor par com `Idempotency-Key`;
- `POST /pairs/{id}/review` — review segregado com `If-Match`;
- `GET /fleets/{id}/composition` — cobertura, erros e médias, ou valores nulos;
- `POST /fleets/{id}/snapshots` — congelar edição preparatória com `Idempotency-Key`.

A UI está em **Admin CarbonFlow → Fontes e prontidão → Eficiências e comparáveis**.

## A completar antes de encerrar a issue #95

- Importador PBEV nativo por PDF (dependente de direitos, assinaturas/QA e cobertura do catálogo) e ligação material dos campos de ciclo/capacidade por linha;
- Comparabilidade documentada de serviço, peso, potência e categoria e, quando aplicável, critérios conservadores da VM0038 com referências específicas e decisão por especialista;
- Definições precisas de vintage de frota x vintage de testes, regras de revisão, regimes flex/etanol/diesel e vínculos temporais;
- Confiança/cobertura real do PBEV por BEV do estoque nacional e propagação de STALE em `RequirementGraph`, seleção por projeto, cálculo BE/PE/ER e qualidade de medição;
- Prova de acesso/licença efetivamente utilizável para o projeto, não apenas para a organização interna.

O software **não pode declarar Verra/VVB aprovado nem calcular créditos reais** com estas versões candidatas.
