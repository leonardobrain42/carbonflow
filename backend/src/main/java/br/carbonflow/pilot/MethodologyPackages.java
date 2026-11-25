package br.carbonflow.pilot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Versioned catalog of the methodology packages the platform can read (D17/A03, #90). It describes packages; it
 * does not admit data, grant licences, run calculations or decide eligibility. A candidate that is not listed keeps
 * a gap, never a fallback. Descriptors are code-defined and versioned with the release: there is no authoring or
 * publication flow here, and a change to a descriptor changes its digest.
 */
final class MethodologyPackages {
    record Descriptor(String packageId, int version, String program, String methodology, String edition,
                      String title, String status, Map<String, String> capabilities, Set<String> activities,
                      String digest) {}
    record ActivityType(String value, String label) {}
    record Catalog(int version, String digest, List<Descriptor> packages, List<ActivityType> activityTypes) {}

    /** Platform-level activity vocabulary of the triage; a package declares which of them it covers. */
    static final List<ActivityType> ACTIVITY_TYPES = List.of(
            new ActivityType("MOBILIDADE_ELETRICA", "Mobilidade elétrica e recarga"),
            new ActivityType("RESIDUOS", "Resíduos e economia circular"),
            new ActivityType("ENERGIA", "Energia e eficiência energética"),
            new ActivityType("AGRICULTURA", "Agricultura, solo e florestas"),
            new ActivityType("OUTRO", "Outra atividade"),
            new ActivityType("DESCONHECIDO", "Preciso de orientação"));

    /** Catalog version: bump when a descriptor is added, removed or changed. */
    static final int CATALOG_VERSION = 2;

    private record Meta(String program, int version, String status, Map<String, String> capabilities) {}

    // What the platform declares about each package. Values restate existing limits: no admission, no external act.
    private static final Map<String, Meta> META = Map.of(
            ProjectJourney.VM0038.id(), new Meta("VCS", 2, "FIRST_SLICE_OPEN_BR_LDV_BEV_DCFC_SIN",
                    Map.of("triageSchema", "AVAILABLE", "calculationAdmission", "NOT_ENABLED",
                            "externalActs", "NOT_ENABLED")));

    private static final Catalog CURRENT = build(ProjectJourney.configured(), META);

    private MethodologyPackages() {}

    /** Whether a code package exists for the methodology and edition (candidate code and edition of its profile). */
    static boolean hasPackage(String methodology, String edition) {
        return CURRENT.packages().stream().anyMatch(item -> item.methodology().equals(methodology)
                && item.edition().equals(edition));
    }

    static boolean knownActivity(String value) {
        return ACTIVITY_TYPES.stream().anyMatch(item -> item.value().equals(value));
    }

    static Catalog current() {
        return CURRENT;
    }

    static List<ProjectJourney.Option> options() {
        return CURRENT.packages().stream()
                .map(item -> new ProjectJourney.Option(item.methodology(), item.title())).toList();
    }

    /** Builds a catalog from profiles and their declared metadata; a profile without metadata is not listed. */
    private static Catalog build(List<ProjectJourney.Profile> profiles, Map<String, Meta> meta) {
        var packages = new ArrayList<Descriptor>();
        for (var profile : profiles) {
            Meta declared = meta.get(profile.id());
            if (declared == null) continue;
            packages.add(descriptor(profile, declared));
        }
        var all = List.copyOf(packages);
        // CF-PACKAGES-001 slice 4: a package that breaks the contract never reaches the production catalog.
        MethodologyPackageContract.requireProduction(all.stream().map(item -> new MethodologyPackageContract.Declaration(
                profiles.stream().filter(profile -> profile.id().equals(item.packageId())).findFirst().orElseThrow(),
                item.program(), item.version(), item.status(), item.capabilities())).toList());
        var canonical = new StringBuilder("CF-METHODOLOGY-PACKAGES@" + CATALOG_VERSION);
        all.forEach(item -> canonical.append('|').append(item.digest()));
        ACTIVITY_TYPES.forEach(item -> canonical.append('|').append(item.value()));
        return new Catalog(CATALOG_VERSION, sha256(canonical.toString()), all, ACTIVITY_TYPES);
    }

    private static Descriptor descriptor(ProjectJourney.Profile profile, Meta meta) {
        var capabilities = new TreeMap<>(meta.capabilities());
        var activities = new java.util.TreeSet<>(profile.activities());
        String canonical = String.join("|", profile.id(), Integer.toString(meta.version()), meta.program(),
                profile.candidate(), profile.edition(), profile.title(), meta.status(),
                capabilities.toString(), activities.toString(),
                profile.questions().toString(), profile.activityQuestions().toString(),
                // A04: the project-document plan is part of the package; changing it moves the digest.
                profile.pd().template(), profile.pd().templateEdition(), profile.pd().sections().toString(),
                profile.pd().blockers().toString());
        return new Descriptor(profile.id(), meta.version(), meta.program(), profile.candidate(), profile.edition(),
                profile.title(), meta.status(), Map.copyOf(capabilities), Set.copyOf(activities), sha256(canonical));
    }

    /** Digest of a descriptor as the catalog would compute it; lets a test prove that a change moves the digest. */
    static String digestOf(ProjectJourney.Profile profile, String program, int version, String status,
                           Map<String, String> capabilities) {
        return descriptor(profile, new Meta(program, version, status, capabilities)).digest();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
