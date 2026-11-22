package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value=OpenPeriodSupportIntegrationTest.Settings.class,restrictToAnnotatedClass=true)
class OpenPeriodSupportIntegrationTest {
    static final UUID E8_STAFF_MANAGER=UUID.fromString("e8000000-0000-4000-8000-000000000001");
    static final UUID E8_STAFF_REVIEWER=UUID.fromString("e8000000-0000-4000-8000-000000000002");
    static final UUID E8_STAFF_ORG=UUID.fromString("e8000000-0000-4000-8000-000000000003");
    static final byte[] E8_STAFF_SECRET="12345123451234512345"
        .getBytes(StandardCharsets.US_ASCII);
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient http=HttpClient.newHttpClient();
    private record Actor(UUID id,String cookie) {}
    private static String key(){return UUID.randomUUID().toString();}
    private static final String CHARGER="""
        {"siteCode":"SITE-1","chargerKind":"DCFC","ratedPowerKw":150,
         "grid":"SIN","topology":"GRID_ONLY","timeZone":"America/Sao_Paulo",
         "sourceReference":"COMMISSION-123"}
        """;
    private static String meter(String kind) {
        return """
          {"chargerCode":"DCFC-1","energyKind":"%s","meterPosition":"%s",
           "sourceSystem":"METER-API-1","calibrationEvidenceRef":"CAL-2027-01",
           "calibrationValidFrom":"2027-01-01T00:00:00Z",
           "calibrationValidUntil":"2028-01-01T00:00:00Z",
           "configuredFrom":"2027-01-01T00:00:00Z",
           "configuredUntil":"2028-01-01T00:00:00Z","timeZone":"America/Sao_Paulo"}
          """.formatted(kind,"ED".equals(kind)?"DOWNSTREAM":"UPSTREAM");
    }
    private static final String DATA=
        "meterCode,sequence,kind,start,endExclusive,zone,quantity,unit\n"+
        "ED-1,SEQ-1,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,2700,kWh\n"+
        "EC-1,SEQ-1,EC,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,3000,kWh\n";
    private static final String FROM="2027-02-01T00:00:00Z",TO="2027-02-01T01:00:00Z";
    private static final String ACCEPTED="{\"outcome\":\"DOCUMENT_CHECKED_INTERNAL\","+
        "\"reason\":\"Conferência técnica interna do documento e da identidade do medidor.\"}";

    @Test
    void proofFilesAreEncryptedReviewedIndependentlyAndSnapshotTurnsStale() throws Exception {
        Actor owner=actor(),op=actor(),qaReviewer=actor(),docsReviewer=actor(),outside=actor();
        Actor staffManager=actorWithId(E8_STAFF_MANAGER);
        Actor staffReviewer=actorWithId(E8_STAFF_REVIEWER);
        verifyStaff(staffManager);
        verifyStaff(staffReviewer); // distinguish valid MFA from absent manager grant
        UUID org=organization(owner.id(),E8_STAFF_ORG);
        String project=project(owner,org);
        configure(owner,project);
        grant(project,org,owner.id(),op.id(),"OP");
        grant(project,org,owner.id(),qaReviewer.id(),"REV");
        grant(project,org,owner.id(),docsReviewer.id(),"REV");
        // The dynamic OPEN MR/PD dependency is VM0038-specific. A source guide
        // can be prepared before monitoring, but is pinned when the MR exists.
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_triage_revision
                  (project_id,organization_id,environment,version,actor_id,current_step,answers)
                VALUES (?,?,'SANDBOX',1,?,1,?::jsonb)
                """,project,org,owner.id(),
                "{\"1\":{\"methodologyCandidate\":\"VM0038\"}}");
            update(c,"""
                INSERT INTO app_technical_guide_revision
                  (project_id,organization_id,environment,guide_id,version,actor_id,answers)
                VALUES (?,?,'SANDBOX','monitoring',1,?,'{}'::jsonb)
                """,project,org,owner.id());
            update(c,"""
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?,?,?,'SANDBOX',?,?,'REV',ARRAY['DOCUMENTATION']::text[],
                        statement_timestamp()+interval '3 days')
                """,UUID.randomUUID(),project,org,docsReviewer.id(),owner.id());
            return null;
        });
        String root="/v1/projects/"+project+"/technical/open-monitoring";
        JsonNode batch=uploadCsv(root+"/batches",op,DATA);
        String id=batch.path("id").asText();
        for(int line: new int[]{2,3}) {
            var response=call("POST",root+"/batches/"+id+"/rows/"+line+"/reviews",
                qaReviewer.cookie(),"\"0\"", """
                {"outcome":"ACCEPTED_FOR_QA",
                 "reason":"Leitura tecnicamente consistente para conciliação horária."}
                """);
            assertEquals(200,response.statusCode(),response.body());
        }
        String snapshots=root+"/period-qa/snapshots";
        String period="{\"from\":\""+FROM+"\",\"until\":\""+TO+"\"}";
        var created=call("POST",snapshots,qaReviewer.cookie(),null,period);
        assertEquals(201,created.statusCode(),created.body());
        String snapshot=json(created).path("id").asText();
        String dossierUrl=root+"/dossiers";
        var bound=call("PUT",dossierUrl+"/M1",owner.cookie(),null,
            "{\"snapshotId\":\""+snapshot+"\",\"note\":\"Vínculo de QA do primeiro período M1, apenas para preparação MR.\"}");
        assertEquals(200,bound.statusCode(),bound.body());
        assertEquals("M1",json(bound).at("/reference/code").asText());
        assertEquals("PENDING_REQUIREMENTS",json(bound).path("state").asText());
        assertFalse(json(bound).path("canUseForE8").asBoolean());
        assertEquals(404,call("GET",dossierUrl,outside.cookie(),null,null).statusCode());
        assertEquals(404,call("PUT",dossierUrl+"/M2",op.cookie(),null,
            "{\"snapshotId\":\""+snapshot+"\",\"note\":\"Tentativa de reatribuição não autorizada por operador.\"}").statusCode());

        String technical="/v1/projects/"+project+"/technical";
        String sectionPath=technical+"/pd-sections/monitoring";
        var drafted=call("PUT",sectionPath,owner.cookie(),null,
            "{\"guideVersion\":1,\"narrative\":\"Plano de medição ED e EC com dados sob QA interno e dossiê de monitoramento M1.\"}");
        assertEquals(200,drafted.statusCode(),drafted.body());
        assertEquals(1,json(drafted).path("number").asInt());
        JsonNode pdInitial=json(call("GET",technical+"/pd-reviews",owner.cookie(),null,null));
        assertEquals(1,pdInitial.size());
        assertEquals("BLOCKED_MONITORING_REQUIREMENTS",
            pdInitial.get(0).at("/monitoringDependency/state").asText());
        assertTrue(pdInitial.get(0).path("outdated").asBoolean());
        assertFalse(pdInitial.get(0).path("actions").toString().contains("SUBMITTED"));

        String admission=snapshots+"/"+snapshot+"/admissibility";
        JsonNode initial=json(call("GET",admission,owner.cookie(),null,null));
        assertEquals("PENDING_DOCUMENTARY_REVIEW",initial.path("state").asText());
        assertFalse(initial.path("documentaryComplete").asBoolean());
        assertFalse(initial.path("usableForE8").asBoolean());
        assertEquals(0,new java.math.BigDecimal(initial.path("historicalEdKwh").asText()).compareTo(
            new java.math.BigDecimal("2700")));
        assertTrue(initial.path("gates").toString().contains("SUPPORT_DOCUMENT_MISSING"));

        assertEquals(404,call("GET",admission,outside.cookie(),null,null).statusCode());
        String proofPath=snapshots+"/"+snapshot+"/support";
        String pdf="%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n";
        String[][] subjects={{"METER_CALIBRATION","ED-1"},{"METER_CALIBRATION","EC-1"},
            {"SOURCE_RIGHTS","METER-API-1"}};
        for(int i=0;i<subjects.length;i++) {
            String requestKey=key();
            String payload=pdf+"%"+i+"\n";
            var result=uploadPdf(proofPath,op.cookie(),subjects[i][0],subjects[i][1],
                requestKey,payload);
            assertEquals(201,result.statusCode(),result.body());
            var proof=json(result);
            assertEquals(subjects[i][0],proof.path("kind").asText());
            assertEquals(64,proof.path("sha256").asText().length());
            assertFalse(result.body().contains("encrypted"));
            assertEquals(proof,json(uploadPdf(proofPath,op.cookie(),subjects[i][0],
                subjects[i][1],requestKey,payload)));
            String reviewUrl=root+"/period-qa/support/"+proof.path("id").asText()+"/reviews";
            assertEquals(404,call("POST",reviewUrl,owner.cookie(),"\"0\"",ACCEPTED).statusCode());
            assertEquals(404,call("POST",reviewUrl,op.cookie(),"\"0\"",ACCEPTED).statusCode());
            assertEquals(403,call("POST",reviewUrl,qaReviewer.cookie(),"\"0\"",ACCEPTED).statusCode());
            var outcome=call("POST",reviewUrl,docsReviewer.cookie(),"\"0\"",ACCEPTED);
            assertEquals(200,outcome.statusCode(),outcome.body());
            assertEquals(1,json(outcome).path("version").asInt());
            assertEquals("\"1\"",outcome.headers().firstValue("ETag").orElseThrow());
        }
        var valid=json(call("GET",admission,owner.cookie(),null,null));
        assertEquals("READY_FOR_NORMATIVE_REVIEW",valid.path("state").asText(),valid.toString());
        assertTrue(valid.path("documentaryComplete").asBoolean());
        assertFalse(valid.path("stale").asBoolean());
        assertFalse(valid.path("usableForE8").asBoolean());
        assertEquals(3,valid.path("documents").size());
        assertEquals(0,valid.path("gates").size());
        String methodUrl=dossierUrl+"/M1/method-review";
        JsonNode methodPending=json(call("GET",methodUrl,owner.cookie(),null,null));
        assertEquals("PENDING_INTERNAL_REVIEW",methodPending.path("state").asText());
        assertFalse(methodPending.path("canUseForE8").asBoolean());
        assertEquals(404,call("GET",methodUrl,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",methodUrl,outside.cookie(),null,null).statusCode());
        String methodBody=methodAssessment();
        assertEquals(404,postMethod(methodUrl,owner.cookie(),key(),"\"0\"",methodBody).statusCode());
        assertEquals(403,postMethod(methodUrl,qaReviewer.cookie(),key(),"\"0\"",methodBody).statusCode());
        String methodKey=key();
        var methodChecked=postMethod(methodUrl,docsReviewer.cookie(),methodKey,"\"0\"",methodBody);
        assertEquals(200,methodChecked.statusCode(),methodChecked.body());
        assertEquals("CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION",
            json(methodChecked).path("state").asText());
        assertEquals("VM0038",json(methodChecked).path("methodology").asText());
        assertEquals("1.1",json(methodChecked).path("methodologyEdition").asText());
        assertEquals(1,json(methodChecked).at("/review/version").asInt());
        assertEquals(6,json(methodChecked).at("/review/checks").size());
        assertFalse(json(methodChecked).path("canUseForE8").asBoolean());
        assertEquals(methodChecked.body(),postMethod(methodUrl,docsReviewer.cookie(),methodKey,
            "\"0\"",methodBody).body());
        assertEquals(409,postMethod(methodUrl,docsReviewer.cookie(),methodKey,"\"0\"",
            methodBody.replace("Justificativa final da conformidade documental interna.",
                "Justificativa divergente para conflito de idempotência.")).statusCode());
        assertEquals(412,postMethod(methodUrl,docsReviewer.cookie(),key(),"\"0\"",methodBody).statusCode());
        assertEquals(1,json(call("GET",methodUrl+"/versions/1",
            owner.cookie(),null,null)).size());

        String energyUrl=dossierUrl+"/M1/e8-energy-candidates";
        String energyPayload="{\"dossierVersion\":1,\"methodReviewVersion\":1}";
        assertEquals(404,call("GET",energyUrl,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",energyUrl,outside.cookie(),null,null).statusCode());
        assertEquals(404,postMethod(energyUrl,docsReviewer.cookie(),key(),"\"1\"",
            energyPayload).statusCode());
        assertEquals(412,postMethod(energyUrl,owner.cookie(),key(),"\"0\"",
            energyPayload).statusCode());
        String prepareKey=key();
        var packageCreated=postMethod(energyUrl,owner.cookie(),prepareKey,"\"1\"",
            energyPayload);
        assertEquals(201,packageCreated.statusCode(),packageCreated.body());
        JsonNode energy=json(packageCreated);
        assertEquals("NOT_ADMITTED",energy.path("state").asText());
        assertFalse(energy.path("usableForE8").asBoolean());
        assertEquals(0,new java.math.BigDecimal(energy.path("edKwh").asText())
            .compareTo(new java.math.BigDecimal("2700")));
        assertEquals(0,new java.math.BigDecimal(energy.path("ecKwh").asText())
            .compareTo(new java.math.BigDecimal("3000")));
        assertEquals(1,energy.path("hourCount").asInt());
        assertEquals(1,energy.path("slotCount").asInt());
        assertEquals(1,energy.path("hours").size());
        assertEquals("DCFC-1",energy.at("/hours/0/chargerCode").asText());
        assertEquals(64,energy.path("sourceSha256").asText().length());
        assertEquals(packageCreated.body(),postMethod(energyUrl,owner.cookie(),prepareKey,
            "\"1\"",energyPayload).body());
        // A mismatched If-Match is rejected as a stale precondition before
        // the command's idempotency fingerprint can be reused.
        assertEquals(412,postMethod(energyUrl,owner.cookie(),prepareKey,"\"1\"",
            "{\"dossierVersion\":1,\"methodReviewVersion\":2}").statusCode());
        assertEquals(1,json(call("GET",energyUrl,owner.cookie(),null,null)).size());
        JsonNode calcWithEnergy=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertTrue(calcWithEnergy.path("gates").toString().contains("E8_ENERGY_INPUT_M1"));
        assertFalse(calcWithEnergy.path("canRunE8").asBoolean());
        for (JsonNode gate : calcWithEnergy.path("gates")) {
            String reason=gate.path("reason").asText();
            assertFalse(reason.contains("_"), gate.path("code").asText()+" "+reason);
        }
        assertTrue(calcGate(calcWithEnergy,"E8_ENERGY_INPUT_M1").path("reason").asText()
            .contains("admissão normativa ainda não existe"));

        // Project-scoped SIN hourly factor candidate, with a declared source
        // (the synthetic example.org fixture is NOT a real VT0011 value).
        String efelUrl=dossierUrl+"/M1/efel-candidates";
        assertEquals(0,json(call("GET",efelUrl,owner.cookie(),null,null)).size());
        assertEquals(404,call("GET",efelUrl,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",efelUrl,outside.cookie(),null,null).statusCode());
        String efelBody=efelFixture(energy.path("id").asText(),"2027-02-01T00:00:00Z","0.5");
        assertEquals(404,postMethod(efelUrl,docsReviewer.cookie(),key(),"\"0\"",
            efelBody).statusCode());
        assertEquals(412,postMethod(efelUrl,owner.cookie(),key(),"\"1\"",
            efelBody).statusCode());
        assertEquals(409,postMethod(efelUrl,owner.cookie(),key(),"\"0\"",
            efelFixture(energy.path("id").asText(),"2027-02-01T01:00:00Z","0.5")).statusCode());
        assertEquals(422,postMethod(efelUrl,owner.cookie(),key(),"\"0\"",
            efelBody.replace("\"hours\":[", "\"hours\":[] ,\"unused\":[")).statusCode());
        String efelKey=key();
        var efelCreated=postMethod(efelUrl,owner.cookie(),efelKey,"\"0\"",efelBody);
        assertEquals(201,efelCreated.statusCode(),efelCreated.body());
        JsonNode efel=json(efelCreated);
        assertEquals("PENDING_INTERNAL_REVIEW_NOT_ADMITTED",efel.path("state").asText());
        assertEquals("tCO2e/kWh",efel.path("normalizedUnit").asText());
        assertEquals(0,new java.math.BigDecimal("0.0005").compareTo(
            new java.math.BigDecimal(efel.at("/hours/0/normalizedValue").asText())));
        assertEquals(1,efel.path("hourCount").asInt());
        assertEquals(64,efel.path("contentSha256").asText().length());
        assertFalse(efel.path("usableForE8").asBoolean());
        assertEquals(efelCreated.body(),postMethod(efelUrl,owner.cookie(),efelKey,
            "\"0\"",efelBody).body());
        assertEquals(409,postMethod(efelUrl,owner.cookie(),efelKey,"\"0\"",
            efelBody.replace("\"value\":0.5", "\"value\":0.7")).statusCode());
        // Versioned candidate E8 binding requires an independently checked
        // factor; the unreviewed upload is not enough to create a pair.
        String bindingUrl=dossierUrl+"/M1/e8-input-bindings";
        String bindingBody="{\"energyCandidateId\":\""+energy.path("id").asText()+
            "\",\"efelCandidateId\":\""+efel.path("id").asText()+"\"}";
        assertEquals(0,json(call("GET",bindingUrl,owner.cookie(),null,null)).size());
        JsonNode missingInputs=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertEquals("MISSING",calcGate(missingInputs,"E8_INPUT_BINDING_M1").path("state").asText());
        assertEquals("MISSING",calcGate(missingInputs,"E8_FLEET_SELECTION_M1").path("state").asText());
        assertEquals(404,call("GET",bindingUrl,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",bindingUrl,outside.cookie(),null,null).statusCode());
        assertEquals(404,postMethod(bindingUrl,docsReviewer.cookie(),key(),
            "\"0\"",bindingBody).statusCode());
        // An unreviewed candidate cannot be bound even with a valid ETag.
        assertEquals(409,postMethod(bindingUrl,owner.cookie(),key(),
            "\"0\"",bindingBody).statusCode());
        String efelReviewUrl=efelUrl+"/"+efel.path("id").asText()+"/reviews";
        String efelReviewBody="""
            {"outcome":"CHECKED_INTERNAL",
             "rationale":"Origem e coerência conferidas apenas internamente, sem aprovação normativa.",
             "evidenceReference":"Dossiê documental interno, versão de fonte declarada."}
            """;
        assertEquals(404,postMethod(efelReviewUrl,owner.cookie(),key(),"\"0\"",
            efelReviewBody).statusCode());
        String efelReviewKey=key();
        var reviewedEfel=postMethod(efelReviewUrl,docsReviewer.cookie(),
            efelReviewKey,"\"0\"",efelReviewBody);
        assertEquals(200,reviewedEfel.statusCode(),reviewedEfel.body());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",json(reviewedEfel).path("state").asText());
        assertEquals(1,json(reviewedEfel).at("/review/version").asInt());
        assertFalse(json(reviewedEfel).path("usableForE8").asBoolean());
        assertEquals(reviewedEfel.body(),postMethod(efelReviewUrl,docsReviewer.cookie(),
            efelReviewKey,"\"0\"",efelReviewBody).body());
        JsonNode preflightWithEfel=json(call("GET",
            technical+"/open-calculation-readiness",owner.cookie(),null,null));
        assertTrue(preflightWithEfel.path("gates").toString().contains("E8_EFEL_M1"));
        assertFalse(preflightWithEfel.path("canRunE8").asBoolean());

        // Immutable input-binding candidate pins existing real-project ED/EC
        // and hourly EFel versions without running PE or granting admission.
        assertEquals(412,postMethod(bindingUrl,owner.cookie(),key(),
            "\"1\"",bindingBody).statusCode());
        String bindKey=key();
        var boundE8Response=postMethod(bindingUrl,owner.cookie(),bindKey,
            "\"0\"",bindingBody);
        assertEquals(201,boundE8Response.statusCode(),boundE8Response.body());
        JsonNode boundE8=json(boundE8Response);
        assertEquals("CANDIDATE_NOT_ADMITTED",boundE8.path("state").asText());
        assertFalse(boundE8.path("usableForE8").asBoolean());
        assertEquals(1,boundE8.path("version").asInt());
        assertEquals(1,boundE8.path("hourCount").asInt());
        assertEquals(1,boundE8.path("slotCount").asInt());
        assertEquals(64,boundE8.path("inputSha256").asText().length());
        assertEquals(energy.path("sourceSha256").asText(),
            boundE8.path("energySha256").asText());
        assertEquals(efel.path("contentSha256").asText(),
            boundE8.path("efelSha256").asText());
        assertEquals("0.0005",boundE8.at("/hours/0/gridFactorTco2ePerKwh").asText());
        assertEquals("2700",boundE8.at("/hours/0/edKwh").asText());
        assertEquals("3000",boundE8.at("/hours/0/ecKwh").asText());
        assertTrue(boundE8.path("gates").toString().contains("EVR_AND_COMPARABLES_NOT_ADMITTED"));
        assertEquals(boundE8Response.body(),postMethod(bindingUrl,owner.cookie(),
            bindKey,"\"0\"",bindingBody).body());
        // A fresh command with an obsolete If-Match is rejected before
        // duplicate-content validation.
        assertEquals(412,postMethod(bindingUrl,owner.cookie(),key(),
            "\"0\"",bindingBody).statusCode());
        assertEquals(409,postMethod(bindingUrl,owner.cookie(),key(),
            "\"1\"",bindingBody).statusCode());
        assertEquals(1,json(call("GET",bindingUrl,owner.cookie(),null,null)).size());
        String bindingDetailUrl=bindingUrl+"/"+boundE8.path("id").asText();
        var bindingDetail=call("GET",bindingDetailUrl,owner.cookie(),null,null);
        assertEquals(200,bindingDetail.statusCode(),bindingDetail.body());
        assertTrue(bindingDetail.headers().firstValue("Cache-Control")
            .orElse("").contains("no-store"));
        assertEquals(boundE8.path("inputSha256").asText(),
            json(bindingDetail).path("inputSha256").asText());
        assertEquals(404,call("GET",bindingDetailUrl,outside.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",bindingDetailUrl,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",bindingUrl+
            "/00000000-0000-0000-0000-000000000001",
            owner.cookie(),null,null).statusCode());
        JsonNode preparedEnergy=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertFalse(preparedEnergy.path("canRunE8").asBoolean());
        assertEquals("NOT_ADMITTED",
            calcGate(preparedEnergy,"E8_INPUT_BINDING_M1").path("state").asText());
        assertEquals("MISSING",
            calcGate(preparedEnergy,"E8_FLEET_SELECTION_M1").path("state").asText());
        assertTrue(preparedEnergy.path("emissions").path("erTco2e").isNull());
        // Staff-catalogue EVR/PBEV remains PRIVATE. Only an explicit staff
        // handoff can publish metadata to the project after a current V63
        // energy/factor binding; a project owner is not platform staff.
        String fleetRoute=dossierUrl+"/M1/e8-fleet-selections";
        assertEquals(0,json(call("GET",fleetRoute,owner.cookie(),null,null)).size());
        assertEquals(404,call("GET",fleetRoute,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",fleetRoute,outside.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",fleetRoute+
            "/00000000-0000-0000-0000-000000000001",
            owner.cookie(),null,null).statusCode());
        String staffRoute="/v1/platform/e8-fleet-selections";
        assertTrue(call("GET",staffRoute+"/options",owner.cookie(),null,null)
            .statusCode()>=400);
        assertTrue(call("POST",staffRoute,owner.cookie(),"\"0\"",
            "{\"projectId\":\""+project+"\",\"period\":\"M1\","+
            "\"energyBindingId\":\""+boundE8.path("id").asText()+
            "\",\"ratingSnapshotId\":\"00000000-0000-0000-0000-000000000001\"}")
            .statusCode()>=400);
        assertEquals(0,json(call("GET",fleetRoute,owner.cookie(),null,null)).size());

        // Positive staff-to-project transfer: sources are deliberately
        // synthetic and live only inside this integration test database.
        UUID[] preparedCatalog=seedScopedRatingCatalog(
            org,staffManager.id(),staffReviewer.id());
        String optionId=boundE8.path("id").asText();
        JsonNode staffOptions=json(call("GET",staffRoute+"/options",
            staffManager.cookie(),null,null));
        assertTrue(staffOptions.toString().contains(optionId),
            "Authorized MFA staff must see only eligible same-org bindings");
        assertFalse(staffOptions.toString().contains("afecKwh100mi"));
        assertFalse(staffOptions.toString().contains("observed_stock"));
        assertEquals(404,call("GET",staffRoute+"/options",
            outside.cookie(),null,null).statusCode());
        String handoffBody="{\"projectId\":\""+project+"\",\"period\":\"M1\""+
            ",\"energyBindingId\":\""+optionId+"\""+
            ",\"ratingSnapshotId\":\""+preparedCatalog[0]+"\"}";
        assertEquals(404,postMethod(staffRoute,staffReviewer.cookie(),key(),
            "\"0\"",handoffBody).statusCode());
        assertEquals(412,postMethod(staffRoute,staffManager.cookie(),key(),
            "\"1\"",handoffBody).statusCode());
        String handoffKey=key();
        var handoffCreated=postMethod(staffRoute,staffManager.cookie(),
            handoffKey,"\"0\"",handoffBody);
        assertEquals(201,handoffCreated.statusCode(),handoffCreated.body());
        JsonNode handoff=json(handoffCreated);
        assertEquals("PREPARATORY_NOT_ADMITTED",handoff.path("state").asText());
        assertFalse(handoff.path("usableForE8").asBoolean());
        assertEquals(1,handoff.path("version").asInt());
        assertEquals(preparedCatalog[0].toString(),
            handoff.path("ratingSnapshotId").asText());
        assertEquals(optionId,handoff.path("energyBindingId").asText());
        assertEquals(64,handoff.path("selectionSha256").asText().length());
        assertEquals(64,handoff.path("fleetSourceSha256").asText().length());
        assertEquals(64,handoff.path("ratingLineageSha256").asText().length());
        assertFalse(handoff.toString().contains("afecKwh100mi"));
        assertFalse(handoff.toString().contains("denominator"));
        assertFalse(handoff.toString().contains("fossilMpg"));
        assertEquals(handoffCreated.body(),postMethod(staffRoute,
            staffManager.cookie(),handoffKey,"\"0\"",handoffBody).body());
        assertEquals(412,postMethod(staffRoute,staffManager.cookie(),key(),
            "\"0\"",handoffBody).statusCode());
        assertEquals(409,postMethod(staffRoute,staffManager.cookie(),key(),
            "\"1\"",handoffBody).statusCode());
        JsonNode visibleHandoff=json(call("GET",fleetRoute,
            owner.cookie(),null,null));
        assertEquals(1,visibleHandoff.size());
        assertEquals(handoff.path("selectionSha256").asText(),
            visibleHandoff.get(0).path("selectionSha256").asText());
        assertFalse(visibleHandoff.toString().contains("afecKwh100mi"));
        String fleetDetailUrl=fleetRoute+"/"+handoff.path("id").asText();
        var fleetDetail=call("GET",fleetDetailUrl,owner.cookie(),null,null);
        assertEquals(200,fleetDetail.statusCode(),fleetDetail.body());
        assertTrue(fleetDetail.headers().firstValue("Cache-Control")
            .orElse("").contains("no-store"));
        assertEquals(404,call("GET",fleetDetailUrl,
            op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",fleetDetailUrl,
            outside.cookie(),null,null).statusCode());
        JsonNode preparedFleet=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertFalse(preparedFleet.path("canRunE8").asBoolean());
        assertEquals("NOT_ADMITTED",
            calcGate(preparedFleet,"E8_INPUT_BINDING_M1").path("state").asText());
        assertEquals("NOT_ADMITTED",
            calcGate(preparedFleet,"E8_FLEET_SELECTION_M1").path("state").asText());
        assertTrue(preparedFleet.path("emissions").path("beTco2e").isNull());
        assertTrue(preparedFleet.path("emissions").path("peTco2e").isNull());
        assertTrue(preparedFleet.path("emissions").path("erTco2e").isNull());
        assertFalse(preparedFleet.toString().contains(handoff.path("fleetSourceSha256").asText()));
        assertFalse(preparedFleet.toString().contains(preparedCatalog[0].toString()));

        Actor preparer=actor();
        grant(project,org,owner.id(),preparer.id(),"PREP");
        pinCompleteSet(project,org,staffManager.id(),staffReviewer.id());
        String runs=dossierUrl+"/M1/e8-runs";
        var authorRun=postMethod(runs,owner.cookie(),key(),"\"0\"","{}");
        assertEquals(201,authorRun.statusCode(),authorRun.body());
        assertEquals("BLOCKED",json(authorRun).path("state").asText());
        assertTrue(json(authorRun).path("memory").isNull());
        assertTrue(json(authorRun).path("gates").toString().contains("ACTOR_SEGREGATION"));
        assertFalse(json(authorRun).path("canIssueVcu").asBoolean());
        var rehearsed=postMethod(runs,preparer.cookie(),key(),"\"1\"","{}");
        assertEquals(201,rehearsed.statusCode(),rehearsed.body());
        JsonNode rehearsal=json(rehearsed);
        assertEquals("REVIEWED_INTERNAL",rehearsal.path("state").asText());
        assertEquals(2,rehearsal.path("version").asInt());
        assertFalse(rehearsal.path("canIssueVcu").asBoolean());
        assertFalse(rehearsal.at("/memory/usableForE8").asBoolean());
        assertEquals("PURE_MATH_TEST_ONLY_NOT_ADMITTED",rehearsal.at("/memory/kernelState").asText());
        assertEquals("4.05",rehearsal.at("/memory/beTco2e").asText(),rehearsal.path("memory").toString());
        assertEquals("1.5",rehearsal.at("/memory/peTco2e").asText(),rehearsal.path("memory").toString());
        assertEquals("2.55",rehearsal.at("/memory/erTco2e").asText(),rehearsal.path("memory").toString());
        assertFalse(rehearsal.toString().contains("EF-SYN"));
        assertFalse(rehearsal.toString().contains("SIM-DY"));
        JsonNode stillBlocked=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertFalse(stillBlocked.path("canRunE8").asBoolean());
        assertTrue(stillBlocked.at("/emissions/erTco2e").isNull());
        JsonNode sections=json(call("GET",technical+"/pd-sections",owner.cookie(),null,null));
        JsonNode quantification=null;
        for (JsonNode section : sections) {
            if ("quantification".equals(section.at("/guide/id").asText())) quantification=section;
        }
        assertNotNull(quantification);
        assertEquals("MEMORY",quantification.at("/calculation/situation").asText());
        assertEquals("4.05",quantification.at("/calculation/beTco2e").asText());
        assertEquals("1.5",quantification.at("/calculation/peTco2e").asText());
        assertEquals("2.55",quantification.at("/calculation/erDisplay").asText());
        assertFalse(quantification.at("/calculation/usableForE8").asBoolean());
        assertFalse(quantification.at("/calculation/marksDocumentReady").asBoolean());
        assertTrue(quantification.at("/calculation/text").asText().contains("O PD não fica pronto"));
        assertFalse(quantification.at("/calculation").toString().contains("EF-SYN"));
        JsonNode additionality=null;
        for (JsonNode section : sections) {
            if ("additionality".equals(section.at("/guide/id").asText())) additionality=section;
        }
        assertNotNull(additionality);
        assertFalse(additionality.at("/additionality/declarationIsOpinion").asBoolean());
        assertFalse(additionality.at("/additionality/concludesAdditionality").asBoolean());
        assertFalse(additionality.at("/additionality/usableForE8").asBoolean());
        assertFalse(additionality.at("/additionality/marksDocumentReady").asBoolean());
        assertEquals(3,additionality.at("/additionality/criteria").size());
        assertFalse(additionality.at("/additionality/criteria").toString().contains("MARKET_ROUTE"));
        JsonNode applicability=null;
        for (JsonNode section : sections) {
            if ("applicability".equals(section.at("/guide/id").asText())) applicability=section;
        }
        assertNotNull(applicability);
        assertEquals("0,7",applicability.at("/applicability/citedStockPercent").asText());
        assertEquals("7,5",applicability.at("/applicability/stockLimitPercent").asText());
        assertEquals("30",applicability.at("/applicability/salesLimitPercent").asText());
        assertTrue(applicability.at("/applicability/citationBelowStockLimit").asBoolean());
        assertFalse(applicability.at("/applicability/closesApplicability").asBoolean());
        assertFalse(applicability.at("/applicability/usableForE8").asBoolean());
        assertFalse(applicability.at("/applicability/marksDocumentReady").asBoolean());
        JsonNode baseline=null;
        for (JsonNode section : sections) {
            if ("baseline".equals(section.at("/guide/id").asText())) baseline=section;
        }
        assertNotNull(baseline);
        assertEquals("REFERENCE",baseline.at("/baseline/situation").asText());
        assertEquals(handoff.path("period").asText(),baseline.at("/baseline/period").asText());
        assertEquals(handoff.path("observationYear").asInt(),
            baseline.at("/baseline/observationYear").asInt());
        assertEquals(handoff.path("cycle").asText(),baseline.at("/baseline/cycle").asText());
        assertFalse(baseline.at("/baseline/declarationIsRating").asBoolean());
        assertFalse(baseline.at("/baseline/admitsBaseline").asBoolean());
        assertFalse(baseline.at("/baseline/copiesLicensedRatings").asBoolean());
        assertFalse(baseline.at("/baseline/usableForE8").asBoolean());
        assertFalse(baseline.at("/baseline/marksDocumentReady").asBoolean());
        assertFalse(baseline.at("/baseline").toString().toLowerCase().contains("afec"));
        assertFalse(baseline.at("/baseline").toString().toLowerCase().contains("mpg"));
        assertFalse(baseline.at("/baseline").toString().contains("denominator"));

        String vtUrl=efelUrl+"/"+efel.path("id").asText()+"/vt0011-workbooks";
        assertEquals(0,json(call("GET",vtUrl,owner.cookie(),null,null)).size());
        assertEquals(404,call("GET",vtUrl,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",vtUrl,outside.cookie(),null,null).statusCode());
        String vtBody=vt0011Fixture(efel.path("id").asText(),"0.5");
        assertEquals(404,postMethod(vtUrl,docsReviewer.cookie(),key(),"\"0\"",
            vtBody).statusCode());
        assertEquals(412,postMethod(vtUrl,owner.cookie(),key(),"\"1\"",
            vtBody).statusCode());
        var vtBad=postMethod(vtUrl,owner.cookie(),key(),"\"0\"",
            vtBody.replace("SIMPLE_OM","INVENTED_METHOD"));
        assertTrue(vtBad.statusCode()>=400,vtBad.body());
        String vtKey=key();
        var vtCreated=postMethod(vtUrl,owner.cookie(),vtKey,"\"0\"",vtBody);
        assertEquals(201,vtCreated.statusCode(),vtCreated.body());
        JsonNode vt=json(vtCreated);
        assertEquals(1,vt.path("version").asInt());
        assertEquals("CASE_2_INCREASE_GRID_CONSUMPTION",vt.path("caseCode").asText());
        assertEquals("1.00",vt.at("/preview/omWeight").asText());
        assertEquals("0.00",vt.at("/preview/bmWeight").asText());
        assertEquals("tCO2/kWh",vt.at("/preview/convertedUnit").asText());
        assertEquals(0,new java.math.BigDecimal("0.0005").compareTo(
            new java.math.BigDecimal(vt.at("/preview/illustrativeCmTco2PerKwh").asText())));
        assertEquals("PENDING_INTERNAL_REVIEW_NOT_ADMITTED",vt.path("state").asText());
        assertFalse(vt.path("usableForE8").asBoolean());
        assertTrue(vt.path("gates").toString().contains("CO2_TO_CO2E"));
        assertEquals(vtCreated.body(),postMethod(vtUrl,owner.cookie(),vtKey,
            "\"0\"",vtBody).body());
        assertEquals(409,postMethod(vtUrl,owner.cookie(),vtKey,"\"0\"",
            vtBody.replace("\"omFactorTco2PerMwh\":0.5",
                "\"omFactorTco2PerMwh\":0.7")).statusCode());
        String vtReviewUrl=vtUrl+"/"+vt.path("id").asText()+"/reviews";
        String vtReviewBody="""
            {"outcome":"CHECKED_INTERNAL",
             "rationale":"Caderno de cálculo examinado internamente sem confirmação da OM real ou dos gases.",
             "evidenceReference":"Lista de verificações documentais para caderno OM do projeto."}
            """;
        assertEquals(404,postMethod(vtReviewUrl,owner.cookie(),key(),"\"0\"",
            vtReviewBody).statusCode());
        String vtReviewKey=key();
        var vtChecked=postMethod(vtReviewUrl,docsReviewer.cookie(),
            vtReviewKey,"\"0\"",vtReviewBody);
        assertEquals(200,vtChecked.statusCode(),vtChecked.body());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",json(vtChecked).path("state").asText());
        assertFalse(json(vtChecked).path("usableForE8").asBoolean());
        assertEquals(1,json(vtChecked).at("/review/version").asInt());
        assertEquals(vtChecked.body(),postMethod(vtReviewUrl,docsReviewer.cookie(),
            vtReviewKey,"\"0\"",vtReviewBody).body());
        assertEquals(1,json(call("GET",vtUrl,owner.cookie(),null,null)).size());
        JsonNode preflightVt=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertTrue(preflightVt.path("gates").toString().contains("E8_VT0011_CASE2_M1"));
        assertFalse(preflightVt.path("canRunE8").asBoolean());

        String simpleOmUrl=dossierUrl+"/M1/tool07-simple-om-a1/"+
            vt.path("id").asText()+"/runs";
        assertEquals(0,json(call("GET",simpleOmUrl,owner.cookie(),null,null)).size());
        assertEquals(404,call("GET",simpleOmUrl,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",simpleOmUrl,outside.cookie(),null,null).statusCode());
        String simpleOmBody=simpleOmFixture(vt.path("id").asText());
        assertEquals(404,postMethod(simpleOmUrl,docsReviewer.cookie(),key(),"\"0\"",
            simpleOmBody).statusCode());
        assertEquals(412,postMethod(simpleOmUrl,owner.cookie(),key(),"\"1\"",
            simpleOmBody).statusCode());
        assertEquals(422,postMethod(simpleOmUrl,owner.cookie(),key(),"\"0\"",
            simpleOmBody.replace("\"consumptionTonnes\":10",
                "\"consumptionTonnes\":-10")).statusCode());
        String omKey=key();
        var omCreated=postMethod(simpleOmUrl,owner.cookie(),omKey,"\"0\"",simpleOmBody);
        assertEquals(201,omCreated.statusCode(),omCreated.body());
        JsonNode om=json(omCreated);
        assertEquals("LCMR_SCREEN_PASSED_PRELIMINARY_NOT_ADMITTED",om.path("state").asText());
        assertEquals(1,om.path("version").asInt());
        assertEquals("FIVE_YEAR_AVERAGE_SHARES",om.at("/preview/approach").asText());
        assertTrue(om.at("/preview/fiveYearLcmrScreenPassed").asBoolean());
        assertEquals(0,new java.math.BigDecimal("0.3").compareTo(
            new java.math.BigDecimal(om.at("/preview/selectedShare").asText())));
        assertEquals(0,new java.math.BigDecimal("700").compareTo(
            new java.math.BigDecimal(om.at("/preview/includedGenerationMwh").asText())));
        assertEquals(0,new java.math.BigDecimal("60").compareTo(
            new java.math.BigDecimal(om.at("/preview/totalEmissionsTco2").asText())));
        assertEquals(0,new java.math.BigDecimal("0.085714285714285714").compareTo(
            new java.math.BigDecimal(om.at("/preview/illustrativeOmTco2PerMwh").asText())));
        assertEquals(2,om.path("plants").size());
        assertEquals(5,om.path("shares").size());
        // Full auditable memory must be identical for the POST result, the
        // scoped detail endpoint and rehydration from persisted NUMERICs.
        String detailUrl=simpleOmUrl+"/"+om.path("id").asText();
        var detailResponse=call("GET",detailUrl,owner.cookie(),null,null);
        assertEquals(200,detailResponse.statusCode(),detailResponse.body());
        // The global Quarkus response filter may normalize Cache-Control to
        // no-store. Assert the security invariant, not header formatting.
        assertTrue(detailResponse.headers().firstValue("Cache-Control")
            .orElse("").contains("no-store"));
        JsonNode detail=json(detailResponse);
        assertEquals(om.path("id").asText(),detail.path("id").asText());
        assertEquals(5,detail.at("/preview/shareCalculations").size());
        assertEquals("0.3",detail.at("/preview/shareCalculations/0/annualShare").asText());
        assertEquals("20",
            detail.at("/preview/plantCalculations/0/fuelCalculations/0/emissionsTco2").asText());
        assertEquals("40",
            detail.at("/preview/plantCalculations/1/fuelCalculations/0/emissionsTco2").asText());
        assertEquals("TOOL07-V7.0-EQ4",
            detail.at("/preview/plantCalculations/0/fuelCalculations/0/equationRef").asText());
        assertEquals(om.at("/preview/plantCalculations").toString(),
            detail.at("/preview/plantCalculations").toString());
        assertEquals(om.at("/preview/shareCalculations").toString(),
            detail.at("/preview/shareCalculations").toString());
        assertEquals(404,call("GET",detailUrl,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",detailUrl,outside.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",simpleOmUrl+
            "/00000000-0000-0000-0000-000000000001",
            owner.cookie(),null,null).statusCode());
        assertEquals(64,om.path("contentSha256").asText().length());
        assertTrue(om.path("gates").toString().contains("OM_RESULT_DIFFERS"));
        assertFalse(om.path("usableForE8").asBoolean());
        assertEquals(omCreated.body(),postMethod(simpleOmUrl,owner.cookie(),omKey,
            "\"0\"",simpleOmBody).body());
        assertEquals(409,postMethod(simpleOmUrl,owner.cookie(),omKey,"\"0\"",
            simpleOmBody.replace("\"consumptionTonnes\":10",
                "\"consumptionTonnes\":11")).statusCode());
        assertEquals(1,json(call("GET",simpleOmUrl,owner.cookie(),null,null)).size());
        JsonNode calcWithOm=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertTrue(calcWithOm.path("gates").toString().contains("E8_TOOL07_SIMPLE_OM_M1"));
        assertFalse(calcWithOm.path("canRunE8").asBoolean());

        assertEquals("READY_FOR_NORMATIVE_REVIEW",
            json(call("GET",dossierUrl,owner.cookie(),null,null)).get(0).path("state").asText());
        JsonNode pdReady=json(call("GET",technical+"/pd-reviews",owner.cookie(),null,null));
        assertFalse(pdReady.get(0).path("outdated").asBoolean());
        assertEquals("READY_FOR_INTERNAL_PD_REVIEW",
            pdReady.get(0).at("/monitoringDependency/state").asText());
        assertTrue(pdReady.get(0).path("actions").toString().contains("SUBMITTED"));
        // Even if the same QA snapshot remains numerically unchanged, updating
        // the MR linkage invalidates the provenance pinned to PD revision 1.
        var mr2=putWithMatch(dossierUrl+"/M1",owner.cookie(),"\"1\"",
            "{\"snapshotId\":\""+snapshot+"\",\"note\":\"Nova versão editorial do dossiê MR para M1, com histórico íntegro.\"}");
        assertEquals(200,mr2.statusCode(),mr2.body());
        assertEquals(2,json(mr2).at("/reference/version").asInt());
        JsonNode staleEnergy=json(call("GET",energyUrl,owner.cookie(),null,null)).get(0);
        assertEquals("STALE_NOT_ADMITTED",staleEnergy.path("state").asText());
        JsonNode staleEfel=json(call("GET",efelUrl,owner.cookie(),null,null)).get(0);
        assertEquals("STALE_NOT_ADMITTED",staleEfel.path("state").asText());
        JsonNode fleetStale=json(call("GET",fleetDetailUrl,
            owner.cookie(),null,null));
        assertEquals("STALE_NOT_ADMITTED",fleetStale.path("state").asText());
        assertTrue(fleetStale.path("stale").asBoolean());
        assertFalse(fleetStale.path("usableForE8").asBoolean());
        assertEquals(handoff.path("selectionSha256").asText(),
            fleetStale.path("selectionSha256").asText());
        JsonNode bindingStale=json(call("GET",bindingDetailUrl,owner.cookie(),null,null));
        assertEquals("STALE_NOT_ADMITTED",bindingStale.path("state").asText());
        assertTrue(bindingStale.path("stale").asBoolean());
        assertFalse(bindingStale.path("usableForE8").asBoolean());
        assertEquals(boundE8.path("inputSha256").asText(),
            bindingStale.path("inputSha256").asText());
        assertEquals(boundE8.path("hours").toString(),
            bindingStale.path("hours").toString());
        JsonNode staleReadiness=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertEquals("STALE",calcGate(staleReadiness,
            "E8_INPUT_BINDING_M1").path("state").asText());
        assertEquals("STALE",calcGate(staleReadiness,
            "E8_FLEET_SELECTION_M1").path("state").asText());
        assertFalse(staleReadiness.path("canRunE8").asBoolean());
        assertTrue(staleReadiness.path("emissions").path("erTco2e").isNull());
        JsonNode staleWorkbook=json(call("GET",vtUrl,owner.cookie(),null,null)).get(0);
        assertEquals("STALE_NOT_ADMITTED",staleWorkbook.path("state").asText());
        JsonNode staleOm=json(call("GET",simpleOmUrl,owner.cookie(),null,null)).get(0);
        assertEquals("STALE_NOT_ADMITTED",staleOm.path("state").asText());
        assertFalse(staleOm.path("usableForE8").asBoolean());
        // Historical detail remains readable, version-pinned and visibly stale.
        var staleDetail=json(call("GET",detailUrl,owner.cookie(),null,null));
        assertEquals("STALE_NOT_ADMITTED",staleDetail.path("state").asText());
        assertEquals(om.at("/preview/plantCalculations").toString(),
            staleDetail.at("/preview/plantCalculations").toString());
        assertFalse(staleWorkbook.path("usableForE8").asBoolean());
        assertTrue(staleWorkbook.path("gates").toString().contains("SOURCE_OR_REVIEW_STALE"));
        assertFalse(staleEfel.path("usableForE8").asBoolean());
        assertTrue(staleEnergy.path("gates").toString().contains("MR_VERSION_CHANGED"));
        assertFalse(staleEnergy.path("usableForE8").asBoolean());
        // MR v2 invalidates the v1 candidate; stale source revision is a
        // precondition failure (412), not an ordinary business conflict.
        assertEquals(412,postMethod(energyUrl,owner.cookie(),key(),"\"1\"",
            energyPayload).statusCode());
        // A new MR version never inherits its predecessor's method review.
        JsonNode newMethod=json(call("GET",methodUrl,owner.cookie(),null,null));
        assertEquals(2,newMethod.path("dossierVersion").asInt());
        assertEquals("PENDING_INTERNAL_REVIEW",newMethod.path("state").asText());
        assertEquals(412,postMethod(methodUrl,docsReviewer.cookie(),key(),"\"0\"",
            methodBody).statusCode());
        assertEquals(1,json(call("GET",methodUrl+"/versions/1",
            owner.cookie(),null,null)).size());
        JsonNode pdObsolete=json(call("GET",technical+"/pd-reviews",owner.cookie(),null,null));
        assertTrue(pdObsolete.get(0).path("outdated").asBoolean());
        assertEquals("STALE",pdObsolete.get(0).at("/monitoringDependency/state").asText());
        assertTrue(pdObsolete.get(0).at("/monitoringDependency/gates").toString()
            .contains("PD_MONITORING_INPUT_CHANGED"));
        assertFalse(pdObsolete.get(0).path("actions").toString().contains("SUBMITTED"));
        var refreshedPd=putWithMatch(sectionPath,owner.cookie(),"\"1\"",
            "{\"guideVersion\":1,\"narrative\":\"Plano de medição ED e EC com dados sob QA interno e dossiê de monitoramento M1.\"}");
        assertEquals(200,refreshedPd.statusCode(),refreshedPd.body());
        assertEquals(2,json(refreshedPd).path("number").asInt());
        JsonNode refreshedQueue=json(call("GET",technical+"/pd-reviews",owner.cookie(),null,null));
        assertFalse(refreshedQueue.get(0).path("outdated").asBoolean());
        assertTrue(refreshedQueue.get(0).path("actions").toString().contains("SUBMITTED"));

        var submitted=call("POST",technical+"/pd-reviews/monitoring/versions/2",
            owner.cookie(),"\"0\"",
            "{\"state\":\"SUBMITTED\",\"reason\":\"Encaminho a seção revisada após conferência documental interna.\"}");
        assertEquals(200,submitted.statusCode(),submitted.body());
        var approved=call("POST",technical+"/pd-reviews/monitoring/versions/2",
            docsReviewer.cookie(),"\"1\"",
            "{\"state\":\"APPROVED_INTERNAL\",\"reason\":\"Parecer interno da seção, sem homologação normativa.\"}");
        assertEquals(200,approved.statusCode(),approved.body());

        long[] evidence=database.read(c->new long[]{
            first(c,"SELECT count(*) FROM app_project_evidence_blob WHERE project_id=? AND mime_type='application/pdf'",
                r->r.getLong(1),project),
            first(c,"SELECT count(*) FROM app_open_period_support_review WHERE project_id=?",
                r->r.getLong(1),project),
            first(c,"SELECT count(*) FROM pilot_import_file WHERE project_id=?",
                r->r.getLong(1),project)
        });
        assertArrayEquals(new long[]{3,3,0},evidence);
        byte[] ciphertext=database.read(c->first(c,"""
            SELECT encrypted FROM app_project_evidence_blob
            WHERE project_id=? AND mime_type='application/pdf' LIMIT 1
            """,r->r.getBytes(1),project));
        assertFalse(new String(ciphertext,StandardCharsets.ISO_8859_1).contains("%PDF-"));

        // Operational source interruption reported after a frozen QA snapshot
        // must immediately invalidate its current admissibility. Review outcome
        // never mutates the historical snapshot or implies E8 admission.
        String events=root+"/events";
        String occurrence="""
            {"kind":"SOURCE_OUTAGE","subjectType":"SOURCE",
             "subjectCode":"METER-API-1",
             "from":"2027-02-01T00:20:00Z","until":"2027-02-01T00:40:00Z",
             "incidentReference":"OUTAGE-2027-001",
             "description":"Interrupção do provedor operacional durante o intervalo medido."}
            """;
        String eventKey=key();
        var reported=postEvent(events,op.cookie(),eventKey,occurrence);
        assertEquals(201,reported.statusCode(),reported.body());
        String eventId=json(reported).path("id").asText();
        var visibleEvents=json(call("GET",events,owner.cookie(),null,null));
        assertEquals(1,visibleEvents.size());
        assertEquals(eventId,visibleEvents.get(0).path("id").asText());
        assertTrue(visibleEvents.get(0).path("review").isNull());
        assertEquals(422,postEvent(events,op.cookie(),key(),
            occurrence.replace("METER-API-1","UNKNOWN-EXTERNAL-SOURCE")).statusCode());
        assertEquals(eventId,json(postEvent(events,op.cookie(),eventKey,occurrence))
            .path("id").asText());
        assertEquals(409,postEvent(events,op.cookie(),eventKey,
            occurrence.replace("OUTAGE-2027-001","OUTAGE-2027-002")).statusCode());
        assertEquals(404,call("GET",events,outside.cookie(),null,null).statusCode());
        assertEquals(404,call("POST",events+"/"+eventId+"/reviews",
            op.cookie(),"\"0\"", """
            {"outcome":"CLEARED_FOR_INTERNAL_QA",
             "reason":"A origem confirmou a janela e comprovou reprocessamento integral.",
             "evidenceReference":"REPROCESS-2027-001"}
            """).statusCode());
        assertTrue(json(call("GET",root+
            "/period-qa?from=2027-02-01T00%3A00%3A00Z&until=2027-02-01T01%3A00%3A00Z",
            owner.cookie(),null,null)).path("gates").toString().contains("OPERATIONAL_SOURCE_OUTAGE"));
        assertEquals("BLOCKED_STALE",json(call("GET",admission,owner.cookie(),null,null))
            .path("state").asText());
        JsonNode staleMr=json(call("GET",dossierUrl,owner.cookie(),null,null));
        assertEquals("STALE",staleMr.get(0).path("state").asText());
        assertTrue(staleMr.get(0).path("stale").asBoolean());
        JsonNode stalePd=json(call("GET",technical+"/pd-reviews",docsReviewer.cookie(),null,null));
        assertTrue(stalePd.get(0).path("outdated").asBoolean());
        assertEquals("BLOCKED_MONITORING_REQUIREMENTS",
            stalePd.get(0).at("/monitoringDependency/state").asText());
        assertFalse(stalePd.get(0).path("actions").toString().contains("FROZEN_INTERNAL"));
        assertEquals(409,call("POST",technical+"/pd-reviews/monitoring/versions/2",
            docsReviewer.cookie(),"\"2\"",
            "{\"state\":\"FROZEN_INTERNAL\",\"reason\":\"Inadequado sem um MR atualizado.\"}").statusCode());
        String clearOccurrence="""
            {"outcome":"CLEARED_FOR_INTERNAL_QA",
             "reason":"A origem confirmou a janela e comprovou reprocessamento integral.",
             "evidenceReference":"REPROCESS-2027-001"}
            """;
        var cleared=call("POST",events+"/"+eventId+"/reviews",docsReviewer.cookie(),
            "\"0\"",clearOccurrence);
        assertEquals(200,cleared.statusCode(),cleared.body());
        assertEquals("\"1\"",cleared.headers().firstValue("ETag").orElseThrow());
        assertEquals(1,json(cleared).path("version").asInt());
        var afterReview=json(call("GET",events,owner.cookie(),null,null));
        assertEquals("CLEARED_FOR_INTERNAL_QA",
            afterReview.get(0).at("/review/outcome").asText());
        assertEquals("READY_FOR_INTERNAL_QA_SNAPSHOT",json(call("GET",root+
            "/period-qa?from=2027-02-01T00%3A00%3A00Z&until=2027-02-01T01%3A00%3A00Z",
            owner.cookie(),null,null)).path("state").asText());
        // Clearance changes SHA lineage; the old snapshot never resurrects.
        JsonNode oldSnapshot=json(call("GET",admission,owner.cookie(),null,null));
        assertEquals("BLOCKED_STALE",oldSnapshot.path("state").asText());
        assertTrue(oldSnapshot.path("gates").toString().contains("SNAPSHOT_REVISIONS_CHANGED"));
        assertFalse(oldSnapshot.path("usableForE8").asBoolean());
        assertEquals(1L,database.read(c->first(c,
            "SELECT count(*) FROM app_open_operational_event WHERE project_id=?",
            r->r.getLong(1),project)).longValue());

        String rejected="{\"outcome\":\"REJECTED\",\"reason\":\"Amostra não validada; outra prova necessária para manter a série.\"}";
        var change=call("POST",root+"/batches/"+id+"/rows/2/reviews",
            qaReviewer.cookie(),"\"1\"",rejected);
        assertEquals(200,change.statusCode(),change.body());
        JsonNode stale=json(call("GET",admission,owner.cookie(),null,null));
        assertEquals("BLOCKED_STALE",stale.path("state").asText());
        assertTrue(stale.path("stale").asBoolean());
        assertTrue(stale.path("gates").toString().contains("SNAPSHOT_QA_STALE"));
        assertFalse(stale.path("usableForE8").asBoolean());
        assertEquals("INTERNAL_QA_ONLY",json(call("GET",snapshots,owner.cookie(),null,null))
            .get(0).path("status").asText());
    }

    @Test
    void proofUploadDoesNotAutomaticallyVerifyRightsAndInvalidPdfFailsClosed() throws Exception {
        Actor owner=actor(),op=actor(),reviewer=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        configure(owner,project);
        grant(project,org,owner.id(),op.id(),"OP");
        grant(project,org,owner.id(),reviewer.id(),"REV");
        String root="/v1/projects/"+project+"/technical/open-monitoring";
        String batch=uploadCsv(root+"/batches",op,DATA).path("id").asText();
        for(int line:new int[]{2,3}) {
            assertEquals(200,call("POST",root+"/batches/"+batch+"/rows/"+line+"/reviews",
                reviewer.cookie(),"\"0\"", """
                {"outcome":"ACCEPTED_FOR_QA","reason":"Leitura consistente para QA interno."}
                """).statusCode());
        }
        String snapshot=json(call("POST",root+"/period-qa/snapshots",reviewer.cookie(),null,
            "{\"from\":\""+FROM+"\",\"until\":\""+TO+"\"}")).path("id").asText();
        String path=root+"/period-qa/snapshots/"+snapshot+"/support";
        assertEquals(422,uploadPdf(path,op.cookie(),"METER_CALIBRATION","ED-1",
            key(),"some plain text, not a PDF certificate").statusCode());
        assertEquals(409,uploadPdf(path,op.cookie(),"METER_CALIBRATION","NOT-A-METER",
            key(),"%PDF-1.4\n1 0 obj\nendobj\n%%EOF").statusCode());
        assertEquals(404,uploadPdf(path,reviewer.cookie(),"METER_CALIBRATION","ED-1",
            key(),"%PDF-1.4\n1 0 obj\nendobj\n%%EOF").statusCode());
        assertEquals(1L,database.read(c->first(c,
            "SELECT count(*) FROM app_project_evidence_blob WHERE project_id=?",
            r->r.getLong(1),project)).longValue()); // original CSV only
        assertFalse(json(call("GET",root+"/period-qa/snapshots/"+snapshot+"/admissibility",
            owner.cookie(),null,null)).path("documentaryComplete").asBoolean());
    }

    @Test
    void initialPdCanBeDraftedWithoutFutureMrAndNoE8IsAllowed() throws Exception {
        Actor owner=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_triage_revision
                  (project_id,organization_id,environment,version,actor_id,current_step,answers)
                VALUES (?,?,'SANDBOX',1,?,1,?::jsonb)
                """,project,org,owner.id(),
                "{\"1\":{\"methodologyCandidate\":\"VM0038\"}}");
            update(c,"""
                INSERT INTO app_technical_guide_revision
                  (project_id,organization_id,environment,guide_id,version,actor_id,answers)
                VALUES (?,?,'SANDBOX','monitoring',1,?,'{}'::jsonb)
                """,project,org,owner.id());
            return null;
        });
        String technical="/v1/projects/"+project+"/technical";
        assertEquals(0,json(call("GET",technical+"/open-monitoring/dossiers",
            owner.cookie(),null,null)).size());
        var section=call("PUT",technical+"/pd-sections/monitoring",owner.cookie(),null,
            "{\"guideVersion\":1,\"narrative\":\"Plano inicial de monitoramento, antes da operação e das leituras futuras.\"}");
        assertEquals(200,section.statusCode(),section.body());
        JsonNode queue=json(call("GET",technical+"/pd-reviews",owner.cookie(),null,null));
        assertEquals(1,queue.size());
        assertTrue(queue.get(0).path("monitoringDependency").isNull());
        assertFalse(queue.get(0).path("outdated").asBoolean());
        assertTrue(queue.get(0).path("actions").toString().contains("SUBMITTED"));
        JsonNode calc=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertFalse(calc.path("canRunE8").asBoolean());
        assertTrue(calc.path("gates").toString().contains("MR_PERIOD_DEPENDENCY"));
    }

    private static String simpleOmFixture(String workbook) {
        return """
          {"workbookId":"%s","workbookReviewVersion":1,
           "inventorySourceReference":"Inventário integral sintético de usinas SIN para teste.",
           "inventorySha256":"%s",
           "fiveYearSourceReference":"Série sintética quinquenal de geração SIN.",
           "fiveYearSha256":"%s",
           "fuelSourceExplanation":"Fonte e calor específico das duas usinas são sintéticos; sem admissão.",
           "systemBoundaryNote":"Sem off-grid no oráculo; unidades representam todo grupo não LCMR.",
           "importsExplanation":"Importações não verificadas, pendência obrigatória de análise normativa.",
           "calculation":{"dataYear":2026,"shareApproach":"FIVE_YEAR_AVERAGE_SHARES",
             "shares":[
               {"year":2022,"totalGridMwh":1000,"lcmrMwh":300},
               {"year":2023,"totalGridMwh":1000,"lcmrMwh":300},
               {"year":2024,"totalGridMwh":1000,"lcmrMwh":300},
               {"year":2025,"totalGridMwh":1000,"lcmrMwh":300},
               {"year":2026,"totalGridMwh":1000,"lcmrMwh":300}],
             "plants":[
               {"plantCode":"T1","netGenerationMwh":400,
                "fuels":[{"fuelCode":"coal","consumptionTonnes":10,
                  "ncvGjPerTonne":20,"efTco2PerGj":0.1,"sourceSha256":"%s"}]},
               {"plantCode":"T2","netGenerationMwh":300,
                "fuels":[{"fuelCode":"gas","consumptionTonnes":8,
                  "ncvGjPerTonne":25,"efTco2PerGj":0.2,"sourceSha256":"%s"}]}]}}
          """.formatted(workbook,"b".repeat(64),"c".repeat(64),
            "d".repeat(64),"e".repeat(64));
    }

    private static String vt0011Fixture(String efel,String om) {
        return """
          {"efelCandidateId":"%s",
           "omMethod":"SIMPLE_OM","omVintageMode":"EX_POST",
           "omDataYear":2026,"omFactorTco2PerMwh":%s,
           "omSourceReference":"Conjunto de dados OM meramente sintético do teste.",
           "omDatasetSha256":"%s",
           "omMethodJustification":"Método OM selecionado para testar estrutura; elegibilidade técnica não confirmada.",
           "gridBoundaryEvidence":"Fronteira elétrica SIN declarada no fixture.",
           "sampleSelectionEvidence":"Amostra de geração somente hipotética, sem verificação.",
           "importTreatmentEvidence":"Importações e sistemas conectados pendentes de prova.",
           "vintageEvidence":"Dados históricos hipotéticos e ano 2026 apenas no fixture.",
           "methodologyNote":"Somente prévia de Case 2; não converte CO2 de OM para CO2e de EFel."}
          """.formatted(efel,om,"b".repeat(64));
    }

    private static String efelFixture(String energy,String hour,String value) {
        return """
          {"energyCandidateId":"%s",
           "sourceTitle":"Série horária sintética de teste, sem fonte real",
           "sourcePublisher":"Laboratório sintético",
           "sourceUri":"https://example.org/test/efel-synthetic-source",
           "sourceEdition":"fixture-v1","claimedProcedure":"Procedimento VT0011 apenas alegado e ainda não verificado",
           "publicationDate":"2026-11-01","sourceVintage":"2027-02 (apenas fixture)",
           "sourceSha256":"%s",
           "sourceRightsReference":"Declaração de uso para testes automatizados",
           "evidenceNote":"Dado exclusivamente sintético, não inserir no E8 real.",
           "unit":"tCO2e/MWh",
           "hours":[{"hour":"%s","value":%s}]}
          """.formatted(energy,"a".repeat(64),hour,value);
    }

    private static String methodAssessment() {
        StringBuilder body=new StringBuilder("{\"dossierVersion\":1,\"outcome\":\"CHECKED_INTERNAL\",")
            .append("\"rationale\":\"Justificativa final da conformidade documental interna.\",")
            .append("\"checks\":[");
        String[] requirements={
            "BOUNDARY_DCFC_SIN","METER_CALIBRATION","SOURCE_RIGHTS",
            "HOURLY_ED_EC","CLOCK_GAPS_MAINTENANCE","DOUBLE_CLAIMING"
        };
        for(int i=0;i<requirements.length;i++) {
            if(i>0)body.append(',');
            body.append("{\"requirement\":\"").append(requirements[i])
                .append("\",\"finding\":\"SUPPORTED_INTERNAL\",")
                .append("\"evidenceReference\":\"DOC-VM0038-2027-")
                .append(i+1).append("\",")
                .append("\"reason\":\"Evidência documental revisada internamente para o critério. \"}");
        }
        return body.append("]}").toString();
    }

    private HttpResponse<String> postMethod(String path,String cookie,String key,
                                             String match,String payload) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/json")
            .header("If-Match",match).header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }

    private void configure(Actor owner,String project) throws Exception {
        String base="/v1/projects/"+project+"/technical/open-monitoring";
        assertEquals(200,call("PUT",base+"/chargers/DCFC-1",owner.cookie(),null,CHARGER).statusCode());
        for(String kind:new String[]{"ED","EC"}) {
            assertEquals(200,call("PUT",base+"/meters/"+kind+"-1",
                owner.cookie(),null,meter(kind)).statusCode());
        }
    }
    private JsonNode uploadCsv(String path,Actor actor,String payload) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",actor.cookie()).header("Content-Type","text/csv")
            .header("X-Source-Id","METER-API-1").header("X-File-Name","meter-qa.csv")
            .header("Idempotency-Key",key())
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var result=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(202,result.statusCode(),result.body());
        return json(result);
    }
    private HttpResponse<String> uploadPdf(String path,String cookie,String kind,
                            String subject,String key,String payload) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/pdf")
            .header("X-Evidence-Kind",kind).header("X-Subject-Code",subject)
            .header("X-Evidence-Reference","Documento de suporte verificado #1")
            .header("X-File-Name","support-evidence.pdf").header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postEvent(String path,String cookie,String idempotency,
                                            String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/json")
            .header("Idempotency-Key",idempotency)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> putWithMatch(String path,String cookie,String match,String body)
        throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/json")
            .header("If-Match",match)
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> call(String method,String path,String cookie,
                    String match,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(body!=null)builder.header("Content-Type","application/json");
        if(match!=null)builder.header("If-Match",match);
        if("PUT".equals(method)) {
            builder.header("If-Match","\"0\"");
            builder.header("Idempotency-Key",key());
        }
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode calcGate(JsonNode readiness,String code) {
        for(JsonNode gate:readiness.path("gates")){
            if(code.equals(gate.path("code").asText()))return gate;
        }
        throw new AssertionError("Gate ausente no diagnóstico: "+code);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
    private Actor actor(){return actorWithId(UUID.randomUUID());}
    private Actor actorWithId(UUID id){
        String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'QA documentary','ACTIVE',statement_timestamp())
                ON CONFLICT (id) DO NOTHING
                """,id,id+"@example.invalid");
            update(c,"""
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return new Actor(id,AccountAccess.COOKIE+"="+token);
    }
    private UUID organization(UUID owner){return organization(owner,UUID.randomUUID());}
    private UUID organization(UUID owner,UUID org){
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_organization(id,environment,display_name)
                VALUES (?,'SANDBOX','Docs carbon')
                ON CONFLICT (id) DO NOTHING
                """,org);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')",org,owner);
            return null;
        });
        return org;
    }
    private String project(Actor owner,UUID org) throws Exception {
        var req=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve("/v1/projects"))
            .header("Cookie",owner.cookie()).header("Content-Type","application/json")
            .header("Idempotency-Key",key()).POST(HttpRequest.BodyPublishers.ofString(
                "{\"organizationId\":\""+org+"\",\"title\":\"Documents QA\"}")).build();
        var result=http.send(req,HttpResponse.BodyHandlers.ofString());
        assertEquals(201,result.statusCode(),result.body());
        return json(result).path("projectId").asText();
    }
    /** Entirely fabricated licensed PBEV/EVR fixture in the project's OWN org.
     * Uses the same canonical V48/V49 tables and rights as the admin workbench.
     * No example number may reach a live E8 run. */
    private UUID[] seedScopedRatingCatalog(UUID org,UUID manager,UUID reviewer) {
        UUID bevA=UUID.randomUUID(),bevB=UUID.randomUUID();
        UUID iceA=UUID.randomUUID(),iceB=UUID.randomUUID();
        UUID fleetSub=UUID.randomUUID(),ratingsSub=UUID.randomUUID();
        UUID fleetLic=UUID.randomUUID(),ratingsLic=UUID.randomUUID();
        UUID fleetObj=UUID.randomUUID(),ratingsObj=UUID.randomUUID();
        UUID fleetImport=UUID.randomUUID(),fleetSnapshot=UUID.randomUUID();
        UUID ratingSnapshot=UUID.randomUUID();
        String edition="E8-2027-"+UUID.randomUUID().toString().substring(0,8);
        database.transaction(c->{
            UUID[] models={bevA,bevB,iceA,iceB};
            String suffix=UUID.randomUUID().toString().substring(0,8);
            for(int i=0;i<models.length;i++)update(c,"""
                INSERT INTO app_fleet_model
                (id,organization_id,manufacturer,model_name,variant,
                 vehicle_class,propulsion,created_by)
                VALUES (?,?,'Ficticia',?,'Base','LDV',?,?)
                """,models[i],org,(i<2?"Synthetic BEV ":"Synthetic ICE ")+suffix+" "+i,
                i<2?"BEV":"OTHER",manager);
            // Project-integration-only identifiers: do not mutate the global
            // official-source catalogue used by PlatformSourceReadiness tests.
            String[] sourceIds={"TEST-E8-EVR-"+edition,"TEST-E8-PBEV-"+edition};
            String[] codes={"OPEN-EVR","OPEN-PBEV"};
            UUID[] subs={fleetSub,ratingsSub};
            UUID[] licenses={fleetLic,ratingsLic};
            UUID[] objects={fleetObj,ratingsObj};
            for(int i=0;i<2;i++){
                update(c,"""
                    INSERT INTO app_source_version
                    (source_id,edition,category,title,rights_holder,official_uri,origin)
                    VALUES (?,?,'EXTERNAL_SOURCE','Synthetic test fixture',
                        'Only internal fictional source','https://example.org/fixture','EXTERNAL')
                    """,sourceIds[i],edition);
                update(c,"""
                    INSERT INTO app_platform_source_submission
                    (id,need_code,source_id,edition,category,title,rights_holder,
                     publisher,official_uri,reference_year,locator,metadata_fingerprint,
                     state,submitted_by,reviewed_by)
                    VALUES (?,?,?,?,'EXTERNAL_SOURCE','Synthetic fixture',
                        'Only internal fictional source','Test editor',
                        'https://example.org/fixture',2027,'TEST:1',?,
                        'METADATA_ACCEPTED',?,?)
                    """,subs[i],codes[i],sourceIds[i],edition,
                    AccessSecrets.digest("metadata "+subs[i]),manager,reviewer);
                update(c,"""
                    INSERT INTO app_content_license
                    (id,source_id,edition,organization_id,submitted_by,environment,
                     purpose,authorization_ref,proof_sha256,starts_at,ends_at)
                    VALUES (?,?,?,?,?,'SANDBOX','AUTHORIZED_PROCESSING',
                        'Fictional fixture approved exclusively in isolated tests',
                        ?,'2020-01-01',statement_timestamp()+interval '1 hour')
                    """,licenses[i],sourceIds[i],edition,org,manager,"a".repeat(64));
                update(c,"""
                    INSERT INTO app_content_license_decision
                    (license_id,version,outcome,decided_by,reason)
                    VALUES (?,1,'APPROVED',?,'Test-only independently checked licence')
                    """,licenses[i],reviewer);
                update(c,"""
                    INSERT INTO app_content_license_capability
                    (license_id,decision_version,operation)
                    VALUES (?,1,'STORE_ORIGINAL'),(?,1,'EXTRACT_TEXT'),
                           (?,1,'SHOW_EXCERPTS'),(?,1,'EXPORT_DERIVATIVES')
                    """,licenses[i],licenses[i],licenses[i],licenses[i]);
                update(c,"""
                    INSERT INTO app_platform_source_object
                    (id,submission_id,organization_id,uploaded_by,license_id,
                     license_version,file_name,file_format,sha256,bytes_count,
                     nonce,encrypted,state,parsed_rows,parser_version)
                    VALUES (?,?,?,?,?,1,'fictional.csv','CSV',?,16,?,?,
                        'APPROVED_FOR_SCOPE',4,'TEST-ONLY')
                    """,objects[i],subs[i],org,manager,licenses[i],
                    AccessSecrets.digest("fixture "+subs[i]),new byte[12],new byte[32]);
            }
            update(c,"""
                INSERT INTO app_fleet_ingestion
                (id,organization_id,source_object_id,source_license_id,source_sha256,
                 data_kind,observation_year,geography,imported_by)
                VALUES (?,?,?,?,?,'STOCK_IN_CIRCULATION',2027,'BR',?)
                """,fleetImport,org,fleetObj,fleetLic,
                AccessSecrets.digest("fleet-2027"),manager);
            update(c,"""
                INSERT INTO app_fleet_composition_snapshot
                (id,ingestion_id,organization_id,observation_year,denominator,
                 number_of_models,source_sha256)
                VALUES (?,?,?,2027,20,2,?)
                """,fleetSnapshot,fleetImport,org,AccessSecrets.digest("fleet-2027"));
            update(c,"""
                INSERT INTO app_fleet_composition_weight
                (snapshot_id,organization_id,model_id,source_row,observed_stock,
                 weight,source_locator)
                VALUES (?,?,?,2,12,0.6,'TEST:2'),(?,?,?,3,8,0.4,'TEST:3')
                """,fleetSnapshot,org,bevA,fleetSnapshot,org,bevB);
            UUID[] ratings=new UUID[4];
            String[] metric={"ev_energy","ev_energy","fuel_economy","fuel_economy"};
            String[] unit={"kWh/100mi","kWh/100mi","mi/USgal","mi/USgal"};
            int[] values={18,23,32,27};
            for(int i=0;i<4;i++){
                ratings[i]=UUID.randomUUID();
                update(c,"""
                    INSERT INTO app_vehicle_rating
                    (id,organization_id,model_id,source_object_id,source_license_id,
                     source_row,source_sha256,year,role,fuel,cycle,seats,cycle_evidence,
                     original_value,original_unit,normalized_value,normalized_unit,
                     metric,locator,created_by)
                    VALUES (?,?,?,?,?,?,?,2027,?,?,'COMBINED',5,
                        'Synthetic combined cycle evidence',?,?,?,?,?,?,?)
                    """,ratings[i],org,models[i],ratingsObj,ratingsLic,i+2,
                    AccessSecrets.digest("fixture "+ratingsSub),
                    i<2?"BEV":"FOSSIL",i<2?"ELECTRICITY":"GASOLINE",
                    values[i],unit[i],values[i],unit[i],metric[i],"TEST:"+(i+2),manager);
            }
            UUID[] pairs=new UUID[2];
            for(int i=0;i<2;i++){
                pairs[i]=UUID.randomUUID();
                update(c,"""
                    INSERT INTO app_vehicle_comparable_pair
                    (id,organization_id,fleet_snapshot_id,bev_model_id,
                     bev_rating_id,fossil_rating_id,reason,service_description,
                     status,version,proposed_by,reviewed_by,review_reason,reviewed_at)
                    VALUES (?,?,?,?,?,?,'Same LDV capacity and service in synthetic test',
                        'Five-seat passenger service','ACCEPTED',2,?,?,
                        'Independent synthetic test review',statement_timestamp())
                    """,pairs[i],org,fleetSnapshot,models[i],ratings[i],
                    ratings[i+2],manager,reviewer);
                update(c,"""
                    INSERT INTO app_vehicle_pair_review_event
                    (pair_id,version,outcome,reviewed_by,reason)
                    VALUES (?,2,'ACCEPTED',?,
                        'Independent synthetic same-cycle and capacity acceptance')
                    """,pairs[i],reviewer);
            }
            update(c,"""
                INSERT INTO app_vehicle_rating_snapshot
                (id,organization_id,fleet_snapshot_id,year,cycle,lineage_digest,
                 afec_kwh_per_100mi,mpg_us,model_count,constructed_by)
                VALUES (?,?,?,2027,'COMBINED',?,20,30,2,?)
                """,ratingSnapshot,org,fleetSnapshot,
                AccessSecrets.digest("rating-2027"),manager);
            update(c,"""
                INSERT INTO app_vehicle_rating_snapshot_line
                (snapshot_id,organization_id,model_id,pair_id,evr,
                 ev_kwh_per_100mi,fossil_mpg_us)
                VALUES (?,?,?,?,12,18,32),(?,?,?,?,8,23,27)
                """,ratingSnapshot,org,bevA,pairs[0],ratingSnapshot,org,bevB,pairs[1]);
            return null;
        });
        return new UUID[]{ratingSnapshot,ratingsLic};
    }

    private void verifyStaff(Actor manager) throws Exception {
        long counter=database.read(c->first(c,
            "SELECT floor(extract(epoch from statement_timestamp())/30)::bigint",
            r->r.getLong(1)));
        String body="{\"code\":\""+ComplianceStaff.codeAt(
            E8_STAFF_SECRET,counter)+"\"}";
        var response=call("POST","/v1/compliance/session",
            manager.cookie(),null,body);
        assertEquals(200,response.statusCode(),response.body());
    }
    public static class Settings extends AccountAccessTest.Inbox {
        @Override public java.util.Map<String,String> start(){
            var properties=new java.util.HashMap<>(super.start());
            String secret=java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(E8_STAFF_SECRET);
            properties.put("carbonflow.compliance.staff",
                E8_STAFF_MANAGER+":"+secret+":"+E8_STAFF_ORG+";"+
                E8_STAFF_REVIEWER+":"+secret+":"+E8_STAFF_ORG);
            properties.put("carbonflow.platform.source-readiness.viewers",
                E8_STAFF_MANAGER+","+E8_STAFF_REVIEWER);
            properties.put("carbonflow.platform.source-work.managers",
                E8_STAFF_MANAGER.toString());
            return properties;
        }
    }

    /** Synthetic fossil reference plus explicit IR and Dy. Not an official factor. */
    private void pinCompleteSet(String project,UUID org,UUID author,UUID reviewer) {
        database.transaction(c->{
            String edition="E8-FUEL-"+UUID.randomUUID().toString().substring(0,8);
            String source=first(c,"""
                SELECT source_id FROM app_methodology_source_need
                WHERE code='OPEN-FOSSIL-EF' AND methodology='VM0038'
                  AND methodology_edition='1.1'
                """,r->r.getString(1));
            UUID submission=UUID.randomUUID(),license=UUID.randomUUID(),object=UUID.randomUUID();
            UUID candidate=UUID.randomUUID(),assessment=UUID.randomUUID();
            byte[] sha=AccessSecrets.digest(edition);
            update(c,"""
                INSERT INTO app_source_version
                 (source_id,edition,category,title,rights_holder,official_uri,origin)
                VALUES (? ,?,'EXTERNAL_SOURCE','Fator fossil ficticio','Autor sintetico',
                        'https://example.org/factor','EXTERNAL')
                """,source,edition);
            update(c,"""
                INSERT INTO app_platform_source_submission
                 (id,need_code,source_id,edition,category,title,rights_holder,publisher,
                  official_uri,reference_year,locator,metadata_fingerprint,state,
                  submitted_by,reviewed_by)
                VALUES (?, 'OPEN-FOSSIL-EF', ?, ?,'EXTERNAL_SOURCE','Fator fossil ficticio',
                        'Autor sintetico','Editor sintetico','https://example.org/factor',
                        2027,'CSV registro 1',?,'METADATA_ACCEPTED',?,?)
                """,submission,source,edition,sha,author,reviewer);
            update(c,"""
                INSERT INTO app_content_license
                 (id,source_id,edition,organization_id,submitted_by,environment,purpose,
                  authorization_ref,proof_sha256,starts_at,ends_at)
                VALUES (?, ?, ?, ? ,?,'SANDBOX','AUTHORIZED_PROCESSING','Somente teste',
                        ?,'2024-01-01',statement_timestamp()+interval '1 hour')
                """,license,source,edition,org,author,"d".repeat(64));
            update(c,"""
                INSERT INTO app_content_license_decision
                 (license_id,version,outcome,decided_by,reason)
                VALUES (?,1,'APPROVED',?,'Autorizacao sintetica independente')
                """,license,reviewer);
            update(c,"""
                INSERT INTO app_content_license_capability
                 (license_id,decision_version,operation)
                VALUES (?,1,'STORE_ORIGINAL'),(?,1,'EXTRACT_TEXT'),
                       (?,1,'SHOW_EXCERPTS'),(?,1,'EXPORT_DERIVATIVES')
                """,license,license,license,license);
            update(c,"""
                INSERT INTO app_platform_source_object
                 (id,submission_id,organization_id,uploaded_by,license_id,license_version,
                  file_name,file_format,sha256,bytes_count,nonce,encrypted,state,
                  parsed_rows,parser_version)
                VALUES (?,?,?,?,?,1,'fator.csv','CSV',?,16,?,?,'APPROVED_FOR_SCOPE',1,'TABULAR-1')
                """,object,submission,org,author,license,sha,new byte[12],new byte[32]);
            update(c,"""
                INSERT INTO app_open_parameter_candidate
                 (id,organization_id,source_object_id,source_license_id,source_row,
                  source_sha256,source_locator,created_by,methodology,methodology_edition,
                  scenario,geography,period_year,parameter_kind,gas,fuel,
                  original_unit,original_value,normalized_unit,normalized_value,
                  conversion_formula,technical_rule_ref,technical_evidence)
                VALUES (?,?,?,?,2,?, 'CSV registro 1',?,'VM0038','1.1',
                        'OPEN_LDV_DCFC_SIN_BR','BR',2027,'EF_FUEL','CO2e','GASOLINE',
                        'tCO2e/USgal',0.009,'tCO2e/USgal',0.009,
                        'identity','VM0038-1.1-EQ1-EF_FUEL','CSV registro 1')
                """,candidate,org,object,license,sha,author);
            update(c,"""
                INSERT INTO app_open_parameter_assessment
                 (id,candidate_id,methodology,methodology_edition,scenario,blocked_capability,
                  outcome,rule_reference,evidence_locator,reason,reviewed_by)
                VALUES (?,?,'VM0038','1.1','OPEN_LDV_DCFC_SIN_BR','BASELINE_BE',
                        'APPROVED_FOR_SCOPE','VM0038-1.1-EQ1','CSV registro 1',
                        'Checagem interna ficticia da fonte',?)
                """,assessment,candidate,reviewer);
            update(c,"""
                INSERT INTO app_open_project_parameter_binding
                 (id,project_id,organization_id,environment,parameter_kind,vintage_year,
                  version,candidate_id,source_sha256,normalized_unit,conversion_formula,
                  value_kind,triage_version,binding_sha256,published_by)
                VALUES (?, ?, ?,'SANDBOX','EF_FUEL',2027,1,?,?, 'tCO2e/USgal','identity',
                        'REFERENCE',1,?,?)
                """,UUID.randomUUID(),project,org,candidate,sha,AccessSecrets.digest("fuel-pin"),author);
            for (String kind : new String[]{"IR","DY"}) {
                update(c,"""
                    INSERT INTO app_open_project_parameter_binding
                     (id,project_id,organization_id,environment,parameter_kind,vintage_year,
                      version,declared_value,rule_reference,evidence_locator,value_kind,
                      triage_version,binding_sha256,published_by)
                    VALUES (?, ?, ?,'SANDBOX',?,2027,1,1,
                            'VM0038 explicit declaration','DOC-1','DECLARED_EXPLICIT',1,?,?)
                    """,UUID.randomUUID(),project,org,kind,AccessSecrets.digest(kind+"-pin"),author);
            }
            return null;
        });
    }

    private void grant(String project,UUID org,UUID owner,UUID actor,String role) {
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_collaborator_grant
                (id,project_id,organization_id,environment,identity_id,granted_by,
                 role,activities,valid_until)
                VALUES (?,?,?,'SANDBOX',?,?,?,ARRAY['MONITORING']::text[],
                        statement_timestamp()+interval '3 days')
                """,UUID.randomUUID(),project,org,actor,owner,role);
            return null;
        });
    }
}
