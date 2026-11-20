package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Internal documentary/methodology assessment of an MR revision, with
 * independent reviewer and immutable evidence-lineage pinning. It is NOT an
 * approval by VVB/Verra and does NOT admit ED/EC for E8 or VCU issuance.
 */
@ApplicationScoped
class OpenMethodReview {
    static final String METHOD="VM0038";
    static final String EDITION="1.1";
    private static final String RULE="CF-OPEN-009";
    static final List<String> REQUIREMENTS=List.of(
        "BOUNDARY_DCFC_SIN",
        "METER_CALIBRATION",
        "SOURCE_RIGHTS",
        "HOURLY_ED_EC",
        "CLOCK_GAPS_MAINTENANCE",
        "DOUBLE_CLAIMING");

    record Check(String requirement,String finding,String evidenceReference,String reason) {}
    record Decision(String outcome,String rationale,List<Check> checks) {}
    record Gate(String code,String detail) {}
    record Review(int version,String outcome,String rationale,List<Check> checks,
                  String evidenceSha256,UUID reviewedBy,OffsetDateTime reviewedAt) {}
    record View(String period,int dossierVersion,UUID snapshotId,String methodology,
                String methodologyEdition,String state,boolean stale,boolean canUseForE8,
                List<Gate> gates,Review review) {}
    private record Target(int version,UUID snapshotId,UUID author,String sourceSha) {}

    private final DynamicProjectScope scope;
    private final ObjectMapper json;

    OpenMethodReview(DynamicProjectScope scope,ObjectMapper json) {
        this.scope=scope;this.json=json;
    }

    View get(String cookie,String project,String period) {
        return scope.read(cookie,project,"VIEW_OPEN_METHOD_REVIEW",(c,ctx)->
            diagnose(c,project,OpenMonitoringDossiers.code(period)));
    }

    List<Review> history(String cookie,String project,String period,int revision) {
        return scope.read(cookie,project,"VIEW_OPEN_METHOD_REVIEW",(c,ctx)->{
            String code=OpenMonitoringDossiers.code(period);
            if(target(c,project,code,revision)==null)throw unavailable();
            return query(c,REVIEW_SELECT+"""
                WHERE project_id=? AND dossier_code=? AND dossier_version=?
                ORDER BY version
                """,r->mapReview(r),project,code,revision);
        });
    }

    View decide(String cookie,String project,String period,String match,String key,
                Decision submitted) {
        return scope.write(cookie,project,"REVIEW_OPEN_METHOD_REVIEW",(c,ctx)->{
            if(ctx.owner() || !"REV".equals(ctx.role()))throw unavailable();
            String code=OpenMonitoringDossiers.code(period);
            Target mr=latest(c,project,code);
            if(mr==null)throw unavailable();
            if(ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
                throw unavailable();
            if(independenceViolation(c,project,mr,ctx.actor().id()))
                throw new PilotProblem(403,"METHOD_REVIEW_NOT_INDEPENDENT","reviewer",
                    RULE,"Revisor não pode ser autor do MR, snapshot, leituras ou comprovantes.");
            Decision normalized=validate(submitted);
            String command=PilotCommands.key(key);
            int expected=PilotCommands.expected(match);
            var evidence=OpenPeriodSupport.diagnose(c,project,mr.snapshotId());
            String checksum=lineage(project,code,mr,evidence);
            if("CHECKED_INTERNAL".equals(normalized.outcome()) &&
               (!"READY_FOR_NORMATIVE_REVIEW".equals(evidence.state()) ||
                normalized.checks().stream().anyMatch(
                    item->!"SUPPORTED_INTERNAL".equals(item.finding()))))
                throw conflict("É necessário QA vigente, documentos conferidos e todos os critérios fundamentados.");
            byte[] fingerprint=AccessSecrets.digest(
                project+"|"+code+"|"+mr.version()+"|"+expected+"|"+
                normalized.outcome()+"|"+normalized.rationale()+"|"+
                normalized.checks()+"|"+checksum);
            Object[] previousCommand=first(c,"""
                SELECT project_id,dossier_code,dossier_version,review_version,fingerprint
                FROM app_open_method_review_command
                WHERE actor_id=? AND command_key=?
                """,r->new Object[]{r.getString(1),r.getString(2),r.getInt(3),
                    r.getInt(4),r.getBytes(5)},ctx.actor().id(),command);
            if(previousCommand!=null) {
                if(!project.equals(previousCommand[0]) || !code.equals(previousCommand[1]) ||
                    mr.version()!=(Integer)previousCommand[2] ||
                    !MessageDigest.isEqual(fingerprint,(byte[])previousCommand[4]))
                    throw conflict("Chave de idempotência utilizada em outra decisão.");
                return diagnose(c,project,code);
            }
            Review old=latestReview(c,project,code,mr.version());
            PilotCommands.requireVersion(expected,old==null?0:old.version());
            if(expected>=100000000)throw invalid("version","Limite de pareceres atingido.");
            update(c,"""
                INSERT INTO app_open_method_review
                (project_id,dossier_code,dossier_version,version,snapshot_id,
                 outcome,rationale,checklist,evidence_sha256,reviewed_by)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)
                """,project,code,mr.version(),expected+1,mr.snapshotId(),
                normalized.outcome(),normalized.rationale(),
                json.valueToTree(normalized.checks()).toString(),
                HexFormat.of().parseHex(checksum),ctx.actor().id());
            update(c,"""
                INSERT INTO app_open_method_review_command
                (actor_id,command_key,project_id,dossier_code,dossier_version,
                 review_version,fingerprint)
                VALUES (?,?,?,?,?,?,?)
                """,ctx.actor().id(),command,project,code,mr.version(),
                expected+1,fingerprint);
            return diagnose(c,project,code);
        });
    }

    static String status(Connection c,String project,String code) throws SQLException {
        return diagnose(c,project,code).state();
    }

    static View diagnose(Connection c,String project,String code) throws SQLException {
        Target mr=latest(c,project,code);
        if(mr==null)throw unavailable();
        var support=OpenPeriodSupport.diagnose(c,project,mr.snapshotId());
        var review=latestReview(c,project,code,mr.version());
        String currentHash=lineage(project,code,mr,support);
        var gates=new ArrayList<Gate>();
        boolean stale=support.stale() ||
            review!=null && !currentHash.equals(review.evidenceSha256());
        if(support.stale())gates.add(new Gate("METHOD_SNAPSHOT_STALE",
            "As leituras, medidores ou ocorrências deixaram de corresponder ao snapshot."));
        if(review!=null && !currentHash.equals(review.evidenceSha256()))
            gates.add(new Gate("METHOD_EVIDENCE_CHANGED",
                "Evidências ou versões dos pareceres mudaram desde a avaliação."));
        if(!support.documentaryComplete())
            gates.add(new Gate("METHOD_DOCUMENTARY_GAPS",
                "Existem documentos ausentes, rejeitados ou não conferidos internamente."));
        if(review==null)gates.add(new Gate("METHOD_REVIEW_PENDING",
            "O revisor independente ainda não avaliou os critérios da edição VM0038 v1.1."));
        else if("RETURNED".equals(review.outcome()))
            gates.add(new Gate("METHOD_REVIEW_RETURNED",
                "O parecer interno solicita correções antes da próxima avaliação."));
        String state=stale?"BLOCKED_STALE":
            !support.documentaryComplete()?"BLOCKED_DOCUMENTARY":
            review==null?"PENDING_INTERNAL_REVIEW":
            "RETURNED".equals(review.outcome())?"RETURNED_FOR_CORRECTION":
            "CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION";
        return new View(code,mr.version(),mr.snapshotId(),METHOD,EDITION,
            state,stale,false,List.copyOf(gates),review);
    }

    private static String lineage(String project,String code,Target mr,
                                  OpenPeriodSupport.Readiness support) {
        StringBuilder value=new StringBuilder("CF-OPEN-METHOD-REVIEW-V1|")
            .append(project).append('|').append(code).append('|')
            .append(mr.version()).append('|').append(mr.snapshotId()).append('|')
            .append(mr.sourceSha()).append('|').append(METHOD).append(':').append(EDITION);
        support.documents().stream().sorted(Comparator.comparing(
            (OpenPeriodSupport.Document d)->d.id().toString())).forEach(doc->{
                value.append('|').append(doc.kind()).append(':')
                    .append(doc.subjectCode()).append(':').append(doc.id())
                    .append(':').append(doc.sha256()).append(':')
                    .append(doc.evidenceReference());
                if(doc.review()!=null)value.append(':').append(doc.review().version())
                    .append(':').append(doc.review().outcome());
                else value.append(":PENDING");
            });
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException cause){throw new IllegalStateException(cause);}
    }

    private static boolean independenceViolation(Connection c,String project,Target mr,UUID reviewer)
        throws SQLException {
        if(reviewer.equals(mr.author()))return true;
        return Boolean.TRUE.equals(first(c,"""
            SELECT EXISTS(
              SELECT 1 FROM app_open_period_qa_snapshot s
              WHERE s.project_id=? AND s.id=? AND s.created_by=?
            ) OR EXISTS(
              SELECT 1 FROM app_open_period_qa_snapshot_line l
              JOIN app_open_measurement_batch b ON b.project_id=l.project_id AND b.id=l.batch_id
              WHERE l.project_id=? AND l.snapshot_id=? AND b.submitted_by=?
            ) OR EXISTS(
              SELECT 1 FROM app_open_period_support_document d
              WHERE d.project_id=? AND d.snapshot_id=? AND d.submitted_by=?
            )
            """,r->r.getBoolean(1),project,mr.snapshotId(),reviewer,
            project,mr.snapshotId(),reviewer,project,mr.snapshotId(),reviewer));
    }

    private static Target latest(Connection c,String project,String code) throws SQLException {
        return first(c,"""
            SELECT version,snapshot_id,authored_by,encode(source_sha256,'hex')
            FROM app_open_mr_dossier_revision
            WHERE project_id=? AND dossier_code=? ORDER BY version DESC LIMIT 1
            """,r->new Target(r.getInt(1),r.getObject(2,UUID.class),
                r.getObject(3,UUID.class),r.getString(4)),project,code);
    }
    private static Target target(Connection c,String project,String code,int revision)
        throws SQLException {
        return first(c,"""
            SELECT version,snapshot_id,authored_by,encode(source_sha256,'hex')
            FROM app_open_mr_dossier_revision
            WHERE project_id=? AND dossier_code=? AND version=?
            """,r->new Target(r.getInt(1),r.getObject(2,UUID.class),
                r.getObject(3,UUID.class),r.getString(4)),project,code,revision);
    }
    private static final String REVIEW_SELECT="""
        SELECT version,outcome,rationale,checklist::text AS checklist,
          encode(evidence_sha256,'hex') AS sha,reviewed_by,reviewed_at
        FROM app_open_method_review
        """;
    private static Review latestReview(Connection c,String project,String code,int mrVersion)
        throws SQLException {
        return first(c,REVIEW_SELECT+"""
            WHERE project_id=? AND dossier_code=? AND dossier_version=?
            ORDER BY version DESC LIMIT 1
            """,OpenMethodReview::mapReview,project,code,mrVersion);
    }
    private static Review mapReview(java.sql.ResultSet r) throws SQLException {
        List<Check> checks;
        try {
            checks=new ObjectMapper().readValue(r.getString("checklist"),
                new TypeReference<List<Check>>(){});
        } catch(java.io.IOException ex) {throw new SQLException("Invalid method review checklist",ex);}
        return new Review(r.getInt("version"),r.getString("outcome"),
            r.getString("rationale"),List.copyOf(checks),r.getString("sha"),
            r.getObject("reviewed_by",UUID.class),
            r.getObject("reviewed_at",OffsetDateTime.class));
    }
    private static Decision validate(Decision input) {
        if(input==null || !Set.of("CHECKED_INTERNAL","RETURNED").contains(input.outcome()))
            throw invalid("outcome","Informe CHECKED_INTERNAL ou RETURNED.");
        String rationale=safe(input.rationale(),15,2000,"rationale");
        if(input.checks()==null || input.checks().size()!=REQUIREMENTS.size())
            throw invalid("checks","Avalie todos os seis requisitos com fundamento verificável.");
        var checked=new ArrayList<Check>();
        var seen=new java.util.HashSet<String>();
        for(Check item:input.checks()) {
            if(item==null || !REQUIREMENTS.contains(item.requirement()) ||
                !seen.add(item.requirement()) ||
                !Set.of("SUPPORTED_INTERNAL","OPEN").contains(item.finding()))
                throw invalid("checks","Critério duplicado, ausente ou classificação inválida.");
            checked.add(new Check(item.requirement(),item.finding(),
                safe(item.evidenceReference(),8,300,"evidenceReference"),
                safe(item.reason(),15,2000,"reason")));
        }
        checked.sort(Comparator.comparingInt(c->REQUIREMENTS.indexOf(c.requirement())));
        if("CHECKED_INTERNAL".equals(input.outcome()) &&
            checked.stream().anyMatch(c->!"SUPPORTED_INTERNAL".equals(c.finding())))
            throw invalid("checks","Não é possível concluir parecer com critérios em aberto.");
        return new Decision(input.outcome(),rationale,List.copyOf(checked));
    }
    private static String safe(String s,int min,int max,String field) {
        if(s==null || s.strip().length()<min || s.strip().length()>max ||
           s.codePoints().anyMatch(Character::isISOControl))
            throw invalid(field,"Texto ou referência verificável fora dos limites.");
        return s.strip();
    }
    private static PilotProblem invalid(String field,String message) {
        return PilotProblem.invalid("PARECER_METODOLOGICO_INVALIDO",field,RULE,message);
    }
    private static PilotProblem conflict(String message) {
        return new PilotProblem(409,"PARECER_METODOLOGICO_CONFLITANTE","assessment",RULE,message);
    }
    private static PilotProblem unavailable() {
        return new PilotProblem(404,"PARECER_METODOLOGICO_INDISPONIVEL","assessment",RULE,
            "Avaliação indisponível.");
    }
}
