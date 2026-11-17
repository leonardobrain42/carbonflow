package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.DocumentRules.*;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class DossierPackages {
    record Action(String transitionId, Ref dossier, String reason, String item, String responsible, String expectedCorrection) {}
    record Review(String actor, OffsetDateTime at, String reason, String item, String responsible,
                  String expectedCorrection, UUID event) {}
    record Source(DossierText.Terms terms, List<DossierText.Section> publicSections, List<DossierText.Section> reservedSections,
                  List<Finding> findings, Map<IntakeModel.RecordType, IntakeModel.CaseEntry> intakeRecords,
                  int configuration, UUID configurationEvent, FixtureCatalog.Provenance forecast, String closureSha256,
                  byte[] closureBytes, byte[] calculationSnapshot, String validatedPdSha256, LocalDate retentionFloor) {}
    record View(UUID id, int revision, int version, String state, String preparedBy, OffsetDateTime preparedAt, Input command,
                List<Finding> findings, Review review, List<Variant> variants, UUID eventId) {}
    record Variant(UUID artifactId, String exposure, String edition, String sha256, String manifestSha256) {}
    record File(String filename, String mediaType, String language, String exposure, long bytes, String sha256) {}
    record Bytes(byte[] content, String sha256) {}
    record ExportInput(int revision, String exposure, String edition, String destination, String channel) {}
    record Export(UUID id, Ref dossier, String exposure, String edition, String destination, String sha256, UUID eventId,
                  String capturedBy) {}
    record ReceiptInput(UUID exportId, Ref dossier, String sha256, String destination, String code,
                        String proof, OffsetDateTime receivedAt) {}
    record ListingPackage(Ref reference, Act purpose, LocalDate actDate, String sha256, String reservedSha256) {}
    record AuditPackage(ListingPackage pack, ClosureRef closure, UUID reservedArtifact, boolean compared) {}
    record ListingDelivery(ListingPackage pack, Export export, Receipt receipt) {}
    record Receipt(ReceiptInput input, String issuer, String capturedBy, UUID eventId) {}
    private record Row(UUID id, int revision, int version, String state, String author, OffsetDateTime time,
                       Input input, Source source, Review review, UUID event) {}
    private record CurrentClosure(byte[] bytes, String sha, boolean eligible) {}
    private record Generated(String exposure, String edition, byte[] manifest, byte[] zip) {}
    private record PieceProof(Piece type, IntakeModel.Ref reference, String attributedIssuer, String status, String language,
                              LocalDate referenceDate, String period, String closureSha256, Ref predecessor) {}

    private static final List<String> EXPOSURES = List.of("PUBLICA", "RESERVADA");
    private static final List<String> EDITIONS = List.of("LIMPA", "COMPARADA");
    private static final String MANUAL_CHANNEL = "ARQUIVO_SIMULADO";
    private static final List<String> DESTINATIONS = List.of("SIM-REGISTRY", "SIM-VVB", "SIM-HUB");
    private static final String ROW_SELECT = "SELECT * FROM pilot_document_dossier WHERE project_id=? AND id=?";
    private static final String VARIANTS = """
            SELECT v.artifact_id,v.exposure,v.edition,v.manifest,a.sha256,a.content
            FROM pilot_document_variant v JOIN pilot_artifact a
              ON (a.project_id,a.id,a.version)=(v.project_id,v.artifact_id,v.revision)
            WHERE v.project_id=? AND v.dossier_id=? AND v.revision=?
            """;

    private final PilotDatabase database;
    private final PilotCommands commands;
    private final PilotAudit audit;
    private final ProjectIntake intake;
    private final FixtureCatalog fixtures;
    private final ObjectMapper mapper;
    private final RetentionPolicy retention;
    private final EvidenceQuota quota;

    DossierPackages(PilotDatabase database, PilotCommands commands, PilotAudit audit, ProjectIntake intake,
                    FixtureCatalog fixtures, ObjectMapper mapper, RetentionPolicy retention, EvidenceQuota quota) {
        this.database = database;
        this.commands = commands;
        this.audit = audit;
        this.intake = intake;
        this.fixtures = fixtures;
        this.mapper = mapper;
        this.retention = retention;
        this.quota = quota;
    }

    JsonNode prepare(PilotAccess.Scope scope, String actor, String key, int expected, Input input) {
        validate(input);
        return commands.execute(scope, actor, "TX-E3-05", key, audit.encode(List.of(expected, input)), null, connection -> {
            String project = scope.project();
            var config = intake.lockAndLoad(connection, project);
            require(config.intake() != null && "ENQUADRADO".equals(config.intake().state())
                    && config.intake().selection() != null, "FICHA_PENDENTE", "Conclua a revisão do enquadramento para preparar um dossiê.");
            var previous = input.predecessor() == null ? null : row(connection, project, input.predecessor());
            if (previous != null) {
                currentRevision(connection, project, previous);
                require(List.of("DEVOLVIDO", "CONGELADO").contains(previous.state())
                        || "RASCUNHO".equals(previous.state()) && actor.equals(previous.author()), "ANTERIOR_INDISPONIVEL",
                        "Use um rascunho de sua autoria ou uma versão devolvida ou congelada.");
                require(previous.input().act() == input.act() && samePeriod(previous.input().closure(), input.closure())
                        && !input.actDate().isBefore(previous.input().actDate()), "ANTECESSOR_DIVERGENTE",
                        "Mantenha o ato, o período e a ordem da rodada.");
            }
            PilotCommands.requireVersion(expected, previous == null ? 0 : previous.version());
            var source = project(connection, project, config, input);
            UUID id = previous == null ? UUID.randomUUID() : previous.id();
            int revision = previous == null ? 1 : previous.revision() + 1;
            UUID event = audit.record(connection, project, actor, "TX-E3-05", "PERMITIDO", id.toString(),
                    Map.of("revision", revision, "act", input.act(), "configuration", config.version()));
            update(connection, """
                    INSERT INTO pilot_document_dossier (project_id,id,revision,row_version,state,prepared_by,intake_version,event_id,command,source)
                    VALUES (?,?,?,1,'RASCUNHO',?,?,?,?::jsonb,?::jsonb)
                    """, project, id, revision, actor, config.version(), event, audit.encode(input), audit.encode(source));
            return view(connection, project, row(connection, project, new Ref(id, revision)));
        });
    }

    List<View> list(String project) {
        return database.transaction(connection -> {
            var records = query(connection, """
                    SELECT * FROM pilot_document_dossier WHERE project_id=? ORDER BY prepared_at DESC,id LIMIT 100
                    """, this::readRow, project);
            var views = new ArrayList<View>();
            for (var item : records) views.add(view(connection, project, item));
            return views;
        });
    }

    List<DossierText.Section> preview(String project, Ref ref, String exposure) {
        if (exposure == null || !EXPOSURES.contains(exposure)) throw invalid("VARIANTE_INVALIDA", "Escolha a exposição do documento.");
        return database.read(connection -> sections(row(connection, project, ref), exposure));
    }

    JsonNode action(PilotAccess.Scope scope, String actor, String key, UUID id, int expected, Action command) {
        if (command.dossier() == null || !id.equals(command.dossier().id()) || command.dossier().revision() < 1
                || !IntakeRules.text(command.reason())) throw invalid("REVISAO_INCOMPLETA", "Indique versão e justificativa.");
        return commands.execute(scope, actor, command.transitionId(), key, audit.encode(List.of(expected, command)), id.toString(),
                connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            var item = row(connection, project, command.dossier());
            currentRevision(connection, project, item);
            PilotCommands.requireVersion(expected, item.version());
            boolean submission = "TX-E3-06".equals(command.transitionId());
            if (submission) {
                require("RASCUNHO".equals(item.state()) && actor.equals(item.author()), "AUTORIA_DIVERGENTE",
                        "O preparador da versão em rascunho deve encaminhá-la para revisão.");
            } else {
                require("EM_REVISAO".equals(item.state()), "TRANSICAO_INDISPONIVEL", "A versão precisa estar em revisão.");
                if (actor.equals(item.author())) throw new PilotProblem(403, "AUTOAPROVACAO_NEGADA", "actor", "TX-E3-07/08",
                        "Outra identidade deve revisar a versão.");
            }
            boolean returned = "TX-E3-07".equals(command.transitionId());
            if (returned) {
                if (!IntakeRules.text(command.item()) || !IntakeRules.text(command.expectedCorrection())
                        || command.responsible() == null || !List.of("PREP", "PP", "OP", "VVB").contains(command.responsible())) {
                    throw invalid("DEVOLUCAO_INCOMPLETA", "Informe item, responsável e correção esperada.");
                }
            } else {
                require(item.source().findings().isEmpty(), "CHECKLIST_INCOMPLETO", "Resolva as pendências do checklist da versão.");
                eligibleClosure(connection, project, item);
            }
            String next = submission ? "EM_REVISAO" : returned ? "DEVOLVIDO" : "CONGELADO";
            UUID event = audit.record(connection, project, actor, command.transitionId(), "PERMITIDO", id.toString(),
                    Map.of("revision", item.revision(), "before", item.version(), "after", item.version() + 1,
                            "from", item.state(), "to", next, "reason", command.reason()));
            var review = submission ? null : new Review(actor, OffsetDateTime.now(ZoneOffset.UTC), command.reason(),
                    command.item(), command.responsible(), command.expectedCorrection(), event);
            if ("CONGELADO".equals(next)) freeze(connection, project, item, review);
            update(connection, """
                    UPDATE pilot_document_dossier SET state=?,row_version=row_version+1,review=?::jsonb,event_id=?
                    WHERE project_id=? AND id=? AND revision=?
                    """, next, review == null ? null : audit.encode(review), event, project, id, item.revision());
            return view(connection, project, row(connection, project, command.dossier()));
        });
    }

    private Source project(Connection connection, String project, IntakeModel.Snapshot config, Input input) throws SQLException {
        var caseRecords = new EnumMap<IntakeModel.RecordType, IntakeModel.CaseEntry>(IntakeModel.RecordType.class);
        config.records().forEach(item -> caseRecords.put(item.input().type(), item));
        var selection = config.intake().selection();
        var inventory = config.intake().candidate().inventory();
        var op = config.operation();
        var commitment = config.commitment();
        var terms = new DossierText.Terms(selection.methodology(), selection.program(), selection.additionality(), inventory.stations().size(),
                inventory.ecCircuits().size(), inventory.vehicles().size(), inventory.models().size(),
                commitment == null ? null : commitment.date(), op == null ? null : op.creditStart(), op == null ? null : op.creditEndInclusive());
        var fixture = fixtures.load(FixtureCatalog.DEFAULT);
        var forecast = new CarbonCalculator().calculate(fixture.fixture());
        var closure = input.closure() == null ? null : findClosure(connection, project, input.closure());
        var closed = closure == null ? null : audit.decode(new String(closure.bytes(), java.nio.charset.StandardCharsets.UTF_8));
        var pd = input.validatedPd() == null ? null : validatedPd(connection, project, input.validatedPd(), input.actDate());
        boolean closureEligible = closure != null && closure.eligible()
                && !input.actDate().isBefore(OffsetDateTime.parse(closed.path("effectiveAt").asText())
                .atZoneSameInstant(VintageWindows.ZONE).toLocalDate());
        var findings = new ArrayList<>(check(input, caseRecords,
                commitment != null && !commitment.date().isAfter(input.actDate()), op != null && !op.start().isAfter(input.actDate()),
                closureEligible, pd != null));
        UUID configEvent = first(connection, "SELECT event_id FROM pilot_intake_revision WHERE project_id=? AND version=?",
                row -> row.getObject(1, UUID.class), project, config.version());
        byte[] calculationSnapshot = closure == null ? null : first(connection, """
                SELECT r.snapshot FROM pilot_period_closure c JOIN pilot_selected_run r
                ON (r.project_id,r.id)=(c.project_id,c.run_id) WHERE c.project_id=? AND c.period=? AND c.revision=?
                """, row -> row.getBytes(1), project, input.closure().period(), input.closure().revision());
        if (closure != null) {
            require(calculationSnapshot != null && FixtureCatalog.sha256(calculationSnapshot).equals(closed.path("snapshotSha256").asText()),
                    "HASH_DIVERGENTE", "O snapshot de cálculo não confere com o hash do fechamento.");
        }
        boolean renderable = input.act() != Act.VERIFICACAO || closed != null;
        LocalDate retentionFloor = RetentionPolicy.floorInclusive(retention.creditEnd(connection, project, op).date());
        return new Source(terms, renderable ? DossierText.sections(input, terms, closed, forecast, false) : List.of(),
                renderable ? DossierText.sections(input, terms, closed, forecast, true) : List.of(), List.copyOf(findings),
                Map.copyOf(caseRecords), config.version(), configEvent, fixture.provenance(), closure == null ? null : closure.sha(),
                closure == null ? null : closure.bytes(), calculationSnapshot, pd, retentionFloor);
    }

    private String validatedPd(Connection connection, String project, Ref ref, LocalDate date) throws SQLException {
        var pd = row(connection, project, ref);
        if (!"CONGELADO".equals(pd.state()) || pd.input().act() != Act.REGISTRO || pd.input().actDate().isAfter(date)) return null;
        return first(connection, VARIANTS + " AND v.exposure='RESERVADA' AND v.edition='LIMPA'", row -> row.getString("sha256"),
                project, ref.id(), ref.revision());
    }

    private void freeze(Connection connection, String project, Row item, Review review) throws SQLException {
        var predecessor = item.input().predecessor() == null ? null : row(connection, project, item.input().predecessor());
        var generated = new ArrayList<Generated>();
        var cutoff = retention.creditEnd(connection, project, intake.latest(connection, project).operation());
        var floor = RetentionPolicy.floorInclusive(cutoff.date());
        for (String exposure : EXPOSURES) {
            generated.add(bundle(project, item, review, predecessor, exposure, "LIMPA", floor));
            if (predecessor != null) generated.add(bundle(project, item, review, predecessor, exposure, "COMPARADA", floor));
        }
        long incoming = generated.stream().mapToLong(g -> g.zip().length + g.manifest().length).sum();
        quota.requireSpace(connection, project, incoming);
        for (var artifact : generated) {
            UUID id = UUID.randomUUID();
            String sha = FixtureCatalog.sha256(artifact.zip());
            update(connection, """
                    INSERT INTO pilot_artifact (project_id,id,version,dossier_id,variant,edition,purpose,title,author_id,
                                                content,sha256,approved_by,approved_at,media_type)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'application/zip')
                    """, project, id, item.revision(), item.id(), artifact.exposure(), artifact.edition(), purpose(item.input()),
                    title(item) + " · " + artifact.edition(), item.author(), artifact.zip(), sha, review.actor(), review.at());
            update(connection, "INSERT INTO pilot_document_variant VALUES (?,?,?,?,?,?,?)", project, item.id(), item.revision(),
                    artifact.exposure(), artifact.edition(), id, artifact.manifest());
            if (item.input().closure() != null) {
                var closure = item.input().closure();
                var input = new MonitoringPeriods.DependentInput("PACOTE", id.toString(), item.revision(), "VINCULADO", null);
                var dependent = new MonitoringPeriods.Dependent(input, review.actor(), review.event(), sha);
                update(connection, "INSERT INTO pilot_period_dependent VALUES (?,?,?,?,?,?,?::jsonb)", project, closure.period(),
                        closure.revision(), "PACOTE", id.toString(), item.revision(), audit.encode(dependent));
            }
        }
    }

    private Generated bundle(String project, Row item, Review review, Row predecessor, String exposure, String edition, LocalDate floor) {
        boolean reserved = "RESERVADA".equals(exposure);
        var files = new LinkedHashMap<String, byte[]>();
        add(files, "document.docx", WordDocument.render(item.input().act().title(),
                "Dossier " + item.id().toString().substring(0, 8) + "   Revision " + item.revision(), sections(item, exposure)));
        if (item.input().act() != Act.VERIFICACAO) {
            boolean indicative = item.input().act() == Act.DESENVOLVIMENTO;
            add(files, "stakeholder-engagement-plan.docx", WordDocument.render("Stakeholder engagement plan", DossierText.stakeholderPlan(indicative)));
            add(files, "esg-assessment.docx", WordDocument.render("Environmental social and governance assessment", DossierText.esgAssessment(indicative)));
        }
        if ("COMPARADA".equals(edition)) {
            add(files, "comparison.docx", WordDocument.render("Changes by section",
                    DossierText.compare(sections(predecessor, exposure), sections(item, exposure))));
        }
        for (var piece : item.input().pieces()) {
            if (piece.item() == Piece.MAPA) {
                add(files, "boundary.kml", WordDocument.bytes(KML));
            } else if (reserved) {
                String issuer = "VVB".equals(piece.item().issuer())
                        ? ("AUDITORIA_V2".equals(purpose(item.input())) ? "SIM-VVB-B" : "SIM-VVB-A") : piece.item().issuer();
                var proof = new PieceProof(piece.item(), piece.reference(), issuer, "SIMULATED_ATTESTATION",
                        LANGUAGE, item.input().actDate(), item.input().closure() == null ? null : item.input().closure().period(),
                        item.source().closureSha256(), item.input().predecessor());
                add(files, "evidence/" + piece.item().name().toLowerCase(java.util.Locale.ROOT) + ".json", json(proof));
            }
        }
        if (reserved) {
            add(files, "evidence/intake-records.json", json(item.source().intakeRecords()));
            if (item.source().closureBytes() != null) {
                add(files, "evidence/monitoring-closure.json", item.source().closureBytes());
                add(files, "evidence/calculation-snapshot.json", item.source().calculationSnapshot());
            }
        }
        var entries = files.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> new File(entry.getKey(),
                mediaType(entry.getKey()), entry.getKey().equals("evidence/intake-records.json") ? "pt-BR" : LANGUAGE,
                exposure, entry.getValue().length, FixtureCatalog.sha256(entry.getValue()))).toList();
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("format", VERSION);
        manifest.put("environment", "SIMULACAO");
        manifest.put("externalUse", "BLOCKED");
        manifest.put("project", project);
        manifest.put("dossier", new Ref(item.id(), item.revision()));
        manifest.put("predecessor", item.input().predecessor());
        manifest.put("act", item.input().act());
        manifest.put("actDate", item.input().actDate());
        manifest.put("checklist", item.input().act().rule());
        manifest.put("exposure", exposure);
        manifest.put("edition", edition);
        manifest.put("language", LANGUAGE);
        manifest.put("template", Map.of("edition", EDITION, "source", item.input().act().template(),
                "sourceSha256", item.input().act().sourceSha256()));
        manifest.put("retentionFloor", floor);
        manifest.put("preparedBy", reserved ? item.author() : "PREP");
        manifest.put("reviewedBy", reserved ? review.actor() : "REV");
        manifest.put("reviewedAt", review.at());
        manifest.put("forecast", item.source().forecast());
        manifest.put("closure", item.input().closure());
        manifest.put("publicOmissions", "Contact, rights and evidence details, signatures, review notes and operational records belong in reserved evidence.");
        if (reserved) {
            manifest.put("review", review);
            manifest.put("configuration", item.source().configuration());
            manifest.put("configurationEvent", item.source().configurationEvent());
            manifest.put("conditions", item.input().conditions().stream().map(condition -> Map.of(
                    "item", condition.item(), "incidence", condition.incidence(), "reason", condition.reason(),
                    "rule", item.input().act().rule(), "configuration", item.source().configuration())).toList());
            manifest.put("closureSha256", item.source().closureSha256());
            manifest.put("validatedPd", item.input().validatedPd());
            manifest.put("validatedPdSha256", item.source().validatedPdSha256());
        }
        manifest.put("files", entries);
        byte[] bytes = json(manifest);
        add(files, "manifest.json", bytes);
        return new Generated(exposure, edition, bytes, DossierZip.pack(files));
    }

    Bytes manifest(String project, Ref ref, String exposure, String edition) {
        return database.read(connection -> variantBytes(connection, project, ref, exposure, edition, true));
    }

    JsonNode export(PilotAccess.Scope scope, String actor, String key, UUID dossier, int expected, ExportInput request) {
        if (request == null || request.revision() < 1 || !("SIM-HUB".equals(request.destination()) ? "DIGITAL_HUMANO_SIMULADO" : MANUAL_CHANNEL).equals(request.channel())
                || request.destination() == null || !DESTINATIONS.contains(request.destination())
                || !exposureValid(request.exposure(), request.edition())) {
            throw invalid("CANAL_OU_VARIANTE_INVALIDA", "Selecione o canal e o destino simulados e a variante exata.");
        }
        require("SIM-VVB".equals(request.destination()) || "PUBLICA".equals(request.exposure()),
                "EXPOSICAO_INCOMPATIVEL", "Use a variante pública para Registry ou Hub. Entregas reservadas usam o destino VVB.");
        var ref = new Ref(dossier, request.revision());
        return commands.execute(scope, actor, "EXPORTAR_PACOTE", key, audit.encode(List.of(ref, expected, request)), dossier.toString(),
                connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            var item = row(connection, project, ref);
            currentRevision(connection, project, item);
            PilotCommands.requireVersion(expected, item.version());
            require("CONGELADO".equals(item.state()), "PACOTE_PENDENTE", "Congele a versão antes da exportação.");
            require("SIM-VVB".equals(request.destination()) || "LIMPA".equals(request.edition()),
                    "EDICAO_INCOMPATIVEL", "Use a edição limpa para esse destino.");
            eligibleClosure(connection, project, item);
            var variant = variantBytes(connection, project, ref, request.exposure(), request.edition(), false);
            UUID id = UUID.randomUUID();
            UUID event = audit.record(connection, project, actor, "EXPORTAR_PACOTE", "PERMITIDO", id.toString(),
                    Map.of("package", ref, "request", request, "sha256", variant.sha256()));
            update(connection, """
                    INSERT INTO pilot_document_export (project_id,id,dossier_id,revision,exposure,edition,actor_id,event_id,destination)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, project, id, dossier, ref.revision(), request.exposure(), request.edition(), actor, event, request.destination());
            return new Export(id, ref, request.exposure(), request.edition(), request.destination(), variant.sha256(), event, actor);
        });
    }

    ListingPackage listingPackage(Connection connection, String project, Ref reference, Act purpose, boolean current)
            throws SQLException {
        var item = row(connection, project, reference);
        if (current) currentRevision(connection, project, item);
        require("CONGELADO".equals(item.state()) && purpose == item.input().act(), "PACOTE_INCOMPATIVEL",
                "Selecione um dossiê congelado para esta finalidade.");
        var variants = view(connection, project, item).variants();
        String publicSha = variants.stream().filter(v -> "PUBLICA".equals(v.exposure()) && "LIMPA".equals(v.edition()))
                .findFirst().orElseThrow().sha256();
        String reservedSha = variants.stream().filter(v -> "RESERVADA".equals(v.exposure()) && "LIMPA".equals(v.edition()))
                .findFirst().orElseThrow().sha256();
        return new ListingPackage(reference, purpose, item.input().actDate(), publicSha, reservedSha);
    }

    AuditPackage auditPackage(Connection connection, String project, Ref reference, Act purpose, boolean current)
            throws SQLException {
        var pack = listingPackage(connection, project, reference, purpose, current);
        var item = row(connection, project, reference);
        if (current) eligibleClosure(connection, project, item);
        var variants = view(connection, project, item).variants();
        UUID reserved = variants.stream().filter(v -> "RESERVADA".equals(v.exposure()) && "LIMPA".equals(v.edition()))
                .findFirst().orElseThrow().artifactId();
        boolean compared = variants.stream().anyMatch(v -> "COMPARADA".equals(v.edition()));
        return new AuditPackage(pack, item.input().closure(), reserved, compared);
    }

    ListingDelivery listingDelivery(Connection connection, String project, Ref reference, UUID exportId, Act purpose,
                                    String destination, boolean current) throws SQLException {
        return delivery(connection, project, reference, exportId, purpose, destination, current, false);
    }

    ListingDelivery auditDelivery(Connection connection, String project, Ref reference, UUID exportId, Act purpose,
                                 String destination, boolean current) throws SQLException {
        return delivery(connection, project, reference, exportId, purpose, destination, current, true);
    }

    private ListingDelivery delivery(Connection connection, String project, Ref reference, UUID exportId, Act purpose,
                                    String destination, boolean current, boolean reserved) throws SQLException {
        var pack = listingPackage(connection, project, reference, purpose, current);
        var export = findExport(connection, project, reference.id(), exportId);
        var receipt = first(connection, "SELECT body FROM pilot_document_receipt WHERE project_id=? AND export_id=?",
                result -> decode(result.getString(1), Receipt.class), project, exportId);
        require(receipt != null && reference.equals(export.dossier()) && export.destination().equals(destination)
                && (reserved ? pack.reservedSha256() : pack.sha256()).equals(export.sha256()), "RECIBO_DIVERGENTE",
                "Vincule o recibo da variante limpa aplicável, na versão e no destino da conferência.");
        return new ListingDelivery(pack, export, receipt);
    }

    List<Export> exports(String project, UUID dossier) {
        return database.read(connection -> query(connection, EXPORT_SELECT + " AND e.dossier_id=? ORDER BY e.exported_at DESC LIMIT 100",
                this::readExport, project, dossier));
    }

    Bytes exported(String project, UUID dossier, UUID export) {
        return database.read(connection -> {
            var item = findExport(connection, project, dossier, export);
            return variantBytes(connection, project, item.dossier(), item.exposure(), item.edition(), false);
        });
    }

    JsonNode receipt(PilotAccess.Scope scope, String actor, String key, UUID dossier, ReceiptInput input) {
        if (input == null || input.exportId() == null || input.dossier() == null || !dossier.equals(input.dossier().id())
                || input.receivedAt() == null || input.sha256() == null || !IntakeRules.id(input.code())
                || !IntakeRules.id(input.proof())) {
            throw invalid("RECIBO_INCOMPLETO", "Informe exportação, pacote, SHA-256, destino, código, data e prova sintética.");
        }
        return commands.execute(scope, actor, "CAPTURAR_RECIBO_DOSSIE", key, audit.encode(input), input.exportId().toString(),
                connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            var export = findExport(connection, project, dossier, input.exportId());
            require(export.dossier().equals(input.dossier()) && export.sha256().equals(input.sha256())
                    && export.destination().equals(input.destination())
                    && !input.receivedAt().isBefore(row(connection, project, input.dossier()).input().actDate()
                    .atStartOfDay(VintageWindows.ZONE).toOffsetDateTime()),
                    "RECIBO_DIVERGENTE", "Confira versão, hash, destino e data do recibo contra a exportação.");
            var existing = first(connection, """
                    SELECT body FROM pilot_document_receipt WHERE project_id=? AND (export_id=? OR (destination=? AND code=?))
                    """, rs -> decode(rs.getString(1), Receipt.class), project, input.exportId(), input.destination(), input.code());
            if (existing != null) {
                require(input.equals(existing.input()), "RECIBO_DIVERGENTE", "A prova já está vinculada a outro conteúdo.");
                return existing;
            }
            UUID event = audit.record(connection, project, actor, "CAPTURAR_RECIBO_DOSSIE", "PERMITIDO", input.exportId().toString(),
                    Map.of("package", input.dossier(), "destination", input.destination(), "code", input.code(), "sha256", input.sha256()));
            var result = new Receipt(input, export.destination(), actor, event);
            update(connection, "INSERT INTO pilot_document_receipt VALUES (?,?,?,?,?,?::jsonb)", project, export.id(), input.code(),
                    input.destination(), event, audit.encode(result));
            return result;
        });
    }

    List<Receipt> receipts(String project, UUID dossier) {
        return database.read(connection -> query(connection, """
                SELECT r.body FROM pilot_document_receipt r JOIN pilot_document_export e ON (r.project_id,r.export_id)=(e.project_id,e.id)
                WHERE r.project_id=? AND e.dossier_id=? ORDER BY e.exported_at DESC LIMIT 100
                """, row -> decode(row.getString(1), Receipt.class), project, dossier));
    }

    private static final String EXPORT_SELECT = """
            SELECT e.*,a.sha256 FROM pilot_document_export e
            JOIN pilot_document_variant v USING (project_id,dossier_id,revision,exposure,edition)
            JOIN pilot_artifact a ON (a.project_id,a.id,a.version)=(v.project_id,v.artifact_id,v.revision)
            WHERE e.project_id=?
            """;

    private Export findExport(Connection connection, String project, UUID dossier, UUID export) throws SQLException {
        var item = first(connection, EXPORT_SELECT + " AND e.dossier_id=? AND e.id=?", this::readExport, project, dossier, export);
        if (item == null) throw PilotAccess.invisible();
        return item;
    }

    private Export readExport(java.sql.ResultSet row) throws SQLException {
        return new Export(row.getObject("id", UUID.class), new Ref(row.getObject("dossier_id", UUID.class), row.getInt("revision")),
                row.getString("exposure"), row.getString("edition"), row.getString("destination"), row.getString("sha256"),
                row.getObject("event_id", UUID.class), row.getString("actor_id"));
    }

    private Row readRow(java.sql.ResultSet row) throws SQLException {
        return new Row(row.getObject("id", UUID.class), row.getInt("revision"), row.getInt("row_version"), row.getString("state"),
                row.getString("prepared_by"), row.getObject("prepared_at", OffsetDateTime.class),
                decode(row.getString("command"), Input.class), decode(row.getString("source"), Source.class),
                row.getString("review") == null ? null : decode(row.getString("review"), Review.class), row.getObject("event_id", UUID.class));
    }

    private Row row(Connection connection, String project, Ref ref) throws SQLException {
        var item = first(connection, ROW_SELECT + " AND revision=?", this::readRow, project, ref.id(), ref.revision());
        if (item == null) throw PilotAccess.invisible();
        return item;
    }

    private View view(Connection connection, String project, Row item) throws SQLException {
        var variants = query(connection, VARIANTS + " ORDER BY v.exposure,v.edition DESC", row ->
                new Variant(row.getObject("artifact_id", UUID.class), row.getString("exposure"), row.getString("edition"),
                        row.getString("sha256"), FixtureCatalog.sha256(row.getBytes("manifest"))), project, item.id(), item.revision());
        return new View(item.id(), item.revision(), item.version(), item.state(), item.author(), item.time(), item.input(),
                item.source().findings(), item.review(), variants, item.event());
    }

    private Bytes variantBytes(Connection connection, String project, Ref ref, String exposure, String edition, boolean manifest) throws SQLException {
        if (!exposureValid(exposure, edition)) throw invalid("VARIANTE_INVALIDA", "Escolha exposição e edição válidas.");
        var item = first(connection, VARIANTS + " AND v.exposure=? AND v.edition=?", row -> {
            byte[] bytes = row.getBytes(manifest ? "manifest" : "content");
            String observed = FixtureCatalog.sha256(bytes);
            require(manifest || observed.equals(row.getString("sha256")), "HASH_DIVERGENTE",
                    "O ZIP diverge do hash do pacote congelado.");
            return new Bytes(bytes, observed);
        }, project, ref.id(), ref.revision(), exposure, edition);
        if (item == null) throw PilotAccess.invisible();
        return item;
    }

    private void eligibleClosure(Connection connection, String project, Row item) throws SQLException {
        if (item.input().closure() == null) return;
        var closure = findClosure(connection, project, item.input().closure());
        require(closure != null && closure.eligible() && closure.sha().equals(item.source().closureSha256()),
                "FECHAMENTO_INAPTO", "A memória do período foi afetada. Prepare uma sucessora com novo fechamento.");
    }

    private CurrentClosure findClosure(Connection connection, String project, ClosureRef closure) throws SQLException {
        var result = first(connection, """
                SELECT c.bytes,c.sha256,NOT EXISTS (SELECT 1 FROM pilot_period_state h
                    WHERE h.project_id=c.project_id AND h.period=c.period AND h.version>c.state_version)
                FROM pilot_period_closure c WHERE c.project_id=? AND c.period=? AND c.revision=?
                """, row -> new CurrentClosure(row.getBytes(1), row.getString(2), row.getBoolean(3)),
                project, closure.period(), closure.revision());
        if (result != null) require(result.sha().equals(FixtureCatalog.sha256(result.bytes())), "HASH_DIVERGENTE",
                "A memória de fechamento não confere com o hash registrado.");
        return result;
    }

    private static boolean samePeriod(ClosureRef earlier, ClosureRef later) {
        return earlier == null ? later == null : later != null && earlier.period().equals(later.period());
    }

    private static boolean exposureValid(String exposure, String edition) {
        return exposure != null && edition != null && EXPOSURES.contains(exposure) && EDITIONS.contains(edition);
    }

    private static List<DossierText.Section> sections(Row item, String exposure) {
        return "RESERVADA".equals(exposure) ? item.source().reservedSections() : item.source().publicSections();
    }

    private static String title(Row item) { return item.input().act().title() + " " + item.id().toString().substring(0, 8)
            + " R" + item.revision(); }

    private void currentRevision(Connection connection, String project, Row item) throws SQLException {
        int latest = first(connection, "SELECT max(revision) FROM pilot_document_dossier WHERE project_id=? AND id=?",
                row -> row.getInt(1), project, item.id());
        require(latest == item.revision(), "VERSAO_HISTORICA", "Abra a revisão atual para iniciar novo uso.");
    }

    private static void validate(Input input) {
        if (input == null || input.act() == null || input.actDate() == null || !EDITION.equals(input.model())
                || !LANGUAGE.equals(input.language())
                || input.pieces() == null || input.pieces().size() > Piece.values().length
                || input.conditions() == null || input.conditions().size() > Condition.values().length
                || (input.privateNote() != null && input.privateNote().length() > 2400)) {
            throw invalid("DOSSIE_INVALIDO", "Informe ato, data, modelo 5.0B, idioma inglês e checklist de simulação.");
        }
        if (input.act() != Act.VERIFICACAO && (input.closure() != null || input.validatedPd() != null)
                || input.closure() != null && (!EnergyImports.validPeriod(input.closure().period()) || input.closure().revision() < 1)
                || input.validatedPd() != null && !valid(input.validatedPd())
                || input.predecessor() != null && (!valid(input.predecessor()) || !IntakeRules.text(input.reason()))) {
            throw invalid("VINCULO_INVALIDO", "Confira finalidade, período, versões e justificativa da sucessora.");
        }
        var allowed = checklist(input.act(), input.closure() == null ? null : input.closure().period(), input.predecessor() != null)
                .pieces().stream().map(PieceOption::item).toList();
        var pieces = new HashSet<Piece>();
        for (var piece : input.pieces()) {
            if (piece == null || piece.item() == null || !allowed.contains(piece.item()) || !pieces.add(piece.item())
                    || !piece.item().reference(input.closure() == null ? null : input.closure().period()).equals(piece.reference())) {
                throw invalid("PECA_OU_AUTORIA_INVALIDA", "Use a referência versionada do emissor previsto para esse ato e período.");
            }
        }
        var conditions = new HashSet<Condition>();
        for (var condition : input.conditions()) {
            if (condition == null || condition.item() == null || condition.incidence() == null || !conditions(input.act()).contains(condition.item())
                    || !conditions.add(condition.item()) || (condition.reason() != null && condition.reason().length() > 600)) {
                throw invalid("INCIDENCIA_INVALIDA", "Informe as incidências previstas sem repetir um item.");
            }
        }
    }

    private static boolean valid(Ref ref) { return ref.id() != null && ref.revision() > 0; }

    private static void add(Map<String, byte[]> files, String name, byte[] content) {
        require(content.length <= 25_000_000, "ANEXO_ACIMA_DO_LIMITE", "Uma peça excede o limite de 25 MB.");
        files.put(name, content);
    }

    private static String mediaType(String filename) {
        if (filename.endsWith(".kml")) return "application/vnd.google-earth.kml+xml";
        if (filename.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/json";
    }

    private byte[] json(Object value) { return WordDocument.bytes(audit.encode(value)); }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Falha ao ler o dossiê", error);
        }
    }

    static PilotProblem invalid(String code, String message) { return PilotProblem.invalid(code, "dossier", "OP-E6-08", message); }
    static void require(boolean condition, String code, String message) {
        if (!condition) throw new PilotProblem(409, code, "dossier", "OP-E6-08", message);
    }

    private static final String KML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>SIMULATION</name>
              <description>Illustrative coordinates in Planaltina for the synthetic project footprint.</description>
              <Placemark><name>Charging station 1</name><Point><coordinates>-47.6500,-15.6300,0</coordinates></Point></Placemark>
              <Placemark><name>Charging station 2</name><Point><coordinates>-47.6501,-15.6301,0</coordinates></Point></Placemark>
            </Document></kml>
            """;
}
