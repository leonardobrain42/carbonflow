package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * BL-DATA-01D: applies alias candidates that a HUMAN already ACCEPTED to the existing canonical catalog, in bulk.
 * For each accepted candidate: reuse or create the model named by the chosen PBEV line (born UNKNOWN/UNKNOWN, i.e. pending),
 * map the exact SENATRAN record key of the observation year to it, and classify ONLY when the operator supplies class,
 * propulsion and evidence for that item. Names never classify. Idempotent per candidate (re-running a file resumes it),
 * conflicts are reported and never overwritten, and applying is all-or-nothing. Nothing here admits data or enables E8.
 */
@ApplicationScoped
class FleetCatalogApplication {
    static final String RULE = "CF-OPEN-005";
    static final int MAX_ITEMS = 100;
    static final String NO_VERSION = "SEM VERSÃO NO PBEV";

    record Classification(String vehicleClass, String propulsion, String reason, String evidence) {}
    record Item(UUID candidateId, Classification classification) {}
    record Input(UUID organizationId, List<Item> items) {}
    record ItemResult(UUID candidateId, boolean ok, String code, String message, List<String> actions, UUID modelId) {}
    record Result(boolean dryRun, boolean applied, int planned, int alreadyApplied, int failed, List<ItemResult> results,
                  boolean usableForE8, String limits) {}

    private static final String LIMITS = "Aplica só decisões humanas já aceitas: cria ou reaproveita o modelo e o alias exato; "
            + "classifica apenas com classe, propulsão e evidência informadas; não admite dados, não avalia o veículo e não habilita E8.";

    private final PilotDatabase database;
    private final PlatformSourceWork managers;
    private final ObjectMapper mapper;

    FleetCatalogApplication(PilotDatabase database, PlatformSourceWork managers, ObjectMapper mapper) {
        this.database = database;
        this.managers = managers;
        this.mapper = mapper;
    }

    Result run(String token, Input input, boolean apply) {
        return database.transaction(c -> {
            var actor = managers.requireManager(c, token);
            if (input == null || input.organizationId() == null || !actor.organizations().contains(input.organizationId()))
                throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "organizationId", RULE, "Organização não disponível para este usuário.");
            if (input.items() == null || input.items().isEmpty() || input.items().size() > MAX_ITEMS)
                throw PilotProblem.invalid("LOTE_INVALIDO", "items", RULE, "Informe de 1 a " + MAX_ITEMS + " candidatos aceitos por lote.");
            var start = c.setSavepoint();
            var results = new ArrayList<ItemResult>();
            var seen = new HashSet<UUID>();
            int planned = 0, already = 0, failed = 0;
            for (var item : input.items()) {
                if (item == null || item.candidateId() == null) throw PilotProblem.invalid("LOTE_INVALIDO", "candidateId", RULE, "Item sem candidato.");
                ItemResult result;
                if (!seen.add(item.candidateId())) {
                    result = new ItemResult(item.candidateId(), false, "CANDIDATO_REPETIDO", "Candidato repetido no lote.", List.of(), null);
                } else {
                    try {
                        result = applyOne(c, actor.id(), input.organizationId(), item);
                    } catch (PilotProblem problem) {
                        result = new ItemResult(item.candidateId(), false, problem.code, problem.getMessage(), List.of(), null);
                    }
                }
                if (!result.ok()) failed++;
                else if ("JA_APLICADO".equals(result.code())) already++;
                else planned++;
                results.add(result);
            }
            boolean keep = apply && failed == 0;
            if (!keep) c.rollback(start); // dry run and any failure leave the catalog untouched (resume = run the file again)
            if (keep) ComplianceStaff.audit(c, actor.id(), null, "APPLY_FLEET_ALIAS_CANDIDATES");
            else ComplianceStaff.audit(c, actor.id(), null, "VIEW_FLEET_ALIAS_CANDIDATES");
            return new Result(!apply, keep, planned, already, failed, List.copyOf(results), false, LIMITS);
        });
    }

    private record Accepted(String period, String recordKey, int line, List<FleetAliasCandidates.Match> matches) {}

    /** Validates first, writes after: a problem thrown here has not written anything for this item. */
    private ItemResult applyOne(Connection c, UUID actor, UUID org, Item item) throws SQLException {
        UUID id = item.candidateId();
        var done = first(c, "SELECT organization_id, model_id FROM app_fleet_alias_application WHERE candidate_id=?",
                r -> new UUID[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class)}, id);
        if (done != null) {
            if (!done[0].equals(org)) throw problem(409, "ORGANIZACAO_DIFERENTE", "organizationId",
                    "Este candidato já foi aplicado em outra organização.");
            return new ItemResult(id, true, "JA_APLICADO", "Já aplicado antes; nada a fazer.", List.of(), done[1]);
        }
        var accepted = first(c, """
                SELECT k.observation_period, k.record_key, d.selected_pbev_line, k.matches::text
                FROM app_fleet_alias_candidate k JOIN app_fleet_alias_candidate_decision d ON d.candidate_id=k.id
                WHERE k.id=? AND d.outcome='ACCEPTED' FOR UPDATE OF k
                """, r -> {
                    try {
                        return new Accepted(r.getString(1), r.getString(2), r.getInt(3),
                                mapper.readValue(r.getString(4), new TypeReference<List<FleetAliasCandidates.Match>>() {}));
                    } catch (JsonProcessingException ex) {
                        throw new SQLException(ex);
                    }
                }, id);
        if (accepted == null) throw problem(409, "CANDIDATO_NAO_ACEITO", "candidateId",
                "Só candidatos com decisão humana ACEITA podem ser aplicados (inexistente, pendente ou rejeitado).");
        var match = accepted.matches().stream().filter(m -> m.pbevLine() != null && m.pbevLine() == accepted.line()).findFirst().orElse(null);
        if (match == null || blank(match.brand()) || blank(match.model())) throw problem(422, "LINHA_PBEV_INVALIDA", "candidateId",
                "A linha escolhida do PBEV não traz marca e modelo.");
        String maker = match.brand().strip(), name = match.model().strip();
        String variant = blank(match.version()) ? NO_VERSION : match.version().strip();
        if (maker.length() > 120 || name.length() > 120 || variant.length() > 120) throw problem(422, "NOME_LONGO", "candidateId",
                "Marca, modelo ou versão do PBEV excede 120 caracteres.");
        String key = FleetCompositionService.normalizeKey(accepted.recordKey());
        int year = Integer.parseInt(accepted.period().substring(0, 4));
        var classification = validated(item.classification());
        var actions = new ArrayList<String>();

        UUID model = first(c, "SELECT id FROM app_fleet_model WHERE organization_id=? AND manufacturer=? AND model_name=? AND variant=? "
                + "ORDER BY recorded_at LIMIT 1", r -> r.getObject(1, UUID.class), org, maker, name, variant);
        boolean createModel = model == null;
        UUID existingAlias = first(c, "SELECT model_id FROM app_fleet_model_alias WHERE organization_id=? AND observation_year=? AND record_key=?",
                r -> r.getObject(1, UUID.class), org, year, key);
        if (existingAlias != null && (createModel || !existingAlias.equals(model))) throw problem(409, "ALIAS_CONFLITANTE", "recordKey",
                "A chave " + key + " de " + year + " já aponta para outro modelo; resolva pelo catálogo, nada foi sobrescrito.");
        int currentVersion = 0;
        if (!createModel) {
            var current = first(c, "SELECT classification_version, vehicle_class, propulsion FROM app_fleet_model_effective WHERE id=?",
                    r -> new Object[] {r.getInt(1), r.getString(2), r.getString(3)}, model);
            currentVersion = (Integer) current[0];
            boolean pending = "UNKNOWN".equals(current[1]) && "UNKNOWN".equals(current[2]);
            if (classification != null && !pending && !(classification.vehicleClass().equals(current[1])
                    && classification.propulsion().equals(current[2]))) throw problem(409, "CLASSIFICACAO_DIVERGENTE", "classification",
                    "O modelo já tem outra classificação vigente (" + current[1] + "/" + current[2] + "); use a rota de classificação do catálogo.");
            if (classification != null && !pending) classification = null; // already current: nothing to record
        }
        // Writes.
        if (createModel) {
            model = UUID.randomUUID();
            update(c, "INSERT INTO app_fleet_model(id,organization_id,manufacturer,model_name,variant,vehicle_class,propulsion,created_by) "
                    + "VALUES (?,?,?,?,?,'UNKNOWN','UNKNOWN',?)", model, org, maker, name, variant, actor);
            actions.add("CREATE_MODEL");
        } else actions.add("REUSE_MODEL");
        boolean createAlias = existingAlias == null;
        if (createAlias) {
            update(c, "INSERT INTO app_fleet_model_alias(organization_id,observation_year,record_key,model_id,mapped_by) VALUES (?,?,?,?,?)",
                    org, year, key, model, actor);
            actions.add("CREATE_ALIAS");
        } else actions.add("ALIAS_EXISTS");
        Integer version = null;
        if (classification != null) {
            version = currentVersion + 1;
            update(c, "INSERT INTO app_fleet_model_classification(model_id,organization_id,version,vehicle_class,propulsion,reason,evidence,decided_by) "
                    + "VALUES (?,?,?,?,?,?,?,?)", model, org, version, classification.vehicleClass(), classification.propulsion(),
                    classification.reason().strip(), classification.evidence().strip(), actor);
            actions.add("CLASSIFY_" + classification.vehicleClass() + "_" + classification.propulsion());
        } else if (createModel || currentVersion == 0) actions.add("MODEL_STAYS_PENDING_CLASSIFICATION");
        update(c, "INSERT INTO app_fleet_alias_application(candidate_id,organization_id,model_id,observation_year,record_key,"
                + "created_model,created_alias,classification_version,applied_by) VALUES (?,?,?,?,?,?,?,?,?)",
                id, org, model, year, key, createModel, createAlias, version, actor);
        return new ItemResult(id, true, "OK", "Aplicável.", List.copyOf(actions), model);
    }

    private static Classification validated(Classification input) {
        if (input == null) return null;
        if (!Set.of("LDV", "HDV", "OTHER").contains(input.vehicleClass()) || !Set.of("BEV", "PHEV", "OTHER").contains(input.propulsion()))
            throw problem(422, "CLASSIFICACAO_INVALIDA", "classification",
                    "Informe classe (LDV, HDV, OTHER) e propulsão (BEV, PHEV, OTHER); desconhecido não se informa, o modelo já nasce pendente.");
        String reason = input.reason() == null ? "" : input.reason().strip();
        String evidence = input.evidence() == null ? "" : input.evidence().strip();
        if (reason.length() < 10 || reason.length() > 1000) throw problem(422, "CLASSIFICACAO_INVALIDA", "reason",
                "O motivo da classificação precisa ter de 10 a 1000 caracteres.");
        if (evidence.length() < 3 || evidence.length() > 1000) throw problem(422, "CLASSIFICACAO_INVALIDA", "evidence",
                "A classificação exige evidência (fonte e localizador), de 3 a 1000 caracteres.");
        return input;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static PilotProblem problem(int status, String code, String field, String message) {
        return new PilotProblem(status, code, field, RULE, message);
    }
}
