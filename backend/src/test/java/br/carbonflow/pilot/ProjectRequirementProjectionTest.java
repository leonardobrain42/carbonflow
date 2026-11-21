package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProjectRequirementProjectionTest {
    @Test
    void sheetMessagesAreNotEternalClientTasksAndOnlyIncidentGatesMove() {
        var missing = project(true, true, disclosureMissing(), preflight(false));
        assertEquals("NEEDS_EVIDENCE", item(missing, "DRIVER_DISCLOSURE").state());
        assertEquals("MINE", item(missing, "DRIVER_DISCLOSURE").group());
        assertTrue(item(missing, "DRIVER_DISCLOSURE").clientAction());
        for (String code : List.of("VMD0049_ROUTE", "MARKET_ROUTE", "REGULATORY_SURPLUS")) {
            var item = item(missing, code);
            assertEquals("AWAITING_NORMATIVE_EVALUATION", item.state());
            assertNotEquals("MINE", item.group());
            assertFalse(item.clientAction());
            assertNull(item.destination());
            assertFalse(item.admitsE8());
        }
        var energy = item(missing, "ED_EC");
        assertEquals("NOT_INCIDENT", energy.state());
        assertFalse(energy.blocksExAntePd());
        assertFalse(energy.clientAction());
        assertFalse(missing.exAnteRequiresMeasuredEnergy());
        assertEquals(1, missing.requirements().stream().filter(i -> "ED_EC".equals(i.requirementId())).count());

        var declared = project(true, true, disclosurePresent(), preflight(false));
        assertEquals("ATTACHED_NOT_ADMITTED", item(declared, "DRIVER_DISCLOSURE").state());
        assertEquals("ATTACHED_NOT_ADMITTED", item(declared, "OWNER_CONTRACT").state());
        assertEquals("AWAITING_REVIEW", item(declared, "OWNER_CONTRACT").group());
        assertFalse(item(declared, "DRIVER_DISCLOSURE").clientAction());
        assertFalse(item(declared, "OWNER_CONTRACT").admitsE8());
        assertNull(item(declared, "OWNER_CONTRACT").destination());
        assertEquals("AWAITING_NORMATIVE_EVALUATION", item(declared, "REGULATORY_SURPLUS").state());
        assertFalse(item(declared, "REGULATORY_SURPLUS").clientAction());
        assertTrue(declared.requirements().stream().noneMatch(i ->
            "APPROVED".equals(i.state()) || "COMPLETED".equals(i.state()) || i.admitsE8()));
    }

    @Test
    void marketAndAdditionalityTestsWaitForAnOpenFirstSliceDeclaration() {
        var closed = OpenApplicability.evaluate(new OpenApplicability.Input(false, true, true,
            "DCFC", "PHEV", "SIN", false, true, null, null, "NOT_DECLARED", null, null,
            "UNKNOWN", null, null), false);
        assertEquals("CLOSED_RESTRICTION", closed.network());
        assertFalse(closed.canUseForE8());
        assertTrue(closed.findings().stream().noneMatch(f ->
            List.of("MARKET_ROUTE", "VMD0049_ROUTE", "REGULATORY_SURPLUS").contains(f.code())));
        assertTrue(closed.findings().stream().anyMatch(f -> "OWNER_CONTRACT".equals(f.code())
            && "NEEDS_EVIDENCE".equals(f.state())));

        var missing = OpenApplicability.evaluate(null, false);
        assertTrue(missing.findings().stream().anyMatch(f -> "NO_DECLARATION".equals(f.code())));
        assertTrue(missing.findings().stream().noneMatch(f -> "MARKET_ROUTE".equals(f.code())));

        var open = disclosurePresent();
        assertFalse(open.canUseForE8());
        assertEquals("PENDING_NORMATIVE_REVIEW", open.norm());
        assertTrue(open.findings().stream().anyMatch(f -> "MARKET_ROUTE".equals(f.code())
            && f.text().contains("Não é rota de adicionalidade")
            && f.text().contains("A declaração não conclui este teste.")));
        assertTrue(open.findings().stream().anyMatch(f -> "VMD0049_ROUTE".equals(f.code())
            && f.text().contains("não aprova a adicionalidade")));
        assertTrue(open.findings().stream().anyMatch(f -> "REGULATORY_SURPLUS".equals(f.code())
            && f.text().contains("não aprova este teste")));
        var projected = project(true, true, open, preflight(false));
        assertNotEquals("MINE", item(projected, "MARKET_ROUTE").group());
        assertFalse(item(projected, "MARKET_ROUTE").admitsE8());
        assertTrue(item(projected, "MARKET_ROUTE").reason().contains("Não é uma rota de adicionalidade"));
    }

    @Test
    void recordedRightsReferenceStaysUnadmitted() {
        var recorded = disclosurePresent();
        assertEquals("DECLARED_REVIEW_PENDING", recorded.rights());
        assertFalse(recorded.canUseForE8());
        assertTrue(recorded.findings().stream().anyMatch(finding -> "OWNER_CONTRACT".equals(finding.code())
            && "ATTACHED_NOT_ADMITTED".equals(finding.state())
            && finding.text().contains("não cria PP jurídico")
            && finding.text().contains("não concede poder de representante")));
        assertTrue(recorded.findings().stream().anyMatch(finding -> "DRIVER_DISCLOSURE".equals(finding.code())
            && finding.text().contains("não comprova a divulgação")));
        var projected = project(true, true, recorded, preflight(false));
        assertTrue(item(projected, "OWNER_CONTRACT").reason().contains("não é prova admitida"));
        assertTrue(item(projected, "DRIVER_DISCLOSURE").nextAction().contains("não admite o direito"));
        assertFalse(projected.canRunE8());

        var partial = project(true, true, disclosureMissing(), preflight(false));
        assertEquals("NEEDS_EVIDENCE", item(partial, "DRIVER_DISCLOSURE").state());
        assertEquals("MINE", item(partial, "DRIVER_DISCLOSURE").group());
        assertEquals("ATTACHED_NOT_ADMITTED", item(partial, "OWNER_CONTRACT").state());
        assertNotEquals("MINE", item(partial, "OWNER_CONTRACT").group());

        var named = OpenApplicability.evaluate(new OpenApplicability.Input(true, false, true,
            "DCFC", "BEV", "SIN", false, false, "Titular digitado", "CONTRACT-LIVRE",
            "ON_CHARGER", "PHOTO-2027", LocalDate.parse("2027-01-15"), "NO_OVERLAP_DECLARED",
            LocalDate.parse("2027-02-01"), null), false);
        assertEquals("NEEDS_EVIDENCE", named.findings().stream()
            .filter(finding -> "OWNER_CONTRACT".equals(finding.code())).findFirst().orElseThrow().state());
        assertTrue(named.findings().stream().anyMatch(finding -> "OWNER_CONTRACT".equals(finding.code())
            && finding.text().contains("nome digitado")
            && finding.text().contains("não cria PP jurídico")));
        assertFalse(named.canUseForE8());
        var namedView = project(true, true, named, preflight(false));
        assertEquals("MINE", item(namedView, "OWNER_CONTRACT").group());
        assertTrue(item(namedView, "OWNER_CONTRACT").clientAction());
        assertFalse(namedView.canRunE8());
    }

    @Test
    void disclosureAfterEitherStartStaysAReview() {
        var operational = OpenApplicability.evaluate(dates("2027-03-01", "2027-02-01", "2027-04-01"), false);
        var gap = operational.findings().stream().filter(f -> "RIGHTS_DATE_GAP".equals(f.code())).findFirst().orElseThrow();
        assertEquals("NEEDS_REVIEW", gap.state());
        assertTrue(gap.text().contains("início operacional"));
        assertTrue(gap.text().contains("não reprova o projeto"));
        assertFalse(operational.canUseForE8());

        var vcs = OpenApplicability.evaluate(dates("2027-05-01", "2027-06-01", "2027-04-01"), false);
        assertTrue(vcs.findings().stream().anyMatch(f -> "RIGHTS_DATE_GAP".equals(f.code())
            && f.text().contains("início de crédito VCS") && "NEEDS_REVIEW".equals(f.state())));

        var both = OpenApplicability.evaluate(dates("2027-05-01", "2027-01-01", "2027-02-01"), false);
        assertTrue(both.findings().stream().anyMatch(f -> "RIGHTS_DATE_GAP".equals(f.code())
            && f.text().contains("início operacional e ao início de crédito VCS")));
        assertEquals(1, both.findings().stream().filter(f -> "RIGHTS_DATE_GAP".equals(f.code())).count());

        var before = OpenApplicability.evaluate(dates("2027-01-10", "2027-02-01", "2027-03-01"), false);
        assertTrue(before.findings().stream().noneMatch(f -> "RIGHTS_DATE_GAP".equals(f.code())));
        assertFalse(before.canUseForE8());
    }

    @Test
    void typedReferenceWithoutAFileStaysWithTheClient() {
        var typed = OpenApplicability.evaluate(new OpenApplicability.Input(true, false, true,
            "DCFC", "BEV", "SIN", false, false, "Titular", "CONTRACT-1", "ON_CHARGER",
            "PHOTO-2027", LocalDate.parse("2027-01-15"), "NO_OVERLAP_DECLARED",
            null, null, "PARTY-1", "CONTRACT-1"), false);
        assertEquals("NEEDS_EVIDENCE", typed.findings().stream()
            .filter(f -> "OWNER_CONTRACT".equals(f.code())).findFirst().orElseThrow().state());
        assertEquals("NEEDS_EVIDENCE", typed.findings().stream()
            .filter(f -> "DRIVER_DISCLOSURE".equals(f.code())).findFirst().orElseThrow().state());
        assertTrue(typed.findings().stream().anyMatch(f -> f.text().contains("falta o arquivo")));
        assertTrue(typed.findings().stream().noneMatch(f -> "PROOF_ADMITTED".equals(f.state())));
        assertFalse(typed.canUseForE8());
        var view = project(true, true, typed, preflight(false));
        assertEquals("MINE", item(view, "OWNER_CONTRACT").group());
        assertEquals("MINE", item(view, "DRIVER_DISCLOSURE").group());
        assertTrue(item(view, "OWNER_CONTRACT").clientAction());

        var received = disclosurePresent();
        var waiting = project(true, true, received, preflight(false));
        assertEquals("Arquivo recebido, aguardando revisão", item(waiting, "OWNER_CONTRACT").situation());
        assertEquals("Arquivo recebido, aguardando revisão", item(waiting, "DRIVER_DISCLOSURE").situation());
        assertNull(item(waiting, "OWNER_CONTRACT").destination());
        assertNull(item(waiting, "DRIVER_DISCLOSURE").destination());
        assertFalse(item(waiting, "DRIVER_DISCLOSURE").clientAction());
        assertTrue(waiting.requirements().stream().noneMatch(item -> "PROOF_ADMITTED".equals(item.state())));
        assertFalse(waiting.canRunE8());
    }

    @Test
    void openBoundaryPlanStaysSeparateFromMeasuredEnergy() {
        var closed = monitored(OpenApplicability.evaluate(new OpenApplicability.Input(false, true, true,
            "DCFC", "PHEV", "SIN", false, true, null, null, "NOT_DECLARED", null, null,
            "UNKNOWN", null, null), false),
            new OpenProjectStations.EnergyPosture("SELECTED", false, true, true));
        assertTrue(closed.requirements().stream().noneMatch(item ->
            List.of("BOUNDARY", "ED_METER", "EC_METER", "ENERGY_SERIES").contains(item.requirementId())));
        assertEquals("NOT_INCIDENT", item(closed, "ED_EC").state());

        var empty = monitored(disclosurePresent(), OpenProjectStations.EnergyPosture.NONE);
        var boundary = item(empty, "BOUNDARY");
        assertEquals("ABSENT", boundary.state());
        assertEquals("MINE", boundary.group());
        assertEquals("estacoes", boundary.destination().screen());
        assertEquals("PREP", boundary.ownerRole());
        assertTrue(boundary.clientAction());
        assertFalse(boundary.blocksExAntePd());
        assertFalse(boundary.admitsE8());
        assertTrue(boundary.reason().contains("não produz energia medida"));
        assertTrue(boundary.reason().contains("não vale zero"));
        assertEquals("NOT_INCIDENT", item(empty, "ED_EC").state());
        assertFalse(empty.exAnteRequiresMeasuredEnergy());
        assertFalse(empty.canRunE8());
        assertTrue(empty.requirements().stream().noneMatch(item ->
            List.of("ED", "EC", "ENERGY_SERIES", "ED_METER").contains(item.requirementId())));

        var planned = monitored(disclosurePresent(),
            new OpenProjectStations.EnergyPosture("SELECTED", true, false, true));
        assertTrue(planned.requirements().stream().noneMatch(item -> "BOUNDARY".equals(item.requirementId())));
        assertTrue(item(planned, "ED_METER").reason().contains("distinto da energia consumida"));
        assertTrue(item(planned, "ED_METER").reason().contains("não há energia medida"));
        assertEquals("monitorar", item(planned, "ED_METER").destination().screen());
        assertTrue(planned.requirements().stream().noneMatch(item ->
            "EC_METER".equals(item.requirementId()) || "ENERGY_SERIES".equals(item.requirementId())));

        var ready = monitored(disclosurePresent(),
            new OpenProjectStations.EnergyPosture("SELECTED", false, true, true));
        var series = item(ready, "ENERGY_SERIES");
        assertEquals("PENDING_MEASUREMENT", series.state());
        assertEquals("OP", series.ownerRole());
        assertFalse(series.blocksExAntePd());
        assertFalse(series.admitsE8());
        assertTrue(series.reason().contains("não vale zero"));
        assertTrue(series.reason().contains("calibração declarada não admite"));
        assertFalse(ready.toString().contains("2700"));
        assertFalse(ready.toString().contains("3000"));
        assertTrue(ready.requirements().stream().noneMatch(item ->
            "ED_METER".equals(item.requirementId()) || "ED".equals(item.requirementId())));

        var stale = monitored(disclosurePresent(),
            new OpenProjectStations.EnergyPosture("STALE", false, true, true));
        assertEquals("STALE", item(stale, "BOUNDARY").state());
        assertTrue(item(stale, "BOUNDARY").reason().contains("não podem ser presumidas"));
        assertTrue(stale.requirements().stream().noneMatch(item ->
            "ED_METER".equals(item.requirementId()) || "ENERGY_SERIES".equals(item.requirementId())));
        assertFalse(stale.canRunE8());
    }

    @Test
    void anotherMethodologyDoesNotReceiveOpenRequirements() {
        var foreign = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "ACM0002", true, true, true, true, true, true,
            disclosureMissing(), preflight(false), ProjectRequirementProjection.Owners.NONE));
        assertFalse(foreign.openBranch());
        assertEquals(1, foreign.requirements().size());
        assertEquals("METHODOLOGY_BRANCH", foreign.requirements().get(0).requirementId());
        assertEquals("OUT_OF_SCOPE", foreign.requirements().get(0).state());
        assertTrue(foreign.requirements().get(0).reason().contains("não usa os requisitos OPEN/DCFC"));
        assertFalse(foreign.canRunE8());
    }

    @Test
    void dateGapAsksForRecheckAndTriageChangeStalesOnlyThatGate() {
        var gap = OpenApplicability.evaluate(new OpenApplicability.Input(true, false, true,
            "DCFC", "BEV", "SIN", false, false, "Titular", "CONTRACT-001", "ON_CHARGER",
            "PHOTO", LocalDate.parse("2027-03-01"), "NO_OVERLAP_DECLARED",
            LocalDate.parse("2027-02-01"), null), false);
        var projected = project(true, true, gap, preflight(false));
        assertEquals("RECHECK_REQUIRED", item(projected, "RIGHTS_DATE_GAP").state());
        assertEquals("AWAITING_REVIEW", item(projected, "RIGHTS_DATE_GAP").group());

        var stale = project(true, true, disclosureMissing(true), preflight(true));
        assertEquals("STALE", item(stale, "TRIAGE_CHANGED").state());
        assertEquals("MINE", item(stale, "TRIAGE_CHANGED").group());
        assertEquals("NEEDS_EVIDENCE", item(stale, "DRIVER_DISCLOSURE").state());
        assertEquals("NOT_ADMITTED", item(stale, "EVR").state());
        assertNotEquals("STALE", item(stale, "EVR").state());
    }

    @Test
    void obtainedSourceAndCompleteDeclarationNeverAdmitE8OrAssignAPerson() {
        var forged = new OpenCalculationPreflight.Result("p", "SANDBOX", "engine", "VM0038",
            OpenCalculationPreflight.SCENARIO, "REQUEST_DATA", "BLOCKED_MISSING_DATA",
            true, true, true, new OpenCalculationPreflight.Emissions("0", "0", "0", "0", "0"),
            List.of(new OpenCalculationPreflight.Gate("EVR", "READY_FOR_HANDOFF",
                "Fonte marcada como pronta no catálogo.", "Vincular", "PREP"),
                new OpenCalculationPreflight.Gate("ED", "PENDING_MEASUREMENT", "sem medição", "medir", "OP"),
                new OpenCalculationPreflight.Gate("EC", "PENDING_MEASUREMENT", "sem medição", "medir", "OP")),
            List.of());
        var view = project(true, true, disclosurePresent(), forged);
        var evr = item(view, "EVR");
        assertEquals("NOT_ADMITTED", evr.state());
        assertFalse(evr.admitsE8());
        assertFalse(evr.clientAction());
        assertEquals("equipe CarbonFlow", evr.ownerRole());
        assertNull(evr.destination());
        assertFalse(view.canRunE8());
        assertFalse(view.canIssueVcu());
        assertFalse(view.toString().contains("SIM-"));
        assertTrue(view.requirements().stream().noneMatch(i -> i.ownerRole().contains("@")));
        assertTrue(view.limits().contains("Nenhum BE"));
    }

    @Test
    void linkedProjectSourceNeverBecomesAdmittedEnergy() {
        var base = preflight(false);
        var gates = new java.util.ArrayList<>(base.gates());
        gates.add(new OpenCalculationPreflight.Gate("E8_FLEET_SELECTION_M1", "NOT_ADMITTED",
            "Frota vinculada ao período M1.", "Conferir a seleção", "PREP/REV"));
        gates.add(new OpenCalculationPreflight.Gate("E8_INPUT_BINDING_M1", "APPROVED_FOR_SCOPE",
            "Energia vinculada ao período M1.", "Conferir o vínculo", "PREP/REV"));
        gates.add(new OpenCalculationPreflight.Gate("E8_FLEET_SELECTION_M2", "MISSING",
            "Nenhuma seleção de frota no período M2.", "Solicitar a seleção", "PREP"));
        gates.add(new OpenCalculationPreflight.Gate("E8_INPUT_BINDING_M2", "STALE",
            "O vínculo de energia de M2 ficou obsoleto.", "Preparar nova versão", "PREP/REV"));
        var linked = new OpenCalculationPreflight.Result(
            base.projectId(), base.environment(), base.engine(), base.methodology(),
            base.scenario(), base.workflowState(), base.calculationState(),
            base.canContinuePreparation(), true, true,
            new OpenCalculationPreflight.Emissions("6.00", "0", "0", "5.30", "5.30"),
            List.copyOf(gates), base.equationPlan());
        var view = project(true, true, disclosurePresent(), linked);
        var fleet = item(view, "E8_FLEET_SELECTION_M1");
        assertEquals("SOURCE_CANDIDATE", fleet.state());
        assertEquals("Fonte vinculada, não admitida", fleet.situation());
        assertEquals("AWAITING_SOURCE", fleet.group());
        assertFalse(fleet.clientAction());
        assertFalse(fleet.admitsE8());
        assertNull(fleet.destination());
        assertTrue(fleet.reason().contains("não produz BE, PE, LE ou ER"));
        assertTrue(fleet.affectedOutputs().contains("linha de base"));
        var energy = item(view, "E8_INPUT_BINDING_M1");
        assertEquals("SOURCE_CANDIDATE", energy.state());
        assertTrue(energy.affectedOutputs().contains("emissões do projeto"));
        assertEquals("NOT_ADMITTED", item(view, "E8_FLEET_SELECTION_M2").state());
        assertEquals("Ainda não vinculado", item(view, "E8_FLEET_SELECTION_M2").situation());
        assertEquals("STALE", item(view, "E8_INPUT_BINDING_M2").state());
        assertEquals("AWAITING_REVIEW", item(view, "E8_INPUT_BINDING_M2").group());
        assertNotEquals("STALE", item(view, "EVR").state());
        assertFalse(view.canRunE8());
        assertFalse(view.canIssueVcu());
        assertFalse(view.toString().contains("6.00"));
        assertFalse(view.toString().contains("5.30"));
    }

    @Test
    void calculationGrantDoesNotReceiveApplicabilityDetailOrMeasuredEnergyDuty() {
        var hidden = project(false, true, disclosureMissing(), preflight(false));
        assertTrue(hidden.requirements().stream().noneMatch(i ->
            "DRIVER_DISCLOSURE".equals(i.requirementId()) || "VMD0049_ROUTE".equals(i.requirementId())));
        assertFalse(hidden.requirements().toString().contains("Titular secreto"));
        assertEquals("NOT_INCIDENT", item(hidden, "ED_EC").state());
        assertFalse(item(hidden, "ED_EC").blocksExAntePd());
        var closed = project(false, false, disclosureMissing(), preflight(false));
        assertFalse(closed.diagnosticVisible());
        assertTrue(closed.requirements().isEmpty());
    }

    @Test
    void changedProofAsksForRecheckAndPreparedSourceStaysUnadmitted() {
        var recheck = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, false,
            disclosurePresent(), preflight(false),
            new ProjectRequirementProjection.Owners(true, "FLEET_SELECTION_MISSING", "BINDING_MISSING")));
        assertEquals("RECHECK_REQUIRED", item(recheck, "RIGHTS_DEPENDENCY").state());
        assertEquals("AWAITING_REVIEW", item(recheck, "RIGHTS_DEPENDENCY").group());
        assertFalse(item(recheck, "RIGHTS_DEPENDENCY").clientAction());
        assertFalse(item(recheck, "RIGHTS_DEPENDENCY").admitsE8());
        assertEquals("AWAITING_NORMATIVE_EVALUATION", item(recheck, "REGULATORY_SURPLUS").state());
        assertEquals("NOT_ADMITTED", item(recheck, "EVR").state());

        var gap = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, false,
            disclosureMissing(), preflight(false),
            new ProjectRequirementProjection.Owners(true, "PREPARATORY_NOT_ADMITTED", "CANDIDATE_NOT_ADMITTED")));
        assertEquals("NEEDS_EVIDENCE", item(gap, "DRIVER_DISCLOSURE").state());
        assertTrue(gap.requirements().stream().noneMatch(i -> "RIGHTS_DEPENDENCY".equals(i.requirementId())));
        assertEquals("SOURCE_CANDIDATE", item(gap, "EVR").state());
        assertEquals("SOURCE_CANDIDATE", item(gap, "EF_GRID").state());
        assertEquals("NOT_ADMITTED", item(gap, "EF_FUEL").state());
        assertFalse(item(gap, "EVR").admitsE8());
        assertEquals("NOT_ADMITTED", item(gap, "IR").state());
        assertEquals("NOT_INCIDENT", item(gap, "ED_EC").state());

        var staleFleet = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, false,
            disclosurePresent(), preflight(false),
            new ProjectRequirementProjection.Owners(false, "STALE_NOT_ADMITTED", "BINDING_MISSING")));
        assertEquals("STALE", item(staleFleet, "EVR").state());
        assertEquals("STALE", item(staleFleet, "AFEC_MPG").state());
        assertEquals("NOT_ADMITTED", item(staleFleet, "EF_FUEL").state());
        assertEquals("NOT_ADMITTED", item(staleFleet, "EF_GRID").state());
        assertEquals("NOT_ADMITTED", item(staleFleet, "IR").state());
        assertFalse(staleFleet.canRunE8());
    }

    @Test
    void recheckStaysOnTheDependencyThatChanged() {
        var rights = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, true,
            disclosurePresent(), preflight(false),
            new ProjectRequirementProjection.Owners(true, "PREPARATORY_NOT_ADMITTED", "CANDIDATE_NOT_ADMITTED",
                OpenProjectStations.EnergyPosture.NONE)));
        assertEquals("RECHECK_REQUIRED", item(rights, "RIGHTS_DEPENDENCY").state());
        assertTrue(item(rights, "RIGHTS_DEPENDENCY").reason().contains("permanecem no estado"));
        assertEquals("AWAITING_NORMATIVE_EVALUATION", item(rights, "MARKET_ROUTE").state());
        assertEquals("SOURCE_CANDIDATE", item(rights, "EVR").state());
        assertEquals("SOURCE_CANDIDATE", item(rights, "EF_GRID").state());
        assertEquals("NOT_ADMITTED", item(rights, "EF_FUEL").state());
        assertFalse(rights.canRunE8());

        var base = preflight(false);
        var gates = new java.util.ArrayList<>(base.gates());
        gates.add(new OpenCalculationPreflight.Gate("E8_FLEET_SELECTION_M1", "NOT_ADMITTED",
            "Frota vinculada ao período M1.", "Conferir a seleção", "PREP/REV"));
        gates.add(new OpenCalculationPreflight.Gate("E8_INPUT_BINDING_M1", "NOT_ADMITTED",
            "Energia vinculada ao período M1.", "Conferir o vínculo", "PREP/REV"));
        gates.add(new OpenCalculationPreflight.Gate("E8_INPUT_BINDING_M2", "MISSING",
            "Nenhum vínculo de energia no período M2.", "Preparar o vínculo", "PREP"));
        var linked = new OpenCalculationPreflight.Result(
            base.projectId(), base.environment(), base.engine(), base.methodology(),
            base.scenario(), base.workflowState(), base.calculationState(),
            base.canContinuePreparation(), true, true,
            new OpenCalculationPreflight.Emissions("6.00", "0", "0", "5.30", "5.30"),
            List.copyOf(gates), base.equationPlan());
        var energy = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, true,
            disclosurePresent(), linked,
            new ProjectRequirementProjection.Owners(false, "PREPARATORY_NOT_ADMITTED", "STALE_NOT_ADMITTED",
                OpenProjectStations.EnergyPosture.NONE)));
        assertEquals("STALE", item(energy, "EF_GRID").state());
        assertEquals("STALE", item(energy, "E8_INPUT_BINDING_M1").state());
        assertTrue(item(energy, "E8_INPUT_BINDING_M1").reason().contains("A frota, o fator fóssil"));
        assertEquals("SOURCE_CANDIDATE", item(energy, "EVR").state());
        assertEquals("SOURCE_CANDIDATE", item(energy, "E8_FLEET_SELECTION_M1").state());
        assertEquals("NOT_ADMITTED", item(energy, "EF_FUEL").state());
        assertEquals("NOT_ADMITTED", item(energy, "IR").state());
        assertEquals("NOT_ADMITTED", item(energy, "DY").state());
        assertEquals("Ainda não vinculado", item(energy, "E8_INPUT_BINDING_M2").situation());
        assertNotEquals("STALE", item(energy, "E8_INPUT_BINDING_M2").state());
        assertFalse(energy.toString().contains("6.00"));

        var station = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, true,
            disclosurePresent(), linked,
            new ProjectRequirementProjection.Owners(false, "PREPARATORY_NOT_ADMITTED", "CANDIDATE_NOT_ADMITTED",
                new OpenProjectStations.EnergyPosture("STALE", false, true, true))));
        assertEquals("STALE", item(station, "BOUNDARY").state());
        assertEquals("STALE", item(station, "EF_GRID").state());
        assertTrue(item(station, "EF_GRID").reason().contains("Só o vínculo de energia"));
        assertEquals("STALE", item(station, "E8_INPUT_BINDING_M1").state());
        assertEquals("SOURCE_CANDIDATE", item(station, "EVR").state());
        assertEquals("SOURCE_CANDIDATE", item(station, "AFEC_MPG").state());
        assertEquals("SOURCE_CANDIDATE", item(station, "E8_FLEET_SELECTION_M1").state());
        assertEquals("NOT_ADMITTED", item(station, "EF_FUEL").state());
        assertEquals("AWAITING_NORMATIVE_EVALUATION", item(station, "MARKET_ROUTE").state());
        assertEquals("NOT_INCIDENT", item(station, "ED_EC").state());
        assertTrue(station.requirements().stream().noneMatch(item ->
            "ENERGY_SERIES".equals(item.requirementId()) || item.admitsE8()));
        assertFalse(station.canRunE8());
        assertFalse(station.canIssueVcu());
    }

    @Test
    void measuredPeriodDoesNotBlockTheExAnteDocumentOrAdmitE8() {
        var base = preflight(false);
        var gates = new java.util.ArrayList<>(base.gates());
        gates.add(new OpenCalculationPreflight.Gate("MR_DEPENDENCY_M1", "REVIEW_REQUIRED",
            "O período M1 ainda não passou pela admissão metodológica. QA interno não libera E8.",
            "Revisar o vínculo do período", "PREP/REV"));
        gates.add(new OpenCalculationPreflight.Gate("E8_ENERGY_INPUT_M1", "PENDING_MEASUREMENT",
            "Pacote horário do período M1: CANDIDATE_MISSING. A preparação não substitui admissão.",
            "Conferir a cadeia do período", "PREP/REV"));
        gates.add(new OpenCalculationPreflight.Gate("ED", "MISSING_BINDING",
            "ED ausente no período", "Informar a série", "OP"));
        gates.add(new OpenCalculationPreflight.Gate("EC", "MISSING_BINDING",
            "EC de 3000 kWh no período de teste", "Informar a série", "OP"));
        var lookingAdmitted = new OpenCalculationPreflight.Result(
            base.projectId(), base.environment(), base.engine(), base.methodology(),
            base.scenario(), base.workflowState(), base.calculationState(),
            base.canContinuePreparation(), true, true, base.emissions(),
            List.copyOf(gates), base.equationPlan());
        var view = project(true, true, disclosurePresent(), lookingAdmitted);
        var exAnte = item(view, "ED_EC");
        assertEquals("NOT_INCIDENT", exAnte.state());
        assertFalse(exAnte.blocksExAntePd());
        assertFalse(exAnte.clientAction());
        assertEquals("NOT_ADMITTED", item(view, "MR_DEPENDENCY_M1").state());
        assertFalse(item(view, "MR_DEPENDENCY_M1").blocksExAntePd());
        assertFalse(item(view, "MR_DEPENDENCY_M1").clientAction());
        assertEquals("NOT_ADMITTED", item(view, "E8_ENERGY_INPUT_M1").state());
        assertTrue(view.requirements().stream().noneMatch(item ->
            "ED".equals(item.requirementId()) || "EC".equals(item.requirementId())));
        assertFalse(view.canRunE8());
        assertFalse(view.canIssueVcu());
        assertFalse(view.exAnteRequiresMeasuredEnergy());
        assertFalse(view.requirements().stream().anyMatch(item -> item.reason().contains("3000")));
    }

    private static ProjectRequirementProjection.View monitored(OpenApplicability.Readiness readiness,
            OpenProjectStations.EnergyPosture posture) {
        return ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, true,
            readiness, preflight(false),
            new ProjectRequirementProjection.Owners(false, "FLEET_SELECTION_MISSING", "BINDING_MISSING", posture)));
    }

    private static ProjectRequirementProjection.View project(boolean applicability, boolean calculation,
            OpenApplicability.Readiness readiness, OpenCalculationPreflight.Result preflight) {
        return ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", applicability, calculation, applicability, true, calculation, false,
            applicability ? readiness : null, calculation ? preflight : null,
            ProjectRequirementProjection.Owners.NONE));
    }

    private static ProjectRequirementProjection.Item item(ProjectRequirementProjection.View view, String id) {
        return view.requirements().stream().filter(i -> id.equals(i.requirementId())).findFirst().orElseThrow();
    }

    private static OpenApplicability.Input dates(String disclosure, String operational, String vcs) {
        return new OpenApplicability.Input(true, false, true,
            "DCFC", "BEV", "SIN", false, false, "Titular", "CONTRACT-1", "ON_CHARGER",
            "PHOTO-2027", LocalDate.parse(disclosure), "NO_OVERLAP_DECLARED",
            null, null, "PARTY-1", "CONTRACT-1",
            "OP-1", LocalDate.parse(operational), "VCS-1", LocalDate.parse(vcs));
    }

    private static OpenApplicability.Readiness disclosureMissing() {
        return disclosureMissing(false);
    }

    private static OpenApplicability.Readiness disclosureMissing(boolean stale) {
        return OpenApplicability.evaluate(new OpenApplicability.Input(true, false, true,
            "DCFC", "BEV", "SIN", false, false, "Titular secreto", "CONTRACT-001",
            "NOT_DECLARED", null, null, "UNKNOWN", LocalDate.parse("2027-02-01"), null,
            "PARTY-1", "CONTRACT-1", null, null, null, null,
            "11111111-1111-4111-8111-111111111111", null), stale);
    }

    private static OpenApplicability.Readiness disclosurePresent() {
        return OpenApplicability.evaluate(new OpenApplicability.Input(true, false, true,
            "DCFC", "BEV", "SIN", false, false, "Titular", "CONTRACT-001", "ON_CHARGER",
            "PHOTO-2027", LocalDate.parse("2027-01-15"), "NO_OVERLAP_DECLARED",
            LocalDate.parse("2027-02-01"), null, "PARTY-1", "CONTRACT-1",
            null, null, null, null,
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"), false);
    }

    private static OpenCalculationPreflight.Result preflight(boolean stale) {
        return OpenCalculationPreflight.evaluate(new OpenCalculationPreflight.Context(
            "p", "SANDBOX", "VM0038", "BR", "LDV", stale ? 2 : 1,
            new OpenCalculationPreflight.Applicability(1, 1, true, false, "DCFC", "BEV", "SIN", false, false)));
    }
}
