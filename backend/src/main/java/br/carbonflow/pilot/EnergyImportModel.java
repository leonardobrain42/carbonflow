package br.carbonflow.pilot;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EnergyImportModel {
    static final String MAPPING = "SIM-CSV-UTF8-ED-EC";
    static final String SOURCE = "SIM-OP-01";
    static final String AUTHORIZATION = "SIM-AUT-ED-EC";
    static final int MAX_BYTES = 10_000_000;
    static final int MAX_LINES = 10_000;
    static final int MAX_FACTS_PER_YEAR = 100_000;
    static final int CHUNK = 250;
    static final List<String> HEADER = List.of("kind", "sourceFactId", "start", "endExclusive", "zone", "siteId",
            "chargerId", "vehicleId", "circuitId", "meterId", "quantity", "unit");

    record Ref(UUID id, int version) {}
    record CodeRef(String id, int version) {}
    record ImportCommand(Ref artifact, CodeRef mappingProfile, String sourceId, CodeRef period) {}
    record FileReceipt(UUID id, int version, String sha256, String originalName, String sourceId, String authorizationId,
                       int bytes, String receivedBy, OffsetDateTime receivedAt) {}
    record FileContent(String name, byte[] bytes) {}
    record Upload(FileReceipt artifact, UUID eventId, boolean repeated) {}
    record Queued(UUID jobId, int version, UUID eventId, boolean repeated) {}
    record Job(String project, UUID id, UUID file, String period, int configurationVersion, int version, String state,
               int total, int processed, Integer columns, String message) {}
    record Report(UUID id, String period, FileReceipt artifact, CodeRef mappingProfile, int configurationVersion,
                  int version, String state, int total, int processed, Map<String, Integer> classifications, String message) {}
    record Line(UUID id, int line, String original, String classification, List<Reason> reasons, Fact fact,
                UUID relatedRowId, int version, String decision, Proposal proposal, Resolution resolution) {}
    record Page(List<Line> lines, String nextCursor) {}
    record Reason(String code, String message) {}
    record Fact(String kind, String sourceFactId, String site, String charger, String vehicle, String circuit, String meter,
                String sourceStart, String sourceEndExclusive, String zone, OffsetDateTime start, OffsetDateTime endExclusive,
                String quantity, String unit, String kwh, String evidence) {
        String channel() { return "ED".equals(kind) ? charger : circuit; }
    }
    record Proposal(String preparedBy, String reason, Ref predecessor) {}
    record Resolution(String reviewedBy, String reason, Ref observation, UUID eventId) {}
    record Observation(Ref ref, Integer predecessor, UUID rowId, String period, int configurationVersion,
                       String physicalHash, Fact fact, Resolution resolution) {}
    record Selection(UUID id, int version, String period, UUID eventId, int observations) {}
    record SelectionPage(List<Observation> observations, String nextCursor) {}
}
