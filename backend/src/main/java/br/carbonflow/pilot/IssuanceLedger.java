package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.IssuanceModel.*;
import static br.carbonflow.pilot.ListingEvidence.*;
import static br.carbonflow.pilot.PilotSql.*;
import static br.carbonflow.pilot.IssuanceModel.require;

@ApplicationScoped
class IssuanceLedger {
    private static final String ACTIVATION_NAMESPACE = "SIM-REGISTRY-ATIVACAO";
    private static final String POSITION_NAMESPACE = "SIM-REGISTRY-POSICAO";
    private final PilotDatabase database;
    private final PilotCommands commands;
    private final PilotAudit audit;
    private final ProjectIntake intake;
    private final ListingEvidence evidence;
    private final ProgramRequests requests;
    private final ObjectMapper mapper;

    IssuanceLedger(PilotDatabase database, PilotCommands commands, PilotAudit audit, ProjectIntake intake,
                   ListingEvidence evidence, ProgramRequests requests, ObjectMapper mapper) {
        this.database=database; this.commands=commands; this.audit=audit; this.intake=intake;
        this.evidence=evidence; this.requests=requests; this.mapper=mapper;
    }

    List<Verification> list(String project) {
        return database.transaction(connection -> {
            var ids=query(connection, """
                    SELECT id FROM pilot_program_request WHERE project_id=? AND kind IN ('V1','V2')
                    AND state->>'state'='APROVADO' ORDER BY kind
                    """, row -> row.getObject(1,UUID.class),project);
            var result=new ArrayList<Verification>();
            for (var id:ids) {
                var request=approved(connection,project,id);
                var scope=cuts(connection,project,request.pack().closure());
                var activation=activation(connection,project,id);
                var entries=new ArrayList<Balance>();
                for(var vintage:scope) entries.add(balance(connection,project,id,vintage));
                result.add(new Verification(id,request.kind().name(),request.target(),request.decision().proof().officialId(),
                        request.pack().closure(),scope,activation,List.copyOf(entries),eligible(connection,project,request.pack().closure())));
            }
            return List.copyOf(result);
        });
    }

    JsonNode activate(PilotAccess.Scope scope,String actor,String key,UUID id,int version,ActivationInput input) {
        if(input==null || !IntakeRules.id(input.registryId()) || !IntakeRules.id(input.approvalId()))
            throw IssuanceModel.invalid("ATIVACAO_INCOMPLETA","Informe registro, aprovação e prova de ativação.");
        proof("ATIVACAO",input.proof());
        var at=officialTime(input.effectiveAt());
        return commands.execute(scope,actor,"COMPROVAR_ATIVACAO",key,audit.encode(List.of(id,version,input)),id.toString(),connection->{
            var project=scope.project();
            intake.lockProject(connection,project);
            var request=approved(connection,project,id);
            var previous=activation(connection,project,id);
            var digest=evidence.digest(List.of(input.registryId(),input.approvalId(),input.proof().target(),input.active(),at.toInstant()));
            var replay=evidence.repeated(connection,project,id,REGISTRY,ACTIVATION_NAMESPACE,input.proof(),digest);
            if(replay!=null)return replay;
            RecoveryWorkflow.requireObservedAct(connection,project,REGISTRY,ACTIVATION_NAMESPACE,input.proof().officialId(),digest);
            PilotCommands.requireVersion(version,previous==null?0:previous.version());
            require(request.target().equals(input.proof().target())&&request.decision().proof().officialId().equals(input.approvalId())
                            &&!at.isBefore(request.decision().effectiveAt()) && (previous==null || previous.registryId().equals(input.registryId())
                            &&at.isAfter(previous.capture().effectiveAt())),
                    "ATIVACAO_DIVERGENTE","Confira a aprovação, o alvo e a ordem dos atos de registro.");
            if(input.active()) require(eligible(connection,project,request.pack().closure()),"FECHAMENTO_AFETADO","Use uma verificação com fechamento vigente.");
            require(first(connection,"SELECT true FROM pilot_verification_activation WHERE registry_id=? AND verification<>?",
                    row->true,input.registryId(),id)==null,"REGISTRO_JA_VINCULADO","O registro identifica outra verificação.");
            UUID event=audit.record(connection,project,actor,"COMPROVAR_ATIVACAO","PERMITIDO",id.toString(),input);
            var capture=new Capture(REGISTRY,ACTIVATION_NAMESPACE,input.proof(),at,actor,event);
            var saved=new Activation(id,version+1,request.kind().name(),input.registryId(),request.pack().closure(),input.approvalId(),
                    input.active(),cuts(connection,project,request.pack().closure()),capture);
            update(connection,"""
                    INSERT INTO pilot_verification_activation VALUES (?,?,?,?::jsonb)
                    ON CONFLICT (project_id,verification) DO UPDATE SET state=excluded.state
                    """,project,id,input.registryId(),audit.encode(saved));
            var result=audit.decode(audit.encode(saved));
            evidence.save(connection,project,id,capture,digest,result);
            return result;
        });
    }

    JsonNode position(PilotAccess.Scope scope,String actor,String key,UUID verification,PositionInput input) {
        if(input==null)throw IssuanceModel.invalid("POSICAO_INCOMPLETA","Informe saldo, recorte e comprovante.");
        return commands.execute(scope,actor,"REGISTRAR_POSICAO",key,audit.encode(List.of(verification,input)),verification.toString(),connection->{
            intake.lockProject(connection,scope.project());
            return insertPosition(connection,scope.project(),actor,verification,input);
        });
    }

    JsonNode insertPosition(Connection connection,String project,String actor,UUID id,PositionInput input)throws SQLException {
        proof("POSICAO",input.proof());
        var at=officialTime(input.asOf());
        require(valid(input.vintage())&&UNIT.equals(input.unit())&&input.pending()!=null&&input.pending().size()<=1000
                        && (input.order()==null||input.order()>=0),"POSICAO_INVALIDA","Informe recorte, unidade fictícia, saldo e pendências explícitas.");
        var amount=decimal(input.official(),false);
        var pending=normalizePending(connection,project,id,input.vintage(),input.pending());
        var active=activation(connection,project,id);
        require(active!=null&&active.registryId().equals(input.proof().target())&&active.cuts().contains(input.vintage())
                        &&!at.isBefore(approved(connection,project,id).decision().effectiveAt()),
                "POSICAO_DIVERGENTE","Comprove o registro e o recorte exato da verificação.");
        var digest=evidence.digest(Arrays.asList(input.proof().target(),input.vintage(),amount,pending,at.toInstant(),input.order()));
        JsonNode replay;
        try{
            replay=evidence.repeated(connection,project,id,REGISTRY,POSITION_NAMESPACE,input.proof(),digest);
        }catch(PilotProblem failure){
            if(!"ATO_CONFLITANTE".equals(failure.code))throw failure;
            return positionConflict(connection,project,id,actor,input,"ATO_CONFLITANTE");
        }
        if(replay!=null)return replay;
        RecoveryWorkflow.requireObservedAct(connection,project,REGISTRY,POSITION_NAMESPACE,input.proof().officialId(),digest);
        var head=latest(connection,project,id,input.vintage());
        boolean sameTime=head!=null&&head.asOf().isEqual(at);
        if(sameTime&&(Objects.equals(head.order(),input.order())||head.order()==null||input.order()==null)
                && (head.official().compareTo(amount)!=0||!samePending(head.pending(),pending))) {
            return positionConflict(connection,project,id,actor,input,"ORDEM_POSICAO_INDETERMINADA");
        }
        boolean historical=head!=null&&(at.isBefore(head.asOf())||sameTime&&(head.order()==null||input.order()==null||input.order()<=head.order()));
        UUID positionId=UUID.randomUUID();
        UUID event=audit.record(connection,project,actor,"REGISTRAR_POSICAO","PERMITIDO",id.toString(),
                Map.of("positionId",positionId,"input",input,"historical",historical));
        var capture=new Capture(REGISTRY,POSITION_NAMESPACE,input.proof(),at,actor,event);
        var saved=new Position(positionId,id,input.vintage(),amount,pending,at,input.order(),capture,historical);
        update(connection,"INSERT INTO pilot_verification_position VALUES (?,?,?,?,?,?,?,?::jsonb)",project,id,positionId,
                saved.vintage().start(),saved.vintage().endExclusive(),at,input.order(),audit.encode(saved));
        var response=audit.decode(audit.encode(new PositionResult(saved,balance(connection,project,id,input.vintage()),null)));
        evidence.save(connection,project,id,capture,digest,response);
        return response;
    }

    private JsonNode positionConflict(Connection c,String project,UUID id,String actor,PositionInput input,String code)throws SQLException{
        var current=latest(c,project,id,input.vintage());
        UUID event=audit.record(c,project,actor,"CONFLITO_POSICAO","NEGADO",id.toString(),Map.of("code",code,"input",input));
        if(current!=null) {
            update(c,"INSERT INTO pilot_position_conflict VALUES (?,?,?,?) ON CONFLICT DO NOTHING",project,id,current.id(),event);
        }
        return audit.decode(audit.encode(new PositionResult(null,balance(c,project,id,input.vintage()),code)));
    }

    List<Position> history(String project,UUID id) {
        return database.read(c->query(c,"""
                SELECT state FROM pilot_verification_position WHERE project_id=? AND verification=?
                ORDER BY start_on,as_of DESC,official_order DESC NULLS LAST,id LIMIT 150
                """,row->decode(row.getString(1),Position.class),project,id));
    }

    Balance balance(Connection c,String project,UUID id,Vintage vintage)throws SQLException{
        return balance(c,project,id,vintage,null);
    }

    Balance balance(Connection c,String project,UUID id,Vintage vintage,UUID resolving)throws SQLException{
        var pos=latest(c,project,id,vintage);
        if(pos==null)return new Balance(null,null,null,"POSICAO_AUSENTE");
        var items=attempts(c,project,id);
        var held=BigDecimal.ZERO;
        String hold=null;
        if(first(c,"SELECT true FROM pilot_position_conflict WHERE project_id=? AND verification=? AND position_id=?",
                row->true,project,id,pos.id())!=null)hold="CONFLITO_POSICAO";
        for(var pending:pos.pending()){
            if(pending.status()==Reflection.INCERTA)hold="REFLEXO_INCERTO";
            if(pending.issuance()==null&&pending.status()==Reflection.NAO_REFLETIDA)held=held.add(pending.amount());
        }
        for(var item:items){
            var request=item.lines().stream().filter(line->line.vintage().equals(vintage)).findFirst().orElse(null);
            if(request==null||item.protocol()==null)continue;
            if(!item.id().equals(resolving)&&item.incidents().stream().anyMatch(incident->incident.resolutionEvent()==null))hold="DIVERGENCIA_PENDENTE";
            if(item.releasedAt()!=null&&!pos.asOf().isAfter(item.releasedAt()))hold="POSICAO_A_ATUALIZAR";
            BigDecimal impact=impact(item,vintage);
            BigDecimal reflected=pos.pending().stream().filter(p->item.id().equals(p.issuance())&&p.status()==Reflection.REFLETIDA)
                    .map(Pending::amount).findFirst().orElse(BigDecimal.ZERO);
            if(reflected.compareTo(impact)>0)hold="POSICAO_A_ATUALIZAR";
            else held=held.add(impact.subtract(reflected));
        }
        BigDecimal available=pos.official().subtract(held);
        if(available.signum()<0)hold="POSICAO_INSUFICIENTE";
        return new Balance(pos,held,hold==null?available:null,hold);
    }

    private static boolean samePending(List<Pending> before,List<Pending> after){
        if(before.size()!=after.size())return false;
        for(int index=0;index<before.size();index++){
            var a=before.get(index);
            var b=after.get(index);
            if(!Objects.equals(a.reference(),b.reference())||!Objects.equals(a.issuance(),b.issuance())
                    ||a.status()!=b.status()||a.amount().compareTo(b.amount())!=0)return false;
        }
        return true;
    }

    private List<Pending> normalizePending(Connection c,String project,UUID verification,Vintage vintage,List<Pending> rows)throws SQLException{
        var keys=new HashSet<String>();
        var result=new ArrayList<Pending>();
        for(var item:rows) {
            require(item!=null&&item.status()!=null&&(item.issuance()==null?IntakeRules.id(item.reference()):item.reference()==null),
                    "PENDENCIA_INVALIDA","Informe a origem e o estado do reflexo.");
            String key=item.issuance()==null?item.reference():item.issuance().toString();
            require(keys.add(key),"PENDENCIA_DUPLICADA","Informe uma pendência por origem.");
            var amount=decimal(item.amount(),false);
            if(item.issuance()!=null){
                var issuance=getAttempt(c,project,item.issuance());
                require(issuance.verification().equals(verification)&&issuance.protocol()!=null&&issuance.lines().stream()
                                .anyMatch(line->line.vintage().equals(vintage)&&amount.compareTo(line.amount())<=0),
                        "PENDENCIA_DIVERGENTE","A reserva pertence à verificação e ao recorte comprovados.");
            }
            result.add(new Pending(item.reference(),item.issuance(),amount,item.status()));
        }
        result.sort(Comparator.comparing(item->item.issuance()==null?item.reference():item.issuance().toString()));
        return List.copyOf(result);
    }

    Activation active(Connection c,String project,UUID id,OffsetDateTime at)throws SQLException{
        var result=activation(c,project,id);
        require(result!=null&&result.active()&&!at.isBefore(result.capture().effectiveAt()),"ATIVACAO_PENDENTE","Comprove a ativação desta verificação.");
        require(eligible(c,project,result.closure()),"FECHAMENTO_AFETADO","A revisão reaberta ficou impedida para nova emissão.");
        return result;
    }

    private Activation activation(Connection c,String project,UUID id)throws SQLException{
        return first(c,"SELECT state FROM pilot_verification_activation WHERE project_id=? AND verification=?",
                row->decode(row.getString(1),Activation.class),project,id);
    }

    private ProgramRequests.Request approved(Connection c,String project,UUID id)throws SQLException{
        var result=requests.get(c,project,id);
        require(result.kind()!=ProgramRequests.Kind.REG&&"APROVADO".equals(result.state())&&result.pack().closure()!=null,
                "APROVACAO_PENDENTE","Selecione V1 ou V2 com aprovação comprovada.");
        return result;
    }

    private List<Vintage> cuts(Connection c,String project,DocumentRules.ClosureRef ref)throws SQLException {
        return query(c,"""
                SELECT (cut->>'start')::date,(cut->>'endExclusive')::date FROM pilot_period_closure p,
                jsonb_array_elements(convert_from(p.bytes,'UTF8')::jsonb#>'{memory,cuts}') AS cut
                WHERE p.project_id=? AND p.period=? AND p.revision=? ORDER BY 1
                """,row->new Vintage(row.getObject(1,java.time.LocalDate.class),row.getObject(2,java.time.LocalDate.class)),
                project,ref.period(),ref.revision());
    }

    private boolean eligible(Connection c,String project,DocumentRules.ClosureRef ref)throws SQLException{
        return first(c,"""
                SELECT true FROM pilot_period_closure p WHERE p.project_id=? AND p.period=? AND p.revision=?
                AND NOT EXISTS (SELECT 1 FROM pilot_period_state h WHERE h.project_id=p.project_id AND h.period=p.period AND h.version>p.state_version)
                """,row->true,project,ref.period(),ref.revision())!=null;
    }

    Position latest(Connection c,String project,UUID id,Vintage cut)throws SQLException {
        return first(c,"""
                SELECT state FROM pilot_verification_position
                WHERE project_id=? AND verification=? AND start_on=? AND end_before=? AND (state->>'historical')::boolean=false
                ORDER BY as_of DESC,official_order DESC NULLS LAST LIMIT 1
                """,row->decode(row.getString(1),Position.class),project,id,cut.start(),cut.endExclusive());
    }

    List<Issuance> attempts(Connection c,String project,UUID id)throws SQLException{
        return query(c,"""
                SELECT state FROM pilot_issuance WHERE project_id=? AND (CAST(? AS uuid) IS NULL OR verification=?)
                ORDER BY id
                """,row->decode(row.getString(1),Issuance.class),project,id,id);
    }

    Issuance getAttempt(Connection c,String project,UUID id)throws SQLException{
        var result=first(c,"SELECT state FROM pilot_issuance WHERE project_id=? AND id=?",row->decode(row.getString(1),Issuance.class),project,id);
        if(result==null)throw PilotAccess.invisible();
        return result;
    }

    static BigDecimal impact(Issuance item,Vintage cut){
        if(item.protocol()==null)return BigDecimal.ZERO;
        if(item.releasedAt()!=null)return net(item,cut);
        return item.lines().stream().filter(line->cut.equals(line.vintage())).map(Requested::amount).findFirst().orElse(BigDecimal.ZERO);
    }

    static BigDecimal net(Issuance item,Vintage cut){
        var deposited=item.deposits().stream().flatMap(deposit->deposit.lines().stream()).filter(line->cut.equals(line.vintage()))
                .map(Line::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        return item.adjustments().stream().map(Adjustment::line).filter(line->cut.equals(line.vintage()))
                .map(Line::amount).reduce(deposited,BigDecimal::add);
    }

    <T>T decode(String raw,Class<T> type){
        try{return mapper.readValue(raw,type);}
        catch(java.io.IOException error){throw new IllegalStateException("Falha ao ler posição ou emissão",error);}
    }
}
