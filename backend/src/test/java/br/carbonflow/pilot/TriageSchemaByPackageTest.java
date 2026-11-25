package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * AGN-02 (D17), triage part: a package defines its own activity and scope questions, activity types and rules,
 * and the generic triage functions follow it. The residues profile exists only in this test (TEST_ONLY): it
 * is not registered, does not claim real support for any methodology and reproduces no norm.
 */
class TriageSchemaByPackageTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ProjectJourney.Option YES = new ProjectJourney.Option("YES", "Sim");
    private static final ProjectJourney.Option NO = new ProjectJourney.Option("NO", "Não");

    private static final ProjectJourney.Profile TEST_ONLY = new ProjectJourney.Profile(
            "TEST_ONLY_RESIDUES", "TEST-ONLY", "0", "Pacote de teste · resíduos", "Atividade", "Escopo",
            "Pacote sintético apenas para provar extensibilidade.", null, List.of("Tópico de teste"),
            List.of(new ProjectJourney.Question("siteType", "Tipo de área", "choice", null,
                    List.of(new ProjectJourney.Option("LANDFILL", "Aterro"), new ProjectJourney.Option("OTHER", "Outro")))),
            List.of(new ProjectJourney.Question("gasCapture", "Há captura de gás?", "choice", null, List.of(YES, NO))),
            Set.of("RESIDUOS"),
            new ProjectJourney.Evaluator() {
                @Override public ProjectJourney.Guidance guidance(JsonNode answers) {
                    return new ProjectJourney.Guidance("A_CONFIRMAR", List.of(), List.of());
                }
                @Override public ProjectTriageDiagnosis.Result diagnose(JsonNode answers, ProjectJourney.Guidance guidance) {
                    return new ProjectTriageDiagnosis.Result("TEST", ProjectTriageDiagnosis.Classification.UNKNOWN_REVIEW_REQUIRED,
                            "regra do pacote de teste", List.of());
                }
            });

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void requiredFieldsFollowThePackageSchema() throws Exception {
        JsonNode answers = json("{\"1\":{\"activityType\":\"RESIDUOS\"}}");
        assertEquals(List.of("gasCapture", "activityDescription"),
                ProjectJourney.required(TEST_ONLY, 2, List.of("activityDescription"), answers));
        assertEquals(List.of("siteType"), ProjectJourney.required(TEST_ONLY, 3, List.of(), answers));
        // The VM0038 package keeps its own schema, and the generic profile still asks nothing extra.
        assertEquals(List.of("installsChargers", "activityDescription"),
                ProjectJourney.required(ProjectJourney.VM0038, 2, List.of("activityDescription"), answers));
        assertEquals(List.of("scopeNotes"), ProjectJourney.required(ProjectJourney.GENERAL, 3, List.of(), answers));
        assertEquals(List.of("activityDescription"),
                ProjectJourney.required(ProjectJourney.GENERAL, 2, List.of("activityDescription"), answers));
    }

    @Test
    void answersAreValidatedAgainstTheProfileOnly() throws Exception {
        assertNull(ProjectJourney.invalidAnswer(TEST_ONLY, 2, json("{\"gasCapture\":\"YES\"}"), json("{}")));
        assertEquals("gasCapture", ProjectJourney.invalidAnswer(TEST_ONLY, 2, json("{\"gasCapture\":\"MAYBE\"}"), json("{}")));
        // A charging question never reaches a package that does not declare it, and the generic profile refuses it too.
        assertEquals("installsChargers", ProjectJourney.invalidAnswer(TEST_ONLY, 2, json("{\"installsChargers\":\"YES\"}"), json("{}")));
        assertEquals("installsChargers", ProjectJourney.invalidAnswer(ProjectJourney.GENERAL, 2, json("{\"installsChargers\":\"YES\"}"), json("{}")));
        assertNull(ProjectJourney.invalidAnswer(ProjectJourney.VM0038, 2, json("{\"installsChargers\":\"NO\"}"), json("{}")));
        assertNull(ProjectJourney.invalidAnswer(TEST_ONLY, 3, json("{\"siteType\":\"LANDFILL\"}"), json("{}")));
        assertEquals("siteType", ProjectJourney.invalidAnswer(TEST_ONLY, 3, json("{\"siteType\":\"CHARGER\"}"), json("{}")));
        assertEquals("vehicleClass", ProjectJourney.invalidAnswer(TEST_ONLY, 3, json("{\"vehicleClass\":\"LDV\"}"), json("{}")));
        // An unchanged older answer is not revalidated (D16).
        assertNull(ProjectJourney.invalidAnswer(TEST_ONLY, 3, json("{\"network\":\"CLOSED\"}"), json("{\"network\":\"CLOSED\"}")));
    }

    @Test
    void guidanceAndDiagnosisComeFromThePackageEvaluator() throws Exception {
        JsonNode answers = json("{\"1\":{\"activityType\":\"RESIDUOS\",\"methodologyCandidate\":\"TEST-ONLY\"}}");
        var guidance = ProjectJourney.guidance(TEST_ONLY, answers);
        assertEquals("A_CONFIRMAR", guidance.state());
        assertEquals("regra do pacote de teste", ProjectTriageDiagnosis.evaluate(TEST_ONLY, answers, guidance).reason());
        // Without a package evaluator there is only the generic gap, never the VM0038 rules.
        assertEquals("SEM_JORNADA_CONFIGURADA", ProjectJourney.guidance(ProjectJourney.GENERAL, answers).state());
        var general = ProjectTriageDiagnosis.evaluate(ProjectJourney.GENERAL, answers,
                ProjectJourney.guidance(ProjectJourney.GENERAL, answers));
        assertEquals(ProjectTriageDiagnosis.Classification.UNSUPPORTED_MVP, general.classification());
        // The test package is not registered: a candidate with that code still resolves to the generic profile.
        assertSame(ProjectJourney.GENERAL, ProjectJourney.profile("TEST-ONLY"));
        assertTrue(ProjectJourney.profiles().stream().noneMatch(profile -> profile.id().startsWith("TEST_ONLY")));
    }
}
