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
                MethodologyPackages.Catalog catalog, MethodologyRegistry.CandidateStatus candidateStatus,
                ProjectJourney.Guidance guidance, ProjectTriageDiagnosis.Result diagnosis,
                List<RetiredAnswer> retiredAnswers, MethodologyPin.Pin methodologyPin,
                List<MethodologyCapabilities.Purpose> capabilities) {}
    /** D16: an older answer that the triage no longer asks; shown read-only with the screen that owns it now. */
    record RetiredAnswer(int step, String key, String label, JsonNode value, String valueLabel,
                         String section, String sectionTitle) {}
    private record Snapshot(int version, int currentStep, ObjectNode answers) {}
    private record Receipt(String action, String projectId, byte[] fingerprint, String response) {}

    private static final List<String> TITLES = List.of(
            "Objetivo e metodologia", "Atividade", "Recorte inicial",
            "Local", "Resumo da triagem");
    private static final List<Set<String>> FIELDS = List.of(
            Set.of("objective", "activityType", "methodologyCandidate"),
            activityFields(),
            journeyFields(),
            Set.of("country", "state", "locality", "referenceDate", "evidenceStatus",
                   "rightsStatus", "rightsJustification", "investmentCommitted", "carbonConsideredBeforeInvestment")
    );
    // D16: the triage requires only the initial hypothesis. baselinePractice and expectedBenefit stay as optional
    // notes (Q3); the formal baseline, rights, additionality and evidence belong to the technical screens.
    private static final List<List<String>> REQUIRED = List.of(
            List.of("objective", "activityType"),
            List.of("activityDescription"),
            List.of(),
            List.of("country", "state", "locality", "referenceDate")
    );

    private final DynamicProjectScope scope;
    private final ObjectMapper mapper;

    ProjectTriageWizard(DynamicProjectScope scope, ObjectMapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

    View get(String cookie, String projectId) {
        return scope.read(cookie, projectId, "VIEW_TRIAGE", (connection, context) ->
                current(connection, context));
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
            keepRetired(step, previous.answers().path(Integer.toString(step)), normalized);
            validateJourneyStep(step, normalized, answers);
            answers.set(Integer.toString(step), normalized);
            Snapshot next = new Snapshot(expected + 1, destination, answers);
            saveRevision(connection, actor, next);
            JsonNode response = mapper.valueToTree(view(connection, actor, next));
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
            JsonNode response = mapper.valueToTree(view(connection, actor, next));
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
                    "activityDescription", "Instalação planejada de recarga controlada para frota de 25 BEVs",
                    "installsChargers", "YES",
                    "baselinePractice", "Veículos de combustão comparáveis ainda não documentados",
                    "expectedBenefit", "Redução potencial de emissões, ainda não quantificada")));
            // D16: only the initial hypothesis; the technical screens collect quantities, energy, rights and evidence.
            answers.set("3", normalizeSample(3, Map.of(
                    "vehicleClass", "LDV", "propulsion", "BEV", "chargerType", "DCFC")));
            answers.set("4", normalizeSample(4, Map.of(
                    "country", "BR", "state", "DF", "locality", "Brasília",
                    "referenceDate", "2027-09-27")));
            answers.put("_example", "25_BEV_BRASILIA_DF_2027_09_27");
            Snapshot next = new Snapshot(expected + 1, 5, answers);
            saveRevision(connection, actor, next);
            JsonNode response = mapper.valueToTree(view(connection, actor, next));
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
                if ("activityType".equals(name) && !MethodologyPackages.knownActivity(content)) throw invalid(name);
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
        return result;
    }

    /**
     * D16: answers that left the triage cannot be changed here. A step saved without one keeps the previous value
     * unchanged, so no answer is lost and the canonical comparison of D15 does not see a change that nobody made.
     * Re-sending the stored value (or a blank for an answer never given) is not a change and is accepted; any other
     * value is refused with the field and the screen that owns it now, never ignored in silence.
     */
    static void keepRetired(int step, JsonNode previous, ObjectNode submitted) {
        for (var item : ProjectJourney.RETIRED_ANSWERS) {
            if (item.step() != step) continue;
            String key = item.key();
            JsonNode sent = submitted.get(key);
            JsonNode stored = previous.get(key);
            if (sent != null) {
                boolean unchanged = stored == null ? sent.isNull() : stored.equals(sent);
                if (!unchanged) throw new PilotProblem(400, "PEDIDO_INVALIDO", key, "CF-IMP-009",
                        "Esta resposta agora é registrada em " + item.sectionTitle()
                                + ". A triagem mantém a resposta anterior só para leitura.");
                if (stored == null) submitted.remove(key);
            } else if (stored != null) {
                submitted.set(key, stored.deepCopy());
            }
        }
    }

    private static List<RetiredAnswer> retiredAnswers(JsonNode answers) {
        var out = new ArrayList<RetiredAnswer>();
        for (var item : ProjectJourney.RETIRED_ANSWERS) {
            JsonNode value = answers.path(Integer.toString(item.step())).path(item.key());
            if (value.isMissingNode() || value.isNull()) continue;
            String label = item.options().stream().filter(option -> option.value().equals(value.asText()))
                    .map(ProjectJourney.Option::label).findFirst().orElse(value.asText());
            out.add(new RetiredAnswer(item.step(), item.key(), item.label(), value.deepCopy(), label,
                    item.section(), item.sectionTitle()));
        }
        return List.copyOf(out);
    }

    /** Step-2 fields: the generic notes plus the activity questions of every configured package (A03). */
    private static Set<String> activityFields() {
        var result = new java.util.HashSet<>(Set.of("activityDescription", "baselinePractice", "expectedBenefit"));
        for (var profile : ProjectJourney.profiles()) {
            profile.activityQuestions().forEach(question -> result.add(question.key()));
        }
        return Set.copyOf(result);
    }

    private static Set<String> journeyFields() {
        var result = new java.util.HashSet<String>();
        result.add("notApplicableReason"); // Read and accept the previous generic step's explicit justification.
        for (var profile : ProjectJourney.profiles()) {
            profile.questions().forEach(question -> result.add(question.key()));
        }
        result.addAll(ProjectJourney.RETIRED.get(3)); // Read so an unchanged older answer can be compared (D16).
        return Set.copyOf(result);
    }

    private static void validateJourneyStep(int step, ObjectNode submitted, ObjectNode answers) {
        var journey = ProjectJourney.profile(answers);
        String rejected = ProjectJourney.invalidAnswer(journey, step, submitted, answers.path(Integer.toString(step)));
        if (rejected != null) throw invalid(rejected);
    }

    /** Packages in code first, then registered methodologies that still have no package (a candidate with a gap). */
    static List<ProjectJourney.Option> methodologyOptions(Connection connection) throws SQLException {
        var all = new ArrayList<>(MethodologyPackages.options());
        all.addAll(MethodologyRegistry.unpackaged(connection));
        return List.copyOf(all);
    }

    private View view(Connection connection, DynamicProjectScope.Context context, Snapshot snapshot) throws SQLException {
        List<StepSummary> stages = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        var answers = snapshot.answers();
        // Diagnosis reads the technical declaration where it exists; stored answers are returned unchanged.
        var effective = ProjectTriageTechnicalScope.effective(connection, context.id(), answers, mapper);
        var journey = ProjectJourney.profile(answers);
        for (int i = 1; i <= 4; i++) {
            JsonNode step = answers.path(Integer.toString(i));
            List<String> required = ProjectJourney.required(journey, i, REQUIRED.get(i - 1), answers);
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
        var guidance = ProjectJourney.guidance(effective);
        return new View(context.id(), context.environment(), snapshot.version(), snapshot.currentStep(),
                "NAO_AVALIADO", answers.path("_example").asText(null), answers.deepCopy(), List.copyOf(stages),
                List.copyOf(pending), snapshot.currentStep() == 5
                    ? "Revise as respostas e organize na preparação técnica o que precisa ser comprovado."
                    : "Salve as respostas e deixe em aberto o que ainda precisa confirmar.",
                journey, methodologyOptions(connection), MethodologyPackages.current(),
                MethodologyRegistry.candidateStatus(connection, ProjectJourney.candidate(answers)), guidance, ProjectTriageDiagnosis.evaluate(effective, guidance),
                retiredAnswers(answers), MethodologyPin.current(connection, context.id()),
                MethodologyCapabilities.evaluate(connection,
                    MethodologyRegistry.candidateStatus(connection, ProjectJourney.candidate(answers))));
    }

    View current(Connection connection, DynamicProjectScope.Context context) throws SQLException {
        return view(connection, context, latestOrEmpty(connection, context.id()));
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
        MethodologyPin.record(connection, actor, revision.version(), ProjectJourney.candidate(revision.answers()));
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
