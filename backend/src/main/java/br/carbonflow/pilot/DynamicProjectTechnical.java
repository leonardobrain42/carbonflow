package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Initial *preparatory* E8 adapter for dynamic projects.
 *
 * The pilot's IntakeRules and DocumentRules remain canonical for their own
 * synthetic lifecycle. This adapter reuses their reference gates and document
 * metadata, but NEVER turns a sandbox draft into an E8-approved configuration,
 * an external submission, an emissions run, or a credit.
 */
@ApplicationScoped
class DynamicProjectTechnical {
    record IntakeData(String activity, String country, String state, String locality,
                      String candidateMethodology, LocalDate referenceActDate, String notes) {}
    record IntakeView(String projectId, UUID organizationId, String environment, int version,
                      String state, String reviewState, IntakeData data,
                      List<IntakeModel.Reason> outstandingGates, String triageCandidate,
                      ProjectJourney.Profile journey, List<ProjectJourney.Option> methodologies,
                      ProjectExperience.Mode experienceMode) {}
    record PreliminaryDossier(UUID id, String projectId, UUID organizationId, String environment,
                              int revision, int intakeVersion, String state, String documentKind,
                              String sourceTemplate, String sourceEdition,
                              List<String> sections, List<IntakeModel.Reason> blockers,
                              IntakeData intake) {}
    record Capabilities(String projectId, String environment, Map<String, String> modules,
                        boolean owner, java.util.Set<String> actions) {}
    private record Receipt(String action, String project, byte[] fingerprint, String result) {}
    private record IntakeRow(int version, IntakeData data) {}
    private record DossierRow(int revision, String json) {}

    private static final List<String> PD_SECTIONS = List.of(
            "Descrição do projeto", "Aplicabilidade e versão metodológica",
            "Cenário de linha de base", "Adicionalidade e datas",
            "Plano de monitoramento", "Salvaguardas e direitos",
            "Rastreabilidade de evidências", "Revisões e encaminhamentos");
    private static final List<IntakeModel.Reason> STANDARD_BLOCKERS = List.of(
            new IntakeModel.Reason("ANALISE_NAO_EXECUTADA", "CF-IMP-008",
                    "O cadastro preliminar não executa a análise de aplicabilidade, adicionalidade ou elegibilidade."),
            new IntakeModel.Reason("DADOS_E_EVIDENCIAS_PENDENTES", "CF-IMP-008",
                    "Faltam evidências admissíveis, critérios, revisões e comprovações de poderes para uso real."));

    private final DynamicProjectScope scopes;
    private final ObjectMapper mapper;

    DynamicProjectTechnical(DynamicProjectScope scopes, ObjectMapper mapper) {
        this.scopes = scopes;
        this.mapper = mapper;
    }

    Capabilities capabilities(String cookie, String project) {
        return scopes.read(cookie, project, "VIEW_CAPABILITIES", (connection, context) ->
                new Capabilities(project, context.environment(), Map.of(
                        "intake", context.actions().contains("VIEW_INTAKE") ? "PREPARATORY_ONLY" : "NOT_ENABLED",
                        "evidence", "NOT_ENABLED", "calculation", "NOT_ENABLED", "monitoring", "NOT_ENABLED",
                        "projectDescription", context.actions().contains("VIEW_DOSSIER") ? "PRELIMINARY_ONLY" : "NOT_ENABLED",
                        "externalActs", "NOT_ENABLED"), context.owner(), context.actions()));
    }

    IntakeView intake(String cookie, String project) {
        return scopes.read(cookie, project, "VIEW_INTAKE",
                (connection, scope) -> view(scope, latestIntake(connection, project),
                        ProjectTriageWizard.savedCandidate(connection, project), ProjectExperience.mode(connection, project)));
    }

    JsonNode saveIntake(String cookie, String project, String keyHeader, String ifMatch, IntakeData input) {
        return scopes.write(cookie, project, "SAVE_INTAKE", (connection, scope) -> {
            String key = PilotCommands.key(keyHeader);
            int expected = PilotCommands.expected(ifMatch);
            IntakeData data = normalize(input);
            byte[] fingerprint = AccessSecrets.digest(mapper.valueToTree(
                    Map.of("project", project, "expected", expected, "data", data)).toString());

            serialiseKey(connection, scope.actor().id(), key);
            Receipt previous = receipt(connection, scope.actor().id(), key);
            if (previous != null) return replay(previous, "SAVE_INTAKE", project, fingerprint);
            IntakeRow current = latestIntake(connection, project);
            PilotCommands.requireVersion(expected, current == null ? 0 : current.version());

            int next = expected + 1;
            update(connection, """
                    INSERT INTO app_project_intake_draft_revision
                      (project_id, organization_id, environment, version, actor_id, payload)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    """, project, scope.organization(), scope.environment(), next, scope.actor().id(), encode(data));
            JsonNode result = mapper.valueToTree(view(scope, new IntakeRow(next, data),
                    ProjectTriageWizard.savedCandidate(connection, project), ProjectExperience.mode(connection, project)));
            storeReceipt(connection, scope, key, "SAVE_INTAKE", fingerprint, result);
            return result;
        });
    }

    PreliminaryDossier dossier(String cookie, String project) {
        return scopes.read(cookie, project, "VIEW_DOSSIER", (connection, scope) -> {
            DossierRow latest = latestDossier(connection, project);
            return latest == null ? null : decode(latest.json(), PreliminaryDossier.class);
        });
    }

    String downloadDossier(String cookie, String project) {
        return scopes.read(cookie, project, "DOWNLOAD_DOSSIER", (connection, scope) -> {
            DossierRow latest = latestDossier(connection, project);
            if (latest == null) throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "dossier", "CF-IMP-008",
                    "Prévia indisponível.");
            return latest.json();
        });
    }

    private static DossierRow latestDossier(Connection connection, String project) throws SQLException {
        return first(connection, """
                SELECT revision, preview::text AS body
                FROM app_project_preliminary_dossier
                WHERE project_id = ? ORDER BY revision DESC LIMIT 1
                """, row -> new DossierRow(row.getInt("revision"), row.getString("body")), project);
    }

    JsonNode prepareDossier(String cookie, String project, String keyHeader, String ifMatch) {
        return scopes.write(cookie, project, "PREPARE_DOSSIER", (connection, scope) -> {
            String key = PilotCommands.key(keyHeader);
            int expected = PilotCommands.expected(ifMatch);
            byte[] fingerprint = AccessSecrets.digest(
                    "PREPARE_DOSSIER:" + project + ":" + expected);

            serialiseKey(connection, scope.actor().id(), key);
            Receipt previous = receipt(connection, scope.actor().id(), key);
            if (previous != null) return replay(previous, "PREPARE_DOSSIER", project, fingerprint);

            IntakeRow current = latestIntake(connection, project);
            if (current == null) throw new PilotProblem(409, "INTAKE_PENDENTE", "intake",
                    "CF-IMP-008", "Salve as informações iniciais antes de preparar a prévia.");
            PilotCommands.requireVersion(expected, current.version());

            Integer previousRevision = first(connection, """
                    SELECT revision FROM app_project_preliminary_dossier
                    WHERE project_id = ? ORDER BY revision DESC LIMIT 1
                    """, row -> row.getInt(1), project);
            Integer preparedForIntake = first(connection, """
                    SELECT revision FROM app_project_preliminary_dossier
                    WHERE project_id = ? AND intake_version = ?
                    """, row -> row.getInt(1), project, current.version());
            if (preparedForIntake != null) {
                throw new PilotProblem(409, "PREVIA_JA_PREPARADA", "intakeVersion",
                        "CF-IMP-008", "A versão atual já possui um dossiê preliminar.");
            }
            int revision = previousRevision == null ? 1 : previousRevision + 1;
            IntakeData data = current.data();
            // A template identifier is metadata only: protected Verra text is not copied into this preview.
            boolean vm0038 = ProjectJourney.profile(data.candidateMethodology()) == ProjectJourney.VM0038;
            String template = vm0038 ? DocumentRules.Act.DESENVOLVIMENTO.template() : "A_DEFINIR_APOS_TRIAGEM";
            String edition = vm0038 ? DocumentRules.EDITION : "INDEFINIDA";
            var dossier = new PreliminaryDossier(UUID.randomUUID(), project, scope.organization(),
                    scope.environment(), revision, current.version(),
                    "RASCUNHO_PRELIMINAR_NAO_ADMISSIVEL", "PD_PREPARATORY_SCAFFOLD",
                    template, edition, PD_SECTIONS, blockers(data.candidateMethodology()), data);
            update(connection, """
                    INSERT INTO app_project_preliminary_dossier
                      (id, project_id, organization_id, environment, revision,
                       intake_version, actor_id, preview)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, dossier.id(), project, scope.organization(), scope.environment(), revision,
                    current.version(), scope.actor().id(), encode(dossier));
            JsonNode result = mapper.valueToTree(dossier);
            storeReceipt(connection, scope, key, "PREPARE_DOSSIER", fingerprint, result);
            return result;
        });
    }

    private IntakeView view(DynamicProjectScope.Context scope, IntakeRow row, String triageCandidate,
                            ProjectExperience.Mode experienceMode) {
        IntakeData data = row == null ? null : row.data();
        String candidate = triageCandidate != null ? triageCandidate : data == null ? null : data.candidateMethodology();
        var journey = ProjectJourney.profile(candidate);
        return new IntakeView(scope.id(), scope.organization(), scope.environment(),
                row == null ? 0 : row.version(), row == null ? "NAO_INICIADO" : "RASCUNHO",
                "NAO_AVALIADO", data, blockers(candidate), triageCandidate, journey, ProjectJourney.METHODOLOGIES,
                experienceMode);
    }

    private static List<IntakeModel.Reason> blockers(String candidate) {
        if (ProjectJourney.profile(candidate) == ProjectJourney.VM0038) {
            var result = new java.util.ArrayList<>(STANDARD_BLOCKERS);
            result.addAll(IntakeRules.REAL_GATES);
            return List.copyOf(result);
        }
        return STANDARD_BLOCKERS;
    }

    private static IntakeRow latestIntake(Connection connection, String project) throws SQLException {
        // Read immutable technical revisions, not pilot_intake_revision.
        return first(connection, """
                SELECT version, payload::text AS payload
                FROM app_project_intake_draft_revision
                WHERE project_id = ? ORDER BY version DESC LIMIT 1
                """, row -> new IntakeRow(row.getInt("version"),
                        decode(row.getString("payload"), IntakeData.class)), project);
    }

    private static IntakeData normalize(IntakeData input) {
        if (input == null) throw invalid("body");
        String activity = value(input.activity(), "activity", 400);
        String country = value(input.country(), "country", 2);
        String state = value(input.state(), "state", 80);
        String locality = value(input.locality(), "locality", 200);
        String candidate = value(input.candidateMethodology(), "candidateMethodology", 400);
        String notes = value(input.notes(), "notes", 2000);
        if (country != null && !country.matches("[A-Z]{2}")) throw invalid("country");
        if (activity == null && country == null && state == null && locality == null && candidate == null
                && input.referenceActDate() == null && notes == null) throw invalid("activity");
        return new IntakeData(activity, country, state, locality, candidate, input.referenceActDate(), notes);
    }

    private static String value(String input, String field, int max) {
        if (input == null || input.isBlank()) return null;
        String value = input.strip();
        if (value.codePointCount(0, value.length()) > max ||
                value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r'))
            throw invalid(field);
        return value;
    }

    private static PilotProblem invalid(String field) {
        return new PilotProblem(400, "PEDIDO_INVALIDO", field, "CF-IMP-008",
                "Confira as informações técnicas do rascunho.");
    }

    private static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Falha ao gravar rascunho técnico", error);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static <T> T decode(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Falha ao ler revisão técnica", error);
        }
    }

    private static void serialiseKey(Connection connection, UUID actor, String key) throws SQLException {
        first(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> true,
                "app_project_technical_command:" + actor + ":" + key);
    }

    private static Receipt receipt(Connection connection, UUID actor, String key) throws SQLException {
        return first(connection, """
                SELECT action, project_id, fingerprint, result::text AS result
                FROM app_project_technical_command WHERE actor_id = ? AND command_key = ?
                """, row -> new Receipt(row.getString("action"), row.getString("project_id"),
                row.getBytes("fingerprint"), row.getString("result")), actor, key);
    }

    private JsonNode replay(Receipt receipt, String action, String project, byte[] fingerprint) {
        if (!receipt.action().equals(action) || !receipt.project().equals(project)
                || !MessageDigest.isEqual(receipt.fingerprint(), fingerprint)) {
            throw new PilotProblem(409, "CHAVE_REUSADA", "Idempotency-Key", "CF-IMP-008",
                    "Esta chave pertence a outro comando.");
        }
        try {
            return mapper.readTree(receipt.result());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Comprovante técnico ilegível", error);
        }
    }

    private static void storeReceipt(Connection connection, DynamicProjectScope.Context scope,
                                     String key, String action, byte[] fingerprint, JsonNode result)
            throws SQLException {
        update(connection, """
                INSERT INTO app_project_technical_command
                  (actor_id, command_key, action, project_id, organization_id, environment, fingerprint, result)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, scope.actor().id(), key, action, scope.id(), scope.organization(),
                scope.environment(), fingerprint, result.toString());
    }
}
