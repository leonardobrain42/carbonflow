package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CF-DATA-001 / CF-IMP-029: platform administration of source licences (SANDBOX). It records a declared authorisation
 * and an independent decision; it never verifies the document, never stores its bytes and never enables calculation,
 * admission or external acts. The capability view (MethodologyCapabilities) and every consumer of
 * {@code app_content_license} keep deriving effect from the latest decision, so a revocation closes at once.
 *
 * Privileges are two configured rosters of staff identities (submitters, approvers); empty means unavailable. The
 * person who submitted a licence cannot approve it (dual custody). Only platform-wide licences are handled here
 * (no organization, project or actor); scoped licences keep their own flows.
 */
@ApplicationScoped
class ContentLicenses {
    static final String RULE = "CF-IMP-029";
    static final Set<String> PURPOSES = Set.of("REQUIREMENT_CATALOG", "CONTEXTUAL_HELP", "AUTHORIZED_PROCESSING");
    static final Set<String> OPERATIONS = Set.of("REFERENCE_REQUIREMENT", "STORE_ORIGINAL", "EXTRACT_TEXT",
            "INDEX_SEARCH", "CREATE_EMBEDDINGS", "RAG_RETRIEVE", "MODEL_INFERENCE", "SHOW_EXCERPTS",
            "DRAFT_CONTENT", "EXPORT_DERIVATIVES");

    record SubmitInput(String sourceId, String edition, String purpose, String authorizationRef, String proofSha256,
                       String startsAt, String endsAt) {}
    record DecisionInput(String outcome, String reason, List<String> operations) {}
    record RevokeInput(String reason) {}
    record License(UUID id, String sourceId, String edition, String purpose, String authorizationRef,
                   String proofSha256, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID submittedBy,
                   int decisionVersion, String state, UUID decidedBy, OffsetDateTime decidedAt, String reason,
                   List<String> operations, String limits) {}

    private static final String LIMITS = "Registro de autorização declarada, em ambiente de testes. O documento não é "
            + "verificado nem guardado aqui; a decisão independente não habilita cálculo, admissão de dados nem atos externos.";

    private final PilotDatabase database;
    private final ComplianceStaff staff;
    private final Set<UUID> submitters;
    private final Set<UUID> approvers;

    ContentLicenses(PilotDatabase database, ComplianceStaff staff,
            @ConfigProperty(name = "carbonflow.platform.content-licenses.submitters") Optional<String> submitters,
            @ConfigProperty(name = "carbonflow.platform.content-licenses.approvers") Optional<String> approvers) {
        this.database = database;
        this.staff = staff;
        this.submitters = roster(submitters);
        this.approvers = roster(approvers);
    }

    private static Set<UUID> roster(Optional<String> value) {
        try {
            return value.map(text -> Arrays.stream(text.split(",", -1)).map(String::strip)
                    .filter(part -> !part.isEmpty()).map(UUID::fromString).collect(Collectors.toUnmodifiableSet()))
                    .orElse(Set.of());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Cadastro de responsáveis por licenças de fontes inválido.");
        }
    }

    List<License> list(String token) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            requireAny(actor);
            ComplianceStaff.audit(c, actor.id(), null, "VIEW_CONTENT_LICENSES");
            return load(c, null);
        });
    }

    License submit(String token, SubmitInput input) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!submitters.contains(actor.id())) throw hidden();
            if (input == null || blank(input.sourceId(), 120) || blank(input.edition(), 60)
                    || input.purpose() == null || !PURPOSES.contains(input.purpose())
                    || blank(input.authorizationRef(), 500) || input.authorizationRef().strip().length() < 3
                    || input.proofSha256() == null || !input.proofSha256().matches("[a-f0-9]{64}"))
                throw invalid("licenca");
            OffsetDateTime starts = time(input.startsAt(), "startsAt");
            OffsetDateTime ends = input.endsAt() == null || input.endsAt().isBlank() ? null : time(input.endsAt(), "endsAt");
            if (ends != null && !ends.isAfter(starts)) throw invalid("endsAt");
            Boolean exists = first(c, "SELECT true FROM app_source_version WHERE source_id=? AND edition=? AND origin<>'PROJECT'",
                    r -> r.getBoolean(1), input.sourceId().strip(), input.edition().strip());
            if (exists == null) throw new PilotProblem(422, "FONTE_INEXISTENTE", "sourceId", RULE,
                    "Registre antes os metadados da fonte e da edição.");
            UUID id = UUID.randomUUID();
            update(c, """
                    INSERT INTO app_content_license(id,source_id,edition,submitted_by,environment,purpose,
                        authorization_ref,proof_sha256,starts_at,ends_at)
                    VALUES (?,?,?,?,'SANDBOX',?,?,?,?,?)
                    """, id, input.sourceId().strip(), input.edition().strip(), actor.id(), input.purpose(),
                    input.authorizationRef().strip(), input.proofSha256(), starts, ends);
            ComplianceStaff.audit(c, actor.id(), null, "SUBMIT_CONTENT_LICENSE");
            return load(c, id).get(0);
        });
    }

    License decide(String token, UUID id, String ifMatch, DecisionInput input) {
        int expected = PilotCommands.expected(ifMatch);
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!approvers.contains(actor.id())) throw hidden();
            if (input == null || input.outcome() == null || !Set.of("APPROVED", "DENIED").contains(input.outcome())
                    || blank(input.reason(), 1000) || input.reason().strip().length() < 10) throw invalid("decisao");
            var operations = operations(input);
            if (input.outcome().equals("APPROVED") && operations.isEmpty()) throw invalid("operations");
            if (input.outcome().equals("DENIED") && !operations.isEmpty()) throw invalid("operations");
            var current = lock(c, id);
            PilotCommands.requireVersion(expected, current.version());
            if (current.state().equals("DENIED") || current.state().equals("REVOKED") || current.state().equals("APPROVED"))
                throw new PilotProblem(409, "LICENCA_ENCERRADA", "id", RULE,
                        "A licença já tem decisão; para mudar, revogue (se aprovada) ou submeta uma nova.");
            if (input.outcome().equals("APPROVED") && actor.id().equals(current.submittedBy()))
                throw new PilotProblem(403, "DUPLA_CUSTODIA", "decidedBy", RULE,
                        "Quem submeteu a autorização não pode aprová-la.");
            insertDecision(c, id, current.version() + 1, input.outcome(), actor.id(), input.reason().strip(), operations);
            ComplianceStaff.audit(c, actor.id(), null, "DECIDE_CONTENT_LICENSE");
            return load(c, id).get(0);
        });
    }

    License revoke(String token, UUID id, String ifMatch, RevokeInput input) {
        int expected = PilotCommands.expected(ifMatch);
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!approvers.contains(actor.id())) throw hidden();
            if (input == null || blank(input.reason(), 1000) || input.reason().strip().length() < 10) throw invalid("motivo");
            var current = lock(c, id);
            PilotCommands.requireVersion(expected, current.version());
            if (!current.state().equals("APPROVED")) throw new PilotProblem(409, "LICENCA_NAO_APROVADA", "id", RULE,
                    "Só uma licença aprovada pode ser revogada.");
            insertDecision(c, id, current.version() + 1, "REVOKED", actor.id(), input.reason().strip(), Set.of());
            ComplianceStaff.audit(c, actor.id(), null, "DECIDE_CONTENT_LICENSE");
            return load(c, id).get(0);
        });
    }

    private static Set<String> operations(DecisionInput input) {
        var result = new TreeSet<String>();
        if (input.operations() != null) for (String operation : input.operations()) {
            if (operation == null || !OPERATIONS.contains(operation)) throw invalid("operations");
            result.add(operation);
        }
        return result;
    }

    private record Locked(int version, String state, UUID submittedBy) {}

    private Locked lock(Connection c, UUID id) throws SQLException {
        UUID submitter = first(c, "SELECT submitted_by FROM app_content_license WHERE id=? AND project_id IS NULL "
                + "AND organization_id IS NULL AND actor_id IS NULL FOR UPDATE", r -> r.getObject(1, UUID.class), id);
        if (submitter == null) throw new PilotProblem(404, "LICENCA_INEXISTENTE", "id", RULE, "Licença não encontrada.");
        var found = load(c, id);
        return new Locked(found.get(0).decisionVersion(), found.get(0).state(), submitter);
    }

    private static void insertDecision(Connection c, UUID id, int version, String outcome, UUID by, String reason,
                                       Set<String> operations) throws SQLException {
        update(c, "INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason) VALUES (?,?,?,?,?)",
                id, version, outcome, by, reason);
        for (String operation : operations)
            update(c, "INSERT INTO app_content_license_capability(license_id,decision_version,operation) VALUES (?,?,?)",
                    id, version, operation);
    }

    private static List<License> load(Connection c, UUID only) throws SQLException {
        return query(c, """
                SELECT l.id,l.source_id,l.edition,l.purpose,l.authorization_ref,l.proof_sha256,l.starts_at,l.ends_at,
                       l.submitted_by, d.version, d.outcome, d.decided_by, d.decided_at, d.reason,
                       COALESCE((SELECT string_agg(cap.operation,',' ORDER BY cap.operation)
                                 FROM app_content_license_capability cap
                                 WHERE cap.license_id=l.id AND cap.decision_version=d.version),'')
                FROM app_content_license l
                LEFT JOIN LATERAL (SELECT version,outcome,decided_by,decided_at,reason FROM app_content_license_decision
                                   WHERE license_id=l.id ORDER BY version DESC LIMIT 1) d ON true
                WHERE l.project_id IS NULL AND l.organization_id IS NULL AND l.actor_id IS NULL
                  AND l.submitted_by IS NOT NULL AND (?::uuid IS NULL OR l.id=?::uuid)
                ORDER BY l.starts_at DESC, l.id
                """, r -> new License(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getObject(7, OffsetDateTime.class),
                        r.getObject(8, OffsetDateTime.class), r.getObject(9, UUID.class), r.getInt(10),
                        r.getString(11) == null ? "PENDING" : r.getString(11), r.getObject(12, UUID.class),
                        r.getObject(13, OffsetDateTime.class), r.getString(14),
                        r.getString(15).isEmpty() ? List.<String>of() : List.of(r.getString(15).split(",")), LIMITS),
                only, only);
    }

    private void requireAny(ComplianceStaff.Staff actor) {
        if (!submitters.contains(actor.id()) && !approvers.contains(actor.id())) throw hidden();
    }

    private static boolean blank(String value, int max) {
        return value == null || value.isBlank() || value.strip().length() > max;
    }

    private static OffsetDateTime time(String value, String field) {
        try {
            if (value == null) throw invalid(field);
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalid(field);
        }
    }

    private static PilotProblem hidden() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "licenses", RULE,
                "Licenças de fontes não disponíveis para este perfil.");
    }

    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("LICENCA_INVALIDA", field, RULE,
                "Confira fonte, edição, finalidade, referência, hash SHA-256 do comprovante, datas (ISO-8601), motivo (mín. 10 caracteres) e operações.");
    }
}
