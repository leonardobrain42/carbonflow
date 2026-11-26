package br.carbonflow.pilot;

import java.util.Map;
import java.util.Set;

/**
 * Public licences the platform knows how to read (platform level, D17: no method or source is fixed here). A licence that
 * is not listed cannot be declared, so no unmodelled licence slips through. The sets say, per operation, whether the
 * licence text itself grants it (LICENSE_GRANT), whether it can only be argued (LEGAL_INTERPRETATION, to be confirmed by
 * an independent approval) or whether the licence does not cover it at all (refused).
 */
final class PublicLicenses {
    record Terms(String identifier, String legalTextUri, Set<String> granted, Set<String> refused, String basis) {}

    static final Map<String, Terms> KNOWN = Map.of("CC-BY-ND-3.0", new Terms("CC-BY-ND-3.0",
            "https://creativecommons.org/licenses/by-nd/3.0/legalcode",
            // Cl. 3: to Reproduce the Work, incorporate it in Collections, Distribute and Publicly Perform it; also the
            // modifications "technically necessary to exercise the rights in other media and formats".
            Set.of("REFERENCE_REQUIREMENT", "STORE_ORIGINAL", "SHOW_EXCERPTS"),
            // ND: the grant has no right to make Adaptations; redistributing a modified work is therefore not covered.
            Set.of("EXPORT_DERIVATIVES"),
            "CC BY-ND 3.0, cláusulas 1 (Adaptation), 3 (License Grant) e 4"));

    private PublicLicenses() {}
}
