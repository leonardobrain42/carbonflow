package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FleetCompositionRulesTest {
    static final UUID A=UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID B=UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    static FleetCompositionRules.Row row(int n,UUID model,String key,String quantity,
            String metric,int year,String geography,String category,String propulsion) {
        return new FleetCompositionRules.Row(n,model,key,
            quantity==null?null:new BigDecimal(quantity),metric,"vehicles",year,geography,
            category,propulsion,"CSV linha "+n);
    }
    @Test
    void stockWeightsAreExactOneWithoutInventingMissingCoverage() {
        var rows=List.of(row(2,A,"MODEL_A","3","stock",2025,"BR","LDV","BEV"),
            row(3,B,"MODEL_B","2","stock",2025,"BR","LDV","BEV"));
        var result=FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",rows);
        assertTrue(result.valid(),result.issues().toString());
        assertEquals(5,result.total());
        assertEquals(2,result.weights().size());
        assertEquals(new BigDecimal("0.600000000000000000"),result.weights().get(0).weight());
        assertEquals(new BigDecimal("1.000000000000000000"),
            result.weights().stream().map(FleetCompositionRules.Weight::weight)
                .reduce(BigDecimal.ZERO,BigDecimal::add));
        assertFalse(result.usableForE8());
    }
    @Test
    void roundingUsesLastWeightForExactSum() {
        var result=FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",
            List.of(row(2,A,"A","1","stock",2025,"BR","LDV","BEV"),
                    row(3,B,"B","2","stock",2025,"BR","LDV","BEV")));
        assertTrue(result.valid());
        assertEquals(new BigDecimal("1.000000000000000000"),
            result.weights().stream().map(FleetCompositionRules.Weight::weight)
                .reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    @Test
    void missingFractionalNegativeAndZeroDenominatorFailClosed() {
        for(var input:List.of("0","-1","1.4")){
            var result=FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",
                List.of(row(2,A,"A",input,"stock",2025,"BR","LDV","BEV")));
            if(input.equals("0"))assertTrue(result.issues().toString().contains("denominador")
                || result.issues().toString().contains("Denominador"));
            else assertFalse(result.valid());
        }
        assertFalse(FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",
            List.of(row(2,A,"A",null,"stock",2025,"BR","LDV","BEV"))).valid());
        assertFalse(FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",List.of()).valid());
    }
    @Test
    void rejectsDuplicateModelsUnknownAliasesMixedYearsAndGeographies() {
        var duplicate=FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",
            List.of(row(2,A,"A","10","stock",2025,"BR","LDV","BEV"),
                    row(3,A,"A_ALIAS","10","stock",2025,"BR","LDV","BEV")));
        assertFalse(duplicate.valid());assertTrue(duplicate.issues().toString().contains("duplicado"));
        var noAlias=FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",
            List.of(row(2,null,"UNMAPPED","10","stock",2025,"BR","LDV","BEV")));
        assertFalse(noAlias.valid());
        assertTrue(noAlias.issues().toString().contains("não classificado"));
        var mixed=FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",
            List.of(row(2,A,"A","10","stock",2024,"BR","LDV","BEV"),
                    row(3,B,"B","1","stock",2025,"DF","LDV","BEV")));
        assertFalse(mixed.valid());
    }
    @Test
    void neverPromotesSalesPhevHdvOrRatingsIntoEvr() {
        assertFalse(FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",
            List.of(row(2,A,"A","100","sales",2025,"BR","LDV","BEV"))).valid());
        assertFalse(FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",
            List.of(row(2,A,"A","100","stock",2025,"BR","LDV","PHEV"))).valid());
        assertFalse(FleetCompositionRules.evaluate(FleetCompositionRules.STOCK,2025,"BR",
            List.of(row(2,A,"A","100","stock",2025,"BR","HDV","BEV"))).valid());
        var sales=FleetCompositionRules.evaluate(FleetCompositionRules.SALES,2025,"BR",
            List.of(row(2,A,"A","100","sales",2025,"BR","LDV","BEV")));
        assertTrue(sales.valid());
        assertTrue(sales.weights().isEmpty());
        assertFalse(sales.usableForE8());
    }
}
