package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Preliminary comparison of declared answers with the supported product scope. */
final class ProjectTriageDiagnosis {
    enum Classification { SUPPORTED_MVP_CONFIGURATION, UNSUPPORTED_MVP, UNKNOWN_REVIEW_REQUIRED }
    record Finding(String code, String title, String detail, int step, List<String> fields) {}
    record Result(String ruleVersion, Classification classification, String reason, List<Finding> findings) {}

    private ProjectTriageDiagnosis() {}

    static Result evaluate(JsonNode answers, ProjectJourney.Guidance guidance) {
        String candidate = ProjectJourney.candidate(answers);
        var findings = new ArrayList<Finding>();
        if (ProjectJourney.profile(answers) != ProjectJourney.VM0038) {
            finding(findings, "METHODOLOGY", "Defina a metodologia candidata",
                    "Confira qual metodologia corresponde à atividade antes de preparar a análise.", 1, "methodologyCandidate");
            return new Result("REG-E2 / CF-IMP-010 v1", candidate == null || candidate.isBlank()
                    ? Classification.UNKNOWN_REVIEW_REQUIRED : Classification.UNSUPPORTED_MVP,
                    candidate == null || candidate.isBlank()
                            ? "A metodologia candidata ainda precisa ser definida."
                            : "A metodologia indicada ainda está fora das configurações disponíveis no CarbonFlow.",
                    List.copyOf(findings));
        }

        JsonNode plan = answers.path("1");
        JsonNode activity = answers.path("2");
        JsonNode profile = answers.path("3");
        JsonNode context = answers.path("4");

        boolean noCharging = "NO".equals(activity.path("installsChargers").asText());
        boolean anotherActivity = ProjectJourney.known(plan.path("activityType"))
                && !"MOBILIDADE_ELETRICA".equals(plan.path("activityType").asText());
        List<String> activityGaps = missing(plan, "activityType");
        activityGaps.addAll(missing(activity, "installsChargers"));
        if (anotherActivity) {
            finding(findings, "ACTIVITY", "Confira a atividade do projeto",
                    "A configuração disponível cobre projetos de instalação de recarga para veículos elétricos.", 1, "activityType");
        }
        if (noCharging) {
            finding(findings, "INSTALLATION", "Confira a instalação de carregadores",
                    "O projeto declarou que a atividade não inclui instalação de sistemas de recarga.", 2, "installsChargers");
        }
        if (!activityGaps.isEmpty()) {
            findings.add(new Finding("ACTIVITY_GAPS", "Confirme a atividade e a instalação",
                    "Informe se a atividade é de mobilidade elétrica e se o projeto inclui instalação de recarga.",
                    activityGaps.contains("activityType") ? 1 : 2, List.copyOf(activityGaps)));
        }

        var profileGaps = new ArrayList<String>();
        for (var question : ProjectJourney.VM0038.questions()) {
            JsonNode answer = profile.path(question.key());
            if (!ProjectJourney.known(answer) || ("choice".equals(question.type())
                    && question.options().stream().noneMatch(option -> option.value().equals(answer.asText())))) {
                profileGaps.add(question.key());
            }
        }
        if (!profileGaps.isEmpty()) {
            findings.add(new Finding("CONFIGURATION", "Complete a configuração técnica",
                    "Confirme os dados da frota e da infraestrutura que será usada para recarga.", 3, List.copyOf(profileGaps)));
        }
        if (!guidance.outsidePilot().isEmpty()) {
            findings.add(new Finding("OUTSIDE_MVP", "Revise os limites da configuração do CarbonFlow",
                    String.join(" ", guidance.outsidePilot()), 0, List.of()));
        }

        List<String> locationGaps = missing(context, "country", "state", "locality");
        if (!locationGaps.isEmpty()) {
            findings.add(new Finding("LOCATION", "Confirme a localização",
                    "Identifique país, estado e município da infraestrutura.", 4, List.copyOf(locationGaps)));
        }
        finding(findings, "DATE", "Documente a cronologia do projeto",
                ProjectJourney.known(context.path("referenceDate"))
                        ? "A data é declarada. Reúna os marcos e documentos para determinar as datas aplicáveis."
                        : "Informe a data de referência e reúna documentos dos marcos do projeto.", 4, "referenceDate");

        String commitment = context.path("investmentCommitted").asText();
        String prior = context.path("carbonConsideredBeforeInvestment").asText();
        if ("YES".equals(commitment) && "NO".equals(prior)) {
            finding(findings, "COMMITTED_INVESTMENT", "Encaminhe o compromisso de investimento para revisão",
                    "Houve compromisso de investimento antes de considerar a receita de carbono. Reúna contratos, datas e provas para análise de adicionalidade.",
                    4, "investmentCommitted", "carbonConsideredBeforeInvestment");
        } else {
            boolean missingChronology = !ProjectJourney.known(context.path("investmentCommitted"))
                    || ("YES".equals(commitment) && !ProjectJourney.known(context.path("carbonConsideredBeforeInvestment")));
            finding(findings, "ADDITIONALITY", "Prepare a análise de adicionalidade",
                    missingChronology ? "Confirme se houve compromisso de investimento e quando a receita de carbono foi considerada."
                            : "Leve a cronologia e as evidências à revisão técnica para avaliar a rota de adicionalidade aplicável.",
                    4, "investmentCommitted", "carbonConsideredBeforeInvestment");
        }
        finding(findings, "RIGHTS", "Comprove a titularidade das reduções",
                "Reúna contratos e documentos que identifiquem os titulares e permitam conferir eventual sobreposição de direitos.",
                4, "rightsStatus");
        finding(findings, "EVIDENCE", "Organize as fontes e evidências",
                "Identifique os documentos e as fontes que sustentam as informações declaradas.", 4, "evidenceStatus");

        var classification = noCharging || anotherActivity || !guidance.outsidePilot().isEmpty()
                ? Classification.UNSUPPORTED_MVP
                : activityGaps.isEmpty() && profileGaps.isEmpty() && locationGaps.isEmpty()
                        ? Classification.SUPPORTED_MVP_CONFIGURATION
                        : Classification.UNKNOWN_REVIEW_REQUIRED;
        String reason = switch (classification) {
            case SUPPORTED_MVP_CONFIGURATION -> "A configuração técnica declarada coincide com o recorte do piloto. A avaliação metodológica requer revisão e evidências.";
            case UNSUPPORTED_MVP -> "Há características declaradas fora do recorte implementado. Consulte os motivos e encaminhe o projeto para avaliação técnica.";
            case UNKNOWN_REVIEW_REQUIRED -> "Ainda faltam dados para comparar a configuração técnica com o recorte do piloto.";
        };
        return new Result("REG-E2 / CF-IMP-010 v1", classification, reason, List.copyOf(findings));
    }

    private static List<String> missing(JsonNode values, String... fields) {
        var result = new ArrayList<String>();
        for (String field : fields) {
            if (!ProjectJourney.known(values.path(field))) result.add(field);
        }
        return result;
    }

    private static void finding(List<Finding> findings, String code, String title, String detail, int step, String... fields) {
        findings.add(new Finding(code, title, detail, step, List.of(fields)));
    }
}
