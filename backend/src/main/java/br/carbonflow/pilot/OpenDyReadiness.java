package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only readiness for the current assessment; never a normative admission. */
final class OpenDyReadiness {
    static final String POLICY="OPEN-DY-READINESS@1";
    record Facts(UUID assessmentId,int version,String assessmentSha256,boolean stale,
                 boolean conclusive,boolean arithmeticValid,boolean positiveTotals,
                 int issuanceCount,String coverageState,String allocationState,
                 String rightsState,String reviewOutcome,boolean exactReview,
                 boolean firstYearExploratoryAbsence,int sharedAllocationCount) {
        Facts(UUID assessmentId,int version,String assessmentSha256,boolean stale,
              boolean conclusive,boolean arithmeticValid,boolean positiveTotals,
              int issuanceCount,String coverageState,String allocationState,
              String rightsState,String reviewOutcome,boolean exactReview) {
            this(assessmentId,version,assessmentSha256,stale,conclusive,arithmeticValid,positiveTotals,
                issuanceCount,coverageState,allocationState,rightsState,reviewOutcome,exactReview,false,0);
        }
    }
    record Gate(String code,String status,String detail) {}
    record Report(String policyVersion,UUID assessmentId,int assessmentVersion,String assessmentSha256,
                  String state,boolean internalChecksComplete,boolean usableForE8,List<Gate> gates) {}
    private OpenDyReadiness() {}

    static Report evaluate(int year,String calendarGate,Facts facts) {
        var gates=new ArrayList<Gate>();
        boolean exists=facts!=null;
        boolean rightsBlocked=exists && "LICENSE_RECHECK_REQUIRED".equals(facts.rightsState());
        boolean firstYearAbsence=year==1 && exists && facts.firstYearExploratoryAbsence();
        gate(gates,"CALENDAR",firstYearAbsence || (year>1 && calendarGate==null),
            firstYearAbsence?"Primeiro ano: janela exploratória de 12 meses antes do início de creditação com ausência declarada (política interna D14); não é um ano anterior normativo."
                :"Janela anterior resolvida no calendário do projeto.",
            year==1?"Primeiro ano: só a ausência comprovada na janela exploratória é aceita internamente (política D14). Créditos encontrados ou pesquisa inconclusiva continuam bloqueados."
                :"Resolva a janela anterior do projeto e o recorte aplicável.");
        gate(gates,"ASSESSMENT",exists,"Avaliação vigente identificada.","Registre uma avaliação para este ano do projeto.");
        if (!exists) {
            for (String code:List.of("FRESHNESS","SOURCE_RIGHTS","ARITHMETIC","SEARCH_COVERAGE","ISSUANCE_INVENTORY","GEOGRAPHIC_ALLOCATION","INDEPENDENT_REVIEW"))
                gates.add(new Gate(code,"NOT_EVALUATED","Aguardando avaliação vigente."));
        } else {
            gate(gates,"FRESHNESS",!facts.stale(),"Contexto da avaliação vigente.","Avaliação requer rechecagem; registre nova versão.");
            gate(gates,"SOURCE_RIGHTS","LICENSE_DECISIONS_CURRENT".equals(facts.rightsState()),
                "Decisões de licença vinculadas estão vigentes.",
                rightsBlocked?"Direitos alterados, expirados ou revogados. Revalide em nova avaliação."
                    :"Vincule licenças vigentes às fontes da pesquisa e evidências.");
            if (rightsBlocked) {
                for (String code:List.of("ARITHMETIC","SEARCH_COVERAGE","ISSUANCE_INVENTORY","GEOGRAPHIC_ALLOCATION","INDEPENDENT_REVIEW"))
                    gates.add(new Gate(code,"NOT_EVALUATED","Rechecagem suspensa por direitos das fontes."));
            } else {
                gate(gates,"ARITHMETIC",facts.conclusive() && facts.arithmeticValid(),
                    "Prévia declaratória reproduzível pela equação 8.",
                    facts.conclusive()?"Totais ou prévia inconsistentes.":"Pesquisa inconclusiva: ERC, ERF e Dy não determinados.");
                boolean coverage="DECLARED_MATRIX_COMPLETE".equals(facts.coverageState());
                gate(gates,"SEARCH_COVERAGE",coverage,
                    "Matriz consistente para os programas declarados; não comprova exaustividade.",
                    "Conclua a matriz das duas categorias, janela e recorte nacional declarados.");
                if (!facts.conclusive() || !facts.arithmeticValid()) {
                    gates.add(new Gate("ISSUANCE_INVENTORY","NOT_EVALUATED","Aguardando totais declarados consistentes."));
                    gates.add(new Gate("GEOGRAPHIC_ALLOCATION","NOT_EVALUATED","Aguardando inventário conciliado."));
                } else if (!facts.positiveTotals() && facts.issuanceCount()==0 && coverage) {
                    gates.add(new Gate("ISSUANCE_INVENTORY","NOT_APPLICABLE","Totais declarados zero e buscas negativas estruturadas; não prova ausência."));
                    gates.add(new Gate("GEOGRAPHIC_ALLOCATION","NOT_APPLICABLE","Nenhuma emissão positiva declarada para atribuir."));
                } else {
                    gate(gates,"ISSUANCE_INVENTORY",facts.positiveTotals() && facts.issuanceCount()>0,
                        "Emissões individualizadas na avaliação.","Individualize e concilie as emissões; ausência de itens não prova zero.");
                    boolean shared=facts.sharedAllocationCount()>0;
                    gate(gates,"GEOGRAPHIC_ALLOCATION",facts.issuanceCount()>0 && !shared &&
                        "DECLARED_ALLOCATION_RECONCILED".equals(facts.allocationState()),
                        "Atribuição geográfica declarada conciliada; base ainda depende de validação.",
                        shared?"Rateio por EVs em circulação (política D14) depende do denominador nacional de EVR, ainda não admitido (G03)."
                            :"Fundamente a parcela BR de cada emissão positiva.");
                }
                gate(gates,"INDEPENDENT_REVIEW","CHECKED_INTERNAL".equals(facts.reviewOutcome()) && facts.exactReview(),
                    "Parecer interno vinculado ao hash exato; não é admissão normativa.",
                    "Obtenha checagem interna independente da versão vigente.");
            }
        }
        boolean internalComplete=gates.stream().allMatch(g->"SATISFIED_INTERNAL".equals(g.status()) || "NOT_APPLICABLE".equals(g.status()));
        // Permanent gates of this policy version. No UI or scalar pin can override them.
        gates.add(new Gate("NORMATIVE_WINDOW","BLOCKED","Política interna D14 adotada (leitura anual y−1; ano 1 pela janela exploratória com ausência comprovada); a interpretação normativa externa (anual versus acumulada, ano inicial) não foi confirmada."));
        gates.add(new Gate("EXTERNAL_EVIDENCE","BLOCKED","Comprove a exaustividade dos programas, registros oficiais e, quando aplicável, a adequação da atribuição geográfica."));
        gates.add(new Gate("E8_ADMISSION","BLOCKED","Ainda não existe admissão normativa da avaliação nem integração ao executor E8."));
        return new Report(POLICY,exists?facts.assessmentId():null,exists?facts.version():0,
            exists?facts.assessmentSha256():null,"BLOCKED",internalComplete,false,List.copyOf(gates));
    }

    private static void gate(List<Gate> gates,String code,boolean satisfied,String success,String failure) {
        gates.add(new Gate(code,satisfied?"SATISFIED_INTERNAL":"BLOCKED",satisfied?success:failure));
    }
}
