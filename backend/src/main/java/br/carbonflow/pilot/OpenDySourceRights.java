package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.enterprise.context.ApplicationScoped;

/** Exact organizational license and decision, never an automatic substitute. */
@ApplicationScoped
class OpenDySourceRights {
    record Input(String sourceId,String sourceEdition,UUID licenseId) {}
    record Pin(String sourceId,String sourceEdition,UUID licenseId,int decisionVersion) {}
    private record Source(String id,String edition) {}
    record Check(String state,List<Pin> pins) {
        boolean blocked() { return "LICENSE_RECHECK_REQUIRED".equals(state); }
        boolean bound() { return !pins.isEmpty(); }
    }
    private final PlatformSourceFiles rights;
    OpenDySourceRights(PlatformSourceFiles rights) { this.rights=rights; }

    static List<Input> normalize(List<Input> requested,List<OpenDyIssuances.Row> rows,List<OpenDySearchCoverage.Row> coverage,List<OpenDyAllocations.Row> allocations) {
        if (requested==null || requested.isEmpty()) {
            if (!coverage.isEmpty() || !allocations.isEmpty()) throw invalid();
            return List.of();
        }
        if (requested.size()>100) throw invalid();
        var expected=new HashSet<Source>();
        for (var row:rows) expected.add(new Source(row.sourceId(),row.sourceEdition()));
        for (var row:coverage) expected.add(new Source(row.sourceId(),row.sourceEdition()));
        for (var row:allocations) expected.add(new Source(row.sourceId(),row.sourceEdition()));
        var found=new HashSet<Source>();
        for (var input:requested) {
            if (input==null || input.licenseId()==null || input.sourceId()==null || input.sourceEdition()==null
                    || !found.add(new Source(input.sourceId(),input.sourceEdition()))) throw invalid();
        }
        if (!expected.equals(found)) throw invalid();
        return requested.stream().sorted(Comparator.comparing(Input::sourceId).thenComparing(Input::sourceEdition)).toList();
    }

    List<Pin> capture(Connection c,UUID org,List<Input> requested) throws SQLException {
        var result=new java.util.ArrayList<Pin>();
        for (var input:requested) {
            // Lock exact license before validating its decision; no new FK-backed decision
            // can be added concurrently until the transaction commits.
            first(c,"SELECT id FROM app_content_license WHERE id=? FOR UPDATE",r->r.getObject(1,UUID.class),input.licenseId());
            int version=version(c,org,input.sourceId(),input.sourceEdition(),input.licenseId());
            if (version==0) throw new PilotProblem(403,"DY_SOURCE_RIGHTS_REQUIRED","license","CF-OPEN-007",
                "A fonte exige licença organizacional vigente, com extração, visualização e derivados autorizados.");
            result.add(new Pin(input.sourceId(),input.sourceEdition(),input.licenseId(),version));
        }
        return List.copyOf(result);
    }

    void store(Connection c,UUID id,List<Pin> pins) throws SQLException {
        for (var pin:pins) update(c,"""
            INSERT INTO app_open_dy_source_license
              (assessment_id,source_id,source_edition,license_id,decision_version)
            VALUES (?,?,?,?,?)
            """,id,pin.sourceId(),pin.sourceEdition(),pin.licenseId(),pin.decisionVersion());
    }

    Check check(Connection c,UUID id) throws SQLException {
        var pins=query(c,"""
            SELECT source_id,source_edition,license_id,decision_version
            FROM app_open_dy_source_license WHERE assessment_id=? ORDER BY source_id,source_edition
            """,r->new Pin(r.getString(1),r.getString(2),r.getObject(3,UUID.class),r.getInt(4)),id);
        if (pins.isEmpty()) return new Check("NO_LICENSE_PIN",List.of());
        UUID org=first(c,"SELECT organization_id FROM app_open_dy_assessment WHERE id=?",
            r->r.getObject(1,UUID.class),id);
        for (var pin:pins) {
            if (version(c,org,pin.sourceId(),pin.sourceEdition(),pin.licenseId())!=pin.decisionVersion())
                return new Check("LICENSE_RECHECK_REQUIRED",List.copyOf(pins));
        }
        return new Check("LICENSE_DECISIONS_CURRENT",List.copyOf(pins));
    }

    private int version(Connection c,UUID org,String source,String edition,UUID license) throws SQLException {
        int version=0;
        for (String operation:List.of("EXTRACT_TEXT","SHOW_EXCERPTS","EXPORT_DERIVATIVES")) {
            var grant=rights.granted(c,Set.of(org),source,edition,license,operation);
            if (grant==null || (version!=0 && version!=grant.decisionVersion())) return 0;
            version=grant.decisionVersion();
        }
        return version;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Vincule uma licença por edição de todas as fontes das emissões, consultas e atribuições. A matriz e a atribuição exigem vínculos.");
    }
}
