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
 * Privileges are three configured rosters of staff identities (importers, reviewers, owners); empty means
 * unavailable. Every decision carries its BASIS (D19): INDEPENDENT needs a reviewer who is not the importer;
 * OWNER_INTERNAL is the product owner's own technical decision, allowed only to the owners roster (even for a
 * candidate the owner imported) and never presented as an independent review. One final decision per candidate.
 */
@ApplicationScoped
class FleetAliasCandidates {
    static final String RULE = "CF-OPEN-005";
    static final int MAX_ITEMS = 500;
    static final Set<String> LEVELS = Set.of("EXACT", "MODEL_ONLY", "AMBIGUOUS");
    static final Set<String> BASES = Set.of("INDEPENDENT", "OWNER_INTERNAL");
    static final int MAX_BULK = 100;
    /** Operation both fleet sources must be independently approved for before any real candidate enters the queue. */
    static final String REQUIRED_OPERATION = "EXTRACT_TEXT";

    private static final Risk NAME_MATCH = new Risk("NAME_MATCH_ONLY",
            "A correspondência é só de nome: não prova propulsão elétrica, classe nem circulação do veículo registrado.");
    private static final Risk HOMONYM = new Risk("HOMONYM",
            "Risco de homônimo: o mesmo modelo pode existir com propulsão a combustão ou híbrida (por exemplo, versões "
                    + "antigas com o mesmo nome); o nome não prova que o registro seja elétrico.");
    private static final Risk DISTINCT_PROPULSION = new Risk("DISTINCT_PROPULSION",
            "A fonte de frota não informa propulsão e o PBEV lista só as versões elétricas: versões de propulsão distinta "
                    + "do mesmo modelo não aparecem como alternativa.");
    private static final Risk VERSION_UNCONFIRMED = new Risk("VERSION_UNCONFIRMED",
            "A versão do nome da Senatran não consta do PBEV; a linha escolhida é uma hipótese e a versão segue não confirmada.");
    private static final Risk MULTIPLE_CANDIDATES = new Risk("MULTIPLE_CANDIDATES",
            "Há mais de um modelo candidato no PBEV; nenhum foi escolhido automaticamente.");

    /** Risks that every consumer must show for a level; the reviewer must acknowledge them to accept (MODEL_ONLY, AMBIGUOUS). */
    static List<Risk> risksOf(String level) {
        return switch (level) {
            case "EXACT" -> List.of(NAME_MATCH);
            case "MODEL_ONLY" -> List.of(NAME_MATCH, HOMONYM, DISTINCT_PROPULSION, VERSION_UNCONFIRMED);
            default -> List.of(NAME_MATCH, HOMONYM, DISTINCT_PROPULSION, VERSION_UNCONFIRMED, MULTIPLE_CANDIDATES);
        };
    }

    record Match(String brand, String model, String version, String category, Integer pbevLine) {}
    record Item(String literalName, String recordKey, String level, String reason, List<Match> matches) {}
    record ImportInput(String observationPeriod, String pbevSourceSha256, String senatranSourceSha256, List<Item> items) {}
    record DecisionInput(String outcome, Integer selectedPbevLine, String reason, String basis, List<String> acknowledgedRisks) {}
    record Risk(String code, String text) {}
    record BulkItem(UUID candidateId, String outcome, Integer selectedPbevLine, String reason, String basis,
                    List<String> acknowledgedRisks) {}
    record BulkInput(List<BulkItem> items) {}
    record ItemResult(UUID candidateId, boolean ok, String code, String message) {}
    record BulkResult(boolean dryRun, boolean applied, int accepted, int rejected, int failed, List<ItemResult> results) {}
    record RightsCheck(String role, String sha256, String operation, boolean registered) {}
    record ItemReport(String literalName, String state, String level, List<String> riskCodes) {}
    record DryRun(boolean importable, boolean rightsGateEnabled, List<RightsCheck> rights, int newItems,
                  int alreadyPresent, List<ItemReport> items, String limits) {}
    record Imported(int created, int alreadyPresent) {}
    record Candidate(UUID id, String observationPeriod, String literalName, String recordKey, String level,
                     String pbevSourceSha256, List<Match> matches, String reason, UUID importedBy,
                     OffsetDateTime importedAt, String state, UUID decidedBy, OffsetDateTime decidedAt,
                     Integer selectedPbevLine, String decisionReason, String decisionBasis, String senatranSourceSha256,
                     List<Risk> risks, String limits) {}

    private static final String LIMITS = "Candidato de alias para revisão: não é alias, não cria modelo nem classifica o "
            + "veículo, e aceitar não admite dados nem habilita cálculo. O catálogo canônico segue pelas suas rotas.";

    private final PilotDatabase database;
    private final ComplianceStaff staff;
    private final ObjectMapper mapper;
    private final Set<UUID> importers;
    private final Set<UUID> reviewers;
    private final Set<UUID> owners;
    private final boolean rightsGate;

    FleetAliasCandidates(PilotDatabase database, ComplianceStaff staff, ObjectMapper mapper,
            @ConfigProperty(name = "carbonflow.platform.fleet-alias-candidates.importers") Optional<String> importers,
            @ConfigProperty(name = "carbonflow.platform.fleet-alias-candidates.reviewers") Optional<String> reviewers,
            @ConfigProperty(name = "carbonflow.platform.fleet-alias-candidates.owners") Optional<String> owners,
            @ConfigProperty(name = "carbonflow.platform.fleet-alias-candidates.rights-gate", defaultValue = "true") boolean rightsGate) {
        this.rightsGate = rightsGate;
        this.database = database;
        this.staff = staff;
        this.mapper = mapper;
        this.importers = roster(importers);
        this.reviewers = roster(reviewers);
        this.owners = roster(owners);
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
            if (!importers.contains(actor.id()) && !reviewers.contains(actor.id()) && !owners.contains(actor.id())) throw hidden();
            ComplianceStaff.audit(c, actor.id(), null, "VIEW_FLEET_ALIAS_CANDIDATES");
            return load(c, null, state);
        });
    }

    private void validateBatch(ImportInput input) {
        if (input == null || input.observationPeriod() == null
                || !input.observationPeriod().matches("[0-9]{4}-(0[1-9]|1[0-2])")
                || input.pbevSourceSha256() == null || !input.pbevSourceSha256().matches("[a-f0-9]{64}")
                || input.senatranSourceSha256() == null || !input.senatranSourceSha256().matches("[a-f0-9]{64}")
                || input.items() == null || input.items().isEmpty() || input.items().size() > MAX_ITEMS)
            throw invalid("lote");
        var seen = new HashSet<String>();
        for (var item : input.items()) {
            validate(item);
            if (!seen.add(item.literalName().strip())) throw invalid("literalName");
        }
    }

    /** A source is registered for the platform only with an independently APPROVED, effective licence for the operation. */
    private boolean rightsRegistered(java.sql.Connection c, String sha256, String operation) throws SQLException {
        return Boolean.TRUE.equals(first(c, """
                SELECT true FROM app_source_version s
                JOIN app_content_license l ON l.source_id = s.source_id AND l.edition = s.edition
                JOIN LATERAL (SELECT version, outcome, decided_by, decided_at FROM app_content_license_decision
                              WHERE license_id = l.id ORDER BY version DESC LIMIT 1) d ON true
                JOIN app_content_license_capability cap ON cap.license_id = l.id AND cap.decision_version = d.version
                WHERE s.sha256 = ? AND s.origin = 'EXTERNAL' AND l.purpose = 'AUTHORIZED_PROCESSING'
                  AND cap.operation = ? AND d.outcome = 'APPROVED' AND l.proof_sha256 IS NOT NULL
                  AND l.submitted_by <> d.decided_by AND d.decided_at <= statement_timestamp()
                  AND l.starts_at <= statement_timestamp() AND (l.ends_at IS NULL OR l.ends_at > statement_timestamp())
                LIMIT 1
                """, r -> r.getBoolean(1), sha256, operation));
    }

    private List<RightsCheck> rightsOf(java.sql.Connection c, ImportInput input) throws SQLException {
        return List.of(
                new RightsCheck("PBEV", input.pbevSourceSha256(), REQUIRED_OPERATION,
                        rightsRegistered(c, input.pbevSourceSha256(), REQUIRED_OPERATION)),
                new RightsCheck("SENATRAN", input.senatranSourceSha256(), REQUIRED_OPERATION,
                        rightsRegistered(c, input.senatranSourceSha256(), REQUIRED_OPERATION)));
    }

    /** Validates and reports exactly what an import would do, writing nothing. Missing rights block it but are reported. */
    DryRun dryRunImport(String token, ImportInput input) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!importers.contains(actor.id()) && !owners.contains(actor.id())) throw hidden();
            validateBatch(input);
            var rights = rightsOf(c, input);
            var reports = new java.util.ArrayList<ItemReport>();
            int fresh = 0;
            for (var item : input.items()) {
                boolean present = Boolean.TRUE.equals(first(c, """
                        SELECT true FROM app_fleet_alias_candidate
                        WHERE literal_name=? AND observation_period=? AND pbev_source_sha256=?
                        """, r -> r.getBoolean(1), item.literalName().strip(), input.observationPeriod(), input.pbevSourceSha256()));
                if (!present) fresh++;
                reports.add(new ItemReport(item.literalName().strip(), present ? "ALREADY_PRESENT" : "NEW", item.level(),
                        risksOf(item.level()).stream().map(Risk::code).toList()));
            }
            ComplianceStaff.audit(c, actor.id(), null, "VIEW_FLEET_ALIAS_CANDIDATES");
            return new DryRun(!rightsGate || rights.stream().allMatch(RightsCheck::registered), rightsGate, rights, fresh,
                    input.items().size() - fresh, reports,
                    "Simulação: nada foi gravado. Importar não aprova alias, não classifica o veículo e não admite dados.");
        });
    }

    Imported importBatch(String token, ImportInput input) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!importers.contains(actor.id()) && !owners.contains(actor.id())) throw hidden();
            validateBatch(input);
            if (rightsGate) {
                var missing = rightsOf(c, input).stream().filter(r -> !r.registered()).map(RightsCheck::role).toList();
                if (!missing.isEmpty()) throw new PilotProblem(403, "DIREITOS_NAO_REGISTRADOS", "sources", RULE,
                        "Direitos de processamento sem aprovação independente vigente para: " + String.join(", ", missing)
                                + ". Registre e aprove a licença (EXTRACT_TEXT) antes de importar.");
            }
            int created = 0;
            for (var item : input.items()) {
                int rows;
                try {
                    rows = update(c, """
                            INSERT INTO app_fleet_alias_candidate(id,observation_period,literal_name,record_key,level,
                                pbev_source_sha256,matches,reason,imported_by,senatran_source_sha256)
                            VALUES (?,?,?,?,?,?,?::jsonb,?,?,?)
                            ON CONFLICT (literal_name, observation_period, pbev_source_sha256) DO NOTHING
                            """, UUID.randomUUID(), input.observationPeriod(), item.literalName().strip(),
                            item.recordKey(), item.level(), input.pbevSourceSha256(), mapper.writeValueAsString(item.matches()),
                            item.reason().strip(), actor.id(), input.senatranSourceSha256());
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
            if (!reviewers.contains(actor.id()) && !owners.contains(actor.id())) throw hidden();
            checkDecision(c, actor.id(), id, input);
            insertDecision(c, actor.id(), id, input);
            return load(c, id, null).get(0);
        });
    }

    /** Never writes. Throws the same problems a real decision would. Matching of names alone never decides anything. */
    private Candidate checkDecision(java.sql.Connection c, UUID actor, UUID id, DecisionInput input) throws SQLException {
        String basis = input == null || input.basis() == null ? "INDEPENDENT" : input.basis();
        if (!BASES.contains(basis)) throw invalid("basis");
        if (input == null || input.outcome() == null || !Set.of("ACCEPTED", "REJECTED").contains(input.outcome())
                || input.reason() == null || input.reason().isBlank() || input.reason().strip().length() < 10
                || input.reason().strip().length() > 1000) throw invalid("decisao");
        UUID importer = first(c, "SELECT imported_by FROM app_fleet_alias_candidate WHERE id=? FOR UPDATE",
                r -> r.getObject(1, UUID.class), id);
        if (importer == null) throw new PilotProblem(404, "CANDIDATO_INEXISTENTE", "id", RULE, "Candidato não encontrado.");
        var current = load(c, id, null).get(0);
        if (!current.state().equals("PENDING")) throw new PilotProblem(409, "CANDIDATO_DECIDIDO", "id", RULE,
                "O candidato já tem decisão.");
        boolean owner = basis.equals("OWNER_INTERNAL");
        if (!(owner ? owners : reviewers).contains(actor)) throw new PilotProblem(403, "BASE_NAO_AUTORIZADA", "basis", RULE,
                owner ? "Seu perfil não está na lista de responsáveis do produto." : "Seu perfil não está na lista de revisores independentes.");
        if (!owner && actor.equals(importer)) throw new PilotProblem(403, "DUPLA_CUSTODIA", "decidedBy", RULE,
                "Quem importou o candidato não pode decidir como revisor independente. Use a decisão interna do responsável, se for o seu papel.");
        if (input.outcome().equals("ACCEPTED")) {
            if (input.selectedPbevLine() == null || current.matches().stream()
                    .noneMatch(m -> input.selectedPbevLine().equals(m.pbevLine()))) throw invalid("selectedPbevLine");
            if (!current.level().equals("EXACT")) {
                var acknowledged = input.acknowledgedRisks() == null ? Set.<String>of() : new HashSet<>(input.acknowledgedRisks());
                var pending = current.risks().stream().map(Risk::code).filter(code -> !acknowledged.contains(code)).toList();
                if (!pending.isEmpty()) throw new PilotProblem(422, "RISCOS_NAO_RECONHECIDOS", "acknowledgedRisks", RULE,
                        "Aceitar um candidato " + current.level() + " exige reconhecer cada risco: " + String.join(", ", pending) + ".");
            }
        } else if (input.selectedPbevLine() != null) throw invalid("selectedPbevLine");
        return current;
    }

    private void insertDecision(java.sql.Connection c, UUID actor, UUID id, DecisionInput input) throws SQLException {
        String basis = input.basis() == null ? "INDEPENDENT" : input.basis();
        update(c, "INSERT INTO app_fleet_alias_candidate_decision(candidate_id,outcome,selected_pbev_line,reason,decided_by,decision_basis) "
                + "VALUES (?,?,?,?,?,?)", id, input.outcome(), input.selectedPbevLine(), input.reason().strip(), actor, basis);
        ComplianceStaff.audit(c, actor, null, "DECIDE_FLEET_ALIAS_CANDIDATE");
    }

    /**
     * Applies HUMAN decisions listed one by one (D): each item names its candidate, outcome, reason and, for MODEL_ONLY or
     * AMBIGUOUS, the risks acknowledged. There is no "accept all" and no default outcome. Dry run reports every item;
     * applying is all-or-nothing, so a single invalid item leaves the queue untouched.
     */
    BulkResult bulkDecide(String token, BulkInput input, boolean apply) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!reviewers.contains(actor.id()) && !owners.contains(actor.id())) throw hidden();
            if (input == null || input.items() == null || input.items().isEmpty() || input.items().size() > MAX_BULK)
                throw invalid("lote");
            var results = new java.util.ArrayList<ItemResult>();
            var ids = new HashSet<UUID>();
            for (var item : input.items()) {
                if (item == null || item.candidateId() == null) throw invalid("candidateId");
                if (!ids.add(item.candidateId())) {
                    results.add(new ItemResult(item.candidateId(), false, "CANDIDATO_REPETIDO", "Candidato repetido no lote."));
                    continue;
                }
                try {
                    checkDecision(c, actor.id(), item.candidateId(), new DecisionInput(item.outcome(), item.selectedPbevLine(),
                            item.reason(), item.basis(), item.acknowledgedRisks()));
                    results.add(new ItemResult(item.candidateId(), true, "OK", "Decisão válida."));
                } catch (PilotProblem problem) {
                    results.add(new ItemResult(item.candidateId(), false, problem.code, problem.getMessage()));
                }
            }
            int failed = (int) results.stream().filter(r -> !r.ok()).count();
            boolean applied = apply && failed == 0;
            int accepted = 0, rejected = 0;
            for (var item : input.items()) {
                if ("ACCEPTED".equals(item.outcome())) accepted++;
                else if ("REJECTED".equals(item.outcome())) rejected++;
            }
            if (applied) for (var item : input.items())
                insertDecision(c, actor.id(), item.candidateId(), new DecisionInput(item.outcome(), item.selectedPbevLine(),
                        item.reason(), item.basis(), item.acknowledgedRisks()));
            else ComplianceStaff.audit(c, actor.id(), null, "VIEW_FLEET_ALIAS_CANDIDATES");
            return new BulkResult(!apply, applied, accepted, rejected, failed, results);
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
                       d.decided_by,d.decided_at,d.selected_pbev_line,d.reason,d.decision_basis,k.senatran_source_sha256
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
                                (Integer) r.getObject(14), r.getString(15), r.getString(16), r.getString(17), risksOf(r.getString(5)), LIMITS);
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
                "Confira período (AAAA-MM), SHA-256 do PBEV, nomes, chave, nível, linhas do PBEV, motivo (mín. 10 caracteres na decisão), a linha escolhida e a base (INDEPENDENT ou OWNER_INTERNAL).");
    }
}
