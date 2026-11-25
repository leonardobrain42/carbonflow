package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * D18 slice 3, positive path: SOURCE_REFERENCE is ENABLED only when the package exists in code, every mandatory
 * source of the methodology has metadata and every one has an approved, current licence decided by someone other
 * than the submitter. The fixtures live in a transaction that is always rolled back, so no other test sees them.
 * Even with all three axes met, calculation/admission and external acts stay closed.
 */
@QuarkusTest
class MethodologyCapabilitiesTest {
    private static final String LEDGER_ONLY = "rollback";
    private static final UUID SUBMITTER = UUID.fromString("c88f1011-84bf-478a-8b86-2b5fd2881e01");
    private static final UUID DECIDER = UUID.fromString("c88f1011-84bf-478a-8b86-2b5fd2881e02");
    private static final MethodologyRegistry.CandidateStatus VM0038 =
            new MethodologyRegistry.CandidateStatus("PACKAGE", "VM0038", "1.1", 1);

    @Inject PilotDatabase database;

    private record Need(String sourceId, String edition, String policy) {}

    /** Runs the scenario inside a transaction and always rolls it back, handing back what the scenario recorded. */
    private List<String> rehearsal(Scenario scenario) {
        var seen = new ArrayList<String>();
        try {
            database.transaction(c -> {
                scenario.run(c, seen);
                throw new IllegalStateException(LEDGER_ONLY);
            });
            fail("the rehearsal must roll back");
        } catch (RuntimeException expected) {
            assertTrue(expected.toString().contains(LEDGER_ONLY) || expected.getCause() != null, expected.toString());
        }
        return seen;
    }

    private interface Scenario { void run(Connection c, List<String> seen) throws SQLException; }

    private static String state(Connection c, String purpose, boolean effective, String axis) throws SQLException {
        var item = MethodologyCapabilities.evaluate(c, VM0038).stream()
                .filter(p -> p.purpose().equals(purpose)).findFirst().orElseThrow();
        return effective ? item.effective() : switch (axis) {
            case "code" -> item.code().state();
            case "data" -> item.data().state();
            default -> item.licence().state();
        };
    }

    private static List<Need> needs(Connection c) throws SQLException {
        var result = new ArrayList<Need>();
        try (var st = c.prepareStatement("""
                SELECT DISTINCT source_id, source_edition, edition_policy FROM app_methodology_source_need
                WHERE methodology='VM0038' AND methodology_edition='1.1' AND mandatory AND kind <> 'PROJECT_EVIDENCE'
                """); var rs = st.executeQuery()) {
            while (rs.next()) result.add(new Need(rs.getString(1), rs.getString(2), rs.getString(3)));
        }
        return result;
    }

    /** The edition the capability query will pick: the exact one, or a top-sorting edition for YEAR/LATEST needs. */
    private static String edition(Need need) {
        return "EXACT".equals(need.policy()) ? need.edition() : "9999";
    }

    /** The edition the capability query resolves for a need: the top-sorting global one, honouring EXACT. */
    private static String resolved(Connection c, Need need) throws SQLException {
        try (var st = c.prepareStatement("""
                SELECT edition FROM app_source_version WHERE project_id IS NULL AND source_id=?
                  AND (? <> 'EXACT' OR edition=?) ORDER BY edition DESC LIMIT 1
                """)) {
            st.setString(1, need.sourceId());
            st.setString(2, need.policy());
            st.setString(3, need.edition());
            try (var rs = st.executeQuery()) {
                assertTrue(rs.next(), need.sourceId());
                return rs.getString(1);
            }
        }
    }

    private static void metadata(Connection c, List<Need> needs) throws SQLException {
        for (var need : needs) {
            update(c, """
                    INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,origin)
                    VALUES (?,?,'EXTERNAL_SOURCE','Fixture de teste','Editorial de teste','EXTERNAL')
                    ON CONFLICT DO NOTHING
                    """, need.sourceId(), edition(need));
        }
    }

    private static List<UUID> licences(Connection c, List<Need> needs, UUID submitter, UUID decider)
            throws SQLException {
        update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Fixture','ACTIVE',statement_timestamp()) ON CONFLICT(id) DO NOTHING
                """, SUBMITTER, SUBMITTER + "@example.invalid");
        update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Fixture','ACTIVE',statement_timestamp()) ON CONFLICT(id) DO NOTHING
                """, DECIDER, DECIDER + "@example.invalid");
        var ids = new ArrayList<UUID>();
        for (var need : needs) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            update(c, """
                    INSERT INTO app_content_license(id,source_id,edition,submitted_by,environment,purpose,
                        authorization_ref,proof_sha256,starts_at,ends_at)
                    VALUES (?,?,?,?,'SANDBOX','REQUIREMENT_CATALOG','Prova interna de testes',?,
                        '2025-01-01',statement_timestamp()+interval '1 hour')
                    """, id, need.sourceId(), resolved(c, need), submitter, "d".repeat(64));
            update(c, """
                    INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                    VALUES (?,1,'APPROVED',?,'Aprovação de teste, sem efeito fora da transação')
                    """, id, decider);
            update(c, "INSERT INTO app_content_license_capability(license_id,decision_version,operation) "
                    + "VALUES (?,1,'REFERENCE_REQUIREMENT')", id);
        }
        return ids;
    }

    @Test
    void sourceReferenceIsEnabledOnlyWhenCodeDataAndLicenceAreAllMetAndNothingElseOpens() {
        rehearsal((c, seen) -> {
            var needs = needs(c);
            assertFalse(needs.isEmpty(), "VM0038 1.1 has mandatory source needs");

            // Baseline in this database: no licence anywhere, so reference is not enabled.
            assertEquals("NOT_ENABLED", state(c, "SOURCE_REFERENCE", true, null));

            // Metadata only: data is met, licence is not, so still not enabled.
            metadata(c, needs);
            assertEquals("MET", state(c, "SOURCE_REFERENCE", false, "code"));
            assertEquals("MET", state(c, "SOURCE_REFERENCE", false, "data"));
            assertEquals("NOT_MET", state(c, "SOURCE_REFERENCE", false, "licence"));
            assertEquals("NOT_ENABLED", state(c, "SOURCE_REFERENCE", true, null));

            // The submitter cannot approve their own licence: the licence axis stays unmet.
            var ids = licences(c, needs, SUBMITTER, SUBMITTER);
            assertEquals("NOT_MET", state(c, "SOURCE_REFERENCE", false, "licence"));
            assertEquals("NOT_ENABLED", state(c, "SOURCE_REFERENCE", true, null));
            for (var id : ids) {
                update(c, "DELETE FROM app_content_license_capability WHERE license_id=?", id);
                update(c, "DELETE FROM app_content_license_decision WHERE license_id=?", id);
                update(c, "DELETE FROM app_content_license WHERE id=?", id);
            }

            // Independent approval of every mandatory source: all three axes met, reference enabled.
            ids = licences(c, needs, SUBMITTER, DECIDER);
            assertEquals("MET", state(c, "SOURCE_REFERENCE", false, "licence"));
            assertEquals("ENABLED", state(c, "SOURCE_REFERENCE", true, null));

            // The gates that matter stay closed whatever the three axes say.
            assertEquals("NOT_ENABLED", state(c, "CALCULATION_ADMISSION", true, null));
            assertEquals("NOT_ENABLED", state(c, "EXTERNAL_ACTS", true, null));

            // One missing licence is enough to close it again (every source, not any source).
            update(c, "DELETE FROM app_content_license_capability WHERE license_id=?", ids.get(0));
            assertEquals("NOT_ENABLED", state(c, "SOURCE_REFERENCE", true, null));
            update(c, "INSERT INTO app_content_license_capability(license_id,decision_version,operation) "
                    + "VALUES (?,1,'REFERENCE_REQUIREMENT')", ids.get(0));
            assertEquals("ENABLED", state(c, "SOURCE_REFERENCE", true, null));

            // A revocation (newer decision) removes the capability.
            update(c, """
                    INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
                    VALUES (?,2,'REVOKED',?,'Revogação de teste')
                    """, ids.get(1), DECIDER);
            assertEquals("NOT_ENABLED", state(c, "SOURCE_REFERENCE", true, null));
            seen.add("done");
        });
    }

    @Test
    void theFixturesDoNotLeakOutOfTheRehearsal() {
        rehearsal((c, seen) -> {
            metadata(c, needs(c));
            licences(c, needs(c), SUBMITTER, DECIDER);
            assertEquals("ENABLED", state(c, "SOURCE_REFERENCE", true, null));
        });
        Boolean enabled = database.read(c -> state(c, "SOURCE_REFERENCE", true, null).equals("ENABLED"));
        assertFalse(enabled, "the rolled-back licences must not be visible afterwards");
    }
}
