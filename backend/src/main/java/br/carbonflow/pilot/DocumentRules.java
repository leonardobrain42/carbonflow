package br.carbonflow.pilot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.carbonflow.pilot.IntakeModel.RecordType;

final class DocumentRules {
    static final String EDITION = "5.0B";
    static final String LANGUAGE = "en";
    static final String VERSION = "e8-007@1";
    static final String PD_SHA = "47922ad388188d628e415945d9977938cc12d2ed3d298a677a50bdaa980138cc";
    static final String MR_SHA = "2ae00a038045938bc83787048b6a5376ee4609ed778951955bbe2fc30b50911d";

    enum Act {
        DESENVOLVIMENTO("PKG-E4-01", "Indicative project description"),
        LISTAGEM_VALIDACAO("PKG-E4-02", "Project description for validation listing"),
        REGISTRO("PKG-E4-03", "Project description for registration"),
        VERIFICACAO("PKG-E4-04", "Monitoring report");

        private final String rule;
        private final String title;
        Act(String rule, String title) { this.rule = rule; this.title = title; }
        String rule() { return rule; }
        String title() { return title; }
        String template() { return this == VERIFICACAO ? "SRC-007_MR_v5.0B.docx" : "SRC-007_PD_v5.0B.docx"; }
        String sourceSha256() { return this == VERIFICACAO ? MR_SHA : PD_SHA; }
    }

    enum Piece {
        COMMUNICATIONS_AGREEMENT("Communications agreement", "PP", false),
        TRANSLATION_PROOF("Versioned translation proof", "PP", false),
        MAPA("Synthetic project boundary map", "PREP", true),
        LISTING_REPRESENTATION("Listing representation", "PP", false),
        CONTRATO_VVB("VVB appointment", "PP", false),
        REGISTRATION_REPRESENTATION("Registration representation", "PP", false),
        VALIDATION_REPORT("Validation report", "VVB", false),
        VALIDATION_REPRESENTATION("Validation representation", "VVB", false),
        ISSUANCE_REPRESENTATION("Issuance representation", "PP", false),
        VERIFICATION_REPORT("Verification report", "VVB", false),
        VERIFICATION_REPRESENTATION("Verification representation", "VVB", false),
        VISITA("Site visit or applicable risk assessment", "VVB", false),
        ESG_MONITORING("ESG monitoring", "OP", false),
        RESPOSTA_RODADA("Response to a review finding", "PP", false);

        private final String title;
        private final String issuer;
        private final boolean publicPiece;
        Piece(String title, String issuer, boolean publicPiece) {
            this.title = title; this.issuer = issuer; this.publicPiece = publicPiece;
        }
        String title() { return title; }
        String issuer() { return issuer; }
        boolean publicPiece() { return publicPiece; }

        IntakeModel.Ref reference(String period) {
            String suffix = period != null && List.of(ISSUANCE_REPRESENTATION, VERIFICATION_REPORT,
                    VERIFICATION_REPRESENTATION, VISITA, ESG_MONITORING).contains(this) ? "-" + period : "";
            return new IntakeModel.Ref("SIM-" + name().replace('_', '-') + suffix, 1);
        }
    }

    enum Condition { COMUNICACAO, TRADUCAO, GEODADOS_MR, REPRESENTACAO_LISTAGEM }
    enum Incidence { INCIDE, NAO_INCIDE, INCERTO }

    record ConditionInput(Condition item, Incidence incidence, String reason) {}
    record PieceInput(Piece item, IntakeModel.Ref reference) {}
    record Ref(UUID id, int revision) {}
    record ClosureRef(String period, int revision) {}
    record Input(Act act, LocalDate actDate, String model, String language, ClosureRef closure, Ref validatedPd,
                 Ref predecessor, String reason, List<PieceInput> pieces, List<ConditionInput> conditions, String privateNote) {}
    record Finding(String code, String item, String message) {}
    record PieceOption(Piece item, String title, String issuer, boolean publicPiece, IntakeModel.Ref reference) {}
    record Checklist(Act act, String rule, String template, String model, String language, List<PieceOption> pieces,
                     List<Condition> conditions) {}

    static List<Piece> required(Act act, boolean successor) {
        List<Piece> pieces = switch (act) {
            case DESENVOLVIMENTO -> List.of();
            case LISTAGEM_VALIDACAO -> List.of(Piece.MAPA, Piece.CONTRATO_VVB);
            case REGISTRO -> List.of(Piece.MAPA, Piece.CONTRATO_VVB, Piece.REGISTRATION_REPRESENTATION, Piece.VALIDATION_REPORT,
                    Piece.VALIDATION_REPRESENTATION);
            case VERIFICACAO -> List.of(Piece.ISSUANCE_REPRESENTATION, Piece.VERIFICATION_REPORT, Piece.VERIFICATION_REPRESENTATION,
                    Piece.VISITA, Piece.ESG_MONITORING);
        };
        if (!successor) return pieces;
        var all = new ArrayList<>(pieces);
        all.add(Piece.RESPOSTA_RODADA);
        return List.copyOf(all);
    }

    static List<Condition> conditions(Act act) {
        return switch (act) {
            case DESENVOLVIMENTO, LISTAGEM_VALIDACAO ->
                    List.of(Condition.COMUNICACAO, Condition.TRADUCAO, Condition.REPRESENTACAO_LISTAGEM);
            case REGISTRO -> List.of(Condition.COMUNICACAO, Condition.TRADUCAO);
            case VERIFICACAO -> List.of(Condition.COMUNICACAO, Condition.TRADUCAO, Condition.GEODADOS_MR);
        };
    }

    static Checklist checklist(Act act, String period, boolean successor) {
        var items = new ArrayList<>(required(act, successor));
        if (conditions(act).contains(Condition.REPRESENTACAO_LISTAGEM)) items.add(Piece.LISTING_REPRESENTATION);
        if (conditions(act).contains(Condition.GEODADOS_MR)) items.add(Piece.MAPA);
        items.add(Piece.COMMUNICATIONS_AGREEMENT);
        items.add(Piece.TRANSLATION_PROOF);
        return new Checklist(act, act.rule(), act.template(), EDITION, LANGUAGE, items.stream()
                .map(item -> new PieceOption(item, item.title(), item.issuer(), item.publicPiece(), item.reference(period))).toList(),
                conditions(act));
    }

    static List<RecordType> intakeRecords(Act act) {
        var common = EnumSet.of(RecordType.DIREITOS, RecordType.EXCEDENTE, RecordType.SOBREPOSICAO, RecordType.ENGAJAMENTO,
                RecordType.MECANISMO, RecordType.ESG, RecordType.FPIC, RecordType.REPARTICAO);
        if (act != Act.DESENVOLVIMENTO) common.add(RecordType.ODS);
        return List.copyOf(common);
    }

    static List<Finding> check(Input input, Map<RecordType, IntakeModel.CaseEntry> records, boolean hasCommitment,
                               boolean hasOperation, boolean closureEligible, boolean pdValid) {
        var findings = new ArrayList<Finding>();
        for (var kind : intakeRecords(input.act())) {
            var item = records.get(kind);
            boolean justified = kind == RecordType.FPIC || kind == RecordType.REPARTICAO;
            if (item == null || item.input().effectiveDate().isAfter(input.actDate())
                    || !("COMPROVADO_SIMULACAO".equals(item.input().status().name())
                    || justified && item.input().status() == IntakeModel.RecordStatus.NAO_APLICAVEL_JUSTIFICADO
                    && IntakeRules.text(item.input().justification()))
                    || item.input().evidence() == null) {
                findings.add(gap("PROVA_FICHA_AUSENTE", kind.name(), "Confira a evidência aplicável e sua data na ficha."));
            }
        }
        if (!hasCommitment || input.act() != Act.DESENVOLVIMENTO && !hasOperation) {
            findings.add(gap("OPERACAO_PENDENTE", "FICHA", "Confira o compromisso e, para o pacote completo, o comissionamento na data do ato."));
        }
        for (var item : required(input.act(), input.predecessor() != null)) requirePiece(input, findings, item);
        for (var condition : conditions(input.act())) {
            var item = input.conditions().stream().filter(entry -> entry.item() == condition).findFirst().orElse(null);
            if (item == null || item.incidence() == Incidence.INCERTO || !IntakeRules.text(item.reason())) {
                findings.add(gap("INCIDENCIA_PENDENTE", condition.name(), "Registre a incidência com justificativa."));
            } else if (item.incidence() == Incidence.INCIDE) {
                switch (condition) {
                    case GEODADOS_MR -> requirePiece(input, findings, Piece.MAPA);
                    case REPRESENTACAO_LISTAGEM -> requirePiece(input, findings, Piece.LISTING_REPRESENTATION);
                    case COMUNICACAO -> requirePiece(input, findings, Piece.COMMUNICATIONS_AGREEMENT);
                    case TRADUCAO -> requirePiece(input, findings, Piece.TRANSLATION_PROOF);
                }
            }
        }
        if (input.act() == Act.VERIFICACAO) {
            if (!closureEligible) findings.add(gap("FECHAMENTO_INAPTO", "MEMORIA", "Selecione o fechamento vigente do período."));
            if (!pdValid) findings.add(gap("PD_AUSENTE", "PD_VALIDADO", "Vincule a versão congelada do PD de registro com prova VVB simulada."));
        }
        return List.copyOf(findings);
    }

    private static void requirePiece(Input input, List<Finding> findings, Piece piece) {
        if (input.pieces().stream().noneMatch(item -> item.item() == piece)) {
            findings.add(gap("PECA_OBRIGATORIA_AUSENTE", piece.name(), "Inclua a peça versionada do checklist."));
        }
    }

    static Finding gap(String code, String item, String message) { return new Finding(code, item, message); }

    static String purpose(Input input) {
        if (input.act() != Act.VERIFICACAO) return "AUDITORIA_VAL";
        return "M1".equals(input.closure().period()) ? "AUDITORIA_V1" : "AUDITORIA_V2";
    }

    private DocumentRules() {}
}
