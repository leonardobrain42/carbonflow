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
 * BL-DATA-01D: accepted candidates are applied to the catalog in bulk (model + alias, classification only when supplied with
 * evidence), idempotently, without overwriting conflicts and all-or-nothing. Candidates and decisions are seeded directly
 * (the queue rules have their own test); the fixture is synthetic and nothing is admitted.
 */
@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class FleetCatalogApplicationIntegrationTest {
    private static final String API="/v1/platform/fleet/alias-candidates";
    private static final String PERIOD="2031-09";
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    private UUID candidate(UUID by,String name,String key,String level,String brand,String model,String version,Integer line,String outcome) {
        UUID id=UUID.randomUUID();
        String v=version==null?"null":"\""+version+"\"";
        String matches="[{\"brand\":\""+brand+"\",\"model\":\""+model+"\",\"version\":"+v+",\"category\":\"Médio\",\"pbevLine\":7}]";
        database.transaction(c->{
            PilotSql.update(c,"INSERT INTO app_fleet_alias_candidate(id,observation_period,literal_name,record_key,level,pbev_source_sha256,"
                +"matches,reason,imported_by,senatran_source_sha256) VALUES (?,?,?,?,?,?,?::jsonb,'Casamento de texto, sem decisão.',?,?)",
                id,PERIOD,name,key,level,"e".repeat(64),matches,by,"f".repeat(64));
            if(outcome!=null)PilotSql.update(c,"INSERT INTO app_fleet_alias_candidate_decision(candidate_id,outcome,selected_pbev_line,reason,decided_by,decision_basis) "
                +"VALUES (?,?,?,'Decisão humana de teste, sintética.',?,'INDEPENDENT')",id,outcome,"ACCEPTED".equals(outcome)?7:null,by);
            return null;
        });
        return id;
    }

    private static String body(UUID org,String... items){return "{\"organizationId\":\""+org+"\",\"items\":["+String.join(",",items)+"]}";}
    private static String it(UUID id,String classification){
        return "{\"candidateId\":\""+id+"\""+(classification==null?"":",\"classification\":"+classification)+"}";
    }

    @Test
    void acceptedCandidatesBecomeModelsAndAliasesWithoutNamesClassifyingAnything() throws Exception {
        var fx=new OpenE8TestFixture(database,mapper,base);
        var s=fx.openProject("2025-08-01");
        String mgr=s.staffManager().cookie();
        UUID by=s.staffReviewer().id();
        UUID pending=candidate(by,"ALFA ZZ-1","ZZ-ALFA-1","EXACT","MARCAZ","ALFA","1.0",7,null);
        UUID rejected=candidate(by,"ALFA ZZ-2","ZZ-ALFA-2","EXACT","MARCAZ","ALFA","1.0",7,"REJECTED");
        UUID a=candidate(by,"ALFA ZZ-3","ZZ-ALFA-3","EXACT","MARCAZ","ALFA","1.0",7,"ACCEPTED");
        UUID b=candidate(by,"ALFA ZZ-4","ZZ-ALFA-4","MODEL_ONLY","MARCAZ","ALFA","1.0",7,"ACCEPTED"); // same PBEV line: model is reused
        UUID c=candidate(by,"BETA ZZ-5","ZZ-BETA-5","EXACT","MARCAZ","BETA",null,7,"ACCEPTED");       // no version in the PBEV
        String evidence="{\"vehicleClass\":\"LDV\",\"propulsion\":\"BEV\",\"reason\":\"Confirmado na ficha do fabricante.\",\"evidence\":\"Ficha do fabricante, p.3\"}";

        // Only accepted decisions apply; the rest is reported, never silently skipped, and nothing is written.
        var bad=fx.json(fx.postMethod(API+"/applications/dry-run",mgr,OpenE8TestFixture.key(),"\"0\"",body(s.org(),it(pending,null),it(rejected,null),it(a,null))));
        assertEquals(2,bad.path("failed").asInt(),bad.toString());
        assertEquals("CANDIDATO_NAO_ACEITO",bad.path("results").get(0).path("code").asText());
        assertEquals(0L,count("app_fleet_alias_application"));

        // Dry run shows the diff of the whole batch (including the reuse inside the batch) and writes nothing.
        var dry=fx.json(fx.postMethod(API+"/applications/dry-run",mgr,OpenE8TestFixture.key(),"\"0\"",body(s.org(),it(a,evidence),it(b,null),it(c,null))));
        assertTrue(dry.path("dryRun").asBoolean());
        assertFalse(dry.path("applied").asBoolean());
        assertEquals(3,dry.path("planned").asInt(),dry.toString());
        assertTrue(dry.path("results").get(0).path("actions").toString().contains("CREATE_MODEL"));
        assertTrue(dry.path("results").get(0).path("actions").toString().contains("CLASSIFY_LDV_BEV"));
        assertTrue(dry.path("results").get(1).path("actions").toString().contains("REUSE_MODEL"),dry.toString());
        assertTrue(dry.path("results").get(2).path("actions").toString().contains("MODEL_STAYS_PENDING_CLASSIFICATION"));
        assertFalse(dry.path("usableForE8").asBoolean());
        assertEquals(0L,count("app_fleet_alias_application"));
        assertEquals(0L,scalar("SELECT count(*) FROM app_fleet_model WHERE manufacturer='MARCAZ'"));

        // Apply: the classified model carries the evidence; the others stay explicitly pending (names never classify).
        var applied=fx.postMethod(API+"/applications",mgr,OpenE8TestFixture.key(),"\"0\"",body(s.org(),it(a,evidence),it(b,null),it(c,null)));
        assertEquals(201,applied.statusCode(),applied.body());
        assertEquals(3,fx.json(applied).path("planned").asInt());
        assertEquals(2L,scalar("SELECT count(*) FROM app_fleet_model WHERE manufacturer='MARCAZ'"));
        assertEquals("LDV/BEV",database.transaction(x->PilotSql.first(x,
            "SELECT vehicle_class||'/'||propulsion FROM app_fleet_model_effective WHERE manufacturer='MARCAZ' AND model_name='ALFA'",r->r.getString(1))));
        assertEquals("UNKNOWN/UNKNOWN",database.transaction(x->PilotSql.first(x,
            "SELECT vehicle_class||'/'||propulsion FROM app_fleet_model_effective WHERE manufacturer='MARCAZ' AND model_name='BETA'",r->r.getString(1))));
        assertEquals("SEM VERSÃO NO PBEV",database.transaction(x->PilotSql.first(x,
            "SELECT variant FROM app_fleet_model WHERE manufacturer='MARCAZ' AND model_name='BETA'",r->r.getString(1))));
        assertEquals(3L,scalar("SELECT count(*) FROM app_fleet_model_alias WHERE organization_id=? AND observation_year=2031 AND record_key LIKE 'ZZ-%'", s.org()));

        // Resume: running the same file again is a no-op; a half-new file only applies what is new.
        UUID d=candidate(by,"ALFA ZZ-6","ZZ-ALFA-6","EXACT","MARCAZ","ALFA","1.0",7,"ACCEPTED");
        var again=fx.json(fx.postMethod(API+"/applications",mgr,OpenE8TestFixture.key(),"\"0\"",body(s.org(),it(a,evidence),it(b,null),it(c,null),it(d,null))));
        assertEquals(3,again.path("alreadyApplied").asInt(),again.toString());
        assertEquals(1,again.path("planned").asInt());
        assertEquals(4L,count("app_fleet_alias_application"));

        // Conflicts are reported and never overwritten: the key already maps to another model, so the whole batch is refused.
        UUID e=candidate(by,"GAMA ZZ-7","ZZ-ALFA-3","EXACT","MARCAZ","GAMA","1.0",7,"ACCEPTED");
        var conflict=fx.postMethod(API+"/applications",mgr,OpenE8TestFixture.key(),"\"0\"",body(s.org(),it(e,null)));
        assertEquals(422,conflict.statusCode(),conflict.body());
        assertEquals("ALIAS_CONFLITANTE",fx.json(conflict).path("results").get(0).path("code").asText());
        assertFalse(fx.json(conflict).path("applied").asBoolean());
        assertEquals(0L,scalar("SELECT count(*) FROM app_fleet_model WHERE model_name='GAMA'"));

        // A different classification on an already classified model is not overwritten here.
        String other="{\"vehicleClass\":\"LDV\",\"propulsion\":\"PHEV\",\"reason\":\"Outra leitura da mesma ficha.\",\"evidence\":\"Ficha, p.4\"}";
        UUID f=candidate(by,"ALFA ZZ-8","ZZ-ALFA-8","EXACT","MARCAZ","ALFA","1.0",7,"ACCEPTED");
        var diverge=fx.json(fx.postMethod(API+"/applications",mgr,OpenE8TestFixture.key(),"\"0\"",body(s.org(),it(f,other))));
        assertEquals("CLASSIFICACAO_DIVERGENTE",diverge.path("results").get(0).path("code").asText(),diverge.toString());
        // Unknown classification is not an input; evidence is mandatory.
        var noEvidence=fx.json(fx.postMethod(API+"/applications/dry-run",mgr,OpenE8TestFixture.key(),"\"0\"",body(s.org(),
            it(f,"{\"vehicleClass\":\"LDV\",\"propulsion\":\"BEV\",\"reason\":\"Motivo suficiente aqui.\",\"evidence\":\"\"}"))));
        assertEquals("CLASSIFICACAO_INVALIDA",noEvidence.path("results").get(0).path("code").asText());

        // Authority: not a platform manager of the organization, and not signed in.
        assertNotEquals(201,fx.postMethod(API+"/applications",s.owner().cookie(),OpenE8TestFixture.key(),"\"0\"",body(s.org(),it(f,null))).statusCode());
        var anonymous=fx.postMethod(API+"/applications","",OpenE8TestFixture.key(),"\"0\"",body(s.org(),it(f,null)));
        assertTrue(anonymous.statusCode()==401||anonymous.statusCode()==403||anonymous.statusCode()==404,anonymous.body());
        // Immutable record.
        assertThrows(RuntimeException.class,()->database.transaction(x->PilotSql.update(x,"DELETE FROM app_fleet_alias_application")));

        database.transaction(x->{ // synthetic fixture: removed as superuser, the tables are append-only
            PilotSql.update(x,"SET LOCAL session_replication_role = replica");
            PilotSql.update(x,"DELETE FROM app_fleet_alias_application WHERE observation_year=2031");
            PilotSql.update(x,"DELETE FROM app_fleet_model_alias WHERE observation_year=2031 AND record_key LIKE 'ZZ-%'");
            PilotSql.update(x,"DELETE FROM app_fleet_model_classification WHERE model_id IN (SELECT id FROM app_fleet_model WHERE manufacturer='MARCAZ')");
            PilotSql.update(x,"DELETE FROM app_fleet_model WHERE manufacturer='MARCAZ'");
            PilotSql.update(x,"DELETE FROM app_fleet_alias_candidate_decision WHERE candidate_id IN (SELECT id FROM app_fleet_alias_candidate WHERE observation_period=?)",PERIOD);
            PilotSql.update(x,"DELETE FROM app_fleet_alias_candidate WHERE observation_period=?",PERIOD);
            return null;
        });
    }

    private long scalar(String sql,Object... args){
        Long value=database.transaction(x->PilotSql.first(x,sql,r->r.getLong(1),args));
        return value;
    }

    private long count(String table){
        return scalar("SELECT count(*) FROM "+table);
    }
}
