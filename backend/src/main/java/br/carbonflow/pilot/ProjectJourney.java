package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Product journeys keyed by methodology edition. Matching a journey does not assess eligibility. */
final class ProjectJourney {
    record Option(String value, String label) {}
    record Question(String key, String label, String type, String help, List<Option> options) {}
    /** What the technical preparation asks next, in the screens that own it (D16); each package declares its own. */
    record NextSection(String section, String title, String detail) {}
    /** Texts the triage screen shows for a package; the screen holds no per-methodology branch (A03). */
    record Presentation(String activityPlaceholder, String baselinePlaceholder, List<NextSection> next) {
        Presentation { next = List.copyOf(next); }
    }
    /**
     * Triage schema of one package (D17/A03): the questions of the activity step (activityQuestions), the activity
     * types it covers, the scope questions and, when the package has its own rules, an evaluator for the guidance and
     * the diagnosis. A profile without evaluator has no package: it only collects the hypothesis and the gaps.
     */
    record Profile(String id, String candidate, String edition, String title, String activitiesTitle, String detailsTitle,
                   String description, String sourceUrl, List<String> technicalTopics, List<Question> questions,
                   List<Question> activityQuestions, Set<String> activities, Presentation presentation,
                   @JsonIgnore Evaluator evaluator) {
        Profile {
            activityQuestions = List.copyOf(activityQuestions);
            activities = Set.copyOf(activities);
        }
    }
    /** Package-owned triage rules. Compares answers with the package scope; never decides eligibility. */
    interface Evaluator {
        Guidance guidance(JsonNode answers);
        ProjectTriageDiagnosis.Result diagnose(JsonNode answers, Guidance guidance);
    }
    record PilotLimit(int step, String field, String message) {}
    record Guidance(String state, List<String> outsideMethodology, @JsonIgnore List<PilotLimit> pilotLimits) {
        @JsonProperty("outsidePilot")
        List<String> outsidePilot() {
            return pilotLimits.stream().map(PilotLimit::message).toList();
        }
    }

    private static final String UNKNOWN = "DESCONHECIDO";
    private static final Option UNKNOWN_OPTION = new Option(UNKNOWN, "Ainda não sei");
    static final Profile GENERAL = new Profile(
            "GENERAL", null, null, "Triagem inicial", "Atividade", "Escopo e lacunas",
            "Descreva o que o projeto faz e quais informações faltam para escolher uma metodologia.",
            null, List.of("Descrição da atividade e localização", "Cenário sem o projeto", "Dados e direitos a comprovar"),
            List.of(new Question("scopeNotes", "O que precisa ser confirmado sobre o escopo?", "text",
                    "Registre dúvidas sobre a operação, a fronteira ou a metodologia candidata.", List.of())),
            List.of(), Set.of(),
            new Presentation("Descreva a atividade que pretende realizar",
                    "Descreva como a atividade ocorreria sem o projeto", List.of(
                    new NextSection("linha-base", "Linha de base", "Cenário sem o projeto e veículos comparáveis."),
                    new NextSection("adicionalidade", "Adicionalidade", "Cronologia do investimento e da receita de carbono, com evidências."),
                    new NextSection("direitos", "Direitos do projeto", "Titulares das reduções e documentos de titularidade."))),
            null);
    static final Profile VM0038 = new Profile(
            "VM0038_V1_1", "VM0038", "1.1", "VM0038 v1.1 · Recarga de veículos elétricos",
            "Atividade", "Recorte inicial",
            "A VM0038 trata da instalação de sistemas de recarga para frotas elegíveis. O piloto sintético original cobre um projeto fechado. A jornada OPEN brasileira está em implantação: é possível preparar uma rede pública de recarga DCFC/BEV do SIN sem VIN dos visitantes, mas o cálculo real depende de fontes oficiais e revisão de direitos.",
            "https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/",
            List.of("Instalação, fronteira e acesso à rede de recarga",
                    "Frota elegível e veículo a combustão comparável",
                    "Energia entregue ao veículo (ED) e consumida pelo sistema (EC)",
                    "Fonte de eletricidade, medidores e documentos de titularidade",
                    "Aplicabilidade por mercado e demonstração de adicionalidade"),
            List.of(
                    choice("vehicleClass", "Classe da frota atendida", "Escolha a classe que a infraestrutura atenderá.",
                            new Option("LDV", "Veículos leves"), new Option("HDV", "Ônibus e caminhões médios ou pesados"),
                            new Option("OTHER", "Outra classe ou combinação")),
                    choice("propulsion", "Propulsão da frota atendida", "Informe os veículos que usarão os carregadores.",
                            new Option("BEV", "BEV · elétrico a bateria"), new Option("PHEV", "PHEV · híbrido plug-in"),
                            new Option("COMBUSTAO", "Combustão ou híbrido sem recarga externa"),
                            new Option("MIXED", "Frota mista")),
                    choice("chargerType", "Tipo de recarga", "Para veículos leves, a combinação com a propulsão precisa ser conferida.",
                            new Option("DCFC", "DCFC · recarga rápida em corrente contínua"),
                            new Option("AC", "AC · recarga em corrente alternada (nível 1 ou 2)"),
                            new Option("MIXED", "Combinação de tecnologias")),
                    choice("design", "Desenho do projeto", "Projetos agrupados têm instâncias de atividade e critérios próprios.",
                            new Option("INDIVIDUAL", "Projeto individual"), new Option("GROUPED", "Projeto agrupado"))),
            List.of(choice("installsChargers", "O projeto inclui instalar sistemas de recarga?",
                    "Indique se a atividade prevê instalar sistemas de recarga.",
                    new Option("YES", "Sim"), new Option("NO", "Não"))),
            Set.of("MOBILIDADE_ELETRICA"),
            new Presentation("Descreva onde, para quem e como os carregadores serão instalados",
                    "Ex.: viagens atendidas por veículos equivalentes a combustão", List.of(
                    new NextSection("rede", "Acesso à rede", "Acesso aos carregadores, identificação da frota, origem da eletricidade e infraestrutura associada."),
                    new NextSection("estacoes", "Estações e fronteira", "Estações, carregadores e veículos do projeto."),
                    new NextSection("monitorar", "Monitorar energia", "Medidores e medição da energia entregue e consumida."),
                    new NextSection("linha-base", "Linha de base", "Cenário sem o projeto e veículos comparáveis."),
                    new NextSection("adicionalidade", "Adicionalidade", "Cronologia do investimento e da receita de carbono, com evidências."),
                    new NextSection("direitos", "Direitos do projeto", "Titulares das reduções e documentos de titularidade."))),
            new Evaluator() {
                @Override public Guidance guidance(JsonNode answers) { return vm0038Guidance(answers); }
                @Override public ProjectTriageDiagnosis.Result diagnose(JsonNode answers, Guidance guidance) {
                    return ProjectTriageDiagnosis.evaluateVm0038(answers, guidance);
                }
            });


    /**
     * D16: answers that left the triage, with the technical screen that owns each one now. Older revisions keep them,
     * every new revision copies them unchanged and the triage refuses to change them; the triage only shows them,
     * read-only, with a link to the owner. Option labels are kept so an older answer can still be read.
     */
    record Retired(int step, String key, String label, String section, String sectionTitle, List<Option> options) {}

    private static final String REDE = "rede", REDE_TITLE = "Acesso à rede";
    static final List<Retired> RETIRED_ANSWERS = List.of(
            retired(3, REDE, REDE_TITLE, choice("network", "Identificação da frota na rede", null,
                    new Option("CLOSED", "Fechada · frota identificada"), new Option("OPEN", "Aberta · frota sem identificação individual"))),
            retired(3, REDE, REDE_TITLE, choice("access", "Acesso aos carregadores", null,
                    new Option("PRIVATE", "Restrito a uma frota definida"), new Option("PUBLIC", "Aberto ao público"))),
            retired(3, REDE, REDE_TITLE, choice("electricitySource", "Origem da eletricidade", null,
                    new Option("SIN", "Somente rede do SIN"), new Option("OTHER_GRID", "Outra rede elétrica"),
                    new Option("ON_SITE", "Geração local"), new Option("MIXED", "Mais de uma origem"),
                    new Option("DESCONHECIDA", "Ainda não conhecida"))),
            retired(3, REDE, REDE_TITLE, choice("associatedInfrastructure", "Infraestrutura elétrica associada", null,
                    new Option("NO", "Sem infraestrutura associada"), new Option("YES", "Com infraestrutura associada"))),
            retired(3, "monitorar", "Monitorar energia", choice("metering", "Medição da energia consumida na recarga", null,
                    new Option("SEGREGATED", "Medição a montante em circuito exclusivo"),
                    new Option("OTHER", "Outra forma de medição ou estimativa"))),
            retired(3, "estacoes", "Estações e fronteira", number("vehicleCount", "Quantidade estimada de veículos")),
            retired(3, "estacoes", "Estações e fronteira", number("chargerCount", "Quantidade estimada de carregadores")),
            retired(4, "evidencias", "Evidências", choice("evidenceStatus", "Situação dos dados de origem", null,
                    new Option("EM_PREPARACAO", "Estou organizando os dados"),
                    new Option("DISPONIVEL_DECLARADO", "Declaro possuir dados, sem conferência"))),
            retired(4, "direitos", "Direitos do projeto", choice("rightsStatus", "Situação de direitos e titularidade", null,
                    new Option("DECLARADO", "Declaro que possuo direitos, sem comprovação"),
                    new Option("NAO_APLICAVEL_JUSTIFICADO", "Entendo que não se aplica, com justificativa"))),
            retired(4, "direitos", "Direitos do projeto", new Question("rightsJustification",
                    "Justificativa sobre titularidade", "text", null, List.of())),
            retired(4, "adicionalidade", "Adicionalidade", choice("investmentCommitted",
                    "Compromisso de investimento", null, new Option("YES", "Sim"), new Option("NO", "Não"))),
            retired(4, "adicionalidade", "Adicionalidade", choice("carbonConsideredBeforeInvestment",
                    "Receita de carbono considerada antes do compromisso", null, new Option("YES", "Sim"), new Option("NO", "Não"))));

    static final Map<Integer, Set<String>> RETIRED = Map.of(
            3, keys(3), 4, keys(4));

    static boolean retired(int step, String key) {
        return RETIRED.getOrDefault(step, Set.of()).contains(key);
    }

    private static Retired retired(int step, String section, String title, Question question) {
        return new Retired(step, question.key(), question.label(), section, title, question.options());
    }

    private static Set<String> keys(int step) {
        return RETIRED_ANSWERS.stream().filter(item -> item.step() == step).map(Retired::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Retired step-3 questions, still needed to read an older answer in the diagnosis. */
    static List<Question> retiredScopeQuestions() {
        return RETIRED_ANSWERS.stream().filter(item -> item.step() == 3)
                .map(item -> new Question(item.key(), item.label(), item.options().isEmpty() ? "number" : "choice",
                        null, item.options())).toList();
    }

    private static final List<Profile> CONFIGURED = List.of(VM0038);
    static final List<Option> METHODOLOGIES = CONFIGURED.stream()
            .map(profile -> new Option(profile.candidate(), profile.title())).toList();

    private ProjectJourney() {}

    static Profile profile(String candidate) {
        if (candidate == null) return GENERAL;
        String code = candidate.strip().toUpperCase(Locale.ROOT);
        return CONFIGURED.stream().filter(profile -> code.equals(profile.candidate())
                || code.equals(profile.candidate() + " V" + profile.edition())).findFirst().orElse(GENERAL);
    }

    static String candidate(JsonNode answers) {
        return answers.path("1").path("methodologyCandidate").asText(null);
    }

    static Profile profile(JsonNode answers) {
        return profile(candidate(answers));
    }

    static boolean known(JsonNode value) {
        return value.isNumber() || value.isTextual() && !value.asText().isBlank()
                && !UNKNOWN.equals(value.asText()) && !"DESCONHECIDA".equals(value.asText());
    }

    static Guidance guidance(JsonNode answers) {
        return guidance(profile(answers), answers);
    }

    static Guidance guidance(Profile profile, JsonNode answers) {
        if (profile.evaluator() == null) return new Guidance("SEM_JORNADA_CONFIGURADA", List.of(), List.of());
        return profile.evaluator().guidance(answers);
    }

    private static Guidance vm0038Guidance(JsonNode answers) {
        List<String> method = new ArrayList<>();
        List<PilotLimit> pilot = new ArrayList<>();
        JsonNode details = answers.path("3");
        JsonNode location = answers.path("4");
        String activity = answers.path("1").path("activityType").asText();
        if ((!activity.isBlank() && !List.of("MOBILIDADE_ELETRICA", UNKNOWN).contains(activity))
                || "NO".equals(answers.path("2").path("installsChargers").asText())) {
            method.add("A atividade indicada precisa incluir a instalação de sistemas de recarga para veículos elegíveis.");
        }
        if ("NAO_APLICAVEL_JUSTIFICADO".equals(location.path("rightsStatus").asText())) {
            method.add("A metodologia exige comprovar a titularidade das reduções.");
        }
        if ("COMBUSTAO".equals(details.path("propulsion").asText())) {
            method.add("A frota atendida precisa ser elétrica ou híbrida plug-in nas combinações previstas pela metodologia.");
        }
        if ("LDV".equals(details.path("vehicleClass").asText()) && "PHEV".equals(details.path("propulsion").asText())
                && "DCFC".equals(details.path("chargerType").asText())) {
            method.add("Para veículos leves híbridos plug-in, a VM0038 v1.1 prevê recarga de nível 1 ou 2.");
        }

        compare(pilot, 4, location, "country", "BR", "O piloto foi implementado para o Brasil.");
        if (!"Distrito Federal".equalsIgnoreCase(location.path("state").asText())) {
            compare(pilot, 4, location, "state", "DF", "O piloto foi implementado para o Distrito Federal.");
        }
        compare(pilot, 3, details, "design", "INDIVIDUAL", "O piloto cobre projetos individuais.");
        compare(pilot, 3, details, "vehicleClass", "LDV", "O piloto cobre veículos leves.");
        compare(pilot, 3, details, "propulsion", "BEV", "O piloto cobre frotas BEV.");
        compare(pilot, 3, details, "chargerType", "DCFC", "O piloto cobre estações DCFC.");
        compare(pilot, 3, details, "network", "CLOSED", "O piloto usa rede fechada, com frota identificada.");
        compare(pilot, 3, details, "access", "PRIVATE", "O piloto usa acesso restrito à frota.");
        String source = details.path("electricitySource").asText();
        if (List.of("OTHER_GRID", "ON_SITE", "MIXED").contains(source)) {
            pilot.add(new PilotLimit(3, "electricitySource", "O piloto usa exclusivamente eletricidade do SIN."));
        }
        compare(pilot, 3, details, "associatedInfrastructure", "NO", "Infraestrutura associada exige uma rota fora do piloto.");
        compare(pilot, 3, details, "metering", "SEGREGATED", "O piloto exige medição a montante em circuito exclusivo.");
        checkCount(pilot, details, "vehicleCount", IntakeRules.MAX_VEHICLES, "veículos");
        checkCount(pilot, details, "chargerCount", IntakeRules.MAX_STATIONS, "carregadores");
        return new Guidance(method.isEmpty() ? "A_CONFIRMAR" : "DIVERGENCIA_METODOLOGICA",
                List.copyOf(method), List.copyOf(pilot));
    }


    /** Configured packages plus the generic profile: the only source of the fields the triage may carry. */
    static List<Profile> profiles() {
        var all = new ArrayList<Profile>();
        all.add(GENERAL);
        all.addAll(CONFIGURED);
        return List.copyOf(all);
    }

    /** Required fields of a step for a profile. The generic profile asks for scope notes only for activities no package covers. */
    static List<String> required(Profile profile, int step, List<String> base, JsonNode answers) {
        if (step == 2) {
            var result = new ArrayList<String>(profile.activityQuestions().stream().map(Question::key).toList());
            result.addAll(base);
            return List.copyOf(result);
        }
        if (step != 3) return base;
        if (profile.evaluator() != null) return profile.questions().stream().map(Question::key).toList();
        String category = answers.path("1").path("activityType").asText();
        return !category.isBlank() && !Set.of("MOBILIDADE_ELETRICA", UNKNOWN).contains(category)
                ? List.of("scopeNotes") : List.of();
    }

    /** Key of the first submitted answer the profile's schema does not accept, or null. */
    static String invalidAnswer(Profile profile, int step, JsonNode submitted, JsonNode previousStep) {
        if (step == 2) {
            var own = profile.activityQuestions();
            for (var entry : submitted.properties()) {
                if (entry.getValue().isNull()) continue;
                var question = own.stream().filter(item -> item.key().equals(entry.getKey())).findFirst().orElse(null);
                if (question != null) {
                    if (!accepts(question, entry.getValue())) return entry.getKey();
                    continue;
                }
                boolean foreign = profiles().stream().flatMap(item -> item.activityQuestions().stream())
                        .anyMatch(item -> item.key().equals(entry.getKey()));
                if (foreign) return entry.getKey();
            }
            return null;
        }
        if (step != 3) return null;
        for (var entry : submitted.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isNull() || value.equals(previousStep.path(key))) continue;
            if (profile.evaluator() == null && "notApplicableReason".equals(key)) continue;
            var question = profile.questions().stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
            if (question == null || !accepts(question, value)) return key;
        }
        return null;
    }

    private static boolean accepts(Question question, JsonNode value) {
        return !"choice".equals(question.type())
                || question.options().stream().anyMatch(option -> option.value().equals(value.asText()));
    }

    private static void compare(List<PilotLimit> reasons, int step, JsonNode answers, String field, String expected, String message) {
        JsonNode value = answers.path(field);
        if (known(value) && !expected.equalsIgnoreCase(value.asText())) {
            reasons.add(new PilotLimit(step, field, message));
        }
    }

    private static void checkCount(List<PilotLimit> reasons, JsonNode answers, String field, int max, String label) {
        JsonNode value = answers.path(field);
        if (value.isIntegralNumber() && (value.asInt() == 0 || value.asInt() > max)) {
            reasons.add(new PilotLimit(3, field, "O piloto aceita de 1 a " + max + " " + label + "."));
        }
    }

    private static Question choice(String key, String label, String help, Option... options) {
        var list = new ArrayList<>(List.of(options));
        list.add(UNKNOWN_OPTION);
        return new Question(key, label, "choice", help, List.copyOf(list));
    }

    private static Question number(String key, String label) {
        return new Question(key, label, "number", null, List.of());
    }
}
