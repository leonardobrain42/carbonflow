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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * VM0038 OPEN: edition-bound, typed planning inputs. Not a canonical ledger, E8 run,
 * legal event, verified evidence, VVB decision, or replacement for historical text.
 */
@ApplicationScoped
class TypedProjectGuides {
    private static final String RULE="CF-UX-TECH-001";
    record Field(String key,String label,String type,List<String> options,String helpText,
        boolean required,String showWhenField,String showWhenValue,
        String requiredWhenField,String requiredWhenValue,String unit,
        BigDecimal min,BigDecimal max,Map<String,String> optionParents) {
        Field withOptions(List<String> values,Map<String,String> parents){
            return new Field(key,label,type,values,helpText,required,showWhenField,showWhenValue,
                requiredWhenField,requiredWhenValue,unit,min,max,parents);
        }
    }
    record Guide(String id,String title,String guidance,String methodology,String edition,
        String branch,int definitionVersion,int version,List<Field> fields,
        Map<String,JsonNode> answers,Map<String,Integer> referenceVersions,Map<String,String> legacyAnswers,
        List<String> legacyNeedsReview,List<String> missing,boolean editable,String state,
        boolean normativeApproved) {}
    record Catalog(String methodology,List<Guide> guides) {}
    private record Revision(int version,int definitionVersion,String answers,String snapshot,
                            String referenceVersions,String methodology,String edition) {}
    private record Receipt(String guide,byte[] fingerprint,String result) {}
    private final DynamicProjectScope scopes;
    private final ObjectMapper mapper;

    TypedProjectGuides(DynamicProjectScope scopes,ObjectMapper mapper){
        this.scopes=scopes;this.mapper=mapper;
    }
    Catalog get(String token,String project){
        return scopes.read(token,project,"VIEW_GUIDES",this::catalog);
    }
    Guide revision(String token,String project,String guideId,int version){
        return scopes.read(token,project,"VIEW_GUIDES",(c,scope)->{
            Guide current=find(catalog(c,scope),guideId);
            if(version<=0||version>current.version())throw unavailable();
            Revision saved=revision(c,project,guideId,version);
            if(saved==null)throw unavailable();
            List<Field> snapshot=read(saved.snapshot(),new TypeReference<>(){});
            List<Field> fields=withOptions(c,scope,visible(snapshot,scope));
            return view(current.id(),current.title(),current.guidance(),saved.methodology(),
                saved.edition(),current.branch(),saved.definitionVersion(),version,fields,
                read(saved.answers(),new TypeReference<>(){}),
                read(saved.referenceVersions(),new TypeReference<>(){}),current.legacyAnswers(),
                false,stale(c,project,fields,read(saved.answers(),new TypeReference<>(){}),
                    read(saved.referenceVersions(),new TypeReference<>(){})));
        });
    }
    Guide save(String token,String project,String guideId,String match,String schemaHeader,
               String idempotencyHeader,Map<String,JsonNode> submitted){
        return scopes.write(token,project,"SAVE_GUIDE",(c,scope)->{
            Guide guide=find(catalog(c,scope),guideId);
            if(!guide.editable())throw unavailable();
            int expected=PilotCommands.expected(match);
            if(schemaHeader==null||!schemaHeader.equals(Integer.toString(guide.definitionVersion())))
                throw new PilotProblem(409,"DEFINICAO_DESATUALIZADA","definitionVersion",RULE,
                    "O formulário ou sua edição mudou. Recarregue as perguntas.");
            String key=PilotCommands.key(idempotencyHeader);
            Map<String,JsonNode> normalized=normalize(c,scope,guide.fields(),submitted);
            Map<String,Integer> referenceVersions=new TreeMap<>();
            // Do not discard hidden monitoring references when a permitted guide actor
            // updates unrelated visible answers without monitoring permissions.
            Revision previous=guide.version()==0?null:revision(c,project,guideId,guide.version());
            if(previous!=null){
                Map<String,JsonNode> old=read(previous.answers(),new TypeReference<>(){});
                Set<String> visibleKeys=new HashSet<>();
                guide.fields().forEach(f->visibleKeys.add(f.key()));
                for(var item:old.entrySet())
                    if(!visibleKeys.contains(item.getKey()))normalized.put(item.getKey(),item.getValue());
                Map<String,Integer> pinned=read(previous.referenceVersions(),new TypeReference<>(){});
                for(var ref:pinned.entrySet())
                    if(!visibleKeys.contains(ref.getKey()))referenceVersions.put(ref.getKey(),ref.getValue());
            }
            for(String field:List.of("stationCode","chargerCode")){
                if(normalized.containsKey(field)&&guide.fields().stream().anyMatch(f->f.key().equals(field))){
                    String table="stationCode".equals(field)?"app_open_site_revision":"app_open_charger_revision";
                    String column="stationCode".equals(field)?"site_code":"charger_code";
                    Integer version=first(c,"SELECT version FROM "+table+
                        " WHERE project_id=? AND "+column+"=? ORDER BY version DESC LIMIT 1",
                        r->r.getInt(1),project,normalized.get(field).asText());
                    if(version==null)throw invalid(field);
                    referenceVersions.put(field,version);
                }
            }
            Map<String,JsonNode> values=Collections.unmodifiableMap(new TreeMap<>(normalized));
            byte[] hash=AccessSecrets.digest("TYPED_GUIDE|"+project+"|"+guideId+"|"+expected+"|"+
                guide.definitionVersion()+"|"+json(values));
            Receipt receipt=first(c,"""
                SELECT guide_id,fingerprint,result::text FROM app_typed_guide_command
                WHERE actor_id=? AND command_key=?
                """,r->new Receipt(r.getString(1),r.getBytes(2),r.getString(3)),scope.actor().id(),key);
            if(receipt!=null){
                if(!guideId.equals(receipt.guide())||!MessageDigest.isEqual(hash,receipt.fingerprint()))
                    throw new PilotProblem(409,"CHAVE_REUSADA","Idempotency-Key",RULE,
                        "A chave já identifica outro conteúdo.");
                return read(receipt.result(),new TypeReference<Guide>(){});
            }
            PilotCommands.requireVersion(expected,guide.version());
            if(expected>=100000000)throw invalid("version");
            // Freeze the full definition snapshot, including fields currently restricted
            // to monitoring actors, so historical interpretation is always deterministic.
            String snapshot=first(c,"""
                SELECT fields::text FROM app_typed_guide_schema
                WHERE guide_id=? AND definition_version=?
                """,r->r.getString(1),guideId,guide.definitionVersion());
            update(c,"""
                INSERT INTO app_typed_guide_revision
                 (project_id,organization_id,environment,guide_id,version,definition_version,
                  methodology,edition,answers,reference_versions,definition_snapshot,actor_id)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)
                """,project,scope.organization(),scope.environment(),guideId,expected+1,
                guide.definitionVersion(),guide.methodology(),guide.edition(),json(values),
                json(referenceVersions),snapshot,scope.actor().id());
            Guide saved=view(guide.id(),guide.title(),guide.guidance(),guide.methodology(),
                guide.edition(),guide.branch(),guide.definitionVersion(),expected+1,
                guide.fields(),values,referenceVersions,guide.legacyAnswers(),true,
                stale(c,project,guide.fields(),values,referenceVersions));
            update(c,"""
                INSERT INTO app_typed_guide_command
                 (actor_id,command_key,project_id,guide_id,fingerprint,result)
                VALUES(?,?,?,?,?,?::jsonb)
                """,scope.actor().id(),key,project,guideId,hash,json(saved));
            return saved;
        });
    }

    private Catalog catalog(Connection c,DynamicProjectScope.Context scope) throws SQLException {
        String methodology=ProjectJourney.profile(ProjectGuides.candidate(c,scope.id())).id();
        List<Guide> list=query(c,"""
            SELECT g.id,g.title,g.guidance,g.activity,s.methodology,s.edition,s.branch,
                   s.definition_version,s.fields::text AS fields,r.version,r.answers::text AS answers,
                   r.reference_versions::text AS ref_versions,
                   legacy.answers::text AS legacy_answers
            FROM app_typed_guide_schema s JOIN app_technical_guide g ON g.id=s.guide_id
            LEFT JOIN LATERAL (
                SELECT version,answers,reference_versions FROM app_typed_guide_revision
                WHERE project_id=? AND guide_id=g.id ORDER BY version DESC LIMIT 1
            ) r ON true
            LEFT JOIN LATERAL (
                SELECT answers FROM app_technical_guide_revision
                WHERE project_id=? AND guide_id=g.id ORDER BY version DESC LIMIT 1
            ) legacy ON true
            WHERE s.methodology=? AND s.definition_version=(
                SELECT max(definition_version) FROM app_typed_guide_schema WHERE guide_id=g.id
            ) ORDER BY g.position
            """,r->{
                if(!scope.owner()&&!scope.actions().contains("GUIDE_"+r.getString("activity")))
                    return null;
                List<Field> original=read(r.getString("fields"),new TypeReference<>(){});
                List<Field> fields=withOptions(c,scope,visible(original,scope));
                Map<String,JsonNode> answers=r.getString("answers")==null?Map.of():
                    read(r.getString("answers"),new TypeReference<>(){});
                Map<String,String> old=r.getString("legacy_answers")==null?Map.of():
                    read(r.getString("legacy_answers"),new TypeReference<>(){});
                Map<String,Integer> refs=r.getString("ref_versions")==null?Map.of():
                    read(r.getString("ref_versions"),new TypeReference<>(){});
                return view(r.getString("id"),r.getString("title"),r.getString("guidance"),
                    r.getString("methodology"),r.getString("edition"),r.getString("branch"),
                    r.getInt("definition_version"),r.getInt("version"),fields,answers,refs,old,
                    scope.owner()||scope.actions().contains("SAVE_GUIDE"),
                    stale(c,scope.id(),fields,answers,refs));
            },scope.id(),scope.id(),methodology);
        return new Catalog(methodology,list.stream().filter(Objects::nonNull).toList());
    }

    static List<Field> visible(List<Field> definitions,DynamicProjectScope.Context scope){
        if(scope.owner()||(scope.actions().contains("VIEW_OPEN_MONITORING")&&
            scope.actions().contains("SAVE_OPEN_MONITORING")))return definitions;
        return definitions.stream().filter(f->!Set.of("STATION_REF","EQUIPMENT_REF").contains(f.type()))
            .toList();
    }

    private List<Field> withOptions(Connection c,DynamicProjectScope.Context scope,List<Field> input)
            throws SQLException {
        List<Field> fields=new ArrayList<>();
        for(Field f:input){
            if("STATION_REF".equals(f.type())){
                List<String> codes=query(c,"""
                    SELECT DISTINCT ON(site_code) site_code FROM app_open_site_revision
                    WHERE project_id=? ORDER BY site_code,version DESC
                    """,r->r.getString(1),scope.id());
                fields.add(f.withOptions(codes,Map.of()));
            } else if("EQUIPMENT_REF".equals(f.type())){
                List<Map.Entry<String,String>> pairs=query(c,"""
                    SELECT charger_code,site_code FROM (
                        SELECT DISTINCT ON(charger_code) charger_code,site_code,
                            charger_kind,grid,topology
                        FROM app_open_charger_revision
                        WHERE project_id=? ORDER BY charger_code,version DESC
                    ) latest WHERE charger_kind='DCFC' AND grid='SIN' AND topology='GRID_ONLY'
                    ORDER BY charger_code
                    """,r->Map.entry(r.getString(1),r.getString(2)),scope.id());
                Map<String,String> parents=new LinkedHashMap<>();
                pairs.forEach(p->parents.put(p.getKey(),p.getValue()));
                fields.add(f.withOptions(List.copyOf(parents.keySet()),Map.copyOf(parents)));
            }else fields.add(f.withOptions(f.options()==null?List.of():f.options(),Map.of()));
        }
        return List.copyOf(fields);
    }

    private static boolean condition(Map<String,JsonNode> data,String key,String expected){
        if(key==null)return true;
        JsonNode node=data.get(key);
        if(node==null||node.isNull()||node.isTextual()&&node.asText().isBlank())return false;
        if("SET".equals(expected))return true;
        return Objects.equals(node.asText(),expected);
    }
    static boolean active(Field field,Map<String,JsonNode> answers){
        return condition(answers,field.showWhenField(),field.showWhenValue());
    }
    private static Guide view(String id,String title,String guidance,String method,String edition,
        String branch,int definitionVersion,int version,List<Field> fields,
        Map<String,JsonNode> raw,Map<String,Integer> refs,Map<String,String> legacy,
        boolean editable,boolean stale){
        Set<String> allowed=new HashSet<>();
        fields.forEach(f->allowed.add(f.key()));
        Map<String,JsonNode> answers=new TreeMap<>();
        raw.forEach((key,value)->{if(allowed.contains(key))answers.put(key,value);});
        List<String> missing=fields.stream().filter(f->active(f,answers))
            .filter(f->(f.required()||f.requiredWhenField()!=null&&
                condition(answers,f.requiredWhenField(),f.requiredWhenValue()))&&!answers.containsKey(f.key()))
            .map(Field::label).toList();
        List<String> legacyReview=version==0?List.copyOf(legacy.keySet().stream().sorted().toList()):List.of();
        String state=version==0?legacy.isEmpty()?"NOT_STARTED":"LEGACY_UNSTRUCTURED_REVIEW_REQUIRED":
            stale?"STALE_REFERENCE_REVIEW_REQUIRED":
            missing.isEmpty()?"FORM_COMPLETED_DECLARED":"INCOMPLETE_DECLARED";
        Map<String,Integer> allowedRefs=new TreeMap<>();
        refs.forEach((key,value)->{if(allowed.contains(key))allowedRefs.put(key,value);});
        return new Guide(id,title,guidance,method,edition,branch,definitionVersion,version,
            List.copyOf(fields),Map.copyOf(answers),Map.copyOf(allowedRefs),Map.copyOf(legacy),
            legacyReview,missing,editable,state,false);
    }
    private static boolean stale(Connection c,String project,List<Field> fields,
        Map<String,JsonNode> answers,Map<String,Integer> pinned) throws SQLException {
        for(Field field:fields){
            Integer expected=pinned.get(field.key());
            JsonNode value=answers.get(field.key());
            if(expected==null||value==null||!Set.of("STATION_REF","EQUIPMENT_REF").contains(field.type()))
                continue;
            String table="STATION_REF".equals(field.type())?"app_open_site_revision":"app_open_charger_revision";
            String column="STATION_REF".equals(field.type())?"site_code":"charger_code";
            Integer latest=first(c,"SELECT version FROM "+table+" WHERE project_id=? AND "+
                column+"=? ORDER BY version DESC LIMIT 1",r->r.getInt(1),project,value.asText());
            if(!Objects.equals(latest,expected))return true;
        }
        return false;
    }
    private Map<String,JsonNode> normalize(Connection c,DynamicProjectScope.Context scope,
        List<Field> fields,Map<String,JsonNode> raw) throws SQLException{
        if(raw==null||raw.size()>fields.size())throw invalid("answers");
        Map<String,Field> byKey=new HashMap<>();
        fields.forEach(f->byKey.put(f.key(),f));
        Map<String,JsonNode> answers=new TreeMap<>();
        for(var item:raw.entrySet()){
            Field field=byKey.get(item.getKey());
            if(field==null)throw invalid(item.getKey());
            JsonNode value=item.getValue();
            if(value==null||value.isNull()||value.isTextual()&&value.asText().isBlank()||
                value.isArray()&&value.size()==0)continue;
            if(!valid(field,value))throw invalid(field.key());
            answers.put(field.key(),value);
        }
        for(Field field:fields){
            if(!active(field,answers)){
                if(answers.containsKey(field.key()))throw invalid(field.key());
                continue;
            }
            if(field.requiredWhenField()!=null&&
                condition(answers,field.requiredWhenField(),field.requiredWhenValue())&&
                !answers.containsKey(field.key()))throw invalid(field.key());
        }
        if(answers.containsKey("periodStart")&&answers.containsKey("periodEnd")&&
            LocalDate.parse(answers.get("periodStart").asText()).isAfter(
                LocalDate.parse(answers.get("periodEnd").asText())))throw invalid("periodEnd");
        if(answers.containsKey("stationCode")){
            String site=answers.get("stationCode").asText();
            Boolean found=first(c,"""
                SELECT true FROM app_open_site_revision WHERE project_id=? AND site_code=?
                ORDER BY version DESC LIMIT 1
                """,r->true,scope.id(),site);
            if(found==null)throw invalid("stationCode");
        }
        if(answers.containsKey("chargerCode")){
            String code=answers.get("chargerCode").asText();
            String station=first(c,"""
                SELECT site_code FROM app_open_charger_revision
                WHERE project_id=? AND charger_code=?
                ORDER BY version DESC LIMIT 1
                """,r->r.getString(1),scope.id(),code);
            if(!Objects.equals(station,answers.get("stationCode")==null?null:
                answers.get("stationCode").asText()))throw invalid("chargerCode");
        }
        return answers;
    }
    private static boolean valid(Field f,JsonNode value){
        return switch(f.type()){
            case "TEXT","TEXTAREA" -> value.isTextual()&&value.asText().length()<=1600&&
                value.asText().length()>0&&value.asText().codePoints().noneMatch(Character::isISOControl);
            case "ENUM","STATION_REF","EQUIPMENT_REF" ->
                value.isTextual()&&f.options()!=null&&f.options().contains(value.asText());
            case "BOOLEAN" -> value.isBoolean();
            case "DATE" -> {
                try{yield value.isTextual()&&value.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    &&LocalDate.parse(value.asText())!=null;}
                catch(java.time.DateTimeException e){yield false;}
            }
            case "YEAR" -> value.isIntegralNumber()&&value.canConvertToInt()&&
                value.intValue()>=1900&&value.intValue()<=2100;
            case "DECIMAL" -> {
                if(!value.isNumber())yield false;
                BigDecimal number=value.decimalValue();
                yield number.scale()<=3&&number.signum()>0&&
                    (f.min()==null||number.compareTo(f.min())>=0)&&
                    (f.max()==null||number.compareTo(f.max())<=0);
            }
            case "MULTI_SELECT" -> {
                if(!value.isArray()||value.size()>10||f.options()==null)yield false;
                Set<String> seen=new HashSet<>();
                boolean ok=true;
                for(JsonNode item:value){
                    if(!item.isTextual()||!f.options().contains(item.asText())||
                        !seen.add(item.asText()))ok=false;
                }
                yield ok;
            }
            default -> false;
        };
    }
    private static Revision revision(Connection c,String project,String guide,int version)
        throws SQLException {
        return first(c,"""
            SELECT version,definition_version,answers::text,definition_snapshot::text,
              reference_versions::text,methodology,edition FROM app_typed_guide_revision
            WHERE project_id=? AND guide_id=? AND version=?
            """,r->new Revision(r.getInt(1),r.getInt(2),r.getString(3),r.getString(4),
                r.getString(5),r.getString(6),r.getString(7)),project,guide,version);
    }
    private static Guide find(Catalog list,String id){
        return list.guides().stream().filter(g->g.id().equals(id))
            .findFirst().orElseThrow(TypedProjectGuides::unavailable);
    }
    private static PilotProblem unavailable(){
        return new PilotProblem(404,"GUIA_INDISPONIVEL","guide",RULE,"Guia indisponível para esta conta.");
    }
    private static PilotProblem invalid(String field){
        return new PilotProblem(400,"RESPOSTA_TIPADA_INVALIDA",field,RULE,
            "Confira tipo, unidade, seleção, condição e referência do campo. Declaração não é prova.");
    }
    private <T> T read(String data,TypeReference<T> type){
        try{return mapper.readValue(data,type);}
        catch(java.io.IOException e){throw new IllegalStateException("Formulário inválido",e);}
    }
    private String json(Object object){
        try{return mapper.writeValueAsString(object);}
        catch(JsonProcessingException e){throw new IllegalStateException("Falha ao serializar guia",e);}
    }
}
