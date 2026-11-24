package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

final class OpenDyIssuances {
    record Input(String program,String registryProject,String issuanceId,String creditKind,
                 LocalDate issuedOn,String geography,String creditsTco2e,
                 String sourceId,String sourceEdition,String locator) {}
    record Row(String program,String registryProject,String issuanceId,String creditKind,
               LocalDate issuedOn,String geography,String creditsTco2e,
               String sourceId,String sourceEdition,String locator) {}
    private record Identity(String program,String project,String issuance) {}
    private OpenDyIssuances() {}

    static List<Row> normalize(List<Input> supplied) {
        if (supplied==null || supplied.isEmpty()) return List.of();
        if (supplied.size()>100) throw new IllegalArgumentException("Máximo de 100 registros por avaliação.");
        var rows=new ArrayList<Row>();
        var seen=new HashSet<Identity>();
        for (Input item:supplied) {
            if (item==null || item.issuedOn()==null || !"BR".equals(item.geography())
                    || (!"ERC".equals(item.creditKind()) && !"ERF".equals(item.creditKind())))
                throw new IllegalArgumentException("Informe data de emissão, recorte BR e categoria ERC ou ERF.");
            var program=OpenDyAssessmentRules.text(item.program(),1,120);
            var project=OpenDyAssessmentRules.text(item.registryProject(),1,160);
            var issuance=OpenDyAssessmentRules.text(item.issuanceId(),1,160);
            var key=new Identity(program.toLowerCase(Locale.ROOT),project.toLowerCase(Locale.ROOT),
                issuance.toLowerCase(Locale.ROOT));
            if (!seen.add(key)) throw new IllegalArgumentException("Emissão duplicada na pesquisa.");
            var amount=OpenDyAssessmentRules.decimal(item.creditsTco2e());
            if (amount.signum()<=0) throw new IllegalArgumentException("Cada emissão deve ter quantidade positiva.");
            rows.add(new Row(program,project,issuance,item.creditKind(),item.issuedOn(),"BR",
                OpenDyAssessmentRules.number(amount),
                OpenDyAssessmentRules.text(item.sourceId(),1,160),
                OpenDyAssessmentRules.text(item.sourceEdition(),1,160),
                OpenDyAssessmentRules.text(item.locator(),3,1000)));
        }
        rows.sort(Comparator.comparing(Row::program).thenComparing(Row::registryProject)
            .thenComparing(Row::issuanceId));
        return List.copyOf(rows);
    }

    static void reconcile(List<Row> rows,OpenDyAssessmentRules.Values values,
                          OffsetDateTime from,OffsetDateTime until) {
        if (rows.isEmpty()) return; // legacy totals remain explicitly unitemized
        if (from==null || until==null || values.erc()==null || values.erf()==null)
            throw new IllegalArgumentException("Itens exigem janela anterior e totais declarados.");
        BigDecimal erc=BigDecimal.ZERO,erf=BigDecimal.ZERO;
        for (Row row:rows) {
            LocalDate date=row.issuedOn();
            LocalDate start=from.atZoneSameInstant(VintageWindows.ZONE).toLocalDate();
            LocalDate end=until.atZoneSameInstant(VintageWindows.ZONE).toLocalDate();
            if (date.isBefore(start) || !date.isBefore(end))
                throw new IllegalArgumentException("Emissão fora da janela do ano anterior.");
            var amount=new BigDecimal(row.creditsTco2e());
            if ("ERC".equals(row.creditKind())) erc=erc.add(amount);
            else erf=erf.add(amount);
        }
        if (erc.compareTo(values.erc())!=0 || erf.compareTo(values.erf())!=0)
            throw new IllegalArgumentException("Somatórios dos itens não coincidem com ERC e ERF declarados.");
    }
}
