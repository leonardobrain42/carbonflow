package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Roteiro #124 PR 2 (etapa 6): persistent platform-level hourly SIN emission-factor series (MCTI dispatch analysis), the
 * output of scripts/import_mcti_hourly.py. It stores the ORIGINAL values and the time labels exactly as imported, with
 * the people-declared time zone and hour convention (the workbook declares neither, and no official document found
 * does). It never admits anything: the series is CANDIDATE_NOT_ADMITTED, usableForE8=false and VT0011/TOOL07 eligibility
 * NOT_EVALUATED. The gas is CO2; CO2e is never assumed. Ingestion needs an effective licence for EXTRACT_TEXT on that
 * source and edition (declared internally by the owner or approved by someone else); without it only this operation is
 * blocked. Internal review (OWNER_INTERNAL) and independent review (INDEPENDENT, not the importer) never substitute for
 * each other. Binding the series to a project (V60) is a separate, still blocked step.
 */
@ApplicationScoped
class GridSeries {
    static final String RULE = "CF-GRID-001";
    static final Set<String> UNITS = Set.of("tCO2/MWh", "tCO2/kWh", "kgCO2/kWh");
    static final Set<String> CONVENTIONS = Set.of("HOUR_ENDING_1_24", "HOUR_START_LABEL_1_24");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,17}(\\.[0-9]{1,18})?");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");
    private static final String LIMITS = "Série candidata: nada aqui é admitido. A série é de CO2 (não CO2e), o fuso e a convenção da hora "
            + "são declarados por pessoas porque a planilha não os traz, e a elegibilidade para VT0011/TOOL07 não foi avaliada.";

    record Original(String fileSha256, String gas, Integer year) {}
    record Parameters(String timezone, String hourConvention, String unit, String targetUnit) {}
    record Rec(String cell, Integer month, Integer day, Integer hourLabel, String valueOriginal, String value,
               String intervalStartLocal, String intervalStartUtc, String intervalEndUtc, String timeIssue) {}
    record ImportInput(String sourceId, String edition, Original original, Parameters parameters,
                       Map<String, Object> quality, List<Rec> records) {}
    record ReviewInput(String basis, String outcome, String reason) {}
    record Review(UUID id, String basis, String outcome, String reason, UUID reviewerId, OffsetDateTime decidedAt) {}
    record Edition(UUID id, String sourceId, String edition, UUID licenseId, int licenseDecisionVersion, String licenseBasis,
                   String workbookSha256, String contentSha256, String region, String method, String gas,
                   String originalUnit, String targetUnit, int dataYear, String timezone, String hourConvention,
                   String timeConventionSource, int rowCount, Map<String, Object> quality, int timeIssueCount,
                   UUID importedBy, OffsetDateTime importedAt, Review internalReview, Review independentReview,
                   String state, boolean usableForE8, String eligibilityVt0011Tool07, Map<String, String> v60Binding,
                   String limits) {}
    record Listing(boolean canImport, boolean canReviewInternal, boolean canReviewIndependent, List<Edition> editions,
                   String limits) {}
    record Value(int month, int day, int hourLabel, String cell, String valueOriginal, String valueTarget,
                 String intervalStartLocal, Instant intervalStartUtc, Instant intervalEndUtc, String timeIssue) {}

    private final PilotDatabase database;
    private final ComplianceStaff staff;
    private final ObjectMapper json;
    private final Set<UUID> managers;
    private final Set<UUID> reviewers;
    private final Set<UUID> owners;

    GridSeries(PilotDatabase database, ComplianceStaff staff, ObjectMapper json,
            @ConfigProperty(name = "carbonflow.platform.grid-series.managers") Optional<String> managers,
            @ConfigProperty(name = "carbonflow.platform.grid-series.reviewers") Optional<String> reviewers,
            @ConfigProperty(name = "carbonflow.platform.grid-series.owners") Optional<String> owners) {
        this.database = database;
        this.staff = staff;
        this.json = json;
        this.managers = roster(managers);
        this.reviewers = roster(reviewers);
        this.owners = roster(owners);
    }

    private static Set<UUID> roster(Optional<String> value) {
        try {
            return value.map(text -> Arrays.stream(text.split(",", -1)).map(String::strip).filter(p -> !p.isEmpty())
                    .map(UUID::fromString).collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Cadastro de responsáveis pelas séries da rede inválido.");
        }
    }

    private boolean member(UUID id) {
        return managers.contains(id) || reviewers.contains(id) || owners.contains(id);
    }

    Listing list(String token) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!member(actor.id())) throw hidden();
            ComplianceStaff.audit(c, actor.id(), null, "VIEW_GRID_SERIES");
            return new Listing(canImport(actor.id()), owners.contains(actor.id()), reviewers.contains(actor.id()),
                    editions(c, null), LIMITS);
        });
    }

    List<Value> values(String token, UUID id, int month) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!member(actor.id())) throw hidden();
            if (month < 1 || month > 12) throw invalid("month");
            if (editions(c, id).isEmpty()) throw new PilotProblem(404, "SERIE_INEXISTENTE", "id", RULE, "Série não encontrada.");
            ComplianceStaff.audit(c, actor.id(), null, "VIEW_GRID_SERIES");
            return query(c, """
                    SELECT month,day,hour_label,cell,value_text,value_target::text,interval_start_local,
                           interval_start_utc,interval_end_utc,time_issue
                    FROM app_grid_series_value WHERE edition_id=? AND month=? ORDER BY day,hour_label
                    """, r -> new Value(r.getInt(1), r.getInt(2), r.getInt(3), r.getString(4), r.getString(5),
                    plain(r.getString(6)), r.getString(7), instant(r.getTimestamp(8)), instant(r.getTimestamp(9)), r.getString(10)),
                    id, month);
        });
    }

    private boolean canImport(UUID id) {
        return managers.contains(id) || owners.contains(id);
    }

    Edition importSeries(String token, ImportInput in) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!member(actor.id())) throw hidden();
            if (!canImport(actor.id())) throw new PilotProblem(403, "PERFIL_SEM_IMPORTACAO", "actor", RULE,
                    "Seu perfil só revisa séries; a importação é do responsável ou do gestor.");
            validate(in);
            String workbook = in.original().fileSha256();
            String registered = first(c, "SELECT sha256 FROM app_source_version WHERE source_id=? AND edition=? AND origin<>'PROJECT'",
                    r -> r.getString(1), in.sourceId().strip(), in.edition().strip());
            if (registered == null) throw new PilotProblem(422, "FONTE_INEXISTENTE", "sourceId", RULE,
                    "Registre antes os metadados da fonte e da edição.");
            if (!workbook.equals(registered)) throw new PilotProblem(422, "ARQUIVO_NAO_CORRESPONDE_A_EDICAO", "original.fileSha256", RULE,
                    "O SHA-256 do arquivo importado não é o da edição registrada.");
            var lic = license(c, in.sourceId().strip(), in.edition().strip());
            if (lic == null) throw new PilotProblem(409, "LICENCA_AUSENTE_PARA_OPERACAO", "license", RULE,
                    "Não há licença efetiva para extrair os dados desta fonte e edição (EXTRACT_TEXT). Só esta operação está bloqueada.");
            String content = contentHash(in);
            UUID id = UUID.randomUUID();
            int issues = (int) in.records().stream().filter(r -> r.timeIssue() != null).count();
            try {
                update(c, """
                        INSERT INTO app_grid_series_edition(id,source_id,edition,license_id,license_decision_version,license_basis,
                            workbook_sha256,content_sha256,gas,original_unit,target_unit,data_year,timezone,hour_convention,
                            row_count,quality,time_issue_count,imported_by)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                        """, id, in.sourceId().strip(), in.edition().strip(), lic.id(), lic.version(), lic.basis(), workbook,
                        content, in.original().gas(), in.parameters().unit(), in.parameters().targetUnit(), in.original().year(),
                        in.parameters().timezone(), in.parameters().hourConvention(), in.records().size(),
                        json.writeValueAsString(in.quality() == null ? Map.of() : in.quality()), issues, actor.id());
            } catch (SQLException ex) {
                if ("23505".equals(ex.getSQLState())) throw new PilotProblem(409, "SERIE_JA_IMPORTADA", "records", RULE,
                        "Esta série, com os mesmos valores e parâmetros de tempo, já foi importada.");
                throw ex;
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw invalid("quality");
            }
            for (var r : in.records()) {
                var value = new BigDecimal(r.value());
                update(c, """
                        INSERT INTO app_grid_series_value(edition_id,month,day,hour_label,cell,value_text,value_original,value_target,
                            interval_start_local,interval_start_utc,interval_end_utc,time_issue)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """, id, r.month(), r.day(), r.hourLabel(), r.cell(), r.valueOriginal(), new BigDecimal(r.valueOriginal()), value,
                        r.intervalStartLocal(), r.intervalStartUtc() == null ? null : OffsetDateTime.parse(r.intervalStartUtc()),
                        r.intervalEndUtc() == null ? null : OffsetDateTime.parse(r.intervalEndUtc()), r.timeIssue());
            }
            ComplianceStaff.audit(c, actor.id(), null, "IMPORT_GRID_SERIES");
            return editions(c, id).get(0);
        });
    }

    Edition review(String token, UUID id, ReviewInput in) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!member(actor.id())) throw hidden();
            if (in == null || !Set.of("INDEPENDENT", "OWNER_INTERNAL").contains(in.basis())
                    || !Set.of("CHECKED", "RETURNED").contains(in.outcome()) || in.reason() == null
                    || in.reason().strip().length() < 10 || in.reason().strip().length() > 1000) throw invalid("revisao");
            UUID importer = first(c, "SELECT imported_by FROM app_grid_series_edition WHERE id=?", r -> r.getObject(1, UUID.class), id);
            if (importer == null) throw new PilotProblem(404, "SERIE_INEXISTENTE", "id", RULE, "Série não encontrada.");
            boolean owner = in.basis().equals("OWNER_INTERNAL");
            if (!(owner ? owners : reviewers).contains(actor.id())) throw new PilotProblem(403, "BASE_NAO_AUTORIZADA", "basis", RULE,
                    owner ? "Seu perfil não está na lista de responsáveis do produto." : "Seu perfil não está na lista de revisores independentes.");
            if (!owner && actor.id().equals(importer)) throw new PilotProblem(403, "DUPLA_CUSTODIA", "reviewer", RULE,
                    "Quem importou a série não pode revisá-la como revisor independente.");
            update(c, "INSERT INTO app_grid_series_review(id,edition_id,basis,outcome,reason,reviewer_id) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID(), id, in.basis(), in.outcome(), in.reason().strip(), actor.id());
            ComplianceStaff.audit(c, actor.id(), null, "REVIEW_GRID_SERIES");
            return editions(c, id).get(0);
        });
    }

    private record Lic(UUID id, int version, String basis) {}

    /** The latest decision must be an internal declaration or an approval that covers EXTRACT_TEXT, in force now. */
    private static Lic license(Connection c, String source, String edition) throws SQLException {
        return first(c, """
                SELECT l.id, d.version, d.outcome
                FROM app_content_license l
                JOIN LATERAL (SELECT version,outcome FROM app_content_license_decision WHERE license_id=l.id
                              ORDER BY version DESC LIMIT 1) d ON true
                JOIN app_content_license_capability cap ON cap.license_id=l.id AND cap.decision_version=d.version
                WHERE l.source_id=? AND l.edition=? AND l.purpose='AUTHORIZED_PROCESSING' AND cap.operation='EXTRACT_TEXT'
                  AND l.project_id IS NULL AND l.organization_id IS NULL AND l.actor_id IS NULL AND l.environment='SANDBOX'
                  AND d.outcome IN ('DECLARED_INTERNAL','APPROVED')
                  AND l.starts_at <= statement_timestamp() AND (l.ends_at IS NULL OR l.ends_at > statement_timestamp())
                ORDER BY d.outcome DESC, l.starts_at DESC LIMIT 1
                """, r -> new Lic(r.getObject(1, UUID.class), r.getInt(2), "APPROVED".equals(r.getString(3)) ? "INDEPENDENT" : "OWNER_INTERNAL"),
                source, edition);
    }

    private void validate(ImportInput in) {
        if (in == null || in.sourceId() == null || in.sourceId().isBlank() || in.edition() == null || in.edition().isBlank()
                || in.original() == null || in.parameters() == null || in.records() == null || in.records().isEmpty()
                || in.records().size() > 9000) throw invalid("importacao");
        var o = in.original();
        var p = in.parameters();
        if (o.fileSha256() == null || !SHA.matcher(o.fileSha256()).matches() || !"CO2".equals(o.gas())
                || o.year() == null || o.year() < 2006 || o.year() > 2100) throw invalid("original");
        if (p.timezone() == null || p.hourConvention() == null || !CONVENTIONS.contains(p.hourConvention())
                || p.unit() == null || !UNITS.contains(p.unit()) || p.targetUnit() == null || !UNITS.contains(p.targetUnit())) throw invalid("parameters");
        try {
            ZoneId.of(p.timezone());
        } catch (DateTimeException ex) {
            throw invalid("parameters.timezone");
        }
        var seen = new HashSet<Integer>();
        for (var r : in.records()) {
            if (r == null || r.month() == null || r.month() < 1 || r.month() > 12 || r.day() == null || r.day() < 1 || r.day() > 31
                    || r.hourLabel() == null || r.hourLabel() < 1 || r.hourLabel() > 24 || r.cell() == null || r.cell().isBlank()
                    || r.valueOriginal() == null || !DECIMAL.matcher(r.valueOriginal()).matches()
                    || r.value() == null || !DECIMAL.matcher(r.value()).matches() || r.intervalStartLocal() == null
                    || !seen.add(r.month() * 10000 + r.day() * 100 + r.hourLabel())) throw invalid("records");
            if ((r.intervalStartUtc() == null) != (r.intervalEndUtc() == null) || (r.intervalStartUtc() == null && r.timeIssue() == null))
                throw invalid("records.time");
            if (r.intervalStartUtc() != null) {
                try {
                    var start = OffsetDateTime.parse(r.intervalStartUtc());
                    var end = OffsetDateTime.parse(r.intervalEndUtc());
                    if (!end.toInstant().equals(start.toInstant().plusSeconds(3600))) throw invalid("records.time");
                } catch (DateTimeException ex) {
                    throw invalid("records.time");
                }
            }
        }
    }

    /** Hash of what the series says (identity of each cell, original value and its UTC interval), independent of formatting. */
    private static String contentHash(ImportInput in) {
        var digest = sha256();
        for (var r : in.records().stream().sorted(java.util.Comparator.comparingInt(
                (Rec r) -> r.month() * 10000 + r.day() * 100 + r.hourLabel())).toList())
            digest.update((r.month() + "|" + r.day() + "|" + r.hourLabel() + "|" + new BigDecimal(r.valueOriginal()).stripTrailingZeros().toPlainString()
                    + "|" + r.intervalStartUtc() + "\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Edition> editions(Connection c, UUID only) throws SQLException {
        var result = new ArrayList<Edition>();
        var rows = query(c, """
                SELECT id,source_id,edition,license_id,license_decision_version,license_basis,workbook_sha256,content_sha256,
                       region,method,gas,original_unit,target_unit,data_year,timezone,hour_convention,time_convention_source,
                       row_count,quality::text,time_issue_count,imported_by,imported_at
                FROM app_grid_series_edition WHERE (?::uuid IS NULL OR id=?::uuid) ORDER BY imported_at DESC
                """, r -> new Object[]{r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getObject(4, UUID.class),
                r.getInt(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11),
                r.getString(12), r.getString(13), r.getInt(14), r.getString(15), r.getString(16), r.getString(17), r.getInt(18),
                r.getString(19), r.getInt(20), r.getObject(21, UUID.class), r.getObject(22, OffsetDateTime.class)}, only, only);
        for (var x : rows) {
            UUID id = (UUID) x[0];
            Review internal = latest(c, id, "OWNER_INTERNAL"), independent = latest(c, id, "INDEPENDENT");
            Map<String, Object> quality;
            try {
                quality = json.readValue((String) x[18], Map.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                quality = Map.of();
            }
            result.add(new Edition(id, (String) x[1], (String) x[2], (UUID) x[3], (Integer) x[4], (String) x[5], (String) x[6],
                    (String) x[7], (String) x[8], (String) x[9], (String) x[10], (String) x[11], (String) x[12], (Integer) x[13],
                    (String) x[14], (String) x[15], (String) x[16], (Integer) x[17], quality, (Integer) x[19], (UUID) x[20],
                    (OffsetDateTime) x[21], internal, independent, "CANDIDATE_NOT_ADMITTED", false, "NOT_EVALUATED",
                    Map.of("state", "NOT_BOUND", "blocker", "A V60 é por projeto, limitada a 744 horas, exige candidato de energia e "
                            + "fixa gás CO2e; esta série é nacional, anual e de CO2. A vinculação é um passo posterior e explícito."),
                    LIMITS));
        }
        return result;
    }

    private static Review latest(Connection c, UUID id, String basis) throws SQLException {
        return first(c, "SELECT id,basis,outcome,reason,reviewer_id,decided_at FROM app_grid_series_review WHERE edition_id=? AND basis=? "
                + "ORDER BY decided_at DESC, id DESC LIMIT 1", r -> new Review(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                r.getString(4), r.getObject(5, UUID.class), r.getObject(6, OffsetDateTime.class)), id, basis);
    }

    private static String plain(String value) {
        return value == null ? null : new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static PilotProblem hidden() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "grid-series", RULE, "Séries da rede não disponíveis para este perfil.");
    }

    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("SERIE_INVALIDA", field, RULE,
                "Confira a saída do importador: SHA-256 do arquivo, gás CO2, ano, fuso, convenção da hora, unidades, até 9000 registros únicos, decimais e intervalos de uma hora.");
    }
}
