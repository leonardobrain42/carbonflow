-- CF-OPEN-001/002: inventory of required documentary/data inputs for a real VM0038 open-network journey.
-- Only metadata describing missing sources is stored here. No protected normative content is copied.
CREATE TABLE app_methodology_source_need (
    code text PRIMARY KEY,
    methodology text NOT NULL,
    methodology_edition text NOT NULL,
    scenario text NOT NULL,
    stage text NOT NULL CHECK (stage IN ('APPLICABILITY','BASELINE','ADDITIONALITY','QUANTIFICATION','MONITORING','DOCUMENTS')),
    kind text NOT NULL CHECK (kind IN ('NORMATIVE','DATASET','PROJECT_EVIDENCE')),
    title text NOT NULL,
    purpose text NOT NULL,
    source_id text,
    source_edition text,
    official_uri text,
    mandatory boolean NOT NULL DEFAULT true,
    condition_note text NOT NULL DEFAULT '',
    CHECK ((source_id IS NULL) = (source_edition IS NULL)),
    CHECK ((kind = 'PROJECT_EVIDENCE') = (source_id IS NULL))
);
CREATE INDEX app_methodology_source_need_context ON app_methodology_source_need(methodology,methodology_edition,scenario,stage);

-- Expected sources, NOT assertions that a source, factor, license or real normative pack has been admitted.
INSERT INTO app_methodology_source_need
 (code,methodology,methodology_edition,scenario,stage,kind,title,purpose,source_id,source_edition,official_uri,mandatory,condition_note)
VALUES
 ('OPEN-METHOD','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','APPLICABILITY','NORMATIVE','VM0038 v1.1','Método, elegibilidade de rede aberta, linha de base e monitoramento','VM0038','1.1','https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/',true,'Documento conhecido por metadados, regras reais ainda não publicadas no motor'),
 ('OPEN-POSITIVE','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','ADDITIONALITY','NORMATIVE','VMD0049 v1.1','Lista positiva por país, categoria e tecnologia','VMD0049','1.1','https://verra.org/methodologies/vmd0049-positive-list-for-electric-vehicle-charging-systems-v1-1/',true,'Entrada do Brasil não significa aprovação da atividade'),
 ('OPEN-VCS','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','APPLICABILITY','NORMATIVE','VCS Standard (edição a resolver)','Regras do programa e excedente regulatório','VCS-STANDARD','5.0','https://verra.org/programs/verified-carbon-standard/',true,'Versão efetivamente incidente deve ser resolvida pelo ato/data; 5.0 é candidata'),
 ('OPEN-VT0011','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','QUANTIFICATION','NORMATIVE','VT0011 v1.0','Fator de emissões da rede elétrica para consumo adicional','VT0011','1.0','https://verra.org/methodologies/vt0011-electricity-system-emission-factors-v1-0/',true,'Determinar modalidade OM/BM e inputs segundo VT0011'),
 ('OPEN-TOOL07','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','QUANTIFICATION','NORMATIVE','CDM TOOL07 v7.0','Ferramenta usada em conjunto com VT0011','TOOL07','7.0',null,true,'Não substituir por fator médio corporativo'),
 ('OPEN-VT0008','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','ADDITIONALITY','NORMATIVE','VT0008 (edição aplicável)','Análise de investimento/barreiras e prática comum para rota alternativa','VT0008','1.0',null,false,'Condicional quando a lista positiva não é atendida'),
 ('OPEN-EV-PENETRATION','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','APPLICABILITY','DATASET','Estoque EV e participação nacional recentes','Verificar condição de penetração §4(1)(b), quando usada','BR-EV-STOCK-SHARE','LATEST', 'https://www.gov.br/transportes/pt-br/assuntos/transito/conteudo-Senatran/frota-de-veiculos-2026',false,'Alternativa à condição 1(a); verificar dados mais recentes à validação'),
 ('OPEN-EV-SALES','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','APPLICABILITY','DATASET','Participação nacional de vendas EV, média trienal','Comparação com limite de 30% quando §4(1)(b) for utilizada','BR-EV-SALES-SHARE-3Y','LATEST',null,false,'Não confundir vendas com estoque acumulado'),
 ('OPEN-EVR','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','BASELINE','DATASET','Frota BEV em circulação por modelo/ano (EVR)','Composição representativa nacional para AFEC/MPG','BR-LDV-BEV-STOCK-BY-MODEL','YEAR',null,true,'Senatran agregada pode ser insuficiente; PBEV não contém EVR'),
 ('OPEN-PBEV','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','BASELINE','DATASET','Inmetro PBEV e ratings BEV/combustão','Eficiências EV e MPG dos comparáveis com mesmo ciclo','BR-PBEV-VEHICLE-RATINGS','YEAR','https://www.gov.br/inmetro/pt-br/assuntos/regulamentacao/avaliacao-da-conformidade/programa-brasileiro-de-etiquetagem/tabelas-de-eficiencia-energetica/veiculos-automotivos-pbe-veicular',true,'Edição/ano/ciclo precisam ser explicitados'),
 ('OPEN-FOSSIL-EF','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','BASELINE','DATASET','Fatores de emissão fóssil por combustível/ano','EF de BE em tCO2e/galão americano','BR-FOSSIL-FUEL-EF','YEAR',null,true,'Revisar gases, mistura, volume e conversões'),
 ('OPEN-GRID-EF','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','QUANTIFICATION','DATASET','Dados SIN para fator elétrico admissível VT0011','EFel do consumo elétrico do projeto em tCO2e/kWh','BR-SIN-VT0011-INPUTS','YEAR','https://www.gov.br/mcti/pt-br/acompanhe-o-mcti/sirene/dados-e-ferramentas/fatores-de-emissao',true,'Não confundir série MCTI publicada com fator automaticamente admissível'),
 ('OPEN-RIGHTS','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','APPLICABILITY','PROJECT_EVIDENCE','Titularidade e divulgação de reduções aos motoristas','Contratos, avisos, termos, provas de publicação e validade',null,null,null,true,'Prova pertence ao projeto/operador, não ao catálogo global'),
 ('OPEN-REG-SURPLUS','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','ADDITIONALITY','PROJECT_EVIDENCE','Excedente regulatório e legislação incidente','Parecer fundamentado e normas territoriais aplicáveis',null,null,null,true,'Análise jurídica/técnica do projeto; não aprovação automática'),
 ('OPEN-CHRONOLOGY','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','ADDITIONALITY','PROJECT_EVIDENCE','Cronologia e compromissos do empreendimento','Atas, contratos, decisão de investimento, consideração do carbono',null,null,null,true,'A exigibilidade concreta depende da rota e das regras incidentes'),
 ('OPEN-ED-EC','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','MONITORING','PROJECT_EVIDENCE','Medidores, calibração e registros ED/EC','Energia entregue, energia consumida, abrangência e QA/QC',null,null,null,true,'Cálculo ex ante pode usar estimativa identificada; monitorado exige medições'),
 ('OPEN-PD','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','DOCUMENTS','PROJECT_EVIDENCE','Dossiê PD com prova por critério','Narrativa, fatos, versões e revisão interna',null,null,null,true,'Não confundir PD interno com validação externa')
;
