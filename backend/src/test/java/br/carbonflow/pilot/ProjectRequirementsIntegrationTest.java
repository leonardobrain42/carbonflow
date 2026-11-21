package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AccountAccessTest.Inbox.class, restrictToAnnotatedClass = true)
class ProjectRequirementsIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client = HttpClient.newHttpClient();
    private record Actor(UUID id, String cookie) {}

    @Test
    void projectionFollowsTheDeclarationAndNeverAdmitsE8() throws Exception {
        Actor owner = actor(), outsider = actor();
        String id = project(owner, organization(owner.id()));
        String path = "/v1/projects/" + id + "/technical/requirements";
        assertEquals(401, call("GET", path, null).statusCode());
        assertEquals(404, call("GET", path, outsider.cookie()).statusCode());
        assertEquals(404, call("GET", "/v1/projects/PIL-SYN-BR-001/technical/requirements", owner.cookie()).statusCode());

        var initial = call("GET", path, owner.cookie());
        assertEquals(200, initial.statusCode(), initial.body());
        assertTrue(initial.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        JsonNode empty = json(initial);
        assertFalse(empty.path("openBranch").asBoolean());
        assertEquals("METHODOLOGY_BRANCH", empty.at("/requirements/0/requirementId").asText());
        assertFalse(empty.toString().contains("VMD0049_ROUTE"));
        assertFalse(empty.path("canRunE8").asBoolean());
        assertFalse(empty.has("emissions"));

        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/intake", owner.cookie(),
            key(), "\"0\"", """
                {"activity":"Recarga aberta","country":"BR","candidateMethodology":"VM0038"}
                """).statusCode());
        JsonNode open = json(call("GET", path, owner.cookie()));
        assertTrue(open.path("openBranch").asBoolean());
        assertEquals("ABSENT", finding(open, "NO_DECLARATION").path("state").asText());
        assertEquals("MINE", finding(open, "NO_DECLARATION").path("group").asText());
        assertFalse(open.findValuesAsText("requirementId").contains("MARKET_ROUTE"));
        assertFalse(open.findValuesAsText("requirementId").contains("VMD0049_ROUTE"));
        assertFalse(open.findValuesAsText("requirementId").contains("REGULATORY_SURPLUS"));
        assertEquals("NOT_INCIDENT", finding(open, "ED_EC").path("state").asText());
        assertFalse(finding(open, "ED_EC").path("blocksExAntePd").asBoolean());
        assertFalse(open.path("exAnteRequiresMeasuredEnergy").asBoolean());
        assertFalse(open.path("canIssueVcu").asBoolean());
        assertFalse(open.toString().contains("SIM-"));
        assertFalse(open.findValuesAsText("ownerRole").stream().anyMatch(role -> role.contains("@")));

        String withoutProof = """
            {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
             "privateLand":true,"chargerType":"DCFC","propulsion":"BEV",
             "electricitySource":"SIN","associatedInfrastructure":false,"v2g":false,
             "chargingOwner":"Titular secreto","carbonRightsContractRef":"CONTRACT-001",
             "driverDisclosureChannel":"NOT_DECLARED","doubleClaimingCheck":"UNKNOWN",
             "effectiveFrom":"2027-02-01"}
            """;
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/open-applicability",
            owner.cookie(), key(), "\"0\"", withoutProof).statusCode());
        JsonNode gaps = json(call("GET", path, owner.cookie()));
        assertEquals("NEEDS_EVIDENCE", finding(gaps, "DRIVER_DISCLOSURE").path("state").asText());
        assertEquals("ATTACHED_NOT_ADMITTED", finding(gaps, "OWNER_CONTRACT").path("state").asText());
        assertNotEquals("MINE", finding(gaps, "OWNER_CONTRACT").path("group").asText());
        assertFalse(gaps.toString().contains("Titular secreto"));
        assertEquals("AWAITING_NORMATIVE_EVALUATION", finding(gaps, "MARKET_ROUTE").path("state").asText());
        assertNotEquals("MINE", finding(gaps, "MARKET_ROUTE").path("group").asText());
        assertTrue(finding(gaps, "MARKET_ROUTE").path("reason").asText().contains("Não é uma rota de adicionalidade"));
        assertFalse(gaps.findValuesAsText("requirementId").contains("NO_DECLARATION"));

        String withProof = withoutProof.replace("\"NOT_DECLARED\"", "\"ON_CHARGER\"")
            .replace("\"UNKNOWN\"", "\"NO_OVERLAP_DECLARED\"")
            .replace("\"effectiveFrom\"",
                "\"driverDisclosureEvidenceRef\":\"PHOTO-2027\",\"driverDisclosureEffectiveOn\":\"2027-01-15\",\"effectiveFrom\"");
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/open-applicability",
            owner.cookie(), key(), "\"1\"", withProof).statusCode());
        JsonNode declared = json(call("GET", path, owner.cookie()));
        assertEquals("ATTACHED_NOT_ADMITTED", finding(declared, "DRIVER_DISCLOSURE").path("state").asText());
        assertEquals("ATTACHED_NOT_ADMITTED", finding(declared, "OWNER_CONTRACT").path("state").asText());
        assertFalse(finding(declared, "OWNER_CONTRACT").path("clientAction").asBoolean());
        assertFalse(finding(declared, "OWNER_CONTRACT").path("admitsE8").asBoolean());
        assertTrue(finding(declared, "OWNER_CONTRACT").path("reason").asText().contains("não cria PP jurídico"));
        assertTrue(finding(declared, "DRIVER_DISCLOSURE").path("reason").asText().contains("não comprova a divulgação"));
        assertFalse(declared.toString().contains("PHOTO-2027"));
        assertEquals("AWAITING_NORMATIVE_EVALUATION", finding(declared, "REGULATORY_SURPLUS").path("state").asText());
        assertEquals("NOT_ADMITTED", finding(declared, "EVR").path("state").asText());
        assertFalse(finding(declared, "EVR").path("admitsE8").asBoolean());
        assertFalse(declared.path("canRunE8").asBoolean());
    }

    @Test
    void changedRightsAskForRecheckWithoutAdmittingTheSource() throws Exception {
        Actor owner = actor();
        String id = project(owner, organization(owner.id()));
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/intake", owner.cookie(),
            key(), "\"0\"", """
                {"activity":"Recarga aberta","country":"BR","candidateMethodology":"VM0038"}
                """).statusCode());
        String first = """
            {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
             "chargerType":"DCFC","propulsion":"BEV","electricitySource":"SIN",
             "associatedInfrastructure":false,"v2g":false,
             "chargingOwner":"Titular","carbonRightsContractRef":"CONTRACT-001",
             "driverDisclosureChannel":"ON_CHARGER","driverDisclosureEvidenceRef":"PHOTO-1",
             "driverDisclosureEffectiveOn":"2027-01-15","doubleClaimingCheck":"NO_OVERLAP_DECLARED",
             "effectiveFrom":"2027-02-01"}
            """;
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/open-applicability",
            owner.cookie(), key(), "\"0\"", first).statusCode());
        JsonNode before = json(call("GET", "/v1/projects/" + id + "/technical/requirements", owner.cookie()));
        assertFalse(before.findValuesAsText("requirementId").contains("RIGHTS_DEPENDENCY"));
        String changed = first.replace("CONTRACT-001", "CONTRACT-002").replace("PHOTO-1", "PHOTO-2");
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/open-applicability",
            owner.cookie(), key(), "\"1\"", changed).statusCode());
        JsonNode after = json(call("GET", "/v1/projects/" + id + "/technical/requirements", owner.cookie()));
        assertEquals("RECHECK_REQUIRED", finding(after, "RIGHTS_DEPENDENCY").path("state").asText());
        assertFalse(finding(after, "RIGHTS_DEPENDENCY").path("admitsE8").asBoolean());
        assertEquals("AWAITING_NORMATIVE_EVALUATION", finding(after, "REGULATORY_SURPLUS").path("state").asText());
        assertEquals("NOT_ADMITTED", finding(after, "EVR").path("state").asText());
        assertEquals("NOT_INCIDENT", finding(after, "ED_EC").path("state").asText());
        assertFalse(after.path("canRunE8").asBoolean());
        assertEquals(2L, database.read(c -> first(c, """
            SELECT count(*) FROM app_project_open_applicability_revision WHERE project_id=?
            """, r -> r.getLong(1), id)).longValue());
    }

    @Test
    void otherMethodologyAndRevokedCalculationGrantStayClosed() throws Exception {
        Actor owner = actor(), calculator = actor(), outsider = actor();
        UUID org = organization(owner.id());
        String id = project(owner, org);
        String path = "/v1/projects/" + id + "/technical/requirements";
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/intake", owner.cookie(),
            key(), "\"0\"", """
                {"activity":"Outra atividade","country":"BR","candidateMethodology":"ACM0002"}
                """).statusCode());
        JsonNode foreign = json(call("GET", path, owner.cookie()));
        assertFalse(foreign.path("openBranch").asBoolean());
        assertEquals("OUT_OF_SCOPE", foreign.at("/requirements/0/state").asText());
        assertFalse(foreign.toString().contains("VMD0049_ROUTE"));
        assertFalse(foreign.toString().contains("\"EVR\""));

        UUID grant = UUID.randomUUID();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, 'PREP', ARRAY['CALCULATION']::text[],
                   statement_timestamp()+interval '3 days')
                """, grant, id, org, calculator.id(), owner.id());
            return null;
        });
        JsonNode limited = json(call("GET", path, calculator.cookie()));
        assertFalse(limited.toString().contains("Titular"));
        assertFalse(limited.findValuesAsText("requirementId").contains("DRIVER_DISCLOSURE"));
        assertEquals(404, call("GET", path, outsider.cookie()).statusCode());
        database.transaction(c -> {
            update(c, """
                UPDATE app_project_collaborator_grant
                SET revoked_at=statement_timestamp(),revoked_by=? WHERE id=?
                """, owner.id(), grant);
            return null;
        });
        assertEquals(404, call("GET", path, calculator.cookie()).statusCode());
        assertTrue(database.read(c -> first(c, """
            SELECT count(*) FROM app_project_technical_access_event
            WHERE actor_id=? AND target_digest=? AND action='VIEW_JOURNEY' AND outcome='DENIED'
            """, r -> r.getLong(1), calculator.id(), AccessSecrets.digest(id))) >= 1L);
    }

    @Test
    void journeyShowsTheSameRequirementAndAcceptsItsDestination() throws Exception {
        Actor owner = actor(), preparer = actor(), calculator = actor();
        UUID org = organization(owner.id());
        String id = project(owner, org);
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/intake", owner.cookie(),
            key(), "\"0\"", """
                {"activity":"Recarga aberta","country":"BR","candidateMethodology":"VM0038"}
                """).statusCode());
        JsonNode requirements = json(call("GET", "/v1/projects/" + id + "/technical/requirements", owner.cookie()));
        JsonNode journey = json(call("GET", "/v1/projects/" + id + "/journey", owner.cookie()));
        JsonNode fromRequirements = finding(requirements, "NO_DECLARATION");
        JsonNode fromJourney = finding(journey.path("diagnosis"), "NO_DECLARATION");
        assertEquals(fromRequirements.path("state").asText(), fromJourney.path("state").asText());
        assertEquals(fromRequirements.path("group").asText(), fromJourney.path("group").asText());
        assertEquals("analisar", fromJourney.at("/destination/screen").asText());
        assertEquals("MINE", fromJourney.path("group").asText());
        assertTrue(journey.path("progress").path("visible").asInt() > 0);
        assertFalse(journey.path("progress").toString().contains("NO_DECLARATION"));
        String destination = """
            {"area":"technical","screen":"analisar","guide":null,"step":null}
            """;
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/journey/resume",
            owner.cookie(), null, null, destination).statusCode());
        assertEquals("analisar", json(call("GET", "/v1/projects/" + id + "/journey", owner.cookie()))
            .at("/resume/screen").asText());
        assertEquals(422, call("PUT", "/v1/projects/" + id + "/journey/resume", owner.cookie(), null, null, """
            {"area":"technical","screen":"analisar","guide":null,"step":"parecer"}
            """).statusCode());

        database.transaction(c -> {
            update(c, """
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, 'PREP', ARRAY['INTAKE']::text[],
                   statement_timestamp()+interval '3 days')
                """, UUID.randomUUID(), id, org, preparer.id(), owner.id());
            update(c, """
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, 'PREP', ARRAY['CALCULATION']::text[],
                   statement_timestamp()+interval '3 days')
                """, UUID.randomUUID(), id, org, calculator.id(), owner.id());
            return null;
        });
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/journey/resume",
            preparer.cookie(), null, null, destination).statusCode());
        JsonNode preparerDiagnosis = json(call("GET", "/v1/projects/" + id + "/journey", preparer.cookie()))
            .path("diagnosis");
        assertFalse(preparerDiagnosis.findValuesAsText("requirementId").contains("NO_DECLARATION"));
        assertEquals(422, call("PUT", "/v1/projects/" + id + "/journey/resume",
            calculator.cookie(), null, null, destination).statusCode());
        JsonNode calculatorDiagnosis = json(call("GET", "/v1/projects/" + id + "/journey", calculator.cookie()))
            .path("diagnosis");
        assertFalse(calculatorDiagnosis.findValuesAsText("requirementId").contains("NO_DECLARATION"));
        assertFalse(calculatorDiagnosis.findValuesAsText("requirementId").contains("DRIVER_DISCLOSURE"));
        assertEquals("NOT_INCIDENT", finding(calculatorDiagnosis, "ED_EC").path("state").asText());
    }

    @Test
    void homologationSeparatesRolesVersionsAndRejectedWrites() throws Exception {
        Actor owner = actor(), preparer = actor(), reviewer = actor(), operator = actor();
        UUID org = organization(owner.id());
        String id = project(owner, org);
        String path = "/v1/projects/" + id + "/technical/requirements";
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/intake", owner.cookie(),
            key(), "\"0\"", """
                {"activity":"Recarga aberta","country":"BR","candidateMethodology":"VM0038"}
                """).statusCode());
        String declared = """
            {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
             "chargerType":"DCFC","propulsion":"BEV","electricitySource":"SIN",
             "associatedInfrastructure":false,"v2g":false,
             "chargingOwner":"Titular","carbonRightsContractRef":"CONTRACT-SEGREDO",
             "driverDisclosureChannel":"ON_CHARGER","driverDisclosureEvidenceRef":"PHOTO-SEGREDO",
             "driverDisclosureEffectiveOn":"2027-01-15","doubleClaimingCheck":"NO_OVERLAP_DECLARED",
             "effectiveFrom":"2027-02-01"}
            """;
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/open-applicability",
            owner.cookie(), key(), "\"0\"", declared).statusCode());
        JsonNode ownerView = json(call("GET", path, owner.cookie()));
        assertFalse(ownerView.path("canRunE8").asBoolean());
        assertFalse(ownerView.toString().contains("CONTRACT-SEGREDO"));
        assertFalse(ownerView.toString().contains("assigneeId"));
        assertEquals("NOT_ADMITTED", finding(ownerView, "EVR").path("state").asText());
        assertEquals("NOT_INCIDENT", finding(ownerView, "ED_EC").path("state").asText());
        assertFalse(finding(ownerView, "ED_EC").path("blocksExAntePd").asBoolean());

        database.transaction(c -> {
            update(c, """
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, 'PREP', ARRAY['INTAKE']::text[],
                   statement_timestamp()+interval '3 days')
                """, UUID.randomUUID(), id, org, preparer.id(), owner.id());
            update(c, """
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, 'REV', ARRAY['DOCUMENTATION']::text[],
                   statement_timestamp()+interval '3 days')
                """, UUID.randomUUID(), id, org, reviewer.id(), owner.id());
            update(c, """
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, 'OP', ARRAY['MONITORING']::text[],
                   statement_timestamp()+interval '3 days')
                """, UUID.randomUUID(), id, org, operator.id(), owner.id());
            return null;
        });
        String resume = """
            {"area":"technical","screen":"analisar","guide":null,"step":null}
            """;
        for (Actor limited : List.of(preparer, reviewer, operator)) {
            JsonNode hidden = json(call("GET", path, limited.cookie()));
            assertFalse(hidden.path("diagnosticVisible").asBoolean());
            assertEquals(0, hidden.path("requirements").size());
            assertFalse(hidden.path("canRunE8").asBoolean());
            assertFalse(hidden.toString().contains("CONTRACT-SEGREDO"));
            assertFalse(hidden.toString().contains("PHOTO-SEGREDO"));
            assertFalse(hidden.toString().contains("EVR"));
        }
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/journey/resume",
            preparer.cookie(), null, null, resume).statusCode());
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/journey/resume",
            reviewer.cookie(), null, null, resume).statusCode());
        assertEquals(422, call("PUT", "/v1/projects/" + id + "/journey/resume",
            operator.cookie(), null, null, resume).statusCode());

        String renamedOwner = declared.replace("\"Titular\"", "\"Outro titular\"");
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/open-applicability",
            owner.cookie(), key(), "\"1\"", renamedOwner).statusCode());
        JsonNode afterRename = json(call("GET", path, owner.cookie()));
        assertFalse(afterRename.findValuesAsText("requirementId").contains("RIGHTS_DEPENDENCY"));
        assertEquals("Titular", database.read(c -> first(c, """
            SELECT charging_owner FROM app_project_open_applicability_revision
            WHERE project_id=? AND version=1
            """, r -> r.getString(1), id)));
        var rejected = call("PUT", "/v1/projects/" + id + "/technical/open-applicability",
            owner.cookie(), key(), "\"1\"", renamedOwner.replace("Outro titular", "Terceiro titular"));
        assertEquals(412, rejected.statusCode(), rejected.body());
        assertEquals(404, call("POST", path, owner.cookie(), key(), null,
            "{\"canRunE8\":true}").statusCode());
        assertEquals(2L, database.read(c -> first(c, """
            SELECT count(*) FROM app_project_open_applicability_revision WHERE project_id=?
            """, r -> r.getLong(1), id)).longValue());
        assertFalse(json(call("GET", path, owner.cookie())).path("canRunE8").asBoolean());
    }

    @Test
    void openNetworkAsksForBoundaryAndSeparateMetersWithoutInventingEnergy() throws Exception {
        Actor owner = actor();
        String id = project(owner, organization(owner.id()));
        String path = "/v1/projects/" + id + "/technical/requirements";
        String root = "/v1/projects/" + id + "/technical";
        assertEquals(200, call("PUT", root + "/intake", owner.cookie(), key(), "\"0\"", """
            {"activity":"Recarga aberta","country":"BR","candidateMethodology":"VM0038"}
            """).statusCode());
        JsonNode undeclared = json(call("GET", path, owner.cookie()));
        assertFalse(undeclared.findValuesAsText("requirementId").contains("BOUNDARY"));

        assertEquals(200, call("PUT", root + "/open-applicability", owner.cookie(), key(), "\"0\"", """
            {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
             "chargerType":"DCFC","propulsion":"BEV","electricitySource":"SIN",
             "associatedInfrastructure":false,"v2g":false,
             "chargingOwner":"Titular","carbonRightsContractRef":"CONTRACT-001",
             "driverDisclosureChannel":"ON_CHARGER","driverDisclosureEvidenceRef":"PHOTO-1",
             "driverDisclosureEffectiveOn":"2027-01-15","doubleClaimingCheck":"NO_OVERLAP_DECLARED",
             "effectiveFrom":"2027-02-01"}
            """).statusCode());
        JsonNode plan = json(call("GET", path, owner.cookie()));
        assertEquals("ABSENT", finding(plan, "BOUNDARY").path("state").asText());
        assertEquals("estacoes", finding(plan, "BOUNDARY").at("/destination/screen").asText());
        assertEquals("NOT_INCIDENT", finding(plan, "ED_EC").path("state").asText());
        assertFalse(finding(plan, "ED_EC").path("blocksExAntePd").asBoolean());
        assertFalse(plan.findValuesAsText("requirementId").contains("ED"));
        assertFalse(plan.findValuesAsText("requirementId").contains("EC"));
        assertFalse(plan.toString().contains("2700"));
        assertFalse(plan.path("canRunE8").asBoolean());

        String site = """
            {"name":"Estação Norte","municipality":"Brasília","state":"DF",
             "address":"Via norte 100","operationalState":"PLANNED","plannedOn":"2027-01-01",
             "commissionedOn":null,"sourceReference":"PLANO-NORTE-1"}
            """;
        assertEquals(200, call("PUT", root + "/open-sites/sites/ST-01", owner.cookie(),
            key(), "\"0\"", site).statusCode());
        assertEquals(200, call("PUT", root + "/open-monitoring/chargers/CH-01", owner.cookie(),
            key(), "\"0\"", """
            {"siteCode":"ST-01","chargerKind":"DCFC","ratedPowerKw":150.000,
             "grid":"SIN","topology":"GRID_ONLY","timeZone":"America/Sao_Paulo",
             "sourceReference":"DOC-CHARGER-123"}
            """).statusCode());
        assertEquals(200, call("PUT", root + "/open-sites/boundary", owner.cookie(), key(), "\"0\"", """
            {"chargers":[{"code":"CH-01","version":1}]}
            """).statusCode());
        JsonNode meters = json(call("GET", path, owner.cookie()));
        assertFalse(meters.findValuesAsText("requirementId").contains("BOUNDARY"));
        assertEquals("ABSENT", finding(meters, "ED_METER").path("state").asText());
        assertEquals("ABSENT", finding(meters, "EC_METER").path("state").asText());
        assertTrue(finding(meters, "ED_METER").path("reason").asText().contains("distinto da energia consumida"));
        assertTrue(finding(meters, "EC_METER").path("reason").asText().contains("não há energia medida"));
        assertFalse(meters.findValuesAsText("requirementId").contains("ENERGY_SERIES"));
        assertFalse(finding(meters, "ED_METER").path("admitsE8").asBoolean());

        String instrument = """
            {"chargerCode":"CH-01","energyKind":"ED","meterPosition":"DOWNSTREAM",
             "sourceSystem":"meter-control-system","calibrationEvidenceRef":"CAL-ED-1",
             "calibrationValidFrom":"2027-01-01T00:00:00Z",
             "calibrationValidUntil":"2028-01-01T00:00:00Z",
             "configuredFrom":"2027-01-01T00:00:00Z",
             "configuredUntil":"2028-01-01T00:00:00Z","timeZone":"America/Sao_Paulo"}
            """;
        assertEquals(200, call("PUT", root + "/open-monitoring/meters/ED-1", owner.cookie(),
            key(), "\"0\"", instrument).statusCode());
        assertEquals(200, call("PUT", root + "/open-monitoring/meters/EC-1", owner.cookie(),
            key(), "\"0\"", instrument.replace("\"ED\"", "\"EC\"")
                .replace("DOWNSTREAM", "UPSTREAM").replace("CAL-ED-1", "CAL-EC-1")).statusCode());
        JsonNode series = json(call("GET", path, owner.cookie()));
        assertEquals("PENDING_MEASUREMENT", finding(series, "ENERGY_SERIES").path("state").asText());
        assertTrue(finding(series, "ENERGY_SERIES").path("reason").asText()
            .contains("calibração declarada não admite"));
        assertFalse(series.findValuesAsText("requirementId").contains("ED_METER"));
        assertFalse(series.findValuesAsText("requirementId").contains("ED"));
        assertFalse(series.toString().contains("2700"));
        assertFalse(series.path("canRunE8").asBoolean());
        assertEquals("NOT_INCIDENT", finding(series, "ED_EC").path("state").asText());

        assertEquals(200, call("PUT", root + "/open-sites/sites/ST-01", owner.cookie(),
            key(), "\"1\"", site.replace("Estação Norte", "Estação Norte revista")).statusCode());
        JsonNode stale = json(call("GET", path, owner.cookie()));
        assertEquals("STALE", finding(stale, "BOUNDARY").path("state").asText());
        assertFalse(stale.findValuesAsText("requirementId").contains("ENERGY_SERIES"));
        assertFalse(stale.findValuesAsText("requirementId").contains("ED_METER"));
        assertFalse(stale.path("canRunE8").asBoolean());
    }

    private static JsonNode finding(JsonNode view, String id) {
        for (var item : view.path("requirements")) {
            if (id.equals(item.path("requirementId").asText())) return item;
        }
        fail("requisito ausente: " + id + " em " + view);
        return null;
    }

    private Actor actor() {
        UUID id = UUID.randomUUID();
        String token = AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Diagnóstico teste','ACTIVE',statement_timestamp())
                """, id, id + "@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """, AccessSecrets.digest(token), id);
            return null;
        });
        return new Actor(id, AccountAccess.COOKIE + "=" + token);
    }

    private UUID organization(UUID owner) {
        UUID id = UUID.randomUUID();
        database.transaction(c -> {
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Diagnóstico teste')", id);
            update(c, "INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')", id, owner);
            return null;
        });
        return id;
    }

    private String project(Actor owner, UUID org) throws Exception {
        var result = call("POST", "/v1/projects", owner.cookie(), key(), null,
            "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto do diagnóstico\"}");
        assertEquals(201, result.statusCode(), result.body());
        return json(result).path("projectId").asText();
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private HttpResponse<String> call(String method, String path, String cookie) throws Exception {
        return call(method, path, cookie, null, null, null);
    }

    private HttpResponse<String> call(String method, String path, String cookie, String key,
            String match, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if (cookie != null) request.header("Cookie", cookie);
        if (key != null) request.header("Idempotency-Key", key);
        if (match != null) request.header("If-Match", match);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
}
