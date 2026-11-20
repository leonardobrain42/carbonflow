package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * An append-only demonstration of TOOL07 v7.0 Simple OM, Option A1.
 * Fails closed on vintage, source revision and review changes.
 * It NEVER creates an admissible E8 parameter or claims authentic plant data.
 */
@ApplicationScoped
class OpenTool07SimpleOmRuns {
    private static final String RULE="CF-OPEN-007";

    record Input(UUID workbookId,int workbookReviewVersion,
                 String inventorySourceReference,String inventorySha256,
                 String fiveYearSourceReference,String fiveYearSha256,
                 String fuelSourceExplanation,String systemBoundaryNote,
                 String importsExplanation,OpenTool07SimpleOmMath.Input calculation) {}
    record View(UUID id,String period,UUID workbookId,int workbookReviewVersion,
                String workbookSha256,int version,String inventorySourceReference,
                String inventorySha256,String fiveYearSourceReference,
                String fiveYearSha256,String fuelSourceExplanation,
                String systemBoundaryNote,String importsExplanation,
                String contentSha256,String state,boolean stale,boolean usableForE8,
                UUID createdBy,OffsetDateTime createdAt,
                OpenTool07SimpleOmMath.Preview preview,
                List<OpenTool07SimpleOmMath.ShareYear> shares,
                List<OpenTool07SimpleOmMath.Plant> plants,List<String> gates) {}

    private record Workbook(UUID id,String period,String method,String vintage,
                            int year,String sha,BigDecimal declaredOm,
                            int reviewVersion) {}
    private record Stored(UUID id,String period,UUID workbook,int workbookReview,
                          String workbookSha,int version,String inventoryRef,
                          String inventorySha,String fiveYearRef,String fiveYearSha,
                          String fuelNote,String boundaryNote,String importsNote,
                          String sha,UUID author,OffsetDateTime at,String approach,
                          int year) {}
    private record Command(String project,UUID run,byte[] fingerprint) {}
    private record Metadata(String inventoryRef,String inventorySha,String fiveYearRef,
                            String fiveYearSha,String fuelNote,String boundaryNote,
                            String importsNote) {}

    private final DynamicProjectScope scope;
    OpenTool07SimpleOmRuns(DynamicProjectScope scope){this.scope=scope;}

    List<View> list(String cookie,String project,String period,UUID workbookId) {
        return scope.read(cookie,project,"VIEW_OPEN_SIMPLE_OM_A1",(c,ctx)->{
            String code=OpenMonitoringDossiers.code(period);
            Workbook workbook=workbook(c,project,workbookId);
            if(workbook==null||!code.equals(workbook.period()))throw unavailable();
            List<Stored> stored=query(c,STORED_SQL+"""
                WHERE project_id=? AND workbook_id=?
                ORDER BY version DESC LIMIT 20
                """,OpenTool07SimpleOmRuns::stored,project,workbookId);
            var values=new ArrayList<View>();
            for(Stored item:stored)values.add(view(c,project,item));
            return List.copyOf(values);
        });
    }

    View submit(String cookie,String project,String period,UUID workbookId,
                String match,String key,Input submitted) {
        return scope.write(cookie,project,"PREPARE_OPEN_SIMPLE_OM_A1",(c,ctx)->{
            if(!ctx.owner()&&!"PREP".equals(ctx.role()))throw unavailable();
            String code=OpenMonitoringDossiers.code(period);
            Workbook source=workbook(c,project,workbookId);
            if(source==null||!code.equals(source.period()))throw unavailable();
            if(submitted==null||submitted.workbookId()==null||
               !workbookId.equals(submitted.workbookId())||submitted.calculation()==null)
                throw invalid("workbookId","Informe caderno e inventário compatíveis.");
            if(!"SIMPLE_OM".equals(source.method()) ||
               !"EX_POST".equals(source.vintage()))
                throw invalid("omMethod","Fatia suportada: Simple OM Option A1 de um ano, ex post.");
            if(source.year()!=submitted.calculation().dataYear())
                throw invalid("dataYear","Ano da geração difere do vintage do caderno.");
            if(!"CHECKED_INTERNAL_NOT_ADMITTED".equals(
                 OpenVt0011Workbooks.state(c,project,code)))
                throw conflict("A fonte EFel ou o caderno VT0011 já não estão conferidos e vigentes.");
            UUID currentWorkbook=first(c,"""
                SELECT id FROM app_open_vt0011_case2_workbook
                WHERE project_id=? AND period_code=?
                ORDER BY authored_at DESC,id DESC LIMIT 1
                """,r->r.getObject(1,UUID.class),project,code);
            if(!workbookId.equals(currentWorkbook))
                throw conflict("Caderno substituído por versão mais recente.");
            if(source.reviewVersion()!=submitted.workbookReviewVersion())
                throw new PilotProblem(412,"TOOL07_REVIEW_CHANGED","workbookReviewVersion",
                    RULE,"A conferência do caderno mudou. Reabra os dados e verifique.");
            Metadata meta=metadata(submitted);
            OpenTool07SimpleOmMath.Preview preview;
            try {preview=OpenTool07SimpleOmMath.calculate(submitted.calculation());}
            catch(IllegalArgumentException error){throw invalid("calculation",error.getMessage());}
            String sha=signature(project,source,submitted,meta);
            int expected=PilotCommands.expected(match);
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("TOOL07-SIMPLE-OM-A1|"+project+"|"+
                source.id()+"|"+expected+"|"+sha);
            Command existing=first(c,"""
                SELECT project_id,run_id,fingerprint
                FROM app_open_tool07_simple_om_command
                WHERE actor_id=? AND command_key=?
                """,r->new Command(r.getString(1),r.getObject(2,UUID.class),r.getBytes(3)),
                ctx.actor().id(),command);
            if(existing!=null){
                if(!project.equals(existing.project())||
                   !MessageDigest.isEqual(fingerprint,existing.fingerprint()))
                    throw conflict("Chave de idempotência já utilizada em outro cálculo.");
                return view(c,project,require(c,project,existing.run()));
            }
            int latest=first(c,"""
                SELECT coalesce(max(version),0) FROM app_open_tool07_simple_om_run
                WHERE project_id=? AND workbook_id=?
                """,r->r.getInt(1),project,workbookId);
            PilotCommands.requireVersion(expected,latest);
            if(latest>=100000000)throw invalid("version","Limite de revisões atingido.");
            Stored same=first(c,STORED_SQL+"""
                WHERE project_id=? AND workbook_id=?
                  AND content_sha256=?
                ORDER BY version DESC LIMIT 1
                """,OpenTool07SimpleOmRuns::stored,project,workbookId,
                HexFormat.of().parseHex(sha));
            if(same!=null)throw conflict("O mesmo conjunto de dados já foi registrado.");
            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_tool07_simple_om_run
                (id,project_id,organization_id,environment,period_code,
                 workbook_id,workbook_review_version,workbook_source_sha256,
                 version,data_year,share_approach,inventory_source_reference,
                 inventory_sha256_claimed,five_year_source_reference,
                 five_year_sha256_claimed,fuel_source_explanation,system_boundary_note,
                 imports_explanation,lcmr_share,lcmr_screen_passed,
                 included_generation_mwh,calculated_co2_t,
                 illustrative_om_tco2_per_mwh,content_sha256,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,project,ctx.organization(),ctx.environment(),code,workbookId,
                source.reviewVersion(),HexFormat.of().parseHex(source.sha()),
                latest+1,preview.dataYear(),preview.approach(),
                meta.inventoryRef(),HexFormat.of().parseHex(meta.inventorySha()),
                meta.fiveYearRef(),HexFormat.of().parseHex(meta.fiveYearSha()),
                meta.fuelNote(),meta.boundaryNote(),meta.importsNote(),
                new BigDecimal(preview.selectedShare()),preview.fiveYearLcmrScreenPassed(),
                new BigDecimal(preview.includedGenerationMwh()),
                new BigDecimal(preview.totalEmissionsTco2())
                    .setScale(12,RoundingMode.HALF_EVEN),
                new BigDecimal(preview.illustrativeOmTco2PerMwh()),
                HexFormat.of().parseHex(sha),ctx.actor().id());
            for(var year:submitted.calculation().shares())update(c,"""
                INSERT INTO app_open_tool07_simple_om_lcmr
                  (project_id,run_id,data_year,total_grid_mwh,lcmr_mwh)
                VALUES(?,?,?,?,?)
                """,project,id,year.year(),year.totalGridMwh(),year.lcmrMwh());
            for(var plant:submitted.calculation().plants()){
                BigDecimal emissions=BigDecimal.ZERO;
                for(var fuel:plant.fuels())emissions=emissions.add(fuel.consumptionTonnes()
                    .multiply(fuel.ncvGjPerTonne())
                    .multiply(fuel.efTco2PerGj()));
                update(c,"""
                    INSERT INTO app_open_tool07_simple_om_plant
                      (project_id,run_id,plant_code,net_generation_mwh,total_co2_t)
                    VALUES(?,?,?,?,?)
                    """,project,id,plant.plantCode(),plant.netGenerationMwh(),
                    emissions.setScale(12,RoundingMode.HALF_EVEN));
                for(var fuel:plant.fuels())update(c,"""
                    INSERT INTO app_open_tool07_simple_om_fuel
                      (project_id,run_id,plant_code,fuel_code,consumption_tonnes,
                       ncv_gj_per_tonne,ef_tco2_per_gj,source_sha256_claimed)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,project,id,plant.plantCode(),fuel.fuelCode(),
                    fuel.consumptionTonnes(),fuel.ncvGjPerTonne(),fuel.efTco2PerGj(),
                    HexFormat.of().parseHex(fuel.sourceSha256()));
            }
            update(c,"""
                INSERT INTO app_open_tool07_simple_om_command
                  (actor_id,command_key,project_id,run_id,fingerprint)
                VALUES(?,?,?,?,?)
                """,ctx.actor().id(),command,project,id,fingerprint);
            return view(c,project,require(c,project,id));
        });
    }

    static String state(Connection c,String project,String period) throws SQLException {
        Stored latest=first(c,STORED_SQL+"""
            WHERE project_id=? AND period_code=?
            ORDER BY created_at DESC,id DESC LIMIT 1
            """,OpenTool07SimpleOmRuns::stored,project,period);
        return latest==null?"SIMPLE_OM_A1_MISSING":view(c,project,latest).state();
    }

    private static View view(Connection c,String project,Stored item) throws SQLException {
        Workbook source=workbook(c,project,item.workbook());
        UUID latestSource=first(c,"""
            SELECT id FROM app_open_vt0011_case2_workbook
            WHERE project_id=? AND period_code=?
            ORDER BY authored_at DESC,id DESC LIMIT 1
            """,r->r.getObject(1,UUID.class),project,item.period());
        boolean stale=source==null||!item.workbook().equals(latestSource)||
            !item.workbookSha().equals(source.sha())||
            item.workbookReview()!=source.reviewVersion()||
            !"CHECKED_INTERNAL_NOT_ADMITTED".equals(
                OpenVt0011Workbooks.state(c,project,item.period()));
        int latest=first(c,"""
            SELECT coalesce(max(version),0) FROM app_open_tool07_simple_om_run
            WHERE project_id=? AND workbook_id=?
            """,r->r.getInt(1),project,item.workbook());
        var years=query(c,"""
            SELECT data_year,total_grid_mwh,lcmr_mwh
            FROM app_open_tool07_simple_om_lcmr
            WHERE project_id=? AND run_id=?
            ORDER BY data_year
            """,r->new OpenTool07SimpleOmMath.ShareYear(r.getInt(1),
                r.getBigDecimal(2),r.getBigDecimal(3)),project,item.id());
        var plants=query(c,"""
            SELECT plant_code,net_generation_mwh
            FROM app_open_tool07_simple_om_plant
            WHERE project_id=? AND run_id=?
            ORDER BY plant_code
            """,r->new Object[]{r.getString(1),r.getBigDecimal(2)},project,item.id());
        var detailed=new ArrayList<OpenTool07SimpleOmMath.Plant>();
        for(Object[] plant:plants){
            var fuels=query(c,"""
                SELECT fuel_code,consumption_tonnes,ncv_gj_per_tonne,
                  ef_tco2_per_gj,encode(source_sha256_claimed,'hex')
                FROM app_open_tool07_simple_om_fuel
                WHERE project_id=? AND run_id=? AND plant_code=?
                ORDER BY fuel_code
                """,r->new OpenTool07SimpleOmMath.Fuel(r.getString(1),
                    r.getBigDecimal(2),r.getBigDecimal(3),r.getBigDecimal(4),
                    r.getString(5)),project,item.id(),plant[0]);
            detailed.add(new OpenTool07SimpleOmMath.Plant((String)plant[0],
                (BigDecimal)plant[1],List.copyOf(fuels)));
        }
        OpenTool07SimpleOmMath.Preview preview=
            OpenTool07SimpleOmMath.calculate(new OpenTool07SimpleOmMath.Input(
                item.year(),item.approach(),years,detailed));
        var gates=new ArrayList<>(preview.gates());
        if(stale)gates.add("WORKBOOK_OR_EFEL_STALE");
        if(latest!=item.version())gates.add("OM_RUN_VERSION_SUPERSEDED");
        if(source!=null&&new BigDecimal(preview.illustrativeOmTco2PerMwh())
            .subtract(source.declaredOm()).abs().compareTo(new BigDecimal("0.000000000001"))>0)
            gates.add("OM_RESULT_DIFFERS_FROM_PREVIOUSLY_DECLARED_FACTOR");
        String state=stale?"STALE_NOT_ADMITTED":
            latest!=item.version()?"SUPERSEDED_NOT_ADMITTED":
            preview.fiveYearLcmrScreenPassed()?
            "LCMR_SCREEN_PASSED_PRELIMINARY_NOT_ADMITTED":
            "LCMR_SCREEN_FAILED_PRELIMINARY_NOT_ADMITTED";
        return new View(item.id(),item.period(),item.workbook(),item.workbookReview(),
            item.workbookSha(),item.version(),item.inventoryRef(),item.inventorySha(),
            item.fiveYearRef(),item.fiveYearSha(),item.fuelNote(),item.boundaryNote(),
            item.importsNote(),item.sha(),state,stale,false,item.author(),item.at(),
            preview,List.copyOf(years),List.copyOf(detailed),List.copyOf(gates));
    }

    private static Workbook workbook(Connection c,String project,UUID id)throws SQLException {
        return first(c,"""
            SELECT w.id,w.period_code,w.om_method,w.om_vintage_mode,w.om_data_year,
              encode(w.content_sha256,'hex'),w.om_factor_tco2_per_mwh,
              coalesce((SELECT max(version) FROM app_open_vt0011_case2_review r
                WHERE r.project_id=w.project_id AND r.workbook_id=w.id),0)
            FROM app_open_vt0011_case2_workbook w
            WHERE w.project_id=? AND w.id=?
            """,r->new Workbook(r.getObject(1,UUID.class),r.getString(2),
                r.getString(3),r.getString(4),r.getInt(5),r.getString(6),
                r.getBigDecimal(7),r.getInt(8)),project,id);
    }

    private static Metadata metadata(Input x) {
        return new Metadata(clean(x.inventorySourceReference(),15,600,"inventorySourceReference"),
            sha(x.inventorySha256(),"inventorySha256"),
            clean(x.fiveYearSourceReference(),15,600,"fiveYearSourceReference"),
            sha(x.fiveYearSha256(),"fiveYearSha256"),
            clean(x.fuelSourceExplanation(),30,2000,"fuelSourceExplanation"),
            clean(x.systemBoundaryNote(),30,2000,"systemBoundaryNote"),
            clean(x.importsExplanation(),30,2000,"importsExplanation"));
    }
    private static String sha(String v,String field){
        if(v==null||!v.matches("[0-9a-fA-F]{64}"))
            throw invalid(field,"SHA-256 declarado exige 64 caracteres hexadecimais.");
        return v.toLowerCase();
    }
    private static String clean(String v,int min,int max,String field){
        if(v==null||v.strip().length()<min||v.strip().length()>max||
            v.codePoints().anyMatch(Character::isISOControl))
            throw invalid(field,"Referência ou justificativa com tamanho/formato inválido.");
        return v.strip();
    }
    private static void append(StringBuilder b,String value) {
        b.append('|').append(value.length()).append('#').append(value);
    }
    private static String signature(String project,Workbook workbook,Input in,Metadata m){
        var b=new StringBuilder("CF-OPEN-TOOL07-SIMPLE-OM-A1-V1");
        for(String v:List.of(project,workbook.id().toString(),workbook.sha(),
            Integer.toString(workbook.reviewVersion()),m.inventoryRef(),m.inventorySha(),
            m.fiveYearRef(),m.fiveYearSha(),m.fuelNote(),m.boundaryNote(),
            m.importsNote(),Integer.toString(in.calculation().dataYear()),
            in.calculation().shareApproach()))append(b,v);
        var years=new ArrayList<>(in.calculation().shares());
        years.sort(Comparator.comparingInt(OpenTool07SimpleOmMath.ShareYear::year));
        for(var y:years)
            for(String v:List.of(Integer.toString(y.year()),
                y.totalGridMwh().stripTrailingZeros().toPlainString(),
                y.lcmrMwh().stripTrailingZeros().toPlainString()))append(b,v);
        var plants=new ArrayList<>(in.calculation().plants());
        plants.sort(Comparator.comparing(OpenTool07SimpleOmMath.Plant::plantCode));
        for(var p:plants) {
            append(b,p.plantCode());
            append(b,p.netGenerationMwh().stripTrailingZeros().toPlainString());
            var fuels=new ArrayList<>(p.fuels());
            fuels.sort(Comparator.comparing(OpenTool07SimpleOmMath.Fuel::fuelCode));
            for(var f:fuels)
                for(String v:List.of(f.fuelCode(),
                    f.consumptionTonnes().stripTrailingZeros().toPlainString(),
                    f.ncvGjPerTonne().stripTrailingZeros().toPlainString(),
                    f.efTco2PerGj().stripTrailingZeros().toPlainString(),
                    f.sourceSha256().toLowerCase()))append(b,v);
        }
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(b.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static final String STORED_SQL="""
        SELECT id,period_code,workbook_id,workbook_review_version,
          encode(workbook_source_sha256,'hex') AS workbook_sha,
          version,inventory_source_reference,
          encode(inventory_sha256_claimed,'hex') AS inventory_sha,
          five_year_source_reference,
          encode(five_year_sha256_claimed,'hex') AS five_year_sha,
          fuel_source_explanation,system_boundary_note,imports_explanation,
          encode(content_sha256,'hex') AS sha,created_by,created_at,
          share_approach,data_year
        FROM app_open_tool07_simple_om_run
        """;
    private static Stored stored(java.sql.ResultSet r)throws SQLException {
        return new Stored(r.getObject("id",UUID.class),r.getString("period_code"),
            r.getObject("workbook_id",UUID.class),r.getInt("workbook_review_version"),
            r.getString("workbook_sha"),r.getInt("version"),
            r.getString("inventory_source_reference"),r.getString("inventory_sha"),
            r.getString("five_year_source_reference"),r.getString("five_year_sha"),
            r.getString("fuel_source_explanation"),r.getString("system_boundary_note"),
            r.getString("imports_explanation"),r.getString("sha"),
            r.getObject("created_by",UUID.class),
            r.getObject("created_at",OffsetDateTime.class),
            r.getString("share_approach"),r.getInt("data_year"));
    }
    private static Stored require(Connection c,String project,UUID id)throws SQLException {
        Stored item=first(c,STORED_SQL+"""
            WHERE project_id=? AND id=?
            """,OpenTool07SimpleOmRuns::stored,project,id);
        if(item==null)throw unavailable();
        return item;
    }
    private static PilotProblem unavailable(){
        return new PilotProblem(404,"SIMPLE_OM_A1_NOT_VISIBLE","run",RULE,
            "Memória OM não encontrada.");
    }
    private static PilotProblem conflict(String detail){
        return new PilotProblem(409,"SIMPLE_OM_A1_CONFLICT","run",RULE,detail);
    }
    private static PilotProblem invalid(String field,String detail){
        return PilotProblem.invalid("SIMPLE_OM_A1_INVALID",field,RULE,detail);
    }
}
