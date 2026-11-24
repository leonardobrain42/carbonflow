package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
/** Exact declared arithmetic, not a normative selection of allocation method. */
final class OpenDyAllocations {
    record Input(String program,String registryProject,String issuanceId,String method,
                 String totalTco2e,String shareNumerator,String shareDenominator,String basisUnit,
                 String sourceId,String sourceEdition,String locator,String rationale) {}
    record Row(int issuanceOrdinal,String program,String registryProject,String issuanceId,String method,
               String totalTco2e,String shareNumerator,String shareDenominator,String brazilTco2e,String basisUnit,
               String sourceId,String sourceEdition,String locator,String rationale) {}
    private record Key(String program,String project,String issuance) {}
    static final String EV_BASIS="EV_IN_CIRCULATION";
    private OpenDyAllocations() {}

    static List<Row> normalize(List<Input> supplied,List<OpenDyIssuances.Row> items) {
        if (supplied==null || supplied.isEmpty()) return List.of();
        if (supplied.size()!=items.size() || supplied.size()>100)
            throw invalid("A atribuição estruturada exige uma referência para cada emissão positiva.");
        var index=new HashMap<Key,Integer>();
        for (int i=0;i<items.size();i++) {
            var item=items.get(i);
            index.put(key(item.program(),item.registryProject(),item.issuanceId()),i);
        }
        var seen=new HashSet<Integer>();
        var rows=new ArrayList<Row>();
        for (var input:supplied) {
            if (input==null) throw invalid("Atribuição inválida.");
            var k=key(OpenDyAssessmentRules.text(input.program(),1,120),
                OpenDyAssessmentRules.text(input.registryProject(),1,160),
                OpenDyAssessmentRules.text(input.issuanceId(),1,160));
            Integer i=index.get(k);
            if (i==null || !seen.add(i)) throw invalid("Atribuição duplicada ou emissão não identificada.");
            var item=items.get(i);
            var total=OpenDyAssessmentRules.decimal(input.totalTco2e());
            var numerator=OpenDyAssessmentRules.decimal(input.shareNumerator());
            var denominator=OpenDyAssessmentRules.decimal(input.shareDenominator());
            var brazil=new BigDecimal(item.creditsTco2e());
            if (total.signum()<=0 || numerator.signum()<=0 || denominator.compareTo(numerator)<0)
                throw invalid("Total e base positivos; parcela não pode exceder o total.");
            boolean direct="DIRECT_BR".equals(input.method());
            boolean shared="DOCUMENTED_SHARE".equals(input.method());
            if ((!direct && !shared) || (direct && (numerator.compareTo(BigDecimal.ONE)!=0
                    || denominator.compareTo(BigDecimal.ONE)!=0)) || (shared && numerator.compareTo(denominator)>=0))
                throw invalid("Atribuição direta exige 1/1; rateio exige fração positiva inferior a 1.");
            // Q05 (D14): a documented share uses the number of EVs in circulation as its only basis.
            if (shared && !EV_BASIS.equals(OpenDyAssessmentRules.text(input.basisUnit(),1,80)))
                throw invalid("Rateio exige a base "+EV_BASIS+" (EVs em circulação); outra base exige decisão específica.");
            if (brazil.multiply(denominator).compareTo(total.multiply(numerator))!=0)
                throw invalid("Quantidade BR diverge do total multiplicado pela fração declarada.");
            rows.add(new Row(i+1,item.program(),item.registryProject(),item.issuanceId(),input.method(),
                num(total),num(numerator),num(denominator),num(brazil),
                OpenDyAssessmentRules.text(input.basisUnit(),1,80),
                OpenDyAssessmentRules.text(input.sourceId(),1,160),
                OpenDyAssessmentRules.text(input.sourceEdition(),1,160),
                OpenDyAssessmentRules.text(input.locator(),3,1000),
                OpenDyAssessmentRules.text(input.rationale(),15,2000)));
        }
        rows.sort(Comparator.comparingInt(Row::issuanceOrdinal));
        return List.copyOf(rows);
    }
    static void store(Connection c,UUID assessment,List<Row> rows) throws SQLException {
        for (var row:rows) update(c,"""
            INSERT INTO app_open_dy_allocation
            (assessment_id,issuance_ordinal,method,total_tco2e,share_numerator,share_denominator,
             brazil_tco2e,basis_unit,source_id,source_edition,locator,rationale)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,assessment,row.issuanceOrdinal(),row.method(),new BigDecimal(row.totalTco2e()),
            new BigDecimal(row.shareNumerator()),new BigDecimal(row.shareDenominator()),
            new BigDecimal(row.brazilTco2e()),row.basisUnit(),row.sourceId(),row.sourceEdition(),row.locator(),row.rationale());
    }
    static List<Row> read(Connection c,UUID assessment) throws SQLException {
        return query(c,"""
            SELECT a.issuance_ordinal,i.program,i.registry_project,i.issuance_id,a.method,
              a.total_tco2e,a.share_numerator,a.share_denominator,a.brazil_tco2e,a.basis_unit,
              a.source_id,a.source_edition,a.locator,a.rationale
            FROM app_open_dy_allocation a JOIN app_open_dy_issuance i
              ON i.assessment_id=a.assessment_id AND i.ordinal=a.issuance_ordinal
            WHERE a.assessment_id=? ORDER BY a.issuance_ordinal
            """,r->new Row(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
            num(r.getBigDecimal(6)),num(r.getBigDecimal(7)),num(r.getBigDecimal(8)),num(r.getBigDecimal(9)),
            r.getString(10),r.getString(11),r.getString(12),r.getString(13),r.getString(14)),assessment);
    }
    private static String num(BigDecimal value) { return OpenDyAssessmentRules.number(value); }
    private static Key key(String p,String j,String i) {
        return new Key(p.toLowerCase(Locale.ROOT),j.toLowerCase(Locale.ROOT),i.toLowerCase(Locale.ROOT));
    }
    private static IllegalArgumentException invalid(String detail) {return new IllegalArgumentException(detail);}
}
