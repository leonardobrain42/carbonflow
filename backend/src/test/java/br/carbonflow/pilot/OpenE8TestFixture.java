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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared fixture for integration tests that need a real OPEN E8 project: actors, organisation, staff roster,
 * monitoring set-up, a fully prepared period (ED/EC batch, QA snapshot, documents, method review, energy and
 * hourly factor candidates, input binding, staff fleet/rating hand-off) and the project parameter pins.
 * Everything is synthetic and lives only in the test database; no value is an official factor or admission.
 * Extracted from OpenPeriodSupportIntegrationTest without changing what that test checks.
 */
public final class OpenE8TestFixture {
    static final UUID STAFF_MANAGER=UUID.fromString("e8000000-0000-4000-8000-000000000001");
    static final UUID STAFF_REVIEWER=UUID.fromString("e8000000-0000-4000-8000-000000000002");
    static final UUID STAFF_ORG=UUID.fromString("e8000000-0000-4000-8000-000000000003");
    static final byte[] STAFF_SECRET="12345123451234512345".getBytes(StandardCharsets.US_ASCII);

    record Actor(UUID id,String cookie) {}
    record Scenario(Actor owner,Actor op,Actor qaReviewer,Actor docsReviewer,Actor outside,
                    Actor staffManager,Actor staffReviewer,UUID org,String project) {}
    record Prepared(String code,int year,int month,String snapshotId,String energyBindingId,String fleetSelectionId) {}

    final PilotDatabase database;
    final ObjectMapper mapper;
    final URL base;
    final HttpClient http=HttpClient.newHttpClient();
    private final Map<String,UUID> ratingSnapshots=new HashMap<>();

    OpenE8TestFixture(PilotDatabase database,ObjectMapper mapper,URL base) {
        this.database=database; this.mapper=mapper; this.base=base;
    }

    static String key(){return UUID.randomUUID().toString();}

    static final String CHARGER="""
        {"siteCode":"SITE-1","chargerKind":"DCFC","ratedPowerKw":150,
         "grid":"SIN","topology":"GRID_ONLY","timeZone":"America/Sao_Paulo",
         "sourceReference":"COMMISSION-123"}
        """;
    static String meter(String kind,String from,String until) {
        return """
          {"chargerCode":"DCFC-1","energyKind":"%s","meterPosition":"%s",
           "sourceSystem":"METER-API-1","calibrationEvidenceRef":"CAL-2027-01",
           "calibrationValidFrom":"%s",
           "calibrationValidUntil":"%s",
           "configuredFrom":"%s",
           "configuredUntil":"%s","timeZone":"America/Sao_Paulo"}
          """.formatted(kind,"ED".equals(kind)?"DOWNSTREAM":"UPSTREAM",from,until,from,until);
    }

    /** Charger plus ED and EC meters, calibrated and configured for the given window. */
    void configure(Actor owner,String project,String from,String until) throws Exception {
        String root="/v1/projects/"+project+"/technical/open-monitoring";
        assertEquals(200,call("PUT",root+"/chargers/DCFC-1",owner.cookie(),null,CHARGER).statusCode());
        for(String kind:new String[]{"ED","EC"}) {
            assertEquals(200,call("PUT",root+"/meters/"+kind+"-1",
                owner.cookie(),null,meter(kind,from,until)).statusCode());
        }
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String,String> start(){
            var properties=new HashMap<>(super.start());
            String secret=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(STAFF_SECRET);
            properties.put("carbonflow.compliance.staff",
                STAFF_MANAGER+":"+secret+":"+STAFF_ORG+";"+STAFF_REVIEWER+":"+secret+":"+STAFF_ORG);
            properties.put("carbonflow.platform.source-readiness.viewers",STAFF_MANAGER+","+STAFF_REVIEWER);
            properties.put("carbonflow.platform.source-work.managers",STAFF_MANAGER.toString());
            properties.put("carbonflow.platform.source-technical.reviewers",STAFF_REVIEWER.toString());
            return properties;
        }
    }

    static String efelFixture(String energy,String hour,String value) {
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

    static String methodAssessment() {
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

    HttpResponse<String> postMethod(String path,String cookie,String key,
                                             String match,String payload) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/json")
            .header("If-Match",match).header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }

    JsonNode uploadCsv(String path,Actor actor,String payload) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",actor.cookie()).header("Content-Type","text/csv")
            .header("X-Source-Id","METER-API-1").header("X-File-Name","meter-qa.csv")
            .header("Idempotency-Key",key())
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var result=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(202,result.statusCode(),result.body());
        return json(result);
    }
    HttpResponse<String> uploadPdf(String path,String cookie,String kind,
                            String subject,String key,String payload) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/pdf")
            .header("X-Evidence-Kind",kind).header("X-Subject-Code",subject)
            .header("X-Evidence-Reference","Documento de suporte verificado #1")
            .header("X-File-Name","support-evidence.pdf").header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> postEvent(String path,String cookie,String idempotency,
                                            String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/json")
            .header("Idempotency-Key",idempotency)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> putWithMatch(String path,String cookie,String match,String body)
        throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/json")
            .header("If-Match",match)
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> call(String method,String path,String cookie,
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

    static JsonNode calcGate(JsonNode readiness,String code) {
        for(JsonNode gate:readiness.path("gates")){
            if(code.equals(gate.path("code").asText()))return gate;
        }
        throw new AssertionError("Gate ausente no diagnóstico: "+code);
    }

    JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
    Actor actor(){return actorWithId(UUID.randomUUID());}
    Actor actorWithId(UUID id){
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
    UUID organization(UUID owner){return organization(owner,UUID.randomUUID());}
    UUID organization(UUID owner,UUID org){
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

    String project(Actor owner,UUID org) throws Exception {
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
    UUID[] seedScopedRatingCatalog(UUID org,UUID manager,UUID reviewer,int year) {
        UUID bevA=UUID.randomUUID(),bevB=UUID.randomUUID();
        UUID iceA=UUID.randomUUID(),iceB=UUID.randomUUID();
        UUID fleetSub=UUID.randomUUID(),ratingsSub=UUID.randomUUID();
        UUID fleetLic=UUID.randomUUID(),ratingsLic=UUID.randomUUID();
        UUID fleetObj=UUID.randomUUID(),ratingsObj=UUID.randomUUID();
        UUID fleetImport=UUID.randomUUID(),fleetSnapshot=UUID.randomUUID();
        UUID ratingSnapshot=UUID.randomUUID();
        String edition="E8-"+year+"-"+UUID.randomUUID().toString().substring(0,8);
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
                        'https://example.org/fixture',%1$s,'TEST:1',?,
                        'METADATA_ACCEPTED',?,?)
                    """.formatted(year),subs[i],codes[i],sourceIds[i],edition,
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
                VALUES (?,?,?,?,?,'STOCK_IN_CIRCULATION',%1$s,'BR',?)
                """.formatted(year),fleetImport,org,fleetObj,fleetLic,
                AccessSecrets.digest("fleet-"+year),manager);
            update(c,"""
                INSERT INTO app_fleet_composition_snapshot
                (id,ingestion_id,organization_id,observation_year,denominator,
                 number_of_models,source_sha256)
                VALUES (?,?,?,%1$s,20,2,?)
                """.formatted(year),fleetSnapshot,fleetImport,org,AccessSecrets.digest("fleet-"+year));
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
                    VALUES (?,?,?,?,?,?,?,%1$s,?,?,'COMBINED',5,
                        'Synthetic combined cycle evidence',?,?,?,?,?,?,?)
                    """.formatted(year),ratings[i],org,models[i],ratingsObj,ratingsLic,i+2,
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
                VALUES (?,?,?,%1$s,'COMBINED',?,20,30,2,?)
                """.formatted(year),ratingSnapshot,org,fleetSnapshot,
                AccessSecrets.digest("rating-"+year),manager);
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

    /**
     * Opens the staff compliance session with the current TOTP step. The server consumes each step once per
     * identity, and the roster is fixed, so another test class may already have used this step: in that case
     * wait for the next 30-second step and try once more.
     */
    void verifyStaff(Actor manager) throws Exception {
        for(int attempt=0;;attempt++) {
            long counter;
            while(true) {
                counter=database.read(c->first(c,
                    "SELECT floor(extract(epoch from statement_timestamp())/30)::bigint",
                    r->r.getLong(1)));
                // The step is consumed once per identity. A replay is recorded as an MFA failure, and failures are
                // limited per identity across every test class, so wait for the next step instead of provoking one.
                Long used=database.read(c->first(c,"SELECT last_counter FROM app_compliance_mfa WHERE identity_id=?",
                    r->r.getLong(1),manager.id()));
                if(used==null || used<counter) break;
                long wait=database.read(c->first(c,
                    "SELECT 30-(floor(extract(epoch from statement_timestamp()))::bigint % 30)",r->r.getLong(1)));
                Thread.sleep((wait+1)*1000L);
            }
            var response=call("POST","/v1/compliance/session",manager.cookie(),null,
                "{\"code\":\""+ComplianceStaff.codeAt(STAFF_SECRET,counter)+"\"}");
            if(response.statusCode()==200)return;
            boolean replayed=response.statusCode()==403 && response.body().contains("MFA_CODIGO_INVALIDO");
            if(!replayed || attempt>=1)fail("Staff session was not opened: "+response.body());
            Thread.sleep(31_000);
        }
    }

    void pinCompleteSet(String project,UUID org,UUID author,UUID reviewer,int year) {
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
                        %1$s,'CSV registro 1',?,'METADATA_ACCEPTED',?,?)
                """.formatted(year),submission,source,edition,sha,author,reviewer);
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
                        'OPEN_LDV_DCFC_SIN_BR','BR',%1$s,'EF_FUEL','CO2e','GASOLINE',
                        'tCO2e/USgal',0.009,'tCO2e/USgal',0.009,
                        'identity','VM0038-1.1-EQ1-EF_FUEL','CSV registro 1')
                """.formatted(year),candidate,org,object,license,sha,author);
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
                VALUES (?, ?, ?,'SANDBOX','EF_FUEL',%1$s,1,?,?, 'tCO2e/USgal','identity',
                        'REFERENCE',1,?,?)
                """.formatted(year),UUID.randomUUID(),project,org,candidate,sha,AccessSecrets.digest("fuel-pin"),author);
            for (String kind : new String[]{"IR","DY"}) {
                update(c,"""
                    INSERT INTO app_open_project_parameter_binding
                     (id,project_id,organization_id,environment,parameter_kind,vintage_year,
                      version,declared_value,rule_reference,evidence_locator,value_kind,
                      triage_version,binding_sha256,published_by)
                    VALUES (?, ?, ?,'SANDBOX',?,%1$s,1,1,
                            'VM0038 explicit declaration','DOC-1','DECLARED_EXPLICIT',1,?,?)
                    """.formatted(year),UUID.randomUUID(),project,org,kind,AccessSecrets.digest(kind+"-pin"),author);
            }
            return null;
        });
    }

    void grant(String project,UUID org,UUID owner,UUID actor,String role) {
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
    /** Synthetic fossil reference plus explicit IR and Dy. Not an official factor. */
    void addIrPin(String project,UUID org,UUID author,int year,int version) {
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_open_project_parameter_binding
                 (id,project_id,organization_id,environment,parameter_kind,vintage_year,
                  version,declared_value,rule_reference,evidence_locator,value_kind,
                  triage_version,binding_sha256,published_by)
                VALUES (?, ?, ?,'SANDBOX','IR',?,?,0,'VM0038 explicit declaration',
                        'DOC-IR','DECLARED_ZERO',1,?,?)
                """,UUID.randomUUID(),project,org,year,version,AccessSecrets.digest("ir-"+year+"-"+version),author);
            return null;
        });
    }

    // ---- scenario builders -------------------------------------------------------------------------------

    /**
     * A SANDBOX VM0038 project with its actors, staff, grants, monitoring set-up, triage, rights events and an
     * OPEN applicability declaration whose operational and VCS start is {@code creditStart} (ISO date).
     */
    Scenario openProject(String creditStart) throws Exception {
        Actor owner=actor(),op=actor(),qaReviewer=actor(),docsReviewer=actor(),outside=actor();
        Actor staffManager=actorWithId(STAFF_MANAGER);
        Actor staffReviewer=actorWithId(STAFF_REVIEWER);
        verifyStaff(staffManager);
        verifyStaff(staffReviewer);
        UUID org=organization(owner.id(),STAFF_ORG);
        String project=project(owner,org);
        configure(owner,project,creditStart+"T00:00:00Z","2031-01-01T00:00:00Z");
        grant(project,org,owner.id(),op.id(),"OP");
        grant(project,org,owner.id(),qaReviewer.id(),"REV");
        grant(project,org,owner.id(),docsReviewer.id(),"REV");
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_triage_revision
                  (project_id,organization_id,environment,version,actor_id,current_step,answers)
                VALUES (?,?,'SANDBOX',1,?,1,?::jsonb)
                """,project,org,owner.id(),"{\"1\":{\"methodologyCandidate\":\"VM0038\"},\"3\":{\"vehicleClass\":\"LDV\"}}");
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
        String technical="/v1/projects/"+project+"/technical";
        var intake=call("PUT",technical+"/intake",owner.cookie(),null,"""
            {"activity":"Operação de recarga de veículos elétricos","country":"BR","state":"DF",
             "locality":"Brasília","candidateMethodology":"VM0038"}
            """);
        assertEquals(200,intake.statusCode(),intake.body());
        assertEquals(200,call("PUT",technical+"/open-rights/events/OP-START",owner.cookie(),null,
            "{\"kind\":\"OPERATIONAL_START\",\"eventOn\":\""+creditStart+"\",\"documentRef\":\"DOC-OP-1\"}").statusCode());
        assertEquals(200,call("PUT",technical+"/open-rights/events/VCS-START",owner.cookie(),null,
            "{\"kind\":\"VCS_START\",\"eventOn\":\""+creditStart+"\",\"documentRef\":\"DOC-VCS-1\"}").statusCode());
        assertEquals(200,call("PUT",technical+"/open-applicability",owner.cookie(),null,"""
            {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
             "chargerType":"DCFC","propulsion":"BEV","electricitySource":"SIN",
             "associatedInfrastructure":false,"v2g":false,
             "operationalEventCode":"OP-START","vcsEventCode":"VCS-START"}
            """).statusCode());
        return new Scenario(owner,op,qaReviewer,docsReviewer,outside,staffManager,staffReviewer,org,project);
    }

    /**
     * Takes one two-hour period (first day of {@code year}-{@code month}, UTC) from an ED/EC batch to a staff
     * fleet/rating hand-off in state PREPARATORY_NOT_ADMITTED. The rating catalogue is seeded once per source year.
     */
    Prepared preparePeriod(Scenario s,String code,int year,int month) throws Exception {
        String day=String.format("%04d-%02d-01T",year,month);
        String from=day+"00:00:00Z",middle=day+"01:00:00Z",until=day+"02:00:00Z";
        String data="meterCode,sequence,kind,start,endExclusive,zone,quantity,unit\n"
            +"ED-1,SEQ-"+code+"-1,ED,"+from+","+middle+",UTC,900,kWh\n"
            +"EC-1,SEQ-"+code+"-1,EC,"+from+","+middle+",UTC,1000,kWh\n"
            +"ED-1,SEQ-"+code+"-2,ED,"+middle+","+until+",UTC,1800,kWh\n"
            +"EC-1,SEQ-"+code+"-2,EC,"+middle+","+until+",UTC,2000,kWh\n";
        String root="/v1/projects/"+s.project()+"/technical/open-monitoring";
        String id=uploadCsv(root+"/batches",s.op(),data).path("id").asText();
        for(int line:new int[]{2,3,4,5}) {
            var reviewed=call("POST",root+"/batches/"+id+"/rows/"+line+"/reviews",s.qaReviewer().cookie(),"\"0\"",
                "{\"outcome\":\"ACCEPTED_FOR_QA\",\"reason\":\"Leitura tecnicamente consistente para conciliação horária.\"}");
            assertEquals(200,reviewed.statusCode(),reviewed.body());
        }
        String snapshots=root+"/period-qa/snapshots";
        var created=call("POST",snapshots,s.qaReviewer().cookie(),null,"{\"from\":\""+from+"\",\"until\":\""+until+"\"}");
        assertEquals(201,created.statusCode(),created.body());
        String snapshot=json(created).path("id").asText();
        String dossierUrl=root+"/dossiers";
        var bound=call("PUT",dossierUrl+"/"+code,s.owner().cookie(),null,
            "{\"snapshotId\":\""+snapshot+"\",\"note\":\"Vínculo de QA do período "+code+", apenas para preparação MR.\"}");
        assertEquals(200,bound.statusCode(),bound.body());
        String pdf="%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n";
        String[][] subjects={{"METER_CALIBRATION","ED-1"},{"METER_CALIBRATION","EC-1"},{"SOURCE_RIGHTS","METER-API-1"}};
        for(int i=0;i<subjects.length;i++) {
            var uploaded=uploadPdf(snapshots+"/"+snapshot+"/support",s.op().cookie(),subjects[i][0],subjects[i][1],
                key(),pdf+"%"+code+"-"+i+"\n");
            assertEquals(201,uploaded.statusCode(),uploaded.body());
            var accepted=call("POST",root+"/period-qa/support/"+json(uploaded).path("id").asText()+"/reviews",
                s.docsReviewer().cookie(),"\"0\"","{\"outcome\":\"DOCUMENT_CHECKED_INTERNAL\","
                +"\"reason\":\"Conferência técnica interna do documento e da identidade do medidor.\"}");
            assertEquals(200,accepted.statusCode(),accepted.body());
        }
        var method=postMethod(dossierUrl+"/"+code+"/method-review",s.docsReviewer().cookie(),key(),"\"0\"",methodAssessment());
        assertEquals(200,method.statusCode(),method.body());
        var energyCreated=postMethod(dossierUrl+"/"+code+"/e8-energy-candidates",s.owner().cookie(),key(),"\"1\"",
            "{\"dossierVersion\":1,\"methodReviewVersion\":1}");
        assertEquals(201,energyCreated.statusCode(),energyCreated.body());
        String energy=json(energyCreated).path("id").asText();
        var efelPayload=mapper.readTree(efelFixture(energy,from,"0.5"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)efelPayload.path("hours"))
            .add(mapper.createObjectNode().put("hour",middle).put("value",0.4));
        String efelUrl=dossierUrl+"/"+code+"/efel-candidates";
        var efelCreated=postMethod(efelUrl,s.owner().cookie(),key(),"\"0\"",mapper.writeValueAsString(efelPayload));
        assertEquals(201,efelCreated.statusCode(),efelCreated.body());
        String efel=json(efelCreated).path("id").asText();
        var efelReviewed=postMethod(efelUrl+"/"+efel+"/reviews",s.docsReviewer().cookie(),key(),"\"0\"","""
            {"outcome":"CHECKED_INTERNAL",
             "rationale":"Origem e coerência conferidas apenas internamente, sem aprovação normativa.",
             "evidenceReference":"Dossiê documental interno, versão de fonte declarada."}
            """);
        assertEquals(200,efelReviewed.statusCode(),efelReviewed.body());
        var binding=postMethod(dossierUrl+"/"+code+"/e8-input-bindings",s.owner().cookie(),key(),"\"0\"",
            "{\"energyCandidateId\":\""+energy+"\",\"efelCandidateId\":\""+efel+"\"}");
        assertEquals(201,binding.statusCode(),binding.body());
        String bindingId=json(binding).path("id").asText();
        UUID rating=ratingSnapshots.computeIfAbsent(s.project()+":"+year,
            k->seedScopedRatingCatalog(s.org(),s.staffManager().id(),s.staffReviewer().id(),year)[0]);
        var handoff=postMethod("/v1/platform/e8-fleet-selections",s.staffManager().cookie(),key(),"\"0\"",
            "{\"projectId\":\""+s.project()+"\",\"period\":\""+code+"\",\"energyBindingId\":\""+bindingId
            +"\",\"ratingSnapshotId\":\""+rating+"\"}");
        assertEquals(201,handoff.statusCode(),handoff.body());
        assertEquals("PREPARATORY_NOT_ADMITTED",json(handoff).path("state").asText());
        return new Prepared(code,year,month,snapshot,bindingId,json(handoff).path("id").asText());
    }
}
