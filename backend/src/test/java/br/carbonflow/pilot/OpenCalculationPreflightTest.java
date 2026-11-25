package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OpenCalculationPreflightTest {
    private static OpenCalculationPreflight.Applicability open(int triage) {
        return new OpenCalculationPreflight.Applicability(
            1,triage,true,false,"DCFC","BEV","SIN",false,false);
    }

    private static OpenCalculationPreflight.Result eval(
            String methodology,int triage,OpenCalculationPreflight.Applicability app) {
        return OpenCalculationPreflight.evaluate(new OpenCalculationPreflight.Context(
            "dynamic-project","SANDBOX",methodology,"BR","LDV",triage,app));
    }

    @Test
    void qualifiedDeclarationsStillCannotCalculateWithoutRealProjectBindings() {
        var result=eval("VM0038",1,open(1));
        assertEquals("BLOCKED_MISSING_DATA",result.calculationState());
        assertEquals("REQUEST_DATA",result.workflowState());
        assertTrue(result.canContinuePreparation());
        assertFalse(result.canRunE8());
        assertFalse(result.canIssueVcu());
        assertNull(result.emissions().beTco2e());
        assertNull(result.emissions().peTco2e());
        assertNull(result.emissions().leTco2e());
        assertNull(result.emissions().erTco2e());
        assertNull(result.emissions().erDisplay());
        assertTrue(result.engine().contains("preflight"));
        assertEquals(7,result.equationPlan().size());
        assertEquals(List.of("AFEC","MPG","BE","PE","LE","DY","ER"),
            result.equationPlan().stream().map(
                OpenCalculationTransparency.Stage::outputCode).toList());
        var be=result.equationPlan().stream().filter(e->e.outputCode().equals("BE"))
            .findFirst().orElseThrow();
        assertEquals("VM0038-1.1-EQ1",be.ruleReference());
        assertEquals("NOT_CALCULATED",be.status());
        assertTrue(be.requiredInputs().containsAll(List.of("ED","EF_FUEL","IR","AFEC","MPG")));
        assertTrue(be.blockedBy().containsAll(
            List.of("NORMATIVE_PACK","EVR","EF_FUEL","IR","ED")));
        assertTrue(be.whyNotCalculated().startsWith("Não calculado."));
        assertTrue(be.whyNotCalculated().contains(
            "Não há energia entregue admitida por carregador e período."));
        assertTrue(be.whyNotCalculated().contains(
            "A melhoria tecnológica não possui regra e prova aplicáveis. A preparação não usa 1 por padrão."));
        assertTrue(be.whyNotCalculated().contains(
            "O fator fóssil por combustível, ano e gás não está admitido para a linha de base."));
        var dy=result.equationPlan().stream().filter(e->e.outputCode().equals("DY"))
            .findFirst().orElseThrow();
        assertTrue(dy.whyNotCalculated().contains(
            "A preparação não assume 1 nem zero."));
        var pe=result.equationPlan().stream().filter(e->e.outputCode().equals("PE"))
            .findFirst().orElseThrow();
        assertTrue(pe.whyNotCalculated().contains(
            "Não há energia consumida admitida por carregador e período."));
        assertTrue(pe.whyNotCalculated().contains("VT0011 e TOOL07"));
        assertFalse(be.whyNotCalculated().contains("tCO2e"));
        assertFalse(be.whyNotCalculated().contains("EF-SYN"));
        assertFalse(result.equationPlan().toString().contains("EF-SYN"));
        Set<String> codes=result.gates().stream().map(OpenCalculationPreflight.Gate::code)
            .collect(Collectors.toSet());
        assertTrue(codes.containsAll(Set.of("NORMATIVE_PACK","CARBON_RIGHTS","EVR",
            "AFEC_MPG","EF_FUEL","IR","DY","ED","EC","EF_GRID")));
        assertTrue(result.gates().stream().anyMatch(g ->
            g.code().equals("ED") && g.state().equals("PENDING_MEASUREMENT")));
        assertTrue(result.gates().stream().anyMatch(g ->
            g.code().equals("EF_GRID") && g.state().equals("MISSING_BINDING")));
    }

    @Test
    void missingDeclarationAndUnknownFieldsAreNotSilentlyFalseOrZero() {
        var missing=eval("VM0038",0,null);
        assertEquals("BLOCKED_MISSING_DATA",missing.calculationState());
        assertTrue(missing.gates().stream().anyMatch(g ->
            g.code().equals("OPEN_APPLICABILITY") && g.state().equals("MISSING")));
        var incomplete=new OpenCalculationPreflight.Applicability(
            1,0,null,null,null,null,null,null,null);
        var unknown=eval("VM0038",0,incomplete);
        assertTrue(unknown.gates().stream().anyMatch(g ->
            g.code().equals("NETWORK_MODE") && g.state().equals("MISSING")));
        assertTrue(unknown.gates().stream().anyMatch(g ->
            g.code().equals("TECHNICAL_BRANCH") && g.state().equals("MISSING")));
        assertFalse(unknown.gates().stream().anyMatch(g -> g.state().equals("OUT_OF_SCOPE")));
        assertFalse(unknown.canRunE8());
    }

    @Test
    void closedPhevSolarV2gAndWrongMethodRemainOutsideBranch() {
        var closed=new OpenCalculationPreflight.Applicability(
            1,0,false,true,"DCFC","BEV","SIN",false,false);
        assertEquals("BLOCKED_NOT_ADMISSIBLE",eval("VM0038",0,closed).calculationState());

        var v2g=new OpenCalculationPreflight.Applicability(
            1,0,true,false,"DCFC","BEV","SIN",false,true);
        var result=eval("VM0038",0,v2g);
        assertEquals("BLOCKED_NOT_ADMISSIBLE",result.calculationState());
        assertTrue(result.gates().stream().anyMatch(g ->
            g.code().equals("TECHNICAL_BRANCH") && g.state().equals("OUT_OF_SCOPE")));

        assertEquals("BLOCKED_NOT_ADMISSIBLE",eval("VM0042",0,open(0)).calculationState());
        assertTrue(eval("VM0042",0,open(0)).equationPlan().stream()
            .allMatch(stage->stage.status().equals("OUT_OF_SCOPE")
                && stage.whyNotCalculated().startsWith("Fora do recorte")));
    }

    @Test
    void triageChangeMarksApplicabilityStaleWithoutOverwritingHistory() {
        var result=eval("VM0038",2,open(1));
        assertEquals("BLOCKED_NOT_ADMISSIBLE",result.calculationState());
        assertTrue(result.gates().stream().anyMatch(g ->
            g.code().equals("OPEN_APPLICABILITY") && g.state().equals("STALE")));
        assertNull(result.emissions().erTco2e());
    }

    @Test
    void countryAndVehicleClassMustBeProvenNotInferredFromMethodology() {
        var foreign=OpenCalculationPreflight.evaluate(new OpenCalculationPreflight.Context(
            "project", "SANDBOX", "VM0038", "US", "LDV", 0, open(0)));
        assertEquals("BLOCKED_NOT_ADMISSIBLE", foreign.calculationState());
        assertTrue(foreign.gates().stream().anyMatch(g ->
            g.code().equals("COUNTRY") && g.state().equals("OUT_OF_SCOPE")));

        var heavy=OpenCalculationPreflight.evaluate(new OpenCalculationPreflight.Context(
            "project", "SANDBOX", "VM0038", "BR", "HDV", 0, open(0)));
        assertEquals("BLOCKED_NOT_ADMISSIBLE", heavy.calculationState());
        assertTrue(heavy.gates().stream().anyMatch(g ->
            g.code().equals("VEHICLE_CLASS") && g.state().equals("OUT_OF_SCOPE")));

        var unknown=OpenCalculationPreflight.evaluate(new OpenCalculationPreflight.Context(
            "project", "SANDBOX", "VM0038", null, null, 0, open(0)));
        assertEquals("BLOCKED_MISSING_DATA", unknown.calculationState());
        assertTrue(unknown.gates().stream().anyMatch(g ->
            g.code().equals("COUNTRY") && g.state().equals("MISSING")));
        assertTrue(unknown.gates().stream().anyMatch(g ->
            g.code().equals("VEHICLE_CLASS") && g.state().equals("MISSING")));
    }

    @Test
    void repeatedPreflightIsDeterministicAndDoesNotUsePilotFactors() {
        var input=new OpenCalculationPreflight.Context(
            "dynamic-project","SANDBOX","VM0038","BR","LDV",1,open(1));
        assertEquals(OpenCalculationPreflight.evaluate(input),
            OpenCalculationPreflight.evaluate(input));
        var result=OpenCalculationPreflight.evaluate(input);
        assertFalse(result.toString().contains("EF-SYN"));
        assertFalse(result.toString().contains("SIM-DY"));
        assertFalse(result.toString().contains("PIL-SYN"));
    }

    @Test
    void d15DecidesStalenessWhenTheCallerKnowsTheRelevantAnswers() {
        // A newer triage version without a relevant change (CF-SPEC-D15) is not stale.
        var current=OpenCalculationPreflight.evaluate(new OpenCalculationPreflight.Context(
            "dynamic-project","SANDBOX","VM0038","BR","LDV",3,open(1),false));
        assertTrue(current.gates().stream().noneMatch(g->"OPEN_APPLICABILITY".equals(g.code())&&"STALE".equals(g.state())));
        assertEquals("BLOCKED_MISSING_DATA",current.calculationState());
        var changed=OpenCalculationPreflight.evaluate(new OpenCalculationPreflight.Context(
            "dynamic-project","SANDBOX","VM0038","BR","LDV",3,open(1),true));
        assertTrue(changed.gates().stream().anyMatch(g->"OPEN_APPLICABILITY".equals(g.code())&&"STALE".equals(g.state())));
        assertEquals("BLOCKED_NOT_ADMISSIBLE",changed.calculationState());
        // Without the D15 answer, any newer version stays conservative.
        assertTrue(eval("VM0038",3,open(1)).gates().stream()
            .anyMatch(g->"OPEN_APPLICABILITY".equals(g.code())&&"STALE".equals(g.state())));
    }
}
