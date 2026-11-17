package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import jakarta.enterprise.context.ApplicationScoped;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static br.carbonflow.pilot.IntakeModel.*;
import static br.carbonflow.pilot.IntakeRules.*;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class ProjectIntake {
    record Proposal(Candidate candidate) {}
    record Review(Ref scopeReview, Ref vigency) {}
    record CommitInput(LocalDate date, Ref evidence) {}
    record OperationInput(LocalDate start, LocalDate creditStart, Ref evidence) {}
    record History(int version, Integer predecessor, UUID eventId) {}
    record Receipt(Snapshot snapshot, Integer predecessor, UUID eventId) {}
    record Status(String projectId, Snapshot snapshot, List<MeterView> meterWindows,
                  List<ReadingResult> readings, List<Reason> realUseBlockers) {}

    private final PilotDatabase database;
    private final PilotAudit audit;
    private final PilotCommands commands;
    private final IntakeCatalog catalog;
    private final ObjectMapper mapper;

    ProjectIntake(PilotDatabase database, PilotAudit audit, PilotCommands commands, IntakeCatalog catalog,
                  ObjectMapper mapper) {
        this.database = database;
        this.audit = audit;
        this.commands = commands;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    Status status(PilotAccess.Scope scope) {
        return database.read(connection -> {
            var snapshot = latest(connection, scope.project());
            var readings = query(connection, """
                    SELECT result FROM pilot_initial_reading
                    WHERE project_id = ? AND configuration_version <= ?
                    ORDER BY recorded_at DESC, origin_id LIMIT 50
                    """, row -> decode(row.getString(1), ReadingResult.class), scope.project(), snapshot.version());
            return new Status(scope.project(), snapshot, views(snapshot), readings, REAL_GATES);
        });
    }

    List<History> history(PilotAccess.Scope scope) {
        return database.read(connection -> query(connection, """
                SELECT version, predecessor, event_id FROM pilot_intake_revision
                WHERE project_id = ? ORDER BY version DESC LIMIT 100
                """, row -> new History(row.getInt(1), row.getObject(2, Integer.class), row.getObject(3, UUID.class)),
                scope.project()));
    }

    Receipt version(PilotAccess.Scope scope, int version) {
        var receipt = database.read(connection -> first(connection, """
                SELECT snapshot, predecessor, event_id FROM pilot_intake_revision WHERE project_id = ? AND version = ?
                """, row -> new Receipt(decode(row.getString(1), Snapshot.class), row.getObject(2, Integer.class),
                row.getObject(3, UUID.class)), scope.project(), version));
        if (receipt == null) throw PilotAccess.invisible();
        return receipt;
    }

    JsonNode propose(PilotAccess.Scope scope, String actor, String key, int expected, Proposal proposal) {
        return change(scope, actor, "TX-E3-01", key, expected, proposal, state -> {
            if (state.intake() != null && ("ENQUADRADO".equals(state.intake().state()) || !state.meters().isEmpty())) {
                throw transition("O enquadramento aprovado ou inventário em uso exige uma revisão formal posterior.");
            }
            if (proposal == null || proposal.candidate() == null) {
                throw invalid("PERFIL_INCOMPLETO", "candidate", "TX-E3-01", "Informe o candidato à triagem.");
            }
            var candidate = proposal.candidate();
            catalog.require(candidate.evidence(), "ESCOPO");
            var reasons = classify(candidate, catalog.examples().candidate().referenceActDate());
            return state.withIntake(new Intake(candidate, reasons.isEmpty() ? "TRIAGEM" : "FORA_DO_ESCOPO",
                    actor, null, null, null, reasons, List.of()));
        });
    }

    JsonNode review(PilotAccess.Scope scope, String actor, String key, int expected, Review review) {
        return change(scope, actor, "TX-E3-02", key, expected, review, state -> {
            var item = requireIntake(state);
            if ("ENQUADRADO".equals(item.state())) throw transition("O projeto já foi enquadrado.");
            if (item.preparedBy().equals(actor)) {
                throw new PilotProblem(403, "AUTOAPROVACAO_NEGADA", "actor", "TX-E3-02",
                        "A triagem deve ser conferida por uma pessoa distinta da preparação.");
            }
            if (review == null) throw invalid("PROVA_INSUFICIENTE", "review", "TX-E3-02", "Informe as evidências da revisão.");
            catalog.require(review.scopeReview(), "REVISAO");
            var vigency = catalog.require(review.vigency(), "VIGENCIA");
            var reasons = classify(item.candidate(), catalog.examples().candidate().referenceActDate());
            if (!reasons.isEmpty()) {
                return state.withIntake(new Intake(item.candidate(), "FORA_DO_ESCOPO", item.preparedBy(), actor,
                        review.scopeReview(), null, reasons, List.of()));
            }
            var gaps = new ArrayList<Reason>();
            var references = new ArrayList<UUID>();
            var sample = catalog.examples().candidate();
            if (!sample.profile().equals(item.candidate().profile()) || !sample.inventory().equals(item.candidate().inventory())) {
                gaps.add(new Reason("CONTORNO_A_COMPROVAR", "REG-E2-06/07",
                        "A evidência de escopo e baseline do catálogo cobre a configuração Planaltina da amostra."));
            }
            for (var type : RIGHTS) {
                var record = latestRecord(state, type);
                if (provedBy(record, item.candidate().referenceActDate())) {
                    references.add(record.id());
                } else {
                    gaps.add(new Reason("PENDENCIA_" + type, type == RecordType.EXCEDENTE ? "PEN-010" : "PEN-004",
                            "Falta evidência sintética aplicável de " + type + "."));
                }
            }
            Rules rules = gaps.isEmpty() ? new Rules(KIT, vigency.date(), item.candidate().referenceActDate(),
                    "02_fontes/MATRIZ_DE_VIGENCIA.md · " + vigency.ref().id() + "@" + vigency.ref().version(),
                    "VCS v5.0", "PD/MR 5.0B", "VM0038 v1.1", "VMD0049 v1.1 · BR/LDV/DCFC",
                    "VT0011 v1.0 + TOOL07 v7.0") : null;
            return state.withIntake(new Intake(item.candidate(), gaps.isEmpty() ? "ENQUADRADO" : "PENDENTE",
                    item.preparedBy(), actor, review.scopeReview(), rules, List.copyOf(gaps), List.copyOf(references)));
        });
    }

    JsonNode record(PilotAccess.Scope scope, String actor, String key, int expected, CaseInput input) {
        return change(scope, actor, "REGISTRAR_EVIDENCIA_INICIAL", key, expected, input, state -> {
            requireIntake(state);
            boolean conditional = input.type() == RecordType.FPIC || input.type() == RecordType.REPARTICAO;
            if (input.status() == null || input.effectiveDate() == null || !text(input.justification())
                    || (input.status() == RecordStatus.NAO_APLICAVEL_JUSTIFICADO && !conditional)) {
                throw invalid("REGISTRO_INCOMPLETO", "record", "CAM-E4-0207", "Informe data, conclusão e justificativa aplicáveis.");
            }
            if (input.evidence() != null || input.status() != RecordStatus.PENDENTE) {
                var proof = catalog.require(input.evidence(), input.type().name());
                requireProofDate(input.effectiveDate(), proof);
            }
            var existing = latestRecord(state, input.type());
            if (existing != null && existing.input().equals(input)) {
                throw transition("Este registro já consta como versão atual.");
            }
            return state.withRecord(new CaseEntry(UUID.randomUUID(), existing == null ? 1 : existing.version() + 1, actor, input));
        });
    }

    JsonNode commit(PilotAccess.Scope scope, String actor, String key, int expected, CommitInput input) {
        return change(scope, actor, "TX-E3-03", key, expected, input, state -> {
            requireEligible(state);
            if (state.commitment() != null) throw transition("O compromisso já foi registrado.");
            if (input == null) throw transition("Informe a data e a prova do compromisso.");
            requireProofDate(input.date(), catalog.require(input.evidence(), "COMPROMISSO"));
            var prior = new ArrayList<UUID>();
            for (var type : PRIOR) {
                var record = latestRecord(state, type);
                if (!provedBy(record, input.date().minusDays(1))) {
                    throw invalid("ANTERIORIDADE_PENDENTE", "commitment", "TX-E3-03",
                            type + " requer evidência anterior ao primeiro compromisso.");
                }
                prior.add(record.id());
            }
            for (var type : List.of(RecordType.FPIC, RecordType.REPARTICAO)) {
                var record = latestRecord(state, type);
                if (record == null || record.input().status() == RecordStatus.PENDENTE
                        || !record.input().effectiveDate().isBefore(input.date())) {
                    throw invalid("INCIDENCIA_PENDENTE", "commitment", "TX-E3-03",
                            "Registre o tratamento fundamentado de " + type + " antes do compromisso.");
                }
                prior.add(record.id());
            }
            return state.withCommitment(new Commitment(input.date(), input.evidence(), actor, List.copyOf(prior)));
        });
    }

    JsonNode reviseMeter(PilotAccess.Scope scope, String actor, String key, int expected, String circuit, MeterInput input) {
        return change(scope, actor, "REVISAR_MEDIDOR", key, expected, new MeterCommand(circuit, input), state -> {
            var intake = requireEligible(state);
            if (intake.candidate().inventory().ecCircuits().stream().noneMatch(item -> item.id().equals(circuit))) {
                throw PilotAccess.invisible();
            }
            if (input == null || !id(input.meterId()) || input.effectiveFrom() == null) {
                throw invalid("MEDIDOR_INCOMPLETO", "meter", "CAM-E4-0303", "Informe medidor e início de vigência.");
            }
            var install = catalog.forMeter(input.installation(), "INSTALACAO", circuit, input.meterId());
            requireProofDate(input.effectiveFrom().atZoneSameInstant(VintageWindows.ZONE).toLocalDate(), install);
            var last = state.meters().reversed().stream().filter(item -> item.circuit().equals(circuit)).findFirst();
            if (last.isPresent() && !last.get().details().effectiveFrom().isBefore(input.effectiveFrom())
                    && !completesEvidence(last.get(), input)) {
                throw invalid("VIGENCIA_SOBREPOSTA", "effectiveFrom", "CAM-E4-0303",
                        "A revisão deve iniciar depois da última revisão do circuito.");
            }
            // Leituras anteriores ficam vinculadas ao estado consultado no momento da pré-checagem.
            if (input.diagram() != null) {
                var diagram = catalog.forMeter(input.diagram(), "DIAGRAMA", circuit, input.meterId());
                if (input.upstream() != Boolean.parseBoolean(diagram.details().get("upstream"))
                        || input.exclusive() != Boolean.parseBoolean(diagram.details().get("exclusive"))) {
                    throw invalid("DIAGRAMA_DIVERGENTE", "diagram", "REG-E2-11", "O contorno informado diverge da evidência.");
                }
            }
            OffsetDateTime from = null;
            OffsetDateTime until = null;
            if (input.calibration() != null) {
                var calibration = catalog.forMeter(input.calibration(), "CALIBRACAO", circuit, input.meterId());
                from = OffsetDateTime.parse(calibration.details().get("from_"));
                until = OffsetDateTime.parse(calibration.details().get("untilExclusive"));
            }
            Integer previous = last.map(MeterRevision::revision).orElse(null);
            return state.withMeter(new MeterRevision(circuit, previous == null ? 1 : previous + 1,
                    previous, actor, input, from, until));
        });
    }

    private record MeterCommand(String circuit, MeterInput input) {}

    private static boolean completesEvidence(MeterRevision previous, MeterInput input) {
        var prior = previous.details();
        return prior.effectiveFrom().isEqual(input.effectiveFrom()) && prior.meterId().equals(input.meterId())
                && (!segregated(previous) || previous.calibratedFrom() == null)
                && input.upstream() && input.exclusive() && input.diagram() != null && input.calibration() != null;
    }

    JsonNode operate(PilotAccess.Scope scope, String actor, String key, int expected, OperationInput input) {
        return change(scope, actor, "TX-E3-04", key, expected, input, state -> {
            var intake = requireEligible(state);
            if (state.commitment() == null || state.operation() != null) {
                throw transition("Registre o compromisso antes do comissionamento. A operação só é registrada uma vez.");
            }
            if (input == null || input.start() == null || !input.start().equals(input.creditStart())
                    || !input.start().isAfter(state.commitment().date())) {
                throw invalid("MARCO_INVALIDO", "operation", "REG-E2-05",
                        "No cenário, o início do crédito coincide com a primeira operação e sucede o compromisso.");
            }
            requireProofDate(input.start(), catalog.require(input.evidence(), "OPERACAO"));
            var rights = latestRecord(state, RecordType.DIREITOS);
            if (!provedBy(rights, input.start())
                    || !input.start().isBefore(LocalDate.parse(catalog.require(rights.input().evidence(), "DIREITOS")
                    .details().get("validUntilExclusive")))) {
                throw invalid("DIREITOS_PENDENTES", "operation", "PEN-004", "A operação exige direitos vigentes.");
            }
            var from = input.start().atStartOfDay(VintageWindows.ZONE).toOffsetDateTime();
            var active = new ArrayList<String>();
            for (var circuit : intake.candidate().inventory().ecCircuits()) {
                var view = covering(state, circuit.id(), from, from.plusNanos(1));
                if (view == null || !segregated(view.revision()) || !calibrated(view.revision(), from, from.plusNanos(1))) {
                    throw invalid("METROLOGIA_PENDENTE", "operation", "REG-E2-11",
                            "Falta medição segregada e calibrada no início da operação para " + circuit.id() + ".");
                }
                active.add(circuit.id() + "@" + view.revision().revision());
            }
            return state.withOperation(new Operation(input.start(), input.creditStart(), input.creditStart().plusYears(5).minusDays(1),
                    input.evidence(), actor, List.copyOf(active)));
        });
    }

    JsonNode checkReading(PilotAccess.Scope scope, String actor, String key, Reading reading) {
        if (reading == null || !id(reading.originId()) || !id(reading.circuit()) || !id(reading.meterId())
                || !decimal(reading.kwh())) {
            throw invalid("LEITURA_INVALIDA", "reading", "REG-E2-11/19",
                    "Informe IDs sintéticos, início e fim com deslocamento e kWh decimal.");
        }
        if (reading.start() == null || reading.endExclusive() == null) throw invalidReadingInterval();
        OffsetDateTime start;
        OffsetDateTime end;
        try {
            start = OffsetDateTime.parse(reading.start());
            end = OffsetDateTime.parse(reading.endExclusive());
        } catch (java.time.format.DateTimeParseException exception) {
            throw invalidReadingInterval();
        }
        if (!start.isBefore(end)) throw invalidReadingInterval();
        return commands.execute(scope, actor, "PRECHECAR_LEITURA_EC", key, audit.encode(reading),
                reading.originId(), connection -> {
                    var state = lockAndLoad(connection, scope.project());
                    requireIntake(state);
                    var reasons = new ArrayList<Reason>();
                    if (state.operation() == null) {
                        reasons.add(new Reason("OPERACAO_PENDENTE", "TX-E3-04", "A operação inicial aguarda comissionamento."));
                    } else {
                        checkPeriod(state.operation().creditStart(), start, end, reasons);
                    }
                    if (reading.evidence() == null) {
                        reasons.add(new Reason("ORIGEM_PENDENTE", "REG-E2-11", "Falta a referência da leitura de origem."));
                    } else {
                        try {
                            catalog.forMeter(reading.evidence(), "LEITURA", reading.circuit(), reading.meterId());
                        } catch (PilotProblem problem) {
                            reasons.add(new Reason(problem.code, problem.rule, problem.getMessage()));
                        }
                    }
                    var view = covering(state, reading.circuit(), start, end);
                    Integer matched = null;
                    if (view == null || !view.revision().details().meterId().equals(reading.meterId())) {
                        reasons.add(new Reason("REVISAO_SEM_COBERTURA", "CAM-E4-0303",
                                "O medidor e sua revisão não cobrem todo o intervalo. Confira a troca ou a origem."));
                    } else {
                        var revision = view.revision();
                        matched = revision.revision();
                        if (!segregated(revision)) {
                            reasons.add(new Reason("EC_NAO_SEGREGADA", "REG-E2-11", "Falta evidência de EC exclusiva a montante."));
                        }
                        if (!calibrated(revision, start, end)) {
                            reasons.add(new Reason("CALIBRACAO_SEM_COBERTURA", "CAM-E4-0304",
                                    "A calibração não cobre todo o intervalo original."));
                        }
                    }
                    String status = reasons.isEmpty() ? "COBERTURA_CONFERIDA" : "PENDENTE";
                    UUID event = audit.record(connection, scope.project(), actor, "PRECHECAR_LEITURA_EC", "PERMITIDO",
                            reading.originId(), Map.of("state", status, "configurationVersion", state.version(), "reasons", reasons));
                    var result = new ReadingResult(reading, status, List.copyOf(reasons), state.version(), matched, event);
                    int inserted = update(connection, """
                            INSERT INTO pilot_initial_reading (project_id, origin_id, configuration_version, event_id, reading, result)
                            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb) ON CONFLICT DO NOTHING
                            """, scope.project(), reading.originId(), state.version(), event, audit.encode(reading), audit.encode(result));
                    if (inserted == 0) throw new PilotProblem(409, "LEITURA_JA_REGISTRADA", "originId", "REG-E2-18",
                            "A leitura de origem já foi registrada e permanece íntegra.");
                    return result;
                });
    }

    private static PilotProblem invalidReadingInterval() {
        return invalid("INTERVALO_INVALIDO", "reading", "REG-E2-19",
                "Informe instantes com deslocamento e fim exclusivo posterior ao início.");
    }

    private void checkPeriod(LocalDate creditStart, OffsetDateTime start, OffsetDateTime end, List<Reason> reasons) {
        var windows = new ArrayList<VintageWindows.Window>();
        for (int year = 0; year < 2; year++) {
            windows.addAll(VintageWindows.split(new VintageWindows.Window(
                    creditStart.plusYears(year), creditStart.plusYears(year + 1))));
        }
        if (windows.stream().noneMatch(window -> window.contains(start) && !end.isAfter(window.ending()))) {
            reasons.add(new Reason("LEITURA_SEM_SEGMENTACAO", "REG-E2-19",
                    "O intervalo precisa estar inteiro em um único recorte de período e vintage."));
        }
    }

    private JsonNode change(PilotAccess.Scope scope, String actor, String action, String key, int expected,
                            Object command, UnaryOperator<Snapshot> apply) {
        String fingerprint = audit.encode(new Fingerprint(expected, command));
        return commands.execute(scope, actor, action, key, fingerprint, scope.project(), connection -> {
            var current = lockAndLoad(connection, scope.project());
            PilotCommands.requireVersion(expected, current.version());
            var next = apply.apply(current).next();
            UUID event = audit.record(connection, scope.project(), actor, action, "PERMITIDO", scope.project(),
                    Map.of("before", current.version(), "after", next.version(), "sample", catalog.examples().version(),
                            "sampleSha256", catalog.examples().sha256(), "supplementSha256", catalog.examples().supplementSha256()));
            Integer predecessor = current.version() == 0 ? null : current.version();
            update(connection, """
                    INSERT INTO pilot_intake_revision (project_id, version, predecessor, event_id, snapshot)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    """, scope.project(), next.version(), predecessor, event, audit.encode(next));
            return new Receipt(next, predecessor, event);
        });
    }

    private record Fingerprint(int version, Object command) {}

    Snapshot lockAndLoad(Connection connection, String project) throws SQLException {
        lockProject(connection, project);
        return latest(connection, project);
    }

    void lockProject(Connection connection, String project) throws SQLException {
        first(connection, "SELECT id FROM pilot_project WHERE id = ? FOR UPDATE", row -> row.getString(1), project);
    }

    Snapshot latest(Connection connection, String project) throws SQLException {
        var snapshot = first(connection, """
                SELECT snapshot FROM pilot_intake_revision WHERE project_id = ? ORDER BY version DESC LIMIT 1
                """, row -> decode(row.getString(1), Snapshot.class), project);
        return snapshot == null ? Snapshot.empty() : snapshot;
    }

    private static Intake requireIntake(Snapshot state) {
        if (state.intake() == null) throw transition("Registre a triagem primeiro.");
        return state.intake();
    }

    private static Intake requireEligible(Snapshot state) {
        var entry = requireIntake(state);
        if (!"ENQUADRADO".equals(entry.state())) throw transition("O enquadramento sintético precisa estar aprovado.");
        return entry;
    }

    private static CaseEntry latestRecord(Snapshot state, RecordType type) {
        return state.records().reversed().stream().filter(record -> record.input().type() == type).findFirst().orElse(null);
    }

    private static boolean provedBy(CaseEntry record, LocalDate cutoff) {
        return record != null && record.input().status() == RecordStatus.COMPROVADO_SIMULACAO
                && !record.input().effectiveDate().isAfter(cutoff);
    }

    private static void requireProofDate(LocalDate date, Evidence evidence) {
        if (!evidence.date().equals(date)) {
            throw invalid("DATA_DIVERGENTE_DA_PROVA", "effectiveDate", "CAM-E4-0203/0207",
                    "A data efetiva deve corresponder à evidência versionada.");
        }
    }

    private static PilotProblem transition(String message) {
        return new PilotProblem(409, "TRANSICAO_INVALIDA", "action", "TX-E3-01/04", message);
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao recuperar a versão de enquadramento", e);
        }
    }
}
