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

@QuarkusTest
@QuarkusTestResource(value=PlatformSourceFilesIntegrationTest.Settings.class,restrictToAnnotatedClass=true)
class PlatformSourceFilesIntegrationTest {
    private static final UUID MANAGER=UUID.fromString("f6de2084-5364-4130-87f9-4bc3d92321a0");
    private static final UUID REVIEWER=UUID.fromString("f6de2084-5364-4130-87f9-4bc3d92321a1");
    private static final UUID VIEWER=UUID.fromString("f6de2084-5364-4130-87f9-4bc3d92321a2");
    private static final UUID ORG=UUID.fromString("f6de2084-5364-4130-87f9-4bc3d92321a3");
    private static final UUID SUBMISSION=UUID.fromString("f6de2084-5364-4130-87f9-4bc3d92321a4");
    private static final UUID LICENSE=UUID.fromString("f6de2084-5364-4130-87f9-4bc3d92321a5");
    private static final byte[] TOTP="56789123456789123456".getBytes(StandardCharsets.US_ASCII);
    private static final String ROOT="/v1/platform/source-files";
    private static final byte[] CSV=("""
        record_key,period_year,metric,value,unit,geography
        vehicleA,2038,stock,15.125,vehicles,BR
        vehicleB,2038,stock,20.5,vehicles,BR
        """).getBytes(StandardCharsets.UTF_8);

    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    final HttpClient client=HttpClient.newHttpClient();

    @Test
    void licensedUploadParsesCandidateAndRevocationErasesOriginalAndDerivatives() throws Exception {
        String manager=actor(MANAGER),reviewer=actor(REVIEWER),viewer=actor(VIEWER);
        seed();

        assertEquals(401,get(ROOT+"/submissions/"+SUBMISSION,null).statusCode());
        assertEquals(403,get(ROOT+"/submissions/"+SUBMISSION,manager).statusCode());
        assertEquals(200,json(request("POST","/v1/compliance/session",manager,
                currentCode().getBytes(StandardCharsets.UTF_8),"application/json",null),200).path("verified").asBoolean()?200:500);

        assertEquals(200,request("POST","/v1/compliance/session",reviewer,
                currentCode().getBytes(StandardCharsets.UTF_8),"application/json",null).statusCode());
        assertEquals(200,request("POST","/v1/compliance/session",viewer,
                currentCode().getBytes(StandardCharsets.UTF_8),"application/json",null).statusCode());

        String upload=ROOT+"/submissions/"+SUBMISSION;
        var reqHeaders=Map.of("X-CarbonFlow-File-Request","1",
                "X-Source-Filename","test.csv","X-Source-Format","CSV");
        assertEquals(404,request("POST",upload,viewer,CSV,"application/octet-stream",
                reqHeaders).statusCode());
        assertEquals(422,request("POST",upload,manager,CSV,"application/octet-stream",
                Map.of("X-CarbonFlow-File-Request","1","X-Source-Filename","test.csv",
                    "X-Source-Format","PDF")).statusCode());
        assertEquals(422,request("POST",upload,manager,CSV,"application/octet-stream",
                Map.of("X-Source-Filename","test.csv","X-Source-Format","CSV")).statusCode());

        JsonNode result=json(request("POST",upload,manager,CSV,"application/octet-stream",reqHeaders),201);
        UUID object=UUID.fromString(result.path("id").asText());
        assertEquals("QUARANTINED",result.path("state").asText());
        assertEquals(CSV.length,result.path("bytesCount").asInt());
        assertFalse(result.path("usableForE8").asBoolean());
        assertEquals(64,result.path("sha256").asText().length());
        JsonNode replay=json(request("POST",upload,manager,CSV,"application/octet-stream",reqHeaders),201);
        assertEquals(object.toString(),replay.path("id").asText());

        byte[] ciphertext=database.read(c->first(c,
                "SELECT encrypted FROM app_platform_source_object WHERE id=?",
                r->r.getBytes(1),object));
        assertNotNull(ciphertext);
        assertEquals(CSV.length+16,ciphertext.length);
        assertFalse(java.util.Arrays.equals(ciphertext,CSV),"PG must not contain plaintext.");

        String parse=ROOT+"/objects/"+object+"/parse";
        JsonNode normalized=json(request("POST",parse,manager,new byte[0],null,null),200);
        assertEquals("NORMALIZED_CANDIDATE",normalized.path("state").asText(),normalized.toString());
        assertEquals(2,normalized.path("parsedRows").asInt());
        assertFalse(normalized.path("usableForE8").asBoolean());

        Integer count=database.read(c->first(c,"SELECT count(*) FROM app_platform_source_candidate WHERE object_id=?",
                r->r.getInt(1),object));
        assertEquals(2,count.intValue());
        assertEquals(404,request("POST",parse,viewer,new byte[0],null,null).statusCode());
        JsonNode preview=json(get(ROOT+"/objects/"+object+"/candidates",reviewer),200);
        assertEquals(2,preview.path("total").asInt());
        assertEquals(2,preview.path("samples").size());
        assertEquals(404,get(ROOT+"/objects/"+object+"/candidates",viewer).statusCode());
        String approval="{\"outcome\":\"APPROVED_FOR_SCOPE\",\"metric\":\"stock\",\"periodYear\":2038,"+
                "\"geography\":\"BR\",\"unit\":\"vehicles\",\"evidenceLocator\":\"Tabela BR 2038, linha 2\","+
                "\"reason\":\"Valores e unidades conferidos manualmente na publicacao, sem vinculo E8\"}";
        assertEquals(403,request("POST",ROOT+"/objects/"+object+"/assess",manager,
                approval.getBytes(StandardCharsets.UTF_8),"application/json",null).statusCode());
        JsonNode assessed=json(request("POST",ROOT+"/objects/"+object+"/assess",reviewer,
                approval.getBytes(StandardCharsets.UTF_8),"application/json",null),200);
        assertEquals("APPROVED_FOR_SCOPE",assessed.path("outcome").asText());
        assertEquals("BASELINE_BE",assessed.path("blockedCapability").asText());
        assertEquals("BR",assessed.path("geography").asText());
        assertFalse(json(get(ROOT+"/submissions/"+SUBMISSION,manager),200)
                .path("files").get(0).path("usableForE8").asBoolean());
        assertEquals(409,request("POST",ROOT+"/objects/"+object+"/assess",reviewer,
                approval.getBytes(StandardCharsets.UTF_8),"application/json",null).statusCode());

        database.transaction(c->update(c, """
            INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
            VALUES (?,2,'REVOKED',?,'Direitos revogados para o teste')
            """,LICENSE,REVIEWER));
        JsonNode suspended=json(get(ROOT+"/submissions/"+SUBMISSION,viewer),200);
        assertEquals("SUSPENDED",suspended.path("files").get(0).path("state").asText());
        assertFalse(suspended.path("files").get(0).path("licenseActive").asBoolean());
        assertEquals(0,suspended.path("files").get(0).path("parsedRows").asInt());
        assertNull(database.read(c->first(c,"SELECT encrypted FROM app_platform_source_object WHERE id=?",
                r->r.getBytes(1),object)));
        assertEquals(0,database.read(c->first(c,"SELECT count(*) FROM app_platform_source_candidate WHERE object_id=?",
                r->r.getInt(1),object)).intValue());
        assertEquals(404,request("POST",parse,manager,new byte[0],null,null).statusCode());
    }

    @Test
    void globalPublicLicenceAuthorisesUploadButGlobalParticularAuthorisationDoesNot() throws Exception {
        String manager=actor(MANAGER),reviewer=actor(REVIEWER);
        seed();
        database.transaction(c->{
            update(c,"DELETE FROM app_compliance_mfa WHERE identity_id=?",MANAGER);
            update(c,"DELETE FROM app_content_license_decision WHERE license_id=? AND version=2",LICENSE);
            return null;
        });
        assertEquals(200,request("POST","/v1/compliance/session",manager,
                currentCode().getBytes(StandardCharsets.UTF_8),"application/json",null).statusCode());
        String upload=ROOT+"/submissions/"+SUBMISSION;
        var hdr=Map.of("X-CarbonFlow-File-Request","1","X-Source-Filename","test.csv","X-Source-Format","CSV");

        UUID object=null;
        try {
            database.transaction(c->update(c,"UPDATE app_content_license SET organization_id=NULL WHERE id=?",LICENSE));
            assertEquals(403,request("POST",upload,manager,CSV,"application/octet-stream",hdr).statusCode(),
                "Global particular authorisation must not grant organisation rights.");

            database.transaction(c->update(c, """
                UPDATE app_content_license SET kind='PUBLIC_LICENSE',license_identifier='PUBLIC-DOMAIN-DECLARED',
                  legal_text_uri='https://example.org/legal',legal_text_sha256=?,evidence_uri='https://example.org/ev',
                  evidence_captured_at=statement_timestamp() WHERE id=?
                ""","b".repeat(64),LICENSE));
            JsonNode result=json(request("POST",upload,manager,CSV,"application/octet-stream",hdr),201);
            UUID created=UUID.fromString(result.path("id").asText());
            object=created;
            assertEquals(ORG,database.read(c->first(c,
                "SELECT organization_id FROM app_platform_source_object WHERE id=?",
                r->r.getObject(1,UUID.class),created)));

            database.transaction(c->update(c, """
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                VALUES (?,2,'REVOKED',?,'Direitos revogados para o teste')
                """,LICENSE,REVIEWER));
            assertEquals(403,request("POST",upload,manager,CSV,"application/octet-stream",hdr).statusCode());
        } finally {
            UUID finalObject=object;
            database.transaction(c->{
                update(c,"DELETE FROM app_content_license_decision WHERE license_id=? AND version=2",LICENSE);
                update(c,"DELETE FROM app_platform_source_object WHERE id=?",finalObject);
                update(c, """
                    UPDATE app_content_license SET organization_id=?,kind='PARTICULAR_AUTHORIZATION',
                      license_identifier=NULL,legal_text_uri=NULL,legal_text_sha256=NULL,evidence_uri=NULL,
                      evidence_captured_at=NULL WHERE id=?
                    """,ORG,LICENSE);
                return null;
            });
        }
    }

    private void seed() {
        database.transaction(c->{
            update(c, """
                INSERT INTO app_organization(id,environment,display_name)
                VALUES (?,'SANDBOX','Isolamento de testes de fontes')
                ON CONFLICT(id) DO NOTHING
                """,ORG);
            update(c, """
                INSERT INTO app_methodology_source_context
                  (methodology,methodology_edition,scenario,title,country,network_mode,
                   vehicle_category,charger_type,energy_system)
                VALUES ('DATAQ','1.0','BR_TABULAR_TEST','Teste de fonte tabular','BR',
                        'OPEN','LDV_BEV','DCFC','SIN')
                ON CONFLICT DO NOTHING
                """);
            update(c, """
                INSERT INTO app_methodology_source_need
                (code,methodology,methodology_edition,scenario,stage,kind,title,purpose,
                 source_id,source_edition,edition_policy,blocked_capability)
                VALUES('TEST-TABULAR-2038','DATAQ','1.0','BR_TABULAR_TEST','BASELINE','DATASET',
                       'Dados de demonstração do parser','Cenário de teste isolado',
                       'TEST-TABULAR-SOURCE-2038','2038','EXACT','BASELINE_BE')
                ON CONFLICT DO NOTHING
                """);
            update(c, """
                INSERT INTO app_source_version
                (source_id,edition,category,title,rights_holder,official_uri,origin)
                VALUES ('TEST-TABULAR-SOURCE-2038','2038','EXTERNAL_SOURCE',
                        'Fonte isolada de teste 2038','Autor fictício',
                        'https://example.org/publications/2038','EXTERNAL')
                ON CONFLICT DO NOTHING
                """);
            update(c, """
                INSERT INTO app_platform_source_submission
                (id,need_code,source_id,edition,category,title,rights_holder,publisher,
                 official_uri,reference_year,locator,metadata_fingerprint,state,
                 submitted_by,reviewed_by)
                VALUES (?, 'TEST-TABULAR-2038','TEST-TABULAR-SOURCE-2038','2038',
                  'EXTERNAL_SOURCE','Fonte isolada de teste 2038','Autor fictício','Editora de testes',
                  'https://example.org/publications/2038',2038,'Tabela teste 2038',?,
                  'METADATA_ACCEPTED',?,?)
                ON CONFLICT(id) DO NOTHING
                """,SUBMISSION,AccessSecrets.digest("unique-test-quarantine"),MANAGER,REVIEWER);
            update(c, """
                INSERT INTO app_content_license
                (id,source_id,edition,organization_id,submitted_by,environment,purpose,
                 authorization_ref,proof_sha256,starts_at,ends_at)
                VALUES (?,'TEST-TABULAR-SOURCE-2038','2038',?,?,'SANDBOX',
                        'AUTHORIZED_PROCESSING','Permissão de teste isolado',?,
                        '2024-01-01',statement_timestamp()+interval '1 hour')
                ON CONFLICT(id) DO NOTHING
                """,LICENSE,ORG,MANAGER,"a".repeat(64));
            update(c, """
                INSERT INTO app_content_license_decision
                  (license_id,version,outcome,decided_by,reason)
                VALUES (?,1,'APPROVED',?,'Autorização de teste independente')
                ON CONFLICT DO NOTHING
                """,LICENSE,REVIEWER);
            update(c, """
                INSERT INTO app_content_license_capability
                  (license_id,decision_version,operation)
                VALUES (?,1,'STORE_ORIGINAL'),(?,1,'EXTRACT_TEXT'),(?,1,'SHOW_EXCERPTS')
                ON CONFLICT DO NOTHING
                """,LICENSE,LICENSE,LICENSE);
            return null;
        });
    }

    private String actor(UUID id) {
        String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c, """
                INSERT INTO app_identity
                (id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Usuário de fonte','ACTIVE',statement_timestamp())
                ON CONFLICT(id) DO NOTHING
                """,id,id+"@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return token;
    }

    private String currentCode() {
        long counter=database.read(c->first(c,
                "SELECT floor(extract(epoch from statement_timestamp()) / 30)::bigint",
                r->r.getLong(1)));
        return "{\"code\":\""+ComplianceStaff.codeAt(TOTP,counter)+"\"}";
    }

    private HttpResponse<String> get(String path,String token) throws Exception {
        return request("GET",path,token,null,null,null);
    }

    private HttpResponse<String> request(String method,String path,String token,byte[] data,
            String contentType,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(token!=null)builder.header("Cookie",AccountAccess.COOKIE+"="+token);
        if(contentType!=null)builder.header("Content-Type",contentType);
        if(headers!=null)headers.forEach(builder::header);
        builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofByteArray(data));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response,int expected) throws Exception {
        assertEquals(expected,response.statusCode(),response.body());
        return mapper.readTree(response.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String,String> start() {
            var values=new HashMap<>(super.start());
            String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(TOTP);
            values.put("carbonflow.compliance.staff",
                    MANAGER+":"+secret+":"+ORG+";"+REVIEWER+":"+secret+":"+ORG+
                    ";"+VIEWER+":"+secret+":"+ORG);
            values.put("carbonflow.compliance.document-key",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            values.put("carbonflow.platform.source-readiness.viewers",
                    MANAGER+","+REVIEWER+","+VIEWER);
            values.put("carbonflow.platform.source-work.managers",MANAGER.toString());
            values.put("carbonflow.platform.source-technical.reviewers",MANAGER+","+REVIEWER);
            values.put("carbonflow.platform.source-files.encryption-key",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(AccessSecrets.digest("file-key-tests")));
            return values;
        }
    }
}
