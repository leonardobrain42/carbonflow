package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** Fictional factors only. A pin is not an admitted E8 input and never defaults IR or Dy to 1. */
@QuarkusTest
@QuarkusTestResource(value = OpenProjectParameterBindingsIntegrationTest.Settings.class,
    restrictToAnnotatedClass = true)
class OpenProjectParameterBindingsIntegrationTest {
    static final UUID MANAGER = UUID.fromString("fa000000-0000-4000-8000-000000000001");
    static final UUID REVIEWER = UUID.fromString("fa000000-0000-4000-8000-000000000002");
    static final UUID OUTSIDER = UUID.fromString("fa000000-0000-4000-8000-000000000003");
    static final UUID OWNER = UUID.fromString("fa000000-0000-4000-8000-000000000010");
    static final UUID ORG = UUID.fromString("fa000000-0000-4000-8000-000000000004");
    static final UUID OTHER_ORG = UUID.fromString("fa000000-0000-4000-8000-000000000005");
    static final byte[] TOTP = "11223344556677889900".getBytes(StandardCharsets.US_ASCII);

    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void projectPinKeepsAbsenceZeroAndOneDistinctAndDoesNotAdmitE8() throws Exception {
        String manager = actor(MANAGER), reviewer = actor(REVIEWER), outsider = actor(OUTSIDER);
        String owner = actor(OWNER);
        seed();
        assertEquals(403, call("GET", "/v1/platform/open-parameter-bindings/options",
            manager, null, null, null).statusCode());
        verify(manager);
        verify(reviewer);
        verify(outsider);
        String fuel = reviewedFactor("/v1/platform/open-fossil-factors", manager, reviewer,
            "fa000000-0000-4000-8000-000000000008");
        String grid = reviewedFactor("/v1/platform/open-grid-factors", manager, reviewer,
            "fa000000-0000-4000-8000-000000000018");
        String project = project(owner);
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_project_triage_revision
                  (project_id, organization_id, environment, version, actor_id, current_step, answers)
                VALUES (?, ?, 'SANDBOX', 1, ?, 1, ?::jsonb)
                """, project, ORG, OWNER, "{\"1\":{\"methodologyCandidate\":\"VM0038\"}}");
            return null;
        });
        // Dy research is a versioned assessment, not another numerical pin.
        String dyPath="/v1/platform/open-dy-assessments/"+project;
        String inconclusive="""
            {"projectYear":2,"outcome":"INCONCLUSIVE","programs":"Programas fictícios A e B",
             "projectReferences":"Busca parcial; nenhum resultado conclusivo",
             "evidenceLocator":"TEST_ONLY registro 1","coverageNote":"Pesquisa parcial de créditos nacionais"}
            """;
        assertEquals(404,call("POST",dyPath,reviewer,key(),"\"0\"",inconclusive).statusCode());
        assertEquals(428,call("POST",dyPath,manager,key(),null,inconclusive).statusCode());
        String dyKey=key();
        JsonNode research=json(call("POST",dyPath,manager,dyKey,"\"0\"",inconclusive),201);
        assertEquals("CREDIT_START_MISSING",research.path("calendarGate").asText());
        assertTrue(research.path("dyPreview").isNull());
        assertFalse(research.path("usableForE8").asBoolean());
        assertEquals(research,json(call("POST",dyPath,manager,dyKey,"\"0\"",inconclusive),200));
        String dyFound=inconclusive.replace("\"INCONCLUSIVE\"","\"FLEET_CREDITS_FOUND\",\"erc\":\"75\",\"erf\":\"25\"");
        assertEquals(422,call("POST",dyPath,manager,key(),"\"1\"",dyFound).statusCode());
        assertEquals(409,call("POST",dyPath,manager,dyKey,"\"0\"",
            inconclusive.replace("registro 1","registro 2")).statusCode());
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_rights_event
                (project_id,organization_id,environment,event_code,version,kind,event_on,document_ref,author_id)
                VALUES (?,?,'SANDBOX','op-dy',1,'OPERATIONAL_START',DATE '2034-07-01','TEST_ONLY',?),
                       (?,?,'SANDBOX','vcs-dy',1,'VCS_START',DATE '2034-07-01','TEST_ONLY',?)
                """,project,ORG,OWNER,project,ORG,OWNER);
            update(c,"""
                INSERT INTO app_project_open_applicability_revision
                (project_id,organization_id,environment,version,triage_version,author_id,
                 unrestricted_bev_access,restricted_to_identified_vehicles,charger_type,propulsion,
                 electricity_source,associated_infrastructure,v2g,operational_event_code,
                 operational_event_on,vcs_event_code,vcs_event_on)
                VALUES (?,?,'SANDBOX',1,1,?,true,false,'DCFC','BEV','SIN',false,false,
                        'op-dy',DATE '2034-07-01','vcs-dy',DATE '2034-07-01')
                """,project,ORG,OWNER);
            return null;
        });
        JsonNode found=json(call("POST",dyPath,manager,key(),"\"1\"",dyFound),201);
        assertEquals("0.75",found.path("dyPreview").asText());
        assertEquals(2,found.path("version").asInt());
        assertTrue(found.path("priorStart").asText().startsWith("2034-07-01"));
        assertTrue(found.path("priorEndExclusive").asText().startsWith("2035-07-01"));
        assertFalse(found.path("usableForE8").asBoolean());
        assertEquals(412,call("POST",dyPath,manager,key(),"\"1\"",dyFound).statusCode());
        String dyRead="/v1/projects/"+project+"/technical/open-dy-assessments/2";
        assertEquals(404,call("GET",dyRead,outsider,null,null,null).statusCode());
        var dyResponse=call("GET",dyRead,owner,null,null,null);
        JsonNode history=json(dyResponse,200);
        assertTrue(dyResponse.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(2,history.path("latestVersion").asInt());
        assertEquals("0.75",history.at("/assessments/0/dyPreview").asText());
        assertTrue(history.at("/assessments/1/stale").asBoolean());
        assertTrue(history.at("/assessments/0/evidenceLocator").isNull());
        assertEquals("TEST_ONLY registro 1",json(call("GET",dyPath+"/2",manager,null,null,null),200)
            .at("/assessments/0/evidenceLocator").asText());
        assertEquals(422,call("POST",dyPath,manager,key(),"\"0\"",
            dyFound.replace("\"projectYear\":2","\"projectYear\":1")).statusCode());
        String reviewPath=dyPath+"/"+found.path("id").asText()+"/review";
        String reviewBody="""
            {"assessmentSha256":"%s","outcome":"CHECKED_INTERNAL",
             "ruleReference":"VM0038-1.1 EQ8 internal check",
             "evidenceReferences":"TEST_ONLY programme and project record",
             "reason":"Independent internal check of declared totals only"}
            """.formatted(found.path("assessmentSha256").asText());
        assertEquals(404,call("POST",reviewPath,manager,key(),"\"2\"",reviewBody).statusCode());
        assertEquals(404,call("GET",dyPath+"/2",outsider,null,null,null).statusCode());
        JsonNode reviewerOptions=json(call("GET","/v1/platform/open-dy-assessments/options",
            reviewer,null,null,null),200);
        assertTrue(reviewerOptions.path("canReview").asBoolean());
        assertFalse(reviewerOptions.path("canRecord").asBoolean());
        assertTrue(reviewerOptions.path("projects").toString().contains(project));
        assertEquals(200,call("GET",dyPath+"/2",reviewer,null,null,null).statusCode());
        assertEquals(422,call("POST",reviewPath,reviewer,key(),"\"2\"",
            reviewBody.replace(found.path("assessmentSha256").asText(),"0".repeat(64))).statusCode());
        assertEquals(412,call("POST",reviewPath,reviewer,key(),"\"1\"",reviewBody).statusCode());
        String reviewKey=key();
        JsonNode reviewed=json(call("POST",reviewPath,reviewer,reviewKey,"\"2\"",reviewBody),201);
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",reviewed.path("state").asText());
        assertFalse(reviewed.path("usableForE8").asBoolean());
        assertEquals(found.path("assessmentSha256"),reviewed.at("/review/assessmentSha256"));
        assertEquals(reviewed,json(call("POST",reviewPath,reviewer,reviewKey,"\"2\"",reviewBody),200));
        assertEquals(409,call("POST",reviewPath,reviewer,key(),"\"2\"",reviewBody).statusCode());
        assertEquals(409,call("POST",reviewPath,reviewer,reviewKey,"\"2\"",
            reviewBody.replace("CHECKED_INTERNAL","REJECTED")).statusCode());
        JsonNode visibleReview=json(call("GET",dyRead,owner,null,null,null),200).at("/assessments/0/review");
        assertEquals("CHECKED_INTERNAL",visibleReview.path("outcome").asText());
        assertTrue(visibleReview.path("reason").isNull());
        assertTrue(visibleReview.path("evidenceReferences").isNull());
        JsonNode revision=json(call("POST",dyPath,manager,key(),"\"2\"",inconclusive),201);
        assertEquals(3,revision.path("version").asInt());
        assertTrue(revision.path("review").isNull());
        assertEquals(409,call("POST",reviewPath,reviewer,key(),"\"3\"",reviewBody).statusCode());
        String incompleteReview=reviewBody.replace(found.path("assessmentSha256").asText(),
            revision.path("assessmentSha256").asText());
        String incompletePath=dyPath+"/"+revision.path("id").asText()+"/review";
        assertEquals(422,call("POST",incompletePath,reviewer,key(),"\"3\"",incompleteReview).statusCode());
        JsonNode needsInfo=json(call("POST",incompletePath,reviewer,key(),"\"3\"",
            incompleteReview.replace("CHECKED_INTERNAL","NEEDS_INFORMATION")),201);
        assertEquals("NEEDS_INFORMATION_NOT_ADMITTED",needsInfo.path("state").asText());
        // Issuance references are declared, never imported or admitted.
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,official_uri,origin)
                VALUES ('TEST-DY-REGISTRY','1','EXTERNAL_SOURCE','Registro fictício','Teste',
                        'https://example.invalid/registry','EXTERNAL') ON CONFLICT DO NOTHING
                """);
            return null;
        });
        String items="""
            [{"program":"TEST-PROGRAM","registryProject":"A","issuanceId":"ISS-1",
              "creditKind":"ERC","issuedOn":"2034-07-01","geography":"BR","creditsTco2e":"75",
              "sourceId":"TEST-DY-REGISTRY","sourceEdition":"1","locator":"TEST_ONLY item A"},
             {"program":"TEST-PROGRAM","registryProject":"B","issuanceId":"ISS-2",
              "creditKind":"ERF","issuedOn":"2035-06-30","geography":"BR","creditsTco2e":"25",
              "sourceId":"TEST-DY-REGISTRY","sourceEdition":"1","locator":"TEST_ONLY item B"}]
            """;
        String itemized=dyFound.strip().substring(0,dyFound.strip().length()-1)+",\"issuances\":"+items+"}";
        assertEquals(422,call("POST",dyPath,manager,key(),"\"3\"",
            itemized.replace("\"creditsTco2e\":\"25\"","\"creditsTco2e\":\"24\"")).statusCode());
        assertEquals(422,call("POST",dyPath,manager,key(),"\"3\"",
            itemized.replace("2035-06-30","2035-07-01")).statusCode());
        assertEquals(422,call("POST",dyPath,manager,key(),"\"3\"",
            itemized.replace("\"sourceEdition\":\"1\"","\"sourceEdition\":\"2\"")).statusCode());
        assertEquals(422,call("POST",dyPath,manager,key(),"\"3\"",
            itemized.replace("\"registryProject\":\"B\",\"issuanceId\":\"ISS-2\"",
                "\"registryProject\":\"A\",\"issuanceId\":\"ISS-1\"")).statusCode());
        String itemKey=key();
        JsonNode itemizedView=json(call("POST",dyPath,manager,itemKey,"\"3\"",itemized),201);
        assertEquals(4,itemizedView.path("version").asInt());
        assertEquals(2,itemizedView.path("issuanceCount").asInt());
        assertEquals("DECLARED_TOTALS_RECONCILED",itemizedView.path("issuanceState").asText());
        assertEquals("0.75",itemizedView.path("dyPreview").asText());
        assertFalse(itemizedView.path("usableForE8").asBoolean());
        assertTrue(itemizedView.path("review").isNull());
        assertEquals(itemizedView,json(call("POST",dyPath,manager,itemKey,"\"3\"",itemized),200));
        assertEquals(409,call("POST",dyPath,manager,itemKey,"\"3\"",
            itemized.replace("TEST_ONLY item B","TEST_ONLY outra referência")).statusCode());
        var projectItem=json(call("GET",dyRead,owner,null,null,null),200).at("/assessments/0");
        assertEquals(2,projectItem.path("issuanceCount").asInt());
        assertEquals(0,projectItem.path("issuances").size());
        assertFalse(projectItem.toString().contains("TEST-DY-REGISTRY"));
        assertEquals("NO_LICENSE_PIN",itemizedView.path("sourceRightsState").asText());
        UUID dyLicense=UUID.randomUUID();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_content_license
                (id,source_id,edition,organization_id,submitted_by,environment,purpose,
                 authorization_ref,proof_sha256,starts_at,ends_at)
                VALUES (?,'TEST-DY-REGISTRY','1',?,?,'SANDBOX','AUTHORIZED_PROCESSING',
                        'TEST_ONLY rights',repeat('a',64),statement_timestamp()-interval '1 day',
                        statement_timestamp()+interval '1 day')
                """,dyLicense,ORG,MANAGER);
            update(c,"""
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                VALUES (?,1,'APPROVED',?,'TEST_ONLY independent rights')
                """,dyLicense,REVIEWER);
            update(c,"""
                INSERT INTO app_content_license_capability(license_id,decision_version,operation)
                VALUES (?,1,'EXTRACT_TEXT'),(?,1,'SHOW_EXCERPTS')
                """,dyLicense,dyLicense);
            return null;
        });
        String licensed=itemized.substring(0,itemized.length()-1)
            +",\"sourceLicenses\":[{\"sourceId\":\"TEST-DY-REGISTRY\",\"sourceEdition\":\"1\",\"licenseId\":\""+dyLicense+"\"}]}";
        assertEquals(403,call("POST",dyPath,manager,key(),"\"4\"",licensed).statusCode());
        database.transaction(c->{
            update(c,"INSERT INTO app_content_license_capability VALUES (?,1,'EXPORT_DERIVATIVES')",dyLicense);
            return null;
        });
        String rightsKey=key();
        JsonNode licensedView=json(call("POST",dyPath,manager,rightsKey,"\"4\"",licensed),201);
        assertEquals(5,licensedView.path("version").asInt());
        assertEquals("LICENSE_DECISIONS_CURRENT",licensedView.path("sourceRightsState").asText());
        assertEquals(1,licensedView.at("/sourceLicenses/0/decisionVersion").asInt());
        assertEquals("0.75",licensedView.path("dyPreview").asText());
        assertFalse(licensedView.path("usableForE8").asBoolean());
        var licensedProject=json(call("GET",dyRead,owner,null,null,null),200).at("/assessments/0");
        assertTrue(licensedProject.path("dyPreview").isNull());
        assertEquals(0,licensedProject.path("sourceLicenses").size());
        String licensedReviewPath=dyPath+"/"+licensedView.path("id").asText()+"/review";
        String licensedReview=reviewBody.replace(found.path("assessmentSha256").asText(),
            licensedView.path("assessmentSha256").asText());
        database.transaction(c->{
            update(c,"UPDATE app_content_license SET ends_at=statement_timestamp()-interval '1 second' WHERE id=?",dyLicense);
            return null;
        });
        JsonNode expiredDy=json(call("GET",dyPath+"/2",manager,null,null,null),200).at("/assessments/0");
        assertEquals("LICENSE_RECHECK_REQUIRED",expiredDy.path("sourceRightsState").asText());
        assertTrue(expiredDy.path("stale").asBoolean());
        assertTrue(expiredDy.path("erc").isNull());
        assertTrue(expiredDy.path("dyPreview").isNull());
        assertTrue(expiredDy.path("evidenceLocator").isNull());
        assertEquals(0,expiredDy.path("issuances").size());
        assertEquals(licensedView.path("assessmentSha256"),expiredDy.path("assessmentSha256"));
        assertEquals(409,call("POST",licensedReviewPath,reviewer,key(),"\"5\"",licensedReview).statusCode());
        database.transaction(c->{
            update(c,"UPDATE app_content_license SET ends_at=statement_timestamp()+interval '1 day' WHERE id=?",dyLicense);
            update(c,"""
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                VALUES (?,2,'REVOKED',?,'TEST_ONLY revocation')
                """,dyLicense,REVIEWER);
            return null;
        });
        JsonNode revoked=json(call("POST",dyPath,manager,rightsKey,"\"4\"",licensed),200);
        assertEquals("LICENSE_RECHECK_REQUIRED",revoked.path("sourceRightsState").asText());
        assertEquals(licensedView.path("id"),revoked.path("id"));
        assertTrue(revoked.path("dyPreview").isNull());
        assertEquals(403,call("POST",dyPath,manager,key(),"\"5\"",licensed).statusCode());
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                VALUES (?,3,'APPROVED',?,'TEST_ONLY new decision')
                """,dyLicense,REVIEWER);
            update(c,"""
                INSERT INTO app_content_license_capability VALUES
                (?,3,'EXTRACT_TEXT'),(?,3,'SHOW_EXCERPTS'),(?,3,'EXPORT_DERIVATIVES')
                """,dyLicense,dyLicense,dyLicense);
            return null;
        });
        assertEquals("LICENSE_RECHECK_REQUIRED",json(call("GET",dyPath+"/2",manager,null,null,null),200)
            .at("/assessments/0/sourceRightsState").asText());
        JsonNode reauthorized=json(call("POST",dyPath,manager,key(),"\"5\"",licensed),201);
        assertEquals(6,reauthorized.path("version").asInt());
        assertEquals(3,reauthorized.at("/sourceLicenses/0/decisionVersion").asInt());
        assertEquals("LICENSE_DECISIONS_CURRENT",reauthorized.path("sourceRightsState").asText());
        assertFalse(reauthorized.path("usableForE8").asBoolean());

        assertEquals(0L,database.read(c->first(c,"SELECT count(*) FROM app_open_project_parameter_binding WHERE project_id=? AND parameter_kind='DY'",
            r->r.getLong(1),project)).longValue());

        long pilotBefore = database.read(c -> first(c,
            "SELECT count(*) FROM pilot_selected_run", r -> r.getLong(1)));
        String pins = "/v1/platform/open-parameter-bindings";
        assertEquals(404, call("POST", pins, reviewer, key(), "\"0\"",
            pin(project, "IR", null, "1")).statusCode());
        assertEquals(422, call("POST", pins, manager, key(), "\"0\"",
            "{\"projectId\":\"" + project + "\",\"parameterKind\":\"IR\",\"vintageYear\":2036,"
                + "\"useDefault\":true}").statusCode());
        assertEquals(422, call("POST", pins, manager, key(), "\"0\"",
            "{\"projectId\":\"" + project + "\",\"parameterKind\":\"DY\",\"vintageYear\":2036}").statusCode());
        assertEquals(422, call("POST", pins, manager, key(), "\"0\"",
            pin(project, "EF_FUEL", fuel, null)
                .replace("\"vintageYear\":2036", "\"vintageYear\":2035")).statusCode());
        assertEquals(404, call("POST", pins, manager, key(), "\"0\"",
            pin(project, "EF_GRID", fuel, null)).statusCode());

        String idempotency = key();
        JsonNode one = json(call("POST", pins, manager, idempotency, "\"0\"",
            pin(project, "IR", null, "1")), 201);
        assertEquals("DECLARED_EXPLICIT", one.path("valueKind").asText());
        assertTrue(one.path("explicitOne").asBoolean());
        assertEquals("1", one.path("declaredValue").asText());
        assertFalse(one.path("usableForE8").asBoolean());
        assertTrue(one.path("gates").toString().contains("EXPLICIT_ONE_IS_NOT_A_DEFAULT"));
        assertEquals(200, call("POST", pins, manager, idempotency, "\"0\"",
            pin(project, "IR", null, "1")).statusCode());
        assertEquals(412, call("POST", pins, manager, key(), "\"0\"",
            pin(project, "IR", null, "0.8")).statusCode());

        JsonNode zero = json(call("POST", pins, manager, key(), "\"0\"",
            pin(project, "DY", null, "0")), 201);
        assertEquals("DECLARED_ZERO", zero.path("valueKind").asText());
        assertFalse(zero.path("explicitOne").asBoolean());
        assertEquals("0", zero.path("declaredValue").asText());

        JsonNode fuelPin = json(call("POST", pins, manager, key(), "\"0\"",
            pin(project, "EF_FUEL", fuel, null)), 201);
        assertEquals("REFERENCE", fuelPin.path("valueKind").asText());
        assertEquals("tCO2e/USgal", fuelPin.path("normalizedUnit").asText());
        assertTrue(fuelPin.path("declaredValue").isNull());
        assertFalse(fuelPin.path("usableForE8").asBoolean());
        JsonNode gridPin = json(call("POST", pins, manager, key(), "\"0\"",
            pin(project, "EF_GRID", grid, null)), 201);
        assertEquals("tCO2e/kWh", gridPin.path("normalizedUnit").asText());
        assertEquals("DIVIDE_BY_1000_KWH_PER_MWH", gridPin.path("conversionFormula").asText());

        String projectPath = "/v1/projects/" + project + "/technical/open-parameter-bindings";
        assertEquals(404, call("GET", projectPath, outsider, null, null, null).statusCode());
        String visible = call("GET", projectPath, owner, null, null, null).body();
        JsonNode listed = json(call("GET", projectPath, owner, null, null, null), 200);
        assertEquals(4, listed.size());
        assertFalse(visible.contains("0.007570823568"), visible);
        assertFalse(visible.contains("0.000400000000"), visible);
        assertTrue(visible.contains("\"declaredValue\":\"1\""));
        assertTrue(visible.contains("\"declaredValue\":\"0\""));
        assertTrue(listed.toString().contains("\"usableForE8\":false"));

        String fuelDetail=projectPath+"/"+fuelPin.path("id").asText();
        var detailResponse=call("GET",fuelDetail,owner,null,null,null);
        JsonNode detail=json(detailResponse,200);
        assertEquals(fuelPin,detail);
        assertTrue(detailResponse.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals("nosniff",detailResponse.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(401,call("GET",fuelDetail,null,null,null,null).statusCode());
        assertEquals(404,call("GET",fuelDetail,outsider,null,null,null).statusCode());
        assertEquals(404,call("GET",projectPath+"/"+UUID.randomUUID(),owner,null,null,null).statusCode());
        String otherProject=project(owner);
        assertEquals(404,call("GET","/v1/projects/"+otherProject+"/technical/open-parameter-bindings/"
            +fuelPin.path("id").asText(),owner,null,null,null).statusCode());
        String irDetail=projectPath+"/"+one.path("id").asText();
        JsonNode revised=json(call("POST",pins,manager,key(),"\"1\"",pin(project,"IR",null,"0.8")),201);
        JsonNode historicIr=json(call("GET",irDetail,owner,null,null,null),200);
        assertEquals(one,historicIr);
        assertEquals(2,revised.path("version").asInt());
        assertEquals("0.8",revised.path("declaredValue").asText());
        // Losing derivative rights does not expose the licensed factor through detail.
        UUID license=database.read(c->first(c,"SELECT source_license_id FROM app_open_parameter_candidate WHERE id=?",
            row->row.getObject(1,UUID.class),UUID.fromString(fuel)));
        database.transaction(c->{
            update(c,"UPDATE app_content_license SET ends_at=statement_timestamp()-interval '1 second' WHERE id=?",license);
            return null;
        });
        JsonNode expired=json(call("GET",fuelDetail,owner,null,null,null),200);
        assertEquals("STALE_NOT_ADMITTED",expired.path("state").asText());
        assertEquals(fuelPin.path("id"),expired.path("id"));
        assertEquals(fuelPin.path("version"),expired.path("version"));
        assertTrue(expired.path("declaredValue").isNull());
        assertFalse(expired.toString().contains("0.007570823568"));
        database.transaction(c->{
            update(c,"UPDATE app_content_license SET ends_at=statement_timestamp()+interval '1 hour' WHERE id=?",license);
            return null;
        });
        JsonNode readiness = json(call("GET",
            "/v1/projects/" + project + "/technical/open-calculation-readiness",
            owner, null, null, null), 200);
        assertFalse(readiness.path("canRunE8").asBoolean());
        assertFalse(readiness.path("canIssueVcu").asBoolean());
        assertEquals("NOT_ADMITTED", gate(readiness, "EF_FUEL"));
        assertEquals("NOT_ADMITTED", gate(readiness, "EF_GRID"));
        assertEquals("NOT_ADMITTED", gate(readiness, "IR"));
        assertEquals("NOT_ADMITTED", gate(readiness, "DY"));
        assertTrue(reason(readiness, "IR").contains("valor explícito"));
        assertTrue(reason(readiness, "DY").contains("Zero declarado"));
        assertFalse(reason(readiness, "EF_FUEL").contains("0.00757"));
        assertTrue(readiness.path("emissions").path("erTco2e").isNull());

        database.transaction(c -> {
            update(c, """
                INSERT INTO app_project_triage_revision
                  (project_id, organization_id, environment, version, actor_id, current_step, answers)
                VALUES (?, ?, 'SANDBOX', 2, ?, 1, ?::jsonb)
                """, project, ORG, OWNER, "{\"1\":{\"methodologyCandidate\":\"VM0038\"}}");
            return null;
        });
        JsonNode stale = json(call("GET", projectPath, owner, null, null, null), 200);
        assertTrue(stale.toString().contains("STALE_NOT_ADMITTED"));
        JsonNode after = json(call("GET",
            "/v1/projects/" + project + "/technical/open-calculation-readiness",
            owner, null, null, null), 200);
        assertEquals("STALE", gate(after, "IR"));
        assertFalse(after.path("canRunE8").asBoolean());
        long pilotAfter = database.read(c -> first(c,
            "SELECT count(*) FROM pilot_selected_run", r -> r.getLong(1)));
        assertEquals(pilotBefore, pilotAfter);
        assertEquals(0L, database.read(c -> first(c, """
            SELECT count(*) FROM app_open_project_parameter_binding WHERE usable_for_e8
            """, r -> r.getLong(1))).longValue());
    }

    private String reviewedFactor(String root, String manager, String reviewer, String object)
            throws Exception {
        JsonNode created = json(call("POST", root, manager, null, null,
            "{\"sourceObjectId\":\"" + object + "\",\"rowIndex\":2}"), 201);
        assertFalse(created.path("usableForE8").asBoolean());
        String review = """
            {"outcome":"APPROVED_FOR_SCOPE",
             "ruleReference":"VM0038-1.1 internal factor check only",
             "evidenceLocator":"CSV registro 1; fictional source",
             "reason":"Unit, year and gas checked in this fictional test only"}
            """;
        JsonNode reviewed = json(call("POST", root + "/" + created.path("id").asText() + "/reviews",
            reviewer, null, null, review), 201);
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED", reviewed.path("status").asText());
        assertFalse(reviewed.path("usableForE8").asBoolean());
        return created.path("id").asText();
    }

    private String pin(String project, String kind, String candidate, String declared) {
        String value = declared == null ? "" : ",\"declaredValue\":\"" + declared
            + "\",\"ruleReference\":\"VM0038-1.1 explicit project rule\","
            + "\"evidenceLocator\":\"prova ficticia do parametro\"";
        String source = candidate == null ? "" : ",\"candidateId\":\"" + candidate + "\"";
        return "{\"projectId\":\"" + project + "\",\"parameterKind\":\"" + kind
            + "\",\"vintageYear\":2036" + source + value + "}";
    }

    private static String gate(JsonNode readiness, String code) {
        for (JsonNode gate : readiness.path("gates")) {
            if (code.equals(gate.path("code").asText())) return gate.path("state").asText();
        }
        return "";
    }

    private static String reason(JsonNode readiness, String code) {
        for (JsonNode gate : readiness.path("gates")) {
            if (code.equals(gate.path("code").asText())) return gate.path("reason").asText();
        }
        return "";
    }

    private void seed() {
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_organization(id, environment, display_name)
                VALUES (?,'SANDBOX','Organizacao ficticia do pino'),
                       (?,'SANDBOX','Outra organizacao ficticia')
                ON CONFLICT(id) DO NOTHING
                """, ORG, OTHER_ORG);
            update(c, """
                INSERT INTO app_organization_membership(organization_id, identity_id, role)
                VALUES (?, ?, 'DRAFT_OWNER') ON CONFLICT DO NOTHING
                """, ORG, OWNER);
            source(c, "OPEN-FOSSIL-EF", "EF_FUEL_GASOLINE", "tCO2e/L", new BigDecimal("0.002"),
                "TEST-FOSSIL-PIN-2036",
                UUID.fromString("fa000000-0000-4000-8000-000000000006"),
                UUID.fromString("fa000000-0000-4000-8000-000000000007"),
                UUID.fromString("fa000000-0000-4000-8000-000000000008"),
                UUID.fromString("fa000000-0000-4000-8000-000000000009"),
                "BASELINE_BE");
            source(c, "OPEN-GRID-EF", "EF_GRID_CO2E", "tCO2e/MWh", new BigDecimal("0.400"),
                "TEST-GRID-PIN-2036",
                UUID.fromString("fa000000-0000-4000-8000-000000000016"),
                UUID.fromString("fa000000-0000-4000-8000-000000000017"),
                UUID.fromString("fa000000-0000-4000-8000-000000000018"),
                UUID.fromString("fa000000-0000-4000-8000-000000000019"),
                "PROJECT_EMISSIONS_PE");
            return null;
        });
    }

    private void source(java.sql.Connection c, String need, String metric, String unit,
                        BigDecimal value, String edition, UUID submission, UUID license,
                        UUID object, UUID assessment, String capability) throws java.sql.SQLException {
        String source = first(c, """
            SELECT source_id FROM app_methodology_source_need
            WHERE code = ? AND methodology = 'VM0038' AND methodology_edition = '1.1'
            """, r -> r.getString(1), need);
        update(c, """
            INSERT INTO app_source_version
             (source_id, edition, category, title, rights_holder, official_uri, origin)
            VALUES (?, ?, 'EXTERNAL_SOURCE', 'Tabela ficticia', 'Autor sintetico',
                    'https://example.org/factor', 'EXTERNAL')
            ON CONFLICT DO NOTHING
            """, source, edition);
        update(c, """
            INSERT INTO app_platform_source_submission
             (id, need_code, source_id, edition, category, title, rights_holder, publisher,
              official_uri, reference_year, locator, metadata_fingerprint, state,
              submitted_by, reviewed_by)
            VALUES (?, ?, ?, ?, 'EXTERNAL_SOURCE', 'Tabela ficticia', 'Autor sintetico',
                    'Editor sintetico', 'https://example.org/factor', 2036, 'CSV registro 1',
                    ?, 'METADATA_ACCEPTED', ?, ?)
            ON CONFLICT (id) DO NOTHING
            """, submission, need, source, edition, AccessSecrets.digest(edition), MANAGER, REVIEWER);
        update(c, """
            INSERT INTO app_content_license
             (id, source_id, edition, organization_id, submitted_by, environment, purpose,
              authorization_ref, proof_sha256, starts_at, ends_at)
            VALUES (?, ?, ?, ?, ?, 'SANDBOX', 'AUTHORIZED_PROCESSING', 'Somente teste',
                    ?, '2024-01-01', statement_timestamp() + interval '1 hour')
            ON CONFLICT (id) DO NOTHING
            """, license, source, edition, ORG, MANAGER, "d".repeat(64));
        update(c, """
            INSERT INTO app_content_license_decision
             (license_id, version, outcome, decided_by, reason)
            VALUES (?, 1, 'APPROVED', ?, 'Autorizacao sintetica independente')
            ON CONFLICT DO NOTHING
            """, license, REVIEWER);
        update(c, """
            INSERT INTO app_content_license_capability
             (license_id, decision_version, operation)
            VALUES (?, 1, 'STORE_ORIGINAL'), (?, 1, 'EXTRACT_TEXT'),
                   (?, 1, 'SHOW_EXCERPTS'), (?, 1, 'EXPORT_DERIVATIVES')
            ON CONFLICT DO NOTHING
            """, license, license, license, license);
        update(c, """
            INSERT INTO app_platform_source_object
             (id, submission_id, organization_id, uploaded_by, license_id, license_version,
              file_name, file_format, sha256, bytes_count, nonce, encrypted, state,
              parsed_rows, parser_version)
            VALUES (?, ?, ?, ?, ?, 1, 'fator.csv', 'CSV', ?, 16, ?, ?, 'APPROVED_FOR_SCOPE',
                    1, 'TABULAR-1')
            ON CONFLICT (id) DO NOTHING
            """, object, submission, ORG, MANAGER, license, AccessSecrets.digest(edition + "-sha"),
            new byte[12], new byte[32]);
        update(c, """
            INSERT INTO app_platform_source_candidate
             (object_id, row_index, record_key, metric, period_year, geography, unit,
              value_numeric, locator)
            VALUES (?, 2, ?, ?, 2036, 'BR', ?, ?, 'CSV registro 1')
            ON CONFLICT DO NOTHING
            """, object, metric, metric, unit, value);
        update(c, """
            INSERT INTO app_platform_source_assessment
             (id, object_id, outcome, methodology, methodology_edition, scenario,
              blocked_capability, metric, period_year, geography, unit, evidence_locator,
              reason, reviewed_by)
            VALUES (?, ?, 'APPROVED_FOR_SCOPE', 'VM0038', '1.1', 'OPEN_LDV_DCFC_SIN_BR',
                    ?, ?, 2036, 'BR', ?, 'CSV registro 1',
                    'Checagem interna ficticia da fonte', ?)
            ON CONFLICT DO NOTHING
            """, assessment, object, capability, metric, unit, REVIEWER);
    }

    private String project(String owner) throws Exception {
        JsonNode created = json(call("POST", "/v1/projects", owner, key(), null,
            "{\"organizationId\":\"" + ORG + "\",\"title\":\"Rede aberta para pino\"}"), 201);
        return created.path("projectId").asText();
    }

    private String actor(UUID id) {
        String token = AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id, email_normalized, display_name, status, email_verified_at)
                VALUES (?, ?, 'Equipe ficticia', 'ACTIVE', statement_timestamp())
                ON CONFLICT (id) DO NOTHING
                """, id, id + "@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash, identity_id, expires_at)
                VALUES (?, ?, statement_timestamp() + interval '1 hour')
                """, AccessSecrets.digest(token), id);
            return null;
        });
        return token;
    }

    private void verify(String token) throws Exception {
        long counter = database.read(c -> first(c, """
            SELECT floor(extract(epoch from statement_timestamp()) / 30)::bigint
            """, r -> r.getLong(1)));
        assertEquals(200, call("POST", "/v1/compliance/session", token, null, null,
            "{\"code\":\"" + ComplianceStaff.codeAt(TOTP, counter) + "\"}").statusCode());
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private HttpResponse<String> call(String method, String path, String token, String idempotency,
                                       String match, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if (token != null) builder.header("Cookie", AccountAccess.COOKIE + "=" + token);
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        if (match != null) builder.header("If-Match", match);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String, String> start() {
            var config = new HashMap<>(super.start());
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(TOTP);
            config.put("carbonflow.compliance.staff", MANAGER + ":" + secret + ":" + ORG
                + ";" + REVIEWER + ":" + secret + ":" + ORG
                + ";" + OUTSIDER + ":" + secret + ":" + OTHER_ORG);
            config.put("carbonflow.compliance.document-key",
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            config.put("carbonflow.platform.source-readiness.viewers",
                MANAGER + "," + REVIEWER + "," + OUTSIDER);
            config.put("carbonflow.platform.source-work.managers", MANAGER.toString());
            config.put("carbonflow.platform.source-technical.reviewers", REVIEWER.toString());
            return config;
        }
    }
}
