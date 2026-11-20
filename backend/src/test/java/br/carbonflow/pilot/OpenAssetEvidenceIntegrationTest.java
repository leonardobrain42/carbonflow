package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** UX-03B: PDF evidence is linked to canonical asset revisions, never E8 or VVB. */
@QuarkusTest
@QuarkusTestResource(value=AccountAccessTest.Inbox.class,restrictToAnnotatedClass=true)
class OpenAssetEvidenceIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client=HttpClient.newHttpClient();
    private record Actor(UUID id,String cookie) {}
    private static String key(){return UUID.randomUUID().toString();}
    private static final String SITE="""
       {"name":"Estação Norte","municipality":"Brasília","state":"DF",
        "address":"Via Norte 100","operationalState":"PLANNED",
        "plannedOn":"2027-01-01","commissionedOn":null,
        "sourceReference":"Referencia textual - nao e arquivo"}
        """;
    private static String charger(String site){
        return "{\"siteCode\":\""+site+"\",\"chargerKind\":\"DCFC\","+
            "\"ratedPowerKw\":150,\"grid\":\"SIN\",\"topology\":\"GRID_ONLY\","+
            "\"timeZone\":\"America/Sao_Paulo\",\"sourceReference\":\"FICHA-123\"}";
    }
    private static final String VALID_REVIEW="""
        {"outcome":"DOCUMENT_CHECKED_INTERNAL",
         "reason":"Conferência documental interna da ficha e do código do equipamento."}
        """;
    private static final String PDF="%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n";

    @Test
    void twoStationsOneChargerEvidenceHasVersionedLinksEncryptedBytesAndIndependentReview()
        throws Exception {
        Actor owner=actor(),prep=actor(),reviewer=actor(),outsider=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        UUID prepGrant=grant(project,org,owner.id(),prep.id(),"PREP");
        grant(project,org,owner.id(),reviewer.id(),"REV");
        String root="/v1/projects/"+project+"/technical";
        String sites=root+"/open-sites",evidence=sites+"/evidence";
        assertEquals(200,call("PUT",sites+"/sites/ST-01",owner.cookie(),key(),"\"0\"",SITE).statusCode());
        assertEquals(200,call("PUT",sites+"/sites/ST-02",owner.cookie(),key(),"\"0\"",
            SITE.replace("Estação Norte","Estação Sul")).statusCode());
        assertEquals(200,call("PUT",root+"/open-monitoring/chargers/CH-01",owner.cookie(),key(),
            "\"0\"",charger("ST-01")).statusCode());
        String north=evidence+"/SITE/ST-01",south=evidence+"/SITE/ST-02",
            hardware=evidence+"/CHARGER/CH-01";
        assertEquals(401,call("GET",north,null,null,null,null).statusCode());
        assertEquals(404,call("GET",north,outsider.cookie(),null,null,null).statusCode());
        assertEquals(404,call("GET",evidence+"/SITE/ST-UNKNOWN",owner.cookie(),null,null,null).statusCode());
        assertEquals(0,json(call("GET",north,owner.cookie(),null,null,null)).path("documents").size());

        byte[] pdf=PDF.getBytes(StandardCharsets.US_ASCII);
        String receipt=key();
        var first=upload(north+"/revisions/1",prep.cookie(),receipt,"LOCATION",
            "Planta documental Norte 2027","página 2","proof-north.pdf",pdf);
        assertEquals(201,first.statusCode(),first.body());
        JsonNode firstProof=json(first);
        assertEquals("ST-01",firstProof.path("subjectCode").asText());
        assertEquals("SITE",firstProof.path("subjectType").asText());
        assertEquals(1,firstProof.path("subjectVersion").asInt());
        assertEquals("PENDING_REVIEW",firstProof.path("state").asText());
        assertFalse(firstProof.path("usableForE8").asBoolean());
        assertEquals(64,firstProof.path("sha256").asText().length());
        assertFalse(first.body().contains("encrypted"));
        assertEquals(firstProof,json(upload(north+"/revisions/1",prep.cookie(),receipt,
            "LOCATION","Planta documental Norte 2027","página 2","proof-north.pdf",pdf)));
        assertEquals(409,upload(north+"/revisions/1",prep.cookie(),receipt,"LOCATION",
            "Referência diferente 2027","página 2","proof-north.pdf",pdf).statusCode());
        assertEquals(400,upload(north+"/revisions/1",owner.cookie(),key(),"UNKNOWN",
            "Fonte normativa indevida","página 2","proof-north.pdf",pdf).statusCode());
        assertEquals(400,upload(north+"/revisions/1",owner.cookie(),key(),"LOCATION",
            "Planta Norte 2027","página 2","proof-north.pdf",
            "documento nao pdf".getBytes(StandardCharsets.UTF_8)).statusCode());
        assertEquals(412,upload(north+"/revisions/2",owner.cookie(),key(),"LOCATION",
            "Planta Norte 2027","página 2","proof-north.pdf",pdf).statusCode());

        var second=upload(south+"/revisions/1",owner.cookie(),key(),"LOCATION",
            "Planta documental Sul 2027","página 3","proof-south.pdf",pdf);
        assertEquals(201,second.statusCode(),second.body());
        assertNotEquals(firstProof.path("id").asText(),json(second).path("id").asText());
        var hardwareProof=upload(hardware+"/revisions/1",owner.cookie(),key(),"SPECIFICATION",
            "Ficha fabricante DCFC 2027","página 4","charger-proof.pdf",
            (PDF+"power 150 kW\n").getBytes(StandardCharsets.US_ASCII));
        assertEquals(201,hardwareProof.statusCode(),hardwareProof.body());
        assertEquals(400,upload(hardware+"/revisions/1",owner.cookie(),key(),"LOCATION",
            "Prova incorreta categoria","página 4","charger-proof.pdf",pdf).statusCode());
        assertEquals(2L,count("app_project_evidence_blob",project)); // same PDF dedup across two sites
        assertEquals(3L,count("app_open_asset_evidence",project));
        var stored=database.read(c->first(c,"""
            SELECT encrypted FROM app_project_evidence_blob WHERE project_id=?
            AND encode(sha256,'hex')=?
            """,r->r.getBytes(1),project,firstProof.path("sha256").asText()));
        assertFalse(new String(stored,StandardCharsets.UTF_8).contains(PDF));

        JsonNode northList=json(call("GET",north,owner.cookie(),null,null,null));
        assertEquals(1,northList.path("documents").size());
        assertTrue(northList.path("canUpload").asBoolean());
        JsonNode southList=json(call("GET",south,owner.cookie(),null,null,null));
        assertEquals(1,southList.path("documents").size());
        assertFalse(southList.path("documents").toString().contains(firstProof.path("id").asText()));
        JsonNode staffList=json(call("GET",north,reviewer.cookie(),null,null,null));
        assertFalse(staffList.path("canUpload").asBoolean());
        assertTrue(staffList.path("canReview").asBoolean());
        String review=evidence+"/documents/"+firstProof.path("id").asText()+"/review";
        assertEquals(404,call("POST",review,owner.cookie(),null,"\"0\"",VALID_REVIEW).statusCode());
        assertEquals(404,call("POST",review,prep.cookie(),null,"\"0\"",VALID_REVIEW).statusCode());
        assertEquals(404,call("POST",review,outsider.cookie(),null,"\"0\"",VALID_REVIEW).statusCode());
        var checked=call("POST",review,reviewer.cookie(),null,"\"0\"",VALID_REVIEW);
        assertEquals(200,checked.statusCode(),checked.body());
        assertEquals(1,json(checked).path("version").asInt());
        assertEquals("CHECKED_INTERNAL",
            json(call("GET",north,owner.cookie(),null,null,null)).at("/documents/0/state").asText());
        assertFalse(json(call("GET",north,owner.cookie(),null,null,null))
            .at("/documents/0/usableForE8").asBoolean());

        assertEquals(200,call("PUT",sites+"/sites/ST-01",owner.cookie(),key(),"\"1\"",
            SITE.replace("Via Norte 100","Via Norte 101")).statusCode());
        JsonNode stale=json(call("GET",north,owner.cookie(),null,null,null));
        assertEquals(2,stale.path("currentVersion").asInt());
        assertEquals("STALE",stale.at("/documents/0/state").asText());
        assertEquals(409,call("POST",review,reviewer.cookie(),null,"\"1\"",VALID_REVIEW).statusCode());
        assertEquals(412,upload(north+"/revisions/1",prep.cookie(),key(),"LOCATION",
            "Nova planta Norte 2027","página 2","proof-north.pdf",pdf).statusCode());
        var correction=upload(north+"/revisions/2",prep.cookie(),key(),"LOCATION",
            "Nova planta Norte 2027","página 2","proof-north.pdf",pdf);
        assertEquals(201,correction.statusCode(),correction.body());
        assertEquals(2,json(correction).path("subjectVersion").asInt());
        assertEquals("PENDING_REVIEW",json(correction).path("state").asText());
        assertEquals(4L,count("app_open_asset_evidence",project));
        assertEquals(1L,count("app_open_asset_evidence_review",project)); // append-only
        database.transaction(c->{update(c,"""
            UPDATE app_project_collaborator_grant SET revoked_at=statement_timestamp(),revoked_by=?
            WHERE id=?
            """,owner.id(),prepGrant);return null;});
        assertEquals(404,call("GET",north,prep.cookie(),null,null,null).statusCode());
        assertEquals(404,upload(north+"/revisions/2",prep.cookie(),key(),"LOCATION",
            "Nova planta Norte 2027","página 2","proof-north.pdf",pdf).statusCode());
        assertEquals(404,call("GET",north.replace(project,"PIL-SYN-BR-001"),
            owner.cookie(),null,null,null).statusCode());
    }

    private long count(String table,String project){
        return database.read(c->first(c,"SELECT count(*) FROM "+table+" WHERE project_id=?",
            r->r.getLong(1),project));
    }
    private UUID grant(String project,UUID org,UUID owner,UUID actor,String role){
        UUID id=UUID.randomUUID();
        database.transaction(c->{update(c,"""
            INSERT INTO app_project_collaborator_grant
            (id,project_id,organization_id,environment,identity_id,granted_by,
             role,activities,valid_until)
            VALUES (?,?,?,'SANDBOX',?,?,?,ARRAY['MONITORING']::text[],
              statement_timestamp()+interval '3 days')
            """,id,project,org,actor,owner,role);return null;});
        return id;
    }
    private Actor actor(){
        UUID id=UUID.randomUUID();String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Provas UX-03B','ACTIVE',statement_timestamp())
                """,id,id+"@example.invalid");
            update(c,"""
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return new Actor(id,AccountAccess.COOKIE+"="+token);
    }
    private UUID organization(UUID owner){
        UUID id=UUID.randomUUID();
        database.transaction(c->{
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES(?,'SANDBOX','Provas')",id);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES(?,?,'DRAFT_OWNER')",id,owner);
            return null;
        });return id;
    }
    private String project(Actor owner,UUID org)throws Exception{
        var created=call("POST","/v1/projects",owner.cookie(),key(),null,
            "{\"organizationId\":\""+org+"\",\"title\":\"Projeto provas\"}");
        assertEquals(201,created.statusCode(),created.body());
        return json(created).path("projectId").asText();
    }
    private HttpResponse<String> upload(String path,String cookie,String key,
         String purpose,String ref,String locator,String filename,byte[] bytes)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        builder.header("Cookie",cookie).header("Content-Type","application/pdf");
        builder.header("Idempotency-Key",key).header("X-Evidence-Purpose",purpose);
        builder.header("X-Evidence-Reference",ref).header("X-Evidence-Locator",locator);
        builder.header("X-File-Name",filename);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> call(String method,String path,String cookie,String key,
         String match,String data)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(match!=null)builder.header("If-Match",match);
        if(data!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,data==null?
            HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data))
            .build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response)throws Exception{return mapper.readTree(response.body());}
}
