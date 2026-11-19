package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, source-agnostic EVR validation. The result is a preparatory composition,
 * NEVER an admitted input for E8 or a verified national EV fleet.
 */
final class FleetCompositionRules {
    static final int WEIGHT_SCALE = 18;
    static final String STOCK = "STOCK_IN_CIRCULATION";
    static final String SALES = "NEW_SALES";

    record Row(int sourceRow, UUID modelId, String recordKey, BigDecimal value,
               String metric, String unit, int observationYear, String geography,
               String vehicleClass, String propulsion, String locator) {}
    record Weight(int sourceRow, UUID modelId, String recordKey, long quantity,
                  BigDecimal weight, String locator) {}
    record Result(long total, List<Weight> weights, List<String> issues, boolean usableForE8) {
        boolean valid() { return issues.isEmpty(); }
    }

    static Result evaluate(String dataKind, int year, String geography, List<Row> input) {
        var issues = new ArrayList<String>();
        var rows = new ArrayList<Row>();
        var seenModels = new HashSet<UUID>();
        var seenSourceRows = new HashSet<Integer>();
        if (!STOCK.equals(dataKind) && !SALES.equals(dataKind)) issues.add("Tipo desconhecido: estoque em circulação e vendas são domínios distintos.");
        if (year < 1900 || year > 2100 || !"BR".equals(geography)) issues.add("O recorte v1 exige ano explícito e geografia BR nacional.");
        if (input == null || input.isEmpty()) issues.add("A série não possui observações; ausência não equivale a zero.");
        if (input != null && input.size() > 2000) issues.add("O limite da série é 2.000 observações.");
        if (!issues.isEmpty()) return new Result(0, List.of(), List.copyOf(issues), false);
        long total = 0;
        for (Row row : input) {
            if (row == null) { issues.add("Observação nula."); continue; }
            String location = row.locator() == null ? "linha " + row.sourceRow() : row.locator();
            if (row.modelId() == null) {
                issues.add(location + ": alias ou modelo não classificado — nenhum peso será descartado.");
                continue;
            }
            if (!seenSourceRows.add(row.sourceRow()) || !seenModels.add(row.modelId())) {
                issues.add(location + ": alias/modelo duplicado no mesmo ano.");
                continue;
            }
            if (row.observationYear() != year || !"BR".equals(row.geography())) {
                issues.add(location + ": anos ou geografias misturados na mesma composição.");
            }
            if (!"vehicles".equals(row.unit()) ||
                    !(STOCK.equals(dataKind) ? "stock".equals(row.metric()) : "sales".equals(row.metric()))) {
                issues.add(location + ": métrica/unidade incompatível; vendas não viram estoque e PBEV não gera EVR.");
            }
            if (!"LDV".equals(row.vehicleClass()) || !"BEV".equals(row.propulsion())) {
                issues.add(location + ": somente LDV BEV pertence ao recorte aberto DCFC v1.");
            }
            if (row.value() == null) {
                issues.add(location + ": quantidade ausente não equivale a zero.");
                continue;
            }
            try {
                long quantity = row.value().longValueExact();
                if (quantity < 0 || quantity > 1_000_000_000_000L) {
                    issues.add(location + ": quantidade fora do intervalo permitido.");
                    continue;
                }
                total = Math.addExact(total, quantity);
                rows.add(row);
            } catch (ArithmeticException problem) {
                issues.add(location + ": EVR precisa ser inteiro; arredondamento é proibido.");
            }
        }
        if (total == 0 && STOCK.equals(dataKind)) issues.add("Denominador EVR nacional é zero; não é possível calcular pesos.");
        if (!issues.isEmpty()) return new Result(0, List.of(), List.copyOf(issues), false);
        if (SALES.equals(dataKind)) return new Result(total,List.of(),List.of(),false);

        rows.sort(Comparator.comparingInt(Row::sourceRow));
        var weighted = new ArrayList<Weight>();
        BigDecimal denominator = BigDecimal.valueOf(total);
        BigDecimal sum = BigDecimal.ZERO.setScale(WEIGHT_SCALE);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            BigDecimal ratio = i == rows.size() - 1
                    ? BigDecimal.ONE.setScale(WEIGHT_SCALE).subtract(sum)
                    : row.value().divide(denominator, WEIGHT_SCALE, RoundingMode.HALF_EVEN);
            if (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0 ||
                    (row.value().signum() > 0 && ratio.signum() == 0)) {
                return new Result(0,List.of(),List.of("Precisão insuficiente para representar os pesos desta série."),false);
            }
            weighted.add(new Weight(row.sourceRow(),row.modelId(),row.recordKey(),
                    row.value().longValueExact(),ratio,row.locator()));
            sum = sum.add(ratio);
        }
        if (sum.compareTo(BigDecimal.ONE) != 0)
            return new Result(0,List.of(),List.of("Os pesos não somam exatamente 1."),false);
        return new Result(total,List.copyOf(weighted),List.of(),false);
    }

    private FleetCompositionRules() {}
}
