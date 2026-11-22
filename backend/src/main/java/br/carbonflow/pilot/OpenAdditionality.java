package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

/** Read model: additionality stays a human opinion even when the guide is filled. */
@ApplicationScoped
class OpenAdditionality {
    private final DynamicProjectScope scope;
    private final OpenApplicability applicability;
    private final ObjectMapper json;

    OpenAdditionality(DynamicProjectScope scope, OpenApplicability applicability, ObjectMapper json) {
        this.scope = scope;
        this.applicability = applicability;
        this.json = json;
    }

    OpenAdditionalityOpinion.Opinion get(String token, String project) {
        return scope.read(token, project, "VIEW_OPEN_APPLICABILITY", (connection, context) -> {
            var readiness = applicability.readinessOf(connection, context.id());
            String raw = first(connection, """
                SELECT answers::text FROM app_technical_guide_revision
                WHERE project_id=? AND guide_id='additionality'
                ORDER BY version DESC LIMIT 1
                """, r -> r.getString(1), context.id());
            Map<String, String> answers;
            try {
                answers = raw == null ? Map.of() : json.readValue(raw, new TypeReference<>() {});
            } catch (com.fasterxml.jackson.core.JacksonException ex) {
                throw new IllegalStateException("Respostas do guia ilegíveis.");
            }
            return OpenAdditionalityOpinion.assess(readiness, answers);
        });
    }
}
