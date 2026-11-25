package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * D18 slice 3: effective capability = code AND data AND licence, per purpose, derived on every read and never
 * stored or edited. A purpose reports the three axes and one effective state; an axis that does not apply is
 * NOT_REQUIRED. The administration cannot enable anything here, and calculation, admission and external acts are
 * separate gates that this cycle never derives. The view carries states only: no source names, counts or licences,
 * so the client never sees the administrative inventory.
 */
final class MethodologyCapabilities {
    record Axis(String state) {}
    record Purpose(String purpose, String label, Axis code, Axis data, Axis licence, String effective,
                   String reason) {}

    static final String MET = "MET";
    static final String NOT_MET = "NOT_MET";
    static final String NOT_REQUIRED = "NOT_REQUIRED";

    private MethodologyCapabilities() {}

    static List<Purpose> evaluate(Connection connection, MethodologyRegistry.CandidateStatus status)
            throws SQLException {
        var result = new ArrayList<Purpose>();
        boolean known = "PACKAGE".equals(status.state()) || "REGISTERED_NO_PACKAGE".equals(status.state());
        var pack = !known ? null : MethodologyPackages.current().packages().stream()
                .filter(item -> item.methodology().equals(status.methodology())
                        && item.edition().equals(status.edition())).findFirst().orElse(null);
        boolean triageCode = pack != null && "AVAILABLE".equals(pack.capabilities().get("triageSchema"));
        result.add(new Purpose("TRIAGE", "Triagem guiada", axis(triageCode), new Axis(NOT_REQUIRED),
                new Axis(NOT_REQUIRED), triageCode ? "ENABLED" : "NOT_ENABLED",
                triageCode ? "Pacote com schema de triagem em código."
                        : known ? "Metodologia registrada sem pacote em código: candidata com lacuna, sem campos próprios."
                        : "Sem metodologia candidata registrada."));

        boolean[] data = {false}, licence = {false};
        if (known) sources(connection, status.methodology(), status.edition(), data, licence);
        boolean reference = pack != null && data[0] && licence[0];
        result.add(new Purpose("SOURCE_REFERENCE", "Referência a requisitos das fontes", axis(pack != null),
                axis(data[0]), axis(licence[0]), reference ? "ENABLED" : "NOT_ENABLED",
                reference ? "Código, metadados de todas as fontes obrigatórias e decisão de licença vigente (ambiente de testes)."
                        : "Falta ao menos um dos três: pacote em código, metadados das fontes obrigatórias ou decisão de licença vigente."));

        for (var gate : List.of(new String[]{"CALCULATION_ADMISSION", "Cálculo e admissão de dados"},
                new String[]{"EXTERNAL_ACTS", "Atos externos"})) {
            result.add(new Purpose(gate[0], gate[1], new Axis(NOT_REQUIRED), new Axis(NOT_REQUIRED),
                    new Axis(NOT_REQUIRED), "NOT_ENABLED",
                    "Gate próprio com revisão e decisão separadas; não deriva de código, dados ou licença."));
        }
        return List.copyOf(result);
    }

    private static Axis axis(boolean met) {
        return new Axis(met ? MET : NOT_MET);
    }

    /**
     * Data: the methodology has mandatory source needs and every one of them has published source metadata.
     * Licence: every one of those sources has an approved REFERENCE_REQUIREMENT decision in force
     * (sandbox, requirement catalog, proof present, decided by someone other than the submitter).
     */
    private static void sources(Connection connection, String methodology, String edition, boolean[] data,
                                boolean[] licence) throws SQLException {
        record Count(int total, int withData, int licensed) {}
        Count count = first(connection, """
                SELECT count(*), count(x.source_id), count(*) FILTER (WHERE x.licensed)
                FROM app_methodology_source_need n
                LEFT JOIN LATERAL (
                    SELECT s.source_id, EXISTS (
                        SELECT 1 FROM app_content_license license
                        JOIN LATERAL (
                            SELECT version,outcome,decided_by,decided_at FROM app_content_license_decision
                            WHERE license_id = license.id ORDER BY version DESC LIMIT 1) decision ON true
                        JOIN app_content_license_capability cap
                          ON cap.license_id = license.id AND cap.decision_version = decision.version
                        WHERE license.source_id = s.source_id AND license.edition = s.edition
                          AND cap.operation = 'REFERENCE_REQUIREMENT'
                          AND license.project_id IS NULL AND license.actor_id IS NULL
                          AND license.environment = 'SANDBOX' AND license.purpose = 'REQUIREMENT_CATALOG'
                          AND license.provider IS NULL AND license.proof_sha256 IS NOT NULL
                          AND license.submitted_by IS NOT NULL AND license.submitted_by <> decision.decided_by
                          AND decision.decided_at <= statement_timestamp() AND decision.outcome = 'APPROVED'
                          AND license.starts_at <= statement_timestamp()
                          AND (license.ends_at IS NULL OR license.ends_at > statement_timestamp())) AS licensed
                    FROM app_source_version s
                    WHERE s.project_id IS NULL AND s.source_id = n.source_id
                      AND (n.edition_policy <> 'EXACT' OR s.edition = n.source_edition)
                    ORDER BY s.edition DESC LIMIT 1) x ON true
                WHERE n.methodology = ? AND n.methodology_edition = ? AND n.mandatory
                  AND n.kind <> 'PROJECT_EVIDENCE'
                """, r -> new Count(r.getInt(1), r.getInt(2), r.getInt(3)), methodology, edition);
        data[0] = count != null && count.total() > 0 && count.withData() == count.total();
        licence[0] = data[0] && count.licensed() == count.total();
    }
}
