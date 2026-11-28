package br.carbonflow.pilot;

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
 * BL-EX-01: the EX ANTE fleet reference for the PD needs no monitoring period, no energy binding and no MR (BL-T01), is
 * staff-only (BL-T06), versioned with replay/concurrency control (BL-T07) and follows the observation-year rule
 * OPEN-BASELINE-FLEET-YEAR@1. The EVR/rating snapshots are the synthetic fixture of the monitored handoff test; nothing is admitted.
 */
@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class OpenBaselineFleetSelectionIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    private static final String API="/v1/platform/open-baseline-fleet-selections";
    private OpenE8TestFixture fx;
    private OpenE8TestFixture.Scenario s;

    private java.net.http.HttpResponse<String> put(String path,String cookie,String match,String payload) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/json").header("If-Match",match)
            .header("Idempotency-Key",OpenE8TestFixture.key())
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build();
        return java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private String sectionSha(String project,int version){
        return database.transaction(c->PilotSql.first(c,
            "SELECT sha256 FROM app_pd_section_version WHERE project_id=? AND section_id='baseline' AND version=?",
            r->r.getString(1),project,version));
    }

    @Test
    void aPdBaselineVersionIsFrozenAgainstTheExactReferenceAndOnlyItsOwnSectionGoesStale() throws Exception {
        fx=new OpenE8TestFixture(database,mapper,base);
        s=fx.openProject("2025-08-01");
        UUID rating2025=fx.seedScopedRatingCatalog(s.org(),s.staffManager().id(),s.staffReviewer().id(),2025)[0];
        String technical="/v1/projects/"+s.project()+"/technical";
        String owner=s.owner().cookie();
        String sectionPath=technical+"/pd-sections/baseline";
        String text="Linha de base ex ante com a frota de referência nomeada, sem registro de monitoramento.";

        // BL-T01/T15: a version written BEFORE any reference cites none and is not pinned.
        assertEquals(200,put(technical+"/guides/baseline",owner,"\"0\"","{\"scenario\":\"Comparável A\"}").statusCode());
        var first=put(sectionPath,owner,"\"0\"","{\"guideVersion\":1,\"narrative\":\""+text+"\"}");
        assertEquals(200,first.statusCode(),first.body());
        assertEquals(0,database.transaction(c->PilotSql.first(c,
            "SELECT count(*) FROM app_pd_baseline_dependency WHERE project_id=?",r->r.getInt(1),s.project())).intValue());
        assertFalse(fx.json(fx.call("GET",sectionPath+"/versions/1/download",owner,null,null)).has("exAnteReference"));

        // Staff publishes reference version 1: the stored version is only informed, not altered nor flagged outdated.
        assertEquals(201,fx.postMethod(API,s.staffManager().cookie(),OpenE8TestFixture.key(),"\"0\"",
            body("2025-09-15",rating2025,WHY)).statusCode());
        JsonNode notCited=baselineSection(fx.json(fx.call("GET",technical+"/pd-sections",owner,null,null))).path("baselineDependency");
        assertEquals("REFERENCE_NOT_CITED",notCited.path("state").asText(),notCited.toString());
        assertFalse(notCited.path("outdated").asBoolean());

        // Version 2 of the section cites the exact reference: frozen in the hashed artifact and in the immutable pin.
        var second=put(sectionPath,owner,"\"1\"","{\"guideVersion\":1,\"narrative\":\""+text+"\"}");
        assertEquals(200,second.statusCode(),second.body());
        assertEquals(2,fx.json(second).path("number").asInt(),"The reference appeared: a new version is needed to cite it.");
        JsonNode artifact2=mapper.readTree(fx.json(fx.call("GET",sectionPath+"/versions/2/download",owner,null,null)).toString());
        assertEquals(1,artifact2.path("exAnteReference").path("version").asInt(),artifact2.toString());
        assertEquals(64,artifact2.path("exAnteReference").path("selectionSha256").asText().length());
        assertFalse(artifact2.path("exAnteReference").path("usableForE8").asBoolean());
        assertFalse(artifact2.toString().contains("AFEC") || artifact2.toString().contains("MPG"));
        assertEquals(1,database.transaction(c->PilotSql.first(c,
            "SELECT selection_version FROM app_pd_baseline_dependency WHERE project_id=? AND section_version=2",
            r->r.getInt(1),s.project())).intValue());
        assertEquals("REFERENCE_CURRENT",baselineSection(fx.json(fx.call("GET",technical+"/pd-sections",owner,null,null)))
            .path("baselineDependency").path("state").asText());
        // Idempotent: the same text with the same reference is the same version, no third one.
        assertEquals(2,fx.json(put(sectionPath,owner,"\"1\"","{\"guideVersion\":1,\"narrative\":\""+text+"\"}"))
            .path("number").asInt());
        String sha2=sectionSha(s.project(),2);

        // BL-T08: a NEW reference version makes only the baseline section outdated; version 2 stays intact.
        assertEquals(201,fx.postMethod(API,s.staffManager().cookie(),OpenE8TestFixture.key(),"\"1\"",
            body("2025-10-01",rating2025,WHY)).statusCode());
        JsonNode sections=fx.json(fx.call("GET",technical+"/pd-sections",owner,null,null));
        JsonNode dep=baselineSection(sections).path("baselineDependency");
        assertEquals("REFERENCE_SUPERSEDED",dep.path("state").asText(),dep.toString());
        assertTrue(dep.path("outdated").asBoolean());
        assertEquals(1,dep.path("pinnedVersion").asInt());
        assertEquals(2,dep.path("currentVersion").asInt());
        int withDependency=0;
        for(JsonNode section:sections) if(!section.path("baselineDependency").isNull()) withDependency++;
        assertEquals(1,withDependency,"Only the dependent section is flagged.");
        assertEquals(sha2,sectionSha(s.project(),2),"BL-T15: the stored version and its hash are never rewritten.");
        JsonNode review=null;
        for(JsonNode item:fx.json(fx.call("GET",technical+"/pd-reviews",owner,null,null)))
            if("baseline".equals(item.path("id").asText())) review=item;
        assertNotNull(review);
        assertTrue(review.path("outdated").asBoolean(),review.toString());
        assertFalse(review.path("actions").toString().contains("SUBMITTED"),"An outdated reference blocks submission.");

        // A new version citing the current reference restores it; version 2 still cites reference 1.
        var third=put(sectionPath,owner,"\"2\"","{\"guideVersion\":1,\"narrative\":\""+text+"\"}");
        assertEquals(200,third.statusCode(),third.body());
        assertEquals(3,fx.json(third).path("number").asInt());
        assertEquals(2,mapper.readTree(fx.json(fx.call("GET",sectionPath+"/versions/3/download",owner,null,null)).toString())
            .path("exAnteReference").path("version").asInt());
        assertEquals(1,mapper.readTree(fx.json(fx.call("GET",sectionPath+"/versions/2/download",owner,null,null)).toString())
            .path("exAnteReference").path("version").asInt());
        assertEquals(sha2,sectionSha(s.project(),2));
        assertEquals("REFERENCE_CURRENT",baselineSection(fx.json(fx.call("GET",technical+"/pd-sections",owner,null,null)))
            .path("baselineDependency").path("state").asText());
        assertEquals(404,fx.call("GET",technical+"/pd-sections",s.outside().cookie(),null,null).statusCode());
    }

    private static JsonNode requirement(JsonNode view,String id) {
        for(JsonNode item:view.path("requirements")) if(id.equals(item.path("requirementId").asText())) return item;
        throw new AssertionError("Requisito ausente: "+id+" em "+view);
    }

    private static JsonNode baselineSection(JsonNode sections) {
        for(JsonNode section:sections) if("baseline".equals(section.path("guide").path("id").asText())) return section;
        throw new AssertionError("Seção baseline ausente: "+sections);
    }

    private String body(String date,UUID rating,String rationale){
        return "{\"projectId\":\""+s.project()+"\",\"referenceDate\":\""+date+"\",\"ratingSnapshotId\":\""+rating
            +"\",\"rationale\":\""+rationale+"\"}";
    }
    private static final String WHY="Referência de frota para a fundamentação do PD, sem período monitorado.";

    @Test
    void exAnteReferenceNeedsNoMonitoringAndStaysPreparatory() throws Exception {
        fx=new OpenE8TestFixture(database,mapper,base);
        s=fx.openProject("2025-08-01");
        UUID rating2025=fx.seedScopedRatingCatalog(s.org(),s.staffManager().id(),s.staffReviewer().id(),2025)[0];
        long monitoring=database.transaction(c->PilotSql.first(c,
            "SELECT (SELECT count(*) FROM app_open_e8_fleet_selection WHERE project_id=?)+(SELECT count(*) FROM app_open_e8_input_binding_candidate WHERE project_id=?)",
            r->r.getLong(1),s.project(),s.project()));
        assertEquals(0,monitoring,"BL-T01: the project has no period, energy binding or monitored selection");

        // Options expose the resolved credit start, not an invented year.
        var options=fx.json(fx.call("GET",API+"/options",s.staffManager().cookie(),null,null));
        JsonNode mine=null;
        for(JsonNode item:options) if(s.project().equals(item.path("projectId").asText())) mine=item;
        assertNotNull(mine,options.toString());
        assertEquals("2025-08-01",mine.path("creditStart").asText());
        assertTrue(mine.path("calendarGate").isNull(),mine.toString());
        assertEquals(0,mine.path("currentVersion").asInt());

        // BL-EX-02, BL-T02: before any selection the project sees an explained empty state, never zeros or an invented year.
        String ctxPath="/v1/projects/"+s.project()+"/technical/baseline/fleet-context";
        JsonNode empty=fx.json(fx.call("GET",ctxPath,s.owner().cookie(),null,null));
        assertEquals("NO_EX_ANTE_REFERENCE",empty.path("state").asText(),empty.toString());
        assertTrue(empty.path("current").isNull());
        assertEquals(0,empty.path("history").size());
        assertFalse(empty.path("usableForE8").asBoolean());
        assertEquals("2025-08-01",empty.path("creditStart").asText());
        assertTrue(empty.path("gaps").toString().contains("NO_EX_ANTE_REFERENCE_CONFIRMED"),empty.toString());
        assertTrue(empty.path("gaps").get(0).path("owner").asText().length()>3);
        // BL-EX-03, BL-T10: before any selection the PD baseline section names no ex ante reference (no object, no zero).
        String pdPath="/v1/projects/"+s.project()+"/technical/pd-sections";
        assertTrue(baselineSection(fx.json(fx.call("GET",pdPath,s.owner().cookie(),null,null))).path("baseline").path("exAnte").isNull());
        // BL-EX-03, diagnóstico por ato: without a reference the PD act names the gap, never zero, never blocking.
        String reqPath="/v1/projects/"+s.project()+"/technical/requirements";
        JsonNode reqEmpty=requirement(fx.json(fx.call("GET",reqPath,s.owner().cookie(),null,null)),"EX_ANTE_FLEET_REFERENCE");
        assertEquals("NOT_ADMITTED",reqEmpty.path("state").asText(),reqEmpty.toString());
        assertEquals("AWAITING_SOURCE",reqEmpty.path("group").asText());
        assertEquals("PD_EX_ANTE",reqEmpty.path("acts").get(0).asText());
        assertFalse(reqEmpty.path("blocksExAntePd").asBoolean());
        assertFalse(reqEmpty.path("admitsE8").asBoolean());
        JsonNode energyAct=requirement(fx.json(fx.call("GET",reqPath,s.owner().cookie(),null,null)),"ED_EC");
        assertEquals("MR_MONITORED",energyAct.path("acts").get(0).asText(),"Measured energy is the monitored act.");
        assertFalse(energyAct.path("blocksExAntePd").asBoolean());
        // Not a member of the project, and not signed in: the project is not even confirmed to exist.
        assertEquals(404,fx.call("GET",ctxPath,s.outside().cookie(),null,null).statusCode());
        assertTrue(fx.call("GET",ctxPath,"",null,null).statusCode()==401||fx.call("GET",ctxPath,"",null,null).statusCode()==404);

        // Version 1: a reference date in 2025 selects the 2025 snapshot; the observation year comes from the DECLARED date.
        String key=OpenE8TestFixture.key();
        var first=fx.postMethod(API,s.staffManager().cookie(),key,"\"0\"",body("2025-09-15",rating2025,WHY));
        assertEquals(201,first.statusCode(),first.body());
        JsonNode v1=fx.json(first);
        assertEquals("PREPARATORY_NOT_ADMITTED",v1.path("state").asText());
        assertEquals("PD_EX_ANTE",v1.path("purpose").asText());
        assertFalse(v1.path("usableForE8").asBoolean());
        assertFalse(v1.path("stale").asBoolean(),v1.toString());
        assertEquals(2025,v1.path("observationYear").asInt());
        assertEquals("2025-09-15",v1.path("referenceDate").asText());
        assertEquals("2025-08-01",v1.path("creditStart").asText());
        assertEquals("OPEN-BASELINE-FLEET-YEAR@1",v1.path("yearRule").asText());
        assertEquals(1,v1.path("version").asInt());
        assertTrue(v1.path("gates").toString().contains("EX_ANTE_REFERENCE_IS_NOT_AN_E8_INPUT"));
        assertTrue(v1.path("gates").toString().contains("EVR_AND_RATINGS_NOT_NORMATIVELY_ADMITTED"));
        assertEquals(64,v1.path("selectionSha256").asText().length());

        // BL-T07: the same key and body replays the same record; the same key with another body is refused.
        var replay=fx.postMethod(API,s.staffManager().cookie(),key,"\"0\"",body("2025-09-15",rating2025,WHY));
        assertEquals(201,replay.statusCode(),replay.body());
        assertEquals(v1.path("id").asText(),fx.json(replay).path("id").asText());
        var otherBody=fx.postMethod(API,s.staffManager().cookie(),key,"\"0\"",body("2025-09-16",rating2025,WHY));
        assertEquals(409,otherBody.statusCode(),otherBody.body());
        // The identical reference under a fresh key is a conflict (nothing changed), and a stale If-Match is refused.
        var same=fx.postMethod(API,s.staffManager().cookie(),OpenE8TestFixture.key(),"\"1\"",body("2025-09-15",rating2025,WHY));
        assertEquals(409,same.statusCode(),same.body());
        var stale=fx.postMethod(API,s.staffManager().cookie(),OpenE8TestFixture.key(),"\"0\"",body("2025-10-01",rating2025,WHY));
        assertEquals(412,stale.statusCode(),stale.body());

        // Observation year rule: a 2026 reference date cannot use the 2025 snapshot, and nothing is recorded.
        var wrongYear=fx.postMethod(API,s.staffManager().cookie(),OpenE8TestFixture.key(),"\"1\"",body("2026-02-01",rating2025,WHY));
        assertEquals(409,wrongYear.statusCode(),wrongYear.body());
        // Invalid input is a 422, never a guessed date.
        var badDate=fx.postMethod(API,s.staffManager().cookie(),OpenE8TestFixture.key(),"\"1\"",body("15/09/2025",rating2025,WHY));
        assertEquals(422,badDate.statusCode(),badDate.body());
        var shortWhy=fx.postMethod(API,s.staffManager().cookie(),OpenE8TestFixture.key(),"\"1\"",body("2025-10-01",rating2025,"curta"));
        assertEquals(422,shortWhy.statusCode(),shortWhy.body());

        // BL-T06: the project owner and a collaborator are not platform staff; no write happens.
        var byOwner=fx.postMethod(API,s.owner().cookie(),OpenE8TestFixture.key(),"\"1\"",body("2025-10-01",rating2025,WHY));
        assertNotEquals(201,byOwner.statusCode(),byOwner.body());
        var anonymous=fx.postMethod(API,"",OpenE8TestFixture.key(),"\"1\"",body("2025-10-01",rating2025,WHY));
        assertTrue(anonymous.statusCode()==401||anonymous.statusCode()==403||anonymous.statusCode()==404,anonymous.body());

        // Version 2 (a new reference date) supersedes version 1: the history is kept, the old one is stale and unusable.
        var second=fx.postMethod(API,s.staffManager().cookie(),OpenE8TestFixture.key(),"\"1\"",body("2025-10-01",rating2025,WHY));
        assertEquals(201,second.statusCode(),second.body());
        assertEquals(2,fx.json(second).path("version").asInt());
        JsonNode history=fx.json(fx.call("GET",API+"/"+s.project(),s.staffManager().cookie(),null,null));
        assertEquals(2,history.size(),history.toString());
        assertEquals(2,history.get(0).path("version").asInt());
        assertFalse(history.get(0).path("stale").asBoolean());
        assertTrue(history.get(1).path("stale").asBoolean());
        assertEquals("STALE_NOT_ADMITTED",history.get(1).path("state").asText());
        assertTrue(history.get(1).path("gates").toString().contains("SELECTION_VERSION_SUPERSEDED"));

        // BL-EX-02: the project reads metadata only; the current version is the latest, the older one is stale; still not an E8 input.
        var seen=fx.call("GET",ctxPath,s.owner().cookie(),null,null);
        assertEquals(200,seen.statusCode(),seen.body());
        assertTrue(seen.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode view=fx.json(seen);
        assertEquals("PREPARATORY_NOT_ADMITTED",view.path("state").asText(),view.toString());
        assertEquals(2,view.path("current").path("version").asInt());
        assertEquals(2,view.path("history").size());
        assertEquals("STALE_NOT_ADMITTED",view.path("history").get(1).path("state").asText());
        assertFalse(view.path("usableForE8").asBoolean());
        assertEquals(0,view.path("gaps").size(),view.toString());
        assertFalse(seen.body().contains("rationale\":null"));
        // BL-EX-03, BL-T01/T10: the PD names the EXACT current version without any period, energy or monitored selection.
        var pdSeen=fx.call("GET",pdPath,s.owner().cookie(),null,null);
        assertEquals(200,pdSeen.statusCode(),pdSeen.body());
        JsonNode pdBaseline=baselineSection(fx.json(pdSeen)).path("baseline");
        JsonNode exAnte=pdBaseline.path("exAnte");
        assertEquals("ABSENT",pdBaseline.path("situation").asText(),"No fallback: the monitored reading stays absent.");
        assertEquals(2,exAnte.path("version").asInt(),exAnte.toString());
        assertEquals(2025,exAnte.path("observationYear").asInt());
        assertEquals(view.path("current").path("selectionSha256").asText(),exAnte.path("selectionSha256").asText());
        assertEquals("PREPARATORY_NOT_ADMITTED",exAnte.path("state").asText());
        assertFalse(exAnte.path("stale").asBoolean());
        assertFalse(exAnte.path("usableForE8").asBoolean());
        assertFalse(pdBaseline.path("usableForE8").asBoolean());
        assertFalse(pdBaseline.path("admitsBaseline").asBoolean());
        assertFalse(pdBaseline.path("marksDocumentReady").asBoolean());
        assertFalse(exAnte.toString().contains("AFEC") || exAnte.toString().contains("MPG"),"No licensed value is copied.");
        assertEquals(404,fx.call("GET",pdPath,s.outside().cookie(),null,null).statusCode());
        JsonNode reqView=fx.json(fx.call("GET",reqPath,s.owner().cookie(),null,null));
        JsonNode reqRef=requirement(reqView,"EX_ANTE_FLEET_REFERENCE");
        assertEquals("SOURCE_CANDIDATE",reqRef.path("state").asText(),reqRef.toString());
        assertTrue(reqRef.path("reason").asText().contains("versão 2"),reqRef.toString());
        assertTrue(reqRef.path("reason").asText().contains("não alimenta o E8"));
        assertFalse(reqRef.path("blocksExAntePd").asBoolean());
        assertEquals(1,reqRef.path("acts").size());
        assertEquals("PD_EX_ANTE",reqView.path("actSummary").get(0).path("act").asText());
        assertTrue(reqView.path("actSummary").get(0).path("requirements").asInt()>=1);
        assertEquals(404,fx.call("GET",reqPath,s.outside().cookie(),null,null).statusCode());
        assertTrue(database.transaction(c->PilotSql.first(c,
            "SELECT count(*) FROM app_project_technical_access_event WHERE action='VIEW_OPEN_BASELINE_FLEET_CONTEXT' AND outcome='PERMITTED'",
            r->r.getLong(1)))>=2,"the project-side reads are audited");

        // Immutable and never touching the monitored handoff (V64).
        assertThrows(RuntimeException.class,()->database.transaction(c->PilotSql.update(c,
            "UPDATE app_open_baseline_fleet_selection SET rationale='alterado depois do fato registrado' WHERE project_id=?",s.project())));
        assertThrows(RuntimeException.class,()->database.transaction(c->PilotSql.update(c,
            "DELETE FROM app_open_baseline_fleet_selection WHERE project_id=?",s.project())));
        assertEquals(0L,(long)database.transaction(c->PilotSql.first(c,
            "SELECT count(*) FROM app_open_e8_fleet_selection WHERE project_id=?",r->r.getLong(1),s.project())));
    }
}
