package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.ListingEvidence.*;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class ProgramRequests {
    static final String NAMESPACE="SIM-VERRA-PEDIDO";
    static final String VVB_NAMESPACE="SIM-VVB-RODADA";
    static final String FEE="SIM-PROVA-TAXA-PEDIDO";
    static final String REUSE="SIM-PROVA-REUSO-180";
    static final String READMIT="SIM-PROVA-NOVA-TENTATIVA-PEDIDO";
    static final Map<String,String> SOURCES=Map.of(
            "TX-E3-34","SIM-PROVA-REVISAO-PEDIDO", "TX-E3-35","SIM-PROVA-RELATORIO-RODADA",
            "TX-E3-36","SIM-PROVA-RESPOSTA-VVB", "TX-E3-37","SIM-PROVA-APROVACAO-PEDIDO",
            "TX-E3-38","SIM-PROVA-DEVOLUCAO-PEDIDO", "TX-E3-39","SIM-PROVA-REJEICAO-PEDIDO",
            "REGISTRO_COMPROVADO","SIM-PROVA-REGISTRO-EFETIVO");

    enum Kind {
        REG, V1, V2;
        VvbAuditWorkflow.Kind audit() { return switch (this) { case REG->VvbAuditWorkflow.Kind.VAL; case V1->VvbAuditWorkflow.Kind.M1; case V2->VvbAuditWorkflow.Kind.M2; }; }
    }
    record Create(Kind kind, UUID audit, DocumentRules.Ref dossier, UUID predecessor, String reason) {}
    record Action(String transitionId,String mode,OffsetDateTime effectiveAt,DocumentRules.Ref dossier, String target,
                  UUID exportId, UUID grantId, Proof proof, String reason, IntakeModel.Ref fee, IntakeModel.Ref reuse, IntakeModel.Ref admission) {}
    record Round(int number,Capture report,String finding,Capture response,DocumentRules.Ref responsePackage,UUID responseReceipt) {}
    record Submission(UUID exportId,String receipt,String destination,String sha256,UUID opinionEvent,IntakeModel.Ref fee) {}
    record ChannelCheck(ListingWorkflow.CheckInput input, String actor, UUID eventId) {}
    record Request(UUID id,int version,Kind kind,UUID audit,String vvb,String issuer,DossierPackages.AuditPackage pack,UUID predecessor,
                   String state,String target,ChannelCheck check,Submission submission,List<Round> rounds,Capture decision,Capture registration,
                   OffsetDateTime lastAt,UUID eventId) {}
    record View(Request request,OffsetDateTime responseDue,OffsetDateTime reuseUntil,OffsetDateTime registrationResubmitUntil,
                boolean overdue,boolean realSubmissionAllowed,boolean emissionReady) {}

    private final PilotDatabase database;
    private final PilotCommands commands;
    private final PilotAudit audit;
    private final ProjectIntake intake;
    private final VvbAuditWorkflow audits;
    private final DossierPackages dossiers;
    private final ListingEvidence evidence;
    private final ObjectMapper mapper;

    ProgramRequests(PilotDatabase database,PilotCommands commands,PilotAudit audit,ProjectIntake intake,VvbAuditWorkflow audits,
                    DossierPackages dossiers,ListingEvidence evidence,ObjectMapper mapper){
        this.database=database;this.commands=commands;this.audit=audit;this.intake=intake;this.audits=audits;
        this.dossiers=dossiers;this.evidence=evidence;this.mapper=mapper;
    }

    List<View> list(PilotAccess.Scope scope,String actor,OffsetDateTime asOf){
        String vvb=scope.has(PilotAccess.VVB)?actor:null;
        return database.read(connection -> {
            var rows = query(connection, """
                    SELECT state FROM pilot_program_request
                    WHERE project_id=? AND (CAST(? AS text) IS NULL OR state->>'vvb'=?)
                    ORDER BY kind,(state->>'lastAt') DESC NULLS LAST,id
                    """, row -> decode(row.getString(1)), scope.project(), vvb, vvb);
            var results = new ArrayList<View>();
            for (var request : rows) {
                if (vvb == null || VvbAuditWorkflow.activeGrant(connection, scope.project(), vvb,
                        request.kind().audit(), request.pack(), null)) {
                    results.add(view(request, asOf));
                }
            }
            return List.copyOf(results);
        });
    }

    JsonNode create(PilotAccess.Scope scope,String actor,String key,Create input){
        if(input==null||input.kind()==null||input.audit()==null||!VvbAuditWorkflow.valid(input.dossier()))
            throw invalid("PEDIDO_INCOMPLETO","Selecione finalidade, auditoria e pacote exato.");
        return commands.execute(scope,actor,"PREPARAR_PEDIDO",key,audit.encode(input),null,c->{
            var project=scope.project();intake.lockProject(c,project);
            var work=audits.get(c,project,input.audit());
            require(input.kind().audit()==work.contract().kind(),"AUDITORIA_DIVERGENTE","Selecione a auditoria deste pedido.");
            var pack=audits.packageFor(c,project,input.kind().audit(),input.dossier());
            var prior=first(c,"""
                    SELECT state FROM pilot_program_request p WHERE project_id=? AND kind=? AND NOT EXISTS (
                       SELECT 1 FROM pilot_program_request n WHERE n.project_id=p.project_id AND n.predecessor=p.id)
                    """,row->decode(row.getString(1)),project,input.kind().name());
            require(prior==null ? input.predecessor()==null : prior.id().equals(input.predecessor())
                            && List.of("DEVOLVIDO","REJEITADO").contains(prior.state()) && IntakeRules.text(input.reason()),
                    "ANTECESSOR_DIVERGENTE","Continue a tentativa em curso ou vincule a antecessora encerrada e o motivo.");
            UUID id=UUID.randomUUID();
            UUID event=audit.record(c,project,actor,"PREPARAR_PEDIDO","PERMITIDO",id.toString(),input);
            var request=new Request(id,1,input.kind(),work.id(),work.contract().vvb(),work.issuer(),pack,input.predecessor(),
                    "NOVO",null,null,null,List.of(),null,null,null,event);
            update(c,"INSERT INTO pilot_program_request VALUES (?,?,?,?,?,?::jsonb)",project,id,input.kind().name(),work.id(),input.predecessor(),audit.encode(request));
            return view(request,null);
        });
    }

    JsonNode check(PilotAccess.Scope scope,String actor,String key,UUID id,int version,ListingWorkflow.CheckInput input){
        if(input==null||!VvbAuditWorkflow.valid(input.dossier())||input.channel()==null||input.checkedAt()==null
                ||!DocumentRules.EDITION.equals(input.documentEdition())||!"VM0038 v1.1".equals(input.methodologyEdition())
                ||!input.channel().evidence().equals(input.source())
                || (input.power()!=null && (!new IntakeModel.Ref(ListingWorkflow.POWER,1).equals(input.power())||input.powerUntilExclusive()==null))
                || (input.power()==null && input.powerUntilExclusive()!=null))
            throw invalid("CONFERENCIA_INCOMPLETA","Comprove canal, edição e vigência aplicáveis ao pacote.");
        return commands.execute(scope,actor,"CONFERIR_CANAL_PEDIDO",key,audit.encode(List.of(id,version,input)),id.toString(),c->{
            String project=scope.project();intake.lockProject(c,project);
            var old=get(c,project,id);PilotCommands.requireVersion(version,old.version());
            require(List.of("NOVO","COM_ACHADO").contains(old.state()),"CONFERENCIA_INDISPONIVEL","Confira o canal na preparação ou na rodada aberta.");
            var pack=audits.packageFor(c,project,old.kind().audit(),input.dossier());
            require(old.state().equals("NOVO") ? (pack.pack().reference().equals(old.pack().pack().reference()) || revisionOf(old, pack))
                    : revisionOf(old, pack), "PACOTE_DIVERGENTE","Confira a versão exata e a sucessão do dossiê.");
            UUID event=audit.record(c,project,actor,"CONFERIR_CANAL_PEDIDO","PERMITIDO",id.toString(),input);
            var next=new Request(id,version+1,old.kind(),old.audit(),old.vvb(),old.issuer(),
                    old.state().equals("NOVO")?pack:old.pack(),old.predecessor(),old.state(),old.target(),
                    new ChannelCheck(input,actor,event),old.submission(),old.rounds(),old.decision(),old.registration(),old.lastAt(),event);
            return view(save(c,project,next),null);
        });
    }

    JsonNode act(PilotAccess.Scope scope,String actor,String key,UUID id,int version,Action input){
        if(input==null||input.transitionId()==null||!(SOURCES.containsKey(input.transitionId())||List.of("TX-E3-33","TX-E3-40").contains(input.transitionId()))
                ||!VvbAuditWorkflow.valid(input.dossier()))
            throw invalid("ACAO_INVALIDA","Selecione etapa e versão do pacote.");
        if(!ListingWorkflow.SIMULATION.equals(input.mode())) throw invalid("USO_REAL_BLOQUEADO","Use apenas provas sintéticas.");
        var at=local(input.effectiveAt());
        return commands.execute(scope,actor,input.transitionId(),key,audit.encode(List.of(id,version,input)),id.toString(),
                c->apply(c,scope.project(),actor,id,version,input,at));
    }

    private Object apply(Connection c,String project,String actor,UUID id,int version,Action input,OffsetDateTime at)throws SQLException{
        intake.lockProject(c,project);
        var old=get(c,project,id);
        String step=input.transitionId();
        boolean official=SOURCES.containsKey(step), vvb="TX-E3-36".equals(step);
        String issuer=vvb?old.issuer():VERRA, namespace=vvb?VVB_NAMESPACE:NAMESPACE;
        if(vvb&&!actor.equals(old.vvb())) throw PilotAccess.invisible();
        String digest=null;
        if(official){
            require(old.target()!=null,"PROTOCOLO_PENDENTE","Vincule a solicitação e o recibo antes de capturar um ato.");
            VvbAuditWorkflow.verify(step,input.proof(),old.target(),SOURCES.get(step));
            digest=evidence.digest(Arrays.asList(step,input.dossier(),input.proof().target(),at.toInstant(),input.reason(),input.exportId()));
            var replay=evidence.repeated(c,project,id,issuer,namespace,input.proof(),digest);
            if(replay!=null)return replay;
        }
        PilotCommands.requireVersion(version,old.version());
        require(old.lastAt()==null||!at.isBefore(old.lastAt()),"DATA_RETROATIVA","Confira a ordem dos atos comprovados.");
        if(!vvb)require(old.pack().pack().reference().equals(input.dossier()),"PACOTE_DIVERGENTE","A prova deve ser da versão em tramitação.");
        var state=old.state();var pack=old.pack();var target=old.target();var submission=old.submission();
        var rounds=new ArrayList<>(old.rounds());var decision=old.decision();var registration=old.registration();
        UUID event=audit.record(c,project,actor,step,"PERMITIDO",id.toString(),
                Map.of("input",input,"effectiveAt",at,"before",state,"version",version,"issuer",official?issuer:"PP"));
        Capture capture=official?new Capture(issuer,namespace,input.proof(),at,actor,event):null;
        switch(step){
            case "TX-E3-33","TX-E3-40" -> {
                require("NOVO".equals(state)&&IntakeRules.id(input.target())&&input.exportId()!=null,
                        "PROTOCOLO_INCOMPLETO","Use tentativa nova, identificador externo e recibo.");
                require(("TX-E3-40".equals(step))==(old.predecessor()!=null),"ANTECESSOR_DIVERGENTE","Use a transição da tentativa.");
                var work=audits.get(c,project,old.audit());
                require("PARECER_POSITIVO".equals(work.state())&&work.pack().pack().reference().equals(input.dossier())
                                && !at.isBefore(work.opinions().getLast().capture().effectiveAt()),"PARECER_PENDENTE","Vincule o parecer positivo do pacote exato.");
                pack=audits.packageFor(c,project,old.kind().audit(),input.dossier());
                renewal(c,project,old,input,at);
                require(new IntakeModel.Ref(FEE,1).equals(input.fee()),"TAXA_PENDENTE","Registre a prova sintética da taxa aplicável.");
                var delivery=deliver(c,project,old,input,at);
                require(update(c,"INSERT INTO pilot_program_request_target VALUES (?,?,?) ON CONFLICT DO NOTHING",input.target(),project,id)==1,
                        "ALVO_JA_VINCULADO","Use o identificador externo próprio desta tentativa.");
                useReceipt(c,project,id,input.exportId());
                submission=new Submission(input.exportId(),delivery.receipt().input().code(),delivery.export().destination(),delivery.export().sha256(),
                        work.opinions().getLast().capture().eventId(),input.fee());
                target=input.target();state="PROTOCOLADO";
                registerDependency(c,project,actor,pack,target,"DECISAO",state,event);
            }
            case "TX-E3-34" -> {require("PROTOCOLADO".equals(state),"TRANSICAO_INDISPONIVEL","A revisão exige protocolo confirmado.");state="EM_REVISAO";}
            case "TX-E3-35" -> {
                require("EM_REVISAO".equals(state)&&rounds.size()<3,"RODADA_INDISPONIVEL","A revisão admite até três rodadas.");
                reason(input);
                rounds.add(new Round(rounds.size()+1,capture,input.reason(),null,null,null));state="COM_ACHADO";
            }
            case "TX-E3-36" -> {
                require("COM_ACHADO".equals(state)&&input.exportId()!=null,"RODADA_PENDENTE","Selecione uma rodada aberta e o recibo da resposta.");
                reason(input);
                var round=rounds.getLast();
                require(!at.isAfter(round.report().effectiveAt().plusDays(90)),"PRAZO_DE_RESPOSTA_VENCIDO","Confira a decisão comprovada sobre a rodada vencida.");
                var amended=audits.packageFor(c,project,old.kind().audit(),input.dossier());
                require(revisionOf(old, amended)&&amended.compared(),"SUCESSORA_AUSENTE","Use a revisão sucessora, limpa e comparada.");
                var work=audits.get(c,project,old.audit());
                audits.requireGrant(c,project,work,amended,input.grantId());
                deliver(c,project,old,input,at);
                useReceipt(c,project,id,input.exportId());
                rounds.set(rounds.size()-1,new Round(round.number(),round.report(),round.finding(),capture,input.dossier(),input.exportId()));
                pack=amended;state="EM_REVISAO";
            }
            case "TX-E3-37","TX-E3-38","TX-E3-39" -> {
                require(List.of("PROTOCOLADO","EM_REVISAO","COM_ACHADO").contains(state),"DECISAO_INDISPONIVEL","Confira a etapa e o alvo da decisão.");
                if(!"TX-E3-37".equals(step)) reason(input);
                state=switch(step){case "TX-E3-37"->"APROVADO";case "TX-E3-38"->"DEVOLVIDO";default->"REJEITADO";};
                decision=capture;
                if ("APROVADO".equals(state)) registerDependency(c,project,actor,pack,target,"ATO_EXTERNO",state,event);
            }
            case "REGISTRO_COMPROVADO" -> {
                require(old.kind()==Kind.REG&&"APROVADO".equals(state)&&registration==null,"REGISTRO_INDISPONIVEL",
                        "Comprove o registro efetivo do pedido REG aprovado.");
                registration=capture;
            }
            default -> throw invalid("ACAO_INVALIDA","Selecione uma etapa do pedido.");
        }
        var next=new Request(id,version+1,old.kind(),old.audit(),old.vvb(),old.issuer(),pack,old.predecessor(),state,target,old.check(),
                submission,List.copyOf(rounds),decision,registration,at,event);
        save(c,project,next);
        var view=audit.decode(audit.encode(view(next,at)));
        if(official)evidence.save(c,project,id,capture,digest,view);
        return view;
    }

    private void renewal(Connection c,String project,Request request,Action input,OffsetDateTime at)throws SQLException{
        if(request.predecessor()==null)return;
        var previous=get(c,project,request.predecessor());
        require(!at.isBefore(previous.decision().effectiveAt()),"DATA_RETROATIVA","Confira a data da decisão anterior.");
        boolean inside="DEVOLVIDO".equals(previous.state())&&!at.isAfter(previous.decision().effectiveAt().plusDays(180));
        if(Objects.equals(previous.pack().pack().reference(),input.dossier())){
            require(inside&&new IntakeModel.Ref(REUSE,1).equals(input.reuse()),"REUSO_INDISPONIVEL",
                    "O reaproveitamento exige decisão aplicável, prova expressa e até 180 dias após devolução.");
        }
        if("REJEITADO".equals(previous.state()))require(new IntakeModel.Ref(READMIT,1).equals(input.admission()),"ADMISSAO_PENDENTE",
                "Junte a comprovação de admissão após rejeição.");
    }

    private DossierPackages.ListingDelivery deliver(Connection c,String project,Request request,Action input,OffsetDateTime at)throws SQLException{
        var check=request.check()==null?null:request.check().input();
        require(check!=null&&check.channel()!=ListingWorkflow.Channel.INDETERMINADO&&check.dossier().equals(input.dossier())
                        &&!at.isBefore(check.checkedAt()),"CANAL_INDETERMINADO","Confirme a rota e a edição para a revisão enviada.");
        require(check.power()!=null&&at.isBefore(check.powerUntilExclusive()),"PODER_INDISPONIVEL","Confirme a prova vigente de poder.");
        var delivery=dossiers.listingDelivery(c,project,input.dossier(),input.exportId(),request.kind().audit().purpose(),check.channel().destination(),true);
        require(delivery.receipt().input().receivedAt().isEqual(at),"RECIBO_DIVERGENTE","Confira a data e o recibo aplicáveis.");
        return delivery;
    }

    private void registerDependency(Connection c,String project,String actor,DossierPackages.AuditPackage pack,
                                    String target,String type,String state,UUID event)throws SQLException{
        if (pack.closure()==null) return;
        var ref=new MonitoringPeriods.DependentInput(type,target,1,state,new IntakeModel.Ref("SIM-PROVA-"+type.replace('_','-'),1));
        var entry=new MonitoringPeriods.Dependent(ref,actor,event,pack.pack().sha256());
        update(c,"INSERT INTO pilot_period_dependent VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                project,pack.closure().period(),pack.closure().revision(),type,target,1,audit.encode(entry));
    }

    private static void useReceipt(Connection c,String project,UUID id,UUID export)throws SQLException{
        require(update(c,"INSERT INTO pilot_program_request_receipt VALUES (?,?,?) ON CONFLICT DO NOTHING",project,export,id)==1,
                "RECIBO_REUTILIZADO","Este recibo já está vinculado ao efeito de um pedido.");
    }

    private static boolean revisionOf(Request old,DossierPackages.AuditPackage pack){
        var from=old.pack().pack().reference();var next=pack.pack().reference();
        return from.id().equals(next.id())&&from.revision()<next.revision();
    }

    Request get(Connection c,String project,UUID id)throws SQLException{
        var result=first(c,"SELECT state FROM pilot_program_request WHERE project_id=? AND id=?",row->decode(row.getString(1)),project,id);
        if(result==null)throw PilotAccess.invisible();return result;
    }

    private Request save(Connection c,String project,Request request)throws SQLException{
        update(c,"UPDATE pilot_program_request SET state=?::jsonb WHERE project_id=? AND id=?",audit.encode(request),project,request.id());
        return request;
    }

    private static View view(Request r,OffsetDateTime asOf){
        var due="COM_ACHADO".equals(r.state())?r.rounds().getLast().report().effectiveAt().plusDays(90):null;
        var reuse="DEVOLVIDO".equals(r.state())?r.decision().effectiveAt().plusDays(180):null;
        return new View(r,due,reuse,reuse!=null&&r.kind()==Kind.REG?r.decision().effectiveAt().plusYears(1):null,
                due!=null&&asOf!=null&&asOf.isAfter(due),false,false);
    }

    private static void reason(Action action){if(!IntakeRules.text(action.reason()))throw invalid("MOTIVO_AUSENTE","Informe o conteúdo e o motivo do ato.");}

    private Request decode(String raw){
        try{return mapper.readValue(raw,Request.class);}
        catch(java.io.IOException e){throw new IllegalStateException("Falha ao ler pedido",e);}
    }
}
