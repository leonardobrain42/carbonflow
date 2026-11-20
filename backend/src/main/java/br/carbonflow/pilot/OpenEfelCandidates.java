package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Typed, immutable hourly grid emission factor CANDIDATES. Source metadata,
 * proof hashes and reviewer notes are claims pending substantive VT0011
 * source admission. They never authorize E8, BE/PE/ER or issuance.
 */
@ApplicationScoped
class OpenEfelCandidates {
    static final String RULE="CF-OPEN-007";
    record HourInput(OffsetDateTime hour,BigDecimal value) {}
    record Input(UUID energyCandidateId,String sourceTitle,String sourcePublisher,
                 String sourceUri,String sourceEdition,String claimedProcedure,
                 LocalDate publicationDate,String sourceVintage,String sourceSha256,
                 String sourceRightsReference,String evidenceNote,String unit,
                 List<HourInput> hours) {}
    record Hour(OffsetDateTime hour,String originalValue,String normalizedValue) {}
    record Review(int version,String outcome,String rationale,String evidenceReference,
                  UUID reviewerId,OffsetDateTime reviewedAt) {}
    record Decision(String outcome,String rationale,String evidenceReference) {}
    record View(UUID id,String period,UUID energyCandidateId,int version,String sourceTitle,
                String sourcePublisher,String sourceUri,String sourceEdition,
                String claimedProcedure,LocalDate publicationDate,String sourceVintage,
                String sourceSha256Claimed,String sourceRightsReference,
                String evidenceNote,String originalUnit,String normalizedUnit,
                String contentSha256,int hourCount,String state,boolean stale,
                boolean usableForE8,UUID submittedBy,OffsetDateTime submittedAt,
                Review review,List<Hour> hours,List<String> gates) {}

    private record Energy(UUID id,String period,int dossierVersion,int methodVersion,
                          String sha,String state,int hourCount) {}
    private record Record(UUID id,String period,UUID energy,int version,String title,
                          String publisher,String uri,String edition,String procedure,
                          LocalDate publicationDate,String vintage,String sourceSha,
                          String rights,String note,String unit,String contentSha,
                          int hourCount,UUID author,OffsetDateTime createdAt) {}
    private record Command(String project,UUID candidate,String action,byte[] fingerprint) {}
    private record Normal(String title,String publisher,String uri,String edition,
                          String procedure,LocalDate published,String vintage,
                          String sourceSha,String rights,String note,String unit,
                          List<Hour> hours,String contentSha) {}

    private final DynamicProjectScope scope;
    OpenEfelCandidates(DynamicProjectScope scope) {this.scope=scope;}

    List<View> list(String cookie,String project,String period) {
        return scope.read(cookie,project,"VIEW_OPEN_EFEL_CANDIDATE",(c,ctx)->{
            String code=OpenMonitoringDossiers.code(period);
            List<Record> versions=query(c,RECORD_SQL+"""
                WHERE project_id=? AND period_code=?
                ORDER BY submitted_at DESC,id DESC LIMIT 20
                """,OpenEfelCandidates::record,project,code);
            List<View> views=new ArrayList<>();
            for(Record entry:versions)views.add(view(c,project,entry));
            return List.copyOf(views);
        });
    }

    View submit(String cookie,String project,String period,String match,String key,Input input) {
        return scope.write(cookie,project,"SUBMIT_OPEN_EFEL_CANDIDATE",(c,ctx)->{
            if(!ctx.owner() && !"PREP".equals(ctx.role()))throw unavailable();
            String code=OpenMonitoringDossiers.code(period);
            if(ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
                throw unavailable();
            int expected=PilotCommands.expected(match);
            String command=PilotCommands.key(key);
            if(input==null || input.energyCandidateId()==null)throw bad("energyCandidateId",
                "Selecione um pacote de energia real revisado e vigente.");
            Energy energy=energy(c,project,input.energyCandidateId());
            if(energy==null || !code.equals(energy.period()))throw unavailable();
            if(!"NOT_ADMITTED".equals(OpenE8EnergyCandidates.state(c,project,code)) ||
               !energy.id().equals(latestEnergy(c,project,code)) ||
               !"NOT_ADMITTED".equals(energy.state()))
                throw conflict("O pacote ED/EC deixou de ser o vigente para este período.");
            Normal normalized=normalize(input,energy,project);
            requireCoverage(c,project,energy,normalized.hours());
            byte[] fingerprint=AccessSecrets.digest("EFEL-HOUR|"+project+"|"+code+"|"+
                expected+"|"+normalized.contentSha());
            Command previous=command(c,ctx.actor().id(),command);
            if(previous!=null) {
                if(!project.equals(previous.project()) || !"SUBMIT".equals(previous.action()) ||
                   !MessageDigest.isEqual(previous.fingerprint(),fingerprint))
                    throw conflict("Chave de idempotência já utilizada para outra série.");
                return view(c,project,require(c,project,previous.candidate()));
            }
            Record old=latest(c,project,energy.id());
            PilotCommands.requireVersion(expected,old==null?0:old.version());
            if(expected>=100000000)throw bad("version","Limite de revisões da fonte.");
            if(old!=null && old.contentSha().equals(normalized.contentSha()))
                throw conflict("A série idêntica já existe; não duplique revisões.");
            UUID id=UUID.randomUUID();
            OffsetDateTime from=normalized.hours().getFirst().hour();
            OffsetDateTime until=normalized.hours().getLast().hour().plusHours(1);
            update(c,"""
                INSERT INTO app_open_efel_hourly_candidate
                 (id,project_id,organization_id,environment,period_code,
                  energy_candidate_id,version,original_unit,source_title,
                  source_publisher,source_uri,source_edition,claimed_procedure,
                  source_publication_date,source_vintage,source_sha256_claimed,
                  source_rights_reference,evidence_note,period_from,period_until,
                  hour_count,content_sha256,submitted_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,project,ctx.organization(),ctx.environment(),code,energy.id(),
                expected+1,normalized.unit(),normalized.title(),normalized.publisher(),
                normalized.uri(),normalized.edition(),normalized.procedure(),
                normalized.published(),normalized.vintage(),
                HexFormat.of().parseHex(normalized.sourceSha()),
                normalized.rights(),normalized.note(),from,until,
                normalized.hours().size(),HexFormat.of().parseHex(normalized.contentSha()),
                ctx.actor().id());
            for(Hour hour:normalized.hours())update(c,"""
                INSERT INTO app_open_efel_hourly_candidate_value
                  (project_id,candidate_id,hour_start,original_value,normalized_value)
                VALUES (?,?,?,?,?)
                """,project,id,hour.hour(),new BigDecimal(hour.originalValue()),
                new BigDecimal(hour.normalizedValue()));
            update(c,"""
                INSERT INTO app_open_efel_hourly_command
                  (actor_id,command_key,project_id,candidate_id,action,fingerprint)
                VALUES (?,?,?,?,?,?)
                """,ctx.actor().id(),command,project,id,"SUBMIT",fingerprint);
            return view(c,project,require(c,project,id));
        });
    }

    View review(String cookie,String project,String period,UUID id,String match,String key,
                Decision submitted) {
        return scope.write(cookie,project,"REVIEW_OPEN_EFEL_CANDIDATE",(c,ctx)->{
            if(ctx.owner() || !"REV".equals(ctx.role()))throw unavailable();
            Record entry=require(c,project,id);
            if(!OpenMonitoringDossiers.code(period).equals(entry.period()))
                throw unavailable();
            if(ctx.actor().id().equals(entry.author()))throw new PilotProblem(
                403,"EFEL_REVIEW_NOT_INDEPENDENT","reviewer",RULE,
                "O remetente dos fatores não pode conferir sua própria fonte.");
            if(submitted==null || !Set.of("CHECKED_INTERNAL","RETURNED").contains(submitted.outcome()))
                throw bad("outcome","Informe uma decisão de revisão interna.");
            String reason=clean(submitted.rationale(),15,2000,"rationale");
            String reference=clean(submitted.evidenceReference(),15,600,"evidenceReference");
            int expected=PilotCommands.expected(match);
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("EFEL-REVIEW|"+project+"|"+id+"|"+
                expected+"|"+submitted.outcome()+"|"+reason+"|"+reference);
            Command previous=command(c,ctx.actor().id(),command);
            if(previous!=null) {
                if(!project.equals(previous.project()) || !id.equals(previous.candidate()) ||
                   !"REVIEW".equals(previous.action()) ||
                   !MessageDigest.isEqual(previous.fingerprint(),fingerprint))
                    throw conflict("Chave de idempotência usada em outro parecer.");
                return view(c,project,entry);
            }
            Record latest=latest(c,project,entry.energy());
            if(latest==null || !id.equals(latest.id()) ||
               !"PENDING_INTERNAL_REVIEW_NOT_ADMITTED".equals(view(c,project,entry).state()) &&
               !"RETURNED_NOT_ADMITTED".equals(view(c,project,entry).state()) &&
               !"CHECKED_INTERNAL_NOT_ADMITTED".equals(view(c,project,entry).state()))
                throw conflict("Fonte substituída ou pacote de energia obsoleto.");
            Review old=latestReview(c,project,id);
            PilotCommands.requireVersion(expected,old==null?0:old.version());
            if(expected>=100000000)throw bad("version","Limite de pareceres atingido.");
            update(c,"""
                INSERT INTO app_open_efel_hourly_review
                  (project_id,candidate_id,version,outcome,rationale,evidence_reference,reviewer_id)
                VALUES (?,?,?,?,?,?,?)
                """,project,id,expected+1,submitted.outcome(),reason,reference,ctx.actor().id());
            update(c,"""
                INSERT INTO app_open_efel_hourly_command
                  (actor_id,command_key,project_id,candidate_id,action,fingerprint,review_version)
                VALUES (?,?,?,?,?,?,?)
                """,ctx.actor().id(),command,project,id,"REVIEW",fingerprint,expected+1);
            return view(c,project,entry);
        });
    }

    /** This method exposes a status only; no source values or document metadata. */
    static String state(Connection c,String project,String period) throws SQLException {
        Record last=first(c,RECORD_SQL+"""
            WHERE project_id=? AND period_code=?
            ORDER BY submitted_at DESC,id DESC LIMIT 1
            """,OpenEfelCandidates::record,project,period);
        return last==null?"EFEL_SOURCE_MISSING":view(c,project,last).state();
    }

    private static Normal normalize(Input input,Energy energy,String project) throws SQLException {
        String title=clean(input.sourceTitle(),10,200,"sourceTitle");
        String publisher=clean(input.sourcePublisher(),3,160,"sourcePublisher");
        String edition=clean(input.sourceEdition(),2,100,"sourceEdition");
        String procedure=clean(input.claimedProcedure(),15,300,"claimedProcedure");
        String vintage=clean(input.sourceVintage(),4,100,"sourceVintage");
        String rights=clean(input.sourceRightsReference(),15,500,"sourceRightsReference");
        String note=clean(input.evidenceNote(),15,2000,"evidenceNote");
        String uri=clean(input.sourceUri(),12,600,"sourceUri");
        URI parsed;
        try {parsed=URI.create(uri);}catch(IllegalArgumentException error){
            throw bad("sourceUri","Informe URL HTTPS válida da fonte declarada.");
        }
        if(!"https".equalsIgnoreCase(parsed.getScheme()) ||
           parsed.getHost()==null || parsed.getUserInfo()!=null ||
           parsed.getFragment()!=null)
            throw bad("sourceUri","Informe URL HTTPS válida, sem credenciais.");
        String sha=clean(input.sourceSha256(),64,64,"sourceSha256").toLowerCase();
        if(!sha.matches("[0-9a-f]{64}"))throw bad("sourceSha256",
            "Informe SHA-256 em hexadecimal da fonte declarada; não é comprovante autenticado.");
        if(input.publicationDate()==null ||
            input.publicationDate().isAfter(LocalDate.now(ZoneOffset.UTC)))
            throw bad("publicationDate","Informe data de publicação não futura.");
        String unit=clean(input.unit(),8,20,"unit");
        if(!Set.of("tCO2e/MWh","tCO2e/kWh","kgCO2e/kWh").contains(unit))
            throw bad("unit","Aceita somente unidades de CO2e elétrico; não misture gases.");
        if(input.hours()==null || input.hours().isEmpty() || input.hours().size()>744)
            throw bad("hours","Informe uma amostra para cada hora, no máximo 744.");
        List<Hour> normalized=new ArrayList<>();
        Set<java.time.Instant> seen=new HashSet<>();
        for(HourInput item:input.hours()) {
            if(item==null || item.hour()==null || item.value()==null)
                throw bad("hours","Não assuma zero para uma hora ausente.");
            var hour=item.hour().toInstant();
            if(hour.getEpochSecond()%3600!=0 || !seen.add(hour))
                throw bad("hours","Horários devem ser UTC integrais e sem duplicatas.");
            OpenEmissionParameterMath.Preview converted;
            try {converted=OpenEmissionParameterMath.convert("EF_GRID","CO2e",unit,item.value());}
            catch(IllegalArgumentException error){throw bad("hours",error.getMessage());}
            normalized.add(new Hour(hour.atOffset(ZoneOffset.UTC),
                item.value().toPlainString(),converted.normalizedValue().toPlainString()));
        }
        normalized.sort(Comparator.comparing(Hour::hour));
        String canonical="CF-OPEN-EFEL-HOURLY-V1|"+project+"|"+energy.id()+"|"+
            energy.sha()+"|"+title+"|"+publisher+"|"+uri+"|"+edition+"|"+
            procedure+"|"+input.publicationDate()+"|"+vintage+"|"+sha+"|"+
            rights+"|"+note+"|"+unit;
        StringBuilder content=new StringBuilder(canonical);
        for(Hour h:normalized)content.append('|').append(h.hour().toInstant())
            .append(':').append(h.originalValue()).append(':').append(h.normalizedValue());
        return new Normal(title,publisher,uri,edition,procedure,input.publicationDate(),
            vintage,sha,rights,note,unit,List.copyOf(normalized),digest(content.toString()));
    }

    private static String digest(String canonical) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }

    private static List<Hour> hours(Connection c,String project,UUID candidate) throws SQLException {
        return query(c,"""
            SELECT hour_start,original_value,normalized_value
            FROM app_open_efel_hourly_candidate_value
            WHERE project_id=? AND candidate_id=?
            ORDER BY hour_start
            """,r->new Hour(r.getObject(1,OffsetDateTime.class),
                r.getBigDecimal(2).toPlainString(),r.getBigDecimal(3).toPlainString()),
            project,candidate);
    }

    private static List<OffsetDateTime> energyHours(Connection c,String project,UUID energy)
        throws SQLException {
        return query(c,"""
            SELECT DISTINCT hour_start FROM app_open_e8_energy_candidate_hour
            WHERE project_id=? AND candidate_id=?
            ORDER BY hour_start
            """,r->r.getObject(1,OffsetDateTime.class),project,energy);
    }

    private static Energy energy(Connection c,String project,UUID energyId) throws SQLException {
        return first(c,"""
            SELECT id,dossier_code,dossier_version,method_review_version,
              encode(source_sha256,'hex') AS sha,status,hour_count
            FROM app_open_e8_energy_candidate WHERE project_id=? AND id=?
            """,r->new Energy(r.getObject("id",UUID.class),r.getString("dossier_code"),
                r.getInt("dossier_version"),r.getInt("method_review_version"),
                r.getString("sha"),r.getString("status"),r.getInt("hour_count")),
            project,energyId);
    }
    private static UUID latestEnergy(Connection c,String project,String period) throws SQLException {
        return first(c,"""
            SELECT id FROM app_open_e8_energy_candidate
            WHERE project_id=? AND dossier_code=?
            ORDER BY created_at DESC,id DESC LIMIT 1
            """,r->r.getObject(1,UUID.class),project,period);
    }

    private static final String RECORD_SQL="""
        SELECT id,period_code,energy_candidate_id,version,source_title,
         source_publisher,source_uri,source_edition,claimed_procedure,
         source_publication_date,source_vintage,
         encode(source_sha256_claimed,'hex') AS source_sha,
         source_rights_reference,evidence_note,original_unit,
         encode(content_sha256,'hex') AS content_sha,hour_count,
         submitted_by,submitted_at
        FROM app_open_efel_hourly_candidate
        """;
    private static Record record(java.sql.ResultSet r) throws SQLException {
        return new Record(r.getObject("id",UUID.class),r.getString("period_code"),
            r.getObject("energy_candidate_id",UUID.class),r.getInt("version"),
            r.getString("source_title"),r.getString("source_publisher"),
            r.getString("source_uri"),r.getString("source_edition"),
            r.getString("claimed_procedure"),r.getObject("source_publication_date",LocalDate.class),
            r.getString("source_vintage"),r.getString("source_sha"),
            r.getString("source_rights_reference"),r.getString("evidence_note"),
            r.getString("original_unit"),r.getString("content_sha"),
            r.getInt("hour_count"),r.getObject("submitted_by",UUID.class),
            r.getObject("submitted_at",OffsetDateTime.class));
    }
    private static Record latest(Connection c,String project,UUID energy) throws SQLException {
        return first(c,RECORD_SQL+"""
            WHERE project_id=? AND energy_candidate_id=?
            ORDER BY version DESC LIMIT 1
            """,OpenEfelCandidates::record,project,energy);
    }
    private static Record require(Connection c,String project,UUID id) throws SQLException {
        Record record=first(c,RECORD_SQL+"""
            WHERE project_id=? AND id=?
            """,OpenEfelCandidates::record,project,id);
        if(record==null)throw unavailable();
        return record;
    }
    private static Review latestReview(Connection c,String project,UUID candidate) throws SQLException {
        return first(c,"""
            SELECT version,outcome,rationale,evidence_reference,reviewer_id,reviewed_at
            FROM app_open_efel_hourly_review
            WHERE project_id=? AND candidate_id=?
            ORDER BY version DESC LIMIT 1
            """,r->new Review(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),
                r.getObject(5,UUID.class),r.getObject(6,OffsetDateTime.class)),
            project,candidate);
    }
    private static Command command(Connection c,UUID actor,String key) throws SQLException {
        return first(c,"""
            SELECT project_id,candidate_id,action,fingerprint
            FROM app_open_efel_hourly_command
            WHERE actor_id=? AND command_key=?
            """,r->new Command(r.getString(1),r.getObject(2,UUID.class),
                r.getString(3),r.getBytes(4)),actor,key);
    }

    private static View view(Connection c,String project,Record record) throws SQLException {
        List<String> gates=new ArrayList<>();
        Energy energy=energy(c,project,record.energy());
        boolean stale=energy==null || !record.energy().equals(
            latestEnergy(c,project,record.period())) ||
            !"NOT_ADMITTED".equals(OpenE8EnergyCandidates.state(c,project,record.period()));
        if(stale)gates.add("ENERGY_CANDIDATE_CHANGED_OR_STALE");
        List<Hour> rows=hours(c,project,record.id());
        if(energy==null || rows.size()!=record.hourCount() ||
           !consistentHours(energyHours(c,project,record.energy()),rows)) {
            stale=true;gates.add("EFEL_HOUR_COVERAGE_CHANGED");
        }
        Record last=latest(c,project,record.energy());
        boolean superseded=last!=null && last.version()!=record.version();
        if(superseded)gates.add("EFEL_SOURCE_VERSION_SUPERSEDED");
        Review review=latestReview(c,project,record.id());
        String state=stale?"STALE_NOT_ADMITTED":
            superseded?"SUPERSEDED_NOT_ADMITTED":
            review==null?"PENDING_INTERNAL_REVIEW_NOT_ADMITTED":
            "RETURNED".equals(review.outcome())?"RETURNED_NOT_ADMITTED":
            "CHECKED_INTERNAL_NOT_ADMITTED";
        if(review==null)gates.add("EFEL_SOURCE_REVIEW_REQUIRED");
        // The URL, source SHA and rights reference are claimant metadata; no
        // external source contents are fetched or presumed authenticated.
        gates.add("NORMATIVE_SOURCE_ADMISSION_REQUIRED");
        return new View(record.id(),record.period(),record.energy(),record.version(),
            record.title(),record.publisher(),record.uri(),record.edition(),
            record.procedure(),record.publicationDate(),record.vintage(),
            record.sourceSha(),record.rights(),record.note(),record.unit(),
            "tCO2e/kWh",record.contentSha(),record.hourCount(),state,stale,
            false,record.author(),record.createdAt(),review,List.copyOf(rows),
            List.copyOf(gates));
    }

    private static boolean consistentHours(List<OffsetDateTime> expected,List<Hour> actual) {
        if(expected.size()!=actual.size())return false;
        for(int i=0;i<expected.size();i++)
            if(!expected.get(i).toInstant().equals(actual.get(i).hour().toInstant()))
                return false;
        return true;
    }
    static void requireCoverage(Connection c,String project,Energy energy,List<Hour> hours)
        throws SQLException {
        List<OffsetDateTime> expected=energyHours(c,project,energy.id());
        if(expected.size()!=energy.hourCount() || !consistentHours(expected,hours))
            throw conflict("EFel deve cobrir exatamente cada hora real de EC: sem lacunas, extrapolação ou horários extras.");
    }
    private static String clean(String value,int min,int max,String field) {
        if(value==null || value.strip().length()<min || value.strip().length()>max ||
           value.codePoints().anyMatch(Character::isISOControl))
            throw bad(field,"Texto ou referência com tamanho ou formato inválido.");
        return value.strip();
    }
    private static PilotProblem bad(String field,String message) {
        return PilotProblem.invalid("EFEL_CANDIDATE_INVALID",field,RULE,message);
    }
    private static PilotProblem conflict(String message) {
        return new PilotProblem(409,"EFEL_CANDIDATE_CONFLICT","candidate",RULE,message);
    }
    private static PilotProblem unavailable() {
        return new PilotProblem(404,"EFEL_CANDIDATE_NOT_VISIBLE","candidate",RULE,
            "Fonte indisponível.");
    }
}
