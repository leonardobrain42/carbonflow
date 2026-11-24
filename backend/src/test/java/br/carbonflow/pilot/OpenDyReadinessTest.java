package br.carbonflow.pilot;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class OpenDyReadinessTest {
    private static final UUID ID=UUID.fromString("00000000-0000-4000-8000-000000000096");
    private static OpenDyReadiness.Facts facts(boolean stale,boolean conclusive,boolean arithmetic,boolean positive,
            int count,String coverage,String allocation,String rights,String review,boolean exact) {
        return new OpenDyReadiness.Facts(ID,7,"a".repeat(64),stale,conclusive,arithmetic,positive,count,
            coverage,allocation,rights,review,exact);
    }
    private static String status(OpenDyReadiness.Report report,String code) {
        return report.gates().stream().filter(g->g.code().equals(code)).findFirst().orElseThrow().status();
    }
    @Test void allInternalChecksStillCannotAdmitE8() {
        var report=OpenDyReadiness.evaluate(2,null,facts(false,true,true,true,2,"DECLARED_MATRIX_COMPLETE",
            "DECLARED_ALLOCATION_RECONCILED","LICENSE_DECISIONS_CURRENT","CHECKED_INTERNAL",true));
        assertTrue(report.internalChecksComplete());
        assertFalse(report.usableForE8());
        assertEquals("BLOCKED",report.state());
        assertEquals(ID,report.assessmentId());
        assertEquals(7,report.assessmentVersion());
        for (String code:java.util.List.of("NORMATIVE_WINDOW","EXTERNAL_EVIDENCE","E8_ADMISSION"))
            assertEquals("BLOCKED",status(report,code));
    }
    @Test void missingStaleRevokedAndFirstYearRemainBlocked() {
        var empty=OpenDyReadiness.evaluate(2,"CREDIT_START_MISSING",null);
        assertEquals("BLOCKED",status(empty,"ASSESSMENT"));
        assertEquals("NOT_EVALUATED",status(empty,"ARITHMETIC"));
        assertNull(empty.assessmentId());
        var revoked=OpenDyReadiness.evaluate(2,null,facts(true,true,false,false,2,"SEARCH_RIGHTS_RECHECK_REQUIRED",
            "ALLOCATION_RIGHTS_RECHECK_REQUIRED","LICENSE_RECHECK_REQUIRED","CHECKED_INTERNAL",true));
        assertFalse(revoked.internalChecksComplete());
        assertEquals("BLOCKED",status(revoked,"SOURCE_RIGHTS"));
        assertEquals("NOT_EVALUATED",status(revoked,"ARITHMETIC"));
        assertEquals("NOT_EVALUATED",status(revoked,"INDEPENDENT_REVIEW"));
        var first=OpenDyReadiness.evaluate(1,"FIRST_YEAR_REVIEW_REQUIRED",facts(false,false,false,false,0,
            "SEARCH_INCONCLUSIVE","NO_STRUCTURED_ALLOCATION","LICENSE_DECISIONS_CURRENT",null,false));
        assertEquals("BLOCKED",status(first,"CALENDAR"));
        assertEquals("BLOCKED",status(first,"ARITHMETIC"));
    }
    @Test void zeroWithoutMatrixAndMismatchedReviewCannotPass() {
        var legacy=OpenDyReadiness.evaluate(2,null,facts(false,true,true,false,0,"NO_SEARCH_MATRIX",
            "NO_STRUCTURED_ALLOCATION","NO_LICENSE_PIN","CHECKED_INTERNAL",false));
        assertEquals("BLOCKED",status(legacy,"ISSUANCE_INVENTORY"));
        assertEquals("BLOCKED",status(legacy,"INDEPENDENT_REVIEW"));
        var zero=OpenDyReadiness.evaluate(2,null,facts(false,true,true,false,0,"DECLARED_MATRIX_COMPLETE",
            "NO_STRUCTURED_ALLOCATION","LICENSE_DECISIONS_CURRENT","CHECKED_INTERNAL",true));
        assertEquals("NOT_APPLICABLE",status(zero,"GEOGRAPHIC_ALLOCATION"));
        assertTrue(zero.internalChecksComplete());
        assertFalse(zero.usableForE8());
    }
}
