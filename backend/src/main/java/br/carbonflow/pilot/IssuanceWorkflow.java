package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.IssuanceLedger.*;
import static br.carbonflow.pilot.IssuanceModel.*;
import static br.carbonflow.pilot.ListingEvidence.Capture;
import static br.carbonflow.pilot.ListingEvidence.VERRA;
import static br.carbonflow.pilot.ListingEvidence.PAYMENT;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class IssuanceWorkflow {
    private static final String RESOLUTION="SIM-PROVA-RESOLUCAO-EMISSAO";
    private static final List<String> STATES_IN_PROGRESS=List.of("PROTOCOLADO","AGUARDANDO_PAGAMENTO","AGUARDANDO_DEPOSITO",
            "DEPOSITO_REGISTRADO","CONCILIADO","COM_DIVERGENCIA");
    private static final List<String> ACTIONS=List.of("TX-E3-49","TX-E3-50","TX-E3-51","TX-E3-52","TX-E3-53","TX-E3-54",
            "TX-E3-55","TX-E3-56","TX-E3-57","AJUSTE","LIBERAR_REMANESCENTE","COMPARAR");

    private final PilotDatabase database;
    private final PilotCommands commands;
    private final PilotAudit audit;
    private final ProjectIntake intake;
    private final IssuanceLedger ledger;
    private final ListingEvidence evidence;
    private final RecoveryWorkflow recovery;

    IssuanceWorkflow(PilotDatabase database,PilotCommands commands,PilotAudit audit,ProjectIntake intake,IssuanceLedger ledger,
                     ListingEvidence evidence,RecoveryWorkflow recovery){
        this.database=database;this.commands=commands;this.audit=audit;this.intake=intake;this.ledger=ledger;this.evidence=evidence;this.recovery=recovery;
    }

    static Map<String,Object> options(){
        return Map.of("unit",UNIT,"accounts",ACCOUNTS,"proofs",PROOFS,"power",ref(POWER),"resolution",ref(RESOLUTION),
                "routes",List.of(ListingWorkflow.Channel.ARQUIVO,ListingWorkflow.Channel.DIGITAL_HUMANO,ListingWorkflow.Channel.INDETERMINADO));
    }

    List<Snapshot> list(String project){
        return database.transaction(c->ledger.attempts(c,project,null).stream().map(item->snapshot(item,null)).toList());
    }

    List<PilotAudit.Event> history(String project,UUID id){
        return database.transaction(c->{
            ledger.getAttempt(c,project,id);
            return query(c,"""
                    SELECT id,actor_id,action,outcome,target,occurred_at,details FROM pilot_access_event
                    WHERE project_id=? AND target=? ORDER BY occurred_at DESC,id DESC LIMIT 100
                    """,row->new PilotAudit.Event(row.getObject(1,UUID.class),row.getString(2),row.getString(3),row.getString(4),
                    row.getString(5),row.getObject(6,OffsetDateTime.class),audit.decode(row.getString(7))),project,id.toString());
        });
    }

    JsonNode create(PilotAccess.Scope scope,String actor,String key,Create input){
        if(input==null||input.verification()==null||!UNIT.equals(input.unit())||input.account()==null||!ACCOUNTS.contains(input.account())
                ||input.lines()==null||input.lines().isEmpty()||input.lines().size()>10)
            throw invalid("PEDIDO_INCOMPLETO","Selecione verificação, conta autorizada e linhas em unidade fictícia.");
        var at=officialTime(input.effectiveAt());
        var keys=new HashSet<Vintage>();
        for(var line:input.lines()){
            require(line!=null&&valid(line.vintage())&&line.position()!=null&&keys.add(line.vintage()),
                    "LINHAS_INVALIDAS","Indique os recortes sem repetir e a posição de cada linha.");
            require(decimal(line.amount(),false).signum()>0,"QUANTIDADE_INVALIDA","A quantidade solicitada deve ser positiva.");
        }
        return commands.execute(scope,actor,"TX-E3-48",key,audit.encode(input),input.verification().toString(),c->{
            String project=scope.project();intake.lockProject(c,project);
            if(input.recoveryProtocol()==null)RecoveryWorkflow.requireIssuanceOpen(c,project);
            var verification=ledger.active(c,project,input.verification(),at);
            UUID id=input.recoveryProtocol()==null?UUID.randomUUID():recovery.requireObservedProtocol(c,project,input.recoveryProtocol(),
                    verification.registryId(),input.account(),null,input.predecessor(),input.lines());
            if(input.recoveryProtocol()!=null)
                require(first(c,"SELECT true FROM pilot_issuance WHERE project_id=? AND id=?",row->true,project,id)==null,
                        "TENTATIVA_JA_PRESERVADA","Selecione a tentativa já preservada para vincular o protocolo observado.");
            if(input.predecessor()!=null){
                var prior=ledger.getAttempt(c,project,input.predecessor());
                require(prior.verification().equals(verification.verification())&&prior.releasedAt()!=null
                                &&List.of("ENCERRADO_SEM_EMISSAO","CONCILIADO").contains(prior.state()),
                        "ANTECESSOR_PENDENTE","A reemissão exige efeito anterior concluído e posição atualizada.");
            }
            checkBalances(c,project,verification,input.lines(),at);
            UUID event=audit.record(c,project,actor,"TX-E3-48","PERMITIDO",id.toString(),input);
            var record=new Issuance(id,1,input.verification(),"EM_PREPARO",null,input.account(),input.predecessor(),
                    List.copyOf(input.lines()),null,null,null,null,List.of(),List.of(),null,List.of(),null,null,at,event);
            update(c,"INSERT INTO pilot_issuance VALUES (?,?,?,?,?,?::jsonb)",project,id,input.verification(),input.predecessor(),null,audit.encode(record));
            return snapshot(record,null);
        });
    }

    JsonNode check(PilotAccess.Scope scope,String actor,String key,UUID id,int version,ChannelInput input){
        if(input==null||input.route()==null||input.checkedAt()==null||!input.route().evidence().equals(input.source())
                ||(input.power()!=null&&(!ref(POWER).equals(input.power())||input.powerUntilExclusive()==null))
                ||(input.power()==null&&input.powerUntilExclusive()!=null))
            throw invalid("CONFERENCIA_INCOMPLETA","Confira a rota simulada, o poder e a vigência.");
        return commands.execute(scope,actor,"CONFERIR_CANAL_EMISSAO",key,audit.encode(List.of(id,version,input)),id.toString(),c->{
            var project=scope.project();intake.lockProject(c,project);
            var old=ledger.getAttempt(c,project,id);PilotCommands.requireVersion(version,old.version());
            require("EM_PREPARO".equals(old.state()),"CONFERENCIA_INDISPONIVEL","Confira o canal enquanto o pedido estiver em preparo.");
            UUID event=audit.record(c,project,actor,"CONFERIR_CANAL_EMISSAO","PERMITIDO",id.toString(),input);
            var next=new Issuance(id,version+1,old.verification(),old.state(),old.externalTarget(),old.account(),old.predecessor(),old.lines(),
                    new Check(input,event),old.protocol(),old.invoice(),old.payment(),old.deposits(),old.adjustments(),old.releasedAt(),
                    old.incidents(),old.comparison(),old.reviewEvent(),old.lastAt(),event);
            save(c,project,next);
            return snapshot(next,null);
        });
    }

    JsonNode act(PilotAccess.Scope scope,String actor,String key,UUID id,int version,Action input) {
        if(input==null||input.transitionId()==null||!ACTIONS.contains(input.transitionId()))
            throw invalid("ACAO_INVALIDA","Escolha a etapa de emissão.");
        if(!ListingWorkflow.SIMULATION.equals(input.mode()))throw invalid("USO_REAL_BLOQUEADO","Use comprovantes de simulação.");
        if(input.captureChannel()==ListingWorkflow.Channel.INDETERMINADO)
            throw invalid("CAPTURA_INDETERMINADA","Identifique o meio da prova externa.");
        var at=officialTime(input.effectiveAt());
        return commands.execute(scope,actor,input.transitionId(),key,audit.encode(List.of(id,version,input)),id.toString(),
                c->apply(c,scope.project(),actor,id,version,input,at));
    }

    private Object apply(Connection c,String project,String actor,UUID id,int version,Action input,OffsetDateTime at)throws SQLException{
        intake.lockProject(c,project);
        var old=ledger.getAttempt(c,project,id);
        String step=input.transitionId();
        boolean official=PROOFS.containsKey(step);
        String authority=switch(step){case "TX-E3-49"->"PP";case "TX-E3-51"->PAYMENT;case "TX-E3-52","AJUSTE"->REGISTRY;default->VERRA;};
        String namespace=namespace(step);
        List<Line> lines=input.lines()==null?List.of():canonical(input.lines(),"AJUSTE".equals(step));
        BigDecimal fee=input.feeAmount()==null?null:decimal(input.feeAmount(),false);
        String digest=null;

        if(official){
            proof(step,input.proof());
            Object terms=switch(step){
                case "TX-E3-50"->Arrays.asList(lines,fee,input.currency());
                case "TX-E3-51"->Arrays.asList(input.referenceAct(),fee,input.currency());
                case "TX-E3-52"->lines;
                case "AJUSTE"->Arrays.asList(input.referenceAct(),lines);
                case "TX-E3-55","TX-E3-56","TX-E3-57","LIBERAR_REMANESCENTE"->Arrays.asList(input.referenceAct(),input.reason());
                default->List.of();
            };
            digest=evidence.digest(Arrays.asList(step,input.proof().target(),at.toInstant(),input.account(),terms));
            try{
                var replay=evidence.repeated(c,project,id,authority,namespace,input.proof(),digest);
                if(replay!=null)return replay;
            }catch(PilotProblem failure){
                if(!"ATO_CONFLITANTE".equals(failure.code)||old.protocol()==null)throw failure;
                return incident(c,project,actor,old,"ATO_CONFLITANTE","A identidade do ato já tem outro alvo ou conteúdo.",input,digest,null);
            }
            RecoveryWorkflow.requireObservedAct(c,project,authority,namespace,input.proof().officialId(),digest);
        }
        PilotCommands.requireVersion(version,old.version());
        require(!at.isBefore(old.lastAt())||List.of("TX-E3-52","AJUSTE").contains(step),"DATA_RETROATIVA",
                "Confira a ordem dos atos comprovados. Depósitos e ajustes tardios exigem o vínculo original.");
        if(official&&!"TX-E3-49".equals(step)){
            require(old.protocol()!=null,"PROTOCOLO_PENDENTE","Vincule primeiro o recibo do pedido.");
            if(!old.externalTarget().equals(input.proof().target())||!old.account().equals(input.account())){
                return incident(c,project,actor,old,"ALVO_OU_CONTA_DIVERGENTE","O alvo ou a conta diverge do protocolo.",input,digest,null);
            }
        }
        if("TX-E3-54".equals(step)){
            require(STATES_IN_PROGRESS.contains(old.state())&&ref("SIM-PROVA-DIVERGENCIA-EMISSAO").equals(input.support()),
                    "DIVERGENCIA_SEM_PROVA","Vincule a evidência da divergência do pedido protocolado.");
            reason(input);
            return incident(c,project,actor,old,"DIVERGENCIA_REVISADA",input.reason(),input,evidence.digest(input),null);
        }
        if("TX-E3-52".equals(step)&&!validDeposit(old,lines,at))
            return incident(c,project,actor,old,"DEPOSITO_DIVERGENTE","Confira estágio, valor e vintages do depósito.",input,digest,null);
        if("AJUSTE".equals(step)&&!validAdjustment(old,lines,input.referenceAct(),at))
            return incident(c,project,actor,old,"AJUSTE_DIVERGENTE","Vincule o ajuste à linha do depósito original, sem saldo líquido inválido.",input,digest,null);

        UUID adjustmentPosition=null;
        if("AJUSTE".equals(step)&&input.newPosition()!=null){
            require(lines.getFirst().vintage().equals(input.newPosition().vintage())&&!officialTime(input.newPosition().asOf()).isBefore(at),
                    "POSICAO_DIVERGENTE","A posição do ajuste deve ser do mesmo recorte e contemporânea ao ato.");
            var saved=ledger.insertPosition(c,project,actor,old.verification(),input.newPosition());
            if(saved.hasNonNull("conflict"))return snapshot(old,saved.path("conflict").asText());
            adjustmentPosition=UUID.fromString(saved.at("/position/id").asText());
        }

        var state=old.state();var external=old.externalTarget();var protocol=old.protocol();
        var invoice=old.invoice();var payment=old.payment();var released=old.releasedAt();
        var deposits=new ArrayList<>(old.deposits());var adjustments=new ArrayList<>(old.adjustments());
        var incidents=new ArrayList<>(old.incidents());Comparison comparison=null;UUID review=old.reviewEvent();
        var metadata=new java.util.LinkedHashMap<String,Object>();
        metadata.put("input",input);metadata.put("effectiveAt",at);metadata.put("before",state);metadata.put("version",version);
        metadata.put("authority",official?authority:actor);
        if("TX-E3-49".equals(step))metadata.put("submitted",Map.of("verification",old.verification(),"account",old.account(),"lines",old.lines(),"unit",UNIT));
        UUID event=audit.record(c,project,actor,step,"PERMITIDO",id.toString(),metadata);
        var capture=official?new Capture(authority,namespace,input.proof(),at,actor,event):null;

        switch(step){
            case "TX-E3-49"->{
                state(old,"EM_PREPARO");
                require(IntakeRules.id(input.target())&&input.target().equals(input.proof().target())&&old.account().equals(input.account()),
                        "RECIBO_DIVERGENTE","Confira o alvo e a conta no recibo do pedido.");
                var check=old.check()==null?null:old.check().input();
                require(check!=null&&check.route()!=ListingWorkflow.Channel.INDETERMINADO&&!at.isBefore(check.checkedAt()),
                        "CANAL_INDETERMINADO","Confirme a rota do pedido simulado.");
                require(check.power()!=null&&at.isBefore(check.powerUntilExclusive()),"PODER_INDISPONIVEL","Comprove o poder vigente.");
                var active=ledger.active(c,project,old.verification(),at);
                if(RecoveryWorkflow.isOpen(c,project))
                    require(old.id().equals(recovery.requireObservedProtocol(c,project,input.proof().officialId(),active.registryId(),
                            input.account(),input.target(),old.predecessor(),old.lines())), "PEDIDO_INTERVALO_DIVERGENTE", "Use a tentativa preservada no intervalo.");
                checkBalances(c,project,active,old.lines(),at);
                require(first(c,"SELECT true FROM pilot_issuance WHERE external_target=?",row->true,input.target())==null,
                        "ALVO_JA_VINCULADO","Use o identificador de uma tentativa própria.");
                protocol=capture;external=input.target();state="PROTOCOLADO";
                linkPeriod(c,project,actor,active.closure(),external,"DECISAO",state,event);
            }
            case "TX-E3-50"->{
                state(old,"PROTOCOLADO");
                require(matches(old,lines)&&fee!=null&&fee.signum()>0&&"BRL".equals(input.currency()),
                        "COBRANCA_DIVERGENTE","Confira a quantidade cobrada, o montante e a moeda de ensaio.");
                invoice=new Invoice(capture,fee,input.currency());state="AGUARDANDO_PAGAMENTO";
            }
            case "TX-E3-51"->{
                state(old,"AGUARDANDO_PAGAMENTO");
                require(invoice!=null&&invoice.capture().proof().officialId().equals(input.referenceAct())&&fee!=null
                                &&invoice.amount().compareTo(fee)==0&&invoice.currency().equals(input.currency()),
                        "QUITACAO_DIVERGENTE","Vincule à cobrança e ao montante exato.");
                payment=capture;state="AGUARDANDO_DEPOSITO";
            }
            case "TX-E3-52"->{
                deposits.add(new Deposit(capture,lines));state=hasOpen(incidents)?"COM_DIVERGENCIA":"DEPOSITO_REGISTRADO";
                if(old.lines().stream().allMatch(line->net(old,line.vintage()).add(amount(lines,line.vintage())).compareTo(line.amount())==0))released=at;
                linkPeriod(c,project,actor,closure(c,project,old),external,"ATO_EXTERNO","DEPOSITO_REGISTRADO",event);
            }
            case "AJUSTE"->{
                adjustments.add(new Adjustment(capture,input.referenceAct(),lines.getFirst(),adjustmentPosition));
                state=hasOpen(incidents)?"COM_DIVERGENCIA":"DEPOSITO_REGISTRADO";
            }
            case "LIBERAR_REMANESCENTE"->{
                require(!deposits.isEmpty()&&released==null&&(!"COM_DIVERGENCIA".equals(state)||ref(RESOLUTION).equals(input.support())),
                        "LIBERACAO_INDISPONIVEL","Comprove o encerramento do remanescente da tentativa parcial.");
                reason(input);
                released=at;
                state=hasOpen(incidents)?"COM_DIVERGENCIA":"DEPOSITO_REGISTRADO";
            }
            case "COMPARAR"->{
                require(!deposits.isEmpty()&&List.of("DEPOSITO_REGISTRADO","COM_DIVERGENCIA").contains(state),
                        "DEPOSITO_AUSENTE","Compare após o depósito comprovado.");
                require(!hasOpen(incidents)||ref(RESOLUTION).equals(input.support()),"RESOLUCAO_PENDENTE","Identifique a prova de resolução das diferenças.");
                comparison=new Comparison(UUID.randomUUID(),version+1,actor,event,at,compare(c,project,old,at),input.support());
            }
            case "TX-E3-53"->{
                comparison=old.comparison();
                require(comparison!=null&&comparison.id().equals(input.comparison())&&comparison.issuanceVersion()==version,
                        "COMPARACAO_DESATUALIZADA","Solicite nova comparação do PREP.");
                if(actor.equals(comparison.preparedBy()))throw PilotAccess.forbidden();
                require(comparison.lines().equals(compare(c,project,old,at)),"POSICAO_DESATUALIZADA","A posição mudou desde a comparação.");
                incidents=resolve(incidents,event);review=event;state="CONCILIADO";
            }
            case "TX-E3-55"->{
                state(old,"COM_DIVERGENCIA");reason(input);
                require(deposits.isEmpty()&&payment==null&&ref(RESOLUTION).equals(input.support()),"RETOMADA_INDISPONIVEL",
                        "Confira a resolução comprovada. Quitação anterior segue pela confirmação oficial.");
                incidents=resolve(incidents,event);state="PROTOCOLADO";
            }
            case "TX-E3-56"->{
                require(List.of("PROTOCOLADO","AGUARDANDO_PAGAMENTO","AGUARDANDO_DEPOSITO","COM_DIVERGENCIA").contains(state)
                                &&deposits.isEmpty(),"ENCERRAMENTO_INDISPONIVEL","Confirme o encerramento oficial de tentativa sem depósito.");
                reason(input);incidents=resolve(incidents,event);released=at;state="ENCERRADO_SEM_EMISSAO";
            }
            case "TX-E3-57"->{
                require(List.of("PROTOCOLADO","COM_DIVERGENCIA").contains(state)&&deposits.isEmpty()
                                &&(!hasOpen(incidents)||ref(RESOLUTION).equals(input.support())),
                        "QUITACAO_INDISPONIVEL","Comprove a condição oficial da cobrança e a resolução aplicável.");
                incidents=resolve(incidents,event);payment=capture;state="AGUARDANDO_DEPOSITO";
            }
            default->throw invalid("ACAO_INVALIDA","Escolha uma etapa da emissão.");
        }
        var last=at.isAfter(old.lastAt())?at:old.lastAt();
        var next=new Issuance(id,version+1,old.verification(),state,external,old.account(),old.predecessor(),old.lines(),old.check(),protocol,
                invoice,payment,List.copyOf(deposits),List.copyOf(adjustments),released,List.copyOf(incidents),comparison,review,last,event);
        save(c,project,next);
        var response=audit.decode(audit.encode(snapshot(next,null)));
        if(official)evidence.save(c,project,id,capture,digest,response);
        return response;
    }

    private static String namespace(String step){
        return switch(step){
            case "TX-E3-49"->"SIM-RECIBOS-EMISSAO";
            case "TX-E3-52"->"SIM-VERRA-DEPOSITO";
            case "AJUSTE"->"SIM-VERRA-AJUSTE";
            default->ACT_NAMESPACE;
        };
    }

    private void checkBalances(Connection c,String project,Activation activation,List<Requested> lines,OffsetDateTime at)throws SQLException{
        for(var line:lines){
            require(activation.cuts().contains(line.vintage()),"VINTAGE_DIVERGENTE","O intervalo deve pertencer à própria verificação.");
            var balance=ledger.balance(c,project,activation.verification(),line.vintage());
            require(balance.position()!=null&&balance.available()!=null,
                    balance.hold()==null?"POSICAO_AUSENTE":balance.hold(),"Confira a posição, suas pendências e eventuais divergências.");
            require(line.position().equals(balance.position().id())&&!at.isBefore(balance.position().asOf()),"POSICAO_DESATUALIZADA",
                    "Selecione o extrato vigente na data do pedido.");
            require(line.amount().compareTo(balance.available())<=0,"SALDO_INSUFICIENTE","A quantidade excede o saldo compartilhado deste recorte.");
        }
    }

    private List<Match> compare(Connection c,String project,Issuance old,OffsetDateTime at)throws SQLException{
        var result=new ArrayList<Match>();
        OffsetDateTime lastEffect=old.deposits().stream().map(d->d.capture().effectiveAt()).max(OffsetDateTime::compareTo).orElseThrow();
        for(var adjustment:old.adjustments())if(adjustment.capture().effectiveAt().isAfter(lastEffect))lastEffect=adjustment.capture().effectiveAt();
        for(var line:old.lines()){
            var cut=line.vintage();
            var balance=ledger.balance(c,project,old.verification(),cut,old.id());
            var deposited=net(old,cut);
            require(balance.position()!=null&&balance.available()!=null&&!balance.position().asOf().isBefore(lastEffect)
                            &&!at.isBefore(balance.position().asOf()),"POSICAO_PENDENTE","Confira a posição que incorpora os efeitos do depósito e os ajustes.");
            var reflected=balance.position().pending().stream().filter(p->old.id().equals(p.issuance())&&p.status()==Reflection.REFLETIDA)
                    .map(Pending::amount).findFirst().orElse(BigDecimal.ZERO);
            require(reflected.compareTo(deposited)>=0,"REFLEXO_PENDENTE","Vincule a prova de que o depósito líquido está refletido na posição.");
            var residue=old.releasedAt()==null?line.amount().subtract(deposited):BigDecimal.ZERO;
            result.add(new Match(cut,balance.position().id(),line.amount(),deposited,residue,balance.position().official(),balance.available()));
        }
        return List.copyOf(result);
    }

    private static boolean matches(Issuance old,List<Line> lines){
        return lines.size()==old.lines().size()&&old.lines().stream().allMatch(line->amount(lines,line.vintage()).compareTo(line.amount())==0);
    }

    private static boolean validDeposit(Issuance old,List<Line> lines,OffsetDateTime at){
        return List.of("AGUARDANDO_DEPOSITO","DEPOSITO_REGISTRADO","CONCILIADO","COM_DIVERGENCIA").contains(old.state())
                &&old.payment()!=null&&!at.isBefore(old.payment().effectiveAt())&&old.releasedAt()==null&&!lines.isEmpty()
                &&lines.stream().allMatch(line->line.amount().signum()>0&&old.lines().stream().anyMatch(r->r.vintage().equals(line.vintage())
                &&net(old,line.vintage()).add(line.amount()).compareTo(r.amount())<=0));
    }

    private static boolean validAdjustment(Issuance old,List<Line> lines,String reference,OffsetDateTime at){
        if(lines.size()!=1||lines.getFirst().amount().signum()==0)return false;
        var line=lines.getFirst();var corrected=old.deposits().stream().filter(d->d.capture().proof().officialId().equals(reference)).findFirst().orElse(null);
        if(corrected==null||at.isBefore(corrected.capture().effectiveAt())||amount(corrected.lines(),line.vintage()).signum()<=0)return false;
        var original=old.adjustments().stream().filter(a->reference.equals(a.corrects())&&a.line().vintage().equals(line.vintage()))
                .map(a->a.line().amount()).reduce(amount(corrected.lines(),line.vintage()),BigDecimal::add);
        var next=net(old,line.vintage()).add(line.amount());
        return original.add(line.amount()).signum()>=0&&next.signum()>=0
                &&old.lines().stream().anyMatch(r->r.vintage().equals(line.vintage())&&next.compareTo(r.amount())<=0);
    }

    private static BigDecimal amount(List<Line> lines,Vintage cut){
        return lines.stream().filter(line->cut.equals(line.vintage())).map(Line::amount).findFirst().orElse(BigDecimal.ZERO);
    }

    private static boolean hasOpen(List<Incident> incidents){return incidents.stream().anyMatch(item->item.resolutionEvent()==null);}
    private static ArrayList<Incident> resolve(List<Incident> items,UUID event){
        var result=new ArrayList<Incident>();
        for(var item:items)result.add(item.resolutionEvent()!=null?item:new Incident(item.id(),item.code(),item.reason(),
                item.fingerprint(),item.reopen(),item.responsible(),item.eventId(),event));
        return result;
    }

    private Snapshot incident(Connection c,String project,String actor,Issuance old,String code,String message,Object proof,
                              String fingerprint,UUID reopen)throws SQLException{
        boolean intentional="DIVERGENCIA_REVISADA".equals(code)||"FECHAMENTO_REABERTO".equals(code);
        if(old.incidents().stream().anyMatch(item->item.fingerprint().equals(fingerprint)))
            return snapshot(old,intentional?null:code);
        UUID event=audit.record(c,project,actor,"TX-E3-54",intentional?"PERMITIDO":"NEGADO",old.id().toString(),
                Map.of("code",code,"message",message,"proof",proof));
        var items=new ArrayList<>(old.incidents());
        items.add(new Incident(UUID.randomUUID(),code,message,fingerprint,reopen,"PP",event,null));
        var next=new Issuance(old.id(),old.version()+1,old.verification(),"COM_DIVERGENCIA",old.externalTarget(),old.account(),old.predecessor(),
                old.lines(),old.check(),old.protocol(),old.invoice(),old.payment(),old.deposits(),old.adjustments(),old.releasedAt(),
                List.copyOf(items),null,old.reviewEvent(),old.lastAt(),event);
        save(c,project,next);return snapshot(next,intentional?null:code);
    }

    void onReopen(Connection c,String project,String actor,String period,int revision,UUID divergence,String reason)throws SQLException{
        if(divergence==null)return;
        var ids=query(c,"""
                SELECT verification FROM pilot_verification_activation WHERE project_id=? AND state#>>'{closure,period}'=?
                AND (state#>>'{closure,revision}')::int=?
                """,row->row.getObject(1,UUID.class),project,period,revision);
        for(var id:ids){
            for(var item:ledger.attempts(c,project,id)){
                if(STATES_IN_PROGRESS.contains(item.state())){
                    incident(c,project,actor,item,"FECHAMENTO_REABERTO",reason,Map.of("period",period,"revision",revision,"divergence",divergence),
                            divergence.toString(),divergence);
                }
            }
        }
    }

    private DocumentRules.ClosureRef closure(Connection c,String project,Issuance item)throws SQLException{
        return first(c,"SELECT state FROM pilot_verification_activation WHERE project_id=? AND verification=?",
                row->ledger.decode(row.getString(1),Activation.class).closure(),project,item.verification());
    }

    private void linkPeriod(Connection c,String project,String actor,DocumentRules.ClosureRef closure,String target,String kind,
                            String state,UUID event)throws SQLException{
        var proof="ATO_EXTERNO".equals(kind)?ref(PROOFS.get("TX-E3-52")):ref(PROOFS.get("TX-E3-49"));
        var input=new MonitoringPeriods.DependentInput(kind,target,1,state,proof);
        var data=new MonitoringPeriods.Dependent(input,actor,event,first(c,"SELECT sha256 FROM pilot_period_closure WHERE project_id=? AND period=? AND revision=?",
                row->row.getString(1),project,closure.period(),closure.revision()));
        update(c,"INSERT INTO pilot_period_dependent VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                project,closure.period(),closure.revision(),kind,target,1,audit.encode(data));
    }

    private void save(Connection c,String project,Issuance item)throws SQLException{
        update(c,"UPDATE pilot_issuance SET external_target=?,state=?::jsonb WHERE project_id=? AND id=?",
                item.externalTarget(),audit.encode(item),project,item.id());
    }

    private static Snapshot snapshot(Issuance item,String conflict){
        return new Snapshot(item,item.lines().stream().map(line->new Line(line.vintage(),net(item,line.vintage()))).toList(),UNIT,conflict);
    }

    private static void state(Issuance item,String expected){
        require(expected.equals(item.state()),"TRANSICAO_INDISPONIVEL","A etapa exige o estado "+expected+".");
    }

    private static void reason(Action input){
        if(!IntakeRules.text(input.reason()))throw invalid("MOTIVO_AUSENTE","Informe o motivo e a providência comprovada.");
    }
}
