package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * D18 slice 3: the registry version and the code package the project was prepared against. Written (append-only)
 * whenever the triage candidate, the registry version or the package digest changes; read with flags that say a
 * newer version exists, never applied automatically (a new version does not alter an existing project).
 */
final class MethodologyPin {
    record Pin(int version, int triageVersion, String methodology, String edition, Integer registryVersion,
               String registryDigest, String packageId, Integer packageVersion, String packageDigest,
               Integer catalogVersion, OffsetDateTime pinnedAt, boolean newerRegistryVersion,
               boolean packageChanged) {}

    private record Resolved(String methodology, String edition, Integer registryVersion, String registryDigest,
                            String packageId, Integer packageVersion, String packageDigest,
                            Integer catalogVersion) {}

    private MethodologyPin() {}

    /** Called inside the transaction that saves a triage revision. Idempotent for an unchanged resolution. */
    static void record(Connection connection, DynamicProjectScope.Context scope, int triageVersion,
                       String candidate) throws SQLException {
        Resolved now = resolve(connection, candidate);
        first(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> Boolean.TRUE,
                "methodology-pin:" + scope.id());
        Pin latest = latest(connection, scope.id());
        Resolved before = latest == null ? null : new Resolved(latest.methodology(), latest.edition(),
                latest.registryVersion(), latest.registryDigest(), latest.packageId(), latest.packageVersion(),
                latest.packageDigest(), latest.catalogVersion());
        if (latest == null ? now.methodology() == null : now.equals(before)) return;
        update(connection, """
                INSERT INTO app_project_methodology_pin
                  (project_id,pin_version,organization_id,environment,triage_version,methodology,
                   methodology_edition,registry_version,registry_digest,package_id,package_version,
                   package_digest,catalog_version,actor_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, scope.id(), latest == null ? 1 : latest.version() + 1, scope.organization(),
                scope.environment(), triageVersion, now.methodology(), now.edition(), now.registryVersion(),
                now.registryDigest(), now.packageId(), now.packageVersion(), now.packageDigest(),
                now.catalogVersion(), scope.actor().id());
    }

    /** The pin in force for the project (null when none was ever written), with the drift flags. */
    static Pin current(Connection connection, String projectId) throws SQLException {
        Pin latest = latest(connection, projectId);
        if (latest == null || latest.methodology() == null) return latest;
        Resolved now = resolve(connection, latest.methodology() + " V" + latest.edition());
        boolean newer = now.registryVersion() != null && latest.registryVersion() != null
                && now.registryVersion() > latest.registryVersion();
        boolean changed = !Objects.equals(now.packageDigest(), latest.packageDigest());
        return new Pin(latest.version(), latest.triageVersion(), latest.methodology(), latest.edition(),
                latest.registryVersion(), latest.registryDigest(), latest.packageId(), latest.packageVersion(),
                latest.packageDigest(), latest.catalogVersion(), latest.pinnedAt(), newer, changed);
    }

    private static Pin latest(Connection connection, String projectId) throws SQLException {
        return first(connection, """
                SELECT pin_version,triage_version,methodology,methodology_edition,registry_version,registry_digest,
                       package_id,package_version,package_digest,catalog_version,pinned_at
                FROM app_project_methodology_pin WHERE project_id=? ORDER BY pin_version DESC LIMIT 1
                """, r -> new Pin(r.getInt(1), r.getInt(2), r.getString(3), r.getString(4),
                (Integer) r.getObject(5), r.getString(6), r.getString(7), (Integer) r.getObject(8), r.getString(9),
                (Integer) r.getObject(10), r.getObject(11, OffsetDateTime.class), false, false), projectId);
    }

    private static Resolved resolve(Connection connection, String candidate) throws SQLException {
        var status = MethodologyRegistry.candidateStatus(connection, candidate);
        boolean known = "PACKAGE".equals(status.state()) || "REGISTERED_NO_PACKAGE".equals(status.state());
        if (!known) return new Resolved(null, null, null, null, null, null, null, null);
        Integer[] version = new Integer[1];
        String digest = first(connection, """
                SELECT version||'|'||digest FROM app_methodology_registry
                WHERE methodology=? AND methodology_edition=? ORDER BY version DESC LIMIT 1
                """, r -> r.getString(1), status.methodology(), status.edition());
        String registryDigest = null;
        if (digest != null) {
            version[0] = Integer.valueOf(digest.substring(0, digest.indexOf('|')));
            registryDigest = digest.substring(digest.indexOf('|') + 1);
        }
        var pack = MethodologyPackages.current().packages().stream()
                .filter(item -> item.methodology().equals(status.methodology())
                        && item.edition().equals(status.edition())).findFirst().orElse(null);
        return new Resolved(status.methodology(), status.edition(), version[0], registryDigest,
                pack == null ? null : pack.packageId(), pack == null ? null : pack.version(),
                pack == null ? null : pack.digest(), pack == null ? null : MethodologyPackages.current().version());
    }
}
