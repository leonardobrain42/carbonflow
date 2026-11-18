package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class ComplianceStaff {
    record Status(boolean verified, OffsetDateTime expiresAt) {}
    record Staff(UUID id, Set<UUID> organizations) {}
    private record Credential(byte[] secret, byte[] tag, Set<UUID> organizations) {}
    private record Attempts(long counter, int failures, boolean expired) {}
    private record Verification(Status status, boolean limited) {}

    private final AccountAccess accounts;
    private final PilotDatabase database;
    private final Map<UUID, Credential> credentials;

    ComplianceStaff(AccountAccess accounts, PilotDatabase database,
                    @ConfigProperty(name = "carbonflow.compliance.staff") Optional<String> roster) {
        this.accounts = accounts;
        this.database = database;
        this.credentials = parse(roster.orElse(""));
    }

    Status status(String token) {
        return database.read(connection -> {
            UUID identity = accounts.requireIdentity(connection, token).id();
            Credential credential = credential(identity);
            return status(connection, token, credential);
        });
    }

    Status verify(String token, String code) {
        Verification outcome = database.transaction(connection -> {
            UUID id = accounts.lockIdentity(connection, token).id();
            Credential credential = credential(id);
            Attempts attempts = first(connection, """
                    SELECT last_counter, failures, window_start <= statement_timestamp() - interval '5 minutes'
                    FROM app_compliance_mfa WHERE identity_id = ?
                    """, row -> new Attempts(row.getLong(1), row.getInt(2), row.getBoolean(3)), id);
            int failures = attempts == null || attempts.expired() ? 0 : attempts.failures();
            if (failures >= 5) return new Verification(null, true);

            long time = first(connection, "SELECT floor(extract(epoch from statement_timestamp()) / 30)::bigint",
                    row -> row.getLong(1));
            long accepted = matchingCounter(credential.secret(), code, time, attempts == null ? -1 : attempts.counter());
            if (accepted < 0) {
                update(connection, """
                        INSERT INTO app_compliance_mfa(identity_id, window_start, failures)
                        VALUES (?, statement_timestamp(), 1)
                        ON CONFLICT(identity_id) DO UPDATE SET
                          failures = ?, window_start = CASE WHEN ?
                            THEN statement_timestamp() ELSE app_compliance_mfa.window_start END
                        """, id, failures + 1, attempts != null && attempts.expired());
                audit(connection, id, null, "MFA_DENIED");
                return new Verification(null, false);
            }

            update(connection, """
                    INSERT INTO app_compliance_mfa(identity_id, last_counter, window_start, failures)
                    VALUES (?, ?, statement_timestamp(), 0)
                    ON CONFLICT(identity_id) DO UPDATE SET last_counter = EXCLUDED.last_counter
                    """, id, accepted);
            OffsetDateTime expiry = first(connection, """
                    INSERT INTO app_compliance_session(token_hash, credential_tag, expires_at)
                    SELECT token_hash, ?, least(expires_at, statement_timestamp() + interval '10 minutes')
                    FROM app_account_session WHERE token_hash = ?
                    ON CONFLICT(token_hash) DO UPDATE SET expires_at = EXCLUDED.expires_at,
                      credential_tag = EXCLUDED.credential_tag RETURNING expires_at
                    """, row -> row.getObject(1, OffsetDateTime.class), credential.tag(), AccessSecrets.digest(token));
            audit(connection, id, null, "MFA_VERIFIED");
            return new Verification(new Status(true, expiry), false);
        });
        if (outcome.status() != null) return outcome.status();
        if (outcome.limited()) throw new PilotProblem(429, "MFA_LIMITE", "code", "CF-IMP-028",
                "Aguarde alguns minutos para tentar novamente.");
        throw new PilotProblem(403, "MFA_CODIGO_INVALIDO", "code", "CF-IMP-028", "Código indisponível ou já utilizado.");
    }

    Staff require(Connection connection, String token) throws SQLException {
        UUID id = accounts.lockIdentity(connection, token).id();
        Credential credential = credential(id);
        if (!status(connection, token, credential).verified()) {
            throw new PilotProblem(403, "MFA_NECESSARIO", "session", "CF-IMP-028", "Confirme o acesso com o autenticador.");
        }
        return new Staff(id, credential.organizations());
    }

    private static Status status(Connection connection, String token, Credential credential) throws SQLException {
        OffsetDateTime expiry = first(connection, """
                SELECT expires_at FROM app_compliance_session
                WHERE token_hash = ? AND credential_tag = ? AND expires_at > statement_timestamp()
                """, row -> row.getObject(1, OffsetDateTime.class), AccessSecrets.digest(token), credential.tag());
        return new Status(expiry != null, expiry);
    }

    static void audit(Connection connection, UUID actor, String project, String action) throws SQLException {
        update(connection, """
                INSERT INTO app_compliance_access_event(id, actor_id, project_id, action) VALUES(?, ?, ?, ?)
                """, UUID.randomUUID(), actor, project, action);
    }

    private Credential credential(UUID id) {
        Credential credential = credentials.get(id);
        if (credential == null) throw unavailable();
        return credential;
    }

    static PilotProblem unavailable() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "compliance", "CF-IMP-028", "Recurso indisponível.");
    }

    private static long matchingCounter(byte[] secret, String code, long time, long consumed) {
        if (code == null || !code.matches("[0-9]{6}")) return -1;
        for (long counter = time; counter >= Math.max(time - 1, consumed + 1); counter--) {
            if (MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII),
                    codeAt(secret, counter).getBytes(StandardCharsets.US_ASCII))) return counter;
        }
        return -1;
    }

    static String codeAt(byte[] secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] raw = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int number = ByteBuffer.wrap(raw, raw[raw.length - 1] & 0xf, 4).getInt() & Integer.MAX_VALUE;
            return String.format(Locale.ROOT, "%06d", number % 1_000_000);
        } catch (GeneralSecurityException problem) {
            throw new IllegalStateException("Autenticador indisponível", problem);
        }
    }

    private static Map<UUID, Credential> parse(String entries) {
        var result = new HashMap<UUID, Credential>();
        if (entries.isBlank()) return Map.of();
        try {
            for (String entry : entries.split(";", -1)) {
                String[] fields = entry.strip().split(":", -1);
                if (fields.length != 3) throw new IllegalArgumentException();
                UUID id = UUID.fromString(fields[0]);
                byte[] secret = Base64.getUrlDecoder().decode(fields[1]);
                if (secret.length < 20 || secret.length > 64) throw new IllegalArgumentException();
                var orgs = Arrays.stream(fields[2].split(",", -1)).map(UUID::fromString)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (result.put(id, new Credential(secret, AccessSecrets.digest(entry.strip()), orgs)) != null) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (IllegalArgumentException error) {
            // Configuration contains secrets. Avoid attaching parser errors to logs.
            throw new IllegalStateException("Cadastro da equipe de conformidade inválido.");
        }
        return Map.copyOf(result);
    }
}
