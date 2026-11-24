package br.carbonflow.pilot;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Conservative preparatory continuity check, not evidence of normative timeliness. */
final class OpenIrAnnualEvidence {
    static final String POLICY="OPEN-IR-ANNUAL-EVIDENCE@1";

    record Snapshot(UUID id,String project,int projectYear,int sourceYear,String assessmentSha,
                    UUID fleetSnapshot,UUID ratingSnapshot,UUID fuelCandidate,
                    String cycle,OffsetDateTime recordedAt,boolean current,boolean annualSpecific) {}
    record Proof(Snapshot previous,Snapshot current,String digest) {}

    private OpenIrAnnualEvidence() {}

    static Proof capture(Snapshot previous,Snapshot current) {
        if(previous==null || current==null || !previous.current() || !current.current())
            throw new IllegalArgumentException("As duas avaliações devem ter referências vigentes.");
        if(previous.id()==null || current.id()==null || previous.id().equals(current.id()) ||
           previous.project()==null || !previous.project().equals(current.project()) ||
           current.projectYear()!=previous.projectYear()+1 || current.sourceYear()!=previous.sourceYear()+1)
            throw new IllegalArgumentException("Selecione avaliação do mesmo projeto e de anos consecutivos.");
        if(!previous.annualSpecific() || !current.annualSpecific())
            throw new IllegalArgumentException("Ambas as avaliações devem usar a rota anual específica.");
        if(previous.recordedAt()==null || current.recordedAt()==null ||
            !current.recordedAt().isAfter(previous.recordedAt()))
            throw new IllegalArgumentException("A avaliação anterior deve anteceder a atual.");
        if(!distinct(previous.fleetSnapshot(),current.fleetSnapshot()) ||
           !distinct(previous.ratingSnapshot(),current.ratingSnapshot()) ||
           !distinct(previous.fuelCandidate(),current.fuelCandidate()))
            throw new IllegalArgumentException("Exija snapshots de frota/ratings e candidato fóssil de cada ano, não apenas novos vínculos.");
        if(previous.cycle()==null || !previous.cycle().equals(current.cycle()))
            throw new IllegalArgumentException("A comparação exige ciclo de ratings consistente.");
        if(!hash(previous.assessmentSha()) || !hash(current.assessmentSha()))
            throw new IllegalArgumentException("Hashes das avaliações indisponíveis.");
        return new Proof(previous,current,OpenIrAssessments.digest(POLICY,current.project(),
            previous.id(),previous.projectYear(),previous.sourceYear(),previous.assessmentSha(),
            previous.fleetSnapshot(),previous.ratingSnapshot(),previous.fuelCandidate(),previous.cycle(),
            previous.recordedAt().toInstant(),current.id(),current.projectYear(),current.sourceYear(),
            current.assessmentSha(),current.fleetSnapshot(),current.ratingSnapshot(),current.fuelCandidate(),
            current.cycle(),current.recordedAt().toInstant()));
    }

    private static boolean distinct(UUID a,UUID b) { return a!=null && b!=null && !a.equals(b); }
    private static boolean hash(String s) { return s!=null && s.matches("[0-9a-f]{64}"); }
}
