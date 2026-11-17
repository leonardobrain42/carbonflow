package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EnergyImportsTest {
    @TestHTTPResource URL base;
    @Inject ObjectMapper json;
    @Inject PilotDatabase database;
    @Inject PilotAudit audit;
    @Inject IntakeCatalog catalogue;
    @Inject DurableJobs durableJobs;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final String project = "SIM-E8-004-" + UUID.randomUUID();
    private final String path = "/v1/projects/" + project;
    private String op;
    private String prep;
    private String rev;

    @BeforeEach
    void commissionFixture() throws Exception {
        // A E8-003 exercita as transições na própria suíte. Aqui fixamos sua ficha para isolar lotes.
        var examples = catalogue.examples();
        var meters = examples.meters().stream().filter(item -> !item.details().meterId().equals("SIM-MED-04")).map(item -> {
            var proof = catalogue.require(item.details().calibration(), "CALIBRACAO");
            boolean successor = item.details().meterId().equals("SIM-MED-03");
            return new IntakeModel.MeterRevision(item.circuit(), successor ? 2 : 1, successor ? 1 : null,
                    "SIM-OP", item.details(), OffsetDateTime.parse(proof.details().get("from_")),
                    OffsetDateTime.parse(proof.details().get("untilExclusive")));
        }).toList();
        var proof = new IntakeModel.CaseEntry(UUID.randomUUID(), 1, "SIM-PP",
                new IntakeModel.CaseInput(IntakeModel.RecordType.SOBREPOSICAO, IntakeModel.RecordStatus.COMPROVADO_SIMULACAO,
                        LocalDate.parse("2027-06-30"), "Pesquisa de sobreposição do ensaio", new IntakeModel.Ref("SIM-SOBREPOSICAO", 1)));
        var decision = new IntakeModel.Intake(examples.candidate(), "ENQUADRADO", "SIM-PREP", "SIM-REV",
                null, null, List.of(), List.of(proof.id()));
        var start = LocalDate.parse("2027-10-01");
        var operation = new IntakeModel.Operation(start, start, start.plusYears(5).minusDays(1),
                new IntakeModel.Ref("SIM-COMISSIONAMENTO", 1), "SIM-OP", List.of("SIM-EC-01@1", "SIM-EC-02@1"));
        var state = new IntakeModel.Snapshot(1, "SIMULACAO", decision, List.of(proof), null, operation, meters);
        database.transaction(connection -> {
            PilotSql.update(connection, "INSERT INTO pilot_project VALUES (?, 'ORG-PILOTO', 'SIMULACAO')", project);
            PilotSql.update(connection, """
                    INSERT INTO pilot_membership SELECT identity_id, ?, roles, valid_from, valid_until, power_proof
                    FROM pilot_membership WHERE project_id = 'PIL-SYN-BR-001'
                    """, project);
            var event = audit.record(connection, project, "SIM-OP", "FIXTURE_E8_003", "PERMITIDO", project, java.util.Map.of());
            PilotSql.update(connection, "INSERT INTO pilot_intake_revision VALUES (?,1,NULL,?,?::jsonb)",
                    project, event, audit.encode(state));
            return null;
        });
        op = login("OP");
        prep = login("PREP");
        rev = login("REV");
    }

    @Test
    void classifiesAndAdmitsEdEcWithDuplicateCorrectionAndFrozenHistory() throws Exception {
        byte[] sample;
        try (var fixture = getClass().getResourceAsStream("/fixtures/e8-004-ed-ec.csv")) {
            sample = fixture.readAllBytes();
        }
        assertStatus(403, upload(op, "origem.csv", "SIM-SEM-PROVA", sample));
        var fileResponse = upload(op, "original.csv", "SIM-AUT-ED-EC", sample);
        assertStatus(201, fileResponse);
        var file = body(fileResponse).path("artifact");
        var repeated = body(assertStatus(201, upload(prep, "reenvio.csv", "SIM-AUT-ED-EC", sample)));
        assertTrue(repeated.path("repeated").asBoolean());
        assertEquals(file.path("id"), repeated.at("/artifact/id"));
        assertEquals(FixtureCatalog.sha256(sample), file.path("sha256").asText());
        String raw = "/imports/files/" + file.path("id").asText() + "/versions/1/content";
        assertEquals(new String(sample, StandardCharsets.UTF_8), assertStatus(200, get(op, raw)).body());
        assertStatus(404, get(login("PREP-OUTRA"), raw));
        assertStatus(403, get(login("VVB"), raw));

        String job = queue(op, file);
        assertEquals(job, queue(prep, file));
        var report = waitFor(job, "CONCLUIDO");
        assertEquals(11, report.path("processed").asInt());
        assertEquals(11, report.path("total").asInt());
        assertEquals(json.readTree("""
                {"AUSENTE":1,"CANDIDATA":3,"CONFLITO_ID":1,"DUPLICATA":1,"INVALIDA":2,"PENDENTE":2,"SOBREPOSICAO":1}
                """), report.path("classifications"));
        var lines = body(assertStatus(200, get(rev, "/imports/" + job + "/rows"))).path("lines");
        assertTrue(lines.get(3).at("/fact/kwh").isNull());
        assertEquals("PENDENTE_ZERO", lines.get(4).at("/reasons/0/code").asText());
        assertEquals("0", lines.get(5).at("/fact/kwh").asText());
        assertEquals("12.5", lines.get(0).at("/fact/kwh").asText());
        assertEquals("2028-03-31T08:00:00-03:00", lines.get(0).at("/fact/sourceStart").asText());
        assertEquals(0, body(get(rev, "/observations?period=M1")).size());

        assertStatus(403, act(op, lines.get(0), "PROPOR", null));
        var ed = decide(prep, lines.get(0), "PROPOR", null);
        assertStatus(403, act(prep, ed, "ADMITIR", null));
        decide(rev, ed, "ADMITIR", null);
        var original = decide(rev, decide(prep, lines.get(1), "PROPOR", null), "ADMITIR", null);
        var ref = original.at("/resolution/observation");
        var duplicate = decide(rev, decide(prep, lines.get(2), "PROPOR", null), "ADMITIR", null);
        assertEquals("REPETIDA", duplicate.path("decision").asText());
        assertEquals(ref, duplicate.at("/resolution/observation"));
        assertStatus(422, act(prep, lines.get(4), "PROPOR", null));
        assertEquals("ADMITIDA", decide(rev, decide(prep, lines.get(5), "PROPOR", null), "ADMITIR", null)
                .path("decision").asText());
        assertEquals("REJEITADA", decide(rev, lines.get(3), "REJEITAR", null).path("decision").asText());

        var frozen = body(assertStatus(201, post(rev, "/periods/M1/selections", null, null)));
        assertEquals(3, frozen.path("observations").asInt());
        String freeze = "/periods/M1/selections/" + frozen.path("id").asText();
        var before = body(get(rev, freeze));

        var incompatible = decide(prep, lines.get(9), "PROPOR", null);
        var conflict = body(assertStatus(409, act(rev, incompatible, "ADMITIR", null)));
        assertEquals("CONFLITO_ID", conflict.path("code").asText());
        var overlap = decide(prep, lines.get(10), "PROPOR", null);
        assertEquals("SOBREPOSICAO", body(assertStatus(409, act(rev, overlap, "ADMITIR", null))).path("code").asText());
        var correction = decide(prep, incompatible, "PROPOR", ref);
        assertStatus(412, act(rev, incompatible, "ADMITIR", null));
        var successor = decide(rev, correction, "ADMITIR", null);
        assertEquals(ref.path("id"), successor.at("/resolution/observation/id"));
        assertEquals(2, successor.at("/resolution/observation/version").asInt());
        assertEquals(before, body(get(rev, freeze)));
        String observation = "/observations/" + ref.path("id").asText() + "/versions/";
        assertEquals("30", body(get(rev, observation + "1")).at("/fact/kwh").asText());
        assertEquals("25", body(get(rev, observation + "2")).at("/fact/kwh").asText());
        assertEquals(3, body(get(rev, "/observations?period=M1")).size());
    }

    @Test
    void refusesLimitsAndResumesFromTheLastCommittedChunk() throws Exception {
        String header = String.join(",", EnergyImportModel.HEADER) + "\n";
        byte[] exactBytes = new byte[10_000_000];
        java.util.Arrays.fill(exactBytes, (byte) 'x');
        assertStatus(201, upload(op, "limite-bytes.csv", "SIM-AUT-ED-EC", exactBytes));
        var overBytes = java.util.Arrays.copyOf(exactBytes, exactBytes.length + 1);
        var rejectedBytes = body(assertStatus(413, upload(op, "excedente-bytes.csv", "SIM-AUT-ED-EC", overBytes)));
        assertEquals("ARQUIVO_MUITO_GRANDE", rejectedBytes.path("code").asText());

        byte[] tooMany = (header + "x\n".repeat(10_001)).getBytes(StandardCharsets.UTF_8);
        String rejectedJob = queue(op, body(assertStatus(201, upload(op, "10001.csv", "SIM-AUT-ED-EC", tooMany))).path("artifact"));
        var rejected = waitFor(rejectedJob, "RECUSADO");
        assertEquals(10_001, rejected.path("total").asInt());
        assertEquals(10_001, rejected.at("/classifications/RECUSADAS_LOTE").asInt());
        assertTrue(body(get(rev, "/imports/" + rejectedJob + "/rows")).path("lines").isEmpty());

        String failure = "ALTER TABLE pilot_import_row ADD CONSTRAINT sim_fault_second_chunk "
                + "CHECK (line <= 251 OR classification='AGUARDANDO') NOT VALID";
        database.read(connection -> PilotSql.update(connection, failure));
        String job;
        JsonNode halted;
        try {
            byte[] exactRows = (header + "invalida\n".repeat(10_000)).getBytes(StandardCharsets.UTF_8);
            job = queue(op, body(assertStatus(201, upload(op, "10000.csv", "SIM-AUT-ED-EC", exactRows))).path("artifact"));
            halted = waitFor(job, "FALHA_RECUPERAVEL");
            assertEquals(250, halted.path("processed").asInt());
            var attempts = body(assertStatus(200, get(op, "/jobs/" + job + "/attempts")));
            assertEquals("FALHA_RECUPERAVEL", attempts.at("/0/state").asText());
            assertEquals("CONFIRMADA", attempts.at("/1/state").asText());
            assertEquals(250, halted.at("/classifications/INVALIDA").asInt());
            assertEquals(9750, halted.at("/classifications/AGUARDANDO").asInt());
        } finally {
            database.read(connection -> PilotSql.update(connection, "ALTER TABLE pilot_import_row DROP CONSTRAINT sim_fault_second_chunk"));
        }
        assertStatus(412, post(prep, "/jobs/" + job + "/retry", null, halted.path("version").asInt() - 1));
        assertStatus(202, post(prep, "/imports/" + job + "/resume", null, halted.path("version").asInt()));
        var done = waitFor(job, "CONCLUIDO");
        assertEquals(10_000, done.path("processed").asInt());
        assertEquals("CONFIRMADA", body(assertStatus(200, get(op, "/jobs/" + job + "/attempts"))).at("/0/state").asText());
        assertStatus(404, get(login("PP"), "/jobs/" + job));
        assertEquals(10_000, done.at("/classifications/INVALIDA").asInt());
        var page = body(get(rev, "/imports/" + job + "/rows"));
        assertEquals(50, page.path("lines").size());
        var next = body(get(rev, "/imports/" + job + "/rows?cursor=" + page.path("nextCursor").asText()));
        assertEquals(52, next.at("/lines/0/line").asInt());
    }

    @Test
    void rollsBackBeforeCommitAndReplaysAfterTheResponseIsLost() throws Exception {
        var csv = monitoringSample("M1");
        var file = receive("reenvio.csv", csv);
        var payload = json.readTree("""
                {"artifact":{"id":"%s","version":1},"mappingProfile":{"id":"SIM-CSV-UTF8-ED-EC","version":1},
                 "sourceId":"SIM-OP-01","period":{"id":"M1","version":1}}
                """.formatted(file.path("id").asText()));
        String key = UUID.randomUUID().toString();
        database.read(connection -> PilotSql.update(connection,
                "ALTER TABLE pilot_access_command ADD CONSTRAINT sim_command_commit_fault CHECK (command_key <> '" + key + "') NOT VALID"));
        try {
            assertStatus(500, postWithKey(op, "/imports", payload, null, key));
            assertTrue(body(assertStatus(200, get(op, "/jobs?period=M1"))).isEmpty());
        } finally {
            database.read(connection -> PilotSql.update(connection,
                    "ALTER TABLE pilot_access_command DROP CONSTRAINT sim_command_commit_fault"));
        }
        // O cliente encerra a leitura sem consumir o corpo da resposta de um comando já confirmado.
        var response = http.send(request(path + "/imports", op).header("Idempotency-Key", key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(202, response.statusCode());
        var recovered = body(assertStatus(202, postWithKey(op, "/imports", payload, null, key)));
        String id = recovered.path("jobId").asText();
        assertFalse(recovered.path("repeated").asBoolean());
        var job = waitFor(id, "CONCLUIDO");
        assertEquals(csv.lines().count() - 1, job.path("processed").asInt());
        assertEquals(1, body(assertStatus(200, get(op, "/jobs?period=M1"))).size());
        assertEquals(recovered, body(assertStatus(202, postWithKey(op, "/imports", payload, null, key))));
    }

    @Test
    void fencesAnExpiredGenerationBeforeAndAfterItsAttemptToCommit() throws Exception {
        String id = queue(op, receive("geracao.csv", monitoringSample("M1")));
        waitFor(id, "CONCLUIDO");
        var jobId = UUID.fromString(id);
        var claim = database.transaction(connection -> {
            // O trabalhador normal ignora o estado recuperável enquanto o teste controla o relógio da reivindicação.
            int version = PilotSql.first(connection, """
                    UPDATE pilot_import_job SET state='FALHA_RECUPERAVEL',version=version+1
                    WHERE project_id=? AND id=? RETURNING version
                    """, row -> row.getInt(1), project, jobId);
            int next = PilotSql.first(connection, """
                    SELECT max(generation)+1 FROM pilot_job_attempt WHERE project_id=? AND job_id=? AND kind='IMPORTACAO'
                    """, row -> row.getInt(1), project, jobId);
            UUID event = audit.record(connection, project, "SIM-OP", "ENSAIAR_GERACAO", "PERMITIDO", id, java.util.Map.of());
            PilotSql.update(connection, """
                    INSERT INTO pilot_job_attempt(project_id,kind,job_id,generation,job_version,state,claimed_at,expires_at,event_id)
                    VALUES (?,'IMPORTACAO',?,?,?,'EM_EXECUCAO',clock_timestamp(),clock_timestamp()+interval '1 second',?)
                    """, project, jobId, next, version, event);
            return new DurableJobs.Claim(DurableJobs.Kind.IMPORTACAO, project, jobId, version, next);
        });
        var old = new DurableJobs.Claim(claim.kind(), claim.project(), claim.id(), claim.version(), claim.generation() - 1);
        assertFalse(durableJobs.process(old, connection -> { fail("Geração anterior recebeu o efeito"); return null; }));
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        assertFalse(durableJobs.process(claim, connection -> {
            entered.set(true);
            PilotSql.update(connection, "UPDATE pilot_import_job SET message='EFEITO_ATRASADO' WHERE project_id=? AND id=?", project, jobId);
            PilotSql.first(connection, "SELECT pg_sleep(1.1)", row -> true);
            return null;
        }));
        assertTrue(entered.get());
        var still = body(assertStatus(200, get(op, "/jobs/" + id)));
        assertFalse("EFEITO_ATRASADO".equals(still.path("message").asText()));
        assertEquals(claim.version(), still.path("version").asInt());
        assertFalse(durableJobs.process(claim, connection -> { fail("Geração vencida recebeu o efeito"); return null; }));
    }

    @Test
    void retainsEvidenceThroughTheInclusiveFloorAndRecalculatesItWithVersionedProof() throws Exception {
        var sample = receive("retencao.csv", monitoringSample("M1"));
        assertStatus(201, post(prep, "/dossiers", json.createObjectNode().put("sample", ProtectedArtifacts.SAMPLE), null));
        var first = body(assertStatus(200, get(op, "/retention?asOf=2034-09-29")));
        assertEquals("2032-09-30", first.at("/creditEnd/date").asText());
        assertEquals("2034-09-30", first.at("/categories/0/floorInclusive").asText());
        assertEquals("GUARDA_MINIMA", first.at("/categories/0/status").asText());
        assertEquals("GUARDA_MINIMA", body(get(op, "/retention?asOf=2034-09-30")).at("/categories/0/status").asText());
        assertEquals("REAVALIAR_RETENCAO", body(get(op, "/retention?asOf=2034-10-01")).at("/categories/0/status").asText());
        assertTrue(first.at("/categories/3/floorInclusive").isNull());
        assertEquals("POLITICA_PENDENTE", first.at("/categories/3/status").asText());

        var correction = json.createObjectNode().put("endsOn", "2033-09-30").put("reason", "Novo fim do último período na simulação.");
        correction.putObject("proof").put("id", "SIM-FIM-CREDITO").put("version", 1);
        assertStatus(403, post(op, "/retention/credit-end", correction, 0));
        assertEquals(1, body(assertStatus(200, post(rev, "/retention/credit-end", correction, 0))).path("version").asInt());
        assertStatus(412, post(rev, "/retention/credit-end", correction, 0));
        var revised = body(get(op, "/retention?asOf=2034-10-01"));
        assertEquals("2035-09-30", revised.at("/categories/0/floorInclusive").asText());
        assertEquals("GUARDA_MINIMA", revised.at("/categories/0/status").asText());
        long stored = revised.at("/storage/usedBytes").asLong();
        assertTrue(stored > sample.path("size").asLong());
        var quota = new EvidenceQuota(stored);
        database.transaction(connection -> {
            assertEquals(stored, quota.usage(connection, project).usedBytes());
            assertEquals("LIMITE_DE_CAPACIDADE",
                    assertThrows(PilotProblem.class, () -> quota.requireSpace(connection, project, 1)).code);
            return null;
        });
        var original = assertStatus(200, get(op, "/imports/files/" + sample.path("id").asText() + "/versions/1/content"));
        assertEquals(sample.path("sha256").asText(), FixtureCatalog.sha256(original.body().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void calculatesBothPeriodsFromAdmittedFactsAndReexecutesExactMemoryAfterNewCandidate() throws Exception {
        var exported = json.createArrayNode();
        for (var period : List.of("M1", "M2")) {
            boolean first = period.equals("M1");
            var csv = String.join(",", EnergyImportModel.HEADER) + "\n"
                    + pair(period + "-A", first ? "2027-10-02" : "2028-10-02", first ? "2700" : "1800", first ? "3000" : "2200")
                    + pair(period + "-B", first ? "2028-01-02" : "2029-01-02", first ? "6300" : "6200", first ? "7000" : "6800");
            var selection = admitAndSave(period, csv);
            assertEquals(4, selection.path("observations").asInt());
            var proposal = calculation(period, selection);
            var missingProof = proposal.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) missingProof.path("proposal")).remove("dy");
            assertEquals("PROVA_INSUFICIENTE", body(assertStatus(422, post(prep, "/runs", missingProof, null))).path("code").asText());

            if (!first) {
                var alternate = proposal.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) alternate.at("/proposal/factor")).put("id", "EF-SIM-PUBLICADO");
                assertEquals("FATOR_NAO_FIXADO", body(assertStatus(422, post(prep, "/runs", alternate, null))).path("code").asText());
            }

            var run = body(assertStatus(201, post(prep, "/runs", proposal, null)));
            String route = "/runs/" + run.path("id").asText();
            assertStatus(403, post(prep, route + "/actions", confirm(), 1));
            assertStatus(202, post(rev, route + "/actions", confirm(), 1));
            var done = waitCalculation(route, "CALCULADA");
            var result = done.path("result");
            assertEquals(first ? "6" : "5.3", result.at("/totals/er").asText());
            assertEquals(first ? "1.8" : "1.14", result.at("/cuts/0/emissions/er").asText());
            assertEquals(first ? "4.2" : "4.16", result.at("/cuts/1/emissions/er").asText());
            assertEquals(first ? "9" : "8", result.at("/totals/be").asText());
            assertEquals(first ? "3" : "2.7", result.at("/totals/pe").asText());
            assertEquals("0", result.at("/totals/le").asText());
            assertEquals("0.0003", result.path("factorPerKwh").asText());
            assertEquals("0", result.at("/comparison/percent").asText());
            assertFalse(result.path("readyForClosing").asBoolean());

            var snapshot = assertStatus(200, get(rev, route + "/snapshot"));
            assertEquals(FixtureCatalog.sha256(snapshot.body().getBytes(StandardCharsets.UTF_8)), done.path("snapshotSha256").asText());
            var parsed = body(snapshot);
            assertEquals(4, parsed.path("observations").asInt());
            assertEquals(64, parsed.path("observationsSha256").asText().length());
            assertEquals("EF-SYN-v1", parsed.at("/inputs/factor/reference/id").asText());
            assertEquals(20, result.path("evrTotal").asInt());
            assertEquals("20", result.path("afec").asText());
            assertEquals("30", result.path("mpg").asText());
            assertEquals("SIM-SOBREPOSICAO", parsed.at("/inputs/discount/evidence/id").asText());

            if (first) {
                var published = json.readTree("""
                        {"code":"EF-SIM-PUBLICADO","value":"0.25","unit":"tCO2/MWh","grid":"OUTRA-REDE",
                         "sourceYears":[2024,2025,2026],"validFrom":"2027-10-01","validUntilExclusive":"2029-10-01",
                         "source":"SIMULAÇÃO · boletim de teste","published":true}
                        """);
                assertStatus(201, post(prep, "/parameters", published, 0));
                assertStatus(412, post(prep, "/parameters", published, 0));
                var updated = published.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) updated).put("value", "0.26");
                assertStatus(201, post(prep, "/parameters", updated, 1));
                assertStatus(200, post(rev, "/parameters/EF-SIM-PUBLICADO/actions",
                        json.readTree("{\"transitionId\":\"EXCLUIR\",\"reason\":\"Rede incompatível\"}"), 1));
                var factors = body(get(rev, "/parameters")).path("factors");
                assertEquals("FIXADO", factors.get(0).path("state").asText());
                assertEquals("0.26", factors.get(2).path("value").asText());
                assertEquals("EXCLUIDO", factors.get(3).path("state").asText());
            }
            var replay = body(assertStatus(200, post(rev, route + "/reexecute", null, null)));
            assertTrue(replay.path("matches").asBoolean());
            assertEquals(result, replay.path("result"));
            assertEquals(snapshot.body(), assertStatus(200, get(rev, route + "/snapshot")).body());
            var evidence = exported.addObject();
            evidence.set("run", done);
            evidence.set("snapshot", parsed);
            evidence.set("reexecution", replay);
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/e8-005-admitted-reference.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(exported));
    }

    @Test
    void preservesSignedResultAndDoesNotFillAbsentEcOrDivideByZero() throws Exception {
        var csv = String.join(",", EnergyImportModel.HEADER) + "\n"
                + pair("NEGATIVO", "2027-10-02", "1", "20")
                + ed("SEM-EC", "2028-01-02", "3");
        var selection = admitAndSave("M1", csv);
        var proposal = body(assertStatus(201, post(prep, "/runs", calculation("M1", selection), null)));
        String route = "/runs/" + proposal.path("id").asText();
        assertStatus(202, post(rev, route + "/actions", confirm(), 1));
        var run = waitCalculation(route, "COM_LACUNAS");
        assertEquals("-0.005", run.at("/result/cuts/0/emissions/er").asText());
        assertTrue(run.at("/result/cuts/1/inputs/ecKwh").isNull());
        assertEquals("0.003", run.at("/result/cuts/1/emissions/be").asText());
        assertTrue(run.at("/result/cuts/1/emissions/pe").isNull());
        assertTrue(run.at("/result/totals/er").isNull());
        assertFalse(run.at("/result/readyForClosing").asBoolean());
        assertNull(SelectedCalculation.compare("0", "-2").percent());
        assertEquals("2", SelectedCalculation.compare("0", "-2").magnitude());
    }

    @Test
    void closesBothWindowsAndReopensWithoutChangingHistoricalBytesOrM2Collection() throws Exception {
        String pp = login("PP");
        var renewal = catalogue.examples().meters().getLast();
        assertStatus(201, post(op, "/inventory/SIM-EC-01/revisions", json.valueToTree(renewal.details()), 1));
        plan("M1", prep);
        plan("M2", pp);
        String m1 = "/periods/M1/actions";
        String m2 = "/periods/M2/actions";
        assertStatus(403, post(rev, m1, periodAction("41", "2027-10-01", null, null), 1));
        periodStep(op, m1, "41", "2027-10-01", 1, "COLETANDO");
        assertStatus(409, post(op, m1, periodAction("42", "2028-09-30", null, null), 2));
        assertStatus(409, post(op, m2, periodAction("41", "2028-09-30", null, null), 1));

        String[] lines = monitoringSample("M1").split("\n");
        var partial = admitAndSave("M1", String.join("\n", java.util.Arrays.copyOf(lines, lines.length - 1)) + "\n");
        var partialRun = done("M1", partial, "CALCULADA");
        assertTrue(partialRun.at("/result/totals/er").isTextual());

        var endM1 = periodStep(op, m1, "42", "2028-10-01", 2, "EM_CONFERENCIA");
        var openM2 = periodStep(op, m2, "41", "2028-10-01", 1, "COLETANDO");
        assertEquals(endM1.path("effectiveAt"), openM2.path("effectiveAt"));
        assertStatus(412, post(op, m2, periodAction("41", "2028-10-01", null, null), 1));
        var incomplete = body(assertStatus(200, get(rev, "/periods/M1/coverage?run=" + partialRun.path("id").asText()
                + "&version=" + partialRun.path("version").asInt())));
        assertFalse(incomplete.path("complete").asBoolean());
        assertEquals("SIM-EC-02", incomplete.at("/examples/0/channel").asText());
        assertStatus(409, post(rev, m1, periodAction("45", "2028-10-01", partialRun, null), 3));
        assertStatus(200, post(rev, m1, periodAction("43", "2028-10-01", partialRun, null), 3));
        assertStatus(409, post(prep, m1, periodAction("44", "2028-10-01", null, UUID.randomUUID().toString()), 4));

        String correction = lines[0] + "\n" + lines[lines.length - 1] + "\n";
        var received = receive("recuperacao.csv", correction);
        var recovered = importedRows(received, "M1");
        decide(rev, decide(prep, recovered.get(0), "PROPOR", null), "ADMITIR", null);
        assertStatus(200, post(prep, m1, periodAction("44", "2028-10-02", null, received.path("id").asText()), 4));

        var selectionM1 = body(assertStatus(201, post(rev, "/periods/M1/selections", null, null)));
        var runM1 = done("M1", selectionM1, "CALCULADA");
        assertEquals("6", runM1.at("/result/totals/er").asText());
        assertStatus(409, post(rev, m1, periodAction("45", "2028-10-02", partialRun, null), 5));

        // Duas decisões concorrentes sobre a mesma versão. Só uma publica o fechamento.
        var close = periodAction("45", "2028-10-02", runM1, null);
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> postStatus(rev, m1, close, 5));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> postStatus(rev, m1, close, 5));
        assertEquals(List.of(200, 412), java.util.stream.Stream.of(first.get(), second.get()).sorted().toList());
        String M1_CLOSURE = "/periods/M1/closures/1";
        var original = assertStatus(200, get(rev, M1_CLOSURE));
        assertEquals("true", original.headers().firstValue("X-Eligible-For-New-Use").orElseThrow());
        assertEquals(FixtureCatalog.sha256(original.body().getBytes(StandardCharsets.UTF_8)),
                original.headers().firstValue("X-Closure-SHA256").orElseThrow());
        var frozen = body(original);
        assertEquals("1.8", frozen.at("/memory/cuts/0/emissions/er").asText());
        assertEquals("4.2", frozen.at("/memory/cuts/1/emissions/er").asText());
        assertEquals("0.30", frozen.at("/memory/factorPerMwh").asText());

        var dossier = body(assertStatus(201, post(prep, "/dossiers", json.readTree("{\"sample\":\"E8-002@1\"}"), null)));
        var artifact = body(assertStatus(200, get(rev, "/artifacts"))).get(0);
        var reference = json.createObjectNode().put("id", artifact.path("id").asText()).put("version", artifact.path("version").asInt());
        var approval = json.createObjectNode().put("transitionId", "TX-E3-08");
        approval.putObject("details").set("artifact", reference);
        assertStatus(200, post(rev, "/dossiers/" + dossier.path("id").asText() + "/actions", approval, 1));
        String packageRoute = "/artifacts/" + reference.path("id").asText() + "/versions/" + reference.path("version").asInt() + "/content";
        var packageBefore = assertStatus(200, get(rev, packageRoute));
        var packageLink = json.createObjectNode().put("kind", "PACOTE").put("reference", reference.path("id").asText())
                .put("version", reference.path("version").asInt()).put("status", "VINCULADO");
        String links = "/periods/M1/dependents";
        assertStatus(201, post(pp, links, packageLink, 6));
        for (var waiting : List.of(List.of("SIM-V1", "EM_REVISAO"), List.of("SIM-I1", "AGUARDANDO_DEPOSITO"))) {
            var pending = json.createObjectNode().put("kind", "DECISAO").put("reference", waiting.get(0))
                    .put("version", 1).put("status", waiting.get(1));
            assertStatus(201, post(pp, links, pending, 6));
        }

        // OP entrega M2 e o REV admite suas leituras enquanto o retorno de I1 está pendente.
        var selectionM2 = admitAndSave("M2", monitoringSample("M2"));
        var runM2 = done("M2", selectionM2, "CALCULADA");
        assertEquals("5.3", runM2.at("/result/totals/er").asText());
        assertEquals("COLETANDO", body(get(rev, "/periods")).get(1).path("state").asText());
        var external = json.createObjectNode().put("kind", "ATO_EXTERNO").put("reference", "SIM-V1")
                .put("version", 1).put("status", "APROVADO");
        assertStatus(422, post(pp, links, external, 6));
        external.putObject("proof").put("id", "SIM-APROVA-V1").put("version", 1);
        assertStatus(201, post(pp, links, external, 6));

        assertStatus(403, post(prep, m1, periodAction("46", "2028-10-15", null, null), 6));
        var reopened = periodStep(rev, m1, "46", "2028-10-15", 6, "EM_CONFERENCIA");
        assertEquals(2, reopened.path("revision").asInt());
        assertEquals(1, reopened.path("predecessor").asInt());
        assertEquals(4, reopened.at("/impact/dependents").size());
        assertTrue(reopened.at("/impact/divergenceId").isTextual());
        assertStatus(409, post(pp, links, packageLink, 7));
        var grant = json.createObjectNode().put("purpose", "AUDITORIA_V1").put("recipient", "SIM-VVB")
                .put("expiresAt", OffsetDateTime.now().plusDays(2).toString());
        grant.set("artifact", reference);
        assertEquals("PACOTE_AFETADO_POR_REABERTURA", body(assertStatus(409, post(pp, "/grants", grant, null))).path("code").asText());
        assertStatus(409, post(rev, m1, periodAction("45", "2028-10-15", runM1, null), 7));
        var historical = assertStatus(200, get(rev, M1_CLOSURE));
        assertEquals("false", historical.headers().firstValue("X-Eligible-For-New-Use").orElseThrow());
        assertEquals(original.body(), historical.body());
        assertEquals(packageBefore.body(), assertStatus(200, get(rev, packageRoute)).body());
        assertTrue(body(assertStatus(200, post(rev, "/runs/" + runM1.path("id").asText() + "/reexecute", null, null)))
                .path("matches").asBoolean());
        assertEquals("COLETANDO", body(get(rev, "/periods")).get(1).path("state").asText());

        var originals = body(get(rev, "/periods/M1/selections/" + selectionM1.path("id").asText())).path("observations");
        var predecessor = java.util.stream.StreamSupport.stream(originals.spliterator(), false)
                .filter(item -> item.at("/fact/sourceFactId").asText().equals("SIM-E8-006-M1-ED-1-01")).findFirst().orElseThrow().path("ref");
        var successorCsv = lines[0] + "\n" + lines[1].replace(",1300,kWh", ",1350,kWh") + "\n";
        var successor = importedRows(receive("ajuste.csv", successorCsv), "M1").get(0);
        decide(rev, decide(prep, successor, "PROPOR", predecessor), "ADMITIR", null);
        var successorSelection = body(assertStatus(201, post(rev, "/periods/M1/selections", null, null)));
        var rerun = done("M1", successorSelection, "CALCULADA");
        assertStatus(200, post(rev, m1, periodAction("45", "2028-10-15", rerun, null), 7));
        var successorClosure = body(assertStatus(200, get(rev, "/periods/M1/closures/2")));
        assertEquals("6.05", successorClosure.at("/memory/totals/er").asText());
        assertStatus(409, post(pp, links, packageLink, 8));
        assertEquals(original.body(), assertStatus(200, get(rev, M1_CLOSURE)).body());

        periodStep(op, m2, "42", "2029-10-01", 2, "EM_CONFERENCIA");
        assertStatus(200, post(rev, m2, periodAction("45", "2029-10-01", runM2, null), 3));
        var m2Closure = body(assertStatus(200, get(rev, "/periods/M2/closures/1")));
        assertEquals("1.14", m2Closure.at("/memory/cuts/0/emissions/er").asText());
        assertEquals("4.16", m2Closure.at("/memory/cuts/1/emissions/er").asText());

        var evidence = json.createObjectNode();
        evidence.set("sameBoundary", json.createArrayNode().add(endM1).add(openM2));
        evidence.set("coverageGap", incomplete);
        evidence.set("M1", frozen);
        evidence.set("M2", m2Closure);
        evidence.set("reopened", reopened);
        evidence.set("M1Successor", successorClosure);
        evidence.set("M1History", body(get(rev, "/periods/M1/history")));
        evidence.set("M2History", body(get(rev, "/periods/M2/history")));
        writeEvidence("e8-006-closures.json", evidence);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/e8-006-M1-r1.json"), original.body());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/e8-006-M1-r1-after-reopen.json"), historical.body());
    }

    @Test
    void admitsUtcCrossingOnlyWithMeasuredReconciledSegments() throws Exception {
        var evidence = json.createArrayNode();
        for (var cut : List.of(List.of("2028-01-01", "M1", "M1"), List.of("2028-10-01", "M1", "M2"),
                List.of("2029-01-01", "M2", "M2"))) {
            var boundary = cut.get(0);
            String start = boundary + "T02:00:00Z";
            String middle = boundary + "T03:00:00Z";
            String end = boundary + "T04:00:00Z";
            String header = String.join(",", EnergyImportModel.HEADER) + "\n";
            var parent = importedRows(receive("agregado.csv", header + measured("PAI", start, end, "8")), cut.get(1)).get(0);
            var left = importedRows(receive("esquerda.csv", header + measured("ESQUERDA", start, middle, "2")), cut.get(1)).get(0);
            var right = importedRows(receive("direita.csv", header + measured("DIREITA", middle, end, "6")), cut.get(2)).get(0);
            var wrong = importedRows(receive("incorreta.csv", header + measured("ERRADA", middle, end, "5")), cut.get(2)).get(0);
            assertEquals("LEITURA_SEM_SEGMENTACAO", parent.at("/reasons/0/code").asText());
            assertStatus(422, act(prep, parent, "PROPOR", null));
            assertStatus(422, act(prep, left, "PROPOR", null));
            parent = decide(rev, parent, "REJEITAR", null);
            assertStatus(422, act(prep, left, "PROPOR", null));
            var splitRoute = "/observations/" + parent.path("id").asText() + "/measured-split";
            var payload = json.createObjectNode().put("proof", "SIM-PROVA-MEDICAO-UTC").put("reason", "Segmentos da leitura registrados na origem");
            payload.set("segments", json.createArrayNode().add(rowRef(left)).add(rowRef(wrong)));
            assertStatus(422, post(op, splitRoute, payload, parent.path("version").asInt()));
            payload.set("segments", json.createArrayNode().add(rowRef(right)).add(rowRef(left)));
            assertStatus(403, post(prep, splitRoute, payload, parent.path("version").asInt()));
            var saved = body(assertStatus(201, post(op, splitRoute, payload, parent.path("version").asInt())));
            assertEquals("2", saved.at("/segments/0/fact/kwh").asText());
            assertEquals("6", saved.at("/segments/1/fact/kwh").asText());
            decide(rev, decide(prep, left, "PROPOR", null), "ADMITIR", null);
            decide(rev, decide(prep, right, "PROPOR", null), "ADMITIR", null);
            assertStatus(422, act(prep, wrong, "PROPOR", null));
            assertEquals(saved, body(assertStatus(200, get(rev, splitRoute))));
            evidence.add(saved);
        }
        writeEvidence("e8-006-segments.json", evidence);
    }

    private String measured(String id, String start, String end, String kwh) {
        return "EC,SIM-" + id + "-" + start.substring(0,10) + "," + start + "," + end
                + ",America/Sao_Paulo,SIM-PATIO-01,,,SIM-EC-02,SIM-MED-02," + kwh + ",kWh\n";
    }

    private JsonNode rowRef(JsonNode row) {
        return json.createObjectNode().put("id", row.path("id").asText()).put("version", row.path("version").asInt());
    }

    private String monitoringSample(String period) throws Exception {
        return assertStatus(200, get(op, "/imports/example/monitoring/" + period)).body();
    }

    private JsonNode receive(String name, String csv) throws Exception {
        return body(assertStatus(201, upload(op, name, "SIM-AUT-ED-EC", csv.getBytes(StandardCharsets.UTF_8)))).path("artifact");
    }

    private JsonNode importedRows(JsonNode file, String period) throws Exception {
        String job = queue(op, file, period);
        waitFor(job, "CONCLUIDO");
        return body(get(rev, "/imports/" + job + "/rows")).path("lines");
    }

    private void plan(String id, String actor) throws Exception {
        String start = id.equals("M1") ? "2027-10-01" : "2028-10-01";
        var input = json.createObjectNode().put("id", id).put("start", start)
                .put("endExclusive", LocalDate.parse(start).plusYears(1).toString()).put("reason", "Calendário do kit");
        assertStatus(403, post(op, "/periods", input, 0));
        assertStatus(201, post(actor, "/periods", input, 0));
    }

    private JsonNode periodAction(String step, String day, JsonNode run, String artifact) {
        var body = json.createObjectNode().put("transitionId", "TX-E3-" + step)
                .put("effectiveAt", day + "T03:00:00Z").put("reason", "Conferência do período sintético");
        if (run != null) body.set("run", rowRef(run));
        if (artifact != null) body.put("recoveryArtifact", artifact);
        return body;
    }

    private JsonNode periodStep(String actor, String path, String step, String day, int version, String state) throws Exception {
        var result = body(assertStatus(200, post(actor, path, periodAction(step, day, null, null), version)));
        assertEquals(state, result.path("state").asText());
        return result;
    }

    private JsonNode done(String period, JsonNode selection, String expected) throws Exception {
        var run = body(assertStatus(201, post(prep, "/runs", calculation(period, selection), null)));
        String route = "/runs/" + run.path("id").asText();
        assertStatus(202, post(rev, route + "/actions", confirm(), 1));
        return waitCalculation(route, expected);
    }

    private int postStatus(String actor, String route, JsonNode payload, int version) {
        try {
            return post(actor, route, payload, version).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeEvidence(String filename, JsonNode evidence) throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", filename),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    @Test
    void freezesDossiersWithRoleGuardsCleanPublicCopiesAndExactReceipts() throws Exception {
        prepareDossierFixture();
        String pp = login("PP");
        String vvb = login("VVB");
        var development = document("DESENVOLVIMENTO", null, null, null, "2027-08-02");
        String confidential = "RESERVED-PRIVATE-111 private.person@example.invalid";
        development.put("privateNote", confidential);
        var blankCondition = (com.fasterxml.jackson.databind.node.ObjectNode) development.path("conditions").get(0);
        blankCondition.put("reason", "");
        var missing = createDossier(prep, development, 0);
        assertTrue(missing.path("findings").toString().contains("INCIDENCIA_PENDENTE"));
        assertEquals("CHECKLIST_INCOMPLETO", body(assertStatus(409, dossierAction(prep, missing, "TX-E3-06"))).path("code").asText());

        var invalidAuthor = development.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidAuthor.at("/pieces/0/reference")).put("id", "SIM-PREP");
        assertStatus(422, post(prep, "/dossiers", json.createObjectNode().set("document", invalidAuthor), 0));

        var listing = document("LISTAGEM_VALIDACAO", null, null, null, "2027-12-01");
        listing.withArray("pieces").remove(0);
        var noMap = createDossier(prep, listing, 0);
        assertTrue(noMap.path("findings").toString().contains("MAPA"));
        assertStatus(409, dossierAction(prep, noMap, "TX-E3-06"));

        var fixed = document("DESENVOLVIMENTO", null, null, missing, "2027-08-02");
        fixed.put("privateNote", confidential);
        var submitted = body(assertStatus(200, dossierAction(prep, createDossier(prep, fixed, 1), "TX-E3-06")));
        assertStatus(403, dossierAction(pp, submitted, "TX-E3-08"));
        // SIM-REV também tem PREP. O papel de REV sozinho não habilita revisão da própria versão.
        var self = createDossier(rev, document("DESENVOLVIMENTO", null, null, null, "2027-08-02"), 0);
        var selfSubmitted = body(assertStatus(200, dossierAction(rev, self, "TX-E3-06")));
        assertEquals("AUTOAPROVACAO_NEGADA",
                body(assertStatus(403, dossierAction(rev, selfSubmitted, "TX-E3-08"))).path("code").asText());
        var frozen = body(assertStatus(200, dossierAction(rev, submitted, "TX-E3-08")));
        assertEquals(4, frozen.path("variants").size());
        assertStatus(409, dossierAction(rev, frozen, "TX-E3-08"));
        assertStatus(403, get(vvb, "/dossiers"));
        String route = "/dossiers/" + frozen.path("id").asText();
        var registry = exportArgs(frozen, "PUBLICA", "LIMPA", "SIM-REGISTRY");
        assertStatus(403, post(prep, route + "/exports", registry, frozen.path("version").asInt()));
        var real = registry.deepCopy().put("channel", "REGISTRY_REAL");
        assertStatus(422, post(pp, route + "/exports", real, frozen.path("version").asInt()));
        assertStatus(409, post(pp, route + "/exports", exportArgs(frozen, "RESERVADA", "LIMPA", "SIM-REGISTRY"), frozen.path("version").asInt()));
        byte[] publicZip = exportedZip(pp, frozen, "PUBLICA", "LIMPA", "SIM-REGISTRY");
        var publicFiles = unzip(publicZip);
        assertEquals(java.util.Set.of("document.docx", "esg-assessment.docx", "stakeholder-engagement-plan.docx", "manifest.json"), publicFiles.keySet());
        assertTrue(word(publicFiles.get("document.docx")).contains("EF-SYN-v1"));
        assertFalse(word(publicFiles.get("document.docx")).contains(confidential));
        assertFalse(new String(publicFiles.get("manifest.json"), StandardCharsets.UTF_8).contains("SIM-REV"));
        assertFalse(unzip(publicFiles.get("document.docx")).keySet().stream().anyMatch(name -> name.startsWith("docProps")));
        var reservedFiles = unzip(exportedZip(pp, frozen, "RESERVADA", "COMPARADA", "SIM-VVB"));
        assertTrue(word(reservedFiles.get("document.docx")).contains(confidential));
        assertTrue(reservedFiles.containsKey("comparison.docx"));
        assertTrue(reservedFiles.containsKey("evidence/resposta_rodada.json"));
        var publicCompared = unzip(exportedZip(pp, frozen, "PUBLICA", "COMPARADA", "SIM-VVB"));
        assertFalse(word(publicCompared.get("comparison.docx")).contains("example.invalid"));

        var export = body(assertStatus(201, post(pp, route + "/exports", registry, frozen.path("version").asInt())));
        var receipt = json.createObjectNode().put("exportId", export.path("id").asText()).put("sha256", "incorrect")
                .put("destination", "SIM-REGISTRY").put("code", "SIM-RECIBO-E8-007")
                .put("proof", "SIM-PROVA-E8-007").put("receivedAt", "2027-08-03T15:00:00Z");
        receipt.set("dossier", dossierRef(frozen));
        assertEquals("RECIBO_DIVERGENTE", body(assertStatus(409, post(pp, route + "/receipts", receipt, null))).path("code").asText());
        receipt.put("sha256", export.path("sha256").asText());
        var receiptResult = body(assertStatus(201, post(pp, route + "/receipts", receipt, null)));
        assertEquals(receiptResult, body(assertStatus(201, post(pp, route + "/receipts", receipt, null))));
        assertEquals("SIM-PP", receiptResult.path("capturedBy").asText());

        var reserved = java.util.stream.StreamSupport.stream(frozen.path("variants").spliterator(), false)
                .filter(item -> item.path("exposure").asText().equals("RESERVADA") && item.path("edition").asText().equals("LIMPA"))
                .findFirst().orElseThrow();
        String binary = "/artifacts/" + reserved.path("artifactId").asText() + "/versions/" + frozen.path("revision").asInt() + "/content";
        assertStatus(404, get(vvb, binary));
        var grant = json.createObjectNode().put("purpose", "AUDITORIA_VAL").put("recipient", "SIM-VVB")
                .put("expiresAt", OffsetDateTime.now().plusDays(1).toString());
        grant.putObject("artifact").put("id", reserved.path("artifactId").asText()).put("version", frozen.path("revision").asInt());
        var granted = body(assertStatus(201, post(pp, "/grants", grant, null))).path("grant");
        var grantedZip = getBytes(vvb, binary);
        assertEquals(200, grantedZip.statusCode());
        assertTrue(word(unzip(grantedZip.body()).get("document.docx")).contains(confidential));
        String publicArtifactId = frozen.at("/variants/0/artifactId").asText();
        assertStatus(404, get(vvb, binary.replace(reserved.path("artifactId").asText(), publicArtifactId)));
        assertStatus(200, post(pp, "/grants/" + granted.path("id").asText() + "/revoke", null, granted.path("version").asInt()));
        assertStatus(404, get(vvb, binary));

        var newInput = document("DESENVOLVIMENTO", null, null, frozen, "2027-08-04");
        newInput.put("privateNote", "New reserved address new-private@example.invalid");
        var successor = freezeDossier(newInput, frozen.path("version").asInt());
        assertStatus(409, post(pp, route + "/exports", registry, frozen.path("version").asInt()));
        var oldZip = getBytes(pp, route + "/exports/" + export.path("id").asText() + "/content");
        assertArrayEquals(publicZip, oldZip.body());
        var successorFiles = unzip(exportedZip(pp, successor, "PUBLICA", "COMPARADA", "SIM-VVB"));
        assertFalse(word(successorFiles.get("comparison.docx")).contains("example.invalid"));

        var rejectedSource = createDossier(prep, document("LISTAGEM_VALIDACAO", null, null, noMap, "2027-12-01"), 1);
        var returned = body(assertStatus(200, dossierAction(rev, body(assertStatus(200,
                dossierAction(prep, rejectedSource, "TX-E3-06"))), "TX-E3-07")));
        assertEquals("DEVOLVIDO", returned.path("state").asText());
        var rebuilt = freezeDossier(document("LISTAGEM_VALIDACAO", null, null, returned, "2027-12-02"), returned.path("version").asInt());
        var listingBytes = exportedZip(pp, rebuilt, "PUBLICA", "LIMPA", "SIM-REGISTRY");
        var listingFiles = unzip(listingBytes);
        assertTrue(new String(listingFiles.get("boundary.kml"), StandardCharsets.UTF_8).contains("<coordinates>"));
        assertTrue(word(listingFiles.get("document.docx")).contains("Projected emission reductions"));
        java.nio.file.Files.write(java.nio.file.Path.of("target/e8-007-PD-indicativo.zip"), publicZip);
        java.nio.file.Files.write(java.nio.file.Path.of("target/e8-007-PD-completo.zip"), listingBytes);

        var evidence = json.createObjectNode();
        evidence.set("publicManifest", json.readTree(publicFiles.get("manifest.json")));
        evidence.set("receipt", receiptResult);
        evidence.set("successor", successor);
        evidence.set("reopenedListing", rebuilt);
        evidence.set("listingManifest", json.readTree(listingFiles.get("manifest.json")));
        writeEvidence("e8-007-dossiers.json", evidence);
    }

    @Test
    void rendersBothMonitoringReportsFromFrozenMemoriesAndBlocksNewExportAfterReopening() throws Exception {
        prepareDossierFixture();
        String pp = login("PP");
        var renewal = catalogue.examples().meters().getLast();
        assertStatus(201, post(op, "/inventory/SIM-EC-01/revisions", json.valueToTree(renewal.details()), 2));
        var pd = freezeDossier(document("REGISTRO", null, null, null, "2028-03-01"), 0);
        var reports = json.createArrayNode();
        for (String period : List.of("M1", "M2")) {
            String year = period.equals("M1") ? "2028" : "2029";
            plan(period, prep);
            String window = "/periods/" + period;
            periodStep(op, window + "/actions", "41", (Integer.parseInt(year) - 1) + "-10-01", 1, "COLETANDO");
            var run = done(period, admitAndSave(period, monitoringSample(period)), "CALCULADA");
            periodStep(op, window + "/actions", "42", year + "-10-01", 2, "EM_CONFERENCIA");
            assertStatus(200, post(rev, window + "/actions", periodAction("45", year + "-10-01", run, null), 3));
            var closure = assertStatus(200, get(rev, window + "/closures/1"));
            var mr = freezeDossier(document("VERIFICACAO", period, pd, null, year + "-12-01"), 0);
            String dossier = "/dossiers/" + mr.path("id").asText();
            byte[] zip = exportedZip(pp, mr, "RESERVADA", "LIMPA", "SIM-VVB");
            var files = unzip(zip);
            assertEquals(closure.body(), new String(files.get("evidence/monitoring-closure.json"), StandardCharsets.UTF_8));
            assertEquals(run.path("snapshotSha256").asText(), FixtureCatalog.sha256(files.get("evidence/calculation-snapshot.json")));
            var word = word(files.get("document.docx"));
            assertTrue(word.contains(period.equals("M1") ? "1.8" : "1.14"));
            assertTrue(word.contains(period.equals("M1") ? "4.2" : "4.16"));
            assertTrue(word.contains("EF-SYN-v1"));
            var manifest = json.readTree(files.get("manifest.json"));
            assertEquals(pd.path("id").asText(), manifest.at("/validatedPd/id").asText());
            assertEquals(closure.headers().firstValue("X-Closure-SHA256").orElseThrow(), manifest.path("closureSha256").asText());
            var delivery = body(get(pp, dossier + "/exports")).get(0);
            String historical = dossier + "/exports/" + delivery.path("id").asText() + "/content";
            if ("M1".equals(period)) {
                var reopened = periodStep(rev, window + "/actions", "46", "2028-12-02", 4, "EM_CONFERENCIA");
                assertEquals(2, reopened.at("/impact/dependents").size());
                assertStatus(409, post(pp, dossier + "/exports", exportArgs(mr, "RESERVADA", "LIMPA", "SIM-VVB"), mr.path("version").asInt()));
                assertArrayEquals(zip, getBytes(pp, historical).body());
                assertEquals(manifest, body(assertStatus(200, get(rev, dossier + "/versions/1/manifest/RESERVADA/LIMPA"))));
            }
            reports.add(manifest);
            java.nio.file.Files.write(java.nio.file.Path.of("target/e8-007-" + period + "-MR.zip"), zip);
        }
        writeEvidence("e8-007-mrs.json", reports);
    }

    private void prepareDossierFixture() {
        database.transaction(connection -> {
            var examples = catalogue.examples();
            var records = new java.util.ArrayList<IntakeModel.CaseEntry>();
            for (var type : DocumentRules.intakeRecords(DocumentRules.Act.REGISTRO)) {
                var proof = catalogue.require(new IntakeModel.Ref("SIM-" + type.name(), 1), type.name());
                records.add(new IntakeModel.CaseEntry(UUID.randomUUID(), 1, "SIM-PP", new IntakeModel.CaseInput(type,
                        IntakeModel.RecordStatus.COMPROVADO_SIMULACAO, proof.date(), "Prova sintética versionada", proof.ref())));
            }
            var rules = new IntakeModel.Rules("0.2.14", LocalDate.parse("2026-11-14"), examples.candidate().referenceActDate(),
                    "Matriz de vigência", "VCS v5.0", "PD/MR 5.0B", "VM0038 v1.1", "VMD0049 v1.1", "TOOL07 v7.0");
            var decision = new IntakeModel.Intake(examples.candidate(), "ENQUADRADO", "SIM-PREP", "SIM-REV", null,
                    rules, List.of(), records.stream().map(IntakeModel.CaseEntry::id).toList());
            var commitment = new IntakeModel.Commitment(LocalDate.parse("2027-07-15"), new IntakeModel.Ref("SIM-COMPROMISSO", 1),
                    "SIM-PP", List.of());
            // Reusar operação e medidores do fixture comum, com a revisão dos documentos registrada à parte.
            String stored = PilotSql.first(connection, "SELECT snapshot FROM pilot_intake_revision WHERE project_id=? AND version=1",
                    row -> row.getString(1), project);
            IntakeModel.Snapshot state;
            try {
                state = json.readValue(stored, IntakeModel.Snapshot.class);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            var next = new IntakeModel.Snapshot(2, "SIMULACAO", decision, records, commitment, state.operation(), state.meters());
            UUID event = audit.record(connection, project, "SIM-REV", "FIXTURE_E8_007", "PERMITIDO", project, java.util.Map.of());
            PilotSql.update(connection, "INSERT INTO pilot_intake_revision VALUES (?,2,1,?,?::jsonb)", project, event, audit.encode(next));
            return null;
        });
    }

    private com.fasterxml.jackson.databind.node.ObjectNode document(String act, String period, JsonNode pd, JsonNode previous, String date) throws Exception {
        var checklist = body(assertStatus(200, get(prep, "/dossiers/checklist?act=" + act
                + (period == null ? "" : "&period=" + period) + "&successor=" + (previous != null))));
        var result = json.createObjectNode().put("act", act).put("actDate", date).put("model", "5.0B").put("language", "en");
        if (period != null) result.putObject("closure").put("period", period).put("revision", 1);
        if (pd != null) result.set("validatedPd", dossierRef(pd));
        if (previous != null) {
            result.set("predecessor", dossierRef(previous));
            result.put("reason", "Correção de rodada na simulação");
        }
        var pieces = result.putArray("pieces");
        for (var item : checklist.path("pieces")) pieces.addObject().put("item", item.path("item").asText()).set("reference", item.path("reference"));
        var conditions = result.putArray("conditions");
        for (var item : checklist.path("conditions")) conditions.addObject().put("item", item.asText())
                .put("incidence", "INCIDE").put("reason", "Checklist aplicável neste ensaio.");
        return result;
    }

    private JsonNode dossierRef(JsonNode item) {
        return json.createObjectNode().put("id", item.path("id").asText()).put("revision", item.path("revision").asInt());
    }

    private JsonNode createDossier(String actor, JsonNode document, int version) throws Exception {
        return body(assertStatus(201, post(actor, "/dossiers", json.createObjectNode().set("document", document), version)));
    }

    private HttpResponse<String> dossierAction(String actor, JsonNode dossier, String step) throws Exception {
        var action = json.createObjectNode().put("transitionId", step).put("reason", "Revisão sintética segregada").put("item", "Mapa")
                .put("responsible", "PREP").put("expectedCorrection", "Conferir o mapa da rodada.");
        action.set("dossier", dossierRef(dossier));
        return post(actor, "/dossiers/" + dossier.path("id").asText() + "/actions", action, dossier.path("version").asInt());
    }

    private JsonNode freezeDossier(JsonNode input, int version) throws Exception {
        var draft = createDossier(prep, input, version);
        assertEquals(0, draft.path("findings").size(), () -> draft.path("findings").toString());
        return body(assertStatus(200, dossierAction(rev, body(assertStatus(200, dossierAction(prep, draft, "TX-E3-06"))), "TX-E3-08")));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode exportArgs(JsonNode dossier, String exposure, String edition, String destination) {
        return json.createObjectNode().put("revision", dossier.path("revision").asInt()).put("exposure", exposure)
                .put("edition", edition).put("destination", destination).put("channel", "ARQUIVO_SIMULADO");
    }

    private byte[] exportedZip(String pp, JsonNode dossier, String exposure, String edition, String destination) throws Exception {
        String path = "/dossiers/" + dossier.path("id").asText() + "/exports";
        var exported = body(assertStatus(201, post(pp, path, exportArgs(dossier, exposure, edition, destination), dossier.path("version").asInt())));
        var response = getBytes(pp, path + "/" + exported.path("id").asText() + "/content");
        assertEquals(200, response.statusCode());
        assertEquals(exported.path("sha256").asText(), FixtureCatalog.sha256(response.body()));
        var files = unzip(response.body());
        var manifest = json.readTree(files.get("manifest.json"));
        for (var file : manifest.path("files")) {
            byte[] data = files.get(file.path("filename").asText());
            assertEquals(file.path("bytes").asInt(), data.length);
            assertEquals(file.path("sha256").asText(), FixtureCatalog.sha256(data));
        }
        return response.body();
    }

    private HttpResponse<byte[]> getBytes(String actor, String route) throws Exception {
        return http.send(request(path + route, actor).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private java.util.Map<String, byte[]> unzip(byte[] zip) throws Exception {
        var files = new java.util.HashMap<String, byte[]>();
        try (var stream = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry entry; (entry = stream.getNextEntry()) != null; ) files.put(entry.getName(), stream.readAllBytes());
        }
        return files;
    }

    private String word(byte[] docx) throws Exception {
        return new String(unzip(docx).get("word/document.xml"), StandardCharsets.UTF_8);
    }

    @Test
    void opensConsultationOnlyOnValidationPublicationAndSeparatesResponsesAndPrivacy() throws Exception {
        prepareDossierFixture();
        String pp = login("PP");
        String vvb = login("VVB");
        String devTarget = listingId("DEV");
        String valTarget = listingId("VAL");
        var pdDev = freezeDossier(document("DESENVOLVIMENTO", null, null, null, "2027-08-02"), 0);
        var pdVal = freezeDossier(document("LISTAGEM_VALIDACAO", null, null, null, "2027-12-01"), 0);
        var devReceipt = listingReceipt(pp, pdDev, "SIM-REGISTRY", "2027-08-02T13:00:00Z");
        var valReceipt = listingReceipt(pp, pdVal, "SIM-HUB", "2027-12-01T13:00:00Z");
        var dev = prepareListing(pp, "DESENVOLVIMENTO", pdDev, null);
        var val = prepareListing(pp, "LISTAGEM_VALIDACAO", pdVal, null);

        dev = listingChannel(pp, dev, pdDev, "INDETERMINADO", "2027-08-02T12:00:00Z", "2030-01-01T03:00:00Z");
        var protocolDev = listingProtocol("TX-E3-09", devReceipt, devTarget);
        assertEquals("CANAL_INDETERMINADO", body(assertStatus(409, listingCall(pp, dev, protocolDev))).path("code").asText());
        dev = listingChannel(pp, dev, pdDev, "ARQUIVO", "2027-08-02T11:00:00Z", "2027-08-02T12:00:00Z");
        assertEquals("PODER_INDISPONIVEL", body(assertStatus(409, listingCall(pp, dev, protocolDev))).path("code").asText());
        dev = listingChannel(pp, dev, pdDev, "ARQUIVO", "2027-08-02T12:00:00Z", "2030-01-01T03:00:00Z");
        assertStatus(422, listingCall(pp, dev, protocolDev.deepCopy().put("mode", "REAL")));
        assertStatus(403, listingCall(prep, dev, protocolDev));
        var uncertain = listingAction("ENVIO_INCERTO", "2027-08-02T15:00:00Z").put("reason", "Resposta incerta no canal");
        dev = listingStep(pp, dev, uncertain);
        assertTrue(dev.at("/attempt/uncertain").asBoolean());
        assertStatus(409, listingCall(pp, dev, uncertain));
        var beforeReceipt = dev;
        assertStatus(409, listingCall(pp, dev, listingProtocol("TX-E3-09", valReceipt, devTarget)));
        dev = listingStep(pp, dev, protocolDev);
        assertEquals(dev, body(assertStatus(200, listingCall(pp, beforeReceipt, protocolDev))));
        assertFalse(dev.at("/attempt/uncertain").asBoolean());
        assertTrue(dev.at("/attempt/consultationId").isNull());

        val = listingChannel(pp, val, pdVal, "DIGITAL_HUMANO", "2027-12-01T12:00:00Z", "2030-01-01T03:00:00Z");
        var protocolVal = listingProtocol("TX-E3-18", valReceipt, valTarget);
        assertEquals("DESENVOLVIMENTO_PENDENTE", body(assertStatus(409, listingCall(pp, val, protocolVal))).path("code").asText());
        var publicationId = listingId("PUB-DEV");
        var pubDev = listingOfficial("TX-E3-14", "2027-08-03T15:00:00Z", publicationId, devTarget)
                .put("page", "https://registry.example.invalid/listagens/" + devTarget);
        assertStatus(409, listingCall(pp, dev, pubDev));
        String invoice = listingId("FEE");
        dev = listingStep(pp, dev, listingOfficial("BOLETO", "2027-08-02T14:00:00Z", invoice, devTarget));
        assertTrue(dev.at("/attempt/consultationId").isNull());
        var pay = listingOfficial("QUITACAO", "2027-08-02T15:00:00Z", listingId("PAID"), devTarget).put("target", invoice);
        dev = listingStep(pp, dev, pay);
        assertEquals("ATO_CONFLITANTE", body(assertStatus(409, listingCall(pp, dev, pay.deepCopy().put("target", listingId("OTHER-FEE"))))).path("code").asText());
        assertTrue(dev.at("/attempt/consultationId").isNull());
        dev = listingStep(pp, dev, pubDev);
        assertEquals("PUBLICADO", dev.at("/attempt/state").asText());
        assertTrue(dev.at("/attempt/consultationId").isNull());

        val = listingStep(pp, val, protocolVal);
        assertTrue(val.at("/attempt/consultationId").isNull());
        var later = body(get(pp, listingRoute(val) + "/readiness?effectiveAt=2028-01-30T13%3A00%3A00Z"));
        assertTrue(later.at("/attempt/consultationId").isNull());
        var valOfficial = listingId("PUB-VAL");
        var pubVal = listingOfficial("TX-E3-14", "2027-12-05T13:00:00Z", valOfficial, valTarget)
                .put("page", "https://registry.example.invalid/listagens/" + valTarget);
        assertStatus(409, listingCall(pp, val, pubVal.deepCopy().put("page", "https://registry.example.invalid/listagens/" + devTarget)));
        var beforePublication = val;
        val = listingStep(pp, val, pubVal);
        assertEquals(val, body(assertStatus(200, listingCall(pp, beforePublication,
                pubVal.deepCopy().put("captureChannel", "CAPTURA_ARQUIVO")))));
        assertEquals("ATO_CONFLITANTE", body(assertStatus(409,
                listingCall(pp, val, pubVal.deepCopy().put("effectiveAt", "2027-12-05T14:00:00Z")))).path("code").asText());
        assertEquals("ATO_CONFLITANTE", body(assertStatus(409,
                listingCall(pp, dev, pubVal))).path("code").asText());
        String consultation = "/consultations/" + val.at("/attempt/consultationId").asText();
        var open = body(assertStatus(200, get(pp, consultation)));
        assertEquals(java.time.Duration.ofDays(30), java.time.Duration.between(
                OffsetDateTime.parse(open.path("opening").asText()), OffsetDateTime.parse(open.path("endExclusive").asText())));
        assertEquals("VERRA", open.at("/publication/authority").asText());
        assertEquals("SIM-PP", open.at("/publication/capturedBy").asText());

        String sensitive = "Contato fictício author@example.invalid";
        var commentBody = listingAction("TX-E3-20", "2027-12-06T13:00:00Z").put("topic", "SEGURANCA")
                .put("body", "Pergunta de ensaio da vizinhança").put("contact", sensitive);
        commentBody.set("proof", listingProof("TX-E3-20", listingId("HUB"), valTarget));
        var inWindow = body(assertStatus(201, post(pp, consultation + "/comments", commentBody, null)));
        assertEquals(inWindow, body(assertStatus(201, post(pp, consultation + "/comments", commentBody, null))));
        assertStatus(409, post(pp, consultation + "/comments", commentBody.deepCopy().put("contact", "Outro contato"), null));
        var lateInput = commentBody.deepCopy().put("effectiveAt", "2028-01-04T13:00:00Z");
        lateInput.set("proof", listingProof("TX-E3-20", listingId("LATE"), valTarget));
        var late = body(assertStatus(201, post(pp, consultation + "/comments", lateInput, null)));
        assertEquals("FORA_DA_JANELA", late.path("window").asText());
        assertEquals("2029-01-04", late.path("responseDue").asText().substring(0,10));

        var close = listingAction("TX-E3-24", "2028-01-04T12:59:59Z").put("noInWindowComments", false);
        close.set("proof", listingProof("TX-E3-24", listingId("END"), valTarget));
        close.set("summary", json.createObjectNode().put("id", "SIM-RESUMO-CONSULTA").put("version",1));
        assertStatus(409, post(pp, consultation + "/actions", close, 1));
        assertEquals("ABERTA", body(get(pp, consultation)).path("state").asText());
        var closed = body(assertStatus(200, post(pp, consultation + "/actions", close.put("effectiveAt", "2028-01-04T13:00:00Z"), 1)));
        assertEquals("ENCERRADA", closed.path("state").asText());
        assertEquals(0, body(get(vvb, consultation + "/comments")).size());

        String commentPath = "/comments/" + inWindow.path("id").asText() + "/actions";
        var forward = listingOfficial("TX-E3-21", "2028-01-04T13:30:00Z", listingId("FWD"), inWindow.path("target").asText())
                .put("vvb", "SIM-VVB");
        var received = body(assertStatus(200, post(pp, commentPath, forward, 1)));
        assertEquals("ENCAMINHADO", received.path("state").asText());
        assertEquals(1, body(get(vvb, "/consultations/assigned")).size());
        assertTrue(body(get(vvb, consultation + "/comments")).at("/0/contact").isNull());
        assertTrue(body(get(rev, consultation + "/comments")).at("/0/contact").isNull());
        assertEquals(sensitive, body(get(pp, consultation + "/comments")).at("/0/contact").asText());

        var reply = listingAction("TX-E3-22", "2028-01-05T13:00:00Z").put("body", "Resposta documentada do proponente");
        reply.set("support", json.createObjectNode().put("id","SIM-PROVA-RESPOSTA-CONSULTA").put("version",1));
        var replied = body(assertStatus(200, post(pp, commentPath, reply, 2)));
        var assessment = listingOfficial("TX-E3-23", "2028-01-06T13:00:00Z", listingId("ASSESS"), inWindow.path("target").asText())
                .put("body", "Resposta revisada de forma independente").put("result", "SUFICIENTE");
        assertStatus(403, post(pp, commentPath, assessment, 3));
        var finding = body(assertStatus(200, post(vvb, commentPath, assessment.deepCopy().put("result","ACHADO"), 3)));
        assertEquals("COM_ACHADO", finding.path("state").asText());
        assertStatus(200, post(pp, commentPath, reply.deepCopy().put("effectiveAt","2028-01-07T13:00:00Z"), 4));
        var finalAssessment = listingOfficial("TX-E3-23", "2028-01-08T13:00:00Z", listingId("ASSESS-FINAL"), inWindow.path("target").asText())
                .put("body","Complementação suficiente").put("result","SUFICIENTE");
        var assessed = body(assertStatus(200, post(vvb, commentPath, finalAssessment, 5)));
        assertEquals("AVALIADO", assessed.path("state").asText());
        assertEquals("SIM-VVB-A", assessed.at("/evaluations/0/capture/authority").asText());
        var complaint = json.createObjectNode().put("type","RECLAMACAO").put("status","PENDENTE").put("effectiveDate","2028-01-08")
                .put("justification","Reclamação independente sobre operação do pátio");
        assertStatus(201, post(pp, "/case-records", complaint, 2));
        var summary = assertStatus(200, get(null, consultation + "/public"));
        assertFalse(summary.body().contains("example.invalid"));
        assertFalse(summary.body().contains("Pergunta de ensaio"));
        assertFalse(summary.body().contains("SIM-PP"));
        assertEquals(1, body(summary).path("inWindow").asInt());
        assertEquals(1, body(summary).path("afterWindow").asInt());
        assertStatus(401, get(null, consultation + "/comments"));

        var evidence = json.createObjectNode();
        evidence.set("development", dev);
        evidence.set("validation", val);
        evidence.set("afterProtocolNoPublication", later);
        evidence.set("publication", open);
        evidence.set("closed", closed);
        evidence.set("forwarding", received);
        evidence.set("response", replied);
        evidence.set("assessment", assessed);
        evidence.set("publicSummary", body(summary));
        evidence.set("listingHistory", body(get(pp, listingRoute(val) + "/history")));
        writeEvidence("e8-008-listing.json", evidence);
    }

    @Test
    void leavesOverdueAttemptPendingUntilProvenAndPreservesReactivationAndSuccession() throws Exception {
        prepareDossierFixture();
        String pp = login("PP");
        String target = listingId("REOPEN");
        var pd = freezeDossier(document("DESENVOLVIMENTO", null, null, null, "2027-08-02"), 0);
        var start = prepareListing(pp, "DESENVOLVIMENTO", pd, null);
        var checked = listingChannel(pp, start, pd, "ARQUIVO", "2027-08-02T12:00:00Z", "2030-01-01T03:00:00Z");
        var uncertain = listingStep(pp, checked, listingAction("ENVIO_INCERTO", "2027-08-02T12:10:00Z").put("reason","Conferir canal"));
        assertStatus(409, listingCall(pp, uncertain, listingAction("CONFIRMAR_AUSENCIA", "2027-08-02T12:30:00Z").put("reason","Consulta ao canal")));
        var confirm = listingAction("CONFIRMAR_AUSENCIA", "2027-08-02T12:30:00Z").put("reason","Consulta ao canal sem recibo");
        confirm.set("support",json.createObjectNode().put("id","SIM-CONFERENCIA-SEM-RECIBO").put("version",1));
        checked = listingStep(pp, uncertain, confirm);
        var receipt = listingReceipt(pp, pd, "SIM-REGISTRY", "2027-08-02T13:00:00Z");
        var protocol = listingStep(pp, checked, listingProtocol("TX-E3-09", receipt, target));
        var demand = listingStep(pp, protocol, listingOfficial("TX-E3-10", "2027-08-03T13:00:00Z", listingId("DEMAND"), target).put("reason","Ajuste exigido"));
        var overdue = body(get(pp, listingRoute(demand) + "/readiness?effectiveAt=2027-12-01T13%3A00%3A00Z"));
        assertTrue(overdue.path("lateResponse").asBoolean());
        assertEquals("PENDENTE", overdue.at("/attempt/state").asText());
        var successorPd = freezeDossier(document("DESENVOLVIMENTO", null, null, pd, "2027-09-01"), pd.path("version").asInt());
        var responseChannel = listingChannel(pp, demand, successorPd, "ARQUIVO", "2027-09-01T12:00:00Z", "2030-01-01T03:00:00Z");
        var lateReceipt = listingReceipt(pp, successorPd, "SIM-REGISTRY", "2027-12-01T13:00:00Z");
        assertEquals("PRAZO_DE_RESPOSTA_VENCIDO", body(assertStatus(409,
                listingCall(pp, responseChannel, listingProtocol("TX-E3-11", lateReceipt, target).put("reason","Resposta tardia")))).path("code").asText());
        var inactive = listingStep(pp, responseChannel, listingOfficial("TX-E3-12", "2027-12-02T13:00:00Z", listingId("INACTIVE"), target).put("reason","Ato de inativação"));
        String invoice = listingId("NEW-FEE");
        inactive = listingStep(pp, inactive, listingOfficial("BOLETO", "2027-12-03T13:00:00Z", invoice, target));
        inactive = listingStep(pp, inactive, listingOfficial("QUITACAO", "2027-12-04T13:00:00Z", listingId("NEW-PAID"), target).put("target",invoice));
        var reactivationReceipt = listingReceipt(pp, successorPd, "SIM-REGISTRY", "2027-12-05T13:00:00Z");
        var reactivation = listingProtocol("TX-E3-13", reactivationReceipt, target).put("reason","Reativação admitida e paga");
        reactivation.set("support",json.createObjectNode().put("id","SIM-PROVA-ADMISSAO-REATIVACAO").put("version",1));
        protocol = listingStep(pp, inactive, reactivation);
        var suspended = listingStep(pp, protocol, listingOfficial("TX-E3-15", "2027-12-06T13:00:00Z", listingId("SUSP"), target).put("reason","Suspensão comprovada"));
        protocol = listingStep(pp, suspended, listingOfficial("TX-E3-16", "2027-12-07T13:00:00Z", listingId("RESUME"), target).put("reason","Retomada comprovada"));
        var rejected = listingStep(pp, protocol, listingOfficial("TX-E3-17", "2027-12-08T13:00:00Z", listingId("REJECT"), target).put("reason","Rejeição comprovada"));
        var finalPd = freezeDossier(document("DESENVOLVIMENTO", null, null, successorPd, "2027-12-09"), successorPd.path("version").asInt());
        assertStatus(409, post(pp, "/listings", json.createObjectNode().put("kind","DESENVOLVIMENTO").set("dossier", dossierRef(finalPd)), null));
        var next = prepareListing(pp, "DESENVOLVIMENTO", finalPd, rejected.at("/attempt/id").asText());
        assertEquals("NOVO", next.at("/attempt/state").asText());
        assertEquals(rejected.at("/attempt/id"), next.at("/attempt/predecessor"));
        var evidence = json.createObjectNode();
        evidence.set("overdue", overdue); evidence.set("reactivated", protocol); evidence.set("rejected", rejected);
        evidence.set("newAttempt", next); evidence.set("history", body(get(pp, listingRoute(rejected) + "/history")));
        writeEvidence("e8-008-recovery.json", evidence);
    }

    private String listingId(String type) { return "SIM-" + type + "-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(java.util.Locale.ROOT); }

    private JsonNode prepareListing(String actor, String kind, JsonNode dossier, String predecessor) throws Exception {
        var payload = json.createObjectNode().put("kind", kind);
        payload.set("dossier", dossierRef(dossier));
        if (predecessor != null) {
            payload.put("predecessor", predecessor);
            payload.putObject("admission").put("id","SIM-PROVA-ADMISSAO-NOVA-TENTATIVA").put("version",1);
        }
        return body(assertStatus(201, post(actor, "/listings", payload, null)));
    }

    private JsonNode listingReceipt(String actor, JsonNode dossier, String destination, String at) throws Exception {
        String route = "/dossiers/" + dossier.path("id").asText();
        var args = exportArgs(dossier, "PUBLICA", "LIMPA", destination);
        if ("SIM-HUB".equals(destination)) args.put("channel", "DIGITAL_HUMANO_SIMULADO");
        var exported = body(assertStatus(201, post(actor, route + "/exports", args, dossier.path("version").asInt())));
        var capture = json.createObjectNode().put("exportId", exported.path("id").asText()).put("sha256", exported.path("sha256").asText())
                .put("destination",destination).put("code",listingId("RECIBO")).put("proof","SIM-PROVA-RECIBO").put("receivedAt",at);
        capture.set("dossier", dossierRef(dossier));
        return body(assertStatus(201, post(actor, route + "/receipts", capture, null))).path("input");
    }

    private JsonNode listingChannel(String actor, JsonNode listing, JsonNode dossier, String channel, String checked, String until) throws Exception {
        var input = json.createObjectNode().put("channel", channel).put("documentEdition","5.0B").put("methodologyEdition","VM0038 v1.1")
                .put("checkedAt", checked).put("powerUntilExclusive", until);
        input.set("dossier", dossierRef(dossier));
        input.putObject("source").put("id","SIM-CANAL-" + channel.replace('_','-')).put("version",1);
        input.putObject("power").put("id","SIM-PODER-LISTAGEM").put("version",1);
        return body(assertStatus(200, post(actor, listingRoute(listing) + "/channel-checks", input, listing.at("/attempt/version").asInt())));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode listingAction(String action, String at) {
        return json.createObjectNode().put("transitionId",action).put("effectiveAt",at).put("mode","SIMULACAO");
    }

    private JsonNode listingProof(String action, String id, String target) {
        var data = json.createObjectNode().put("officialId",id).put("target",target);
        data.putObject("evidence").put("id",ListingEvidence.PROOFS.get(action)).put("version",1);
        return data;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode listingOfficial(String action, String at, String id, String target) {
        var input = listingAction(action,at);
        input.set("proof",listingProof(action,id,target));
        return input;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode listingProtocol(String step, JsonNode receipt, String target) {
        var action = listingAction(step,receipt.path("receivedAt").asText()).put("target",target).put("exportId",receipt.path("exportId").asText());
        action.set("dossier",receipt.path("dossier"));
        return action;
    }

    private String listingRoute(JsonNode listing) { return "/listings/" + listing.at("/attempt/id").asText(); }

    private HttpResponse<String> listingCall(String actor, JsonNode listing, JsonNode payload) throws Exception {
        return post(actor, listingRoute(listing) + "/actions", payload, listing.at("/attempt/version").asInt());
    }

    private JsonNode listingStep(String actor, JsonNode listing, JsonNode payload) throws Exception {
        return body(assertStatus(200, listingCall(actor, listing, payload)));
    }

    @Test
    void validatesWithVvbThenRegistersAndVerifiesTwoPeriodsWithRotationAndIndependentApproval() throws Exception {
        prepareDossierFixture();
        String pp=login("PP"), vvb=login("VVB"), other=login("VVB-B");
        var renewal=catalogue.examples().meters().getLast();
        assertStatus(201,post(op,"/inventory/SIM-EC-01/revisions",json.valueToTree(renewal.details()),2));
        var consult=openValidationConsultation(pp);
        var pd=freezeDossier(document("REGISTRO",null,null,null,"2028-03-01"),0);
        var val=contractAudit(pp,"VAL",pd,consult.path("id").asText(),"SIM-VVB","2027-12-01T12:00:00Z");
        var grant=auditGrant(pp,pd,"AUDITORIA_VAL","SIM-VVB");
        var opening=auditAction(val,pd,"TX-E3-26","2027-12-05T13:00:00Z").put("grantId",grant);
        assertStatus(404,auditCall(other,val,opening));
        assertStatus(403,auditCall(pp,val,opening));
        val=auditStep(vvb,val,opening);
        var visit=auditAction(val,pd,"VISITA","2027-12-20T13:00:00Z").put("reason","Visita em campo no ensaio");
        val=auditStep(vvb,val,visit);
        assertEquals("CONSULTA_PENDENTE",body(assertStatus(409,auditCall(vvb,val,opinion(val,pd,true,"2027-12-21T13:00:00Z")))).path("code").asText());
        var closing=listingAction("TX-E3-24","2028-01-04T13:00:00Z").put("noInWindowComments",true);
        closing.set("proof",listingProof("TX-E3-24",listingId("CLOSE-AUDIT"),consult.path("target").asText()));
        closing.putObject("summary").put("id","SIM-RESUMO-CONSULTA").put("version",1);
        assertStatus(200,post(pp,"/consultations/"+consult.path("id").asText()+"/actions",closing,1));
        val=auditStep(vvb,val,auditAction(val,pd,"TX-E3-27","2028-01-06T13:00:00Z").put("criterion","REG-E2-18").put("reason","Ajustar trilha"));
        String finding=val.at("/audit/findings/0/id").asText();
        var fixed=freezeDossier(document("REGISTRO",null,null,pd,"2028-03-01"),pd.path("version").asInt());
        var grantFixed=auditGrant(pp,fixed,"AUDITORIA_VAL","SIM-VVB");
        var fixReceipt=reservedReceipt(pp,fixed,"2028-03-01T13:00:00Z");
        var response=auditAction(val,fixed,"TX-E3-28","2028-03-01T13:00:00Z").put("grantId",grantFixed).put("finding",finding)
                .put("exportId",fixReceipt.path("exportId").asText()).put("reason","Dossiê revisado entregue");
        val=auditStep(pp,val,response);
        assertEquals("RESPOSTA_ENVIADA",val.at("/audit/state").asText());
        assertStatus(409,auditCall(vvb,val,opinion(val,pd,true,"2028-03-01T14:00:00Z")));
        val=auditStep(vvb,val,auditAction(val,fixed,"TX-E3-29","2028-03-01T14:00:00Z").put("grantId",grantFixed).put("finding",finding)
                .put("accepted",true).put("reason","Achado atendido"));
        assertEquals("VISITA_PENDENTE",body(assertStatus(409,auditCall(vvb,val,opinion(val,fixed,true,"2028-03-01T15:00:00Z")))).path("code").asText());
        val=auditStep(vvb,val,opinion(val,fixed,false,"2028-03-01T15:00:00Z"));
        var blocked=prepareProgram(pp,val,fixed,null);
        var blockedReceipt=listingReceipt(pp,fixed,"SIM-REGISTRY","2028-03-02T13:00:00Z");
        blocked=programChannel(pp,blocked,fixed,"2028-03-02T12:00:00Z");
        assertEquals("PARECER_PENDENTE",body(assertStatus(409,requestCall(pp,blocked,programProtocol(blocked,fixed,blockedReceipt,listingId("REG-BLOCK"),false)))).path("code").asText());

        var regPd=freezeDossier(document("REGISTRO",null,null,fixed,"2028-03-02"),fixed.path("version").asInt());
        var regGrant=auditGrant(pp,regPd,"AUDITORIA_VAL","SIM-VVB");
        var reopen=auditAction(val,regPd,"TX-E3-31","2028-03-02T13:00:00Z").put("grantId",regGrant).put("reason","Retomada com revisão corrigida");
        assertStatus(404,auditCall(other,val,reopen));
        val=auditStep(vvb,val,reopen);
        val=auditStep(vvb,val,auditAction(val,regPd,"VISITA","2027-12-20T13:00:00Z").put("reason","Prova da visita reapreciada no pacote sucessor"));
        val=auditStep(vvb,val,opinion(val,regPd,true,"2028-03-02T14:00:00Z"));
        assertEquals(2,val.at("/audit/opinions").size());
        assertEquals("PARECER_NEGATIVO",val.at("/audit/opinions/0/result").asText());
        var reg=programChannel(pp,blocked,regPd,"2028-03-03T12:00:00Z");
        var regReceipt=listingReceipt(pp,regPd,"SIM-REGISTRY","2028-03-03T13:00:00Z");
        reg=requestStep(pp,reg,programProtocol(reg,regPd,regReceipt,listingId("REG"),false));
        reg=requestStep(pp,reg,requestAct(reg,regPd,"TX-E3-34","2028-03-04T13:00:00Z"));
        reg=requestStep(pp,reg,requestAct(reg,regPd,"TX-E3-35","2028-03-05T13:00:00Z").put("reason","Rodada da revisão externa"));
        var asOf=body(get(pp,"/requests?asOf=2028-07-10T14%3A00%3A00Z")).get(0);
        assertTrue(asOf.path("overdue").asBoolean());
        assertEquals("COM_ACHADO",asOf.at("/request/state").asText());
        reg=requestStep(pp,reg,requestAct(reg,regPd,"TX-E3-38","2028-07-11T13:00:00Z").put("reason","Devolução comprovada"));
        var previousReg=reg;
        reg=prepareProgram(pp,val,regPd,previousReg.at("/request/id").asText());
        reg=programChannel(pp,reg,regPd,"2028-07-12T12:00:00Z");
        var afterCut=listingReceipt(pp,regPd,"SIM-REGISTRY","2029-02-01T13:00:00Z");
        String regTarget=listingId("REG-NOVA");
        assertEquals("REUSO_INDISPONIVEL",body(assertStatus(409,requestCall(pp,reg,programProtocol(reg,regPd,afterCut,regTarget,true)))).path("code").asText());
        var within=listingReceipt(pp,regPd,"SIM-REGISTRY","2028-07-13T13:00:00Z");
        reg=requestStep(pp,reg,programProtocol(reg,regPd,within,regTarget,true));
        reg=requestStep(pp,reg,requestAct(reg,regPd,"TX-E3-37","2028-07-14T13:00:00Z"));
        assertTrue(reg.at("/request/registration").isNull());

        var m1=closedMr(regPd,"M1","2028");
        var auditM1=contractAudit(pp,"M1",m1,null,"SIM-VVB","2028-09-01T13:00:00Z");
        String grantM1=auditGrant(pp,m1,"AUDITORIA_V1","SIM-VVB");
        var m1Opening=auditAction(auditM1,m1,"TX-E3-26","2028-10-02T13:00:00Z").put("grantId",grantM1);
        assertEquals("REGISTRO_PENDENTE",body(assertStatus(409,auditCall(vvb,auditM1,m1Opening))).path("code").asText());
        reg=requestStep(pp,reg,requestAct(reg,regPd,"REGISTRO_COMPROVADO","2028-07-15T13:00:00Z"));
        auditM1=auditStep(vvb,auditM1,m1Opening);
        assertEquals("VISITA_PENDENTE",body(assertStatus(409,auditCall(vvb,auditM1,opinion(auditM1,m1,true,"2028-10-03T13:00:00Z")))).path("code").asText());
        auditM1=auditStep(vvb,auditM1,auditAction(auditM1,m1,"VISITA","2028-09-30T13:00:00Z").put("reason","Visita antes do fim"));
        assertEquals("COBERTURA_PENDENTE",body(assertStatus(409,auditCall(vvb,auditM1,opinion(auditM1,m1,true,"2028-10-03T13:00:00Z")))).path("code").asText());
        String sha=assertStatus(200,get(rev,"/periods/M1/closures/1")).headers().firstValue("X-Closure-SHA256").orElseThrow();
        auditM1=auditStep(vvb,auditM1,auditAction(auditM1,m1,"COBERTURA","2028-10-03T13:00:00Z").put("reason","Cobertura complementar").put("closureSha256",sha));
        auditM1=auditStep(vvb,auditM1,opinion(auditM1,m1,true,"2028-10-04T13:00:00Z"));
        assertEquals(2,auditM1.at("/audit/opinions/0/vintages").size());

        var v1=prepareProgram(pp,auditM1,m1,null);
        v1=programChannel(pp,v1,m1,"2028-12-01T12:00:00Z");
        var v1Receipt=listingReceipt(pp,m1,"SIM-REGISTRY","2028-12-01T13:00:00Z");
        v1=requestStep(pp,v1,programProtocol(v1,m1,v1Receipt,listingId("V1"),false));
        v1=requestStep(pp,v1,requestAct(v1,m1,"TX-E3-34","2028-12-02T13:00:00Z"));
        v1=requestStep(pp,v1,requestAct(v1,m1,"TX-E3-35","2028-12-03T13:00:00Z").put("reason","Relatório de revisão"));
        var adjusted=freezeDossier(document("VERIFICACAO","M1",regPd,m1,"2028-12-04"),m1.path("version").asInt());
        v1=programChannel(pp,v1,adjusted,"2028-12-04T12:00:00Z");
        String adjustedGrant=auditGrant(pp,adjusted,"AUDITORIA_V1","SIM-VVB");
        var amendedReceipt=listingReceipt(pp,adjusted,"SIM-REGISTRY","2028-12-04T13:00:00Z");
        var vvbReply=requestAct(v1,adjusted,"TX-E3-36","2028-12-04T13:00:00Z").put("reason","VVB complementou a revisão")
                .put("grantId",adjustedGrant).put("exportId",amendedReceipt.path("exportId").asText());
        assertStatus(403,requestCall(pp,v1,vvbReply));
        v1=requestStep(vvb,v1,vvbReply);
        assertStatus(409,requestCall(pp,v1,requestAct(v1,m1,"TX-E3-37","2028-12-05T13:00:00Z")));
        v1=requestStep(pp,v1,requestAct(v1,adjusted,"TX-E3-37","2028-12-05T13:00:00Z"));
        assertFalse(v1.path("emissionReady").asBoolean());

        var firstIssuance=startFirstIssuance(pp,v1);
        var m2=closedMr(regPd,"M2","2029");
        var wrong=contractInput("M2",m2,null,"SIM-VVB","2029-09-01T13:00:00Z");
        assertEquals("ROTACAO_VVB_REQUERIDA",body(assertStatus(409,post(pp,"/audits",wrong,null))).path("code").asText());
        var auditM2=contractAudit(pp,"M2",m2,null,"SIM-VVB-B","2029-09-01T13:00:00Z");
        String grantM2=auditGrant(pp,m2,"AUDITORIA_V2","SIM-VVB-B");
        String m1HistoricalGrant=auditGrant(pp,m1,"AUDITORIA_V1","SIM-VVB-B");
        auditM2=auditStep(other,auditM2,auditAction(auditM2,m2,"TX-E3-26","2029-10-02T13:00:00Z").put("grantId",grantM2));
        assertEquals("RISCO_PENDENTE",body(assertStatus(409,auditCall(other,auditM2,opinion(auditM2,m2,true,"2029-10-03T13:00:00Z")))).path("code").asText());
        var risk=auditAction(auditM2,m2,"RISCO","2029-10-03T13:00:00Z").put("reason","Conhecimento do M1 e controles; dispensa no ensaio")
                .put("mandatoryVisit",true).put("waiveVisit",true).put("previousGrant",m1HistoricalGrant);
        assertStatus(409,auditCall(other,auditM2,risk));
        auditM2=auditStep(other,auditM2,risk.put("mandatoryVisit",false));
        auditM2=auditStep(other,auditM2,opinion(auditM2,m2,true,"2029-10-04T13:00:00Z"));
        var v2=prepareProgram(pp,auditM2,m2,null);
        v2=programChannel(pp,v2,m2,"2029-12-03T12:00:00Z");
        var v2Receipt=listingReceipt(pp,m2,"SIM-REGISTRY","2029-12-03T13:00:00Z");
        v2=requestStep(pp,v2,programProtocol(v2,m2,v2Receipt,listingId("V2"),false));
        var approveV2=requestAct(v2,m2,"TX-E3-37","2029-12-04T13:00:00Z");
        var before=v2;
        v2=requestStep(pp,v2,approveV2);
        assertEquals(v2,body(assertStatus(200,requestCall(pp,before,approveV2))));
        assertFalse(v2.path("emissionReady").asBoolean());
        var evidence=json.createObjectNode();
        evidence.set("VAL",val);evidence.set("REG",reg);evidence.set("returnedREG",previousReg);
        evidence.set("M1",auditM1);evidence.set("M2",auditM2);evidence.set("V1",v1);evidence.set("V2",v2);
        evidence.set("overdue",asOf);
        evidence.set("decisionTrail",body(get(pp,"/requests/"+v2.at("/request/id").asText()+"/history")));
        writeEvidence("e8-009-trajectory.json",evidence);
        finishIssuanceCycles(pp,firstIssuance,v2);
    }

    private record IssuanceCycle(String verification, String registry, JsonNode vintage, JsonNode initialPosition,
                                 JsonNode waiting, JsonNode rejected) {}

    private IssuanceCycle startFirstIssuance(String pp, JsonNode v1) throws Exception {
        var cuts=body(assertStatus(200,get(pp,"/verifications"))).get(0);
        String verification=cuts.path("id").asText(), registry=listingId("REGISTRY-V1");
        var vintage=cuts.path("cuts").get(1);
        assertEquals("2028-01-01",vintage.path("start").asText());
        assertEquals("2028-10-01",vintage.path("endExclusive").asText());
        var create=issuanceCreate(verification,vintage,UUID.randomUUID().toString(),"8","SIM-CONTA-01","2028-12-07T13:00:00Z");
        assertEquals("ATIVACAO_PENDENTE",failure(pp,"/issuances",create,null));
        var activation=activation(v1,registry,"2028-12-06T13:00:00Z");
        assertEquals("ATIVACAO_DIVERGENTE",failure(pp,"/verifications/"+verification+"/activation-evidence",
                activation.deepCopy().put("approvalId","SIM-APROVACAO-OUTRA"),0));
        assertStatus(403,post(prep,"/verifications/"+verification+"/activation-evidence",activation,0));
        assertStatus(200,post(pp,"/verifications/"+verification+"/activation-evidence",activation,0));
        assertEquals("POSICAO_AUSENTE",failure(pp,"/issuances",create,null));
        String route="/verifications/"+verification+"/positions";
        var first=position(vintage,registry,"10","2028-12-06T14:00:00Z");
        first.withArray("pending").addObject().put("reference","SIM-PENDENCIA-01").put("amount","2")
                .put("status","NAO_REFLETIDA");
        assertEquals("QUANTIDADE_INVALIDA",failure(pp,route,first.deepCopy().put("official","-1"),null));
        var negative=first.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)negative.withArray("pending").get(0)).put("amount","-2");
        assertEquals("QUANTIDADE_INVALIDA",failure(pp,route,negative,null));
        var wrong=first.deepCopy();
        wrong.withObject("/proof").put("target","SIM-OUTRO-REGISTRO");
        assertEquals("POSICAO_DIVERGENTE",failure(pp,route,wrong,null));
        var uncertain=first.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)uncertain.withArray("pending").get(0)).put("status","INCERTA");
        var held=body(assertStatus(201,post(pp,route,uncertain,null)));
        assertEquals("REFLEXO_INCERTO",held.at("/balance/hold").asText());
        assertEquals("REFLEXO_INCERTO",failure(pp,"/issuances",create,null));
        first.put("asOf","2028-12-06T15:00:00Z");
        first.withObject("/proof").put("officialId",listingId("POS-V1"));
        var position=body(assertStatus(201,post(pp,route,first,null)));
        assertEquals(10,position.at("/position/official").asInt());
        assertEquals(2,position.at("/balance/unreflected").asInt());
        assertEquals(8,position.at("/balance/available").asInt());

        var setup=issuanceCreate(verification,vintage,position.at("/position/id").asText(),"9","SIM-CONTA-01","2028-12-07T13:00:00Z");
        assertEquals("SALDO_INSUFICIENTE",failure(pp,"/issuances",setup,null));
        var firstDraft=issuanceDraft(pp,verification,vintage,position,"8","SIM-CONTA-01","2028-12-07");
        var secondDraft=issuanceDraft(pp,verification,vintage,position,"8","SIM-CONTA-02","2028-12-07");
        var firstReceipt=protocol(firstDraft,"2028-12-07T14:00:00Z");
        var secondReceipt=protocol(secondDraft,"2028-12-07T14:00:00Z");
        HttpResponse<String> firstResult, secondResult;
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(()->issuanceCall(pp,firstDraft,firstReceipt));
            var b=executor.submit(()->issuanceCall(pp,secondDraft,secondReceipt));
            firstResult=a.get();
            secondResult=b.get();
        }
        boolean firstWon=firstResult.statusCode()==200;
        var winner=body(assertStatus(200,firstWon?firstResult:secondResult));
        var loser=body(assertStatus(409,firstWon?secondResult:firstResult));
        assertEquals("SALDO_INSUFICIENTE",loser.path("code").asText());
        assertEquals(0,balance(pp,"V1",vintage).path("available").asInt(-1));
        var invoice=issuanceAct(winner,"TX-E3-50","2028-12-07T15:00:00Z",listingId("COBRANCA")).put("feeAmount","80.00").put("currency","BRL");
        invoice.putArray("lines").add(issuanceLine(vintage,"8.0"));
        var invoiced=issuanceStep(pp,winner,invoice);
        var pay=issuanceAct(invoiced,"TX-E3-51","2028-12-07T16:00:00Z",listingId("QUITA"))
                .put("referenceAct",invoice.at("/proof/officialId").asText()).put("feeAmount","80.00").put("currency","BRL");
        var waiting=issuanceStep(pp,invoiced,pay);
        assertEquals("AGUARDANDO_DEPOSITO",waiting.at("/attempt/state").asText());
        assertEquals(0,waiting.at("/netDeposited/0/amount").asInt(-1));
        return new IssuanceCycle(verification,registry,vintage,position,waiting,loser);
    }

    private void finishIssuanceCycles(String pp, IssuanceCycle first, JsonNode v2) throws Exception {
        String v1Route="/verifications/"+first.verification()+"/positions";
        var v1Cut=first.vintage();
        var v1=issuance(pp,first.waiting());
        assertEquals("AGUARDANDO_DEPOSITO",v1.at("/attempt/state").asText());

        var second=verification(pp,"V2");
        String v2Id=second.path("id").asText(), v2Registry=listingId("REGISTRY-V2");
        var v2Cut=second.path("cuts").get(0);
        assertEquals("2028-10-01",v2Cut.path("start").asText());
        assertEquals("2029-01-01",v2Cut.path("endExclusive").asText());
        String v2Route="/verifications/"+v2Id+"/positions";
        assertStatus(200,post(pp,"/verifications/"+v2Id+"/activation-evidence",activation(v2,v2Registry,"2029-12-05T13:00:00Z"),0));
        assertEquals("POSICAO_DIVERGENTE",failure(pp,v1Route,position(v1Cut,v2Registry,"30","2029-12-05T14:00:00Z"),null));
        var position2=body(assertStatus(201,post(pp,v2Route,position(v2Cut,v2Registry,"30","2029-12-05T14:00:00Z"),null)));
        assertEquals(30,position2.at("/balance/available").asInt());
        assertEquals(0,balance(pp,"V1",v1Cut).path("available").asInt(-1));

        String depositId=listingId("DEPOSITO");
        var deposit=issuanceAct(v1,"TX-E3-52","2028-12-08T14:00:00Z",depositId).put("captureChannel","ARQUIVO");
        deposit.putArray("lines").add(issuanceLine(v1Cut,"5"));
        var deposited=issuanceStep(pp,v1,deposit);
        assertEquals(5,deposited.at("/netDeposited/0/amount").asInt());
        assertEquals("DEPOSITO_REGISTRADO",deposited.at("/attempt/state").asText());
        var repeat=deposit.deepCopy().put("effectiveAt","2028-12-08T11:00:00-03:00").put("captureChannel","DIGITAL_HUMANO");
        repeat.putArray("lines").add(issuanceLine(v1Cut,"5.00"));
        assertEquals(deposited,issuanceStep(pp,v1,repeat));
        var mismatched=repeat.deepCopy();
        mismatched.putArray("lines").add(issuanceLine(v1Cut,"6"));
        var conflict=body(assertStatus(409,issuanceCall(pp,deposited,mismatched)));
        assertEquals("ATO_CONFLITANTE",conflict.path("conflict").asText());
        assertEquals("COM_DIVERGENCIA",conflict.at("/attempt/state").asText());
        assertEquals(5,conflict.at("/netDeposited/0/amount").asInt());

        var release=issuanceAct(conflict,"LIBERAR_REMANESCENTE","2028-12-09T13:00:00Z",listingId("REMANESCENTE"))
                .put("reason","Retorno encerra as três unidades não depositadas.");
        release.set("support",issuanceRef("SIM-PROVA-RESOLUCAO-EMISSAO"));
        var released=issuanceStep(pp,conflict,release);
        var adjust=issuanceAct(released,"AJUSTE","2028-12-09T14:00:00Z",listingId("AJUSTE")).put("referenceAct",depositId);
        adjust.putArray("lines").add(issuanceLine(v1Cut,"-1"));
        var afterAdjustment=position(v1Cut,first.registry(),"4","2028-12-09T14:00:00Z");
        reflected(afterAdjustment,released,"4");
        adjust.set("newPosition",afterAdjustment);
        var adjusted=issuanceStep(pp,released,adjust);
        assertEquals(4,adjusted.at("/netDeposited/0/amount").asInt());
        var head=balance(pp,"V1",v1Cut);
        assertEquals(4,head.at("/position/official").asInt());
        assertEquals(0,head.at("/unreflected").asInt(-1));
        var older=body(assertStatus(201,post(pp,v1Route,position(v1Cut,first.registry(),"13","2028-12-09T13:30:00Z"),null)));
        assertTrue(older.at("/position/historical").asBoolean());
        assertEquals(head,balance(pp,"V1",v1Cut));
        var contradict=position(v1Cut,first.registry(),"7","2028-12-09T14:00:00Z");
        var ordering=body(assertStatus(409,post(pp,v1Route,contradict,null)));
        assertEquals("ORDEM_POSICAO_INDETERMINADA",ordering.path("conflict").asText());
        assertTrue(balance(pp,"V1",v1Cut).path("available").isNull());
        var decisive=position(v1Cut,first.registry(),"4","2028-12-09T15:00:00Z");
        reflected(decisive,released,"4");
        assertStatus(201,post(pp,v1Route,decisive,null));

        var compare=issuanceAct(adjusted,"COMPARAR","2028-12-09T16:00:00Z",null);
        compare.set("support",issuanceRef("SIM-PROVA-RESOLUCAO-EMISSAO"));
        assertStatus(403,issuanceCall(pp,adjusted,compare));
        var compared=issuanceStep(prep,adjusted,compare);
        assertEquals(4,compared.at("/attempt/comparison/lines/0/available").asInt());
        var conciliated=reviewIssuance(compared,"2028-12-09T17:00:00Z");
        assertEquals(4,balance(pp,"V1",v1Cut).path("available").asInt());

        var successor=issuanceCreate(first.verification(),v1Cut,balance(pp,"V1",v1Cut).at("/position/id").asText(),
                "1","SIM-CONTA-02","2028-12-10T13:00:00Z").put("predecessor",conciliated.at("/attempt/id").asText());
        var successorDraft=body(assertStatus(201,post(pp,"/issuances",successor,null)));
        assertEquals(conciliated.at("/attempt/id"),successorDraft.at("/attempt/predecessor"));

        var secondDraft=issuanceDraft(pp,v2Id,v2Cut,position2,"2","SIM-CONTA-02","2029-12-06");
        var secondProtocol=issuanceStep(pp,secondDraft,protocol(secondDraft,"2029-12-06T14:00:00Z"));
        var official=issuanceAct(secondProtocol,"TX-E3-57","2029-12-06T15:00:00Z",listingId("SEM-PAGAMENTO"));
        var secondWaiting=issuanceStep(pp,secondProtocol,official);
        var reused=issuanceAct(secondWaiting,"TX-E3-52","2029-12-06T16:00:00Z",depositId);
        reused.putArray("lines").add(issuanceLine(v2Cut,"2"));
        var secondConflict=body(assertStatus(409,issuanceCall(pp,secondWaiting,reused)));
        assertEquals("ATO_CONFLITANTE",secondConflict.path("conflict").asText());
        assertEquals(4,issuance(pp,conciliated).at("/netDeposited/0/amount").asInt());
        assertEquals(0,secondConflict.at("/netDeposited/0/amount").asInt(-1));
        var deposit2=issuanceAct(secondConflict,"TX-E3-52","2029-12-06T17:00:00Z",listingId("DEPOSITO-V2"));
        deposit2.putArray("lines").add(issuanceLine(v2Cut,"2"));
        var deposited2=issuanceStep(pp,secondConflict,deposit2);
        var final2=position(v2Cut,v2Registry,"28","2029-12-06T18:00:00Z");
        reflected(final2,deposited2,"2");
        assertStatus(201,post(pp,v2Route,final2,null));
        var compare2=issuanceAct(deposited2,"COMPARAR","2029-12-06T19:00:00Z",null);
        compare2.set("support",issuanceRef("SIM-PROVA-RESOLUCAO-EMISSAO"));
        var conciliated2=reviewIssuance(issuanceStep(prep,deposited2,compare2),"2029-12-06T20:00:00Z");
        assertEquals(28,balance(pp,"V2",v2Cut).path("available").asInt());

        var period=body(assertStatus(200,get(rev,"/periods"))).get(0);
        assertEquals("M1",period.path("id").asText());
        var reopened=periodStep(rev,"/periods/M1/actions","46","2029-12-07",period.path("version").asInt(),"EM_CONFERENCIA");
        var impacted=issuance(pp,conciliated);
        assertFalse(verification(pp,"V1").path("closureEligible").asBoolean());
        assertEquals("COM_DIVERGENCIA",impacted.at("/attempt/state").asText());
        assertEquals(4,impacted.at("/netDeposited/0/amount").asInt());
        assertEquals(reopened.at("/impact/divergenceId"),impacted.at("/attempt/incidents/1/reopen"));
        assertEquals("FECHAMENTO_AFETADO",failure(pp,"/issuances",successor,null));
        assertEquals(conciliated2,issuance(pp,conciliated2));

        var evidence=json.createObjectNode();
        evidence.put("kit","0.2.14").put("project",project).put("fixture","PIL-SYN-BR-001 / E8-010 / synth");
        evidence.set("v1InitialPosition",first.initialPosition());evidence.set("concurrentAttemptRejected",first.rejected());
        evidence.set("v1Waiting",first.waiting());evidence.set("v2InitialPosition",position2);
        evidence.set("v1Partial",deposited);evidence.set("v1Adjusted",adjusted);evidence.set("stalePosition",older);
        evidence.set("positionConflict",ordering);evidence.set("v1Reconciled",conciliated);
        evidence.set("reissue",successorDraft);evidence.set("crossAttemptConflict",secondConflict);
        evidence.set("v2Reconciled",conciliated2);evidence.set("m1Reopened",reopened);evidence.set("v1AfterReopen",impacted);
        writeEvidence("e8-010-trajectory.json",evidence);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode activation(JsonNode request,String registry,String at) {
        var input=json.createObjectNode().put("registryId",registry).put("approvalId",request.at("/request/decision/proof/officialId").asText())
                .put("active",true).put("effectiveAt",at);
        input.set("proof",issuanceProof("ATIVACAO",listingId("ATIVA"),request.at("/request/target").asText()));
        return input;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode position(JsonNode vintage,String registry,String amount,String at) {
        var input=json.createObjectNode().put("unit",IssuanceModel.UNIT).put("official",amount).put("asOf",at);
        input.set("vintage",vintage);
        input.set("proof",issuanceProof("POSICAO",listingId("POS"),registry));
        input.putArray("pending");
        return input;
    }

    private void reflected(com.fasterxml.jackson.databind.node.ObjectNode position,JsonNode attempt,String amount) {
        position.withArray("pending").addObject().put("issuance",attempt.at("/attempt/id").asText()).put("amount",amount).put("status","REFLETIDA");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode issuanceCreate(String verification,JsonNode cut,String position,String amount,String account,String at) {
        var input=json.createObjectNode().put("verification",verification).put("account",account).put("unit",IssuanceModel.UNIT).put("effectiveAt",at);
        input.putArray("lines").add(issuanceLine(cut,amount).put("position",position));
        return input;
    }

    private JsonNode issuanceDraft(String pp,String verification,JsonNode cut,JsonNode position,String amount,String account,String day) throws Exception {
        var input=issuanceCreate(verification,cut,position.at("/position/id").asText(),amount,account,day+"T13:00:00Z");
        var draft=body(assertStatus(201,post(pp,"/issuances",input,null)));
        var checked=json.createObjectNode().put("route","ARQUIVO").put("checkedAt",day+"T13:00:00Z")
                .put("powerUntilExclusive","2031-01-01T03:00:00Z");
        checked.set("source",issuanceRef("SIM-CANAL-ARQUIVO"));checked.set("power",issuanceRef(IssuanceModel.POWER));
        return body(assertStatus(200,post(pp,issuanceRoute(draft)+"/channel-checks",checked,1)));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode protocol(JsonNode draft,String at) {
        String target=listingId("EMISSAO");
        var input=issuanceAct(draft,"TX-E3-49",at,null).put("target",target);
        input.set("proof",issuanceProof("TX-E3-49",listingId("RECIBO"),target));
        return input;
    }

    private JsonNode verification(String actor,String kind) throws Exception {
        for(var item:body(assertStatus(200,get(actor,"/verifications")))) if(kind.equals(item.path("kind").asText()))return item;
        throw new AssertionError("Verificação ausente: "+kind);
    }

    private JsonNode balance(String actor,String kind,JsonNode cut) throws Exception {
        var verification=verification(actor,kind);
        for(int index=0;index<verification.path("cuts").size();index++)
            if(cut.equals(verification.path("cuts").get(index)))return verification.path("balances").get(index);
        throw new AssertionError("Recorte ausente: "+cut);
    }

    private JsonNode issuance(String actor,JsonNode attempt) throws Exception {
        for(var item:body(assertStatus(200,get(actor,"/issuances"))))
            if(attempt.at("/attempt/id").equals(item.at("/attempt/id")))return item;
        throw new AssertionError("Tentativa ausente");
    }

    private JsonNode reviewIssuance(JsonNode compared,String at) throws Exception {
        var input=issuanceAct(compared,"TX-E3-53",at,null).put("comparison",compared.at("/attempt/comparison/id").asText());
        assertStatus(403,issuanceCall(prep,compared,input));
        var reviewed=issuanceStep(rev,compared,input);
        assertEquals("CONCILIADO",reviewed.at("/attempt/state").asText());
        return reviewed;
    }

    private String issuanceRoute(JsonNode attempt) { return "/issuances/"+attempt.at("/attempt/id").asText(); }
    private HttpResponse<String> issuanceCall(String actor,JsonNode attempt,JsonNode action) throws Exception {
        return post(actor,issuanceRoute(attempt)+"/actions",action,attempt.at("/attempt/version").asInt());
    }
    private JsonNode issuanceStep(String actor,JsonNode attempt,JsonNode action) throws Exception {
        return body(assertStatus(200,issuanceCall(actor,attempt,action)));
    }
    private String failure(String actor,String route,JsonNode payload,Integer version) throws Exception {
        return body(assertStatus(409,post(actor,route,payload,version))).path("code").asText();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode issuanceAct(JsonNode attempt,String step,String at,String officialId) {
        var action=json.createObjectNode().put("transitionId",step).put("effectiveAt",at).put("mode","SIMULACAO")
                .put("account",attempt.at("/attempt/account").asText());
        if(officialId!=null)action.set("proof",issuanceProof(step,officialId,attempt.at("/attempt/externalTarget").asText()));
        return action;
    }
    private JsonNode issuanceProof(String step,String officialId,String target) {
        var proof=json.createObjectNode().put("officialId",officialId).put("target",target);
        proof.set("evidence",issuanceRef(IssuanceModel.PROOFS.get(step)));
        return proof;
    }
    private JsonNode issuanceRef(String id) { return json.createObjectNode().put("id",id).put("version",1); }
    private com.fasterxml.jackson.databind.node.ObjectNode issuanceLine(JsonNode cut,String amount) {
        var line=json.createObjectNode().put("amount",amount);
        line.set("vintage",cut);
        return line;
    }

    private JsonNode openValidationConsultation(String pp) throws Exception {
        var pd=freezeDossier(document("DESENVOLVIMENTO",null,null,null,"2027-08-02"),0);
        var dev=prepareListing(pp,"DESENVOLVIMENTO",pd,null);
        dev=listingChannel(pp,dev,pd,"ARQUIVO","2027-08-02T12:00:00Z","2031-01-01T03:00:00Z");
        String devTarget=listingId("DEV-AUD");
        dev=listingStep(pp,dev,listingProtocol("TX-E3-09",listingReceipt(pp,pd,"SIM-REGISTRY","2027-08-02T13:00:00Z"),devTarget));
        String invoice=listingId("FEE-AUD");
        dev=listingStep(pp,dev,listingOfficial("BOLETO","2027-08-02T14:00:00Z",invoice,devTarget));
        dev=listingStep(pp,dev,listingOfficial("QUITACAO","2027-08-02T15:00:00Z",listingId("PAID-AUD"),devTarget).put("target",invoice));
        listingStep(pp,dev,listingOfficial("TX-E3-14","2027-08-03T13:00:00Z",listingId("PUB-DEV-AUD"),devTarget)
                .put("page","https://registry.example.invalid/listagens/"+devTarget));
        var full=freezeDossier(document("LISTAGEM_VALIDACAO",null,null,null,"2027-12-01"),0);
        var val=prepareListing(pp,"LISTAGEM_VALIDACAO",full,null);
        val=listingChannel(pp,val,full,"ARQUIVO","2027-12-01T12:00:00Z","2031-01-01T03:00:00Z");
        String target=listingId("VAL-AUD");
        val=listingStep(pp,val,listingProtocol("TX-E3-18",listingReceipt(pp,full,"SIM-REGISTRY","2027-12-01T13:00:00Z"),target));
        val=listingStep(pp,val,listingOfficial("TX-E3-14","2027-12-05T13:00:00Z",listingId("PUB-VAL-AUD"),target)
                .put("page","https://registry.example.invalid/listagens/"+target));
        return body(assertStatus(200,get(pp,"/consultations/"+val.at("/attempt/consultationId").asText())));
    }

    private JsonNode closedMr(JsonNode pd,String period,String year) throws Exception {
        plan(period,prep);
        String path="/periods/"+period+"/actions";
        periodStep(op,path,"41",(Integer.parseInt(year)-1)+"-10-01",1,"COLETANDO");
        var run=done(period,admitAndSave(period,monitoringSample(period)),"CALCULADA");
        periodStep(op,path,"42",year+"-10-01",2,"EM_CONFERENCIA");
        assertStatus(200,post(rev,path,periodAction("45",year+"-10-01",run,null),3));
        return freezeDossier(document("VERIFICACAO",period,pd,null,year+"-12-01"),0);
    }

    private JsonNode contractInput(String kind,JsonNode dossier,String consult,String actor,String at){
        var input=json.createObjectNode().put("kind",kind).put("consultation",consult).put("vvb",actor).put("scope","Escopo do ensaio")
                .put("contractedAt",at).put("accreditedUntil","2031-01-01T03:00:00Z");
        input.set("dossier",dossierRef(dossier));input.putObject("contract").put("id",VvbAuditWorkflow.AGREEMENT).put("version",1);
        input.putObject("accreditation").put("id",VvbAuditWorkflow.ACCREDITATION).put("version",1);
        return input;
    }

    private JsonNode contractAudit(String pp,String kind,JsonNode dossier,String consult,String actor,String at)throws Exception{
        return body(assertStatus(201,post(pp,"/audits",contractInput(kind,dossier,consult,actor,at),null)));
    }

    private String auditGrant(String pp,JsonNode dossier,String purpose,String actor)throws Exception{
        var variant=java.util.stream.StreamSupport.stream(dossier.path("variants").spliterator(),false)
                .filter(v->"RESERVADA".equals(v.path("exposure").asText())&&"LIMPA".equals(v.path("edition").asText())).findFirst().orElseThrow();
        var input=json.createObjectNode().put("purpose",purpose).put("recipient",actor).put("expiresAt",OffsetDateTime.now().plusDays(1).toString());
        input.putObject("artifact").put("id",variant.path("artifactId").asText()).put("version",dossier.path("revision").asInt());
        var grant=body(assertStatus(201,post(pp,"/grants",input,null))).path("grant");
        String id=grant.path("id").asText();
        assertStatus(200,post(pp,"/grants/"+id+"/receipts",null,grant.path("version").asInt()));
        return id;
    }

    private JsonNode reservedReceipt(String pp,JsonNode dossier,String at)throws Exception{
        String path="/dossiers/"+dossier.path("id").asText();
        var exported=body(assertStatus(201,post(pp,path+"/exports",exportArgs(dossier,"RESERVADA","LIMPA","SIM-VVB"),
                dossier.path("version").asInt())));
        var receipt=json.createObjectNode().put("exportId",exported.path("id").asText()).put("sha256",exported.path("sha256").asText())
                .put("receivedAt",at).put("destination","SIM-VVB").put("code",listingId("RES-REC")).put("proof","SIM-PROVA-RES-REC");
        receipt.set("dossier",dossierRef(dossier));
        return body(assertStatus(201,post(pp,path+"/receipts",receipt,null))).path("input");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode auditAction(JsonNode audit,JsonNode dossier,String step,String at){
        var input=json.createObjectNode().put("transitionId",step).put("effectiveAt",at);
        input.set("dossier",dossierRef(dossier));
        if(VvbAuditWorkflow.SOURCES.containsKey(step))input.set("proof",programProof(VvbAuditWorkflow.SOURCES.get(step),audit.path("target").asText()));
        return input;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode opinion(JsonNode a,JsonNode d,boolean positive,String at){
        var input=auditAction(a,d,positive?"TX-E3-32":"TX-E3-30",at).put("reason","Parecer emitido pelo VVB do ensaio");
        input.putObject("report").put("id",VvbAuditWorkflow.REPORT).put("version",1);
        input.putObject("representation").put("id",VvbAuditWorkflow.REPRESENTATION).put("version",1);
        return input;
    }

    private HttpResponse<String> auditCall(String actor,JsonNode a,JsonNode input)throws Exception{
        return post(actor,"/audits/"+a.at("/audit/id").asText()+"/actions",input,a.at("/audit/version").asInt());
    }
    private JsonNode auditStep(String actor,JsonNode a,JsonNode input)throws Exception{return body(assertStatus(200,auditCall(actor,a,input)));}

    private String requestPath(JsonNode request){return "/requests/"+request.at("/request/id").asText();}
    private JsonNode prepareProgram(String pp,JsonNode audit,JsonNode dossier,String previous)throws Exception{
        String kind=switch(audit.at("/audit/contract/kind").asText()){case "VAL"->"REG";case "M1"->"V1";default->"V2";};
        var input=json.createObjectNode().put("kind",kind).put("audit",audit.at("/audit/id").asText()).put("predecessor",previous)
                .put("reason","Pacote e tentativa conferidos");
        input.set("dossier",dossierRef(dossier));
        return body(assertStatus(201,post(pp,"/requests",input,null)));
    }

    private JsonNode programChannel(String pp,JsonNode r,JsonNode dossier,String date)throws Exception{
        var input=json.createObjectNode().put("channel","ARQUIVO").put("checkedAt",date).put("powerUntilExclusive","2031-01-01T03:00:00Z")
                .put("documentEdition","5.0B").put("methodologyEdition","VM0038 v1.1");
        input.set("dossier",dossierRef(dossier));
        input.putObject("source").put("id","SIM-CANAL-ARQUIVO").put("version",1);
        input.putObject("power").put("id","SIM-PODER-LISTAGEM").put("version",1);
        return body(assertStatus(200,post(pp,requestPath(r)+"/channel-checks",input,r.at("/request/version").asInt())));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode programProtocol(JsonNode r,JsonNode pack,JsonNode receipt,String target,boolean after){
        var input=requestAct(r,pack,after?"TX-E3-40":"TX-E3-33",receipt.path("receivedAt").asText())
                .put("target",target).put("exportId",receipt.path("exportId").asText());
        input.putObject("fee").put("id",ProgramRequests.FEE).put("version",1);
        if(after)input.putObject("reuse").put("id",ProgramRequests.REUSE).put("version",1);
        return input;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode requestAct(JsonNode r,JsonNode dossier,String step,String date){
        var input=json.createObjectNode().put("transitionId",step).put("mode","SIMULACAO").put("effectiveAt",date);
        input.set("dossier",dossierRef(dossier));
        if(ProgramRequests.SOURCES.containsKey(step))input.set("proof",programProof(ProgramRequests.SOURCES.get(step),r.at("/request/target").asText()));
        return input;
    }
    private JsonNode programProof(String code,String target){
        var proof=json.createObjectNode().put("officialId",listingId("PROG")).put("target",target);
        proof.putObject("evidence").put("id",code).put("version",1);return proof;
    }
    private HttpResponse<String> requestCall(String actor,JsonNode r,JsonNode input)throws Exception{
        return post(actor,requestPath(r)+"/actions",input,r.at("/request/version").asInt());
    }
    private JsonNode requestStep(String actor,JsonNode r,JsonNode input)throws Exception{return body(assertStatus(200,requestCall(actor,r,input)));}

    private JsonNode admitAndSave(String period, String csv) throws Exception {
        var file = body(assertStatus(201, upload(op, period + ".csv", "SIM-AUT-ED-EC", csv.getBytes(StandardCharsets.UTF_8)))).path("artifact");
        String job = queue(op, file, period);
        waitFor(job, "CONCLUIDO");
        var lines = body(get(rev, "/imports/" + job + "/rows")).path("lines");
        for (var row : lines) {
            assertEquals("CANDIDATA", row.path("classification").asText());
            decide(rev, decide(prep, row, "PROPOR", null), "ADMITIR", null);
        }
        return body(assertStatus(201, post(rev, "/periods/" + period + "/selections", null, null)));
    }

    private String ed(String id, String day, String amount) {
        return "ED,SIM-ED-" + id + "," + day + "T08:00:00-03:00," + day
                + "T08:20:00-03:00,America/Sao_Paulo,SIM-PATIO-01,SIM-DCFC-01,SIM-BEV-01,,SIM-ED-01," + amount + ",kWh\n";
    }

    private String pair(String id, String day, String ed, String ec) {
        return ed(id, day, ed) + "EC,SIM-EC-" + id + "," + day + "T12:00:00-03:00," + day
                + "T13:00:00-03:00,America/Sao_Paulo,SIM-PATIO-01,,,SIM-EC-02,SIM-MED-02," + ec + ",kWh\n";
    }

    private JsonNode calculation(String period, JsonNode selection) throws Exception {
        return json.readTree("""
                {"proposal":{"period":"%s","selection":{"id":"%s","version":1},
                 "baseline":{"id":"BASE-SYN-LDV","version":1},"factor":{"id":"EF-SYN-v1","version":1},
                 "dy":{"id":"SIM-DY-REDE-01","version":1}}}
                """.formatted(period, selection.path("id").asText()));
    }

    private JsonNode confirm() throws Exception {
        return json.readTree("{\"transitionId\":\"CONFIRMAR\",\"reason\":\"Conferência da seleção sintética\"}");
    }

    private JsonNode waitCalculation(String route, String expected) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode state;
        do {
            state = body(assertStatus(200, get(rev, route)));
            if (expected.equals(state.path("state").asText())) return state;
            if ("FALHA_RECUPERAVEL".equals(state.path("state").asText())) fail(state.toString());
            Thread.sleep(150);
        } while (System.nanoTime() < deadline);
        fail("Estado calculado não alcançado " + state);
        return state;
    }

    private String queue(String actor, JsonNode file) throws Exception {
        return queue(actor, file, "M1");
    }

    private String queue(String actor, JsonNode file, String period) throws Exception {
        var payload = json.readTree("""
                {"artifact":{"id":"%s","version":1},"mappingProfile":{"id":"SIM-CSV-UTF8-ED-EC","version":1},
                 "sourceId":"SIM-OP-01","period":{"id":"%s","version":1}}
                """.formatted(file.path("id").asText(), period));
        return body(assertStatus(202, post(actor, "/imports", payload, null))).path("jobId").asText();
    }

    private JsonNode waitFor(String job, String wanted) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(55).toNanos();
        JsonNode result;
        do {
            result = body(assertStatus(200, get(rev, "/jobs/" + job)));
            if (wanted.equals(result.path("state").asText())) return result;
            Thread.sleep(150);
        } while (System.nanoTime() < deadline);
        fail("Estado esperado " + wanted + ", observado " + result);
        return result;
    }

    private JsonNode decide(String actor, JsonNode row, String action, JsonNode predecessor) throws Exception {
        return body(assertStatus(200, act(actor, row, action, predecessor))).path("line");
    }

    private HttpResponse<String> act(String actor, JsonNode row, String action, JsonNode predecessor) throws Exception {
        var payload = json.createObjectNode().put("transitionId", action).put("reason", "Conferência da massa sintética");
        if (predecessor != null) payload.set("predecessor", predecessor);
        return post(actor, "/observations/" + row.path("id").asText() + "/actions", payload, row.path("version").asInt());
    }

    private HttpResponse<String> upload(String actor, String name, String auth, byte[] bytes) throws Exception {
        var request = request(path + "/artifacts", actor).expectContinue(true)
                .header("Content-Type", "text/csv").header("X-File-Name", name)
                .header("X-Source-Id", "SIM-OP-01").header("X-Source-Authorization", auth)
                .header("Idempotency-Key", UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        return send(request);
    }

    private String login(String actor) throws Exception {
        var payload = json.createObjectNode().put("identity", "SIM-" + actor)
                .put("password", "piloto-" + actor.toLowerCase(java.util.Locale.ROOT));
        var response = send(request("/v1/session", null).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())));
        assertStatus(204, response);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> post(String actor, String route, JsonNode payload, Integer version) throws Exception {
        return postWithKey(actor, route, payload, version, UUID.randomUUID().toString());
    }

    private HttpResponse<String> postWithKey(String actor, String route, JsonNode payload, Integer version, String key) throws Exception {
        var builder = request(path + route, actor).header("Idempotency-Key", key);
        if (version != null) builder.header("If-Match", "\"" + version + "\"");
        if (payload == null) return send(builder.POST(HttpRequest.BodyPublishers.noBody()));
        return send(builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString())));
    }

    private HttpResponse<String> get(String actor, String route) throws Exception {
        return send(request(path + route, actor).GET());
    }

    private HttpRequest.Builder request(String route, String actor) {
        var builder = HttpRequest.newBuilder(URI.create(base.toString()).resolve(route)).timeout(Duration.ofSeconds(20));
        return actor == null ? builder : builder.header("Cookie", actor);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }

    private HttpResponse<String> assertStatus(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), () -> response.body().substring(0, Math.min(500, response.body().length())));
        return response;
    }
}
