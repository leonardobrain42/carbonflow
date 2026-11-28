package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * BL-EX-03 (BL-T08/T15): a PD "baseline" section version is frozen against the EXACT EX ANTE reference (version and
 * selection hash) it was written with. A later reference version or a stale one flags only THIS section as outdated;
 * the stored version, its artifact and its hash are never rewritten. Metadata only: no stock, rating or percentage.
 */
final class OpenPdBaselineDependency {
    static final String SECTION = "baseline";
    record Reference(int version, String selectionSha256, int observationYear, String cycle, String state,
                     boolean stale) {}
    record Dependency(String state, boolean outdated, Integer pinnedVersion, String pinnedSelectionSha256,
                      Integer currentVersion, String currentSelectionSha256, String text) {}

    private OpenPdBaselineDependency() {}

    /** Newest EX ANTE selection of the project, or null. Read for provenance, so it does not depend on the viewer. */
    static Reference current(Connection c, String project) throws SQLException {
        return OpenBaselineFleetSelections.history(c, project).stream().findFirst()
            .map(v -> new Reference(v.version(), v.selectionSha256(), v.observationYear(), v.cycle(), v.state(),
                v.stale() || v.usableForE8() || !"PREPARATORY_NOT_ADMITTED".equals(v.state())))
            .orElse(null);
    }

    static Reference pinned(Connection c, String project, int sectionVersion) throws SQLException {
        if (sectionVersion <= 0) return null;
        return first(c, """
            SELECT d.selection_version,d.selection_sha256,s.observation_year,s.cycle
            FROM app_pd_baseline_dependency d
            JOIN app_open_baseline_fleet_selection s ON (s.project_id,s.version)=(d.project_id,d.selection_version)
            WHERE d.project_id=? AND d.section_id=? AND d.section_version=?
            """, r -> new Reference(r.getInt(1), r.getString(2), r.getInt(3), r.getString(4),
                "PREPARATORY_NOT_ADMITTED", false), project, SECTION, sectionVersion);
    }

    static void pin(Connection c, String project, int sectionVersion) throws SQLException {
        Reference now = current(c, project);
        if (now == null) return;
        update(c, """
            INSERT INTO app_pd_baseline_dependency(project_id,section_id,section_version,selection_version,selection_sha256)
            VALUES(?,?,?,?,?)
            """, project, SECTION, sectionVersion, now.version(), now.selectionSha256());
    }

    /** True when the stored reference and the current one are the same exact reference (both absent counts as same). */
    static boolean same(Reference pinned, Reference now) {
        if (pinned == null || now == null) return pinned == now;
        return pinned.version() == now.version() && Objects.equals(pinned.selectionSha256(), now.selectionSha256());
    }

    /** Null for other sections and when there is neither a pinned nor a current reference (nothing to say). */
    static Dependency evaluate(Connection c, String project, String section, int sectionVersion) throws SQLException {
        if (!SECTION.equals(section) || sectionVersion <= 0) return null;
        return evaluate(pinned(c, project, sectionVersion), current(c, project));
    }

    static Dependency evaluate(Reference pinned, Reference now) {
        if (pinned == null && now == null) return null;
        Integer pv = pinned == null ? null : pinned.version(), cv = now == null ? null : now.version();
        String ps = pinned == null ? null : pinned.selectionSha256(), cs = now == null ? null : now.selectionSha256();
        if (pinned == null) return new Dependency("REFERENCE_NOT_CITED", false, null, null, cv, cs,
            "Esta versão foi redigida sem citar a referência de frota ex ante, que existe agora (versão " + cv
                + "). Gere uma nova versão se quiser citá-la.");
        if (now == null) return new Dependency("REFERENCE_MISSING", true, pv, ps, null, null,
            "A referência de frota ex ante citada (versão " + pv + ") não está mais disponível. Gere uma nova versão.");
        if (!same(pinned, now)) return new Dependency("REFERENCE_SUPERSEDED", true, pv, ps, cv, cs,
            "A referência de frota ex ante mudou desde a redação desta versão (citava a versão " + pv
                + "; a vigente é a " + cv + "). Gere uma nova versão. Esta versão continua íntegra.");
        if (now.stale()) return new Dependency("REFERENCE_STALE", true, pv, ps, cv, cs,
            "A referência de frota ex ante citada (versão " + pv + ") deixou de estar corrente. "
                + "Reconfirme a referência e gere uma nova versão. Esta versão continua íntegra.");
        return new Dependency("REFERENCE_CURRENT", false, pv, ps, cv, cs,
            "A versão cita a referência de frota ex ante vigente (versão " + pv + "), preparatória e não admitida.");
    }
}
