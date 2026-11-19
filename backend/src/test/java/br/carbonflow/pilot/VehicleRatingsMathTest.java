package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleRatingsMathTest {
    private static final UUID A=UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID B=UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    static BigDecimal dec(String text){return new BigDecimal(text);}

    @Test
    void explicitMetricConversionsNeverConfuseUsWithImperialGallons(){
        var energy=VehicleRatingsMath.convert("BEV","MJ/km",dec("0.5"));
        assertEquals("kWh/100mi",energy.unit());
        assertEquals(dec("22.352000000000"),energy.value());
        assertEquals(dec("32.186880000000"),VehicleRatingsMath.convert("BEV",
            "kWh/100km",dec("20")).value());
        assertEquals(dec("40.233600000000"),VehicleRatingsMath.convert("BEV",
            "kWh/km",dec("0.25")).value());
        assertEquals(dec("18.000000000000"),VehicleRatingsMath.convert("BEV",
            "kWh/100mi",dec("18")).value());
        var fossil=VehicleRatingsMath.convert("FOSSIL","km/L",dec("10"));
        assertEquals("mi/USgal",fossil.unit());
        assertEquals(dec("23.521458333333"),fossil.value());
        assertEquals(dec("32.000000000000"),VehicleRatingsMath.convert("FOSSIL",
            "mi/USgal",dec("32")).value());
        assertThrows(IllegalArgumentException.class,()->VehicleRatingsMath.convert(
            "FOSSIL","mi/IMPERIALgal",dec("32")));
        assertThrows(IllegalArgumentException.class,()->VehicleRatingsMath.convert(
            "BEV","MPGe",dec("100")));
        assertThrows(IllegalArgumentException.class,()->VehicleRatingsMath.convert(
            "BEV","MJ/km",dec("0")));
        assertThrows(IllegalArgumentException.class,()->VehicleRatingsMath.convert(
            "BEV","MJ/km",dec("-1")));
    }

    @Test
    void syntheticA12At18And32B8At23And27YieldsAfec20Mpg30(){
        var rows=List.of(new VehicleRatingsMath.WeightedRow(A,12,dec("18"),
                dec("32"),"COMBINED","GASOLINE"),
            new VehicleRatingsMath.WeightedRow(B,8,dec("23"),
                dec("27"),"COMBINED","GASOLINE"));
        var result=VehicleRatingsMath.compose(rows);
        assertTrue(result.valid(),result.issues().toString());
        assertEquals(20,result.evrTotal());
        assertEquals(dec("20.000000000000"),result.afecKwhPer100mi());
        assertEquals(dec("30.000000000000"),result.mpgUs());
        assertEquals("COMBINED",result.cycle());
        assertFalse(result.usableForE8(),"Mesmo o cálculo sintético não vira parâmetro real.");
    }

    @Test
    void incompleteMixedFuelMixedCycleOrDuplicateLeavesNoNumericResult(){
        var good=new VehicleRatingsMath.WeightedRow(A,12,dec("18"),dec("32"),
            "COMBINED","GASOLINE");
        for(var invalid:List.of(
            List.of(good,new VehicleRatingsMath.WeightedRow(B,8,dec("23"),dec("27"),
                "URBAN","GASOLINE")),
            List.of(good,new VehicleRatingsMath.WeightedRow(B,8,dec("23"),dec("27"),
                "COMBINED","DIESEL")),
            List.of(good,new VehicleRatingsMath.WeightedRow(A,8,dec("23"),dec("27"),
                "COMBINED","GASOLINE")),
            List.of(good,new VehicleRatingsMath.WeightedRow(B,0,dec("23"),dec("27"),
                "COMBINED","GASOLINE"))
        )){
            var result=VehicleRatingsMath.compose(invalid);
            assertFalse(result.valid());
            assertNull(result.afecKwhPer100mi());
            assertNull(result.mpgUs());
            assertFalse(result.usableForE8());
        }
        assertFalse(VehicleRatingsMath.compose(List.of()).valid());
    }
}
