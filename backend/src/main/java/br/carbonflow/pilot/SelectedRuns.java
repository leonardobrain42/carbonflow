package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static br.carbonflow.pilot.SelectedCalculation.*;
import static br.carbonflow.pilot.SelectedParameters.distinct;
import static br.carbonflow.pilot.SelectedParameters.invalid;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class SelectedRuns {
    private static final Logger LOG = Logger.getLogger(SelectedRuns.class);

    private record Saved(byte[] bytes, String sha256, Memory result) {}
    private record Source(EnergyImportModel.Ref observation, UUID decision, int configurationVersion,
                          UUID row, int line, UUID artifact, String artifactSha256, String authorization, String source,
                          String mapping, int mappingVersion, JsonNode fact,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                          UUID segmentationEvent) {}

    private final PilotDatabase database;
    private final PilotAudit audit;
    private final PilotCommands commands;
    private final ProjectIntake intake;
    private final FixtureCatalog fixtures;
    private final SelectedParameters parameters;
    private final ObjectMapper mapper;
    private final DurableJobs durableJobs;
    private ScheduledExecutorService worker;

    SelectedRuns(PilotDatabase database, PilotAudit audit, PilotCommands commands, ProjectIntake intake,
                 FixtureCatalog fixtures, SelectedParameters parameters, ObjectMapper mapper, DurableJobs durableJobs) {
        this.database = database;
        this.audit = audit;
        this.commands = commands;
        this.intake = intake;
        this.fixtures = fixtures;
        this.parameters = parameters;
        this.mapper = mapper;
        this.durableJobs = durableJobs;
    }

    JsonNode propose(PilotAccess.Scope scope, String actor, String key, Proposal proposal) {
        if (proposal == null || !EnergyImports.validPeriod(proposal.period()) || proposal.selection() == null
                || proposal.selection().id() == null || proposal.selection().version() != 1) {
            throw invalid("SELECAO_INVALIDA", "selection", "Indique o período e o retrato ED/EC versionado.");
        }
        if (!BASELINE.equals(proposal.baseline())) {
            throw invalid("BASELINE_NAO_SUPORTADA", "baseline", "Use a versão da linha de base LDV do kit.");
        }
        if (!FACTOR.equals(proposal.factor())) {
            throw invalid("FATOR_NAO_FIXADO", "factor", "Candidatos e dados publicados não substituem EF-SYN-v1.");
        }
        if (!DY.equals(proposal.dy())) {
            throw invalid("PROVA_INSUFICIENTE", "dy", "A prova da exceção de rede privada e fechada é obrigatória.");
        }
        return commands.execute(scope, actor, "PROPOR_CALCULO_SELECAO", key, audit.encode(proposal), proposal.period(), connection -> {
            var config = intake.lockAndLoad(connection, scope.project());
            String project = scope.project();
            UUID selectionEvent = first(connection, """
                    SELECT event_id FROM pilot_selection_snapshot WHERE project_id=? AND id=? AND period=?
                    """, row -> row.getObject(1, UUID.class), project, proposal.selection().id(), proposal.period());
            if (selectionEvent == null) throw PilotAccess.invisible();

            var inputs = pinInputs(connection, scope, proposal, config, selectionEvent);
            UUID id = UUID.randomUUID();
            UUID event = audit.record(connection, project, actor, "PROPOR_CALCULO_SELECAO", "PERMITIDO", id.toString(),
                    Map.of("selection", proposal.selection(), "factor", proposal.factor(), "configuration", config.version()));
            update(connection, """
                    INSERT INTO pilot_selected_run
                        (project_id,id,period,selection_id,configuration_version,proposed_by,propose_event,inputs,state)
                    VALUES (?,?,?,?,?,?,?,?::jsonb,'PROPOSTA')
                    """, project, id, proposal.period(), proposal.selection().id(), config.version(), actor, event, audit.encode(inputs));
            return requiredRun(connection, project, id);
        });
    }

    private Inputs pinInputs(Connection connection, PilotAccess.Scope scope, Proposal proposal, IntakeModel.Snapshot config,
                             UUID selectionEvent) throws SQLException {
        if (!"SIMULACAO".equals(scope.environment()) || config.operation() == null || config.intake() == null
                || !"ENQUADRADO".equals(config.intake().state())) {
            throw invalid("OPERACAO_PENDENTE", "configuration", "O cálculo exige a implantação sintética enquadrada e comissionada.");
        }
        var profile = config.intake().candidate().profile();
        if (!Boolean.TRUE.equals(profile.closedNetwork()) || !Boolean.TRUE.equals(profile.privateAccess())
                || !"SIN".equals(profile.grid()) || !Boolean.FALSE.equals(profile.additionalInfrastructure())) {
            throw invalid("PERFIL_NAO_SUPORTADO", "configuration", "A exceção requer a rede privada fechada e o perfil SIN.");
        }
        var evidence = config.records().reversed().stream()
                .filter(record -> record.input().type() == IntakeModel.RecordType.SOBREPOSICAO).findFirst()
                .filter(record -> config.intake().reviewedRecords().contains(record.id())
                        && record.input().status() == IntakeModel.RecordStatus.COMPROVADO_SIMULACAO
                        && new IntakeModel.Ref("SIM-SOBREPOSICAO", 1).equals(record.input().evidence()))
                .orElseThrow(() -> invalid("PROVA_INSUFICIENTE", "dy", "O inventário deve vincular a prova revisada de ausência de sobreposição."));

        var source = fixtures.load(FixtureCatalog.DEFAULT);
        var fixture = source.fixture();
        var estimate = new CarbonCalculator().calculate(fixture).periods().stream()
                .filter(period -> period.id().equals(proposal.period())).findFirst().orElseThrow();
        if (!estimate.start().equals(EnergyChecks.startOfPeriod(config, proposal.period()))) {
            throw invalid("JANELA_DIVERGENTE", "configuration", "A janela atual diverge das vigências do cenário fixado.");
        }
        var inventory = config.intake().candidate().inventory();
        var fleet = new ArrayList<Model>();
        for (var model : inventory.models()) {
            int index = switch (model.id()) {
                case "SIM-MODELO-01" -> 0;
                case "SIM-MODELO-02" -> 1;
                default -> throw invalid("MODELO_FORA_DA_BASELINE", "baseline", "Falta comparável revisado para o modelo.");
            };
            var baseline = fixture.calculation().models().get(index);
            int count = (int) inventory.vehicles().stream().filter(vehicle -> model.id().equals(vehicle.model())).count();
            fleet.add(new Model(model.id(), model.bev(), model.comparable(),
                    new Fixture.Model(count, baseline.evKwhPer100Mi(), baseline.mpg())));
        }
        var baseline = new Baseline(BASELINE, "Recarga de frota leve para serviço equivalente em Planaltina/DF",
                List.copyOf(fleet), fixture.calculation().fuelFactorTco2PerGal(), fixture.calculation().ir(),
                "Gasolina comercial sintética · um combustível", source.provenance().source());
        new CarbonCalculator().terms(fleet.stream().map(Model::values).toList(), baseline.fossilFactorTco2PerUsGal(),
                baseline.ir(), fixture.calculation().factor().value(), estimate.year());
        var discount = new Discount(DY, "EXCECAO_REDE_PRIVADA_FECHADA", "1", evidence.id(),
                evidence.input().evidence(), source.provenance().source());
        UUID configEvent = first(connection, "SELECT event_id FROM pilot_intake_revision WHERE project_id=? AND version=?",
                row -> row.getObject(1, UUID.class), scope.project(), config.version());
        return new Inputs(scope.environment(), scope.project(), proposal, config.version(), configEvent, selectionEvent,
                source.tzdbVersion(), fixture.calculation().zone(), source.provenance(), fixture.method(),
                baseline, parameters.pinned(source), discount, estimate);
    }

    JsonNode queue(PilotAccess.Scope scope, String actor, String key, UUID id, int expected, String transition, String reason) {
        if (!List.of("CONFIRMAR", "RETOMAR").contains(transition) || !IntakeRules.text(reason)) {
            throw invalid("REVISAO_INCOMPLETA", "action", "Informe a decisão e fundamente a revisão.");
        }
        return commands.execute(scope, actor, transition + "_CALCULO_SELECAO", key, audit.encode(List.of(id, expected, reason)),
                id.toString(), connection -> {
                    String project = scope.project();
                    var run = first(connection, "SELECT * FROM pilot_selected_run WHERE project_id=? AND id=? FOR UPDATE",
                            this::run, project, id);
                    if (run == null) throw PilotAccess.invisible();
                    PilotCommands.requireVersion(expected, run.version());
                    distinct(run.proposedBy(), actor);
                    String required = "CONFIRMAR".equals(transition) ? "PROPOSTA" : "FALHA_RECUPERAVEL";
                    if (!required.equals(run.state())) {
                        throw new PilotProblem(409, "TRANSICAO_INVALIDA", "state", "OP-E6-07", "A execução não está disponível para esta ação.");
                    }
                    var event = audit.record(connection, project, actor, transition + "_CALCULO_SELECAO", "PERMITIDO",
                            id.toString(), Map.of("before", expected, "after", expected + 1, "reason", reason));
                    if ("CONFIRMAR".equals(transition)) {
                        update(connection, """
                                UPDATE pilot_selected_run SET reviewed_by=?, review_event=?, state='AGUARDANDO',version=version+1
                                WHERE project_id=? AND id=?
                                """, actor, event, project, id);
                    } else {
                        update(connection, "UPDATE pilot_selected_run SET state='AGUARDANDO',version=version+1 WHERE project_id=? AND id=?",
                                project, id);
                    }
                    return requiredRun(connection, project, id);
                });
    }

    List<Run> list(String project) {
        return database.read(connection -> query(connection, """
                SELECT * FROM pilot_selected_run WHERE project_id=? ORDER BY proposed_at DESC,id DESC LIMIT 50
                """, this::run, project));
    }

    Optional<Run> find(String project, UUID id) {
        return Optional.ofNullable(database.read(connection -> first(connection,
                "SELECT * FROM pilot_selected_run WHERE project_id=? AND id=?", this::run, project, id)));
    }

    Optional<byte[]> snapshot(String project, UUID id) {
        return Optional.ofNullable(database.read(connection -> first(connection,
                "SELECT snapshot FROM pilot_selected_run WHERE project_id=? AND id=?", row -> row.getBytes(1), project, id)));
    }

    Inputs inputs(String project, UUID id) {
        var input = database.read(connection -> first(connection, "SELECT inputs FROM pilot_selected_run WHERE project_id=? AND id=?",
                row -> mapping(row.getString(1), Inputs.class), project, id));
        if (input == null) throw PilotAccess.invisible();
        return input;
    }

    JsonNode replay(PilotAccess.Scope scope, String actor, String key, UUID id) {
        return commands.execute(scope, actor, "REEXECUTAR_SNAPSHOT", key, id.toString(), id.toString(), connection -> {
            var saved = first(connection, "SELECT snapshot,snapshot_sha256,result FROM pilot_selected_run WHERE project_id=? AND id=?",
                    row -> new Saved(row.getBytes(1), row.getString(2), mapping(row.getString(3), Memory.class)), scope.project(), id);
            if (saved == null) throw PilotAccess.invisible();
            if (saved.bytes() == null) throw invalid("SNAPSHOT_PENDENTE", "snapshot", "Aguarde a execução desta proposta.");
            String actualSha = FixtureCatalog.sha256(saved.bytes());
            if (!actualSha.equals(saved.sha256())) throw invalid("HASH_DIVERGENTE", "snapshot", "A projeção salva apresentou divergência.");
            var snapshot = mapping(new String(saved.bytes(), StandardCharsets.UTF_8), Snapshot.class);
            var result = compute(snapshot);
            boolean match = result.equals(saved.result());
            var event = audit.record(connection, scope.project(), actor, "REEXECUTAR_SNAPSHOT", match ? "PERMITIDO" : "NEGADO",
                    id.toString(), Map.of("snapshotSha256", actualSha, "matches", match));
            return new Replay(actualSha, match, result, event);
        });
    }

    void start(@Observes StartupEvent event) {
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "pilot-selected-calculation");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::work, 0, 400, TimeUnit.MILLISECONDS);
    }

    void stop(@Observes ShutdownEvent event) {
        if (worker == null) return;
        worker.shutdownNow();
        try {
            worker.awaitTermination(4, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void work() {
        DurableJobs.Claim claim = null;
        try {
            claim = durableJobs.claim(DurableJobs.Kind.CALCULO);
            if (claim == null) return;
            var current = claim;
            durableJobs.process(current, connection -> {
                var inputs = first(connection, "SELECT inputs FROM pilot_selected_run WHERE project_id=? AND id=?",
                        row -> mapping(row.getString(1), Inputs.class), current.project(), current.id());
                var snapshot = project(connection, inputs);
                var result = compute(snapshot);
                byte[] bytes = audit.encode(snapshot).getBytes(StandardCharsets.UTF_8);
                String sha = FixtureCatalog.sha256(bytes);
                var event = audit.record(connection, current.project(), null, "CALCULAR_SELECAO", "PERMITIDO", current.id().toString(),
                        Map.of("snapshotSha256", sha, "status", result.status(), "observations", snapshot.observations(),
                                "generation", current.generation()));
                update(connection, """
                        UPDATE pilot_selected_run SET state=?,version=version+1,snapshot=?,snapshot_sha256=?,result=?::jsonb,result_event=?
                        WHERE project_id=? AND id=?
                        """, result.status(), bytes, sha, audit.encode(result), event, current.project(), current.id());
                return null;
            });
        } catch (RuntimeException failure) {
            if (worker.isShutdown()) return;
            LOG.warnf("Falha no job de cálculo %s (%s)", claim == null ? "indisponível" : claim.id(), failure.getClass().getSimpleName());
            if (claim != null) {
                try {
                    durableJobs.fail(claim, "FALHA_CALCULO_SELECAO", "O cálculo pode ser retomado da última revisão.");
                } catch (RuntimeException unavailable) {
                    LOG.warn("O banco ainda está indisponível para registrar a falha do cálculo");
                }
            }
        }
    }

    private Snapshot project(Connection connection, Inputs inputs) throws SQLException {
        var windows = VintageWindows.split(new VintageWindows.Window(inputs.estimate().start(), inputs.estimate().endExclusive()));
        var cuts = windows.stream().map(Sum::new).toList();
        MessageDigest digest = sha256();
        int total = 0;
        try (var statement = connection.prepareStatement("""
                SELECT v.id,v.version,v.event_id,v.configuration_version,v.fact,r.id AS row_id,r.line,
                           f.id AS artifact_id,f.sha256,f.authorization_id,f.source_id,j.mapping_id,j.mapping_version,sp.event_id AS split_event
                FROM pilot_selection_entry s
                JOIN pilot_observation_revision v ON (v.project_id,v.id,v.version)=(s.project_id,s.observation_id,s.observation_version)
                JOIN pilot_import_row r ON (r.project_id,r.id)=(v.project_id,v.row_id)
                JOIN pilot_import_job j ON (j.project_id,j.id)=(r.project_id,r.job_id)
                JOIN pilot_import_file f ON (f.project_id,f.id)=(j.project_id,j.file_id)
                LEFT JOIN pilot_measured_segment ms ON (ms.project_id,ms.segment_row)=(r.project_id,r.id)
                LEFT JOIN pilot_measured_split sp ON (sp.project_id,sp.original_row)=(ms.project_id,ms.original_row)
                WHERE s.project_id=? AND s.snapshot_id=? ORDER BY s.observation_id
                """)) {
            statement.setString(1, inputs.project());
            statement.setObject(2, inputs.proposal().selection().id());
            statement.setFetchSize(1000);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var fact = mapping(rows.getString("fact"), EnergyImportModel.Fact.class);
                    var window = VintageWindows.assign(fact.start(), fact.endExclusive(), windows, "observation");
                    cuts.get(windows.indexOf(window)).add(fact);
                    var source = new Source(new EnergyImportModel.Ref(rows.getObject("id", UUID.class), rows.getInt("version")),
                            rows.getObject("event_id", UUID.class), rows.getInt("configuration_version"),
                            rows.getObject("row_id", UUID.class), rows.getInt("line"), rows.getObject("artifact_id", UUID.class),
                            rows.getString("sha256"), rows.getString("authorization_id"), rows.getString("source_id"),
                            rows.getString("mapping_id"), rows.getInt("mapping_version"), audit.decode(rows.getString("fact")),
                            rows.getObject("split_event", UUID.class));
                    digest.update(audit.encode(source).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                    total++;
                }
            }
        }
        return new Snapshot(SNAPSHOT_FORMAT, ENGINE, inputs, total, HexFormat.of().formatHex(digest.digest()),
                cuts.stream().map(Sum::totals).toList());
    }

    private static final class Sum {
        private final VintageWindows.Window window;
        private BigDecimal ed = BigDecimal.ZERO;
        private BigDecimal ec = BigDecimal.ZERO;
        private int edCount;
        private int ecCount;

        Sum(VintageWindows.Window window) { this.window = window; }

        void add(EnergyImportModel.Fact fact) {
            var value = CarbonCalculator.decimal(fact.kwh(), "observation.kwh");
            if ("ED".equals(fact.kind())) {
                ed = ed.add(value);
                edCount++;
            } else if ("EC".equals(fact.kind())) {
                ec = ec.add(value);
                ecCount++;
            } else {
                throw new IllegalStateException("Classe incongruente na seleção");
            }
        }

        Totals totals() {
            return new Totals(window.start(), window.endExclusive(), edCount == 0 ? null : CarbonCalculator.number(ed),
                    ecCount == 0 ? null : CarbonCalculator.number(ec), edCount, ecCount);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Run requiredRun(Connection connection, String project, UUID id) throws SQLException {
        var run = first(connection, "SELECT * FROM pilot_selected_run WHERE project_id=? AND id=?", this::run, project, id);
        if (run == null) throw PilotAccess.invisible();
        return run;
    }

    private Run run(ResultSet row) throws SQLException {
        return new Run(row.getObject("id", UUID.class), row.getInt("version"), row.getString("state"), row.getString("period"),
                row.getObject("selection_id", UUID.class), row.getInt("configuration_version"), row.getString("proposed_by"),
                row.getObject("proposed_at", OffsetDateTime.class), row.getObject("propose_event", UUID.class),
                row.getString("reviewed_by"), row.getObject("review_event", UUID.class), row.getString("snapshot_sha256"),
                mapping(row.getString("result"), Memory.class), row.getObject("result_event", UUID.class));
    }

    private <T> T mapping(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Falha ao recuperar a projeção de cálculo", error);
        }
    }
}
