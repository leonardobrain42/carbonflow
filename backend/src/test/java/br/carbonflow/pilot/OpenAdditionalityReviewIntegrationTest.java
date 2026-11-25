package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * CF-OPEN-010 (#99): independent internal opinion per additionality criterion. It is a record of a human opinion;
 * it never concludes additionality, never admits a route and never makes anything usable for E8.
 */
@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class OpenAdditionalityReviewIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    private static final String BODY="""
        {"outcome":"CHECKED_INTERNAL",
         "rationale":"Parecer interno independente sobre a lista positiva, sem aprovar a adicionalidade.",
         "evidenceReference":"Registro interno de conferência #1"}
        """;

    private void grantRev(String project,UUID org,UUID owner,UUID actor) {
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_collaborator_grant
                (id,project_id,organization_id,environment,identity_id,granted_by,role,activities,valid_until)
                VALUES (?,?,?,'SANDBOX',?,?,'REV',ARRAY['ADDITIONALITY']::text[],statement_timestamp()+interval '3 days')
                """,UUID.randomUUID(),project,org,actor,owner);
            return null;
        });
    }

    private void guideRevision(String project,UUID org,UUID author,int version) {
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_technical_guide_revision
                (project_id,organization_id,environment,guide_id,version,actor_id,answers)
                VALUES (?,?,'SANDBOX','additionality',?,?,'{"route":"POSITIVE_LIST"}'::jsonb)
                """,project,org,version,author);
            return null;
        });
    }

    @Test
    void reviewerRecordsVersionedIndependentOpinionsThatNeverConcludeAdditionality() throws Exception {
        var fx=new OpenE8TestFixture(database,mapper,base);
        var s=fx.openProject("2026-01-01");
        var author=fx.actor();     // REV who also wrote the guide: not independent
        var reviewer=fx.actor();   // independent REV
        grantRev(s.project(),s.org(),s.owner().id(),author.id());
        grantRev(s.project(),s.org(),s.owner().id(),reviewer.id());
        guideRevision(s.project(),s.org(),author.id(),1);
        String url="/v1/projects/"+s.project()+"/technical/open-additionality";
        String requirements="/v1/projects/"+s.project()+"/technical/requirements";
        assertEquals("AWAITING_NORMATIVE_EVALUATION",requirement(fx.json(fx.call("GET",requirements,s.owner().cookie(),null,null)),"VMD0049_ROUTE").path("state").asText());
        String chronology=url+"/CHRONOLOGY/reviews";
        String positive=url+"/POSITIVE_LIST/reviews";

        JsonNode before=fx.json(fx.call("GET",url,reviewer.cookie(),null,null));
        for(JsonNode criterion:before.path("criteria"))
            assertEquals("PENDING_HUMAN_OPINION",criterion.at("/review/state").asText());

        // Who may post.
        assertEquals(404,fx.postMethod(positive,s.owner().cookie(),OpenE8TestFixture.key(),"\"0\"",BODY).statusCode(),"owner is not a reviewer");
        assertEquals(404,fx.postMethod(positive,s.outside().cookie(),OpenE8TestFixture.key(),"\"0\"",BODY).statusCode());
        assertEquals(404,fx.postMethod(url+"/NOT_A_CRITERION/reviews",reviewer.cookie(),OpenE8TestFixture.key(),"\"0\"",BODY).statusCode());
        assertEquals(403,fx.postMethod(positive,author.cookie(),OpenE8TestFixture.key(),"\"0\"",BODY).statusCode(),
            "the author of the guide cannot review it");
        // Input.
        assertEquals(422,fx.postMethod(positive,reviewer.cookie(),OpenE8TestFixture.key(),"\"0\"",
            BODY.replace("CHECKED_INTERNAL","APPROVED")).statusCode());
        assertEquals(422,fx.postMethod(positive,reviewer.cookie(),OpenE8TestFixture.key(),"\"0\"",
            BODY.replace("Parecer interno independente sobre a lista positiva, sem aprovar a adicionalidade.","curto")).statusCode());
        assertEquals(412,fx.postMethod(positive,reviewer.cookie(),OpenE8TestFixture.key(),"\"1\"",BODY).statusCode());

        String key=OpenE8TestFixture.key();
        var created=fx.postMethod(positive,reviewer.cookie(),key,"\"0\"",BODY);
        assertEquals(201,created.statusCode(),created.body());
        assertTrue(created.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode view=fx.json(created);
        assertFalse(view.path("concludesAdditionality").asBoolean());
        assertFalse(view.path("usableForE8").asBoolean());
        for(JsonNode criterion:view.path("criteria")) assertFalse(criterion.path("concludes").asBoolean());
        JsonNode done=criterion(view,"POSITIVE_LIST");
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",done.at("/review/state").asText());
        assertEquals(1,done.at("/review/version").asInt());
        assertFalse(done.at("/review/stale").asBoolean());
        assertEquals("PENDING_HUMAN_OPINION",criterion(view,"CHRONOLOGY").at("/review/state").asText(),"other criteria stay pending");

        var projected=requirement(fx.json(fx.call("GET",requirements,s.owner().cookie(),null,null)),"VMD0049_ROUTE");
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",projected.path("state").asText());
        assertFalse(projected.path("admitsE8").asBoolean());
        assertEquals("AWAITING_REVIEW",projected.path("group").asText());

        // Idempotency: the same command replays, another content under the same key conflicts.
        var replay=fx.postMethod(positive,reviewer.cookie(),key,"\"0\"",BODY);
        assertEquals(200,replay.statusCode(),replay.body());
        assertEquals(view,fx.json(replay));
        assertEquals(409,fx.postMethod(positive,reviewer.cookie(),key,"\"0\"",BODY.replace("#1","#2")).statusCode());

        // A new version needs the current ETag and does not rewrite the previous one.
        assertEquals(412,fx.postMethod(positive,reviewer.cookie(),OpenE8TestFixture.key(),"\"0\"",BODY.replace("#1","#3")).statusCode());
        var returned=fx.postMethod(positive,reviewer.cookie(),OpenE8TestFixture.key(),"\"1\"",
            BODY.replace("CHECKED_INTERNAL","RETURNED").replace("#1","#4"));
        assertEquals(201,returned.statusCode(),returned.body());
        assertEquals("RETURNED_NOT_ADMITTED",criterion(fx.json(returned),"POSITIVE_LIST").at("/review/state").asText());
        assertEquals(2,criterion(fx.json(returned),"POSITIVE_LIST").at("/review/version").asInt());
        assertEquals(2L,database.read(c->PilotSql.first(c,"SELECT count(*) FROM app_open_additionality_opinion WHERE project_id=?",
            r->r.getLong(1),s.project())).longValue());

        // The opinion belongs to the declaration it saw: a new guide revision makes it stale, not silently valid.
        var chronologyOpinion=fx.postMethod(chronology,reviewer.cookie(),OpenE8TestFixture.key(),"\"0\"",BODY.replace("lista positiva","cronologia"));
        assertEquals(201,chronologyOpinion.statusCode(),chronologyOpinion.body());
        guideRevision(s.project(),s.org(),s.owner().id(),2);
        JsonNode stale=fx.json(fx.call("GET",url,reviewer.cookie(),null,null));
        for(String code:new String[]{"POSITIVE_LIST","CHRONOLOGY"}) {
            assertEquals("STALE_NOT_ADMITTED",criterion(stale,code).at("/review/state").asText(),code);
            assertTrue(criterion(stale,code).at("/review/stale").asBoolean(),code);
        }
        assertEquals("PENDING_HUMAN_OPINION",criterion(stale,"REGULATORY_SURPLUS").at("/review/state").asText());

        assertEquals("RECHECK_REQUIRED",requirement(fx.json(fx.call("GET",requirements,s.owner().cookie(),null,null)),"VMD0049_ROUTE").path("state").asText());
        assertEquals("AWAITING_NORMATIVE_EVALUATION",requirement(fx.json(fx.call("GET",requirements,s.owner().cookie(),null,null)),"REGULATORY_SURPLUS").path("state").asText());

        // Records are immutable.
        assertThrows(RuntimeException.class,()->database.transaction(c->{
            update(c,"UPDATE app_open_additionality_opinion SET outcome='RETURNED' WHERE project_id=?",s.project());
            return null;
        }));
    }

    private static JsonNode requirement(JsonNode view,String id) {
        for(JsonNode item:view.path("requirements")) if(id.equals(item.path("requirementId").asText())) return item;
        throw new AssertionError("requirement "+id+" in "+view);
    }

    private static JsonNode criterion(JsonNode opinion,String code) {
        for(JsonNode criterion:opinion.path("criteria")) if(code.equals(criterion.path("code").asText())) return criterion;
        throw new AssertionError("criterion "+code);
    }
}
