package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * CF-SPEC-G04 (internal engineering evidence only): the executor reads Dy from the versioned, independently
 * reviewed assessment of the project year, never from the legacy scalar pin. Nothing is admitted.
 */
@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class OpenDyRunIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    private OpenE8TestFixture fx;
    private OpenE8TestFixture.Scenario s;

    private String key(){return OpenE8TestFixture.key();}
    private String runs(String period){
        return "/v1/projects/"+s.project()+"/technical/open-monitoring/dossiers/"+period+"/e8-runs";
    }
    private JsonNode dyInput(String period) throws Exception {
        var response=fx.call("GET",runs(period),s.owner().cookie(),null,null);
        assertEquals(200,response.statusCode(),response.body());
        for(JsonNode input:fx.json(response).at("/currentSelection/inputs"))
            if("DY".equals(input.path("code").asText())) return input;
        throw new AssertionError("DY input absent from the selection of "+period);
    }
    private String dyState(String period) throws Exception {return dyInput(period).path("state").asText();}

    private JsonNode record(int year,String version,String suffix) throws Exception {
        var response=fx.postMethod("/v1/platform/open-dy-assessments/"+s.project(),s.staffManager().cookie(),key(),
            "\""+version+"\"","{\"projectYear\":"+year+",\"outcome\":\"NO_FLEET_CREDITS_DECLARED\",\"erc\":\"0\",\"erf\":\"0\","
            +"\"programs\":\"Programas pesquisados para o teste "+suffix+"\",\"projectReferences\":\"Referências do teste "+suffix+"\","
            +"\"evidenceLocator\":\"Localizador do teste "+suffix+"\",\"coverageNote\":\"Cobertura declarada no teste "+suffix+"\"}");
        assertEquals(201,response.statusCode(),response.body());
        return fx.json(response);
    }
    private int review(JsonNode recorded,String outcome,String cookie,String match) throws Exception {
        var response=fx.postMethod("/v1/platform/open-dy-assessments/"+s.project()+"/"+recorded.path("id").asText()+"/review",
            cookie,key(),"\""+match+"\"","{\"assessmentSha256\":\""+recorded.path("assessmentSha256").asText()+"\","
            +"\"outcome\":\""+outcome+"\",\"ruleReference\":\"VM0038-1.1-EQ8 (Dy)\","
            +"\"evidenceReferences\":\"Registro de pesquisa de teste\","
            +"\"reason\":\"Parecer interno independente de teste, sem admissão normativa.\"}");
        return response.statusCode();
    }

    @Test
    void executorReadsTheReviewedAssessmentOfTheProjectYearAndFollowsItsVersions() throws Exception {
        fx=new OpenE8TestFixture(database,mapper,base);
        s=fx.openProject("2023-01-01");
        fx.preparePeriod(s,"Y1-M1",2023,2);
        fx.preparePeriod(s,"Y2-M1",2024,2);
        fx.preparePeriod(s,"Y2-M2",2024,3);
        fx.pinCompleteSet(s.project(),s.org(),s.staffManager().id(),s.staffReviewer().id(),2023);
        fx.pinCompleteSet(s.project(),s.org(),s.staffManager().id(),s.staffReviewer().id(),2024);

        // The legacy DY pin that pinCompleteSet publishes never authorises a new attempt.
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",dyState("Y1-M1"));
        assertEquals("MISSING_FOR_PROJECT_YEAR",dyState("Y2-M1"));
        assertEquals("STRUCTURED_PROJECT_YEAR",dyInput("Y2-M1").path("basis").asText());

        // Assessed but not reviewed: not usable. The author cannot review it.
        JsonNode recorded=record(2,"0","v1");
        assertEquals("REVIEW_REQUIRED",dyState("Y2-M1"));
        assertEquals(404,review(recorded,"CHECKED_INTERNAL",s.staffManager().cookie(),"1"));
        assertEquals(201,review(recorded,"CHECKED_INTERNAL",s.staffReviewer().cookie(),"1"));
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",dyState("Y2-M1"));
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",dyState("Y2-M2"),"both periods of the year read the same assessment");
        assertEquals(dyInput("Y2-M1").path("id").asText(),dyInput("Y2-M2").path("id").asText());
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",dyState("Y1-M1"),"another project year is untouched");

        // A run records the exact Dy context and never admits anything.
        var attempt=fx.postMethod(runs("Y2-M2"),s.owner().cookie(),key(),"\"0\"","{}");
        assertEquals(201,attempt.statusCode(),attempt.body());
        JsonNode run=fx.json(attempt);
        assertFalse(run.path("canIssueVcu").asBoolean());
        assertEquals(recorded.path("id").asText(),run.at("/dyContext/assessmentId").asText());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",run.at("/dyContext/state").asText());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",OpenE8TestFixture.calcGate(run,"DY").path("state").asText());
        assertFalse(run.path("stale").asBoolean());
        var replay=fx.postMethod(runs("Y2-M2"),s.owner().cookie(),key(),"\"1\"","{}");
        assertEquals(200,replay.statusCode(),replay.body());
        assertEquals(run.path("id").asText(),fx.json(replay).path("id").asText());
        assertEquals(run.at("/dyContext/contextSha256").asText(),fx.json(replay).at("/dyContext/contextSha256").asText());

        // A newer assessment supersedes the reviewed one: the period falls back to REVIEW_REQUIRED and the old
        // attempt turns stale, while its history stays.
        JsonNode second=record(2,"1","v2");
        assertEquals("REVIEW_REQUIRED",dyState("Y2-M2"));
        var listed=fx.json(fx.call("GET",runs("Y2-M2"),s.owner().cookie(),null,null));
        assertTrue(listed.at("/runs/0/stale").asBoolean(),"the attempt that read the old assessment is stale");
        assertEquals(recorded.path("id").asText(),listed.at("/runs/0/dyContext/assessmentId").asText());

        // A rejected review is not a check.
        assertEquals(201,review(second,"REJECTED",s.staffReviewer().cookie(),"2"));
        assertEquals("REVIEW_REQUIRED",dyState("Y2-M2"));
        var blocked=fx.postMethod(runs("Y2-M2"),s.owner().cookie(),key(),"\"1\"","{}");
        assertEquals(201,blocked.statusCode(),blocked.body());
        JsonNode next=fx.json(blocked);
        assertEquals(2,next.path("version").asInt(),"the new reading creates a new attempt, never rewrites the old one");
        assertEquals("REVIEW_REQUIRED",OpenE8TestFixture.calcGate(next,"DY").path("state").asText());
        assertEquals("BLOCKED",next.path("state").asText());
    }
}
