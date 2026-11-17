package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static br.carbonflow.pilot.EnergyImportModel.*;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class EnergyImports {
    private static final Logger LOG = Logger.getLogger(EnergyImports.class);
    private static final String USABLE = "('CANDIDATA','DUPLICATA','CONFLITO_ID','SOBREPOSICAO')";
    static final String FILE_COLUMNS = """
            id, sha256, original_name, source_id, authorization_id, octet_length(bytes) AS size, received_by, received_at
            """;
    private static final TypeReference<List<Reason>> REASONS = new TypeReference<>() {};

    private record Previous(UUID row, String hash) {}
    private record Raw(UUID id, String text) {}

    private final PilotDatabase database;
    private final PilotAudit audit;
    private final PilotCommands commands;
    private final ProjectIntake intake;
    private final ObjectMapper mapper;
    private final DurableJobs durableJobs;
    private final EvidenceQuota quota;
    private ScheduledExecutorService worker;

    EnergyImports(PilotDatabase database, PilotAudit audit, PilotCommands commands, ProjectIntake intake, ObjectMapper mapper,
                  DurableJobs durableJobs, EvidenceQuota quota) {
        this.database = database;
        this.audit = audit;
        this.commands = commands;
        this.intake = intake;
        this.mapper = mapper;
        this.durableJobs = durableJobs;
        this.quota = quota;
    }

    void start(@Observes StartupEvent event) {
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "pilot-ed-ec-import");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::work, 0, 200, TimeUnit.MILLISECONDS);
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

    JsonNode upload(PilotAccess.Scope scope, String actor, String key, String name, String source,
                    String authorization, byte[] bytes) {
        if (!SOURCE.equals(source) || !AUTHORIZATION.equals(authorization)) {
            throw new PilotProblem(403, "ORIGEM_SEM_AUTORIZACAO", "authorization", "OP-E6-05",
                    "Use a autorização sintética da origem declarada.");
        }
        if (name == null || !name.matches("[\\p{L}0-9._ -]{1,120}") || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            throw invalid("ARQUIVO_INVALIDO", "fileName", "Informe um nome CSV válido.");
        }
        if (bytes.length == 0) throw invalid("ARQUIVO_VAZIO", "file", "O arquivo está vazio.");
        if (bytes.length > MAX_BYTES) {
            throw new PilotProblem(413, "ARQUIVO_MUITO_GRANDE", "file", "CT-E5-27",
                    "Limite de 10.000.000 bytes por CSV. O lote inteiro foi recusado.");
        }
        String hash = FixtureCatalog.sha256(bytes);
        return commands.execute(scope, actor, "RECEBER_CSV_ED_EC", key, audit.encode(List.of(name, source, authorization, hash)),
                hash, connection -> {
                    intake.lockProject(connection, scope.project());
                    var existing = first(connection, "SELECT " + FILE_COLUMNS + """
                            FROM pilot_import_file WHERE project_id = ? AND source_id = ? AND sha256 = ?
                            """, EnergyImports::file, scope.project(), source, hash);
                    if (existing != null) {
                        UUID event = uploadEvent(connection, scope.project(), actor, name, existing, true);
                        return new Upload(existing, event, true);
                    }
                    quota.requireSpace(connection, scope.project(), bytes.length);
                    UUID id = UUID.randomUUID();
                    update(connection, """
                            INSERT INTO pilot_import_file
                                (project_id, id, sha256, original_name, source_id, authorization_id, received_by, bytes)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """, scope.project(), id, hash, name, source, authorization, actor, bytes);
                    var storedFile = findFile(connection, scope.project(), id);
                    return new Upload(storedFile, uploadEvent(connection, scope.project(), actor, name, storedFile, false), false);
                });
    }

    private UUID uploadEvent(Connection connection, String project, String actor, String name, FileReceipt stored, boolean repeated)
            throws SQLException {
        return audit.record(connection, project, actor, "RECEBER_CSV_ED_EC", "PERMITIDO", stored.id().toString(),
                Map.of("sha256", stored.sha256(), "receivedName", name, "source", stored.sourceId(),
                        "authorization", stored.authorizationId(), "repeated", repeated));
    }

    JsonNode enqueue(PilotAccess.Scope scope, String actor, String key, ImportCommand command) {
        if (command == null || command.artifact() == null || command.artifact().id() == null || command.artifact().version() != 1
                || !new CodeRef(MAPPING, 1).equals(command.mappingProfile()) || !SOURCE.equals(command.sourceId())
                || command.period() == null || command.period().version() != 1 || !validPeriod(command.period().id())) {
            throw invalid("IMPORTACAO_INVALIDA", "import", "Confira origem, artefato, mapeamento e período versionados.");
        }
        UUID fileId = command.artifact().id();
        String period = command.period().id();
        return commands.execute(scope, actor, "SOLICITAR_IMPORTACAO_ED_EC", key, audit.encode(command), fileId.toString(),
                connection -> {
                    var configuration = intake.lockAndLoad(connection, scope.project());
                    var artifact = findFile(connection, scope.project(), fileId);
                    if (artifact == null || !command.sourceId().equals(artifact.sourceId())) throw PilotAccess.invisible();
                    var prior = first(connection, """
                            SELECT * FROM pilot_import_job WHERE project_id = ? AND file_id = ? AND mapping_id = ?
                              AND mapping_version = 1 AND period = ?
                            """, EnergyImports::job, scope.project(), fileId, MAPPING, period);
                    boolean repeated = prior != null;
                    if (!repeated && configuration.operation() == null) {
                        throw invalid("OPERACAO_PENDENTE", "period", "Conclua o comissionamento na ficha da E8-003. O bruto permanece disponível.");
                    }
                    UUID id = repeated ? prior.id() : UUID.randomUUID();
                    int version = repeated ? prior.version() : 1;
                    if (!repeated) {
                        MonitoringPeriods.requireIntakeOpen(connection, scope.project(), period);
                        update(connection, """
                                INSERT INTO pilot_import_job
                                    (project_id,id,file_id,mapping_id,mapping_version,period,configuration_version,requested_by,state)
                                VALUES (?, ?, ?, ?, 1, ?, ?, ?, 'AGUARDANDO')
                                """, scope.project(), id, fileId, MAPPING, period, configuration.version(), actor);
                    }
                    UUID event = audit.record(connection, scope.project(), actor, "SOLICITAR_IMPORTACAO_ED_EC", "PERMITIDO",
                            id.toString(), Map.of("sha256", artifact.sha256(), "period", period, "repeated", repeated,
                                    "mapping", command.mappingProfile(), "configurationVersion",
                                    repeated ? prior.configurationVersion() : configuration.version()));
                    return new Queued(id, version, event, repeated);
                });
    }

    JsonNode resume(PilotAccess.Scope scope, String actor, String key, UUID id, int expected) {
        return commands.execute(scope, actor, "RETOMAR_IMPORTACAO_ED_EC", key, id + "@" + expected, id.toString(),
                connection -> {
                    var job = first(connection, "SELECT * FROM pilot_import_job WHERE project_id = ? AND id = ? FOR UPDATE",
                            EnergyImports::job, scope.project(), id);
                    if (job == null) throw PilotAccess.invisible();
                    PilotCommands.requireVersion(expected, job.version());
                    if (!"FALHA_RECUPERAVEL".equals(job.state())) {
                        throw new PilotProblem(409, "SEM_FALHA_RECUPERAVEL", "job", "OP-E6-13",
                                "A retomada exige uma falha recuperável.");
                    }
                    update(connection, """
                            UPDATE pilot_import_job SET state = ?, message = NULL, version = version + 1
                            WHERE project_id = ? AND id = ?
                            """, job.columns() == null ? "AGUARDANDO" : "PROCESSANDO", scope.project(), id);
                    UUID event = audit.record(connection, scope.project(), actor, "RETOMAR_IMPORTACAO_ED_EC", "PERMITIDO",
                            id.toString(), Map.of("processed", job.processed(), "before", expected, "after", expected + 1));
                    return new Queued(id, expected + 1, event, false);
                });
    }

    List<Job> jobs(String project) {
        return database.read(connection -> query(connection, """
                SELECT * FROM pilot_import_job WHERE project_id = ? ORDER BY created_at DESC, id DESC LIMIT 50
                """, EnergyImports::job, project));
    }

    Report report(String project, UUID id) {
        return database.transaction(connection -> {
            // O relatório usa um único snapshot para as contagens e o estado.
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var job = findJob(connection, project, id);
            var counts = new LinkedHashMap<String, Integer>();
            query(connection, """
                    SELECT classification, count(*) FROM pilot_import_row WHERE project_id = ? AND job_id = ?
                    GROUP BY classification ORDER BY classification
                    """, row -> counts.put(row.getString(1), row.getInt(2)), project, id);
            if ("RECUSADO".equals(job.state())) counts.put("RECUSADAS_LOTE", job.total());
            return new Report(id, job.period(), findFile(connection, project, job.file()), new CodeRef(MAPPING, 1),
                    job.configurationVersion(), job.version(), job.state(), job.total(), job.processed(), counts, job.message());
        });
    }

    Page rows(String project, UUID id, String cursor) {
        int after = lineCursor(cursor);
        return database.read(connection -> {
            findJob(connection, project, id);
            var rows = query(connection, """
                    SELECT * FROM pilot_import_row WHERE project_id = ? AND job_id = ? AND line > ?
                    ORDER BY line LIMIT 51
                    """, this::line, project, id, after);
            String next = rows.size() > 50 ? encodeCursor(Integer.toString(rows.get(49).line())) : null;
            return new Page(rows.subList(0, Math.min(rows.size(), 50)), next);
        });
    }

    FileContent content(String project, UUID id) {
        var content = database.read(connection -> first(connection, """
                SELECT original_name, bytes FROM pilot_import_file WHERE project_id = ? AND id = ?
                """, row -> new FileContent(row.getString(1), row.getBytes(2)), project, id));
        if (content == null) throw PilotAccess.invisible();
        return content;
    }

    private void work() {
        DurableJobs.Claim claim = null;
        try {
            claim = durableJobs.claim(DurableJobs.Kind.IMPORTACAO);
            if (claim == null) return;
            var current = claim;
            durableJobs.process(current, connection -> {
                var job = findJob(connection, current.project(), current.id());
                if (job.columns() == null) prepare(connection, job);
                else process(connection, job);
                return null;
            });
        } catch (RuntimeException error) {
            if (worker.isShutdown()) return;
            LOG.warnf("Falha no processamento durável de ED/EC, lote %s, classe %s",
                    claim == null ? "indisponível" : claim.id(), error.getClass().getSimpleName());
            if (claim != null) {
                try {
                    durableJobs.fail(claim, "FALHA_IMPORTACAO_ED_EC", "A etapa não confirmada pode ser retomada.");
                } catch (RuntimeException unavailable) {
                    LOG.warn("O banco ainda está indisponível para registrar a falha de importação");
                }
            }
        }
    }

    private void prepare(Connection connection, Job job) throws SQLException {
        byte[] bytes = first(connection, "SELECT bytes FROM pilot_import_file WHERE project_id = ? AND id = ?",
                row -> row.getBytes(1), job.project(), job.file());
        var plan = EnergyCsv.plan(bytes);
        if (plan.rejection() != null) {
            update(connection, """
                    UPDATE pilot_import_job SET state='RECUSADO',version=version+1,total=?,processed=?,message=?
                    WHERE project_id=? AND id=?
                    """, plan.received(), plan.received(), plan.rejection(), job.project(), job.id());
            return;
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO pilot_import_row (project_id,id,job_id,line,original) VALUES (?,?,?,?,?)
                """)) {
            int line = 2;
            for (String original : plan.rows()) {
                insert.setString(1, job.project());
                insert.setObject(2, UUID.randomUUID());
                insert.setObject(3, job.id());
                insert.setInt(4, line++);
                insert.setString(5, original);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        update(connection, """
                UPDATE pilot_import_job SET state='PROCESSANDO',version=version+1,total=?,columns=?
                WHERE project_id=? AND id=?
                """, plan.received(), EnergyCsv.columnCount(bytes), job.project(), job.id());
    }

    private void process(Connection connection, Job job) throws SQLException {
        // Uma transação por trecho. O próximo trecho sempre parte das linhas que ainda aguardam.
        intake.lockProject(connection, job.project());
        var config = first(connection, """
                SELECT snapshot FROM pilot_intake_revision WHERE project_id = ? AND version = ?
                """, row -> decode(row.getString(1), IntakeModel.Snapshot.class), job.project(), job.configurationVersion());
        var rows = query(connection, """
                SELECT id, original FROM pilot_import_row WHERE project_id=? AND job_id=? AND classification='AGUARDANDO'
                ORDER BY line LIMIT ?
                """, row -> new Raw(row.getObject(1, UUID.class), row.getString(2)), job.project(), job.id(), CHUNK);
        for (var raw : rows) classify(connection, job, config, raw);
        int processed = job.processed() + rows.size();
        update(connection, """
                UPDATE pilot_import_job SET version=version+1,state=?,processed=? WHERE project_id=? AND id=?
                """, processed == job.total() ? "CONCLUIDO" : "PROCESSANDO", processed, job.project(), job.id());
    }

    private void classify(Connection connection, Job job, IntakeModel.Snapshot config, Raw raw) throws SQLException {
        var result = EnergyChecks.preview(raw.text(), job.columns(), config, job.period());
        var fact = result.fact();
        String classification = result.classification();
        var reasons = result.reasons();
        String hash = null;
        UUID related = null;
        if ("CANDIDATA".equals(classification)) {
            hash = EnergyChecks.hash(fact);
            var sameId = fact.sourceFactId() == null ? null : previous(connection, job.project(),
                    "source_fact = ?", fact.sourceFactId());
            if (sameId != null && !hash.equals(sameId.hash())) {
                classification = "CONFLITO_ID";
                related = sameId.row();
                reasons = List.of(new Reason("CONFLITO_ID", "O mesmo ID da origem já tem outro conteúdo. Solicite revisão."));
            } else {
                var identical = previous(connection, job.project(), "physical_hash = ?", hash);
                if (identical != null) {
                    classification = "DUPLICATA";
                    related = identical.row();
                    reasons = List.of(new Reason("DUPLICATA", "Conteúdo físico equivalente já aparece em outra linha."));
                } else {
                    var overlap = overlapping(connection, job.project(), fact);
                    if (overlap != null) {
                        classification = "SOBREPOSICAO";
                        related = overlap.row();
                        reasons = List.of(new Reason("SOBREPOSICAO", "O intervalo sobrepõe outra leitura do mesmo contorno."));
                    }
                }
            }
        }
        update(connection, """
                UPDATE pilot_import_row SET classification=?, reasons=?::jsonb, fact=?::jsonb,source_fact=?,
                       physical_hash=?,related_row_id=?,kind=?,channel=?,vehicle=?,start_at=?,end_at=?
                WHERE project_id=? AND id=?
                """, classification, audit.encode(reasons), fact == null ? null : audit.encode(fact),
                fact == null ? null : fact.sourceFactId(), hash, related, fact == null ? null : fact.kind(),
                fact == null ? null : fact.channel(), fact == null ? null : fact.vehicle(),
                fact == null ? null : fact.start(), fact == null ? null : fact.endExclusive(), job.project(), raw.id());
    }

    private Previous overlapping(Connection connection, String project, Fact fact) throws SQLException {
         // Restringe primeiro pelo índice de intervalo. A ordenação por lote e linha
        // e o filtro de projeto não devem induzir a leitura de todo o histórico.
        return first(connection, """
                WITH matches AS MATERIALIZED (
                    SELECT id,physical_hash,job_id,line,project_id,kind,channel,vehicle,classification
                    FROM pilot_import_row
                    WHERE physical_hash IS NOT NULL AND tstzrange(start_at,end_at,'[)') && tstzrange(?,?,'[)')
                )
                SELECT id,physical_hash FROM matches WHERE project_id=? AND kind=?
                  AND (channel=? OR (?='ED' AND vehicle=?))
                  AND classification IN
                """ + USABLE + " ORDER BY job_id,line LIMIT 1",
                row -> new Previous(row.getObject(1, UUID.class), row.getString(2)),
                fact.start(), fact.endExclusive(), project, fact.kind(), fact.channel(), fact.kind(), fact.vehicle());
    }

    private Previous previous(Connection connection, String project, String condition, Object... values) throws SQLException {
        Object[] args = new Object[values.length + 1];
        args[0] = project;
        System.arraycopy(values, 0, args, 1, values.length);
        return first(connection, "SELECT id,physical_hash FROM pilot_import_row WHERE project_id=? AND fact IS NOT NULL AND classification IN "
                        + USABLE + " AND " + condition + " ORDER BY job_id,line LIMIT 1",
                row -> new Previous(row.getObject(1, UUID.class), row.getString(2)), args);
    }

    static Job findJob(Connection connection, String project, UUID id) throws SQLException {
        var job = first(connection, "SELECT * FROM pilot_import_job WHERE project_id = ? AND id = ?",
                EnergyImports::job, project, id);
        if (job == null) throw PilotAccess.invisible();
        return job;
    }

    private static Job job(ResultSet row) throws SQLException {
        return new Job(row.getString("project_id"), row.getObject("id", UUID.class), row.getObject("file_id", UUID.class),
                row.getString("period"), row.getInt("configuration_version"), row.getInt("version"), row.getString("state"),
                row.getInt("total"), row.getInt("processed"), row.getObject("columns", Integer.class), row.getString("message"));
    }

    private static FileReceipt findFile(Connection connection, String project, UUID id) throws SQLException {
        return first(connection, "SELECT " + FILE_COLUMNS + " FROM pilot_import_file WHERE project_id = ? AND id = ?",
                EnergyImports::file, project, id);
    }

    private static FileReceipt file(ResultSet row) throws SQLException {
        return new FileReceipt(row.getObject("id", UUID.class), 1, row.getString("sha256"), row.getString("original_name"),
                row.getString("source_id"), row.getString("authorization_id"), row.getInt("size"),
                row.getString("received_by"), row.getObject("received_at", OffsetDateTime.class));
    }

    Line line(ResultSet row) throws SQLException {
        try {
            return new Line(row.getObject("id", UUID.class), row.getInt("line"), row.getString("original"),
                    row.getString("classification"), mapper.readValue(row.getString("reasons"), REASONS),
                    decode(row.getString("fact"), Fact.class), row.getObject("related_row_id", UUID.class),
                    row.getInt("review_version"), row.getString("decision"), decode(row.getString("proposal"), Proposal.class),
                    decode(row.getString("resolution"), Resolution.class));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Não foi possível recuperar a prévia", error);
        }
    }

    <T> T decode(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Não foi possível recuperar o registro de ED/EC", error);
        }
    }

    static boolean validPeriod(String period) { return "M1".equals(period) || "M2".equals(period); }

    static String encodeCursor(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    static String decodeCursor(String cursor) {
        if (cursor.length() > 64) throw new IllegalArgumentException();
        return new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int lineCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            int line = Integer.parseInt(decodeCursor(cursor));
            if (line < 2 || line > MAX_LINES + 1) throw new IllegalArgumentException();
            return line;
        } catch (IllegalArgumentException error) {
            throw invalid("CURSOR_INVALIDO", "cursor", "O cursor da prévia é inválido.");
        }
    }

    static PilotProblem invalid(String code, String field, String message) {
        return PilotProblem.invalid(code, field, "OP-E6-05/06", message);
    }
}
