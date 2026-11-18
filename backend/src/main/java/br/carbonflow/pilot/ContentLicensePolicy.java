package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;

@ApplicationScoped
class ContentLicensePolicy {
    enum Operation {
        REFERENCE_REQUIREMENT, STORE_ORIGINAL, EXTRACT_TEXT, INDEX_SEARCH, CREATE_EMBEDDINGS,
        RAG_RETRIEVE, MODEL_INFERENCE, SHOW_EXCERPTS, DRAFT_CONTENT, EXPORT_DERIVATIVES
    }
    enum Purpose { REQUIREMENT_CATALOG, CONTEXTUAL_HELP, AUTHORIZED_PROCESSING }
    record SourceRef(String id, String edition) {}
    record Authorization(UUID licenseId, int decisionVersion) {}
    record Metadata(String id, String edition, String category, String title, String rightsHolder,
                    String officialUri, String origin, boolean requirementReferenceAvailable) {}

    private final boolean normativeAiDisabled;

    ContentLicensePolicy(@ConfigProperty(name = "carbonflow.normative-ai.disabled", defaultValue = "true") boolean disabled) {
        this.normativeAiDisabled = disabled;
    }

    boolean normativeAiDisabled() {
        return normativeAiDisabled;
    }

    Authorization authorize(Connection connection, DynamicProjectScope.Context scope, SourceRef source,
                            Purpose purpose, Operation operation, String provider) throws SQLException {
        if (normativeAiDisabled && switch (operation) {
            case CREATE_EMBEDDINGS, RAG_RETRIEVE, MODEL_INFERENCE, DRAFT_CONTENT -> true;
            default -> false;
        }) return null;

        return first(connection, """
                SELECT license.id, decision.version
                FROM app_source_version source
                JOIN app_content_license license ON license.source_id = source.source_id AND license.edition = source.edition
                JOIN LATERAL (
                    SELECT version, outcome, decided_by, decided_at FROM app_content_license_decision
                    WHERE license_id = license.id ORDER BY version DESC LIMIT 1
                ) decision ON true
                JOIN app_content_license_capability capability ON capability.license_id = license.id
                    AND capability.decision_version = decision.version
                WHERE source.source_id = ? AND source.edition = ?
                    AND (source.project_id IS NULL OR source.project_id = ?)
                    AND (license.organization_id IS NULL OR license.organization_id = ?)
                    AND (license.project_id IS NULL OR license.project_id = ?)
                    AND (license.actor_id IS NULL OR license.actor_id = ?)
                    AND license.environment = ? AND license.purpose = ?
                    AND license.provider IS NOT DISTINCT FROM ?::text
                    AND capability.operation = ?
                    AND decision.decided_at <= statement_timestamp()
                    AND license.starts_at <= statement_timestamp()
                    AND (license.ends_at IS NULL OR license.ends_at > statement_timestamp())
                    AND (
                        (decision.outcome = 'SYNTHETIC_FIXTURE' AND source.origin = 'OWN_SYNTHETIC'
                            AND license.submitted_by IS NULL AND capability.operation = 'REFERENCE_REQUIREMENT')
                        OR (decision.outcome = 'APPROVED' AND license.proof_sha256 IS NOT NULL
                            AND license.submitted_by <> decision.decided_by
                            AND license.organization_id IS NOT NULL
                            AND (source.project_id IS NULL OR license.project_id = source.project_id))
                    )
                ORDER BY license.id LIMIT 1
                """, row -> new Authorization(row.getObject(1, UUID.class), row.getInt(2)),
                source.id(), source.edition(), scope.id(), scope.organization(), scope.id(), scope.actor().id(),
                scope.environment(), purpose.name(), provider, operation.name());
    }

    List<Metadata> metadata(Connection connection, DynamicProjectScope.Context scope, ProjectJourney.Profile method) throws SQLException {
        List<Metadata> sources = query(connection, """
                SELECT source_id,edition,category,title,rights_holder,official_uri,origin
                FROM app_source_version WHERE project_id IS NULL AND (
                    origin = 'OWN_SYNTHETIC' OR (source_id = ? AND edition = ?))
                ORDER BY origin,source_id,edition
                """, row -> new Metadata(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                        row.getString(5), row.getString(6), row.getString(7), false), method.candidate(), method.edition());
        var result = new java.util.ArrayList<Metadata>();
        for (Metadata source : sources) {
            boolean allowed = authorize(connection, scope, new SourceRef(source.id(), source.edition()),
                    Purpose.REQUIREMENT_CATALOG, Operation.REFERENCE_REQUIREMENT, null) != null;
            result.add(new Metadata(source.id(), source.edition(), source.category(), source.title(),
                    source.rightsHolder(), source.officialUri(), source.origin(), allowed));
        }
        return List.copyOf(result);
    }
}
