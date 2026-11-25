package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * #94/#95: review queue of alias CANDIDATES between literal SENATRAN names and electric rows of the Inmetro PBEV
 * table. A candidate is evidence for a reviewer, never an alias. This class never touches the canonical catalog
 * (models, aliases), a decision does not create either of them, and nothing here admits data, rates a vehicle or
 * enables calculation. Registering the model and its alias still goes through the existing catalog routes.
 *
 * Privileges are two configured rosters of staff identities (importers, reviewers); empty means unavailable. The
 * person who imported a candidate cannot decide on it. One final decision per candidate.
 */
@ApplicationScoped
class FleetAliasCandidates {
    static final String RULE = "CF-OPEN-005";
    static final int MAX_ITEMS = 500;
    static final Set<String> LEVELS = Set.of("EXACT", "MODEL_ONLY", "AMBIGUOUS");

    record Match(String brand, String model, String version, String category, Integer pbevLine) {}
    record Item(String literalName, String recordKey, String level, String reason, List<Match> matches) {}
    record ImportInput(String observationPeriod, String pbevSourceSha256, List<Item> items) {}
    record DecisionInput(String outcome, Integer selectedPbevLine, String reason) {}
    record Imported(int created, int alreadyPresent) {}
    record Candidate(UUID id, String observationPeriod, String literalName, String recordKey, String level,
                     String pbevSourceSha256, List<Match> matches, String reason, UUID importedBy,
                     OffsetDateTime importedAt, String state, UUID decidedBy, OffsetDateTime decidedAt,
                     Integer selectedPbevLine, String decisionReason, String limits) {}

    private static final String LIMITS = "Candidato de alias para revisão: não é alias, não cria modelo nem classifica o "
            + "veículo, e aceitar não admite dados nem habilita cálculo. O catálogo canônico segue pelas suas rotas.";

    private final PilotDatabase database;
    private final ComplianceStaff staff;
    private final ObjectMapper mapper;
    private final Set<UUID> importers;
    private final Set<UUID> reviewers;

    FleetAliasCandidates(PilotDatabase database, ComplianceStaff staff, ObjectMapper mapper,
            @ConfigProperty(name = "carbonflow.platform.fleet-alias-candidates.importers") Optional<String> importers,
            @ConfigProperty(name = "carbonflow.platform.fleet-alias-candidates.reviewers") Optional<String> reviewers) {
        this.database = database;
        this.staff = staff;
        this.mapper = mapper;
        this.importers = roster(importers);
        this.reviewers = roster(reviewers);
    }

    private static Set<UUID> roster(Optional<String> value) {
        try {
            return value.map(text -> Arrays.stream(text.split(",", -1)).map(String::strip)
                    .filter(part -> !part.isEmpty()).map(UUID::fromString).collect(Collectors.toUnmodifiableSet()))
                    .orElse(Set.of());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Cadastro de responsáveis por candidatos de alias inválido.");
        }
    }

    List<Candidate> list(String token, String state) {
        if (state != null && !Set.of("PENDING", "ACCEPTED", "REJECTED").contains(state)) throw invalid("state");
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!importers.contains(actor.id()) && !reviewers.contains(actor.id())) throw hidden();
            ComplianceStaff.audit(c, actor.id(), null, "VIEW_FLEET_ALIAS_CANDIDATES");
            return load(c, null, state);
        });
    }

    Imported importBatch(String token, ImportInput input) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!importers.contains(actor.id())) throw hidden();
            if (input == null || input.observationPeriod() == null
                    || !input.observationPeriod().matches("[0-9]{4}-(0[1-9]|1[0-2])")
                    || input.pbevSourceSha256() == null || !input.pbevSourceSha256().matches("[a-f0-9]{64}")
                    || input.items() == null || input.items().isEmpty() || input.items().size() > MAX_ITEMS)
                throw invalid("lote");
            int created = 0;
            var seen = new HashSet<String>();
            for (var item : input.items()) {
                validate(item);
                if (!seen.add(item.literalName().strip())) throw invalid("literalName");
                int rows;
                try {
                    rows = update(c, """
                            INSERT INTO app_fleet_alias_candidate(id,observation_period,literal_name,record_key,level,
                                pbev_source_sha256,matches,reason,imported_by)
                            VALUES (?,?,?,?,?,?,?::jsonb,?,?)
                            ON CONFLICT (literal_name, observation_period, pbev_source_sha256) DO NOTHING
                            """, UUID.randomUUID(), input.observationPeriod(), item.literalName().strip(),
                            item.recordKey(), item.level(), input.pbevSourceSha256(), mapper.writeValueAsString(item.matches()),
                            item.reason().strip(), actor.id());
                } catch (JsonProcessingException ex) {
                    throw invalid("matches");
                }
                created += rows;
            }
            ComplianceStaff.audit(c, actor.id(), null, "IMPORT_FLEET_ALIAS_CANDIDATES");
            return new Imported(created, input.items().size() - created);
        });
    }

    Candidate decide(String token, UUID id, DecisionInput input) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!reviewers.contains(actor.id())) throw hidden();
            if (input == null || input.outcome() == null || !Set.of("ACCEPTED", "REJECTED").contains(input.outcome())
                    || input.reason() == null || input.reason().isBlank() || input.reason().strip().length() < 10
                    || input.reason().strip().length() > 1000) throw invalid("decisao");
            UUID importer = first(c, "SELECT imported_by FROM app_fleet_alias_candidate WHERE id=? FOR UPDATE",
                    r -> r.getObject(1, UUID.class), id);
            if (importer == null) throw new PilotProblem(404, "CANDIDATO_INEXISTENTE", "id", RULE, "Candidato não encontrado.");
            var current = load(c, id, null).get(0);
            if (!current.state().equals("PENDING")) throw new PilotProblem(409, "CANDIDATO_DECIDIDO", "id", RULE,
                    "O candidato já tem decisão.");
            if (actor.id().equals(importer)) throw new PilotProblem(403, "DUPLA_CUSTODIA", "decidedBy", RULE,
                    "Quem importou o candidato não pode decidir sobre ele.");
            if (input.outcome().equals("ACCEPTED")) {
                if (input.selectedPbevLine() == null || current.matches().stream()
                        .noneMatch(m -> input.selectedPbevLine().equals(m.pbevLine()))) throw invalid("selectedPbevLine");
            } else if (input.selectedPbevLine() != null) throw invalid("selectedPbevLine");
            update(c, "INSERT INTO app_fleet_alias_candidate_decision(candidate_id,outcome,selected_pbev_line,reason,decided_by) "
                    + "VALUES (?,?,?,?,?)", id, input.outcome(), input.selectedPbevLine(), input.reason().strip(), actor.id());
            ComplianceStaff.audit(c, actor.id(), null, "DECIDE_FLEET_ALIAS_CANDIDATE");
            return load(c, id, null).get(0);
        });
    }

    private static void validate(Item item) {
        if (item == null || item.literalName() == null || item.literalName().isBlank()
                || item.literalName().strip().length() > 200
                || item.recordKey() == null || !item.recordKey().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                || item.level() == null || !LEVELS.contains(item.level())
                || item.reason() == null || item.reason().isBlank() || item.reason().strip().length() > 500
                || item.matches() == null || item.matches().isEmpty() || item.matches().size() > 50)
            throw invalid("item");
        var lines = new HashSet<Integer>();
        for (var match : item.matches())
            if (match == null || match.brand() == null || match.brand().isBlank() || match.model() == null
                    || match.model().isBlank() || match.pbevLine() == null || match.pbevLine() < 1
                    || !lines.add(match.pbevLine())) throw invalid("matches");
    }

    private List<Candidate> load(java.sql.Connection c, UUID only, String state) throws SQLException {
        return query(c, """
                SELECT k.id,k.observation_period,k.literal_name,k.record_key,k.level,k.pbev_source_sha256,k.matches::text,
                       k.reason,k.imported_by,k.imported_at,
                       CASE WHEN d.candidate_id IS NULL THEN 'PENDING' ELSE d.outcome END,
                       d.decided_by,d.decided_at,d.selected_pbev_line,d.reason
                FROM app_fleet_alias_candidate k LEFT JOIN app_fleet_alias_candidate_decision d ON d.candidate_id=k.id
                WHERE (?::uuid IS NULL OR k.id=?::uuid)
                  AND (?::text IS NULL OR (CASE WHEN d.candidate_id IS NULL THEN 'PENDING' ELSE d.outcome END)=?::text)
                ORDER BY k.imported_at DESC, k.literal_name
                LIMIT 500
                """, r -> {
                    try {
                        return new Candidate(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                                r.getString(5), r.getString(6), mapper.readValue(r.getString(7), new TypeReference<List<Match>>() {}),
                                r.getString(8), r.getObject(9, UUID.class), r.getObject(10, OffsetDateTime.class),
                                r.getString(11), r.getObject(12, UUID.class), r.getObject(13, OffsetDateTime.class),
                                (Integer) r.getObject(14), r.getString(15), LIMITS);
                    } catch (JsonProcessingException ex) {
                        throw new SQLException(ex);
                    }
                }, only, only, state, state);
    }

    private static PilotProblem hidden() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "candidates", RULE,
                "Candidatos de alias não disponíveis para este perfil.");
    }

    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("CANDIDATO_INVALIDO", field, RULE,
                "Confira período (AAAA-MM), SHA-256 do PBEV, nomes, chave, nível, linhas do PBEV, motivo (mín. 10 caracteres na decisão) e a linha escolhida.");
    }
}
