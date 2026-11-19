package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure dimensional contracts for VM0038 open-network preparatory work.
 * Values are never inferred from US/EPA defaults, MPGe, or a different test cycle.
 */
final class VehicleRatingsMath {
    static final int SCALE=12;
    static final BigDecimal MILE_KM=new BigDecimal("1.609344");
    static final BigDecimal KM_100_MILES=new BigDecimal("160.9344");
    static final BigDecimal US_GALLON_L=new BigDecimal("3.785411784");
    static final BigDecimal MJ_PER_KWH=new BigDecimal("3.6");

    record Converted(BigDecimal value,String unit,String conversion) {}
    record WeightedRow(UUID modelId,long evr,BigDecimal afec,BigDecimal mpg,
                       String cycle,String fuel) {}
    record Composition(Long evrTotal,BigDecimal afecKwhPer100mi,BigDecimal mpgUs,
                       String cycle,List<String> issues,boolean usableForE8){
        boolean valid(){return issues.isEmpty();}
    }
    private VehicleRatingsMath(){}

    static Converted convert(String role,String originalUnit,BigDecimal value){
        if(value==null || value.signum()<=0 || value.precision()>30 || value.scale()>12)
            throw bad("Rating precisa ser um decimal positivo, com unidade original explícita.");
        BigDecimal normalized;
        String target;
        String formula;
        if("BEV".equals(role)){
            target="kWh/100mi";
            switch(originalUnit){
                case "kWh/100mi" -> {normalized=value;formula="IDENTITY";}
                case "kWh/100km" -> {
                    normalized=value.multiply(MILE_KM);formula="KWH_100KM_TO_100MI";
                }
                case "kWh/km" -> {
                    normalized=value.multiply(KM_100_MILES);formula="KWH_KM_TO_100MI";
                }
                case "MJ/km" -> {
                    normalized=value.multiply(KM_100_MILES).divide(MJ_PER_KWH,24,
                        RoundingMode.HALF_EVEN);formula="MJ_KM_TO_KWH_100MI";
                }
                default -> throw bad("Unidade elétrica incompatível; MPGe e ciclo não se convertem implicitamente.");
            }
        }else if("FOSSIL".equals(role)){
            target="mi/USgal";
            switch(originalUnit){
                case "mi/USgal" -> {normalized=value;formula="IDENTITY_US_GALLON";}
                case "km/L" -> {
                    normalized=value.multiply(US_GALLON_L).divide(MILE_KM,24,
                        RoundingMode.HALF_EVEN);formula="KM_L_TO_US_MPG";
                }
                default -> throw bad("Somente km/L ou mi/USgal; galão imperial e energia equivalente não são MPG.");
            }
        }else throw bad("Papel BEV ou FOSSIL obrigatório.");
        normalized=normalized.setScale(SCALE,RoundingMode.HALF_EVEN);
        if(normalized.signum()<=0 || normalized.precision()>30)
            throw bad("Valor normalizado excede a precisão suportada.");
        return new Converted(normalized,target,formula);
    }

    static Composition compose(List<WeightedRow> rows) {
        var issues=new ArrayList<String>();
        if(rows==null || rows.isEmpty())return new Composition(null,null,null,null,
            List.of("Nenhum par BEV/fóssil revisado; ausência não é zero."),false);
        long total=0;
        BigDecimal energy=BigDecimal.ZERO,fuel=BigDecimal.ZERO;
        String cycle=null;
        var seen=new HashSet<UUID>();
        for(var row:rows){
            if(row==null || row.modelId()==null || !seen.add(row.modelId())){
                issues.add("Modelo canônico ausente ou duplicado na composição.");continue;
            }
            if(row.evr()<=0){issues.add("EVR por modelo precisa ser positivo; não inferir denominador.");continue;}
            if(row.afec()==null || row.mpg()==null || row.afec().signum()<=0 || row.mpg().signum()<=0){
                issues.add("Par sem dois ratings positivos e revistos.");continue;
            }
            if(!Set.of("URBAN","HIGHWAY","COMBINED").contains(row.cycle())){
                issues.add("Ciclo de ensaio inválido.");continue;
            }
            if(cycle==null)cycle=row.cycle();
            if(!cycle.equals(row.cycle()))issues.add("Mistura de ciclos de ensaio é proibida.");
            if(!"GASOLINE".equals(row.fuel()))
                issues.add("Combustíveis não homogêneos exigem rota de fatores própria; não agregar MPG automaticamente.");
            try{
                total=Math.addExact(total,row.evr());
                energy=energy.add(row.afec().multiply(BigDecimal.valueOf(row.evr())));
                fuel=fuel.add(row.mpg().multiply(BigDecimal.valueOf(row.evr())));
            }catch(ArithmeticException ex){issues.add("Soma EVR excede limite numérico.");}
        }
        if(total<=0)issues.add("Denominador EVR ausente ou zero.");
        if(!issues.isEmpty())return new Composition(null,null,null,cycle,List.copyOf(issues),false);
        BigDecimal div=BigDecimal.valueOf(total);
        return new Composition(total,energy.divide(div,SCALE,RoundingMode.HALF_EVEN),
            fuel.divide(div,SCALE,RoundingMode.HALF_EVEN),cycle,List.of(),false);
    }

    private static IllegalArgumentException bad(String reason){return new IllegalArgumentException(reason);}
}
