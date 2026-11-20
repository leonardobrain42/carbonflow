package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Entirely invented fixture; never an official Brazilian stock or PBEV value. */
class OpenE8FleetSelectionsRulesTest {
    private static final UUID FLEET=UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID RATING=UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static OpenE8FleetSelections.Fleet fleet(){
        return new OpenE8FleetSelections.Fleet(FLEET,RATING,
            2027,2027,"COMBINED","a".repeat(64),"b".repeat(64),
            "OFFICIAL_TOTAL_NOT_VERIFIED","PREPARATORY","PREPARATORY",
            "STOCK_IN_CIRCULATION","BR",20L,2,2,null,null,null);
    }
    private static OpenE8FleetSelections.Coverage coverage(){
        return new OpenE8FleetSelections.Coverage(2,2,20,20,0,2);
    }
    private static void reject(OpenE8FleetSelections.Fleet f,
                               OpenE8FleetSelections.Coverage c) {
        assertThrows(PilotProblem.class,()->OpenE8FleetSelections.check(f,2027,c));
    }

    @Test
    void completePreparatoryStockAndReviewedRatingsCanBeReferencedButNotAdmitted(){
        assertDoesNotThrow(()->OpenE8FleetSelections.check(fleet(),2027,coverage()));
        assertThrows(PilotProblem.class,
            ()->OpenE8FleetSelections.check(fleet(),2026,coverage()));
    }

    @Test
    void salesAreNotStockAndOldYearCannotBeReusedAsCurrentEvr(){
        var f=fleet();
        reject(new OpenE8FleetSelections.Fleet(f.fleetId(),f.ratingId(),
            2027,2027,f.cycle(),f.fleetSha(),f.ratingSha(),f.fleetCoverage(),
            f.ratingState(),f.stockState(),"NEW_SALES",f.geography(),
            f.denominator(),f.fleetModels(),f.ratingModels(),
            null,null,null),coverage());
        reject(new OpenE8FleetSelections.Fleet(f.fleetId(),f.ratingId(),
            2026,2026,f.cycle(),f.fleetSha(),f.ratingSha(),f.fleetCoverage(),
            f.ratingState(),f.stockState(),f.stockKind(),f.geography(),
            f.denominator(),f.fleetModels(),f.ratingModels(),
            null,null,null),coverage());
    }

    @Test
    void anyMissingModelOrUnacceptedComparableBlocksProjectLink(){
        var c=coverage();
        reject(fleet(),new OpenE8FleetSelections.Coverage(
            2,1,20,12,1,1));
        reject(fleet(),new OpenE8FleetSelections.Coverage(
            2,2,20,20,0,1)); // only one human-accepted pair
        reject(fleet(),new OpenE8FleetSelections.Coverage(
            2,2,20,20,1,2)); // mismatched model/EVR
        reject(fleet(),new OpenE8FleetSelections.Coverage(
            2,2,19,20,0,2)); // stock totals inconsistent
        reject(fleet(),new OpenE8FleetSelections.Coverage(
            1,2,20,20,0,2)); // unauthorized missing stock row
        assertEquals(2,c.acceptedPairs());
    }

    @Test
    void revokedLicensedDataCannotBeReusedEvenWhenOldHashStillExists(){
        var f=fleet();
        reject(new OpenE8FleetSelections.Fleet(f.fleetId(),f.ratingId(),
            2027,2027,f.cycle(),f.fleetSha(),f.ratingSha(),"SUSPENDED",
            f.ratingState(),f.stockState(),f.stockKind(),f.geography(),
            f.denominator(),f.fleetModels(),f.ratingModels(),
            OffsetDateTime.parse("2027-03-01T00:00:00Z"),null,null),coverage());
        reject(new OpenE8FleetSelections.Fleet(f.fleetId(),f.ratingId(),
            2027,2027,f.cycle(),f.fleetSha(),f.ratingSha(),
            f.fleetCoverage(),"STALE_REFERENCE",f.stockState(),
            f.stockKind(),f.geography(),f.denominator(),
            f.fleetModels(),f.ratingModels(),null,null,null),coverage());
        reject(new OpenE8FleetSelections.Fleet(f.fleetId(),f.ratingId(),
            2027,2027,f.cycle(),f.fleetSha(),f.ratingSha(),
            f.fleetCoverage(),f.ratingState(),"SUSPENDED",
            f.stockKind(),f.geography(),f.denominator(),
            f.fleetModels(),f.ratingModels(),null,null,
            OffsetDateTime.parse("2027-03-01T00:00:00Z")),coverage());
    }

    @Test
    void unverifiedNationalDenominatorMustRemainPreparatory(){
        var f=fleet();
        reject(new OpenE8FleetSelections.Fleet(f.fleetId(),f.ratingId(),
            2027,2027,f.cycle(),f.fleetSha(),f.ratingSha(),
            f.fleetCoverage(),f.ratingState(),f.stockState(),
            f.stockKind(),"US",f.denominator(),
            f.fleetModels(),f.ratingModels(),null,null,null),coverage());
        reject(new OpenE8FleetSelections.Fleet(f.fleetId(),f.ratingId(),
            2027,2027,f.cycle(),f.fleetSha(),f.ratingSha(),
            f.fleetCoverage(),f.ratingState(),f.stockState(),
            f.stockKind(),f.geography(),null,
            f.fleetModels(),f.ratingModels(),null,null,null),coverage());
        reject(new OpenE8FleetSelections.Fleet(f.fleetId(),f.ratingId(),
            2027,2027,f.cycle(),f.fleetSha(),f.ratingSha(),
            f.fleetCoverage(),f.ratingState(),f.stockState(),
            f.stockKind(),f.geography(),f.denominator(),
            1,2,null,null,null),coverage());
    }
}
