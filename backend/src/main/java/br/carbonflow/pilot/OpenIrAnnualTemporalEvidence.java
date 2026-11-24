package br.carbonflow.pilot;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.UUID;

/** Conservative internal policy. Dates and locators are human-reviewed declarations, not verified documents. */
final class OpenIrAnnualTemporalEvidence {
    static final String POLICY="OPEN-IR-ANNUAL-TEMPORAL@1";
    enum Metric {EVR,AFEC,MPG,EF_FUEL}
    record Input(Metric metric,LocalDate updatedOn,LocalDate validFrom,LocalDate validUntil,String evidenceLocator) {}
    record Window(LocalDate yearFrom,LocalDate yearUntil,LocalDate periodFrom,LocalDate periodUntil,LocalDate reviewedOn) {}
    record Item(Input evidence,String referenceSha256) {}
    record Proof(Window window,List<Item> items,String digest) {}

    private OpenIrAnnualTemporalEvidence() {}

    static Window window(OpenE8Timing.Calendar calendar,OpenE8Timing.Context timing,OffsetDateTime reviewedAt) {
        if(calendar==null||calendar.gate()!=null||calendar.window()==null||timing==null||
            timing.year().gate()!=null||timing.window()==null||reviewedAt==null)
            throw new IllegalArgumentException("Calendário, período e data de parecer são necessários.");
        var w=timing.window();
        return new Window(day(calendar.window().from()),day(calendar.window().until()),day(w.from()),
            w.until().toInstant().minusNanos(1).atZone(VintageWindows.ZONE).toLocalDate().plusDays(1),day(reviewedAt));
    }

    private static LocalDate day(OffsetDateTime value) {
        return value.atZoneSameInstant(VintageWindows.ZONE).toLocalDate();
    }

    static List<Input> normalized(List<Input> submitted) {
        if(submitted==null||submitted.size()!=Metric.values().length)
            throw new IllegalArgumentException("Informe evidência datada para cada um dos quatro parâmetros.");
        var byMetric=new EnumMap<Metric,Input>(Metric.class);
        for(var input:submitted) {
            if(input==null||input.metric()==null||input.updatedOn()==null||input.validFrom()==null||input.validUntil()==null)
                throw new IllegalArgumentException("Informe todas as datas e o parâmetro da evidência.");
            String locator=OpenDyAssessmentRules.text(input.evidenceLocator(),3,2000);
            if(byMetric.put(input.metric(),new Input(input.metric(),input.updatedOn(),input.validFrom(),input.validUntil(),locator))!=null)
                throw new IllegalArgumentException("Parâmetro de evidência repetido.");
        }
        return List.copyOf(byMetric.values());
    }

    static String commandDigest(List<Input> inputs) {
        var parts=new ArrayList<Object>();
        parts.add(POLICY);parts.add("COMMAND");
        for(var input:normalized(inputs)) {
            parts.add(input.metric());parts.add(input.updatedOn());parts.add(input.validFrom());
            parts.add(input.validUntil());parts.add(input.evidenceLocator());
        }
        return OpenIrAssessments.digest(parts.toArray());
    }

    static Proof capture(Window window,OpenIrAnnualEvidence.Snapshot snapshot,List<Input> inputs) {
        if(window==null||snapshot==null||snapshot.projectYear()<2||window.yearFrom()==null||window.yearUntil()==null||
           window.periodFrom()==null||window.periodUntil()==null||window.reviewedOn()==null||
           !window.yearFrom().isBefore(window.yearUntil())||!window.periodFrom().isBefore(window.periodUntil())||
           window.periodFrom().isBefore(window.yearFrom())||window.periodUntil().isAfter(window.yearUntil()))
            throw new IllegalArgumentException("Janela anual ou período incompatível.");
        var items=new ArrayList<Item>();
        var parts=new ArrayList<Object>();
        parts.add(POLICY);parts.add(snapshot.assessmentSha());parts.add(window.yearFrom());parts.add(window.yearUntil());
        parts.add(window.periodFrom());parts.add(window.periodUntil());parts.add(window.reviewedOn());
        for(var input:normalized(inputs)) {
            if(input.updatedOn().isBefore(window.yearFrom())||!input.updatedOn().isBefore(window.yearUntil())||
                input.updatedOn().isAfter(window.reviewedOn()))
                throw new IllegalArgumentException(input.metric()+": atualização deve ocorrer no ano do projeto, sem data futura ao parecer.");
            if(!input.validFrom().isBefore(input.validUntil())||input.validFrom().isAfter(window.periodFrom())||
                input.validUntil().isBefore(window.periodUntil()))
                throw new IllegalArgumentException(input.metric()+": vigência declarada não cobre integralmente o período.");
            UUID reference=switch(input.metric()) {
                case EVR->snapshot.fleetSnapshot();
                case AFEC,MPG->snapshot.ratingSnapshot();
                case EF_FUEL->snapshot.fuelCandidate();
            };
            if(reference==null)throw new IllegalArgumentException("Referência canônica ausente.");
            String refSha=OpenIrAssessments.digest(POLICY,snapshot.assessmentSha(),input.metric(),reference);
            items.add(new Item(input,refSha));parts.add(refSha);parts.add(input.updatedOn());parts.add(input.validFrom());
            parts.add(input.validUntil());parts.add(input.evidenceLocator());
        }
        return new Proof(window,List.copyOf(items),OpenIrAssessments.digest(parts.toArray()));
    }
}
