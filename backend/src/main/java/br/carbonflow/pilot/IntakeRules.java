package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static br.carbonflow.pilot.IntakeModel.*;

final class IntakeRules {
    static final int MAX_VEHICLES = 100;
    static final int MAX_STATIONS = 10;
    static final List<RecordType> RIGHTS = List.of(RecordType.DIREITOS, RecordType.EXCEDENTE, RecordType.SOBREPOSICAO);
    static final List<RecordType> PRIOR = List.of(RecordType.ENGAJAMENTO, RecordType.MECANISMO, RecordType.ESG);
    static final List<Reason> REAL_GATES = List.of(
            new Reason("PEN-002/012", "CAM-E4-0208", "Revalidar edição, datas, canal e dados reais por ato e vintage."),
            new Reason("PEN-003/005", "REG-E2-07/11", "Validar linha de base, OM/EF, amostra ED/EC autorizada, metrologia e cobertura."),
            new Reason("PEN-004", "REG-E2-04", "Comprovar titularidade e excluir sobreposição de direitos e créditos."),
            new Reason("PEN-010", "REG-E2-03", "Comprovar excedente regulatório, licenças e condições locais."),
            new Reason("PEN-011", "REG-E2-20", "Confirmar representação, geodados, V5#89, VVB, ramos, frações e posição aplicáveis."));

    static List<Reason> classify(Candidate submitted, LocalDate supportedDate) {
        var profile = submitted.profile();
        if (profile == null || submitted.inventory() == null || submitted.referenceActDate() == null) {
            throw invalid("PERFIL_INCOMPLETO", "profile", "REG-E2-01", "Informe perfil, inventário e data de referência.");
        }
        if (profile.country() == null || profile.state() == null || !text(profile.locality())
                || profile.design() == null || profile.vehicleClass() == null || profile.propulsion() == null
                || profile.charger() == null || profile.grid() == null || profile.ecRoute() == null
                || profile.closedNetwork() == null || profile.privateAccess() == null || profile.additionalInfrastructure() == null) {
            throw invalid("PERFIL_INCOMPLETO", "profile", "REG-E2-01", "Preencha cada dimensão do perfil.");
        }
        validateInventory(submitted.inventory());
        var reasons = new ArrayList<Reason>();
        if (!"BR".equals(profile.country()) || !"DF".equals(profile.state()) || !"INDIVIDUAL".equals(profile.design())) {
            reasons.add(new Reason("JURISDICAO_OU_DESENHO", "REG-E2-01/02", "O recorte cobre projetos individuais no DF, Brasil."));
        }
        if (!profile.closedNetwork() || !profile.privateAccess()) {
            reasons.add(new Reason("REDE_FORA_DO_RECORTE", "REG-E2-01", "A rede deve ser fechada, com frota identificada, e privada, com acesso controlado."));
        }
        if (!"LDV".equals(profile.vehicleClass()) || !"BEV".equals(profile.propulsion()) || !"DCFC".equals(profile.charger())) {
            reasons.add(new Reason("FROTA_OU_RECARGA_FORA_DO_RECORTE", "REG-E2-01/02", "O recorte exige BEVs leves e estações DCFC."));
        }
        if (!"SIN".equals(profile.grid()) || profile.additionalInfrastructure()) {
            reasons.add(new Reason("SUPRIMENTO_FORA_DO_RECORTE", "REG-E2-06/20", "O recorte exige SIN exclusivo e ausência de infraestrutura associada."));
        }
        if (!"MEDIDA_SEGREGADA".equals(profile.ecRoute())) {
            reasons.add(new Reason("ROTA_EC_FORA_DO_RECORTE", "REG-E2-11", "Use EC medida a montante em circuito exclusivo. A rota de 92,3% fica fora do piloto."));
        }
        if (!supportedDate.equals(submitted.referenceActDate())) {
            reasons.add(new Reason("VIGENCIA_A_REVALIDAR", "CAM-E4-0208", "A seleção temporal preparada corresponde à data do ato da amostra 0.2.14."));
        }
        return List.copyOf(reasons);
    }

    private static void validateInventory(Inventory inventory) {
        var sites = ids(inventory.sites(), Function.identity(), 3, "pátios");
        var stations = ids(inventory.stations(), Station::id, MAX_STATIONS, "DCFCs");
        var models = ids(inventory.models(), Model::id, 5, "modelos");
        ids(inventory.vehicles(), Vehicle::id, MAX_VEHICLES, "BEVs");
        ids(inventory.ecCircuits(), Circuit::id, 10, "canais EC");
        if (inventory.stations().stream().anyMatch(station -> !sites.contains(station.site())
                || !text(station.model()) || !decimal(station.powerKw()) || new BigDecimal(station.powerKw()).signum() <= 0)
                || inventory.models().stream().anyMatch(model -> !text(model.bev()) || !text(model.comparable()))
                || inventory.vehicles().stream().anyMatch(vehicle -> !models.contains(vehicle.model()))) {
            throw invalid("INVENTARIO_INCOERENTE", "inventory", "CAM-E4-0302/0305",
                    "Confira os vínculos de pátio, estação, veículo, linha de base e potência nominal.");
        }
        var linkedStations = new HashSet<String>();
        for (var circuit : inventory.ecCircuits()) {
            if (!stations.contains(circuit.station()) || !linkedStations.add(circuit.station())) {
                throw invalid("CONTORNO_EC_INVALIDO", "inventory.ecCircuits", "REG-E2-11",
                        "Relacione um circuito EC exclusivo por DCFC.");
            }
        }
        if (!linkedStations.equals(stations)) {
            throw invalid("CONTORNO_EC_INCOMPLETO", "inventory.ecCircuits", "REG-E2-11", "Há DCFC sem circuito EC.");
        }
    }

    private static <T> Set<String> ids(List<T> items, Function<T, String> key, int limit, String unit) {
        if (items == null || items.isEmpty()) {
            throw invalid("INVENTARIO_INCOMPLETO", "inventory", "CAM-E4-0301/0305", "Informe " + unit + ".");
        }
        if (items.size() > limit) {
            throw invalid("LIMITE_DE_CAPACIDADE", "inventory", "RF-E5-22",
                    "Limite de " + limit + " " + unit + " por projeto. Cadastro preservado.");
        }
        Set<String> ids = new HashSet<>();
        for (T item : items) {
            if (item == null || !id(key.apply(item)) || !ids.add(key.apply(item))) {
                throw invalid("INVENTARIO_INCOERENTE", "inventory", "CAM-E4-0301/0305", "Use IDs sintéticos únicos em " + unit + ".");
            }
        }
        return ids;
    }

    static MeterView covering(Snapshot state, String circuit, OffsetDateTime start, OffsetDateTime end) {
        return views(state).stream().filter(view -> view.revision().circuit().equals(circuit)
                && !start.isBefore(view.revision().details().effectiveFrom())
                && (view.effectiveUntilExclusive() == null || (start.isBefore(view.effectiveUntilExclusive())
                && !end.isAfter(view.effectiveUntilExclusive())))).findFirst().orElse(null);
    }

    static List<MeterView> views(Snapshot state) {
        // A vigência termina no início da sucessora. A predecessora permanece intacta.
        var result = new ArrayList<MeterView>();
        var nextStart = new java.util.HashMap<String, OffsetDateTime>();
        for (var revision : state.meters().reversed()) {
            result.add(new MeterView(revision, nextStart.put(revision.circuit(), revision.details().effectiveFrom())));
        }
        return List.copyOf(result.reversed());
    }

    static boolean calibrated(MeterRevision meter, OffsetDateTime start, OffsetDateTime end) {
        return meter.calibratedFrom() != null && !start.isBefore(meter.calibratedFrom())
                && !end.isAfter(meter.calibratedUntil());
    }

    static boolean segregated(MeterRevision meter) {
        return meter.details().diagram() != null && meter.details().upstream() && meter.details().exclusive();
    }

    static boolean id(String value) {
        return value != null && value.matches("SIM-[A-Z0-9-]{1,60}");
    }

    static boolean text(String value) {
        return value != null && !value.isBlank() && value.length() <= 600;
    }

    static boolean decimal(String value) {
        return value != null && value.matches("[0-9]{1,12}(\\.[0-9]{1,9})?");
    }

    static PilotProblem invalid(String code, String field, String rule, String message) {
        return PilotProblem.invalid(code, field, rule, message);
    }
}
