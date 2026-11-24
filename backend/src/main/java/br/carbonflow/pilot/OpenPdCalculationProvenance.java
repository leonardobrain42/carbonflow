package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only mapping of the run's pinned references, never a second input ledger.
 * Source bytes, licensed values, locators and personal review data stay with their owners.
 */
final class OpenPdCalculationProvenance {
    record Input(String symbol, String origin, String unit, Integer vintageYear,
                 UUID referenceId, Integer version, String href, String access) {}
    record View(String mappingVersion, String sectionId, UUID runId, int runVersion,
                String inputSha256, String memoryHref, String method, String engine,
                String reviewStatus, String kernelStatus, OffsetDateTime recordedAt,
                OpenE8ProjectRuns.Timing timing, List<Input> inputs,
                List<OpenE8RunDecision.Step> references, String limits) {}
    private record Ref(UUID id, int version, Integer year, String unit, String kind) {}

    private OpenPdCalculationProvenance() {}

    static View read(Connection c, DynamicProjectScope.Context scope, OpenE8ProjectRuns.Attempt run)
            throws SQLException {
        if (!scope.actions().contains("VIEW_OPEN_E8_RUN") || run == null
                || !scope.id().equals(run.projectId())
                || !"MEMORY".equals(OpenPdCalculationNote.from(run).situation())) return null;
        String root = "/v1/projects/" + scope.id() + "/technical/";
        String periodRoot = root + "open-monitoring/dossiers/" + run.period() + "/";
        var inputs = new ArrayList<Input>();
        Ref energy = scope.actions().contains("VIEW_OPEN_E8_BINDING_CANDIDATE")
            ? first(c, """
                SELECT id,version FROM app_open_e8_input_binding_candidate
                WHERE project_id=? AND id=? AND period_code=?
                """, r -> new Ref(r.getObject(1, UUID.class), r.getInt(2), null, null, null),
                scope.id(), run.energyBindingId(), run.period()) : null;
        inputs.add(input("ED", "Medição de energia entregue · vínculo do período", "kWh",
            energy, periodRoot + "e8-input-bindings/"));
        inputs.add(input("EC", "Medição de energia consumida · vínculo do período", "kWh",
            energy, periodRoot + "e8-input-bindings/"));
        inputs.add(input("EF_GRID", "Fator elétrico horário · vínculo do período", "tCO2e/kWh",
            energy, periodRoot + "e8-input-bindings/"));
        Ref fleet = scope.actions().contains("VIEW_OPEN_E8_FLEET_SELECTION")
            ? first(c, """
                SELECT id,version,observation_year FROM app_open_e8_fleet_selection
                WHERE project_id=? AND id=? AND period_code=?
                """, r -> new Ref(r.getObject(1, UUID.class), r.getInt(2), r.getInt(3), null, null),
                scope.id(), run.fleetSelectionId(), run.period()) : null;
        inputs.add(input("EVR", "Estoque EVR · seleção de frota e ratings", "veículos",
            fleet, periodRoot + "e8-fleet-selections/"));
        inputs.add(input("AFEC", "Rating BEV ponderado · seleção de frota e ratings", "kWh/100 mi",
            fleet, periodRoot + "e8-fleet-selections/"));
        inputs.add(input("MPG", "Rating fóssil comparável ponderado · seleção de frota e ratings", "mi/US gal",
            fleet, periodRoot + "e8-fleet-selections/"));
        UUID[] pins = first(c, "SELECT fuel_pin_id,ir_pin_id,dy_pin_id FROM app_open_e8_run WHERE project_id=? AND id=?",
            r -> new UUID[]{r.getObject(1, UUID.class),r.getObject(2, UUID.class),r.getObject(3, UUID.class)}, scope.id(),run.id());
        inputs.add(pin(c, scope, "EF_FUEL", pins == null ? null : pins[0]));
        inputs.add(pin(c, scope, "IR", pins == null ? null : pins[1]));
        inputs.add(pin(c, scope, "DY", pins == null ? null : pins[2]));
        return new View("OPEN-PD-CALCULATION-PROVENANCE@1", "quantification", run.id(),
            run.version(), run.inputSha256(), periodRoot + "e8-runs/" + run.id(),
            OpenCalculationTransparency.REFERENCE, run.memory().engine(), run.state(),
            run.memory().kernelState(), run.createdAt(), run.timing(), List.copyOf(inputs),
            run.memory().trace(),
            "Prévia de leitura vinculada à memória, sem cópia para a narrativa ou versão congelada. "
                + "Unidades normalizadas do contrato E8; origem limitada aos vínculos canônicos. "
                + "Títulos, unidades originais, conversões e locators das fontes permanecem com seus owners. "
                + "Sem conteúdo licenciado, revisão editorial, admissão normativa ou verificação externa. "
                + "O PD não fica pronto.");
    }

    private static Input pin(Connection c, DynamicProjectScope.Context scope, String symbol, UUID id)
            throws SQLException {
        Ref ref = scope.actions().contains("VIEW_OPEN_PARAMETER_BINDING") && id != null
            ? first(c, """
                SELECT id,version,vintage_year,normalized_unit,value_kind
                FROM app_open_project_parameter_binding
                WHERE project_id=? AND id=? AND parameter_kind=?
                """, r -> new Ref(r.getObject(1, UUID.class), r.getInt(2), r.getInt(3),
                    r.getString(4), r.getString(5)), scope.id(), id, symbol) : null;
        String origin = ref == null ? null : "REFERENCE".equals(ref.kind())
            ? "Referência de catálogo · pino publicado no projeto"
            : "Declaração explícita com regra e prova · pino do projeto";
        // The existing list API is not an immutable detail endpoint: do not link it as one.
        String unit = ref == null ? null : ref.unit() != null ? ref.unit()
            : "IR".equals(symbol) || "DY".equals(symbol) ? "adimensional" : null;
        return input(symbol, origin, unit, ref, null);
    }

    private static Input input(String symbol, String origin, String unit, Ref ref, String prefix) {
        if (ref == null) return new Input(symbol, "Referência protegida ou indisponível", null,
            null, null, null, null, "RESTRICTED_OR_UNAVAILABLE");
        return new Input(symbol, origin, unit, ref.year(), ref.id(), ref.version(),
            prefix == null ? null : prefix + ref.id(), "PROJECT_METADATA_ONLY");
    }
}
