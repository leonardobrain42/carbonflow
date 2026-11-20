package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TOOL07 v7.0 Eq. 1/2 (five-year LCMR screen) and Eq. 3/4
 * (Simple OM Option A1), for one disclosed grid-system year only.
 *
 * An arithmetic precheck is NOT proof of method eligibility: plant inventory,
 * LCMR classifications, fuel consumption, imports and source rights remain
 * unverified. No E8 use or equivalence between tCO2 and tCO2e is asserted.
 */
final class OpenTool07SimpleOmMath {
    static final String RULE="TOOL07-V7.0-EQ1-EQ2-EQ3-EQ4";
    static final String APPROACH_1="FIVE_YEAR_AVERAGE_SHARES";
    static final String APPROACH_2="FIVE_YEAR_AGGREGATE_SHARE";
    private static final BigDecimal ONE_HALF=new BigDecimal("0.50");
    private static final MathContext MC=MathContext.DECIMAL128;

    record ShareYear(int year,BigDecimal totalGridMwh,BigDecimal lcmrMwh) {}
    record Fuel(String fuelCode,BigDecimal consumptionTonnes,
                BigDecimal ncvGjPerTonne,BigDecimal efTco2PerGj,
                String sourceSha256) {}
    record Plant(String plantCode,BigDecimal netGenerationMwh,List<Fuel> fuels) {}
    record Input(int dataYear,String shareApproach,List<ShareYear> shares,List<Plant> plants) {}
    record PlantResult(String plantCode,String netGenerationMwh,
                       String emissionsTco2,String efTco2PerMwh) {}
    record Preview(String reference,int dataYear,String approach,
                   String shareApproach1,String shareApproach2,String selectedShare,
                   boolean fiveYearLcmrScreenPassed,
                   String includedGenerationMwh,String totalEmissionsTco2,
                   String illustrativeOmTco2PerMwh,
                   String illustrativeOmTco2PerKwh,
                   List<PlantResult> plantCalculations,List<String> gates,
                   boolean usableForE8) {}

    static Preview calculate(Input input) {
        if(input==null||input.dataYear()<1994||input.dataYear()>Year.now().getValue())
            throw new IllegalArgumentException("Ano OM fora da janela permitida.");
        if(!Set.of(APPROACH_1,APPROACH_2).contains(input.shareApproach()))
            throw new IllegalArgumentException("Selecione a abordagem LCMR das equações 1 ou 2.");
        if(input.shares()==null||input.shares().size()!=5)
            throw new IllegalArgumentException("São necessários cinco anos contíguos de geração LCMR.");
        if(input.plants()==null||input.plants().isEmpty()||input.plants().size()>500)
            throw new IllegalArgumentException("Inventário de unidades geradoras ausente ou excessivo.");
        if(input.shares().stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Dados quinquenais não podem conter ano nulo.");
        List<ShareYear> shares=new ArrayList<>(input.shares());
        shares.sort(Comparator.comparingInt(ShareYear::year));
        BigDecimal sumShares=BigDecimal.ZERO,totalGrid=BigDecimal.ZERO,totalLcmr=BigDecimal.ZERO;
        for(int i=0;i<5;i++){
            ShareYear s=shares.get(i);
            if(s==null || s.year()!=input.dataYear()-4+i ||
               !positive(s.totalGridMwh())||!nonnegative(s.lcmrMwh()) ||
               s.lcmrMwh().compareTo(s.totalGridMwh())>0)
                throw new IllegalArgumentException("Série LCMR deve conter anos contínuos, total positivo e LCMR até o total.");
            sumShares=sumShares.add(s.lcmrMwh().divide(s.totalGridMwh(),24,RoundingMode.HALF_EVEN));
            totalGrid=totalGrid.add(s.totalGridMwh());
            totalLcmr=totalLcmr.add(s.lcmrMwh());
        }
        BigDecimal approach1=sumShares.divide(BigDecimal.valueOf(5),24,RoundingMode.HALF_EVEN);
        BigDecimal approach2=totalLcmr.divide(totalGrid,24,RoundingMode.HALF_EVEN);
        BigDecimal selected=APPROACH_1.equals(input.shareApproach())?approach1:approach2;

        var ordered=new ArrayList<>(input.plants());
        ordered.sort(Comparator.comparing(Plant::plantCode,
            Comparator.nullsFirst(Comparator.naturalOrder())));
        var plantCodes=new HashSet<String>();
        List<PlantResult> breakdown=new ArrayList<>();
        BigDecimal generation=BigDecimal.ZERO,emissions=BigDecimal.ZERO;
        for(Plant p:ordered){
            if(p==null||p.plantCode()==null||
               !p.plantCode().matches("[A-Za-z0-9_.-]{1,64}")||
               !plantCodes.add(p.plantCode())||
               !positive(p.netGenerationMwh())||p.fuels()==null||
               p.fuels().isEmpty()||p.fuels().size()>20)
                throw new IllegalArgumentException("Unidade geradora com identificador, geração ou combustíveis inválidos.");
            BigDecimal unitEmission=BigDecimal.ZERO;
            var fuelCodes=new HashSet<String>();
            for(Fuel f:p.fuels()){
                if(f==null||f.fuelCode()==null||
                   !f.fuelCode().matches("[A-Za-z0-9_.-]{1,64}")||
                   !fuelCodes.add(f.fuelCode())||
                   !positive(f.consumptionTonnes())||
                   !positive(f.ncvGjPerTonne())||
                   !nonnegative(f.efTco2PerGj())||
                   f.sourceSha256()==null||!f.sourceSha256().matches("[a-fA-F0-9]{64}"))
                    throw new IllegalArgumentException("Combustível requer consumo em t, NCV GJ/t, EF tCO2/GJ e SHA-256 declarado.");
                unitEmission=unitEmission.add(f.consumptionTonnes()
                    .multiply(f.ncvGjPerTonne(),MC)
                    .multiply(f.efTco2PerGj(),MC),MC);
            }
            if(unitEmission.precision()>30 ||
               unitEmission.precision()-unitEmission.scale()>18)
                throw new IllegalArgumentException("CO2 de unidade excede precisão suportada.");
            BigDecimal rate=unitEmission.divide(p.netGenerationMwh(),18,RoundingMode.HALF_EVEN);
            breakdown.add(new PlantResult(p.plantCode(),p.netGenerationMwh().toPlainString(),
                unitEmission.toPlainString(),rate.toPlainString()));
            generation=generation.add(p.netGenerationMwh());
            emissions=emissions.add(unitEmission);
        }
        BigDecimal expected=shares.get(4).totalGridMwh()
            .subtract(shares.get(4).lcmrMwh());
        if(generation.compareTo(expected)!=0)
            throw new IllegalArgumentException("Soma de geração não LCMR difere de total da rede menos LCMR em y.");
        if(emissions.precision()>30 ||
           emissions.precision()-emissions.scale()>18)
            throw new IllegalArgumentException("Soma das emissões excede precisão suportada.");
        BigDecimal om=emissions.divide(generation,18,RoundingMode.HALF_EVEN);
        BigDecimal omKwh=om.divide(new BigDecimal("1000"),21,RoundingMode.HALF_EVEN);
        boolean sharePassed=selected.compareTo(ONE_HALF)<0;
        var gates=new ArrayList<String>();
        if(!sharePassed)gates.add("LCMR_5YEAR_SCREEN_NOT_PASSED");
        gates.add("LCMR_CLASSIFICATION_NOT_AUTHENTICATED");
        gates.add("PLANT_AND_FUEL_INPUTS_NOT_AUTHENTICATED");
        gates.add("IMPORTS_AND_GRID_BOUNDARY_NOT_VERIFIED");
        gates.add("OPTION_A1_NCV_AND_CO2_FACTORS_NOT_VERIFIED");
        gates.add("VT0011_DATA_VINTAGE_AND_OTHER_METHOD_CHECKS_PENDING");
        gates.add("NO_CO2_TO_CO2E_HOURLY_BRIDGE");
        gates.add("E8_NORMATIVE_ADMISSION_NOT_GRANTED");
        return new Preview(RULE,input.dataYear(),input.shareApproach(),
            approach1.toPlainString(),approach2.toPlainString(),selected.toPlainString(),
            sharePassed,generation.toPlainString(),emissions.toPlainString(),
            om.toPlainString(),omKwh.toPlainString(),
            List.copyOf(breakdown),List.copyOf(gates),false);
    }

    private static boolean positive(BigDecimal v){return bounded(v)&&v.signum()>0;}
    private static boolean nonnegative(BigDecimal v){return bounded(v)&&v.signum()>=0;}
    private static boolean bounded(BigDecimal v) {
        return v!=null && v.signum()>=0 && v.precision()<=30 && v.scale()<=12
            && v.precision()-v.scale()<=18;
    }
    private OpenTool07SimpleOmMath(){}
}
