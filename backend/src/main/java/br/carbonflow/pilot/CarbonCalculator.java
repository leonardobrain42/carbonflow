package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static br.carbonflow.pilot.PilotProblem.invalid;

final class CarbonCalculator {
    static final String VERSION = "vm0038-piloto@1";
    private static final MathContext DECIMAL = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    static final LocalDate FIRST_START = LocalDate.of(2027, 10, 1);

    record Memory(String be, String pe, String le, String er, String erDisplay) {}

    record CutReport(int vintage, LocalDate start, LocalDate endExclusive, LocalDate endInclusive,
                     String edKwh, String ecKwh, Memory emissions) {}

    record PeriodReport(String id, int year, LocalDate start, LocalDate endExclusive, LocalDate endInclusive,
                        List<CutReport> cuts, Memory emissions) {}

    record Results(String afecKwhPer100Mi, String mpg, String dy, String electricityFactorTco2PerKwh,
                   String emissionsUnit, String energyUnit, List<PeriodReport> periods) {}

    Results calculate(Fixture fixture) {
        validateProfile(fixture);
        var input = fixture.calculation();
        if (!"SIM-DY-REDE-01".equals(fixture.method().discountProof())) {
            throw invalid("PROVA_INSUFICIENTE", "method.discountProof", "REG-E2-17",
                    "A exceção sintética de Dy requer a prova de rede privada e fechada.");
        }
        BigDecimal fuel = decimal(input.fuelFactorTco2PerGal(), "calculation.fuelFactorTco2PerGal");
        BigDecimal ir = decimal(input.ir(), "calculation.ir");
        if (ir.compareTo(BigDecimal.ONE) != 0) {
            throw invalid("PERFIL_NAO_SUPORTADO", "calculation.ir", "REG-E2-10", "O perfil LDV usa IR = 1.");
        }
        BigDecimal electricityFactor = decimal(input.factor().value(), "calculation.factor.value").movePointLeft(3);
        Fleet fleet = fleet(input.models());
        var periods = input.periods();
        if (periods == null || periods.size() != 2) {
            throw invalid("LACUNA_OBRIGATORIA", "calculation.periods", "REG-E2-19", "A massa exige M1 e M2.");
        }
        var reports = new ArrayList<PeriodReport>();
        for (int index = 0; index < periods.size(); index++) {
            var period = periods.get(index);
            var start = FIRST_START.plusYears(index);
            var window = new VintageWindows.Window(start, start.plusYears(1));
            if (period == null || !("M" + (index + 1)).equals(period.id()) || period.year() != index + 1
                    || !start.equals(period.start()) || !window.endExclusive().equals(period.endExclusive())) {
                throw invalid("PERIODO_INVALIDO", "calculation.periods[" + index + "]", "REG-E2-19",
                        "Identidade, ano do projeto ou janela divergente do calendário sintético.");
            }
            reports.add(calculatePeriod(period, window, fleet, fuel, ir, electricityFactor));
        }
        return new Results(number(fleet.afec()), number(fleet.mpg()), "1", number(electricityFactor),
                "tCO2e", "kWh", List.copyOf(reports));
    }

    private void validateProfile(Fixture fixture) {
        var method = fixture.method();
        var input = fixture.calculation();
        if (!"1".equals(fixture.contractVersion()) || !"SIMULACAO".equals(fixture.environment())
                || !"PIL-SYN-BR-001".equals(fixture.projectId()) || method == null || input == null
                || !"VCS".equals(method.program()) || !"5.0".equals(method.ruleSet())
                || !"PD/MR 5.0B".equals(method.documentModel()) || !"VM0038@1.1".equals(method.packageVersion())
                || !"BR-BEV-LDV-DCFC-FECHADO-SIN".equals(method.profile())
                || !List.of("VMD0049@1.1", "VT0011@1.0", "TOOL07@7.0").equals(method.dependencies())
                || !VintageWindows.ZONE.getId().equals(input.zone())) {
            throw invalid("PERFIL_NAO_SUPORTADO", "fixture", "ARC-E6-03",
                    "Use a massa sintética versionada do PIL-SYN-BR-001.");
        }
        var factor = input.factor();
        if (factor == null || !"EF-SYN-v1".equals(factor.id()) || !"tCO2/MWh".equals(factor.unit())
                || decimal(factor.value(), "calculation.factor.value").compareTo(new BigDecimal("0.30")) != 0) {
            throw invalid("FONTE_NAO_ADMISSIVEL", "calculation.factor", "REG-E2-15",
                    "A execução requer EF-SYN-v1 de 0,30 tCO₂/MWh.");
        }
    }

    record Fleet(BigDecimal afec, BigDecimal mpg) {}
    record Terms(Fleet fleet, BigDecimal adjustedFuel, BigDecimal denominator, BigDecimal factorPerKwh) {
        Memory apply(BigDecimal ed, BigDecimal ec) {
            BigDecimal be = ed == null ? null : ed.multiply(adjustedFuel, DECIMAL).divide(denominator, DECIMAL);
            BigDecimal pe = ec == null ? null : ec.multiply(factorPerKwh, DECIMAL);
            return memory(be, pe, be == null || pe == null ? null : be.subtract(pe, DECIMAL));
        }
    }

    Terms terms(List<Fixture.Model> models, String fossil, String improvement, String factorPerMwh, int year) {
        var fleet = fleet(models);
        BigDecimal fuel = decimal(fossil, "calculation.fuelFactorTco2PerGal");
        BigDecimal ir = decimal(improvement, "calculation.ir");
        if (ir.compareTo(BigDecimal.ONE) != 0 || year < 1) {
            throw invalid("PERFIL_NAO_SUPORTADO", "calculation.ir", "REG-E2-10", "O perfil LDV usa IR = 1 e ano positivo.");
        }
        return terms(fleet, fuel, ir, decimal(factorPerMwh, "calculation.factor.value").movePointLeft(3), year);
    }

    private Terms terms(Fleet fleet, BigDecimal fuel, BigDecimal ir, BigDecimal electricityFactor, int year) {
        return new Terms(fleet, fuel.multiply(HUNDRED, DECIMAL).multiply(ir.pow(year - 1, DECIMAL), DECIMAL),
                fleet.afec().multiply(fleet.mpg(), DECIMAL), electricityFactor);
    }

    private Fleet fleet(List<Fixture.Model> models) {
        long count = 0;
        BigDecimal weightedEnergy = BigDecimal.ZERO;
        BigDecimal weightedMpg = BigDecimal.ZERO;
        if (models != null) {
            for (var model : models) {
                BigDecimal energy = decimal(model.evKwhPer100Mi(), "calculation.models.evKwhPer100Mi");
                BigDecimal mpg = decimal(model.mpg(), "calculation.models.mpg");
                if (model.count() < 0 || energy.signum() == 0 || mpg.signum() == 0) {
                    throw invalid("MODELO_INVALIDO", "calculation.models", "REG-E2-09",
                            "EVR deve ser não negativo e AFEC e MPG devem ser positivos.");
                }
                count += model.count();
                var weight = BigDecimal.valueOf(model.count());
                weightedEnergy = weightedEnergy.add(energy.multiply(weight));
                weightedMpg = weightedMpg.add(mpg.multiply(weight));
            }
        }
        if (count == 0) {
            throw invalid("EVR_TOTAL_INVALIDO", "calculation.models.count", "REG-E2-09",
                    "A quantidade total de veículos deve ser positiva.");
        }
        var total = BigDecimal.valueOf(count);
        return new Fleet(weightedEnergy.divide(total, DECIMAL), weightedMpg.divide(total, DECIMAL));
    }

    private PeriodReport calculatePeriod(Fixture.Period period, VintageWindows.Window window, Fleet fleet,
                                         BigDecimal fuel, BigDecimal ir, BigDecimal electricityFactor) {
        var windows = VintageWindows.split(window);
        String field = "calculation." + period.id() + ".cuts";
        if (period.cuts() == null || period.cuts().size() != windows.size()) {
            throw invalid("LACUNA_OBRIGATORIA", field, "REG-E2-19", "Faltam os agregados de cada vintage.");
        }
        var reports = new ArrayList<CutReport>();
        BigDecimal totalBe = BigDecimal.ZERO;
        BigDecimal totalPe = BigDecimal.ZERO;
        BigDecimal totalEr = BigDecimal.ZERO;
        var terms = terms(fleet, fuel, ir, electricityFactor, period.year());
        for (int index = 0; index < windows.size(); index++) {
            var cut = period.cuts().get(index);
            var expected = windows.get(index);
            String cutField = field + "[" + index + "]";
            if (cut == null || cut.start() == null || cut.endExclusive() == null) {
                throw invalid("LACUNA_OBRIGATORIA", cutField, "REG-E2-19", "Informe as duas datas do recorte.");
            }
            var actual = new VintageWindows.Window(cut.start(), cut.endExclusive());
            VintageWindows.assign(actual.opening(), actual.ending(), windows, cutField);
            if (!actual.equals(expected)) {
                throw invalid("LACUNA_OBRIGATORIA", cutField, "REG-E2-19",
                        "O agregado deve cobrir exatamente as datas do recorte sintético.");
            }
            BigDecimal ed = decimal(cut.edKwh(), cutField + ".edKwh");
            BigDecimal ec = decimal(cut.ecKwh(), cutField + ".ecKwh");
            var result = terms.apply(ed, ec);
            reports.add(new CutReport(actual.start().getYear(), actual.start(), actual.endExclusive(),
                    actual.endExclusive().minusDays(1), number(ed), number(ec), result));
            totalBe = totalBe.add(new BigDecimal(result.be()));
            totalPe = totalPe.add(new BigDecimal(result.pe()));
            totalEr = totalEr.add(new BigDecimal(result.er()));
        }
        return new PeriodReport(period.id(), period.year(), window.start(), window.endExclusive(),
                window.endExclusive().minusDays(1), List.copyOf(reports), memory(totalBe, totalPe, totalEr));
    }

    static BigDecimal decimal(String value, String field) {
        if (value == null) {
            throw invalid("LACUNA_OBRIGATORIA", field, "REG-E2-08/11", "O valor obrigatório está ausente.");
        }
        if (!value.matches("[0-9]+(\\.[0-9]+)?")) {
            throw invalid("ENTRADA_INVALIDA", field, "CAL-E2-01/07",
                    "Informe um decimal não negativo, com ponto e sem expoente.");
        }
        return new BigDecimal(value);
    }

    static Memory memory(BigDecimal be, BigDecimal pe, BigDecimal er) {
        // CAL-E2-05 a 07. Fuga zero e Dy=1 no ramo sintético comprovado.
        return new Memory(number(be), number(pe), "0", number(er),
                er == null ? null : er.setScale(2, RoundingMode.HALF_EVEN).toPlainString());
    }

    static String number(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
