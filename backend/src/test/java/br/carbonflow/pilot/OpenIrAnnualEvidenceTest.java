package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenIrAnnualEvidenceTest {
    private static OpenIrAnnualEvidence.Snapshot snapshot(String project,int year,int source,
            int number,boolean current,boolean annual,String cycle) {
        return new OpenIrAnnualEvidence.Snapshot(id(number),project,year,source,
            String.format("%064x",number),id(number+10),id(number+20),id(number+30),cycle,
            OffsetDateTime.parse("2026-01-01T00:00:00Z").plusDays(number),current,annual);
    }
    private static UUID id(int n) { return new UUID(0,n); }

    @Test
    void capturesTwoDistinctConsecutiveAnnualSetsWithoutRequiringNumericalChange() {
        var prior=snapshot("A",1,2025,1,true,true,"COMBINED");
        var current=snapshot("A",2,2026,2,true,true,"COMBINED");
        var proof=OpenIrAnnualEvidence.capture(prior,current);
        assertEquals(prior,proof.previous());
        assertEquals(current,proof.current());
        assertEquals(proof.digest(),OpenIrAnnualEvidence.capture(prior,current).digest());
        assertNotEquals(proof.digest(),OpenIrAnnualEvidence.capture(prior,
            snapshot("A",2,2026,3,true,true,"COMBINED")).digest());
    }

    @Test
    void rejectsMissingWrongScopeGapsStaleAndMethodMismatch() {
        var prior=snapshot("A",1,2025,1,true,true,"COMBINED");
        var current=snapshot("A",2,2026,2,true,true,"COMBINED");
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(null,current));
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(prior,prior));
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(prior,
            snapshot("B",2,2026,2,true,true,"COMBINED")));
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(prior,
            snapshot("A",3,2026,2,true,true,"COMBINED")));
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(prior,
            snapshot("A",2,2027,2,true,true,"COMBINED")));
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(prior,
            snapshot("A",2,2026,2,false,true,"COMBINED")));
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(prior,
            snapshot("A",2,2026,2,true,false,"COMBINED")));
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(prior,
            snapshot("A",2,2026,2,true,true,"URBAN")));
    }

    @Test
    void rejectsReusedUnderlyingCandidateAndOutOfOrderAssessment() {
        var prior=snapshot("A",1,2025,1,true,true,"COMBINED");
        var current=snapshot("A",2,2026,2,true,true,"COMBINED");
        var reused=new OpenIrAnnualEvidence.Snapshot(current.id(),current.project(),2,2026,
            current.assessmentSha(),current.fleetSnapshot(),current.ratingSnapshot(),
            prior.fuelCandidate(),current.cycle(),current.recordedAt(),true,true);
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(prior,reused));
        var reverse=new OpenIrAnnualEvidence.Snapshot(current.id(),current.project(),2,2026,
            current.assessmentSha(),current.fleetSnapshot(),current.ratingSnapshot(),
            current.fuelCandidate(),current.cycle(),prior.recordedAt().minusDays(1),true,true);
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualEvidence.capture(prior,reverse));
    }
}
