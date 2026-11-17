package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class ListingEvidence {
    static final String VERRA = "VERRA";
    static final String VERRA_LISTING = "SIM-VERRA-LISTAGEM";
    static final String VERRA_CONSULTATION = "SIM-VERRA-CONSULTA";
    static final String HUB = "SIM-HUB-CONSULTA";
    static final String PAYMENT = "SIM-INSTITUICAO-PAGADORA";
    static final String VVB = "SIM-VVB-CONSULTA";
    static final Map<String, String> PROOFS = Map.ofEntries(
            Map.entry("BOLETO", "SIM-PROVA-BOLETO"),
            Map.entry("QUITACAO", "SIM-PROVA-QUITACAO-LISTAGEM"),
            Map.entry("TX-E3-10", "SIM-PROVA-EXIGENCIA"),
            Map.entry("TX-E3-12", "SIM-PROVA-INATIVACAO"),
            Map.entry("TX-E3-14", "SIM-PROVA-PUBLICACAO-01"),
            Map.entry("TX-E3-15", "SIM-PROVA-SUSPENSAO"),
            Map.entry("TX-E3-16", "SIM-PROVA-RETOMADA"),
            Map.entry("TX-E3-17", "SIM-PROVA-REJEICAO"),
            Map.entry("TX-E3-20", "SIM-PROVA-RECEBIMENTO-HUB"),
            Map.entry("TX-E3-21", "SIM-PROVA-ENCAMINHAMENTO"),
            Map.entry("TX-E3-23", "SIM-PROVA-AVALIACAO-VVB"),
            Map.entry("TX-E3-24", "SIM-PROVA-FIM-CONSULTA"));

    record Proof(String officialId, String target, IntakeModel.Ref evidence) {}
    record Capture(String authority, String namespace, Proof proof, OffsetDateTime effectiveAt, String capturedBy, UUID eventId) {}
    private record Existing(String project, UUID subject, String digest, String response) {}

    private final PilotAudit audit;

    ListingEvidence(PilotAudit audit) {
        this.audit = audit;
    }

    static void verify(String action, Proof proof) {
        String expected = PROOFS.get(action);
        if (expected == null || proof == null || !IntakeRules.id(proof.officialId()) || !IntakeRules.id(proof.target())
                || !new IntakeModel.Ref(expected, 1).equals(proof.evidence())) {
            throw invalid("PROVA_INSUFICIENTE", "Use o ato, o alvo e a prova versionada de ensaio correspondentes.");
        }
    }

    JsonNode repeated(Connection connection, String project, UUID subject, String authority, String namespace,
                      Proof proof, String digest) throws SQLException {
        first(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> true,
                authority + ":" + namespace + ":" + proof.officialId());
        var past = first(connection, """
                SELECT project_id,subject,digest,response FROM pilot_listing_external_act
                WHERE authority=? AND namespace=? AND official_id=?
                """, row -> new Existing(row.getString(1), row.getObject(2, UUID.class), row.getString(3), row.getString(4)),
                authority, namespace, proof.officialId());
        if (past == null) return null;
        require(past.project().equals(project) && past.subject().equals(subject) && past.digest().equals(digest),
                "ATO_CONFLITANTE", "Já existe captura para essa identidade oficial. Confira o alvo e o conteúdo antes de continuar.");
        return audit.decode(past.response());
    }

    void save(Connection connection, String project, UUID subject, Capture capture, String digest, JsonNode response) throws SQLException {
        update(connection, """
                INSERT INTO pilot_listing_external_act (authority,namespace,official_id,project_id,subject,digest,event_id,response)
                VALUES (?,?,?,?,?,?,?,?::jsonb)
                """, capture.authority(), capture.namespace(), capture.proof().officialId(), project, subject,
                digest, capture.eventId(), audit.encode(response));
    }

    String digest(Object officialContent) {
        return FixtureCatalog.sha256(audit.encode(officialContent).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static OffsetDateTime local(OffsetDateTime at) {
        if (at == null) throw invalid("DATA_AUSENTE", "Informe a data efetiva do evento de ensaio.");
        return at.atZoneSameInstant(VintageWindows.ZONE).toOffsetDateTime();
    }

    static PilotProblem invalid(String code, String message) { return PilotProblem.invalid(code, "listing", "OP-E6-09", message); }
    static void require(boolean condition, String code, String message) {
        if (!condition) throw new PilotProblem(409, code, "listing", "OP-E6-09", message);
    }
}
