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
@QuarkusTestResource(value=AccountAccessTest.Inbox.class,restrictToAnnotatedClass=true)
class OpenPeriodSupportIntegrationTest {
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
        UUID org=organization(owner.id());
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
        JsonNode staleWorkbook=json(call("GET",vtUrl,owner.cookie(),null,null)).get(0);
        assertEquals("STALE_NOT_ADMITTED",staleWorkbook.path("state").asText());
        JsonNode staleOm=json(call("GET",simpleOmUrl,owner.cookie(),null,null)).get(0);
        assertEquals("STALE_NOT_ADMITTED",staleOm.path("state").asText());
        assertFalse(staleOm.path("usableForE8").asBoolean());
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
    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
    private Actor actor() {
        UUID id=UUID.randomUUID();
        String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'QA documentary','ACTIVE',statement_timestamp())
                """,id,id+"@example.invalid");
            update(c,"""
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return new Actor(id,AccountAccess.COOKIE+"="+token);
    }
    private UUID organization(UUID owner) {
        UUID org=UUID.randomUUID();
        database.transaction(c->{
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Docs carbon')",org);
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
