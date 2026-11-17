package br.carbonflow.pilot;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record Fixture(String contractVersion, String environment, String projectId, Method method,
               Calculation calculation, IssuanceStatus issuanceStatus) {

    record Method(String program, String ruleSet, String documentModel,
                  @JsonProperty("package") String packageVersion,
                  List<String> dependencies, String profile, String discountProof) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Calculation(String zone, Factor factor, Factor replacementCandidate, String fuelFactorTco2PerGal,
                       String ir, List<Model> models, List<Period> periods) {}

    record Factor(String id, String value, String unit) {}

    record Model(int count, String evKwhPer100Mi, String mpg) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Period(String id, int year, LocalDate start, LocalDate endExclusive, List<Cut> cuts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cut(LocalDate start, LocalDate endExclusive, String edKwh, String ecKwh) {}

    record IssuanceStatus(String sameVerificationRequired, Reference activationEvidence, Reference approvalEvidence,
                          String pendingI1ExpectedState, String m2ExpectedState) {}

    record Reference(String id, int version) {}
}
