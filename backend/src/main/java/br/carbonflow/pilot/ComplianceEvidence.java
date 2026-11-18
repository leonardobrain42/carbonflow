package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class ComplianceEvidence {
    record Document(UUID id, int bytesCount, OffsetDateTime recordedAt) {}
    private record Encrypted(byte[] nonce, byte[] bytes) {}

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FILES_PER_REQUEST = 5;
    private static final String RULE = "CF-IMP-028";
    private final DynamicProjectScope scope;
    private final byte[] key;

    ComplianceEvidence(DynamicProjectScope scope,
                       @ConfigProperty(name = "carbonflow.compliance.document-key") Optional<String> config) {
        this.scope = scope;
        this.key = config.filter(value -> !value.isBlank()).map(ComplianceEvidence::decodeKey).orElse(null);
    }

    List<Document> documents(String token, String project, UUID request) {
        return scope.read(token, project, "VIEW_AUTHORITY_DOCUMENTS", (connection, context) -> {
            requireDocumentAccess(connection, project, request);
            return documents(connection, project, request);
        });
    }

    List<Document> add(String token, String project, UUID request, UUID id, InputStream input) {
        return scope.write(token, project, "ADD_AUTHORITY_DOCUMENT", (connection, context) -> {
            var assessment = requireDocumentAccess(connection, project, request);
            if (assessment != null && assessment.decision() != ComplianceDecisions.Outcome.NEEDS_INFORMATION) {
                throw new PilotProblem(409, "COMPROVANTES_ENCERRADOS", "request", RULE,
                        "A solicitação já recebeu uma decisão sobre os comprovantes.");
            }
            byte[] pdf = readPdf(input);
            byte[] digest = digest(pdf);
            byte[] previous = first(connection, """
                    SELECT sha256 FROM app_compliance_evidence WHERE project_id = ? AND request_id = ? AND id = ?
                    """, row -> row.getBytes(1), project, request, id);
            if (previous != null) {
                if (MessageDigest.isEqual(previous, digest)) return documents(connection, project, request);
                throw new PilotProblem(409, "DOCUMENTO_REUSADO", "document", RULE, "O identificador já pertence a outro PDF.");
            }
            if (documents(connection, project, request).size() >= MAX_FILES_PER_REQUEST) {
                throw new PilotProblem(409, "LIMITE_DOCUMENTOS", "document", RULE, "Limite de cinco PDFs para esta solicitação.");
            }
            byte[] nonce = AccessSecrets.randomBytes(12);
            update(connection, """
                    INSERT INTO app_compliance_evidence(project_id,request_id,id,uploaded_by,sha256,nonce,bytes_count,encrypted)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, project, request, id, context.actor().id(), digest, nonce, pdf.length,
                    crypt(Cipher.ENCRYPT_MODE, project, request, id, nonce, pdf));
            return documents(connection, project, request);
        });
    }

    byte[] download(String token, String project, UUID request, UUID id) {
        return scope.write(token, project, "DOWNLOAD_AUTHORITY_DOCUMENT", (connection, context) ->
                download(connection, project, request, id));
    }

    static List<Document> documents(Connection connection, String project, UUID request) throws SQLException {
        return query(connection, """
                SELECT id, bytes_count, recorded_at FROM app_compliance_evidence
                WHERE project_id = ? AND request_id = ? ORDER BY recorded_at, id
                """, row -> new Document(row.getObject(1, UUID.class), row.getInt(2),
                row.getObject(3, OffsetDateTime.class)), project, request);
    }

    byte[] download(Connection connection, String project, UUID request, UUID id) throws SQLException {
        requireDocumentAccess(connection, project, request);
        Encrypted document = first(connection, """
                SELECT nonce, encrypted FROM app_compliance_evidence WHERE project_id = ? AND request_id = ? AND id = ?
                """, row -> new Encrypted(row.getBytes(1), row.getBytes(2)), project, request, id);
        if (document == null) throw ComplianceStaff.unavailable();
        return crypt(Cipher.DECRYPT_MODE, project, request, id, document.nonce(), document.bytes());
    }

    static ComplianceDecisions.Decision requireDocumentAccess(Connection connection, String project, UUID request) throws SQLException {
        requireOpen(connection, project, request);
        var assessment = ComplianceDecisions.latest(connection, project, request);
        if (assessment != null && assessment.decision() == ComplianceDecisions.Outcome.REVOKED) {
            throw ComplianceStaff.unavailable();
        }
        return assessment;
    }

    static void requireOpen(Connection connection, String project, UUID request) throws SQLException {
        String current = first(connection, """
                SELECT state FROM app_project_authority_request_revision WHERE project_id = ? AND request_id = ?
                ORDER BY version DESC LIMIT 1
                """, row -> row.getString(1), project, request);
        if (!"SUBMITTED".equals(current)) throw ComplianceStaff.unavailable();
    }

    private byte[] crypt(int mode, String project, UUID request, UUID document, byte[] nonce, byte[] bytes) {
        if (key == null) throw new PilotProblem(503, "ARQUIVOS_INDISPONIVEIS", "document", RULE,
                "O armazenamento reservado de documentos está indisponível.");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD((project + "/" + request + "/" + document).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(bytes);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Falha ao processar documento reservado", error);
        }
    }

    private static byte[] readPdf(InputStream input) {
        try {
            byte[] bytes = input == null ? new byte[0] : input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new PilotProblem(413, "DOCUMENTO_GRANDE", "document", RULE,
                    "Selecione um PDF de até 2 MiB.");
            if (bytes.length < 6 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw new PilotProblem(415, "TIPO_DOCUMENTO_INVALIDO", "document", RULE, "Selecione um arquivo PDF.");
            }
            return bytes;
        } catch (IOException error) {
            throw new PilotProblem(400, "DOCUMENTO_INVALIDO", "document", RULE, "O arquivo não pôde ser lido.");
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Resumo do documento indisponível", error);
        }
    }

    private static byte[] decodeKey(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != 32) throw new IllegalArgumentException();
            return decoded;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Chave de documentos de conformidade inválida.");
        }
    }
}
