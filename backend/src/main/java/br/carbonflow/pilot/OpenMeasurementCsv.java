package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A strict, deterministic preview of project meter CSV data. All rows remain
 * CANDIDATE or QUARANTINED; neither is an admitted ED/EC observation.
 * The original bytes are kept separately as AES-GCM project evidence.
 */
final class OpenMeasurementCsv {
    static final String VERSION = "CF-OPEN-009-CSV-v1";
    static final List<String> HEADER = List.of(
        "meterCode","sequence","kind","start","endExclusive","zone","quantity","unit");

    record MeterScope(String code,int version,String kind,String chargerCode,
                      int chargerVersion,int currentChargerVersion,String chargerKind,
                      String grid,String topology,String timeZone,
                      OffsetDateTime calibrationFrom,OffsetDateTime calibrationUntil,
                      OffsetDateTime configuredFrom,OffsetDateTime configuredUntil) {}

    record Candidate(int line,String sequence,String meterCode,Integer meterVersion,
                     String kind,OffsetDateTime start,OffsetDateTime end,String zone,
                     BigDecimal kwh,byte[] sourceLineSha256,String state,List<String> reasons) {}

    record Preview(List<Candidate> rows,boolean quarantined) {}

    private OpenMeasurementCsv() {}

    static Preview parse(byte[] source,Map<String,MeterScope> meters,Set<String> previous) {
        if(source==null || source.length==0 || source.length>2_000_000)
            throw invalid("file","O CSV deve conter entre 1 e 2.000.000 bytes.");
        String content;
        try {
            content=StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source)).toString();
        } catch(CharacterCodingException e) {
            throw invalid("file","CSV exige codificação UTF-8 válida.");
        }
        if(content.indexOf('\0')>=0)throw invalid("file","O CSV contém bytes nulos.");
        List<String> lines=content.lines().toList();
        if(lines.size()<2 || lines.size()>2001)throw invalid("file","Informe de 1 a 2.000 linhas de medição.");
        List<String> header;
        try {
            header=EnergyCsv.fields(lines.getFirst().replaceFirst("^\uFEFF",""));
        } catch(IllegalArgumentException e) {
            throw invalid("file","Cabeçalho CSV inválido.");
        }
        if(!HEADER.equals(header))throw invalid("file",
            "Cabeçalho exigido: "+String.join(",",HEADER));
        var result=new ArrayList<Candidate>();
        var seen=new HashSet<String>(previous);
        boolean quarantine=false;
        for(int i=1;i<lines.size();i++) {
            String raw=lines.get(i);
            List<String> reasons=new ArrayList<>();
            List<String> fields;
            try {
                fields=EnergyCsv.fields(raw);
            } catch(IllegalArgumentException e) {
                fields=List.of();
            }
            if(fields.size()!=HEADER.size()) {
                reasons.add("CSV_COLUMNS_INVALID");
                fields=List.of("","","","","","","","");
            }
            String code=fields.get(0), seq=fields.get(1), kind=fields.get(2), zone=fields.get(5);
            if(!safe(code))reasons.add("METER_CODE_INVALID");
            if(!safe(seq))reasons.add("SEQUENCE_INVALID");
            if(!"ED".equals(kind)&&!"EC".equals(kind))reasons.add("KIND_INVALID");

            OffsetDateTime start=parseDate(fields.get(3));
            OffsetDateTime end=parseDate(fields.get(4));
            if(start==null || end==null || !start.toInstant().isBefore(end.toInstant())) {
                reasons.add("TIME_INTERVAL_INVALID");
            } else if(Duration.between(start.toInstant(),end.toInstant()).compareTo(Duration.ofHours(1))>0) {
                // Hourly electricity EF may apply. Until the series is admitted,
                // reject larger gaps instead of silently splitting/allocating kWh.
                reasons.add("NON_HOURLY_INTERVAL");
            }
            if(!Set.of("UTC","America/Sao_Paulo").contains(zone) ||
                start==null || end==null || !offsetValid(start,zone) || !offsetValid(end,zone)) {
                reasons.add("TIME_ZONE_INVALID");
            }
            BigDecimal quantity=parseNumber(fields.get(6));
            BigDecimal kwh=null;
            if(quantity==null) {
                reasons.add(fields.get(6).isBlank()?"ENERGY_MISSING":"ENERGY_DECIMAL_INVALID");
            } else if(quantity.signum()<0) {
                reasons.add("NEGATIVE_ENERGY");
            } else if(quantity.signum()==0) {
                reasons.add("ZERO_REQUIRES_EVIDENCE");
            }
            String unit=fields.get(7);
            if(!Set.of("Wh","kWh","MWh").contains(unit))reasons.add("ENERGY_UNIT_INVALID");
            if(quantity!=null && quantity.signum()>0 && Set.of("Wh","kWh","MWh").contains(unit)) {
                kwh=switch(unit) {
                    case "Wh" -> quantity.movePointLeft(3);
                    case "MWh" -> quantity.movePointRight(3);
                    default -> quantity;
                };
                if(kwh.scale()>12 || kwh.precision()-kwh.scale()>18) {
                    reasons.add("ENERGY_PRECISION_EXCEEDED");
                    kwh=null;
                }
            }
            MeterScope meter=meters.get(code);
            Integer version=null;
            if(meter==null) {
                reasons.add("METER_NOT_REGISTERED");
            } else {
                version=meter.version();
                if(!kind.equals(meter.kind()))reasons.add("METER_KIND_MISMATCH");
                if(meter.chargerVersion()!=meter.currentChargerVersion())
                    reasons.add("CHARGER_CONFIGURATION_STALE");
                if(!"DCFC".equals(meter.chargerKind()) ||
                    !"SIN".equals(meter.grid()) || !"GRID_ONLY".equals(meter.topology()))
                    reasons.add("UNSUPPORTED_TOPOLOGY");
                if(!"UTC".equals(zone) && !zone.equals(meter.timeZone()))
                    reasons.add("METER_ZONE_MISMATCH");
                if(start!=null && end!=null) {
                    if(!covers(meter.calibrationFrom(),meter.calibrationUntil(),start,end))
                        reasons.add("CALIBRATION_NOT_COVERING");
                    if(!covers(meter.configuredFrom(),meter.configuredUntil(),start,end))
                        reasons.add("METER_CONFIGURATION_NOT_COVERING");
                }
            }
            if(start!=null && end!=null && safe(seq) && safe(code)) {
                String identity=String.join("|",code,seq,start.toInstant().toString(),
                    end.toInstant().toString());
                if(!seen.add(identity))reasons.add("DUPLICATE_SOURCE_INTERVAL");
            }
            boolean valid=reasons.isEmpty();
            quarantine |= !valid;
            result.add(new Candidate(i+1,safe(seq)?seq:null,safe(code)?code:null,
                version,Set.of("ED","EC").contains(kind)?kind:null,start,end,
                Set.of("UTC","America/Sao_Paulo").contains(zone)?zone:null,kwh,
                AccessSecrets.digest(raw),valid?"CANDIDATE":"QUARANTINED",
                List.copyOf(reasons)));
        }
        return new Preview(List.copyOf(result),quarantine);
    }

    static String fingerprint(Candidate c) {
        if(c.meterCode()==null || c.start()==null || c.end()==null ||
            c.sequence()==null)return null;
        return String.join("|",c.meterCode(),c.sequence(),
            c.start().toInstant().toString(),c.end().toInstant().toString());
    }

    private static boolean covers(OffsetDateTime from,OffsetDateTime until,
                                   OffsetDateTime start,OffsetDateTime end) {
        return from!=null && until!=null && !from.toInstant().isAfter(start.toInstant())
            && !until.toInstant().isBefore(end.toInstant());
    }
    private static boolean offsetValid(OffsetDateTime date,String zone) {
        if(!Set.of("UTC","America/Sao_Paulo").contains(zone))return false;
        return date.getOffset().equals(ZoneOffset.UTC) ||
            date.getOffset().equals(ZoneId.of(zone).getRules().getOffset(date.toInstant()));
    }
    private static boolean safe(String code) {
        return code!=null && code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
            && !code.toUpperCase(java.util.Locale.ROOT).startsWith("SIM-")
            && !code.toUpperCase(java.util.Locale.ROOT).startsWith("PIL-SYN");
    }
    private static OffsetDateTime parseDate(String value) {
        try {return OffsetDateTime.parse(value);}catch(RuntimeException e){return null;}
    }
    private static BigDecimal parseNumber(String value) {
        if(value==null || !value.matches("-?(0|[1-9][0-9]{0,17})(\\.[0-9]{1,12})?"))
            return null;
        try{return new BigDecimal(value);}catch(NumberFormatException e){return null;}
    }
    private static PilotProblem invalid(String field,String message) {
        return PilotProblem.invalid("CSV_MEDICAO_INVALIDO",field,VERSION,message);
    }
}
