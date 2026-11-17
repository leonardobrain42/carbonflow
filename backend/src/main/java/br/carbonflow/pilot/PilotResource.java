package br.carbonflow.pilot;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ExceptionMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Path("/v1/projects/{project}/runs")
@Produces(MediaType.APPLICATION_JSON)
public class PilotResource {
    static final String PROJECT = "PIL-SYN-BR-001";
    static final String ENVIRONMENT = "SIMULACAO";
    static final String PATH = "/v1/projects/" + PROJECT + "/runs";

    public record Command(Fixture.Reference fixture, SelectedCalculation.Proposal proposal) {}

    record Run(UUID id, int version, UUID eventId, String environment, String projectId, String actor,
               OffsetDateTime createdAt, String snapshotSha256, FixtureCatalog.Provenance provenance,
               String engineVersion, String zone, Fixture.Method method, Fixture.Factor factor,
               CarbonCalculator.Results results, List<PilotScenario.Checkpoint> checkpoints) {}

    private final PilotAccess access;
    private final FixtureCatalog catalog;
    private final RunStore store;
    private final SelectedRuns selected;
    private final ObjectMapper mapper;

    PilotResource(PilotAccess access, FixtureCatalog catalog, RunStore store, SelectedRuns selected, ObjectMapper mapper) {
        this.access = access;
        this.catalog = catalog;
        this.store = store;
        this.selected = selected;
        this.mapper = mapper;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String key, Command command) {
        var scope = access.require(project, "CALCULAR", null, PilotAccess.PREP);
        if (command != null && command.proposal() != null) {
            if (command.fixture() != null) throw SelectedParameters.invalid("ENTRADA_AMBIGUA", "command", "Escolha uma origem de cálculo.");
            return Response.status(201).entity(selected.propose(scope, access.actor().id(), PilotCommands.key(key),
                    command.proposal())).build();
        }
        if (!PROJECT.equals(scope.project())) {
            throw access.denied(project, "CALCULAR", null, PilotAccess.invisible());
        }
        String actor = access.actor().id();
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new PilotProblem(400, "PEDIDO_INVALIDO", "Idempotency-Key", "OP-E6-07",
                    "Informe uma chave de idempotência válida.");
        }
        if (command == null || command.fixture() == null || command.fixture().id() == null || command.fixture().version() < 1) {
            throw new PilotProblem(400, "PEDIDO_INVALIDO", "fixture", "OP-E6-07",
                    "Informe a identidade e a versão da massa.");
        }
        var reference = command.fixture();
        var current = store.byKey(scope, key);
        if (current.isPresent()) {
            return replay(current.get(), reference, actor);
        }

        var snapshot = catalog.load(reference);
        var fixture = snapshot.fixture();
        var results = new CarbonCalculator().calculate(fixture);
        var checkpoints = new PilotScenario().demonstrate(results, fixture.issuanceStatus());
        var snapshotBytes = catalog.snapshotBytes(snapshot);

        var id = UUID.randomUUID();
        var at = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var report = new Run(id, 1, id, ENVIRONMENT, PROJECT, actor, at,
                FixtureCatalog.sha256(snapshotBytes), snapshot.provenance(), snapshot.engineVersion(),
                fixture.calculation().zone(), fixture.method(), fixture.calculation().factor(), results, checkpoints);
        if (!store.insert(scope, actor, id, key, reference, at, snapshotBytes, encode(report))) {
            return replay(store.byKey(scope, key).orElseThrow(), reference, actor);
        }
        return Response.created(URI.create(PATH + "/" + id)).entity(report).build();
    }

    @GET
    @Path("/latest")
    public Response latest(@PathParam("project") String project) {
        var scope = access.require(project, "CONSULTAR_EXECUCOES", null, PilotAccess.PREP, PilotAccess.REV, PilotAccess.PP);
        var entry = store.latest(scope);
        access.allowed(scope, "CONSULTAR_EXECUCOES", null);
        return entry.map(value -> Response.ok(decode(value)).build())
                .orElseGet(() -> Response.noContent().build());
    }

    @GET
    @Path("/{id}")
    public JsonNode get(@PathParam("project") String project, @PathParam("id") String id) {
        var scope = access.require(project, "CONSULTAR_EXECUCAO", id, PilotAccess.PREP, PilotAccess.REV, PilotAccess.PP);
        UUID runId = runId(id);
        var entry = store.byId(scope, runId).map(this::decode).or(() ->
                selected.find(project, runId).map(value -> (JsonNode) mapper.valueToTree(value))).orElseThrow(
                () -> access.denied(project, "CONSULTAR_EXECUCAO", id, PilotAccess.invisible()));
        access.allowed(scope, "CONSULTAR_EXECUCAO", id);
        return entry;
    }

    @GET
    @Path("/{id}/snapshot")
    public byte[] snapshot(@PathParam("project") String project, @PathParam("id") String id) {
        var scope = access.require(project, "BAIXAR_SNAPSHOT", id, PilotAccess.PREP, PilotAccess.REV, PilotAccess.PP);
        UUID runId = runId(id);
        var bytes = store.snapshot(scope, runId).or(() -> selected.snapshot(project, runId)).orElseThrow(
                () -> access.denied(project, "BAIXAR_SNAPSHOT", id, PilotAccess.invisible()));
        access.allowed(scope, "BAIXAR_SNAPSHOT", id);
        return bytes;
    }

    private UUID runId(String id) {
        try {
            var uuid = UUID.fromString(id);
            if (!uuid.toString().equalsIgnoreCase(id)) {
                throw PilotAccess.invisible();
            }
            return uuid;
        } catch (IllegalArgumentException e) {
            throw PilotAccess.invisible();
        }
    }

    private Response replay(RunStore.Entry entry, Fixture.Reference reference, String actor) {
        if (!entry.fixture().equals(reference) || !entry.actor().equals(actor)) {
            throw new PilotProblem(409, "CHAVE_REUSADA", "Idempotency-Key", "OP-E6-07",
                    "A chave já identifica um comando com outra massa.");
        }
        var report = decode(entry);
        return Response.created(URI.create(PATH + "/" + report.path("id").asText())).entity(report).build();
    }

    private String encode(Run run) {
        try {
            return mapper.writeValueAsString(run);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar a memória", e);
        }
    }

    private JsonNode decode(RunStore.Entry entry) {
        try {
            return mapper.readTree(entry.reportJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao ler a memória persistida", e);
        }
    }
}

@Provider
class PilotProblemMapper implements ExceptionMapper<PilotProblem> {
    record Issue(String path, String ruleId, String reason) {}
    record ErrorBody(int status, String code, UUID requestId, String message, List<Issue> issues) {}

    @Override
    public Response toResponse(PilotProblem problem) {
        var body = new ErrorBody(problem.status, problem.code, UUID.randomUUID(), problem.getMessage(),
                List.of(new Issue(problem.field, problem.rule, problem.getMessage())));
        return Response.status(problem.status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }
}
