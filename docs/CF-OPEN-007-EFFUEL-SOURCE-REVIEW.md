# CF-OPEN-007 / issue #96 — Fator fóssil brasileiro candidato com fonte e parecer

## Estado e objetivo

Primeira integração **administrativa** do parâmetro EFfuel para VM0038 v1.1, rede OPEN Brasil LDV/BEV/DCFC/SIN. Uma fonte apresentada pela plataforma precisa possuir metadados publicados, arquivo em quarentena criptografada, linha extraída pelo parser tabular, avaliação técnica da fonte e licença de processamento **vigente e independente**. Esta fatia NÃO cria `ProjectParameterBinding`, não aprova insumos de projeto, não executa E8, não publica valores para usuários e não altera o piloto.

Não usar os valores sintéticos dos testes como fator brasileiro. Somente um dado real de fonte elegível e seu parecer podem dar origem a um candidato técnico; eventual admissibilidade normativa ainda precisa de um fluxo distinto.

## Jornada administrativa

1. **Fonte:** a equipe de aquisição registra a necessidade `OPEN-FOSSIL-EF`, faz upload autorizado de CSV/XLSX e extrai a métrica tipada `EF_FUEL_GASOLINE`, ano, território BR e unidade explícita. Uma pessoa independente revisa o escopo da fonte (`VM0038/1.1/OPEN_LDV_DCFC_SIN_BR/BASELINE_BE`) e aprova ou rejeita o arquivo dentro da ferramenta de fontes.
2. **Preparação EFfuel:** Admin CarbonFlow → Fontes e prontidão → **Fatores fósseis EFfuel**. Gestor autorizado seleciona arquivo e linha extraída; não digita fator numérico. O backend valida origem, organização, linha e licença, converte com `OpenEmissionParameterMath` e registra `app_open_parameter_candidate` (V50) com SHA, localizador, original, unidade, valor convertido e fórmula.
3. **Revisão:** um revisor técnico configurado e com MFA, diferente de quem cadastrou o fator e de quem submeteu/fizer upload da fonte, registra decisão `APPROVED_FOR_SCOPE`, `REJECTED_FOR_SCOPE` ou `NEEDS_INFORMATION`, edição normativa, localizador e justificativa. O registro imutável é `app_open_parameter_assessment` (V50).
4. **Licença:** cada leitura e operação confirma `EXTRACT_TEXT`, `SHOW_EXCERPTS` e `EXPORT_DERIVATIVES` no direito da mesma organização, edição e objeto. Em caso de expiração/revogação, os valores não são exibidos; a revogação aciona os gatilhos existentes da V50 que expurgam os derivados numéricos.

## Contratos

`GET /v1/platform/open-fossil-factors`: fontes elegíveis e candidatos da organização sob `PlatformSourceReadiness.requireViewer`. Os valores são ocultados quando a licença não está disponível.

`GET /v1/platform/open-fossil-factors/sources/{objectId}/rows`: linhas licenciadas correspondentes à métrica `EF_FUEL_GASOLINE`.

`POST /v1/platform/open-fossil-factors`: `{"sourceObjectId":"<uuid>","rowIndex":2}`, apenas equipe gestora. Gás CO2e, combustível GASOLINE e geografia BR são **tipados e fixos** nesta primeira fatia, não opções livres.

`POST /v1/platform/open-fossil-factors/{candidateId}/reviews`: `{"outcome":"...","ruleReference":"...","evidenceLocator":"...","reason":"..."}`, revisão independente.

Todas as respostas usam `private, no-store` e `nosniff`. As ações são auditadas sob MFA; nenhuma rota pública/de projeto é adicionada nesta entrega.

## Transformação dimensional

As unidades explicitamente aceitas no parser foram ampliadas para `tCO2e/L`, `kgCO2e/L` e `tCO2e/USgal`. O alvo obrigatório da preparação é `tCO2e/USgal`. A conversão usa galão US exato `3.785411784 L`, quilograma/t=1000 quando aplicável, `BigDecimal` e escala de saída 18. O gás precisa ser **CO2e na origem**: um fator CO2 isolado não é transformado em CO2e por conveniência ou por GWP implícito. Outros combustíveis (diesel, etanol/flex), conversões por massa/energia, GWP e composição de gases exigem revisão e implementação específica.

O parecer de escopo técnico não comprova qualidade nacional da amostra, documento oficial, vintage correto, equipamento, regulamentação ou aplicabilidade normativa. Logo, `usableForE8=false` **sempre**, mesmo após `CHECKED_INTERNAL_NOT_ADMITTED`.

## Testes e aceite

- Testes dimensionais: cada unidade e fórmula, valor ausente/negativo, incompatibilidade CO2 vs CO2e, IR não substituído por 1 e unidade imperial não presumida.
- Teste de integração Quarkus/PostgreSQL: MFA obrigatória, organização isolada, recusa de fonte e linha invisíveis, ausência de número livre no POST, derivação do campo numérico da linha original, persistência V50, duplicata rejeitada, revisão segregada, leitura por perfil, e revogação com ocultação de valores.
- Angular: seleção acessível da fonte/linha, exposição da conversão, texto dos gates, estado sem admissão e revisão humana.

**Não fecha #96:** ainda faltam fontes oficiais realmente verificadas, EFfuel por gases/combustíveis adicionais, IR, Dy, validações de outros gases e unidade, `ProjectParameterBinding` imutável por projeto/vintage, admissão normativa, decisão sobre direitos e integração transacional ao runner E8 da #97. O PR #133 (diagnóstico V63/V64) é independente desta fatia e não precisa ser mesclado para que esta integração seja testada.
