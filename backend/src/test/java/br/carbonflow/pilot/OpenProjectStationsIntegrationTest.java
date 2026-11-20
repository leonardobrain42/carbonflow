package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
class OpenProjectStationsIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client=HttpClient.newHttpClient();
    private static String key(){return UUID.randomUUID().toString();}
    private record Actor(UUID id,String cookie){}
    private static final String SITE1 = """
        {"name":"Estação Norte","municipality":"Brasília","state":"DF",
         "address":"Via norte 100","operationalState":"PLANNED","plannedOn":"2027-01-01",
         "commissionedOn":null,"sourceReference":"PLANO-NORTE-1"}
        """;
    private static final String SITE2 = """
        {"name":"Estação Sul","municipality":"Brasília","state":"DF",
         "address":"Via sul 200","operationalState":"INSTALLED","plannedOn":"2027-01-01",
         "commissionedOn":null,"sourceReference":"PLANO-SUL-2"}
        """;
    private static String charger(String site,String power,String kind){
        return "{\"siteCode\":\""+site+"\",\"chargerKind\":\""+kind+
            "\",\"ratedPowerKw\":"+power+",\"grid\":\"SIN\",\"topology\":\"GRID_ONLY\","+
            "\"timeZone\":\"America/Sao_Paulo\",\"sourceReference\":\"DOC-CHARGER-123\"}";
    }

    @Test
    void recordsTwoSitesAndBoundaryPinsCanonicalVersionsWithoutAdmittingEnergy() throws Exception {
        Actor owner=actor(),stranger=actor();
        String project=project(owner,organization(owner.id()));
        String root="/v1/projects/"+project+"/technical";
        String sites=root+"/open-sites",chargers=root+"/open-monitoring/chargers";
        assertEquals(401,call("GET",sites,null,null,null,null).statusCode());
        assertEquals(404,call("GET",sites,stranger.cookie(),null,null,null).statusCode());
        assertEquals(404,call("GET","/v1/projects/PIL-SYN-BR-001/technical/open-sites",
            owner.cookie(),null,null,null).statusCode());
        assertEquals("NO_SELECTION",json(call("GET",sites,owner.cookie(),null,null,null))
            .at("/boundary/state").asText());

        String s1=sites+"/sites/ST-01",s2=sites+"/sites/ST-02";
        String receipt=key();
        var saved=call("PUT",s1,owner.cookie(),receipt,"\"0\"",SITE1);
        assertEquals(200,saved.statusCode(),saved.body());
        assertEquals(1,json(saved).path("version").asInt());
        assertEquals(json(saved),json(call("PUT",s1,owner.cookie(),receipt,"\"0\"",SITE1)));
        assertEquals(409,call("PUT",s1,owner.cookie(),receipt,"\"0\"",
            SITE1.replace("Norte","Oeste")).statusCode());
        assertEquals(412,call("PUT",s1,owner.cookie(),key(),"\"0\"",SITE1).statusCode());
        assertEquals(428,call("PUT",s2,owner.cookie(),key(),null,SITE2).statusCode());
        assertEquals(200,call("PUT",s2,owner.cookie(),key(),"\"0\"",SITE2).statusCode());
        assertEquals(422,call("PUT",sites+"/sites/INVALID",owner.cookie(),key(),"\"0\"",
            SITE1.replace("\"PLANNED\"","\"ACTIVE\"")).statusCode());

        assertEquals(200,call("PUT",chargers+"/CH-01",owner.cookie(),key(),"\"0\"",
            charger("ST-01","150.000","DCFC")).statusCode());
        assertEquals(200,call("PUT",chargers+"/CH-02",owner.cookie(),key(),"\"0\"",
            charger("ST-02","180.000","DCFC")).statusCode());
        String picked="{\"chargers\":[{\"code\":\"CH-01\",\"version\":1},{\"code\":\"CH-02\",\"version\":1}]}";
        String boundaryKey=key();
        var boundary=call("PUT",sites+"/boundary",owner.cookie(),boundaryKey,"\"0\"",picked);
        assertEquals(200,boundary.statusCode(),boundary.body());
        assertEquals("DRAFT_REVIEW_REQUIRED",json(boundary).path("state").asText());
        assertFalse(json(boundary).path("usableForE8").asBoolean());
        assertEquals(2,json(boundary).path("selected").size());
        assertEquals(json(boundary),json(call("PUT",sites+"/boundary",owner.cookie(),boundaryKey,"\"0\"",picked)));
        assertEquals(409,call("PUT",sites+"/boundary",owner.cookie(),boundaryKey,"\"0\"",
            "{\"chargers\":[]}").statusCode());
        assertEquals(412,call("PUT",sites+"/boundary",owner.cookie(),key(),"\"0\"",picked).statusCode());
        assertEquals(422,call("PUT",sites+"/boundary",owner.cookie(),key(),"\"1\"",
            "{\"chargers\":[{\"code\":\"CH-01\",\"version\":1},{\"code\":\"CH-01\",\"version\":1}]}").statusCode());
        assertEquals(200,call("PUT",s1,owner.cookie(),key(),"\"1\"",
            SITE1.replace("Via norte 100","Via norte 101")).statusCode());
        var stale=json(call("GET",sites,owner.cookie(),null,null,null));
        assertEquals("STALE",stale.at("/boundary/state").asText());
        assertFalse(stale.at("/boundary/usableForE8").asBoolean());
        assertEquals(2,stale.path("sites").size());
        assertEquals(2,stale.path("chargers").size());

        var renewed=call("PUT",sites+"/boundary",owner.cookie(),key(),"\"1\"",picked);
        assertEquals(200,renewed.statusCode(),renewed.body());
        assertEquals(2,json(renewed).path("version").asInt());
        assertEquals(2,json(renewed).at("/selected/0/siteVersion").asInt());
        assertEquals(2L,count("app_open_project_boundary_revision",project));
        assertEquals(4L,count("app_open_project_boundary_charger",project));
        assertEquals(3L,count("app_open_site_revision",project));

        assertEquals(200,call("PUT",chargers+"/CH-02",owner.cookie(),key(),"\"1\"",
            charger("ST-02","180.000","AC")).statusCode());
        assertEquals("STALE",json(call("GET",sites,owner.cookie(),null,null,null))
            .at("/boundary/state").asText());
        String unsupported="{\"chargers\":[{\"code\":\"CH-01\",\"version\":1},{\"code\":\"CH-02\",\"version\":2}]}";
        assertEquals(422,call("PUT",sites+"/boundary",owner.cookie(),key(),"\"2\"",
            unsupported).statusCode());
        assertEquals(200,call("PUT",sites+"/boundary",owner.cookie(),key(),"\"2\"",
            "{\"chargers\":[{\"code\":\"CH-01\",\"version\":1}]}").statusCode());
        assertEquals("DRAFT_REVIEW_REQUIRED",json(call("GET",sites,owner.cookie(),null,null,null))
            .at("/boundary/state").asText());
        assertEquals(404,call("PUT",sites+"/boundary",stranger.cookie(),key(),"\"3\"",
            "{\"chargers\":[]}").statusCode());
        assertEquals(0L,count("pilot_import_file",project));
    }

    @Test
    void reviewerCannotWriteAndRevokedPreparerLosesReadAndWrite() throws Exception {
        Actor owner=actor(),preparer=actor(),reviewer=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        UUID prep=grant(project,org,owner.id(),preparer.id(),"PREP");
        grant(project,org,owner.id(),reviewer.id(),"REV");
        String sites="/v1/projects/"+project+"/technical/open-sites";
        assertEquals(200,call("GET",sites,preparer.cookie(),null,null,null).statusCode());
        assertEquals(200,call("GET",sites,reviewer.cookie(),null,null,null).statusCode());
        assertEquals(404,call("PUT",sites+"/sites/ST-01",reviewer.cookie(),key(),"\"0\"",
            SITE1).statusCode());
        assertEquals(200,call("PUT",sites+"/sites/ST-01",preparer.cookie(),key(),"\"0\"",
            SITE1).statusCode());
        database.transaction(c->{update(c,"""
            UPDATE app_project_collaborator_grant SET revoked_at=statement_timestamp(),revoked_by=?
            WHERE id=?
            """,owner.id(),prep);return null;});
        assertEquals(404,call("GET",sites,preparer.cookie(),null,null,null).statusCode());
        assertEquals(404,call("PUT",sites+"/boundary",preparer.cookie(),key(),"\"0\"",
            "{\"chargers\":[]}").statusCode());
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
            VALUES (?, ?, ?, 'SANDBOX', ?, ?, ?, ARRAY['MONITORING']::text[],
             statement_timestamp()+interval '3 days')
            """,id,project,org,actor,owner,role);return null;});
        return id;
    }
    private Actor actor(){
        UUID id=UUID.randomUUID();String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Estação teste','ACTIVE',statement_timestamp())
                """,id,id+"@example.invalid");
            update(c,"""
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES(?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return new Actor(id,AccountAccess.COOKIE+"="+token);
    }
    private UUID organization(UUID owner){
        UUID id=UUID.randomUUID();
        database.transaction(c->{
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES(?,'SANDBOX','Estações teste')",id);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES(?,?,'DRAFT_OWNER')",id,owner);
            return null;
        });return id;
    }
    private String project(Actor owner,UUID org) throws Exception{
        var created=call("POST","/v1/projects",owner.cookie(),key(),null,
            "{\"organizationId\":\""+org+"\",\"title\":\"Duas estações\"}");
        assertEquals(201,created.statusCode(),created.body());
        return json(created).path("projectId").asText();
    }
    private HttpResponse<String> call(String method,String path,String cookie,String key,
        String match,String data) throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(match!=null)builder.header("If-Match",match);
        if(data!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,data==null?
            HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data))
            .build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> result) throws Exception{return mapper.readTree(result.body());}
}
