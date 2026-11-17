package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.ListingEvidence.*;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class VvbAuditWorkflow {
    static final String NAMESPACE = "SIM-VVB-AUDITORIA";
    static final String ACCREDITATION = "SIM-ACREDITACAO";
    static final String AGREEMENT = "SIM-CONTRATO";
    static final String REPORT = "SIM-RELATORIO-PARECER";
    static final String REPRESENTATION = "SIM-REPRESENTACAO-PARECER";
    static final Map<String,String> SOURCES = Map.ofEntries(
            Map.entry("TX-E3-26","SIM-PROVA-ABERTURA-AUDITORIA"),
            Map.entry("VISITA","SIM-PROVA-VISITA-VVB"),
            Map.entry("RISCO","SIM-PROVA-RISCO-VVB"),
            Map.entry("COBERTURA","SIM-PROVA-COBERTURA-COMPLEMENTAR"),
            Map.entry("TX-E3-27","SIM-PROVA-ACHADO-VVB"),
            Map.entry("TX-E3-29","SIM-PROVA-AVALIACAO-ACHADO"),
            Map.entry("TX-E3-30","SIM-PROVA-PARECER-NEGATIVO"),
            Map.entry("TX-E3-31","SIM-PROVA-RETOMADA-VVB"),
            Map.entry("TX-E3-32","SIM-PROVA-PARECER-POSITIVO"),
            Map.entry("REVISAR_SUCESSORA","SIM-PROVA-REVISAO-SUCESSORA"));

    enum Kind {
        VAL, M1, M2;
        DocumentRules.Act purpose() { return this == VAL ? DocumentRules.Act.REGISTRO : DocumentRules.Act.VERIFICACAO; }
        String grantPurpose() { return switch (this) { case VAL -> "AUDITORIA_VAL"; case M1 -> "AUDITORIA_V1"; case M2 -> "AUDITORIA_V2"; }; }
    }
    record Contract(Kind kind, DocumentRules.Ref dossier, UUID consultation, String vvb, String scope,
                    OffsetDateTime contractedAt, OffsetDateTime accreditedUntil, IntakeModel.Ref contract, IntakeModel.Ref accreditation) {}
    record Action(String transitionId, OffsetDateTime effectiveAt, DocumentRules.Ref dossier, Proof proof, UUID grantId,
                  UUID finding, String reason, String criterion, Boolean mandatoryVisit, Boolean waiveVisit, UUID previousGrant,
                  String closureSha256, UUID exportId, Boolean accepted, IntakeModel.Ref report, IntakeModel.Ref representation) {}
    record Visit(DocumentRules.Ref dossier, Capture capture, String detail) {}
    record Risk(DocumentRules.Ref dossier, Capture capture, UUID m1Audit, UUID previousGrant, boolean mandatoryVisit, boolean waived, String reason) {}
    record Finding(UUID id, String criterion, String reason, DocumentRules.Ref dossier, String state, UUID eventId) {}
    record Opinion(DocumentRules.Ref dossier, String result, Capture capture, IntakeModel.Ref report, IntakeModel.Ref representation,
                   List<String> vintages, String reason) {}
    record Audit(UUID id, int version, Contract contract, String issuer, DossierPackages.AuditPackage pack, String state,
                 UUID grantId, DocumentRules.Ref proposed, UUID responseExport, Visit visit, Risk risk, Visit coverage,
                 List<Finding> findings, List<Opinion> opinions, OffsetDateTime openedAt, OffsetDateTime lastAt, UUID eventId) {}
    record View(Audit audit, String target) {}

    private final PilotDatabase database;
    private final PilotCommands commands;
    private final PilotAudit audit;
    private final ProjectIntake intake;
    private final DossierPackages dossiers;
    private final ListingEvidence evidence;
    private final ObjectMapper mapper;

    VvbAuditWorkflow(PilotDatabase database, PilotCommands commands, PilotAudit audit, ProjectIntake intake,
                     DossierPackages dossiers, ListingEvidence evidence, ObjectMapper mapper) {
        this.database = database; this.commands = commands; this.audit = audit; this.intake = intake;
        this.dossiers = dossiers; this.evidence = evidence; this.mapper = mapper;
    }

    List<View> list(PilotAccess.Scope scope, String actor) {
        String restriction = scope.has(PilotAccess.VVB) ? actor : null;
        return database.read(connection -> query(connection, """
                SELECT state FROM pilot_vvb_audit WHERE project_id=? AND (CAST(? AS text) IS NULL OR vvb=?)
                ORDER BY kind
                """, row -> {
                    var work = decode(row.getString(1), Audit.class);
                    if (restriction != null && !activeGrant(connection, scope.project(), work.contract().vvb(),
                            work.contract().kind(), work.pack(), work.grantId())) {
                        // A equipe contratada pode ver a tarefa aguardando concessão, sem ler o histórico reservado.
                        return view(new Audit(work.id(), work.version(), work.contract(), work.issuer(), work.pack(), work.state(), null,
                                null, null, null, null, null, List.of(), List.of(), work.openedAt(), work.lastAt(), work.eventId()));
                    }
                    return view(work);
                }, scope.project(), restriction, restriction));
    }

    JsonNode create(PilotAccess.Scope scope, String actor, String key, Contract input) {
        if (input == null || input.kind() == null || !valid(input.dossier()) || !IntakeRules.id(input.vvb()) || !IntakeRules.text(input.scope())
                || input.contractedAt() == null || input.accreditedUntil() == null || !input.contractedAt().isBefore(input.accreditedUntil())
                || !new IntakeModel.Ref(AGREEMENT, 1).equals(input.contract()) || !new IntakeModel.Ref(ACCREDITATION, 1).equals(input.accreditation())
                || ((input.kind() == Kind.VAL) != (input.consultation() != null))) {
            throw invalid("CONTRATACAO_INCOMPLETA","Vincule escopo, organismo, provas de contratação e acreditação, dossiê e consulta quando aplicável.");
        }
        return commands.execute(scope, actor, "CONTRATAR_VVB", key, audit.encode(input), null, connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            String issuer = first(connection, """
                    SELECT i.external_issuer FROM pilot_identity i JOIN pilot_membership m ON i.id=m.identity_id
                    WHERE m.project_id=? AND m.identity_id=? AND 'VVB'=ANY(m.roles) AND m.valid_from<=clock_timestamp() AND m.valid_until>clock_timestamp()
                    """, row -> row.getString(1), project, input.vvb());
            if (issuer == null) throw PilotAccess.invisible();
            require(byKind(connection, project, input.kind()) == null, "AUDITORIA_EXISTENTE",
                    "Abra a auditoria existente para registrar a etapa ou a retomada.");
            var pack = packageFor(connection, project, input.kind(), input.dossier());
            if (input.kind() == Kind.VAL) consultation(connection, project, input.consultation());
            requireRotation(connection, project, input.kind(), issuer);
            UUID id = UUID.randomUUID();
            UUID event = audit.record(connection, project, actor, "CONTRATAR_VVB", "PERMITIDO", id.toString(), input);
            var result = new Audit(id,1,input,issuer,pack,"PREVISTA", null,null,null,null,null,null,List.of(),List.of(),null,null,event);
            update(connection,"INSERT INTO pilot_vvb_audit VALUES (?,?,?,?,?::jsonb)",project,id,input.kind().name(),input.vvb(),audit.encode(result));
            return view(result);
        });
    }

    JsonNode act(PilotAccess.Scope scope, String actor, String key, UUID id, int version, Action input) {
        if (input == null || input.transitionId() == null || (!SOURCES.containsKey(input.transitionId()) && !List.of("TX-E3-28","VINCULAR_CONCESSAO").contains(input.transitionId()))
                || !valid(input.dossier())) throw invalid("ACAO_INVALIDA","Indique a etapa, o pacote exato e a prova.");
        var at = local(input.effectiveAt());
        return commands.execute(scope,actor,input.transitionId(),key,audit.encode(List.of(id,version,input)),id.toString(),
                connection -> apply(connection,scope.project(),actor,id,version,input,at));
    }

    private Object apply(Connection c,String project,String actor,UUID id,int version,Action input,OffsetDateTime at) throws SQLException {
        intake.lockProject(c,project);
        var old = get(c,project,id);
        var kind = old.contract().kind();
        String step = input.transitionId();
        boolean vvbAction = !List.of("TX-E3-28","VINCULAR_CONCESSAO").contains(step);
        if (vvbAction && !old.contract().vvb().equals(actor)) throw PilotAccess.invisible();
        String digest = null;
        if (vvbAction) {
            verify(step,input.proof(),target(id),SOURCES.get(step));
            digest = evidence.digest(Arrays.asList(step,input.dossier(),at.toInstant(),input.finding(),input.reason(),input.criterion(),
                    input.mandatoryVisit(),input.waiveVisit(),input.previousGrant(),input.closureSha256(),input.accepted(),input.report(),input.representation()));
            var replay = evidence.repeated(c,project,id,old.issuer(),NAMESPACE,input.proof(),digest);
            if (replay != null) return replay;
        }
        PilotCommands.requireVersion(version,old.version());
        if (!List.of("VISITA", "RISCO", "COBERTURA", "VINCULAR_CONCESSAO").contains(step)) {
            require(old.lastAt()==null || !at.isBefore(old.lastAt()), "DATA_RETROATIVA","Confira a data efetiva da auditoria.");
        }
        require(at.isBefore(old.contract().accreditedUntil()),"ACREDITACAO_VENCIDA","Confirme a acreditação para a data e escopo do trabalho.");
        String state=old.state();
        var pack=old.pack();
        UUID grant=old.grantId();
        var proposed=old.proposed();
        UUID responseExport=old.responseExport();
        var visit=old.visit();
        var risk=old.risk();
        var coverage=old.coverage();
        var findings=new ArrayList<>(old.findings());
        var opinions=new ArrayList<>(old.opinions());
        var openedAt=old.openedAt();
        boolean successor = List.of("TX-E3-28","TX-E3-29","TX-E3-31","REVISAR_SUCESSORA").contains(step);
        if (!successor) {
            require(old.pack().pack().reference().equals(input.dossier()),"PACOTE_DIVERGENTE","A prova deve citar a versão em exame.");
            packageFor(c,project,kind,input.dossier());
        }
        var details = Map.of("input",input,"effectiveAt",at,"before",state,"revision",old.version(),
                "issuer",vvbAction?old.issuer():"PP");
        UUID event=audit.record(c,project,actor,step,"PERMITIDO",id.toString(),details);
        Capture capture=vvbAction?new Capture(old.issuer(),NAMESPACE,input.proof(),at,actor,event):null;

        switch(step) {
            case "TX-E3-26" -> {
                state(old,"PREVISTA");
                require(!at.isBefore(old.contract().contractedAt()),"CONTRATO_PENDENTE","A abertura exige contrato aplicável.");
                if (kind == Kind.VAL) {
                    var consult=consultation(c,project,old.contract().consultation());
                    require(!at.isBefore(consult.opening()),"CONSULTA_PENDENTE","A consulta comprovada inicia a janela da validação.");
                } else {
                    require(registrationActive(c,project,at),"REGISTRO_PENDENTE","Vincule comprovação do registro antes da verificação.");
                    require(!at.isBefore(MonitoringClosure.readClosure(c,project,pack.closure()).effectiveAt()),
                            "FECHAMENTO_PENDENTE","A abertura depende do fechamento vigente.");
                }
                requireRotation(c,project,kind,old.issuer());
                requireGrant(c,project,old,pack,input.grantId());
                grant=input.grantId(); openedAt=at; state="EM_ANALISE";
            }
            case "VINCULAR_CONCESSAO" -> {
                require(input.grantId()!=null, "CONCESSAO_PENDENTE", "Informe o identificador da nova concessão.");
                requireGrant(c,project,old,pack,input.grantId());
                grant=input.grantId();
            }
            case "VISITA","RISCO","COBERTURA" -> {
                require(List.of("EM_ANALISE","COM_ACHADO","RESPOSTA_ENVIADA").contains(state),"AUDITORIA_PENDENTE","Abra a auditoria para registrar o trabalho do VVB.");
                requireGrant(c,project,old,pack,grant);
                reason(input);
                if ("VISITA".equals(step)) {
                    require(!at.isBefore(kind==Kind.VAL ? openedAt : old.contract().contractedAt()),"VISITA_FORA_DO_ESCOPO","A visita precisa ocorrer no escopo da auditoria contratada.");
                    visit=new Visit(input.dossier(),capture,input.reason());
                } else if ("RISCO".equals(step)) {
                    require(kind==Kind.M2 && input.mandatoryVisit()!=null && input.waiveVisit()!=null
                                    && !(input.mandatoryVisit() && input.waiveVisit()),
                            "RISCO_INCOMPLETO","Informe a análise de risco de M2 e a decisão aplicável à visita.");
                    var m1=byKind(c,project,Kind.M1);
                    require(m1!=null && "PARECER_POSITIVO".equals(m1.state()),"M1_PENDENTE","Identifique o trabalho anterior de M1.");
                    require(input.previousGrant()!=null && activeGrant(c,project,old.contract().vvb(),Kind.M1,m1.pack(),input.previousGrant()),
                            "ACESSO_M1_PENDENTE","Comprove acesso às evidências de M1 para o VVB de M2.");
                    risk=new Risk(input.dossier(),capture,m1.id(),input.previousGrant(),input.mandatoryVisit(),input.waiveVisit(),input.reason());
                } else {
                    require(kind!=Kind.VAL && visit!=null && !at.isBefore(visit.capture().effectiveAt()),
                            "VISITA_PENDENTE","Vincule a visita e a cobertura até o fim do período.");
                    var closed=MonitoringClosure.readClosure(c,project,pack.closure());
                    require(closed.sha().equals(input.closureSha256()) && !at.isBefore(closed.effectiveAt()),
                            "COBERTURA_DIVERGENTE","Comprove a cobertura pelo hash do fechamento.");
                    coverage=new Visit(input.dossier(),capture,closed.sha());
                }
            }
            case "TX-E3-27" -> {
                require(List.of("EM_ANALISE","COM_ACHADO").contains(state),"AUDITORIA_PENDENTE","O achado pertence ao trabalho em exame.");
                requireGrant(c,project,old,pack,grant);
                reason(input);
                if(!IntakeRules.text(input.criterion())) throw invalid("CRITERIO_AUSENTE","Informe o critério e a evidência requerida.");
                findings.add(new Finding(UUID.randomUUID(),input.criterion(),input.reason(),input.dossier(),"ABERTO",event)); state="COM_ACHADO";
            }
            case "TX-E3-28" -> {
                state(old,"COM_ACHADO"); reason(input);
                var changed=findingPackage(c,project,old,input);
                requireGrant(c,project,old,changed,input.grantId());
                require(input.exportId()!=null,"RECIBO_AUSENTE","Vincule o recibo da entrega reservada ao VVB.");
                var delivery=dossiers.auditDelivery(c,project,input.dossier(),input.exportId(),kind.purpose(),"SIM-VVB",true);
                require(delivery.receipt().input().receivedAt().isEqual(at),"RECIBO_DIVERGENTE","Use a data efetiva do recibo da resposta.");
                replace(findings,input.finding(),"ABERTO","RESPONDIDO",event);
                proposed=input.dossier(); responseExport=input.exportId(); state="RESPOSTA_ENVIADA";
            }
            case "TX-E3-29" -> {
                state(old,"RESPOSTA_ENVIADA"); reason(input);
                require(input.dossier().equals(proposed) && input.accepted()!=null,"RESPOSTA_DIVERGENTE","Avalie a versão recebida.");
                var reviewed=findingPackage(c,project,old,input);
                requireGrant(c,project,old,reviewed,input.grantId());
                replace(findings,input.finding(),"RESPONDIDO",input.accepted()?"ATENDIDO":"ABERTO",event);
                if(input.accepted()) {pack=reviewed; grant=input.grantId();}
                proposed=null; state=findings.stream().allMatch(item->"ATENDIDO".equals(item.state()))?"EM_ANALISE":"COM_ACHADO";
            }
            case "TX-E3-30","TX-E3-32" -> {
                boolean positive="TX-E3-32".equals(step);
                require(positive?"EM_ANALISE".equals(state):List.of("EM_ANALISE","COM_ACHADO","RESPOSTA_ENVIADA").contains(state),
                        "AUDITORIA_PENDENTE","Confira o estado e os achados antes de emitir parecer.");
                requireGrant(c,project,old,pack,grant);
                reason(input);
                require(new IntakeModel.Ref(REPORT,1).equals(input.report()) && new IntakeModel.Ref(REPRESENTATION,1).equals(input.representation()),
                        "PARECER_INCOMPLETO","Vincule relatório e Representation emitidos pelo VVB.");
                List<String> vintages=opinionGuards(c,project,old,at,positive);
                state=positive?"PARECER_POSITIVO":"PARECER_NEGATIVO";
                opinions.add(new Opinion(input.dossier(),state,capture,input.report(),input.representation(),vintages,input.reason()));
            }
            case "TX-E3-31" -> {
                state(old,"PARECER_NEGATIVO"); reason(input);
                pack=successor(c,project,old,input.dossier());
                requireGrant(c,project,old,pack,input.grantId());
                grant=input.grantId(); proposed=null; state=findings.stream().allMatch(f->"ATENDIDO".equals(f.state()))?"EM_ANALISE":"COM_ACHADO";
                if ("COM_ACHADO".equals(state)) {
                    for(int i=0;i<findings.size();i++) {
                        var f=findings.get(i);
                        if("RESPONDIDO".equals(f.state())) findings.set(i,new Finding(f.id(),f.criterion(),f.reason(),f.dossier(),"ABERTO",event));
                    }
                }
            }
            case "REVISAR_SUCESSORA" -> {
                state(old,"PARECER_POSITIVO"); reason(input);
                pack=successor(c,project,old,input.dossier());
                requireGrant(c,project,old,pack,input.grantId());
                grant=input.grantId(); state="EM_ANALISE";
            }
            default -> throw invalid("ACAO_INVALIDA","Selecione uma etapa da auditoria.");
        }
        var latest = old.lastAt();
        if (!"VINCULAR_CONCESSAO".equals(step) && (latest == null || at.isAfter(latest))) latest = at;
        var next=new Audit(id,version+1,old.contract(),old.issuer(),pack,state,grant,proposed,responseExport,
                visit,risk,coverage,List.copyOf(findings),List.copyOf(opinions),openedAt,latest,event);
        update(c,"UPDATE pilot_vvb_audit SET state=?::jsonb WHERE project_id=? AND id=?",audit.encode(next),project,id);
        var result=audit.decode(audit.encode(view(next)));
        if(vvbAction) evidence.save(c,project,id,capture,digest,result);
        return result;
    }

    private List<String> opinionGuards(Connection c,String project,Audit old,OffsetDateTime at,boolean positive) throws SQLException {
        var kind=old.contract().kind();
        if (positive) {
            require(old.findings().stream().allMatch(f->"ATENDIDO".equals(f.state())), "ACHADOS_PENDENTES","Conclua a avaliação de cada achado.");
            if(kind==Kind.VAL) {
                var consult=consultation(c,project,old.contract().consultation());
                require(consult.closure()!=null && !at.isBefore(consult.closure().effectiveAt()) && first(c,"""
                        SELECT true FROM pilot_consultation_comment WHERE project_id=? AND consultation_id=?
                        AND (state->'received'->>'effectiveAt')::timestamptz<=?
                        AND (state->>'state'<>'AVALIADO' OR
                             (state#>>'{evaluations,-1,capture,effectiveAt}')::timestamptz>?) LIMIT 1
                        """,row->true,project,consult.id(),at,at)==null,"CONSULTA_PENDENTE","Comprove o encerramento e as avaliações aplicáveis da consulta.");
            }
            boolean sameRisk=old.risk()!=null && old.risk().dossier().equals(old.pack().pack().reference());
            if(kind==Kind.M2) require(sameRisk,"RISCO_PENDENTE","Registre o risco e a análise do histórico de M1 para este pacote.");
            boolean visitRequired=kind!=Kind.M2 || !old.risk().waived();
            require(!visitRequired||old.visit()!=null && old.visit().dossier().equals(old.pack().pack().reference()),
                    "VISITA_PENDENTE","Vincule a visita obrigatória à versão examinada.");
        }
        if(kind==Kind.VAL) return List.of();
        var closure=MonitoringClosure.readClosure(c,project,old.pack().closure());
        if(positive && old.visit()!=null && old.visit().dossier().equals(old.pack().pack().reference())
                && old.visit().capture().effectiveAt().isBefore(closure.endExclusive())) {
            require(old.coverage()!=null && old.coverage().dossier().equals(old.pack().pack().reference())
                    && old.coverage().detail().equals(closure.sha()),"COBERTURA_PENDENTE","Complete a evidência até o fim do período após visita antecipada.");
        }
        return closure.vintages();
    }

    DossierPackages.AuditPackage packageFor(Connection c,String project,Kind kind,DocumentRules.Ref ref) throws SQLException {
        var pack=dossiers.auditPackage(c,project,ref,kind.purpose(),true);
        require(kind==Kind.VAL || pack.closure()!=null&&kind.name().equals(pack.closure().period()),"PERIODO_DIVERGENTE",
                "Vincule o MR do período desta auditoria.");
        return pack;
    }

    private DossierPackages.AuditPackage findingPackage(Connection c,String project,Audit old,Action input) throws SQLException {
        if (!input.dossier().equals(old.pack().pack().reference())) return successor(c,project,old,input.dossier());
        var affected=old.findings().stream().filter(item->item.id().equals(input.finding())).findFirst().orElse(null);
        require(affected!=null&&affected.dossier().revision()<input.dossier().revision(),"SUCESSORA_AUSENTE",
                "A resposta precisa citar uma revisão posterior à que recebeu o achado.");
        return packageFor(c,project,old.contract().kind(),input.dossier());
    }

    private DossierPackages.AuditPackage successor(Connection c,String project,Audit old,DocumentRules.Ref ref) throws SQLException {
        var prior=old.pack().pack().reference();
        require(prior.id().equals(ref.id())&&ref.revision()>prior.revision(),"SUCESSORA_AUSENTE","Use a revisão sucessora do dossiê.");
        return packageFor(c,project,old.contract().kind(),ref);
    }

    void requireGrant(Connection c,String project,Audit old,DossierPackages.AuditPackage pack,UUID grant) throws SQLException {
        require(grant!=null && activeGrant(c,project,old.contract().vvb(),old.contract().kind(),pack,grant),"CONCESSAO_PENDENTE","Vincule concessão vigente e recibo para a variante reservada do pacote.");
    }

    static boolean activeGrant(Connection c,String project,String actor,Kind kind,DossierPackages.AuditPackage pack,UUID grant) throws SQLException {
        return first(c,"""
                SELECT true FROM pilot_grant g JOIN pilot_receipt r USING (project_id)
                WHERE r.grant_id=g.id AND g.project_id=? AND (CAST(? AS uuid) IS NULL OR g.id=?)
                  AND g.recipient_id=? AND g.purpose=? AND g.artifact_id=? AND g.artifact_version=?
                  AND g.granted_at<=clock_timestamp() AND g.expires_at>clock_timestamp() AND g.revoked_at IS NULL LIMIT 1
                """,row->true,project,grant,grant,actor,kind.grantPurpose(),pack.reservedArtifact(),pack.pack().reference().revision())!=null;
    }

    private ConsultationWorkflow.Consultation consultation(Connection c,String project,UUID id) throws SQLException {
        var result=first(c,"SELECT state FROM pilot_consultation WHERE project_id=? AND id=?",
                row->decode(row.getString(1),ConsultationWorkflow.Consultation.class),project,id);
        if(result==null) throw PilotAccess.invisible();
        return result;
    }

    private void requireRotation(Connection c,String project,Kind kind,String issuer) throws SQLException {
        if(kind!=Kind.M2)return;
        var m1=byKind(c,project,Kind.M1);
        require(m1!=null && "PARECER_POSITIVO".equals(m1.state()) && !m1.issuer().equals(issuer),"ROTACAO_VVB_REQUERIDA",
                "M2 exige o trabalho de M1 e um organismo distinto.");
    }

    private static boolean registrationActive(Connection c,String project,OffsetDateTime at) throws SQLException {
        return first(c,"""
                SELECT true FROM pilot_program_request WHERE project_id=? AND kind='REG' AND state->>'state'='APROVADO'
                AND (state->'registration'->>'effectiveAt')::timestamptz<=? LIMIT 1
                """,row->true,project,at)!=null;
    }

    private static void replace(List<Finding> items,UUID id,String old,String next,UUID event) {
        var match=items.stream().filter(f->f.id().equals(id)&&old.equals(f.state())).findFirst().orElse(null);
        require(match!=null,"ACHADO_DIVERGENTE","Selecione o achado correspondente a esta etapa.");
        items.set(items.indexOf(match),new Finding(match.id(),match.criterion(),match.reason(),match.dossier(),next,event));
    }

    Audit get(Connection c,String project,UUID id) throws SQLException {
        var result=first(c,"SELECT state FROM pilot_vvb_audit WHERE project_id=? AND id=?",row->decode(row.getString(1),Audit.class),project,id);
        if(result==null)throw PilotAccess.invisible();
        return result;
    }

    private Audit byKind(Connection c,String project,Kind kind)throws SQLException{
        return first(c,"SELECT state FROM pilot_vvb_audit WHERE project_id=? AND kind=?",row->decode(row.getString(1),Audit.class),project,kind.name());
    }

    static void verify(String step,Proof proof,String target,String source){
        if(proof==null||!IntakeRules.id(proof.officialId())||!target.equals(proof.target())||!new IntakeModel.Ref(source,1).equals(proof.evidence())){
            throw invalid("PROVA_DIVERGENTE","Vincule a prova específica ao alvo e ao ato "+step+".");
        }
    }
    static String target(UUID id){return "SIM-AUD-"+id.toString().toUpperCase(java.util.Locale.ROOT);}
    private static View view(Audit item){return new View(item,target(item.id()));}
    private static void state(Audit bean,String value){require(value.equals(bean.state()),"TRANSICAO_INDISPONIVEL","A auditoria precisa estar em "+value+".");}
    private static void reason(Action input){if(!IntakeRules.text(input.reason())) throw invalid("MOTIVO_AUSENTE","Informe a justificativa e o escopo da prova.");}
    static boolean valid(DocumentRules.Ref ref){return ref!=null&&ref.id()!=null&&ref.revision()>0;}
    private <T>T decode(String json,Class<T> type){
        try{return mapper.readValue(json,type);}
        catch (java.io.IOException error){throw new IllegalStateException("Falha ao ler auditoria",error);}
    }
}
