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

    @Test
    void paddedDatabaseNumericsPreserveTheSameValidEmissions() {
        // PostgreSQL NUMERIC(30,12) is read back with twelve decimal places.
        // This is the same source data as the unpadded integration fixture.
        var dbYears=years(2026,"1000.000000000000","300.000000000000");
        var dbPlants=List.of(
            new OpenTool07SimpleOmMath.Plant("T1",n("400.000000000000"),
                List.of(fuel("coal","10.000000000000","20.000000000000",
                    "0.100000000000"))),
            new OpenTool07SimpleOmMath.Plant("T2",n("300.000000000000"),
                List.of(fuel("gas","8.000000000000","25.000000000000",
                    "0.200000000000"))));
        var stored=OpenTool07SimpleOmMath.calculate(new OpenTool07SimpleOmMath.Input(
            2026,OpenTool07SimpleOmMath.APPROACH_1,dbYears,dbPlants));
        var fresh=OpenTool07SimpleOmMath.calculate(new OpenTool07SimpleOmMath.Input(
            2026,OpenTool07SimpleOmMath.APPROACH_1,years(2026,"1000","300"),
            List.of(new OpenTool07SimpleOmMath.Plant("T1",n("400"),
                List.of(fuel("coal","10","20","0.1"))),
                new OpenTool07SimpleOmMath.Plant("T2",n("300"),
                List.of(fuel("gas","8","25","0.2"))))));
        assertEquals(fresh.totalEmissionsTco2(),stored.totalEmissionsTco2());
        assertEquals("60",stored.totalEmissionsTco2());
        assertEquals(fresh.illustrativeOmTco2PerMwh(),
            stored.illustrativeOmTco2PerMwh());
        assertEquals("20",stored.plantCalculations().get(0).emissionsTco2());
        assertEquals("40",stored.plantCalculations().get(1).emissionsTco2());
    }

    @Test
    void stillRejectsExcessMeaningfulPrecisionRatherThanSilentlyRounding() {
        var input=new OpenTool07SimpleOmMath.Input(2026,
            OpenTool07SimpleOmMath.APPROACH_1,years(2026,"1000","300"),
            List.of(new OpenTool07SimpleOmMath.Plant("T1",n("700"),
                List.of(fuel("gas","1.123456789012","1.123456789012",
                    "1.123456789012")))));
        assertThrows(IllegalArgumentException.class,
            ()->OpenTool07SimpleOmMath.calculate(input));
    }

    @Test
    void traceExplainsFiveAnnualSharesAndEveryFuelWithoutRoundingOrRelyingOnInputOrder() {
        var years=years(2026,"1000","300");
        var coal=fuel("coal","10","20","0.1");
        var gas=fuel("gas","8","25","0.2");
        var input=new OpenTool07SimpleOmMath.Input(2026,
            OpenTool07SimpleOmMath.APPROACH_1,years,
            List.of(new OpenTool07SimpleOmMath.Plant("T1",n("700"),
                List.of(gas,coal)))); // deliberately unsorted
        var out=OpenTool07SimpleOmMath.calculate(input);
        assertEquals(5,out.shareCalculations().size());
        assertEquals(2022,out.shareCalculations().getFirst().year());
        assertEquals("0.3",out.shareCalculations().getFirst().annualShare());
        assertEquals("300",out.shareCalculations().getFirst().lcmrMwh());
        assertEquals("1000",out.shareCalculations().getFirst().totalGridMwh());
        var unit=out.plantCalculations().getFirst();
        assertEquals("60",unit.emissionsTco2());
        assertEquals(2,unit.fuelCalculations().size());
        assertEquals("coal",unit.fuelCalculations().get(0).fuelCode());
        assertEquals("20",unit.fuelCalculations().get(0).emissionsTco2());
        assertEquals("10",unit.fuelCalculations().get(0).consumptionTonnes());
        assertEquals("20",unit.fuelCalculations().get(0).ncvGjPerTonne());
        assertEquals("0.1",unit.fuelCalculations().get(0).efTco2PerGj());
        assertEquals("TOOL07-V7.0-EQ4",
            unit.fuelCalculations().get(0).equationRef());
        assertEquals(SHA,unit.fuelCalculations().get(0).declaredSourceSha256());
        assertEquals("gas",unit.fuelCalculations().get(1).fuelCode());
        assertEquals("40",unit.fuelCalculations().get(1).emissionsTco2());
        // The JDBC NUMERIC(30,12) roundtrip should not change audit values
        // or sort order for the very same material inputs.
        var stored=new OpenTool07SimpleOmMath.Input(2026,
            OpenTool07SimpleOmMath.APPROACH_1,
            years(2026,"1000.000000000000","300.000000000000"),
            List.of(new OpenTool07SimpleOmMath.Plant("T1",n("700.000000000000"),
                List.of(fuel("coal","10.000000000000","20.000000000000",
                    "0.100000000000"),
                    fuel("gas","8.000000000000","25.000000000000",
                    "0.200000000000")))));
        var replay=OpenTool07SimpleOmMath.calculate(stored);
        assertEquals(out.shareCalculations(),replay.shareCalculations());
        assertEquals(out.plantCalculations(),replay.plantCalculations());
        assertEquals(out.illustrativeOmTco2PerMwh(),
            replay.illustrativeOmTco2PerMwh());
        assertFalse(replay.usableForE8());
    }
}
