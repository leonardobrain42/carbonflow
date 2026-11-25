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
import br.carbonflow.pilot.OpenE8TestFixture.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class OpenPeriodSupportIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    // Shared fixture: see OpenE8TestFixture (helpers were extracted verbatim, behaviour unchanged).
    static final UUID E8_STAFF_MANAGER=OpenE8TestFixture.STAFF_MANAGER;
    static final UUID E8_STAFF_REVIEWER=OpenE8TestFixture.STAFF_REVIEWER;
    static final UUID E8_STAFF_ORG=OpenE8TestFixture.STAFF_ORG;
    private OpenE8TestFixture fixture;
    private OpenE8TestFixture fx(){
        if(fixture==null)fixture=new OpenE8TestFixture(database,mapper,base);
        return fixture;
    }
    private static String key(){return OpenE8TestFixture.key();}
    private static String efelFixture(String energy,String hour,String value){return OpenE8TestFixture.efelFixture(energy,hour,value);}
    private static String methodAssessment(){return OpenE8TestFixture.methodAssessment();}
    private static JsonNode calcGate(JsonNode readiness,String code){return OpenE8TestFixture.calcGate(readiness,code);}
    private HttpResponse<String> postMethod(String path,String cookie,String key,String match,String payload) throws Exception {return fx().postMethod(path,cookie,key,match,payload);}
    private void configure(Actor owner,String project) throws Exception {fx().configure(owner,project,"2027-01-01T00:00:00Z","2028-01-01T00:00:00Z");}
    private JsonNode uploadCsv(String path,Actor actor,String payload) throws Exception {return fx().uploadCsv(path,actor,payload);}
    private HttpResponse<String> uploadPdf(String path,String cookie,String kind,String subject,String key,String payload) throws Exception {return fx().uploadPdf(path,cookie,kind,subject,key,payload);}
    private HttpResponse<String> postEvent(String path,String cookie,String idempotency,String body) throws Exception {return fx().postEvent(path,cookie,idempotency,body);}
    private HttpResponse<String> putWithMatch(String path,String cookie,String match,String body) throws Exception {return fx().putWithMatch(path,cookie,match,body);}
    private HttpResponse<String> call(String method,String path,String cookie,String match,String body) throws Exception {return fx().call(method,path,cookie,match,body);}
    private JsonNode json(HttpResponse<String> response) throws Exception {return fx().json(response);}
    private Actor actor(){return fx().actor();}
    private Actor actorWithId(UUID id){return fx().actorWithId(id);}
    private UUID organization(UUID owner){return fx().organization(owner);}
    private UUID organization(UUID owner,UUID org){return fx().organization(owner,org);}
    private String project(Actor owner,UUID org) throws Exception {return fx().project(owner,org);}
    private UUID[] seedScopedRatingCatalog(UUID org,UUID manager,UUID reviewer){return fx().seedScopedRatingCatalog(org,manager,reviewer,2027);}
    private void verifyStaff(Actor manager) throws Exception {fx().verifyStaff(manager);}
    private void addIrPin(String project,UUID org,UUID author,int year,int version){fx().addIrPin(project,org,author,year,version);}
    private void pinCompleteSet(String project,UUID org,UUID author,UUID reviewer){fx().pinCompleteSet(project,org,author,reviewer,2027);}
    private void grant(String project,UUID org,UUID owner,UUID actor,String role){fx().grant(project,org,owner,actor,role);}
    @TestHTTPResource URL base;
    private final HttpClient http=HttpClient.newHttpClient();
    private static final String DATA=
        "meterCode,sequence,kind,start,endExclusive,zone,quantity,unit\n"+
        "ED-1,SEQ-1,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,2700,kWh\n"+
        "EC-1,SEQ-1,EC,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,3000,kWh\n";
    private static final String FROM="2027-02-01T00:00:00Z",TO="2027-02-01T01:00:00Z";
    private static final String ACCEPTED="{\"outcome\":\"DOCUMENT_CHECKED_INTERNAL\","+
        "\"reason\":\"Conferência técnica interna do documento e da identidade do medidor.\"}";

    @Test
    void proofFilesAreEncryptedReviewedIndependentlyAndSnapshotTurnsStale() throws Exception {
        String data="meterCode,sequence,kind,start,endExclusive,zone,quantity,unit\n"
            +"ED-1,SEQ-1,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,900,kWh\n"
            +"EC-1,SEQ-1,EC,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,1000,kWh\n"
            +"ED-1,SEQ-2,ED,2027-02-01T01:00:00Z,2027-02-01T02:00:00Z,UTC,1800,kWh\n"
            +"EC-1,SEQ-2,EC,2027-02-01T01:00:00Z,2027-02-01T02:00:00Z,UTC,2000,kWh\n";
        String until="2027-02-01T02:00:00Z";
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
        String projectTechnical="/v1/projects/"+project+"/technical";
        assertEquals(200,call("PUT",projectTechnical+"/open-rights/events/OP-START",owner.cookie(),null,"""
            {"kind":"OPERATIONAL_START","eventOn":"2027-01-15","documentRef":"DOC-OP-1"}
            """).statusCode());
        assertEquals(200,call("PUT",projectTechnical+"/open-rights/events/VCS-START",owner.cookie(),null,"""
            {"kind":"VCS_START","eventOn":"2027-01-15","documentRef":"DOC-VCS-1"}
            """).statusCode());
        assertEquals(200,call("PUT",projectTechnical+"/open-applicability",owner.cookie(),null,"""
            {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
             "chargerType":"DCFC","propulsion":"BEV","electricitySource":"SIN",
             "associatedInfrastructure":false,"v2g":false,
             "operationalEventCode":"OP-START","vcsEventCode":"VCS-START"}
            """).statusCode());

        String root="/v1/projects/"+project+"/technical/open-monitoring";
        JsonNode batch=uploadCsv(root+"/batches",op,data);
        String id=batch.path("id").asText();
        for(int line: new int[]{2,3,4,5}) {
            var response=call("POST",root+"/batches/"+id+"/rows/"+line+"/reviews",
                qaReviewer.cookie(),"\"0\"", """
                {"outcome":"ACCEPTED_FOR_QA",
                 "reason":"Leitura tecnicamente consistente para conciliação horária."}
                """);
            assertEquals(200,response.statusCode(),response.body());
        }
        String snapshots=root+"/period-qa/snapshots";
        String period="{\"from\":\""+FROM+"\",\"until\":\""+until+"\"}";
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
        assertEquals(2,energy.path("hourCount").asInt());
        assertEquals(2,energy.path("slotCount").asInt());
        assertEquals(2,energy.path("hours").size());
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
        var efelPayload=mapper.readTree(efelFixture(energy.path("id").asText(),FROM,"0.5"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)efelPayload.path("hours"))
            .add(mapper.createObjectNode().put("hour","2027-02-01T01:00:00Z").put("value",0.4));
        String efelBody=mapper.writeValueAsString(efelPayload);
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
        assertEquals(2,efel.path("hourCount").asInt());
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
        assertEquals(2,boundE8.path("hourCount").asInt());
        assertEquals(2,boundE8.path("slotCount").asInt());
        assertEquals(64,boundE8.path("inputSha256").asText().length());
        assertEquals(energy.path("sourceSha256").asText(),
            boundE8.path("energySha256").asText());
        assertEquals(efel.path("contentSha256").asText(),
            boundE8.path("efelSha256").asText());
        assertEquals("0.0005",boundE8.at("/hours/0/gridFactorTco2ePerKwh").asText());
        assertEquals("900",boundE8.at("/hours/0/edKwh").asText());
        assertEquals("1000",boundE8.at("/hours/0/ecKwh").asText());
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
        // V92: even a complete legacy scalar set does not authorize IR in year one.
        var authorRun=postMethod(runs,owner.cookie(),key(),"\"0\"","{}");
        assertEquals(201,authorRun.statusCode(),authorRun.body());
        JsonNode authorAttempt=json(authorRun);
        assertEquals("BLOCKED",authorAttempt.path("state").asText(),authorRun.body());
        assertTrue(authorAttempt.path("memory").isNull());
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",
            calcGate(authorAttempt,"IR_STRUCTURED").path("state").asText());
        assertFalse(authorAttempt.path("canIssueVcu").asBoolean());

        // Neither a different vintage nor a different actor bypasses first-year IR.
        addIrPin(project,org,staffManager.id(),2028,1);
        addIrPin(project,org,staffManager.id(),2028,2);
        String attemptKey=key();
        var recorded=postMethod(runs,preparer.cookie(),attemptKey,"\"1\"","{}");
        assertEquals(201,recorded.statusCode(),recorded.body());
        JsonNode attempt=json(recorded);
        assertEquals("BLOCKED",attempt.path("state").asText());
        assertEquals(2,attempt.path("version").asInt());
        assertEquals(1,attempt.at("/timing/projectYear").asInt());
        assertEquals(2027,attempt.at("/timing/sourceYear").asInt());
        assertEquals(java.time.Instant.parse(FROM),
            java.time.OffsetDateTime.parse(attempt.at("/timing/windowStart").asText()).toInstant());
        assertEquals(java.time.Instant.parse(until),
            java.time.OffsetDateTime.parse(attempt.at("/timing/windowEndExclusive").asText()).toInstant());
        assertFalse(attempt.path("canIssueVcu").asBoolean());
        assertTrue(attempt.path("memory").isNull());
        assertTrue(attempt.path("engine").isNull());
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",attempt.at("/irContext/state").asText());
        assertTrue(attempt.at("/irContext/assessmentId").isNull());
        assertEquals(64,attempt.at("/irContext/contextSha256").asText().length());
        assertEquals(authorAttempt.at("/irContext"),attempt.at("/irContext"));
        assertNull(database.read(c->first(c,
            "SELECT ir_pin_id FROM app_open_e8_run WHERE project_id=? AND id=?",
            r->r.getObject(1,UUID.class),project,UUID.fromString(attempt.path("id").asText()))));
        String runDetail=runs+"/"+attempt.path("id").asText();
        assertEquals(attempt,json(call("GET",runDetail,preparer.cookie(),null,null)));
        var initialReplay=postMethod(runs,preparer.cookie(),attemptKey,"\"1\"","{}");
        assertEquals(200,initialReplay.statusCode(),initialReplay.body());
        assertEquals(attempt,json(initialReplay));
        assertFalse(attempt.toString().contains("EF-SYN"));
        assertFalse(attempt.toString().contains("SIM-DY"));

        String annualUrl=technical+"/open-calculation-years/1";
        var annualResponse=call("GET",annualUrl,preparer.cookie(),null,null);
        assertEquals(200,annualResponse.statusCode(),annualResponse.body());
        assertTrue(annualResponse.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        JsonNode annual=json(annualResponse);
        assertEquals("BLOCKED",annual.path("state").asText());
        assertTrue(annual.path("totals").isNull());
        assertEquals(1,annual.path("members").size(),"Only the latest version of M1 is selected");
        assertEquals(attempt.path("id"),annual.at("/members/0/runId"));
        assertTrue(annual.at("/members/0/totals").isNull());
        assertTrue(annual.path("issues").toString().contains("RUN_NOT_CALCULATED"));
        assertTrue(annual.path("issues").toString().contains("COVERAGE_GAP"));
        assertFalse(annual.path("usableForE8").asBoolean());
        assertFalse(annual.path("canIssueVcu").asBoolean());
        assertEquals(annual,json(call("GET",annualUrl,preparer.cookie(),null,null)));
        assertEquals(404,call("GET",annualUrl,op.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",annualUrl,outside.cookie(),null,null).statusCode());
        JsonNode stillBlocked=json(call("GET",technical+"/open-calculation-readiness",
            owner.cookie(),null,null));
        assertFalse(stillBlocked.path("canRunE8").asBoolean());
        assertTrue(stillBlocked.at("/emissions/erTco2e").isNull());

        JsonNode sections=json(call("GET",technical+"/pd-sections",owner.cookie(),null,null));
        var quantification=calculationNote(sections);
        assertEquals("BLOCKED",quantification.path("situation").asText());
        assertTrue(quantification.path("beTco2e").isNull());
        assertTrue(quantification.path("peTco2e").isNull());
        assertTrue(quantification.path("erDisplay").isNull());
        assertTrue(quantification.path("provenance").isNull());
        assertFalse(quantification.path("usableForE8").asBoolean());
        assertFalse(quantification.path("marksDocumentReady").asBoolean());
        assertTrue(quantification.path("text").asText().contains("O PD não fica pronto"));
        String selectedPath=technical+"/pd-sections?period=M1&run="+attempt.path("id").asText();
        var selected=calculationNote(json(call("GET",selectedPath,owner.cookie(),null,null)));
        assertEquals(quantification,selected);
        var preceding=calculationNote(json(call("GET",technical+"/pd-sections?period=M1&run="
            +authorAttempt.path("id").asText(),owner.cookie(),null,null)));
        assertEquals("BLOCKED",preceding.path("situation").asText());
        assertTrue(preceding.path("beTco2e").isNull());
        assertEquals(404,call("GET",technical+"/pd-sections?period=M2&run="+attempt.path("id").asText(),
            owner.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",technical+"/pd-sections?period=M1&run="+UUID.randomUUID(),
            owner.cookie(),null,null).statusCode());
        assertEquals(400,call("GET",technical+"/pd-sections?period=M1",owner.cookie(),null,null).statusCode());
        assertEquals(400,call("GET",technical+"/pd-sections?run="+attempt.path("id").asText(),
            owner.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",selectedPath,outside.cookie(),null,null).statusCode());
        // Documentation alone must not bypass the canonical memory grant.
        Actor documentationOnly=actor();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?,?,?,'SANDBOX',?,?,'REV',ARRAY['DOCUMENTATION','CALCULATION']::text[],
                    statement_timestamp()+interval '3 days')
                """,UUID.randomUUID(),project,org,documentationOnly.id(),owner.id());
            return null;
        });
        var limited=calculationNote(json(call("GET",technical+"/pd-sections",
            documentationOnly.cookie(),null,null)));
        assertEquals("BLOCKED",limited.path("situation").asText());
        assertTrue(limited.path("provenance").isNull());
        database.transaction(c->{
            update(c,"""
                UPDATE app_project_collaborator_grant SET activities=ARRAY['DOCUMENTATION']::text[]
                WHERE project_id=? AND identity_id=?
                """,project,documentationOnly.id());
            return null;
        });
        var hiddenResponse=call("GET",technical+"/pd-sections",documentationOnly.cookie(),null,null);
        assertTrue(hiddenResponse.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(404,call("GET",selectedPath,documentationOnly.cookie(),null,null).statusCode());
        var hidden=json(hiddenResponse);
        assertFalse(hidden.toString().contains("\"situation\":\"MEMORY\""));
        assertFalse(hidden.toString().contains(attempt.path("id").asText()));
        assertFalse(hidden.toString().contains(attempt.path("inputSha256").asText()));
        assertEquals(404,call("GET",runDetail,documentationOnly.cookie(),null,null).statusCode());
        assertEquals(404,call("GET",technical+"/pd-sections",outside.cookie(),null,null).statusCode());
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

        // A legacy IR revision in either year is irrelevant to the structured run.
        addIrPin(project,org,staffManager.id(),2028,3);
        addIrPin(project,org,staffManager.id(),2027,2);
        assertEquals(annual,json(call("GET",annualUrl,owner.cookie(),null,null)));
        assertEquals(attempt,json(call("GET",runDetail,owner.cookie(),null,null)));
        // Revoking the actual fleet license does stale the run without rewriting its context.
        database.transaction(c->{
            update(c,"UPDATE app_content_license SET ends_at=statement_timestamp()-interval '1 second' WHERE id=?",
                preparedCatalog[1]);
            return null;
        });
        JsonNode old=json(call("GET",runDetail,owner.cookie(),null,null));
        assertEquals("STALE",old.path("state").asText());
        assertTrue(old.path("memory").isNull());
        assertEquals(attempt.path("timing"),old.path("timing"));
        assertEquals(attempt.path("irContext"),old.path("irContext"));
        assertEquals(attempt.path("inputSha256"),old.path("inputSha256"));
        var staleSelected=calculationNote(json(call("GET",selectedPath,owner.cookie(),null,null)));
        assertEquals("BLOCKED",staleSelected.path("situation").asText());
        assertTrue(staleSelected.path("beTco2e").isNull());
        assertTrue(staleSelected.path("provenance").isNull());
        JsonNode annualStale=json(call("GET",annualUrl,owner.cookie(),null,null));
        assertTrue(annualStale.path("totals").isNull());
        assertTrue(annualStale.path("issues").toString().contains("RUN_STALE"));
        assertNotEquals(annual.path("manifestSha256"),annualStale.path("manifestSha256"));
        var replay=postMethod(runs,preparer.cookie(),attemptKey,"\"1\"","{}");
        assertEquals(200,replay.statusCode(),replay.body());
        assertEquals(old,json(replay));
        database.transaction(c->{
            update(c,"UPDATE app_content_license SET ends_at=statement_timestamp()+interval '3 days' WHERE id=?",
                preparedCatalog[1]);
            return null;
        });
        assertEquals(attempt,json(call("GET",runDetail,owner.cookie(),null,null)));
        assertEquals(annual,json(call("GET",annualUrl,owner.cookie(),null,null)));

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




    private static JsonNode calculationNote(JsonNode sections) {
        for (JsonNode section : sections)
            if ("quantification".equals(section.at("/guide/id").asText())) return section.path("calculation");
        fail("Missing quantification section");
        return null;
    }

}
