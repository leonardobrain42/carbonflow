package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Conservative hourly ED/EC reconciliation. This is an internal QA preview,
 * not normative project admission and NEVER an E8 calculation input.
 */
final class OpenPeriodQa {
    record Meter(String code,int version,String charger,int chargerVersion,int currentChargerVersion,String kind,String chargerKind,
                 String grid,String topology,boolean calibrationEvidence,
                 Instant calibrationFrom,Instant calibrationUntil,
                 Instant configuredFrom,Instant configuredUntil) {}

    record Reading(UUID batch,int line,String meterCode,int meterVersion,String kind,
                   Instant start,Instant end,BigDecimal kwh,String rowState,
                   String batchState,String reviewOutcome,int reviewVersion,String sourceHash,String lineHash) {}

    record Gate(String code,String detail,String charger,String meter,Instant hour) {}
    record Ref(UUID batch,int line,int reviewVersion,String meter,int version,Instant start,Instant end,
               String sourceHash,String lineHash) {}
    record Result(String state,boolean readyForInternalSnapshot,boolean canRunE8,
                  String edKwh,String ecKwh,List<Gate> gates,List<Ref> accepted) {}

    private OpenPeriodQa() {}

    static Result evaluate(OffsetDateTime from,OffsetDateTime until,
                           List<String> chargers,List<Meter> meters,List<Reading> rows) {
        if(from==null || until==null)throw invalid("period","Informe início e fim em ISO 8601.");
        Instant start=from.toInstant(),end=until.toInstant();
        long minutes=Duration.between(start,end).toMinutes();
        if(minutes<60 || minutes>744*60L || minutes%60!=0 ||
           start.getEpochSecond()%3600!=0 || end.getEpochSecond()%3600!=0) {
            throw invalid("period","Use um intervalo UTC de horas completas, entre 1 e 744 horas.");
        }
        List<Gate> gates=new ArrayList<>();
        Set<String> duplicateGate=new HashSet<>();
        List<Ref> accepted=new ArrayList<>();
        BigDecimal ed=BigDecimal.ZERO,ec=BigDecimal.ZERO;
        Map<String,Meter> byCode=new HashMap<>();
        Map<String,List<Meter>> byCharger=new HashMap<>();
        for(Meter m:meters) {
            byCode.put(m.code(),m);
            byCharger.computeIfAbsent(m.charger(),k->new ArrayList<>()).add(m);
            if(m.chargerVersion()!=m.currentChargerVersion())
                add(gates,duplicateGate,"CHARGER_VERSION_STALE",
                    "Configuração da leitura refere-se a revisão antiga do carregador.",
                    m.charger(),m.code(),null);
            if(!"DCFC".equals(m.chargerKind()) || !"SIN".equals(m.grid())
                || !"GRID_ONLY".equals(m.topology()))
                add(gates,duplicateGate,"OUT_OF_SCOPE_TOPOLOGY",
                    "Topologia sem DCFC/SIN simples exige outro ramo.",
                    m.charger(),m.code(),null);
        }
        for(String code:chargers)byCharger.computeIfAbsent(code,k->new ArrayList<>());
        if(byCharger.isEmpty())add(gates,duplicateGate,"NO_CHARGERS",
            "Cadastre carregadores com medições distintas de ED e EC.",null,null,null);

        Map<String,List<Reading>> slotRows=new HashMap<>();
        for(Reading row:rows) {
            if(row.start()==null || row.end()==null ||
               !row.start().isBefore(end) || !row.end().isAfter(start))continue;
            if("REJECTED".equals(row.reviewOutcome())) continue;
            Meter meter=byCode.get(row.meterCode());
            if(meter==null || meter.version()!=row.meterVersion()) {
                add(gates,duplicateGate,"METER_VERSION_STALE",
                    "Leitura referenciada por versão antiga ou medidor removido.",
                    null,row.meterCode(),row.start());
                continue;
            }
            if(!"CANDIDATE".equals(row.rowState()) ||
               !"CANDIDATE".equals(row.batchState())) {
                add(gates,duplicateGate,"QUARANTINE_UNRESOLVED",
                    "Lote ou linha em quarentena; reprove a linha e envie correção em novo lote.",
                    meter.charger(),meter.code(),row.start());
                continue;
            }
            if(!"ACCEPTED_FOR_QA".equals(row.reviewOutcome())) {
                add(gates,duplicateGate,"REVIEW_MISSING",
                    "Parecer independente pendente para a leitura.",
                    meter.charger(),meter.code(),row.start());
                continue;
            }
            if(!row.start().equals(start) && row.start().isBefore(start) ||
               !row.end().equals(end) && row.end().isAfter(end) ||
               !Duration.between(row.start(),row.end()).equals(Duration.ofHours(1)) ||
               row.start().getEpochSecond()%3600!=0) {
                add(gates,duplicateGate,"NON_HOURLY_READING",
                    "Intervalo não coincide com uma hora integral do período selecionado.",
                    meter.charger(),meter.code(),row.start());
                continue;
            }
            if(row.kwh()==null || row.kwh().signum()<=0 ||
               !row.kind().equals(meter.kind())) {
                add(gates,duplicateGate,"READING_INVALID",
                    "Quantidade ou variável não corresponde à configuração.",
                    meter.charger(),meter.code(),row.start());
                continue;
            }
            if(!"DCFC".equals(meter.chargerKind()) || !"SIN".equals(meter.grid())
                || !"GRID_ONLY".equals(meter.topology())) {
                add(gates,duplicateGate,"OUT_OF_SCOPE_TOPOLOGY",
                    "Somente DCFC/SIN sem geração, bateria ou V2G no recorte inicial.",
                    meter.charger(),meter.code(),row.start());
                continue;
            }
            if(!meter.calibrationEvidence() ||
                !covers(meter.calibrationFrom(),meter.calibrationUntil(),row.start(),row.end()) ||
                !covers(meter.configuredFrom(),meter.configuredUntil(),row.start(),row.end())) {
                add(gates,duplicateGate,"METER_CALIBRATION_STALE",
                    "Calibração documentada ou configuração não cobre esta hora.",
                    meter.charger(),meter.code(),row.start());
                continue;
            }
            String k=meter.charger()+"|"+meter.kind()+"|"+row.start();
            slotRows.computeIfAbsent(k,x->new ArrayList<>()).add(row);
        }
        for(var entry:byCharger.entrySet()) {
            String charger=entry.getKey();
            for(String kind:List.of("ED","EC")) {
                long configs=entry.getValue().stream().filter(m->kind.equals(m.kind())).count();
                if(configs!=1)add(gates,duplicateGate,"METER_ROLE_CONFLICT",
                    "Exija exatamente um medidor "+kind+" por carregador e por versão.",
                    charger,null,null);
            }
            for(Instant hour=start;hour.isBefore(end);hour=hour.plus(Duration.ofHours(1))) {
                Reading edRow=single(slotRows.get(charger+"|ED|"+hour),gates,duplicateGate,charger,"ED",hour);
                Reading ecRow=single(slotRows.get(charger+"|EC|"+hour),gates,duplicateGate,charger,"EC",hour);
                if(edRow==null || ecRow==null)continue;
                if(ecRow.kwh().compareTo(edRow.kwh())<0) {
                    add(gates,duplicateGate,"EC_BELOW_ED",
                        "EC menor que ED nesta hora: verificar fronteira e perdas antes de fechar.",
                        charger,null,hour);
                    continue;
                }
                ed=ed.add(edRow.kwh());
                ec=ec.add(ecRow.kwh());
                accepted.add(ref(edRow));
                accepted.add(ref(ecRow));
            }
        }
        if(gates.isEmpty() && accepted.isEmpty())add(gates,duplicateGate,"PENDING_MEASUREMENT",
            "Nenhuma medição revisada foi encontrada.",null,null,null);
        boolean ready=gates.isEmpty() && !accepted.isEmpty();
        accepted.sort(Comparator.comparing(Ref::meter)
            .thenComparing(Ref::start).thenComparing(Ref::batch).thenComparingInt(Ref::line));
        return new Result(ready?"READY_FOR_INTERNAL_QA_SNAPSHOT":"BLOCKED_QA",
            ready,false,ready?ed.toPlainString():null,ready?ec.toPlainString():null,
            List.copyOf(gates),ready?List.copyOf(accepted):List.of());
    }

    private static Ref ref(Reading r) {
        return new Ref(r.batch(),r.line(),r.reviewVersion(),r.meterCode(),r.meterVersion(),
            r.start(),r.end(),r.sourceHash(),r.lineHash());
    }
    private static Reading single(List<Reading> input,List<Gate> gates,Set<String> seen,
                                  String charger,String kind,Instant hour) {
        if(input==null || input.isEmpty()) {
            add(gates,seen,"HOUR_GAP_"+kind,
                "Sem leitura "+kind+" revisada nesta hora.",charger,null,hour);
            return null;
        }
        if(input.size()!=1) {
            add(gates,seen,"HOUR_OVERLAP_"+kind,
                "Leituras duplicadas ou superpostas de "+kind+" nesta hora.",charger,null,hour);
            return null;
        }
        return input.getFirst();
    }
    private static boolean covers(Instant a,Instant b,Instant from,Instant until) {
        return a!=null && b!=null && !a.isAfter(from) && !b.isBefore(until);
    }
    private static void add(List<Gate> result,Set<String> seen,
                            String code,String detail,String charger,String meter,Instant hour) {
        String key=code+"|"+charger+"|"+meter+"|"+hour;
        if(seen.add(key))result.add(new Gate(code,detail,charger,meter,hour));
    }
    private static PilotProblem invalid(String field,String message) {
        return PilotProblem.invalid("PERIODO_QA_INVALIDO",field,"CF-OPEN-009",message);
    }
}
