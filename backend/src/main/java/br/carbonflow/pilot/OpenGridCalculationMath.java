package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Pure VM0038 v1.1 §9 (Eq. 1–4, 7–8) arithmetic for the single
 * OPEN/BR/LDV/BEV/DCFC/SIN, grid-only, one-fleet/one-fuel branch.
 *
 * The project runner calls this kernel with versioned inputs for internal
 * rehearsals. Parameter admission and project permissions belong to the caller.
 */
final class OpenGridCalculationMath {
    static final String ENGINE = "vm0038-open-grid-arithmetic@1";
    static final String HOURLY_ENGINE = "vm0038-open-grid-arithmetic@2-hourly";
    static final String METHOD = "VM0038@1.1";
    static final String BRANCH = "OPEN_LDV_BEV_DCFC_SIN_GRID_ONLY";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int MAX_MODELS = 500;

    record FleetRow(String modelCode, long evr, BigDecimal kwhPer100Miles,
                    BigDecimal comparableMpg) {}
    record Discount(BigDecimal chargingCreditsTco2e, BigDecimal fleetCreditsTco2e,
                    boolean noFleetCreditsVerified) {}
    record Input(String branch, int projectYear, List<FleetRow> fleet,
                 BigDecimal edKwh, BigDecimal ecKwh,
                 BigDecimal fuelTco2ePerUsGallon, BigDecimal gridTco2ePerKwh,
                 BigDecimal ir, Discount discount) {}
    record Operand(String code, String value, String unit) {}
    record Step(String code, String reference, String description, String unit,
                List<Operand> inputs, String result) {}
    record ModelWeight(String modelCode, long evr, String kwhPer100Miles,
                       String comparableMpg, String weightedEnergy,
                       String weightedMpg) {}
    record Memory(String engine, String method, String branch, String state,
                  boolean usableForE8, int projectYear, long evrTotal,
                  String afecKwhPer100Miles, String mpgUs,
                  String edKwh, String ecKwh, String fuelTco2ePerUsGallon,
                  String gridTco2ePerKwh, String ir, String dy,
                  String beTco2e, String peTco2e, String leTco2e,
                  String erTco2e, String erDisplay,
                  List<ModelWeight> fleetTrace, List<Step> equationTrace,
                  OpenHourlyProjectEmissions.Memory hourlyPe) {}

    private OpenGridCalculationMath() {}

    static Memory calculate(Input input) {
        return calculate(input, null);
    }

    static Memory calculate(Input input, OpenHourlyProjectEmissions.Series hourly) {
        if (input == null || !BRANCH.equals(input.branch()))
            throw invalid("Somente rede OPEN DCFC/SIN sem infraestrutura associada.");
        if (input.projectYear() < 1 || input.projectYear() > 100)
            throw invalid("Ano relativo do projeto deve estar entre 1 e 100.");
        if (input.fleet() == null || input.fleet().isEmpty()
                || input.fleet().size() > MAX_MODELS)
            throw invalid("Composição nacional EVR por modelo ausente ou excessiva.");

        var sorted = new ArrayList<>(input.fleet());
        if (sorted.contains(null))throw invalid("Modelo EVR nulo.");
        sorted.sort(Comparator.comparing(FleetRow::modelCode,
            Comparator.nullsFirst(Comparator.naturalOrder())));
        var names = new HashSet<String>();
        long total = 0;
        BigDecimal weightedAfec = BigDecimal.ZERO;
        BigDecimal weightedMpg = BigDecimal.ZERO;
        var rows = new ArrayList<ModelWeight>();
        for (var row: sorted) {
            if (row.modelCode() == null
                    || !row.modelCode().matches("[A-Za-z0-9_.-]{1,64}")
                    || !names.add(row.modelCode()) || row.evr() <= 0)
                throw invalid("Modelo EVR inválido, repetido ou com estoque não positivo.");
            var ev = positive(row.kwhPer100Miles(), "rating BEV kWh/100 mi");
            var mpg = positive(row.comparableMpg(), "rating comparável MPG");
            try { total = Math.addExact(total, row.evr()); }
            catch (ArithmeticException e) { throw invalid("Soma EVR excede limite inteiro."); }
            BigDecimal weight = BigDecimal.valueOf(row.evr());
            var e = ev.multiply(weight, MC);
            var m = mpg.multiply(weight, MC);
            weightedAfec = weightedAfec.add(e, MC);
            weightedMpg = weightedMpg.add(m, MC);
            rows.add(new ModelWeight(row.modelCode(), row.evr(), number(ev),
                number(mpg), number(e), number(m)));
        }
        var denominator = BigDecimal.valueOf(total);
        BigDecimal afec = weightedAfec.divide(denominator, MC);
        BigDecimal mpg = weightedMpg.divide(denominator, MC);

        BigDecimal ed = nonnegative(input.edKwh(), "ED kWh");
        BigDecimal ec = nonnegative(input.ecKwh(), "EC kWh");
        BigDecimal fossil = nonnegative(input.fuelTco2ePerUsGallon(),
            "EFfuel tCO2e/US gal");
        OpenHourlyProjectEmissions.Memory hourlyPe = hourly == null ? null
            : OpenHourlyProjectEmissions.calculate(hourly);
        BigDecimal grid = hourlyPe == null
            ? nonnegative(input.gridTco2ePerKwh(), "EFel tCO2e/kWh") : null;
        if (hourlyPe != null && (input.gridTco2ePerKwh() != null
                || ed.compareTo(new BigDecimal(hourlyPe.edKwh())) != 0
                || ec.compareTo(new BigDecimal(hourlyPe.ecKwh())) != 0))
            throw invalid("Totais ou rota de fator incompatíveis com a série horária.");
        BigDecimal ir = positive(input.ir(), "IR");
        BigDecimal discount = factor(input.discount());
        BigDecimal irExponent = ir.pow(input.projectYear() - 1, MC);
        BigDecimal mileage = afec.multiply(mpg, MC);
        BigDecimal be = ed.multiply(fossil, MC)
            .multiply(ONE_HUNDRED, MC).multiply(irExponent, MC)
            .divide(mileage, MC);
        BigDecimal pe = hourlyPe == null ? ec.multiply(grid, MC) : new BigDecimal(hourlyPe.peTco2e());
        BigDecimal le = BigDecimal.ZERO; // VM0038 v1.1 §9.3, not a configurable default.
        BigDecimal er = be.subtract(pe, MC).multiply(discount, MC);

        var steps = List.of(
            step("AFEC","VM0038-1.1-EQ2","Média BEV ponderada pela frota EVR",
                "kWh/100 mi",number(afec),
                op("SUM_EVR_KWH", weightedAfec,"EVR × kWh/100 mi"),
                op("EVR_TOTAL",denominator,"veículos")),
            step("MPG","VM0038-1.1-EQ3","Média fóssil comparável ponderada por EVR",
                "mi/US gal",number(mpg),
                op("SUM_EVR_MPG", weightedMpg,"EVR × mi/US gal"),
                op("EVR_TOTAL",denominator,"veículos")),
            step("BE","VM0038-1.1-EQ1","Emissões de linha de base",
                "tCO2e",number(be),
                op("ED",ed,"kWh"),op("EF_FUEL",fossil,"tCO2e/US gal"),
                op("DISTANCE_FACTOR",ONE_HUNDRED,"mi/100 mi"),
                op("IR_POWER",irExponent,"adimensional"),
                op("AFEC",afec,"kWh/100 mi"),op("MPG",mpg,"mi/US gal")),
            hourlyPe == null
                ? step("PE","VM0038-1.1-EQ4","Emissões da eletricidade consumida",
                    "tCO2e",number(pe),op("EC",ec,"kWh"),op("EF_GRID",grid,"tCO2e/kWh"))
                : step("PE","VM0038-1.1-EQ4","Soma horária de EC × EFel",
                    "tCO2e",number(pe),op("EC",ec,"kWh"),
                    op("HOUR_COUNT",BigDecimal.valueOf(hourlyPe.hours().size()),"horas")),
            step("LE","VM0038-1.1-SECTION-9.3",
                "Fuga de minimis conforme ramo VM0038","tCO2e",number(le)),
            step("DY",input.discount().noFleetCreditsVerified()?
                "VM0038-1.1-SECTION-9.4-NO-FLEET-CREDITS":
                "VM0038-1.1-EQ8","Desconto por créditos concorrentes",
                "adimensional",number(discount),
                op("ERC",input.discount().chargingCreditsTco2e(),"tCO2e"),
                op("ERF",input.discount().fleetCreditsTco2e(),"tCO2e")),
            step("ER","VM0038-1.1-EQ7",
                "Reduções líquidas após desconto Dy","tCO2e",number(er),
                op("BE",be,"tCO2e"),op("PE",pe,"tCO2e"),
                op("DY",discount,"adimensional")));
        return new Memory(hourlyPe == null ? ENGINE : HOURLY_ENGINE,METHOD,BRANCH,
            "PURE_MATH_TEST_ONLY_NOT_ADMITTED",false,input.projectYear(),total,
            number(afec),number(mpg),number(ed),number(ec),number(fossil),
            grid == null ? null : number(grid),number(ir),number(discount),number(be),number(pe),
            number(le),number(er),er.setScale(2,RoundingMode.HALF_EVEN)
                .toPlainString(),List.copyOf(rows),steps,hourlyPe);
    }

    /**
     * The explicit no-fleet-credit exception requires independent proof from
     * the caller's future admission layer. Zero values alone cannot assert it.
     * Eq. 8 has no value when both credit categories are zero.
     */
    static BigDecimal factor(Discount input) {
        if (input == null)throw invalid("Dy requer avaliação de dupla contagem.");
        var erc = nonnegative(input.chargingCreditsTco2e(),"ERC");
        var erf = nonnegative(input.fleetCreditsTco2e(),"ERF");
        if (input.noFleetCreditsVerified()) {
            if (erf.signum() != 0)
                throw invalid("Prova de ausência de créditos de frota contradiz ERF.");
            return BigDecimal.ONE;
        }
        if (erf.signum() == 0)
            throw invalid("ERF zero sem prova de ausência de créditos de frota.");
        return erc.divide(erc.add(erf,MC),MC);
    }

    private static Step step(String code,String rule,String description,
                             String unit,String result,Operand... inputs) {
        return new Step(code,rule,description,unit,List.of(inputs),result);
    }
    private static Operand op(String code,BigDecimal value,String unit) {
        return new Operand(code,number(value),unit);
    }
    private static BigDecimal positive(BigDecimal v,String field) {
        v = nonnegative(v,field);
        if (v.signum()==0)throw invalid(field+" deve ser positivo.");
        return v;
    }
    private static BigDecimal nonnegative(BigDecimal v,String field) {
        if (v==null)throw invalid(field+" ausente; não substituir por zero.");
        var normalized=v.stripTrailingZeros();
        if (normalized.signum()<0 || normalized.precision()>30
                || normalized.scale()>12 || normalized.precision()-normalized.scale()>18)
            throw invalid(field+" possui valor ou precisão não suportados.");
        return normalized.signum()==0?BigDecimal.ZERO:normalized;
    }
    private static String number(BigDecimal v) {
        return v.signum()==0?"0":v.stripTrailingZeros().toPlainString();
    }
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }
}
