package br.carbonflow.pilot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decides whether a project calculation attempt may call the pure kernel.
 * The caller resolves the dated monitoring window and matching parameter versions.
 * A complete operand set still produces an internal rehearsal.
 */
final class OpenE8RunDecision {
    record OperandSet(boolean complete, boolean stale, boolean actorIsAuthor,
                      List<OpenCalculationPreflight.Gate> gates,
                      OpenGridCalculationMath.Input input, OpenHourlyProjectEmissions.Series hourly) {
        OperandSet(boolean complete, boolean stale, boolean actorIsAuthor,
                   List<OpenCalculationPreflight.Gate> gates, OpenGridCalculationMath.Input input) {
            this(complete, stale, actorIsAuthor, gates, input, null);
        }
    }

    record Step(String code, String reference, String unit, String result) {}

    record Memory(String engine, String kernelState, boolean usableForE8,
                  String afecKwhPer100Miles, String mpgUs,
                  String beTco2e, String peTco2e, String leTco2e, String dy,
                  String erTco2e, String erDisplay, List<Step> trace,
                  OpenHourlyProjectEmissions.Memory hourlyPe) {}

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
            var kernel = OpenGridCalculationMath.calculate(set.input(), set.hourly());
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

    record StoredTrace(List<Step> steps, OpenHourlyProjectEmissions.Memory hourlyPe) {}
    private static final ObjectMapper JSON = new ObjectMapper();

    static StoredTrace readTrace(String json) {
        if (json == null || json.isBlank()) return new StoredTrace(List.of(), null);
        try {
            // Previous engines stored a JSON array with the seven equation steps.
            if (JSON.readTree(json).isArray())
                return new StoredTrace(List.of(JSON.readValue(json, Step[].class)), null);
            return JSON.readValue(json, StoredTrace.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Memória E8 armazenada inválida.", error);
        }
    }

    static List<Step> parseTrace(String json) {
        return readTrace(json).steps();
    }

    static String traceJson(Memory memory) {
        try {
            return JSON.writeValueAsString(new StoredTrace(memory.trace(), memory.hourlyPe()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Falha ao gravar a memória E8.", error);
        }
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
                ? "Não há energia entregue vigente neste período."
                : "A energia entregue está vinculada e permanece não admitida.";
            case "EC" -> "MISSING".equals(state)
                ? "Não há energia consumida vigente neste período."
                : "A energia consumida está vinculada e permanece não admitida.";
            case "EF_GRID" -> "MISSING".equals(state)
                ? "Não há fator elétrico vinculado neste período."
                : "O fator elétrico vinculado não é parâmetro admitido do E8.";
            case "HOURLY_COVERAGE_INVALID" ->
                "A série de energia e fatores exige todas as horas da janela, com a mesma região e versão.";
            case "HOURLY_EFEL_NOT_UNIFORM" ->
                "As horas do vínculo não compartilham um único fator elétrico. O motor não calcula média.";
            case "EVR" -> "MISSING".equals(state)
                ? "Não há seleção de frota EVR neste período."
                : "A seleção de frota existe e não está admitida.";
            case "AFEC_MPG" -> "MISSING".equals(state)
                ? "Não há ratings de projeto vinculados neste período."
                : "Os ratings vinculados não estão admitidos. O catálogo licenciado não é copiado para o run.";
            case "RUN_WINDOW_MISSING" -> "A tentativa precisa da janela de QA vinculada a ED, EC e EFel.";
            case "CREDIT_START_MISSING" ->
                "A declaração precisa vincular o início operacional e o início do período de crédito.";
            case "CREDIT_START_RECHECK" ->
                "As datas da declaração precisam ser reconfirmadas com os eventos e a triagem vigentes.";
            case "CREDIT_START_MISMATCH" ->
                "O início de crédito informado diverge do início operacional do primeiro sistema de recarga.";
            case "WINDOW_BEFORE_CREDIT_START" -> "A janela começa antes da data de início de crédito declarada.";
            case "WINDOW_PROJECT_YEAR_BOUNDARY" ->
                "A vigência da janela não cabe em um único ano do projeto suportado pelo ensaio.";
            case "WINDOW_SOURCE_YEAR_BOUNDARY" ->
                "A janela cruza a fronteira UTC do ano das fontes de frota e fatores.";
            case "FLEET_WINDOW_MISMATCH" -> "A seleção de frota precisa corresponder ao vínculo e ao ano das fontes da janela.";
            case "RUN_TIMING_LEGACY" -> "Esta tentativa antecede o registro da janela e da vigência.";
            case "EF_FUEL" -> switch (state) {
                case "MISSING_FOR_SOURCE_YEAR" -> "Falta um fator fóssil vinculado para o ano das fontes da janela.";
                case "STALE" -> "O fator fóssil do ano das fontes precisa de rechecagem.";
                case "NOT_ADMITTED" ->
                    "O fator fóssil está vinculado por referência e permanece não admitido.";
                case "REVIEW_REQUIRED" ->
                    "O fator fóssil vinculado não tem valor utilizável neste ensaio.";
                default -> "Não há fator fóssil do projeto com valor armazenado.";
            };
            case "EF_FUEL_WINDOW" -> "A vigência da entrada do catálogo não cobre toda a janela do período. O motor não aplica o fator de uma vigência a horas de outra nem calcula média entre vigências.";
            case "RUN_IR_LEGACY" -> "Tentativa anterior ao vínculo estruturado de IR; memória histórica preservada.";
            case "IR_STRUCTURED" -> switch (state) {
                case "TIMING_UNRESOLVED" -> "O calendário da janela ainda não permite selecionar IR.";
                case "FIRST_YEAR_REVIEW_REQUIRED" -> "Primeiro ano/ex ante requer avaliação da rota anual com parecer inicial independente vinculado ao plano ex ante vigente; sem isso não se presume IR.";
                case "FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED" -> "Primeiro ano: IR=1 pela rota de atualização anual, apenas como estimativa ex ante com plano conferido; não comprova atualização observada nem admite parâmetro ou crédito.";
                case "MISSING_FOR_PROJECT_YEAR" -> "Falta avaliação estruturada de IR para os anos do projeto e da fonte.";
                case "PERIOD_REFERENCE_MISMATCH" -> "A avaliação não corresponde aos vínculos exatos de frota, fator e janela deste período.";
                case "STALE" -> "Avaliação de IR obsoleta ou referências sem vigência.";
                case "ANNUAL_EVIDENCE_RECHECK_REQUIRED" -> "A continuidade anual deve ser reavaliada.";
                case "CHECKED_INTERNAL_NOT_ADMITTED" -> "IR=1 conferido com referências anuais, apenas para ensaio interno; sem admissão normativa.";
                default -> "IR precisa de parecer independente e evidências; pino escalar legado não substitui a avaliação.";
            };
            case "IR" -> switch (state) {
                case "MISSING_FOR_SOURCE_YEAR" -> "Falta a declaração de IR para o ano das fontes da janela.";
                case "STALE" -> "A declaração de IR precisa de rechecagem.";
                case "NOT_ADMITTED" ->
                    "A melhoria tecnológica foi declarada com regra e prova e permanece não admitida.";
                case "REVIEW_REQUIRED" ->
                    "A melhoria declarada como zero não entra no ensaio. A tentativa não troca zero por 1.";
                default ->
                    "A melhoria tecnológica não tem valor normalizado armazenado. A tentativa não inventa 1.";
            };
            case "DY" -> switch (state) {
                case "MISSING_FOR_SOURCE_YEAR" -> "Falta a declaração de Dy aplicável ao ano das fontes da janela.";
                case "STALE" -> "A declaração de Dy precisa de rechecagem.";
                case "NOT_ADMITTED" ->
                    "O desconto foi declarado como ausência comprovada de créditos de frota e permanece não admitido.";
                case "REVIEW_REQUIRED" ->
                    "O valor declarado de Dy não é a prova de ausência de créditos de frota. O ensaio não inventa a divisão entre créditos.";
                default ->
                    "O desconto por outro crédito não tem valor nem prova armazenados. A tentativa não presume desconto.";
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
                "Os vínculos desta tentativa não são mais os vigentes do período.";
            default -> "Pendência do conjunto de cálculo.";
        };
        String action = switch (code) {
            case "ACTOR_SEGREGATION" -> "Outro ator precisa registrar a execução interna";
            case "RUN_REFERENCES", "RUN_TIMING_LEGACY", "RUN_IR_LEGACY" -> "Registrar uma nova tentativa com as versões atuais";
            case "RUN_WINDOW_MISSING" -> "Vincular o snapshot de QA e o fator elétrico à janela";
            case "CREDIT_START_MISSING", "CREDIT_START_RECHECK", "CREDIT_START_MISMATCH" ->
                "Conferir os eventos de início e salvar a declaração de rede aberta";
            case "WINDOW_BEFORE_CREDIT_START" -> "Revisar a janela e a data documentada de início de crédito";
            case "WINDOW_PROJECT_YEAR_BOUNDARY", "WINDOW_SOURCE_YEAR_BOUNDARY" ->
                "Preparar janelas separadas na mudança de ano";
            case "IR_STRUCTURED" -> "Conferir avaliação estruturada e prova anual na linha de base";
            case "FLEET_WINDOW_MISMATCH" -> "Vincular a frota do ano das fontes ao pacote horário vigente";
            case "EF_FUEL_WINDOW" -> "Preparar janelas separadas na mudança de vigência do combustível, cada uma com o fator da sua vigência";
            case "HOURLY_COVERAGE_INVALID" -> "Conferir as horas e os totais do pacote de energia";
            case "HOURLY_EFEL_NOT_UNIFORM" -> "Manter um único fator elétrico horário ou esperar o cálculo por hora";
            default -> "Completar o vínculo e a admissão antes de executar";
        };
        String owner = switch (code) {
            case "ACTOR_SEGREGATION", "CARBON_RIGHTS", "NORMATIVE_PACK" -> "REV";
            case "RUN_REFERENCES", "RUN_TIMING_LEGACY" -> "PREP";
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
            kernel.erDisplay(), List.copyOf(trace), kernel.hourlyPe());
    }

}
