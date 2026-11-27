package br.carbonflow.pilot;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * BL-EX-02: what the project itself sees about its EX ANTE fleet reference (BL-EX-01) on baseline step 2.
 * Metadata only: no stock, rating or licensed value is copied. The reference is preparatory and NEVER an E8 input.
 * An empty result is explained by gaps (with owner and next action), never by zeros or percentages.
 */
@ApplicationScoped
class OpenBaselineFleetContext {
    static final String ACTION="VIEW_OPEN_BASELINE_FLEET_CONTEXT";
    record Gap(String code,String owner,String nextAction) {}
    record Context(String projectId,String state,LocalDate creditStart,OpenBaselineFleetSelections.View current,
                   List<OpenBaselineFleetSelections.View> history,List<Gap> gaps,boolean usableForE8,List<String> notes) {}

    private final DynamicProjectScope scope;
    OpenBaselineFleetContext(DynamicProjectScope scope){this.scope=scope;}

    Context read(String token,String project) {
        return scope.read(token,project,ACTION,(c,ctx)->build(c,ctx.id()));
    }

    private static Context build(java.sql.Connection c,String project)throws SQLException{
        var history=OpenBaselineFleetSelections.history(c,project);
        var calendar=OpenE8Timing.calendar(c,project,1);
        var gaps=new ArrayList<Gap>();
        if(calendar.gate()!=null)
            gaps.add(new Gap("CREDIT_START_UNRESOLVED","Equipe do projeto","Resolver o início de creditação do projeto (calendário)."));
        var current=history.isEmpty()?null:history.get(0);
        String state;
        if(current==null){
            state="NO_EX_ANTE_REFERENCE";
            gaps.add(new Gap("NO_EX_ANTE_REFERENCE_CONFIRMED","Gestor de fontes da plataforma",
                "Aguardar a seleção da frota de referência (EVR e ratings publicados e licenciados) para este projeto."));
        }else if(current.stale()){
            state="STALE_NOT_ADMITTED";
            for(String gate:current.gates())
                if(!gate.startsWith("EVR_AND_RATINGS") && !gate.startsWith("EX_ANTE_REFERENCE"))
                    gaps.add(new Gap(gate,"Gestor de fontes da plataforma","Reconfirmar a referência de frota ex ante."));
        }else state="PREPARATORY_NOT_ADMITTED";
        return new Context(project,state,calendar.creditStart(),current,history,List.copyOf(gaps),false,List.of(
            "A referência ex ante é preparatória: não é admitida e não alimenta o E8.",
            "Rede aberta: veículos usuários não são nominados; a frota de referência vem da composição nacional por modelo.",
            "Sem seleção não há frota igual a zero: o dado apenas não foi vinculado ainda."));
    }
}
