package br.carbonflow.pilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class SandboxAccountMailbox {
    record Message(String recipient, String purpose, String token, OffsetDateTime expiresAt) {}

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final Optional<Path> directory;
    private final ObjectMapper json;

    SandboxAccountMailbox(@ConfigProperty(name = "carbonflow.accounts.sandbox-inbox") Optional<String> directory,
                          ObjectMapper json) {
        this.directory = directory.filter(value -> !value.isBlank()).map(Path::of).map(Path::toAbsolutePath);
        this.json = json;
    }

    Path requireEnabled() {
        return directory.orElseThrow(() ->
                new PilotProblem(404, "RECURSO_NAO_VISIVEL", "account", "CF-IMP-003", "Recurso indisponível."));
    }

    void requireAvailable() {
        Path inbox = requireEnabled();
        try {
            if (!Files.isDirectory(inbox) || !Files.isWritable(inbox)
                    || !Files.getPosixFilePermissions(inbox).equals(PRIVATE_DIRECTORY)) {
                throw unavailable();
            }
        } catch (IOException | UnsupportedOperationException problem) {
            throw unavailable();
        }
    }

    void deliver(Message message) {
        deliver(UUID.randomUUID(), message);
    }

    void deliver(UUID notification, Message message) {
        Path inbox = requireEnabled();
        try {
            Path pending = Files.createTempFile(inbox, ".pending-", ".tmp");
            try {
                Files.write(pending, json.writeValueAsBytes(message));
                Files.move(pending, inbox.resolve(notification + ".json"), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
        } catch (IOException problem) {
            throw unavailable();
        }
    }

    private static PilotProblem unavailable() {
        return new PilotProblem(503, "CAIXA_SANDBOX_INDISPONIVEL", "account", "CF-IMP-003",
                "A caixa de e-mail de teste está indisponível.");
    }
}
