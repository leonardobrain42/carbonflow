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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

@QuarkusTest
@QuarkusTestResource(value=FleetCompositionIntegrationTest.Settings.class,restrictToAnnotatedClass=true)
class FleetCompositionIntegrationTest {
    static final UUID MANAGER=UUID.fromString("ff5c0000-0000-4000-8000-000000000001");
    static final UUID REVIEWER=UUID.fromString("ff5c0000-0000-4000-8000-000000000002");
    static final UUID OUTSIDER=UUID.fromString("ff5c0000-0000-4000-8000-000000000003");
    static final UUID ORG=UUID.fromString("ff5c0000-0000-4000-8000-000000000004");
    static final UUID OTHER=UUID.fromString("ff5c0000-0000-4000-8000-000000000005");
    static final UUID SUBMISSION=UUID.fromString("ff5c0000-0000-4000-8000-000000000006");
    static final UUID LICENSE=UUID.fromString("ff5c0000-0000-4000-8000-000000000007");
    static final UUID SOURCE_OBJECT=UUID.fromString("ff5c0000-0000-4000-8000-000000000008");
    static final UUID SUBMISSION2=UUID.fromString("ff5c0000-0000-4000-8000-000000000011");
    static final UUID LICENSE2=UUID.fromString("ff5c0000-0000-4000-8000-000000000012");
    static final UUID SOURCE_OBJECT2=UUID.fromString("ff5c0000-0000-4000-8000-000000000013");
    static final byte[] SECRET="23456789012345678901".getBytes(StandardCharsets.US_ASCII);
    static final String ROOT="/v1/platform/fleet";
    static final int YEAR=2081;

    @Inject PilotDatabase db;
    @Inject ObjectMapper json;
    @TestHTTPResource URL base;
    final HttpClient http=HttpClient.newHttpClient();

    @Test
    void scopedStockCreatesExactWeightsWithIndependentAssessmentThenRevocationPurgesAll()throws Exception{
        String manager=actor(MANAGER),reviewer=actor(REVIEWER),outsider=actor(OUTSIDER);
        seed(false);
        assertEquals(401,request("GET",ROOT+"/board",null,null,null).statusCode());
        assertEquals(403,request("GET",ROOT+"/board",manager,null,null).statusCode());
        verify(manager);
        verify(reviewer);
        verify(outsider);

        var board=read(request("GET",ROOT+"/board",manager,null,null),200);
        assertEquals("OPEN-EVR",board.path("missingNeed").asText());
        assertEquals(1,board.path("sources").size());
        assertTrue(board.path("sources").get(0).path("rightsActive").asBoolean());
        assertEquals(0,board.path("models").size());
        assertEquals(0,read(request("GET",ROOT+"/board",outsider,null,null),200)
            .path("sources").size());
        assertEquals(404,request("GET",ROOT+"/sources/"+SOURCE_OBJECT+"/preview?year="+YEAR,
            outsider,null,null).statusCode());

        JsonNode incomplete=read(request("GET",ROOT+"/sources/"+SOURCE_OBJECT+"/preview?year="+YEAR,
            manager,null,null),200);
        assertFalse(incomplete.path("valid").asBoolean());
        assertFalse(incomplete.path("canCreateSnapshot").asBoolean());
        assertTrue(incomplete.path("issues").toString().contains("modelo não classificado"));
        assertEquals(409,request("POST",ROOT+"/imports",manager,key(), "{\"sourceObjectId\":\""+
            SOURCE_OBJECT+"\",\"year\":"+YEAR+"}").statusCode());

        String one="{\"organizationId\":\""+ORG+"\",\"manufacturer\":\"Ficticia\","+
            "\"modelName\":\"BEV Modelo A\",\"variant\":\"Base\","+
            "\"vehicleClass\":\"LDV\",\"propulsion\":\"BEV\","+
            "\"aliasYear\":"+YEAR+",\"sourceRecordKey\":\"MODEL_A\"}";
        String firstKey=key();
        JsonNode model1=read(request("POST",ROOT+"/models",manager,firstKey,one),201);
        assertEquals(model1,read(request("POST",ROOT+"/models",manager,firstKey,one),201));
        assertEquals(409,request("POST",ROOT+"/models",manager,firstKey,
            one.replace("Base","Premium")).statusCode());
        String second=one.replace("BEV Modelo A","BEV Modelo B").replace("MODEL_A","MODEL_B");
        JsonNode model2=read(request("POST",ROOT+"/models",manager,key(),second),201);
        assertNotEquals(model1.path("id").asText(),model2.path("id").asText());
        assertEquals(409,request("POST",ROOT+"/models/"+model2.path("id").asText()+"/aliases",
            manager,key(),"{\"organizationId\":\""+ORG+"\",\"year\":"+YEAR+
                ",\"sourceRecordKey\":\"MODEL_A\"}").statusCode());
        assertEquals(404,request("POST",ROOT+"/models",outsider,key(),one).statusCode());
        assertEquals(422,request("POST",ROOT+"/models",manager,key(),
            one.replace("\"BEV\"","\"ICE\"")).statusCode());

        JsonNode classified=read(request("GET",ROOT+"/sources/"+SOURCE_OBJECT+"/preview?year="+YEAR,
            manager,null,null),200);
        assertTrue(classified.path("valid").asBoolean(),classified.toString());
        assertFalse(classified.path("canCreateSnapshot").asBoolean());
        assertEquals(100,classified.path("denominator").asInt());
        assertEquals(2,classified.path("rows").size());
        assertTrue(classified.path("issues").toString().contains("Parecer independente"));

        db.transaction(c->{
            update(c, """
                INSERT INTO app_platform_source_assessment
                (id,object_id,outcome,methodology,methodology_edition,scenario,
                 blocked_capability,metric,period_year,geography,unit,evidence_locator,reason,reviewed_by)
                VALUES (?,?,'APPROVED_FOR_SCOPE','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR',
                 'BASELINE_BE','stock',?,'BR','vehicles','Tabular test line 2',
                 'Parecer sintético independente somente para o teste',?)
                """,UUID.randomUUID(),SOURCE_OBJECT,YEAR,REVIEWER);
            update(c,"UPDATE app_platform_source_object SET state='APPROVED_FOR_SCOPE' WHERE id=?",SOURCE_OBJECT);
            return null;
        });
        JsonNode ready=read(request("GET",ROOT+"/sources/"+SOURCE_OBJECT+"/preview?year="+YEAR,
            reviewer,null,null),200);
        assertTrue(ready.path("canCreateSnapshot").asBoolean(),ready.toString());
        assertFalse(ready.path("usableForE8").asBoolean());

        String payload="{\"sourceObjectId\":\""+SOURCE_OBJECT+"\",\"year\":"+YEAR+"}";
        String snapshotKey=key();
        JsonNode first=read(request("POST",ROOT+"/imports",manager,snapshotKey,payload),201);
        JsonNode repeated=read(request("POST",ROOT+"/imports",manager,snapshotKey,payload),201);
        assertEquals(first,repeated);
        assertEquals(100,first.at("/snapshot/denominator").asInt());
        assertEquals("RATINGS_NOT_LINKED",first.at("/snapshot/ratingState").asText());
        assertEquals("OFFICIAL_TOTAL_NOT_VERIFIED",first.at("/snapshot/coverageState").asText());
        assertFalse(first.at("/snapshot/usableForE8").asBoolean());
        assertEquals(2,first.path("weights").size());
        assertEquals(0,new java.math.BigDecimal("1").compareTo(
            first.path("weights").valueStream()
                .map(n->new java.math.BigDecimal(n.path("weight").asText()))
                .reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add)));
        assertEquals(1L,count("app_fleet_ingestion"));
        assertEquals(1L,count("app_fleet_composition_snapshot"));
        assertEquals(2L,count("app_fleet_stock_observation"));
        assertEquals(0L,count("app_fleet_sales_observation"));
        String imported=first.at("/snapshot/ingestionId").asText();
        assertEquals(404,request("GET",ROOT+"/imports/"+imported,outsider,null,null).statusCode());

        // An independently approved later edition creates another immutable snapshot.
        seedSecondEdition();
        JsonNode secondPreview=read(request("GET",ROOT+"/sources/"+SOURCE_OBJECT2+
            "/preview?year="+YEAR,manager,null,null),200);
        assertTrue(secondPreview.path("canCreateSnapshot").asBoolean(),secondPreview.toString());
        JsonNode newer=read(request("POST",ROOT+"/imports",manager,key(),
            "{\"sourceObjectId\":\""+SOURCE_OBJECT2+"\",\"year\":"+YEAR+"}"),201);
        assertEquals(100,newer.at("/snapshot/denominator").asInt());
        assertEquals(2L,count("app_fleet_composition_snapshot"));
        assertEquals("STALE_REFERENCE",read(request("GET",ROOT+"/imports/"+imported,
            manager,null,null),200).at("/snapshot/state").asText());
        assertTrue(read(request("GET",ROOT+"/imports/"+imported,
            manager,null,null),200).path("weights").valueStream()
                .anyMatch(item->item.path("evr").asInt()==25));
        assertEquals("PREPARATORY",newer.at("/snapshot/state").asText());

        // Source rights revocation must purge snapshots AND observations, not only the original.
        db.transaction(c->{
            update(c, """
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                VALUES (?,2,'REVOKED',?,'Revogação de direitos simulada no teste')
                """,LICENSE,REVIEWER);
            return null;
        });
        assertEquals(2L,count("app_fleet_composition_weight"),
            "O snapshot da outra licença permanece; o revogado foi expurgado.");
        assertEquals(2L,count("app_fleet_stock_observation"));
        assertEquals(0L,count("app_fleet_sales_observation"));
        assertEquals(2L,count("app_fleet_composition_snapshot"));
        assertNull(db.read(c->first(c, """
            SELECT s.denominator FROM app_fleet_composition_snapshot s
            JOIN app_fleet_ingestion i ON i.id=s.ingestion_id
            WHERE i.source_license_id=?
            """,r->(Long)r.getObject(1),LICENSE)));
        assertNull(db.read(c->first(c, """
            SELECT s.number_of_models FROM app_fleet_composition_snapshot s
            JOIN app_fleet_ingestion i ON i.id=s.ingestion_id
            WHERE i.source_license_id=?
            """,r->(Integer)r.getObject(1),LICENSE)));
        assertEquals(404,request("GET",ROOT+"/imports/"+imported,manager,null,null).statusCode());
        assertEquals(200,request("GET",ROOT+"/imports/"+newer.at("/snapshot/ingestionId").asText(),
            manager,null,null).statusCode());
        JsonNode postRevocation=read(request("GET",ROOT+"/board",manager,null,null),200);
        JsonNode oldStatus=postRevocation.path("snapshots").valueStream()
            .filter(n->imported.equals(n.path("ingestionId").asText())).findFirst().orElseThrow();
        assertEquals("RIGHTS_UNAVAILABLE",oldStatus.path("state").asText());
        assertTrue(oldStatus.path("denominator").isNull());
        assertEquals(404,request("GET",ROOT+"/sources/"+SOURCE_OBJECT+"/preview?year="+YEAR,
            manager,null,null).statusCode());
    }

    @AfterEach
    void cleanupIsolatedFixtures() {
        // These SOURCE_VERSION rows feed the global inventory. Deleting them prevents
        // the platform-readiness integration tests from seeing our invented dataset.
        db.transaction(c->{
            update(c,"DELETE FROM app_fleet_command WHERE actor_id IN (?,?,?)",
                MANAGER,REVIEWER,OUTSIDER);
            update(c,"""
                DELETE FROM app_fleet_composition_weight WHERE organization_id=?
                """,ORG);
            update(c,"DELETE FROM app_fleet_composition_snapshot WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_stock_observation WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_sales_observation WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_ingestion WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_model_alias WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_fleet_model WHERE organization_id=?",ORG);
            update(c,"DELETE FROM app_platform_source_assessment WHERE object_id IN (?,?)",
                SOURCE_OBJECT,SOURCE_OBJECT2);
            update(c,"DELETE FROM app_platform_source_candidate WHERE object_id IN (?,?)",
                SOURCE_OBJECT,SOURCE_OBJECT2);
            update(c,"DELETE FROM app_platform_source_object WHERE id IN (?,?)",
                SOURCE_OBJECT,SOURCE_OBJECT2);
            update(c,"DELETE FROM app_platform_source_submission_event WHERE submission_id IN (?,?)",
                SUBMISSION,SUBMISSION2);
            update(c,"DELETE FROM app_platform_source_submission WHERE id IN (?,?)",
                SUBMISSION,SUBMISSION2);
            update(c,"DELETE FROM app_content_license_capability WHERE license_id IN (?,?)",
                LICENSE,LICENSE2);
            update(c,"DELETE FROM app_content_license_decision WHERE license_id IN (?,?)",
                LICENSE,LICENSE2);
            update(c,"DELETE FROM app_content_license WHERE id IN (?,?)",
                LICENSE,LICENSE2);
            update(c,"""
                DELETE FROM app_source_version
                WHERE source_id='BR-LDV-BEV-STOCK-BY-MODEL'
                  AND edition IN ('2081','2081-R2')
                """);
            return null;
        });
    }

    private void seedSecondEdition() {
        db.transaction(c->{
            update(c, """
                INSERT INTO app_source_version
                (source_id,edition,category,title,rights_holder,official_uri,origin)
                VALUES('BR-LDV-BEV-STOCK-BY-MODEL','2081-R2','EXTERNAL_SOURCE',
                       'Base EVR sintética corrigida','Editorial de teste',
                       'https://example.org/evr-v2','EXTERNAL')
                """);
            update(c, """
                INSERT INTO app_platform_source_submission
                (id,need_code,source_id,edition,category,title,rights_holder,publisher,
                 official_uri,reference_year,locator,metadata_fingerprint,state,
                 submitted_by,reviewed_by)
                VALUES(?,'OPEN-EVR','BR-LDV-BEV-STOCK-BY-MODEL','2081-R2','EXTERNAL_SOURCE',
                       'Base EVR sintética corrigida','Editorial de teste','Gerador de testes',
                       'https://example.org/evr-v2',?,'Tabela 2',?,'METADATA_ACCEPTED',?,?)
                """,SUBMISSION2,YEAR,AccessSecrets.digest("fleet metadata r2"),
                MANAGER,REVIEWER);
            update(c, """
                INSERT INTO app_content_license
                (id,source_id,edition,organization_id,submitted_by,environment,purpose,
                 authorization_ref,proof_sha256,starts_at,ends_at)
                VALUES (?,'BR-LDV-BEV-STOCK-BY-MODEL','2081-R2',?,?,'SANDBOX',
                  'AUTHORIZED_PROCESSING','Autorização R2 somente fixture',?,
                  '2020-01-01',statement_timestamp()+interval '1 hour')
                """,LICENSE2,ORG,MANAGER,"c".repeat(64));
            update(c, """
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                VALUES(?,1,'APPROVED',?,'Aprovação R2 de teste para dados fictícios')
                """,LICENSE2,REVIEWER);
            update(c, """
                INSERT INTO app_content_license_capability(license_id,decision_version,operation)
                VALUES (?,1,'STORE_ORIGINAL'),(?,1,'EXTRACT_TEXT'),
                       (?,1,'SHOW_EXCERPTS'),(?,1,'EXPORT_DERIVATIVES')
                """,LICENSE2,LICENSE2,LICENSE2,LICENSE2);
            update(c, """
                INSERT INTO app_platform_source_object
                (id,submission_id,organization_id,uploaded_by,license_id,license_version,
                 file_name,file_format,sha256,bytes_count,nonce,encrypted,state,parsed_rows,parser_version)
                VALUES (?,?,?,?,?,1,'evr-fixture-v2.csv','CSV',?,16,?,?,
                        'APPROVED_FOR_SCOPE',2,'TEST-FIXTURE')
                """,SOURCE_OBJECT2,SUBMISSION2,ORG,MANAGER,LICENSE2,
                AccessSecrets.digest("fictional bytes version 2"),new byte[12],new byte[32]);
            update(c, """
                INSERT INTO app_platform_source_candidate
                (object_id,row_index,record_key,metric,period_year,geography,unit,value_numeric,locator)
                VALUES(?,2,'MODEL_A','stock',?,'BR','vehicles',20,'TEST-R2:ROW2'),
                      (?,3,'MODEL_B','stock',?,'BR','vehicles',80,'TEST-R2:ROW3')
                """,SOURCE_OBJECT2,YEAR,SOURCE_OBJECT2,YEAR);
            update(c, """
                INSERT INTO app_platform_source_assessment
                (id,object_id,outcome,methodology,methodology_edition,scenario,
                 blocked_capability,metric,period_year,geography,unit,evidence_locator,reason,reviewed_by)
                VALUES (?,?,'APPROVED_FOR_SCOPE','VM0038','1.1','OPEN_LDV_DCFC_SIN_BR',
                 'BASELINE_BE','stock',?,'BR','vehicles','Tabular R2 line 2',
                 'Parecer sintético independente versão R2',?)
                """,UUID.randomUUID(),SOURCE_OBJECT2,YEAR,REVIEWER);
            return null;
        });
    }

    private void seed(boolean alternative) {
        db.transaction(c->{
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','EVR teste')",ORG);
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Outra organização')",OTHER);
            update(c, """
                INSERT INTO app_source_version
                (source_id,edition,category,title,rights_holder,official_uri,origin)
                VALUES('BR-LDV-BEV-STOCK-BY-MODEL',?,'EXTERNAL_SOURCE',
                  'Base EVR sintética segregada','Editorial de teste',
                  'https://example.org/evr-test','EXTERNAL')
                """,String.valueOf(YEAR));
            update(c, """
                INSERT INTO app_platform_source_submission
                (id,need_code,source_id,edition,category,title,rights_holder,publisher,
                 official_uri,reference_year,locator,metadata_fingerprint,state,
                 submitted_by,reviewed_by)
                VALUES(?,'OPEN-EVR','BR-LDV-BEV-STOCK-BY-MODEL',?,'EXTERNAL_SOURCE',
                 'Base EVR sintética segregada','Editorial de teste','Gerador de testes',
                 'https://example.org/evr-test',?,'Tabela 1',?,
                 'METADATA_ACCEPTED',?,?)
                """,SUBMISSION,String.valueOf(YEAR),YEAR,
                AccessSecrets.digest("fleet metadata 2081"),MANAGER,REVIEWER);
            update(c, """
                INSERT INTO app_content_license
                (id,source_id,edition,organization_id,submitted_by,environment,purpose,
                 authorization_ref,proof_sha256,starts_at,ends_at)
                VALUES (?,'BR-LDV-BEV-STOCK-BY-MODEL',?,?,?,'SANDBOX',
                  'AUTHORIZED_PROCESSING','Autorização exclusivamente fixture',?,
                  '2020-01-01',statement_timestamp()+interval '1 hour')
                """,LICENSE,String.valueOf(YEAR),ORG,MANAGER,"b".repeat(64));
            update(c, """
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                VALUES(?,1,'APPROVED',?,'Aprovação de teste para dados fictícios')
                """,LICENSE,REVIEWER);
            update(c, """
                INSERT INTO app_content_license_capability(license_id,decision_version,operation)
                VALUES (?,1,'STORE_ORIGINAL'),(?,1,'EXTRACT_TEXT'),
                       (?,1,'SHOW_EXCERPTS'),(?,1,'EXPORT_DERIVATIVES')
                """,LICENSE,LICENSE,LICENSE,LICENSE);
            byte[] digest=AccessSecrets.digest("fictional bytes - no production data");
            update(c, """
                INSERT INTO app_platform_source_object
                (id,submission_id,organization_id,uploaded_by,license_id,license_version,
                 file_name,file_format,sha256,bytes_count,nonce,encrypted,state,parsed_rows,parser_version)
                VALUES (?,?,?,?,?,1,'evr-fixture.csv','CSV',?,16,?,?, 'NORMALIZED_CANDIDATE',2,'TEST-FIXTURE')
                """,SOURCE_OBJECT,SUBMISSION,ORG,MANAGER,LICENSE,digest,new byte[12],new byte[32]);
            update(c, """
                INSERT INTO app_platform_source_candidate
                (object_id,row_index,record_key,metric,period_year,geography,unit,value_numeric,locator)
                VALUES(?,2,'MODEL_A','stock',?,'BR','vehicles',25,'TEST:ROW2'),
                      (?,3,'MODEL_B','stock',?,'BR','vehicles',75,'TEST:ROW3')
                """,SOURCE_OBJECT,YEAR,SOURCE_OBJECT,YEAR);
            return null;
        });
    }

    private long count(String table) {
        if(!java.util.Set.of("app_fleet_ingestion","app_fleet_composition_snapshot",
            "app_fleet_composition_weight","app_fleet_stock_observation",
            "app_fleet_sales_observation").contains(table))throw new IllegalArgumentException();
        return db.read(c->first(c,"SELECT count(*) FROM "+table,r->r.getLong(1)));
    }
    private String actor(UUID id){
        String token=AccessSecrets.newToken();
        db.transaction(c->{
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES(?,?,'EVR teste','ACTIVE',statement_timestamp())
                ON CONFLICT(id) DO NOTHING
                """,id,id+"@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES(?,?,statement_timestamp()+interval '1 hour')
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
        assertEquals(200,request("POST","/v1/compliance/session",token,null,code).statusCode());
    }
    private static String key(){return UUID.randomUUID().toString();}
    private HttpResponse<String> request(String method,String path,String token,String key,
            String payload)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(token!=null)builder.header("Cookie",AccountAccess.COOKIE+"="+token);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(payload!=null)builder.header("Content-Type","application/json");
        builder.method(method,payload==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(payload));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode read(HttpResponse<String> result,int status)throws Exception{
        assertEquals(status,result.statusCode(),result.body());
        return json.readTree(result.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String,String> start(){
            var values=new HashMap<>(super.start());
            var secret=Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET);
            values.put("carbonflow.compliance.staff",
                MANAGER+":"+secret+":"+ORG+";"+REVIEWER+":"+secret+":"+ORG+
                ";"+OUTSIDER+":"+secret+":"+OTHER);
            values.put("carbonflow.platform.source-readiness.viewers",
                MANAGER+","+REVIEWER+","+OUTSIDER);
            values.put("carbonflow.platform.source-work.managers",MANAGER.toString());
            return values;
        }
    }
}
