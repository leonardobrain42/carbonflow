package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decides whether a project calculation attempt may call the pure kernel.
 * The production reader never supplies a complete operand set: IR, Dy and
 * EFfuel have no stored project value. A unit test supplies that set.
 * The kernel result stays a rehearsal: canIssueVcu is false.
 */
final class OpenE8RunDecision {
    record OperandSet(boolean complete, boolean stale, boolean actorIsAuthor,
                      List<OpenCalculationPreflight.Gate> gates,
                      OpenGridCalculationMath.Input input) {}

    record Step(String code, String reference, String unit, String result) {}

    record Memory(String engine, String kernelState, boolean usableForE8,
                  String afecKwhPer100Miles, String mpgUs,
                  String beTco2e, String peTco2e, String leTco2e, String dy,
                  String erTco2e, String erDisplay, List<Step> trace) {}

    record Decision(String state, boolean canIssueVcu,
                    List<OpenCalculationPreflight.Gate> gates,
                    List<OpenCalculationTransparency.Stage> equationPlan,
                    Memory memory) {}

    @FunctionalInterface
    interface Pins { OperandSet load(UUID actor); }

    private OpenE8RunDecision() {}

    static Decision decide(Pins pins, UUID actor) {
        if (pins == null || actor == null)
            throw new IllegalArgumentException("Leitor ou ator ausente.");
        return decide(pins.load(actor));
    }

    static Decision decide(OperandSet set) {
        if (set == null) throw new IllegalArgumentException("Conjunto ausente.");
        var gates = new ArrayList<OpenCalculationPreflight.Gate>();
        if (set.gates() != null) gates.addAll(set.gates());
        boolean rehearsal = set.complete() && !set.stale() && !set.actorIsAuthor()
            && set.input() != null;
        if (rehearsal) {
            var kernel = OpenGridCalculationMath.calculate(set.input());
            if (kernel.usableForE8())
                throw new IllegalStateException("O kernel marcou o ensaio como admitido.");
            if (gates.isEmpty()) gates.add(gate("NORMATIVE_PACK", "NOT_ADMITTED"));
            return new Decision("REVIEWED_INTERNAL", false, List.copyOf(gates),
                OpenCalculationTransparency.plan(gates), memory(kernel));
        }
        if (set.complete() && set.actorIsAuthor())
            gates.add(gate("ACTOR_SEGREGATION", "REVIEW_REQUIRED"));
        if (gates.isEmpty()) gates.add(gate("EF_FUEL", "MISSING"));
        return new Decision("BLOCKED", false, List.copyOf(gates),
            OpenCalculationTransparency.plan(gates), null);
    }

    /** Read model. A stored row is never rewritten when its links move. */
    static String responseState(String stored, boolean stale) {
        return stale ? "STALE" : stored;
    }

    static List<Step> parseTrace(String json) {
        if (json == null || json.isBlank()) return List.of();
        var steps = new ArrayList<Step>();
        for (String object : json.split("\\},\\{")) {
            String code = field(object, "code");
            String reference = field(object, "reference");
            String unit = field(object, "unit");
            String result = field(object, "result");
            if (code != null && reference != null && unit != null && result != null)
                steps.add(new Step(code, reference, unit, result));
        }
        return List.copyOf(steps);
    }

    static String traceJson(Memory memory) {
        var json = new StringBuilder("[");
        for (int i = 0; i < memory.trace().size(); i++) {
            if (i > 0) json.append(',');
            Step step = memory.trace().get(i);
            json.append("{\"code\":\"").append(escape(step.code()))
                .append("\",\"reference\":\"").append(escape(step.reference()))
                .append("\",\"unit\":\"").append(escape(step.unit()))
                .append("\",\"result\":\"").append(escape(step.result()))
                .append("\"}");
        }
        return json.append(']').toString();
    }

    static String encode(List<OpenCalculationPreflight.Gate> gates) {
        var encoded = new StringBuilder();
        for (var gate : gates) {
            if (!encoded.isEmpty()) encoded.append('\n');
            encoded.append(gate.code()).append('=').append(gate.state());
        }
        return encoded.toString();
    }

    static OpenCalculationPreflight.Gate gate(String code, String state) {
        String reason = switch (code) {
            case "ED" -> "MISSING".equals(state)
                ? "Não há vínculo ED vigente neste período."
                : "O pacote ED está vinculado e permanece não admitido.";
            case "EC" -> "MISSING".equals(state)
                ? "Não há vínculo EC vigente neste período."
                : "O pacote EC está vinculado e permanece não admitido.";
            case "EF_GRID" -> "MISSING".equals(state)
                ? "Não há fator elétrico vinculado neste período."
                : "O fator elétrico vinculado não é parâmetro admitido do E8.";
            case "HOURLY_EFEL_NOT_UNIFORM" ->
                "As horas do vínculo não compartilham um único EFel. O motor não calcula média.";
            case "EVR" -> "MISSING".equals(state)
                ? "Não há seleção de frota EVR neste período."
                : "A seleção de frota existe e não está admitida.";
            case "AFEC_MPG" -> "MISSING".equals(state)
                ? "Não há ratings de projeto vinculados neste período."
                : "Os ratings vinculados não estão admitidos. O catálogo licenciado não é copiado para o run.";
            case "EF_FUEL" ->
                "Não há EFfuel de projeto com valor armazenado.";
            case "IR" ->
                "IR não tem valor normalizado armazenado. A tentativa não inventa 1.";
            case "DY" ->
                "Dy não tem valor nem prova armazenados. A tentativa não presume desconto.";
            case "NORMATIVE_PACK" ->
                "O pacote normativo do projeto não está admitido para execução.";
            case "CARBON_RIGHTS" ->
                "Os direitos sobre as reduções não estão admitidos para execução.";
            case "TECHNICAL_BRANCH" ->
                "O ramo OPEN de rede exclusiva ainda não está confirmado para execução.";
            case "ACTOR_SEGREGATION" ->
                "Quem registra a tentativa é autor dos vínculos. A execução interna exige outro ator.";
            case "RUN_REFERENCES" ->
                "Os vínculos desta tentativa não são mais os vigentes do período.";
            default -> "Pendência do conjunto de cálculo.";
        };
        String action = switch (code) {
            case "ACTOR_SEGREGATION" -> "Outro ator precisa registrar a execução interna";
            case "RUN_REFERENCES" -> "Registrar uma nova tentativa com as versões atuais";
            case "HOURLY_EFEL_NOT_UNIFORM" -> "Manter um único EFel horário ou esperar o runner horário";
            default -> "Completar o vínculo e a admissão antes de executar";
        };
        String owner = switch (code) {
            case "ACTOR_SEGREGATION", "CARBON_RIGHTS", "NORMATIVE_PACK" -> "REV";
            case "RUN_REFERENCES" -> "PREP";
            default -> "PREP/REV";
        };
        return new OpenCalculationPreflight.Gate(code, state, reason, action, owner);
    }

    private static Memory memory(OpenGridCalculationMath.Memory kernel) {
        var trace = new ArrayList<Step>();
        for (var step : kernel.equationTrace()) {
            trace.add(new Step(step.code(), step.reference(), step.unit(), step.result()));
        }
        return new Memory(kernel.engine(), kernel.state(), kernel.usableForE8(),
            kernel.afecKwhPer100Miles(), kernel.mpgUs(), kernel.beTco2e(),
            kernel.peTco2e(), kernel.leTco2e(), kernel.dy(), kernel.erTco2e(),
            kernel.erDisplay(), List.copyOf(trace));
    }

    private static String field(String object, String name) {
        String mark = "\"" + name + "\":\"";
        int start = object.indexOf(mark);
        if (start < 0) return null;
        int from = start + mark.length();
        var value = new StringBuilder();
        for (int i = from; i < object.length(); i++) {
            char current = object.charAt(i);
            if (current == '\\' && i + 1 < object.length()) {
                value.append(object.charAt(i + 1));
                i++;
            } else if (current == '"') return value.toString();
            else value.append(current);
        }
        return null;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
