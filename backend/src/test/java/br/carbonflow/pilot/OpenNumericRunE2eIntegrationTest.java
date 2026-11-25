package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
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
 * CF-SPEC-G04/G05/G09 (internal engineering evidence only, synthetic data): the first numeric rehearsal through real
 * HTTP and canonical data. Year 1 = 2023, year 2 = 2024 with two periods. IR is checked for both years, Dy for year 2
 * is a positive, licensed, itemized and independently reviewed assessment (ERC 75, ERF 25, so Dy = 0.75).
 * Nothing is admitted: no VCU, no {@code usableForE8}, the normative gates stay NOT_ADMITTED.
 */
@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class OpenNumericRunE2eIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    private OpenE8TestFixture fx;
    private OpenE8TestFixture.Scenario s;

    private static final String SOURCE="E2E-DY-REGISTRY";
    private static final String PLAN_ITEM="{\"source\":\"Registro interno de atualização\",\"responsible\":\"Equipe técnica\","
        +"\"procedure\":\"Procedimento de atualização anual descrito para o teste.\"}";
    private static final String EX_ANTE="{\"evr\":"+PLAN_ITEM+",\"afec\":"+PLAN_ITEM+",\"mpg\":"+PLAN_ITEM+",\"fuel\":"+PLAN_ITEM
        +",\"rationale\":\"Plano ex ante de atualização dos quatro parâmetros no segundo ano.\"}";

    private String key(){return OpenE8TestFixture.key();}
    private String runs(String period){
        return "/v1/projects/"+s.project()+"/technical/open-monitoring/dossiers/"+period+"/e8-runs";
    }
    private String evidence(String metric){
        return "{\"metric\":\""+metric+"\",\"updatedOn\":\"2024-03-10\",\"validFrom\":\"2024-01-01\","
            +"\"validUntil\":\"2024-12-31\",\"evidenceLocator\":\"Registro de atualização "+metric+"\"}";
    }
    private JsonNode post(String path,String cookie,String match,String body,int status) throws Exception {
        var response=fx.postMethod(path,cookie,key(),match,body);
        assertEquals(status,response.statusCode(),response.body());
        return fx.json(response);
    }
    private JsonNode recordIr(String period,String match) throws Exception {
        return post("/v1/platform/open-ir-assessments/"+s.project(),s.staffManager().cookie(),match,
            "{\"period\":\""+period+"\",\"frequency\":\"ANNUAL\",\"basis\":\"PROJECT_SPECIFIC\","
            +"\"rationale\":\"Atualização anual declarada para teste integrado, sem admissão.\"}",201);
    }
    private static BigDecimal dec(JsonNode node){return new BigDecimal(node.asText());}

    @Test
    void firstNumericRehearsalUsesTheReviewedDyAndStaysNotAdmitted() throws Exception {
        fx=new OpenE8TestFixture(database,mapper,base);
        s=fx.openProject("2023-01-01");
        fx.preparePeriod(s,"Y1-M1",2023,2);
        fx.preparePeriod(s,"Y2-M1",2024,2);
        fx.preparePeriod(s,"Y2-M2",2024,3);
        fx.pinCompleteSet(s.project(),s.org(),s.staffManager().id(),s.staffReviewer().id(),2023);
        fx.pinCompleteSet(s.project(),s.org(),s.staffManager().id(),s.staffReviewer().id(),2024);
        // The owner authored the energy binding, so the attempt is run by a different PREP collaborator (segregation).
        var prep=fx.actor();
        fx.grant(s.project(),s.org(),s.owner().id(),prep.id(),"PREP");

        // IR: year 1 ex ante plan + initial review, year 2 annual observed update with dated evidence.
        JsonNode plan=post("/v1/projects/"+s.project()+"/technical/open-ir-ex-ante-plan",s.owner().cookie(),"\"0\"",EX_ANTE,201);
        JsonNode y1=recordIr("Y1-M1","\"0\"");
        post("/v1/platform/open-ir-assessments/"+s.project()+"/"+y1.path("id").asText()+"/review",s.staffReviewer().cookie(),"\"1\"",
            "{\"assessmentSha256\":\""+y1.path("assessmentSha256").asText()+"\",\"outcome\":\"INITIAL_BASELINE_CHECKED\","
            +"\"annualUpdateBasis\":\"PLAN_ONLY\",\"evidenceLocator\":\"Plano ex ante e referências do ano 1\","
            +"\"reason\":\"Parecer inicial independente, sem afirmar atualização observada.\","
            +"\"initialPlan\":{\"evr\":\"MATCH: atualização anual do EVR conforme o plano ex ante.\","
            +"\"afec\":\"MATCH: atualização anual do AFEC conforme o plano ex ante.\","
            +"\"mpg\":\"MATCH: atualização anual do MPG conforme o plano ex ante.\","
            +"\"fuel\":\"MATCH: atualização anual do fator fóssil conforme o plano ex ante.\"},"
            +"\"exAntePlanId\":\""+plan.path("id").asText()+"\",\"exAntePlanVersion\":"+plan.path("version").asInt()
            +",\"exAntePlanSha256\":\""+plan.path("planSha256").asText()+"\"}",201);
        JsonNode y2=recordIr("Y2-M1","\"0\"");
        post("/v1/platform/open-ir-assessments/"+s.project()+"/"+y2.path("id").asText()+"/review",s.staffReviewer().cookie(),"\"1\"",
            "{\"assessmentSha256\":\""+y2.path("assessmentSha256").asText()+"\",\"outcome\":\"CHECKED_INTERNAL\","
            +"\"annualUpdateBasis\":\"OBSERVED_UPDATE\",\"evidenceLocator\":\"Registros de atualização do ano 2\","
            +"\"reason\":\"Atualização observada conferida internamente, sem admissão normativa.\","
            +"\"priorAssessmentId\":\""+y1.path("id").asText()+"\",\"temporalEvidence\":["
            +evidence("EVR")+","+evidence("AFEC")+","+evidence("MPG")+","+evidence("EF_FUEL")+"]}",201);

        // Without a reviewed Dy the period is not calculated.
        JsonNode before=post(runs("Y2-M1"),prep.cookie(),"\"0\"","{}",201);
        assertEquals("BLOCKED",before.path("state").asText());
        assertEquals("MISSING_FOR_PROJECT_YEAR",OpenE8TestFixture.calcGate(before,"DY").path("state").asText());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",OpenE8TestFixture.calcGate(before,"IR_STRUCTURED").path("state").asText());

        // Dy of year 2: positive, licensed, itemized in the year-1 window, independently reviewed.
        UUID license=UUID.randomUUID();
        database.transaction(c->{
            PilotSql.update(c,"""
                INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,official_uri,origin)
                VALUES (?,'1','EXTERNAL_SOURCE','Registro fictício do E2E','Teste','https://example.invalid/e2e','EXTERNAL')
                ON CONFLICT DO NOTHING
                """,SOURCE);
            PilotSql.update(c,"""
                INSERT INTO app_content_license
                (id,source_id,edition,organization_id,submitted_by,environment,purpose,
                 authorization_ref,proof_sha256,starts_at,ends_at)
                VALUES (?,?,'1',?,?,'SANDBOX','AUTHORIZED_PROCESSING','TEST_ONLY rights',repeat('a',64),
                        statement_timestamp()-interval '1 day',statement_timestamp()+interval '1 day')
                """,license,SOURCE,s.org(),s.staffManager().id());
            PilotSql.update(c,"""
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                VALUES (?,1,'APPROVED',?,'TEST_ONLY independent rights')
                """,license,s.staffReviewer().id());
            PilotSql.update(c,"""
                INSERT INTO app_content_license_capability(license_id,decision_version,operation)
                VALUES (?,1,'EXTRACT_TEXT'),(?,1,'SHOW_EXCERPTS'),(?,1,'EXPORT_DERIVATIVES')
                """,license,license,license);
            return null;
        });
        String items="[{\"program\":\"TEST-PROGRAM\",\"registryProject\":\"A\",\"issuanceId\":\"ISS-1\",\"creditKind\":\"ERC\","
            +"\"issuedOn\":\"2023-03-01\",\"geography\":\"BR\",\"creditsTco2e\":\"75\",\"sourceId\":\""+SOURCE+"\","
            +"\"sourceEdition\":\"1\",\"locator\":\"TEST_ONLY item A\"},"
            +"{\"program\":\"TEST-PROGRAM\",\"registryProject\":\"B\",\"issuanceId\":\"ISS-2\",\"creditKind\":\"ERF\","
            +"\"issuedOn\":\"2023-09-01\",\"geography\":\"BR\",\"creditsTco2e\":\"25\",\"sourceId\":\""+SOURCE+"\","
            +"\"sourceEdition\":\"1\",\"locator\":\"TEST_ONLY item B\"}]";
        JsonNode dy=post("/v1/platform/open-dy-assessments/"+s.project(),s.staffManager().cookie(),"\"0\"",
            "{\"projectYear\":2,\"outcome\":\"FLEET_CREDITS_FOUND\",\"erc\":\"75\",\"erf\":\"25\","
            +"\"programs\":\"Programas pesquisados no teste E2E\",\"projectReferences\":\"Referências do teste E2E\","
            +"\"evidenceLocator\":\"Localizador do teste E2E\",\"coverageNote\":\"Cobertura declarada no teste E2E\","
            +"\"issuances\":"+items+",\"sourceLicenses\":[{\"sourceId\":\""+SOURCE+"\",\"sourceEdition\":\"1\",\"licenseId\":\""+license+"\"}]}",201);
        assertEquals("0.75",dy.path("dyPreview").asText());
        post("/v1/platform/open-dy-assessments/"+s.project()+"/"+dy.path("id").asText()+"/review",s.staffReviewer().cookie(),"\"1\"",
            "{\"assessmentSha256\":\""+dy.path("assessmentSha256").asText()+"\",\"outcome\":\"CHECKED_INTERNAL\","
            +"\"ruleReference\":\"VM0038-1.1-EQ8 (Dy)\",\"evidenceReferences\":\"Registro de pesquisa do teste E2E\","
            +"\"reason\":\"Parecer interno independente de teste, sem admissão normativa.\"}",201);

        // First numeric rehearsal: REVIEWED_INTERNAL with Dy = 0.75 and ER = (BE - PE) x Dy, nothing admitted.
        JsonNode run=post(runs("Y2-M1"),prep.cookie(),"\"1\"","{}",201);
        assertEquals("REVIEWED_INTERNAL",run.path("state").asText(),run.toString());
        assertFalse(run.path("canIssueVcu").asBoolean());
        assertFalse(run.path("stale").asBoolean());
        assertFalse(run.at("/memory/usableForE8").asBoolean());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",run.at("/dyContext/state").asText());
        assertEquals(dy.path("id").asText(),run.at("/dyContext/assessmentId").asText());
        assertEquals(0,new BigDecimal("0.75").compareTo(dec(run.at("/memory/dy"))));
        BigDecimal be=dec(run.at("/memory/beTco2e")),pe=dec(run.at("/memory/peTco2e")),er=dec(run.at("/memory/erTco2e"));
        assertEquals(0,be.subtract(pe).multiply(new BigDecimal("0.75")).compareTo(er),"ER = (BE - PE) x Dy");
        assertEquals("NOT_ADMITTED",OpenE8TestFixture.calcGate(run,"NORMATIVE_PACK").path("state").asText());
        assertEquals("NOT_ADMITTED",OpenE8TestFixture.calcGate(run,"CARBON_RIGHTS").path("state").asText());

        // Idempotent re-execution returns the same immutable attempt.
        var again=fx.postMethod(runs("Y2-M1"),prep.cookie(),key(),"\"2\"","{}");
        assertEquals(200,again.statusCode(),again.body());
        assertEquals(run.path("id").asText(),fx.json(again).path("id").asText());

        // The second period of the same year reads the very same Dy and IR assessments.
        JsonNode second=post(runs("Y2-M2"),prep.cookie(),"\"0\"","{}",201);
        assertEquals("REVIEWED_INTERNAL",second.path("state").asText(),second.toString());
        assertEquals(run.at("/dyContext/assessmentId").asText(),second.at("/dyContext/assessmentId").asText());
        assertEquals(run.at("/irContext/assessmentId").asText(),second.at("/irContext/assessmentId").asText());

        // Annual consolidation of year 2: both calculated windows share one set of references (no Dy, IR, fleet or
        // fuel mismatch). The two-hour windows do not cover the year, so the total stays blocked: absence is not zero.
        var annual=fx.call("GET","/v1/projects/"+s.project()+"/technical/open-calculation-years/2",s.owner().cookie(),null,null);
        assertEquals(200,annual.statusCode(),annual.body());
        JsonNode year=fx.json(annual);
        assertFalse(year.path("usableForE8").asBoolean());
        assertFalse(year.path("canIssueVcu").asBoolean());
        var codes=new java.util.HashSet<String>();
        for(JsonNode issue:year.path("issues")) codes.add(issue.path("code").asText());
        for(String reference:java.util.List.of("DY_REFERENCE_MISSING","DY_REFERENCE_MISMATCH","IR_REFERENCE_MISSING",
            "IR_REFERENCE_MISMATCH","FLEET_REFERENCE_MISMATCH","FUEL_REFERENCE_MISMATCH","FLEET_FUEL_REFERENCE_MISSING"))
            assertFalse(codes.contains(reference),reference+" in "+codes);
        assertTrue(codes.contains("COVERAGE_GAP"),"the sample windows do not cover the whole year: "+codes);
        assertTrue(year.path("totals").isNull(),"no annual total over a partial year");

        // A newer Dy assessment supersedes the reviewed one: the attempt turns stale and a new reading is not numeric.
        JsonNode newer=post("/v1/platform/open-dy-assessments/"+s.project(),s.staffManager().cookie(),"\"1\"",
            "{\"projectYear\":2,\"outcome\":\"INCONCLUSIVE\","
            +"\"programs\":\"Programas pesquisados no teste E2E\",\"projectReferences\":\"Referências do teste E2E\","
            +"\"evidenceLocator\":\"Localizador do teste E2E\",\"coverageNote\":\"Cobertura declarada no teste E2E\"}",201);
        assertTrue(newer.path("dyPreview").isNull());
        var listed=fx.json(fx.call("GET",runs("Y2-M1"),s.owner().cookie(),null,null));
        assertTrue(listed.at("/runs/0/stale").asBoolean(),"the numeric attempt that read the old Dy is stale");
        JsonNode blocked=post(runs("Y2-M1"),prep.cookie(),"\"2\"","{}",201);
        assertEquals("BLOCKED",blocked.path("state").asText());
        assertEquals("REVIEW_REQUIRED",OpenE8TestFixture.calcGate(blocked,"DY").path("state").asText());
        assertTrue(blocked.at("/memory").isNull(),"no numbers without a reviewed Dy");
    }
}
