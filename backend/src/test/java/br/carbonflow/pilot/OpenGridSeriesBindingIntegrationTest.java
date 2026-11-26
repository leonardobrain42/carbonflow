package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Roteiro #124 PR 3: the platform series feeds the project's EFel candidate under CO2_ONLY (GWP=1), without admitting anything. */
@QuarkusTest
@QuarkusTestResource(value = OpenE8TestFixture.Settings.class, restrictToAnnotatedClass = true)
class OpenGridSeriesBindingIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private static final String SOURCE = "MCTI-SIN-DESPACHO", EDITION = "2026-janout-7f8e2292";

    private UUID series(UUID owner, int rows) throws Exception {
        UUID license = UUID.randomUUID(), edition = UUID.randomUUID();
        return database.transaction(c -> {
            update(c, "INSERT INTO app_content_license(id,source_id,edition,submitted_by,environment,purpose,authorization_ref,proof_sha256,starts_at) "
                    + "VALUES (?,?,?,?,'SANDBOX','AUTHORIZED_PROCESSING','Licença sintética de teste',?,'2026-01-01T00:00:00Z')", license, SOURCE, EDITION, owner, "a".repeat(64));
            update(c, "INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason) VALUES (?,1,'DECLARED_INTERNAL',?,'Declaração sintética de teste.')", license, owner);
            update(c, "INSERT INTO app_grid_series_edition(id,source_id,edition,license_id,license_decision_version,license_basis,workbook_sha256,content_sha256,"
                    + "gas,original_unit,target_unit,data_year,timezone,hour_convention,row_count,quality,time_issue_count,imported_by) "
                    + "VALUES (?,?,?,?,1,'OWNER_INTERNAL',?,?,'CO2','tCO2/MWh','tCO2/MWh',2026,'America/Sao_Paulo','HOUR_ENDING_1_24',?,'{}'::jsonb,0,?)",
                    edition, SOURCE, EDITION, license, "b".repeat(64), UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), rows, owner);
            for (int i = 0; i < rows; i++)
                update(c, "INSERT INTO app_grid_series_value(edition_id,month,day,hour_label,cell,value_text,value_original,value_target,interval_start_local,"
                        + "interval_start_utc,interval_end_utc) VALUES (?,2,1,?,?,?,?,?,'2026-02-01T00:00',?::timestamptz,?::timestamptz)", edition, i + 1, "A" + i,
                        "0.5439000000000001", new java.math.BigDecimal("0.5439000000000001"), new java.math.BigDecimal("0.5439000000000001"),
                        i == 0 ? "2026-02-01T00:00:00Z" : "2026-02-01T01:00:00Z", i == 0 ? "2026-02-01T01:00:00Z" : "2026-02-01T02:00:00Z");
            return edition;
        });
    }

    @Test
    void derivesTheProjectCandidateFromTheSeriesUnderCo2OnlyAndNeverAdmitsIt() throws Exception {
        var fx = new OpenE8TestFixture(database, mapper, base);
        var s = fx.openProject("2023-01-01");
        fx.preparePeriod(s, "Y1-M1", 2026, 2);
        String root = "/v1/projects/" + s.project() + "/technical/open-monitoring/dossiers/Y1-M1";
        UUID energy = database.transaction(c -> first(c, "SELECT id FROM app_open_e8_energy_candidate WHERE project_id=? ORDER BY created_at DESC LIMIT 1",
                r -> r.getObject(1, UUID.class), s.project()));
        UUID full = series(s.owner().id(), 2), partial = series(s.owner().id(), 1);
        String url = root + "/grid-series-bindings";
        String body = "{\"energyCandidateId\":\"%s\",\"gridSeriesId\":\"%s\",\"boundary\":\"%s\"}";

        assertEquals(422, fx.postMethod(url, s.owner().cookie(), OpenE8TestFixture.key(), "\"1\"", body.formatted(energy, full, "CO2_CH4_N2O")).statusCode());
        var partialResp = fx.postMethod(url, s.owner().cookie(), OpenE8TestFixture.key(), "\"1\"", body.formatted(energy, partial, "CO2_ONLY"));
        assertEquals(409, partialResp.statusCode(), partialResp.body());
        assertEquals(404, fx.postMethod(url, s.outside().cookie(), OpenE8TestFixture.key(), "\"1\"", body.formatted(energy, full, "CO2_ONLY")).statusCode());

        var ok = fx.postMethod(url, s.owner().cookie(), OpenE8TestFixture.key(), "\"1\"", body.formatted(energy, full, "CO2_ONLY"));
        assertEquals(201, ok.statusCode(), ok.body());
        JsonNode result = fx.json(ok);
        assertEquals("NOT_ADMITTED", result.path("admission").asText());
        assertFalse(result.at("/candidate/usableForE8").asBoolean());
        assertEquals("1", result.at("/binding/gwpCo2").asText());
        assertEquals("CO2", result.at("/binding/sourceGas").asText());
        assertEquals("DECLARED_BY_PEOPLE", result.at("/binding/timeBasis").asText());
        assertEquals("tCO2e/kWh", result.at("/candidate/normalizedUnit").asText());
        assertEquals("tCO2e/MWh", result.at("/candidate/originalUnit").asText());
        assertEquals(0, new java.math.BigDecimal("0.5439000000000001").compareTo(new java.math.BigDecimal(result.at("/candidate/hours/0/originalValue").asText())));
        assertEquals(2, result.at("/binding/coveredHours").asInt());
        assertTrue(result.path("gates").toString().contains("LICENSE_NOT_INDEPENDENTLY_APPROVED"));
        assertTrue(result.path("gates").toString().contains("VT0011_TOOL07_ELIGIBILITY_NOT_EVALUATED"));
        var get = fx.call("GET", url + "/" + result.at("/candidate/id").asText(), s.owner().cookie(), null, null);
        assertEquals(200, get.statusCode(), get.body());
    }
}
