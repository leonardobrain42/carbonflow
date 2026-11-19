package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Read-only platform diagnostic. A license scoped to a customer organization never
 * authorizes platform-wide processing, real normative publication or E8 calculations.
 */
@ApplicationScoped
class PlatformSourceReadiness {
    record Context(String methodology, String edition, String scenario, String title, String country,
                   String networkMode, String vehicleCategory, String chargerType, String energySystem,
                   String catalogState) {}

    record Need(String code, String stage, String kind, String title, String purpose,
                String sourceId, String sourceEdition, String officialUri, boolean mandatory,
                String conditionNote, String status, String nextAction,
                String blockedCapability, String editionPolicy, String responsibleParty, String priority,
                String observedEdition, String rightsScope,
                boolean referenceAllowedInStaffOrganization, boolean extractionAllowedInStaffOrganization,
                boolean ragAllowedInStaffOrganization, boolean modelAllowedInStaffOrganization,
                boolean technicalAdmissionConfirmed) {}

    record View(String methodology, String edition, String scenario, OffsetDateTime asOf,
                String normativeRuleStatus, String dynamicCalculationStatus, boolean normativeAiDisabled,
                int missingSourceCount, int missingReferenceRightsCount,
                int reviewPendingCount, int projectEvidenceCount,
                int total, Integer nextOffset, List<Need> needs) {}

    private final PilotDatabase database;
    private final ComplianceStaff staff;
    private final ContentLicensePolicy licensePolicy;
    private final Set<UUID> viewers;

    PlatformSourceReadiness(PilotDatabase database, ComplianceStaff staff, ContentLicensePolicy licensePolicy,
            @ConfigProperty(name = "carbonflow.platform.source-readiness.viewers") Optional<String> configuredViewers) {
        this.database = database;
        this.staff = staff;
        this.licensePolicy = licensePolicy;
        try {
            this.viewers = configuredViewers.map(value -> Arrays.stream(value.split(",", -1))
                    .map(String::strip).filter(valuePart -> !valuePart.isEmpty())
                    .map(UUID::fromString).collect(Collectors.toUnmodifiableSet()))
                    .orElse(Set.of());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Cadastro de leitores do painel de fontes inválido.");
        }
    }

    List<Context> contexts(String token) {
        return database.transaction(connection -> {
            ComplianceStaff.Staff actor = viewer(connection, token);
            List<Context> rows = query(connection, """
                    SELECT methodology,methodology_edition,scenario,title,country,network_mode,
                           vehicle_category,charger_type,energy_system,catalog_state
                    FROM app_methodology_source_context
                    ORDER BY methodology,methodology_edition,scenario
                    """, row -> new Context(row.getString(1),row.getString(2),row.getString(3),
                    row.getString(4),row.getString(5),row.getString(6),row.getString(7),
                    row.getString(8),row.getString(9),row.getString(10)));
            ComplianceStaff.audit(connection, actor.id(), null, "VIEW_PLATFORM_SOURCE_CONTEXTS");
            return List.copyOf(rows);
        });
    }

    View read(String token, String methodology, String edition, String scenario,
              String stage, String status, int offset, int limit) {
        return database.transaction(connection -> {
            ComplianceStaff.Staff actor = viewer(connection, token);
            if (methodology == null || !methodology.matches("[A-Z][A-Z0-9_-]{2,39}")) throw invisible();
            if (offset < 0 || offset > 100000 || limit < 1 || limit > 100) throw invalid("page");
            if (stage != null && !stage.isBlank() && !Set.of("APPLICABILITY", "BASELINE", "ADDITIONALITY",
                    "QUANTIFICATION", "MONITORING", "DOCUMENTS").contains(stage)) throw invalid("stage");
            if (status != null && !status.isBlank() && !Set.of("MISSING_SOURCE_METADATA",
                    "METADATA_ONLY", "SCOPED_REFERENCE_NOT_ADMITTED", "VERSION_SELECTION_REQUIRED",
                    "PROJECT_SPECIFIC_EVIDENCE").contains(status)) throw invalid("status");

            List<Context> matching = query(connection, """
                    SELECT methodology,methodology_edition,scenario,title,country,network_mode,
                           vehicle_category,charger_type,energy_system,catalog_state
                    FROM app_methodology_source_context
                    WHERE methodology = ? AND (?::text IS NULL OR methodology_edition = ?)
                        AND (?::text IS NULL OR scenario = ?)
                    ORDER BY methodology_edition,scenario
                    LIMIT 2
                    """, row -> new Context(row.getString(1),row.getString(2),row.getString(3),
                    row.getString(4),row.getString(5),row.getString(6),row.getString(7),
                    row.getString(8),row.getString(9),row.getString(10)),
                    methodology, emptyToNull(edition), emptyToNull(edition),
                    emptyToNull(scenario), emptyToNull(scenario));
            if (matching.isEmpty()) throw invisible();
            if (matching.size() > 1) throw new PilotProblem(400, "CONTEXTO_NECESSARIO", "scenario",
                    "CF-OPEN-001", "Informe a edição e o cenário do inventário.");
            Context target = matching.getFirst();

            java.sql.Array organizations = connection.createArrayOf("uuid",
                    actor.organizations().toArray(UUID[]::new));
            List<Need> all;
            try {
                all = query(connection, """
                    SELECT n.code,n.stage,n.kind,n.title,n.purpose,n.source_id,n.source_edition,
                           COALESCE(n.official_uri,sv.official_uri),n.mandatory,n.condition_note,
                           n.blocked_capability,n.edition_policy,n.responsible_party,n.priority,
                           sv.edition AS observed_edition,
                           COALESCE(rights.ref,false) AS ref_right,
                           COALESCE(rights.extract,false) AS extract_right,
                           COALESCE(rights.rag,false) AS rag_right,
                           COALESCE(rights.model,false) AS model_right
                    FROM app_methodology_source_need n
                    LEFT JOIN LATERAL (
                        SELECT source.edition,source.official_uri,source.source_id
                        FROM app_source_version source
                        WHERE source.project_id IS NULL AND source.source_id = n.source_id
                          AND (n.edition_policy <> 'EXACT' OR source.edition = n.source_edition)
                        ORDER BY source.edition DESC LIMIT 1
                    ) sv ON true
                    LEFT JOIN LATERAL (
                        SELECT bool_or(cap.operation = 'REFERENCE_REQUIREMENT') AS ref,
                               bool_or(cap.operation = 'EXTRACT_TEXT') AS extract,
                               bool_or(cap.operation = 'RAG_RETRIEVE') AS rag,
                               bool_or(cap.operation = 'MODEL_INFERENCE') AS model
                        FROM app_content_license license
                        JOIN LATERAL (
                            SELECT version,outcome,decided_by,decided_at
                            FROM app_content_license_decision
                            WHERE license_id = license.id ORDER BY version DESC LIMIT 1
                        ) decision ON true
                        JOIN app_content_license_capability cap
                          ON cap.license_id = license.id AND cap.decision_version = decision.version
                        WHERE license.source_id = sv.source_id AND license.edition = sv.edition
                          AND license.organization_id = ANY(?)
                          AND license.project_id IS NULL AND license.actor_id IS NULL
                          AND license.environment = 'SANDBOX'
                          AND license.purpose = 'REQUIREMENT_CATALOG'
                          AND license.provider IS NULL
                          AND license.proof_sha256 IS NOT NULL
                          AND license.submitted_by IS NOT NULL
                          AND license.submitted_by <> decision.decided_by
                          AND decision.decided_at <= statement_timestamp()
                          AND decision.outcome = 'APPROVED'
                          AND license.starts_at <= statement_timestamp()
                          AND (license.ends_at IS NULL OR license.ends_at > statement_timestamp())
                    ) rights ON true
                    WHERE n.methodology = ? AND n.methodology_edition = ? AND n.scenario = ?
                    ORDER BY CASE n.stage
                        WHEN 'APPLICABILITY' THEN 1 WHEN 'BASELINE' THEN 2
                        WHEN 'ADDITIONALITY' THEN 3 WHEN 'QUANTIFICATION' THEN 4
                        WHEN 'MONITORING' THEN 5 ELSE 6 END, n.priority, n.code
                    """, row -> {
                        String kind = row.getString("kind");
                        String observed = row.getString("observed_edition");
                        String policy = row.getString("edition_policy");
                        boolean ref = row.getBoolean("ref_right");
                        boolean extract = row.getBoolean("extract_right");
                        boolean rag = row.getBoolean("rag_right");
                        boolean model = row.getBoolean("model_right");
                        String state = status(kind, observed, policy, ref);
                        String scope = ref || extract || rag || model ? "STAFF_ORGANIZATION_SCOPED" : "NO_ACTIVE_SCOPED_RIGHTS";
                        return new Need(row.getString("code"),row.getString("stage"),kind,
                                row.getString("title"),row.getString("purpose"),row.getString("source_id"),
                                row.getString("source_edition"),row.getString(8),row.getBoolean("mandatory"),
                                row.getString("condition_note"),state,nextAction(state),
                                row.getString("blocked_capability"),policy,
                                row.getString("responsible_party"),row.getString("priority"),
                                observed,scope,ref,extract,rag && !licensePolicy.normativeAiDisabled(),
                                model && !licensePolicy.normativeAiDisabled(),false);
                    }, organizations, target.methodology(),target.edition(),target.scenario());
            } finally {
                organizations.free();
            }

            List<Need> filtered = all.stream().filter(n -> stage == null || stage.isBlank() || stage.equals(n.stage()))
                    .filter(n -> status == null || status.isBlank() || status.equals(n.status())).toList();
            int total = filtered.size();
            List<Need> page = filtered.stream().skip(offset).limit(limit).toList();
            Integer nextOffset = offset + limit < total ? offset + limit : null;
            Integer synthetic = first(connection, """
                    SELECT count(*) FROM app_norm_pack
                    WHERE methodology = ? AND methodology_edition = ? AND state = 'PUBLISHED_SYNTHETIC'
                    """, row -> row.getInt(1), target.methodology(),target.edition());
            ComplianceStaff.audit(connection, actor.id(), null, "VIEW_PLATFORM_SOURCE_READINESS");
            return new View(target.methodology(),target.edition(),target.scenario(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    synthetic != null && synthetic > 0 ? "ONLY_SYNTHETIC_RULE_PACKS" : "NO_PUBLISHED_REAL_RULE_PACK",
                    "NOT_ENABLED_FOR_DYNAMIC_PROJECTS", licensePolicy.normativeAiDisabled(),
                    (int) all.stream().filter(n -> n.mandatory()
                            && n.status().equals("MISSING_SOURCE_METADATA")).count(),
                    (int) all.stream().filter(n -> n.mandatory() &&
                            (n.status().equals("METADATA_ONLY") ||
                             n.status().equals("VERSION_SELECTION_REQUIRED"))).count(),
                    (int) all.stream().filter(n -> n.status().equals("SCOPED_REFERENCE_NOT_ADMITTED")).count(),
                    (int) all.stream().filter(n -> n.kind().equals("PROJECT_EVIDENCE")).count(),
                    total,nextOffset,List.copyOf(page));
        });
    }

    /** Kept for legacy unit expectations; rights are scoped, never platform-wide. */
    static String status(String kind, boolean registered, boolean referencePermission) {
        if ("PROJECT_EVIDENCE".equals(kind)) return "PROJECT_SPECIFIC_EVIDENCE";
        if (!registered) return "MISSING_SOURCE_METADATA";
        return referencePermission ? "SCOPED_REFERENCE_NOT_ADMITTED" : "METADATA_ONLY";
    }

    static String status(String kind, String observedEdition, String editionPolicy, boolean referencePermission) {
        if ("PROJECT_EVIDENCE".equals(kind)) return "PROJECT_SPECIFIC_EVIDENCE";
        if (observedEdition == null) return "MISSING_SOURCE_METADATA";
        if (!"EXACT".equals(editionPolicy)) return "VERSION_SELECTION_REQUIRED";
        return referencePermission ? "SCOPED_REFERENCE_NOT_ADMITTED" : "METADATA_ONLY";
    }

    static String nextAction(String status) {
        return switch (status) {
            case "PROJECT_SPECIFIC_EVIDENCE" -> "Solicitar ao titular/operador no projeto, com prova e responsável.";
            case "MISSING_SOURCE_METADATA" -> "Cadastrar a edição e origem oficial; conferir direitos antes de ingerir.";
            case "VERSION_SELECTION_REQUIRED" -> "Selecionar a edição aplicável ao ano e projeto; revisar fonte, direitos e método.";
            case "METADATA_ONLY" -> "Conferir autorização por operação e revisão; não extrair nem indexar automaticamente.";
            default -> "Direito somente para organização autorizada; revisar admissibilidade e uso antes de cálculo.";
        };
    }

    private ComplianceStaff.Staff viewer(Connection connection, String token) throws SQLException {
        ComplianceStaff.Staff actor = staff.require(connection, token);
        if (!viewers.contains(actor.id())) throw new PilotProblem(404, "RECURSO_NAO_VISIVEL",
                "viewer", "CF-OPEN-001", "Painel administrativo não disponível para este perfil.");
        return actor;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static PilotProblem invalid(String field) {
        return new PilotProblem(400, "FILTRO_INVALIDO", field, "CF-OPEN-001", "Filtro do inventário inválido.");
    }

    private static PilotProblem invisible() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "methodology",
                "CF-OPEN-001", "Metodologia não disponível neste painel.");
    }
}
