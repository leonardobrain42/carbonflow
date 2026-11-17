package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PilotResourceTest {
    @TestHTTPResource
    URL base;

    @Inject
    ObjectMapper json;

    @Inject
    DataSource database;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private String cookie;

    @BeforeEach
    void signIn() throws Exception {
        var login = send(HttpRequest.newBuilder(URI.create(base.toString()).resolve("/v1/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"identity":"SIM-PREP","password":"piloto-prep"}
                        """)).build());
        assertEquals(204, login.statusCode(), login.body());
        cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    @Test
    void freezesFourCutsAndKeepsM2CollectingWhileV1AndI1Wait() throws Exception {
        var created = send(command(UUID.randomUUID().toString(), "E6-EXEMPLOS"));
        assertEquals(201, created.statusCode(), created.body());
        var report = json.readTree(created.body());
        assertEquals("SIMULACAO", report.path("environment").asText());
        assertEquals("SIM-PREP", report.path("actor").asText());
        assertEquals("0.2.14", report.at("/provenance/kitVersion").asText());
        assertEquals("EF-SYN-v1", report.at("/factor/id").asText());
        assertEquals("0.30", report.at("/factor/value").asText());
        assertEquals("tCO2/MWh", report.at("/factor/unit").asText());
        assertEquals("America/Sao_Paulo", report.path("zone").asText());
        assertEquals("20", report.at("/results/afecKwhPer100Mi").asText());
        assertEquals("30", report.at("/results/mpg").asText());
        assertEquals("1", report.at("/results/dy").asText());
        assertEquals("0.0003", report.at("/results/electricityFactorTco2PerKwh").asText());

        assertCut(report.at("/results/periods/0/cuts/0"), 2027, "2027-10-01", "2027-12-31", "2.7", "0.9", "1.80");
        assertCut(report.at("/results/periods/0/cuts/1"), 2028, "2028-01-01", "2028-09-30", "6.3", "2.1", "4.20");
        assertCut(report.at("/results/periods/1/cuts/0"), 2028, "2028-10-01", "2028-12-31", "1.8", "0.66", "1.14");
        assertCut(report.at("/results/periods/1/cuts/1"), 2029, "2029-01-01", "2029-09-30", "6.2", "2.04", "4.16");
        assertMemory(report.at("/results/periods/0/emissions"), "9", "3", "6.00");
        assertMemory(report.at("/results/periods/1/emissions"), "8", "2.7", "5.30");
        assertEquals("EM_CONFERENCIA", report.at("/checkpoints/0/m1").asText());
        assertEquals("COLETANDO", report.at("/checkpoints/0/m2").asText());
        assertEquals("FECHADO", report.at("/checkpoints/1/m1").asText());
        assertEquals("EM_REVISAO", report.at("/checkpoints/1/v1").asText());
        assertEquals("NOVO", report.at("/checkpoints/1/i1").asText());
        assertEquals("COLETANDO", report.at("/checkpoints/1/m2").asText());
        assertEquals("APROVADO", report.at("/checkpoints/2/v1").asText());
        assertEquals("AGUARDANDO_DEPOSITO", report.at("/checkpoints/2/i1").asText());
        assertEquals("COLETANDO", report.at("/checkpoints/2/m2").asText());

        String id = report.path("id").asText();
        assertEquals(report, json.readTree(get("/" + id).body()));
        var snapshotResponse = get("/" + id + "/snapshot");
        assertEquals(200, snapshotResponse.statusCode());
        var snapshot = json.readTree(snapshotResponse.body());
        assertEquals("EF-SYN-v2", snapshot.at("/fixture/calculation/replacementCandidate/id").asText());
        assertEquals("EF-SYN-v1", snapshot.at("/fixture/calculation/factor/id").asText());
        assertEquals(report.path("snapshotSha256").asText(),
                FixtureCatalog.sha256(snapshotResponse.body().getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, count("id = ? AND jsonb_typeof(report) = 'object'", UUID.fromString(id)));
    }

    @Test
    void rejectsMissingEcAndLeavesPreviousRunReadable() throws Exception {
        var created = send(command(UUID.randomUUID().toString(), "E6-EXEMPLOS"));
        var report = json.readTree(created.body());
        String id = report.path("id").asText();
        String invalidKey = UUID.randomUUID().toString();
        var invalid = send(command(invalidKey, "E6-EXEMPLOS-SEM-EC"));
        assertEquals(422, invalid.statusCode(), invalid.body());
        var error = json.readTree(invalid.body());
        assertEquals("LACUNA_OBRIGATORIA", error.path("code").asText());
        assertEquals("calculation.M1.cuts[0].ecKwh", error.at("/issues/0/path").asText());
        assertEquals(0, count("command_key = ?", invalidKey));
        assertEquals(report, json.readTree(get("/" + id).body()));

        var next = send(command(UUID.randomUUID().toString(), "E6-EXEMPLOS"));
        assertEquals(201, next.statusCode(), next.body());
        assertNotEquals(id, json.readTree(next.body()).path("id").asText());
        assertEquals(report, json.readTree(get("/" + id).body()));
    }

    @Test
    void retriesAndConcurrentRequestsPersistOnlyOneRunForTheKey() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = command(key, "E6-EXEMPLOS");
        var first = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var one = first.get();
        var two = second.get();
        assertEquals(201, one.statusCode(), one.body());
        assertEquals(201, two.statusCode(), two.body());
        assertEquals(json.readTree(one.body()), json.readTree(two.body()));
        assertEquals(one.headers().firstValue("location"), two.headers().firstValue("location"));
        assertEquals(json.readTree(one.body()), json.readTree(send(request).body()));
        assertEquals(1, count("command_key = ?", key));

        var conflict = send(command(key, "E6-EXEMPLOS-SEM-EC"));
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals("CHAVE_REUSADA", json.readTree(conflict.body()).path("code").asText());
    }

    @Test
    void rejectsUnknownFixturesAndRunIds() throws Exception {
        var response = send(command(UUID.randomUUID().toString(), "MASSA-REAL"));
        assertEquals(422, response.statusCode(), response.body());
        assertEquals("FONTE_NAO_ADMISSIVEL", json.readTree(response.body()).path("code").asText());
        assertEquals(404, get("/" + UUID.randomUUID()).statusCode());
        assertEquals(404, get("/id-invalido").statusCode());
    }

    private HttpRequest command(String key, String fixture) {
        return HttpRequest.newBuilder(uri(""))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"fixture":{"id":"%s","version":1}}
                        """.formatted(fixture))).build();
    }

    private HttpResponse<String> get(String suffix) throws Exception {
        return send(HttpRequest.newBuilder(uri(suffix)).header("Cookie", cookie).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String suffix) {
        return URI.create(base.toString()).resolve(PilotResource.PATH + suffix);
    }

    private int count(String condition, Object value) throws SQLException {
        try (var connection = database.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM synthetic_run WHERE " + condition)) {
            statement.setObject(1, value);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private void assertCut(JsonNode node, int year, String start, String end, String be, String pe, String er) {
        assertEquals(year, node.path("vintage").asInt());
        assertEquals(start, node.path("start").asText());
        assertEquals(end, node.path("endInclusive").asText());
        assertMemory(node.path("emissions"), be, pe, er);
    }

    private void assertMemory(JsonNode node, String be, String pe, String er) {
        assertEquals(be, node.path("be").asText());
        assertEquals(pe, node.path("pe").asText());
        assertEquals("0", node.path("le").asText());
        assertEquals(er, node.path("erDisplay").asText());
        assertEquals(0, new java.math.BigDecimal(er).compareTo(new java.math.BigDecimal(node.path("er").asText())));
    }
}
