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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value=VehicleRatingIntegrationTest.Settings.class,restrictToAnnotatedClass=true)
class VehicleRatingIntegrationTest {
    static final UUID MANAGER=UUID.fromString("fc950000-0000-4000-8000-000000000001");
    static final UUID REVIEWER=UUID.fromString("fc950000-0000-4000-8000-000000000002");
    static final UUID OUTSIDER=UUID.fromString("fc950000-0000-4000-8000-000000000003");
    static final UUID ORG=UUID.fromString("fc950000-0000-4000-8000-000000000004");
    static final UUID OTHER=UUID.fromString("fc950000-0000-4000-8000-000000000005");
    static final UUID BEV_A=UUID.fromString("fc950000-0000-4000-8000-000000000006");
    static final UUID BEV_B=UUID.fromString("fc950000-0000-4000-8000-000000000007");
    static final UUID ICE_A=UUID.fromString("fc950000-0000-4000-8000-000000000008");
    static final UUID ICE_B=UUID.fromString("fc950000-0000-4000-8000-000000000009");
    static final UUID FLEET_SUB=UUID.fromString("fc950000-0000-4000-8000-000000000010");
    static final UUID PBEV_SUB=UUID.fromString("fc950000-0000-4000-8000-000000000011");
    static final UUID FLEET_LIC=UUID.fromString("fc950000-0000-4000-8000-000000000012");
    static final UUID PBEV_LIC=UUID.fromString("fc950000-0000-4000-8000-000000000013");
    static final UUID FLEET_OBJECT=UUID.fromString("fc950000-0000-4000-8000-000000000014");
    static final UUID PBEV_OBJECT=UUID.fromString("fc950000-0000-4000-8000-000000000015");
    static final UUID FLEET_IMPORT=UUID.fromString("fc950000-0000-4000-8000-000000000016");
    static final UUID FLEET_SNAPSHOT=UUID.fromString("fc950000-0000-4000-8000-000000000017");
    static final byte[] SECRET="12345123451234512345".getBytes(StandardCharsets.US_ASCII);
    static final int YEAR=2076;
    static final String ROOT="/v1/platform/vehicle-ratings";
    @Inject PilotDatabase db;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    final HttpClient client=HttpClient.newHttpClient();

    @Test
    void acceptsOnlyLicensedAssessedRatingsAndIndependentPairsAndPurgesDerivatives()throws Exception {
        String manager=actor(MANAGER),reviewer=actor(REVIEWER),outsider=actor(OUTSIDER);
        seed();
        assertEquals(401,send("GET",ROOT+"/board",null,null,null,null).statusCode());
        assertEquals(403,send("GET",ROOT+"/board",manager,null,null,null).statusCode());
        verify(manager);verify(reviewer);verify(outsider);
        JsonNode board=ok(send("GET",ROOT+"/board",manager,null,null,null),200);
        assertEquals(1,board.path("sources").size());
        assertEquals(1,board.path("fleets").size());
        assertEquals(0,ok(send("GET",ROOT+"/board",outsider,null,null,null),200).path("sources").size());
        assertEquals(404,send("GET",ROOT+"/sources/"+PBEV_OBJECT+"/candidates",
            outsider,null,null,null).statusCode());

        var candidates=ok(send("GET",ROOT+"/sources/"+PBEV_OBJECT+"/candidates",
            manager,null,null,null),200);
        assertEquals(4,candidates.size());
        assertEquals(BEV_A.toString(),candidates.get(0).path("fleetModelId").asText());
        assertTrue(candidates.get(0).path("fleetModelTitle").asText().contains("EV-A"));

        String wrongModel="{\"organizationId\":\""+ORG+"\",\"modelId\":\""+BEV_B+
            "\",\"sourceObjectId\":\""+PBEV_OBJECT+"\",\"sourceRow\":2"+
            ",\"role\":\"BEV\",\"fuel\":\"ELECTRICITY\",\"cycle\":\"COMBINED\",\"seats\":5,"+
            "\"cycleEvidence\":\"PBEV combo table line 2\"}";
        JsonNode divergent=ok(send("POST",ROOT+"/ratings",manager,key(),null,wrongModel),409);
        assertEquals("MODELO_DIVERGENTE",divergent.path("code").asText());

        String[] ids=new String[4];
        UUID[] vehicles={BEV_A,BEV_B,ICE_A,ICE_B};
        for(int idx=0;idx<4;idx++){
            String role=idx<2?"BEV":"FOSSIL";
            String fuel=idx<2?"ELECTRICITY":"GASOLINE";
            String payload="{\"organizationId\":\""+ORG+"\",\"modelId\":\""+vehicles[idx]+
                "\",\"sourceObjectId\":\""+PBEV_OBJECT+"\",\"sourceRow\":"+(idx+2)+
                ",\"role\":\""+role+"\",\"fuel\":\""+fuel+
                "\",\"cycle\":\"COMBINED\",\"seats\":5,\"cycleEvidence\":\"PBEV combo table line "+(idx+2)+"\"}";
            String key=key();
            JsonNode created=ok(send("POST",ROOT+"/ratings",manager,key,null,payload),201);
            ids[idx]=created.path("id").asText();
            assertEquals(YEAR,created.path("year").asInt());
            assertEquals(idx<2?"kWh/100mi":"mi/USgal",created.path("normalizedUnit").asText());
            assertEquals(ids[idx],ok(send("POST",ROOT+"/ratings",manager,key,null,payload),201)
                .path("id").asText());
        }
        // A source line cannot be silently assigned to two different canonical models.
        String repeatedRow = """
            {"organizationId":"%s","modelId":"%s","sourceObjectId":"%s",
             "sourceRow":2,"role":"BEV","fuel":"ELECTRICITY","cycle":"COMBINED",
             "seats":5,"cycleEvidence":"PBEV same row 2"}
            """.formatted(ORG,BEV_B,PBEV_OBJECT);
        assertEquals(409,send("POST",ROOT+"/ratings",manager,key(),null,repeatedRow).statusCode());
        assertEquals(404,send("POST",ROOT+"/ratings",outsider,key(),null,
            "{\"organizationId\":\""+ORG+"\"}").statusCode());

        JsonNode incomplete=ok(send("GET",ROOT+"/fleets/"+FLEET_SNAPSHOT+"/composition",
            manager,null,null,null),200);
        assertFalse(incomplete.path("canSavePreparatory").asBoolean());
        assertTrue(incomplete.path("issues").toString().contains("sem par aprovado"));
        assertTrue(incomplete.path("afecKwh100mi").isNull());
        assertFalse(incomplete.path("usableForE8").asBoolean());

        for(int i=0;i<2;i++){
            String proposal="{\"organizationId\":\""+ORG+"\",\"fleetSnapshotId\":\""+
                FLEET_SNAPSHOT+"\",\"bevRatingId\":\""+ids[i]+
                "\",\"fossilRatingId\":\""+ids[i+2]+
                "\",\"serviceDescription\":\"Passenger LDV five seats; service equivalent\","+
                "\"reason\":\"Same LDV service and five-seat capacity, combined Brazilian test cycle\"}";
            JsonNode pair=ok(send("POST",ROOT+"/pairs",manager,key(),null,proposal),201);
            String id=pair.path("id").asText();
            assertEquals("PROPOSED",pair.path("status").asText());
            String review="{\"outcome\":\"ACCEPTED\","+
                "\"reason\":\"Verified same national cycle, capacity and comparable service\"}";
            assertEquals(403,send("POST",ROOT+"/pairs/"+id+"/review",manager,
                null,"\"1\"",review).statusCode());
            assertEquals(428,send("POST",ROOT+"/pairs/"+id+"/review",reviewer,
                null,null,review).statusCode());
            JsonNode approved=ok(send("POST",ROOT+"/pairs/"+id+"/review",reviewer,
                null,"\"1\"",review),200);
            assertEquals(2,approved.path("version").asInt());
            assertEquals("ACCEPTED",approved.path("status").asText());
            assertEquals(412,send("POST",ROOT+"/pairs/"+id+"/review",reviewer,
                null,"\"1\"",review).statusCode());
        }
        JsonNode ready=ok(send("GET",ROOT+"/fleets/"+FLEET_SNAPSHOT+"/composition",
            manager,null,null,null),200);
        assertTrue(ready.path("canSavePreparatory").asBoolean(),ready.toString());
        assertEquals(0,new java.math.BigDecimal("20")
            .compareTo(ready.path("afecKwh100mi").decimalValue()));
        assertEquals(0,new java.math.BigDecimal("30")
            .compareTo(ready.path("mpgUs").decimalValue()));
        assertFalse(ready.path("usableForE8").asBoolean());
        String freezeKey=key();
        JsonNode frozen=ok(send("POST",ROOT+"/fleets/"+FLEET_SNAPSHOT+"/snapshots",
            manager,freezeKey,null,null),201);
        assertEquals("PREPARATORY",frozen.path("status").asText());
        assertEquals(frozen.path("id").asText(),
            ok(send("POST",ROOT+"/fleets/"+FLEET_SNAPSHOT+"/snapshots",
                manager,freezeKey,null,null),201).path("id").asText());
        assertFalse(frozen.path("usableForE8").asBoolean());
        assertEquals(2,db.read(c->first(c,
            "SELECT count(*) FROM app_vehicle_rating_snapshot_line WHERE organization_id=?",
            r->r.getInt(1),ORG)).intValue());

        db.transaction(c->{
            update(c, """
                INSERT INTO app_content_license_decision
                 (license_id,version,outcome,decided_by,reason)
                VALUES (?,2,'REVOKED',?,'Remover todos os derivados PBEV da fixture')
                """,PBEV_LIC,REVIEWER);
            return null;
        });
        assertEquals(0,db.read(c->first(c,
            "SELECT count(*) FROM app_vehicle_rating_snapshot_line WHERE organization_id=?",
            r->r.getInt(1),ORG)).intValue());
        assertNull(db.read(c->first(c,
            "SELECT afec_kwh_per_100mi FROM app_vehicle_rating_snapshot WHERE organization_id=?",
            r->r.getBigDecimal(1),ORG)));
        assertNull(db.read(c->first(c,
            "SELECT normalized_value FROM app_vehicle_rating WHERE organization_id=? LIMIT 1",
            r->r.getBigDecimal(1),ORG)));
        assertEquals(403,send("GET",ROOT+"/sources/"+PBEV_OBJECT+"/candidates",
            manager,null,null,null).statusCode());
        var after=ok(send("GET",ROOT+"/board",manager,null,null,null),200);
        assertTrue(after.path("ratings").isEmpty());
        assertTrue(after.path("snapshots").get(0).path("afecKwh100mi").isNull());
    }

    @AfterEach
    void cleanup(){
        db.transaction(c->{
            update(c,"DELETE FROM app_vehicle_rating_command WHERE actor_id IN (?,?,?)",
                MANAGER,REVIEWER,OUTSIDER);
            update(c,"DELETE FROM app_vehicle_rating_snapshot_line WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_vehicle_rating_snapshot WHERE organization_id=?",ORG);
            update(c,"""
                DELETE FROM app_vehicle_pair_review_event
                WHERE pair_id IN (SELECT id FROM app_vehicle_comparable_pair WHERE organization_id=?)
                """,ORG);
            update(c,"DELETE FROM app_vehicle_comparable_pair WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_vehicle_rating WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_composition_weight WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_model_alias WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_composition_snapshot WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_stock_observation WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_ingestion WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_model WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_platform_source_assessment WHERE object_id IN (?,?)",
                FLEET_OBJECT,PBEV_OBJECT);
            update(c,"DELETE FROM app_platform_source_candidate WHERE object_id IN (?,?)",
                FLEET_OBJECT,PBEV_OBJECT);
            update(c,"DELETE FROM app_platform_source_object WHERE id IN (?,?)",
                FLEET_OBJECT,PBEV_OBJECT);
            update(c,"DELETE FROM app_platform_source_submission_event WHERE submission_id IN (?,?)",
                FLEET_SUB,PBEV_SUB);
            update(c,"DELETE FROM app_platform_source_submission WHERE id IN (?,?)",
                FLEET_SUB,PBEV_SUB);
            update(c,"DELETE FROM app_content_license_capability WHERE license_id IN (?,?)",
                FLEET_LIC,PBEV_LIC);
            update(c,"DELETE FROM app_content_license_decision WHERE license_id IN (?,?)",
                FLEET_LIC,PBEV_LIC);
            update(c,"DELETE FROM app_content_license WHERE id IN (?,?)",FLEET_LIC,PBEV_LIC);
            update(c,"""
                DELETE FROM app_source_version WHERE source_id IN
                 ('BR-PBEV-VEHICLE-RATINGS','BR-LDV-BEV-STOCK-BY-MODEL')
                 AND edition='2076-CF-TEST'
                """);
            return null;
        });
    }

    private void seed(){
        db.transaction(c->{
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','PBEV teste')",ORG);
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','PBEV outsider')",OTHER);
            Object[][] models={
                {BEV_A,"EV-A","BEV","A"},{BEV_B,"EV-B","BEV","B"},
                {ICE_A,"ICE-A","OTHER","C"},{ICE_B,"ICE-B","OTHER","D"}};
            for(var model:models){
                update(c,"""
                    INSERT INTO app_fleet_model
                     (id,organization_id,manufacturer,model_name,variant,vehicle_class,propulsion,created_by)
                    VALUES (?,?,'Ficticia',?,'Base','LDV',?,?)
                    """,model[0],ORG,model[1],model[2],MANAGER);
                update(c,"""
                    INSERT INTO app_fleet_model_alias
                     (organization_id,observation_year,record_key,model_id,mapped_by)
                    VALUES (?,?,?,?,?)
                    """,ORG,YEAR,model[3],model[0],MANAGER);
            }
            for(var need:new Object[][]{
                {FLEET_SUB,"OPEN-EVR","BR-LDV-BEV-STOCK-BY-MODEL",FLEET_LIC,FLEET_OBJECT},
                {PBEV_SUB,"OPEN-PBEV","BR-PBEV-VEHICLE-RATINGS",PBEV_LIC,PBEV_OBJECT}}){
                update(c,"""
                    INSERT INTO app_source_version
                     (source_id,edition,category,title,rights_holder,official_uri,origin)
                    VALUES (?,'2076-CF-TEST','EXTERNAL_SOURCE','Fonte sintética de teste',
                      'Titular fictício','https://example.org/test','EXTERNAL')
                    """,need[2]);
                update(c,"""
                    INSERT INTO app_platform_source_submission
                    (id,need_code,source_id,edition,category,title,rights_holder,publisher,
                     official_uri,reference_year,locator,metadata_fingerprint,state,
                     submitted_by,reviewed_by)
                    VALUES (?,?,?,'2076-CF-TEST','EXTERNAL_SOURCE','Arquivo sintético',
                     'Titular fictício','Editor de testes','https://example.org/test',
                     ?,'Tabela teste',?,'METADATA_ACCEPTED',?,?)
                    """,need[0],need[1],need[2],YEAR,
                    AccessSecrets.digest("rating fixture "+need[1]),MANAGER,REVIEWER);
                update(c,"""
                    INSERT INTO app_content_license
                    (id,source_id,edition,organization_id,submitted_by,environment,purpose,
                     authorization_ref,proof_sha256,starts_at,ends_at)
                    VALUES (?,?,'2076-CF-TEST',?,?,'SANDBOX','AUTHORIZED_PROCESSING',
                     'Exclusivamente fixture autorizada',?, '2020-01-01',
                     statement_timestamp()+interval '1 hour')
                    """,need[3],need[2],ORG,MANAGER,"a".repeat(64));
                update(c,"""
                    INSERT INTO app_content_license_decision
                     (license_id,version,outcome,decided_by,reason)
                    VALUES (?,1,'APPROVED',?,'Fixture licenciada por revisor distinto')
                    """,need[3],REVIEWER);
                update(c,"""
                    INSERT INTO app_content_license_capability(license_id,decision_version,operation)
                    VALUES (?,1,'STORE_ORIGINAL'),(?,1,'EXTRACT_TEXT'),
                           (?,1,'SHOW_EXCERPTS'),(?,1,'EXPORT_DERIVATIVES')
                    """,need[3],need[3],need[3],need[3]);
                update(c,"""
                    INSERT INTO app_platform_source_object
                     (id,submission_id,organization_id,uploaded_by,license_id,license_version,
                      file_name,file_format,sha256,bytes_count,nonce,encrypted,state,parsed_rows,parser_version)
                    VALUES(?,?,?,?,?,1,'test.csv','CSV',?,16,?,?,
                     'APPROVED_FOR_SCOPE',4,'TEST-ONLY')
                    """,need[4],need[0],ORG,MANAGER,need[3],
                    AccessSecrets.digest("synthetic data "+need[1]),new byte[12],new byte[32]);
            }
            update(c,"""
                INSERT INTO app_fleet_ingestion
                (id,organization_id,source_object_id,source_license_id,source_sha256,
                 data_kind,observation_year,geography,imported_by)
                VALUES(?,?,?,?,?,'STOCK_IN_CIRCULATION',?,'BR',?)
                """,FLEET_IMPORT,ORG,FLEET_OBJECT,FLEET_LIC,
                AccessSecrets.digest("synthetic data OPEN-EVR"),YEAR,MANAGER);
            update(c,"""
                INSERT INTO app_fleet_composition_snapshot
                 (id,ingestion_id,organization_id,observation_year,geography,
                  denominator,number_of_models,source_sha256)
                VALUES (?,?,?,?,'BR',20,2,?)
                """,FLEET_SNAPSHOT,FLEET_IMPORT,ORG,YEAR,
                AccessSecrets.digest("synthetic data OPEN-EVR"));
            update(c,"""
                INSERT INTO app_fleet_composition_weight
                (snapshot_id,organization_id,model_id,source_row,observed_stock,weight,source_locator)
                VALUES(?,?,?,2,12,0.6,'TEST:2'),(?,?,?,3,8,0.4,'TEST:3')
                """,FLEET_SNAPSHOT,ORG,BEV_A,FLEET_SNAPSHOT,ORG,BEV_B);
            update(c,"""
                INSERT INTO app_platform_source_candidate
                 (object_id,row_index,record_key,metric,period_year,geography,unit,value_numeric,locator)
                VALUES
                 (?,2,'A','ev_energy',?,'BR','kWh/100mi',18,'PBEV Test 2'),
                 (?,3,'B','ev_energy',?,'BR','kWh/100mi',23,'PBEV Test 3'),
                 (?,4,'C','fuel_economy',?,'BR','mi/USgal',32,'PBEV Test 4'),
                 (?,5,'D','fuel_economy',?,'BR','mi/USgal',27,'PBEV Test 5')
                """,PBEV_OBJECT,YEAR,PBEV_OBJECT,YEAR,PBEV_OBJECT,YEAR,PBEV_OBJECT,YEAR);
            for(String metric:new String[]{"ev_energy","fuel_economy"}){
                update(c,"""
                    INSERT INTO app_platform_source_assessment
                     (id,object_id,outcome,methodology,methodology_edition,scenario,
                      blocked_capability,metric,period_year,geography,unit,
                      evidence_locator,reason,reviewed_by)
                    VALUES (?,?,'APPROVED_FOR_SCOPE','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR',
                     'BASELINE_BE',?,?,'BR',?,
                     'PBEV synthetic table line 2','Verificação independente exclusivamente fixture',?)
                    """,UUID.randomUUID(),PBEV_OBJECT,metric,YEAR,
                    metric.equals("ev_energy")?"kWh/100mi":"mi/USgal",REVIEWER);
            }
            return null;
        });
    }

    private String actor(UUID id){
        String token=AccessSecrets.newToken();
        db.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES(?,?,'Teste rating','ACTIVE',statement_timestamp())
                ON CONFLICT(id) DO NOTHING
                """,id,id+"@example.invalid");
            update(c,"""
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return token;
    }
    private void verify(String token)throws Exception{
        long counter=db.read(c->first(c,
            "SELECT floor(extract(epoch from statement_timestamp())/30)::bigint",
            r->r.getLong(1)));
        String code="{\"code\":\""+ComplianceStaff.codeAt(SECRET,counter)+"\"}";
        assertEquals(200,send("POST","/v1/compliance/session",token,null,null,code).statusCode());
    }
    private String key(){return UUID.randomUUID().toString();}
    private HttpResponse<String> send(String method,String path,String token,String key,
            String ifMatch,String body)throws Exception{
        var url=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(token!=null)url.header("Cookie",AccountAccess.COOKIE+"="+token);
        if(key!=null)url.header("Idempotency-Key",key);
        if(ifMatch!=null)url.header("If-Match",ifMatch);
        if(body!=null)url.header("Content-Type","application/json");
        return client.send(url.method(method,body==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(HttpResponse<String> r,int code)throws Exception{
        assertEquals(code,r.statusCode(),r.body());
        return mapper.readTree(r.body());
    }
    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String,String> start(){
            var props=new HashMap<>(super.start());
            var secret=Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET);
            props.put("carbonflow.compliance.staff",
                MANAGER+":"+secret+":"+ORG+";"+REVIEWER+":"+secret+":"+ORG+
                ";"+OUTSIDER+":"+secret+":"+OTHER);
            props.put("carbonflow.platform.source-readiness.viewers",
                MANAGER+","+REVIEWER+","+OUTSIDER);
            props.put("carbonflow.platform.source-work.managers",MANAGER.toString());
            props.put("carbonflow.platform.vehicle-ratings.reviewers",MANAGER+","+REVIEWER);
            return props;
        }
    }
}
