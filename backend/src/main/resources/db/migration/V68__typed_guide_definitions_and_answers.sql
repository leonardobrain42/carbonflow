-- CF-UX-TECH-001 / UX-02B. Dedicated typed answer revisions; V26/V66 legacy JSON is immutable.
-- Definitions are scoped to the methodology edition and never masquerade as authoritative Verra rules.
CREATE TABLE app_typed_guide_schema (
 guide_id text NOT NULL REFERENCES app_technical_guide(id),
 methodology text NOT NULL,
 edition text NOT NULL,
 branch text NOT NULL,
 definition_version integer NOT NULL CHECK (definition_version>0),
 fields jsonb NOT NULL CHECK (jsonb_typeof(fields)='array'),
 PRIMARY KEY(guide_id,definition_version)
);
CREATE TABLE app_typed_guide_revision (
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 guide_id text NOT NULL REFERENCES app_technical_guide(id),
 version integer NOT NULL CHECK(version>0),
 definition_version integer NOT NULL,
 methodology text NOT NULL,
 edition text NOT NULL,
 answers jsonb NOT NULL CHECK(jsonb_typeof(answers)='object'),
 definition_snapshot jsonb NOT NULL CHECK(jsonb_typeof(definition_snapshot)='array'),
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 saved_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,guide_id,version),
 FOREIGN KEY(project_id,organization_id,environment) REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(guide_id,definition_version) REFERENCES app_typed_guide_schema(guide_id,definition_version)
);
CREATE TABLE app_typed_guide_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL REFERENCES app_project(id),
 guide_id text NOT NULL REFERENCES app_technical_guide(id),
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 result jsonb NOT NULL CHECK(jsonb_typeof(result)='object'),
 PRIMARY KEY(actor_id,command_key)
);
CREATE TRIGGER app_typed_guide_revision_immutable BEFORE UPDATE OR DELETE ON app_typed_guide_revision
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_typed_guide_schema_immutable BEFORE UPDATE OR DELETE ON app_typed_guide_schema
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

INSERT INTO app_typed_guide_schema(guide_id,methodology,edition,branch,definition_version,fields)
 VALUES ('applicability','VM0038_V1_1','1.1','OPEN_BR_BEV_LDV_DCFC_SIN',1,'[{"key":"incidence","label":"Incidência candidata do critério","type":"ENUM","options":["A confirmar","Aplicável","Não aplicável"],"helpText":"Não confirma aplicabilidade. A decisão metodológica e sua fonte exigem revisão.","required":false},{"key":"justification","label":"Justificativa da não aplicabilidade","type":"TEXTAREA","options":[],"helpText":"Obrigatória quando declarar ''Não aplicável''; não é parecer normativo.","required":false,"showWhenField":"incidence","showWhenValue":"Não aplicável","requiredWhenField":"incidence","requiredWhenValue":"Não aplicável"},{"key":"stationCode","label":"Estação já cadastrada","type":"STATION_REF","options":[],"helpText":"Selecione uma estação real do inventário canônico. Sem seleção não há fronteira comprovada.","required":false},{"key":"chargerCode","label":"Equipamento da estação","type":"EQUIPMENT_REF","options":[],"helpText":"Selecione um DCFC/SIN da estação. Não cria energia medida nem altera a fronteira.","required":false,"showWhenField":"stationCode","showWhenValue":"SET"},{"key":"plannedDate","label":"Data operacional prevista","type":"DATE","options":[],"helpText":"Previsão operacional não é automaticamente data de início VCS.","required":false},{"key":"unrestrictedAccess","label":"Recarga aberta a qualquer BEV compatível?","type":"BOOLEAN","options":[],"helpText":"Declaração inicial; requer verificações de critérios e prova de acesso.","required":false}]'::jsonb);

INSERT INTO app_typed_guide_schema(guide_id,methodology,edition,branch,definition_version,fields)
 VALUES ('baseline','VM0038_V1_1','1.1','OPEN_BR_BEV_LDV_DCFC_SIN',1,'[{"key":"scenario","label":"Alternativa ao projeto (descrição)","type":"TEXTAREA","options":[],"helpText":"Contexto humano do cenário de linha de base; não cria comparáveis admitidos.","required":false},{"key":"vehicleClass","label":"Classe de veículo da comparação","type":"ENUM","options":["LDV","A confirmar"],"helpText":"Escolha a classe do estudo; valide as fontes brasileiras antes de calcular.","required":false},{"key":"referenceYear","label":"Ano do dado de eficiência","type":"YEAR","options":[],"helpText":"Ano de referência do catálogo candidato, não da metodologia.","required":false,"showWhenField":"vehicleClass","showWhenValue":"LDV"},{"key":"efficiencyValue","label":"Consumo candidato","type":"DECIMAL","options":[],"helpText":"Número positivo indicativo; não é entrada admitida no cálculo E8.","required":false,"unit":"ver campo unidade","min":0.001,"max":10000},{"key":"efficiencyUnit","label":"Unidade original do consumo","type":"ENUM","options":["kWh/100km","km/L"],"helpText":"Unidades distintas não são convertidas automaticamente.","required":false,"showWhenField":"efficiencyValue","showWhenValue":"SET","requiredWhenField":"efficiencyValue","requiredWhenValue":"SET"}]'::jsonb);

INSERT INTO app_typed_guide_schema(guide_id,methodology,edition,branch,definition_version,fields)
 VALUES ('additionality','VM0038_V1_1','1.1','OPEN_BR_BEV_LDV_DCFC_SIN',1,'[{"key":"route","label":"Rota candidata de adicionalidade","type":"ENUM","options":["Incidência a verificar","Lista positiva VMD0049 a verificar","Demonstração por projeto a revisar"],"helpText":"A rota permanece sujeita a incidência da metodologia e análise independente.","required":false},{"key":"considered","label":"Consideração da receita de carbono","type":"DATE","options":[],"helpText":"Data declarada; evento e documento permanecem no módulo jurídico.","required":false},{"key":"decision","label":"Decisão de investimento","type":"DATE","options":[],"helpText":"Data declarada, não evento juridicamente comprovado.","required":false},{"key":"commitment","label":"Primeiro compromisso vinculante","type":"DATE","options":[],"helpText":"Data declarada: pode anteceder consideração de receita e exige revisão.","required":false}]'::jsonb);

INSERT INTO app_typed_guide_schema(guide_id,methodology,edition,branch,definition_version,fields)
 VALUES ('monitoring','VM0038_V1_1','1.1','OPEN_BR_BEV_LDV_DCFC_SIN',1,'[{"key":"energyKinds","label":"Fluxos a planejar","type":"MULTI_SELECT","options":["ED","EC"],"helpText":"ED e EC são grandezas diferentes; esta seleção não insere leituras.","required":false},{"key":"readingFrequency","label":"Frequência prevista de medição","type":"ENUM","options":["A confirmar","Horária","Diária"],"helpText":"O período e a fonte EFel podem exigir granularidade horária.","required":false},{"key":"calibrationValidUntil","label":"Validade declarada da calibração","type":"DATE","options":[],"helpText":"Informe somente como planejamento; certificado vinculado ao medidor é canônico.","required":false}]'::jsonb);

INSERT INTO app_typed_guide_schema(guide_id,methodology,edition,branch,definition_version,fields)
 VALUES ('safeguards','VM0038_V1_1','1.1','OPEN_BR_BEV_LDV_DCFC_SIN',1,'[{"key":"consultationPlanned","label":"Consulta a partes interessadas prevista?","type":"BOOLEAN","options":[],"helpText":"Não presume consulta realizada.","required":false},{"key":"consultationDate","label":"Data prevista da consulta","type":"DATE","options":[],"helpText":"Previsão não comprova realização nem recibo.","required":false,"showWhenField":"consultationPlanned","showWhenValue":"true"},{"key":"riskContext","label":"Risco ou salvaguarda a estudar","type":"TEXTAREA","options":[],"helpText":"Contexto de trabalho. Encaminhe prova e parecer na etapa própria.","required":false}]'::jsonb);

INSERT INTO app_typed_guide_schema(guide_id,methodology,edition,branch,definition_version,fields)
 VALUES ('quantification','VM0038_V1_1','1.1','OPEN_BR_BEV_LDV_DCFC_SIN',1,'[{"key":"periodStart","label":"Início do recorte","type":"DATE","options":[],"helpText":"Não confere completude dos dados ED/EC.","required":false},{"key":"periodEnd","label":"Fim do recorte","type":"DATE","options":[],"helpText":"Deve ser igual ou posterior ao início do período.","required":false},{"key":"vintage","label":"Ano de referência","type":"YEAR","options":[],"helpText":"Ano inteiro de 1900 a 2100; não é evidência de período.","required":false},{"key":"sourceKinds","label":"Fontes consideradas no planejamento","type":"MULTI_SELECT","options":["ED","EC","EFel","EVR","Combustível"],"helpText":"Estas são opções de planejamento; nenhuma é automaticamente admitida.","required":false}]'::jsonb);

-- This migration does not change legacy guide answers, timestamps, hashes, E8 or PD status.
