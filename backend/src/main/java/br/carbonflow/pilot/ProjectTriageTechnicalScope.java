package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;

import java.sql.Connection;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * D16: the triage diagnosis reads scope answers that left the triage from the technical declaration that now
 * owns them. The latest open-applicability revision wins over an older triage answer; without a declaration the
 * older answer (if any) is still used, and without either the item stays unconfirmed. Only exact equivalences
 * are mapped, an "a confirmar" declaration stays unconfirmed, and the stored triage answers are never changed.
 */
final class ProjectTriageTechnicalScope {
    private record Declaration(Boolean unrestrictedAccess, Boolean restrictedToIdentified, String electricitySource,
                               Boolean associatedInfrastructure, String rightsParty) {}

    private static final String UNKNOWN = "DESCONHECIDO";

    private ProjectTriageTechnicalScope() {}

    static ObjectNode effective(Connection connection, String project, JsonNode answers, ObjectMapper mapper)
            throws SQLException {
        ObjectNode out = answers != null && answers.isObject() ? ((ObjectNode) answers).deepCopy() : mapper.createObjectNode();
        Declaration declaration = first(connection, """
                SELECT unrestricted_bev_access, restricted_to_identified_vehicles, electricity_source,
                       associated_infrastructure, rights_party_code
                FROM app_project_open_applicability_revision WHERE project_id=?
                ORDER BY version DESC LIMIT 1
                """, r -> new Declaration((Boolean) r.getObject(1), (Boolean) r.getObject(2), r.getString(3),
                (Boolean) r.getObject(4), r.getString(5)), project);
        if (declaration == null) return out;
        ObjectNode scope = step(out, "3");
        if (declaration.restrictedToIdentified() != null)
            scope.put("network", declaration.restrictedToIdentified() ? "CLOSED" : "OPEN");
        if (declaration.unrestrictedAccess() != null)
            scope.put("access", declaration.unrestrictedAccess() ? "PUBLIC" : "PRIVATE");
        if (declaration.electricitySource() != null)
            scope.put("electricitySource", "UNKNOWN".equals(declaration.electricitySource())
                    ? UNKNOWN : declaration.electricitySource());
        if (declaration.associatedInfrastructure() != null)
            scope.put("associatedInfrastructure", declaration.associatedInfrastructure() ? "YES" : "NO");
        if (declaration.rightsParty() != null && !declaration.rightsParty().isBlank() && out.path("4").isObject()) {
            // Direitos do projeto names a holder: an older "not applicable" answer no longer drives the diagnosis.
            ((ObjectNode) out.get("4")).remove(java.util.List.of("rightsStatus", "rightsJustification"));
        }
        return out;
    }

    private static ObjectNode step(ObjectNode answers, String key) {
        JsonNode node = answers.get(key);
        return node instanceof ObjectNode object ? object : answers.putObject(key);
    }
}
