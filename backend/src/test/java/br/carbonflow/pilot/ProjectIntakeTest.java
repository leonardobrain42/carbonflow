package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProjectIntakeTest {
    @TestHTTPResource URL base;
    @Inject ObjectMapper mapper;
    @Inject PilotDatabase database;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String project = "SIM-E8-003-" + UUID.randomUUID().toString().substring(0, 8);
    private final String path = "/v1/projects/" + project;
    private int version;

    @Test
    void preservesScopeEvidenceMilestonesAndOriginalReadings() throws Exception {
        seedProject();
        String prep = login("PREP");
        String rev = login("REV");
        String pp = login("PP");
        String op = login("OP");
        String outside = login("PREP-OUTRA");
        String vvb = login("VVB");

        assertEquals(401, request("GET", path, null, null, null, null).statusCode());
        assertEquals(404, request("GET", path, outside, null, null, null).statusCode());
        assertEquals(403, request("GET", path, vvb, null, null, null).statusCode());
        JsonNode samples = body(get(prep, "/intake/examples"));
        JsonNode candidate = samples.path("candidate");
        assertEquals("E8-003@1", samples.path("version").asText());

        var empty = body(get(prep, ""));
        assertEquals(0, empty.at("/snapshot/version").asInt());
        var candidatePayload = mapper.createObjectNode().set("candidate", candidate);
        assertEquals(403, post(op, "/intake/" + project + "/actions", action("TX-E3-01", candidatePayload), 0).statusCode());
        assertEquals(428, post(prep, "/intake/" + project + "/actions", action("TX-E3-01", candidatePayload), null).statusCode());

        ObjectNode excluded = candidate.deepCopy();
        var profile = (ObjectNode) excluded.path("profile");
        profile.put("closedNetwork", false).put("additionalInfrastructure", true).put("ecRoute", "ESTIMADA_92_3");
        var excludedCommand = action("TX-E3-01", mapper.createObjectNode().set("candidate", excluded));
        String key = UUID.randomUUID().toString();
        var receipt = request("POST", path + "/intake/" + project + "/actions", prep, excludedCommand, 0, key);
        assertEquals(200, receipt.statusCode(), receipt.body());
        assertEquals(body(receipt), body(request("POST", path + "/intake/" + project + "/actions", prep, excludedCommand, 0, key)));
        version = 1;
        var rejection = applyAction(rev, "/intake", "TX-E3-02", reviewDetails());
        assertEquals("FORA_DO_ESCOPO", rejection.at("/snapshot/intake/state").asText());
        assertTrue(rejection.at("/snapshot/intake/selection").isNull());
        assertEquals(Set.of("REDE_FORA_DO_RECORTE", "SUPRIMENTO_FORA_DO_RECORTE", "ROTA_EC_FORA_DO_RECORTE"),
                codes(rejection.at("/snapshot/intake/reasons")));

        ObjectNode tooMany = candidate.deepCopy();
        var vehicles = (com.fasterxml.jackson.databind.node.ArrayNode) tooMany.at("/inventory/vehicles");
        for (int i = vehicles.size() + 1; i <= 100; i++) {
            vehicles.addObject().put("id", "SIM-VEICULO-" + i).put("model", "SIM-MODELO-01");
        }
        var atLimit = applyAction(prep, "/intake", "TX-E3-01", mapper.createObjectNode().set("candidate", tooMany));
        assertEquals(100, atLimit.at("/snapshot/intake/candidate/inventory/vehicles").size());
        vehicles.addObject().put("id", "SIM-VEICULO-101").put("model", "SIM-MODELO-01");
        assertError(post(prep, "/intake/" + project + "/actions",
                action("TX-E3-01", mapper.createObjectNode().set("candidate", tooMany)), version), 422, "LIMITE_DE_CAPACIDADE");
        assertEquals(atLimit.path("snapshot"), body(get(prep, "")).path("snapshot"));

        applyAction(rev, "/intake", "TX-E3-01", candidatePayload);
        assertError(post(rev, "/intake/" + project + "/actions", action("TX-E3-02", reviewDetails()), version),
                403, "AUTOAPROVACAO_NEGADA");
        applyAction(prep, "/intake", "TX-E3-01", candidatePayload);
        var pending = applyAction(rev, "/intake", "TX-E3-02", reviewDetails());
        assertEquals(Set.of("PENDENCIA_DIREITOS", "PENDENCIA_EXCEDENTE", "PENDENCIA_SOBREPOSICAO"),
                codes(pending.at("/snapshot/intake/reasons")));
        assertEquals("PENDENTE", pending.at("/snapshot/intake/state").asText());
        assertTrue(pending.at("/snapshot/intake/selection").isNull());

        for (var name : Set.of("DIREITOS", "EXCEDENTE", "SOBREPOSICAO")) applyRecord(pp, name, "SIM-" + name, "2027-06-30", false);
        var reviewed = applyAction(rev, "/intake", "TX-E3-02", reviewDetails());
        assertEquals("ENQUADRADO", reviewed.at("/snapshot/intake/state").asText());
        assertEquals("VM0038 v1.1", reviewed.at("/snapshot/intake/selection/methodology").asText());
        assertEquals(3, reviewed.at("/snapshot/intake/reviewedRecords").size());

        for (var name : Set.of("ENGAJAMENTO", "MECANISMO", "ODS")) applyRecord(prep, name, "SIM-" + name, "2027-06-30", false);
        for (var name : Set.of("FPIC", "REPARTICAO")) applyRecord(prep, name, "SIM-" + name, "2027-06-30", true);
        applyRecord(pp, "RECLAMACAO", "SIM-RECLAMACAO", "2027-06-30", false);
        applyRecord(prep, "ESG", "SIM-ESG-TARDIO", "2027-07-16", false);
        var commit = mapper.createObjectNode().put("date", "2027-07-15").set("evidence", ref("SIM-COMPRA-DCFC"));
        assertError(post(pp, "/implantations/" + project + "/actions", action("TX-E3-03", commit), version),
                422, "ANTERIORIDADE_PENDENTE");
        applyRecord(prep, "ESG", "SIM-ESG", "2027-06-30", false);
        assertEquals("2027-07-15", applyAction(pp, "/implantations", "TX-E3-03", commit).at("/snapshot/commitment/date").asText());
        var operation = mapper.createObjectNode().put("start", "2027-10-01").put("creditStart", "2027-10-01")
                .set("evidence", ref("SIM-COMISSIONAMENTO"));
        assertError(post(op, "/implantations/" + project + "/actions", action("TX-E3-04", operation), version),
                422, "METROLOGIA_PENDENTE");

        var first = samples.at("/meters/0/details");
        ObjectNode initialUncalibrated = first.deepCopy();
        initialUncalibrated.putNull("calibration");
        apply(op, "/inventory/SIM-EC-01/revisions", initialUncalibrated, 201);
        var unresolved = reading(op, "SIM-SEM-CAL", "SIM-MED-01", "2028-03-31T22:00:00-03:00",
                "2028-03-31T23:00:00-03:00");
        assertTrue(codes(unresolved.path("reasons")).contains("CALIBRACAO_SEM_COBERTURA"));
        apply(op, "/inventory/SIM-EC-01/revisions", first, 201);
        apply(op, "/inventory/SIM-EC-02/revisions", samples.at("/meters/1/details"), 201);
        var operating = applyAction(op, "/implantations", "TX-E3-04", operation);
        assertEquals("2027-10-01", operating.at("/snapshot/operation/creditStart").asText());
        assertEquals("2032-09-30", operating.at("/snapshot/operation/creditEndInclusive").asText());
        int beforeSwap = version;
        var successor = samples.at("/meters/2/details");
        assertError(post(op, "/inventory/SIM-EC-01/revisions", successor, version - 1), 412, "VERSAO_OBSOLETA");
        var swapped = apply(op, "/inventory/SIM-EC-01/revisions", successor, 201);
        assertEquals(2, swapped.at("/snapshot/meters/3/predecessor").asInt());
        assertEquals("SIM-MED-01-CALIBRACAO", body(get(op, "/intake/versions/" + beforeSwap))
                .at("/snapshot/meters/1/details/calibration/id").asText());
        assertTrue(body(get(op, "")).at("/meterWindows/1/effectiveUntilExclusive").asText().contains("2028-04-01"));

        var crossing = reading(op, "SIM-CRUZAMENTO", "SIM-MED-01", "2028-04-01T02:30:00Z", "2028-04-01T03:30:00Z");
        assertEquals("PENDENTE", crossing.path("state").asText());
        assertEquals(Set.of("REVISAO_SEM_COBERTURA"), codes(crossing.path("reasons")));
        assertEquals("12.50", crossing.at("/raw/kwh").asText());
        var previous = reading(op, "SIM-ANTES", "SIM-MED-01", "2028-04-01T02:30:00Z", "2028-04-01T03:00:00Z");
        var next = reading(op, "SIM-DEPOIS", "SIM-MED-03", "2028-04-01T03:00:00Z", "2028-04-01T03:30:00Z");
        assertEquals("COBERTURA_CONFERIDA", previous.path("state").asText());
        assertEquals("COBERTURA_CONFERIDA", next.path("state").asText());
        var expired = reading(op, "SIM-CAL-VENCIDA", "SIM-MED-03", "2028-10-02T00:00:00-03:00", "2028-10-02T01:00:00-03:00");
        assertEquals(Set.of("CALIBRACAO_SEM_COBERTURA"), codes(expired.path("reasons")));
        assertEquals("2028-10-02T00:00:00-03:00", expired.at("/raw/start").asText());
        var window = reading(op, "SIM-JANELA", "SIM-MED-03", "2028-10-01T02:50:00Z", "2028-10-01T03:20:00Z");
        assertTrue(codes(window.path("reasons")).contains("LEITURA_SEM_SEGMENTACAO"));

        var current = body(get(op, ""));
        assertEquals(version, current.at("/snapshot/version").asInt());
        assertEquals(6, current.path("readings").size());
        assertTrue(codes(current.path("realUseBlockers")).containsAll(Set.of("PEN-004", "PEN-010")));
        assertEquals("FORA_DO_ESCOPO", body(get(prep, "/intake/versions/1")).at("/snapshot/intake/state").asText());
    }

    private void seedProject() {
        database.transaction(connection -> {
            PilotSql.update(connection, "INSERT INTO pilot_project VALUES (?, 'ORG-PILOTO', 'SIMULACAO')", project);
            PilotSql.update(connection, """
                    INSERT INTO pilot_membership SELECT identity_id, ?, roles, valid_from, valid_until, power_proof
                    FROM pilot_membership WHERE project_id = 'PIL-SYN-BR-001'
                    """, project);
            return null;
        });
    }

    private ObjectNode ref(String id) { return mapper.createObjectNode().put("id", id).put("version", 1); }

    private ObjectNode reviewDetails() {
        var detail = mapper.createObjectNode();
        detail.set("scopeReview", ref("SIM-PARECER-ESCOPO"));
        detail.set("vigency", ref("SIM-MATRIZ-VIGENCIA"));
        return detail;
    }

    private ObjectNode action(String tx, JsonNode details) {
        return mapper.createObjectNode().put("transitionId", tx).set("details", details);
    }

    private JsonNode applyAction(String auth, String route, String tx, JsonNode detail) throws Exception {
        return apply(auth, route + "/" + project + "/actions", action(tx, detail), 200);
    }

    private void applyRecord(String auth, String type, String proof, String date, boolean conditional) throws Exception {
        apply(auth, "/case-records", mapper.createObjectNode().put("type", type).put("effectiveDate", date)
                .put("status", conditional ? "NAO_APLICAVEL_JUSTIFICADO" : "COMPROVADO_SIMULACAO")
                .put("justification", "Ensaio sintético com prova").set("evidence", ref(proof)), 201);
    }

    private JsonNode reading(String auth, String id, String meter, String start, String end) throws Exception {
        var payload = mapper.createObjectNode().put("originId", id).put("circuit", "SIM-EC-01").put("meterId", meter)
                .put("start", start).put("endExclusive", end).put("kwh", "12.50").set("evidence", ref(meter + "-LEITURA"));
        var response = post(auth, "/inventory/readings", payload, null);
        assertEquals(201, response.statusCode(), response.body());
        return body(response);
    }

    private JsonNode apply(String auth, String route, JsonNode payload, int wanted) throws Exception {
        var response = post(auth, route, payload, version);
        assertEquals(wanted, response.statusCode(), response.body());
        var result = body(response);
        assertEquals(++version, result.at("/snapshot/version").asInt());
        return result;
    }

    private String login(String actor) throws Exception {
        var response = request("POST", "/v1/session", null, mapper.createObjectNode().put("identity", "SIM-" + actor)
                .put("password", "piloto-" + actor.toLowerCase(java.util.Locale.ROOT)), null, null);
        assertEquals(204, response.statusCode(), response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> post(String auth, String route, JsonNode payload, Integer expected) throws Exception {
        return request("POST", path + route, auth, payload, expected, UUID.randomUUID().toString());
    }

    private HttpResponse<String> get(String auth, String route) throws Exception {
        var response = request("GET", path + route, auth, null, null, null);
        assertEquals(200, response.statusCode(), response.body());
        return response;
    }

    private HttpResponse<String> request(String method, String route, String auth, JsonNode payload, Integer expected,
                                         String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base.toString()).resolve(route)).timeout(Duration.ofSeconds(10));
        if (auth != null) request.header("Cookie", auth);
        if (expected != null) request.header("If-Match", "\"" + expected + "\"");
        if (key != null) request.header("Idempotency-Key", key);
        if (payload != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, body(response).path("code").asText());
    }

    private Set<String> codes(JsonNode items) {
        return StreamSupport.stream(items.spliterator(), false).map(item -> item.path("code").asText()).collect(Collectors.toSet());
    }
}
