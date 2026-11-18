package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Five-screen intake questionnaire. Progress means "answers collected", never
 * methodology eligibility, E8 readiness, VVB approval or carbon credit issuance.
 */
@ApplicationScoped
class ProjectTriageWizard {
    record Mutation(JsonNode answers, int nextStep) {}
    record Cursor(int nextStep) {}
    record StepSummary(int number, String title, String status, List<String> missingFields) {}
    record View(String projectId, String environment, int version, int currentStep,
                String analysisState, String exampleKind, JsonNode answers,
                List<StepSummary> stages, List<String> pending,
                String nextAction, ProjectJourney.Profile journey, List<ProjectJourney.Option> methodologies,
                ProjectJourney.Guidance guidance, ProjectTriageDiagnosis.Result diagnosis) {}
    private record Snapshot(int version, int currentStep, ObjectNode answers) {}
    private record Receipt(String action, String projectId, byte[] fingerprint, String response) {}

    private static final List<String> TITLES = List.of(
            "Planejamento", "Atividade", "Frota e recarga",
            "Local, dados e direitos", "Resumo da triagem");
    private static final List<Set<String>> FIELDS = List.of(
            Set.of("objective", "activityType", "methodologyCandidate"),
            Set.of("activityDescription", "baselinePractice", "expectedBenefit", "installsChargers"),
            journeyFields(),
            Set.of("country", "state", "locality", "referenceDate", "evidenceStatus",
                   "rightsStatus", "rightsJustification", "investmentCommitted", "carbonConsideredBeforeInvestment")
    );
    private static final List<List<String>> REQUIRED = List.of(
            List.of("objective", "activityType"),
            List.of("activityDescription", "baselinePractice"),
            List.of(),
            List.of("country", "state", "locality", "referenceDate", "evidenceStatus", "rightsStatus")
    );

    private final DynamicProjectScope scope;
    private final ObjectMapper mapper;

    ProjectTriageWizard(DynamicProjectScope scope, ObjectMapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

    View get(String cookie, String projectId) {
        return scope.read(cookie, projectId, "VIEW_TRIAGE", (connection, context) ->
                view(context, latestOrEmpty(connection, projectId)));
    }

    JsonNode save(String cookie, String projectId, int step, String keyHeader, String ifMatch, Mutation input) {
        if (step < 1 || step > 4) throw invalid("step");
        if (input == null || input.answers() == null || !input.answers().isObject()) throw invalid("answers");
        int destination = destination(input.nextStep());
        ObjectNode normalized = normalize(step, input.answers());
        int expected = PilotCommands.expected(ifMatch);
        String key = PilotCommands.key(keyHeader);
        ObjectNode request = mapper.createObjectNode();
        request.put("step", step);
        request.put("expected", expected);
        request.put("nextStep", destination);
        request.set("answers", normalized);
        byte[] digest = fingerprint(projectId, "SAVE_STEP", request);

        return scope.write(cookie, projectId, "SAVE_TRIAGE", (connection, actor) -> {
            serialise(connection, actor.actor().id(), key);
            Receipt receipt = receipt(connection, actor.actor().id(), key);
            if (receipt != null) return replay(receipt, "SAVE_STEP", projectId, digest);
            Snapshot previous = latestOrEmpty(connection, projectId);
            PilotCommands.requireVersion(expected, previous.version());
            ObjectNode answers = previous.answers().deepCopy();
            validateJourneyStep(step, normalized, answers);
            answers.set(Integer.toString(step), normalized);
            Snapshot next = new Snapshot(expected + 1, destination, answers);
            saveRevision(connection, actor, next);
            JsonNode response = mapper.valueToTree(view(actor, next));
            remember(connection, actor, key, "SAVE_STEP", digest, response);
            return response;
        });
    }

    JsonNode move(String cookie, String projectId, String keyHeader, String ifMatch, Cursor input) {
        if (input == null) throw invalid("nextStep");
        int destination = destination(input.nextStep());
        int expected = PilotCommands.expected(ifMatch);
        String key = PilotCommands.key(keyHeader);
        ObjectNode request = mapper.createObjectNode().put("expected", expected).put("nextStep", destination);
        byte[] digest = fingerprint(projectId, "MOVE_CURSOR", request);

        return scope.write(cookie, projectId, "MOVE_TRIAGE", (connection, actor) -> {
            serialise(connection, actor.actor().id(), key);
            Receipt receipt = receipt(connection, actor.actor().id(), key);
            if (receipt != null) return replay(receipt, "MOVE_CURSOR", projectId, digest);
            Snapshot previous = latestOrEmpty(connection, projectId);
            PilotCommands.requireVersion(expected, previous.version());
            Snapshot next = new Snapshot(expected + 1, destination, previous.answers().deepCopy());
            saveRevision(connection, actor, next);
            JsonNode response = mapper.valueToTree(view(actor, next));
            remember(connection, actor, key, "MOVE_CURSOR", digest, response);
            return response;
        });
    }

    JsonNode example(String cookie, String projectId, String keyHeader, String ifMatch) {
        int expected = PilotCommands.expected(ifMatch);
        String key = PilotCommands.key(keyHeader);
        byte[] digest = fingerprint(projectId, "APPLY_EXAMPLE",
                mapper.createObjectNode().put("expected", expected).put("sample", "25-BEV-BRASILIA-2027"));

        return scope.write(cookie, projectId, "APPLY_TRIAGE_EXAMPLE", (connection, actor) -> {
            serialise(connection, actor.actor().id(), key);
            Receipt receipt = receipt(connection, actor.actor().id(), key);
            if (receipt != null) return replay(receipt, "APPLY_EXAMPLE", projectId, digest);
            Snapshot previous = latestOrEmpty(connection, projectId);
            PilotCommands.requireVersion(expected, previous.version());
            // These are new illustrative user answers, not the PIL-SYN-BR-001 fixture or E8 evidence.
            ObjectNode answers = mapper.createObjectNode();
            answers.set("1", normalizeSample(1, Map.of(
                    "objective", "Estudar redução potencial pela eletrificação de frota",
                    "activityType", "MOBILIDADE_ELETRICA", "methodologyCandidate", "VM0038")));
            answers.set("2", normalizeSample(2, Map.of(
                    "activityDescription", "Instalação planejada de recarga controlada para frota BEV",
                    "installsChargers", "YES",
                    "baselinePractice", "Veículos de combustão comparáveis ainda não documentados",
                    "expectedBenefit", "Redução potencial de emissões, ainda não quantificada")));
            answers.set("3", normalizeSample(3, Map.of(
                    "vehicleCount", 25, "vehicleClass", "LDV", "propulsion", "BEV", "chargerType", "DCFC",
                    "chargerCount", 1, "electricitySource", "DESCONHECIDA")));
            answers.set("4", normalizeSample(4, Map.of(
                    "country", "BR", "state", "DF", "locality", "Brasília",
                    "referenceDate", "2027-09-27",
                    "evidenceStatus", "DESCONHECIDO", "rightsStatus", "DESCONHECIDO")));
            answers.put("_example", "25_BEV_BRASILIA_DF_2027_09_27");
            Snapshot next = new Snapshot(expected + 1, 5, answers);
            saveRevision(connection, actor, next);
            JsonNode response = mapper.valueToTree(view(actor, next));
            remember(connection, actor, key, "APPLY_EXAMPLE", digest, response);
            return response;
        });
    }

    private static int destination(int step) {
        if (step < 1 || step > 5) throw invalid("nextStep");
        return step;
    }

    private ObjectNode normalizeSample(int step, Map<String, ?> values) {
        JsonNode sample = mapper.valueToTree(values);
        return normalize(step, sample);
    }

    private ObjectNode normalize(int step, JsonNode input) {
        if (input == null || !input.isObject()) throw invalid("answers");
        ObjectNode result = mapper.createObjectNode();
        var names = input.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!FIELDS.get(step - 1).contains(name)) throw invalid(name);
            JsonNode value = input.get(name);
            if (value == null || value.isNull()) {
                result.putNull(name);
            } else if ("vehicleCount".equals(name) || "chargerCount".equals(name)) {
                if (!value.isIntegralNumber() || !value.canConvertToInt()
                        || value.asInt() < 0 || value.asInt() > 100_000) throw invalid(name);
                result.put(name, value.asInt());
            } else if (value.isTextual()) {
                String content = value.asText().strip();
                if (content.codePointCount(0, content.length()) > ("activityDescription".equals(name)
                        || "baselinePractice".equals(name) || "expectedBenefit".equals(name) ? 800 : 400)
                        || content.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) throw invalid(name);
                if (content.isBlank()) {
                    result.putNull(name);
                    continue;
                }
                if ("country".equals(name)) {
                    content = content.toUpperCase(java.util.Locale.ROOT);
                    if (!content.matches("[A-Z]{2}")) throw invalid(name);
                }
                if ("referenceDate".equals(name)) {
                    try { LocalDate.parse(content); }
                    catch (DateTimeParseException error) { throw invalid(name); }
                }
                if ("activityType".equals(name) && !Set.of("MOBILIDADE_ELETRICA", "RESIDUOS",
                        "ENERGIA", "AGRICULTURA", "OUTRO", "DESCONHECIDO").contains(content)) throw invalid(name);
                if ("evidenceStatus".equals(name) && !Set.of("DESCONHECIDO", "EM_PREPARACAO",
                        "DISPONIVEL_DECLARADO").contains(content)) throw invalid(name);
                if ("rightsStatus".equals(name) && !Set.of("DESCONHECIDO", "DECLARADO",
                        "NAO_APLICAVEL_JUSTIFICADO").contains(content)) throw invalid(name);
                if (Set.of("investmentCommitted", "carbonConsideredBeforeInvestment").contains(name)
                        && !Set.of("YES", "NO", "DESCONHECIDO").contains(content)) throw invalid(name);
                result.put(name, content);
            } else {
                throw invalid(name);
            }
        }
        if (step == 4 && "NAO_APLICAVEL_JUSTIFICADO".equals(result.path("rightsStatus").asText())
                && result.path("rightsJustification").asText("").isBlank()) throw invalid("rightsJustification");
        return result;
    }

    private static Set<String> journeyFields() {
        var result = new java.util.HashSet<String>();
        result.add("notApplicableReason"); // Read and accept the previous generic step's explicit justification.
        for (var profile : List.of(ProjectJourney.GENERAL, ProjectJourney.VM0038)) {
            profile.questions().forEach(question -> result.add(question.key()));
        }
        return Set.copyOf(result);
    }

    private static void validateJourneyStep(int step, ObjectNode submitted, ObjectNode answers) {
        var journey = ProjectJourney.profile(answers);
        if (step == 2 && submitted.hasNonNull("installsChargers") &&
                (journey != ProjectJourney.VM0038 || !Set.of("YES", "NO", "DESCONHECIDO")
                        .contains(submitted.path("installsChargers").asText()))) {
            throw invalid("installsChargers");
        }
        if (step != 3) return;
        JsonNode previous = answers.path("3");
        submitted.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isNull() || value.equals(previous.path(key))) return;
            if (journey == ProjectJourney.GENERAL && "notApplicableReason".equals(key)) return;
            var question = journey.questions().stream().filter(item -> item.key().equals(key)).findFirst()
                    .orElseThrow(() -> invalid(key));
            if ("choice".equals(question.type()) &&
                    question.options().stream().noneMatch(option -> option.value().equals(value.asText()))) {
                throw invalid(key);
            }
        });
    }

    private View view(DynamicProjectScope.Context context, Snapshot snapshot) {
        List<StepSummary> stages = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        var answers = snapshot.answers();
        var journey = ProjectJourney.profile(answers);
        for (int i = 1; i <= 4; i++) {
            JsonNode step = answers.path(Integer.toString(i));
            List<String> required = REQUIRED.get(i - 1);
            if (i == 2 && journey == ProjectJourney.VM0038) {
                required = List.of("installsChargers", "activityDescription", "baselinePractice");
            }
            if (i == 3) required = journey == ProjectJourney.VM0038
                    ? journey.questions().stream().map(ProjectJourney.Question::key).toList() : List.of();
            if (i == 3 && journey == ProjectJourney.GENERAL) {
                String category = answers.path("1").path("activityType").asText();
                if (!category.isBlank() && !Set.of("MOBILIDADE_ELETRICA", "DESCONHECIDO").contains(category)) {
                    required = List.of("scopeNotes");
                }
            }
            boolean previousJustification = i == 3 && journey == ProjectJourney.GENERAL
                    && ProjectJourney.known(step.path("notApplicableReason"));
            List<String> missing = previousJustification ? List.of() : required.stream()
                    .filter(field -> !ProjectJourney.known(step.path(field))).toList();
            String state = previousJustification ? "NAO_APLICAVEL_JUSTIFICADO"
                    : step.isMissingNode() || step.size() == 0 ? "NAO_INICIADO"
                    : missing.isEmpty() ? "PREENCHIDO" : "PENDENTE";
            for (String field : missing) pending.add("Etapa " + i + ": " + field);
            String title = switch (i) {
                case 2 -> journey.activitiesTitle();
                case 3 -> journey.detailsTitle();
                default -> TITLES.get(i - 1);
            };
            stages.add(new StepSummary(i, title, state, missing));
        }
        stages.add(new StepSummary(5, TITLES.get(4), "RESUMO_SEM_PARECER", List.of()));
        var guidance = ProjectJourney.guidance(answers);
        return new View(context.id(), context.environment(), snapshot.version(), snapshot.currentStep(),
                "NAO_AVALIADO", answers.path("_example").asText(null), answers.deepCopy(), List.copyOf(stages),
                List.copyOf(pending), snapshot.currentStep() == 5
                    ? "Revise as respostas e organize na preparação técnica o que precisa ser comprovado."
                    : "Salve as respostas e deixe em aberto o que ainda precisa confirmar.",
                journey, ProjectJourney.METHODOLOGIES, guidance, ProjectTriageDiagnosis.evaluate(answers, guidance));
    }

    static String savedCandidate(Connection connection, String projectId) throws SQLException {
        return ProjectJourney.candidate(latestOrEmpty(connection, projectId).answers());
    }

    private static Snapshot latest(Connection connection, String project) throws SQLException {
        return first(connection, """
                SELECT version, current_step, answers::text AS answers
                FROM app_project_triage_revision WHERE project_id = ?
                ORDER BY version DESC LIMIT 1
                """, row -> new Snapshot(row.getInt("version"), row.getInt("current_step"),
                decode(row.getString("answers"))), project);
    }

    private static Snapshot latestOrEmpty(Connection connection, String project) throws SQLException {
        Snapshot current = latest(connection, project);
        return current == null ? new Snapshot(0, 1, JSON.createObjectNode()) : current;
    }

    private static void saveRevision(Connection connection, DynamicProjectScope.Context actor,
                                     Snapshot revision) throws SQLException {
        update(connection, """
                INSERT INTO app_project_triage_revision
                  (project_id, organization_id, environment, version, actor_id, current_step, answers)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, actor.id(), actor.organization(), actor.environment(), revision.version(),
                actor.actor().id(), revision.currentStep(), revision.answers().toString());
    }

    private static void serialise(Connection connection, UUID actor, String key) throws SQLException {
        first(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> true,
                "triage:" + actor + ":" + key);
    }

    private static Receipt receipt(Connection connection, UUID actor, String key) throws SQLException {
        return first(connection, """
                SELECT action, project_id, fingerprint, response::text AS response
                FROM app_project_triage_command WHERE actor_id = ? AND command_key = ?
                """, row -> new Receipt(row.getString("action"), row.getString("project_id"),
                row.getBytes("fingerprint"), row.getString("response")), actor, key);
    }

    private JsonNode replay(Receipt receipt, String action, String project, byte[] fingerprint) {
        if (!receipt.action().equals(action) || !receipt.projectId().equals(project)
                || !MessageDigest.isEqual(receipt.fingerprint(), fingerprint)) {
            throw new PilotProblem(409, "CHAVE_REUSADA", "Idempotency-Key", "CF-IMP-009",
                    "Esta chave já identifica outro comando.");
        }
        try { return mapper.readTree(receipt.response()); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Recibo de triagem inválido", error); }
    }

    private static void remember(Connection connection, DynamicProjectScope.Context actor, String key,
                                 String action, byte[] fingerprint, JsonNode response) throws SQLException {
        update(connection, """
                INSERT INTO app_project_triage_command
                  (actor_id, command_key, project_id, organization_id, environment, action, fingerprint, response)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, actor.actor().id(), key, actor.id(), actor.organization(), actor.environment(),
                action, fingerprint, response.toString());
    }

    private static byte[] fingerprint(String project, String action, JsonNode request) {
        return AccessSecrets.digest(project + "|" + action + "|" + request.toString());
    }

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static ObjectNode decode(String json) {
        try { return (ObjectNode) JSON.readTree(json); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Triagem armazenada inválida", error); }
    }

    private static PilotProblem invalid(String field) {
        return new PilotProblem(400, "PEDIDO_INVALIDO", field, "CF-IMP-009",
                "Revise os campos da etapa. Valores desconhecidos podem ficar em branco.");
    }
}
