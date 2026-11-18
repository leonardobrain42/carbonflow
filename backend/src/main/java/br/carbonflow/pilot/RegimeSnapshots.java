package br.carbonflow.pilot;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.ContentLicensePolicy.Operation.REFERENCE_REQUIREMENT;
import static br.carbonflow.pilot.ContentLicensePolicy.Purpose.REQUIREMENT_CATALOG;
import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

@ApplicationScoped
class RegimeSnapshots {
    record Payload(RequirementResolver.Input input, RequirementResolver.Resolution resolution) {}
    record Snapshot(UUID id, String sha256, OffsetDateTime createdAt, Payload payload) {}

    private final DynamicProjectScope scopes;
    private final RequirementResolver resolver;
    private final ContentLicensePolicy licenses;
    private final ObjectMapper json;

    RegimeSnapshots(DynamicProjectScope scopes, RequirementResolver resolver, ContentLicensePolicy licenses, ObjectMapper json) {
        this.scopes = scopes;
        this.resolver = resolver;
        this.licenses = licenses;
        this.json = json;
    }

    RequirementResolver.Resolution preview(String cookie, String project, RequirementResolver.Input input) {
        return scopes.read(cookie,project,"VIEW_GUIDES",(connection,scope) -> resolver.resolve(connection,scope,input));
    }

    Snapshot save(String cookie, String project, RequirementResolver.Input input) {
        return scopes.write(cookie,project,"SAVE_GUIDE",(connection,scope) -> {
            var resolution = resolver.resolve(connection,scope,input);
            if (resolution.state() == RequirementResolver.State.SOURCE_USAGE_BLOCKED) throw blocked();
            if (resolution.state() != RequirementResolver.State.RESOLVED_SYNTHETIC) {
                throw new PilotProblem(422,"UNKNOWN_REVIEW_REQUIRED","regime","ECA-002",resolution.reason());
            }
            var payload = new Payload(input,resolution);
            JsonNode tree = json.valueToTree(payload);
            String canonical = tree.toString();
            String digest = FixtureCatalog.sha256(canonical.getBytes(StandardCharsets.UTF_8));
            update(connection, """
                    INSERT INTO app_regime_snapshot(id,project_id,organization_id,environment,actor_id,pack_id,payload,sha256)
                    VALUES (?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (project_id,actor_id,sha256) DO NOTHING
                    """,UUID.randomUUID(),project,scope.organization(),scope.environment(),scope.actor().id(),
                    resolution.packId(),canonical,digest);
            return first(connection, """
                    SELECT id,sha256,created_at,payload::text FROM app_regime_snapshot
                    WHERE project_id=? AND actor_id=? AND sha256=?
                    """,row -> snapshot(row.getObject(1,UUID.class),row.getString(2),row.getObject(3,OffsetDateTime.class),
                    row.getString(4)),project,scope.actor().id(),digest);
        });
    }

    Snapshot get(String cookie, String project, UUID id) {
        return scopes.read(cookie,project,"VIEW_GUIDES",(connection,scope) -> {
            var snapshot = first(connection, """
                    SELECT id,sha256,created_at,payload::text FROM app_regime_snapshot
                    WHERE project_id=? AND actor_id=? AND id=?
                    """,row -> snapshot(row.getObject(1,UUID.class),row.getString(2),row.getObject(3,OffsetDateTime.class),
                    row.getString(4)),project,scope.actor().id(),id);
            if (snapshot == null) {
                throw new PilotProblem(404,"RECURSO_NAO_VISIVEL","snapshot","ECA-002","Versão indisponível.");
            }
            for (var result : snapshot.payload().resolution().rules()) {
                if (licenses.authorize(connection,scope,result.rule().source(),REQUIREMENT_CATALOG,REFERENCE_REQUIREMENT,null) == null) {
                    throw blocked();
                }
            }
            return snapshot;
        });
    }

    private Snapshot snapshot(UUID id, String digest, OffsetDateTime createdAt, String body) {
        try {
            return new Snapshot(id,digest,createdAt,json.readValue(body,Payload.class));
        } catch (JsonProcessingException problem) {
            throw new IllegalStateException("Snapshot de regime inválido",problem);
        }
    }

    private static PilotProblem blocked() {
        return new PilotProblem(423,"SOURCE_USAGE_BLOCKED","regime","ECA-002",
                "Uma fonte do regime está sem autorização de uso vigente.");
    }
}
