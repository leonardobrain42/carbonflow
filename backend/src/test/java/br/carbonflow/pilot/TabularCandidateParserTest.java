package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class TabularCandidateParserTest {
    private static final String HEADER="record_key,period_year,metric,value,unit,geography\r\n";

    @Test
    void validCsvPreservesDecimalsYearUnitGeographyAndLocators() {
        String csv=HEADER+"abc,2024,energy,0.001200000001,kWh,BR\r\n"+
            "def,2024,energy,1.25,kWh,BR\r\n";
        var parsed=TabularCandidateParser.parse(csv.getBytes(StandardCharsets.UTF_8),"CSV",2024);
        assertTrue(parsed.valid(),parsed.issues().toString());
        assertEquals(2,parsed.candidates().size());
        assertEquals("0.001200000001",parsed.candidates().getFirst().value().toPlainString());
        assertEquals(2024,parsed.candidates().getFirst().year());
        assertEquals("BR",parsed.candidates().getFirst().geography());
        assertEquals("kWh",parsed.candidates().getFirst().unit());
        assertTrue(parsed.candidates().getFirst().locator().contains("CSV"));
    }

    @Test
    void rejectsMissingSchemaDuplicatesMixedVintageAndUnknownUnitsWithoutPartialCandidate() {
        var absent=TabularCandidateParser.parse(
          "record_key,period_year,value\na,2024,5".getBytes(StandardCharsets.UTF_8),"CSV",2024);
        assertFalse(absent.valid());
        assertTrue(absent.issues().getFirst().contains("Colunas obrigatórias"));
        var duplicated=TabularCandidateParser.parse((HEADER+
          "a,2024,fleet,5,vehicles,BR\r\na,2024,fleet,6,vehicles,BR\r\n")
          .getBytes(StandardCharsets.UTF_8),"CSV",2024);
        assertFalse(duplicated.valid());
        assertEquals(0,duplicated.candidates().size());
        assertTrue(duplicated.issues().getFirst().contains("duplicada"));
        var bad=TabularCandidateParser.parse((HEADER+
          "a,2025,fleet,5,vehicles,BR\r\nb,2024,fleet,7,not_a_unit,BR\r\n")
          .getBytes(StandardCharsets.UTF_8),"CSV",2024);
        assertFalse(bad.valid());
        assertEquals(0,bad.candidates().size());
        assertEquals(2,bad.issues().size());
    }

    @Test
    void rejectsMalformedUtf8AndBinaryPretendingToBeCsv() {
        byte[] bad=new byte[]{(byte)0xc3,(byte)0x28,0x41,0x42,0x43,0x44,0x45,0x46,
                               0x41,0x42,0x43,0x44,0x45,0x46,0x41,0x42};
        assertFalse(TabularCandidateParser.parse(bad,"CSV",2024).valid());
    }

    @Test
    void xlsxParsesSingleSheetAndRejectsFormulaCells() throws Exception {
        byte[] valid=workbook(false);
        var parsed=TabularCandidateParser.parse(valid,"XLSX",2024);
        assertTrue(parsed.valid(),parsed.issues().toString());
        assertEquals(1,parsed.candidates().size());
        assertEquals("XLSX Fonte!2",parsed.candidates().getFirst().locator());
        var formulas=TabularCandidateParser.parse(workbook(true),"XLSX",2024);
        assertFalse(formulas.valid());
        assertTrue(formulas.issues().getFirst().contains("Fórmula"));
    }

    private static byte[] workbook(boolean formula) throws Exception {
        try(var excel=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=excel.createSheet("Fonte");
            var headers=sheet.createRow(0);
            String[] labels=TabularCandidateParser.REQUIRED;
            for(int i=0;i<labels.length;i++)headers.createCell(i).setCellValue(labels[i]);
            var row=sheet.createRow(1);
            row.createCell(0).setCellValue("a");
            row.createCell(1).setCellValue(2024);
            row.createCell(2).setCellValue("energy");
            if(formula)row.createCell(3,CellType.FORMULA).setCellFormula("1+2");
            else row.createCell(3).setCellValue(0.25);
            row.createCell(4).setCellValue("kWh");
            row.createCell(5).setCellValue("BR");
            excel.write(output);
            return output.toByteArray();
        }
    }

    /** The CSV written by scripts/build_evr_stock_csv.py (#94) is exactly this shape: keys sanitised, one year, vehicles. */
    @Test
    void evrBridgeCsvIsAcceptedAndStaysACandidateNeverAdmitted() {
        String csv=HEADER+"I_BYD_DOLPHIN_GS_180EV,2026,stock,69235,vehicles,BR\r\n"+
            "BYD_DOLPHIN_MINI_GS5EV,2026,stock,43839,vehicles,BR\r\n";
        var parsed=TabularCandidateParser.parse(csv.getBytes(StandardCharsets.UTF_8),"CSV",2026);
        assertTrue(parsed.valid(),parsed.issues().toString());
        assertEquals(2,parsed.candidates().size());
        assertEquals("stock",parsed.candidates().get(0).metric());
        assertEquals("vehicles",parsed.candidates().get(0).unit());
        // A literal Senatran name is NOT a valid key: the bridge exists because of this.
        var raw=TabularCandidateParser.parse((HEADER+"I/BYD DOLPHIN GS 180EV,2026,stock,1,vehicles,BR\r\n")
            .getBytes(StandardCharsets.UTF_8),"CSV",2026);
        assertFalse(raw.valid());
    }
}
