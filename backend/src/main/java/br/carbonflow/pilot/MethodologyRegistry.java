package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Registry of methodologies kept by the platform administration (D18, CF-PACKAGES-001 slice 1). It records metadata
 * only and is append-only. Registering a methodology makes it selectable as a candidate with an explicit gap: it
 * creates no package, admits no data, grants no licence and enables no calculation or external act.
 */
@ApplicationScoped
class MethodologyRegistry {
    record Entry(String program, String methodology, String edition, int version, String title,
                 String rightsHolder, String officialUri, String origin, OffsetDateTime registeredAt,
                 String digest, boolean hasPackage) {}
    record Input(String program, String methodology, String edition, String title, String rightsHolder,
                 String officialUri) {}
    /** Derived state of a project's candidate, never stored: what the platform knows about it today. */
    record CandidateStatus(String state, String methodology, String edition, Integer registryVersion) {}
    record Saved(Entry entry, boolean created) {}

    private static final String RULE = "CF-PACKAGES-001";
    private final PilotDatabase database;
    private final ComplianceStaff staff;
    private final Set<UUID> managers;

    MethodologyRegistry(PilotDatabase database, ComplianceStaff staff,
            @ConfigProperty(name = "carbonflow.platform.methodology-registry.managers") Optional<String> configured) {
        this.database = database;
        this.staff = staff;
        try {
            this.managers = configured.map(value -> Arrays.stream(value.split(",", -1))
                    .map(String::strip).filter(part -> !part.isEmpty())
                    .map(UUID::fromString).collect(Collectors.toUnmodifiableSet()))
                    .orElse(Set.of());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Cadastro de gestores do registro de metodologias inválido.");
        }
    }

    List<Entry> list(String token) {
        return database.transaction(connection -> {
            ComplianceStaff.Staff actor = requireManager(connection, token);
            ComplianceStaff.audit(connection, actor.id(), null, "VIEW_METHODOLOGY_REGISTRY");
            return latest(connection);
        });
    }

    Saved register(String token, String ifMatch, Input received) {
        Input input = normalize(received);
        int expected = PilotCommands.expected(ifMatch);
        return database.transaction(connection -> {
            ComplianceStaff.Staff actor = requireManager(connection, token);
            // Serialise writers of the same key: the version check below must see the newest row.
            first(connection, "SELECT pg_advisory_xact_lock(hashtext(?))", row -> Boolean.TRUE,
                    "methodology-registry|" + input.program() + "|" + input.methodology() + "|" + input.edition());
            Entry current = latest(connection, input.program(), input.methodology(), input.edition());
            if (current != null && sameContent(current, input)) return new Saved(current, false);
            PilotCommands.requireVersion(expected, current == null ? 0 : current.version());
            int next = current == null ? 1 : current.version() + 1;
            String digest = digest(input, next);
            update(connection, """
                    INSERT INTO app_methodology_registry
                      (program,methodology,methodology_edition,version,title,rights_holder,official_uri,
                       origin,registered_by,digest)
                    VALUES (?,?,?,?,?,?,?,'ADMIN',?,?)
                    """, input.program(), input.methodology(), input.edition(), next, input.title(),
                    input.rightsHolder(), input.officialUri(), actor.id(), digest);
            ComplianceStaff.audit(connection, actor.id(), null, "REGISTER_METHODOLOGY");
            return new Saved(latest(connection, input.program(), input.methodology(), input.edition()), true);
        });
    }

    /** Latest version of every registered key, whether or not a code package exists for it. */
    static List<Entry> latest(Connection connection) throws SQLException {
        return query(connection, """
                SELECT DISTINCT ON (program,methodology,methodology_edition)
                       program,methodology,methodology_edition,version,title,rights_holder,official_uri,
                       origin,registered_at,digest
                FROM app_methodology_registry
                ORDER BY program,methodology,methodology_edition,version DESC
                """, MethodologyRegistry::row);
    }

    private static Entry latest(Connection connection, String program, String methodology, String edition)
            throws SQLException {
        return first(connection, """
                SELECT program,methodology,methodology_edition,version,title,rights_holder,official_uri,
                       origin,registered_at,digest
                FROM app_methodology_registry
                WHERE program=? AND methodology=? AND methodology_edition=?
                ORDER BY version DESC LIMIT 1
                """, MethodologyRegistry::row, program, methodology, edition);
    }

    private static Entry row(java.sql.ResultSet r) throws SQLException {
        String methodology = r.getString(2), edition = r.getString(3);
        return new Entry(r.getString(1), methodology, edition, r.getInt(4), r.getString(5), r.getString(6),
                r.getString(7), r.getString(8), r.getObject(9, OffsetDateTime.class), r.getString(10),
                MethodologyPackages.hasPackage(methodology, edition));
    }

    /** Value the triage stores for a registered methodology that has no package. */
    static String candidateValue(Entry entry) {
        return entry.methodology() + " V" + entry.edition();
    }

    /** Registered methodologies without a code package, as triage options: a candidate with an explicit gap. */
    static List<ProjectJourney.Option> unpackaged(Connection connection) throws SQLException {
        return latest(connection).stream().filter(entry -> !entry.hasPackage())
                .map(entry -> new ProjectJourney.Option(candidateValue(entry), entry.title())).toList();
    }

    static CandidateStatus candidateStatus(Connection connection, String candidate) throws SQLException {
        if (candidate == null || candidate.isBlank()) return new CandidateStatus("NONE", null, null, null);
        if (ProjectJourney.profile(candidate) != ProjectJourney.GENERAL) {
            var profile = ProjectJourney.profile(candidate);
            return new CandidateStatus("PACKAGE", profile.candidate(), profile.edition(), null);
        }
        String value = candidate.strip().toUpperCase(Locale.ROOT);
        for (Entry entry : latest(connection)) {
            if (value.equals(candidateValue(entry).toUpperCase(Locale.ROOT))
                    || value.equals(entry.methodology())) {
                return new CandidateStatus(entry.hasPackage() ? "PACKAGE" : "REGISTERED_NO_PACKAGE",
                        entry.methodology(), entry.edition(), entry.version());
            }
        }
        return new CandidateStatus("UNREGISTERED", null, null, null);
    }

    private static boolean sameContent(Entry current, Input input) {
        return current.title().equals(input.title()) && current.rightsHolder().equals(input.rightsHolder())
                && java.util.Objects.equals(current.officialUri(), input.officialUri());
    }

    static String digest(Input input, int version) {
        String canonical = String.join("|", input.program(), input.methodology(), input.edition(),
                Integer.toString(version), input.title(), input.rightsHolder(),
                input.officialUri() == null ? "" : input.officialUri());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static Input normalize(Input value) {
        if (value == null) throw invalid("body");
        String program = strip(value.program()), methodology = strip(value.methodology());
        String edition = strip(value.edition()), title = collapse(value.title());
        String holder = collapse(value.rightsHolder()), uri = value.officialUri() == null ? null : value.officialUri().strip();
        if (uri != null && uri.isEmpty()) uri = null;
        if (program == null || !program.matches("[A-Z][A-Z0-9_-]{1,39}")) throw invalid("program");
        if (methodology == null || !methodology.matches("[A-Z][A-Z0-9_-]{2,39}")) throw invalid("methodology");
        if (edition == null || !edition.matches("[0-9A-Za-z][0-9A-Za-z._-]{0,19}")) throw invalid("edition");
        if (title == null || title.length() < 3 || title.length() > 200) throw invalid("title");
        if (holder == null || holder.isEmpty() || holder.length() > 200) throw invalid("rightsHolder");
        if (uri != null && !uri.matches("https://[^\\s]{4,250}")) throw invalid("officialUri");
        return new Input(program, methodology, edition, title, holder, uri);
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    /** Single-line text without control characters. */
    private static String collapse(String value) {
        if (value == null) return null;
        String text = value.strip();
        if (text.codePoints().anyMatch(Character::isISOControl)) throw invalid("text");
        return text.replaceAll("\\s+", " ");
    }

    private ComplianceStaff.Staff requireManager(Connection connection, String token) throws SQLException {
        ComplianceStaff.Staff actor = staff.require(connection, token);
        if (!managers.contains(actor.id())) throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "manager", RULE,
                "Registro de metodologias não disponível para este perfil.");
        return actor;
    }

    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("REGISTRO_INVALIDO", field, RULE,
                "Confira programa, metodologia, edição, título, titular dos direitos e endereço oficial (https).");
    }
}
