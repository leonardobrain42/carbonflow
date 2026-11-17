package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static br.carbonflow.pilot.CarbonCalculator.decimal;
import static br.carbonflow.pilot.CarbonCalculator.number;

final class SelectedCalculation {
    static final String SNAPSHOT_FORMAT = "e8-005@1";
    static final String ENGINE = "vm0038-piloto-selecao@1";
    static final Fixture.Reference BASELINE = new Fixture.Reference("BASE-SYN-LDV", 1);
    static final Fixture.Reference FACTOR = new Fixture.Reference("EF-SYN-v1", 1);
    static final Fixture.Reference DY = new Fixture.Reference("SIM-DY-REDE-01", 1);

    record Proposal(String period, EnergyImportModel.Ref selection, Fixture.Reference baseline,
                    Fixture.Reference factor, Fixture.Reference dy) {}
    record FactorInput(String code, String value, String unit, String grid, List<Integer> sourceYears,
                       LocalDate validFrom, LocalDate validUntilExclusive, String source, boolean published) {}
    record Factor(Fixture.Reference reference, String value, String unit, String grid, List<Integer> sourceYears,
                  String sourceYearsNote, LocalDate validFrom, LocalDate validUntilExclusive, String source,
                  boolean published, String state, String reason) {}
    record ParameterCatalogue(Fixture.Reference baseline, Fixture.Reference dy, List<Factor> factors,
                              FixtureCatalog.Provenance provenance) {}
    record Model(String inventoryModel, String bev, String comparable, Fixture.Model values) {}
    record Baseline(Fixture.Reference reference, String service, List<Model> models, String fossilFactorTco2PerUsGal,
                    String ir, String fossilFuel, String source) {}
    record Discount(Fixture.Reference reference, String branch, String dy, UUID reviewedCaseRecord,
                    IntakeModel.Ref evidence, String source) {}
    record Inputs(String environment, String project, Proposal proposal, int configurationVersion, UUID configurationEvent,
                  UUID selectionEvent, String tzdbVersion, String zone, FixtureCatalog.Provenance provenance,
                  Fixture.Method method, Baseline baseline, Factor factor, Discount discount, CarbonCalculator.PeriodReport estimate) {}
    record Totals(LocalDate start, LocalDate endExclusive, String edKwh, String ecKwh, int edCount, int ecCount) {}
    record Snapshot(String format, String engine, Inputs inputs, int observations, String observationsSha256,
                    List<Totals> cuts) {}
    record Comparison(String estimate, String actual, String difference, String magnitude, String percent, String note) {}
    record Cut(LocalDate start, LocalDate endExclusive, int vintage, Totals inputs, CarbonCalculator.Memory emissions,
               String status, Comparison comparison) {}
    record Memory(String status, String factorPerMwh, String factorPerKwh, String factorConversion, String afec,
                  String mpg, int evrTotal, String dy, String removal, String emissionsUnit,
                  String rules, List<Cut> cuts, CarbonCalculator.Memory totals, Comparison comparison,
                  boolean readyForClosing, String coverageNote) {}
    record Run(UUID id, int version, String state, String period, UUID selectionId, int configurationVersion,
               String proposedBy, OffsetDateTime proposedAt, UUID proposeEvent, String reviewedBy, UUID reviewEvent,
               String snapshotSha256, Memory result, UUID resultEvent) {}
    record Replay(String snapshotSha256, boolean matches, Memory result, UUID eventId) {}

    static Memory compute(Snapshot snapshot) {
        if (!SNAPSHOT_FORMAT.equals(snapshot.format()) || !ENGINE.equals(snapshot.engine())) {
            throw PilotProblem.invalid("MOTOR_NAO_DISPONIVEL", "snapshot", "ARC-E6-06", "A versão de motor da projeção precisa estar disponível.");
        }
        Inputs inputs = snapshot.inputs();
        if (!DY.equals(inputs.discount().reference()) || !"1".equals(inputs.discount().dy())
                || inputs.discount().reviewedCaseRecord() == null || inputs.discount().evidence() == null) {
            throw PilotProblem.invalid("PROVA_INSUFICIENTE", "dy", "REG-E2-17", "A exceção de rede requer prova de ausência de crédito concorrente.");
        }
        if (!FACTOR.equals(inputs.factor().reference()) || !"SIN".equals(inputs.factor().grid())
                || !"tCO2/MWh".equals(inputs.factor().unit())
                || decimal(inputs.factor().value(), "factor.value").compareTo(new BigDecimal("0.30")) != 0) {
            throw PilotProblem.invalid("FATOR_NAO_FIXADO", "factor", "REG-E2-15", "O fator fixado do cenário é EF-SYN-v1.");
        }
        var models = inputs.baseline().models().stream().map(Model::values).toList();
        var terms = new CarbonCalculator().terms(models, inputs.baseline().fossilFactorTco2PerUsGal(),
                inputs.baseline().ir(), inputs.factor().value(), inputs.estimate().year());

        var cuts = new ArrayList<Cut>();
        for (var totals : snapshot.cuts()) {
            var estimate = inputs.estimate().cuts().stream()
                    .filter(cut -> cut.start().equals(totals.start()) && cut.endExclusive().equals(totals.endExclusive()))
                    .findFirst().orElseThrow();
            var ed = totals.edKwh() == null ? null : decimal(totals.edKwh(), "edKwh");
            var ec = totals.ecKwh() == null ? null : decimal(totals.ecKwh(), "ecKwh");
            var values = terms.apply(ed, ec);
            cuts.add(new Cut(totals.start(), totals.endExclusive(), totals.start().getYear(), totals, values,
                    values.er() == null ? "COM_LACUNAS" : "CALCULADA", compare(estimate.emissions().erDisplay(), values.er())));
        }
        var totals = CarbonCalculator.memory(sum(cuts, CarbonCalculator.Memory::be), sum(cuts, CarbonCalculator.Memory::pe),
                sum(cuts, CarbonCalculator.Memory::er));
        return new Memory(totals.er() == null ? "COM_LACUNAS" : "CALCULADA",
                inputs.factor().value(), number(terms.factorPerKwh()), "EF tCO₂/MWh ÷ 1.000 = EF tCO₂/kWh",
                number(terms.fleet().afec()), number(terms.fleet().mpg()), models.stream().mapToInt(Fixture.Model::count).sum(),
                inputs.discount().dy(), "0", "tCO2e", "CAL-E2-01–07 e conversão CAL-E2-09; OM ex ante de referência sintética",
                List.copyOf(cuts), totals, compare(inputs.estimate().emissions().erDisplay(), totals.er()),
                false, "A seleção preserva as leituras admitidas. A cobertura integral e o fechamento pertencem à E8-006.");
    }

    static Comparison compare(String estimate, String actual) {
        if (actual == null) return new Comparison(estimate, null, null, null, null, "Recorte sem ED ou EC suficiente para comparar.");
        BigDecimal base = new BigDecimal(estimate);
        BigDecimal difference = new BigDecimal(actual).subtract(base);
        BigDecimal percent = base.signum() == 0 ? null : difference.movePointRight(2).divide(base, MathContext.DECIMAL128);
        return new Comparison(estimate, actual, number(difference), number(difference.abs()), number(percent),
                percent == null ? "Estimativa zero. A diferença é apresentada em tCO₂e." : "Comparativo com a estimativa sintética do kit.");
    }

    private static BigDecimal sum(List<Cut> cuts, java.util.function.Function<CarbonCalculator.Memory, String> amount) {
        BigDecimal total = BigDecimal.ZERO;
        for (var cut : cuts) {
            String value = amount.apply(cut.emissions());
            if (value == null) return null;
            total = total.add(new BigDecimal(value));
        }
        return total;
    }
}
