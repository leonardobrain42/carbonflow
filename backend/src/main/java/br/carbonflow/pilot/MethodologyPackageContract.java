package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contract and conformance kit of a methodology package in code (D18, CF-PACKAGES-001 slice 4).
 *
 * A package is what a pull request adds for a registered methodology: a triage profile plus the declaration the
 * platform keeps about it. This kit states what every package must satisfy, as executable rules. The catalog is
 * built through {@link #requireProduction}: a package that breaks a rule never reaches the production catalog and
 * the release fails to start, so the failure is found in CI, not by a customer.
 *
 * The kit checks structure, vocabulary, isolation and neutrality. It never evaluates whether a methodology's rules
 * are right, admits data, grants a licence or enables calculation: those stay separate gates.
 */
final class MethodologyPackageContract {
    /** What a package declares, as the catalog reads it. */
    record Declaration(ProjectJourney.Profile profile, String program, int version, String status,
                       Map<String, String> capabilities) {}
    record Violation(String rule, String subject, String message) {}

    static final Set<String> CAPABILITY_KEYS = Set.of("triageSchema", "calculationAdmission", "externalActs");
    /** Answer keys the platform owns; a package must not redefine them. */
    static final Set<String> PLATFORM_KEYS = Set.of("objective", "activityType", "methodologyCandidate",
            "activityDescription", "baselinePractice", "expectedBenefit", "notApplicableReason", "scopeNotes",
            "country", "state", "locality", "referenceDate", "evidenceStatus", "rightsStatus",
            "rightsJustification", "investmentCommitted", "carbonConsideredBeforeInvestment", "_example");

    private static final Pattern PROGRAM = Pattern.compile("[A-Z][A-Z0-9_-]{1,39}");
    private static final Pattern METHODOLOGY = Pattern.compile("[A-Z][A-Z0-9_-]{2,39}");
    private static final Pattern EDITION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]{0,19}");
    private static final Pattern PACKAGE_ID = Pattern.compile("[A-Z][A-Z0-9_]{2,59}");
    private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{2,79}");
    private static final Pattern QUESTION_KEY = Pattern.compile("[a-z][A-Za-z0-9]{1,39}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MethodologyPackageContract() {}

    /** Rules about one package. */
    static List<Violation> check(Declaration d) {
        var out = new ArrayList<Violation>();
        var p = d.profile();
        String id = p == null ? "?" : String.valueOf(p.id());
        if (p == null) return List.of(new Violation("PKG-ID", id, "Pacote sem perfil de triagem."));

        if (p.id() == null || !PACKAGE_ID.matcher(p.id()).matches())
            out.add(new Violation("PKG-ID", id, "packageId fora do formato."));
        if (p.candidate() == null || !METHODOLOGY.matcher(p.candidate()).matches())
            out.add(new Violation("PKG-KEY", id, "Código da metodologia fora do formato do registro."));
        if (p.edition() == null || !EDITION.matcher(p.edition()).matches())
            out.add(new Violation("PKG-KEY", id, "Edição fora do formato do registro."));
        if (d.program() == null || !PROGRAM.matcher(d.program()).matches())
            out.add(new Violation("PKG-KEY", id, "Programa fora do formato do registro."));
        if (d.version() < 1) out.add(new Violation("PKG-VERSION", id, "A versão do pacote começa em 1."));
        if (d.status() == null || !STATUS.matcher(d.status()).matches())
            out.add(new Violation("PKG-STATUS", id, "Status fora do formato."));
        if (blank(p.title()) || blank(p.description()))
            out.add(new Violation("PKG-TEXT", id, "Título e descrição são obrigatórios."));

        if (p.activities().isEmpty())
            out.add(new Violation("PKG-ACTIVITY", id, "O pacote precisa declarar ao menos uma atividade."));
        for (String activity : p.activities()) {
            if (!MethodologyPackages.knownActivity(activity))
                out.add(new Violation("PKG-ACTIVITY", id, "Atividade fora do vocabulário da plataforma: " + activity));
        }

        var keys = new HashSet<String>();
        var all = new ArrayList<ProjectJourney.Question>(p.questions());
        all.addAll(p.activityQuestions());
        for (var question : all) {
            String key = question.key();
            if (key == null || !QUESTION_KEY.matcher(key).matches())
                out.add(new Violation("PKG-QUESTION", id, "Chave de pergunta inválida: " + key));
            else if (PLATFORM_KEYS.contains(key))
                out.add(new Violation("PKG-QUESTION", id, "A chave pertence à plataforma: " + key));
            else if (!keys.add(key))
                out.add(new Violation("PKG-QUESTION", id, "Chave repetida no pacote: " + key));
            if (blank(question.label()))
                out.add(new Violation("PKG-QUESTION", id, "Pergunta sem rótulo: " + key));
            if (!Set.of("choice", "number").contains(question.type()))
                out.add(new Violation("PKG-QUESTION", id, "Tipo de pergunta desconhecido: " + key));
            if ("choice".equals(question.type())) {
                var values = new HashSet<String>();
                if (question.options().size() < 2)
                    out.add(new Violation("PKG-QUESTION", id, "Pergunta de escolha com menos de duas opções: " + key));
                for (var option : question.options()) {
                    if (blank(option.value()) || blank(option.label()) || !values.add(option.value()))
                        out.add(new Violation("PKG-QUESTION", id, "Opção inválida ou repetida em " + key));
                }
            }
        }

        var caps = d.capabilities();
        if (caps == null || !caps.keySet().equals(CAPABILITY_KEYS)) {
            out.add(new Violation("PKG-CAPABILITY", id, "As capacidades declaradas devem ser exatamente " + CAPABILITY_KEYS));
        } else {
            if (!Set.of("AVAILABLE", "NOT_ENABLED").contains(caps.get("triageSchema")))
                out.add(new Violation("PKG-CAPABILITY", id, "triageSchema: AVAILABLE ou NOT_ENABLED."));
            for (String gate : List.of("calculationAdmission", "externalActs")) {
                if (!"NOT_ENABLED".equals(caps.get(gate)))
                    out.add(new Violation("PKG-CAPABILITY", id, "O pacote não habilita " + gate
                            + ": é um gate próprio, decidido fora do pacote."));
            }
        }

        if (p.presentation() == null || p.presentation().next() == null)
            out.add(new Violation("PKG-PRESENTATION", id, "Apresentação ausente."));
        out.addAll(neutrality(id, p));
        out.addAll(digestSensitivity(id, d));
        return List.copyOf(out);
    }

    /** Rules between packages: identity, answer keys and visible text must not cross methodologies (AGN-01). */
    static List<Violation> checkSet(List<Declaration> all) {
        var out = new ArrayList<Violation>();
        var ids = new HashSet<String>();
        var keys = new HashSet<String>();
        var owners = new java.util.HashMap<String, String>();
        for (var d : all) {
            var p = d.profile();
            if (!ids.add(p.id()))
                out.add(new Violation("PKG-DUPLICATE", p.id(), "packageId repetido."));
            if (!keys.add(d.program() + "|" + p.candidate() + "|" + p.edition()))
                out.add(new Violation("PKG-DUPLICATE", p.id(), "Programa, metodologia e edição repetidos."));
            for (var question : questions(p)) {
                String owner = owners.putIfAbsent(question.key(), p.id());
                if (owner != null && !owner.equals(p.id()))
                    out.add(new Violation("PKG-ISOLATION", p.id(), "A chave " + question.key()
                            + " já pertence ao pacote " + owner + "."));
            }
        }
        for (var d : all) {
            String text = visibleText(d.profile()).toLowerCase(Locale.ROOT);
            for (var other : all) {
                if (other == d) continue;
                for (String token : List.of(other.profile().candidate(), other.profile().id())) {
                    if (token != null && Pattern.compile("(?<![a-z0-9])" + Pattern.quote(token.toLowerCase(Locale.ROOT))
                            + "(?![a-z0-9])").matcher(text).find())
                        out.add(new Violation("PKG-ISOLATION", d.profile().id(),
                                "O texto do pacote cita outra metodologia: " + token));
                }
            }
        }
        return List.copyOf(out);
    }

    /** Production catalog gate: every rule, no test-only package. Throws so a bad release does not start. */
    static void requireProduction(List<Declaration> all) {
        var out = new ArrayList<Violation>();
        for (var d : all) {
            out.addAll(check(d));
            String id = d.profile() == null ? "" : String.valueOf(d.profile().id());
            if (id.startsWith("TEST_ONLY") || String.valueOf(d.status()).startsWith("TEST_ONLY")
                    || String.valueOf(d.profile() == null ? "" : d.profile().candidate()).startsWith("TEST-ONLY"))
                out.add(new Violation("PKG-TEST-ONLY", id, "Pacote de teste não entra no catálogo de produção."));
        }
        out.addAll(checkSet(all));
        if (!out.isEmpty()) {
            var message = new StringBuilder("Pacotes de metodologia fora do contrato (CF-PACKAGES-001):");
            out.forEach(v -> message.append("\n - ").append(v.rule()).append(' ').append(v.subject())
                    .append(": ").append(v.message()));
            throw new IllegalStateException(message.toString());
        }
    }

    /** The evaluator answers neutrally to empty or foreign answers: it never throws and always says where it stands. */
    private static List<Violation> neutrality(String id, ProjectJourney.Profile p) {
        var out = new ArrayList<Violation>();
        if (p.evaluator() == null) {
            out.add(new Violation("PKG-EVALUATOR", id, "Pacote sem avaliador de triagem."));
            return out;
        }
        try {
            for (String sample : List.of("{}", "{\"1\":{\"activityType\":\"DESCONHECIDO\"}}",
                    "{\"1\":\"texto\",\"2\":[],\"3\":null,\"9\":{\"x\":1}}",
                    "{\"1\":{\"activityType\":\"OUTRO\",\"methodologyCandidate\":\"OUTRA-METODOLOGIA\"}}")) {
                JsonNode answers = MAPPER.readTree(sample);
                var guidance = p.evaluator().guidance(answers);
                if (guidance == null || blank(guidance.state()))
                    out.add(new Violation("PKG-NEUTRAL", id, "Orientação ausente para respostas vazias ou alheias."));
                else if (p.evaluator().diagnose(answers, guidance) == null)
                    out.add(new Violation("PKG-NEUTRAL", id, "Diagnóstico ausente para respostas vazias ou alheias."));
            }
        } catch (Exception error) {
            out.add(new Violation("PKG-NEUTRAL", id, "O avaliador falhou diante de respostas vazias ou alheias: "
                    + error.getClass().getSimpleName()));
        }
        return out;
    }

    /** Any change to what the package declares must move its digest; otherwise a pin could not see the change. */
    private static List<Violation> digestSensitivity(String id, Declaration d) {
        var out = new ArrayList<Violation>();
        try {
            String base = MethodologyPackages.digestOf(d.profile(), d.program(), d.version(), d.status(), d.capabilities());
            var changedCaps = new java.util.HashMap<>(d.capabilities());
            changedCaps.put("triageSchema", "AVAILABLE".equals(d.capabilities().get("triageSchema")) ? "NOT_ENABLED" : "AVAILABLE");
            var variants = Map.of(
                    "programa", MethodologyPackages.digestOf(d.profile(), d.program() + "X", d.version(), d.status(), d.capabilities()),
                    "versão", MethodologyPackages.digestOf(d.profile(), d.program(), d.version() + 1, d.status(), d.capabilities()),
                    "status", MethodologyPackages.digestOf(d.profile(), d.program(), d.version(), d.status() + "X", d.capabilities()),
                    "capacidades", MethodologyPackages.digestOf(d.profile(), d.program(), d.version(), d.status(), changedCaps));
            variants.forEach((name, digest) -> {
                if (digest.equals(base)) out.add(new Violation("PKG-DIGEST", id, "O digest não muda com " + name + "."));
            });
            if (!base.matches("[a-f0-9]{64}")) out.add(new Violation("PKG-DIGEST", id, "Digest fora do formato."));
        } catch (RuntimeException error) {
            out.add(new Violation("PKG-DIGEST", id, "Não foi possível calcular o digest."));
        }
        return out;
    }

    private static List<ProjectJourney.Question> questions(ProjectJourney.Profile p) {
        var all = new ArrayList<>(p.questions());
        all.addAll(p.activityQuestions());
        return all;
    }

    /** Everything a customer would read from the package; used to detect another methodology's name leaking in. */
    static String visibleText(ProjectJourney.Profile p) {
        var text = new StringBuilder();
        for (String value : new String[]{p.title(), p.activitiesTitle(), p.detailsTitle(), p.description()}) {
            if (value != null) text.append(value).append('\n');
        }
        if (p.technicalTopics() != null) p.technicalTopics().forEach(item -> text.append(item).append('\n'));
        for (var question : questions(p)) {
            text.append(question.label()).append('\n');
            if (question.help() != null) text.append(question.help()).append('\n');
            question.options().forEach(option -> text.append(option.label()).append('\n'));
        }
        if (p.presentation() != null) {
            text.append(p.presentation().activityPlaceholder()).append('\n')
                    .append(p.presentation().baselinePlaceholder()).append('\n');
            if (p.presentation().next() != null) p.presentation().next().forEach(item ->
                    text.append(item.title()).append('\n').append(item.detail()).append('\n'));
        }
        return text.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
