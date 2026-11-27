package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** #94/#95: alias candidates are imported and decided by different people and never touch the canonical catalog. */
@QuarkusTest
@QuarkusTestResource(value = FleetAliasCandidatesIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class FleetAliasCandidatesIntegrationTest {
    private static final UUID IMPORTER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d41");
    private static final UUID REVIEWER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d42");
    private static final UUID DUAL = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d43");
    private static final UUID OTHER_STAFF = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d44");
    private static final UUID OWNER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d45");
    // Identities of the owner-basis test are separate: a one-time code cannot be replayed by the same identity.
    private static final UUID REVIEWER2 = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d46");
    private static final UUID IMPORTER2 = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d47");
    private static final UUID REVIEWER3 = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d48");
    private static final UUID IMPORTER3 = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d49");
    private static final UUID OTHER3 = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d4a");
    private static final UUID ORG = UUID.fromString("71e71a49-e926-4eaa-aab8-86b04add8a41");
    private static final byte[] OTP = "78901234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/fleet/alias-candidates";
    private static final String SHA = "c".repeat(64);
    private static final String SEN = "d".repeat(64);

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    private static String item(String name, String key, String level, String matches) {
        return "{\"literalName\":\"" + name + "\",\"recordKey\":\"" + key + "\",\"level\":\"" + level
                + "\",\"reason\":\"Casamento de texto, sem decisão de alias.\",\"matches\":" + matches + "}";
    }

    private static String match(int line, String version) {
        return "{\"brand\":\"BYD\",\"model\":\"DOLPHIN\",\"version\":\"" + version + "\",\"category\":\"Médio\",\"pbevLine\":" + line + "}";
    }

    private static String batch(String period, String sha, String... items) {
        return batchWith(period, sha, SEN, items);
    }

    private static String batchWith(String period, String sha, String senatran, String... items) {
        return "{\"observationPeriod\":\"" + period + "\",\"pbevSourceSha256\":\"" + sha + "\",\"senatranSourceSha256\":\""
                + senatran + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    /** Independently approved, effective licence (EXTRACT_TEXT) for a source identified by its SHA-256; test fixtures only. */
    private void seedRights(String sha, String name, String lastOutcome, String submitter, String decider, String endsAt) {
        session(IMPORTER);
        session(REVIEWER2);
        database.transaction(c -> {
            update(c, "INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,sha256,origin) "
                    + "VALUES (?,'t1','EXTERNAL_SOURCE','Fonte sintética de teste','Teste',?,'EXTERNAL') ON CONFLICT DO NOTHING", name, sha);
            UUID license = UUID.randomUUID();
            update(c, "INSERT INTO app_content_license(id,source_id,edition,submitted_by,environment,purpose,authorization_ref,"
                    + "proof_sha256,starts_at,ends_at) VALUES (?,?,'t1',?::uuid,'SANDBOX','AUTHORIZED_PROCESSING','Fixture sintética',?,"
                    + "statement_timestamp()-interval '1 day',?::timestamptz)", license, name, submitter, "e".repeat(64), endsAt);
            update(c, "INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason) "
                    + "VALUES (?,1,'APPROVED',?::uuid,'Fixture sintética de teste')", license, decider);
            update(c, "INSERT INTO app_content_license_capability(license_id,decision_version,operation) VALUES (?,1,'EXTRACT_TEXT')", license);
            if (!lastOutcome.equals("APPROVED")) {
                update(c, "INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason) "
                        + "VALUES (?,2,?,?::uuid,'Fixture sintética de teste')", license, lastOutcome, decider);
            }
            return null;
        });
    }

    private void seedDefaultRights() {
        seedRights(SHA, "TEST-ALIAS-PBEV", "APPROVED", IMPORTER.toString(), REVIEWER2.toString(), null);
        seedRights(SEN, "TEST-ALIAS-SENATRAN", "APPROVED", IMPORTER.toString(), REVIEWER2.toString(), null);
    }

    private long count(String table) {
        return database.read(c -> first(c, "SELECT count(*) FROM " + table, r -> r.getLong(1))).longValue();
    }

    @Test
    void candidatesAreImportedOnceDecidedByAnotherPersonAndNeverTouchTheCatalog() throws Exception {
        // The deep link of the administration screen is served as the SPA (CI serves routes from this list).
        var page = call("GET", "/workspace/platform/alias-candidates", null, null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String nameA = "I/BYD DOLPHIN GS 180EV " + suffix, nameB = "I/RENAULT E-KWID TECHNO " + suffix;
        String itemA = item(nameA, "I_BYD_DOLPHIN_" + suffix, "EXACT", "[" + match(1, "GS 180EV") + "]");
        String itemB = item(nameB, "I_RENAULT_" + suffix, "MODEL_ONLY", "[" + match(10, "INTENS") + "," + match(11, "CARGO") + "]");
        database.transaction(c -> update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG));
        long models = count("app_fleet_model"), aliases = count("app_fleet_model_alias");
        seedDefaultRights();
        String importer = session(IMPORTER), reviewer = session(REVIEWER), dual = session(DUAL), other = session(OTHER_STAFF);
        assertEquals(401, call("GET", API, null, null, null).statusCode());
        assertEquals(403, call("GET", API, importer, null, null).statusCode());
        for (String who : new String[]{importer, reviewer, dual, other}) verify(who);

        // Outside both rosters nothing is visible or possible; the reviewer cannot import, the importer cannot decide.
        assertEquals(404, call("GET", API, other, null, null).statusCode());
        assertEquals(404, call("POST", API, other, null, batch("2026-09", SHA, itemA)).statusCode());
        assertEquals(404, call("POST", API, reviewer, null, batch("2026-09", SHA, itemA)).statusCode());

        // Invalid batches.
        assertEquals(422, call("POST", API, importer, null, batch("2026-13", SHA, itemA)).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", "abc", itemA)).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA)).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, item(nameA, "K", "NONE", "[" + match(1, "X") + "]"))).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, item(nameA, "K", "EXACT", "[]"))).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, item(nameA, "chave inválida", "EXACT", "[" + match(1, "X") + "]"))).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, item(nameA, "K", "EXACT", "[" + match(1, "X") + "," + match(1, "Y") + "]"))).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, itemA, itemA)).statusCode());

        // Import is idempotent per name, period and PBEV file.
        var first = json.readTree(ok(call("POST", API, importer, null, batch("2026-09", SHA, itemA, itemB))).body());
        assertEquals(2, first.path("created").asInt());
        var again = json.readTree(ok(call("POST", API, importer, null, batch("2026-09", SHA, itemA, itemB))).body());
        assertEquals(0, again.path("created").asInt());
        assertEquals(2, again.path("alreadyPresent").asInt());

        // Listing: pending, with the evidence and the limits.
        var pending = json.readTree(ok(call("GET", API + "?state=PENDING", reviewer, null, null)).body());
        String idA = null, idB = null;
        for (var c : pending) {
            if (c.path("literalName").asText().equals(nameA)) idA = c.path("id").asText();
            if (c.path("literalName").asText().equals(nameB)) idB = c.path("id").asText();
        }
        assertNotNull(idA);
        assertNotNull(idB);
        final String acceptedId = idA;
        var shownA = json.readTree(ok(call("GET", API, reviewer, null, null)).body());
        assertEquals("PENDING", find(shownA, idA).path("state").asText());
        assertEquals("EXACT", find(shownA, idA).path("level").asText());
        assertTrue(find(shownA, idA).path("limits").asText().contains("não é alias"));
        assertEquals(2, find(shownA, idB).path("matches").size());
        assertEquals(422, call("GET", API + "?state=OUTRO", reviewer, null, null).statusCode());

        // Decisions: only reviewers, never the importer of the candidate.
        String decisionA = API + "/" + idA + "/decision", decisionB = API + "/" + idB + "/decision";
        String accept = "{\"outcome\":\"ACCEPTED\",\"selectedPbevLine\":1,\"reason\":\"Nome e versão conferidos no PBEV.\"}";
        assertEquals(404, call("POST", decisionA, importer, null, accept).statusCode());
        assertEquals(404, call("POST", API + "/" + UUID.randomUUID() + "/decision", reviewer, null, accept).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null, accept.replace("\"selectedPbevLine\":1,", "")).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null, accept.replace("\"selectedPbevLine\":1", "\"selectedPbevLine\":99")).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null, accept.replace("Nome e versão conferidos no PBEV.", "curto")).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null, accept.replace("ACCEPTED", "APPROVED")).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null,
                "{\"outcome\":\"REJECTED\",\"selectedPbevLine\":1,\"reason\":\"Recusado, mas com linha escolhida.\"}").statusCode());

        // Dual custody: someone holding both privileges cannot decide on their own import.
        var own = json.readTree(call("POST", API, dual, null, batch("2026-08", SHA, item(nameA + " próprio", "K_" + suffix, "EXACT", "[" + match(5, "X") + "]"))).body());
        assertEquals(1, own.path("created").asInt());
        var ownList = json.readTree(ok(call("GET", API + "?state=PENDING", dual, null, null)).body());
        String ownId = null;
        for (var c : ownList) if (c.path("literalName").asText().endsWith("próprio")) ownId = c.path("id").asText();
        assertNotNull(ownId);
        assertEquals(403, call("POST", API + "/" + ownId + "/decision", dual, null,
                "{\"outcome\":\"REJECTED\",\"reason\":\"Tentativa da mesma pessoa.\"}").statusCode());

        // Acceptance picks one PBEV line explicitly and is final.
        var accepted = json.readTree(ok(call("POST", decisionA, reviewer, null, accept)).body());
        assertEquals("ACCEPTED", accepted.path("state").asText());
        assertEquals(1, accepted.path("selectedPbevLine").asInt());
        assertEquals(REVIEWER.toString(), accepted.path("decidedBy").asText());
        assertEquals(409, call("POST", decisionA, reviewer, null, accept).statusCode());
        // MODEL_ONLY: the reviewer must name which version, and may also reject.
        var rejected = json.readTree(ok(call("POST", decisionB, reviewer, null,
                "{\"outcome\":\"REJECTED\",\"reason\":\"A versão Techno não existe no PBEV.\"}")).body());
        assertEquals("REJECTED", rejected.path("state").asText());
        assertTrue(rejected.path("selectedPbevLine").isNull());
        assertEquals(1, json.readTree(ok(call("GET", API + "?state=ACCEPTED", reviewer, null, null)).body()).findValues("id").stream()
                .filter(v -> v.asText().equals(acceptedId)).count());

        // The canonical catalog was never touched, and decisions are append-only.
        assertEquals(models, count("app_fleet_model"));
        assertEquals(aliases, count("app_fleet_model_alias"));
        var update = assertThrows(RuntimeException.class, () -> database.transaction(c -> update(c,
                "UPDATE app_fleet_alias_candidate_decision SET reason='alterada depois da decisão' WHERE true")));
        assertNotNull(update);

        // Successful operations left one audit event each: 3 imports + 4 listings + 2 decisions. Refused attempts,
        // including the invalid state filter, roll back or fail before the transaction and leave nothing.
        Long audited = database.read(c -> first(c, "SELECT count(*) FROM app_compliance_access_event WHERE action IN "
                + "('VIEW_FLEET_ALIAS_CANDIDATES','IMPORT_FLEET_ALIAS_CANDIDATES','DECIDE_FLEET_ALIAS_CANDIDATE') "
                + "AND actor_id IN (?,?,?)", r -> r.getLong(1), IMPORTER, REVIEWER, DUAL));
        assertEquals(9L, audited.longValue());
    }

    /** D19: the owner decides on the owner-internal basis, even on a candidate they imported; it is never independent. */
    @Test
    void ownerInternalDecisionIsExplicitNeverIndependentAndLimitedToTheOwnersRoster() throws Exception {
        database.transaction(c -> update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        seedDefaultRights();
        String owner = session(OWNER), reviewer = session(REVIEWER2), importer = session(IMPORTER2);
        for (String who : new String[]{owner, reviewer, importer}) verify(who);
        long models = count("app_fleet_model"), aliases = count("app_fleet_model_alias");
        String one = item("I/OWNER A " + suffix, "O_A_" + suffix, "EXACT", "[" + match(7, "X") + "]");
        String two = item("I/OWNER B " + suffix, "O_B_" + suffix, "EXACT", "[" + match(8, "Y") + "]");
        // The owner imports and sees; the reviewer-only profile cannot claim the owner basis.
        assertEquals(2, json.readTree(ok(call("POST", API, owner, null, batch("2026-07", SHA, one, two))).body()).path("created").asInt());
        String idOne = null, idTwo = null;
        for (var c : json.readTree(ok(call("GET", API + "?state=PENDING", owner, null, null)).body())) {
            if (c.path("literalName").asText().endsWith("OWNER A " + suffix)) idOne = c.path("id").asText();
            if (c.path("literalName").asText().endsWith("OWNER B " + suffix)) idTwo = c.path("id").asText();
        }
        assertNotNull(idOne);
        assertNotNull(idTwo);
        String accept = "{\"outcome\":\"ACCEPTED\",\"selectedPbevLine\":7,\"reason\":\"Conferido pelo responsável do produto.\",\"basis\":\"OWNER_INTERNAL\"}";
        assertEquals(403, call("POST", API + "/" + idOne + "/decision", reviewer, null, accept).statusCode());
        assertEquals(404, call("POST", API + "/" + idOne + "/decision", importer, null, accept).statusCode());
        assertEquals(422, call("POST", API + "/" + idOne + "/decision", owner, null, accept.replace("OWNER_INTERNAL", "SELF")).statusCode());
        // Without a basis the decision is INDEPENDENT, which the importer-owner cannot satisfy (and is not a reviewer).
        var noBasis = call("POST", API + "/" + idOne + "/decision", owner, null, accept.replace(",\"basis\":\"OWNER_INTERNAL\"", ""));
        assertEquals(403, noBasis.statusCode());
        assertEquals("BASE_NAO_AUTORIZADA", json.readTree(noBasis.body()).path("code").asText());
        assertEquals("PENDING", find(json.readTree(ok(call("GET", API, owner, null, null)).body()), idOne).path("state").asText());

        var decided = json.readTree(ok(call("POST", API + "/" + idOne + "/decision", owner, null, accept)).body());
        assertEquals("ACCEPTED", decided.path("state").asText());
        assertEquals("OWNER_INTERNAL", decided.path("decisionBasis").asText());
        assertEquals(OWNER.toString(), decided.path("decidedBy").asText());
        assertEquals(409, call("POST", API + "/" + idOne + "/decision", owner, null, accept).statusCode());
        // An independent reviewer (not the importer) still decides on the INDEPENDENT basis.
        var independent = json.readTree(ok(call("POST", API + "/" + idTwo + "/decision", reviewer, null,
                "{\"outcome\":\"REJECTED\",\"reason\":\"Revisão independente do candidato.\",\"basis\":\"INDEPENDENT\"}")).body());
        assertEquals("INDEPENDENT", independent.path("decisionBasis").asText());
        // The listing shows the basis, and the catalog is untouched.
        var listed = json.readTree(ok(call("GET", API, owner, null, null)).body());
        assertEquals("OWNER_INTERNAL", find(listed, idOne).path("decisionBasis").asText());
        assertEquals("INDEPENDENT", find(listed, idTwo).path("decisionBasis").asText());
        assertEquals(models, count("app_fleet_model"));
        assertEquals(aliases, count("app_fleet_model_alias"));
    }

    private static String hex64() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private JsonNode pendingByName(String who, String suffix) throws Exception {
        var found = json.createObjectNode();
        for (var c : json.readTree(ok(call("GET", API + "?state=PENDING", who, null, null)).body()))
            if (c.path("literalName").asText().endsWith(suffix)) found.set(c.path("literalName").asText(), c);
        return found;
    }

    /** BL-DATA-01C/D: no real import without independent approval of both sources; MODEL_ONLY risks are explicit. */
    @Test
    void rightsGateBlocksImportUntilBothSourcesAreIndependentlyApprovedAndRisksAreExplicit() throws Exception {
        database.transaction(c -> update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG));
        String suffix = UUID.randomUUID().toString().substring(0, 8), tag = " G" + suffix;
        String pbev = hex64(), sen = hex64();
        String importer = session(IMPORTER3), reviewer = session(REVIEWER3), other = session(OTHER3);
        for (String who : new String[]{importer, reviewer, other}) verify(who);
        String exact = item("I/GATE EXACT" + tag, "G_E_" + suffix, "EXACT", "[" + match(1, "GS") + "]");
        String modelOnly = item("I/GATE MODELO" + tag, "G_M_" + suffix, "MODEL_ONLY", "[" + match(10, "INTENS") + "," + match(11, "CARGO") + "]");
        String ambiguous = item("I/GATE AMBIGUO" + tag, "G_A_" + suffix, "AMBIGUOUS", "[" + match(20, "A") + "," + match(21, "B") + "]");
        String body = batchWith("2026-09", pbev, sen, exact, modelOnly, ambiguous);
        long before = count("app_fleet_alias_candidate");

        // Without any registered right: the dry run reports it, the real import is refused and nothing is written.
        var dry = json.readTree(ok(call("POST", API + "/dry-run", importer, null, body)).body());
        assertFalse(dry.path("importable").asBoolean());
        assertTrue(dry.path("rightsGateEnabled").asBoolean());
        assertEquals(2, dry.path("rights").size());
        for (var right : dry.path("rights")) assertFalse(right.path("registered").asBoolean());
        assertEquals(3, dry.path("newItems").asInt());
        assertEquals(List.of("NAME_MATCH_ONLY", "HOMONYM", "DISTINCT_PROPULSION", "VERSION_UNCONFIRMED"),
                json.convertValue(dry.path("items").get(1).path("riskCodes"), List.class));
        var refused = call("POST", API, importer, null, body);
        assertEquals(403, refused.statusCode());
        assertEquals("DIREITOS_NAO_REGISTRADOS", json.readTree(refused.body()).path("code").asText());
        assertEquals(before, count("app_fleet_alias_candidate"));
        assertEquals(404, call("POST", API + "/dry-run", other, null, body).statusCode());
        assertEquals(422, call("POST", API + "/dry-run", importer, null, body.replace(sen, "curto")).statusCode());
        assertEquals(422, call("POST", API, importer, null, body.replace(",\"senatranSourceSha256\":\"" + sen + "\"", "")).statusCode());

        // One source approved is not enough; nor a self-approval, a revoked licence or an expired one.
        seedRights(pbev, "GATE-PBEV-" + suffix, "APPROVED", IMPORTER.toString(), REVIEWER2.toString(), null);
        assertEquals(403, call("POST", API, importer, null, body).statusCode());
        seedRights(sen, "GATE-SEN-SELF-" + suffix, "APPROVED", REVIEWER2.toString(), REVIEWER2.toString(), null);
        assertEquals(403, call("POST", API, importer, null, body).statusCode());
        String sen2 = hex64(), sen3 = hex64();
        seedRights(sen2, "GATE-SEN-REV-" + suffix, "REVOKED", IMPORTER.toString(), REVIEWER2.toString(), null);
        assertEquals(403, call("POST", API, importer, null, body.replace(sen, sen2)).statusCode());
        seedRights(sen3, "GATE-SEN-EXP-" + suffix, "APPROVED", IMPORTER.toString(), REVIEWER2.toString(), OffsetDateTime.now().minusHours(1).toString());
        assertEquals(403, call("POST", API, importer, null, body.replace(sen, sen3)).statusCode());
        assertEquals(before, count("app_fleet_alias_candidate"));

        // Both approved by someone else: dry run is importable, the import records the senatran provenance.
        seedRights(sen, "GATE-SEN-" + suffix, "APPROVED", IMPORTER.toString(), REVIEWER2.toString(), null);
        assertTrue(json.readTree(ok(call("POST", API + "/dry-run", importer, null, body)).body()).path("importable").asBoolean());
        assertEquals(before, count("app_fleet_alias_candidate"));
        assertEquals(3, json.readTree(ok(call("POST", API, importer, null, body)).body()).path("created").asInt());
        var again = json.readTree(ok(call("POST", API + "/dry-run", importer, null, body)).body());
        assertEquals(0, again.path("newItems").asInt());
        assertEquals(3, again.path("alreadyPresent").asInt());

        // Risks are part of every listed candidate; names alone never decide.
        var pending = pendingByName(reviewer, tag);
        var cExact = pending.path("I/GATE EXACT" + tag);
        var cModel = pending.path("I/GATE MODELO" + tag);
        var cAmb = pending.path("I/GATE AMBIGUO" + tag);
        assertEquals(sen, cModel.path("senatranSourceSha256").asText());
        assertEquals(List.of("NAME_MATCH_ONLY"), json.convertValue(cExact.path("risks").findValues("code"), List.class));
        assertEquals(List.of("NAME_MATCH_ONLY", "HOMONYM", "DISTINCT_PROPULSION", "VERSION_UNCONFIRMED"),
                json.convertValue(cModel.path("risks").findValues("code"), List.class));
        assertEquals(5, cAmb.path("risks").size());
        assertTrue(cModel.path("risks").get(1).path("text").asText().contains("combustão"));

        // Accepting MODEL_ONLY/AMBIGUOUS needs every risk acknowledged; rejecting does not.
        String idModel = cModel.path("id").asText(), idAmb = cAmb.path("id").asText(), idExact = cExact.path("id").asText();
        String acceptModel = "{\"outcome\":\"ACCEPTED\",\"selectedPbevLine\":10,\"reason\":\"Versão conferida manualmente no PBEV.\"%s}";
        var noAck = call("POST", API + "/" + idModel + "/decision", reviewer, null, acceptModel.formatted(""));
        assertEquals(422, noAck.statusCode());
        assertEquals("RISCOS_NAO_RECONHECIDOS", json.readTree(noAck.body()).path("code").asText());
        assertEquals(422, call("POST", API + "/" + idModel + "/decision", reviewer, null,
                acceptModel.formatted(",\"acknowledgedRisks\":[\"HOMONYM\"]")).statusCode());
        String allAck = ",\"acknowledgedRisks\":[\"NAME_MATCH_ONLY\",\"HOMONYM\",\"DISTINCT_PROPULSION\",\"VERSION_UNCONFIRMED\"]";
        assertEquals("PENDING", find(json.readTree(ok(call("GET", API, reviewer, null, null)).body()), idModel).path("state").asText());

        // Bulk decisions: dry run reports every item, applying is all-or-nothing, there is no accept-all.
        String ok1 = "{\"candidateId\":\"" + idExact + "\",\"outcome\":\"ACCEPTED\",\"selectedPbevLine\":1,\"reason\":\"Nome e versão conferidos no PBEV.\"}";
        String weak = "{\"candidateId\":\"" + idModel + "\",\"outcome\":\"ACCEPTED\",\"selectedPbevLine\":10,\"reason\":\"Versão conferida manualmente no PBEV.\"}";
        String strong = weak.replace("}", allAck.substring(0, allAck.length()) + "}");
        String rej = "{\"candidateId\":\"" + idAmb + "\",\"outcome\":\"REJECTED\",\"reason\":\"Dois modelos candidatos; recusado.\"}";
        String dryBody = "{\"items\":[" + ok1 + "," + weak + "," + rej + "]}";
        var plan = json.readTree(ok(call("POST", API + "/decisions/dry-run", reviewer, null, dryBody)).body());
        assertTrue(plan.path("dryRun").asBoolean());
        assertFalse(plan.path("applied").asBoolean());
        assertEquals(1, plan.path("failed").asInt());
        assertEquals("RISCOS_NAO_RECONHECIDOS", plan.path("results").get(1).path("code").asText());
        var blocked = call("POST", API + "/decisions", reviewer, null, dryBody);
        assertEquals(422, blocked.statusCode());
        assertFalse(json.readTree(blocked.body()).path("applied").asBoolean());
        assertEquals(3, pendingByName(reviewer, tag).size());  // nothing was decided
        assertEquals(404, call("POST", API + "/decisions", importer, null, dryBody).statusCode());
        assertEquals(422, call("POST", API + "/decisions", reviewer, null, "{\"items\":[]}").statusCode());
        var repeated = json.readTree(ok(call("POST", API + "/decisions/dry-run", reviewer, null, "{\"items\":[" + ok1 + "," + ok1 + "]}")).body());
        assertEquals("CANDIDATO_REPETIDO", repeated.path("results").get(1).path("code").asText());
        var many = new StringBuilder("{\"items\":[");
        for (int i = 0; i <= FleetAliasCandidates.MAX_BULK; i++) many.append(i == 0 ? "" : ",").append(ok1);
        assertEquals(422, call("POST", API + "/decisions/dry-run", reviewer, null, many + "]}").statusCode());

        var applied = call("POST", API + "/decisions", reviewer, null, "{\"items\":[" + ok1 + "," + strong + "," + rej + "]}");
        assertEquals(201, applied.statusCode(), applied.body());
        var result = json.readTree(applied.body());
        assertTrue(result.path("applied").asBoolean());
        assertEquals(2, result.path("accepted").asInt());
        assertEquals(1, result.path("rejected").asInt());
        var listed = json.readTree(ok(call("GET", API, reviewer, null, null)).body());
        assertEquals("ACCEPTED", find(listed, idModel).path("state").asText());
        assertEquals("REJECTED", find(listed, idAmb).path("state").asText());
        // A candidate already decided is reported, not decided again.
        var again2 = json.readTree(ok(call("POST", API + "/decisions/dry-run", reviewer, null, "{\"items\":[" + ok1 + "]}")).body());
        assertEquals("CANDIDATO_DECIDIDO", again2.path("results").get(0).path("code").asText());
    }

    /** A one-time code cannot be replayed, so a session already verified by an earlier test is reused. */
    private void verify(String who) throws Exception {
        var current = call("GET", "/v1/compliance/session", who, null, null);
        if (current.statusCode() == 200 && json.readTree(current.body()).path("verified").asBoolean()) return;
        assertEquals(200, call("POST", "/v1/compliance/session", who, null, currentCode()).statusCode());
    }

    private static JsonNode find(JsonNode list, String id) {
        for (var item : list) if (item.path("id").asText().equals(id)) return item;
        throw new AssertionError("candidate not listed: " + id);
    }

    private HttpResponse<String> ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return response;
    }

    private String session(UUID id) {
        String token = AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES(?,?,'Conta de teste','ACTIVE',statement_timestamp()) ON CONFLICT(id) DO NOTHING
                """, id, id + "@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES(?,?,statement_timestamp()+interval '1 hour')
                """, AccessSecrets.digest(token), id);
            return null;
        });
        return token;
    }

    private String currentCode() {
        long counter = database.read(c -> first(c,
                "SELECT floor(extract(epoch from statement_timestamp()) / 30)::bigint", row -> row.getLong(1)));
        return "{\"code\":\"" + ComplianceStaff.codeAt(OTP, counter) + "\"}";
    }

    private HttpResponse<String> call(String method, String path, String token, String etag, String body) throws Exception {
        return call(method, path, token, etag, body, null);
    }

    private HttpResponse<String> call(String method, String path, String token, String etag, String body, String key)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path));
        if (token != null) builder.header("Cookie", AccountAccess.COOKIE + "=" + token);
        if (etag != null) builder.header("If-Match", etag);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String, String> start() {
            var result = new HashMap<>(super.start());
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(OTP);
            result.put("carbonflow.compliance.staff", IMPORTER + ":" + secret + ":" + ORG + ";" + REVIEWER + ":" + secret + ":" + ORG
                    + ";" + DUAL + ":" + secret + ":" + ORG + ";" + OTHER_STAFF + ":" + secret + ":" + ORG + ";" + OWNER + ":" + secret + ":" + ORG
                    + ";" + REVIEWER2 + ":" + secret + ":" + ORG + ";" + IMPORTER2 + ":" + secret + ":" + ORG
                    + ";" + REVIEWER3 + ":" + secret + ":" + ORG + ";" + IMPORTER3 + ":" + secret + ":" + ORG + ";" + OTHER3 + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.fleet-alias-candidates.importers", IMPORTER + "," + DUAL + "," + IMPORTER2 + "," + IMPORTER3);
            result.put("carbonflow.platform.fleet-alias-candidates.reviewers", REVIEWER + "," + DUAL + "," + REVIEWER2 + "," + REVIEWER3);
            result.put("carbonflow.platform.fleet-alias-candidates.owners", OWNER.toString());
            return result;
        }
    }
}
