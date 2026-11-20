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
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Explicit references from a draft MR to QA-only snapshots. A reference
 * propagates STALE to the MR and its dependent PD narratives; it never
 * admits observations, exports an official MR, or unlocks E8.
 */
@ApplicationScoped
class OpenMonitoringDossiers {
    private static final String RULE="CF-OPEN-009";
    private static final Set<String> DEPENDENT_PD_SECTIONS=Set.of("monitoring","quantification");
    private final DynamicProjectScope scope;

    record Input(UUID snapshotId,String note) {}
    record Reference(String code,int version,UUID snapshotId,String snapshotSha256,
                     String note,UUID authoredBy,OffsetDateTime createdAt) {}
    record Gate(String code,String detail) {}
    record Dossier(Reference reference,String state,boolean stale,
                   boolean canUseForE8,List<Gate> gates) {}
    record Manifest(String sha256,List<Dossier> dossiers) {}
    record PdDependency(String state,boolean outdated,boolean canApproveInternally,
                        String pinnedManifestSha256,String currentManifestSha256,
                        List<Gate> gates) {}

    OpenMonitoringDossiers(DynamicProjectScope scope) {this.scope=scope;}

    List<Dossier> list(String cookie,String project) {
        return scope.read(cookie,project,"VIEW_OPEN_MONITORING",(c,ctx)->manifest(c,project).dossiers());
    }

    Dossier bind(String cookie,String project,String code,String match,Input submitted) {
        return scope.write(cookie,project,"SAVE_OPEN_MONITORING",(c,ctx)->{
            if(ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
                throw unavailable();
            String period=code(code);
            if(submitted==null || submitted.snapshotId()==null)
                throw PilotProblem.invalid("MR_SNAPSHOT_INVALIDO","snapshot",RULE,
                    "Selecione um snapshot de QA que pertença ao projeto.");
            String note=normalizeNote(submitted.note());
            int expected=PilotCommands.expected(match);
            Reference previous=first(c,"""
                SELECT dossier_code,version,snapshot_id,encode(source_sha256,'hex') AS sha,
                       note,authored_by,created_at
                FROM app_open_mr_dossier_revision
                WHERE project_id=? AND dossier_code=?
                ORDER BY version DESC LIMIT 1
                """,OpenMonitoringDossiers::reference,project,period);
            if(previous!=null && previous.version()==expected+1 &&
               previous.authoredBy().equals(ctx.actor().id()) &&
               previous.snapshotId().equals(submitted.snapshotId()) &&
               previous.note().equals(note))return view(c,project,previous);
            PilotCommands.requireVersion(expected,previous==null?0:previous.version());
            if(expected>=100000000)
                throw PilotProblem.invalid("MR_SNAPSHOT_INVALIDO","version",RULE,
                    "Limite de versões do dossiê atingido.");
            String sha=first(c,"""
                SELECT encode(source_sha256,'hex')
                FROM app_open_period_qa_snapshot
                WHERE project_id=? AND id=? AND status='INTERNAL_QA_ONLY'
                  AND usable_for_e8=false
                """,r->r.getString(1),project,submitted.snapshotId());
            if(sha==null)throw unavailable();
            // Require the referenced QA snapshot to be CURRENT before binding.
            // Its documents may still be pending; that is clearly disclosed
            // in dossier state, and is never treated as normative admission.
            var ready=OpenPeriodSupport.diagnose(c,project,submitted.snapshotId());
            if(ready.stale())
                throw new PilotProblem(409,"SNAPSHOT_OBSOLETO","snapshot",RULE,
                    "Snapshot obsoleto: revise leituras e crie uma nova versão de QA.");
            update(c,"""
                INSERT INTO app_open_mr_dossier_revision
                (project_id,organization_id,environment,dossier_code,version,
                 snapshot_id,source_sha256,note,authored_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,project,ctx.organization(),ctx.environment(),period,expected+1,
                submitted.snapshotId(),HexFormat.of().parseHex(sha),note,ctx.actor().id());
            Reference saved=first(c,"""
                SELECT dossier_code,version,snapshot_id,encode(source_sha256,'hex') AS sha,
                       note,authored_by,created_at
                FROM app_open_mr_dossier_revision
                WHERE project_id=? AND dossier_code=? AND version=?
                """,OpenMonitoringDossiers::reference,project,period,expected+1);
            return view(c,project,saved);
        });
    }

    static Manifest manifest(Connection c,String project) throws SQLException {
        List<Reference> refs=query(c,"""
            SELECT DISTINCT ON (dossier_code)
              dossier_code,version,snapshot_id,encode(source_sha256,'hex') AS sha,
              note,authored_by,created_at
            FROM app_open_mr_dossier_revision WHERE project_id=?
            ORDER BY dossier_code,version DESC
            """,OpenMonitoringDossiers::reference,project);
        if(refs.isEmpty())return new Manifest(null,List.of());
        StringBuilder canonical=new StringBuilder("CF-OPEN-MR-V1|").append(project);
        for(Reference ref:refs)canonical.append('|').append(ref.code()).append(':')
            .append(ref.version()).append(':').append(ref.snapshotId()).append(':')
            .append(ref.snapshotSha256());
        List<Dossier> views=new java.util.ArrayList<>();
        for(Reference ref:refs)views.add(view(c,project,ref));
        return new Manifest(sha(canonical.toString()),List.copyOf(views));
    }

    static PdDependency pdDependency(Connection c,String project,String section,int version)
        throws SQLException {
        if(!DEPENDENT_PD_SECTIONS.contains(section))return null;
        Manifest current=manifest(c,project);
        String pinned=pinnedPd(c,project,section,version);
        if(current.dossiers().isEmpty() && pinned==null)return null;
        var gates=new java.util.ArrayList<Gate>();
        boolean changed=!java.util.Objects.equals(current.sha256(),pinned);
        if(changed)gates.add(new Gate("PD_MONITORING_INPUT_CHANGED",
            "Os vínculos de MR mudaram desde a redação desta versão do PD. Gere uma nova versão."));
        for(Dossier item:current.dossiers()) {
            if(!"READY_FOR_NORMATIVE_REVIEW".equals(item.state()))
                gates.add(new Gate("MR_"+item.state(),
                    "Período "+item.reference().code()+" requer correção: "+
                    item.gates().stream().map(Gate::detail).findFirst().orElse("revisar dados e documentos")));
        }
        // The quantification section may not progress on the strength of
        // documentary QA alone; a distinct, independent VM0038 checklist
        // is required. The monitoring plan (PD) remains preparable without
        // treating this as external certification.
        if("quantification".equals(section)) {
            for(Dossier item:current.dossiers()) {
                String methodState=OpenMethodReview.status(c,project,item.reference().code());
                if(!"CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION".equals(methodState))
                    gates.add(new Gate("METHOD_"+methodState,
                        "Período "+item.reference().code()+
                        " requer avaliação interna independente da VM0038 v1.1."));
            }
        }
        boolean blocked=!gates.isEmpty();
        return new PdDependency(changed?"STALE":blocked?"BLOCKED_MONITORING_REQUIREMENTS":
            "READY_FOR_INTERNAL_PD_REVIEW",blocked,!blocked,pinned,current.sha256(),List.copyOf(gates));
    }

    static String pinnedPd(Connection c,String project,String section,int version)
        throws SQLException {
        if(!DEPENDENT_PD_SECTIONS.contains(section) || version<=0)return null;
        return first(c,"""
            SELECT manifest_sha256 FROM app_pd_monitoring_dependency
            WHERE project_id=? AND section_id=? AND section_version=?
            """,r->r.getString(1),project,section,version);
    }

    static void pinPd(Connection c,String project,String section,int version)
        throws SQLException {
        if(!DEPENDENT_PD_SECTIONS.contains(section))return;
        Manifest current=manifest(c,project);
        if(current.sha256()==null)return;
        update(c,"""
            INSERT INTO app_pd_monitoring_dependency
              (project_id,section_id,section_version,manifest_sha256)
            VALUES(?,?,?,?)
            """,project,section,version,current.sha256());
    }

    private static Dossier view(Connection c,String project,Reference reference) throws SQLException {
        var readiness=OpenPeriodSupport.diagnose(c,project,reference.snapshotId());
        String state=readiness.stale()?"STALE":
            readiness.documentaryComplete()?"READY_FOR_NORMATIVE_REVIEW":"PENDING_REQUIREMENTS";
        var gates=new java.util.ArrayList<Gate>();
        for(OpenPeriodSupport.Gate gate:readiness.gates())
            gates.add(new Gate(gate.code(),gate.detail()));
        return new Dossier(reference,state,readiness.stale(),false,List.copyOf(gates));
    }

    private static Reference reference(java.sql.ResultSet row) throws SQLException {
        return new Reference(row.getString("dossier_code"),row.getInt("version"),
            row.getObject("snapshot_id",UUID.class),row.getString("sha"),
            row.getString("note"),row.getObject("authored_by",UUID.class),
            row.getObject("created_at",OffsetDateTime.class));
    }

    static String code(String code) {
        if(code==null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))
            throw PilotProblem.invalid("MR_PERIODO_INVALIDO","dossier",RULE,
                "Informe um código de período, por exemplo M1 ou M2.");
        return code;
    }
    private static String normalizeNote(String note) {
        if(note==null || note.strip().length()<15 || note.strip().length()>2000 ||
            note.codePoints().anyMatch(Character::isISOControl))
            throw PilotProblem.invalid("MR_NOTA_INVALIDA","note",RULE,
                "Informe uma fundamentação entre 15 e 2000 caracteres.");
        return note.strip();
    }
    private static String sha(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException problem){throw new IllegalStateException(problem);}
    }
    private static PilotProblem unavailable() {
        return new PilotProblem(404,"MR_NAO_VISIVEL","dossier",RULE,"Recurso indisponível.");
    }
}
