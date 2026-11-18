package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.ContentLicensePolicy.Operation.REFERENCE_REQUIREMENT;
import static br.carbonflow.pilot.ContentLicensePolicy.Purpose.REQUIREMENT_CATALOG;
import static br.carbonflow.pilot.PilotSql.query;

@ApplicationScoped
class RequirementResolver {
    enum Act { PD, MR }
    enum DateBasis { ACT_DATE, PERIOD_START }
    enum State { RESOLVED_SYNTHETIC, UNKNOWN_REVIEW_REQUIRED, SOURCE_USAGE_BLOCKED }
    enum ConditionKind { ALWAYS, BOOLEAN_EQUALS, QUANTITY_AT_LEAST, MANUAL_REVIEW }
    enum Incidence { APPLICABLE, NOT_APPLICABLE, INDETERMINATE, MANUAL_REVIEW_REQUIRED }
    record Quantity(BigDecimal value, String unit) {}
    record Input(Act act, LocalDate actDate, LocalDate periodStart, Boolean hasEnergyRecords, Quantity meteredEnergy) {}
    record Condition(ConditionKind kind, String field, Boolean expectedBoolean, BigDecimal minimumQuantity, String unit) {}
    record Rule(String code, String title, String section, String field, ContentLicensePolicy.SourceRef source,
                String locator, Condition condition, List<String> dependencies) {}
    record RuleResult(Rule rule, Incidence incidence, String reason, ContentLicensePolicy.Authorization license) {}
    record Resolution(State state, String methodology, String methodologyEdition, String packId, Integer packEdition,
                      Act act, DateBasis dateBasis, LocalDate dateUsed, String reason, List<RuleResult> rules) {}

    private record Pack(String id, int edition) {}
    private record Dependency(String rule, String dependsOn) {}
    private final ContentLicensePolicy licenses;

    RequirementResolver(ContentLicensePolicy licenses) {
        this.licenses = licenses;
    }

    Resolution resolve(Connection connection, DynamicProjectScope.Context scope, Input input) throws SQLException {
        validate(input);
        var method = ProjectJourney.profile(ProjectGuides.candidate(connection, scope.id()));
        DateBasis basis = input.act() == Act.PD ? DateBasis.ACT_DATE : DateBasis.PERIOD_START;
        LocalDate date = basis == DateBasis.ACT_DATE ? input.actDate() : input.periodStart();
        if (method == ProjectJourney.GENERAL || date == null) {
            return unresolved(method, input.act(), basis, date, State.UNKNOWN_REVIEW_REQUIRED,
                    "Confirme a metodologia e a data de referência do ato ou do período.");
        }
        List<Pack> packs = query(connection, """
                SELECT pack.id,pack.edition FROM app_norm_pack pack
                JOIN app_norm_pack_window w ON w.pack_id=pack.id
                WHERE pack.methodology=? AND pack.methodology_edition=? AND pack.state='PUBLISHED_SYNTHETIC'
                    AND w.act=? AND w.date_basis=? AND w.effective_from<=?
                    AND (w.effective_until IS NULL OR w.effective_until>?)
                LIMIT 2
                """, row -> new Pack(row.getString(1),row.getInt(2)),
                method.candidate(),method.edition(),input.act().name(),basis.name(),date,date);
        if (packs.size() != 1) {
            return unresolved(method, input.act(), basis, date, State.UNKNOWN_REVIEW_REQUIRED,
                    "A combinação de edição, ato e data precisa de revisão do catálogo.");
        }
        Pack pack = packs.getFirst();
        List<Rule> rules = rules(connection, pack, input.act());
        if (rules.isEmpty() || !validDependencies(rules)) {
            return unresolved(method, input.act(), basis, date, State.UNKNOWN_REVIEW_REQUIRED,
                    "A estrutura de dependências deste pacote precisa de revisão.");
        }
        List<RuleResult> results = new ArrayList<>();
        for (Rule rule : rules) {
            var license = licenses.authorize(connection, scope, rule.source(), REQUIREMENT_CATALOG, REFERENCE_REQUIREMENT, null);
            if (license == null) {
                return unresolved(method, input.act(), basis, date, State.SOURCE_USAGE_BLOCKED,
                        "Há referências sem autorização vigente para resolução.");
            }
            results.add(evaluate(rule,input,license));
        }
        return new Resolution(State.RESOLVED_SYNTHETIC,method.candidate(),method.edition(),pack.id(),pack.edition(),
                input.act(),basis,date,"Resultado de demonstração. As incidências precisam ser lidas no contexto do exemplo.",
                List.copyOf(results));
    }

    private static Resolution unresolved(ProjectJourney.Profile method, Act act, DateBasis basis, LocalDate date,
                                         State state, String reason) {
        return new Resolution(state,method.candidate(),method.edition(),null,null,act,basis,date,reason,List.of());
    }

    private static List<Rule> rules(Connection connection, Pack pack, Act act) throws SQLException {
        var edges = new HashMap<String,List<String>>();
        for (var edge : query(connection, """
                SELECT rule_code,depends_on FROM app_norm_rule_dependency WHERE pack_id=? ORDER BY depends_on
                """, row -> new Dependency(row.getString(1),row.getString(2)),pack.id())) {
            edges.computeIfAbsent(edge.rule(), ignored -> new ArrayList<>()).add(edge.dependsOn());
        }
        return query(connection, """
                SELECT code,title,section,field,source_id,source_edition,locator,
                    condition_kind,condition_field,expected_boolean,minimum_quantity,unit
                FROM app_norm_rule WHERE pack_id=? AND act=? ORDER BY position,code
                """, row -> new Rule(row.getString(1),row.getString(2),row.getString(3),row.getString(4),
                new ContentLicensePolicy.SourceRef(row.getString(5),row.getString(6)),row.getString(7),
                new Condition(ConditionKind.valueOf(row.getString(8)),row.getString(9),
                        row.getObject(10,Boolean.class),row.getBigDecimal(11),row.getString(12)),
                List.copyOf(edges.getOrDefault(row.getString(1), List.of()))),pack.id(),act.name());
    }

    static boolean validDependencies(List<Rule> rules) {
        var remaining = new HashMap<String, LinkedHashSet<String>>();
        var ready = new ArrayDeque<String>();
        for (Rule rule : rules) {
            var dependencies = new LinkedHashSet<>(rule.dependencies());
            if (remaining.put(rule.code(), dependencies) != null) return false;
            if (dependencies.isEmpty()) ready.add(rule.code());
        }
        while (!ready.isEmpty()) {
            String resolved = ready.remove();
            remaining.remove(resolved);
            remaining.forEach((code, dependencies) -> {
                if (dependencies.remove(resolved) && dependencies.isEmpty()) ready.add(code);
            });
        }
        return remaining.isEmpty();
    }

    private static RuleResult evaluate(Rule rule, Input input, ContentLicensePolicy.Authorization license) {
        Condition condition = rule.condition();
        Boolean applies = switch (condition.kind()) {
            case ALWAYS -> true;
            case MANUAL_REVIEW -> null;
            case BOOLEAN_EQUALS -> input.hasEnergyRecords() == null ? null
                    : input.hasEnergyRecords().equals(condition.expectedBoolean());
            case QUANTITY_AT_LEAST -> input.meteredEnergy() == null ? null
                    : input.meteredEnergy().value().compareTo(condition.minimumQuantity()) >= 0;
        };
        if (condition.kind() == ConditionKind.MANUAL_REVIEW) {
            return new RuleResult(rule,Incidence.MANUAL_REVIEW_REQUIRED,"A hipótese exige parecer humano.",license);
        }
        if (applies == null) {
            return new RuleResult(rule,Incidence.INDETERMINATE,"O dado da condição ainda não foi informado.",license);
        }
        return new RuleResult(rule,applies ? Incidence.APPLICABLE : Incidence.NOT_APPLICABLE,
                applies ? "A condição de incidência foi atendida no exemplo."
                        : "O dado informado não atende à condição de incidência do exemplo.",license);
    }

    private static void validate(Input input) {
        if (input == null || input.act() == null) {
            throw invalid("act","Selecione PD ou MR.");
        }
        Quantity energy = input.meteredEnergy();
        if (energy != null && (energy.value() == null || !"kWh".equals(energy.unit())
                || energy.value().signum() < 0 || energy.value().precision() > 20 || energy.value().scale() > 6
                || (long) energy.value().precision() - energy.value().scale() > 14)) {
            throw invalid("meteredEnergy","Informe a energia em kWh, com valor não negativo e até seis casas decimais.");
        }
    }

    private static PilotProblem invalid(String field, String message) {
        return new PilotProblem(400,"REGIME_INPUT_INVALID",field,"ECA-002",message);
    }
}
