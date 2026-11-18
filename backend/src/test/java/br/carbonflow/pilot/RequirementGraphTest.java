package br.carbonflow.pilot;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static br.carbonflow.pilot.RequirementGraph.Status.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementGraphTest {
    @Test
    void recheckFollowsOnlyAffectedEdgesAndCyclesProduceNoResult() {
        var precedent = rule("A","additionality",List.of());
        var dependent = rule("B","quantification",List.of("A"));
        var independent = rule("C","monitoring",List.of());
        var inputs = new RequirementGraph.Inputs(Set.of("A","B","C"),Set.of("additionality"),Set.of());
        assertEquals(List.of(RECHECK_REQUIRED,DATA_RECORDED,RECHECK_REQUIRED),
                RequirementGraph.evaluate(List.of(dependent,independent,precedent),inputs).stream()
                        .map(RequirementGraph.Node::status).toList());
        assertTrue(RequirementGraph.evaluate(List.of(dependent,rule("A","additionality",List.of("B"))),inputs).isEmpty());
    }

    private static RequirementResolver.RuleResult rule(String code, String guide, List<String> parents) {
        var rule = new RequirementResolver.Rule(code,code,guide,"field",null,"",
                new RequirementResolver.Condition(RequirementResolver.ConditionKind.ALWAYS,null,null,null,null),parents);
        return new RequirementResolver.RuleResult(rule,RequirementResolver.Incidence.APPLICABLE,"",null);
    }
}
