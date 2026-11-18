package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class ProjectGuides {
    record Field(String key, String label, String type, List<String> options) {}
    record Guide(String id, String title, String guidance, List<Field> fields, int version,
                 Map<String,String> answers, List<String> missing, boolean editable) {}
    record Catalog(String methodology, List<Guide> guides) {}
    private final DynamicProjectScope scopes;
    private final ObjectMapper json;

    ProjectGuides(DynamicProjectScope scopes, ObjectMapper json) {
        this.scopes = scopes;
        this.json = json;
    }

    Catalog get(String token, String project) {
        return scopes.read(token, project, "VIEW_GUIDES", this::catalog);
    }

    Guide save(String token, String project, String id, String match, Map<String,String> submitted) {
        return scopes.write(token, project, "SAVE_GUIDE", (c, scope) -> {
            var catalog = catalog(c, scope);
            Guide guide = catalog.guides().stream().filter(g -> g.id().equals(id) && g.editable())
                    .findFirst().orElseThrow(ProjectGuides::unavailable);
            Map<String,String> answers = normalize(guide, submitted);
            int expected = PilotCommands.expected(match);
            if (expected == guide.version() - 1 && answers.equals(guide.answers())
                    && Boolean.TRUE.equals(first(c, "SELECT actor_id = ? FROM app_technical_guide_revision WHERE project_id=? AND guide_id=? AND version=?",
                    r -> r.getBoolean(1), scope.actor().id(), project, id, guide.version()))) return guide;
            PilotCommands.requireVersion(expected, guide.version());
            var inputs = new TreeMap<String,Integer>();
            if ("quantification".equals(id)) {
                for (Guide item : catalog.guides()) {
                    if (item.version() > 0 && Set.of("applicability","baseline","additionality","monitoring").contains(item.id())) {
                        inputs.put(item.id(), item.version());
                    }
                }
            }
            if (answers.equals(guide.answers())) {
                if (!"quantification".equals(id)) return guide;
                var pinned = query(c, """
                        SELECT input_id,input_version FROM app_quantification_guide_input
                        WHERE project_id=? AND version=?
                        """, r -> Map.entry(r.getString(1),r.getInt(2)),project,expected);
                if (inputs.entrySet().equals(Set.copyOf(pinned))) return guide;
            }
            update(c, """
                    INSERT INTO app_technical_guide_revision(project_id,organization_id,environment,guide_id,version,actor_id,answers)
                    VALUES(?,?,?,?,?,?,?::jsonb)
                    """, project, scope.organization(), scope.environment(), id, expected+1, scope.actor().id(), json.valueToTree(answers).toString());
            for (var input : inputs.entrySet()) {
                update(c, """
                        INSERT INTO app_quantification_guide_input(project_id,guide_id,version,input_id,input_version)
                        VALUES (?,?,?,?,?)
                        """, project, id, expected + 1, input.getKey(), input.getValue());
            }
            return view(guide.id(), guide.title(), guide.guidance(), guide.fields(), expected+1, answers, true);
        });
    }

    Guide revision(String token, String project, String id, int version) {
        return scopes.read(token, project, "VIEW_GUIDES", (c, scope) -> {
            Guide guide = catalog(c, scope).guides().stream().filter(g -> g.id().equals(id)).findFirst()
                    .orElseThrow(ProjectGuides::unavailable);
            String data = first(c, """
                    SELECT answers::text FROM app_technical_guide_revision WHERE project_id=? AND guide_id=? AND version=?
                    """, r -> r.getString(1), project, id, version);
            if (data == null) throw unavailable();
            return view(id, guide.title(), guide.guidance(), guide.fields(), version,
                    read(data, new TypeReference<>() {}), false);
        });
    }

    List<Guide> quantificationInputs(String token, String project, int version) {
        return scopes.read(token, project, "VIEW_GUIDES", (c, scope) -> {
            var visible = catalog(c, scope).guides();
            if (visible.stream().noneMatch(g -> "quantification".equals(g.id()) && version > 0 && version <= g.version())) {
                throw unavailable();
            }
            var sources = visible.stream().collect(java.util.stream.Collectors.toMap(Guide::id, g -> g));
            return query(c, """
                    SELECT i.input_id,i.input_version,r.answers::text FROM app_quantification_guide_input i
                    JOIN app_technical_guide_revision r ON r.project_id=i.project_id AND r.guide_id=i.input_id
                        AND r.version=i.input_version WHERE i.project_id=? AND i.version=? ORDER BY i.input_id
                    """, r -> {
                        Guide source = sources.get(r.getString("input_id"));
                        if (source == null) return null;
                        return view(source.id(),source.title(),source.guidance(),source.fields(),r.getInt("input_version"),
                                read(r.getString("answers"), new TypeReference<>() {}),false);
                    },project,version).stream().filter(Objects::nonNull).toList();
        });
    }

    private static PilotProblem unavailable() {
        return new PilotProblem(404,"GUIA_INDISPONIVEL","guide","CF-IMP-017","Guia indisponível.");
    }

    Catalog catalog(Connection c, DynamicProjectScope.Context scope) throws SQLException {
        String candidate = ProjectTriageWizard.savedCandidate(c, scope.id());
        if (candidate == null) candidate = first(c, """
                SELECT payload->>'candidateMethodology' FROM app_project_intake_draft_revision
                WHERE project_id=? ORDER BY version DESC LIMIT 1
                """, r -> r.getString(1), scope.id());
        String method = ProjectJourney.profile(candidate).id();
        List<Guide> guides = query(c, """
                SELECT g.*, r.version, r.answers FROM app_technical_guide g
                LEFT JOIN LATERAL (SELECT version,answers FROM app_technical_guide_revision
                    WHERE project_id=? AND guide_id=g.id ORDER BY version DESC LIMIT 1) r ON true
                WHERE methodology=? ORDER BY position
                """, r -> {
                    String activity = r.getString("activity");
                    if (!scope.owner() && !scope.actions().contains("GUIDE_" + activity)) return null;
                    List<Field> fields = read(r.getString("fields"), new TypeReference<>() {});
                    String data = r.getString("answers");
                    Map<String,String> answers = data == null ? Map.of() : read(data,new TypeReference<>() {});
                    return view(r.getString("id"),r.getString("title"),r.getString("guidance"),fields,
                            r.getInt("version"), answers,scope.owner() || scope.actions().contains("SAVE_GUIDE"));
                },scope.id(),method);
        return new Catalog(method,guides.stream().filter(Objects::nonNull).toList());
    }

    static List<String> supportedActivities(Connection c) throws SQLException {
        return query(c, """
                SELECT DISTINCT activity FROM app_technical_guide WHERE activity <> 'OWNER'
                UNION SELECT 'DOCUMENTATION' ORDER BY 1
                """, r -> r.getString(1));
    }

    private static Guide view(String id,String title,String guidance,List<Field> fields,int version,
                              Map<String,String> answers,boolean editable) {
        return new Guide(id,title,guidance,fields,version,answers,
                fields.stream().filter(f -> !"justification".equals(f.key()) || "Não aplicável".equals(answers.get("incidence")))
                        .filter(f -> !answers.containsKey(f.key())).map(Field::label).toList(),editable);
    }

    private <T> T read(String data, TypeReference<T> type) {
        try { return json.readValue(data,type); }
        catch (java.io.IOException e) { throw new IllegalStateException("Guia inválido", e); }
    }

    private static Map<String,String> normalize(Guide guide, Map<String,String> submitted) {
        if (submitted == null || submitted.size() > guide.fields().size()) throw invalid();
        Map<String,String> answers = new TreeMap<>();
        for (var pair : submitted.entrySet()) {
            Field field = guide.fields().stream().filter(f -> f.key().equals(pair.getKey())).findFirst().orElseThrow(ProjectGuides::invalid);
            if (pair.getValue() == null) continue;
            String value = pair.getValue().strip();
            if (value.isEmpty()) continue;
            if (value.length() > 1600 || value.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\t')) throw invalid();
            if ("select".equals(field.type()) && !field.options().contains(value)) throw invalid();
            if ("date".equals(field.type())) {
                try { LocalDate.parse(value); } catch (java.time.DateTimeException e) { throw invalid(); }
            }
            answers.put(field.key(),value);
        }
        if ("Não aplicável".equals(answers.get("incidence")) && !answers.containsKey("justification")) throw invalid();
        if (answers.containsKey("periodStart") && answers.containsKey("periodEnd") &&
                LocalDate.parse(answers.get("periodStart")).isAfter(LocalDate.parse(answers.get("periodEnd")))) throw invalid();
        return Map.copyOf(answers);
    }

    private static PilotProblem invalid() {
        return new PilotProblem(400,"RESPOSTA_INVALIDA","answers","CF-IMP-017","Confira os campos e justifique qualquer indicação de não aplicabilidade.");
    }
}
