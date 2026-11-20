package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Immutable candidate-only bridge from independently reviewed OPEN meter data
 * to a future E8 adapter. No admitted inputs, equations, emissions or credits.
 * Source references NEVER come from the PIL-SYN tables.
 */
@ApplicationScoped
class OpenE8EnergyCandidates {
    private static final String RULE="CF-OPEN-008";
    private final DynamicProjectScope scope;

    record Input(int dossierVersion,int methodReviewVersion) {}
    record Hour(String chargerCode,OffsetDateTime from,OffsetDateTime until,
                String edKwh,String ecKwh,UUID edBatchId,int edLine,int edReview,
                UUID ecBatchId,int ecLine,int ecReview) {}
    record Candidate(UUID id,String projectId,String period,int dossierVersion,
                     int methodReviewVersion,UUID snapshotId,String sourceSha256,
                     String edKwh,String ecKwh,int hourCount,int slotCount,
                     String state,boolean stale,boolean usableForE8,
                     OffsetDateTime createdAt,List<String> gates,List<Hour> hours) {}
    private record Target(int version,UUID snapshot,UUID author,String snapshotSha,
                          OffsetDateTime from,OffsetDateTime until,
                          BigDecimal ed,BigDecimal ec,String methodSha) {}
    private record Row(UUID batch,int line,int review,String meter,String charger,
                       String kind,OffsetDateTime from,OffsetDateTime until,
                       BigDecimal kwh,String sourceSha,String lineSha) {}
    private record Stored(UUID id,String projectId,String period,int dossierVersion,
                          int methodReviewVersion,UUID snapshotId,String sha,
                          String ed,String ec,int hours,int slots,OffsetDateTime createdAt) {}
    private static final class Pair {
        Row ed; Row ec;
        void add(Row r) {
            if("ED".equals(r.kind())) {
                if(ed!=null)throw conflict("Duas linhas ED na mesma hora e carregador.");
                ed=r;
            } else if("EC".equals(r.kind())) {
                if(ec!=null)throw conflict("Duas linhas EC na mesma hora e carregador.");
                ec=r;
            } else throw conflict("Variável energética desconhecida no snapshot.");
        }
    }

    OpenE8EnergyCandidates(DynamicProjectScope scope) {this.scope=scope;}

    List<Candidate> list(String cookie,String project,String period) {
        return scope.read(cookie,project,"VIEW_OPEN_E8_ENERGY_CANDIDATE",(c,ctx)->{
            String code=OpenMonitoringDossiers.code(period);
            return query(c,STORED_SQL+"""
                WHERE project_id=? AND dossier_code=?
                ORDER BY created_at DESC,id DESC LIMIT 20
                """,OpenE8EnergyCandidates::stored,project,code).stream()
                .map(item->{
                    try{return view(c,project,item);}
                    catch(SQLException error){throw new RuntimeException(error);}
                }).toList();
        });
    }

    Candidate prepare(String cookie,String project,String period,String key,String match,
                      Input input) {
        return scope.write(cookie,project,"PREPARE_OPEN_E8_ENERGY_CANDIDATE",(c,ctx)->{
            if(!ctx.owner() && !"PREP".equals(ctx.role()))throw unavailable();
            if(ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
                throw unavailable();
            String code=OpenMonitoringDossiers.code(period);
            if(input==null || input.dossierVersion()<1 || input.methodReviewVersion()<1)
                throw invalid("input","Informe as versões do MR e da avaliação interna.");
            PilotCommands.requireVersion(PilotCommands.expected(match),input.methodReviewVersion());
            String command=PilotCommands.key(key);
            Target target=target(c,project,code);
            if(target==null)throw unavailable();
            if(target.version()!=input.dossierVersion())
                throw new PilotProblem(412,"MR_REVISION_CHANGED","dossierVersion",RULE,
                    "O MR mudou. Reabra o formulário e confira as provas.");
            OpenMethodReview.View method=OpenMethodReview.diagnose(c,project,code);
            if(!"CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION".equals(method.state()) ||
                method.review()==null || method.review().version()!=input.methodReviewVersion())
                throw conflict("O parecer metodológico interno está pendente, desatualizado ou em outra revisão.");
            OpenPeriodSupport.Readiness support=OpenPeriodSupport.diagnose(c,project,target.snapshot());
            if(support.stale() || !support.documentaryComplete())
                throw conflict("O snapshot ou a documentação já não são vigentes.");
            var qa=OpenMeasurementReviewQa.evaluate(c,project,target.from(),target.until());
            if(!qa.readyForInternalSnapshot())
                throw conflict("A reconciliação horária atual não satisfaz o QA.");
            String qaHash=OpenMeasurementReviewQa.hash(project,target.from(),target.until(),
                qa.accepted(),OpenMonitoringEvents.impacting(c,project,target.from(),target.until()));
            if(!qaHash.equals(target.snapshotSha()))
                throw conflict("As leituras e ocorrências atuais não correspondem ao snapshot.");
            List<Row> rows=rows(c,project,target.snapshot());
            List<Hour> slots=reconcile(target,rows);
            int hourCount=Math.toIntExact(Duration.between(
                target.from().toInstant(),target.until().toInstant()).toHours());
            if(hourCount<1 || hourCount>744 || slots.size()>74400)
                throw conflict("O período ultrapassa o limite de cobertura horária.");
            String contentSha=signature(project,code,target,method,rows,slots);
            byte[] fingerprint=AccessSecrets.digest(
                project+"|"+code+"|"+input.dossierVersion()+"|"+input.methodReviewVersion()+
                "|"+contentSha);
            Object[] previous=first(c,"""
                SELECT project_id,candidate_id,fingerprint
                FROM app_open_e8_energy_candidate_command
                WHERE actor_id=? AND command_key=?
                """,r->new Object[]{r.getString(1),r.getObject(2,UUID.class),r.getBytes(3)},
                ctx.actor().id(),command);
            if(previous!=null) {
                if(!project.equals(previous[0]) ||
                    !MessageDigest.isEqual(fingerprint,(byte[])previous[2]))
                    throw conflict("A chave de idempotência foi usada para outro pacote.");
                return view(c,project,load(c,project,(UUID)previous[1]));
            }
            Stored existing=first(c,STORED_SQL+"""
                WHERE project_id=? AND dossier_code=? AND dossier_version=?
                  AND method_review_version=? AND source_sha256=?
                """,OpenE8EnergyCandidates::stored,project,code,target.version(),
                input.methodReviewVersion(),HexFormat.of().parseHex(contentSha));
            Stored record=existing;
            if(record==null) {
                UUID id=UUID.randomUUID();
                update(c,"""
                    INSERT INTO app_open_e8_energy_candidate
                    (id,project_id,organization_id,environment,dossier_code,
                     dossier_version,method_review_version,snapshot_id,
                     source_sha256,ed_kwh,ec_kwh,hour_count,slot_count,created_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,project,ctx.organization(),ctx.environment(),code,
                    target.version(),input.methodReviewVersion(),target.snapshot(),
                    HexFormat.of().parseHex(contentSha),target.ed(),target.ec(),
                    hourCount,slots.size(),ctx.actor().id());
                for(Hour slot:slots) {
                    update(c,"""
                        INSERT INTO app_open_e8_energy_candidate_hour
                        (project_id,candidate_id,charger_code,hour_start,
                         ed_kwh,ec_kwh,ed_batch_id,ed_source_line,ed_review_version,
                         ec_batch_id,ec_source_line,ec_review_version)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """,project,id,slot.chargerCode(),slot.from(),
                        new BigDecimal(slot.edKwh()),new BigDecimal(slot.ecKwh()),
                        slot.edBatchId(),slot.edLine(),slot.edReview(),
                        slot.ecBatchId(),slot.ecLine(),slot.ecReview());
                }
                record=load(c,project,id);
            }
            update(c,"""
                INSERT INTO app_open_e8_energy_candidate_command
                (actor_id,command_key,project_id,candidate_id,fingerprint)
                VALUES (?,?,?,?,?)
                """,ctx.actor().id(),command,project,record.id(),fingerprint);
            return view(c,project,record);
        });
    }

    static String state(Connection c,String project,String period) throws SQLException {
        Stored last=first(c,STORED_SQL+"""
            WHERE project_id=? AND dossier_code=?
            ORDER BY created_at DESC,id DESC LIMIT 1
            """,OpenE8EnergyCandidates::stored,project,period);
        return last==null?"CANDIDATE_MISSING":view(c,project,last).state();
    }

    private static Candidate view(Connection c,String project,Stored item) throws SQLException {
        var gates=new ArrayList<String>();
        boolean stale=false;
        Target current=target(c,project,item.period());
        if(current==null || current.version()!=item.dossierVersion() ||
            !current.snapshot().equals(item.snapshotId())) {
            stale=true;gates.add("MR_VERSION_CHANGED");
        }
        OpenMethodReview.View method=current==null?null:
            OpenMethodReview.diagnose(c,project,item.period());
        if(method==null || !"CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION".equals(method.state()) ||
            method.review()==null || method.review().version()!=item.methodReviewVersion()) {
            stale=true;gates.add("METHOD_REVIEW_CHANGED");
        }
        if(current!=null) {
            OpenPeriodSupport.Readiness support=OpenPeriodSupport.diagnose(c,project,item.snapshotId());
            if(support.stale() || !support.documentaryComplete()) {
                stale=true;gates.add("SNAPSHOT_OR_SUPPORT_STALE");
            }
            if(!stale) {
                // Detect even subtle edits to the source review and bindings.
                var qa=OpenMeasurementReviewQa.evaluate(c,project,current.from(),current.until());
                if(!qa.readyForInternalSnapshot() ||
                    !current.snapshotSha().equals(OpenMeasurementReviewQa.hash(
                        project,current.from(),current.until(),qa.accepted(),
                        OpenMonitoringEvents.impacting(c,project,current.from(),current.until())))) {
                    stale=true;gates.add("SOURCE_REVISIONS_CHANGED");
                } else {
                    List<Row> actual=rows(c,project,item.snapshotId());
                    List<Hour> candidate=reconcile(current,actual);
                    if(!item.sha().equals(signature(project,item.period(),current,
                            method,actual,candidate))) {
                        stale=true;gates.add("CANDIDATE_LINEAGE_CHANGED");
                    }
                }
            }
        }
        return new Candidate(item.id(),item.projectId(),item.period(),
            item.dossierVersion(),item.methodReviewVersion(),item.snapshotId(),
            item.sha(),item.ed(),item.ec(),item.hours(),item.slots(),
            stale?"STALE_NOT_ADMITTED":"NOT_ADMITTED",stale,false,item.createdAt(),
            List.copyOf(gates),hours(c,project,item.id()));
    }

    private static List<Hour> hours(Connection c,String project,UUID id) throws SQLException {
        return query(c,"""
            SELECT charger_code,hour_start,ed_kwh,ec_kwh,ed_batch_id,ed_source_line,
                   ed_review_version,ec_batch_id,ec_source_line,ec_review_version
            FROM app_open_e8_energy_candidate_hour
            WHERE project_id=? AND candidate_id=?
            ORDER BY hour_start,charger_code
            """,r->new Hour(r.getString("charger_code"),
                r.getObject("hour_start",OffsetDateTime.class),
                r.getObject("hour_start",OffsetDateTime.class).plusHours(1),
                r.getBigDecimal("ed_kwh").toPlainString(),
                r.getBigDecimal("ec_kwh").toPlainString(),
                r.getObject("ed_batch_id",UUID.class),r.getInt("ed_source_line"),
                r.getInt("ed_review_version"),r.getObject("ec_batch_id",UUID.class),
                r.getInt("ec_source_line"),r.getInt("ec_review_version")),project,id);
    }

    private static List<Hour> reconcile(Target period,List<Row> rows) {
        if(rows.isEmpty())throw conflict("O snapshot não contém linhas admitidas no QA.");
        Map<String,Pair> paired=new LinkedHashMap<>();
        for(Row row:rows) {
            if(row.from()==null || row.until()==null ||
                row.from().isBefore(period.from()) || row.until().isAfter(period.until()) ||
                !Duration.between(row.from(),row.until()).equals(Duration.ofHours(1)) ||
                row.from().toInstant().getEpochSecond()%3600!=0 ||
                row.kwh()==null || row.kwh().signum()<=0)
                throw conflict("Há leitura com hora ou quantidade incompatível.");
            String key=row.charger()+"|"+row.from().toInstant();
            paired.computeIfAbsent(key,k->new Pair()).add(row);
        }
        var slots=new ArrayList<Hour>();
        BigDecimal totalEd=BigDecimal.ZERO,totalEc=BigDecimal.ZERO;
        for(var entry:paired.entrySet()) {
            Pair pair=entry.getValue();
            if(pair.ed==null || pair.ec==null)
                throw conflict("Falta ED ou EC para um carregador/hora.");
            Row ed=pair.ed,ec=pair.ec;
            if(ec.kwh().compareTo(ed.kwh())<0)
                throw conflict("EC menor que ED na hora: rever perdas e fronteira.");
            totalEd=totalEd.add(ed.kwh());totalEc=totalEc.add(ec.kwh());
            slots.add(new Hour(ed.charger(),ed.from(),ed.until(),
                ed.kwh().toPlainString(),ec.kwh().toPlainString(),
                ed.batch(),ed.line(),ed.review(),ec.batch(),ec.line(),ec.review()));
        }
        if(totalEd.compareTo(period.ed())!=0 || totalEc.compareTo(period.ec())!=0)
            throw conflict("Soma ED/EC das horas diverge do snapshot imutável.");
        slots.sort(Comparator.comparing(Hour::from).thenComparing(Hour::chargerCode));
        return List.copyOf(slots);
    }

    private static List<Row> rows(Connection c,String project,UUID snapshot) throws SQLException {
        return query(c,"""
            SELECT r.batch_id,r.source_line,l.review_version,
                   r.meter_code,m.charger_code,r.energy_kind,r.start_at,r.end_at,
                   r.kwh,encode(e.sha256,'hex') AS source_sha,
                   encode(r.source_line_sha256,'hex') AS line_sha
            FROM app_open_period_qa_snapshot_line l
            JOIN app_open_measurement_candidate r
              ON r.project_id=l.project_id AND r.batch_id=l.batch_id
                AND r.source_line=l.source_line
            JOIN app_open_measurement_review q
              ON q.project_id=l.project_id AND q.batch_id=l.batch_id
                AND q.source_line=l.source_line AND q.version=l.review_version
                AND q.outcome='ACCEPTED_FOR_QA'
            JOIN app_open_meter_configuration_revision m
              ON m.project_id=r.project_id AND m.meter_code=r.meter_code
                AND m.version=r.meter_version AND m.energy_kind=r.energy_kind
            JOIN app_open_measurement_batch b
              ON b.project_id=r.project_id AND b.id=r.batch_id
            JOIN app_project_evidence_blob e
              ON e.project_id=b.project_id AND e.id=b.evidence_blob_id
            WHERE l.project_id=? AND l.snapshot_id=?
            ORDER BY r.start_at,m.charger_code,r.energy_kind,r.batch_id,r.source_line
            """,r->new Row(r.getObject("batch_id",UUID.class),r.getInt("source_line"),
                r.getInt("review_version"),r.getString("meter_code"),
                r.getString("charger_code"),r.getString("energy_kind"),
                r.getObject("start_at",OffsetDateTime.class),
                r.getObject("end_at",OffsetDateTime.class),r.getBigDecimal("kwh"),
                r.getString("source_sha"),r.getString("line_sha")),project,snapshot);
    }

    private static String signature(String project,String code,Target period,
                                    OpenMethodReview.View method,List<Row> rows,
                                    List<Hour> slots) {
        StringBuilder canonical=new StringBuilder("CF-OPEN-E8-ENERGY-CANDIDATE-V1")
            .append('|').append(project).append('|').append(code)
            .append('|').append(period.version()).append('|').append(period.snapshot())
            .append('|').append(period.snapshotSha())
            .append('|').append(method.methodology()).append(':').append(method.methodologyEdition())
            .append('|').append(method.review().version())
            .append('|').append(method.review().evidenceSha256());
        for(Row r:rows)canonical.append("|source:").append(r.batch()).append(':')
            .append(r.line()).append(':').append(r.review())
            .append(':').append(r.meter()).append(':').append(r.charger())
            .append(':').append(r.kind()).append(':').append(r.from().toInstant())
            .append(':').append(r.until().toInstant()).append(':')
            .append(r.kwh().toPlainString()).append(':').append(r.sourceSha())
            .append(':').append(r.lineSha());
        for(Hour slot:slots)canonical.append("|hour:").append(slot.chargerCode())
            .append(':').append(slot.from().toInstant())
            .append(':').append(slot.edKwh()).append(':').append(slot.ecKwh());
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException problem){throw new IllegalStateException(problem);}
    }

    private static Target target(Connection c,String project,String code) throws SQLException {
        return first(c,"""
            SELECT d.version,d.snapshot_id,d.authored_by,
                encode(d.source_sha256,'hex') AS source_sha,
                q.period_start,q.period_end,q.ed_kwh,q.ec_kwh
            FROM app_open_mr_dossier_revision d
            JOIN app_open_period_qa_snapshot q
              ON q.project_id=d.project_id AND q.id=d.snapshot_id
            WHERE d.project_id=? AND d.dossier_code=?
            ORDER BY d.version DESC LIMIT 1
            """,r->new Target(r.getInt("version"),r.getObject("snapshot_id",UUID.class),
                r.getObject("authored_by",UUID.class),r.getString("source_sha"),
                r.getObject("period_start",OffsetDateTime.class),
                r.getObject("period_end",OffsetDateTime.class),
                r.getBigDecimal("ed_kwh"),r.getBigDecimal("ec_kwh"),null),project,code);
    }

    private static final String STORED_SQL="""
        SELECT id,project_id,dossier_code,dossier_version,method_review_version,
          snapshot_id,encode(source_sha256,'hex') AS sha,ed_kwh,ec_kwh,
          hour_count,slot_count,created_at FROM app_open_e8_energy_candidate
        """;
    private static Stored stored(java.sql.ResultSet r) throws SQLException {
        return new Stored(r.getObject("id",UUID.class),r.getString("project_id"),
            r.getString("dossier_code"),r.getInt("dossier_version"),
            r.getInt("method_review_version"),r.getObject("snapshot_id",UUID.class),
            r.getString("sha"),r.getBigDecimal("ed_kwh").toPlainString(),
            r.getBigDecimal("ec_kwh").toPlainString(),
            r.getInt("hour_count"),r.getInt("slot_count"),
            r.getObject("created_at",OffsetDateTime.class));
    }
    private static Stored load(Connection c,String project,UUID id) throws SQLException {
        Stored record=first(c,STORED_SQL+"""
            WHERE project_id=? AND id=?
            """,OpenE8EnergyCandidates::stored,project,id);
        if(record==null)throw unavailable();
        return record;
    }
    private static PilotProblem unavailable() {
        return new PilotProblem(404,"PACOTE_ENERGIA_INDISPONIVEL","candidate",RULE,
            "Pacote indisponível.");
    }
    private static PilotProblem conflict(String detail) {
        return new PilotProblem(409,"PACOTE_ENERGIA_BLOQUEADO","candidate",RULE,detail);
    }
    private static PilotProblem invalid(String field,String detail) {
        return PilotProblem.invalid("PACOTE_ENERGIA_INVALIDO",field,RULE,detail);
    }
}
