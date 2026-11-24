package br.carbonflow.pilot;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
class OpenDyAllocationsTest {
    private static final OpenDyIssuances.Row ITEM=new OpenDyIssuances.Row("Program","Project","ISS","ERC",
        LocalDate.parse("2034-07-01"),"BR","75","S","1","test item");
    private static OpenDyAllocations.Input input(String method,String total,String numerator,String denominator) {
        return new OpenDyAllocations.Input("Program","Project","ISS",method,total,numerator,denominator,
            "activity","S","1","test allocation","Declared test allocation basis");
    }
    @Test void exactShareAndDirectAttribution() {
        var rows=OpenDyAllocations.normalize(List.of(input("DOCUMENTED_SHARE","100","3","4")),List.of(ITEM));
        assertEquals("75",rows.get(0).brazilTco2e());
        assertDoesNotThrow(()->OpenDyAllocations.normalize(List.of(input("DIRECT_BR","75","1","1")),List.of(ITEM)));
        assertThrows(IllegalArgumentException.class,()->OpenDyAllocations.normalize(List.of(input("DOCUMENTED_SHARE","100","2","4")),List.of(ITEM)));
        assertThrows(IllegalArgumentException.class,()->OpenDyAllocations.normalize(List.of(input("DOCUMENTED_SHARE","75","2","1")),List.of(ITEM)));
        assertThrows(IllegalArgumentException.class,()->OpenDyAllocations.normalize(List.of(input("DIRECT_BR","100","3","4")),List.of(ITEM)));
        assertThrows(IllegalArgumentException.class,()->OpenDyAllocations.normalize(List.of(input("DOCUMENTED_SHARE","100","0","4")),List.of(ITEM)));
        assertThrows(IllegalArgumentException.class,()->OpenDyAllocations.normalize(List.of(input("DIRECT_BR","75","1","1"),input("DIRECT_BR","75","1","1")),List.of(ITEM)));
    }
}
