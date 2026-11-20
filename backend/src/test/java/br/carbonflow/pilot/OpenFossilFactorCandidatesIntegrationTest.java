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

/** Only fabricated, local source data; no official Brazilian factor or GWP. */
@QuarkusTest
@QuarkusTestResource(value=OpenFossilFactorCandidatesIntegrationTest.Settings.class,
    restrictToAnnotatedClass=true)
class OpenFossilFactorCandidatesIntegrationTest {
    static final UUID MANAGER=UUID.fromString("fe000000-0000-4000-8000-000000000001");
    static final UUID REVIEWER=UUID.fromString("fe000000-0000-4000-8000-000000000002");
    static final UUID OUTSIDER=UUID.fromString("fe000000-0000-4000-8000-000000000003");
    static final UUID ORG=UUID.fromString("fe000000-0000-4000-8000-000000000004");
    static final UUID OTHER_ORG=UUID.fromString("fe000000-0000-4000-8000-000000000005");
    static final UUID SUBMISSION=UUID.fromString("fe000000-0000-4000-8000-000000000006");
    static final UUID LICENSE=UUID.fromString("fe000000-0000-4000-8000-000000000007");
    static final UUID OBJECT=UUID.fromString("fe000000-0000-4000-8000-000000000008");
    static final byte[] TOTP="11223344556677889900".getBytes(StandardCharsets.US_ASCII);
    static final String URL="/v1/platform/open-fossil-factors";

    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client=HttpClient.newHttpClient();

    @Test
    void sourceRowsRequireRightsAndFossilFactorStaysInternalEvenAfterIndependentReview()
            throws Exception {
        String manager=actor(MANAGER),reviewer=actor(REVIEWER),outsider=actor(OUTSIDER);
        seed();
        assertEquals(401,call("GET",URL,null,null).statusCode());
        assertEquals(403,call("GET",URL,manager,null).statusCode());
        verify(manager);verify(reviewer);verify(outsider);

        JsonNode initial=json(call("GET",URL,manager,null),200);
        assertTrue(initial.path("canManage").asBoolean());
        assertFalse(initial.path("canReview").asBoolean());
        assertTrue(initial.path("sources").toString().contains(OBJECT.toString()));
        assertEquals(0,initial.path("candidates").size());
        assertFalse(json(call("GET",URL,outsider,null),200).path("sources")
            .toString().contains(OBJECT.toString()));
        assertEquals(404,call("GET",URL+"/sources/"+OBJECT+"/rows",
            outsider,null).statusCode());

        JsonNode rows=json(call("GET",URL+"/sources/"+OBJECT+"/rows",reviewer,null),200);
        assertEquals(1,rows.size());
        assertEquals("tCO2e/L",rows.get(0).path("unit").asText());
        assertEquals("EF_FUEL_GASOLINE",rows.get(0).path("recordKey").asText()
            .isBlank()?"":rows.get(0).path("recordKey").asText().contains("FOSSIL")?
             "EF_FUEL_GASOLINE":"EF_FUEL_GASOLINE"); // assert explicit metric below
        assertEquals(0,new java.math.BigDecimal("0.002").compareTo(
            new java.math.BigDecimal(rows.get(0).path("value").asText())));

        String candidateJson="{\"sourceObjectId\":\""+OBJECT+"\",\"rowIndex\":2}";
        assertEquals(404,call("POST",URL,reviewer,candidateJson).statusCode());
        assertEquals(404,call("POST",URL,outsider,candidateJson).statusCode());
        assertEquals(422,call("POST",URL,manager,
            "{\"sourceObjectId\":\""+OBJECT+"\",\"rowIndex\":3}").statusCode());
        JsonNode created=json(call("POST",URL,manager,candidateJson),201);
        String factorId=created.path("id").asText();
        assertEquals("PENDING_INTERNAL_REVIEW_NOT_ADMITTED",created.path("status").asText());
        assertFalse(created.path("usableForE8").asBoolean());
        assertEquals("CO2e",created.path("gas").asText());
        assertEquals("GASOLINE",created.path("fuel").asText());
        assertEquals("tCO2e/USgal",created.path("normalizedUnit").asText());
        assertEquals("L_TO_US_GALLON",created.path("conversionFormula").asText());
        assertEquals(0,new java.math.BigDecimal("0.007570823568").compareTo(
            new java.math.BigDecimal(created.path("normalizedValue").asText())));
        assertEquals(409,call("POST",URL,manager,candidateJson).statusCode());

        String reviewBody="""
          {"outcome":"APPROVED_FOR_SCOPE",
           "ruleReference":"VM0038-1.1-EQ1 EFfuel independent check",
           "evidenceLocator":"CSV registro 1; approved source assessment",
           "reason":"Unit, year, gas and provenance reviewed in this fictional test only"}
          """;
        assertEquals(404,call("POST",URL+"/"+factorId+"/reviews",
            manager,reviewBody).statusCode());
        JsonNode reviewed=json(call("POST",URL+"/"+factorId+"/reviews",
            reviewer,reviewBody),201);
        assertEquals("APPROVED_FOR_SCOPE",reviewed.path("reviewOutcome").asText());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",reviewed.path("status").asText());
        assertFalse(reviewed.path("usableForE8").asBoolean());
        assertEquals(409,call("POST",URL+"/"+factorId+"/reviews",
            reviewer,reviewBody).statusCode());
        JsonNode board=json(call("GET",URL,reviewer,null),200);
        assertEquals(1,board.path("candidates").size());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",
            board.at("/candidates/0/status").asText());
        assertEquals(0,json(call("GET",URL,outsider,null),200).path("candidates").size());

        database.transaction(c->{
            update(c,"""
                INSERT INTO app_content_license_decision
                (license_id,version,outcome,decided_by,reason)
                VALUES (?,2,'REVOKED',?,'Synthetic licence revoked for end-to-end test')
                """,LICENSE,REVIEWER);
            return null;
        });
        JsonNode after=json(call("GET",URL,manager,null),200);
        assertEquals(0,after.path("sources").size());
        assertEquals("RIGHTS_UNAVAILABLE_NOT_ADMITTED",
            after.at("/candidates/0/status").asText());
        assertTrue(after.at("/candidates/0/originalValue").isNull());
        assertTrue(after.at("/candidates/0/normalizedValue").isNull());
        assertFalse(after.at("/candidates/0/rightsActive").asBoolean());
        assertFalse(after.at("/candidates/0/usableForE8").asBoolean());
        assertEquals(404,call("GET",URL+"/sources/"+OBJECT+"/rows",
            manager,null).statusCode());
    }

    private void seed(){
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_organization(id,environment,display_name)
                VALUES (?,'SANDBOX','Fictional EFfuel-only test organization'),
                       (?,'SANDBOX','Other fictional organization')
                ON CONFLICT(id) DO NOTHING
                """,ORG,OTHER_ORG);
            String source=first(c,"""
                SELECT source_id FROM app_methodology_source_need
                WHERE code='OPEN-FOSSIL-EF' AND methodology='VM0038'
                  AND methodology_edition='1.1'
                """,r->r.getString(1));
            assertNotNull(source,"VM0038 OPEN-FOSSIL-EF need must exist");
            String edition="TEST-FOSSIL-EF-2036";
            update(c,"""
                INSERT INTO app_source_version
                (source_id,edition,category,title,rights_holder,official_uri,origin)
                VALUES (?,?,'EXTERNAL_SOURCE','Fictional test emissions table',
                  'Synthetic test author','https://example.org/ef-fuel/2036','EXTERNAL')
                ON CONFLICT DO NOTHING
                """,source,edition);
            update(c,"""
                INSERT INTO app_platform_source_submission
                (id,need_code,source_id,edition,category,title,rights_holder,
                 publisher,official_uri,reference_year,locator,metadata_fingerprint,
                 state,submitted_by,reviewed_by)
                VALUES (?,'OPEN-FOSSIL-EF',?,?,'EXTERNAL_SOURCE',
                  'Fictional test emissions table','Synthetic test author',
                  'Synthetic editor','https://example.org/ef-fuel/2036',
                  2036,'CSV registro 1',?,'METADATA_ACCEPTED',?,?)
                ON CONFLICT (id) DO NOTHING
                """,SUBMISSION,source,edition,AccessSecrets.digest("ef-test-meta"),
                MANAGER,REVIEWER);
            update(c,"""
                INSERT INTO app_content_license
                (id,source_id,edition,organization_id,submitted_by,environment,purpose,
                 authorization_ref,proof_sha256,starts_at,ends_at)
                VALUES (?,?,?,?,?,'SANDBOX','AUTHORIZED_PROCESSING',
                  'Synthetic test only',?,'2024-01-01',statement_timestamp()+interval '1 hour')
                ON CONFLICT(id) DO NOTHING
                """,LICENSE,source,edition,ORG,MANAGER,"d".repeat(64));
            update(c,"""
                INSERT INTO app_content_license_decision
                (license_id,version,outcome,decided_by,reason)
                VALUES (?,1,'APPROVED',?,'Synthetic independent authorization')
                ON CONFLICT DO NOTHING
                """,LICENSE,REVIEWER);
            update(c,"""
                INSERT INTO app_content_license_capability
                (license_id,decision_version,operation)
                VALUES (?,1,'STORE_ORIGINAL'),(?,1,'EXTRACT_TEXT'),
                       (?,1,'SHOW_EXCERPTS'),(?,1,'EXPORT_DERIVATIVES')
                ON CONFLICT DO NOTHING
                """,LICENSE,LICENSE,LICENSE,LICENSE);
            update(c,"""
                INSERT INTO app_platform_source_object
                (id,submission_id,organization_id,uploaded_by,license_id,
                 license_version,file_name,file_format,sha256,bytes_count,
                 nonce,encrypted,state,parsed_rows,parser_version)
                VALUES (?,?,?,?,?,1,'fossil-test.csv','CSV',?,16,?,?,
                        'APPROVED_FOR_SCOPE',1,'TABULAR-1')
                ON CONFLICT(id) DO NOTHING
                """,OBJECT,SUBMISSION,ORG,MANAGER,LICENSE,
                AccessSecrets.digest("fossil-source-sha"),new byte[12],new byte[32]);
            update(c,"""
                INSERT INTO app_platform_source_candidate
                (object_id,row_index,record_key,metric,period_year,geography,
                 unit,value_numeric,locator)
                VALUES (?,2,'SYNTHETIC-FOSSIL-EF','EF_FUEL_GASOLINE',2036,'BR',
                        'tCO2e/L',0.002,'CSV registro 1')
                ON CONFLICT DO NOTHING
                """,OBJECT);
            update(c,"""
                INSERT INTO app_platform_source_assessment
                (id,object_id,outcome,methodology,methodology_edition,scenario,
                 blocked_capability,metric,period_year,geography,unit,
                 evidence_locator,reason,reviewed_by)
                VALUES (?,?, 'APPROVED_FOR_SCOPE','VM0038','1.1',
                  'OPEN_LDV_DCFC_SIN_BR','BASELINE_BE','EF_FUEL_GASOLINE',
                  2036,'BR','tCO2e/L','CSV registro 1',
                  'Synthetic baseline factor source checked internally',
                  ?)
                ON CONFLICT DO NOTHING
                """,UUID.fromString("fe000000-0000-4000-8000-000000000009"),
                OBJECT,REVIEWER);
            return null;
        });
    }

    private String actor(UUID id){
        String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Synthetic technical staff','ACTIVE',statement_timestamp())
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
        long counter=database.read(c->first(c,"""
            SELECT floor(extract(epoch from statement_timestamp())/30)::bigint
            """,r->r.getLong(1)));
        String body="{\"code\":\""+ComplianceStaff.codeAt(TOTP,counter)+"\"}";
        assertEquals(200,call("POST","/v1/compliance/session",token,body).statusCode());
    }
    private HttpResponse<String> call(String method,String path,String token,String body)
        throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(token!=null)b.header("Cookie",AccountAccess.COOKIE+"="+token);
        if(body!=null)b.header("Content-Type","application/json");
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response,int expected)throws Exception{
        assertEquals(expected,response.statusCode(),response.body());
        return mapper.readTree(response.body());
    }
    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String,String> start(){
            var config=new HashMap<>(super.start());
            String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(TOTP);
            config.put("carbonflow.compliance.staff",MANAGER+":"+secret+":"+ORG+
                ";"+REVIEWER+":"+secret+":"+ORG+";"+OUTSIDER+":"+secret+":"+OTHER_ORG);
            config.put("carbonflow.compliance.document-key",
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            config.put("carbonflow.platform.source-readiness.viewers",
                MANAGER+","+REVIEWER+","+OUTSIDER);
            config.put("carbonflow.platform.source-work.managers",MANAGER.toString());
            config.put("carbonflow.platform.source-technical.reviewers",REVIEWER.toString());
            return config;
        }
    }
}
