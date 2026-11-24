package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decides whether a project calculation attempt may call the pure kernel.
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

    static String encode(List<OpenCalculationPreflight.Gate> gates) {
        var encoded = new StringBuilder();
        for (var gate : gates) {
            if (!encoded.isEmpty()) encoded.append('\n');
            encoded.append(gate.code()).append('=').append(gate.state());
        }
        return encoded.toString();
    }

    static OpenCalculationPreflight.Gate gate(String code, String state) {
        if ("OUTSIDE_APPLICABLE_WINDOW".equals(state)) {
            return new OpenCalculationPreflight.Gate(code, state,
                "O vínculo cadastrado está fora do ano civil ou da janela desta tentativa.",
                "Vincular uma versão aplicável a este período", "PREP/REV");
        }
        String reason = switch (code) {
            case "ED" -> "MISSING".equals(state)
                ? "Não há energia entregue vigente neste período."
                : "A energia entregue está vinculada e permanece não admitida.";
            case "EC" -> "MISSING".equals(state)
                ? "Não há energia consumida vigente neste período."
                : "A energia consumida está vinculada e permanece não admitida.";
            case "EF_GRID" -> "MISSING".equals(state)
                ? "Não há fator elétrico vinculado neste período."
                : "O fator elétrico vinculado não é parâmetro admitido do E8.";
            case "HOURLY_EFEL_NOT_UNIFORM" ->
                "As horas do vínculo não compartilham um único fator elétrico. O motor não calcula média.";
            case "EVR" -> "MISSING".equals(state)
                ? "Não há seleção de frota EVR neste período."
                : "A seleção de frota existe e não está admitida.";
            case "AFEC_MPG" -> "MISSING".equals(state)
                ? "Não há ratings de projeto vinculados neste período."
                : "Os ratings vinculados não estão admitidos. O catálogo licenciado não é copiado para o run.";
            case "EF_FUEL" -> switch (state) {
                case "NOT_ADMITTED" ->
                    "O fator fóssil está vinculado por referência e permanece não admitido.";
                case "REVIEW_REQUIRED" ->
                    "O fator fóssil vinculado não tem valor utilizável neste ensaio.";
                default -> "Não há fator fóssil do projeto com valor armazenado.";
            };
            case "IR" -> switch (state) {
                case "NOT_ADMITTED" ->
                    "A melhoria tecnológica foi declarada com regra e prova e permanece não admitida.";
                case "REVIEW_REQUIRED" ->
                    "A melhoria declarada como zero não entra no ensaio. A tentativa não troca zero por 1.";
                default ->
                    "A melhoria tecnológica não tem valor normalizado armazenado. A tentativa não inventa 1.";
            };
            case "DY" -> switch (state) {
                case "NOT_ADMITTED" ->
                    "O desconto foi declarado como ausência comprovada de créditos de frota e permanece não admitido.";
                case "REVIEW_REQUIRED" ->
                    "O valor declarado de Dy não é a prova de ausência de créditos de frota. O ensaio não inventa a divisão entre créditos.";
                default ->
                    "O desconto por outro crédito não tem valor nem prova armazenados. A tentativa não presume desconto.";
            };
            case "PROJECT_YEAR" -> switch (state) {
                case "WINDOW_MISSING" ->
                    "Vincule o snapshot de QA com as datas da janela de monitoramento a este período.";
                case "WINDOW_CROSSES_YEAR" ->
                    "A janela inclui horas de dois anos civis no fuso de Brasília. Divida o período na virada do ano.";
                case "CREDITING_START_CONFLICT" ->
                    "A data declarada para o início de crédito diverge do início operacional da primeira estação.";
                case "CREDITING_START_REVISED" ->
                    "O evento de início foi revisado. Atualize o vínculo na declaração de rede aberta.";
                case "WINDOW_BEFORE_CREDITING" ->
                    "A janela começa antes do início operacional da primeira estação.";
                case "PROJECT_YEAR_OUT_OF_RANGE" ->
                    "O ano da janela está fora do intervalo de cálculo suportado.";
                default ->
                    "Vincule à declaração de rede aberta o evento de início operacional da primeira estação.";
            };
            case "NORMATIVE_PACK" ->
                "O pacote normativo do projeto não está admitido para execução.";
            case "CARBON_RIGHTS" ->
                "Os direitos sobre as reduções não estão admitidos para execução.";
            case "TECHNICAL_BRANCH" ->
                "O ramo OPEN de rede exclusiva ainda não está confirmado para execução.";
            case "ACTOR_SEGREGATION" ->
                "Quem registra a tentativa é autor dos vínculos. A execução interna exige outro ator.";
            case "RUN_REFERENCES" ->
                "O calendário ou os vínculos desta tentativa exigem rechecagem.";
            default -> "Pendência do conjunto de cálculo.";
        };
        String action = switch (code) {
            case "PROJECT_YEAR" -> "Conferir as datas de início de crédito e do monitoramento";
            case "ACTOR_SEGREGATION" -> "Outro ator precisa registrar a execução interna";
            case "RUN_REFERENCES" -> "Registrar uma nova tentativa com as versões atuais";
            case "HOURLY_EFEL_NOT_UNIFORM" -> "Manter um único fator elétrico horário ou esperar o cálculo por hora";
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

}
