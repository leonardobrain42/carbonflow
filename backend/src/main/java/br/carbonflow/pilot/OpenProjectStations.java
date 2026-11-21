package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

/** Site metadata plus immutable version references into the canonical V52 charger inventory. */
@ApplicationScoped
class OpenProjectStations {
    private static final String RULE = "CF-UX-TECH-001";
    private static final Set<String> UF = Set.of("AC","AL","AP","AM","BA","CE","DF","ES","GO","MA",
        "MT","MS","MG","PA","PB","PR","PE","PI","RJ","RN","RS","RO","RR","SC","SP","SE","TO");
    private final DynamicProjectScope scopes;
    private final ObjectMapper mapper;

    record SiteInput(String name, String municipality, String state, String address,
        String operationalState, LocalDate plannedOn, LocalDate commissionedOn, String sourceReference) {}
    record Site(String code, int version, SiteInput details, OffsetDateTime recordedAt) {}
    record Charger(String code, int version, String siteCode, String kind, BigDecimal powerKw,
        String grid, String topology, String timeZone, String sourceReference) {}
    record Selection(String chargerCode, int chargerVersion, String siteCode, int siteVersion) {}
    record Boundary(int version, List<Selection> selected, String state,
        List<String> findings, boolean usableForE8) {}
    /** Boundary pins only. Meter flags are false unless every selected charger matches the current revision. */
    record EnergyPosture(String boundary, boolean plannedOnly, boolean edMeter, boolean ecMeter) {
        static final EnergyPosture NONE = new EnergyPosture("NO_SELECTION", false, false, false);
    }
    record Catalog(String projectId, String environment, List<Site> sites,
        List<Charger> chargers, Boundary boundary) {}
    record Choice(String code, int version) {}
    record BoundaryInput(List<Choice> chargers) {}
    private record Receipt(String action, String target, byte[] fingerprint, String result) {}

    OpenProjectStations(DynamicProjectScope scopes, ObjectMapper mapper) {
        this.scopes=scopes;this.mapper=mapper;
    }

    Catalog get(String token, String project) {
        return scopes.read(token,project,"VIEW_OPEN_MONITORING",OpenProjectStations::catalog);
    }

    Site saveSite(String token,String project,String rawCode,String match,String key,SiteInput raw) {
        return scopes.write(token,project,"SAVE_OPEN_MONITORING",(c,scope)->{
            String code=OpenMonitoringConfiguration.code(rawCode);
            SiteInput input=normalize(raw);
            int expected=PilotCommands.expected(match);
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("SITE|"+project+"|"+code+"|"+expected+"|"+json(input));
            lock(c,project+"|SITE|"+code);
            Receipt previous=receipt(c,scope.actor().id(),command);
            if(previous!=null)return replay(previous,"SAVE_SITE",code,fingerprint,Site.class);
            Site old=site(c,project,code);
            PilotCommands.requireVersion(expected,old==null?0:old.version());
            update(c,"""
                INSERT INTO app_open_site_revision
                (project_id,organization_id,environment,site_code,version,display_name,
                 municipality,state_code,address,operational_state,planned_on,commissioned_on,
                 source_reference,recorded_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,project,scope.organization(),scope.environment(),code,expected+1,input.name(),
                input.municipality(),input.state(),input.address(),input.operationalState(),
                input.plannedOn(),input.commissionedOn(),input.sourceReference(),scope.actor().id());
            Site saved=site(c,project,code);
            store(c,scope.actor().id(),command,project,"SAVE_SITE",code,fingerprint,saved);
            return saved;
        });
    }

    Boundary saveBoundary(String token,String project,String match,String key,BoundaryInput raw) {
        return scopes.write(token,project,"SAVE_OPEN_MONITORING",(c,scope)->{
            if(raw==null || raw.chargers()==null || raw.chargers().size()>100)
                throw invalid("chargers");
            List<Choice> choices=new ArrayList<>(raw.chargers());
            if(choices.stream().anyMatch(x->x==null||x.code()==null||x.version()<1))
                throw invalid("chargers");
            choices.sort(Comparator.comparing(Choice::code));
            Set<String> distinct=new HashSet<>();
            for(Choice item:choices)if(!distinct.add(item.code()))throw invalid("chargers");
            int expected=PilotCommands.expected(match);
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("BOUNDARY|"+project+"|"+expected+"|"+json(choices));
            lock(c,project+"|BOUNDARY");
            Receipt previous=receipt(c,scope.actor().id(),command);
            if(previous!=null)return replay(previous,"SAVE_BOUNDARY","BOUNDARY",fingerprint,Boundary.class);
            int version=currentBoundary(c,project);
            PilotCommands.requireVersion(expected,version);
            Catalog catalog=catalog(c,scope);
            List<Selection> refs=new ArrayList<>();
            for(Choice chosen:choices){
                Charger charger=catalog.chargers().stream().filter(x->x.code().equals(chosen.code()))
                    .findFirst().orElseThrow(()->invalid("chargers"));
                if(chosen.version()!=charger.version())
                    throw new PilotProblem(412,"VERSAO_OBSOLETA","chargers",RULE,
                        "O carregador mudou. Recarregue a seleção.");
                Site site=catalog.sites().stream().filter(x->x.code().equals(charger.siteCode()))
                    .findFirst().orElseThrow(()->new PilotProblem(409,"ESTACAO_NAO_CADASTRADA",
                        "siteCode",RULE,"Cadastre a estação do carregador primeiro."));
                if(!"DCFC".equals(charger.kind())||!"SIN".equals(charger.grid())||
                    !"GRID_ONLY".equals(charger.topology()))
                    throw new PilotProblem(422,"RAMO_NAO_SUPORTADO","chargers",RULE,
                        "Este recorte aceita somente DCFC, SIN e topologia somente rede.");
                refs.add(new Selection(charger.code(),charger.version(),site.code(),site.version()));
            }
            update(c,"""
                INSERT INTO app_open_project_boundary_revision
                (project_id,organization_id,environment,version,recorded_by)
                VALUES(?,?,?,?,?)
                """,project,scope.organization(),scope.environment(),version+1,scope.actor().id());
            for(Selection ref:refs){
                update(c,"""
                    INSERT INTO app_open_project_boundary_charger
                    (project_id,boundary_version,charger_code,charger_version,site_code,site_version)
                    VALUES(?,?,?,?,?,?)
                    """,project,version+1,ref.chargerCode(),ref.chargerVersion(),
                    ref.siteCode(),ref.siteVersion());
            }
            Boundary saved=boundary(version+1,refs,List.of());
            store(c,scope.actor().id(),command,project,"SAVE_BOUNDARY","BOUNDARY",fingerprint,saved);
            return saved;
        });
    }

    private static Catalog catalog(Connection c, DynamicProjectScope.Context scope) throws SQLException {
        String project=scope.id();
        List<Site> sites=query(c,"""
            SELECT DISTINCT ON(site_code) site_code,version,display_name,municipality,
             state_code,address,operational_state,planned_on,commissioned_on,source_reference,recorded_at
            FROM app_open_site_revision WHERE project_id=? ORDER BY site_code,version DESC
            """,OpenProjectStations::mapSite,project);
        List<Charger> chargers=query(c,"""
            SELECT DISTINCT ON(charger_code) charger_code,version,site_code,charger_kind,
             rated_power_kw,grid,topology,time_zone,source_reference
            FROM app_open_charger_revision WHERE project_id=? ORDER BY charger_code,version DESC
            """,r->new Charger(r.getString(1),r.getInt(2),r.getString(3),r.getString(4),
                r.getBigDecimal(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9)),project);
        int version=currentBoundary(c,project);
        List<Selection> selected=version==0?List.of():query(c,"""
            SELECT charger_code,charger_version,site_code,site_version
            FROM app_open_project_boundary_charger WHERE project_id=? AND boundary_version=?
            ORDER BY charger_code
            """,r->new Selection(r.getString(1),r.getInt(2),r.getString(3),r.getInt(4)),project,version);
        List<String> findings=new ArrayList<>();
        for(Selection ref:selected){
            Site site=sites.stream().filter(x->x.code().equals(ref.siteCode())).findFirst().orElse(null);
            Charger charger=chargers.stream().filter(x->x.code().equals(ref.chargerCode())).findFirst().orElse(null);
            if(site==null||charger==null||site.version()!=ref.siteVersion()||
                charger.version()!=ref.chargerVersion()||
                !ref.siteCode().equals(charger.siteCode()))
                findings.add("Seleção obsoleta: "+ref.chargerCode()+". Refaça com as revisões atuais.");
            if(site!=null&&!"ACTIVE".equals(site.details().operationalState()))
                findings.add("Estação "+site.code()+" não declarada ativa; energia medida não pode ser presumida.");
        }
        return new Catalog(project,scope.environment(),List.copyOf(sites),List.copyOf(chargers),
            boundary(version,selected,findings));
    }

    private static Boundary boundary(int version,List<Selection> refs,List<String> problems){
        List<String> messages=new ArrayList<>(problems);
        boolean stale=messages.stream().anyMatch(x->x.startsWith("Seleção obsoleta"));
        String state=stale?"STALE":refs.isEmpty()?"NO_SELECTION":"DRAFT_REVIEW_REQUIRED";
        if(refs.isEmpty())messages.add("Nenhum carregador está na fronteira.");
        else if(!stale)messages.add("Fronteira preparatória: prova de titularidade, revisão e aplicabilidade pendentes.");
        return new Boundary(version,List.copyOf(refs),state,List.copyOf(messages),false);
    }

    static EnergyPosture posture(Connection c, String project) throws SQLException {
        int version = currentBoundary(c, project);
        if (version == 0) return EnergyPosture.NONE;
        record Pin(String charger, int chargerVersion, String site, int siteVersion,
                Integer currentCharger, String currentSite, Integer currentSiteVersion,
                String operational) {}
        List<Pin> pins = query(c, """
            SELECT b.charger_code, b.charger_version, b.site_code, b.site_version,
              c.version AS current_charger, c.site_code AS current_site,
              s.version AS current_site_version, s.operational_state
            FROM app_open_project_boundary_charger b
            LEFT JOIN (
              SELECT DISTINCT ON (charger_code) charger_code, version, site_code
              FROM app_open_charger_revision WHERE project_id=?
              ORDER BY charger_code, version DESC
            ) c ON c.charger_code=b.charger_code
            LEFT JOIN (
              SELECT DISTINCT ON (site_code) site_code, version, operational_state
              FROM app_open_site_revision WHERE project_id=?
              ORDER BY site_code, version DESC
            ) s ON s.site_code=b.site_code
            WHERE b.project_id=? AND b.boundary_version=?
            ORDER BY b.charger_code
            """, r -> new Pin(r.getString("charger_code"), r.getInt("charger_version"),
                r.getString("site_code"), r.getInt("site_version"),
                (Integer) r.getObject("current_charger"), r.getString("current_site"),
                (Integer) r.getObject("current_site_version"), r.getString("operational_state")),
            project, project, project, version);
        if (pins.isEmpty()) return EnergyPosture.NONE;
        boolean stale = pins.stream().anyMatch(pin -> pin.currentCharger() == null
            || pin.currentSiteVersion() == null || pin.currentSite() == null
            || pin.currentCharger() != pin.chargerVersion()
            || pin.currentSiteVersion() != pin.siteVersion()
            || !pin.currentSite().equals(pin.site()));
        if (stale) return new EnergyPosture("STALE", false, false, false);
        record MeterPin(String charger, int chargerVersion, String kind) {}
        List<MeterPin> meters = query(c, """
            SELECT DISTINCT ON (meter_code) charger_code, charger_version, energy_kind
            FROM app_open_meter_configuration_revision WHERE project_id=?
            ORDER BY meter_code, version DESC
            """, r -> new MeterPin(r.getString("charger_code"), r.getInt("charger_version"),
                r.getString("energy_kind")), project);
        boolean ed = pins.stream().allMatch(pin -> meters.stream().anyMatch(meter ->
            pin.charger().equals(meter.charger()) && meter.chargerVersion() == pin.chargerVersion()
                && "ED".equals(meter.kind())));
        boolean ec = pins.stream().allMatch(pin -> meters.stream().anyMatch(meter ->
            pin.charger().equals(meter.charger()) && meter.chargerVersion() == pin.chargerVersion()
                && "EC".equals(meter.kind())));
        boolean plannedOnly = pins.stream().noneMatch(pin -> "ACTIVE".equals(pin.operational()));
        return new EnergyPosture("SELECTED", plannedOnly, ed, ec);
    }

    private static int currentBoundary(Connection c,String project) throws SQLException {
        Integer value=first(c,"SELECT max(version) FROM app_open_project_boundary_revision WHERE project_id=?",
            r->(Integer)r.getObject(1),project);
        return value==null?0:value;
    }
    private static Site site(Connection c,String project,String code) throws SQLException {
        return first(c,"""
            SELECT site_code,version,display_name,municipality,state_code,address,operational_state,
              planned_on,commissioned_on,source_reference,recorded_at
            FROM app_open_site_revision WHERE project_id=? AND site_code=? ORDER BY version DESC LIMIT 1
            """,OpenProjectStations::mapSite,project,code);
    }
    private static Site mapSite(java.sql.ResultSet r) throws SQLException {
        return new Site(r.getString("site_code"),r.getInt("version"),
            new SiteInput(r.getString("display_name"),r.getString("municipality"),
                r.getString("state_code").strip(),r.getString("address"),r.getString("operational_state"),
                r.getObject("planned_on",LocalDate.class),r.getObject("commissioned_on",LocalDate.class),
                r.getString("source_reference")),r.getObject("recorded_at",OffsetDateTime.class));
    }
    static SiteInput normalize(SiteInput raw){
        if(raw==null)throw invalid("body");
        String state=raw.state()==null?"":raw.state().trim().toUpperCase(Locale.ROOT);
        if(!UF.contains(state)||raw.operationalState()==null||
           !Set.of("PLANNED","INSTALLED","ACTIVE").contains(raw.operationalState()))
            throw invalid("state");
        if("ACTIVE".equals(raw.operationalState())&&raw.commissionedOn()==null||
           "PLANNED".equals(raw.operationalState())&&raw.commissionedOn()!=null)
            throw invalid("commissionedOn");
        if(raw.plannedOn()!=null&&raw.commissionedOn()!=null&&
            raw.plannedOn().isAfter(raw.commissionedOn()))throw invalid("commissionedOn");
        return new SiteInput(text(raw.name(),3,120,true,"name"),
            text(raw.municipality(),2,100,true,"municipality"),state,
            text(raw.address(),3,200,false,"address"),raw.operationalState(),
            raw.plannedOn(),raw.commissionedOn(),text(raw.sourceReference(),3,300,false,"sourceReference"));
    }
    private static String text(String raw,int min,int max,boolean required,String field){
        if(raw==null||raw.isBlank()){
            if(required)throw invalid(field);
            return null;
        }
        String s=raw.strip();
        if(s.length()<min||s.length()>max||s.codePoints().anyMatch(Character::isISOControl))
            throw invalid(field);
        return s;
    }
    private static PilotProblem invalid(String field){
        return PilotProblem.invalid("ESTACAO_INVALIDA",field,RULE,
            "Confira localização, data, situação operacional e seleção de carregadores.");
    }
    private static void lock(Connection c,String id) throws SQLException {
        first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->true,id);
    }
    private Receipt receipt(Connection c,UUID actor,String key) throws SQLException {
        return first(c,"""
            SELECT action,target_code,fingerprint,result::text FROM app_open_site_command
            WHERE actor_id=? AND command_key=?
            """,r->new Receipt(r.getString(1),r.getString(2),r.getBytes(3),r.getString(4)),actor,key);
    }
    private <T> T replay(Receipt r,String action,String target,byte[] hash,Class<T> type){
        if(!r.action().equals(action)||!r.target().equals(target)||
            !MessageDigest.isEqual(r.fingerprint(),hash))
            throw new PilotProblem(409,"CHAVE_REUSADA","Idempotency-Key",RULE,
                "A chave já pertence a outro comando.");
        try{return mapper.readValue(r.result(),type);}
        catch(JsonProcessingException e){throw new IllegalStateException("Recibo ilegível",e);}
    }
    private void store(Connection c,UUID actor,String key,String project,String action,
                       String target,byte[] hash,Object result) throws SQLException {
        update(c,"""
            INSERT INTO app_open_site_command
            (actor_id,command_key,project_id,action,target_code,fingerprint,result)
            VALUES(?,?,?,?,?,?,?::jsonb)
            """,actor,key,project,action,target,hash,json(result));
    }
    private String json(Object data){
        try{return mapper.writeValueAsString(data);}
        catch(JsonProcessingException e){throw new IllegalStateException("Falha ao serializar",e);}
    }
}
