package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

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
}
