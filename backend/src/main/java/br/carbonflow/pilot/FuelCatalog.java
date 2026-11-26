package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * D19 slice 5 / Q12 (#96): persistent, extensible catalog of fuels (platform level). Holds identities, blend
 * compositions and data points (properties and emission factors) with evidence, validity, vintage, gas, scope, GWP set
 * and ORIGINAL unit. It never admits anything: every response says NOT_ADMITTED, and registration, internal review
 * (OWNER_INTERNAL, owners roster) and independent review (INDEPENDENT, reviewers roster, never the registrant) are
 * different append-only facts that never substitute for each other. Conversions between units are derived on read, from
 * density/LHV entries that exist, overlap the factor's validity and carry a REVIEWED_OK review; nothing is guessed.
 */
@ApplicationScoped
class FuelCatalog {
    static final String RULE = "CF-FUEL-001";
    static final Set<String> FAMILIES = Set.of("GASOLINE", "ETHANOL", "DIESEL", "BIODIESEL", "NATURAL_GAS", "BIOMETHANE", "HVO", "OTHER");
    static final Set<String> GASES = Set.of("CO2_FOSSIL", "CO2_BIOGENIC", "CO2_UNSPECIFIED", "CH4", "N2O", "CO2E_AGGREGATE");
    static final Set<String> SCOPES = Set.of("TTW", "WTW", "UNSPECIFIED");
    static final Set<String> COMPOSITION_BASES = Set.of("VOLUME_FRACTION", "MASS_FRACTION", "ENERGY_FRACTION");
    static final Set<String> READ_STATES = Set.of("PRIMARY_READ", "SECONDARY");
    static final Set<String> OUTCOMES = Set.of("REVIEWED_OK", "REJECTED", "NEEDS_INFORMATION");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?");
    private static final Pattern SLUG = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");
    private static final Pattern GWP = Pattern.compile("AR[4-9]_[0-9]{2,3}");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern EF_UNIT = Pattern.compile("(t|kg|g)(CO2e|CO2|CH4|N2O)?/(MJ|GJ|TJ|L|USgal|kg|m3)");
    private static final Set<String> DENSITY_UNITS = Set.of("kg/L", "kg/m3");
    private static final Set<String> LHV_UNITS = Set.of("MJ/kg", "MJ/L", "MJ/m3");
    private static final BigDecimal USGAL = new BigDecimal("3.785411784");
    private static final MathContext MC = new MathContext(30);
    private static final String LIMITS = "Catálogo de referência: nada aqui é admitido. A revisão interna do responsável não "
            + "equivale à revisão independente, e a admissão de um fator é um ato normativo separado.";

    record FuelInput(String id, String name, String family, String role, String phase, List<String> pending) {}
    record Fuel(String id, String name, String family, String role, String phase, List<String> pending) {}
    record EntryInput(String kind, String fuelId, String componentId, String compositionBasis, String nominal,
                      Boolean remainder, String parameter, String gas, String emissionScope, String gwpSet,
                      String value, String unit, Integer vintageYear, String ipccReference, String ipccValue,
                      String ipccUnit, String validFrom, String validTo, String validityRule, String evidenceSource,
                      String evidenceLocator, String readState, String evidenceSha256, String retrievedOn) {}
    record ReviewInput(String basis, String outcome, String reason) {}
    record Review(UUID id, String basis, String outcome, String reason, UUID reviewerId, OffsetDateTime decidedAt) {}
    record Qaqc(String state, String relativeDeviation, String note) {}
    record Entry(UUID id, String kind, String fuelId, String componentId, String compositionBasis, String nominal,
                 boolean remainder, String parameter, String gas, String emissionScope, String gwpSet, String value,
                 String unit, Integer vintageYear, String ipccReference, String ipccValue, String ipccUnit,
                 LocalDate validFrom, LocalDate validTo, String validityRule, String evidenceSource,
                 String evidenceLocator, String readState, String evidenceSha256, LocalDate retrievedOn,
                 UUID registeredBy, OffsetDateTime registeredAt, Review internalReview, Review independentReview,
                 Qaqc qaqc, String admission) {}
    record Catalog(boolean canRegister, boolean canReviewInternal, boolean canReviewIndependent, List<Fuel> fuels,
                   List<Entry> entries, String admission, String limits) {}
    record Step(String from, String to, String factor, UUID entryId, String note) {}
    record Requirement(String gas, String state, UUID entryId, String value, String unit, String gwpSet, String review,
                       String methodUnit, String methodValue, String conversionBlocker, String note) {}
    record Resolution(String boundary, String fuelId, LocalDate date, String scope, List<Requirement> requirements,
                      List<String> consistency, String state, List<String> blockers, String admission, String limits) {}
    record Conversion(UUID entryId, String originalValue, String originalUnit, String targetUnit, String value,
                      List<Step> steps, String admission, String limits) {}

    private final PilotDatabase database;
    private final ComplianceStaff staff;
    private final Set<UUID> managers;
    private final Set<UUID> reviewers;
    private final Set<UUID> owners;

    FuelCatalog(PilotDatabase database, ComplianceStaff staff,
            @ConfigProperty(name = "carbonflow.platform.fuel-catalog.managers") Optional<String> managers,
            @ConfigProperty(name = "carbonflow.platform.fuel-catalog.reviewers") Optional<String> reviewers,
            @ConfigProperty(name = "carbonflow.platform.fuel-catalog.owners") Optional<String> owners) {
        this.database = database;
        this.staff = staff;
        this.managers = roster(managers);
        this.reviewers = roster(reviewers);
        this.owners = roster(owners);
    }

    private static Set<UUID> roster(Optional<String> value) {
        try {
            return value.map(text -> Arrays.stream(text.split(",", -1)).map(String::strip)
                    .filter(part -> !part.isEmpty()).map(UUID::fromString).collect(Collectors.toUnmodifiableSet()))
                    .orElse(Set.of());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Cadastro de responsáveis pelo catálogo de combustíveis inválido.");
        }
    }

    private boolean member(UUID id) {
        return managers.contains(id) || reviewers.contains(id) || owners.contains(id);
    }

    Catalog catalog(String token) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!member(actor.id())) throw hidden();
            ComplianceStaff.audit(c, actor.id(), null, "VIEW_FUEL_CATALOG");
            return new Catalog(managers.contains(actor.id()) || owners.contains(actor.id()),
                    owners.contains(actor.id()), reviewers.contains(actor.id()), fuels(c), entries(c, null),
                    "NOT_ADMITTED", LIMITS);
        });
    }

    List<FuelBoundaries.Boundary> boundaries(String token) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!member(actor.id())) throw hidden();
            return FuelBoundaries.PRESETS;
        });
    }

    Fuel registerFuel(String token, FuelInput in) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!managers.contains(actor.id()) && !owners.contains(actor.id())) throw hidden();
            if (in == null || in.id() == null || !SLUG.matcher(in.id()).matches() || in.name() == null
                    || in.name().isBlank() || in.name().strip().length() > 120 || in.family() == null || !FAMILIES.contains(in.family())
                    || in.role() == null || !Set.of("PRODUCT", "BLEND").contains(in.role()) || in.phase() == null || !Set.of("LIQUID", "GAS").contains(in.phase())
                    || (in.pending() != null && (in.pending().size() > 20 || in.pending().stream()
                            .anyMatch(p -> p == null || p.isBlank() || p.length() > 300)))) throw invalid("combustivel");
            if (first(c, "SELECT 1 FROM app_fuel WHERE id=?", r -> 1, in.id()) != null)
                throw new PilotProblem(409, "COMBUSTIVEL_EXISTENTE", "id", RULE, "Já existe um combustível com esse identificador.");
            update(c, "INSERT INTO app_fuel(id,name,family,role,phase,pending,created_by) VALUES (?,?,?,?,?,?,?)",
                    in.id(), in.name().strip(), in.family(), in.role(), in.phase(),
                    c.createArrayOf("text", (in.pending() == null ? List.<String>of() : in.pending()).toArray()), actor.id());
            ComplianceStaff.audit(c, actor.id(), null, "REGISTER_FUEL");
            return fuels(c).stream().filter(f -> f.id().equals(in.id())).findFirst().orElseThrow();
        });
    }

    Entry registerEntry(String token, EntryInput in) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!managers.contains(actor.id()) && !owners.contains(actor.id())) throw hidden();
            if (in == null || in.kind() == null || in.fuelId() == null) throw invalid("entrada");
            String role = first(c, "SELECT role FROM app_fuel WHERE id=? FOR UPDATE", r -> r.getString(1), in.fuelId());
            if (role == null) throw new PilotProblem(404, "COMBUSTIVEL_INEXISTENTE", "fuelId", RULE, "Combustível não encontrado.");
            LocalDate from = date(in.validFrom(), "validFrom"), to = in.validTo() == null ? null : date(in.validTo(), "validTo");
            if (to != null && to.isBefore(from)) throw invalid("validTo");
            if (in.evidenceSource() == null || in.evidenceSource().strip().length() < 3 || in.evidenceSource().strip().length() > 300
                    || in.evidenceLocator() == null || in.evidenceLocator().strip().length() < 3
                    || in.evidenceLocator().strip().length() > 300 || in.readState() == null || !READ_STATES.contains(in.readState())
                    || (in.evidenceSha256() != null && !SHA.matcher(in.evidenceSha256()).matches())
                    || (in.validityRule() != null && (in.validityRule().strip().length() < 3 || in.validityRule().length() > 500)))
                throw invalid("evidencia");
            LocalDate retrieved = in.retrievedOn() == null ? null : date(in.retrievedOn(), "retrievedOn");
            UUID id = UUID.randomUUID();
            if (in.kind().equals("COMPOSITION")) {
                if (!role.equals("BLEND")) throw new PilotProblem(422, "NAO_E_MISTURA", "fuelId", RULE, "Só uma mistura (BLEND) recebe composição.");
                boolean remainder = Boolean.TRUE.equals(in.remainder());
                BigDecimal nominal = remainder ? null : decimal(in.nominal(), "nominal");
                if (in.compositionBasis() == null || !COMPOSITION_BASES.contains(in.compositionBasis()) || in.componentId() == null
                        || in.componentId().equals(in.fuelId()) || (remainder == (nominal != null))
                        || (nominal != null && nominal.compareTo(BigDecimal.ONE) > 0)
                        || first(c, "SELECT 1 FROM app_fuel WHERE id=?", r -> 1, in.componentId()) == null)
                    throw invalid("composicao");
                checkComposition(c, in, from, to, nominal, remainder);
                update(c, """
                        INSERT INTO app_fuel_entry(id,kind,fuel_id,component_id,composition_basis,nominal,remainder,valid_from,
                            valid_to,validity_rule,evidence_source,evidence_locator,read_state,evidence_sha256,retrieved_on,registered_by)
                        VALUES (?,'COMPOSITION',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, id, in.fuelId(), in.componentId(), in.compositionBasis(), nominal, remainder, from, to,
                        blank(in.validityRule()), in.evidenceSource().strip(), in.evidenceLocator().strip(), in.readState(),
                        in.evidenceSha256(), retrieved, actor.id());
            } else if (in.kind().equals("DATUM")) {
                validateDatum(c, in, from, to);
                update(c, """
                        INSERT INTO app_fuel_entry(id,kind,fuel_id,parameter,gas,emission_scope,gwp_set,value_text,unit,vintage_year,
                            ipcc_reference,ipcc_value,ipcc_unit,valid_from,valid_to,validity_rule,evidence_source,evidence_locator,
                            read_state,evidence_sha256,retrieved_on,registered_by)
                        VALUES (?,'DATUM',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, id, in.fuelId(), in.parameter(), in.gas(), in.emissionScope(), in.gwpSet(), in.value(), in.unit(),
                        in.vintageYear(), blank(in.ipccReference()), in.ipccValue(), in.ipccUnit(), from, to,
                        blank(in.validityRule()), in.evidenceSource().strip(), in.evidenceLocator().strip(), in.readState(),
                        in.evidenceSha256(), retrieved, actor.id());
            } else throw invalid("kind");
            ComplianceStaff.audit(c, actor.id(), null, "REGISTER_FUEL_ENTRY");
            return entries(c, id).get(0);
        });
    }

    Entry review(String token, UUID id, ReviewInput in) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!reviewers.contains(actor.id()) && !owners.contains(actor.id())) throw hidden();
            if (in == null || in.basis() == null || !Set.of("INDEPENDENT", "OWNER_INTERNAL").contains(in.basis())
                    || in.outcome() == null || !OUTCOMES.contains(in.outcome()) || in.reason() == null || in.reason().strip().length() < 10
                    || in.reason().strip().length() > 1000) throw invalid("revisao");
            UUID registrant = first(c, "SELECT registered_by FROM app_fuel_entry WHERE id=?", r -> r.getObject(1, UUID.class), id);
            if (registrant == null) throw new PilotProblem(404, "REGISTRO_INEXISTENTE", "id", RULE, "Registro não encontrado.");
            boolean owner = in.basis().equals("OWNER_INTERNAL");
            if (!(owner ? owners : reviewers).contains(actor.id())) throw new PilotProblem(403, "BASE_NAO_AUTORIZADA", "basis", RULE,
                    owner ? "Seu perfil não está na lista de responsáveis do produto." : "Seu perfil não está na lista de revisores independentes.");
            if (!owner && actor.id().equals(registrant)) throw new PilotProblem(403, "DUPLA_CUSTODIA", "reviewer", RULE,
                    "Quem registrou o dado não pode revisá-lo como revisor independente. Use a revisão interna do responsável, se for o seu papel.");
            update(c, "INSERT INTO app_fuel_entry_review(id,entry_id,basis,outcome,reason,reviewer_id) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID(), id, in.basis(), in.outcome(), in.reason().strip(), actor.id());
            ComplianceStaff.audit(c, actor.id(), null, "REVIEW_FUEL_ENTRY");
            return entries(c, id).get(0);
        });
    }

    Conversion convert(String token, UUID id, String targetUnit) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!member(actor.id())) throw hidden();
            var found = entries(c, id);
            if (found.isEmpty()) throw new PilotProblem(404, "REGISTRO_INEXISTENTE", "id", RULE, "Registro não encontrado.");
            ComplianceStaff.audit(c, actor.id(), null, "CONVERT_FUEL_ENTRY");
            return convertEntry(c, found.get(0), targetUnit);
        });
    }

    private Conversion convertEntry(Connection c, Entry entry, String targetUnit) throws SQLException {
        UUID id = entry.id();
        {
            var target = targetUnit == null ? null : EF_UNIT.matcher(targetUnit);
            var source = entry.unit() == null ? null : EF_UNIT.matcher(entry.unit());
            if (!"EMISSION_FACTOR".equals(entry.parameter()) || target == null || !target.matches() || !source.matches())
                throw invalid("targetUnit");
            if (!label(source).equals(label(target))) throw new PilotProblem(422, "GAS_DA_UNIDADE_DIFERENTE", "targetUnit", RULE,
                    "A conversão não troca o gás (por exemplo, CO2 para CO2e) nem o rótulo da unidade.");
            var steps = new ArrayList<Step>();
            BigDecimal value = new BigDecimal(entry.value());
            BigDecimal numeratorFactor = mass(source.group(1)).divide(mass(target.group(1)), MC);
            if (numeratorFactor.compareTo(BigDecimal.ONE) != 0) {
                value = value.multiply(numeratorFactor, MC);
                steps.add(new Step(source.group(1), target.group(1), numeratorFactor.stripTrailingZeros().toPlainString(), null,
                        "massa do numerador (exata)"));
            }
            String from = source.group(3), to = target.group(3);
            if (!from.equals(to)) {
                var edges = edges(c, entry);
                var path = path(edges, from, to);
                if (path == null) throw new PilotProblem(422, "CONVERSAO_SEM_PROPRIEDADE", "targetUnit", RULE,
                        "A conversão de " + from + " para " + to + " exige densidade e/ou PCI deste combustível, já registrados, "
                                + "com vigência que cubra o fator e revisão REVIEWED_OK.");
                BigDecimal ratio = BigDecimal.ONE;
                for (var edge : path) {
                    ratio = ratio.multiply(edge.ratio(), MC);
                    steps.add(new Step(edge.from(), edge.to(), edge.ratio().round(MC).stripTrailingZeros().toPlainString(),
                            edge.entryId(), edge.note()));
                }
                value = value.divide(ratio, MC);
            }
            return new Conversion(id, entry.value(), entry.unit(), targetUnit, value.round(MC).stripTrailingZeros().toPlainString(),
                    List.copyOf(steps), "NOT_ADMITTED", LIMITS);
        }
    }

    /**
     * Resolves which catalog entries would supply EFi,f,y for a candidate boundary, fuel, scope and date. Read-only and
     * non-binding: the result is a candidate with the review state of each entry, never an admitted factor, and the
     * origin of the CO2 is never presumed (CO2_UNSPECIFIED does not satisfy a fossil requirement).
     */
    Resolution resolve(String token, String boundaryId, String fuelId, String date, String scope, String gwpSet) {
        return database.transaction(c -> {
            var actor = staff.require(c, token);
            if (!member(actor.id())) throw hidden();
            var boundary = boundaryId == null ? null : FuelBoundaries.preset(boundaryId);
            LocalDate on = date(date, "date");
            if (boundary == null || fuelId == null || !SCOPES.contains(scope) || (gwpSet != null && !GWP.matcher(gwpSet).matches()))
                throw invalid("boundary");
            if (first(c, "SELECT 1 FROM app_fuel WHERE id=?", r -> 1, fuelId) == null)
                throw new PilotProblem(404, "COMBUSTIVEL_INEXISTENTE", "fuelId", RULE, "Combustível não encontrado.");
            ComplianceStaff.audit(c, actor.id(), null, "VIEW_FUEL_CATALOG");
            var all = entries(c, null).stream().filter(e -> e.kind().equals("DATUM") && e.fuelId().equals(fuelId)
                    && "EMISSION_FACTOR".equals(e.parameter()) && scope.equals(e.emissionScope()) && !e.validFrom().isAfter(on)
                    && (e.validTo() == null || !e.validTo().isBefore(on))).toList();
            var requirements = new ArrayList<Requirement>();
            var blockers = new ArrayList<String>();
            var gases = boundary.baselineGases();
            var direct = new java.util.LinkedHashMap<String, Entry>();
            for (String gas : gases)
                all.stream().filter(e -> e.gas().equals(gas.equals("CO2") ? "CO2_FOSSIL" : gas)).findFirst().ifPresent(e -> direct.put(gas, e));
            var aggregate = gwpSet == null || gases.size() == 1 ? java.util.Optional.<Entry>empty()
                    : all.stream().filter(e -> e.gas().equals("CO2E_AGGREGATE") && gwpSet.equals(e.gwpSet())).findFirst();
            if (direct.size() == gases.size()) {
                for (String gas : gases) requirements.add(requirement(c, gas, "FOUND", direct.get(gas), gas.equals("CO2") ? "tCO2/USgal" : null, ""));
            } else if (direct.isEmpty() && aggregate.isPresent()) {
                requirements.add(requirement(c, "CO2E_AGGREGATE", "FOUND_AGGREGATE", aggregate.get(), "tCO2e/USgal",
                        "CO2e agregado com a base " + gwpSet + " cobre todos os gases da fronteira; confira a consistência com o projeto."));
            } else {
                boolean unspecified = all.stream().anyMatch(e -> e.gas().equals("CO2_UNSPECIFIED"));
                boolean onlyAggregate = gases.size() == 1 && all.stream().anyMatch(e -> e.gas().equals("CO2E_AGGREGATE"));
                for (String gas : gases) {
                    if (direct.containsKey(gas)) {
                        requirements.add(requirement(c, gas, "FOUND", direct.get(gas), gas.equals("CO2") ? "tCO2/USgal" : null, ""));
                        continue;
                    }
                    String note = gas.equals("CO2") && unspecified ? "Há CO2 de origem não especificada: não presumo que seja fóssil."
                            : onlyAggregate ? "Só há CO2e agregado, que inclui CH4 e N2O e destoa de uma fronteira só de CO2."
                            : !direct.isEmpty() && gwpSet != null ? "Fatores separados e CO2e agregado não se misturam (risco de dupla contagem); registre o gás que falta."
                            : "Nenhum fator registrado para este gás, escopo e data.";
                    requirements.add(new Requirement(gas, "MISSING", null, null, null, null, null, null, null, null, note));
                    blockers.add(gas + ": " + note);
                }
            }
            for (var req : requirements) if (req.conversionBlocker() != null) blockers.add(req.gas() + ": " + req.conversionBlocker());
            var consistency = FuelBoundaries.inconsistencies(boundary.baselineGases(), boundary.projectGases());
            blockers.addAll(consistency);
            return new Resolution(boundary.id(), fuelId, on, scope, List.copyOf(requirements), List.copyOf(consistency),
                    blockers.isEmpty() ? "RESOLVED_CANDIDATE" : "UNRESOLVED", List.copyOf(blockers), "NOT_ADMITTED", LIMITS);
        });
    }

    private Requirement requirement(Connection c, String gas, String state, Entry e, String methodUnit, String note) throws SQLException {
        String review = e.independentReview() != null && e.independentReview().outcome().equals("REVIEWED_OK") ? "INDEPENDENT_OK"
                : e.internalReview() != null && e.internalReview().outcome().equals("REVIEWED_OK") ? "INTERNAL_OK" : "NOT_REVIEWED_OK";
        String converted = null, blocker = null;
        if (methodUnit != null) {
            try {
                converted = convertEntry(c, e, methodUnit).value();
            } catch (PilotProblem problem) {
                blocker = "conversão para " + methodUnit + " não resolvida (" + problem.code + ")";
            }
        }
        return new Requirement(gas, state, e.id(), e.value(), e.unit(), e.gwpSet(), review, methodUnit, converted, blocker, note);
    }

    private record Edge(String from, String to, BigDecimal ratio, UUID entryId, String note) {}

    private List<Edge> edges(Connection c, Entry factor) throws SQLException {
        var list = new ArrayList<Edge>();
        add(list, "GJ", "MJ", new BigDecimal(1000), null, "exato");
        add(list, "TJ", "MJ", new BigDecimal(1000000), null, "exato");
        add(list, "USgal", "L", USGAL, null, "galão US exato (3,785411784 L)");
        for (var e : entries(c, null)) {
            if (!e.fuelId().equals(factor.fuelId()) || !"DATUM".equals(e.kind()) || e.parameter().equals("EMISSION_FACTOR")
                    || e.internalReview() == null && e.independentReview() == null) continue;
            boolean ok = (e.internalReview() != null && e.internalReview().outcome().equals("REVIEWED_OK"))
                    || (e.independentReview() != null && e.independentReview().outcome().equals("REVIEWED_OK"));
            boolean covers = !e.validFrom().isAfter(factor.validFrom()) && (e.validTo() == null || !e.validTo().isBefore(factor.validFrom()));
            if (!ok || !covers) continue;
            BigDecimal v = new BigDecimal(e.value());
            if (v.signum() == 0) continue;
            switch (e.unit()) {
                case "kg/L" -> add(list, "L", "kg", v, e.id(), "densidade");
                case "kg/m3" -> add(list, "m3", "kg", v, e.id(), "densidade");
                case "MJ/kg" -> add(list, "kg", "MJ", v, e.id(), "PCI");
                case "MJ/L" -> add(list, "L", "MJ", v, e.id(), "PCI");
                case "MJ/m3" -> add(list, "m3", "MJ", v, e.id(), "PCI");
                default -> { }
            }
        }
        return list;
    }

    private static void add(List<Edge> list, String from, String to, BigDecimal ratio, UUID id, String note) {
        list.add(new Edge(from, to, ratio, id, note));
        list.add(new Edge(to, from, BigDecimal.ONE.divide(ratio, MC), id, note + " (inverso)"));
    }

    private static List<Edge> path(List<Edge> edges, String from, String to) {
        var previous = new HashMap<String, Edge>();
        var queue = new ArrayDeque<String>(List.of(from));
        previous.put(from, null);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (node.equals(to)) break;
            for (var edge : edges) {
                if (edge.from().equals(node) && !previous.containsKey(edge.to())) {
                    previous.put(edge.to(), edge);
                    queue.add(edge.to());
                }
            }
        }
        if (!previous.containsKey(to)) return null;
        var result = new ArrayList<Edge>();
        for (String node = to; previous.get(node) != null; node = previous.get(node).from()) result.add(0, previous.get(node));
        return result;
    }

    private static String label(java.util.regex.Matcher m) {
        return m.group(2) == null ? "" : m.group(2);
    }

    private static BigDecimal mass(String unit) {
        return switch (unit) { case "t" -> new BigDecimal(1000); case "g" -> new BigDecimal("0.001"); default -> BigDecimal.ONE; };
    }

    private void checkComposition(Connection c, EntryInput in, LocalDate from, LocalDate to, BigDecimal nominal,
            boolean remainder) throws SQLException {
        var siblings = entries(c, null).stream().filter(e -> e.kind().equals("COMPOSITION") && e.fuelId().equals(in.fuelId())
                && e.compositionBasis().equals(in.compositionBasis())).toList();
        BigDecimal fixed = nominal == null ? BigDecimal.ZERO : nominal;
        for (var e : siblings) {
            boolean same = e.validFrom().equals(from) && java.util.Objects.equals(e.validTo(), to);
            if (!same && overlaps(e.validFrom(), e.validTo(), from, to))
                throw new PilotProblem(409, "JANELA_SOBREPOSTA", "validFrom", RULE,
                        "A composição já tem outra janela que se sobrepõe a esta; use a mesma janela ou uma disjunta.");
            if (!same) continue;
            if (e.componentId().equals(in.componentId()))
                throw new PilotProblem(409, "COMPONENTE_REPETIDO", "componentId", RULE, "O componente já consta nesta janela.");
            if (remainder && e.remainder()) throw new PilotProblem(409, "COMPLEMENTO_REPETIDO", "remainder", RULE,
                    "A janela já tem um componente de complemento.");
            if (e.nominal() != null) fixed = fixed.add(new BigDecimal(e.nominal()));
        }
        if (fixed.compareTo(BigDecimal.ONE) > 0)
            throw new PilotProblem(422, "SOMA_ACIMA_DE_1", "nominal", RULE, "Os componentes da janela somariam mais que 1.");
    }

    private void validateDatum(Connection c, EntryInput in, LocalDate from, LocalDate to) throws SQLException {
        String parameter = in.parameter();
        if (parameter == null || !Set.of("EMISSION_FACTOR", "DENSITY", "LHV").contains(parameter)) throw invalid("parameter");
        decimal(in.value(), "value");
        if (in.vintageYear() == null || in.vintageYear() < 1990 || in.vintageYear() > 2100) throw invalid("vintageYear");
        if (parameter.equals("DENSITY") ? !DENSITY_UNITS.contains(in.unit()) : parameter.equals("LHV") ? !LHV_UNITS.contains(in.unit())
                : in.unit() == null || !EF_UNIT.matcher(in.unit()).matches())
            throw new PilotProblem(422, "UNIDADE_FORA_DA_DIMENSAO", "unit", RULE, "A unidade não pertence à dimensão do parâmetro.");
        if (parameter.equals("EMISSION_FACTOR")) {
            if (in.gas() == null || !GASES.contains(in.gas()) || in.emissionScope() == null || !SCOPES.contains(in.emissionScope())) throw invalid("gas");
            if ((in.gas().equals("CO2E_AGGREGATE")) != (in.gwpSet() != null)
                    || (in.gwpSet() != null && !GWP.matcher(in.gwpSet()).matches())) throw new PilotProblem(422, "GWP_OBRIGATORIO_PARA_CO2E", "gwpSet", RULE,
                            "CO2e agregado exige a base de GWP explícita (por exemplo, AR5_100) e só ele a aceita.");
            var unitMatcher = EF_UNIT.matcher(in.unit());
            unitMatcher.matches();
            String label = label(unitMatcher);
            boolean consistent = label.isEmpty() || (label.equals("CO2e") ? in.gas().equals("CO2E_AGGREGATE")
                    : label.equals("CO2") ? in.gas().startsWith("CO2_") && !in.gas().equals("CO2E_AGGREGATE") : label.equals(in.gas()));
            if (!consistent) throw new PilotProblem(422, "GAS_DIVERGE_DA_UNIDADE", "unit", RULE,
                    "O rótulo do gás na unidade não confere com o gás informado; nada é convertido entre gases implicitamente.");
            if ((in.ipccValue() == null) != (in.ipccUnit() == null) || (in.ipccValue() != null
                    && (in.ipccReference() == null || in.ipccReference().strip().length() < 3
                            || !DECIMAL.matcher(in.ipccValue()).matches() || !EF_UNIT.matcher(in.ipccUnit()).matches())))
                throw invalid("ipcc");
        } else if (in.gas() != null || in.emissionScope() != null || in.gwpSet() != null || in.ipccValue() != null)
            throw invalid("gas");
        for (var e : entries(c, null)) {
            if (e.kind().equals("DATUM") && e.fuelId().equals(in.fuelId()) && e.parameter().equals(parameter)
                    && java.util.Objects.equals(e.gas(), in.gas()) && java.util.Objects.equals(e.emissionScope(), in.emissionScope())
                    && java.util.Objects.equals(e.gwpSet(), in.gwpSet()) && overlaps(e.validFrom(), e.validTo(), from, to))
                throw new PilotProblem(409, "JANELA_SOBREPOSTA", "validFrom", RULE,
                        "Já existe valor desta série (combustível, parâmetro, gás, escopo e GWP) cuja vigência se sobrepõe.");
        }
    }

    private static boolean overlaps(LocalDate s1, LocalDate e1, LocalDate s2, LocalDate e2) {
        return (e1 == null || !s2.isAfter(e1)) && (e2 == null || !s1.isAfter(e2));
    }

    private static Qaqc qaqc(String value, String unit, String ipccValue, String ipccUnit) {
        if (ipccValue == null) return new Qaqc("NOT_COMPARED", null, "Sem valor de referência IPCC 2019 informado.");
        if (!java.util.Objects.equals(unit, ipccUnit)) return new Qaqc("COMPARED_UNIT_MISMATCH", null,
                "Unidades diferentes: nenhuma conversão foi aplicada ao confronto.");
        BigDecimal reference = new BigDecimal(ipccValue);
        if (reference.signum() == 0) return new Qaqc("COMPARED_REFERENCE_ZERO", null, "Referência zero: desvio relativo indefinido.");
        return new Qaqc("COMPARED_SAME_UNIT", new BigDecimal(value).subtract(reference).divide(reference, MC)
                .setScale(6, java.math.RoundingMode.HALF_EVEN).toPlainString(),
                "Desvio relativo (valor − referência) / referência; não é critério de aceitação.");
    }

    private List<Fuel> fuels(Connection c) throws SQLException {
        return query(c, "SELECT id,name,family,role,phase,pending FROM app_fuel ORDER BY id",
                r -> new Fuel(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        Arrays.asList((String[]) r.getArray(6).getArray())));
    }

    private List<Entry> entries(Connection c, UUID only) throws SQLException {
        var reviews = new HashMap<UUID, Map<String, Review>>();
        for (var row : query(c, """
                SELECT DISTINCT ON (entry_id, basis) entry_id,id,basis,outcome,reason,reviewer_id,decided_at
                FROM app_fuel_entry_review ORDER BY entry_id, basis, decided_at DESC, id
                """, r -> Map.entry(r.getObject(1, UUID.class), new Review(r.getObject(2, UUID.class), r.getString(3),
                r.getString(4), r.getString(5), r.getObject(6, UUID.class), r.getObject(7, OffsetDateTime.class)))))
            reviews.computeIfAbsent(row.getKey(), k -> new HashMap<>()).put(row.getValue().basis(), row.getValue());
        return query(c, """
                SELECT id,kind,fuel_id,component_id,composition_basis,nominal::text,remainder,parameter,gas,emission_scope,gwp_set,
                       value_text,unit,vintage_year,ipcc_reference,ipcc_value,ipcc_unit,valid_from,valid_to,validity_rule,
                       evidence_source,evidence_locator,read_state,evidence_sha256,retrieved_on,registered_by,registered_at
                FROM app_fuel_entry WHERE (?::uuid IS NULL OR id=?::uuid) ORDER BY fuel_id, kind, registered_at, id LIMIT 2000
                """, r -> {
                    UUID id = r.getObject(1, UUID.class);
                    var rv = reviews.getOrDefault(id, Map.of());
                    String nominal = r.getString(6);
                    return new Entry(id, r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                            nominal == null ? null : new BigDecimal(nominal).stripTrailingZeros().toPlainString(),
                            r.getBoolean(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12),
                            r.getString(13), (Integer) r.getObject(14), r.getString(15), r.getString(16), r.getString(17),
                            r.getObject(18, LocalDate.class), r.getObject(19, LocalDate.class), r.getString(20), r.getString(21),
                            r.getString(22), r.getString(23), r.getString(24), r.getObject(25, LocalDate.class),
                            r.getObject(26, UUID.class), r.getObject(27, OffsetDateTime.class), rv.get("OWNER_INTERNAL"),
                            rv.get("INDEPENDENT"), r.getString(8) == null || !r.getString(8).equals("EMISSION_FACTOR") ? null
                                    : qaqc(r.getString(12), r.getString(13), r.getString(16), r.getString(17)), "NOT_ADMITTED");
                }, only, only);
    }

    private static BigDecimal decimal(String text, String field) {
        if (text == null || !DECIMAL.matcher(text).matches()) throw invalid(field);
        return new BigDecimal(text);
    }

    private static LocalDate date(String text, String field) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException ex) {
            throw invalid(field);
        }
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static PilotProblem hidden() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "fuel-catalog", RULE, "Catálogo de combustíveis não disponível para este perfil.");
    }

    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("CATALOGO_COMBUSTIVEL_INVALIDO", field, RULE,
                "Confira os campos do registro: identificadores, vocabulários, decimais em texto, datas AAAA-MM-DD, evidência (fonte, localizador e estado de leitura) e motivo (mín. 10 caracteres).");
    }
}
