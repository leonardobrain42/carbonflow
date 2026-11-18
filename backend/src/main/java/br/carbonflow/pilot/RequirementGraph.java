package br.carbonflow.pilot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RequirementGraph {
    enum Classification { MANDATORY, CONDITIONAL, OPTIONAL, NOT_APPLICABLE, INDETERMINATE }
    enum Status { COLLECT_DATA, DATA_RECORDED, REVIEW_REQUIRED, DEPENDENCY_PENDING, RECHECK_REQUIRED, NOT_APPLICABLE }
    record Node(String code, String title, String guide, String field, Classification classification, Status status,
                String reason, List<String> dependencies, ContentLicensePolicy.SourceRef source, String locator,
                boolean interpretation) {}
    record Inputs(Set<String> recorded, Set<String> stale, Set<String> optional) {}

    private RequirementGraph() {}

    static List<Node> evaluate(List<RequirementResolver.RuleResult> rules, Inputs inputs) {
        var byCode = new HashMap<String, RequirementResolver.RuleResult>();
        var pending = new HashMap<String, Integer>();
        var downstream = new HashMap<String, List<String>>();
        var ready = new ArrayDeque<String>();
        for (var result : rules) {
            var rule = result.rule();
            if (byCode.put(rule.code(), result) != null) return List.of();
            int count = new HashSet<>(rule.dependencies()).size();
            pending.put(rule.code(), count);
            if (count == 0) ready.add(rule.code());
            for (String parent : new HashSet<>(rule.dependencies())) {
                downstream.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(rule.code());
            }
        }

        var evaluated = new HashMap<String, Node>();
        while (!ready.isEmpty()) {
            String code = ready.remove();
            var result = byCode.get(code);
            var parents = result.rule().dependencies().stream().map(evaluated::get).toList();
            evaluated.put(code, evaluateNode(result, inputs, parents));
            for (String child : downstream.getOrDefault(code, List.of())) {
                if (pending.computeIfPresent(child, (key, count) -> count - 1) == 0) ready.add(child);
            }
        }
        if (evaluated.size() != rules.size()) return List.of();
        return rules.stream().map(result -> evaluated.get(result.rule().code())).toList();
    }

    private static Node evaluateNode(RequirementResolver.RuleResult result, Inputs inputs, List<Node> parents) {
        var rule = result.rule();
        boolean optional = inputs.optional().contains(rule.code());
        boolean interpretation = rule.condition().kind() == RequirementResolver.ConditionKind.MANUAL_REVIEW;
        Classification classification = switch (result.incidence()) {
            case NOT_APPLICABLE -> Classification.NOT_APPLICABLE;
            case INDETERMINATE, MANUAL_REVIEW_REQUIRED -> Classification.INDETERMINATE;
            case APPLICABLE -> optional ? Classification.OPTIONAL
                    : rule.condition().kind() == RequirementResolver.ConditionKind.ALWAYS
                    ? Classification.MANDATORY : Classification.CONDITIONAL;
        };
        Status status;
        if (classification == Classification.NOT_APPLICABLE) {
            status = Status.NOT_APPLICABLE;
        } else if (inputs.stale().contains(rule.section())
                || parents.stream().anyMatch(parent -> parent.status() == Status.RECHECK_REQUIRED)) {
            status = Status.RECHECK_REQUIRED;
        } else if (parents.stream().anyMatch(RequirementGraph::blocksDependent)) {
            status = Status.DEPENDENCY_PENDING;
        } else if (interpretation) {
            status = Status.REVIEW_REQUIRED;
        } else if (classification == Classification.INDETERMINATE || !inputs.recorded().contains(rule.code())) {
            status = Status.COLLECT_DATA;
        } else {
            status = Status.DATA_RECORDED;
        }
        String reason = switch (status) {
            case NOT_APPLICABLE -> result.reason();
            case RECHECK_REQUIRED -> "Uma referência ou etapa anterior mudou. Confira os dados da revisão atual.";
            case DEPENDENCY_PENDING -> "Há uma etapa anterior a resolver. A coleta desta seção pode continuar.";
            case REVIEW_REQUIRED -> "Registre a hipótese e encaminhe as referências para avaliação humana.";
            case COLLECT_DATA -> classification == Classification.INDETERMINATE ? result.reason()
                    : "Registre os dados de origem e as referências disponíveis.";
            case DATA_RECORDED -> "Há um dado registrado no rascunho. A comprovação segue pendente.";
        };
        return new Node(rule.code(), rule.title(), rule.section(), rule.field(), classification, status, reason,
                rule.dependencies(), rule.source(), rule.locator(), interpretation);
    }

    private static boolean blocksDependent(Node parent) {
        return parent.classification() != Classification.OPTIONAL
                && parent.status() != Status.NOT_APPLICABLE
                && parent.status() != Status.DATA_RECORDED;
    }
}
