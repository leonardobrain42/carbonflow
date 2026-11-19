package br.carbonflow.pilot;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;

/**
 * Staff-only diagnostic of source metadata gaps. Reference rights are not technical admission.
 * This intentionally exposes no customer uploads, project facts or copyrighted source text.
 */
@ApplicationScoped
class PlatformSourceReadiness {
    private static final String SCENARIO = "OPEN_LDV_DCFC_SIN_BR";

    record Need(String code, String stage, String kind, String title, String purpose,
                String sourceId, String sourceEdition, String officialUri, boolean mandatory,
                String conditionNote, String status, String nextAction) {}
    record View(String methodology, String edition, String scenario, OffsetDateTime asOf,
                String normativeRuleStatus, int missingSourceCount, int missingReferenceRightsCount,
                int reviewPendingCount, int projectEvidenceCount, List<Need> needs) {}

    private final PilotDatabase database;
    private final ComplianceStaff staff;

    PlatformSourceReadiness(PilotDatabase database, ComplianceStaff staff) {
        this.database = database;
        this.staff = staff;
    }

    View read(String token, String methodology) {
        if (methodology == null || !methodology.matches("[A-Z0-9]{3,20}")) {
            throw invisible();
        }
        return database.transaction(connection -> {
            var actor = staff.require(connection, token);
            var needs = query(connection, """
                SELECT n.code, n.methodology_edition, n.stage, n.kind, n.title, n.purpose,
                       n.source_id, n.source_edition, n.official_uri, n.mandatory, n.condition_note,
                       (v.source_id IS NOT NULL) AS registered,
                       EXISTS (
                         SELECT 1 FROM app_content_license l
                         JOIN LATERAL (
                           SELECT d.version, d.outcome FROM app_content_license_decision d
                           WHERE d.license_id = l.id ORDER BY d.version DESC LIMIT 1
                         ) latest ON true
                         JOIN app_content_license_capability cap
                           ON cap.license_id = l.id AND cap.decision_version = latest.version
                         WHERE l.source_id = n.source_id AND l.edition = n.source_edition
                           AND l.organization_id IS NOT NULL AND l.project_id IS NULL AND l.actor_id IS NULL
                           AND l.environment = 'SANDBOX' AND l.provider IS NULL
                           AND l.proof_sha256 IS NOT NULL
                           AND l.submitted_by IS NOT NULL
                           AND latest.decided_by IS NOT NULL
                           AND l.submitted_by <> latest.decided_by
                           AND l.purpose = 'REQUIREMENT_CATALOG'
                           AND l.starts_at <= statement_timestamp()
                           AND (l.ends_at IS NULL OR l.ends_at > statement_timestamp())
                           AND latest.outcome = 'APPROVED'
                           AND cap.operation = 'REFERENCE_REQUIREMENT'
                       ) AS reference_authorized
                FROM app_methodology_source_need n
                LEFT JOIN app_source_version v ON v.source_id = n.source_id AND v.edition = n.source_edition
                WHERE n.methodology = ? AND n.scenario = ?
                ORDER BY CASE n.stage
                    WHEN 'APPLICABILITY' THEN 1 WHEN 'BASELINE' THEN 2
                    WHEN 'ADDITIONALITY' THEN 3 WHEN 'QUANTIFICATION' THEN 4
                    WHEN 'MONITORING' THEN 5 ELSE 6 END, n.code
                """, row -> {
                    String kind = row.getString("kind");
                    boolean registered = row.getBoolean("registered");
                    boolean authorized = row.getBoolean("reference_authorized");
                    String state = status(kind, registered, authorized);
                    return new Need(row.getString("code"), row.getString("stage"), kind,
                            row.getString("title"), row.getString("purpose"),
                            row.getString("source_id"), row.getString("source_edition"),
                            row.getString("official_uri"), row.getBoolean("mandatory"),
                            row.getString("condition_note"), state, nextAction(state));
                }, methodology, SCENARIO);
            if (needs.isEmpty()) throw invisible();
            String edition = first(connection, """
                SELECT methodology_edition FROM app_methodology_source_need
                WHERE methodology = ? AND scenario = ? LIMIT 1
                """, row -> row.getString(1), methodology, SCENARIO);
            Integer demoPacks = first(connection, """
                SELECT count(*) FROM app_norm_pack
                WHERE methodology = ? AND methodology_edition = ? AND state = 'PUBLISHED_SYNTHETIC'
                """, row -> row.getInt(1), methodology, edition);
            ComplianceStaff.audit(connection, actor.id(), null, "VIEW_PLATFORM_SOURCE_READINESS");
            return new View(methodology, edition, SCENARIO, OffsetDateTime.now(ZoneOffset.UTC),
                    demoPacks != null && demoPacks > 0 ? "ONLY_SYNTHETIC_RULE_PACKS" : "NO_PUBLISHED_REAL_RULE_PACK",
                    (int) needs.stream().filter(n -> n.mandatory() && n.status().equals("MISSING_SOURCE_METADATA")).count(),
                    (int) needs.stream().filter(n -> n.mandatory() && n.status().equals("METADATA_ONLY")).count(),
                    (int) needs.stream().filter(n -> n.status().equals("REFERENCE_AUTHORIZED_NOT_ADMITTED")).count(),
                    (int) needs.stream().filter(n -> n.kind().equals("PROJECT_EVIDENCE")).count(),
                    List.copyOf(needs));
        });
    }

    static String status(String kind, boolean registered, boolean authorized) {
        if ("PROJECT_EVIDENCE".equals(kind)) return "PROJECT_SPECIFIC_EVIDENCE";
        if (!registered) return "MISSING_SOURCE_METADATA";
        if (!authorized) return "METADATA_ONLY";
        return "REFERENCE_AUTHORIZED_NOT_ADMITTED";
    }

    static String nextAction(String status) {
        return switch (status) {
            case "PROJECT_SPECIFIC_EVIDENCE" -> "Solicitar ao titular/operador no projeto, com prova e responsável.";
            case "MISSING_SOURCE_METADATA" -> "Cadastrar a edição e origem oficial; conferir direitos antes de ingerir.";
            case "METADATA_ONLY" -> "Conferir autorização por operação e revisão; não extrair nem indexar automaticamente.";
            default -> "Referência autorizada; ainda exigir admissibilidade técnica por uso e publicação das regras reais.";
        };
    }

    private static PilotProblem invisible() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "methodology",
                "CF-OPEN-001", "Metodologia não disponível neste painel.");
    }
}
