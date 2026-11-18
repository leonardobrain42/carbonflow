package br.carbonflow.pilot;

import static br.carbonflow.pilot.AccessSecrets.*;
import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class AccountAccess {
    static final String COOKIE = "carbonflow_account_session";
    static final int SESSION_SECONDS = 8 * 60 * 60;
    private static final int SESSION_LIMIT = 2;
    private static final int HASH_ITERATIONS = 600_000;
    private static final String ACTIVE_SESSION = """
            SELECT i.id, i.email_normalized, i.display_name
            FROM app_account_session s JOIN app_identity i ON i.id = s.identity_id
            WHERE s.token_hash = ? AND s.expires_at > statement_timestamp()
              AND i.status = 'ACTIVE' AND i.email_verified_at IS NOT NULL
            """;
    private static final Pattern EMAIL = Pattern.compile(
            "[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");

    enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }

    record Identity(UUID id, String email, String name) {}
    record Session(Identity identity, List<ProjectContext.Draft> projects) {}
    private record Credential(UUID identity, byte[] salt, byte[] hash, int iterations) {}
    private record Challenge(boolean usable, String consumedKey, byte[] consumedFingerprint) {}
    private record AccountState(String status, boolean emailVerified) {
        boolean active() {
            return "ACTIVE".equals(status) && emailVerified;
        }

        boolean accepts(Purpose purpose) {
            return purpose == Purpose.VERIFY_EMAIL ? "PENDING_VERIFICATION".equals(status) : active();
        }
    }

    private final PilotDatabase database;
    private final ProjectRepository projects;
    private final SandboxAccountMailbox mailbox;

    AccountAccess(PilotDatabase database, ProjectRepository projects, SandboxAccountMailbox mailbox) {
        this.database = database;
        this.projects = projects;
        this.mailbox = mailbox;
    }

    void register(String email, String displayName, String commandKey, String peer) {
        mailbox.requireAvailable();
        String recipient = email(email);
        limit("REGISTER", peer, recipient);
        if (displayName == null || displayName.isBlank() || displayName.length() > 120) {
            throw badField("name");
        }
        String key = PilotCommands.key(commandKey);
        database.transaction(connection -> {
            UUID identity = first(connection, """
                    INSERT INTO app_identity (id, email_normalized, display_name) VALUES (?, ?, ?)
                    ON CONFLICT (email_normalized) DO NOTHING RETURNING id
                    """, row -> row.getObject("id", UUID.class), UUID.randomUUID(), recipient, displayName.strip());
            if (identity != null) {
                event(connection, identity, "ACCOUNT_REGISTERED");
                issue(connection, identity, recipient, Purpose.VERIFY_EMAIL, key);
            }
            return null;
        });
    }

    void requestChallenge(Purpose purpose, String email, String commandKey, String peer) {
        mailbox.requireAvailable();
        String recipient = email(email);
        limit(purpose.name(), peer, recipient);
        String key = PilotCommands.key(commandKey);
        database.transaction(connection -> {
            UUID identity = first(connection, "SELECT id FROM app_identity WHERE email_normalized = ?",
                    row -> row.getObject("id", UUID.class), recipient);
            var state = identity == null ? null : accountForUpdate(connection, identity);
            if (state != null && state.accepts(purpose)) {
                issue(connection, identity, recipient, purpose, key);
            }
            return null;
        });
    }

    void complete(Purpose purpose, String token, String newPassword, String commandKey, String peer) {
        mailbox.requireEnabled();
        limit("COMPLETE_" + purpose.name(), peer, null);
        String key = PilotCommands.key(commandKey);
        if (!isToken(token)) {
            throw invalidChallenge();
        }
        requireNewPassword(newPassword);
        byte[] tokenHash = digest(token);
        byte[] receiptSalt = Arrays.copyOf(digest("account-completion:" + token), 16);
        database.transaction(connection -> {
            UUID actor = first(connection, """
                    SELECT identity_id FROM app_account_challenge WHERE token_hash = ? AND purpose = ?
                    """, row -> row.getObject("identity_id", UUID.class), tokenHash, purpose.name());
            if (actor == null) {
                throw invalidChallenge();
            }
            var state = accountForUpdate(connection, actor);
            Challenge challenge = first(connection, """
                    SELECT consumed_at IS NULL AND superseded_at IS NULL
                           AND expires_at > statement_timestamp() AS usable,
                           consumed_key, consumed_fingerprint
                    FROM app_account_challenge WHERE token_hash = ? AND purpose = ?
                    """, row -> new Challenge(row.getBoolean("usable"),
                    row.getString("consumed_key"), row.getBytes("consumed_fingerprint")), tokenHash, purpose.name());
            if (challenge != null && key.equals(challenge.consumedKey())) {
                byte[] fingerprint = passwordHash(newPassword, receiptSalt, HASH_ITERATIONS);
                if (!MessageDigest.isEqual(fingerprint, challenge.consumedFingerprint())) {
                    throw new PilotProblem(409, "CHAVE_REUSADA", "Idempotency-Key", "CF-IMP-003",
                            "A chave já identifica outro comando.");
                }
                return null;
            }
            if (challenge == null || !challenge.usable() || state == null || !state.accepts(purpose)) {
                throw invalidChallenge();
            }
            setPassword(connection, actor, newPassword);
            if (purpose == Purpose.VERIFY_EMAIL) {
                update(connection, """
                        UPDATE app_identity SET status = 'ACTIVE', email_verified_at = statement_timestamp() WHERE id = ?
                        """, actor);
                event(connection, actor, "EMAIL_VERIFIED");
            } else {
                update(connection, "DELETE FROM app_account_session WHERE identity_id = ?", actor);
                event(connection, actor, "PASSWORD_RESET");
            }
            byte[] fingerprint = passwordHash(newPassword, receiptSalt, HASH_ITERATIONS);
            update(connection, """
                    UPDATE app_account_challenge
                    SET consumed_at = statement_timestamp(), consumed_key = ?, consumed_fingerprint = ?
                    WHERE token_hash = ?
                    """, key, fingerprint, tokenHash);
            return null;
        });
    }

    String login(String email, String password, String previous, String peer) {
        mailbox.requireEnabled();
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        limit("LOGIN", peer, normalized.length() <= 254 ? normalized : null);
        if (!validEmail(normalized) || password == null || password.length() > 128) {
            throw unauthenticated();
        }
        var credential = database.read(connection -> first(connection, """
                SELECT c.identity_id, c.password_salt, c.password_hash, c.password_iterations
                FROM app_account_credential c JOIN app_identity i ON i.id = c.identity_id
                WHERE i.email_normalized = ?
                """, row -> new Credential(row.getObject("identity_id", UUID.class), row.getBytes("password_salt"),
                row.getBytes("password_hash"), row.getInt("password_iterations")), normalized));
        byte[] candidate = passwordHash(password, credential == null ? new byte[16] : credential.salt(),
                credential == null ? HASH_ITERATIONS : credential.iterations());
        if (credential == null || !MessageDigest.isEqual(candidate, credential.hash())) {
            throw unauthenticated();
        }
        String token = newToken();
        database.transaction(connection -> {
            first(connection, "SELECT pg_advisory_xact_lock(hashtextextended('app_account_session', 0))", row -> true);
            var state = accountForUpdate(connection, credential.identity());
            byte[] current = first(connection, "SELECT password_hash FROM app_account_credential WHERE identity_id = ?",
                    row -> row.getBytes("password_hash"), credential.identity());
            if (current == null || !MessageDigest.isEqual(current, credential.hash()) || state == null || !state.active()) {
                throw unauthenticated();
            }
            update(connection, """
                    DELETE FROM app_account_session WHERE expires_at <= statement_timestamp() OR token_hash = ?
                    """, isToken(previous) ? digest(previous) : null);
            long sessions = first(connection, "SELECT count(*) FROM app_account_session", row -> row.getLong(1));
            if (sessions >= SESSION_LIMIT) {
                throw new PilotProblem(409, "CAPACIDADE_DE_SESSOES", "session", "DEC-011",
                        "Encerre uma sessão ativa para entrar no sandbox.");
            }
            update(connection, """
                    INSERT INTO app_account_session (token_hash, identity_id, expires_at)
                    VALUES (?, ?, statement_timestamp() + make_interval(secs => ?))
                    """, digest(token), credential.identity(), SESSION_SECONDS);
            event(connection, credential.identity(), "SESSION_OPENED");
            return null;
        });
        return token;
    }

    Session session(String token) {
        var identity = database.read(connection -> requireIdentity(connection, token));
        return new Session(identity, projects.listDrafts(identity.id()));
    }

    Identity requireIdentity(Connection connection, String token) throws SQLException {
        mailbox.requireEnabled();
        if (!isToken(token)) {
            throw unauthenticated();
        }
        return activeIdentity(connection, ACTIVE_SESSION, digest(token));
    }

    Identity lockIdentity(Connection connection, String token) throws SQLException {
        var identity = requireIdentity(connection, token);
        // Use the same lock order as credential changes: account first, session second.
        first(connection, "SELECT id FROM app_identity WHERE id = ? FOR NO KEY UPDATE",
                row -> row.getObject("id", UUID.class), identity.id());
        return activeIdentity(connection, ACTIVE_SESSION + "FOR SHARE OF s", digest(token));
    }

    private static Identity activeIdentity(Connection connection, String sql, byte[] tokenHash) throws SQLException {
        Identity identity = first(connection, sql, row -> new Identity(row.getObject("id", UUID.class),
                row.getString("email_normalized"), row.getString("display_name")), tokenHash);
        if (identity == null) {
            throw unauthenticated();
        }
        return identity;
    }

    void logout(String token) {
        mailbox.requireEnabled();
        if (isToken(token)) {
            database.transaction(connection -> {
                UUID identity = first(connection, """
                        DELETE FROM app_account_session WHERE token_hash = ? RETURNING identity_id
                        """, row -> row.getObject("identity_id", UUID.class), digest(token));
                if (identity != null) {
                    event(connection, identity, "SESSION_CLOSED");
                }
                return null;
            });
        }
    }

    private void issue(Connection connection, UUID identity, String recipient, Purpose purpose, String key) throws SQLException {
        var alreadyIssued = first(connection, """
                SELECT true FROM app_account_challenge WHERE identity_id = ? AND purpose = ? AND command_key = ?
                """, row -> true, identity, purpose.name(), key);
        if (alreadyIssued != null) {
            return;
        }
        update(connection, """
                UPDATE app_account_challenge SET superseded_at = statement_timestamp()
                WHERE identity_id = ? AND purpose = ? AND superseded_at IS NULL AND consumed_at IS NULL
                """, identity, purpose.name());
        String token = newToken();
        OffsetDateTime expires = first(connection, """
                INSERT INTO app_account_challenge (token_hash, identity_id, purpose, command_key, expires_at)
                VALUES (?, ?, ?, ?, statement_timestamp() + interval '20 minutes') RETURNING expires_at
                """, row -> row.getObject("expires_at", OffsetDateTime.class), digest(token), identity, purpose.name(), key);
        mailbox.deliver(new SandboxAccountMailbox.Message(recipient, purpose.name(), token, expires));
        event(connection, identity, purpose.name() + "_REQUESTED");
    }

    private static AccountState accountForUpdate(Connection connection, UUID identity) throws SQLException {
        return first(connection, """
                SELECT status, email_verified_at IS NOT NULL AS email_verified
                FROM app_identity WHERE id = ? FOR UPDATE
                """, row -> new AccountState(row.getString("status"), row.getBoolean("email_verified")), identity);
    }

    private static void setPassword(Connection connection, UUID identity, String password) throws SQLException {
        byte[] salt = randomBytes(16);
        update(connection, """
                INSERT INTO app_account_credential (identity_id, password_salt, password_hash, password_iterations)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (identity_id) DO UPDATE SET password_salt = EXCLUDED.password_salt,
                    password_hash = EXCLUDED.password_hash, password_iterations = EXCLUDED.password_iterations
                """, identity, salt, passwordHash(password, salt, HASH_ITERATIONS), HASH_ITERATIONS);
    }

    private static void event(Connection connection, UUID identity, String action) throws SQLException {
        update(connection, "INSERT INTO app_account_event (id, identity_id, action) VALUES (?, ?, ?)",
                UUID.randomUUID(), identity, action);
    }

    private void limit(String action, String peer, String email) {
        boolean allowed = database.transaction(connection -> {
            update(connection, "DELETE FROM app_account_rate_window WHERE window_start < statement_timestamp() - interval '1 day'");
            if (attempt(connection, digest(action + ":peer:" + peer)) > 30) {
                return false;
            }
            return email == null || attempt(connection, digest(action + ":email:" + email)) <= 8;
        });
        if (!allowed) {
            throw new PilotProblem(429, "LIMITE_DE_TENTATIVAS", "account", "CF-IMP-003",
                    "Aguarde antes de tentar novamente.");
        }
    }

    private static int attempt(Connection connection, byte[] bucket) throws SQLException {
        return first(connection, """
                INSERT INTO app_account_rate_window (bucket_key, window_start, attempts)
                VALUES (?, date_bin(interval '5 minutes', statement_timestamp(), timestamptz '2000-01-01 00:00:00+00'), 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                    window_start = greatest(app_account_rate_window.window_start, EXCLUDED.window_start),
                    attempts = CASE WHEN app_account_rate_window.window_start >= EXCLUDED.window_start
                                    THEN least(app_account_rate_window.attempts + 1, 31) ELSE 1 END
                RETURNING attempts
                """, row -> row.getInt("attempts"), bucket);
    }

    static String email(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!validEmail(normalized)) {
            throw badField("email");
        }
        return normalized;
    }

    private static boolean validEmail(String email) {
        int at = email.indexOf('@');
        return email.length() <= 254 && at > 0 && at <= 64 && EMAIL.matcher(email).matches()
                && !email.startsWith(".") && email.charAt(at - 1) != '.' && !email.contains("..");
    }

    private static void requireNewPassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw badField("password");
        }
    }

    private static PilotProblem badField(String field) {
        return new PilotProblem(400, "PEDIDO_INVALIDO", field, "CF-IMP-003", "Confira os campos informados.");
    }

    private static PilotProblem invalidChallenge() {
        return new PilotProblem(422, "DESAFIO_INDISPONIVEL", "token", "CF-IMP-003", "Solicite um novo link.");
    }

    private static PilotProblem unauthenticated() {
        return new PilotProblem(401, "SESSAO_NECESSARIA", "session", "CF-IMP-003", "Conta ou credencial indisponível.");
    }
}
