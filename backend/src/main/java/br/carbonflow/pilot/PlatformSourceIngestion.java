package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Source provenance registry. Only approved METADATA gets published to app_source_version.
 * It never stores or fetches protected content, imports numerical values or grants a license.
 */
@ApplicationScoped
class PlatformSourceIngestion {
    record Intake(UUID id, String needCode, String sourceId, String edition, String category,
                  String title, String rightsHolder, String publisher, String officialUri,
                  LocalDate publicationDate, Integer referenceYear, String locator,
                  String declaredOriginalSha256, String state, int version,
                  UUID submittedBy, String submittedByName, UUID reviewedBy,
                  OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    record Event(int version, String state, UUID actorId, String actorName,
                 String reason, OffsetDateTime recordedAt) {}
    record Detail(Intake intake, List<Event> history) {}
    record Board(boolean canSubmit, boolean canReview, List<Intake> submissions) {}
    record Submit(String needCode, String edition, String category, String title,
                  String rightsHolder, String publisher, String officialUri,
                  LocalDate publicationDate, Integer referenceYear, String locator,
                  String declaredOriginalSha256, String note) {}
    record Review(String outcome, String reason) {}

    private record Need(String code, String sourceId, String expectedEdition,
                        String kind, String editionPolicy, String catalogState) {}
    private record Published(String category, String title, String rightsHolder, String officialUri) {}

    private final PilotDatabase database;
    private final PlatformSourceReadiness readiness;
    private final PlatformSourceWork work;
    private final Set<UUID> reviewers;

    PlatformSourceIngestion(PilotDatabase database, PlatformSourceReadiness readiness,
            PlatformSourceWork work,
            @ConfigProperty(name = "carbonflow.platform.source-ingestion.reviewers") Optional<String> roster) {
        this.database = database;
        this.readiness = readiness;
        this.work = work;
        try {
            this.reviewers = roster.map(s -> Arrays.stream(s.split(",", -1))
                    .map(String::strip).filter(x -> !x.isEmpty()).map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Cadastro de revisores de fontes inválido.");
        }
    }

    Board list(String token, String method, String edition, String scenario) {
        return database.transaction(connection -> {
            var actor = readiness.requireViewer(connection, token);
            if (method == null || edition == null || scenario == null ||
                first(connection, """
                    SELECT 1 FROM app_methodology_source_context
                    WHERE methodology = ? AND methodology_edition = ? AND scenario = ?
                    """, row -> row.getInt(1), method, edition, scenario) == null) throw invisible();
            var entries = query(connection, """
                    SELECT s.id,s.need_code,s.source_id,s.edition,s.category,s.title,
                           s.rights_holder,s.publisher,s.official_uri,s.publication_date,s.reference_year,
                           s.locator,s.declared_original_sha256,s.state,s.version,s.submitted_by,
                           coalesce(author.display_name,''),s.reviewed_by,s.created_at,s.updated_at
                    FROM app_platform_source_submission s
                    JOIN app_methodology_source_need n ON n.code = s.need_code
                    JOIN app_identity author ON author.id = s.submitted_by
                    WHERE n.methodology = ? AND n.methodology_edition = ? AND n.scenario = ?
                    ORDER BY s.created_at DESC,s.id DESC LIMIT 100
                    """, PlatformSourceIngestion::map, method,edition,scenario);
            ComplianceStaff.audit(connection, actor.id(), null, "VIEW_PLATFORM_SOURCE_INGESTION");
            return new Board(work.canManage(actor.id()), reviewers.contains(actor.id()), List.copyOf(entries));
        });
    }

    Detail detail(String token, UUID id) {
        return database.transaction(connection -> {
            var actor = readiness.requireViewer(connection,token);
            var result = fetch(connection,id);
            ComplianceStaff.audit(connection, actor.id(), null, "VIEW_PLATFORM_SOURCE_INGESTION");
            return result;
        });
    }

    Detail submit(String token, Submit request) {
        return database.transaction(connection -> {
            var actor = work.requireManager(connection,token);
            if (request == null) throw invalid("source","Informe metadados da fonte.");
            Need need = need(connection,request.needCode());
            if (!"ACTIVE".equals(need.catalogState())) throw invalid("needCode","Contexto suspenso.");
            String edition = validatedEdition(request.edition(), need);
            String category = validatedCategory(request.category(),need.kind());
            String title = required(request.title(),"title",3,200);
            String rightsHolder = required(request.rightsHolder(),"rightsHolder",2,200);
            String publisher = required(request.publisher(),"publisher",2,200);
            String url = validatedUrl(request.officialUri());
            String locator = required(request.locator(),"locator",2,300);
            String note = required(request.note(),"note",3,2000);
            String declaredDigest = request.declaredOriginalSha256() == null ||
                    request.declaredOriginalSha256().isBlank() ? null :
                    required(request.declaredOriginalSha256(),"declaredOriginalSha256",64,64);
            if (declaredDigest != null && !declaredDigest.matches("[a-f0-9]{64}")) {
                throw invalid("declaredOriginalSha256","Informe SHA-256 hexadecimal minúsculo.");
            }
            Integer year = request.referenceYear();
            if (year != null && (year < 1900 || year > 2100)) {
                throw invalid("referenceYear","Ano de referência fora da faixa permitida.");
            }
            if (request.publicationDate() != null && request.publicationDate().isAfter(LocalDate.now().plusDays(1))) {
                throw invalid("publicationDate","A publicação não pode estar no futuro.");
            }
            String[] elements = {need.code(),need.sourceId(),edition,category,title,rightsHolder,publisher,
                url,locator,request.publicationDate()==null?"":request.publicationDate().toString(),
                year==null?"":year.toString(),declaredDigest==null?"":declaredDigest};
            StringBuilder canonical = new StringBuilder();
            for (String element : elements) canonical.append(element.length()).append(':').append(element);
            byte[] fingerprint = AccessSecrets.digest(canonical.toString());

            UUID identifier = first(connection, """
                    INSERT INTO app_platform_source_submission
                    (id,need_code,source_id,edition,category,title,rights_holder,publisher,official_uri,
                     publication_date,reference_year,locator,declared_original_sha256,
                     metadata_fingerprint,submitted_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (need_code,metadata_fingerprint) DO NOTHING
                    RETURNING id
                    """, row -> row.getObject(1,UUID.class),UUID.randomUUID(),need.code(),
                    need.sourceId(),edition,category,title,rightsHolder,publisher,url,
                    request.publicationDate(),year,locator,declaredDigest,fingerprint,actor.id());
            if (identifier != null) {
                update(connection, """
                    INSERT INTO app_platform_source_submission_event
                        (submission_id,version,state,actor_id,reason)
                    VALUES (?,1,'PENDING_REVIEW',?,?)
                    """,identifier,actor.id(),note);
                ComplianceStaff.audit(connection,actor.id(),null,"SUBMIT_PLATFORM_SOURCE_METADATA");
            } else {
                identifier = first(connection, """
                    SELECT id FROM app_platform_source_submission
                    WHERE need_code = ? AND metadata_fingerprint = ?
                    """,row -> row.getObject(1,UUID.class),need.code(),fingerprint);
            }
            return fetch(connection,identifier);
        });
    }

    Detail review(String token, UUID id, int expectedVersion, Review input) {
        return database.transaction(connection -> {
            var actor = readiness.requireViewer(connection,token);
            if (!reviewers.contains(actor.id())) throw invisible();
            if (input == null || !Set.of("ACCEPT_METADATA","REQUEST_CORRECTION","REJECT")
                    .contains(input.outcome())) {
                throw invalid("outcome","Parecer de metadados inválido.");
            }
            String reason = required(input.reason(),"reason",3,2000);
            Detail selected = fetch(connection,id);
            Intake intake = selected.intake();
            if (intake.submittedBy().equals(actor.id())) {
                throw new PilotProblem(403,"SEGREGACAO_OBRIGATORIA","reviewer","CF-OPEN-003",
                        "O responsável pelo cadastro não pode revisar a própria submissão.");
            }
            if (intake.version() != expectedVersion || !"PENDING_REVIEW".equals(intake.state())) {
                throw new PilotProblem(409,"REVISAO_OBSOLETA","If-Match","CF-OPEN-003",
                        "Submissão já alterada ou revisão concluída. Atualize a consulta.");
            }
            String nextState = switch(input.outcome()) {
                case "ACCEPT_METADATA" -> "METADATA_ACCEPTED";
                case "REQUEST_CORRECTION" -> "NEEDS_CORRECTION";
                default -> "REJECTED";
            };
            if ("METADATA_ACCEPTED".equals(nextState)) {
                // Serialize acceptance of the same source edition to prevent concurrent metadata overwrite.
                first(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        row -> row.getObject(1),intake.sourceId()+"@"+intake.edition());
                Published published = first(connection, """
                    SELECT category,title,rights_holder,official_uri
                    FROM app_source_version WHERE source_id = ? AND edition = ? AND project_id IS NULL
                    FOR UPDATE
                    """,row -> new Published(row.getString(1),row.getString(2),row.getString(3),row.getString(4)),
                    intake.sourceId(),intake.edition());
                if (published != null && (!published.category().equals(intake.category()) ||
                        !published.title().equals(intake.title()) ||
                        !published.rightsHolder().equals(intake.rightsHolder()) ||
                        !java.util.Objects.equals(published.officialUri(),intake.officialUri()))) {
                    throw new PilotProblem(409,"EDICAO_JA_CADASTRADA","edition","CF-OPEN-003",
                            "Esta edição já possui metadados distintos. Registre uma nova edição ou reconcilie.");
                }
                if (published == null) update(connection, """
                    INSERT INTO app_source_version
                        (source_id,edition,category,title,rights_holder,official_uri,origin)
                    VALUES (?,?,?,?,?,?,'EXTERNAL')
                    """,intake.sourceId(),intake.edition(),intake.category(),intake.title(),
                    intake.rightsHolder(),intake.officialUri());
            }
            Integer version = first(connection, """
                UPDATE app_platform_source_submission
                SET version = version + 1,state = ?,reviewed_by = ?,updated_at = statement_timestamp()
                WHERE id = ? AND version = ? AND state = 'PENDING_REVIEW'
                RETURNING version
                """,row -> row.getInt(1),nextState,actor.id(),id,expectedVersion);
            if (version == null) throw new PilotProblem(409,"REVISAO_OBSOLETA","If-Match",
                    "CF-OPEN-003","Submissão alterada por outro revisor.");
            update(connection, """
                INSERT INTO app_platform_source_submission_event
                    (submission_id,version,state,actor_id,reason)
                VALUES (?,?,?,?,?)
                """,id,version,nextState,actor.id(),reason);
            ComplianceStaff.audit(connection,actor.id(),null,"REVIEW_PLATFORM_SOURCE_METADATA");
            return fetch(connection,id);
        });
    }

    private static Detail fetch(Connection connection, UUID id) throws SQLException {
        if (id == null) throw invisible();
        Intake item = first(connection, """
                SELECT s.id,s.need_code,s.source_id,s.edition,s.category,s.title,
                       s.rights_holder,s.publisher,s.official_uri,s.publication_date,s.reference_year,
                       s.locator,s.declared_original_sha256,s.state,s.version,s.submitted_by,
                       coalesce(author.display_name,''),s.reviewed_by,s.created_at,s.updated_at
                FROM app_platform_source_submission s
                JOIN app_identity author ON author.id = s.submitted_by
                WHERE s.id = ?
                """,PlatformSourceIngestion::map,id);
        if (item == null) throw invisible();
        List<Event> events = query(connection, """
                SELECT e.version,e.state,e.actor_id,coalesce(a.display_name,''),e.reason,e.recorded_at
                FROM app_platform_source_submission_event e
                JOIN app_identity a ON a.id = e.actor_id
                WHERE e.submission_id = ? ORDER BY e.version DESC
                """,row -> new Event(row.getInt(1),row.getString(2),
                row.getObject(3,UUID.class),row.getString(4),row.getString(5),
                row.getObject(6,OffsetDateTime.class)),id);
        return new Detail(item,List.copyOf(events));
    }

    private static Intake map(java.sql.ResultSet r) throws SQLException {
        return new Intake(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),
                r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),
                r.getObject(10,LocalDate.class),r.getObject(11,Integer.class),r.getString(12),
                r.getString(13),r.getString(14),r.getInt(15),r.getObject(16,UUID.class),
                r.getString(17),r.getObject(18,UUID.class),
                r.getObject(19,OffsetDateTime.class),r.getObject(20,OffsetDateTime.class));
    }

    private static Need need(Connection c, String code) throws SQLException {
        if (code == null || !code.matches("[A-Z][A-Z0-9_-]{2,80}")) throw invisible();
        Need need = first(c, """
            SELECT n.code,n.source_id,n.source_edition,n.kind,n.edition_policy,c.catalog_state
            FROM app_methodology_source_need n
            JOIN app_methodology_source_context c
            ON c.methodology=n.methodology AND c.methodology_edition=n.methodology_edition
              AND c.scenario=n.scenario
            WHERE n.code=?
            """,row -> new Need(row.getString(1),row.getString(2),row.getString(3),
            row.getString(4),row.getString(5),row.getString(6)),code);
        if (need == null || "PROJECT_EVIDENCE".equals(need.kind()) || need.sourceId()==null) {
            throw invisible();
        }
        return need;
    }

    private static String validatedEdition(String value, Need need) {
        String edition=required(value,"edition",1,30);
        if (!edition.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,29}") ||
                "YEAR".equals(edition) || "LATEST".equals(edition)) {
            throw invalid("edition","Informe uma edição concreta, não o placeholder YEAR/LATEST.");
        }
        if ("EXACT".equals(need.editionPolicy()) && !edition.equals(need.expectedEdition())) {
            throw invalid("edition","Edição difere do catálogo previsto para esta fonte.");
        }
        if ("YEAR".equals(need.editionPolicy()) && !edition.matches("(19|20)[0-9]{2}")) {
            throw invalid("edition","Uma fonte anual exige ano concreto (AAAA).");
        }
        return edition;
    }

    private static String validatedCategory(String value, String kind) {
        if (value == null) throw invalid("category","Categoria da fonte obrigatória.");
        if ("DATASET".equals(kind) && "EXTERNAL_SOURCE".equals(value)) return value;
        if ("NORMATIVE".equals(kind) && Set.of("METHODOLOGY","MODULE","TOOL",
                "PROGRAM_DOCUMENT","TEMPLATE").contains(value)) return value;
        throw invalid("category","Categoria incompatível com a necessidade documental.");
    }

    private static String validatedUrl(String value) {
        String trimmed=required(value,"officialUri",12,1000);
        try {
            URI uri=URI.create(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null ||
                    uri.getHost().isBlank() || uri.getUserInfo()!=null ||
                    uri.getHost().equalsIgnoreCase("localhost") || uri.getHost().startsWith("127.")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw invalid("officialUri","Informe URL HTTPS oficial válida, sem usuário e senha.");
        }
        return trimmed;
    }

    private static String required(String value, String field, int min, int max) {
        if (value == null || value.strip().length()<min || value.strip().length()>max) {
            throw invalid(field,"Campo obrigatório ou com tamanho inválido.");
        }
        return value.strip();
    }

    private static PilotProblem invalid(String field,String message) {
        return PilotProblem.invalid("METADADOS_INVALIDOS",field,"CF-OPEN-003",message);
    }

    private static PilotProblem invisible() {
        return new PilotProblem(404,"RECURSO_NAO_VISIVEL","source","CF-OPEN-003",
                "Submissão indisponível para esta equipe.");
    }
}
