# CF-OPEN-007 / #96 — Caderno técnico VT0011 v1.0 + TOOL07 v7.0, caso 2 (fatia 10)

**Estado:** implementação preparatória com rastreabilidade, revisão interna e preview puramente algébrico. **Não calcula OM a partir de usinas, não valida o procedimento completo da TOOL07, não admite EFel, não gera PE/BE/ER e não emite VCU.**

## Referências normativas consultadas (20/11/2026)

1. [VM0038 Electric Vehicle Charging Systems v1.1 — Verra](https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/) — ativa desde 19 agosto de 2026 e adota VT0011.
2. [VT0011 Electricity System Emission Factors v1.0 — Verra, PDF](https://verra.org/documents/vt0011-electricity-system-emission-factors-v1-0/) — ativa desde fevereiro de 2025. Aplicada em conjunto com TOOL07, alterando seus parágrafos conforme indicado.
3. [TOOL07 v7.0 — UNFCCC/CDM, PDF](https://cdm.unfccc.int/methodologies/PAmethodologies/tools/am-tool-07-v7.0.pdf) — guia dos quatro métodos OM, dados de usinas e Eq. 16 da margem combinada.

### Seleção técnica restrita e verificada

**VT0011 §5, parágrafo 86, caso 2:** para projetos que aumentam o consumo de eletricidade da rede, os pesos são OM = 1,0 e BM = 0,0. O valor de BM **não é presumido zero**; seu *peso* é nulo nesse caso.

**TOOL07 v7.0 §6.3, parágrafo 38:** métodos OM: Simple OM; Simple adjusted OM; Dispatch data analysis OM; Average OM. A escolha deve ser demonstrada com as condições de elegibilidade do TOOL07, não é feita automaticamente. O caderno registra escolha, justificativa e documentação.

**TOOL07 Eq. 16:** CM = OM × wOM + BM × wBM. A classe `OpenVt0011Case2Math` ilustra, para um fator OM **informado mas não verificado**, o resultado numérico OM × 1.0, assim como a conversão aritmética de **tCO2/MWh para tCO2/kWh** (÷ 1000) por `BigDecimal`. Isso **não** determina OM e **não** transforma CO2 em CO2e. O fator EFel anterior da V60 é CO2e horário, por isso não pode ser substituído pelo preview de CO2.

A ferramenta VT0011 modifica regras de importações, combustíveis e margem de construção; tais regras *não* são reproduzidas integralmente nesta fatia. **Nenhum default de 1,3 tCO2/MWh nem fator MCTI foi aplicado automaticamente.** A indicação de caso 2 pressupõe a fronteira do projeto DCFC/SIN GRID_ONLY da versão dinâmica e continua sujeita à análise de suas particularidades.

## V61 — modelo append-only (nunca admitido)

- `app_open_vt0011_case2_workbook`: registro por organização/projeto/período/série EFel (FK ao candidato V60) com versão, caso 2 fixo, edição VT0011 1.0 / TOOL07 7.0, método OM escolhido, vintage EX_ANTE/EX_POST e ano, OM *declarado* em tCO2/MWh, referência ao conjunto de dados e SHA-256 declarado, fundamentação do método, fronteira, amostra, importações, vintage e limitações CO2/CO2e. Fixa SHA da fonte EFel, a **versão do último parecer interno EFel** e SHA determinístico do caderno. Os pesos OM/BM são constraints SQL 1,00/0,00.
- `app_open_vt0011_case2_review`: parecer REV por versão, com justificativa, evidência e autor; trigger de banco impede reviewer igual a autor do caderno **ou** remetente da fonte EFel.
- `app_open_vt0011_case2_command`: replay idempotente por identidade+chave+fingerprint. Chaves não podem ser reutilizadas em outro projeto/ação/payload. Triggers de UPDATE/DELETE impedem adulteração de histórico.
- Banco obriga `state='INTERNAL_WORKBOOK_NOT_ADMITTED'` e `usable_for_e8=false`. O preview `OpenVt0011Case2Math.Preview` também retorna `usableForE8=false`.

## API e UX
- `GET /v1/projects/{project}/technical/open-monitoring/dossiers/{period}/efel-candidates/{efel}/vt0011-workbooks` — histórico de até 20 versões, status dinâmico, preview e lacunas explícitas.
- `POST` na mesma URL com `{efelCandidateId,omMethod,omVintageMode,omDataYear,omFactorTco2PerMwh,omSourceReference,omDatasetSha256,omMethodJustification,gridBoundaryEvidence,sampleSelectionEvidence,importTreatmentEvidence,vintageEvidence,methodologyNote}`, `If-Match: "N"` e `Idempotency-Key`.
- `POST .../vt0011-workbooks/{workbook}/reviews` com `{outcome,rationale,evidenceReference}` e pré-condições de revisão.

Só DRAFT_OWNER/PREP com MONITORING pode redigir; REV com MONITORING lê e revisa; OP/CALC-only não acessam a memória técnica nem os números. Consulta read-only no preflight E8 expõe apenas `E8_VT0011_CASE2_M1/M2` e estado, mantendo todos os cálculos reais bloqueados.

Na tela: **Monitoramento → MR → Pacote ED/EC → EFel → Caderno VT0011** dentro da estrutura estável. Formulários não selecionam método OM/vintage nem fornecem fator padrão. Prévia tCO2 em unidade correta, justificativa e pendências visíveis.

## Gate STALE e continuidade histórica

Caderno torna-se `STALE_NOT_ADMITTED` se o pacote ED/EC, o MR, a fonte EFel ou o parecer EFel tiverem sido substituídos, devolvidos ou ficarem obsoletos; quando há nova revisão do caderno, a anterior é `SUPERSEDED_NOT_ADMITTED`. Parecer `CHECKED_INTERNAL` não remove os bloqueios técnicos de admissibilidade: prova de dados OM, autenticidade de fonte, conversão de gases, vínculo com EFel horário e execução do E8. Valores exatos e hashes anteriores permanecem.

## Testes e limitações

- Testes matemáticos de pesos 1/0, unidade tCO2/MWh → tCO2/kWh, zero explícito e precisão inválida.
- Integração Quarkus/PostgreSQL com **dados sintéticos de teste**: pacote ED=2.700 kWh / EC=3.000 kWh, EFel candidata 0,5 tCO2e/MWh (não real), OM *ilustrativa* 0,5 tCO2/MWh. CM ilustrativa 0,5 tCO2/MWh = 0,0005 tCO2/kWh, mas **não** equivalente ao EFel CO2e e **não** aproveitável no E8.
- Versões, segurança multi-tenant, If-Match e idempotência, revisão independente e STALE após MR v2, sem contaminação PIL-SYN.

**Pendente das issues #96/#97/#98:** implementar validações condicionais e equações OM de TOOL07 por método selecionado (incluindo LCMR, imports, limites e amostras), obter dados brasileiros com licença e autenticidade, conferir vintages, evidenciar vínculo CO2→CO2e quando aplicável, ligação real entre OM/EFel horário, formalizar aprovação normativa por finalidade e, só então, habilitar cálculo E8. A fórmula de Case2, isolada, não conclui esses trabalhos.
