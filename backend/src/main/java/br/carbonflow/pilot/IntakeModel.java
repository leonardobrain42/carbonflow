package br.carbonflow.pilot;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class IntakeModel {
    static final String KIT = "0.2.14";
    static final String ENVIRONMENT = "SIMULACAO";

    record Ref(String id, int version) {}
    record Profile(String country, String state, String locality, String design, Boolean closedNetwork,
                   Boolean privateAccess, String vehicleClass, String propulsion, String charger,
                   String grid, Boolean additionalInfrastructure, String ecRoute) {}
    record Station(String id, String site, String model, String powerKw) {}
    record Vehicle(String id, String model) {}
    record Model(String id, String bev, String comparable) {}
    record Circuit(String id, String station) {}
    record Inventory(List<String> sites, List<Station> stations, List<Model> models,
                     List<Vehicle> vehicles, List<Circuit> ecCircuits) {}
    record Candidate(Profile profile, Inventory inventory, LocalDate referenceActDate, Ref evidence) {}
    record Reason(String code, String rule, String message) {}
    record Rules(String kit, LocalDate sourceCutoff, LocalDate referenceActDate, String matrix, String program,
                 String packageEdition, String methodology, String additionality, String gridRoute) {}
    record Intake(Candidate candidate, String state, String preparedBy, String reviewedBy, Ref reviewEvidence,
                  Rules selection, List<Reason> reasons, List<UUID> reviewedRecords) {}

    enum RecordType {
        DIREITOS, EXCEDENTE, SOBREPOSICAO, ENGAJAMENTO, MECANISMO, ESG, RECLAMACAO, ODS, FPIC, REPARTICAO
    }
    enum RecordStatus {
        COMPROVADO_SIMULACAO, PENDENTE, NAO_APLICAVEL_JUSTIFICADO
    }
    record CaseInput(RecordType type, RecordStatus status, LocalDate effectiveDate, String justification, Ref evidence) {}
    record CaseEntry(UUID id, int version, String recordedBy, CaseInput input) {}
    record Commitment(LocalDate date, Ref evidence, String recordedBy, List<UUID> priorRecords) {}
    record Operation(LocalDate start, LocalDate creditStart, LocalDate creditEndInclusive, Ref evidence,
                     String recordedBy, List<String> meterRevisions) {}

    record MeterInput(String meterId, OffsetDateTime effectiveFrom, boolean upstream, boolean exclusive,
                      Ref installation, Ref diagram, Ref calibration) {}
    record MeterRevision(String circuit, int revision, Integer predecessor, String recordedBy, MeterInput details,
                         OffsetDateTime calibratedFrom, OffsetDateTime calibratedUntil) {}
    record MeterView(MeterRevision revision, OffsetDateTime effectiveUntilExclusive) {}
    record Reading(String originId, String circuit, String meterId, String start, String endExclusive,
                   String kwh, Ref evidence) {}
    record ReadingResult(Reading raw, String state, List<Reason> reasons, int configurationVersion,
                         Integer meterRevision, UUID eventId) {}

    record Evidence(Ref ref, String kind, String source, LocalDate date, Map<String, String> details) {}
    record Snapshot(int version, String environment, Intake intake, List<CaseEntry> records,
                    Commitment commitment, Operation operation, List<MeterRevision> meters) {
        static Snapshot empty() {
            return new Snapshot(0, ENVIRONMENT, null, List.of(), null, null, List.of());
        }

        Snapshot next() {
            return new Snapshot(version + 1, environment, intake, records, commitment, operation, meters);
        }

        Snapshot withIntake(Intake value) {
            return new Snapshot(version, environment, value, records, commitment, operation, meters);
        }

        Snapshot withRecord(CaseEntry value) {
            return new Snapshot(version, environment, intake, append(records, value), commitment, operation, meters);
        }

        Snapshot withCommitment(Commitment value) {
            return new Snapshot(version, environment, intake, records, value, operation, meters);
        }

        Snapshot withOperation(Operation value) {
            return new Snapshot(version, environment, intake, records, commitment, value, meters);
        }

        Snapshot withMeter(MeterRevision value) {
            return new Snapshot(version, environment, intake, records, commitment, operation, append(meters, value));
        }

        private static <T> List<T> append(List<T> list, T value) {
            var next = new ArrayList<>(list);
            next.add(value);
            return List.copyOf(next);
        }
    }
}
