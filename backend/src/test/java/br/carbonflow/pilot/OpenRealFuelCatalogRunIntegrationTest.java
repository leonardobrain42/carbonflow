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
 * Roteiro #124, item 7: E8 rehearsal with the REAL fuel-catalog data (docs/data/fuel-catalog-br-seed.json, etapa 5) and
 * synthetic everything else (fleet, energy, series, reviews, Dy). The project starts on 2025-08-01, so project year 1 is the E30
 * window of the CNPE (to 2026-07-31) and year 2 is the E32 window (from 2026-08-01): the IR gets a distinct catalog entry per
 * year with real validity. The reviews are synthetic test facts; nothing is admitted and no real review is implied.
 */
@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class OpenRealFuelCatalogRunIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    private OpenE8TestFixture fx;
    private OpenE8TestFixture.Scenario s;
    /** All test classes share ONE database in CI and the catalog is append-only, so this rehearsal owns private fuel ids; the VALUES are the real ones. */
    private String gasoline,diesel;

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
        return "{\"metric\":\""+metric+"\",\"updatedOn\":\"2026-09-10\",\"validFrom\":\"2026-08-01\","
            +"\"validUntil\":\"2027-07-31\",\"evidenceLocator\":\"Registro de atualização "+metric+"\"}";
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
                        + "interval_start_utc,interval_end_utc) VALUES (?,2,1,?,?,?,?,?,'2026-09-01T00:00',?::timestamptz,?::timestamptz + interval '1 hour')",
                        edition, i + 1, "A" + i, FACTOR, new BigDecimal(FACTOR), new BigDecimal(FACTOR), starts[i], starts[i]);
            return edition;
        });
    }

    @Test
    void peFollowsTheSeriesBoundCandidateEndToEndAndNothingIsAdmitted() throws Exception {
        fx=new OpenE8TestFixture(database,mapper,base);
        s=fx.openProject("2025-08-01");
        String suffix=UUID.randomUUID().toString().substring(0,8).toUpperCase().replace('-','A');
        gasoline="RG"+suffix;
        diesel="RD"+suffix;
        database.transaction(c->{
            update(c,"INSERT INTO app_fuel(id,name,family,role,phase,pending) VALUES (?,'Cópia de teste da gasolina C (valores reais do seed)','OTHER','PRODUCT','LIQUID',ARRAY['só teste'])",gasoline);
            update(c,"INSERT INTO app_fuel(id,name,family,role,phase,pending) VALUES (?,'Combustível de teste para o caso negativo','OTHER','PRODUCT','LIQUID',ARRAY['só teste'])",diesel);
            return null;
        });
        fx.preparePeriod(s,"Y1-M1",2025,9);
        UUID seriesId=series(s.owner().id(),"2026-09-01T00:00:00Z","2026-09-01T01:00:00Z");
        fx.preparePeriod(s,"Y2-M1",2026,9,(sc,dossierUrl,code,energy)->bindAndReview(fx,sc,dossierUrl,code,energy,seriesId));
        fx.preparePeriod(s,"Y2-M2",2026,10);
        // BE: the fossil factor comes from the fuel catalog by reference (CO2_ONLY, GWP = 1), not from a file candidate.
        registerSeedGasolineC();
        var threeGas=fx.postMethod("/v1/platform/open-parameter-bindings",s.staffManager().cookie(),key(),"\"0\"",
            "{\"projectId\":\""+s.project()+"\",\"parameterKind\":\"EF_FUEL\",\"vintageYear\":2025,"
            +"\"catalog\":{\"fuelId\":\""+gasoline+"\",\"boundary\":\"CO2_CH4_N2O\",\"scope\":\"TTW\",\"date\":\"2025-09-01\"}}");
        assertEquals(422,threeGas.statusCode(),threeGas.body());
        var typed=fx.postMethod("/v1/platform/open-parameter-bindings",s.staffManager().cookie(),key(),"\"0\"",
            "{\"projectId\":\""+s.project()+"\",\"parameterKind\":\"EF_FUEL\",\"vintageYear\":2025,\"declaredValue\":\"0.009\","
            +"\"catalog\":{\"fuelId\":\""+gasoline+"\",\"boundary\":\"CO2_ONLY\",\"scope\":\"TTW\",\"date\":\"2025-09-01\"}}");
        assertEquals(422,typed.statusCode(),"a typed number never rides with a catalog reference: "+typed.body());
        for(int year:new int[]{2025,2026}) {
            fx.pinIrDy(s.project(),s.org(),s.staffManager().id(),year);
            var pinned=fx.postMethod("/v1/platform/open-parameter-bindings",s.staffManager().cookie(),key(),"\"0\"",
                "{\"projectId\":\""+s.project()+"\",\"parameterKind\":\"EF_FUEL\",\"vintageYear\":"+year+","
                +"\"catalog\":{\"fuelId\":\""+gasoline+"\",\"boundary\":\"CO2_ONLY\",\"scope\":\"TTW\",\"date\":\""+year+"-09-01\"}}");
            assertEquals(201,pinned.statusCode(),pinned.body());
            assertEquals("CATALOG_REFERENCE",fx.json(pinned).at("/valueKind").asText());
            assertEquals("tCO2e/USgal",fx.json(pinned).at("/normalizedUnit").asText());
            // The pin records the ORIGINAL catalog value: year 1 = E30 (CNPE 9/2025), year 2 = E32 (CNPE 9/2026), distinct per year.
            String formula=fx.json(pinned).at("/conversionFormula").asText();
            assertTrue(formula.contains(year==2025?"1548.383340 gCO2/L":"1504.143816 gCO2/L"),formula);
            assertFalse(formula.contains(year==2025?"1504.143816":"1548.383340"),formula);
        }
        // The owner authored the energy binding, so the attempt is run by a different PREP collaborator (segregation).
        var prep=fx.actor();
        fx.grant(s.project(),s.org(),s.owner().id(),prep.id(),"PREP");

        // IR: year 1 ex ante plan + initial review, year 2 annual observed update with dated evidence.
        JsonNode plan=post("/v1/projects/"+s.project()+"/technical/open-ir-ex-ante-plan",s.owner().cookie(),"\"0\"",EX_ANTE,201);
        // Q13/D21 step 2a: a transition declaration needs two entries of the same factor pinned in the year; year 1 has one.
        var rejected=fx.postMethod("/v1/platform/open-ir-assessments/"+s.project(),s.staffManager().cookie(),key(),"\"0\"",
            "{\"period\":\"Y1-M1\",\"frequency\":\"ANNUAL\",\"basis\":\"PROJECT_SPECIFIC\","
            +"\"rationale\":\"Atualização anual declarada para teste integrado, sem admissão.\","
            +"\"fuelTransition\":{\"actReference\":\"Res. CNPE 9/2026 (teste)\",\"evidenceLocator\":\"Seed, entradas E30 e E32\"}}");
        assertEquals(409,rejected.statusCode(),rejected.body());
        JsonNode y1=recordIr("Y1-M1","\"0\"");
        assertTrue(y1.path("fuelTransition").isNull()||y1.path("fuelTransition").isMissingNode(),"no declaration unless informed");
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
        // Year 2 (source year 2026) also pins the E30 entry (the window-aware selector still serves Y2-M1 with the E32 one): two entries
        // of the same factor now sit in the year, so the declaration is accepted and records both entries and the act. No guard moves.
        var e30Pin=fx.postMethod("/v1/platform/open-parameter-bindings",s.staffManager().cookie(),key(),"\"1\"",
            "{\"projectId\":\""+s.project()+"\",\"parameterKind\":\"EF_FUEL\",\"vintageYear\":2026,"
            +"\"catalog\":{\"fuelId\":\""+gasoline+"\",\"boundary\":\"CO2_ONLY\",\"scope\":\"TTW\",\"date\":\"2026-03-01\"}}");
        assertEquals(201,e30Pin.statusCode(),e30Pin.body());
        JsonNode y2=post("/v1/platform/open-ir-assessments/"+s.project(),s.staffManager().cookie(),"\"0\"",
            "{\"period\":\"Y2-M1\",\"frequency\":\"ANNUAL\",\"basis\":\"PROJECT_SPECIFIC\","
            +"\"rationale\":\"Atualização anual declarada para teste integrado, sem admissão.\","
            +"\"fuelTransition\":{\"actReference\":\"Res. CNPE 9/2026 (teste)\",\"evidenceLocator\":\"Seed, entradas E30 e E32\"}}",201);
        assertEquals(2,y2.at("/fuelTransition/entryIds").size(),y2.toString());
        assertEquals("Res. CNPE 9/2026 (teste)",y2.at("/fuelTransition/actReference").asText());
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
            +"\"issuedOn\":\"2025-09-01\",\"geography\":\"BR\",\"creditsTco2e\":\"75\",\"sourceId\":\""+DY_SOURCE+"\","
            +"\"sourceEdition\":\"1\",\"locator\":\"TEST_ONLY item A\"},"
            +"{\"program\":\"TEST-PROGRAM\",\"registryProject\":\"B\",\"issuanceId\":\"ISS-2\",\"creditKind\":\"ERF\","
            +"\"issuedOn\":\"2026-03-01\",\"geography\":\"BR\",\"creditsTco2e\":\"25\",\"sourceId\":\""+DY_SOURCE+"\","
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
        // BE = ED x EF_fuel x 100 x IR / (AFEC x MPG), EF_fuel = 1504.143816 gCO2/L (REAL catalog entry E32, derived 0.68 x 0.742 x 2981.1) = 0.005693... tCO2e/USgal.
        BigDecimal ef=new BigDecimal("0.001504143816").multiply(new BigDecimal("3.785411784"));
        BigDecimal expectedBe=new BigDecimal("2700").multiply(ef).multiply(new BigDecimal("100"))
            .divide(dec(run.at("/memory/afecKwhPer100Miles")).multiply(dec(run.at("/memory/mpgUs"))),20,java.math.RoundingMode.HALF_EVEN);
        assertTrue(expectedBe.subtract(be).abs().compareTo(new BigDecimal("0.000001"))<0,"BE from the catalog: expected "+expectedBe+" got "+be);
        assertEquals("NOT_ADMITTED",OpenE8TestFixture.calcGate(run,"EF_FUEL").path("state").asText());

        // Q13/D21 step 1: a LATER pin of the same year whose validity does not cover the window no longer displaces the pin that
        // does. Pin 2026 again to an entry valid only from 2026-10-01; Y2-M1 (2026-09-01) keeps resolving to the E32 gasolina entry
        // (the guard EF_FUEL_WINDOW itself is exercised where no pin of the year covers the window: OpenSeriesBoundRunIntegrationTest).
        registerFuelDatum(diesel,"2.6",2026,"2026-10-01","2027-01-27");
        var laterPin=fx.postMethod("/v1/platform/open-parameter-bindings",s.staffManager().cookie(),key(),"\"2\"",
            "{\"projectId\":\""+s.project()+"\",\"parameterKind\":\"EF_FUEL\",\"vintageYear\":2026,"
            +"\"catalog\":{\"fuelId\":\""+diesel+"\",\"boundary\":\"CO2_ONLY\",\"scope\":\"TTW\",\"date\":\"2026-11-01\"}}");
        assertEquals(201,laterPin.statusCode(),laterPin.body());
        var againResponse=fx.postMethod(runs("Y2-M1"),prep.cookie(),key(),"\"2\"","{}");
        assertTrue(againResponse.statusCode()==200||againResponse.statusCode()==201,againResponse.statusCode()+" "+againResponse.body());
        JsonNode again=fx.json(againResponse);
        assertFalse(again.path("gates").toString().contains("EF_FUEL_WINDOW"),"the covering pin still applies: "+again);
        assertTrue(expectedBe.subtract(dec(again.at("/memory/beTco2e"))).abs().compareTo(new BigDecimal("0.000001"))<0,"same BE from the covering pin");

        var bindingGet=fx.call("GET","/v1/projects/"+s.project()+"/technical/open-monitoring/dossiers/Y2-M1/grid-series-bindings/"+boundEfel[0],s.owner().cookie(),null,null);
        assertEquals(200,bindingGet.statusCode(),bindingGet.body());
        assertEquals("DISPATCH_TOP",fx.json(bindingGet).at("/binding/definitionHypothesis").asText());
        assertTrue(fx.json(bindingGet).path("gates").toString().contains("SERIES_DEFINITION_HYPOTHESIS_DISPATCH_TOP_NOT_CONFIRMED"));
    }


    /** Registers, from the versioned seed, the two real gasolina C fossil-CO2 values (E30 and E32) under this test's private fuel id, with a synthetic OWNER review. */
    /** Q13/D21 step 1: one source year holds two catalog pins (E30 then E32); each period window selects the pin whose validity covers it. */
    @Test
    void aYearWithTwoRegulatoryVintagesSelectsThePinOfEachWindow() throws Exception {
        fx=new OpenE8TestFixture(database,mapper,base);
        s=fx.openProject("2025-08-01");
        gasoline="RG"+UUID.randomUUID().toString().substring(0,8).toUpperCase().replace('-','A');
        database.transaction(c->{
            update(c,"INSERT INTO app_fuel(id,name,family,role,phase,pending) VALUES (?,'Cópia de teste da gasolina C (valores reais do seed)','OTHER','PRODUCT','LIQUID',ARRAY['só teste'])",gasoline);
            return null;
        });
        registerSeedGasolineC();
        UUID[] pins=new UUID[2];
        String[] dates={"2026-03-01","2026-09-01"}; // E30 (to 2026-07-31) first, E32 (from 2026-08-01) second = the latest version
        for(int i=0;i<2;i++) {
            var pinned=fx.postMethod("/v1/platform/open-parameter-bindings",s.staffManager().cookie(),key(),"\""+i+"\"",
                "{\"projectId\":\""+s.project()+"\",\"parameterKind\":\"EF_FUEL\",\"vintageYear\":2026,"
                +"\"catalog\":{\"fuelId\":\""+gasoline+"\",\"boundary\":\"CO2_ONLY\",\"scope\":\"TTW\",\"date\":\""+dates[i]+"\"}}");
            assertEquals(201,pinned.statusCode(),pinned.body());
            pins[i]=UUID.fromString(fx.json(pinned).at("/id").asText());
        }
        assertNotEquals(pins[0],pins[1]);
        database.transaction(c->{
            String p=s.project();
            // a window fully inside E30 -> E30 pin, although E32 is the latest version
            assertEquals(pins[0],OpenProjectParameterBindings.forSourceYearWindow(c,p,"EF_FUEL",2026,
                java.time.LocalDate.parse("2026-03-01"),java.time.LocalDate.parse("2026-03-31")).id());
            // a window fully inside E32 -> E32 pin
            assertEquals(pins[1],OpenProjectParameterBindings.forSourceYearWindow(c,p,"EF_FUEL",2026,
                java.time.LocalDate.parse("2026-09-01"),java.time.LocalDate.parse("2026-09-30")).id());
            // a window that crosses the change is covered by neither: falls back to the latest pin and the E8 gate EF_FUEL_WINDOW blocks it
            assertEquals(pins[1],OpenProjectParameterBindings.forSourceYearWindow(c,p,"EF_FUEL",2026,
                java.time.LocalDate.parse("2026-07-15"),java.time.LocalDate.parse("2026-08-15")).id());
            // no window = the previous behavior, latest pin of the year
            assertEquals(pins[1],OpenProjectParameterBindings.forSourceYear(c,p,"EF_FUEL",2026).id());
            return null;
        });
    }

    private void registerSeedGasolineC() throws Exception {
        var seed=mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/data/fuel-catalog-br-seed.json")));
        int found=0;
        for(JsonNode e:seed.path("entries")) {
            if(!"GASOLINE_C".equals(e.path("fuelId").asText())||!"EMISSION_FACTOR".equals(e.path("parameter").asText())
               ||!"CO2_FOSSIL".equals(e.path("gas").asText())) continue;
            found++;
            UUID entry=UUID.randomUUID();
            database.transaction(c->{
                update(c,"""
                    INSERT INTO app_fuel_entry(id,kind,fuel_id,parameter,gas,emission_scope,value_text,unit,vintage_year,valid_from,valid_to,
                      validity_rule,evidence_source,evidence_locator,read_state,evidence_sha256,retrieved_on,registered_by)
                    VALUES (?,'DATUM',?,'EMISSION_FACTOR','CO2_FOSSIL','TTW',?,?,?,?::date,?::date,?,?,?,?,?,?::date,?)
                    """,entry,gasoline,e.path("value").asText(),e.path("unit").asText(),e.path("vintageYear").asInt(),
                    e.path("validFrom").asText(),e.path("validTo").isNull()?null:e.path("validTo").asText(),e.path("validityRule").asText(),
                    e.path("evidenceSource").asText(),e.path("evidenceLocator").asText(),e.path("readState").asText(),
                    e.path("evidenceSha256").asText(),e.path("retrievedOn").asText(),s.staffManager().id());
                update(c,"""
                    INSERT INTO app_fuel_entry_review(id,entry_id,basis,outcome,reason,reviewer_id)
                    VALUES (?,?,'OWNER_INTERNAL','REVIEWED_OK','Revisão interna SINTÉTICA do teste; não é a revisão do responsável.',?)
                    """,UUID.randomUUID(),entry,s.staffReviewer().id());
                return null;
            });
        }
        assertEquals(2,found,"the seed carries the E30 and E32 gasolina C factors");
    }

    private void registerFuelDatum(String fuel,String value,int vintage,String from,String to) {
        UUID entry=UUID.randomUUID();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_fuel_entry(id,kind,fuel_id,parameter,gas,emission_scope,value_text,unit,vintage_year,valid_from,valid_to,
                  evidence_source,evidence_locator,read_state,registered_by)
                VALUES (?,'DATUM',?,'EMISSION_FACTOR','CO2_FOSSIL','TTW',?,'kgCO2/L',?,?::date,?::date,
                  'Valor SINTÉTICO de teste, não é fator oficial','teste integrado','SECONDARY',?)
                """,entry,fuel,value,vintage,from,to,s.staffManager().id());
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
