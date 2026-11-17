package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import com.fasterxml.jackson.databind.JsonNode;

import static br.carbonflow.pilot.SelectedCalculation.*;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class SelectedParameters {
    static final String ALTERNATIVE = "EF-SYN-v2";
    private final FixtureCatalog fixtures;
    private final PilotDatabase database;
    private final PilotAudit audit;
    private final PilotCommands commands;
    private final ProjectIntake intake;

    SelectedParameters(FixtureCatalog fixtures, PilotDatabase database, PilotAudit audit, PilotCommands commands,
                       ProjectIntake intake) {
        this.fixtures = fixtures;
        this.database = database;
        this.audit = audit;
        this.commands = commands;
        this.intake = intake;
    }

    ParameterCatalogue catalogue(String project) {
        var reference = fixtures.load(FixtureCatalog.DEFAULT);
        var factors = new ArrayList<Factor>();
        factors.add(pinned(reference));
        var alternative = reference.fixture().calculation().replacementCandidate();
        factors.add(new Factor(new Fixture.Reference(alternative.id(), 1), alternative.value(), alternative.unit(),
                "SIN", List.of(), "A amostra alternativa também não representa séries anuais reais.",
                CarbonCalculator.FIRST_START, CarbonCalculator.FIRST_START.plusYears(2), reference.provenance().source(),
                false, "CANDIDATO", "A alternativa permanece fora da seleção fixada."));
        factors.addAll(database.read(connection -> query(connection, """
                SELECT c.value, r.reason FROM pilot_factor_candidate c
                LEFT JOIN pilot_factor_review r USING (project_id,code,version)
                WHERE c.project_id=? ORDER BY c.code,c.version DESC LIMIT 100
                """, row -> {
                    var candidate = audit.decode(row.getString(1));
                    String reason = row.getString(2);
                    return new Factor(new Fixture.Reference(candidate.at("/reference/id").asText(),
                            candidate.at("/reference/version").asInt()), candidate.path("value").asText(),
                            candidate.path("unit").asText(), candidate.path("grid").asText(),
                            years(candidate.path("sourceYears")), "Anos declarados do candidato sintético.",
                            LocalDate.parse(candidate.path("validFrom").asText()),
                            LocalDate.parse(candidate.path("validUntilExclusive").asText()), candidate.path("source").asText(),
                            candidate.path("published").asBoolean(), reason == null ? "CANDIDATO" : "EXCLUIDO", reason);
                }, project)));
        return new ParameterCatalogue(BASELINE, DY, List.copyOf(factors), reference.provenance());
    }

    private List<Integer> years(JsonNode values) {
        var result = new ArrayList<Integer>();
        values.forEach(item -> result.add(item.asInt()));
        return List.copyOf(result);
    }

    Factor pinned(FixtureCatalog.Snapshot reference) {
        var factor = reference.fixture().calculation().factor();
        return new Factor(FACTOR, factor.value(), factor.unit(), "SIN", List.of(),
                "O valor fixado na E6 é sintético e não deriva de séries anuais.", CarbonCalculator.FIRST_START,
                CarbonCalculator.FIRST_START.plusYears(2), reference.provenance().source(), false, "FIXADO",
                "Hipótese ex ante de referência do kit 0.2.14.");
    }

    JsonNode propose(PilotAccess.Scope scope, String actor, String key, int expected, FactorInput input) {
        if (input == null || input.code() == null || !input.code().matches("EF-SIM-[A-Z0-9-]{1,45}")
                || !IntakeRules.decimal(input.value()) || !IntakeRules.text(input.unit()) || !IntakeRules.text(input.grid())
                || input.sourceYears() == null || input.sourceYears().isEmpty() || input.sourceYears().size() > 10
                || input.sourceYears().stream().anyMatch(year -> year == null || year < 1900 || year > 2200)
                || input.sourceYears().stream().distinct().count() != input.sourceYears().size()
                || input.validFrom() == null || input.validUntilExclusive() == null
                || !input.validFrom().isBefore(input.validUntilExclusive()) || !IntakeRules.text(input.source())) {
            throw invalid("PARAMETRO_INVALIDO", "factor", "Informe código sintético, valor, unidade, rede, anos-fonte, vigência e origem.");
        }
        return commands.execute(scope, actor, "PROPOR_FATOR", key, audit.encode(List.of(expected, input)), input.code(), connection -> {
            intake.lockProject(connection, scope.project());
            int latest = first(connection, "SELECT coalesce(max(version),0) FROM pilot_factor_candidate WHERE project_id=? AND code=?",
                    row -> row.getInt(1), scope.project(), input.code());
            PilotCommands.requireVersion(expected, latest);
            int version = latest + 1;
            var factor = new Factor(new Fixture.Reference(input.code(), version), input.value(), input.unit(),
                    input.grid(), input.sourceYears(), "Anos declarados do candidato sintético.", input.validFrom(),
                    input.validUntilExclusive(), input.source(), input.published(), "CANDIDATO", null);
            var event = audit.record(connection, scope.project(), actor, "PROPOR_FATOR", "PERMITIDO", input.code(),
                    Map.of("version", version, "origin", input.source(), "published", input.published()));
            update(connection, "INSERT INTO pilot_factor_candidate VALUES (?,?,?,?::jsonb,?,?)",
                    scope.project(), input.code(), version, audit.encode(factor), actor, event);
            return Map.of("factor", factor, "eventId", event);
        });
    }

    JsonNode exclude(PilotAccess.Scope scope, String actor, String key, String code, int version, String reason) {
        if (!IntakeRules.text(reason)) throw invalid("REVISAO_INCOMPLETA", "reason", "Informe o motivo da exclusão.");
        return commands.execute(scope, actor, "EXCLUIR_FATOR", key, audit.encode(List.of(code, version, reason)), code, connection -> {
            String owner = first(connection, "SELECT submitted_by FROM pilot_factor_candidate WHERE project_id=? AND code=? AND version=? FOR UPDATE",
                    row -> row.getString(1), scope.project(), code, version);
            if (owner == null) throw PilotAccess.invisible();
            distinct(owner, actor);
            var event = audit.record(connection, scope.project(), actor, "EXCLUIR_FATOR", "PERMITIDO", code,
                    Map.of("version", version, "reason", reason));
            if (update(connection, "INSERT INTO pilot_factor_review VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    scope.project(), code, version, actor, reason, event) == 0) {
                throw new PilotProblem(409, "PARAMETRO_JA_REVISADO", "factor", "OP-E6-07", "A decisão já foi registrada nesta versão.");
            }
            return Map.of("reference", new Fixture.Reference(code, version), "state", "EXCLUIDO", "eventId", event);
        });
    }

    static void distinct(String owner, String actor) {
        if (owner.equals(actor)) throw new PilotProblem(403, "AUTOAPROVACAO_NEGADA", "actor", "OP-E6-07",
                "Outra identidade deve revisar a proposta.");
    }

    static PilotProblem invalid(String code, String field, String message) {
        return PilotProblem.invalid(code, field, "OP-E6-07", message);
    }
}
