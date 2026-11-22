package br.carbonflow.pilot;

import java.util.List;
import java.util.UUID;

sealed interface ProjectContext permits ProjectContext.Pilot, ProjectContext.Draft {
    String projectId();

    String environment();

    record Pilot(String projectId, String organizationId, String environment, List<String> roles,
                 String powerProof) implements ProjectContext {}

    /**
     * {@code access} is {@code OWNER} on the creator's list and {@code CALCULATION}
     * when a second account sees the draft only through an active calculation grant.
     */
    record Draft(String projectId, UUID organizationId, String environment, String state,
                 int version, String access) implements ProjectContext {}
}
