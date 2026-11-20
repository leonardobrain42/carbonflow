package br.carbonflow.pilot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Parses one bounded, explicit dimensional schema without guessing units, years,
 * emission factors or filling gaps. A parsed candidate is NEVER technically admitted.
 */
final class TabularCandidateParser {
    static final String VERSION = "TABULAR-1";
    static final int MAX_ROWS = 2000;
    static final String[] REQUIRED = {
        "record_key","period_year","metric","value","unit","geography"
    };
    static final Set<String> UNITS = Set.of("vehicles","units","kWh","MWh","km","MJ/km",
        "kWh/km","kWh/100km","kWh/100mi","km/L","mi/USgal","tCO2/MWh","tCO2/kWh",
        "kgCO2e/km","tCO2e","tCO2e/L","tCO2e/USgal","kgCO2e/L");

    record Candidate(int row, String recordKey, int year, String metric, BigDecimal value,
                     String unit, String geography, String locator) {}
    record Parsed(List<Candidate> candidates, List<String> issues, int readRows) {
        boolean valid() {return issues.isEmpty() && !candidates.isEmpty();}
    }

    private TabularCandidateParser() {}

    static Parsed parse(byte[] data, String format, Integer referenceYear) {
        if (data == null || data.length < 16) return invalid("Arquivo vazio ou incompleto.");
        try {
            return switch(format) {
                case "CSV" -> csv(data,referenceYear);
                case "XLSX" -> xlsx(data,referenceYear);
                default -> invalid("Formato não suportado.");
            };
        } catch (Exception ex) {
            return invalid("Arquivo não reconhecido como tabela segura deste formato.");
        }
    }

    private static Parsed csv(byte[] data,Integer referenceYear) throws IOException {
        // Strict UTF-8: silently replacing malformed bytes would destroy evidence.
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data));
        } catch (CharacterCodingException error) {
            return invalid("CSV não possui codificação UTF-8 válida.");
        }
        if (new String(data,StandardCharsets.UTF_8).indexOf('\0')>=0) return invalid("CSV contém bytes NUL.");
        var rows = new ArrayList<Map<String,String>>();
        var locators = new ArrayList<String>();
        try(var reader=new InputStreamReader(new ByteArrayInputStream(data),StandardCharsets.UTF_8);
            var parser=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            var header = new LinkedHashMap<String,Integer>();
            for (var entry:parser.getHeaderMap().entrySet()) {
                String key=entry.getKey().replace("\uFEFF","").trim().toLowerCase(Locale.ROOT);
                if(header.putIfAbsent(key,entry.getValue())!=null) return invalid("Cabeçalho CSV duplicado: "+key);
            }
            if (!header.keySet().containsAll(List.of(REQUIRED))) return invalid(
                "Colunas obrigatórias ausentes. Esperado: "+String.join(", ",REQUIRED));
            for(var record:parser) {
                if(rows.size()>=MAX_ROWS) return invalid("Arquivo excede 2.000 linhas de dados.");
                var values=new HashMap<String,String>();
                for(var field:REQUIRED) {
                    int index=header.get(field);
                    values.put(field,index<record.size()?record.get(index):"");
                }
                rows.add(values);
                locators.add("CSV registro "+record.getRecordNumber());
            }
        }
        return validate(rows,locators,referenceYear);
    }

    private static Parsed xlsx(byte[] data,Integer referenceYear) throws IOException {
        if (data.length<4 || data[0]!='P' || data[1]!='K' || data[2]!=3 || data[3]!=4) {
            return invalid("XLSX não é um ZIP OOXML válido.");
        }
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(data))) {
            if(workbook.getNumberOfSheets()!=1) return invalid("XLSX deve conter uma única aba nesta fase.");
            Sheet sheet=workbook.getSheetAt(0);
            Row headerRow=sheet.getRow(0);
            if(headerRow==null) return invalid("XLSX sem cabeçalho na primeira linha.");
            if(sheet.getLastRowNum()>MAX_ROWS) return invalid("XLSX excede 2.000 linhas de dados.");
            var formatter=new DataFormatter(Locale.ROOT);
            var headers=new HashMap<String,Integer>();
            for(Cell cell:headerRow) {
                if(cell.getCellType()==CellType.FORMULA) return invalid("Fórmula proibida no cabeçalho.");
                String heading=formatter.formatCellValue(cell).replace("\uFEFF","").trim().toLowerCase(Locale.ROOT);
                if(headers.putIfAbsent(heading,cell.getColumnIndex())!=null) {
                    return invalid("Cabeçalho duplicado: "+heading);
                }
            }
            if(!headers.keySet().containsAll(List.of(REQUIRED))) return invalid(
                "Colunas obrigatórias ausentes. Esperado: "+String.join(", ",REQUIRED));
            var rows=new ArrayList<Map<String,String>>();
            var locators=new ArrayList<String>();
            for(int rowIndex=1;rowIndex<=sheet.getLastRowNum();rowIndex++) {
                Row row=sheet.getRow(rowIndex);
                if(row==null) return invalid("Linha "+(rowIndex+1)+" ausente na aba.");
                var fields=new HashMap<String,String>();
                for(var col:REQUIRED) {
                    Cell cell=row.getCell(headers.get(col),Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if(cell!=null && cell.getCellType()==CellType.FORMULA) {
                        return invalid("Fórmula não permitida na linha "+(rowIndex+1)+".");
                    }
                    fields.put(col,cell==null?"":formatter.formatCellValue(cell));
                }
                rows.add(fields);
                locators.add("XLSX "+sheet.getSheetName()+"!"+(rowIndex+1));
            }
            return validate(rows,locators,referenceYear);
        }
    }

    private static Parsed validate(List<Map<String,String>> rows,List<String> locators,Integer referenceYear) {
        var candidates=new ArrayList<Candidate>();
        var issues=new ArrayList<String>();
        var keys=new HashSet<String>();
        if(rows.isEmpty()) issues.add("Nenhuma linha de dados foi encontrada.");
        for(int i=0;i<rows.size();i++) {
            var r=rows.get(i);
            String key=r.get("record_key").strip(),metric=r.get("metric").strip();
            String unit=r.get("unit").strip(), geography=r.get("geography").strip().toUpperCase(Locale.ROOT);
            String yearStr=r.get("period_year").strip(),valueStr=r.get("value").strip();
            String locator=locators.get(i);
            if(!key.matches("[A-Za-z0-9._-]{1,100}") ||
                !metric.matches("[A-Za-z0-9._-]{1,80}") ||
                !geography.matches("[A-Z][A-Z0-9._-]{1,63}") ||
                !UNITS.contains(unit) || !yearStr.matches("(19|20)[0-9]{2}") ||
                !valueStr.matches("[+-]?[0-9]{1,18}(?:\\.[0-9]{1,12})?")) {
                if(issues.size()<25) issues.add(locator+": valor, unidade, período, geografia ou chave inválidos.");
                continue;
            }
            int year=Integer.parseInt(yearStr);
            if(referenceYear!=null && referenceYear!=year) {
                if(issues.size()<25) issues.add(locator+": ano difere do ano de referência declarado na fonte.");
                continue;
            }
            String uniqueness=key+"|"+metric+"|"+year+"|"+geography+"|"+unit;
            if(!keys.add(uniqueness)) {
                if(issues.size()<25) issues.add(locator+": chave dimensional duplicada.");
                continue;
            }
            BigDecimal number=new BigDecimal(valueStr);
            if(number.precision()>30 || number.scale()>12) {
                if(issues.size()<25) issues.add(locator+": precisão numérica não permitida.");
                continue;
            }
            candidates.add(new Candidate(i+2,key,year,metric,number,unit,geography,locator));
        }
        if(!issues.isEmpty()) candidates.clear(); // Fail closed: no partial dataset silently imported.
        return new Parsed(List.copyOf(candidates),List.copyOf(issues),rows.size());
    }

    private static Parsed invalid(String message) {return new Parsed(List.of(),List.of(message),0);}
}
