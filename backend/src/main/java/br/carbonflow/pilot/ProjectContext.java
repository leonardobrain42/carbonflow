package br.carbonflow.pilot;

import java.util.List;
import java.util.UUID;

sealed interface ProjectContext permits ProjectContext.Pilot, ProjectContext.Draft {
    String projectId();

    String environment();

    record Pilot(String projectId, String organizationId, String environment, List<String> roles,
                 String powerProof) implements ProjectContext {}

    record Draft(String projectId, UUID organizationId, String environment, String state,
                 int version) implements ProjectContext {}
}
