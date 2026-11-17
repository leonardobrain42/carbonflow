package br.carbonflow.pilot;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.zone.ZoneRulesProvider;
import java.util.HexFormat;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@ApplicationScoped
class FixtureCatalog {
    static final String KIT_VERSION = "0.2.14";
    static final Fixture.Reference DEFAULT = new Fixture.Reference("E6-EXEMPLOS", 1);
    static final Fixture.Reference MISSING_EC = new Fixture.Reference("E6-EXEMPLOS-SEM-EC", 1);
    private static final String RESOURCE = "/fixtures/e6-exemplos.json";
    private static final String SOURCE = "03_especificacao/e6/exemplos.json";
    private static final String EXPECTED_SHA256 = "cd1dd2b7d9e72a989fc26fbfbf7682f6238ff47701524c25b3aa42d1cdb1e19b";

    record Provenance(Fixture.Reference reference, String kitVersion, String source, String sourceSha256,
                      String variation) {}

    record Snapshot(String snapshotFormat, String engineVersion, String tzdbVersion, Provenance provenance,
                    Fixture fixture) {}

    private final ObjectMapper mapper;

    FixtureCatalog(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Snapshot load(Fixture.Reference reference) {
        if (!DEFAULT.equals(reference) && !MISSING_EC.equals(reference)) {
            throw PilotProblem.invalid("FONTE_NAO_ADMISSIVEL", "fixture", "ARC-E6-06",
                    "Selecione uma das massas sintéticas versionadas.");
        }
        try (var stream = FixtureCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Massa E6 ausente na aplicação");
            }
            byte[] bytes = stream.readAllBytes();
            String digest = sha256(bytes);
            if (!EXPECTED_SHA256.equals(digest)) {
                throw new IllegalStateException("A massa empacotada diverge da versão 0.2.14");
            }
            var tree = mapper.readTree(bytes);
            String variation = "ORIGINAL";
            if (MISSING_EC.equals(reference)) {
                var cut = (ObjectNode) tree.at("/calculation/periods/0/cuts/0");
                cut.remove("ecKwh");
                variation = "M1_PRIMEIRO_RECORTE_SEM_EC";
            }
            var source = new Provenance(reference, KIT_VERSION, SOURCE, digest, variation);
            var tzdb = ZoneRulesProvider.getVersions(VintageWindows.ZONE.getId()).lastKey();
            return new Snapshot("e8-001@1", CarbonCalculator.VERSION, tzdb, source, mapper.treeToValue(tree, Fixture.class));
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler a massa sintética", e);
        }
    }

    byte[] snapshotBytes(Snapshot snapshot) {
        try {
            // Formato v1 tem projeção tipada e ordem declarada de campos e listas.
            return mapper.writeValueAsBytes(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar o snapshot", e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
