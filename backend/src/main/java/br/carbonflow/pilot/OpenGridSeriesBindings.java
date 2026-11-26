package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roteiro #124 PR 3: derives a project's hourly EFel candidate (V60, the owner of the rules) from the platform series
 * (V113) for exactly the hours the project's ED/EC package covers. Nothing is copied beyond those hours, the original
 * literal value is kept, and the gas boundary is explicit: only CO2_ONLY (VM0038 v1.1, section 6, Table 1), with
 * EF_CO2e = EF_CO2 x GWP(CO2) and GWP(CO2) = 1 recorded on the binding. A boundary that needs CH4 or N2O is refused because
 * no electric CH4/N2O factor exists. Hours the series cannot place in UTC are never guessed. The result is a candidate:
 * NOT_ADMITTED, and the time basis stays "declared by people" until an official source defines it.
 */
@ApplicationScoped
class OpenGridSeriesBindings {
    static final String RULE = "CF-OPEN-007";
    record Input(UUID energyCandidateId, UUID gridSeriesId, String boundary, String definitionHypothesis) {}
    record Binding(UUID id, UUID candidateId, UUID gridSeriesId, String boundary, String sourceGas, String gwpCo2,
                   String conversion, int coveredHours, String seriesContentSha256, String timezone,
                   String hourConvention, String timeBasis, String definitionHypothesis, UUID licenseId, String licenseBasis, OffsetDateTime createdAt) {}
    record Result(OpenEfelCandidates.View candidate, Binding binding, List<String> gates, String admission) {}

    private final DynamicProjectScope scope;
    private final OpenEfelCandidates efel;

    OpenGridSeriesBindings(DynamicProjectScope scope, OpenEfelCandidates efel) {
        this.scope = scope;
        this.efel = efel;
    }

    Result bind(String cookie, String project, String period, String match, String key, Input input) {
        return scope.write(cookie, project, "SUBMIT_OPEN_EFEL_CANDIDATE", (c, ctx) -> {
            if (!ctx.owner() && !"PREP".equals(ctx.role())) throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "project", RULE, "Recurso não disponível.");
            if (input == null || input.energyCandidateId() == null || input.gridSeriesId() == null || input.boundary() == null)
                throw bad("input", "Informe o pacote de energia, a série nacional e a fronteira de gases.");
            String hypothesis = input.definitionHypothesis() == null ? "DISPATCH_TOP" : input.definitionHypothesis();
            if (!java.util.Set.of("DISPATCH_TOP", "SYSTEM_AVERAGE").contains(hypothesis))
                throw bad("definitionHypothesis", "Use DISPATCH_TOP (topo do despacho) ou SYSTEM_AVERAGE (média do sistema).");
            var boundary = FuelBoundaries.preset(input.boundary());
            if (boundary == null) throw bad("boundary", "Fronteira desconhecida.");
            if (!boundary.projectGases().equals(List.of("CO2")))
                throw new PilotProblem(422, "FRONTEIRA_SEM_FATOR_ELETRICO_DE_CH4_N2O", "boundary", RULE,
                        "Esta fronteira inclui CH4 e N2O, e não há fator elétrico desses gases; use CO2_ONLY.");
            var series = first(c, """
                    SELECT e.id,e.source_id,e.edition,e.workbook_sha256,e.content_sha256,e.original_unit,e.timezone,
                           e.hour_convention,e.imported_at,e.license_id,e.license_decision_version,e.license_basis,
                           s.title,s.rights_holder,s.official_uri,l.license_identifier,e.data_year
                    FROM app_grid_series_edition e
                    JOIN app_source_version s ON s.source_id=e.source_id AND s.edition=e.edition
                    JOIN app_content_license l ON l.id=e.license_id
                    WHERE e.id=?
                    """, r -> new Object[]{r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                    r.getString(6), r.getString(7), r.getString(8), r.getObject(9, OffsetDateTime.class), r.getObject(10, UUID.class),
                    r.getInt(11), r.getString(12), r.getString(13), r.getString(14), r.getString(15), r.getString(16), r.getInt(17)},
                    input.gridSeriesId());
            if (series == null) throw new PilotProblem(404, "SERIE_INEXISTENTE", "gridSeriesId", RULE, "Série nacional não encontrada.");
            String unit = switch ((String) series[5]) {
                case "tCO2/MWh" -> "tCO2e/MWh";
                case "tCO2/kWh" -> "tCO2e/kWh";
                case "kgCO2/kWh" -> "kgCO2e/kWh";
                default -> throw bad("unit", "Unidade da série fora do vocabulário.");
            };
            var expected = OpenEfelCandidates.energyHoursOf(c, project, input.energyCandidateId());
            if (expected.isEmpty()) throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "energyCandidateId", RULE, "Pacote de energia não encontrado.");
            Map<Instant, String> found = new HashMap<>();
            for (var row : query(c, "SELECT interval_start_utc,value_text FROM app_grid_series_value WHERE edition_id=? AND interval_start_utc IS NOT NULL "
                    + "AND interval_start_utc >= ? AND interval_start_utc <= ?",
                    r -> new Object[]{r.getTimestamp(1).toInstant(), r.getString(2)}, input.gridSeriesId(),
                    expected.getFirst(), expected.getLast()))
                found.put((Instant) row[0], (String) row[1]);
            var hours = new ArrayList<OpenEfelCandidates.HourInput>();
            var missing = new ArrayList<String>();
            for (var hour : expected) {
                String value = found.get(hour.toInstant());
                if (value == null) missing.add(hour.toInstant().toString());
                else hours.add(new OpenEfelCandidates.HourInput(hour.withOffsetSameInstant(ZoneOffset.UTC), new BigDecimal(value)));
            }
            if (!missing.isEmpty()) throw new PilotProblem(409, "SERIE_NAO_COBRE_AS_HORAS", "gridSeriesId", RULE,
                    "A série nacional não tem valor com instante UTC para " + missing.size() + " hora(s) do pacote de energia (por exemplo "
                            + missing.get(0) + "): fora do período da série ou horas ambíguas. Nada é completado ou extrapolado.");
            String basis = (String) series[11];
            String note = "Derivado da série nacional " + series[0] + " (conteúdo " + ((String) series[4]).substring(0, 12) + "…), "
                    + hours.size() + " hora(s) do pacote de energia. Fronteira CO2_ONLY (VM0038 v1.1, seção 6, Tabela 1): fator de CO2 "
                    + "convertido em CO2e com GWP(CO2)=1 aplicado de forma explícita. Fuso " + series[6] + " e convenção " + series[7]
                    + " declarados por pessoas (sem definição oficial encontrada). Hipótese de trabalho sobre o valor horário: " + hypothesis
                    + " (declarada por pessoas; matriz R11 em aberto até resposta do MCTI/ONS). Data de publicação da planilha desconhecida: usada a data de importação.";
            var candidateInput = new OpenEfelCandidates.Input(input.energyCandidateId(), (String) series[12], (String) series[13],
                    (String) series[14], ((String) series[2]).length() >= 2 ? (String) series[2] : "ed-" + series[2],
                    "Margem de Operação por Análise de Despacho do MCTI, série horária de CO2, vinculada sob CO2_ONLY (GWP do CO2 = 1); elegibilidade VT0011/TOOL07 não avaliada",
                    ((OffsetDateTime) series[8]).toLocalDate(), "ano-base " + series[16], (String) series[3],
                    "Licença pública " + series[15] + " (declaração " + series[9] + ", versão " + series[10] + "), base "
                            + basis + (basis.equals("OWNER_INTERNAL") ? ": declaração interna do responsável, sem aprovação independente" : ""),
                    note, unit, hours);
            var candidate = efel.submitIn(c, ctx, project, period, match, key, candidateInput);
            UUID id = UUID.randomUUID();
            update(c, """
                    INSERT INTO app_open_efel_series_binding(id,project_id,candidate_id,grid_series_id,boundary,source_gas,gwp_co2,conversion,
                        covered_hours,series_content_sha256,timezone,hour_convention,time_basis,definition_hypothesis,license_id,license_basis,created_by)
                    VALUES (?,?,?,?,'CO2_ONLY','CO2',1,?,?,?,?,?,'DECLARED_BY_PEOPLE',?,?,?,?)
                    ON CONFLICT (project_id,candidate_id) DO NOTHING
                    """, id, project, candidate.id(), input.gridSeriesId(),
                    "EF_CO2e = EF_CO2 x GWP(CO2), GWP(CO2) = 1; unidade " + series[5] + " -> " + unit, hours.size(), series[4],
                    series[6], series[7], hypothesis, series[9], basis, ctx.actor().id());
            return result(c, project, candidate);
        });
    }

    Result get(String cookie, String project, String period, UUID candidateId) {
        return scope.read(cookie, project, "VIEW_OPEN_EFEL_CANDIDATE", (c, ctx) -> {
            var candidate = efel.list(cookie, project, period).stream().filter(v -> v.id().equals(candidateId)).findFirst().orElse(null);
            if (candidate == null) throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "candidate", RULE, "Candidato não encontrado.");
            return result(c, project, candidate);
        });
    }

    private static Result result(Connection c, String project, OpenEfelCandidates.View candidate) throws SQLException {
        var binding = first(c, """
                SELECT id,candidate_id,grid_series_id,boundary,source_gas,gwp_co2::text,conversion,covered_hours,series_content_sha256,
                       timezone,hour_convention,time_basis,definition_hypothesis,license_id,license_basis,created_at
                FROM app_open_efel_series_binding WHERE project_id=? AND candidate_id=?
                """, r -> new Binding(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class), r.getString(4),
                r.getString(5), new BigDecimal(r.getString(6)).stripTrailingZeros().toPlainString(), r.getString(7), r.getInt(8), r.getString(9),
                r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getObject(14, UUID.class), r.getString(15), r.getObject(16, OffsetDateTime.class)),
                project, candidate.id());
        var gates = new ArrayList<String>();
        if (binding != null) {
            gates.add("TIME_BASIS_DECLARED_BY_PEOPLE");
            gates.add("SERIES_DEFINITION_HYPOTHESIS_" + binding.definitionHypothesis() + "_NOT_CONFIRMED");
            if (binding.licenseBasis().equals("OWNER_INTERNAL")) gates.add("LICENSE_NOT_INDEPENDENTLY_APPROVED");
        }
        gates.add("VT0011_TOOL07_ELIGIBILITY_NOT_EVALUATED");
        gates.add("SOURCE_APPLICABILITY_MDL_NOTE_OPEN");
        gates.add("SERIES_NOT_ADMITTED");
        return new Result(candidate, binding, List.copyOf(gates), "NOT_ADMITTED");
    }

    private static PilotProblem bad(String field, String message) {
        return PilotProblem.invalid("VINCULO_SERIE_INVALIDO", field, RULE, message);
    }
}
