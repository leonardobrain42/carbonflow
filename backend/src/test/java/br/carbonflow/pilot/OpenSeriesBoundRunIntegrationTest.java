package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
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
 * Roteiro #124, vertical PE rehearsal (SYNTHETIC data, internal engineering evidence only): the project's hourly EFel of
 * Y2-M1 is DERIVED from the platform series (V113 -> V60 binding, CO2_ONLY, GWP=1) and carried through review, E8 input
 * binding, fleet hand-off, IR/Dy and the numeric attempt. Nothing is admitted.
 */
@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class OpenSeriesBoundRunIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    private OpenE8TestFixture fx;
    private OpenE8TestFixture.Scenario s;

    private static final String DY_SOURCE="E2E-DY-REGISTRY";
    private static final String PLAN_ITEM="{\"source\":\"Registro interno de atualização\",\"responsible\":\"Equipe técnica\","
        +"\"procedure\":\"Procedimento de atualização anual descrito para o teste.\"}";
    private static final String EX_ANTE="{\"evr\":"+PLAN_ITEM+",\"afec\":"+PLAN_ITEM+",\"mpg\":"+PLAN_ITEM+",\"fuel\":"+PLAN_ITEM
        +",\"rationale\":\"Plano ex ante de atualização dos quatro parâmetros no segundo ano.\"}";

    private String key(){return OpenE8TestFixture.key();}
    private String runs(String period){
        return "/v1/projects/"+s.project()+"/technical/open-monitoring/dossiers/"+period+"/e8-runs";
    }
    private String evidence(String metric){
        return "{\"metric\":\""+metric+"\",\"updatedOn\":\"2024-03-10\",\"validFrom\":\"2024-01-01\","
            +"\"validUntil\":\"2024-12-31\",\"evidenceLocator\":\"Registro de atualização "+metric+"\"}";
    }
    private JsonNode post(String path,String cookie,String match,String body,int status) throws Exception {
        var response=fx.postMethod(path,cookie,key(),match,body);
        assertEquals(status,response.statusCode(),response.body());
        return fx.json(response);
    }
    private JsonNode recordIr(String period,String match) throws Exception {
        return post("/v1/platform/open-ir-assessments/"+s.project(),s.staffManager().cookie(),match,
            "{\"period\":\""+period+"\",\"frequency\":\"ANNUAL\",\"basis\":\"PROJECT_SPECIFIC\","
            +"\"rationale\":\"Atualização anual declarada para teste integrado, sem admissão.\"}",201);
    }
    private static BigDecimal dec(JsonNode node){return new BigDecimal(node.asText());}

    private static final String MCTI_SOURCE = "MCTI-SIN-DESPACHO", MCTI_EDITION = "2026-janout-7f8e2292";
    private static final String FACTOR = "0.5439000000000001"; // 16 decimals, as the real MCTI file

    private UUID series(UUID owner, String firstUtc, String secondUtc) throws Exception {
        UUID license = UUID.randomUUID(), edition = UUID.randomUUID();
        return database.transaction(c -> {
            update(c, "INSERT INTO app_content_license(id,source_id,edition,submitted_by,environment,purpose,authorization_ref,proof_sha256,starts_at) "
                    + "VALUES (?,?,?,?,'SANDBOX','AUTHORIZED_PROCESSING','Licença sintética de teste',?,'2026-01-01T00:00:00Z')", license, MCTI_SOURCE, MCTI_EDITION, owner, "a".repeat(64));
            update(c, "INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason) VALUES (?,1,'DECLARED_INTERNAL',?,'Declaração sintética de teste.')", license, owner);
            update(c, "INSERT INTO app_grid_series_edition(id,source_id,edition,license_id,license_decision_version,license_basis,workbook_sha256,content_sha256,"
                    + "gas,original_unit,target_unit,data_year,timezone,hour_convention,row_count,quality,time_issue_count,imported_by) "
                    + "VALUES (?,?,?,?,1,'OWNER_INTERNAL',?,?,'CO2','tCO2/MWh','tCO2/MWh',2026,'America/Sao_Paulo','HOUR_ENDING_1_24',2,'{}'::jsonb,0,?)",
                    edition, MCTI_SOURCE, MCTI_EDITION, license, "b".repeat(64), UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), owner);
            String[] starts = {firstUtc, secondUtc};
            for (int i = 0; i < 2; i++)
                update(c, "INSERT INTO app_grid_series_value(edition_id,month,day,hour_label,cell,value_text,value_original,value_target,interval_start_local,"
                        + "interval_start_utc,interval_end_utc) VALUES (?,2,1,?,?,?,?,?,'2024-02-01T00:00',?::timestamptz,?::timestamptz + interval '1 hour')",
                        edition, i + 1, "A" + i, FACTOR, new BigDecimal(FACTOR), new BigDecimal(FACTOR), starts[i], starts[i]);
            return edition;
        });
    }

    @Test
    void peFollowsTheSeriesBoundCandidateEndToEndAndNothingIsAdmitted() throws Exception {
        fx=new OpenE8TestFixture(database,mapper,base);
        s=fx.openProject("2023-01-01");
        fx.preparePeriod(s,"Y1-M1",2023,2);
        UUID seriesId=series(s.owner().id(),"2024-02-01T00:00:00Z","2024-02-01T01:00:00Z");
        fx.preparePeriod(s,"Y2-M1",2024,2,(sc,dossierUrl,code,energy)->bindAndReview(fx,sc,dossierUrl,code,energy,seriesId));
        fx.preparePeriod(s,"Y2-M2",2024,3);
        // BE: the fossil factor comes from the fuel catalog by reference (CO2_ONLY, GWP = 1), not from a file candidate.
        registerFuelDatum("2.2",2022,"2020-01-01","2023-12-31");
        registerFuelDatum("2.25",2023,"2024-01-01",null);
        var threeGas=fx.postMethod("/v1/platform/open-parameter-bindings",s.staffManager().cookie(),key(),"\"0\"",
            "{\"projectId\":\""+s.project()+"\",\"parameterKind\":\"EF_FUEL\",\"vintageYear\":2023,"
            +"\"catalog\":{\"fuelId\":\"GASOLINE_C\",\"boundary\":\"CO2_CH4_N2O\",\"scope\":\"TTW\",\"date\":\"2023-06-01\"}}");
        assertEquals(422,threeGas.statusCode(),threeGas.body());
        var typed=fx.postMethod("/v1/platform/open-parameter-bindings",s.staffManager().cookie(),key(),"\"0\"",
            "{\"projectId\":\""+s.project()+"\",\"parameterKind\":\"EF_FUEL\",\"vintageYear\":2023,\"declaredValue\":\"0.009\","
            +"\"catalog\":{\"fuelId\":\"GASOLINE_C\",\"boundary\":\"CO2_ONLY\",\"scope\":\"TTW\",\"date\":\"2023-06-01\"}}");
        assertEquals(422,typed.statusCode(),"a typed number never rides with a catalog reference: "+typed.body());
        for(int year:new int[]{2023,2024}) {
            fx.pinIrDy(s.project(),s.org(),s.staffManager().id(),year);
            var pinned=fx.postMethod("/v1/platform/open-parameter-bindings",s.staffManager().cookie(),key(),"\"0\"",
                "{\"projectId\":\""+s.project()+"\",\"parameterKind\":\"EF_FUEL\",\"vintageYear\":"+year+","
                +"\"catalog\":{\"fuelId\":\"GASOLINE_C\",\"boundary\":\"CO2_ONLY\",\"scope\":\"TTW\",\"date\":\""+year+"-06-01\"}}");
            assertEquals(201,pinned.statusCode(),pinned.body());
            assertEquals("CATALOG_REFERENCE",fx.json(pinned).at("/valueKind").asText());
            assertEquals("tCO2e/USgal",fx.json(pinned).at("/normalizedUnit").asText());
        }
        // The owner authored the energy binding, so the attempt is run by a different PREP collaborator (segregation).
        var prep=fx.actor();
        fx.grant(s.project(),s.org(),s.owner().id(),prep.id(),"PREP");

        // IR: year 1 ex ante plan + initial review, year 2 annual observed update with dated evidence.
        JsonNode plan=post("/v1/projects/"+s.project()+"/technical/open-ir-ex-ante-plan",s.owner().cookie(),"\"0\"",EX_ANTE,201);
        JsonNode y1=recordIr("Y1-M1","\"0\"");
        post("/v1/platform/open-ir-assessments/"+s.project()+"/"+y1.path("id").asText()+"/review",s.staffReviewer().cookie(),"\"1\"",
            "{\"assessmentSha256\":\""+y1.path("assessmentSha256").asText()+"\",\"outcome\":\"INITIAL_BASELINE_CHECKED\","
            +"\"annualUpdateBasis\":\"PLAN_ONLY\",\"evidenceLocator\":\"Plano ex ante e referências do ano 1\","
            +"\"reason\":\"Parecer inicial independente, sem afirmar atualização observada.\","
            +"\"initialPlan\":{\"evr\":\"MATCH: atualização anual do EVR conforme o plano ex ante.\","
            +"\"afec\":\"MATCH: atualização anual do AFEC conforme o plano ex ante.\","
            +"\"mpg\":\"MATCH: atualização anual do MPG conforme o plano ex ante.\","
            +"\"fuel\":\"MATCH: atualização anual do fator fóssil conforme o plano ex ante.\"},"
            +"\"exAntePlanId\":\""+plan.path("id").asText()+"\",\"exAntePlanVersion\":"+plan.path("version").asInt()
            +",\"exAntePlanSha256\":\""+plan.path("planSha256").asText()+"\"}",201);
        JsonNode y2=recordIr("Y2-M1","\"0\"");
        post("/v1/platform/open-ir-assessments/"+s.project()+"/"+y2.path("id").asText()+"/review",s.staffReviewer().cookie(),"\"1\"",
            "{\"assessmentSha256\":\""+y2.path("assessmentSha256").asText()+"\",\"outcome\":\"CHECKED_INTERNAL\","
            +"\"annualUpdateBasis\":\"OBSERVED_UPDATE\",\"evidenceLocator\":\"Registros de atualização do ano 2\","
            +"\"reason\":\"Atualização observada conferida internamente, sem admissão normativa.\","
            +"\"priorAssessmentId\":\""+y1.path("id").asText()+"\",\"temporalEvidence\":["
            +evidence("EVR")+","+evidence("AFEC")+","+evidence("MPG")+","+evidence("EF_FUEL")+"]}",201);

        // Without a reviewed Dy the period is not calculated.
        JsonNode before=post(runs("Y2-M1"),prep.cookie(),"\"0\"","{}",201);
        assertEquals("BLOCKED",before.path("state").asText());
        assertEquals("MISSING_FOR_PROJECT_YEAR",OpenE8TestFixture.calcGate(before,"DY").path("state").asText());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",OpenE8TestFixture.calcGate(before,"IR_STRUCTURED").path("state").asText());

        // Dy of year 2: positive, licensed, itemized in the year-1 window, independently reviewed.
        UUID license=UUID.randomUUID();
        database.transaction(c->{
            PilotSql.update(c,"""
                INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,official_uri,origin)
                VALUES (?,'1','EXTERNAL_SOURCE','Registro fictício do E2E','Teste','https://example.invalid/e2e','EXTERNAL')
                ON CONFLICT DO NOTHING
                """,DY_SOURCE);
            PilotSql.update(c,"""
                INSERT INTO app_content_license
                (id,source_id,edition,organization_id,submitted_by,environment,purpose,
                 authorization_ref,proof_sha256,starts_at,ends_at)
                VALUES (?,?,'1',?,?,'SANDBOX','AUTHORIZED_PROCESSING','TEST_ONLY rights',repeat('a',64),
                        statement_timestamp()-interval '1 day',statement_timestamp()+interval '1 day')
                """,license,DY_SOURCE,s.org(),s.staffManager().id());
            PilotSql.update(c,"""
                INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                VALUES (?,1,'APPROVED',?,'TEST_ONLY independent rights')
                """,license,s.staffReviewer().id());
            PilotSql.update(c,"""
                INSERT INTO app_content_license_capability(license_id,decision_version,operation)
                VALUES (?,1,'EXTRACT_TEXT'),(?,1,'SHOW_EXCERPTS'),(?,1,'EXPORT_DERIVATIVES')
                """,license,license,license);
            return null;
        });
        String items="[{\"program\":\"TEST-PROGRAM\",\"registryProject\":\"A\",\"issuanceId\":\"ISS-1\",\"creditKind\":\"ERC\","
            +"\"issuedOn\":\"2023-03-01\",\"geography\":\"BR\",\"creditsTco2e\":\"75\",\"sourceId\":\""+DY_SOURCE+"\","
            +"\"sourceEdition\":\"1\",\"locator\":\"TEST_ONLY item A\"},"
            +"{\"program\":\"TEST-PROGRAM\",\"registryProject\":\"B\",\"issuanceId\":\"ISS-2\",\"creditKind\":\"ERF\","
            +"\"issuedOn\":\"2023-09-01\",\"geography\":\"BR\",\"creditsTco2e\":\"25\",\"sourceId\":\""+DY_SOURCE+"\","
            +"\"sourceEdition\":\"1\",\"locator\":\"TEST_ONLY item B\"}]";
        JsonNode dy=post("/v1/platform/open-dy-assessments/"+s.project(),s.staffManager().cookie(),"\"0\"",
            "{\"projectYear\":2,\"outcome\":\"FLEET_CREDITS_FOUND\",\"erc\":\"75\",\"erf\":\"25\","
            +"\"programs\":\"Programas pesquisados no teste E2E\",\"projectReferences\":\"Referências do teste E2E\","
            +"\"evidenceLocator\":\"Localizador do teste E2E\",\"coverageNote\":\"Cobertura declarada no teste E2E\","
            +"\"issuances\":"+items+",\"sourceLicenses\":[{\"sourceId\":\""+DY_SOURCE+"\",\"sourceEdition\":\"1\",\"licenseId\":\""+license+"\"}]}",201);
        assertEquals("0.75",dy.path("dyPreview").asText());
        post("/v1/platform/open-dy-assessments/"+s.project()+"/"+dy.path("id").asText()+"/review",s.staffReviewer().cookie(),"\"1\"",
            "{\"assessmentSha256\":\""+dy.path("assessmentSha256").asText()+"\",\"outcome\":\"CHECKED_INTERNAL\","
            +"\"ruleReference\":\"VM0038-1.1-EQ8 (Dy)\",\"evidenceReferences\":\"Registro de pesquisa do teste E2E\","
            +"\"reason\":\"Parecer interno independente de teste, sem admissão normativa.\"}",201);

        // First numeric rehearsal: REVIEWED_INTERNAL with Dy = 0.75 and ER = (BE - PE) x Dy, nothing admitted.
        JsonNode run=post(runs("Y2-M1"),prep.cookie(),"\"1\"","{}",201);
        assertEquals("REVIEWED_INTERNAL",run.path("state").asText(),run.toString());
        assertFalse(run.path("canIssueVcu").asBoolean());
        assertFalse(run.path("stale").asBoolean());
        assertFalse(run.at("/memory/usableForE8").asBoolean());
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED",run.at("/dyContext/state").asText());
        assertEquals(dy.path("id").asText(),run.at("/dyContext/assessmentId").asText());
        assertEquals(0,new BigDecimal("0.75").compareTo(dec(run.at("/memory/dy"))));
        BigDecimal be=dec(run.at("/memory/beTco2e")),pe=dec(run.at("/memory/peTco2e")),er=dec(run.at("/memory/erTco2e"));
        assertEquals(0,be.subtract(pe).multiply(new BigDecimal("0.75")).compareTo(er),"ER = (BE - PE) x Dy");
        assertEquals("NOT_ADMITTED",OpenE8TestFixture.calcGate(run,"NORMATIVE_PACK").path("state").asText());
        assertEquals("NOT_ADMITTED",OpenE8TestFixture.calcGate(run,"CARBON_RIGHTS").path("state").asText());

        // Roteiro #124 vertical PE: PE = sum(EC x EF) with EF DERIVED from the platform series (CO2_ONLY, GWP = 1).
        // EC = 1000 kWh + 2000 kWh; EF = 0.5439000000000001 tCO2/MWh = 0.0005439000000000001 tCO2e/kWh at both hours.
        BigDecimal expectedPe=new BigDecimal("3000").multiply(new BigDecimal("0.0005439000000000001"));
        assertTrue(expectedPe.subtract(pe).abs().compareTo(new BigDecimal("0.000001"))<0,"PE from the series: expected "+expectedPe+" got "+pe);
        assertEquals("NOT_ADMITTED",OpenE8TestFixture.calcGate(run,"EF_GRID").path("state").asText());
        // BE = ED x EF_fuel x 100 x IR / (AFEC x MPG), EF_fuel = 2.25 kgCO2/L = 0.008517176514 tCO2e/USgal (synthetic catalog entry of the project year).
        BigDecimal ef=new BigDecimal("0.00225").multiply(new BigDecimal("3.785411784"));
        BigDecimal expectedBe=new BigDecimal("2700").multiply(ef).multiply(new BigDecimal("100"))
            .divide(dec(run.at("/memory/afecKwhPer100Miles")).multiply(dec(run.at("/memory/mpgUs"))),20,java.math.RoundingMode.HALF_EVEN);
        assertTrue(expectedBe.subtract(be).abs().compareTo(new BigDecimal("0.000001"))<0,"BE from the catalog: expected "+expectedBe+" got "+be);
        assertEquals("NOT_ADMITTED",OpenE8TestFixture.calcGate(run,"EF_FUEL").path("state").asText());

        var bindingGet=fx.call("GET","/v1/projects/"+s.project()+"/technical/open-monitoring/dossiers/Y2-M1/grid-series-bindings/"+boundEfel[0],s.owner().cookie(),null,null);
        assertEquals(200,bindingGet.statusCode(),bindingGet.body());
        assertEquals("DISPATCH_TOP",fx.json(bindingGet).at("/binding/definitionHypothesis").asText());
        assertTrue(fx.json(bindingGet).path("gates").toString().contains("SERIES_DEFINITION_HYPOTHESIS_DISPATCH_TOP_NOT_CONFIRMED"));
    }


    private void registerFuelDatum(String value,int vintage,String from,String to) {
        UUID entry=UUID.randomUUID();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_fuel_entry(id,kind,fuel_id,parameter,gas,emission_scope,value_text,unit,vintage_year,valid_from,valid_to,
                  evidence_source,evidence_locator,read_state,registered_by)
                VALUES (?,'DATUM','GASOLINE_C','EMISSION_FACTOR','CO2_FOSSIL','TTW',?,'kgCO2/L',?,?::date,?::date,
                  'Valor SINTÉTICO de teste, não é fator oficial','teste integrado','SECONDARY',?)
                """,entry,value,vintage,from,to,s.staffManager().id());
            update(c,"""
                INSERT INTO app_fuel_entry_review(id,entry_id,basis,outcome,reason,reviewer_id)
                VALUES (?,?,'OWNER_INTERNAL','REVIEWED_OK','Conferência interna sintética do teste.',?)
                """,UUID.randomUUID(),entry,s.staffReviewer().id());
            return null;
        });
    }

    private final String[] boundEfel=new String[1];
    private String bindAndReview(OpenE8TestFixture fx,OpenE8TestFixture.Scenario sc,String dossierUrl,String code,String energy,UUID seriesId) throws Exception {
        var bound=fx.postMethod(dossierUrl+"/"+code+"/grid-series-bindings",sc.owner().cookie(),key(),"\"0\"",
            "{\"energyCandidateId\":\""+energy+"\",\"gridSeriesId\":\""+seriesId+"\",\"boundary\":\"CO2_ONLY\"}");
        assertEquals(201,bound.statusCode(),bound.body());
        JsonNode result=fx.json(bound);
        assertEquals("1",result.at("/binding/gwpCo2").asText());
        String efel=result.at("/candidate/id").asText();
        boundEfel[0]=efel;
        var reviewed=fx.postMethod(dossierUrl+"/"+code+"/efel-candidates/"+efel+"/reviews",sc.docsReviewer().cookie(),key(),"\"0\"",
            "{\"outcome\":\"CHECKED_INTERNAL\",\"rationale\":\"Origem e coerência conferidas apenas internamente, sem aprovação normativa.\","
            +"\"evidenceReference\":\"Vínculo da série nacional (V114), conferência interna sintética.\"}");
        assertEquals(200,reviewed.statusCode(),reviewed.body());
        return efel;
    }
}
