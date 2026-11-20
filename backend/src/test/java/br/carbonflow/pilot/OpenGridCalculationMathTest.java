package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Explicitly invented test values. Not official Brazilian factors, fleet
 * observations, Verra approval, or a project-scoped run.
 */
class OpenGridCalculationMathTest {
    private static BigDecimal n(String value){return new BigDecimal(value);}
    private static List<OpenGridCalculationMath.FleetRow> fleet() {
        return List.of(
            new OpenGridCalculationMath.FleetRow("BEV-B",8,n("23"),n("27")),
            new OpenGridCalculationMath.FleetRow("BEV-A",12,n("18"),n("32")));
    }
    private static OpenGridCalculationMath.Input in(
            String branch,int year,List<OpenGridCalculationMath.FleetRow> fleet,
            String ed,String ec,String fossil,String electricity,String ir,
            OpenGridCalculationMath.Discount discount) {
        return new OpenGridCalculationMath.Input(branch,year,fleet,
            ed==null?null:n(ed),ec==null?null:n(ec),
            fossil==null?null:n(fossil),
            electricity==null?null:n(electricity),
            ir==null?null:n(ir),discount);
    }
    private static OpenGridCalculationMath.Discount noFleetCredits() {
        return new OpenGridCalculationMath.Discount(n("0"),n("0"),true);
    }
    private static OpenGridCalculationMath.Input valid() {
        return in(OpenGridCalculationMath.BRANCH,1,fleet(),"2700","3000",
            "0.009","0.0003","1",noFleetCredits());
    }

    @Test
    void weightedEq2Eq3AndEq1Eq4Eq7AreReproducibleWithSeparateEdAndEc() {
        var result=OpenGridCalculationMath.calculate(valid());
        assertEquals("20",result.afecKwhPer100Miles());
        assertEquals("30",result.mpgUs());
        assertEquals(20,result.evrTotal());
        assertEquals("4.05",result.beTco2e());
        assertEquals("0.9",result.peTco2e());
        assertEquals("0",result.leTco2e());
        assertEquals("1",result.dy());
        assertEquals("3.15",result.erTco2e());
        assertEquals("3.15",result.erDisplay());
        assertFalse(result.usableForE8());
        assertEquals("PURE_MATH_TEST_ONLY_NOT_ADMITTED",result.state());
        assertEquals(7,result.equationTrace().size());
        assertEquals(List.of("AFEC","MPG","BE","PE","LE","DY","ER"),
            result.equationTrace().stream().map(
                OpenGridCalculationMath.Step::code).toList());
        assertEquals("BEV-A",result.fleetTrace().getFirst().modelCode());
        assertEquals("216",result.fleetTrace().getFirst().weightedEnergy());
        assertEquals("384",result.fleetTrace().getFirst().weightedMpg());
        assertEquals("VM0038-1.1-EQ1",result.equationTrace().get(2).reference());
        assertEquals("ED",result.equationTrace().get(2).inputs().getFirst().code());
        assertEquals("EC",result.equationTrace().get(3).inputs().getFirst().code());
        assertEquals("3000",result.equationTrace().get(3).inputs().getFirst().value());
    }

    @Test
    void projectYearImprovementAndDiscountAreNeverImplicitDefaults() {
        var result=OpenGridCalculationMath.calculate(in(
            OpenGridCalculationMath.BRANCH,2,fleet(),"2700","3000",
            "0.009","0.0003","0.99",
            new OpenGridCalculationMath.Discount(n("60"),n("40"),false)));
        assertEquals("4.0095",result.beTco2e());
        assertEquals("0.9",result.peTco2e());
        assertEquals("0.6",result.dy());
        assertEquals("1.8657",result.erTco2e());
        assertEquals("1.87",result.erDisplay());
        assertEquals("VM0038-1.1-EQ8",
            result.equationTrace().get(5).reference());
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,fleet(),"2700","3000",
               "0.009","0.0003",null,noFleetCredits())));
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,fleet(),"2700","3000",
               "0.009","0.0003","1",null)));
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,fleet(),"2700","3000",
               "0.009","0.0003","1",
               new OpenGridCalculationMath.Discount(n("0"),n("0"),false))));
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,fleet(),"2700","3000",
               "0.009","0.0003","1",
               new OpenGridCalculationMath.Discount(n("0"),n("10"),true))));
    }

    @Test
    void zeroIsLegitimateOnlyWhenProvidedNotWhenMissingAndCannotBecomeCredits() {
        var zero=OpenGridCalculationMath.calculate(in(
            OpenGridCalculationMath.BRANCH,1,fleet(),"0","0",
            "0.009","0.0003","1",noFleetCredits()));
        assertEquals("0",zero.beTco2e());
        assertEquals("0",zero.peTco2e());
        assertEquals("0",zero.erTco2e());
        assertFalse(zero.usableForE8());
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,fleet(),null,"3000",
               "0.009","0.0003","1",noFleetCredits())));
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,fleet(),"2700",null,
               "0.009","0.0003","1",noFleetCredits())));
    }

    @Test
    void wrongBranchAndUnadmittedFleetCannotProduceCalculation() {
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in("GRID_WITH_BATTERY",1,fleet(),"2700","3000",
                "0.009","0.0003","1",noFleetCredits())));
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,List.of(),"2700","3000",
                "0.009","0.0003","1",noFleetCredits())));
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,
                List.of(fleet().getFirst(),fleet().getFirst()),"2700","3000",
                "0.009","0.0003","1",noFleetCredits())));
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,
                List.of(new OpenGridCalculationMath.FleetRow("BEV-X",0,
                    n("18"),n("32"))),"2700","3000",
                "0.009","0.0003","1",noFleetCredits())));
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,
                List.of(new OpenGridCalculationMath.FleetRow("BEV-X",Long.MAX_VALUE,
                    n("18"),n("32")),
                    new OpenGridCalculationMath.FleetRow("BEV-Y",Long.MAX_VALUE,
                    n("18"),n("32"))),"2700","3000",
                "0.009","0.0003","1",noFleetCredits())));
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,101,fleet(),"2700","3000",
                "0.009","0.0003","1",noFleetCredits())));
    }

    @Test
    void postgresqlNumericPaddingDoesNotChangeTraceOrResult() {
        var before=OpenGridCalculationMath.calculate(valid());
        var after=OpenGridCalculationMath.calculate(in(
            OpenGridCalculationMath.BRANCH,1,
            List.of(new OpenGridCalculationMath.FleetRow("BEV-A",12,
                    n("18.000000000000"),n("32.000000000000")),
                new OpenGridCalculationMath.FleetRow("BEV-B",8,
                    n("23.000000000000"),n("27.000000000000"))),
            "2700.000000000000","3000.000000000000",
            "0.009000000000","0.000300000000","1.000000000000",
            new OpenGridCalculationMath.Discount(n("0.000000000000"),
                n("0.000000000000"),true)));
        assertEquals(before,after);
        assertThrows(IllegalArgumentException.class,()->OpenGridCalculationMath.calculate(
            in(OpenGridCalculationMath.BRANCH,1,fleet(),"2700.1234567890123",
                "3000","0.009","0.0003","1",noFleetCredits())));
    }

    @Test
    void noSilentCappingOfNegativeNetResult() {
        var result=OpenGridCalculationMath.calculate(in(
            OpenGridCalculationMath.BRANCH,1,fleet(),"1","3000",
            "0.009","0.0003","1",noFleetCredits()));
        assertTrue(new BigDecimal(result.erTco2e()).signum()<0);
        assertEquals("VM0038-1.1-EQ7",result.equationTrace().getLast().reference());
    }
}
