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
import java.util.TreeMap;
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
    record OperationGround(String operation, String groundKind, String grounds) {}
    record DeclarePublicInput(String sourceId, String edition, String purpose, String licenseIdentifier,
                              String legalTextSha256, String evidenceUri, String evidenceCapturedAt,
                              String evidenceSha256, String startsAt, String startsAtBasis, String endsAt,
                              String reason, List<OperationGround> operations) {}
    record DecisionInput(String outcome, String reason, List<String> operations) {}
    record RevokeInput(String reason) {}
    record License(UUID id, String sourceId, String edition, String purpose, String authorizationRef,
                   String proofSha256, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID submittedBy,
                   int decisionVersion, String state, UUID decidedBy, OffsetDateTime decidedAt, String reason,
                   List<String> operations, String limits, String kind, String licenseIdentifier,
                   String startsAtBasis, String decisionBasis) {}

    private static final String LIMITS = "Registro de autorização declarada, em ambiente de testes. O documento não é "
            + "verificado nem guardado aqui; a decisão independente não habilita cálculo, admissão de dados nem atos externos.";

    private final PilotDatabase database;
    private final ComplianceStaff staff;
    private final Set<UUID> submitters;
    private final Set<UUID> approvers;
    private final Set<UUID> owners;

    ContentLicenses(PilotDatabase database, ComplianceStaff staff,
            @ConfigProperty(name = "carbonflow.platform.content-licenses.submitters") Optional<String> submitters,
            @ConfigProperty(name = "carbonflow.platform.content-licenses.approvers") Optional<String> approvers,
            @ConfigProperty(name = "carbonflow.platform.content-licenses.owners") Optional<String> owners) {
        this.database = database;
        this.staff = staff;
        this.submitters = roster(submitters);
        this.approvers = roster(approvers);
        this.owners = roster(owners);
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
                throw invalid("licenca", "Confira a fonte e a edição, a finalidade, a referência da autorização (mín. 3 caracteres) e o SHA-256 do comprovante (64 caracteres hexadecimais minúsculos).");
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

    /**
     * D19 extended to licences: the product owner records a PUBLIC licence (e.g. CC BY-ND 3.0) with verifiable evidence as an
     * internal declaration. It is NOT an approval: the decision is DECLARED_INTERNAL, which no consumer accepts, and an
     * independent approver (not the person who declared) must still approve it. Each claimed operation states whether the
     * licence text grants it or whether it is a legal interpretation; operations the licence does not cover are refused.
     */
    License declarePublic(String token, DeclarePublicInput input) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!owners.contains(actor.id())) throw hidden();
            if (input == null || blank(input.sourceId(), 120) || blank(input.edition(), 60) || input.purpose() == null
                    || !PURPOSES.contains(input.purpose()) || input.licenseIdentifier() == null
                    || blank(input.evidenceUri(), 500) || !input.evidenceUri().strip().startsWith("https://")
                    || blank(input.reason(), 1000) || input.reason().strip().length() < 10
                    || input.legalTextSha256() == null || !input.legalTextSha256().matches("[a-f0-9]{64}")
                    || input.evidenceSha256() == null || !input.evidenceSha256().matches("[a-f0-9]{64}")
                    || input.operations() == null || input.operations().isEmpty())
                throw invalid("licenca", "Confira os campos da declaração: fonte, versão, finalidade, endereço https da página, justificativa (mín. 10 caracteres), SHA-256 da captura e do texto jurídico (64 caracteres hexadecimais) e ao menos uma operação.");
            var terms = PublicLicenses.KNOWN.get(input.licenseIdentifier());
            if (terms == null) throw new PilotProblem(422, "LICENCA_PUBLICA_DESCONHECIDA", "licenseIdentifier", RULE,
                    "Só licenças públicas modeladas pela plataforma podem ser declaradas.");
            OffsetDateTime captured = time(input.evidenceCapturedAt(), "evidenceCapturedAt");
            OffsetDateTime starts = time(input.startsAt(), "startsAt");
            OffsetDateTime ends = input.endsAt() == null || input.endsAt().isBlank() ? null : time(input.endsAt(), "endsAt");
            String basis = input.startsAtBasis();
            // The start is never earlier than what the evidence proves: capture date unless availability is proven.
            if (!Set.of("CAPTURE_DATE", "PROVEN_AVAILABILITY").contains(basis)
                    || ("CAPTURE_DATE".equals(basis) && !starts.isEqual(captured))
                    || ("PROVEN_AVAILABILITY".equals(basis) && starts.isAfter(captured))
                    || (ends != null && !ends.isAfter(starts)))
                throw invalid("startsAt", "A vigência não pode começar antes da evidência: com o critério \"Data da captura\" ela deve ser idêntica à data da captura; com \"Disponibilização anterior comprovada\", igual ou anterior a ela; e o fim, se houver, depois do início.");
            var chosen = new TreeMap<String, OperationGround>();
            for (var item : input.operations()) {
                if (item == null || item.operation() == null || !OPERATIONS.contains(item.operation())
                        || item.groundKind() == null || !Set.of("LICENSE_GRANT", "LEGAL_INTERPRETATION").contains(item.groundKind())
                        || item.grounds() == null || item.grounds().strip().length() < 20 || item.grounds().strip().length() > 2000
                        || chosen.put(item.operation(), item) != null)
                    throw invalid("operations", "Cada operação declarada precisa ser conhecida, aparecer uma só vez e ter natureza do fundamento (cláusula ou interpretação) e fundamento de 20 a 2000 caracteres.");
                if (terms.refused().contains(item.operation()))
                    throw new PilotProblem(422, "OPERACAO_FORA_DA_LICENCA", "operations", RULE,
                            "A licença não cobre " + item.operation() + " (" + terms.basis() + ").");
                if (item.groundKind().equals("LICENSE_GRANT") && !terms.granted().contains(item.operation()))
                    throw new PilotProblem(422, "FUNDAMENTO_NAO_E_CLAUSULA", "operations", RULE,
                            item.operation() + " não consta da concessão da licença; declare-a como interpretação jurídica.");
            }
            Boolean exists = first(c, "SELECT true FROM app_source_version WHERE source_id=? AND edition=? AND origin<>'PROJECT'",
                    r -> r.getBoolean(1), input.sourceId().strip(), input.edition().strip());
            if (exists == null) throw new PilotProblem(422, "FONTE_INEXISTENTE", "sourceId", RULE,
                    "Registre antes os metadados da fonte e da edição.");
            UUID id = UUID.randomUUID();
            update(c, """
                    INSERT INTO app_content_license(id,source_id,edition,submitted_by,environment,purpose,authorization_ref,
                        proof_sha256,starts_at,ends_at,kind,license_identifier,legal_text_uri,legal_text_sha256,evidence_uri,
                        evidence_captured_at,starts_at_basis)
                    VALUES (?,?,?,?,'SANDBOX',?,?,?,?,?,'PUBLIC_LICENSE',?,?,?,?,?,?)
                    """, id, input.sourceId().strip(), input.edition().strip(), actor.id(), input.purpose(),
                    "Licença pública " + terms.identifier() + " · captura " + input.evidenceUri().strip(),
                    input.evidenceSha256(), starts, ends, terms.identifier(), terms.legalTextUri(),
                    input.legalTextSha256(), input.evidenceUri().strip(), captured, basis);
            insertDecision(c, id, 1, "DECLARED_INTERNAL", actor.id(), input.reason().strip(), chosen.keySet());
            for (var item : chosen.values())
                update(c, "INSERT INTO app_content_license_declared_ground(license_id,decision_version,operation,ground_kind,grounds) VALUES (?,?,?,?,?)",
                        id, 1, item.operation(), item.groundKind(), item.grounds().strip());
            ComplianceStaff.audit(c, actor.id(), null, "DECLARE_PUBLIC_CONTENT_LICENSE");
            return load(c, id).get(0);
        });
    }

    License decide(String token, UUID id, String ifMatch, DecisionInput input) {
        int expected = PilotCommands.expected(ifMatch);
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!approvers.contains(actor.id())) throw hidden();
            if (input == null || input.outcome() == null || !Set.of("APPROVED", "DENIED").contains(input.outcome())
                    || blank(input.reason(), 1000) || input.reason().strip().length() < 10)
                throw invalid("decisao", "A decisão precisa de um desfecho (aprovar ou negar) e de um motivo com pelo menos 10 caracteres.");
            var operations = operations(input);
            if (input.outcome().equals("APPROVED") && operations.isEmpty())
                throw invalid("operations", "Para aprovar, marque ao menos uma operação.");
            if (input.outcome().equals("DENIED") && !operations.isEmpty())
                throw invalid("operations", "Ao negar, não marque operações.");
            var current = lock(c, id);
            PilotCommands.requireVersion(expected, current.version());
            if (current.state().equals("DENIED") || current.state().equals("REVOKED") || current.state().equals("APPROVED"))
                throw new PilotProblem(409, "LICENCA_ENCERRADA", "id", RULE,
                        "A licença já tem decisão; para mudar, revogue (se aprovada) ou submeta uma nova.");
            if (input.outcome().equals("APPROVED")) {
                // A public licence is approved only for what the owner declared (with grounds): the approver can narrow it
                // but never widen it, so blocked operations (e.g. AI, or derivatives under a no-derivatives licence) stay out.
                Boolean isPublic = first(c, "SELECT true FROM app_content_license WHERE id=? AND kind='PUBLIC_LICENSE'",
                        r -> r.getBoolean(1), id);
                if (Boolean.TRUE.equals(isPublic)) {
                    var declared = new TreeSet<>(query(c, "SELECT operation FROM app_content_license_declared_ground "
                            + "WHERE license_id=? AND decision_version=1", r -> r.getString(1), id));
                    var beyond = new TreeSet<>(operations);
                    beyond.removeAll(declared);
                    if (!beyond.isEmpty()) throw new PilotProblem(422, "OPERACAO_NAO_DECLARADA", "operations", RULE,
                            "Esta licença pública foi declarada só para " + String.join(", ", declared) + ". Não é possível aprovar: "
                                    + String.join(", ", beyond) + ". Desmarque o que não foi declarado.");
                }
            }
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
            if (input == null || blank(input.reason(), 1000) || input.reason().strip().length() < 10)
                throw invalid("motivo", "A revogação precisa de um motivo com pelo menos 10 caracteres.");
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
            if (operation == null || !OPERATIONS.contains(operation))
                throw invalid("operations", "Operação desconhecida: " + operation + ".");
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
                                 WHERE cap.license_id=l.id AND cap.decision_version=d.version),''),
                       l.kind, l.license_identifier, l.starts_at_basis
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
                        r.getString(15).isEmpty() ? List.<String>of() : List.of(r.getString(15).split(",")), LIMITS,
                        r.getString(16), r.getString(17), r.getString(18),
                        "DECLARED_INTERNAL".equals(r.getString(11)) ? "OWNER_INTERNAL" : r.getString(11) == null ? null : "INDEPENDENT"),
                only, only);
    }

    private void requireAny(ComplianceStaff.Staff actor) {
        if (!submitters.contains(actor.id()) && !approvers.contains(actor.id()) && !owners.contains(actor.id())) throw hidden();
    }

    private static boolean blank(String value, int max) {
        return value == null || value.isBlank() || value.strip().length() > max;
    }

    private static OffsetDateTime time(String value, String field) {
        try {
            if (value == null) throw invalid(field, "Informe " + field + " em ISO-8601 com fuso, por exemplo 2026-11-25T20:44:00Z.");
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalid(field, "Data inválida em " + field + ": use ISO-8601 com fuso, por exemplo 2026-11-25T20:44:00Z.");
        }
    }

    private static PilotProblem hidden() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "licenses", RULE,
                "Licenças de fontes não disponíveis para este perfil.");
    }

    private static PilotProblem invalid(String field, String message) {
        return PilotProblem.invalid("LICENCA_INVALIDA", field, RULE, message);
    }

    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("LICENCA_INVALIDA", field, RULE,
                "Confira fonte, edição, finalidade, referência, hash SHA-256 do comprovante, datas (ISO-8601), motivo (mín. 10 caracteres) e operações.");
    }
}
