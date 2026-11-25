package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * CF-SPEC-G05 (internal engineering evidence only): the IR preparatory chain across project years, with real HTTP
 * calls and canonical data. Year 1 = 2023 (ex ante plan + initial baseline review), year 2 = 2024 with two periods
 * (annual observed update with dated evidence per metric, then reuse by the second period, then a superseded
 * fleet hand-off). Nothing is admitted: the IR gate never leaves its NOT_ADMITTED states and no VCU can be issued.
 * Years are in the past because the temporal evidence may not be dated after the review.
 */
@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class OpenIrTwoPeriodIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    private OpenE8TestFixture fx;
    private OpenE8TestFixture.Scenario s;

    private String key(){return OpenE8TestFixture.key();}
    private String runs(String period){
        return "/v1/projects/"+s.project()+"/technical/open-monitoring/dossiers/"+period+"/e8-runs";
    }
    /** The IR input reference the E8 run selection exposes for one period. */
    private JsonNode irInput(String period) throws Exception {
        var response=fx.call("GET",runs(period),s.owner().cookie(),null,null);
        assertEquals(200,response.statusCode(),response.body());
        for(JsonNode input:fx.json(response).at("/currentSelection/inputs"))
            if("IR".equals(input.path("code").asText())) return input;
        throw new AssertionError("IR input absent from the selection of "+period);
    }
    private String irState(String period) throws Exception {return irInput(period).path("state").asText();}

    private static final String PLAN_ITEM="{\"source\":\"Registro interno de atualização\",\"responsible\":\"Equipe técnica\","
        +"\"procedure\":\"Procedimento de atualização anual descrito para o teste.\"}";
    private static final String EX_ANTE="{\"evr\":"+PLAN_ITEM+",\"afec\":"+PLAN_ITEM+",\"mpg\":"+PLAN_ITEM+",\"fuel\":"+PLAN_ITEM
        +",\"rationale\":\"Plano ex ante de atualização dos quatro parâmetros no segundo ano.\"}";

    private JsonNode record(String period,String match) throws Exception {
        var response=fx.postMethod("/v1/platform/open-ir-assessments/"+s.project(),s.staffManager().cookie(),key(),match,
            "{\"period\":\""+period+"\",\"frequency\":\"ANNUAL\",\"basis\":\"PROJECT_SPECIFIC\","
            +"\"rationale\":\"Atualização anual declarada para teste integrado, sem admissão.\"}");
        assertEquals(201,response.statusCode(),response.body());
        return fx.json(response);
    }
    private String evidence(String metric){return evidence(metric,"2024-12-31");}
    private String evidence(String metric,String validUntil){
        return "{\"metric\":\""+metric+"\",\"updatedOn\":\"2024-03-10\",\"validFrom\":\"2024-01-01\","
            +"\"validUntil\":\""+validUntil+"\",\"evidenceLocator\":\"Registro de atualização "+metric+"\"}";
    }
    /** Year-1 initial review of {@code period}, pinning the current ex ante plan. */
    private void initialReview(JsonNode recorded,JsonNode planRevision,String latest) throws Exception {
        var review=fx.postMethod("/v1/platform/open-ir-assessments/"+s.project()+"/"+recorded.path("id").asText()+"/review",
            s.staffReviewer().cookie(),key(),"\""+latest+"\"","{\"assessmentSha256\":\""+recorded.path("assessmentSha256").asText()
            +"\",\"outcome\":\"INITIAL_BASELINE_CHECKED\",\"annualUpdateBasis\":\"PLAN_ONLY\","
            +"\"evidenceLocator\":\"Plano ex ante e referências do ano 1\","
            +"\"reason\":\"Parecer inicial independente, sem afirmar atualização observada.\","
            +"\"initialPlan\":{\"evr\":\"MATCH: atualização anual do EVR conforme o plano ex ante.\","
            +"\"afec\":\"MATCH: atualização anual do AFEC conforme o plano ex ante.\","
            +"\"mpg\":\"MATCH: atualização anual do MPG conforme o plano ex ante.\","
            +"\"fuel\":\"MATCH: atualização anual do fator fóssil conforme o plano ex ante.\"},"
            +"\"exAntePlanId\":\""+planRevision.path("id").asText()+"\",\"exAntePlanVersion\":"
            +planRevision.path("version").asInt()+",\"exAntePlanSha256\":\""+planRevision.path("planSha256").asText()+"\"}");
        assertEquals(201,review.statusCode(),review.body());
    }

    @Test
    void irChainAcrossYearOneAndTwoPeriodsOfYearTwo() throws Exception {
        fx=new OpenE8TestFixture(database,mapper,base);
        s=fx.openProject("2023-01-01");
        var y1=fx.preparePeriod(s,"Y1-M1",2023,2);
        var y2a=fx.preparePeriod(s,"Y2-M1",2024,2);
        var y2b=fx.preparePeriod(s,"Y2-M2",2024,3);
        fx.pinCompleteSet(s.project(),s.org(),s.staffManager().id(),s.staffReviewer().id(),2023);
        fx.pinCompleteSet(s.project(),s.org(),s.staffManager().id(),s.staffReviewer().id(),2024);

        // Before any structured assessment nothing authorises IR, in either year.
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",irState("Y1-M1"));
        assertEquals("MISSING_FOR_PROJECT_YEAR",irState("Y2-M1"));
        assertEquals("MISSING_FOR_PROJECT_YEAR",irState("Y2-M2"));
        assertEquals(2023,fx.json(fx.call("GET",runs("Y1-M1"),s.owner().cookie(),null,null))
            .at("/currentSelection/timing/sourceYear").asInt());
        assertEquals(2,fx.json(fx.call("GET",runs("Y2-M2"),s.owner().cookie(),null,null))
            .at("/currentSelection/timing/projectYear").asInt());

        // Year 1: ex ante plan by the project owner, structured assessment by the staff manager, initial review by
        // a different staff reviewer that pins the plan version and hash.
        String planUrl="/v1/projects/"+s.project()+"/technical/open-ir-ex-ante-plan";
        var plan=fx.postMethod(planUrl,s.owner().cookie(),key(),"\"0\"",EX_ANTE);
        assertEquals(201,plan.statusCode(),plan.body());
        JsonNode planRevision=fx.json(plan);
        JsonNode y1Recorded=record("Y1-M1","\"0\"");
        String y1Assessment=y1Recorded.path("id").asText(),y1Sha=y1Recorded.path("assessmentSha256").asText();
        var initialReview=fx.postMethod("/v1/platform/open-ir-assessments/"+s.project()+"/"+y1Assessment+"/review",
            s.staffReviewer().cookie(),key(),"\"1\"","{\"assessmentSha256\":\""+y1Sha+"\",\"outcome\":\"INITIAL_BASELINE_CHECKED\","
            +"\"annualUpdateBasis\":\"PLAN_ONLY\",\"evidenceLocator\":\"Plano ex ante e referências do ano 1\","
            +"\"reason\":\"Parecer inicial independente, sem afirmar atualização observada.\","
            +"\"initialPlan\":{\"evr\":\"MATCH: atualização anual do EVR conforme o plano ex ante.\","
            +"\"afec\":\"MATCH: atualização anual do AFEC conforme o plano ex ante.\","
            +"\"mpg\":\"MATCH: atualização anual do MPG conforme o plano ex ante.\","
            +"\"fuel\":\"MATCH: atualização anual do fator fóssil conforme o plano ex ante.\"},"
            +"\"exAntePlanId\":\""+planRevision.path("id").asText()+"\",\"exAntePlanVersion\":"
            +planRevision.path("version").asInt()+",\"exAntePlanSha256\":\""+planRevision.path("planSha256").asText()+"\"}");
        assertEquals(201,initialReview.statusCode(),initialReview.body());
        assertEquals("FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED",irState("Y1-M1"));

        // Year 2, first period: assessed but not yet reviewed; the second period does not match it.
        JsonNode y2Recorded=record("Y2-M1","\"0\"");
        String y2Assessment=y2Recorded.path("id").asText();
        assertEquals("REVIEW_REQUIRED",irState("Y2-M1"));
        assertEquals("PERIOD_REFERENCE_MISMATCH",irState("Y2-M2"));

        // Annual review: independent reviewer, observed update, link to the year-1 assessment and dated evidence
        // for the four metrics. Both periods of year 2 then read the same checked, still not admitted, IR.
        String observed="{\"assessmentSha256\":\""+y2Recorded.path("assessmentSha256").asText()+"\",\"outcome\":\"CHECKED_INTERNAL\","
            +"\"annualUpdateBasis\":\"OBSERVED_UPDATE\",\"evidenceLocator\":\"Registros de atualização do ano 2\","
            +"\"reason\":\"Atualização observada conferida internamente, sem admissão normativa.\","
            +"\"priorAssessmentId\":\""+y1Assessment+"\",\"temporalEvidence\":["
            +evidence("EVR")+","+evidence("AFEC")+","+evidence("MPG")+","+evidence("EF_FUEL")+"]}";
        String reviewUrl="/v1/platform/open-ir-assessments/"+s.project()+"/"+y2Assessment+"/review";
        var checked=fx.postMethod(reviewUrl,s.staffReviewer().cookie(),key(),"\"1\"",observed);
        assertEquals(201,checked.statusCode(),checked.body());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",irState("Y2-M1"));
        JsonNode reused=irInput("Y2-M2");
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",reused.path("state").asText(),
            "The second period of the same year must reuse the checked assessment");
        assertEquals(irInput("Y2-M1").path("id").asText(),reused.path("id").asText(),
            "Reuse points at the same assessment, it does not create another one");

        // Re-execution: running the reused period again with unchanged inputs returns the same immutable attempt
        // (HTTP 200, same id and version, same IR context hash); nothing becomes calculable or issuable.
        var firstRun=fx.postMethod(runs("Y2-M2"),s.owner().cookie(),key(),"\"0\"","{}");
        assertEquals(201,firstRun.statusCode(),firstRun.body());
        JsonNode runOne=fx.json(firstRun);
        assertEquals("BLOCKED",runOne.path("state").asText());
        assertFalse(runOne.path("canIssueVcu").asBoolean());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",OpenE8TestFixture.calcGate(runOne,"IR_STRUCTURED").path("state").asText());
        assertEquals(y2Assessment,runOne.at("/irContext/assessmentId").asText());
        var secondRun=fx.postMethod(runs("Y2-M2"),s.owner().cookie(),key(),"\"1\"","{}");
        assertEquals(200,secondRun.statusCode(),secondRun.body());
        JsonNode runTwo=fx.json(secondRun);
        assertEquals(runOne.path("id").asText(),runTwo.path("id").asText());
        assertEquals(1,runTwo.path("version").asInt());
        assertEquals(runOne.at("/irContext/contextSha256").asText(),runTwo.at("/irContext/contextSha256").asText());

        // Revocation: the licence behind the year-2 fleet snapshot is revoked by an independent decision. The IR
        // read by both periods of year 2 goes stale, a new assessment cannot be recorded, a new run shows the stale
        // gate, and year 1 (a different source year and licence) is untouched.
        database.transaction(c->{
            PilotSql.update(c,"""
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                SELECT i.source_license_id,2,'REVOKED',?,'Licença revogada em teste integrado do IR'
                FROM app_fleet_composition_snapshot fs JOIN app_fleet_ingestion i ON i.id=fs.ingestion_id
                WHERE fs.organization_id=? AND fs.observation_year=2024
                """,s.staffReviewer().id(),s.org());
            return null;
        });
        assertEquals("STALE",irState("Y2-M1"));
        assertEquals("STALE",irState("Y2-M2"));
        assertEquals("FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED",irState("Y1-M1"));
        var blockedRecord=fx.postMethod("/v1/platform/open-ir-assessments/"+s.project(),s.staffManager().cookie(),key(),"\"1\"",
            "{\"period\":\"Y2-M1\",\"frequency\":\"ANNUAL\",\"basis\":\"PROJECT_SPECIFIC\","
            +"\"rationale\":\"Nova avaliação após revogação, deve ser recusada.\"}");
        assertEquals(409,blockedRecord.statusCode(),blockedRecord.body());
        var afterRevocation=fx.postMethod(runs("Y2-M2"),s.owner().cookie(),key(),"\"1\"","{}");
        assertEquals(201,afterRevocation.statusCode(),afterRevocation.body());
        assertEquals("STALE",OpenE8TestFixture.calcGate(fx.json(afterRevocation),"IR_STRUCTURED").path("state").asText());
        assertEquals("STALE",fx.json(afterRevocation).path("state").asText());
        assertFalse(fx.json(afterRevocation).path("canIssueVcu").asBoolean());
        assertEquals(2,fx.json(afterRevocation).path("version").asInt(),"Revocation creates a new attempt, never rewrites the old one");
    }

    /**
     * G05 gaps, synthetic data: declared validity that stops covering a later period of the same year, and a
     * second window inside project year 1. Characterises the current conservative behaviour.
     */
    @Test
    void validityNotCoveringALaterPeriodAndSeveralWindowsInYearOne() throws Exception {
        fx=new OpenE8TestFixture(database,mapper,base);
        s=fx.openProject("2023-01-01");
        fx.preparePeriod(s,"Y1-M1",2023,2);
        fx.preparePeriod(s,"Y1-M2",2023,3);
        fx.preparePeriod(s,"Y2-M1",2024,2);
        fx.preparePeriod(s,"Y2-M3",2024,8);
        fx.pinCompleteSet(s.project(),s.org(),s.staffManager().id(),s.staffReviewer().id(),2023);
        fx.pinCompleteSet(s.project(),s.org(),s.staffManager().id(),s.staffReviewer().id(),2024);

        var plan=fx.postMethod("/v1/projects/"+s.project()+"/technical/open-ir-ex-ante-plan",s.owner().cookie(),key(),"\"0\"",EX_ANTE);
        assertEquals(201,plan.statusCode(),plan.body());
        JsonNode planRevision=fx.json(plan);
        JsonNode y1=record("Y1-M1","\"0\"");
        initialReview(y1,planRevision,"1");
        assertEquals("FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED",irState("Y1-M1"));

        // Evidence declared valid only until April: it covers February, not August of the same year.
        JsonNode y2=record("Y2-M1","\"0\"");
        var review=fx.postMethod("/v1/platform/open-ir-assessments/"+s.project()+"/"+y2.path("id").asText()+"/review",
            s.staffReviewer().cookie(),key(),"\"1\"","{\"assessmentSha256\":\""+y2.path("assessmentSha256").asText()+"\","
            +"\"outcome\":\"CHECKED_INTERNAL\",\"annualUpdateBasis\":\"OBSERVED_UPDATE\","
            +"\"evidenceLocator\":\"Registros de atualização do ano 2\","
            +"\"reason\":\"Atualização observada conferida internamente, vigência curta.\","
            +"\"priorAssessmentId\":\""+y1.path("id").asText()+"\",\"temporalEvidence\":["
            +evidence("EVR","2024-04-30")+","+evidence("AFEC","2024-04-30")+","+evidence("MPG","2024-04-30")+","
            +evidence("EF_FUEL","2024-04-30")+"]}");
        assertEquals(201,review.statusCode(),review.body());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",irState("Y2-M1"));
        assertEquals("ANNUAL_EVIDENCE_RECHECK_REQUIRED",irState("Y2-M3"),
            "A later period outside the declared validity must not reuse the assessment");
        var blocked=fx.postMethod(runs("Y2-M3"),s.owner().cookie(),key(),"\"0\"","{}");
        assertEquals(201,blocked.statusCode(),blocked.body());
        assertEquals("ANNUAL_EVIDENCE_RECHECK_REQUIRED",OpenE8TestFixture.calcGate(fx.json(blocked),"IR_STRUCTURED").path("state").asText());
        assertFalse(fx.json(blocked).path("canIssueVcu").asBoolean());

        // Year 1, second window: the first-year review is per period, it is not reused across windows.
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",irState("Y1-M2"));
        JsonNode y1b=record("Y1-M2","\"1\"");
        initialReview(y1b,planRevision,"2");
        assertEquals("FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED",irState("Y1-M2"));
        // The newer year-1 version supersedes the first one: the earlier window must be reviewed again.
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",irState("Y1-M1"));
    }
}
