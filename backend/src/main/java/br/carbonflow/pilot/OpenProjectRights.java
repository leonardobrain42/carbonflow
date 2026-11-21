package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Holder and contract records a project can point at.
 * A row does not create a legal proponent, a grant, or an admitted proof.
 */
@ApplicationScoped
class OpenProjectRights {
    static final String RULE = "CF-IMP-013";
    record PartyInput(String kind, String displayName) {}
    record ContractInput(String partyCode, String label) {}
    record Party(String code, int version, String kind, String displayName) {}
    record Contract(String code, int version, String partyCode, int partyVersion, String label) {}
    record Catalog(List<Party> parties, List<Contract> contracts,
        boolean createsLegalPower, boolean admitsRights) {}
    private record Saved(String project, String target, int revision, byte[] fingerprint) {}

    private final DynamicProjectScope scope;
    OpenProjectRights(DynamicProjectScope scope) { this.scope = scope; }

    Catalog get(String token, String project) {
        return scope.read(token, project, "VIEW_OPEN_APPLICABILITY",
            (connection, context) -> catalog(connection, context.id()));
    }

    Catalog saveParty(String token, String project, String code, String ifMatch, String key, PartyInput received) {
        return scope.write(token, project, "SAVE_OPEN_APPLICABILITY", (connection, context) -> {
            String party = code(code, "party");
            PartyInput input = party(received);
            return remember(connection, context, project, "party:" + party, key, ifMatch,
                context.id() + "|party|" + party + "|" + input,
                currentParty(connection, project, party), () -> {
                    int next = currentParty(connection, project, party) + 1;
                    update(connection, """
                        INSERT INTO app_project_rights_party
                          (project_id,organization_id,environment,party_code,version,kind,display_name,author_id)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, context.id(), context.organization(), context.environment(), party, next,
                        input.kind(), input.displayName(), context.actor().id());
                    return next;
                });
        });
    }

    Catalog saveContract(String token, String project, String code, String ifMatch, String key, ContractInput received) {
        return scope.write(token, project, "SAVE_OPEN_APPLICABILITY", (connection, context) -> {
            String contract = code(code, "contract");
            ContractInput input = contract(received);
            Party holder = latestParty(connection, project, input.partyCode());
            if (holder == null) throw invalid("partyCode");
            return remember(connection, context, project, "contract:" + contract, key, ifMatch,
                context.id() + "|contract|" + contract + "|" + holder.code() + "|" + holder.version() + "|" + input.label(),
                currentContract(connection, project, contract), () -> {
                    int next = currentContract(connection, project, contract) + 1;
                    update(connection, """
                        INSERT INTO app_project_rights_contract
                          (project_id,organization_id,environment,contract_code,version,
                           party_code,party_version,label,author_id)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """, context.id(), context.organization(), context.environment(), contract, next,
                        holder.code(), holder.version(), input.label(), context.actor().id());
                    return next;
                });
        });
    }

    /** The declaration may point only at a contract that belongs to the selected holder. */
    static boolean linked(Connection connection, String project, String partyCode, String contractCode)
            throws SQLException {
        if (partyCode == null || contractCode == null) return false;
        return first(connection, """
            SELECT 1 FROM app_project_rights_contract c
            JOIN app_project_rights_party p
              ON p.project_id=c.project_id AND p.party_code=c.party_code AND p.version=c.party_version
            WHERE c.project_id=? AND c.contract_code=? AND c.party_code=?
              AND c.version=(SELECT max(version) FROM app_project_rights_contract
                             WHERE project_id=? AND contract_code=?)
            """, r -> true, project, contractCode, partyCode, project, contractCode) != null;
    }

    static String holderName(Connection connection, String project, String partyCode) throws SQLException {
        Party party = latestParty(connection, project, partyCode);
        return party == null ? null : party.displayName();
    }

    private Catalog remember(Connection connection, DynamicProjectScope.Context context, String project,
            String target, String key, String ifMatch, String fingerprint, int current,
            SqlInt insert) throws SQLException {
        String command = PilotCommands.key(key);
        int expected = PilotCommands.expected(ifMatch);
        byte[] digest = AccessSecrets.digest(fingerprint);
        Saved previous = first(connection, """
            SELECT project_id,target_code,revision,fingerprint FROM app_project_rights_command
            WHERE actor_id=? AND command_key=?
            """, r -> new Saved(r.getString(1), r.getString(2), r.getInt(3), r.getBytes(4)),
            context.actor().id(), command);
        if (previous != null) {
            if (!previous.project().equals(project) || !previous.target().equals(target)
                    || !MessageDigest.isEqual(previous.fingerprint(), digest))
                throw new PilotProblem(409, "CHAVE_REUTILIZADA", "Idempotency-Key", RULE,
                    "Esta chave identifica outro registro.");
            return catalog(connection, project);
        }
        if (expected != current)
            throw new PilotProblem(412, "VERSAO_DIVERGENTE", "If-Match", RULE,
                "O registro mudou. Confira a versão antes de salvar.");
        int next = insert.get();
        update(connection, """
            INSERT INTO app_project_rights_command
              (actor_id,command_key,project_id,fingerprint,target_code,revision)
            VALUES (?,?,?,?,?,?)
            """, context.actor().id(), command, project, digest, target, next);
        return catalog(connection, project);
    }

    private static Catalog catalog(Connection connection, String project) throws SQLException {
        List<Party> parties = query(connection, """
            SELECT DISTINCT ON (party_code) party_code,version,kind,display_name
            FROM app_project_rights_party WHERE project_id=?
            ORDER BY party_code, version DESC
            """, r -> new Party(r.getString(1), r.getInt(2), r.getString(3), r.getString(4)), project);
        List<Contract> contracts = query(connection, """
            SELECT DISTINCT ON (contract_code) contract_code,version,party_code,party_version,label
            FROM app_project_rights_contract WHERE project_id=?
            ORDER BY contract_code, version DESC
            """, r -> new Contract(r.getString(1), r.getInt(2), r.getString(3), r.getInt(4), r.getString(5)),
            project);
        return new Catalog(parties, contracts, false, false);
    }

    private static Party latestParty(Connection connection, String project, String code) throws SQLException {
        return first(connection, """
            SELECT party_code,version,kind,display_name FROM app_project_rights_party
            WHERE project_id=? AND party_code=? ORDER BY version DESC LIMIT 1
            """, r -> new Party(r.getString(1), r.getInt(2), r.getString(3), r.getString(4)), project, code);
    }

    private static int currentParty(Connection connection, String project, String code) throws SQLException {
        Integer value = first(connection, """
            SELECT max(version) FROM app_project_rights_party WHERE project_id=? AND party_code=?
            """, r -> (Integer) r.getObject(1), project, code);
        return value == null ? 0 : value;
    }

    private static int currentContract(Connection connection, String project, String code) throws SQLException {
        Integer value = first(connection, """
            SELECT max(version) FROM app_project_rights_contract WHERE project_id=? AND contract_code=?
            """, r -> (Integer) r.getObject(1), project, code);
        return value == null ? 0 : value;
    }

    private static PartyInput party(PartyInput raw) {
        if (raw == null || raw.kind() == null
                || !Set.of("ORGANIZATION", "PERSON").contains(raw.kind())) throw invalid("kind");
        return new PartyInput(raw.kind(), text(raw.displayName(), 2, 200, "displayName"));
    }

    private static ContractInput contract(ContractInput raw) {
        if (raw == null) throw invalid("contract");
        return new ContractInput(code(raw.partyCode(), "partyCode"), text(raw.label(), 2, 300, "label"));
    }

    static String code(String raw, String field) {
        if (raw == null) throw invalid(field);
        String value = raw.strip();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,40}")) throw invalid(field);
        return value.toUpperCase(Locale.ROOT);
    }

    private static String text(String raw, int min, int max, String field) {
        if (raw == null) throw invalid(field);
        String value = raw.strip();
        if (value.length() < min || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw invalid(field);
        return value;
    }

    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("DIREITO_INVALIDO", field, RULE,
            "Confira o titular e o contrato. O registro não cria PP nem concede poder.");
    }

    @FunctionalInterface
    private interface SqlInt { int get() throws SQLException; }
}
