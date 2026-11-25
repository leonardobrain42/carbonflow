package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Source contexts and needs of a registered methodology (D18, CF-PACKAGES-001 slice 2). A need names the source a
 * methodology expects (metadata only). It does not register, license or admit that source: the existing source
 * intake and licence decisions do, and the readiness console derives the status from them. Creation only: a need or
 * context is never edited or deleted here, and a repeated identical request changes nothing.
 */
@ApplicationScoped
class MethodologySources {
    record Context(String methodology, String edition, String scenario, String title, String country,
                   String networkMode, String vehicleCategory, String chargerType, String energySystem,
                   int needCount, int registeredSourceCount) {}
    record ContextInput(String scenario, String title, String country, String networkMode,
                        String vehicleCategory, String chargerType, String energySystem) {}
    record Need(String code, String methodology, String edition, String scenario, String stage, String kind,
                String title, String purpose, String sourceId, String sourceEdition, String officialUri,
                boolean mandatory, String conditionNote, String blockedCapability, String editionPolicy,
                String responsibleParty, String priority) {}
    record NeedInput(String code, String stage, String kind, String title, String purpose, String sourceId,
                     String sourceEdition, String officialUri, Boolean mandatory, String conditionNote,
                     String blockedCapability, String editionPolicy, String responsibleParty, String priority) {}
    record SavedContext(Context context, boolean created) {}
    record SavedNeed(Need need, boolean created) {}

    private static final String RULE = "CF-PACKAGES-001";
    private static final Set<String> STAGES = Set.of("APPLICABILITY", "BASELINE", "ADDITIONALITY",
            "QUANTIFICATION", "MONITORING", "DOCUMENTS");
    private static final Set<String> KINDS = Set.of("NORMATIVE", "DATASET", "PROJECT_EVIDENCE");
    private static final Set<String> CAPABILITIES = Set.of("PREPARATION", "APPLICABILITY", "BASELINE_BE",
            "PROJECT_EMISSIONS_PE", "EMISSION_REDUCTIONS_ER", "ADDITIONALITY", "MONITORING", "PD", "AI_SUPPORT");
    private static final Set<String> POLICIES = Set.of("EXACT", "YEAR", "LATEST");
    private static final Set<String> PARTIES = Set.of("CARBONFLOW", "PROPONENT", "OPERATOR");
    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2");

    private final PilotDatabase database;
    private final MethodologyRegistry registry;

    MethodologySources(PilotDatabase database, MethodologyRegistry registry) {
        this.database = database;
        this.registry = registry;
    }

    List<Context> contexts(String token, String methodology, String edition) {
        return database.transaction(connection -> {
            ComplianceStaff.Staff actor = registry.requireManager(connection, token);
            requireRegistered(connection, methodology, edition);
            ComplianceStaff.audit(connection, actor.id(), null, "VIEW_METHODOLOGY_SOURCES");
            return contexts(connection, methodology, edition);
        });
    }

    SavedContext registerContext(String token, String methodology, String edition, ContextInput received) {
        ContextInput input = normalize(received);
        return database.transaction(connection -> {
            ComplianceStaff.Staff actor = registry.requireManager(connection, token);
            requireRegistered(connection, methodology, edition);
            lock(connection, "context|" + methodology + "|" + edition + "|" + input.scenario());
            Context current = context(connection, methodology, edition, input.scenario());
            if (current != null) {
                if (same(current, input)) return new SavedContext(current, false);
                throw conflict("scenario", "Já existe um contexto com este cenário e conteúdo diferente.");
            }
            update(connection, """
                    INSERT INTO app_methodology_source_context
                      (methodology,methodology_edition,scenario,title,country,network_mode,vehicle_category,
                       charger_type,energy_system)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, methodology, edition, input.scenario(), input.title(), input.country(),
                    input.networkMode(), input.vehicleCategory(), input.chargerType(), input.energySystem());
            ComplianceStaff.audit(connection, actor.id(), null, "REGISTER_METHODOLOGY_CONTEXT");
            return new SavedContext(context(connection, methodology, edition, input.scenario()), true);
        });
    }

    SavedNeed registerNeed(String token, String methodology, String edition, String scenario, NeedInput received) {
        NeedInput input = normalize(received);
        return database.transaction(connection -> {
            ComplianceStaff.Staff actor = registry.requireManager(connection, token);
            requireRegistered(connection, methodology, edition);
            if (context(connection, methodology, edition, scenario) == null) {
                throw new PilotProblem(404, "CONTEXTO_NAO_ENCONTRADO", "scenario", RULE,
                        "Registre o contexto da metodologia antes das necessidades de fonte.");
            }
            lock(connection, "need|" + input.code());
            Need current = need(connection, input.code());
            if (current != null) {
                if (current.methodology().equals(methodology) && current.edition().equals(edition)
                        && current.scenario().equals(scenario) && same(current, input)) {
                    return new SavedNeed(current, false);
                }
                throw conflict("code", "Já existe uma necessidade com este código e conteúdo diferente.");
            }
            update(connection, """
                    INSERT INTO app_methodology_source_need
                      (code,methodology,methodology_edition,scenario,stage,kind,title,purpose,source_id,
                       source_edition,official_uri,mandatory,condition_note,blocked_capability,edition_policy,
                       responsible_party,priority)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, input.code(), methodology, edition, scenario, input.stage(), input.kind(), input.title(),
                    input.purpose(), input.sourceId(), input.sourceEdition(), input.officialUri(),
                    input.mandatory(), input.conditionNote(), input.blockedCapability(), input.editionPolicy(),
                    input.responsibleParty(), input.priority());
            ComplianceStaff.audit(connection, actor.id(), null, "REGISTER_METHODOLOGY_NEED");
            return new SavedNeed(need(connection, input.code()), true);
        });
    }

    private static List<Context> contexts(Connection connection, String methodology, String edition)
            throws SQLException {
        return query(connection, CONTEXT_SQL + " WHERE c.methodology=? AND c.methodology_edition=? ORDER BY c.scenario",
                MethodologySources::contextRow, methodology, edition);
    }

    private static Context context(Connection connection, String methodology, String edition, String scenario)
            throws SQLException {
        return first(connection, CONTEXT_SQL
                + " WHERE c.methodology=? AND c.methodology_edition=? AND c.scenario=?",
                MethodologySources::contextRow, methodology, edition, scenario);
    }

    /** Needs counted per context; a source counts as registered only when the intake already published its metadata. */
    private static final String CONTEXT_SQL = """
            SELECT c.methodology,c.methodology_edition,c.scenario,c.title,c.country,c.network_mode,
                   c.vehicle_category,c.charger_type,c.energy_system,
                   (SELECT count(*) FROM app_methodology_source_need n
                     WHERE (n.methodology,n.methodology_edition,n.scenario)=(c.methodology,c.methodology_edition,c.scenario)),
                   (SELECT count(*) FROM app_methodology_source_need n
                     WHERE (n.methodology,n.methodology_edition,n.scenario)=(c.methodology,c.methodology_edition,c.scenario)
                       AND n.source_id IS NOT NULL
                       AND EXISTS (SELECT 1 FROM app_source_version s
                                   WHERE s.project_id IS NULL AND s.source_id=n.source_id
                                     AND (n.edition_policy<>'EXACT' OR s.edition=n.source_edition)))
            FROM app_methodology_source_context c
            """;

    private static Context contextRow(java.sql.ResultSet r) throws SQLException {
        return new Context(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getInt(10), r.getInt(11));
    }

    private static Need need(Connection connection, String code) throws SQLException {
        return first(connection, """
                SELECT code,methodology,methodology_edition,scenario,stage,kind,title,purpose,source_id,
                       source_edition,official_uri,mandatory,condition_note,blocked_capability,edition_policy,
                       responsible_party,priority
                FROM app_methodology_source_need WHERE code=?
                """, r -> new Need(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11),
                r.getBoolean(12), r.getString(13), r.getString(14), r.getString(15), r.getString(16),
                r.getString(17)), code);
    }

    private static void requireRegistered(Connection connection, String methodology, String edition)
            throws SQLException {
        Boolean found = first(connection, """
                SELECT true FROM app_methodology_registry WHERE methodology=? AND methodology_edition=? LIMIT 1
                """, r -> r.getBoolean(1), methodology, edition);
        if (found == null) throw new PilotProblem(404, "METODOLOGIA_NAO_REGISTRADA", "methodology", RULE,
                "Registre a metodologia e a edição antes de cadastrar contextos e fontes.");
    }

    private static void lock(Connection connection, String key) throws SQLException {
        first(connection, "SELECT pg_advisory_xact_lock(hashtext(?))", r -> Boolean.TRUE, "methodology-sources|" + key);
    }

    private static boolean same(Context current, ContextInput input) {
        return current.title().equals(input.title()) && current.country().equals(input.country())
                && Objects.equals(current.networkMode(), input.networkMode())
                && Objects.equals(current.vehicleCategory(), input.vehicleCategory())
                && Objects.equals(current.chargerType(), input.chargerType())
                && Objects.equals(current.energySystem(), input.energySystem());
    }

    private static boolean same(Need current, NeedInput input) {
        return current.stage().equals(input.stage()) && current.kind().equals(input.kind())
                && current.title().equals(input.title()) && current.purpose().equals(input.purpose())
                && Objects.equals(current.sourceId(), input.sourceId())
                && Objects.equals(current.sourceEdition(), input.sourceEdition())
                && Objects.equals(current.officialUri(), input.officialUri())
                && current.mandatory() == input.mandatory() && current.conditionNote().equals(input.conditionNote())
                && current.blockedCapability().equals(input.blockedCapability())
                && current.editionPolicy().equals(input.editionPolicy())
                && current.responsibleParty().equals(input.responsibleParty())
                && current.priority().equals(input.priority());
    }

    static ContextInput normalize(ContextInput v) {
        if (v == null) throw invalid("body");
        String scenario = text(v.scenario()), title = line(v.title()), country = text(v.country());
        if (scenario == null || !scenario.matches("[A-Z][A-Z0-9_]{2,59}")) throw invalid("scenario");
        if (title == null || title.length() < 3 || title.length() > 200) throw invalid("title");
        if (country == null || !country.matches("[A-Z]{2}")) throw invalid("country");
        return new ContextInput(scenario, title, country, attribute(v.networkMode(), "networkMode"),
                attribute(v.vehicleCategory(), "vehicleCategory"), attribute(v.chargerType(), "chargerType"),
                attribute(v.energySystem(), "energySystem"));
    }

    static NeedInput normalize(NeedInput v) {
        if (v == null) throw invalid("body");
        String code = text(v.code()), stage = text(v.stage()), kind = text(v.kind());
        String title = line(v.title()), purpose = line(v.purpose()), note = v.conditionNote() == null ? "" : line(v.conditionNote());
        String sourceId = text(v.sourceId()), sourceEdition = text(v.sourceEdition());
        String uri = text(v.officialUri());
        String capability = v.blockedCapability() == null ? "PREPARATION" : text(v.blockedCapability());
        String policy = v.editionPolicy() == null ? "EXACT" : text(v.editionPolicy());
        String party = v.responsibleParty() == null ? "CARBONFLOW" : text(v.responsibleParty());
        String priority = v.priority() == null ? "P0" : text(v.priority());
        if (code == null || !code.matches("[A-Z][A-Z0-9_-]{2,59}")) throw invalid("code");
        if (stage == null || !STAGES.contains(stage)) throw invalid("stage");
        if (kind == null || !KINDS.contains(kind)) throw invalid("kind");
        if (title == null || title.length() < 3 || title.length() > 200) throw invalid("title");
        if (purpose == null || purpose.length() < 3 || purpose.length() > 500) throw invalid("purpose");
        if (note == null || note.length() > 500) throw invalid("conditionNote");
        if (capability == null || !CAPABILITIES.contains(capability)) throw invalid("blockedCapability");
        if (policy == null || !POLICIES.contains(policy)) throw invalid("editionPolicy");
        if (party == null || !PARTIES.contains(party)) throw invalid("responsibleParty");
        if (priority == null || !PRIORITIES.contains(priority)) throw invalid("priority");
        if (uri != null && !uri.matches("https://[^\\s]{4,250}")) throw invalid("officialUri");
        boolean evidence = "PROJECT_EVIDENCE".equals(kind);
        if (evidence != (sourceId == null) || (sourceId == null) != (sourceEdition == null)) throw invalid("sourceId");
        if (sourceId != null) {
            if (!sourceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,79}")) throw invalid("sourceId");
            if (!sourceEdition.matches("[0-9A-Za-z][0-9A-Za-z._-]{0,19}")) throw invalid("sourceEdition");
            // 'YEAR' and 'LATEST' are policy tokens (the console resolves them); an exact policy needs a real edition.
            if ("YEAR".equals(policy) != "YEAR".equals(sourceEdition)
                    || "LATEST".equals(policy) != "LATEST".equals(sourceEdition)) throw invalid("editionPolicy");
        }
        return new NeedInput(code, stage, kind, title, purpose, sourceId, sourceEdition, uri,
                v.mandatory() == null || v.mandatory(), note, capability, policy, party, priority);
    }

    private static String attribute(String value, String field) {
        String text = text(value);
        if (text != null && !text.matches("[A-Z0-9_]{1,60}")) throw invalid(field);
        return text;
    }

    private static String text(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** Single-line text without control characters. */
    private static String line(String value) {
        String text = text(value);
        if (text == null) return null;
        if (text.codePoints().anyMatch(Character::isISOControl)) throw invalid("text");
        return text.replaceAll("\\s+", " ");
    }

    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("FONTE_METODOLOGIA_INVALIDA", field, RULE,
                "Confira os campos. Fontes são descritas só por metadados; não informe trechos de normas.");
    }

    private static PilotProblem conflict(String field, String message) {
        return new PilotProblem(409, "REGISTRO_EXISTENTE", field, RULE, message);
    }
}
