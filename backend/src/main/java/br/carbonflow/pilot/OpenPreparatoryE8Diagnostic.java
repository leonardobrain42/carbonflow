package br.carbonflow.pilot;

import java.util.List;

/**
 * Projects see only their own preparatory handoff status, never private
 * staff-source values, unlicensed ratings, or a calculated E8 result.
 * An internal review or handoff cannot be interpreted as normative admission.
 */
final class OpenPreparatoryE8Diagnostic {
    private OpenPreparatoryE8Diagnostic() {}

    static List<OpenCalculationPreflight.Gate> gates(
            String period, String inputState, String fleetState) {
        return List.of(
            inputGate(period,inputState),
            fleetGate(period,fleetState));
    }

    private static OpenCalculationPreflight.Gate inputGate(String period,String state) {
        String code="E8_INPUT_BINDING_"+period;
        return switch (state) {
            case "CANDIDATE_NOT_ADMITTED" -> new OpenCalculationPreflight.Gate(
                code,"NOT_ADMITTED",
                "ED/EC e EFel horário possuem vínculo interno versionado para "+period+
                    ", mas nenhum desses parâmetros foi admitido normativamente. Não há PE calculado.",
                "Revisar fonte SIN, VT0011/TOOL07, vigência, direitos e parecer de admissibilidade",
                "PREP/REV");
            case "STALE_NOT_ADMITTED" -> new OpenCalculationPreflight.Gate(
                code,"STALE",
                "O vínculo ED/EC–EFel de "+period+" foi desatualizado por mudança de "+
                    "fonte, QA, revisão ou MR; a memória antiga segue histórica, não executável.",
                "Conferir as mudanças e preparar nova versão do vínculo horário","PREP/REV");
            case "BINDING_MISSING" -> new OpenCalculationPreflight.Gate(
                code,"MISSING",
                "Nenhum vínculo imutável ED/EC–EFel foi preparado para "+period+".",
                "Revisar o EFel internamente e vincular às medições ED/EC do mesmo período",
                "PREP/REV");
            default -> new OpenCalculationPreflight.Gate(
                code,"REVIEW_REQUIRED",
                "O estado do vínculo ED/EC–EFel de "+period+
                    " não está confirmado: não autorizar cálculo.",
                "Conferir fontes e estados de preparação com equipe técnica","PREP/REV");
        };
    }

    private static OpenCalculationPreflight.Gate fleetGate(String period,String state) {
        String code="E8_FLEET_SELECTION_"+period;
        return switch (state) {
            case "PREPARATORY_NOT_ADMITTED" -> new OpenCalculationPreflight.Gate(
                code,"NOT_ADMITTED",
                "O gestor vinculou referências EVR/PBEV ao projeto para "+period+
                    "; frota nacional, ratings, comparáveis e licenças continuam sujeitos "+
                    "a admissão normativa. Não há AFEC/MPG admitidos nem BE calculado.",
                "Conferir cobertura nacional, comparabilidade, licença e parecer de revisão",
                "GESTOR_FONTES/PREP/REV");
            case "STALE_NOT_ADMITTED" -> new OpenCalculationPreflight.Gate(
                code,"STALE",
                "A seleção EVR/PBEV de "+period+" depende de origem, licença, versão "+
                    "ou vínculo horário desatualizado; usar somente para histórico.",
                "Solicitar ao gestor de fontes nova seleção após revisar licenças e "+
                    "composição nacional","GESTOR_FONTES/PREP/REV");
            case "FLEET_SELECTION_MISSING" -> new OpenCalculationPreflight.Gate(
                code,"MISSING",
                "Nenhuma seleção de referências EVR/PBEV foi publicada para "+period+".",
                "Solicitar ao gestor autorizado uma seleção do mesmo ano, organização "+
                    "e período com cobertura e comparáveis avaliados","GESTOR_FONTES/PREP");
            default -> new OpenCalculationPreflight.Gate(
                code,"REVIEW_REQUIRED",
                "O estado da seleção EVR/PBEV de "+period+
                    " não está confirmado; não autorizar BE/ER.",
                "Conferir direitos, origem e histórico da seleção com o gestor","GESTOR_FONTES/REV");
        };
    }
}
