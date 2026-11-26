-- Roteiro #124, Q13 (D21), passo 2a: declaração da transição normativa do fator fóssil dentro do ano, gravada junto da avaliação
-- de IR. Aditiva e sem efeito de cálculo: nenhuma guarda é relaxada nesta migração (o reuso e a consolidação seguem iguais).
-- O conjunto de entradas é calculado pelo servidor a partir dos pinos do ano; o ato normativo e a evidência são declarados.
CREATE TABLE app_open_ir_fuel_transition (
 assessment_id uuid PRIMARY KEY REFERENCES app_open_ir_assessment(id),
 act_reference text NOT NULL CHECK (length(btrim(act_reference)) BETWEEN 8 AND 300),
 evidence_locator text NOT NULL CHECK (length(btrim(evidence_locator)) BETWEEN 3 AND 300),
 entry_ids uuid[] NOT NULL CHECK (cardinality(entry_ids) >= 2),
 set_sha256 bytea NOT NULL CHECK (octet_length(set_sha256) = 32)
);
CREATE TRIGGER app_open_ir_fuel_transition_immutable
 BEFORE UPDATE OR DELETE ON app_open_ir_fuel_transition
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
