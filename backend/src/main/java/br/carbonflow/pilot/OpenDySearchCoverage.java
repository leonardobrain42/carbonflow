package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Declared search matrix, never proof that the programme universe is exhaustive. */
final class OpenDySearchCoverage {
    record Row(String program,String creditKind,String territory,String result,
               LocalDate searchedFrom,LocalDate searchedUntilExclusive,LocalDate searchedOn,
               String sourceId,String sourceEdition,String locator,String limitations) {}
    private record Key(String program,String kind) {}
    private OpenDySearchCoverage() {}

    static List<Row> normalize(List<Row> supplied) {
        if (supplied==null || supplied.isEmpty()) return List.of();
        if (supplied.size()>100) throw invalid("Máximo de 100 linhas de pesquisa.");
        var rows=new ArrayList<Row>();
        var seen=new HashSet<Key>();
        for (var row:supplied) {
            if (row==null || row.creditKind()==null || !Set.of("ERC","ERF").contains(row.creditKind())
                    || row.territory()==null || !Set.of("NATIONAL_BR","PARTIAL_BR").contains(row.territory())
                    || row.result()==null || !Set.of("FOUND","NO_RESULTS","INCOMPLETE").contains(row.result())
                    || row.searchedFrom()==null || row.searchedUntilExclusive()==null || row.searchedOn()==null
                    || !row.searchedUntilExclusive().isAfter(row.searchedFrom())
                    || (!"INCOMPLETE".equals(row.result()) && row.searchedOn().isBefore(row.searchedUntilExclusive())))
                throw invalid("Informe categoria, abrangência, resultado, janela e data da consulta coerentes.");
            String program=OpenDyAssessmentRules.text(row.program(),1,120);
            if (!seen.add(key(program,row.creditKind()))) throw invalid("Programa e categoria duplicados na matriz.");
            rows.add(new Row(program,row.creditKind(),row.territory(),row.result(),
                row.searchedFrom(),row.searchedUntilExclusive(),row.searchedOn(),
                OpenDyAssessmentRules.text(row.sourceId(),1,160),
                OpenDyAssessmentRules.text(row.sourceEdition(),1,160),
                OpenDyAssessmentRules.text(row.locator(),3,1000),
                OpenDyAssessmentRules.text(row.limitations(),8,2000)));
        }
        rows.sort(Comparator.comparing(Row::program,String.CASE_INSENSITIVE_ORDER).thenComparing(Row::creditKind));
        return List.copyOf(rows);
    }

    static void validate(List<Row> rows,List<OpenDyIssuances.Row> issuances,
                         OpenDyAssessmentRules.Values values,OffsetDateTime from,OffsetDateTime until) {
        if (rows.isEmpty()) return;
        boolean inconclusive="INCONCLUSIVE".equals(values.outcome());
        LocalDate start=from==null?null:from.atZoneSameInstant(VintageWindows.ZONE).toLocalDate();
        LocalDate end=until==null?null:until.atZoneSameInstant(VintageWindows.ZONE).toLocalDate();
        var cells=new HashMap<Key,Row>();
        var programs=new HashSet<String>();
        for (var row:rows) {
            var k=key(row.program(),row.creditKind());
            cells.put(k,row);programs.add(k.program());
            if (start!=null && end!=null && (row.searchedFrom().isBefore(start) || row.searchedUntilExclusive().isAfter(end)))
                throw invalid("A pesquisa não pode extrapolar a janela anterior do projeto.");
            if (!inconclusive && (!"NATIONAL_BR".equals(row.territory()) || "INCOMPLETE".equals(row.result())
                    || !row.searchedFrom().equals(start) || !row.searchedUntilExclusive().equals(end)))
                throw invalid("Lacuna territorial, temporal ou de consulta: registre pesquisa inconclusiva.");
        }
        if (inconclusive) return; // findings without a finalized inventory are not quantified
        for (String program:programs) {
            if (!cells.containsKey(new Key(program,"ERC")) || !cells.containsKey(new Key(program,"ERF")))
                throw invalid("Cada programa declarado exige pesquisa das duas categorias ERC e ERF.");
        }
        var matched=new HashSet<Key>();
        for (var item:issuances) {
            var k=key(item.program(),item.creditKind());
            var row=cells.get(k);
            if (row==null || !"FOUND".equals(row.result()) || !row.sourceId().equals(item.sourceId())
                    || !row.sourceEdition().equals(item.sourceEdition()))
                throw invalid("Emissão sem consulta positiva correspondente na mesma edição da fonte.");
            matched.add(k);
        }
        for (var entry:cells.entrySet()) {
            if ("FOUND".equals(entry.getValue().result()) != matched.contains(entry.getKey()))
                throw invalid("Resultado positivo exige emissão individualizada; resultado negativo não pode conter emissão.");
        }
        if (issuances.isEmpty() && (values.erc().signum()!=0 || values.erf().signum()!=0))
            throw invalid("Totais positivos exigem emissões individualizadas.");
    }

    static void store(Connection c,UUID id,List<Row> rows) throws SQLException {
        int ordinal=0;
        for (var row:rows) update(c,"""
            INSERT INTO app_open_dy_search_coverage
              (assessment_id,ordinal,program,credit_kind,territory,result,searched_from,
               searched_until_exclusive,searched_on,source_id,source_edition,locator,limitations)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,++ordinal,row.program(),row.creditKind(),row.territory(),row.result(),
            row.searchedFrom(),row.searchedUntilExclusive(),row.searchedOn(),row.sourceId(),
            row.sourceEdition(),row.locator(),row.limitations());
    }

    static List<Row> read(Connection c,UUID id) throws SQLException {
        return query(c,"""
            SELECT program,credit_kind,territory,result,searched_from,searched_until_exclusive,
              searched_on,source_id,source_edition,locator,limitations
            FROM app_open_dy_search_coverage WHERE assessment_id=? ORDER BY ordinal
            """,r->new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
            r.getObject(5,LocalDate.class),r.getObject(6,LocalDate.class),r.getObject(7,LocalDate.class),
            r.getString(8),r.getString(9),r.getString(10),r.getString(11)),id);
    }

    private static Key key(String program,String kind) { return new Key(program.toLowerCase(Locale.ROOT),kind); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
