package br.carbonflow.pilot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenIrExAntePlansTest {
    @Test void scopeCanBePendingButNotExplicitlyOutside() {
        String candidate="OPEN_LDV_DCFC_SIN_BR_CANDIDATE";
        assertEquals("SCOPE_PENDING",OpenIrExAntePlans.classify(true,"BR",null,candidate,"PENDING_CONFIGURATION",false));
        assertEquals("OPEN_CANDIDATE",OpenIrExAntePlans.classify(true,"BR","LDV",candidate,"OPEN_CANDIDATE",false));
        assertEquals("SCOPE_PENDING",OpenIrExAntePlans.classify(true,"BR","LDV",candidate,"OPEN_CANDIDATE",true));
        assertEquals("BRANCH_OUTSIDE_SCOPE",OpenIrExAntePlans.classify(true,"BR","HDV",candidate,"OPEN_CANDIDATE",false));
        assertEquals("BRANCH_OUTSIDE_SCOPE",OpenIrExAntePlans.classify(true,"BR","LDV",candidate,"CLOSED_RESTRICTION",false));
        assertEquals("BRANCH_OUTSIDE_SCOPE",OpenIrExAntePlans.classify(true,"US","LDV",candidate,"OPEN_CANDIDATE",false));
        assertEquals("METHOD_OUTSIDE_SCOPE",OpenIrExAntePlans.classify(false,"BR","LDV",candidate,"OPEN_CANDIDATE",false));
    }
}
