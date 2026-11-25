package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * D18 slice 4: the conformance kit. The production catalog passes; a test-only package passes the structural rules
 * but never the production gate; and every rule is proven by a package built to break it, so the kit is not decorative.
 */
class MethodologyPackageContractTest {
    private static final Map<String, String> CAPS = Map.of("triageSchema", "AVAILABLE",
            "calculationAdmission", "NOT_ENABLED", "externalActs", "NOT_ENABLED");
    private static final ProjectJourney.Option YES = new ProjectJourney.Option("YES", "Sim");
    private static final ProjectJourney.Option NO = new ProjectJourney.Option("NO", "Não");

    private static final ProjectJourney.Evaluator NEUTRAL = new ProjectJourney.Evaluator() {
        @Override public ProjectJourney.Guidance guidance(JsonNode answers) {
            return new ProjectJourney.Guidance("A_CONFIRMAR", List.of(), List.of());
        }
        @Override public ProjectTriageDiagnosis.Result diagnose(JsonNode answers, ProjectJourney.Guidance guidance) {
            return new ProjectTriageDiagnosis.Result("TEST", ProjectTriageDiagnosis.Classification.UNKNOWN_REVIEW_REQUIRED,
                    "regra do pacote de teste", List.of());
        }
    };

    private static ProjectJourney.Profile profile(String id, String candidate, List<ProjectJourney.Question> questions,
            List<ProjectJourney.Question> activityQuestions, Set<String> activities, String description,
            ProjectJourney.Evaluator evaluator) {
        return new ProjectJourney.Profile(id, candidate, "0", "Pacote de teste", "Atividade", "Escopo", description,
                null, List.of("Tópico de teste"), questions, activityQuestions, activities,
                new ProjectJourney.Presentation("Descreva a área", "Descreva a destinação atual", List.of(
                        new ProjectJourney.NextSection("residuos-base", "Linha de base", "Item de teste."))), evaluator);
    }

    private static ProjectJourney.Question choice(String key) {
        return new ProjectJourney.Question(key, "Pergunta " + key, "choice", null, List.of(YES, NO));
    }

    private static MethodologyPackageContract.Declaration good() {
        return declaration(profile("TEST_ONLY_RESIDUES", "TEST-ONLY", List.of(choice("siteType")),
                List.of(choice("gasCapture")), Set.of("RESIDUOS"), "Pacote sintético.", NEUTRAL));
    }

    private static MethodologyPackageContract.Declaration declaration(ProjectJourney.Profile profile) {
        return new MethodologyPackageContract.Declaration(profile, "TESTPROG", 1, "TEST_ONLY_SYNTHETIC", CAPS);
    }

    private static Set<String> rules(List<MethodologyPackageContract.Violation> found) {
        var out = new java.util.HashSet<String>();
        found.forEach(item -> out.add(item.rule()));
        return out;
    }

    @Test
    void theProductionCatalogConformsToTheContract() {
        var declarations = MethodologyPackages.current().packages().stream().map(item ->
                new MethodologyPackageContract.Declaration(ProjectJourney.configured().stream()
                        .filter(profile -> profile.id().equals(item.packageId())).findFirst().orElseThrow(),
                        item.program(), item.version(), item.status(), item.capabilities())).toList();
        assertFalse(declarations.isEmpty());
        declarations.forEach(item -> assertEquals(List.of(), MethodologyPackageContract.check(item), item.profile().id()));
        assertEquals(List.of(), MethodologyPackageContract.checkSet(declarations));
        assertDoesNotThrow(() -> MethodologyPackageContract.requireProduction(declarations));
    }

    @Test
    void aTestOnlyPackageConformsStructurallyAndCoexistsWithTheProductionOneButNeverEntersProduction() {
        assertEquals(List.of(), MethodologyPackageContract.check(good()));
        var vm = MethodologyPackages.current().packages().get(0);
        var production = new MethodologyPackageContract.Declaration(ProjectJourney.VM0038, vm.program(), vm.version(),
                vm.status(), vm.capabilities());
        assertEquals(List.of(), MethodologyPackageContract.checkSet(List.of(production, good())));
        var error = assertThrows(IllegalStateException.class,
                () -> MethodologyPackageContract.requireProduction(List.of(good())));
        assertTrue(error.getMessage().contains("PKG-TEST-ONLY"), error.getMessage());
    }

    @Test
    void identityAndVocabularyRulesCatchBadPackages() {
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("bad id", "TEST-ONLY",
                List.of(choice("siteType")), List.of(), Set.of("RESIDUOS"), "x", NEUTRAL)))).contains("PKG-ID"));
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("TEST_ONLY_A", "x",
                List.of(), List.of(), Set.of("RESIDUOS"), "x", NEUTRAL)))).contains("PKG-KEY"));
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("TEST_ONLY_A", "TEST-ONLY",
                List.of(), List.of(), Set.of("ATIVIDADE_INVENTADA"), "x", NEUTRAL)))).contains("PKG-ACTIVITY"));
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("TEST_ONLY_A", "TEST-ONLY",
                List.of(), List.of(), Set.of(), "x", NEUTRAL)))).contains("PKG-ACTIVITY"));
        var noVersion = new MethodologyPackageContract.Declaration(good().profile(), "TESTPROG", 0, "TEST_ONLY_X", CAPS);
        assertTrue(rules(MethodologyPackageContract.check(noVersion)).contains("PKG-VERSION"));
    }

    @Test
    void questionRulesCatchDuplicatesPlatformKeysAndBrokenChoices() {
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("TEST_ONLY_A", "TEST-ONLY",
                List.of(choice("siteType")), List.of(choice("siteType")), Set.of("RESIDUOS"), "x", NEUTRAL))))
                .contains("PKG-QUESTION"));
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("TEST_ONLY_A", "TEST-ONLY",
                List.of(choice("country")), List.of(), Set.of("RESIDUOS"), "x", NEUTRAL)))).contains("PKG-QUESTION"));
        var oneOption = new ProjectJourney.Question("siteType", "Tipo", "choice", null, List.of(YES));
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("TEST_ONLY_A", "TEST-ONLY",
                List.of(oneOption), List.of(), Set.of("RESIDUOS"), "x", NEUTRAL)))).contains("PKG-QUESTION"));
        var unknownType = new ProjectJourney.Question("siteType", "Tipo", "script", null, List.of());
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("TEST_ONLY_A", "TEST-ONLY",
                List.of(unknownType), List.of(), Set.of("RESIDUOS"), "x", NEUTRAL)))).contains("PKG-QUESTION"));
    }

    @Test
    void aPackageCannotEnableCalculationAdmissionOrExternalActsOrChangeTheCapabilitySet() {
        for (String gate : List.of("calculationAdmission", "externalActs")) {
            var caps = new HashMap<>(CAPS);
            caps.put(gate, "AVAILABLE");
            assertTrue(rules(MethodologyPackageContract.check(new MethodologyPackageContract.Declaration(
                    good().profile(), "TESTPROG", 1, "TEST_ONLY_X", caps))).contains("PKG-CAPABILITY"), gate);
        }
        var extra = new HashMap<>(CAPS);
        extra.put("autoApprove", "AVAILABLE");
        assertTrue(rules(MethodologyPackageContract.check(new MethodologyPackageContract.Declaration(
                good().profile(), "TESTPROG", 1, "TEST_ONLY_X", extra))).contains("PKG-CAPABILITY"));
    }

    @Test
    void anEvaluatorThatFailsOrIsMissingIsRejected() {
        var failing = new ProjectJourney.Evaluator() {
            @Override public ProjectJourney.Guidance guidance(JsonNode answers) {
                throw new IllegalStateException("não trata respostas vazias");
            }
            @Override public ProjectTriageDiagnosis.Result diagnose(JsonNode answers, ProjectJourney.Guidance guidance) {
                return null;
            }
        };
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("TEST_ONLY_A", "TEST-ONLY",
                List.of(), List.of(), Set.of("RESIDUOS"), "x", failing)))).contains("PKG-NEUTRAL"));
        assertTrue(rules(MethodologyPackageContract.check(declaration(profile("TEST_ONLY_A", "TEST-ONLY",
                List.of(), List.of(), Set.of("RESIDUOS"), "x", null)))).contains("PKG-EVALUATOR"));
    }

    @Test
    void isolationRulesCatchSharedKeysAndTextThatNamesAnotherMethodology() {
        var vm = MethodologyPackages.current().packages().get(0);
        var production = new MethodologyPackageContract.Declaration(ProjectJourney.VM0038, vm.program(), vm.version(),
                vm.status(), vm.capabilities());
        String sharedKey = ProjectJourney.VM0038.questions().get(0).key();
        var sharing = declaration(profile("TEST_ONLY_B", "TEST-ONLY", List.of(choice(sharedKey)), List.of(),
                Set.of("RESIDUOS"), "Pacote sintético.", NEUTRAL));
        assertTrue(rules(MethodologyPackageContract.checkSet(List.of(production, sharing))).contains("PKG-ISOLATION"));
        var leaking = declaration(profile("TEST_ONLY_B", "TEST-ONLY", List.of(choice("siteType")), List.of(),
                Set.of("RESIDUOS"), "Funciona como a VM0038 para resíduos.", NEUTRAL));
        assertTrue(rules(MethodologyPackageContract.checkSet(List.of(production, leaking))).contains("PKG-ISOLATION"));
        assertTrue(rules(MethodologyPackageContract.checkSet(List.of(good(), good()))).contains("PKG-DUPLICATE"));
    }

    @Test
    void theDigestMovesWithEveryDeclaredField() {
        assertEquals(List.of(), MethodologyPackageContract.check(good()).stream()
                .filter(item -> item.rule().equals("PKG-DIGEST")).toList());
        String base = MethodologyPackages.digestOf(good().profile(), "TESTPROG", 1, "TEST_ONLY_SYNTHETIC", CAPS);
        var other = new HashMap<>(CAPS);
        other.put("triageSchema", "NOT_ENABLED");
        for (String changed : List.of(
                MethodologyPackages.digestOf(good().profile(), "TESTPROGX", 1, "TEST_ONLY_SYNTHETIC", CAPS),
                MethodologyPackages.digestOf(good().profile(), "TESTPROG", 2, "TEST_ONLY_SYNTHETIC", CAPS),
                MethodologyPackages.digestOf(good().profile(), "TESTPROG", 1, "TEST_ONLY_OTHER", CAPS),
                MethodologyPackages.digestOf(good().profile(), "TESTPROG", 1, "TEST_ONLY_SYNTHETIC", other))) {
            assertNotEquals(base, changed);
        }
    }
}
