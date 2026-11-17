package br.carbonflow.pilot;

import java.io.IOException;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import com.fasterxml.jackson.databind.ObjectMapper;

import static br.carbonflow.pilot.IntakeModel.*;

@ApplicationScoped
class IntakeCatalog {
    static final String SOURCE = "fixtures/e8-003-enquadramento.json";

    record MeterExample(String circuit, MeterInput details) {}
    record Sample(String version, Candidate candidate, List<Evidence> evidence, List<MeterExample> meters) {}
    record MeterSupplement(List<Evidence> evidence, List<MeterExample> meters) {}
    record Examples(String version, String source, String sha256, Candidate candidate,
                    List<Evidence> evidence, List<MeterExample> meters, String supplementSha256) {}

    private final Examples examples;

    IntakeCatalog(ObjectMapper mapper) {
        try (var input = getClass().getResourceAsStream("/" + SOURCE);
             var extension = getClass().getResourceAsStream("/fixtures/e8-006-metrologia.json")) {
            if (input == null || extension == null) {
                throw new IllegalStateException("Amostra E8-003 indisponível");
            }
            var bytes = input.readAllBytes();
            var sample = mapper.readValue(bytes, Sample.class);
            var supplementalBytes = extension.readAllBytes();
            var supplement = mapper.readValue(supplementalBytes, MeterSupplement.class);
            var evidence = java.util.stream.Stream.concat(sample.evidence().stream(), supplement.evidence().stream()).toList();
            var meters = java.util.stream.Stream.concat(sample.meters().stream(), supplement.meters().stream()).toList();
            examples = new Examples(sample.version(), SOURCE, FixtureCatalog.sha256(bytes),
                    sample.candidate(), evidence, meters, FixtureCatalog.sha256(supplementalBytes));
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler a amostra E8-003", e);
        }
    }

    Examples examples() {
        return examples;
    }

    Evidence require(Ref ref, String kind) {
        return examples.evidence().stream()
                .filter(proof -> proof.ref().equals(ref) && proof.kind().equals(kind))
                .findFirst()
                .orElseThrow(() -> PilotProblem.invalid("PROVA_INSUFICIENTE", "evidence", "OP-E6-01/02",
                        "Selecione a evidência sintética versionada aplicável a " + kind + "."));
    }

    Evidence forMeter(Ref ref, String kind, String circuit, String meter) {
        var evidence = require(ref, kind);
        if (!circuit.equals(evidence.details().get("circuit")) || !meter.equals(evidence.details().get("meter"))) {
            throw PilotProblem.invalid("PROVA_FORA_DO_CONTORNO", "evidence", "REG-E2-11",
                    "A evidência pertence a outro circuito ou medidor.");
        }
        return evidence;
    }
}
