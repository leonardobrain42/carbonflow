package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/** Product journeys keyed by methodology edition. Matching a journey does not assess eligibility. */
final class ProjectJourney {
    record Option(String value, String label) {}
    record Question(String key, String label, String type, String help, List<Option> options) {}
    record Profile(String id, String candidate, String edition, String title, String activitiesTitle, String detailsTitle,
                   String description, String sourceUrl, List<String> technicalTopics, List<Question> questions) {}
    record Guidance(String state, List<String> outsideMethodology, List<String> outsidePilot) {}

    private static final String UNKNOWN = "DESCONHECIDO";
    private static final Option UNKNOWN_OPTION = new Option(UNKNOWN, "Ainda não sei");
    static final Profile GENERAL = new Profile(
            "GENERAL", null, null, "Triagem inicial", "Atividade", "Escopo e lacunas",
            "Descreva o que o projeto faz e quais informações faltam para escolher uma metodologia.",
            null, List.of("Descrição da atividade e localização", "Cenário sem o projeto", "Dados e direitos a comprovar"),
            List.of(new Question("scopeNotes", "O que precisa ser confirmado sobre o escopo?", "text",
                    "Registre dúvidas sobre a operação, a fronteira ou a metodologia candidata.", List.of())));
    static final Profile VM0038 = new Profile(
            "VM0038_V1_1", "VM0038", "1.1", "VM0038 v1.1 · Recarga de veículos elétricos",
            "Instalação e cenário sem o projeto", "Frota, recarga e medição",
            "A VM0038 trata da instalação de sistemas de recarga para frotas elegíveis. O piloto do CarbonFlow cobre um projeto individual no DF, com frota leve BEV, recarga rápida DC em rede fechada e privada, SIN exclusivo, sem infraestrutura associada e com medição segregada.",
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
                    choice("network", "Identificação da frota na rede", "Uma rede fechada identifica a frota que utiliza os carregadores.",
                            new Option("CLOSED", "Fechada · frota identificada"), new Option("OPEN", "Aberta · frota sem identificação individual")),
                    choice("access", "Acesso aos carregadores", "Considere quem pode recarregar nos pontos do projeto.",
                            new Option("PRIVATE", "Restrito a uma frota definida"), new Option("PUBLIC", "Aberto ao público")),
                    choice("electricitySource", "Origem da eletricidade", "SIN é o Sistema Interligado Nacional brasileiro.",
                            new Option("SIN", "Somente rede do SIN"), new Option("OTHER_GRID", "Outra rede elétrica"),
                            new Option("ON_SITE", "Geração local"), new Option("MIXED", "Mais de uma origem")),
                    choice("associatedInfrastructure", "Infraestrutura elétrica associada", "Considere geração local, armazenamento e fluxo bidirecional.",
                            new Option("NO", "Sem infraestrutura associada"), new Option("YES", "Com infraestrutura associada")),
                    choice("metering", "Medição da energia consumida na recarga", "O piloto usa circuito exclusivo, com medição a montante do carregador.",
                            new Option("SEGREGATED", "Medição a montante em circuito exclusivo"),
                            new Option("OTHER", "Outra forma de medição ou estimativa")),
                    choice("design", "Desenho do projeto", "Projetos agrupados têm instâncias de atividade e critérios próprios.",
                            new Option("INDIVIDUAL", "Projeto individual"), new Option("GROUPED", "Projeto agrupado")),
                    number("vehicleCount", "Quantidade estimada de veículos"),
                    number("chargerCount", "Quantidade estimada de carregadores")));

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
        if (profile(answers) == GENERAL) {
            return new Guidance("SEM_JORNADA_CONFIGURADA", List.of(), List.of());
        }
        List<String> method = new ArrayList<>();
        List<String> pilot = new ArrayList<>();
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

        compare(pilot, location, "country", "BR", "O piloto foi implementado para o Brasil.");
        if (!"Distrito Federal".equalsIgnoreCase(location.path("state").asText())) {
            compare(pilot, location, "state", "DF", "O piloto foi implementado para o Distrito Federal.");
        }
        compare(pilot, details, "design", "INDIVIDUAL", "O piloto cobre projetos individuais.");
        compare(pilot, details, "vehicleClass", "LDV", "O piloto cobre veículos leves.");
        compare(pilot, details, "propulsion", "BEV", "O piloto cobre frotas BEV.");
        compare(pilot, details, "chargerType", "DCFC", "O piloto cobre estações DCFC.");
        compare(pilot, details, "network", "CLOSED", "O piloto usa rede fechada, com frota identificada.");
        compare(pilot, details, "access", "PRIVATE", "O piloto usa acesso restrito à frota.");
        String source = details.path("electricitySource").asText();
        if (List.of("OTHER_GRID", "ON_SITE", "MIXED").contains(source)) {
            pilot.add("O piloto usa exclusivamente eletricidade do SIN.");
        }
        compare(pilot, details, "associatedInfrastructure", "NO", "Infraestrutura associada exige uma rota fora do piloto.");
        compare(pilot, details, "metering", "SEGREGATED", "O piloto exige medição a montante em circuito exclusivo.");
        checkCount(pilot, details, "vehicleCount", IntakeRules.MAX_VEHICLES, "veículos");
        checkCount(pilot, details, "chargerCount", IntakeRules.MAX_STATIONS, "carregadores");
        return new Guidance(method.isEmpty() ? "A_CONFIRMAR" : "DIVERGENCIA_METODOLOGICA",
                List.copyOf(method), List.copyOf(pilot));
    }

    private static void compare(List<String> reasons, JsonNode answers, String field, String expected, String message) {
        JsonNode value = answers.path(field);
        if (known(value) && !expected.equalsIgnoreCase(value.asText())) reasons.add(message);
    }

    private static void checkCount(List<String> reasons, JsonNode answers, String field, int max, String label) {
        JsonNode value = answers.path(field);
        if (value.isIntegralNumber() && (value.asInt() == 0 || value.asInt() > max)) {
            reasons.add("O piloto aceita de 1 a " + max + " " + label + ".");
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
