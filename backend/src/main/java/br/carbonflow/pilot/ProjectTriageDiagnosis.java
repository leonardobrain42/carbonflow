package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Preliminary comparison of declared answers with the supported product scope. */
final class ProjectTriageDiagnosis {
    enum Classification { SUPPORTED_MVP_CONFIGURATION, UNSUPPORTED_MVP, UNKNOWN_REVIEW_REQUIRED }
    enum FindingKind { ANSWER, PILOT_SCOPE, TECHNICAL }
    record Finding(String code, FindingKind kind, String title, String detail, int step, List<String> fields) {}
    record Result(String ruleVersion, Classification classification, String reason, List<Finding> findings) {}

    private static final String RULE_VERSION = "REG-E2 / CF-IMP-010 v3";

    private ProjectTriageDiagnosis() {}

    static Result evaluate(JsonNode answers, ProjectJourney.Guidance guidance) {
        String candidate = ProjectJourney.candidate(answers);
        var findings = new ArrayList<Finding>();
        if (ProjectJourney.profile(answers) != ProjectJourney.VM0038) {
            finding(findings, "METHODOLOGY", "Defina a metodologia candidata",
                    "Confira qual metodologia corresponde à atividade antes de preparar a análise.", 1, "methodologyCandidate");
            return new Result(RULE_VERSION, candidate == null || candidate.isBlank()
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
        boolean activityMissing = !ProjectJourney.known(plan.path("activityType"));
        boolean installationMissing = !ProjectJourney.known(activity.path("installsChargers"));
        if (activityMissing) {
            finding(findings, "ACTIVITY_MISSING", "Informe a atividade do projeto",
                    "Indique qual atividade descreve melhor o projeto.", 1, "activityType");
        } else if (anotherActivity) {
            finding(findings, "ACTIVITY", "Confira a atividade do projeto",
                    "A configuração disponível cobre projetos de instalação de recarga para veículos elétricos.", 1, "activityType");
        }
        if (installationMissing) {
            finding(findings, "INSTALLATION_MISSING", "Informe se há instalação de carregadores",
                    "Indique se a atividade prevê instalar sistemas de recarga.", 2, "installsChargers");
        } else if (noCharging) {
            finding(findings, "INSTALLATION", "Confira a instalação de carregadores",
                    "O projeto declarou que a atividade não inclui instalação de sistemas de recarga.", 2, "installsChargers");
        }

        // D16: the triage asks only for the initial scope; answers that left it must be confirmed on the technical
        // screens (or come from an older revision) before the scope can be called compatible. Nothing is presumed.
        var profileGaps = new ArrayList<String>();
        var technicalGaps = new ArrayList<String>();
        for (var question : ProjectJourney.VM0038.questions()) {
            JsonNode answer = profile.path(question.key());
            if (!ProjectJourney.known(answer) || ("choice".equals(question.type())
                    && question.options().stream().noneMatch(option -> option.value().equals(answer.asText())))) {
                (ProjectJourney.retired(3, question.key()) ? technicalGaps : profileGaps).add(question.key());
            }
        }
        if (!profileGaps.isEmpty()) {
            findings.add(new Finding("CONFIGURATION", FindingKind.ANSWER, "Complete o recorte inicial",
                    "Confirme a classe da frota, a propulsão, o tipo de recarga e o desenho do projeto.", 3, List.copyOf(profileGaps)));
        }
        if (!technicalGaps.isEmpty()) {
            findings.add(new Finding("TECHNICAL_SCOPE_TO_CONFIRM", FindingKind.TECHNICAL, "Confirme a configuração na preparação técnica",
                    "Acesso, identificação da frota, energia e infraestrutura associada são declarados em Acesso à rede; "
                            + "medição e quantidades, em Estações e fronteira e em Monitoramento.", 0, List.copyOf(technicalGaps)));
        }
        for (var limit : guidance.pilotLimits()) {
            findings.add(new Finding("OUTSIDE_MVP_" + limit.field(), FindingKind.PILOT_SCOPE, null,
                    limit.message(), limit.step(), List.of(limit.field())));
        }

        List<String> locationGaps = missing(context, "country", "state", "locality");
        if (!locationGaps.isEmpty()) {
            findings.add(new Finding("LOCATION", FindingKind.ANSWER, "Confirme a localização",
                    "Identifique país, estado e município da infraestrutura.", 4, List.copyOf(locationGaps)));
        }
        if (!ProjectJourney.known(context.path("referenceDate"))) {
            finding(findings, "REFERENCE_DATE", "Informe a data de referência pretendida",
                    "Registre a data que você pretende usar como ponto de partida para a análise.", 4, "referenceDate");
        }

        // D16: investment, rights and evidence are no longer asked here. An older answer still informs the
        // technical findings below; its absence is not a triage gap.
        String commitment = context.path("investmentCommitted").asText();
        String prior = context.path("carbonConsideredBeforeInvestment").asText();

        technical(findings, "BASELINE", "Elabore o cenário de linha de base",
                "Descreva como as viagens ocorreriam sem o projeto e identifique veículos comparáveis e fontes de dados.");
        technical(findings, "DATE", "Documente a cronologia do projeto",
                "Reúna os marcos e documentos do projeto para determinar as datas aplicáveis.");
        if ("YES".equals(commitment) && "NO".equals(prior)) {
            technical(findings, "COMMITTED_INVESTMENT", "Revise a cronologia do investimento na análise de adicionalidade",
                    "Você informou que o investimento foi comprometido antes de considerar a receita de carbono. Reúna contratos e datas na Adicionalidade para revisão técnica.");
        } else {
            technical(findings, "ADDITIONALITY", "Prepare a análise de adicionalidade",
                    "Registre na Adicionalidade a cronologia do investimento e da receita de carbono, com evidências, para a revisão técnica avaliar a rota aplicável.");
        }
        technical(findings, "RIGHTS", "Comprove a titularidade das reduções",
                "Em Direitos do projeto, indique os titulares e os documentos que permitam conferir eventual sobreposição de direitos.");
        technical(findings, "EVIDENCE", "Organize as fontes e evidências",
                "Identifique os documentos e as fontes que sustentam as informações declaradas.");

        var classification = noCharging || anotherActivity || !guidance.pilotLimits().isEmpty()
                ? Classification.UNSUPPORTED_MVP
                : !activityMissing && !installationMissing && profileGaps.isEmpty() && technicalGaps.isEmpty()
                        && locationGaps.isEmpty()
                        ? Classification.SUPPORTED_MVP_CONFIGURATION
                        : Classification.UNKNOWN_REVIEW_REQUIRED;
        String reason = switch (classification) {
            case SUPPORTED_MVP_CONFIGURATION -> "A configuração técnica declarada coincide com o recorte do piloto. A avaliação metodológica requer revisão e evidências.";
            case UNSUPPORTED_MVP -> "Há características declaradas fora do recorte implementado. Consulte os motivos e encaminhe o projeto para avaliação técnica.";
            case UNKNOWN_REVIEW_REQUIRED -> "Ainda faltam dados para comparar a configuração técnica com o recorte do piloto.";
        };
        return new Result(RULE_VERSION, classification, reason, List.copyOf(findings));
    }

    private static List<String> missing(JsonNode values, String... fields) {
        var result = new ArrayList<String>();
        for (String field : fields) {
            if (!ProjectJourney.known(values.path(field))) result.add(field);
        }
        return result;
    }

    private static void finding(List<Finding> findings, String code, String title, String detail, int step, String... fields) {
        findings.add(new Finding(code, FindingKind.ANSWER, title, detail, step, List.of(fields)));
    }

    private static void technical(List<Finding> findings, String code, String title, String detail) {
        findings.add(new Finding(code, FindingKind.TECHNICAL, title, detail, 0, List.of()));
    }
}
