package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.util.Set;

/** Metadata and arithmetic preview only; a declaration is not independent proof. */
final class OpenDyAssessmentRules {
    record Values(String outcome, BigDecimal erc, BigDecimal erf, BigDecimal preview) {}
    private OpenDyAssessmentRules() {}

    static Values values(String outcome, String erc, String erf, String calendarGate) {
        if (outcome == null || !Set.of("INCONCLUSIVE","FLEET_CREDITS_FOUND",
                "NO_FLEET_CREDITS_DECLARED").contains(outcome))
            throw new IllegalArgumentException("Informe o resultado da pesquisa.");
        if ("INCONCLUSIVE".equals(outcome)) {
            if (erc != null || erf != null)
                throw new IllegalArgumentException("Pesquisa inconclusiva não possui totais nem Dy.");
            return new Values(outcome, null, null, null);
        }
        if (calendarGate != null)
            throw new IllegalArgumentException("O ano anterior não está resolvido. Registre pesquisa inconclusiva.");
        var charging = decimal(erc);
        var fleet = decimal(erf);
        boolean absence = "NO_FLEET_CREDITS_DECLARED".equals(outcome);
        if (absence ? fleet.signum()!=0 : fleet.signum()<=0)
            throw new IllegalArgumentException("ERF precisa corresponder ao resultado declarado da pesquisa.");
        // Only preview the declared scenario. Do not send this Discount to the
        // project runner or imply that noFleetCreditsVerified is a real verdict.
        var preview = OpenGridCalculationMath.factor(
            new OpenGridCalculationMath.Discount(charging,fleet,absence));
        return new Values(outcome, charging, fleet, preview);
    }

    static BigDecimal decimal(String raw) {
        if (raw==null || raw.length()>40 || !raw.matches("\\d{1,18}(\\.\\d{1,12})?"))
            throw new IllegalArgumentException("ERC e ERF exigem valores explícitos não negativos em tCO2e (até 12 decimais).");
        return new BigDecimal(raw).stripTrailingZeros();
    }

    static String text(String value, int min, int max) {
        if (value==null || value.strip().length()<min || value.strip().length()>max
                || value.indexOf('\0')>=0)
            throw new IllegalArgumentException("Informe programas, referências, localizador e limites da pesquisa.");
        return value.strip();
    }

    static String number(BigDecimal value) {
        return value==null?null:value.stripTrailingZeros().toPlainString();
    }
}
