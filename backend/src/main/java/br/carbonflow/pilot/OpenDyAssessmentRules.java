package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.util.Set;

/** Metadata and arithmetic preview only; a declaration is not independent proof. */
final class OpenDyAssessmentRules {
    record Values(String outcome, BigDecimal erc, BigDecimal erf, BigDecimal preview) {}
    /** Declared totals from the start of crediting through the end of year y-1 (Q05.1, D14). Never the current reading. */
    record Cumulative(BigDecimal erc, BigDecimal erf, BigDecimal preview) {}
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

    /**
     * Cumulative totals are optional, come in pairs, need a resolved previous year and a non-inconclusive search,
     * and include the annual window, so they can never be smaller than the annual totals.
     */
    static Cumulative cumulative(String erc,String erf,int projectYear,Values annual) {
        if (erc==null && erf==null) return null;
        if (erc==null || erf==null)
            throw new IllegalArgumentException("Informe ERC e ERF acumulados juntos.");
        if (projectYear<2 || annual==null || annual.erc()==null)
            throw new IllegalArgumentException("Totais acumulados exigem ano 2 ou posterior e pesquisa não inconclusiva.");
        var charging=decimal(erc);
        var fleet=decimal(erf);
        if (charging.compareTo(annual.erc())<0 || fleet.compareTo(annual.erf())<0)
            throw new IllegalArgumentException("Totais acumulados incluem a janela anual e não podem ser menores que ela.");
        if (charging.signum()==0 && fleet.signum()==0 && !"NO_FLEET_CREDITS_DECLARED".equals(annual.outcome()))
            throw new IllegalArgumentException("Totais acumulados zerados só são coerentes com ausência declarada no ano anterior.");
        var preview=OpenGridCalculationMath.factor(new OpenGridCalculationMath.Discount(charging,fleet,fleet.signum()==0));
        return new Cumulative(charging,fleet,preview);
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
