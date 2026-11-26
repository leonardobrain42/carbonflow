package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
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
    /** Optional declaration (Q13/D21 step 2a): the normative act that separates two catalog vintages of the fuel factor inside the year. */
    record FuelTransitionInput(String actReference, String evidenceLocator) {}
    record FuelTransition(String actReference, String evidenceLocator, List<UUID> entryIds, String setSha256) {}
    record Input(String period, String frequency, String basis, String rationale, FuelTransitionInput fuelTransition) {
        Input(String period, String frequency, String basis, String rationale) { this(period,frequency,basis,rationale,null); }
    }
    record ReviewInput(String assessmentSha256, String outcome, String annualUpdateBasis,
                       String evidenceLocator, String reason, UUID priorAssessmentId, InitialPlan initialPlan,
                       UUID exAntePlanId, Integer exAntePlanVersion, String exAntePlanSha256,
                       List<OpenIrAnnualTemporalEvidence.Input> temporalEvidence) {}
    record InitialPlan(String evr, String afec, String mpg, String fuel) {
        InitialPlan validated() {
            return new InitialPlan(OpenDyAssessmentRules.text(evr,15,2000),
                OpenDyAssessmentRules.text(afec,15,2000),OpenDyAssessmentRules.text(mpg,15,2000),
                OpenDyAssessmentRules.text(fuel,15,2000));
        }
    }
    record PlanLink(UUID id,int version,String sha256) {}
    record SourceMetadata(String sourceId,String edition,String title,Integer referenceYear) {}
    record ReviewReferences(UUID fleetSnapshotId,UUID ratingSnapshotId,UUID fuelCandidateId,
                            List<SourceMetadata> evr,List<SourceMetadata> afec,List<SourceMetadata> mpg,List<SourceMetadata> fuel) {}
    record InitialContext(LocalDate followupOn,String planSha256,InitialPlan plan,PlanLink exAntePlan) {}
    record Review(UUID reviewerId, String outcome, String assessmentSha256, String ruleReference,
                  String annualUpdateBasis, String evidenceLocator, String reason, OffsetDateTime reviewedAt,
                  InitialContext initialContext) {}
    record AnnualEvidence(UUID priorAssessmentId, String evidenceSha256, String state, String policy,
                          OpenIrAnnualTemporalEvidence.Window window,List<OpenIrAnnualTemporalEvidence.Item> temporalItems) {}
    record View(UUID id, String projectId, int projectYear, int sourceYear, int version,
                String period, UUID fleetSelectionId, UUID fuelPinId, String frequency,
                String basis, String rationale, String state, String assessmentSha256,
                String candidate, boolean stale, boolean usableForE8, OffsetDateTime recordedAt, Review review, AnnualEvidence annualEvidence,
                String initialLinkState, OpenIrExAntePlans.ReviewTarget exAnteOption, ReviewReferences reviewReferences,
                OpenIrAnnualTemporalEvidence.Window temporalWindow, FuelTransition fuelTransition) {}
    record Result(View view, boolean created) {}
    record ProjectOption(String projectId,String title) {}
    record Options(boolean canRecord,boolean canReview,List<ProjectOption> projects) {}
    record PeriodOption(String period,int projectYear,int sourceYear,int latestVersion,
                        UUID fleetSelectionId,UUID fuelPinId) {}
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
            String actReference=null,actEvidence=null;
            if(input.fuelTransition()!=null) {
                try {
                    actReference=OpenDyAssessmentRules.text(input.fuelTransition().actReference(),8,300);
                    actEvidence=OpenDyAssessmentRules.text(input.fuelTransition().evidenceLocator(),3,300);
                } catch(IllegalArgumentException e) { throw invalid("Informe o ato normativo (8 a 300 caracteres) e o local da evidência (3 a 300)."); }
            }
            // Hashes and replays of every command without a declaration stay exactly as before.
            String commandSha=actReference==null
                ?digest("OPEN-IR-COMMAND@1",project,period,expected,frequency,basis,rationale)
                :digest("OPEN-IR-COMMAND@2",project,period,expected,frequency,basis,rationale,actReference,actEvidence);
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
            if(actReference!=null) recordFuelTransition(c,project,source,id,actReference,actEvidence);
            return new Result(view(c,first(c,SELECT+" WHERE id=?",OpenIrAssessments::stored,id),true),true);
        });
    }

    /**
     * Q13/D21 step 2a. The server derives the catalog entries pinned for the source year; the declaration is accepted only when
     * they are two or more distinct entries of the SAME fuel, parameter, gas and scope (a regulatory change of the factor), never
     * for a choice between unrelated factors. It records the entries and the declared act; it relaxes no guard.
     */
    private static void recordFuelTransition(Connection c,String project,int source,UUID assessment,String act,String evidence)
            throws SQLException {
        var entries=query(c,"""
            SELECT DISTINCT e.id,e.fuel_id,e.parameter,e.gas,e.emission_scope
            FROM app_open_efuel_catalog_pin cp
            JOIN app_open_project_parameter_binding b ON b.id=cp.binding_id
            JOIN app_fuel_entry e ON e.id=cp.entry_id
            WHERE b.project_id=? AND b.parameter_kind='EF_FUEL' AND b.vintage_year=?
            ORDER BY e.id
            """,r->new Object[]{r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5)},project,source);
        var series=new java.util.HashSet<List<Object>>();
        for(var e:entries) series.add(List.of(e[1],e[2],e[3],Objects.toString(e[4],"")));
        if(entries.size()<2 || series.size()!=1)
            throw conflict("A declaração exige duas ou mais entradas do mesmo fator fóssil (mesmo combustível, parâmetro, gás e escopo) fixadas no ano.");
        var ids=entries.stream().map(e->(UUID)e[0]).toList();
        String setSha=digest("OPEN-IR-FUEL-TRANSITION@1",assessment,ids,act,evidence);
        update(c,"""
            INSERT INTO app_open_ir_fuel_transition(assessment_id,act_reference,evidence_locator,entry_ids,set_sha256)
            VALUES (?,?,?,?,?)
            """,assessment,act,evidence,c.createArrayOf("uuid",ids.toArray(UUID[]::new)),bytes(setSha));
    }

    private static FuelTransition fuelTransition(Connection c,UUID assessment) throws SQLException {
        return first(c,"""
            SELECT act_reference,evidence_locator,entry_ids,encode(set_sha256,'hex')
            FROM app_open_ir_fuel_transition WHERE assessment_id=?
            """,r->new FuelTransition(r.getString(1),r.getString(2),
                java.util.Arrays.asList((UUID[])r.getArray(3).getArray()),r.getString(4)),assessment);
    }

    Options options(String token) {
        return database.transaction(c->{
            var actor=staff(c,token);
            var organizations=c.createArrayOf("uuid",actor.organizations().toArray(UUID[]::new));
            try {
                var rows=query(c,"""
                    SELECT id,title FROM app_project
                    WHERE organization_id=ANY(?) AND environment='SANDBOX' AND state='DRAFT'
                    ORDER BY title,id
                    """,r->new ProjectOption(r.getString(1),r.getString(2)),organizations);
                var projects=new ArrayList<ProjectOption>();
                for(var row:rows) {
                    if(ProjectJourney.profile(ProjectGuides.candidate(c,row.projectId()))==ProjectJourney.VM0038)
                        projects.add(row);
                    if(projects.size()>=80) break;
                }
                return new Options(managers.canManage(actor.id()),reviewers.contains(actor.id()),List.copyOf(projects));
            } finally { organizations.free(); }
        });
    }

    private static void requireStaffProject(Connection c,String project,Set<UUID> organizations) throws SQLException {
        UUID org=first(c,"""
            SELECT organization_id FROM app_project
            WHERE id=? AND environment='SANDBOX' AND state='DRAFT' FOR SHARE
            """,r->r.getObject(1,UUID.class),project);
        if(org==null || !organizations.contains(org) ||
           ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
            throw hidden();
    }

    List<PeriodOption> periodOptions(String token,String project) {
        return database.transaction(c->{
            var actor=managers.requireManager(c,token);
            requireStaffProject(c,project,actor.organizations());
            var periods=query(c,"""
                SELECT DISTINCT period_code FROM app_open_e8_fleet_selection
                WHERE project_id=? ORDER BY period_code LIMIT 100
                """,r->r.getString(1),project);
            var result=new ArrayList<PeriodOption>();
            for(String period:periods) {
                Refs refs=references(c,project,period);
                if(refs==null) continue;
                int year=refs.timing().year().projectYear(),source=refs.timing().year().sourceYear();
                Integer latest=first(c,"""
                    SELECT coalesce(max(version),0) FROM app_open_ir_assessment
                    WHERE project_id=? AND project_year=? AND source_year=?
                    """,r->r.getInt(1),project,year,source);
                result.add(new PeriodOption(period,year,source,latest,refs.fleet().id(),refs.fuel().id()));
            }
            return List.copyOf(result);
        });
    }

    List<View> recent(String token,String project,boolean staffView) {
        if(!staffView) return scope.read(token,project,"VIEW_OPEN_PARAMETER_BINDING",
            (c,ctx)->recentRows(c,project,false));
        return database.transaction(c->{
            var actor=staff(c,token);
            requireStaffProject(c,project,actor.organizations());
            return recentRows(c,project,true);
        });
    }

    private static List<View> recentRows(Connection c,String project,boolean staff) throws SQLException {
        var result=new ArrayList<View>();
        for(var row:query(c,SELECT+"""
            WHERE project_id=? ORDER BY recorded_at DESC,id LIMIT 50
            """,OpenIrAssessments::stored,project))
            result.add(view(c,row,staff));
        return List.copyOf(result);
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
            boolean initial="INITIAL_BASELINE_CHECKED".equals(input.outcome());
            InitialPlan plan;
            boolean annualCheck="CHECKED_INTERNAL".equals(input.outcome());
            List<OpenIrAnnualTemporalEvidence.Input> temporalInputs;
            boolean legacyInitial=initial && input.exAntePlanId()==null && input.exAntePlanVersion()==null && input.exAntePlanSha256()==null;
            try {
                OpenIrReviewRules.validate(input.outcome(),input.annualUpdateBasis(),row.state(),row.year());
                if(initial && (input.initialPlan()==null || input.priorAssessmentId()!=null))
                    throw new IllegalArgumentException("Informe os quatro planos de atualização, sem avaliação anterior.");
                if(initial && !legacyInitial && (input.exAntePlanId()==null || input.exAntePlanVersion()==null || input.exAntePlanSha256()==null))
                    throw new IllegalArgumentException("Selecione a versão e o hash do plano ex ante vigente.");
                if(!initial && (input.initialPlan()!=null || input.exAntePlanId()!=null || input.exAntePlanVersion()!=null || input.exAntePlanSha256()!=null))
                    throw new IllegalArgumentException("Plano inicial reservado ao parecer de primeiro ano.");
                plan=initial?input.initialPlan().validated():null;
                if(initial && !legacyInitial) {
                    OpenIrHandoffRules.note(plan.evr()); OpenIrHandoffRules.note(plan.afec());
                    OpenIrHandoffRules.note(plan.mpg()); OpenIrHandoffRules.note(plan.fuel());
                }
                if(!annualCheck && input.temporalEvidence()!=null)
                    throw new IllegalArgumentException("Evidência temporal é reservada à atualização anual observada.");
                temporalInputs=input.temporalEvidence()==null?null:OpenIrAnnualTemporalEvidence.normalized(input.temporalEvidence());
                locator=OpenDyAssessmentRules.text(input.evidenceLocator(),3,2000);
                reason=OpenDyAssessmentRules.text(input.reason(),15,2500);
            } catch(IllegalArgumentException e) { throw invalid(e.getMessage()); }
            String fingerprint=digest("OPEN-IR-REVIEW@1",project,assessment,row.sha(),expected,
                input.outcome(),input.annualUpdateBasis(),locator,reason);
            if(input.priorAssessmentId()!=null)
                fingerprint=digest("OPEN-IR-REVIEW-ANNUAL@1",fingerprint,input.priorAssessmentId());
            if(legacyInitial) fingerprint=digest("OPEN-IR-INITIAL-PLAN@1",fingerprint,plan.evr(),plan.afec(),plan.mpg(),plan.fuel());
            else if(plan!=null) fingerprint=digest("OPEN-IR-INITIAL-HANDOFF@1",fingerprint,plan.evr(),plan.afec(),plan.mpg(),plan.fuel(),
                input.exAntePlanId(),input.exAntePlanVersion(),input.exAntePlanSha256());
            if(temporalInputs!=null) fingerprint=digest("OPEN-IR-REVIEW-TEMPORAL@1",fingerprint,
                OpenIrAnnualTemporalEvidence.commandDigest(temporalInputs));
            Object[] replay=first(c,"""
                SELECT assessment_id,encode(command_sha256,'hex') FROM app_open_ir_review
                WHERE reviewer_id=? AND command_key=?
                """,r->new Object[]{r.getObject(1,UUID.class),r.getString(2)},actor.id(),command);
            if(replay!=null) {
                if(!assessment.equals(replay[0]) || !fingerprint.equals(replay[1]))
                    throw conflict("Esta chave já identifica outro parecer.");
                return new Result(view(c,row,true),false);
            }
            if(legacyInitial) throw invalid("Selecione a versão e o hash do plano ex ante vigente.");
            if(annualCheck && temporalInputs==null)throw invalid("Informe as evidências temporais dos quatro parâmetros.");
            Integer latest=first(c,"""
                SELECT max(version) FROM app_open_ir_assessment
                WHERE project_id=? AND project_year=? AND source_year=?
                """,r->r.getInt(1),project,row.year(),row.sourceYear());
            PilotCommands.requireVersion(expected,latest);
            if(view(c,row,true).stale()) throw conflict("Avaliação desatualizada. Registre nova versão.");
            if(readReview(c,assessment,true)!=null) throw conflict("Já existe parecer nesta versão.");
            OffsetDateTime reviewedAt=first(c,"SELECT statement_timestamp()",r->r.getObject(1,OffsetDateTime.class));
            OpenIrAnnualEvidence.Proof proof=null;
            OpenIrAnnualTemporalEvidence.Proof temporal=null;
            if("CHECKED_INTERNAL".equals(input.outcome())) {
                if(input.priorAssessmentId()==null)
                    throw invalid("Selecione a avaliação canônica do ano anterior.");
                Stored previous=first(c,SELECT+" WHERE project_id=? AND id=?",
                    OpenIrAssessments::stored,project,input.priorAssessmentId());
                if(previous==null) throw hidden();
                try {
                    if(previous.year()>1 && !annualCurrent(c,previous))
                        throw new IllegalArgumentException("A prova anual anterior precisa estar vigente.");
                    proof=OpenIrAnnualEvidence.capture(annualSnapshot(c,previous),annualSnapshot(c,row));
                    if(reviewReferences(c,row)==null)
                        throw new IllegalArgumentException("Metadados das fontes efetivas indisponíveis.");
                    temporal=OpenIrAnnualTemporalEvidence.capture(temporalWindow(c,row,reviewedAt),proof.current(),temporalInputs);
                } catch(IllegalArgumentException e) { throw invalid(e.getMessage()); }
            } else if(input.priorAssessmentId()!=null) {
                throw invalid("Vínculo de atualização observada é reservado à checagem interna.");
            }
            OpenIrExAntePlans.ReviewTarget target=null;
            LocalDate followup=null;
            String planSha=null;
            if(initial) {
                target=OpenIrExAntePlans.currentReviewTarget(c,project);
                if(target==null || !target.id().equals(input.exAntePlanId()) || target.version()!=input.exAntePlanVersion()
                    || !target.sha256().equals(input.exAntePlanSha256()))
                    throw conflict("Plano ex ante ausente, obsoleto ou alterado. Reconfirme a versão vigente.");
                if(actor.id().equals(target.author())) throw hidden();
                if(reviewReferences(c,row)==null) throw conflict("Referências efetivas indisponíveis.");
                var calendar=OpenE8Timing.calendar(c,project,2);
                if(calendar.gate()!=null || calendar.window()==null)
                    throw conflict("Calendário inicial precisa de rechecagem.");
                followup=calendar.window().from().atZoneSameInstant(VintageWindows.ZONE).toLocalDate();
                planSha=digest("OPEN-IR-INITIAL-CONTEXT@2",row.sha(),row.timingSha(),followup,
                    target.id(),target.version(),target.sha256(),
                    plan.evr(),plan.afec(),plan.mpg(),plan.fuel(),actor.id(),fingerprint);
            }
            update(c,"""
                INSERT INTO app_open_ir_review
                (assessment_id,assessment_sha256,author_id,reviewer_id,outcome,rule_reference,
                 annual_update_basis,evidence_locator,reason,command_key,command_sha256,
                 initial_evr_plan,initial_afec_plan,initial_mpg_plan,initial_fuel_plan,initial_followup_on,initial_plan_sha256,
                 ex_ante_project_id,ex_ante_plan_id,ex_ante_plan_version,ex_ante_plan_sha256,ex_ante_plan_author,reviewed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,assessment,bytes(row.sha()),author,actor.id(),input.outcome(),
                OpenIrAssessment.RULE_REFERENCE,input.annualUpdateBasis(),locator,reason,
                command,bytes(fingerprint),plan==null?null:plan.evr(),plan==null?null:plan.afec(),
                plan==null?null:plan.mpg(),plan==null?null:plan.fuel(),followup,bytes(planSha),
                target==null?null:project,target==null?null:target.id(),target==null?null:target.version(),
                target==null?null:bytes(target.sha256()),target==null?null:target.author(),reviewedAt);
            if(proof!=null) {
                var prior=proof.previous();
                update(c,"""
                    INSERT INTO app_open_ir_annual_evidence
                    (assessment_id,prior_assessment_id,project_id,project_year,source_year,
                     prior_project_year,prior_source_year,assessment_sha256,prior_assessment_sha256,
                     evidence_sha256,policy)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """,row.id(),prior.id(),project,row.year(),row.sourceYear(),prior.projectYear(),
                    prior.sourceYear(),bytes(row.sha()),bytes(prior.assessmentSha()),bytes(temporalDigest(c,proof,temporal)),
                    OpenIrAnnualEvidence.POLICY);
                for(var item:temporal.items()) {
                    var e=item.evidence();
                    update(c,"""
                        INSERT INTO app_open_ir_annual_temporal_evidence
                        (assessment_id,metric,updated_on,valid_from,valid_until,evidence_locator,reference_sha256,
                         year_from,year_until,period_from,period_until,reviewed_on)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """,row.id(),e.metric().name(),e.updatedOn(),e.validFrom(),e.validUntil(),e.evidenceLocator(),bytes(item.referenceSha256()),
                        temporal.window().yearFrom(),temporal.window().yearUntil(),temporal.window().periodFrom(),
                        temporal.window().periodUntil(),temporal.window().reviewedOn());
                }
            }
            return new Result(view(c,row,true),true);
        });
    }

    private static Review readReview(Connection c,UUID id,boolean staff) throws SQLException {
        return first(c,"""
            SELECT reviewer_id,outcome,encode(assessment_sha256,'hex'),rule_reference,
                annual_update_basis,evidence_locator,reason,reviewed_at,initial_followup_on,
                encode(initial_plan_sha256,'hex'),initial_evr_plan,initial_afec_plan,initial_mpg_plan,initial_fuel_plan,
                ex_ante_plan_id,ex_ante_plan_version,encode(ex_ante_plan_sha256,'hex')
            FROM app_open_ir_review WHERE assessment_id=?
            """,r->new Review(staff?r.getObject(1,UUID.class):null,r.getString(2),r.getString(3),
                r.getString(4),r.getString(5),staff?r.getString(6):null,staff?r.getString(7):null,
                r.getObject(8,OffsetDateTime.class),r.getString(10)==null?null:
                    new InitialContext(r.getObject(9,LocalDate.class),r.getString(10),staff?
                        new InitialPlan(r.getString(11),r.getString(12),r.getString(13),r.getString(14)):null,
                        r.getObject(15,UUID.class)==null?null:new PlanLink(r.getObject(15,UUID.class),r.getInt(16),r.getString(17)))),id);
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
        var fuel=OpenE8ProjectRuns.sourcePinReference(c,project,"EF_FUEL",fleet.year(),timing.window());
        if(!"NOT_ADMITTED".equals(fuel.state()) || fuel.id()==null) return null;
        return new Refs(fleet,fuel,timing,OpenCalculationReadiness.triageRevision(c,project));
    }

    static OpenE8IrContext forRun(Connection c,String project,String period,
                                  UUID fleet,UUID fuel,OpenE8Timing.Context timing) throws SQLException {
        Integer year=timing.year().projectYear(),source=timing.year().sourceYear();
        boolean resolved=timing.year().gate()==null && year!=null && source!=null;
        Stored row=resolved?first(c,SELECT+"""
            WHERE project_id=? AND project_year=? AND source_year=?
            ORDER BY version DESC LIMIT 1
            """,OpenIrAssessments::stored,project,year,source):null;
        if(row==null) {
            String state=OpenE8IrContext.classify(year,source,resolved,false,false,false,false,false,false,false);
            return OpenE8IrContext.of(project,period,year,source,null,null,null,state,null,null,null,null);
        }
        Review review=readReview(c,row.id(),true);
        AnnualEvidence annual=annualEvidence(c,row,review,false);
        boolean checked=review!=null && "CHECKED_INTERNAL".equals(review.outcome());
        boolean proof=annual!=null && "CONSECUTIVE_REFERENCES_NOT_ADMITTED".equals(annual.state());
        boolean exact=period.equals(row.period()) && Objects.equals(fleet,row.fleet())
            && Objects.equals(fuel,row.fuel()) && timing.fingerprint().equals(row.timingSha());
        boolean matches=exact;
        OpenIrIntraYearReuse.Span reusedSpan=null;
        if(!exact && !period.equals(row.period()) && year>1 && checked && proof) {
            var reuse=intraYearReuse(c,row,annual,period,fleet,fuel,timing);
            matches=reuse!=OpenIrIntraYearReuse.Outcome.REFERENCE_MISMATCH;
            proof=reuse==OpenIrIntraYearReuse.Outcome.REUSABLE;
            if(proof) reusedSpan=periodSpan(timing);
        }
        String linkState=initialLinkState(c,row,review);
        boolean planLinked=review!=null && "INITIAL_BASELINE_CHECKED".equals(review.outcome())
            && "LINKED_CURRENT".equals(linkState);
        String state=OpenE8IrContext.classify(year,source,resolved,true,stale(c,row),matches,
            checked,proof,"1".equals(row.candidate()),planLinked);
        UUID author=first(c,"SELECT author_id FROM app_open_ir_assessment WHERE id=?",
            r->r.getObject(1,UUID.class),row.id());
        String reviewSha=first(c,"SELECT encode(command_sha256,'hex') FROM app_open_ir_review WHERE assessment_id=?",
            r->r.getString(1),row.id());
        if(linkState!=null) reviewSha=digest("OPEN-IR-INITIAL-LINK-STATE@1",reviewSha,linkState);
        if(reusedSpan!=null && OpenE8IrContext.CHECKED.equals(state))
            reviewSha=OpenIrIntraYearReuse.marker(reviewSha,row.period(),reusedSpan,row.id(),row.sha());
        return OpenE8IrContext.of(project,period,year,source,row.id(),row.version(),row.sha(),state,
            reviewSha,annual==null?null:annual.evidenceSha256(),author,review==null?null:review.reviewerId());
    }

    /** Compares the period asked by a run with the assessed period using canonical references, never row ids. */
    private static OpenIrIntraYearReuse.Outcome intraYearReuse(Connection c,Stored row,AnnualEvidence annual,
            String period,UUID fleet,UUID fuel,OpenE8Timing.Context timing) throws SQLException {
        Refs target=references(c,row.project(),period);
        if(target==null || target.triage()!=row.triage() || !Objects.equals(fleet,target.fleet().id())
           || !Objects.equals(fuel,target.fuel().id()) || fleet==null || fuel==null)
            return OpenIrIntraYearReuse.Outcome.REFERENCE_MISMATCH;
        var assessed=canonical(c,row.project(),row.fleet(),row.fuel(),row.sourceYear());
        var requested=canonical(c,row.project(),fleet,fuel,row.sourceYear());
        return OpenIrIntraYearReuse.decide(
            new OpenIrIntraYearReuse.Subject(row.year(),row.sourceYear(),assessed,null),
            new OpenIrIntraYearReuse.Subject(timing.year().projectYear(),timing.year().sourceYear(),requested,periodSpan(timing)),
            annual.window(),annual.temporalItems().stream().toList());
    }

    private static OpenIrIntraYearReuse.Span periodSpan(OpenE8Timing.Context timing) {
        if(timing==null || timing.window()==null) return null;
        var w=timing.window();
        // Same local-day convention as the temporal evidence window: last touched day plus one, exclusive.
        return new OpenIrIntraYearReuse.Span(w.from().atZoneSameInstant(VintageWindows.ZONE).toLocalDate(),
            w.until().toInstant().minusNanos(1).atZone(VintageWindows.ZONE).toLocalDate().plusDays(1));
    }

    private static OpenIrIntraYearReuse.Canonical canonical(Connection c,String project,UUID fleet,UUID fuel,
            int sourceYear) throws SQLException {
        if(fleet==null || fuel==null) return null;
        return first(c,"""
            SELECT f.fleet_snapshot_id,f.rating_snapshot_id,COALESCE(b.candidate_id,cp.entry_id),f.cycle
            FROM app_open_e8_fleet_selection f
            JOIN app_open_project_parameter_binding b ON b.project_id=f.project_id
            LEFT JOIN app_open_efuel_catalog_pin cp ON cp.binding_id=b.id
            WHERE f.project_id=? AND f.id=? AND b.id=? AND b.parameter_kind='EF_FUEL'
                AND f.observation_year=? AND b.vintage_year=?
            """,r->new OpenIrIntraYearReuse.Canonical(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getObject(3,UUID.class),r.getString(4)),project,fleet,fuel,sourceYear,sourceYear);
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
        boolean current=!stale(c,row) && (row.year()==1
            ? "LINKED_CURRENT".equals(initialLinkState(c,row,opinion))
            : (opinion==null || "CHECKED_INTERNAL".equals(opinion.outcome())));
        return first(c,"""
            SELECT f.fleet_snapshot_id,f.rating_snapshot_id,COALESCE(b.candidate_id,cp.entry_id),f.cycle
            FROM app_open_e8_fleet_selection f
            JOIN app_open_project_parameter_binding b ON b.project_id=f.project_id
            LEFT JOIN app_open_efuel_catalog_pin cp ON cp.binding_id=b.id
            WHERE f.project_id=? AND f.id=? AND b.id=? AND b.parameter_kind='EF_FUEL'
                AND f.observation_year=? AND b.vintage_year=?
            """,r->new OpenIrAnnualEvidence.Snapshot(row.id(),row.project(),row.year(),row.sourceYear(),
                row.sha(),r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                r.getString(4),row.at(),current,"PENDING_REVIEW".equals(row.state())),
            row.project(),row.fleet(),row.fuel(),row.sourceYear(),row.sourceYear());
    }

    private static OpenIrAnnualTemporalEvidence.Window temporalWindow(Connection c,Stored row,OffsetDateTime reviewedAt)throws SQLException {
        Refs refs=references(c,row.project(),row.period());
        if(refs==null)return null;
        try {
            return OpenIrAnnualTemporalEvidence.window(OpenE8Timing.calendar(c,row.project(),row.year()),refs.timing(),reviewedAt);
        } catch(IllegalArgumentException e) {return null;}
    }

    private static String temporalDigest(Connection c,OpenIrAnnualEvidence.Proof proof,
                                          OpenIrAnnualTemporalEvidence.Proof temporal)throws SQLException {
        String priorDigest=proof.previous().projectYear()==1?null:first(c,"""
            SELECT encode(evidence_sha256,'hex') FROM app_open_ir_annual_evidence WHERE assessment_id=?
            """,r->r.getString(1),proof.previous().id());
        return digest(OpenIrAnnualTemporalEvidence.POLICY,annualDigest(c,proof),priorDigest,temporal.digest());
    }

    private static boolean annualCurrent(Connection c,Stored row)throws SQLException {
        AnnualEvidence prior=annualEvidence(c,row,readReview(c,row.id(),false),false);
        return prior!=null && "CONSECUTIVE_REFERENCES_NOT_ADMITTED".equals(prior.state());
    }

    private static AnnualEvidence annualEvidence(Connection c,Stored row,Review review,boolean staff) throws SQLException {
        if(review==null || !"CHECKED_INTERNAL".equals(review.outcome())) return null;
        AnnualEvidence pin=first(c,"""
            SELECT prior_assessment_id,encode(evidence_sha256,'hex'),policy
            FROM app_open_ir_annual_evidence WHERE assessment_id=?
            """,r->new AnnualEvidence(r.getObject(1,UUID.class),r.getString(2),
                "CONSECUTIVE_REFERENCES_NOT_ADMITTED",r.getString(3),null,List.of()),row.id());
        if(pin==null) return new AnnualEvidence(null,null,"LEGACY_EVIDENCE_MISSING",OpenIrAnnualEvidence.POLICY,null,List.of());
        var items=query(c,"""
            SELECT metric,updated_on,valid_from,valid_until,evidence_locator,encode(reference_sha256,'hex')
            FROM app_open_ir_annual_temporal_evidence WHERE assessment_id=?
            """,r->new OpenIrAnnualTemporalEvidence.Item(new OpenIrAnnualTemporalEvidence.Input(
                OpenIrAnnualTemporalEvidence.Metric.valueOf(r.getString(1)),r.getObject(2,LocalDate.class),
                r.getObject(3,LocalDate.class),r.getObject(4,LocalDate.class),r.getString(5)),r.getString(6)),row.id());
        if(items.isEmpty())return new AnnualEvidence(pin.priorAssessmentId(),pin.evidenceSha256(),
            "TEMPORAL_EVIDENCE_MISSING",pin.policy(),null,List.of());
        var ordered=items.stream().sorted(java.util.Comparator.comparing(i->i.evidence().metric())).toList();
        var visible=ordered.stream().map(i->staff?i:new OpenIrAnnualTemporalEvidence.Item(
            new OpenIrAnnualTemporalEvidence.Input(i.evidence().metric(),i.evidence().updatedOn(),i.evidence().validFrom(),
                i.evidence().validUntil(),null),i.referenceSha256())).toList();
        Stored prior=first(c,SELECT+" WHERE project_id=? AND id=?",OpenIrAssessments::stored,
            row.project(),pin.priorAssessmentId());
        var frozen=query(c,"""
            SELECT DISTINCT year_from,year_until,period_from,period_until,reviewed_on
            FROM app_open_ir_annual_temporal_evidence WHERE assessment_id=?
            """,r->new OpenIrAnnualTemporalEvidence.Window(r.getObject(1,LocalDate.class),r.getObject(2,LocalDate.class),
                r.getObject(3,LocalDate.class),r.getObject(4,LocalDate.class),r.getObject(5,LocalDate.class)),row.id());
        var window=frozen.size()==1?frozen.getFirst():null;
        var currentWindow=temporalWindow(c,row,review.reviewedAt());
        try {
            if(window!=null && window.equals(currentWindow) && prior!=null && (prior.year()==1 || annualCurrent(c,prior))) {
                var proof=OpenIrAnnualEvidence.capture(annualSnapshot(c,prior),annualSnapshot(c,row));
                var temporal=OpenIrAnnualTemporalEvidence.capture(window,proof.current(),
                    ordered.stream().map(OpenIrAnnualTemporalEvidence.Item::evidence).toList());
                if(temporal.items().equals(ordered) && pin.evidenceSha256().equals(temporalDigest(c,proof,temporal)))
                    return new AnnualEvidence(pin.priorAssessmentId(),pin.evidenceSha256(),pin.state(),pin.policy(),window,visible);
            }
        } catch(IllegalArgumentException e) { /* immutable historical proof requires recheck */ }
        return new AnnualEvidence(pin.priorAssessmentId(),pin.evidenceSha256(),
            "STALE_EVIDENCE_NOT_ADMITTED",pin.policy(),window,visible);
    }

    private static View view(Connection c,Stored row,boolean staff) throws SQLException {
        boolean stale=stale(c,row);
        Review review=readReview(c,row.id(),staff);
        AnnualEvidence annual=annualEvidence(c,row,review,staff);
        boolean evidenceMissing=annual!=null && !"CONSECUTIVE_REFERENCES_NOT_ADMITTED".equals(annual.state());
        String linkState=initialLinkState(c,row,review);
        boolean linkStale="LEGACY_LINK_MISSING".equals(linkState)||"LINK_STALE".equals(linkState);
        String state=stale?"STALE_NOT_ADMITTED":linkStale?"INITIAL_LINK_RECHECK_REQUIRED":evidenceMissing?"ANNUAL_EVIDENCE_RECHECK_REQUIRED":
            review==null?row.state():review.outcome()+"_NOT_ADMITTED";
        return new View(row.id(),row.project(),row.year(),row.sourceYear(),row.version(),row.period(),
            row.fleet(),row.fuel(),row.frequency(),row.basis(),staff?row.rationale():null,
            state,row.sha(),stale || linkStale || evidenceMissing || (review!=null && !"CHECKED_INTERNAL".equals(review.outcome()))?
                null:row.candidate(),stale || linkStale || evidenceMissing,
            false,row.at(),review,annual,linkState,
            staff&&row.year()==1&&review==null&&!stale?OpenIrExAntePlans.currentReviewTarget(c,row.project()):null,
            staff&&!stale&&review==null?reviewReferences(c,row):null,
            staff&&row.year()>1&&review==null&&!stale&&reviewReferences(c,row)!=null?temporalWindow(c,row,first(c,"SELECT statement_timestamp()",r->r.getObject(1,OffsetDateTime.class))):null,
            fuelTransition(c,row.id()));
    }

    private static ReviewReferences reviewReferences(Connection c,Stored row)throws SQLException {
        UUID[] ids=first(c,"""
            SELECT f.fleet_snapshot_id,f.rating_snapshot_id,COALESCE(b.candidate_id,cp.entry_id)
            FROM app_open_e8_fleet_selection f
            JOIN app_open_project_parameter_binding b ON b.project_id=f.project_id
            LEFT JOIN app_open_efuel_catalog_pin cp ON cp.binding_id=b.id
            WHERE f.project_id=? AND f.id=? AND b.id=? AND b.parameter_kind='EF_FUEL'
              AND f.fleet_snapshot_id IS NOT NULL AND f.rating_snapshot_id IS NOT NULL AND COALESCE(b.candidate_id,cp.entry_id) IS NOT NULL
            """,r->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)},
            row.project(),row.fleet(),row.fuel());
        if(ids==null)return null;
        var evr=query(c,"""
            SELECT s.source_id,s.edition,s.title,s.reference_year
            FROM app_fleet_composition_snapshot f JOIN app_fleet_ingestion i ON i.id=f.ingestion_id
            JOIN app_platform_source_object o ON o.id=i.source_object_id
            JOIN app_platform_source_submission s ON s.id=o.submission_id WHERE f.id=?
            """,OpenIrAssessments::sourceMetadata,ids[0]);
        var afec=ratingSources(c,ids[1],true);
        var mpg=ratingSources(c,ids[1],false);
        List<SourceMetadata> fuel=query(c,"""
            SELECT s.source_id,s.edition,s.title,s.reference_year
            FROM app_open_parameter_candidate p JOIN app_platform_source_object o ON o.id=p.source_object_id
            JOIN app_platform_source_submission s ON s.id=o.submission_id WHERE p.id=?
            """,OpenIrAssessments::sourceMetadata,ids[2]);
        if(fuel.isEmpty())fuel=query(c,"""
            SELECT 'FUEL-CATALOG:'||e.fuel_id,e.id::text,e.evidence_source,e.vintage_year
            FROM app_fuel_entry e WHERE e.id=?
            """,OpenIrAssessments::sourceMetadata,ids[2]);
        if(evr.isEmpty()||afec.isEmpty()||mpg.isEmpty()||fuel.isEmpty())return null;
        return new ReviewReferences(ids[0],ids[1],ids[2],evr,afec,mpg,fuel);
    }

    private static List<SourceMetadata> ratingSources(Connection c,UUID snapshot,boolean bev)throws SQLException {
        return query(c,"""
            SELECT DISTINCT s.source_id,s.edition,s.title,s.reference_year
            FROM app_vehicle_rating_snapshot_line l JOIN app_vehicle_comparable_pair p ON p.id=l.pair_id
            JOIN app_vehicle_rating r ON r.id=CASE WHEN ? THEN p.bev_rating_id ELSE p.fossil_rating_id END
            JOIN app_platform_source_object o ON o.id=r.source_object_id
            JOIN app_platform_source_submission s ON s.id=o.submission_id
            WHERE l.snapshot_id=? ORDER BY s.source_id,s.edition,s.title,s.reference_year
            """,OpenIrAssessments::sourceMetadata,bev,snapshot);
    }

    private static SourceMetadata sourceMetadata(ResultSet r)throws SQLException {
        return new SourceMetadata(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,Integer.class));
    }

    private static String initialLinkState(Connection c,Stored row,Review review)throws SQLException {
        if(row.year()!=1 || review==null || !"INITIAL_BASELINE_CHECKED".equals(review.outcome()))return null;
        var link=review.initialContext()==null?null:review.initialContext().exAntePlan();
        if(link==null)return "LEGACY_LINK_MISSING";
        var current=OpenIrExAntePlans.currentReviewTarget(c,row.project());
        return current!=null && current.id().equals(link.id()) && current.version()==link.version()
            && current.sha256().equals(link.sha256())?"LINKED_CURRENT":"LINK_STALE";
    }

    private static String annualDigest(Connection c,OpenIrAnnualEvidence.Proof proof)throws SQLException {
        if(proof.previous().projectYear()!=1)return proof.digest();
        var opinion=readReview(c,proof.previous().id(),false);
        if(opinion==null || opinion.initialContext()==null || opinion.initialContext().exAntePlan()==null)
            throw invalid("Parecer inicial sem plano ex ante vinculado.");
        var initial=opinion.initialContext();
        var link=initial.exAntePlan();
        return digest("OPEN-IR-ANNUAL-INITIAL-LINK@1",proof.digest(),initial.planSha256(),
            link.id(),link.version(),link.sha256());
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
