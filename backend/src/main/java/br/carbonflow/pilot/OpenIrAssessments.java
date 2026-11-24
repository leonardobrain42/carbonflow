package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.Optional;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/** Staff records a preparatory IR assessment from current project-owned references.
 * No client-supplied year, hash, factor value or reviewer identity is trusted.
 */
@ApplicationScoped
class OpenIrAssessments {
    record Input(String period, String frequency, String basis, String rationale) {}
    record ReviewInput(String assessmentSha256, String outcome, String annualUpdateBasis,
                       String evidenceLocator, String reason, UUID priorAssessmentId) {}
    record Review(UUID reviewerId, String outcome, String assessmentSha256, String ruleReference,
                  String annualUpdateBasis, String evidenceLocator, String reason, OffsetDateTime reviewedAt) {}
    record AnnualEvidence(UUID priorAssessmentId, String evidenceSha256, String state, String policy) {}
    record View(UUID id, String projectId, int projectYear, int sourceYear, int version,
                String period, UUID fleetSelectionId, UUID fuelPinId, String frequency,
                String basis, String rationale, String state, String assessmentSha256,
                String candidate, boolean stale, boolean usableForE8, OffsetDateTime recordedAt, Review review, AnnualEvidence annualEvidence) {}
    record Result(View view, boolean created) {}
    private record Fleet(UUID id, UUID binding, int version, int year, String sha) {}
    private record Refs(Fleet fleet, OpenE8ProjectRuns.InputReference fuel, OpenE8Timing.Context timing,
                        int triage) {}
    private record Stored(UUID id, String project, int year, int sourceYear, int version,
                          int triage, String period, UUID fleet, UUID fuel, String timingSha,
                          String fleetSha, String fuelSha, String frequency, String basis,
                          String rationale, String state, String sha, String candidate,
                          OffsetDateTime at, String commandSha) {}

    private final PilotDatabase database;
    private final PlatformSourceWork managers;
    private final DynamicProjectScope scope;
    private final PlatformSourceReadiness readiness;
    private final Set<UUID> reviewers;

    OpenIrAssessments(PilotDatabase database, PlatformSourceWork managers, DynamicProjectScope scope,
                      PlatformSourceReadiness readiness,
                      @ConfigProperty(name="carbonflow.platform.source-technical.reviewers") Optional<String> roster) {
        this.database=database; this.managers=managers; this.scope=scope; this.readiness=readiness;
        reviewers=roster.map(value->Arrays.stream(value.split(",")).map(String::strip)
            .filter(v->!v.isBlank()).map(UUID::fromString).collect(Collectors.toUnmodifiableSet()))
            .orElse(Set.of());
    }

    private ComplianceStaff.Staff staff(Connection c,String token) throws SQLException {
        var actor=readiness.requireViewer(c,token);
        if(!managers.canManage(actor.id()) && !reviewers.contains(actor.id())) throw hidden();
        return actor;
    }

    Result record(String token, String project, String key, String match, Input input) {
        return database.transaction(c -> {
            var actor=managers.requireManager(c,token);
            String command=PilotCommands.key(key);
            int expected=PilotCommands.expected(match);
            first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->true,
                "OPEN-IR:"+actor.id()+":"+command);
            UUID org=first(c,"""
                SELECT organization_id FROM app_project
                WHERE id=? AND environment='SANDBOX' AND state='DRAFT' FOR UPDATE
                """,r->r.getObject(1,UUID.class),project);
            if(org==null || !actor.organizations().contains(org)) throw hidden();
            if(input==null) throw invalid("Informe período, frequência, abordagem e justificativa.");
            String period=OpenMonitoringDossiers.code(input.period());
            OpenIrAssessment.Frequency frequency;
            OpenIrAssessment.Basis basis;
            String rationale;
            try {
                frequency=OpenIrAssessment.Frequency.valueOf(Objects.toString(input.frequency(),""));
                basis=OpenIrAssessment.Basis.valueOf(Objects.toString(input.basis(),""));
                rationale=OpenDyAssessmentRules.text(input.rationale(),15,2500);
            } catch(IllegalArgumentException e) { throw invalid("Frequência, abordagem ou justificativa inválida."); }
            String commandSha=digest("OPEN-IR-COMMAND@1",project,period,expected,frequency,basis,rationale);
            Stored replay=first(c,SELECT+" WHERE author_id=? AND command_key=?",
                OpenIrAssessments::stored,actor.id(),command);
            if(replay!=null) {
                if(!project.equals(replay.project()) || !commandSha.equals(replay.commandSha()))
                    throw conflict("Chave já utilizada para outra avaliação.");
                return new Result(view(c,replay,true),false);
            }

            Refs refs=references(c,project,period);
            if(refs==null) throw conflict("Resolva o ramo, calendário, frota/ratings e fator fóssil vigente.");
            int year=refs.timing().year().projectYear(), source=refs.timing().year().sourceYear();
            Integer latest=first(c,"""
                SELECT coalesce(max(version),0) FROM app_open_ir_assessment
                WHERE project_id=? AND project_year=? AND source_year=?
                """,r->r.getInt(1),project,year,source);
            PilotCommands.requireVersion(expected,latest);
            if(expected>=100000000) throw conflict("Limite de versões atingido.");
            var decision=OpenIrAssessment.assess(new OpenIrAssessment.Input(project,
                OpenIrAssessment.BRANCH,year,source,frequency,basis,actor.id(),evidence(refs)),null);
            if(decision.status()!=OpenIrAssessment.Status.PENDING_METHOD &&
               decision.status()!=OpenIrAssessment.Status.PENDING_REVIEW)
                throw conflict("Referências não satisfazem o contrato preparatório.");
            String sha=digest("OPEN-IR-STORED@1",commandSha,actor.id(),refs.triage(),
                refs.timing().fingerprint(),refs.fleet().id(),refs.fleet().sha(),
                refs.fuel().id(),refs.fuel().sha256(),decision.assessmentSha256(),decision.status());
            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_ir_assessment
                (id,project_id,organization_id,environment,project_year,source_year,version,triage_version,
                 period_code,fleet_selection_id,fuel_pin_id,timing_sha256,fleet_sha256,fuel_sha256,
                 frequency,basis,rationale,policy,kernel_sha256,assessment_sha256,state,candidate,
                 author_id,command_key,command_sha256)
                VALUES (?,?,?,'SANDBOX',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,project,org,year,source,expected+1,refs.triage(),period,refs.fleet().id(),
                refs.fuel().id(),bytes(refs.timing().fingerprint()),bytes(refs.fleet().sha()),
                bytes(refs.fuel().sha256()),frequency.name(),basis.name(),rationale,decision.policy(),
                bytes(decision.assessmentSha256()),bytes(sha),decision.status().name(),decision.candidate(),
                actor.id(),command,bytes(commandSha));
            return new Result(view(c,first(c,SELECT+" WHERE id=?",OpenIrAssessments::stored,id),true),true);
        });
    }

    List<View> history(String token,String project,int year,int sourceYear) {
        return scope.read(token,project,"VIEW_OPEN_PARAMETER_BINDING",(c,ctx)->
            historyRows(c,project,year,sourceYear,false));
    }

    List<View> staffHistory(String token,String project,int year,int sourceYear) {
        return database.transaction(c -> {
            var actor=staff(c,token);
            UUID org=first(c,"""
                SELECT organization_id FROM app_project
                WHERE id=? AND environment='SANDBOX' AND state='DRAFT' FOR SHARE
                """,r->r.getObject(1,UUID.class),project);
            if(org==null || !actor.organizations().contains(org)) throw hidden();
            return historyRows(c,project,year,sourceYear,true);
        });
    }

    Result review(String token,String project,UUID assessment,String key,String match,ReviewInput input) {
        return database.transaction(c->{
            var actor=staff(c,token);
            if(!reviewers.contains(actor.id())) throw hidden();
            String command=PilotCommands.key(key);
            int expected=PilotCommands.expected(match);
            first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->true,
                "OPEN-IR-REVIEW:"+actor.id()+":"+command);
            UUID org=first(c,"""
                SELECT organization_id FROM app_project
                WHERE id=? AND environment='SANDBOX' AND state='DRAFT' FOR UPDATE
                """,r->r.getObject(1,UUID.class),project);
            if(org==null || !actor.organizations().contains(org)) throw hidden();
            Stored row=first(c,SELECT+" WHERE project_id=? AND id=?",OpenIrAssessments::stored,project,assessment);
            if(row==null) throw hidden();
            UUID author=first(c,"SELECT author_id FROM app_open_ir_assessment WHERE id=?",
                r->r.getObject(1,UUID.class),assessment);
            if(actor.id().equals(author)) throw hidden();
            if(input==null || !row.sha().equals(input.assessmentSha256()))
                throw invalid("Informe o hash exato da avaliação.");
            String locator,reason;
            try {
                OpenIrReviewRules.validate(input.outcome(),input.annualUpdateBasis(),row.state());
                locator=OpenDyAssessmentRules.text(input.evidenceLocator(),3,2000);
                reason=OpenDyAssessmentRules.text(input.reason(),15,2500);
            } catch(IllegalArgumentException e) { throw invalid(e.getMessage()); }
            String fingerprint=digest("OPEN-IR-REVIEW@1",project,assessment,row.sha(),expected,
                input.outcome(),input.annualUpdateBasis(),locator,reason);
            if(input.priorAssessmentId()!=null)
                fingerprint=digest("OPEN-IR-REVIEW-ANNUAL@1",fingerprint,input.priorAssessmentId());
            Object[] replay=first(c,"""
                SELECT assessment_id,encode(command_sha256,'hex') FROM app_open_ir_review
                WHERE reviewer_id=? AND command_key=?
                """,r->new Object[]{r.getObject(1,UUID.class),r.getString(2)},actor.id(),command);
            if(replay!=null) {
                if(!assessment.equals(replay[0]) || !fingerprint.equals(replay[1]))
                    throw conflict("Esta chave já identifica outro parecer.");
                return new Result(view(c,row,true),false);
            }
            Integer latest=first(c,"""
                SELECT max(version) FROM app_open_ir_assessment
                WHERE project_id=? AND project_year=? AND source_year=?
                """,r->r.getInt(1),project,row.year(),row.sourceYear());
            PilotCommands.requireVersion(expected,latest);
            if(view(c,row,true).stale()) throw conflict("Avaliação desatualizada. Registre nova versão.");
            if(readReview(c,assessment,true)!=null) throw conflict("Já existe parecer nesta versão.");
            OpenIrAnnualEvidence.Proof proof=null;
            if("CHECKED_INTERNAL".equals(input.outcome())) {
                if(input.priorAssessmentId()==null)
                    throw invalid("Selecione a avaliação canônica do ano anterior.");
                Stored previous=first(c,SELECT+" WHERE project_id=? AND id=?",
                    OpenIrAssessments::stored,project,input.priorAssessmentId());
                if(previous==null) throw hidden();
                try {
                    proof=OpenIrAnnualEvidence.capture(annualSnapshot(c,previous),annualSnapshot(c,row));
                } catch(IllegalArgumentException e) { throw invalid(e.getMessage()); }
            } else if(input.priorAssessmentId()!=null) {
                throw invalid("Vínculo de atualização observada é reservado à checagem interna.");
            }
            update(c,"""
                INSERT INTO app_open_ir_review
                (assessment_id,assessment_sha256,author_id,reviewer_id,outcome,rule_reference,
                 annual_update_basis,evidence_locator,reason,command_key,command_sha256)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,assessment,bytes(row.sha()),author,actor.id(),input.outcome(),
                OpenIrAssessment.RULE_REFERENCE,input.annualUpdateBasis(),locator,reason,
                command,bytes(fingerprint));
            if(proof!=null) {
                var prior=proof.previous();
                update(c,"""
                    INSERT INTO app_open_ir_annual_evidence
                    (assessment_id,prior_assessment_id,project_id,project_year,source_year,
                     prior_project_year,prior_source_year,assessment_sha256,prior_assessment_sha256,
                     evidence_sha256,policy)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """,row.id(),prior.id(),project,row.year(),row.sourceYear(),prior.projectYear(),
                    prior.sourceYear(),bytes(row.sha()),bytes(prior.assessmentSha()),bytes(proof.digest()),
                    OpenIrAnnualEvidence.POLICY);
            }
            return new Result(view(c,row,true),true);
        });
    }

    private static Review readReview(Connection c,UUID id,boolean staff) throws SQLException {
        return first(c,"""
            SELECT reviewer_id,outcome,encode(assessment_sha256,'hex'),rule_reference,
                annual_update_basis,evidence_locator,reason,reviewed_at
            FROM app_open_ir_review WHERE assessment_id=?
            """,r->new Review(staff?r.getObject(1,UUID.class):null,r.getString(2),r.getString(3),
                r.getString(4),r.getString(5),staff?r.getString(6):null,staff?r.getString(7):null,
                r.getObject(8,OffsetDateTime.class)),id);
    }

    private static List<View> historyRows(Connection c,String project,int year,int sourceYear,boolean staff)
            throws SQLException {
        if(year<1 || year>100 || sourceYear<1900 || sourceYear>2100)
            throw invalid("Anos fora do contrato.");
        var result=new ArrayList<View>();
        for(var row:query(c,SELECT+"""
            WHERE project_id=? AND project_year=? AND source_year=?
            ORDER BY version DESC LIMIT 50
            """,OpenIrAssessments::stored,project,year,sourceYear))
            result.add(view(c,row,staff));
        return List.copyOf(result);
    }

    /** Reuses one fleet handoff for the three metrics it contains. */
    private static List<OpenIrAssessment.Evidence> evidence(Refs r) {
        var out=new ArrayList<OpenIrAssessment.Evidence>();
        for(var metric:List.of(OpenIrAssessment.Parameter.EVR,OpenIrAssessment.Parameter.AFEC,
                OpenIrAssessment.Parameter.MPG))
            out.add(new OpenIrAssessment.Evidence(metric,r.fleet().id(),r.fleet().version(),
                r.fleet().sha(),r.fleet().year()));
        out.add(new OpenIrAssessment.Evidence(OpenIrAssessment.Parameter.EF_FUEL,r.fuel().id(),
            r.fuel().version(),r.fuel().sha256(),r.fleet().year()));
        return List.copyOf(out);
    }

    private static Refs references(Connection c,String project,String period) throws SQLException {
        if(ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
            return null;
        var applicability=OpenApplicability.currentReadiness(c,project);
        if(applicability.stale() || !"OPEN_LDV_DCFC_SIN_BR_CANDIDATE".equals(applicability.category())
                || !"OPEN_CANDIDATE".equals(applicability.network())) return null;
        String vehicle=first(c,"""
            SELECT answers #>> '{3,vehicleClass}' FROM app_project_triage_revision
            WHERE project_id=? ORDER BY version DESC LIMIT 1
            """,r->r.getString(1),project);
        String country=first(c,"""
            SELECT payload->>'country' FROM app_project_intake_draft_revision
            WHERE project_id=? ORDER BY version DESC LIMIT 1
            """,r->r.getString(1),project);
        if(!"LDV".equals(vehicle) || !"BR".equals(country)) return null;
        if(!"PREPARATORY_NOT_ADMITTED".equals(OpenE8FleetSelections.currentState(c,project,period)))
            return null;
        Fleet fleet=first(c,"""
            SELECT id,energy_binding_id,version,observation_year,encode(selection_sha256,'hex')
            FROM app_open_e8_fleet_selection WHERE project_id=? AND period_code=?
            ORDER BY version DESC LIMIT 1
            """,r->new Fleet(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),
                r.getInt(4),r.getString(5)),project,period);
        if(fleet==null) return null;
        var timing=OpenE8Timing.resolve(c,project,fleet.binding());
        if(timing.year().gate()!=null || timing.year().projectYear()==null ||
           timing.year().sourceYear()==null || fleet.year()!=timing.year().sourceYear()) return null;
        var fuel=OpenE8ProjectRuns.sourcePinReference(c,project,"EF_FUEL",fleet.year());
        if(!"NOT_ADMITTED".equals(fuel.state()) || fuel.id()==null) return null;
        return new Refs(fleet,fuel,timing,OpenCalculationReadiness.triageRevision(c,project));
    }

    private static boolean stale(Connection c,Stored row) throws SQLException {
        Refs current=references(c,row.project(),row.period());
        Integer latest=first(c,"""
            SELECT max(version) FROM app_open_ir_assessment
            WHERE project_id=? AND project_year=? AND source_year=?
            """,r->r.getInt(1),row.project(),row.year(),row.sourceYear());
        return current==null || row.version()!=latest || row.triage()!=current.triage()
            || !row.timingSha().equals(current.timing().fingerprint())
            || !row.fleet().equals(current.fleet().id()) || !row.fleetSha().equals(current.fleet().sha())
            || !row.fuel().equals(current.fuel().id()) || !row.fuelSha().equals(current.fuel().sha256());
    }

    private static OpenIrAnnualEvidence.Snapshot annualSnapshot(Connection c,Stored row) throws SQLException {
        Review opinion=readReview(c,row.id(),false);
        boolean current=!stale(c,row) && (opinion==null || "CHECKED_INTERNAL".equals(opinion.outcome()));
        return first(c,"""
            SELECT f.fleet_snapshot_id,f.rating_snapshot_id,b.candidate_id,f.cycle
            FROM app_open_e8_fleet_selection f
            JOIN app_open_project_parameter_binding b ON b.project_id=f.project_id
            WHERE f.project_id=? AND f.id=? AND b.id=? AND b.parameter_kind='EF_FUEL'
                AND f.observation_year=? AND b.vintage_year=?
            """,r->new OpenIrAnnualEvidence.Snapshot(row.id(),row.project(),row.year(),row.sourceYear(),
                row.sha(),r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                r.getString(4),row.at(),current,"PENDING_REVIEW".equals(row.state())),
            row.project(),row.fleet(),row.fuel(),row.sourceYear(),row.sourceYear());
    }

    private static AnnualEvidence annualEvidence(Connection c,Stored row,Review review) throws SQLException {
        if(review==null || !"CHECKED_INTERNAL".equals(review.outcome())) return null;
        AnnualEvidence pin=first(c,"""
            SELECT prior_assessment_id,encode(evidence_sha256,'hex'),policy
            FROM app_open_ir_annual_evidence WHERE assessment_id=?
            """,r->new AnnualEvidence(r.getObject(1,UUID.class),r.getString(2),
                "CONSECUTIVE_REFERENCES_NOT_ADMITTED",r.getString(3)),row.id());
        if(pin==null) return new AnnualEvidence(null,null,"LEGACY_EVIDENCE_MISSING",OpenIrAnnualEvidence.POLICY);
        Stored prior=first(c,SELECT+" WHERE project_id=? AND id=?",OpenIrAssessments::stored,
            row.project(),pin.priorAssessmentId());
        try {
            if(prior!=null && pin.evidenceSha256().equals(
                OpenIrAnnualEvidence.capture(annualSnapshot(c,prior),annualSnapshot(c,row)).digest()))
                return pin;
        } catch(IllegalArgumentException e) { /* immutable historical proof requires recheck */ }
        return new AnnualEvidence(pin.priorAssessmentId(),pin.evidenceSha256(),
            "STALE_EVIDENCE_NOT_ADMITTED",pin.policy());
    }

    private static View view(Connection c,Stored row,boolean staff) throws SQLException {
        boolean stale=stale(c,row);
        Review review=readReview(c,row.id(),staff);
        AnnualEvidence annual=annualEvidence(c,row,review);
        boolean evidenceMissing=annual!=null && !"CONSECUTIVE_REFERENCES_NOT_ADMITTED".equals(annual.state());
        String state=stale?"STALE_NOT_ADMITTED":evidenceMissing?"ANNUAL_EVIDENCE_RECHECK_REQUIRED":
            review==null?row.state():review.outcome()+"_NOT_ADMITTED";
        return new View(row.id(),row.project(),row.year(),row.sourceYear(),row.version(),row.period(),
            row.fleet(),row.fuel(),row.frequency(),row.basis(),staff?row.rationale():null,
            state,row.sha(),stale || evidenceMissing || (review!=null && !"CHECKED_INTERNAL".equals(review.outcome()))?
                null:row.candidate(),stale || (annual!=null && "STALE_EVIDENCE_NOT_ADMITTED".equals(annual.state())),
            false,row.at(),review,annual);
    }

    static String digest(Object... parts) {
        var b=new StringBuilder();
        for(Object part:parts) {
            if(part==null) b.append("-1:");
            else {String s=part.toString();b.append(s.length()).append(':').append(s);}
        }
        return HexFormat.of().formatHex(AccessSecrets.digest(b.toString()));
    }
    private static byte[] bytes(String hex) { return hex==null?null:HexFormat.of().parseHex(hex); }

    private static final String SELECT="""
        SELECT id,project_id,project_year,source_year,version,triage_version,period_code,
            fleet_selection_id,fuel_pin_id,encode(timing_sha256,'hex'),encode(fleet_sha256,'hex'),
            encode(fuel_sha256,'hex'),frequency,basis,rationale,state,encode(assessment_sha256,'hex'),
            candidate::text,recorded_at,encode(command_sha256,'hex') FROM app_open_ir_assessment
        """;
    private static Stored stored(ResultSet r) throws SQLException {
        return new Stored(r.getObject(1,UUID.class),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),
            r.getInt(6),r.getString(7),r.getObject(8,UUID.class),r.getObject(9,UUID.class),
            r.getString(10),r.getString(11),r.getString(12),r.getString(13),r.getString(14),
            r.getString(15),r.getString(16),r.getString(17),r.getString(18),
            r.getObject(19,OffsetDateTime.class),r.getString(20));
    }
    private static PilotProblem hidden() {
        return new PilotProblem(404,"IR_ASSESSMENT_NOT_VISIBLE","project","CF-OPEN-007","Recurso indisponível.");
    }
    private static PilotProblem invalid(String detail) {
        return PilotProblem.invalid("IR_ASSESSMENT_INVALID","assessment","CF-OPEN-007",detail);
    }
    private static PilotProblem conflict(String detail) {
        return new PilotProblem(409,"IR_ASSESSMENT_CONFLICT","assessment","CF-OPEN-007",detail);
    }
}
