package br.carbonflow.pilot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import static br.carbonflow.pilot.DocumentRules.*;

final class DossierText {
    record Terms(String methodology, String program, String additionality, int stations, int circuits, int vehicles,
                 int models, LocalDate commitment, LocalDate creditStart, LocalDate creditEnd) {}
    record Section(String key, String heading, String text, List<List<String>> table) {
        Section(String key, String heading, String text) { this(key, heading, text, List.of()); }
    }

    static List<Section> sections(Input input, Terms terms, JsonNode closure, CarbonCalculator.Results forecast, boolean reserved) {
        var sections = new ArrayList<Section>();
        sections.add(new Section("cover", "Project and purpose",
                "PIL-SYN-BR-001 is a synthetic private electric vehicle charging project in Planaltina, Federal District, Brazil. "
                + "This " + input.act().title().toLowerCase(java.util.Locale.ROOT) + " applies the " + input.act().rule()
                + " checklist at the simulated act date " + input.actDate() + "."));
        if (input.act() == Act.VERIFICACAO) monitoring(sections, terms, closure);
        else description(sections, terms, forecast, input.act() == Act.DESENVOLVIMENTO);

        if (reserved && input.privateNote() != null && !input.privateNote().isBlank()) {
            sections.add(new Section("annotation", "Restricted working note", input.privateNote()));
        }
        return List.copyOf(sections);
    }

    private static void description(List<Section> sections, Terms terms, CarbonCalculator.Results forecast, boolean indicative) {
        sections.add(new Section("pd-1.1", "Project design and eligibility",
                "Sections 1.1 to 1.5 and 1.15 to 1.17. The activity is an individual Energy and Industry project. "
                + "The synthetic proponent supports operation and carbon rights with separate evidence. The reviewed overlap search "
                + "covers the chargers and competing credits. The eligibility review also considers regulated obligations, other programs and Scope 3 claims."));
        sections.add(new Section("pd-1.8", "Schedule and charging boundary",
                "Sections 1.8 to 1.13. The boundary contains " + terms.stations() + " fixed DC fast chargers and "
                + terms.vehicles() + " light duty battery electric vehicles across " + terms.models() + " models. "
                + "Electricity comes from the SIN grid and upstream energy use is measured on " + terms.circuits()
                + " dedicated circuits. " + schedule(terms, indicative)));
        sections.add(new Section("pd-2.4", "Stakeholder engagement and grievances",
                "Section 2.4. The scenario records dated engagement and a public route to receive, investigate and resolve grievances. "
                + "The stakeholder plan and ESG assessment record synthetic outreach and mitigations. Access to supporting records follows the evidence classification."));
        sections.add(new Section("pd-3.1", "Method and baseline",
                "Sections 3.1 and 3.2. The scenario applies " + terms.methodology() + " under " + terms.program() + " and "
                + "the " + terms.additionality() + " route. Equivalent light duty combustion vehicles provide the baseline service. "
                + "Regulatory surplus and the positive list are evaluated separately. The reference uses EF-SYN-v1, 0.30 tCO₂/MWh."));
        if (indicative) {
            var projected = forecast.periods().stream().map(p -> p.id() + " " + p.emissions().erDisplay() + " tCO₂e").toList();
            sections.add(new Section("pd-1.10", "Indicative reductions",
                    "Section 1.10. The synthetic estimates for the first two periods are " + String.join(" and ", projected)
                    + ". The estimates are projections at this act date."));
        } else {
            sections.add(new Section("pd-1.14", "Location and safeguards",
                    "Sections 1.14, 1.18 to 1.19 and 2.1 to 2.5. The synthetic map shows two illustrative charger positions. "
                    + "The assessment covers access, environmental and safety risks, complaints and corrective measures. "
                    + "SDG 7, 11 and 13 indicators track electricity use, closed fleet charging and estimated emission reductions in this simulation."));
            sections.add(new Section("pd-3.3", "Boundary and additionality",
                    "Sections 3.3 to 3.6. The project tracks delivered energy to eligible vehicles as ED and metered upstream consumption as EC. "
                    + "The synthetic segregated circuit and utility records support the electrical boundary. No competing credit was found in the reviewed scenario. "
                    + "There are no modeled removals."));
            sections.add(new Section("pd-4", "Projected emission reductions",
                    "Sections 4.1 to 4.5. The estimates use the equivalent fossil fleet and fixed ex ante electricity factor. "
                    + "Leakage is zero under the methodology. Estimates are grouped by the local monitoring and calendar cuts.", estimates(forecast)));
            sections.add(new Section("pd-5", "Monitoring plan and appendices",
                    "Sections 5.1 and 5.2 and Appendix 2. Inventory, authorization, calibration, meter changes and reading provenance are retained. "
                    + "The review checks completeness, units, source, interval, duplicates and version history before period closure. "
                    + "A qualified reviewer confirms treatment of missing data and changes. Restricted evidence accompanies the reserved package."));
        }
    }

    private static void monitoring(List<Section> sections, Terms terms, JsonNode closure) {
        var memory = closure.path("memory");
        sections.add(new Section("mr-1", "Monitoring period and boundary",
                "Sections 1.1 to 1.13. The frozen monitoring period is " + closure.path("period").asText() + ", revision "
                + closure.path("revision").asText() + ", from " + closure.at("/window/start").asText() + " through "
                + inclusive(closure.at("/window/endExclusive").asText()) + ", inclusive in America/Sao_Paulo. "
                + schedule(terms, false) + " The boundary has " + terms.stations() + " charging stations and " + terms.circuits() + " upstream circuits."));
        sections.add(new Section("mr-2", "Safeguards and implementation",
                "Sections 2.1 to 2.6 and 3.1 to 3.5. The pilot links the applicable rights, grievance and ESG monitoring records. "
                + "The activity uses " + terms.methodology() + " and the closed private fleet boundary. Grouped project and loss provisions do not apply to this Energy and Industry scenario."));
        sections.add(new Section("mr-4", "Monitoring data and quality",
                "Sections 4.1 and 4.2 and Appendix 2. ED measures energy delivered to eligible vehicles and EC covers upstream consumption. "
                + "The closure confirms coverage of all expected channels and uses the current admitted selection and reviewed configuration. "
                + "The reserved memory links the precise run, selection and source snapshot."));
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("Vintage and dates inclusive", "BE", "PE", "LE", "ER", "PD estimate"));
        for (var cut : memory.path("cuts")) {
            rows.add(List.of(cut.path("vintage").asText() + "  " + cut.path("start").asText() + " to " + inclusive(cut.path("endExclusive").asText()),
                    cut.at("/emissions/be").asText(), cut.at("/emissions/pe").asText(), cut.at("/emissions/le").asText(),
                    cut.at("/emissions/erDisplay").asText(), cut.at("/comparison/estimate").asText()));
        }
        rows.add(List.of("Total", memory.at("/totals/be").asText(), memory.at("/totals/pe").asText(), memory.at("/totals/le").asText(),
                memory.at("/totals/erDisplay").asText(), memory.at("/comparison/estimate").asText()));
        sections.add(new Section("mr-5", "Achieved emission reductions",
                "Sections 5.1 to 5.5. Values are in tCO₂e. BE is baseline emissions, PE is project emissions, LE is methodological leakage "
                + "and ER is the reduction after project emissions and leakage. The synthetic EF-SYN-v1 is " + memory.path("factorPerMwh").asText()
                + " tCO₂/MWh and Dy is " + memory.path("dy").asText() + ". Modeled removals are zero. The difference against the PD for the period is "
                + memory.at("/comparison/difference").asText() + " tCO₂e.", List.copyOf(rows)));
    }

    private static List<List<String>> estimates(CarbonCalculator.Results forecast) {
        var table = new ArrayList<List<String>>();
        table.add(List.of("Period and dates inclusive", "BE", "PE", "LE", "ER"));
        for (var period : forecast.periods()) {
            for (var cut : period.cuts()) {
                var value = cut.emissions();
                table.add(List.of(period.id() + "  " + cut.start() + " to " + cut.endInclusive(),
                        value.be(), value.pe(), value.le(), value.erDisplay()));
            }
        }
        return List.copyOf(table);
    }

    private static String schedule(Terms terms, boolean indicative) {
        if (indicative) {
            String prior = terms.commitment() == null ? "The commitment date is pending." :
                    "The commitment was recorded on " + terms.commitment() + ".";
            return prior + " For development, the projected crediting start is October 2027 and remains subject to commissioning.";
        }
        if (terms.creditStart() == null) return "The operational date remains subject to commissioning.";
        return "The commitment was recorded on " + terms.commitment() + " and crediting runs from "
                + terms.creditStart() + " through " + terms.creditEnd() + ".";
    }

    private static String inclusive(String exclusive) { return LocalDate.parse(exclusive).minusDays(1).toString(); }

    static List<Section> stakeholderPlan(boolean indicative) {
        return List.of(
                new Section("purpose", "Purpose and stage", (indicative ? "Draft for development. " : "Plan for the synthetic act. ")
                        + "The pilot considers charging site staff, fleet operators and potentially affected neighbours."),
                new Section("engagement", "Engagement and follow up",
                        "The preparation team records outreach dates, issues raised and actions taken. The operator reviews safety and access matters. "
                        + "A separate tracker holds supporting records and identifies contacts only in the reserved view."),
                new Section("grievances", "Grievance mechanism",
                        "Affected people can raise access, safety or environmental concerns. The scenario team logs the date, assigns an owner, follows up with the person "
                        + "raising the concern and preserves the outcome and route for reconsideration. Grievance handling is distinct from public consultation."));
    }

    static List<Section> esgAssessment(boolean indicative) {
        return List.of(
                new Section("purpose", "Purpose and stage", (indicative ? "Draft for development. " : "Assessment for the synthetic act. ")
                        + "The pilot exercises safeguards for a fixed charging site and a closed light duty fleet."),
                new Section("risk", "Risk and controls",
                        "The operator reviews electrical safety, access controls and changes to the grid and circuit boundary. "
                        + "The preparation team checks legal obligations, engagement and site conditions through separate records."),
                new Section("follow-up", "Monitoring and responsibility",
                        "The operator reviews incidents and mitigation during each monitoring period. PREP links the records to the period and REV checks coverage "
                        + "and confidentiality. Changes affecting the project design are carried into the next review."));
    }

    static List<Section> compare(List<Section> previous, List<Section> current) {
        Map<String, Section> old = new LinkedHashMap<>();
        previous.forEach(item -> old.put(item.key(), item));
        var changes = new ArrayList<Section>();
        for (var section : current) {
            var prior = old.remove(section.key());
            if (prior == null) {
                changes.add(new Section(section.key(), "Added " + section.heading(), section.text(), section.table()));
            } else if (!prior.equals(section)) {
                changes.add(new Section(prior.key(), "Previous " + prior.heading(), prior.text(), prior.table()));
                changes.add(new Section(section.key(), "Current " + section.heading(), section.text(), section.table()));
            }
        }
        for (var prior : old.values()) changes.add(new Section(prior.key(), "Removed " + prior.heading(), prior.text(), prior.table()));
        return changes.isEmpty() ? List.of(new Section("no-change", "Changes by section", "No content changes in this exposure.")) : List.copyOf(changes);
    }
}
