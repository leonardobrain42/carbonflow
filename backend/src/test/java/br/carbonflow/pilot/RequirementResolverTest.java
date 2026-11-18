package br.carbonflow.pilot;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementResolverTest {
    @Test
    void cycleOrUnresolvedDependencyInvalidatesTheRegime() {
        var root = rule("A", List.of());
        var dependent = rule("B", List.of("A"));
        assertTrue(RequirementResolver.validDependencies(List.of(dependent, root)));
        assertFalse(RequirementResolver.validDependencies(List.of(dependent, rule("A",List.of("B")))));
        assertFalse(RequirementResolver.validDependencies(List.of(dependent)));
    }

    private static RequirementResolver.Rule rule(String code, List<String> dependencies) {
        return new RequirementResolver.Rule(code,code,"monitoring","ed",
                new ContentLicensePolicy.SourceRef("CF-ECA-DEMO","1"),"DEMO-02",
                new RequirementResolver.Condition(RequirementResolver.ConditionKind.ALWAYS,null,null,null,null),
                dependencies);
    }
}
