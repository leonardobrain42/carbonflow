package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Fictitious data only; a source's gas claim is still subject to human review. */
class OpenFossilFactorParserTest {
    private static TabularCandidateParser.Parsed sample(String unit,String value){
        String csv="record_key,period_year,metric,value,unit,geography\n"+
            "fictional-fuel,2036,EF_FUEL_GASOLINE,"+value+","+unit+",BR\n";
        return TabularCandidateParser.parse(csv.getBytes(StandardCharsets.UTF_8),
            "CSV",2036);
    }

    @Test void acceptsExplicitFuelUnitsAndConvertsWithoutChangingGas(){
        for(String unit:new String[]{"tCO2e/L","kgCO2e/L","tCO2e/USgal"}){
            var parsed=sample(unit,"0.002");
            assertTrue(parsed.valid(),parsed.issues().toString());
            var row=parsed.candidates().get(0);
            assertEquals(unit,row.unit());
            assertEquals("EF_FUEL_GASOLINE",row.metric());
            var preview=OpenEmissionParameterMath.convert("EF_FUEL","CO2e",
                row.unit(),row.value());
            assertEquals("tCO2e/USgal",preview.normalizedUnit());
            assertFalse(preview.usableForE8());
        }
        assertEquals(0,OpenEmissionParameterMath.convert("EF_FUEL","CO2e",
            "tCO2e/L",new BigDecimal("0.002")).normalizedValue()
            .compareTo(new BigDecimal("0.007570823568")));
    }

    @Test void rejectsUnsupportedUnitOrInferredGases(){
        assertFalse(sample("tCO2e/imperial-gal","0.002").valid());
        assertFalse(sample("tCO2/L","0.002").valid()); // no implicit CO2 to CO2e
        assertThrows(IllegalArgumentException.class,()->
            OpenEmissionParameterMath.convert("IR",null,"1",BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,()->
            OpenEmissionParameterMath.convert("EF_FUEL","CO2",
                "tCO2e/L",new BigDecimal("0.002")));
    }

    @Test void negativeAndMissingAreNotPlaceholderZero(){
        var parsed=sample("tCO2e/L","-0.02");
        assertTrue(parsed.valid()); // raw parser does not approve provenance
        assertThrows(IllegalArgumentException.class,()->
            OpenEmissionParameterMath.convert("EF_FUEL","CO2e",
                parsed.candidates().get(0).unit(),parsed.candidates().get(0).value()));
        assertThrows(IllegalArgumentException.class,()->
            OpenEmissionParameterMath.convert("EF_FUEL","CO2e","tCO2e/L",null));
    }
}
