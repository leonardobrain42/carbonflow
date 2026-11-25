package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Itemized issuances from the start of crediting up to, excluding, the annual window (D14 Q05.1).
 * Declared references only: no admission, no coverage proof for this earlier span, no use by the executor.
 */
final class OpenDyCumulativeItems {
    private OpenDyCumulativeItems() {}

    static List<OpenDyIssuances.Row> normalize(List<OpenDyIssuances.Input> supplied,
                                               OpenDyAssessmentRules.Cumulative cumulative) {
        if (supplied==null || supplied.isEmpty()) return List.of();
        if (cumulative==null)
            throw new IllegalArgumentException("Itens anteriores exigem os totais acumulados declarados.");
        return OpenDyIssuances.normalize(supplied);
    }

    /**
     * Prior items lie in [creditStart, annualFrom), do not repeat an annual item, and together with the
     * annual totals must equal the declared cumulative totals.
     */
    static void reconcile(List<OpenDyIssuances.Row> items,List<OpenDyIssuances.Row> annual,
                          OpenDyAssessmentRules.Values values,OpenDyAssessmentRules.Cumulative cumulative,
                          LocalDate creditStart,OffsetDateTime annualFrom) {
        if (items.isEmpty()) return;
        if (cumulative==null || values.erc()==null || creditStart==null || annualFrom==null)
            throw new IllegalArgumentException("Itens anteriores exigem totais acumulados, início de creditação e janela anual.");
        var end=annualFrom.atZoneSameInstant(VintageWindows.ZONE).toLocalDate();
        var seen=new java.util.HashSet<String>();
        for (var row:annual) seen.add(key(row));
        BigDecimal erc=BigDecimal.ZERO,erf=BigDecimal.ZERO;
        for (var row:items) {
            if (row.issuedOn().isBefore(creditStart) || !row.issuedOn().isBefore(end))
                throw new IllegalArgumentException("Emissão anterior fora do intervalo entre o início da creditação e a janela anual.");
            if (!seen.add(key(row)))
                throw new IllegalArgumentException("Emissão repetida entre a janela anual e o período anterior.");
            var amount=new BigDecimal(row.creditsTco2e());
            if ("ERC".equals(row.creditKind())) erc=erc.add(amount); else erf=erf.add(amount);
        }
        if (values.erc().add(erc).compareTo(cumulative.erc())!=0 || values.erf().add(erf).compareTo(cumulative.erf())!=0)
            throw new IllegalArgumentException("Totais anuais mais itens anteriores não coincidem com os acumulados declarados.");
    }

    static void store(Connection c,UUID id,List<OpenDyIssuances.Row> rows) throws SQLException {
        int ordinal=0;
        for (var row:rows) update(c,"""
            INSERT INTO app_open_dy_cumulative_item
            (assessment_id,ordinal,program,registry_project,issuance_id,credit_kind,
             issued_on,geography,credits_tco2e,source_id,source_edition,locator)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,++ordinal,row.program(),row.registryProject(),row.issuanceId(),row.creditKind(),
            row.issuedOn(),row.geography(),new BigDecimal(row.creditsTco2e()),
            row.sourceId(),row.sourceEdition(),row.locator());
    }

    static List<OpenDyIssuances.Row> read(Connection c,UUID id) throws SQLException {
        return query(c,"""
            SELECT program,registry_project,issuance_id,credit_kind,issued_on,geography,
                credits_tco2e,source_id,source_edition,locator
            FROM app_open_dy_cumulative_item WHERE assessment_id=? ORDER BY ordinal
            """,r->new OpenDyIssuances.Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
                r.getObject(5,LocalDate.class),r.getString(6),
                OpenDyAssessmentRules.number(r.getBigDecimal(7)),r.getString(8),r.getString(9),r.getString(10)),id);
    }

    private static String key(OpenDyIssuances.Row r) {
        return (r.program()+"\u0000"+r.registryProject()+"\u0000"+r.issuanceId()).toLowerCase(Locale.ROOT);
    }
}
