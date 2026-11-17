package br.carbonflow.pilot;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class MonitoringPeriods {
    record Proposal(String id, LocalDate start, LocalDate endExclusive, String reason) {}
    record Action(String transitionId, OffsetDateTime effectiveAt, String reason, EnergyImportModel.Ref run,
                  UUID recoveryArtifact) {}
    record DependentInput(String kind, String reference, int version, String status, IntakeModel.Ref proof) {}
    record Dependent(DependentInput input, String recordedBy, UUID event, String sha256) {}
    record Impact(int affectedRevision, List<Dependent> dependents, UUID divergenceId, String state) {}
    record Period(String id, int revision, int version, String state, LocalDate start, LocalDate endExclusive,
                  String zone, int projectYear, OffsetDateTime effectiveAt, String actor, String reason, UUID eventId,
                  Integer predecessor, String closureSha256, PeriodCoverage.Report coverage, Impact impact) {
        VintageWindows.Window window() { return new VintageWindows.Window(start, endExclusive); }
    }
    record ClosureBytes(byte[] bytes, String sha256, boolean eligible) {}
    private record Run(int version, String state, String author, UUID selection, int configuration, byte[] snapshot,
                       String sha256, JsonNode result, UUID reviewed, UUID completed) {}

    private final PilotDatabase database;
    private final PilotAudit audit;
    private final PilotCommands commands;
    private final ProjectIntake intake;
    private final ObjectMapper mapper;
    private final IssuanceWorkflow issuance;

    MonitoringPeriods(PilotDatabase database, PilotAudit audit, PilotCommands commands, ProjectIntake intake, ObjectMapper mapper,
                      IssuanceWorkflow issuance) {
        this.database = database;
        this.audit = audit;
        this.commands = commands;
        this.intake = intake;
        this.mapper = mapper;
        this.issuance = issuance;
    }

    List<Period> list(String project) {
        return database.read(connection -> query(connection, """
                SELECT DISTINCT ON (period) snapshot FROM pilot_period_state WHERE project_id=?
                ORDER BY period,version DESC
                """, row -> decode(row.getString(1)), project));
    }

    List<Period> history(String project, String period) {
        return database.read(connection -> query(connection, """
                SELECT snapshot FROM pilot_period_state WHERE project_id=? AND period=? ORDER BY version DESC
                """, row -> decode(row.getString(1)), project, period));
    }

    JsonNode propose(PilotAccess.Scope scope, String actor, String key, int expected, Proposal input) {
        if (input == null || !EnergyImports.validPeriod(input.id()) || input.start() == null || input.endExclusive() == null
                || !IntakeRules.text(input.reason())) {
            throw invalid("PERIODO_INVALIDO", "Informe período, início, fim exclusivo e motivo.");
        }
        return commands.execute(scope, actor, "PROPOR_PERIODO", key, audit.encode(List.of(expected, input)), input.id(), connection -> {
            String project = scope.project();
            var config = intake.lockAndLoad(connection, project);
            var current = head(connection, project, input.id());
            PilotCommands.requireVersion(expected, current == null ? 0 : current.version());
            require(current == null, "PERIODO_EXISTENTE", "A janela já foi proposta. Consulte o seu estado.");
            // O calendário do cenário pode ser preparado antes do comissionamento.
            var anchor = config.operation() == null ? LocalDate.of(2027, 10, 1) : config.operation().creditStart();
            int year = "M1".equals(input.id()) ? 1 : 2;
            var start = anchor.plusYears(year - 1);
            if (!start.equals(input.start()) || !start.plusYears(1).equals(input.endExclusive())) {
                throw invalid("JANELA_DIVERGENTE", "Use as datas anuais do cenário com fim exclusivo.");
            }
            UUID event = audit.record(connection, project, actor, "PROPOR_PERIODO", "PERMITIDO", input.id(), input);
            return save(connection, project, new Period(input.id(), 1, 1, "PREVISTO", start, input.endExclusive(),
                    VintageWindows.ZONE.getId(), year, null, actor, input.reason(), event, null, null, null, null));
        });
    }

    JsonNode act(PilotAccess.Scope scope, String actor, String key, String period, int expected, Action action) {
        if (action.effectiveAt() == null || !IntakeRules.text(action.reason())) {
            throw invalid("ACAO_INCOMPLETA", "Informe o instante efetivo da simulação e o motivo.");
        }
        return commands.execute(scope, actor, action.transitionId(), key, audit.encode(List.of(period, expected, action)), period, connection -> {
            String project = scope.project();
            var config = intake.lockAndLoad(connection, project);
            var old = head(connection, project, period);
            if (old == null) throw PilotAccess.invisible();
            PilotCommands.requireVersion(expected, old.version());
            var at = action.effectiveAt().atZoneSameInstant(VintageWindows.ZONE).toOffsetDateTime();
            require(old.effectiveAt() == null || !at.isBefore(old.effectiveAt()), "INSTANTE_RETROATIVO",
                    "O marco efetivo deve suceder ou coincidir com o último deste período.");
            var window = old.window();
            var op = config.operation();
            require(op != null && op.creditStart().plusYears(old.projectYear() - 1L).equals(old.start())
                    && !old.endExclusive().minusDays(1).isAfter(op.creditEndInclusive()), "OPERACAO_PENDENTE",
                    "Confirme a operação aplicável à janela na ficha.");
            return switch (action.transitionId()) {
                case "TX-E3-41" -> {
                    state(old, "PREVISTO");
                    require(window.contains(at), "FORA_DA_JANELA", "Abra a coleta dentro da própria janela.");
                    yield advance(connection, project, actor, old, action, at, "COLETANDO", null, null, null, op.evidence());
                }
                case "TX-E3-42" -> {
                    state(old, "COLETANDO");
                    require(!at.isBefore(window.ending()), "JANELA_EM_CURSO", "A coleta só encerra no fim exclusivo ou após ele.");
                    yield advance(connection, project, actor, old, action, at, "EM_CONFERENCIA", null, null, null, op.evidence());
                }
                case "TX-E3-43" -> {
                    state(old, "EM_CONFERENCIA");
                    var report = inspect(connection, project, old, config, action.run(), false);
                    require(!report.complete(), "COBERTURA_COMPLETA", "A memória e as leituras avaliadas não apresentam lacunas.");
                    yield advance(connection, project, actor, old, action, at, "COM_LACUNAS", report, null, null, action.run());
                }
                case "TX-E3-44" -> {
                    state(old, "COM_LACUNAS");
                    require(recovery(connection, project, old, action.recoveryArtifact()), "RECUPERACAO_SEM_PROVA",
                            "Informe o artefato de recuperação entregue pelo OP e admitido depois da classificação das lacunas.");
                    yield advance(connection, project, actor, old, action, at, "EM_CONFERENCIA", null, null, null,
                            action.recoveryArtifact());
                }
                case "TX-E3-45" -> close(connection, project, actor, old, action, at, config);
                case "TX-E3-46" -> reopen(connection, project, actor, old, action, at);
                default -> throw invalid("ACAO_INVALIDA", "Escolha a transição de monitoramento.");
            };
        });
    }

    PeriodCoverage.Report assess(String project, String period, EnergyImportModel.Ref run) {
        return database.transaction(connection -> {
            var config = intake.lockAndLoad(connection, project);
            var current = head(connection, project, period);
            if (current == null) throw PilotAccess.invisible();
            return inspect(connection, project, current, config, run, false);
        });
    }

    private PeriodCoverage.Report inspect(Connection connection, String project, Period period, IntakeModel.Snapshot config,
                                          EnergyImportModel.Ref reference, boolean strict) throws SQLException {
        var run = run(connection, project, period, reference);
        var coverage = PeriodCoverage.assess(connection, project, period.id(), run.selection(), period.window(), config);
        var findings = new ArrayList<>(coverage.examples());
        int issues = coverage.findings();
        if (!"CALCULADA".equals(run.state())) {
            findings.add(new PeriodCoverage.Finding("MEMORIA_INCOMPLETA", null, null, null, null));
            issues++;
        }
        if (run.configuration() != config.version() || !isCurrent(connection, project, period.id(), run.selection())) {
            findings.add(new PeriodCoverage.Finding("SELECAO_OU_CONFIGURACAO_DESATUALIZADA", null, null, null, null));
            issues++;
        }
        if (strict) require(issues == 0, "FECHAMENTO_COM_LACUNAS",
                "A conferência encontrou " + issues + " pendências. Consulte a cobertura e gere memória com a seleção atual.");
        return new PeriodCoverage.Report(issues == 0, issues, List.copyOf(findings), coverage.expectedChannels());
    }

    private Period close(Connection connection, String project, String actor, Period old, Action command,
                         OffsetDateTime at, IntakeModel.Snapshot config) throws SQLException {
        state(old, "EM_CONFERENCIA");
        var run = run(connection, project, old, command.run());
        if (actor.equals(run.author())) {
            throw new PilotProblem(403, "AUTOAPROVACAO_NEGADA", "actor", "TX-E3-45", "Outra identidade deve decidir o fechamento.");
        }
        var used = first(connection, """
                SELECT run_id,selection_id FROM pilot_period_closure WHERE project_id=? AND (run_id=? OR selection_id=?)
                """, row -> true,
                project, command.run().id(), run.selection());
        require(used == null, "MEMORIA_JA_FECHADA", "Salve nova seleção e gere nova memória para a versão sucessora.");
        var report = inspect(connection, project, old, config, command.run(), true);
        require(run.sha256().equals(FixtureCatalog.sha256(run.snapshot())), "HASH_DIVERGENTE", "A memória salva está divergente.");
        var manifest = Map.ofEntries(Map.entry("format", "e8-006@1"), Map.entry("environment", "SIMULACAO"),
                Map.entry("project", project), Map.entry("period", old.id()), Map.entry("revision", old.revision()),
                Map.entry("window", old.window()), Map.entry("zone", old.zone()), Map.entry("effectiveAt", at),
                Map.entry("reviewedBy", actor), Map.entry("reason", command.reason()), Map.entry("coverage", report),
                Map.entry("operation", config.operation()), Map.entry("run", command.run()), Map.entry("selectionId", run.selection()),
                Map.entry("snapshotSha256", run.sha256()), Map.entry("memoryEvent", run.completed()), Map.entry("reviewEvent", run.reviewed()),
                Map.entry("memory", run.result()));
        byte[] bytes = audit.encode(manifest).getBytes(StandardCharsets.UTF_8);
        String sha = FixtureCatalog.sha256(bytes);
        var next = advance(connection, project, actor, old, command, at, "FECHADO", report, null, sha, command.run());
        update(connection, "INSERT INTO pilot_period_closure VALUES (?,?,?,?,?,?,?,?)", project, old.id(), old.revision(),
                next.version(), command.run().id(), run.selection(), bytes, sha);
        return next;
    }

    private Period reopen(Connection connection, String project, String actor, Period old, Action command,
                          OffsetDateTime at) throws SQLException {
        state(old, "FECHADO");
        var dependents = dependents(connection, project, old.id(), old.revision());
        boolean external = dependents.stream().anyMatch(item -> "ATO_EXTERNO".equals(item.input().kind()));
        var impact = new Impact(old.revision(), dependents, external ? UUID.randomUUID() : null,
                external ? "DIVERGENCIA_PENDENTE_E8_010" : "REANALISE_INTERNA");
        var reopened = advance(connection, project, actor, old, command, at, "EM_CONFERENCIA", null, impact, null, impact);
        issuance.onReopen(connection, project, actor, old.id(), old.revision(), impact.divergenceId(), command.reason());
        return reopened;
    }

    List<Dependent> dependents(String project, String period, int revision) {
        return database.read(connection -> dependents(connection, project, period, revision));
    }

    private List<Dependent> dependents(Connection connection, String project, String period, int revision) throws SQLException {
        return query(connection, """
                SELECT details FROM pilot_period_dependent WHERE project_id=? AND period=? AND revision=?
                ORDER BY kind,reference,reference_version
                """, row -> decode(row.getString(1), Dependent.class), project, period, revision);
    }

    JsonNode link(PilotAccess.Scope scope, String actor, String key, String period, int expected, DependentInput input) {
        if (input == null || input.kind() == null || !IntakeRules.text(input.reference()) || input.version() < 1
                || !IntakeRules.text(input.status())) throw invalid("DEPENDENTE_INVALIDO", "Informe tipo, referência, versão e situação.");
        return commands.execute(scope, actor, "VINCULAR_DEPENDENTE_FECHAMENTO", key,
                audit.encode(List.of(period, expected, input)), period, connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            var old = head(connection, project, period);
            if (old == null) throw PilotAccess.invisible();
            PilotCommands.requireVersion(expected, old.version());
            state(old, "FECHADO");
            require(first(connection, """
                    SELECT true FROM pilot_period_dependent WHERE project_id=? AND period=? AND revision<?
                      AND kind=? AND reference=? AND reference_version>=? LIMIT 1
                    """, row -> true, project, period, old.revision(), input.kind(), input.reference(), input.version()) == null,
                    "DEPENDENTE_AFETADO", "Use uma nova versão do dependente após a análise de impacto.");
            String sha = dependencyProof(connection, project, old, input);
            var existing = first(connection, """
                    SELECT details FROM pilot_period_dependent WHERE project_id=? AND period=? AND revision=?
                    AND kind=? AND reference=? AND reference_version=?
                    """, row -> decode(row.getString(1), Dependent.class), project, period, old.revision(), input.kind(), input.reference(), input.version());
            if (existing != null) {
                require(existing.input().equals(input), "DEPENDENTE_JA_VINCULADO", "A referência já possui outra prova ou situação.");
                return existing;
            }
            UUID event = audit.record(connection, project, actor, "VINCULAR_DEPENDENTE_FECHAMENTO", "PERMITIDO",
                    period + "@" + old.revision(), input);
            var result = new Dependent(input, actor, event, sha);
            update(connection, "INSERT INTO pilot_period_dependent VALUES (?,?,?,?,?,?,?::jsonb)", project, period,
                    old.revision(), input.kind(), input.reference(), input.version(), audit.encode(result));
            return result;
        });
    }

    private String dependencyProof(Connection connection, String project, Period old, DependentInput input) throws SQLException {
        if ("PACOTE".equals(input.kind())) {
            UUID id;
            try {
                id = UUID.fromString(input.reference());
            } catch (IllegalArgumentException error) {
                throw invalid("PACOTE_INVALIDO", "Informe o ID do artefato aprovado.");
            }
            if (!id.toString().equals(input.reference())) throw invalid("PACOTE_INVALIDO", "Use o ID do artefato como exibido.");
            String sha = first(connection, """
                    SELECT sha256 FROM pilot_artifact WHERE project_id=? AND id=? AND version=? AND purpose=?
                    AND approved_at IS NOT NULL
                    """, row -> row.getString(1), project, id, input.version(), "AUDITORIA_V" + old.projectYear());
            if (sha == null) throw PilotAccess.invisible();
            return sha;
        }

        boolean v1 = "SIM-V1".equals(input.reference());
        boolean i1 = "SIM-I1".equals(input.reference());
        boolean pending = "DECISAO".equals(input.kind()) && input.proof() == null
                && (v1 && "EM_REVISAO".equals(input.status()) || i1 && "AGUARDANDO_DEPOSITO".equals(input.status()));
        boolean witnessed = "ATO_EXTERNO".equals(input.kind())
                && (v1 && "APROVADO".equals(input.status()) && new IntakeModel.Ref("SIM-APROVA-V1", 1).equals(input.proof())
                || i1 && "DEPOSITO_REGISTRADO".equals(input.status()) && new IntakeModel.Ref("SIM-DEPOSITO-01", 1).equals(input.proof()));
        if (!"M1".equals(old.id()) || !(pending || witnessed)) {
            throw invalid("PROVA_SINTETICA_INVALIDA", "Use o estado ou comprovante simulado de V1/I1 previsto no kit.");
        }
        return null;
    }

    ClosureBytes closure(String project, String period, int revision) {
        var result = database.read(connection -> first(connection, """
                SELECT c.bytes,c.sha256,h.state='FECHADO' AND h.version=c.state_version
                FROM pilot_period_closure c JOIN LATERAL (
                  SELECT state,version FROM pilot_period_state WHERE project_id=c.project_id AND period=c.period
                  ORDER BY version DESC LIMIT 1
                ) h ON true WHERE c.project_id=? AND c.period=? AND c.revision=?
                """, row -> new ClosureBytes(row.getBytes(1), row.getString(2), row.getBoolean(3)), project, period, revision));
        if (result == null) throw PilotAccess.invisible();
        return result;
    }

    private Period advance(Connection connection, String project, String actor, Period old, Action action, OffsetDateTime at,
                           String next, PeriodCoverage.Report coverage, Impact impact, String sha, Object proof) throws SQLException {
        int revision = impact == null ? old.revision() : old.revision() + 1;
        int version = old.version() + 1;
        UUID event = audit.record(connection, project, actor, action.transitionId(), "PERMITIDO", old.id(), Map.of(
                "from", old.state(), "to", next, "before", old.version(), "after", version, "revision", revision,
                "effectiveAt", at, "reason", action.reason(), "proof", proof));
        return save(connection, project, new Period(old.id(), revision, version, next, old.start(), old.endExclusive(), old.zone(),
                old.projectYear(), at, actor, action.reason(), event, revision > 1 ? revision - 1 : null, sha, coverage,
                impact == null ? old.impact() : impact));
    }

    private Period save(Connection connection, String project, Period next) throws SQLException {
        update(connection, "INSERT INTO pilot_period_state VALUES (?,?,?,?,?::jsonb)", project, next.id(), next.version(),
                next.state(), audit.encode(next));
        return next;
    }

    private Period head(Connection connection, String project, String period) throws SQLException {
        return first(connection, """
                SELECT snapshot FROM pilot_period_state WHERE project_id=? AND period=? ORDER BY version DESC LIMIT 1
                """, row -> decode(row.getString(1)), project, period);
    }

    static void requireIntakeOpen(Connection connection, String project, String period) throws SQLException {
        String state = first(connection, """
                SELECT state FROM pilot_period_state WHERE project_id=? AND period=? ORDER BY version DESC LIMIT 1
                """, row -> row.getString(1), project, period);
        require(!"FECHADO".equals(state) && !"PREVISTO".equals(state), "COLETA_INDISPONIVEL",
                "O OP precisa abrir a coleta prevista ou o REV precisa reabrir a versão fechada.");
    }

    private Run run(Connection connection, String project, Period period, EnergyImportModel.Ref ref) throws SQLException {
        if (ref == null || ref.id() == null || ref.version() < 1) throw invalid("MEMORIA_AUSENTE", "Selecione uma memória versionada.");
        var run = first(connection, """
                SELECT version,state,proposed_by,selection_id,configuration_version,snapshot,snapshot_sha256,result,
                       review_event,result_event FROM pilot_selected_run WHERE project_id=? AND id=? AND period=?
                """, row -> new Run(row.getInt(1), row.getString(2), row.getString(3), row.getObject(4, UUID.class), row.getInt(5),
                row.getBytes(6), row.getString(7), row.getString(8) == null ? null : audit.decode(row.getString(8)),
                row.getObject(9, UUID.class), row.getObject(10, UUID.class)), project, ref.id(), period.id());
        if (run == null) throw PilotAccess.invisible();
        PilotCommands.requireVersion(ref.version(), run.version());
        require(run.snapshot() != null && run.reviewed() != null, "MEMORIA_PENDENTE", "Aguarde a memória revisada.");
        return run;
    }

    private boolean isCurrent(Connection connection, String project, String period, UUID selection) throws SQLException {
        return first(connection, """
                WITH active AS (
                    SELECT h.id,h.version FROM pilot_observation_head h JOIN pilot_observation_revision v
                    ON (h.project_id,h.id,h.version)=(v.project_id,v.id,v.version) WHERE h.project_id=? AND v.period=?
                ), saved AS (
                    SELECT observation_id AS id,observation_version AS version FROM pilot_selection_entry
                    WHERE project_id=? AND snapshot_id=?
                )
                SELECT NOT EXISTS (SELECT 1 FROM active FULL JOIN saved USING (id,version)
                                   WHERE active.id IS NULL OR saved.id IS NULL)
                """, row -> row.getBoolean(1), project, period, project, selection);
    }

    private boolean recovery(Connection connection, String project, Period old, UUID artifact) throws SQLException {
        return artifact != null && first(connection, """
                SELECT true FROM pilot_import_file f JOIN pilot_membership m
                  ON (m.project_id,m.identity_id)=(f.project_id,f.received_by)
                JOIN pilot_import_job j ON (j.project_id,j.file_id)=(f.project_id,f.id)
                JOIN pilot_import_row r ON (r.project_id,r.job_id)=(j.project_id,j.id)
                JOIN pilot_observation_revision v ON (v.project_id,v.row_id)=(r.project_id,r.id)
                JOIN pilot_observation_head h ON (h.project_id,h.id,h.version)=(v.project_id,v.id,v.version)
                JOIN pilot_access_event admitted ON admitted.id=v.event_id
                JOIN pilot_access_event gap ON gap.id=?
                WHERE f.project_id=? AND f.id=? AND j.period=? AND 'OP'=ANY(m.roles)
                  AND f.received_at>=m.valid_from AND f.received_at<m.valid_until AND admitted.occurred_at>gap.occurred_at
                LIMIT 1
                """, row -> true, old.eventId(), project, artifact, old.id()) != null;
    }

    private Period decode(String value) { return decode(value, Period.class); }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Falha ao ler a versão de monitoramento", e);
        }
    }

    private static void state(Period period, String expected) {
        require(period.state().equals(expected), "TRANSICAO_INDISPONIVEL", "A ação exige o período em " + expected + ".");
    }

    static void require(boolean allowed, String code, String message) {
        if (!allowed) throw new PilotProblem(409, code, "period", "OP-E6-04", message);
    }

    static PilotProblem invalid(String code, String message) {
        return PilotProblem.invalid(code, "period", "OP-E6-04", message);
    }
}
