package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure *arithmetic illustration* of VT0011 v1.0 §5 para 86 Case 2 and
 * TOOL07 v7.0 Eq.16. This is NOT an operating-margin algorithm, not a
 * declaration that project-specific TOOL07 eligibility is met, and not a
 * CO2 -> CO2e conversion. The provided OM number is unverified.
 */
final class OpenVt0011Case2Math {
    static final String BASIS="VT0011-V1.0-P86-CASE2_TOOL07-V7.0-EQ16";
    static final BigDecimal OM_WEIGHT=BigDecimal.ONE;
    static final BigDecimal BM_WEIGHT=BigDecimal.ZERO;
    static final String ORIGINAL_UNIT="tCO2/MWh";
    static final String CONVERTED_UNIT="tCO2/kWh";
    static final int SCALE=18;

    record Preview(String reference,String caseCode,String formula,String omWeight,
                   String bmWeight,String omFactorTco2PerMwh,
                   String illustrativeCmTco2PerMwh,String illustrativeCmTco2PerKwh,
                   String originalUnit,String convertedUnit,boolean usableForE8,
                   String qualification) {}

    private OpenVt0011Case2Math() {}

    static Preview illustrate(BigDecimal unverifiedOmFactor) {
        if(unverifiedOmFactor==null || unverifiedOmFactor.signum()<0 ||
           unverifiedOmFactor.precision()>30 ||
           unverifiedOmFactor.scale()>12 ||
           unverifiedOmFactor.precision()-unverifiedOmFactor.scale()>18)
            throw new IllegalArgumentException("OM em tCO2/MWh inválido ou com precisão excessiva.");
        BigDecimal result=unverifiedOmFactor.multiply(OM_WEIGHT);
        BigDecimal converted=result.divide(new BigDecimal("1000"),SCALE,RoundingMode.HALF_EVEN);
        if(result.signum()>0 && converted.signum()==0)
            throw new IllegalArgumentException("Valor OM perderia precisão na normalização.");
        return new Preview(BASIS,"CASE_2_INCREASE_GRID_CONSUMPTION",
            "EFgrid,CM,y=EFgrid,OM,y*1.0+EFgrid,BM,y*0.0",
            "1.00","0.00",unverifiedOmFactor.toPlainString(),
            result.toPlainString(),converted.toPlainString(),
            ORIGINAL_UNIT,CONVERTED_UNIT,false,
            "Apenas aritmética do peso do Case 2. OM não verificada; BM não presumida zero. "+
            "Resultado em CO2 NÃO é fator CO2e horário admitido.");
    }
}
