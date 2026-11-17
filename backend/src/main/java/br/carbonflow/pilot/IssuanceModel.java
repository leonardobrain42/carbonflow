package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import static br.carbonflow.pilot.ListingEvidence.Capture;
import static br.carbonflow.pilot.ListingEvidence.Proof;

final class IssuanceModel {
    static final String UNIT = "UNIDADE_FICTICIA";
    static final String POWER = "SIM-PODER-EMISSAO";
    static final String ACT_NAMESPACE = "SIM-VERRA-EMISSAO";
    static final String REGISTRY = "SIM-REGISTRY";
    static final List<String> ACCOUNTS = List.of("SIM-CONTA-01", "SIM-CONTA-02");
    static final Map<String,String> PROOFS = Map.ofEntries(
            Map.entry("ATIVACAO", "SIM-ATIVA-VERIFICACAO"),
            Map.entry("POSICAO", "SIM-PROVA-POSICAO"),
            Map.entry("TX-E3-49", "SIM-RECIBO-EMISSAO"),
            Map.entry("TX-E3-50", "SIM-PROVA-COBRANCA-EMISSAO"),
            Map.entry("TX-E3-51", "SIM-PROVA-QUITACAO-EMISSAO"),
            Map.entry("TX-E3-52", "SIM-DEPOSITO-01"),
            Map.entry("TX-E3-55", "SIM-PROVA-RETOMADA-EMISSAO"),
            Map.entry("TX-E3-56", "SIM-PROVA-ENCERRAMENTO-EMISSAO"),
            Map.entry("TX-E3-57", "SIM-PROVA-QUITACAO-OFICIAL"),
            Map.entry("AJUSTE", "SIM-PROVA-AJUSTE-01"),
            Map.entry("LIBERAR_REMANESCENTE", "SIM-PROVA-ENCERRAR-REMANESCENTE"));

    enum Reflection { REFLETIDA, NAO_REFLETIDA, INCERTA }

    record Vintage(LocalDate start, LocalDate endExclusive) {}
    record ActivationInput(String registryId, String approvalId, OffsetDateTime effectiveAt, boolean active, Proof proof) {}
    record Activation(UUID verification, int version, String kind, String registryId, DocumentRules.ClosureRef closure,
                      String approvalId, boolean active, List<Vintage> cuts, Capture capture) {}
    record Pending(String reference, UUID issuance, BigDecimal amount, Reflection status) {}
    record PositionInput(Vintage vintage, String unit, BigDecimal official, List<Pending> pending, OffsetDateTime asOf,
                         Integer order, Proof proof) {}
    record Position(UUID id, UUID verification, Vintage vintage, BigDecimal official, List<Pending> pending,
                    OffsetDateTime asOf, Integer order, Capture capture, boolean historical) {}
    record Balance(Position position, BigDecimal unreflected, BigDecimal available, String hold) {}
    record PositionResult(Position position, Balance balance, String conflict) {}
    record Requested(Vintage vintage, UUID position, BigDecimal amount) {}
    record Create(UUID verification, UUID predecessor, String account, String unit, List<Requested> lines, OffsetDateTime effectiveAt,
                  @JsonInclude(JsonInclude.Include.NON_NULL) String recoveryProtocol) {}
    record ChannelInput(ListingWorkflow.Channel route, OffsetDateTime checkedAt, OffsetDateTime powerUntilExclusive,
                        IntakeModel.Ref source, IntakeModel.Ref power) {}
    record Check(ChannelInput input, UUID eventId) {}
    record Line(Vintage vintage, BigDecimal amount) {}
    record Action(String transitionId, String mode, OffsetDateTime effectiveAt, String account, Proof proof,
                  String target, String referenceAct, String reason, List<Line> lines,
                  BigDecimal feeAmount, String currency, IntakeModel.Ref support, UUID comparison,
                  PositionInput newPosition, ListingWorkflow.Channel captureChannel) {}
    record Deposit(Capture capture, List<Line> lines) {}
    record Adjustment(Capture capture, String corrects, Line line, UUID newPosition) {}
    record Invoice(Capture capture, BigDecimal amount, String currency) {}
    record Match(Vintage vintage, UUID position, BigDecimal requested, BigDecimal deposited, BigDecimal reserved,
                 BigDecimal official, BigDecimal available) {}
    record Comparison(UUID id, int issuanceVersion, String preparedBy, UUID eventId, OffsetDateTime effectiveAt,
                      List<Match> lines, IntakeModel.Ref resolution) {}
    record Incident(UUID id, String code, String reason, String fingerprint, UUID reopen, String responsible,
                    UUID eventId, UUID resolutionEvent) {}
    record Issuance(UUID id, int version, UUID verification, String state, String externalTarget, String account, UUID predecessor,
                    List<Requested> lines, Check check, Capture protocol, Invoice invoice, Capture payment,
                    List<Deposit> deposits, List<Adjustment> adjustments, OffsetDateTime releasedAt, List<Incident> incidents,
                    Comparison comparison, UUID reviewEvent, OffsetDateTime lastAt, UUID eventId) {}
    record Snapshot(Issuance attempt, List<Line> netDeposited, String unit, String conflict) {}
    record Verification(UUID id, String kind, String requestTarget, String approvalId, DocumentRules.ClosureRef closure,
                        List<Vintage> cuts, Activation activation, List<Balance> balances, boolean closureEligible) {}

    static final Comparator<Vintage> BY_VINTAGE = Comparator.comparing(Vintage::start).thenComparing(Vintage::endExclusive);

    static OffsetDateTime officialTime(OffsetDateTime value) {
        if (value == null) throw invalid("DATA_AUSENTE", "Informe a data efetiva da prova de ensaio.");
        return value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    static boolean valid(Vintage cut) {
        return cut != null && cut.start() != null && cut.endExclusive() != null && cut.endExclusive().isAfter(cut.start());
    }

    static BigDecimal decimal(BigDecimal amount, boolean signed) {
        require(amount != null && amount.scale() <= 9 && (long)amount.precision()-amount.scale() <= 15
                        && (signed || amount.signum() >= 0), "QUANTIDADE_INVALIDA", "Informe decimal válido e respeite o sinal permitido.");
        return amount.stripTrailingZeros();
    }

    static List<Line> canonical(List<Line> lines, boolean signed) {
        require(lines != null && !lines.isEmpty() && lines.size() <= 10, "LINHAS_INVALIDAS", "Informe os recortes e quantidades do ato.");
        var keys = new HashSet<Vintage>();
        for (var line : lines) {
            require(line != null && valid(line.vintage()) && keys.add(line.vintage()),
                    "VINTAGE_INVALIDO", "Informe cada recorte exato uma vez.");
        }
        return lines.stream().map(line -> new Line(line.vintage(), decimal(line.amount(), signed)))
                .sorted(Comparator.comparing(Line::vintage, BY_VINTAGE)).toList();
    }

    static IntakeModel.Ref ref(String id) { return new IntakeModel.Ref(id, 1); }

    static void proof(String step, Proof input) {
        if (input == null || !IntakeRules.id(input.officialId()) || !IntakeRules.id(input.target())
                || !ref(PROOFS.get(step)).equals(input.evidence())) {
            throw invalid("PROVA_INSUFICIENTE", "Vincule o identificador, o alvo e a prova de ensaio aplicável ao ato.");
        }
    }

    static void require(boolean value, String code, String message) {
        if (!value) throw new PilotProblem(409, code, "issuance", "OP-E6-12", message);
    }

    static PilotProblem invalid(String code, String message) {
        return PilotProblem.invalid(code, "issuance", "OP-E6-12", message);
    }
}
