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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Candidate-only E8 input snapshot. Joins immutable project ED/EC measurements
 * and hourly grid EFel with independent INTERNAL review. Does not look at
 * national fleet/fossil/IR/Dy, does not run emissions math, does not admit VT0011.
 * A candidate binding is NEVER an admitted ProjectParameterBinding.
 */
@ApplicationScoped
class OpenE8InputBindings {
    private static final String RULE="CF-OPEN-008";
    record Input(UUID energyCandidateId,UUID efelCandidateId) {}
    record Hour(OffsetDateTime hour,String edKwh,String ecKwh,
                String gridFactorTco2ePerKwh,int chargerCount) {}
    record View(UUID id,String projectId,String period,int version,
                UUID energyCandidateId,UUID efelCandidateId,
                String energySha256,String efelSha256,int efelReviewVersion,
                String inputSha256,int hourCount,int slotCount,
                String state,boolean stale,boolean usableForE8,
                OffsetDateTime createdAt,List<String> gates,List<Hour> hours) {}

    private record Energy(UUID id,String period,String sha,int hourCount,
                          int slotCount,BigDecimal ed,BigDecimal ec) {}
    private record Factor(UUID id,String period,UUID energy,String sha,
                          int hourCount) {}
    private record Review(int version,String outcome) {}
    private record Stored(UUID id,String project,String period,int version,
                          UUID energy,UUID factor,String energySha,
                          String factorSha,int review,String inputSha,
                          int hourCount,int slotCount,OffsetDateTime created) {}
    private record Reading(OffsetDateTime hour,BigDecimal ed,BigDecimal ec) {}
    private record FactorHour(OffsetDateTime hour,BigDecimal factor) {}
    private static final class Acc {
        BigDecimal ed=BigDecimal.ZERO,ec=BigDecimal.ZERO;
        int chargers=0;
        void add(Reading r) {
            ed=ed.add(r.ed()); ec=ec.add(r.ec()); chargers++;
        }
    }

    private final DynamicProjectScope scope;
    OpenE8InputBindings(DynamicProjectScope scope){this.scope=scope;}

    List<View> list(String cookie,String project,String period){
        return scope.read(cookie,project,"VIEW_OPEN_E8_BINDING_CANDIDATE",(c,ctx)->{
            String code=OpenMonitoringDossiers.code(period);
            var rows=query(c,STORED+"""
                WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 20
                """,OpenE8InputBindings::stored,project,code);
            var out=new ArrayList<View>();
            for(var record:rows)out.add(view(c,project,record));
            return List.copyOf(out);
        });
    }

    View detail(String cookie,String project,String period,UUID id){
        return scope.read(cookie,project,"VIEW_OPEN_E8_BINDING_CANDIDATE",(c,ctx)->{
            String code=OpenMonitoringDossiers.code(period);
            Stored record=first(c,STORED+"""
                WHERE project_id=? AND period_code=? AND id=?
                """,OpenE8InputBindings::stored,project,code,id);
            if(record==null)throw unavailable();
            return view(c,project,record);
        });
    }

    View prepare(String cookie,String project,String period,String key,
                 String match,Input request){
        return scope.write(cookie,project,"PREPARE_OPEN_E8_BINDING_CANDIDATE",(c,ctx)->{
            if(!ctx.owner() && !"PREP".equals(ctx.role()))throw unavailable();
            if(ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
                throw unavailable();
            String code=OpenMonitoringDossiers.code(period);
            if(request==null || request.energyCandidateId()==null ||
               request.efelCandidateId()==null)
                throw bad("candidate","Selecione as duas versões ED/EC e EFel.");
            String command=PilotCommands.key(key);
            int expected=PilotCommands.expected(match);
            byte[] fingerprint=AccessSecrets.digest(
                "E8-INPUT-BINDING-V1|"+project+"|"+code+"|"+expected+"|"+
                request.energyCandidateId()+"|"+request.efelCandidateId());
            Object[] old=first(c,"""
                SELECT project_id,binding_id,fingerprint
                FROM app_open_e8_input_binding_command
                WHERE actor_id=? AND command_key=?
                """,r->new Object[]{r.getString(1),r.getObject(2,UUID.class),
                                    r.getBytes(3)},ctx.actor().id(),command);
            if(old!=null){
                if(!project.equals(old[0]) ||
                    !MessageDigest.isEqual(fingerprint,(byte[])old[2]))
                    throw conflict("A chave de idempotência foi usada com outra origem.");
                Stored previous=first(c,STORED+"""
                    WHERE project_id=? AND id=?
                    """,OpenE8InputBindings::stored,project,(UUID)old[1]);
                if(previous==null)throw unavailable();
                return view(c,project,previous);
            }

            Stored latest=first(c,STORED+"""
                WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 1
                """,OpenE8InputBindings::stored,project,code);
            PilotCommands.requireVersion(expected,latest==null?0:latest.version());
            if(expected>=100000000)throw conflict("Limite de versões atingido.");
            Energy energy=energy(c,project,request.energyCandidateId());
            Factor factor=factor(c,project,request.efelCandidateId());
            if(energy==null || factor==null || !code.equals(energy.period()) ||
                !code.equals(factor.period()) || !energy.id().equals(factor.energy()))
                throw unavailable();

            // Source presence and INTERNAL review are necessary for staging,
            // not sufficient for source admission or any E8 execution.
            if(!"NOT_ADMITTED".equals(OpenE8EnergyCandidates.state(c,project,code)) ||
                !energy.id().equals(latestEnergy(c,project,code)))
                throw conflict("O pacote energético não é o vigente.");
            if(!"CHECKED_INTERNAL_NOT_ADMITTED".equals(
                    OpenEfelCandidates.state(c,project,code)) ||
                !factor.id().equals(latestFactor(c,project,code)))
                throw conflict("Fator EFel precisa de revisão interna vigente.");
            Review review=review(c,project,factor.id());
            if(review==null || !"CHECKED_INTERNAL".equals(review.outcome()))
                throw conflict("A fonte EFel ainda não recebeu conferência independente.");

            List<Hour> hours=align(readings(c,project,energy.id()),
                factorHours(c,project,factor.id()),energy,factor);
            String signature=signature(project,code,energy,factor,review,hours);
            // Avoid duplicate content under a fresh idempotency key:
            // one binding version per exact source/review pair.
            if(latest!=null && latest.energy().equals(energy.id()) &&
                latest.factor().equals(factor.id()) && latest.review()==review.version())
                throw conflict("Este par de candidatos já está vinculado.");

            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_e8_input_binding_candidate
                (id,project_id,organization_id,environment,period_code,version,
                 energy_candidate_id,efel_candidate_id,energy_sha256,efel_sha256,
                 efel_review_version,input_sha256,hour_count,slot_count,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,project,ctx.organization(),ctx.environment(),code,expected+1,
                energy.id(),factor.id(),HexFormat.of().parseHex(energy.sha()),
                HexFormat.of().parseHex(factor.sha()),review.version(),
                HexFormat.of().parseHex(signature),hours.size(),energy.slotCount(),
                ctx.actor().id());
            for(Hour h:hours){
                update(c,"""
                    INSERT INTO app_open_e8_input_binding_hour
                    (project_id,binding_id,hour_start,charger_count,
                     ed_kwh,ec_kwh,efel_tco2e_per_kwh)
                    VALUES (?,?,?,?,?,?,?)
                    """,project,id,h.hour(),h.chargerCount(),new BigDecimal(h.edKwh()),
                    new BigDecimal(h.ecKwh()),
                    new BigDecimal(h.gridFactorTco2ePerKwh()));
            }
            update(c,"""
                INSERT INTO app_open_e8_input_binding_command
                (actor_id,command_key,project_id,binding_id,fingerprint)
                VALUES (?,?,?,?,?)
                """,ctx.actor().id(),command,project,id,fingerprint);
            Stored stored=first(c,STORED+"""
                WHERE project_id=? AND id=?
                """,OpenE8InputBindings::stored,project,id);
            return view(c,project,stored);
        });
    }

    /** Current status of a precisely scoped historical energy/factor input.
     * Used for safe downstream handoffs: original metadata remains readable
     * even after a source is withdrawn, but cannot be reused as current. */
    static String state(Connection c,String project,String period,UUID id)
        throws SQLException {
        Stored stored=first(c,STORED+"""
            WHERE project_id=? AND period_code=? AND id=?
            """,OpenE8InputBindings::stored,project,period,id);
        return stored==null?"BINDING_MISSING":view(c,project,stored).state();
    }

    private static View view(Connection c,String project,Stored record)
        throws SQLException {
        var gates=new ArrayList<String>();
        Energy e=energy(c,project,record.energy());
        Factor f=factor(c,project,record.factor());
        if(e==null || f==null ||
            !record.energySha().equals(e.sha()) ||
            !record.factorSha().equals(f.sha()))
            gates.add("SOURCE_BINDING_CHANGED");
        if(!record.energy().equals(latestEnergy(c,project,record.period())) ||
            !"NOT_ADMITTED".equals(OpenE8EnergyCandidates.state(c,project,
                record.period())))
            gates.add("ENERGY_SOURCE_STALE");
        if(!record.factor().equals(latestFactor(c,project,record.period())) ||
            !"CHECKED_INTERNAL_NOT_ADMITTED".equals(
                OpenEfelCandidates.state(c,project,record.period())))
            gates.add("EFEL_SOURCE_STALE");
        Review current=review(c,project,record.factor());
        if(current==null || !"CHECKED_INTERNAL".equals(current.outcome()) ||
            current.version()!=record.review())
            gates.add("EFEL_REVIEW_CHANGED");
        gates.add("EVR_AND_COMPARABLES_NOT_ADMITTED");
        gates.add("FOSSIL_FACTOR_IR_AND_DY_NOT_ADMITTED");
        gates.add("NORMATIVE_VT0011_RIGHTS_NOT_ADMITTED");
        gates.add("E8_EXECUTION_NOT_ENABLED");
        List<Hour> hours=query(c,"""
            SELECT hour_start,ed_kwh,ec_kwh,efel_tco2e_per_kwh,charger_count
            FROM app_open_e8_input_binding_hour
            WHERE project_id=? AND binding_id=? ORDER BY hour_start
            """,r->new Hour(r.getObject(1,OffsetDateTime.class),
                num(r.getBigDecimal(2)),num(r.getBigDecimal(3)),
                num(r.getBigDecimal(4)),r.getInt(5)),project,record.id());
        boolean stale=gates.stream().anyMatch(x->x.contains("STALE") ||
            x.equals("EFEL_REVIEW_CHANGED") || x.equals("SOURCE_BINDING_CHANGED"));
        return new View(record.id(),record.project(),record.period(),record.version(),
            record.energy(),record.factor(),record.energySha(),record.factorSha(),
            record.review(),record.inputSha(),record.hourCount(),record.slotCount(),
            stale?"STALE_NOT_ADMITTED":"CANDIDATE_NOT_ADMITTED",stale,false,
            record.created(),List.copyOf(gates),hours);
    }

    /** Does not multiply EC by EFel here: a candidate is NOT an E8 result. */
    private static List<Hour> align(List<Reading> measurements,List<FactorHour> factors,
            Energy energy,Factor factor){
        Map<OffsetDateTime,Acc> grouped=new HashMap<>();
        BigDecimal ed=BigDecimal.ZERO,ec=BigDecimal.ZERO;
        for(Reading r:measurements){
            if(r.hour()==null || r.ed()==null || r.ec()==null ||
                r.ed().signum()<=0 || r.ec().compareTo(r.ed())<0)
                throw conflict("Leituras ED/EC incompletas ou contraditórias.");
            OffsetDateTime utc=r.hour().withOffsetSameInstant(ZoneOffset.UTC);
            grouped.computeIfAbsent(utc,k->new Acc()).add(r);
            ed=ed.add(r.ed());ec=ec.add(r.ec());
        }
        if(grouped.isEmpty() || grouped.size()>744 ||
            measurements.size()!=energy.slotCount() ||
            grouped.size()!=energy.hourCount() ||
            grouped.size()!=factor.hourCount() ||
            ed.compareTo(energy.ed())!=0 || ec.compareTo(energy.ec())!=0 ||
            factors.size()!=grouped.size())
            throw conflict("A cobertura horária ou os totais divergem dos candidatos.");
        var hours=new ArrayList<Hour>();
        var used=new java.util.HashSet<OffsetDateTime>();
        for(FactorHour f:factors){
            if(f.hour()==null || f.factor()==null || f.factor().signum()<0)
                throw conflict("Fator horário ausente ou inválido.");
            OffsetDateTime utc=f.hour().withOffsetSameInstant(ZoneOffset.UTC);
            Acc a=grouped.get(utc);
            if(a==null || !used.add(utc) || a.chargers>100)
                throw conflict("Fator não cobre cada hora e seus carregadores.");
            hours.add(new Hour(utc,num(a.ed),num(a.ec),num(f.factor()),a.chargers));
        }
        hours.sort(Comparator.comparing(Hour::hour));
        return List.copyOf(hours);
    }

    private static List<Reading> readings(Connection c,String project,UUID id)
        throws SQLException {
        return query(c,"""
            SELECT hour_start,ed_kwh,ec_kwh
            FROM app_open_e8_energy_candidate_hour
            WHERE project_id=? AND candidate_id=?
            ORDER BY hour_start,charger_code
            """,r->new Reading(r.getObject(1,OffsetDateTime.class),
                r.getBigDecimal(2),r.getBigDecimal(3)),project,id);
    }
    private static List<FactorHour> factorHours(Connection c,String project,UUID id)
        throws SQLException {
        return query(c,"""
            SELECT hour_start,normalized_value
            FROM app_open_efel_hourly_candidate_value
            WHERE project_id=? AND candidate_id=? ORDER BY hour_start
            """,r->new FactorHour(r.getObject(1,OffsetDateTime.class),
                r.getBigDecimal(2)),project,id);
    }
    private static String signature(String project,String period,Energy energy,
            Factor factor,Review review,List<Hour> hours){
        StringBuilder s=new StringBuilder("CF-OPEN-E8-INPUT-BINDING-V1|")
            .append(project).append('|').append(period).append('|')
            .append(energy.id()).append(':').append(energy.sha()).append('|')
            .append(factor.id()).append(':').append(factor.sha()).append('|')
            .append(review.version()).append(':').append(review.outcome());
        for(Hour h:hours)s.append('|').append(h.hour().toInstant()).append(':')
            .append(h.chargerCount()).append(':').append(h.edKwh()).append(':')
            .append(h.ecKwh()).append(':').append(h.gridFactorTco2ePerKwh());
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(s.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static String num(BigDecimal b){
        return b.signum()==0?"0":b.stripTrailingZeros().toPlainString();
    }
    private static Energy energy(Connection c,String project,UUID id)
        throws SQLException {
        return first(c,"""
            SELECT id,dossier_code,encode(source_sha256,'hex'),hour_count,
                   slot_count,ed_kwh,ec_kwh
            FROM app_open_e8_energy_candidate
            WHERE project_id=? AND id=?
            """,r->new Energy(r.getObject(1,UUID.class),r.getString(2),
                r.getString(3),r.getInt(4),r.getInt(5),
                r.getBigDecimal(6),r.getBigDecimal(7)),project,id);
    }
    private static Factor factor(Connection c,String project,UUID id)
        throws SQLException {
        return first(c,"""
            SELECT id,period_code,energy_candidate_id,
                   encode(content_sha256,'hex'),hour_count
            FROM app_open_efel_hourly_candidate
            WHERE project_id=? AND id=?
            """,r->new Factor(r.getObject(1,UUID.class),r.getString(2),
                r.getObject(3,UUID.class),r.getString(4),r.getInt(5)),project,id);
    }
    private static Review review(Connection c,String project,UUID id)
        throws SQLException {
        return first(c,"""
            SELECT version,outcome FROM app_open_efel_hourly_review
            WHERE project_id=? AND candidate_id=?
            ORDER BY version DESC LIMIT 1
            """,r->new Review(r.getInt(1),r.getString(2)),project,id);
    }
    private static UUID latestEnergy(Connection c,String project,String period)
        throws SQLException {
        return first(c,"""
            SELECT id FROM app_open_e8_energy_candidate
            WHERE project_id=? AND dossier_code=?
            ORDER BY created_at DESC,id DESC LIMIT 1
            """,r->r.getObject(1,UUID.class),project,period);
    }
    private static UUID latestFactor(Connection c,String project,String period)
        throws SQLException {
        return first(c,"""
            SELECT id FROM app_open_efel_hourly_candidate
            WHERE project_id=? AND period_code=?
            ORDER BY submitted_at DESC,id DESC LIMIT 1
            """,r->r.getObject(1,UUID.class),project,period);
    }
    private static final String STORED="""
        SELECT id,project_id,period_code,version,energy_candidate_id,
          efel_candidate_id,encode(energy_sha256,'hex'),
          encode(efel_sha256,'hex'),efel_review_version,
          encode(input_sha256,'hex'),hour_count,slot_count,created_at
        FROM app_open_e8_input_binding_candidate
        """;
    private static Stored stored(java.sql.ResultSet r) throws SQLException {
        return new Stored(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
            r.getInt(4),r.getObject(5,UUID.class),r.getObject(6,UUID.class),
            r.getString(7),r.getString(8),r.getInt(9),r.getString(10),
            r.getInt(11),r.getInt(12),r.getObject(13,OffsetDateTime.class));
    }
    private static PilotProblem unavailable() {
        return new PilotProblem(404,"E8_INPUT_BINDING_UNAVAILABLE","binding",RULE,
            "Vínculo indisponível.");
    }
    private static PilotProblem bad(String field,String reason) {
        return PilotProblem.invalid("E8_INPUT_BINDING_INVALID",field,RULE,reason);
    }
    private static PilotProblem conflict(String reason) {
        return new PilotProblem(409,"E8_INPUT_BINDING_CONFLICT","binding",RULE,reason);
    }
}
