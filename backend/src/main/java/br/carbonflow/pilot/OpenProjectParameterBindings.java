package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Staff pin of one reviewed fossil factor, one reviewed grid factor, or an
 * explicit IR/Dy onto a VM0038 draft. The pin never copies a licensed number,
 * never fills 1 for a missing scalar, and never sets usableForE8.
 */
@ApplicationScoped
class OpenProjectParameterBindings {
    record Option(String projectId, String title, int triageVersion, List<PinSummary> pins) {}
    record PinSummary(String parameterKind, int vintageYear, int version) {}
    record Input(String projectId, String parameterKind, Integer vintageYear,
                 UUID candidateId, String declaredValue, String ruleReference,
                 String evidenceLocator, Boolean useDefault) {}
    record Result(View view, boolean created) {}
    record View(UUID id, String projectId, String parameterKind, int vintageYear,
                int version, String methodology, String methodologyEdition,
                String geography, String normalizedUnit, String conversionFormula,
                String valueKind, String declaredValue, boolean explicitOne,
                String state, boolean stale, boolean usableForE8,
                int triageVersion, String bindingSha256, OffsetDateTime publishedAt,
                List<String> gates) {}

    private record Target(UUID organization, String title) {}
    private record Candidate(UUID id, UUID organization, String kind, int year,
                             String gas, String fuel, String grid, String unit,
                             BigDecimal normalized, String formula, String status,
                             String sha, UUID license, String sourceId, String edition,
                             String outcome) {}
    private record Stored(UUID id, String project, UUID organization, String kind,
                          int year, int version, UUID candidate, String sha,
                          String unit, String formula, BigDecimal declared,
                          String rule, String evidence, String valueKind,
                          int triageVersion, String digest, OffsetDateTime published, UUID author) {}

    private final PilotDatabase database;
    private final PlatformSourceWork managers;
    private final DynamicProjectScope scope;

    OpenProjectParameterBindings(PilotDatabase database, PlatformSourceWork managers,
                                 DynamicProjectScope scope) {
        this.database = database;
        this.managers = managers;
        this.scope = scope;
    }

    List<Option> options(String token) {
        return database.transaction(connection -> {
            var actor = managers.requireManager(connection, token);
            if (actor.organizations().isEmpty()) return List.of();
            var organizations = connection.createArrayOf("uuid",
                actor.organizations().toArray(UUID[]::new));
            try {
                var rows = query(connection, """
                    SELECT p.id, p.title, p.organization_id
                    FROM app_project p
                    WHERE p.organization_id = ANY(?) AND p.environment = 'SANDBOX'
                      AND p.state = 'DRAFT'
                    ORDER BY p.title, p.id LIMIT 80
                    """, r -> new Object[]{r.getString(1), r.getString(2),
                        r.getObject(3, UUID.class)}, organizations);
                var result = new ArrayList<Option>();
                for (Object[] row : rows) {
                    String project = (String) row[0];
                    if (ProjectJourney.profile(ProjectGuides.candidate(connection, project))
                            != ProjectJourney.VM0038) continue;
                    int triage = OpenCalculationReadiness.triageRevision(connection, project);
                    var pins = query(connection, """
                        SELECT parameter_kind, vintage_year, version
                        FROM app_open_project_parameter_binding
                        WHERE project_id = ?
                        ORDER BY parameter_kind, version DESC
                        """, r -> new PinSummary(r.getString(1), r.getInt(2), r.getInt(3)),
                        project);
                    result.add(new Option(project, (String) row[1], triage, List.copyOf(pins)));
                }
                ComplianceStaff.audit(connection, actor.id(), null,
                    "VIEW_OPEN_PARAMETER_BINDING_OPTIONS");
                return List.copyOf(result);
            } finally {
                organizations.free();
            }
        });
    }

    List<View> list(String token, String project) {
        return scope.read(token, project, "VIEW_OPEN_PARAMETER_BINDING", (connection, context) -> {
            var rows = query(connection, SELECT + """
                WHERE project_id = ? ORDER BY parameter_kind, version DESC LIMIT 40
                """, OpenProjectParameterBindings::stored, project);
            var out = new ArrayList<View>();
            for (Stored row : rows) out.add(view(connection, row, true));
            return List.copyOf(out);
        });
    }

    Result pin(String token, String key, String match, Input input) {
        return database.transaction(connection -> {
            var actor = managers.requireManager(connection, token);
            if (input == null || input.projectId() == null || input.parameterKind() == null
                    || input.vintageYear() == null) {
                throw invalid("Informe projeto, tipo e ano do pino.");
            }
            if (Boolean.TRUE.equals(input.useDefault())) {
                throw invalid("Não existe default. Ausência, zero e 1 são declarações diferentes.");
            }
            String kind = input.parameterKind();
            if (!List.of("EF_FUEL", "EF_GRID", "IR", "DY").contains(kind)) {
                throw invalid("Tipo de parâmetro desconhecido.");
            }
            int year = input.vintageYear();
            if (year < 1900 || year > 2100) throw invalid("Ano fora do contrato.");
            String command = PilotCommands.key(key);
            int expected = PilotCommands.expected(match);
            OpenProjectParameterRules.Scalar scalar = null;
            if ("IR".equals(kind) || "DY".equals(kind)) {
                if (input.candidateId() != null) {
                    throw invalid("IR e Dy não apontam para um fator de emissão.");
                }
                try {
                    scalar = OpenProjectParameterRules.scalar(kind, input.declaredValue(),
                        input.ruleReference(), input.evidenceLocator());
                } catch (IllegalArgumentException ex) {
                    throw invalid(ex.getMessage());
                }
            } else {
                if (input.declaredValue() != null || input.ruleReference() != null
                        || input.evidenceLocator() != null) {
                    throw invalid("O fator é uma referência de fonte. Não envie um número digitado.");
                }
                if (input.candidateId() == null) throw invalid("Selecione o fator já revisado.");
            }
            String fingerprintSource = "OPEN-PARAMETER-PIN-V1|" + input.projectId() + "|"
                + kind + "|" + year + "|" + expected + "|"
                + (input.candidateId() == null ? "" : input.candidateId()) + "|"
                + (scalar == null ? "" : scalar.canonical() + "|"
                    + input.ruleReference().strip() + "|" + input.evidenceLocator().strip());
            byte[] fingerprint = AccessSecrets.digest(fingerprintSource);
            Object[] replay = first(connection, """
                SELECT project_id, binding_id, fingerprint
                FROM app_open_project_parameter_binding_command
                WHERE actor_id = ? AND command_key = ?
                """, r -> new Object[]{r.getString(1), r.getObject(2, UUID.class), r.getBytes(3)},
                actor.id(), command);
            if (replay != null) {
                if (!input.projectId().equals(replay[0])
                        || !MessageDigest.isEqual(fingerprint, (byte[]) replay[2])) {
                    throw conflict("A chave de idempotência foi usada com outro pino.");
                }
                Stored previous = first(connection, SELECT + " WHERE project_id = ? AND id = ?",
                    OpenProjectParameterBindings::stored, input.projectId(), (UUID) replay[1]);
                if (previous == null) throw hidden();
                return new Result(view(connection, previous, false), false);
            }
            Target project = first(connection, """
                SELECT organization_id, title FROM app_project
                WHERE id = ? AND state = 'DRAFT' AND environment = 'SANDBOX'
                FOR UPDATE
                """, r -> new Target(r.getObject(1, UUID.class), r.getString(2)), input.projectId());
            if (project == null || !actor.organizations().contains(project.organization())
                    || ProjectJourney.profile(ProjectGuides.candidate(connection, input.projectId()))
                        != ProjectJourney.VM0038) {
                throw hidden();
            }
            int triage = OpenCalculationReadiness.triageRevision(connection, input.projectId());
            Candidate candidate = null;
            if (scalar == null) {
                candidate = candidate(connection, input.candidateId(), kind);
                if (candidate == null || !project.organization().equals(candidate.organization())) {
                    throw hidden();
                }
            }
            Stored latest = first(connection, SELECT + """
                WHERE project_id = ? AND parameter_kind = ? AND vintage_year = ?
                ORDER BY version DESC LIMIT 1
                """, OpenProjectParameterBindings::stored, input.projectId(), kind, year);
            PilotCommands.requireVersion(expected, latest == null ? 0 : latest.version());
            if (expected >= 100000000) throw conflict("Limite de versões atingido.");
            String unit = null;
            String formula = null;
            String digest;
            if (candidate != null) {
                if (!"CANDIDATE".equals(candidate.status()) || candidate.normalized() == null
                        || !"APPROVED_FOR_SCOPE".equals(candidate.outcome())) {
                    throw conflict("O fator precisa estar revisado internamente e com licença vigente.");
                }
                if (!rightsActive(connection, java.util.Set.of(project.organization()), candidate)) {
                    throw conflict("A licença não permite usar este fator nem como referência.");
                }
                try {
                    OpenProjectParameterRules.factor(kind, year, candidate.year(), candidate.gas(),
                        candidate.fuel(), candidate.grid(), candidate.unit());
                } catch (IllegalArgumentException ex) {
                    throw invalid(ex.getMessage());
                }
                unit = candidate.unit();
                formula = candidate.formula();
                if (latest != null && candidate.id().equals(latest.candidate())) {
                    throw conflict("Esta mesma versão de fator já está vinculada a este ano.");
                }
                digest = HexFormat.of().formatHex(AccessSecrets.digest(
                    input.projectId() + "|" + kind + "|" + year + "|" + (expected + 1) + "|"
                        + candidate.id() + "|" + candidate.sha() + "|" + unit + "|" + formula));
            } else {
                if (latest != null && scalar.canonical().equals(canonical(latest.declared()))
                        && input.ruleReference().strip().equals(latest.rule())
                        && input.evidenceLocator().strip().equals(latest.evidence())) {
                    throw conflict("Esta mesma declaração já está vinculada a este ano.");
                }
                digest = HexFormat.of().formatHex(AccessSecrets.digest(
                    input.projectId() + "|" + kind + "|" + year + "|" + (expected + 1) + "|"
                        + scalar.valueKind() + "|" + scalar.canonical() + "|"
                        + input.ruleReference().strip() + "|" + input.evidenceLocator().strip()));
            }
            UUID id = UUID.randomUUID();
            update(connection, """
                INSERT INTO app_open_project_parameter_binding
                 (id, project_id, organization_id, environment, parameter_kind, vintage_year,
                  version, candidate_id, source_sha256, normalized_unit, conversion_formula,
                  declared_value, rule_reference, evidence_locator, value_kind, triage_version,
                  binding_sha256, published_by)
                VALUES (?,?,?,'SANDBOX',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, input.projectId(), project.organization(), kind, year, expected + 1,
                candidate == null ? null : candidate.id(),
                candidate == null ? null : HexFormat.of().parseHex(candidate.sha()),
                unit, formula,
                scalar == null ? null : new BigDecimal(scalar.canonical()),
                scalar == null ? null : input.ruleReference().strip(),
                scalar == null ? null : input.evidenceLocator().strip(),
                scalar == null ? "REFERENCE" : scalar.valueKind(),
                triage, HexFormat.of().parseHex(digest), actor.id());
            update(connection, """
                INSERT INTO app_open_project_parameter_binding_command
                 (actor_id, command_key, project_id, binding_id, fingerprint)
                VALUES (?,?,?,?,?)
                """, actor.id(), command, input.projectId(), id, fingerprint);
            ComplianceStaff.audit(connection, actor.id(), null, "PIN_OPEN_PARAMETER_BINDING");
            Stored stored = first(connection, SELECT + " WHERE project_id = ? AND id = ?",
                OpenProjectParameterBindings::stored, input.projectId(), id);
            return new Result(view(connection, stored, false), true);
        });
    }

    /** Most recent reference year for project-level readiness without a monitoring window. */
    static StoredPin latest(Connection connection, String project, String kind) throws SQLException {
        Stored stored = first(connection, SELECT + """
            WHERE project_id = ? AND parameter_kind = ?
            ORDER BY vintage_year DESC, version DESC LIMIT 1
            """, OpenProjectParameterBindings::stored, project, kind);
        if (stored == null) return null;
        boolean stale = stale(connection, stored);
        return new StoredPin(stored.kind(), stored.year(), stored.valueKind(),
            canonical(stored.declared()), stale);
    }

    record StoredPin(String kind, int year, String valueKind, String declaredCanonical, boolean stale) {}

    record AnnualPin(UUID id, UUID author, String valueKind, BigDecimal declared,
                     String rule, String evidence, boolean stale) {}

    static AnnualPin annual(Connection c, String project, String kind, int year)
            throws SQLException {
        Stored pin = first(c, SELECT + """
            WHERE project_id=? AND parameter_kind=? AND vintage_year=?
            ORDER BY version DESC LIMIT 1
            """, OpenProjectParameterBindings::stored, project, kind, year);
        if (pin == null) return null;
        return new AnnualPin(pin.id(), pin.author(), pin.valueKind(), pin.declared(),
            pin.rule(), pin.evidence(), stale(c, pin));
    }

    static boolean hasOtherYear(Connection c, String project, String kind, int year)
            throws SQLException {
        return first(c, """
            SELECT EXISTS (
              SELECT 1 FROM app_open_project_parameter_binding
              WHERE project_id=? AND parameter_kind=? AND vintage_year<>?)
            """, r -> r.getBoolean(1), project, kind, year);
    }

    private View view(Connection connection, Stored stored, boolean hideLicensedNumber) throws SQLException {
        boolean stale = stale(connection, stored);
        boolean one = stored.declared() != null && stored.declared().compareTo(BigDecimal.ONE) == 0;
        boolean scalar = "IR".equals(stored.kind()) || "DY".equals(stored.kind());
        String declared = scalar ? canonical(stored.declared()) : null;
        if (hideLicensedNumber && !scalar) declared = null;
        var gates = new ArrayList<String>();
        gates.add("NOT_ADMITTED_FOR_E8");
        if (stale) gates.add("RECHECK_REQUIRED");
        if (one) gates.add("EXPLICIT_ONE_IS_NOT_A_DEFAULT");
        if ("DECLARED_ZERO".equals(stored.valueKind())) gates.add("DECLARED_ZERO_IS_NOT_ABSENCE");
        return new View(stored.id(), stored.project(), stored.kind(), stored.year(),
            stored.version(), "VM0038", "1.1", "BR", stored.unit(), stored.formula(),
            stored.valueKind(), hideLicensedNumber && !scalar ? null : declared, one,
            stale ? "STALE_NOT_ADMITTED" : "PINNED_NOT_ADMITTED", stale, false,
            stored.triageVersion(), stored.digest(), stored.published(), List.copyOf(gates));
    }

    private static boolean stale(Connection connection, Stored stored) throws SQLException {
        int triage = OpenCalculationReadiness.triageRevision(connection, stored.project());
        if (triage != stored.triageVersion()) return true;
        if (stored.candidate() == null) return false;
        Candidate candidate = candidate(connection, stored.candidate(), stored.kind());
        if (candidate == null || !"CANDIDATE".equals(candidate.status())
                || candidate.normalized() == null
                || !"APPROVED_FOR_SCOPE".equals(candidate.outcome())) return true;
        return !rightsActive(connection, java.util.Set.of(stored.organization()), candidate);
    }

    private static boolean rightsActive(Connection connection, java.util.Set<UUID> organizations,
                                        Candidate candidate) throws SQLException {
        for (String operation : List.of("EXPORT_DERIVATIVES", "EXTRACT_TEXT", "SHOW_EXCERPTS")) {
            var array = connection.createArrayOf("uuid", organizations.toArray(UUID[]::new));
            try {
                UUID license = first(connection, """
                    SELECT l.id
                    FROM app_content_license l
                    JOIN LATERAL (
                        SELECT version, outcome, decided_by, decided_at
                        FROM app_content_license_decision
                        WHERE license_id = l.id ORDER BY version DESC LIMIT 1
                    ) d ON true
                    JOIN app_content_license_capability cap ON cap.license_id = l.id
                      AND cap.decision_version = d.version
                    WHERE l.id = ? AND l.source_id = ? AND l.edition = ?
                      AND l.organization_id = ANY(?) AND l.project_id IS NULL AND l.actor_id IS NULL
                      AND l.environment = 'SANDBOX' AND l.purpose = 'AUTHORIZED_PROCESSING'
                      AND l.provider IS NULL AND cap.operation = ?
                      AND l.proof_sha256 IS NOT NULL AND l.submitted_by IS NOT NULL
                      AND d.decided_by IS NOT NULL AND d.decided_by <> l.submitted_by
                      AND d.outcome = 'APPROVED' AND d.decided_at <= statement_timestamp()
                      AND l.starts_at <= statement_timestamp()
                      AND (l.ends_at IS NULL OR l.ends_at > statement_timestamp())
                    """, r -> r.getObject(1, UUID.class), candidate.license(), candidate.sourceId(),
                    candidate.edition(), array, operation);
                if (license == null) return false;
            } finally {
                array.free();
            }
        }
        return true;
    }

    private static Candidate candidate(Connection connection, UUID id, String kind) throws SQLException {
        String capability = "EF_GRID".equals(kind) ? "PROJECT_EMISSIONS_PE" : "BASELINE_BE";
        return first(connection, """
            SELECT p.id, p.organization_id, p.parameter_kind, p.period_year, p.gas, p.fuel, p.grid,
                   p.normalized_unit, p.normalized_value, p.conversion_formula, p.status,
                   encode(p.source_sha256,'hex'), p.source_license_id, s.source_id, s.edition, a.outcome
            FROM app_open_parameter_candidate p
            JOIN app_platform_source_object o ON o.id = p.source_object_id
            JOIN app_platform_source_submission s ON s.id = o.submission_id
            LEFT JOIN app_open_parameter_assessment a ON a.candidate_id = p.id
              AND a.methodology = 'VM0038' AND a.methodology_edition = '1.1'
              AND a.scenario = 'OPEN_LDV_DCFC_SIN_BR' AND a.blocked_capability = ?
            WHERE p.id = ? AND p.parameter_kind = ?
            """, r -> new Candidate(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                r.getString(3), r.getInt(4), r.getString(5), r.getString(6), r.getString(7),
                r.getString(8), r.getBigDecimal(9), r.getString(10), r.getString(11),
                r.getString(12), r.getObject(13, UUID.class), r.getString(14), r.getString(15),
                r.getString(16)), capability, id, kind);
    }

    private static final String SELECT = """
        SELECT id, project_id, organization_id, parameter_kind, vintage_year, version,
               candidate_id, encode(source_sha256,'hex'), normalized_unit, conversion_formula,
               declared_value, rule_reference, evidence_locator, value_kind, triage_version,
               encode(binding_sha256,'hex'), published_at, published_by
        FROM app_open_project_parameter_binding
        """;

    private static Stored stored(java.sql.ResultSet row) throws SQLException {
        return new Stored(row.getObject(1, UUID.class), row.getString(2),
            row.getObject(3, UUID.class), row.getString(4), row.getInt(5), row.getInt(6),
            row.getObject(7, UUID.class), row.getString(8), row.getString(9), row.getString(10),
            row.getBigDecimal(11), row.getString(12), row.getString(13), row.getString(14),
            row.getInt(15), row.getString(16), row.getObject(17, OffsetDateTime.class),
            row.getObject(18, UUID.class));
    }

    private static String canonical(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    private static PilotProblem invalid(String message) {
        return PilotProblem.invalid("PARAMETER_PIN_INVALID", "parameter", OpenProjectParameterRules.RULE, message);
    }

    private static PilotProblem hidden() {
        return new PilotProblem(404, "PARAMETER_PIN_NOT_AVAILABLE", "project",
            OpenProjectParameterRules.RULE, "Pino indisponível.");
    }

    private static PilotProblem conflict(String message) {
        return new PilotProblem(409, "PARAMETER_PIN_CONFLICT", "parameter",
            OpenProjectParameterRules.RULE, message);
    }
}
