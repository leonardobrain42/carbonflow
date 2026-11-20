package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Only synthetic numerical oracles; no official SIN/ONS/MCTI data here. */
class OpenTool07SimpleOmMathTest {
    static final String SHA="a".repeat(64);
    private static BigDecimal n(String n){return new BigDecimal(n);}
    private static OpenTool07SimpleOmMath.Fuel fuel(
            String code,String tonnes,String ncv,String ef) {
        return new OpenTool07SimpleOmMath.Fuel(code,n(tonnes),n(ncv),n(ef),SHA);
    }
    private static List<OpenTool07SimpleOmMath.ShareYear> years(int year,String total,
                                                                String lcmr){
        var rows=new ArrayList<OpenTool07SimpleOmMath.ShareYear>();
        for(int i=year-4;i<=year;i++)
            rows.add(new OpenTool07SimpleOmMath.ShareYear(i,n(total),n(lcmr)));
        return rows;
    }
    @Test
    void fiveYearScreenAndPlantFuelOptionA1UseSeparateDimensions(){
        var input=new OpenTool07SimpleOmMath.Input(2026,
            OpenTool07SimpleOmMath.APPROACH_1,years(2026,"1000","300"),
            List.of(
                new OpenTool07SimpleOmMath.Plant("T1",n("400"),
                    List.of(fuel("coal","10","20","0.1"))),
                new OpenTool07SimpleOmMath.Plant("T2",n("300"),
                    List.of(fuel("gas","8","25","0.2")))));
        var out=OpenTool07SimpleOmMath.calculate(input);
        assertTrue(out.fiveYearLcmrScreenPassed());
        assertEquals(0,n("0.3").compareTo(n(out.selectedShare())));
        assertEquals(0,n("700").compareTo(n(out.includedGenerationMwh())));
        assertEquals(0,n("60").compareTo(n(out.totalEmissionsTco2())));
        assertEquals(0,n("0.085714285714285714").compareTo(
            n(out.illustrativeOmTco2PerMwh())));
        assertEquals(0,n("0.000085714285714285714").compareTo(
            n(out.illustrativeOmTco2PerKwh())));
        assertEquals("TOOL07-V7.0-EQ1-EQ2-EQ3-EQ4",out.reference());
        assertEquals(2,out.plantCalculations().size());
        assertFalse(out.usableForE8());
        assertTrue(out.gates().contains("NO_CO2_TO_CO2E_HOURLY_BRIDGE"));
    }
    @Test
    void approachOneAndTwoCanDisagreeAndCrossingFiftyPercentFails(){
        var rows=List.of(
            new OpenTool07SimpleOmMath.ShareYear(2022,n("100"),n("20")),
            new OpenTool07SimpleOmMath.ShareYear(2023,n("100"),n("20")),
            new OpenTool07SimpleOmMath.ShareYear(2024,n("100"),n("20")),
            new OpenTool07SimpleOmMath.ShareYear(2025,n("100"),n("20")),
            new OpenTool07SimpleOmMath.ShareYear(2026,n("1000"),n("700")));
        var plants=List.of(new OpenTool07SimpleOmMath.Plant("T1",n("300"),
            List.of(fuel("coal","5","20","0.1"))));
        var a=OpenTool07SimpleOmMath.calculate(new OpenTool07SimpleOmMath.Input(
            2026,OpenTool07SimpleOmMath.APPROACH_1,rows,plants));
        var b=OpenTool07SimpleOmMath.calculate(new OpenTool07SimpleOmMath.Input(
            2026,OpenTool07SimpleOmMath.APPROACH_2,rows,plants));
        assertTrue(a.fiveYearLcmrScreenPassed());
        assertFalse(b.fiveYearLcmrScreenPassed());
        assertTrue(b.gates().contains("LCMR_5YEAR_SCREEN_NOT_PASSED"));
        assertFalse(b.usableForE8());
    }
    @Test
    void rejectsMissingYearsMismatchedGenerationDuplicatesAndInvalidFuel(){
        var years=years(2026,"1000","300");
        var plant=new OpenTool07SimpleOmMath.Plant("T1",n("700"),
            List.of(fuel("gas","8","25","0.2")));
        var good=new OpenTool07SimpleOmMath.Input(2026,
            OpenTool07SimpleOmMath.APPROACH_1,years,List.of(plant));
        assertNotNull(OpenTool07SimpleOmMath.calculate(good));
        assertThrows(IllegalArgumentException.class,()->OpenTool07SimpleOmMath.calculate(
            new OpenTool07SimpleOmMath.Input(2026,
                OpenTool07SimpleOmMath.APPROACH_1,years.subList(1,5),List.of(plant))));
        assertThrows(IllegalArgumentException.class,()->OpenTool07SimpleOmMath.calculate(
            new OpenTool07SimpleOmMath.Input(2026,
                OpenTool07SimpleOmMath.APPROACH_1,years,List.of(
                    new OpenTool07SimpleOmMath.Plant("T1",n("699"),plant.fuels())))));
        assertThrows(IllegalArgumentException.class,()->OpenTool07SimpleOmMath.calculate(
            new OpenTool07SimpleOmMath.Input(2026,
                OpenTool07SimpleOmMath.APPROACH_1,years,List.of(plant,plant))));
        assertThrows(IllegalArgumentException.class,()->OpenTool07SimpleOmMath.calculate(
            new OpenTool07SimpleOmMath.Input(2026,
                OpenTool07SimpleOmMath.APPROACH_1,years,List.of(
                    new OpenTool07SimpleOmMath.Plant("T1",n("700"),
                        List.of(fuel("gas","-8","25","0.2")))))));
        assertThrows(IllegalArgumentException.class,()->OpenTool07SimpleOmMath.calculate(
            new OpenTool07SimpleOmMath.Input(2026,
                OpenTool07SimpleOmMath.APPROACH_1,years,List.of(
                    new OpenTool07SimpleOmMath.Plant("T1",n("700"),
                        List.of(new OpenTool07SimpleOmMath.Fuel(
                            "gas",n("8"),n("25"),n("0.2"),"not-a-sha")))))));
    }
}
