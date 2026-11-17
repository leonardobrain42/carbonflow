package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import com.fasterxml.jackson.databind.JsonNode;

import static br.carbonflow.pilot.EnergyImportModel.*;
import static br.carbonflow.pilot.EnergyImports.invalid;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class EnergyReviews {
    record Action(String transitionId, String reason, Ref predecessor) {}
    record Change(Line line, UUID eventId) {}

    private static final String ACTIVE = """
            SELECT v.* FROM pilot_observation_head h
            JOIN pilot_observation_revision v ON (v.project_id,v.id,v.version)=(h.project_id,h.id,h.version)
            WHERE h.project_id=?
            """;

    private final PilotDatabase database;
    private final PilotAudit audit;
    private final PilotCommands commands;
    private final ProjectIntake intake;
    private final EnergyImports imports;

    EnergyReviews(PilotDatabase database, PilotAudit audit, PilotCommands commands, ProjectIntake intake,
                  EnergyImports imports) {
        this.database = database;
        this.audit = audit;
        this.commands = commands;
        this.intake = intake;
        this.imports = imports;
    }

    JsonNode act(PilotAccess.Scope scope, String actor, String key, UUID id, int expected, Action action) {
        if (action == null || action.transitionId() == null || !IntakeRules.text(action.reason())
                || (action.predecessor() != null && (action.predecessor().id() == null || action.predecessor().version() < 1))) {
            throw invalid("DECISAO_INCOMPLETA", "action", "Informe decisão, motivo e referência válida quando houver correção.");
        }

        return commands.execute(scope, actor, "DECISAO_ED_EC_" + action.transitionId(), key,
                audit.encode(List.of(id, expected, action)), id.toString(), connection -> {
                    String project = scope.project();
                    var configuration = intake.lockAndLoad(connection, project);
                    var line = getLine(connection, project, id);
                    PilotCommands.requireVersion(expected, line.version());
                    UUID jobId = first(connection, "SELECT job_id FROM pilot_import_row WHERE project_id=? AND id=?",
                            row -> row.getObject(1, UUID.class), project, id);
                    var job = EnergyImports.findJob(connection, project, jobId);
                    if (!job.state().equals("CONCLUIDO")) throw conflict("PREVIA_INCOMPLETA", "Aguarde a conclusão da prévia.");
                    if (!List.of("EM_PREVIA", "PROPOSTA").contains(line.decision())) {
                        throw conflict("DECISAO_JA_REGISTRADA", "A decisão desta linha já foi registrada.");
                    }
                    MonitoringPeriods.requireIntakeOpen(connection, project, job.period());
                    return switch (action.transitionId()) {
                        case "PROPOR" -> propose(connection, project, actor, line, job, configuration, action);
                        case "ADMITIR" -> admit(connection, project, actor, line, job, configuration, action);
                        case "REJEITAR" -> reject(connection, project, actor, line, action);
                        default -> throw invalid("ACAO_INVALIDA", "transitionId", "Ação de revisão desconhecida.");
                    };
                });
    }

    private Change propose(Connection connection, String project, String actor, Line line, Job job,
                           IntakeModel.Snapshot config, Action action) throws SQLException {
        Fact fact = requireAdmissible(line, job, config);
        MeasuredSplits.requireReconciled(connection, project, line.id(), fact);
        if (action.predecessor() != null) {
            requirePredecessor(connection, project, fact, job.period(), action.predecessor());
        }
        var proposal = new Proposal(actor, action.reason(), action.predecessor());
        UUID event = audit.record(connection, project, actor, "PROPOR_ED_EC", "PERMITIDO", line.id().toString(),
                Map.of("before", line.version(), "after", line.version() + 1, "proposal", proposal, "job", job.id()));
        update(connection, """
                UPDATE pilot_import_row SET review_version=review_version+1,decision='PROPOSTA',proposal=?::jsonb
                WHERE project_id=? AND id=?
                """, audit.encode(proposal), project, line.id());
        return new Change(getLine(connection, project, line.id()), event);
    }

    private Change admit(Connection connection, String project, String actor, Line line, Job job,
                         IntakeModel.Snapshot config, Action action) throws SQLException {
        var proposal = line.proposal();
        if (proposal == null || !"PROPOSTA".equals(line.decision())) {
            throw conflict("PROPOSTA_AUSENTE", "O PREP precisa propor a linha antes da admissão.");
        }
        if (actor.equals(proposal.preparedBy())) {
            throw new PilotProblem(403, "AUTOAPROVACAO_NEGADA", "actor", "OP-E6-06",
                    "Outra identidade deve revisar a proposta.");
        }
        if (action.predecessor() != null) {
            throw invalid("CORRECAO_NA_PROPOSTA", "predecessor", "A predecessora deve constar da proposta do PREP.");
        }
        Fact fact = requireAdmissible(line, job, config);
        MeasuredSplits.requireReconciled(connection, project, line.id(), fact);
        String hash = EnergyChecks.hash(fact);
        var previous = proposal.predecessor() == null ? null : requirePredecessor(connection, project, fact, job.period(), proposal.predecessor());
        var alias = findAlias(connection, project, fact.sourceFactId());
        if (previous == null && alias != null && !alias.physicalHash().equals(hash)) {
            throw conflict("CONFLITO_ID", "A identidade externa já tem outro conteúdo. Proponha uma correção vinculada.");
        }

        var equal = first(connection, ACTIVE + " AND v.physical_hash=? LIMIT 1", this::observation, project, hash);
        boolean duplicate = equal != null;
        if (duplicate && previous != null && !equal.ref().id().equals(previous.ref().id())) {
            throw conflict("FATO_CONFLITANTE", "A correção colide com outro fato ativo.");
        }
        // Mesmo ato de origem e mesmo valor retorna a versão ativa. Um fato físico igual com outro ID vira alias.
        if (duplicate && alias != null && !alias.ref().id().equals(equal.ref().id())) {
            throw conflict("CONFLITO_ID", "O ID externo já está vinculado a outro fato.");
        }
        UUID previousId = previous == null ? null : previous.ref().id();
        if (!duplicate) {
            boolean overlap = first(connection, ACTIVE + """
                     AND v.kind=? AND (v.channel=? OR (?='ED' AND v.vehicle=?))
                     AND v.start_at < ? AND v.end_at > ?
                     AND v.id IS DISTINCT FROM CAST(? AS uuid) LIMIT 1
                    """, row -> true, project, fact.kind(), fact.channel(), fact.kind(), fact.vehicle(), fact.endExclusive(),
                    fact.start(), previousId) != null;
            if (overlap) throw conflict("SOBREPOSICAO", "Há observação ativa sobreposta no contorno ou veículo.");
        }
        int localYear = fact.start().atZoneSameInstant(VintageWindows.ZONE).getYear();
        if (!duplicate) {
            long used = first(connection, "SELECT count(*) FROM (" + ACTIVE
                            + " AND v.kind=? AND v.local_year=? AND v.id IS DISTINCT FROM CAST(? AS uuid)) current_facts",
                    row -> row.getLong(1), project, fact.kind(), localYear, previousId);
            if (used >= MAX_FACTS_PER_YEAR) {
                throw invalid("LIMITE_DE_CAPACIDADE", "observation", "Limite de 100.000 fatos por classe e ano civil.");
            }
        }

        Ref ref = duplicate ? equal.ref() : new Ref(previous == null ? UUID.randomUUID() : previousId,
                previous == null ? 1 : previous.ref().version() + 1);
        String decision = duplicate ? "REPETIDA" : "ADMITIDA";
        UUID event = audit.record(connection, project, actor, "ADMITIR_ED_EC", "PERMITIDO", line.id().toString(),
                Map.of("decision", decision, "observation", ref, "configurationVersion", config.version(),
                        "proposal", proposal, "reason", action.reason(), "job", job.id()));
        var resolution = new Resolution(actor, action.reason(), ref, event);

        if (!duplicate) {
            update(connection, """
                    INSERT INTO pilot_observation_revision
                        (project_id,id,version,predecessor,row_id,period,configuration_version,kind,local_year,channel,
                         vehicle,start_at,end_at,physical_hash,fact,resolution,event_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)
                    """, project, ref.id(), ref.version(), previous == null ? null : previous.ref().version(), line.id(),
                    job.period(), config.version(), fact.kind(), localYear, fact.channel(), fact.vehicle(), fact.start(),
                    fact.endExclusive(), hash, audit.encode(fact), audit.encode(resolution), event);
            update(connection, """
                    INSERT INTO pilot_observation_head VALUES (?,?,?)
                    ON CONFLICT (project_id,id) DO UPDATE SET version=excluded.version
                    """, project, ref.id(), ref.version());
        }
        if (fact.sourceFactId() != null) {
            update(connection, "INSERT INTO pilot_observation_alias VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    project, fact.sourceFactId(), ref.id());
        }
        return resolve(connection, project, line, decision, resolution);
    }

    private Change reject(Connection connection, String project, String actor, Line line, Action action) throws SQLException {
        UUID event = audit.record(connection, project, actor, "REJEITAR_ED_EC", "PERMITIDO", line.id().toString(),
                Map.of("before", line.version(), "after", line.version() + 1, "reason", action.reason()));
        return resolve(connection, project, line, "REJEITADA", new Resolution(actor, action.reason(), null, event));
    }

    private Change resolve(Connection connection, String project, Line line, String decision, Resolution resolution)
            throws SQLException {
        update(connection, """
                UPDATE pilot_import_row SET review_version=review_version+1,decision=?,resolution=?::jsonb
                WHERE project_id=? AND id=?
                """, decision, audit.encode(resolution), project, line.id());
        return new Change(getLine(connection, project, line.id()), resolution.eventId());
    }

    private Fact requireAdmissible(Line line, Job job, IntakeModel.Snapshot latest) {
        var current = EnergyChecks.preview(line.original(), job.columns(), latest, job.period());
        if (!"CANDIDATA".equals(current.classification())) {
            var reason = current.reasons().getFirst();
            throw invalid(reason.code(), "observation", reason.message());
        }
        return current.fact();
    }

    private Observation requirePredecessor(Connection connection, String project, Fact fact, String period, Ref ref)
            throws SQLException {
        var current = first(connection, ACTIVE + " AND v.id=?", this::observation, project, ref.id());
        if (current == null) throw PilotAccess.invisible();
        PilotCommands.requireVersion(ref.version(), current.ref().version());
        var candidate = current.fact();
        var alias = findAlias(connection, project, fact.sourceFactId());
        // Uma identidade natural sem ID externo só pode ser corrigida conservando contorno e intervalo.
        boolean sameIdentity = alias == null
                ? candidate.sourceFactId() == null && fact.sourceFactId() == null
                    && fact.start().isEqual(candidate.start()) && fact.endExclusive().isEqual(candidate.endExclusive())
                : alias.ref().id().equals(current.ref().id());
        if (!sameIdentity || !period.equals(current.period()) || !fact.kind().equals(candidate.kind())
                || !fact.channel().equals(candidate.channel()) || !java.util.Objects.equals(fact.vehicle(), candidate.vehicle())) {
            throw conflict("CORRECAO_SEM_VINCULO", "A predecessora deve pertencer ao mesmo fato, contorno e período.");
        }
        return current;
    }

    private Observation findAlias(Connection connection, String project, String source) throws SQLException {
        if (source == null) return null;
        return first(connection, ACTIVE + """
                 AND EXISTS (SELECT 1 FROM pilot_observation_alias a WHERE a.project_id=h.project_id
                             AND a.observation_id=h.id AND a.source_fact=?)
                """, this::observation, project, source);
    }

    private Line getLine(Connection connection, String project, UUID id) throws SQLException {
        var line = first(connection, "SELECT * FROM pilot_import_row WHERE project_id=? AND id=?", imports::line, project, id);
        if (line == null) throw PilotAccess.invisible();
        return line;
    }

    List<Observation> active(String project, String period) {
        return database.read(connection -> query(connection, ACTIVE + " AND v.period=? ORDER BY v.start_at,v.id LIMIT 50",
                this::observation, project, period));
    }

    Observation version(String project, Ref ref) {
        var record = database.read(connection -> first(connection, """
                SELECT * FROM pilot_observation_revision WHERE project_id=? AND id=? AND version=?
                """, this::observation, project, ref.id(), ref.version()));
        if (record == null) throw PilotAccess.invisible();
        return record;
    }

    JsonNode freeze(PilotAccess.Scope scope, String actor, String key, String period) {
        return commands.execute(scope, actor, "CONGELAR_SELECAO_ED_EC", key, period, period, connection -> {
            intake.lockAndLoad(connection, scope.project());
            UUID id = UUID.randomUUID();
            // A seleção de cálculo é independente do fechamento do período.
            UUID event = audit.record(connection, scope.project(), actor, "CONGELAR_SELECAO_ED_EC", "PERMITIDO",
                    id.toString(), Map.of("period", period, "purpose", "RETRATO_SELECAO_SIMULADA"));
            update(connection, "INSERT INTO pilot_selection_snapshot (project_id,id,period,event_id) VALUES (?,?,?,?)",
                    scope.project(), id, period, event);
            int size = update(connection, """
                    INSERT INTO pilot_selection_entry
                    SELECT h.project_id, ?, h.id,h.version FROM pilot_observation_head h
                    JOIN pilot_observation_revision v ON (v.project_id,v.id,v.version)=(h.project_id,h.id,h.version)
                    WHERE h.project_id=? AND v.period=?
                    """, id, scope.project(), period);
            return new Selection(id, 1, period, event, size);
        });
    }

    List<Selection> selections(String project, String period) {
        return database.read(connection -> query(connection, """
                SELECT s.id,s.period,s.event_id,(SELECT count(*) FROM pilot_selection_entry e
                    WHERE e.project_id=s.project_id AND e.snapshot_id=s.id) AS amount
                FROM pilot_selection_snapshot s WHERE s.project_id=? AND s.period=?
                ORDER BY s.selected_at DESC LIMIT 50
                """, row -> new Selection(row.getObject(1, UUID.class), 1, row.getString(2),
                row.getObject(3, UUID.class), row.getInt(4)), project, period));
    }

    SelectionPage selection(String project, String period, UUID snapshot, String cursor) {
        UUID after = null;
        if (cursor != null) {
            try {
                after = UUID.fromString(EnergyImports.decodeCursor(cursor));
            } catch (IllegalArgumentException error) {
                throw invalid("CURSOR_INVALIDO", "cursor", "O cursor da seleção é inválido.");
            }
        }
        UUID previous = after;
        return database.read(connection -> {
            if (first(connection, "SELECT id FROM pilot_selection_snapshot WHERE project_id=? AND id=? AND period=?",
                    row -> row.getObject(1, UUID.class), project, snapshot, period) == null) throw PilotAccess.invisible();
            var rows = query(connection, """
                    SELECT v.* FROM pilot_selection_entry e JOIN pilot_observation_revision v
                      ON (v.project_id,v.id,v.version) = (e.project_id,e.observation_id,e.observation_version)
                    WHERE e.project_id=? AND e.snapshot_id=? AND (CAST(? AS uuid) IS NULL OR e.observation_id>?)
                    ORDER BY e.observation_id LIMIT 51
                    """, this::observation, project, snapshot, previous, previous);
            String next = rows.size() > 50 ? EnergyImports.encodeCursor(rows.get(49).ref().id().toString()) : null;
            return new SelectionPage(rows.subList(0, Math.min(50, rows.size())), next);
        });
    }

    private Observation observation(ResultSet row) throws SQLException {
        return new Observation(new Ref(row.getObject("id", UUID.class), row.getInt("version")),
                row.getObject("predecessor", Integer.class), row.getObject("row_id", UUID.class), row.getString("period"),
                row.getInt("configuration_version"), row.getString("physical_hash"), imports.decode(row.getString("fact"), Fact.class),
                imports.decode(row.getString("resolution"), Resolution.class));
    }

    private static PilotProblem conflict(String code, String message) {
        return new PilotProblem(409, code, "observation", "OP-E6-06", message);
    }
}
