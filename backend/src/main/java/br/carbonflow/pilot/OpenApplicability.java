package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Project-level preliminary applicability. A successful write only records declarations:
 * it cannot admit normative rules, auto-approve carbon title or release BE/PE/ER.
 */
@ApplicationScoped
class OpenApplicability {
    static final String RULE="CF-OPEN-004";
    record Input(Boolean unrestrictedBevAccess,Boolean restrictedToIdentifiedVehicles,
        Boolean privateLand,String chargerType,String propulsion,String electricitySource,
        Boolean associatedInfrastructure,Boolean v2g,String chargingOwner,
        String carbonRightsContractRef,String driverDisclosureChannel,String driverDisclosureEvidenceRef,
        LocalDate driverDisclosureEffectiveOn,String doubleClaimingCheck,LocalDate effectiveFrom,
        String explanation) {}
    record Revision(int version,int triageVersion,UUID authorId,Input answers,OffsetDateTime recordedAt) {}
    record Finding(String code,String state,String text) {}
    record Readiness(String category,String network,String rights,String norm,
        boolean canContinuePreparation,boolean canUseForE8,boolean stale,List<Finding> findings,
        List<String> sourceDependencies) {}
    record View(String projectId,int version,int latestTriageVersion,Revision latest,Readiness readiness) {}
    private record SavedCommand(String project,int revision,byte[] fingerprint) {}

    private final DynamicProjectScope scope;
    OpenApplicability(DynamicProjectScope scope){this.scope=scope;}

    View get(String token,String project) {
        return scope.read(token,project,"VIEW_OPEN_APPLICABILITY",
            (connection,context)->build(connection,context.id()));
    }

    View save(String token,String project,String ifMatch,String key,Input received) {
        return scope.write(token,project,"SAVE_OPEN_APPLICABILITY",(connection,context)->{
            Input input=normalize(received);
            String command=PilotCommands.key(key);
            int expected=PilotCommands.expected(ifMatch);
            byte[] fingerprint=AccessSecrets.digest(context.id()+"|"+input);
            SavedCommand prev=first(connection, """
                SELECT project_id,revision,fingerprint FROM app_project_open_applicability_command
                WHERE actor_id=? AND command_key=?
                """,r->new SavedCommand(r.getString(1),r.getInt(2),r.getBytes(3)),
                context.actor().id(),command);
            if(prev!=null) {
                if(!prev.project().equals(project) ||
                        !java.security.MessageDigest.isEqual(prev.fingerprint(),fingerprint))
                    throw new PilotProblem(409,"CHAVE_REUTILIZADA","Idempotency-Key",RULE,
                        "Esta chave identifica outra alteração.");
                return build(connection,project);
            }

            View old=build(connection,project);
            if(expected!=old.version())
                throw new PilotProblem(412,"VERSAO_DIVERGENTE","If-Match",RULE,
                    "O registro mudou. Confira a versão antes de salvar.");
            int next=old.version()+1;
            update(connection, """
                INSERT INTO app_project_open_applicability_revision
                  (project_id,organization_id,environment,version,triage_version,author_id,
                   unrestricted_bev_access,restricted_to_identified_vehicles,private_land,
                   charger_type,propulsion,electricity_source,associated_infrastructure,v2g,
                   charging_owner,carbon_rights_contract_ref,driver_disclosure_channel,
                   driver_disclosure_evidence_ref,driver_disclosure_effective_on,
                   double_claiming_check,effective_from,explanation)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,context.id(),context.organization(),context.environment(),next,old.latestTriageVersion(),
                context.actor().id(),input.unrestrictedBevAccess(),input.restrictedToIdentifiedVehicles(),
                input.privateLand(),input.chargerType(),input.propulsion(),input.electricitySource(),
                input.associatedInfrastructure(),input.v2g(),input.chargingOwner(),
                input.carbonRightsContractRef(),input.driverDisclosureChannel(),input.driverDisclosureEvidenceRef(),
                input.driverDisclosureEffectiveOn(),input.doubleClaimingCheck(),input.effectiveFrom(),input.explanation());
            update(connection, """
                INSERT INTO app_project_open_applicability_command(actor_id,command_key,project_id,fingerprint,revision)
                VALUES (?,?,?,?,?)
                """,context.actor().id(),command,project,fingerprint,next);
            return build(connection,project);
        });
    }

    private static View build(Connection c,String project)throws SQLException {
        int triage=first(c, """
            SELECT COALESCE(MAX(version),0) FROM app_project_triage_revision WHERE project_id=?
            """,r->r.getInt(1),project);
        Revision revision=first(c, """
            SELECT version,triage_version,author_id,unrestricted_bev_access,
                   restricted_to_identified_vehicles,private_land,charger_type,propulsion,
                   electricity_source,associated_infrastructure,v2g,charging_owner,
                   carbon_rights_contract_ref,driver_disclosure_channel,
                   driver_disclosure_evidence_ref,driver_disclosure_effective_on,
                   double_claiming_check,effective_from,explanation,recorded_at
            FROM app_project_open_applicability_revision WHERE project_id=?
            ORDER BY version DESC LIMIT 1
            """,r->new Revision(r.getInt(1),r.getInt(2),r.getObject(3,UUID.class),
                new Input((Boolean)r.getObject(4),(Boolean)r.getObject(5),
                    (Boolean)r.getObject(6),r.getString(7),r.getString(8),r.getString(9),
                    (Boolean)r.getObject(10),(Boolean)r.getObject(11),r.getString(12),
                    r.getString(13),r.getString(14),r.getString(15),
                    r.getObject(16,LocalDate.class),r.getString(17),
                    r.getObject(18,LocalDate.class),r.getString(19)),
                r.getObject(20,OffsetDateTime.class)),project);
        Readiness ready=evaluate(revision==null?null:revision.answers(),
            revision!=null && revision.triageVersion()!=triage);
        return new View(project,revision==null?0:revision.version(),triage,revision,ready);
    }

    static Readiness evaluate(Input data,boolean stale) {
        var findings=new ArrayList<Finding>();
        String network="PENDING_CONFIGURATION";
        String category="OPEN_LDV_DCFC_SIN_BR_CANDIDATE";
        if(data==null) {
            findings.add(new Finding("NO_DECLARATION","PENDING",
                "A preparação de rede aberta ainda não foi preenchida."));
        } else {
            if(Boolean.TRUE.equals(data.restrictedToIdentifiedVehicles()) ||
                Boolean.FALSE.equals(data.unrestrictedBevAccess())) {
                network="CLOSED_RESTRICTION";
                findings.add(new Finding("CLOSED_NETWORK","OUTSIDE_FIRST_SCOPE",
                    "Restrição a uma frota identificada requer outro modelo de linha de base."));
            } else if(Boolean.TRUE.equals(data.unrestrictedBevAccess()) &&
                    Boolean.FALSE.equals(data.restrictedToIdentifiedVehicles())) {
                network="OPEN_CANDIDATE";
                // Land ownership and paid access are not proxies for network restriction.
            } else {
                findings.add(new Finding("ACCESS_UNDETERMINED","PENDING",
                    "Declare se qualquer BEV compatível pode acessar e se há restrições à frota."));
            }
            boolean outside = Set.of("AC","MIXED").contains(Objects.toString(data.chargerType(),""))
                    || Set.of("PHEV","MIXED").contains(Objects.toString(data.propulsion(),""))
                    || Set.of("OTHER_GRID","ON_SITE","MIXED").contains(Objects.toString(data.electricitySource(),""))
                    || Boolean.TRUE.equals(data.associatedInfrastructure())
                    || Boolean.TRUE.equals(data.v2g());
            boolean incomplete = !"DCFC".equals(data.chargerType())
                    || !"BEV".equals(data.propulsion())
                    || !"SIN".equals(data.electricitySource())
                    || data.associatedInfrastructure()==null || data.v2g()==null;
            if(outside) {
                category="OUTSIDE_FIRST_IMPLEMENTED_SCOPE";
                findings.add(new Finding("CONFIGURATION_BRANCH","OUTSIDE_FIRST_SCOPE",
                    "Primeira fatia cobre LDV BEV, DCFC, somente SIN, sem geração, bateria ou V2G."));
            } else if(incomplete) {
                category="CONFIGURATION_PENDING";
                findings.add(new Finding("CONFIGURATION_PENDING","PENDING",
                    "Complete tecnologia, propulsão, rede elétrica e infraestrutura antes de classificar o recorte."));
            }
            if(data.chargingOwner()==null || data.carbonRightsContractRef()==null) {
                findings.add(new Finding("OWNER_CONTRACT","NEEDS_EVIDENCE",
                    "Registre titular do sistema e referência verificável do contrato de direitos."));
            }
            if(data.driverDisclosureChannel()==null ||
                "NOT_DECLARED".equals(data.driverDisclosureChannel()) ||
                data.driverDisclosureEvidenceRef()==null ||
                data.driverDisclosureEffectiveOn()==null) {
                findings.add(new Finding("DRIVER_DISCLOSURE","NEEDS_EVIDENCE",
                    "É necessária prova datada de divulgação aos motoristas em rede aberta."));
            }
            if(data.effectiveFrom()!=null && data.driverDisclosureEffectiveOn()!=null &&
                    data.driverDisclosureEffectiveOn().isAfter(data.effectiveFrom())) {
                findings.add(new Finding("RIGHTS_DATE_GAP","NEEDS_REVIEW",
                    "A divulgação ocorreu depois da data de início declarada; revisar período e efeitos."));
            }
            if(!"NO_OVERLAP_DECLARED".equals(data.doubleClaimingCheck())) {
                findings.add(new Finding("DOUBLE_CLAIMING","NEEDS_REVIEW",
                    "Conferir sobreposição de direitos e outros programas, não presumir exclusividade."));
            }
        }
        findings.add(new Finding("VMD0049_ROUTE","PENDING_NORMATIVE_SOURCE",
            "Rota de lista positiva é hipótese separada; selecionar edição vigente e verificar Brasil/tecnologia."));
        findings.add(new Finding("MARKET_ROUTE","PENDING_OFFICIAL_DATA",
            "Rota alternativa exige dados nacionais oficiais recentes na validação; ausência não equivale a zero."));
        findings.add(new Finding("REGULATORY_SURPLUS","PENDING_LEGAL_REVIEW",
            "Excedente regulatório e cronologia exigem análise separada; não decorrentes de lista positiva."));
        findings.add(new Finding("ED_EC","PENDING_MEASUREMENT",
            "ED e EC exigem instrumentos e registros distintos; nenhum valor é simulado."));
        if(stale) findings.add(0,new Finding("TRIAGE_CHANGED","STALE",
            "A triagem foi revisada depois desta declaração. Reconfirme o recorte antes de análise."));
        String rights=findings.stream().anyMatch(f->Set.of("OWNER_CONTRACT","DRIVER_DISCLOSURE",
            "RIGHTS_DATE_GAP","DOUBLE_CLAIMING").contains(f.code()))?"EVIDENCE_PENDING":"DECLARED_REVIEW_PENDING";
        return new Readiness(category,network,rights,"PENDING_NORMATIVE_REVIEW",
            true,false,stale,List.copyOf(findings),
            List.of("OPEN-METHOD","OPEN-POSITIVE","OPEN-EV-PENETRATION","OPEN-EV-SALES",
                    "OPEN-RIGHTS","OPEN-ED-EC"));
    }

    private static Input normalize(Input i) {
        if(i==null)throw invalid("answers");
        if(i.chargerType()!=null && !Set.of("DCFC","AC","MIXED","UNKNOWN").contains(i.chargerType()))
            throw invalid("chargerType");
        if(i.propulsion()!=null && !Set.of("BEV","PHEV","MIXED","UNKNOWN").contains(i.propulsion()))
            throw invalid("propulsion");
        if(i.electricitySource()!=null && !Set.of("SIN","OTHER_GRID","ON_SITE","MIXED","UNKNOWN").contains(i.electricitySource()))
            throw invalid("electricitySource");
        if(i.driverDisclosureChannel()!=null && !Set.of("ON_CHARGER","APP_OR_WEBSITE",
            "TERMS_OF_SERVICE","NOT_DECLARED").contains(i.driverDisclosureChannel()))
            throw invalid("driverDisclosureChannel");
        if(i.doubleClaimingCheck()!=null && !Set.of("UNKNOWN","REVIEW_REQUIRED",
            "POTENTIAL_OVERLAP","NO_OVERLAP_DECLARED").contains(i.doubleClaimingCheck()))
            throw invalid("doubleClaimingCheck");
        String owner=clean(i.chargingOwner(),200),contract=clean(i.carbonRightsContractRef(),300),
            disclosure=clean(i.driverDisclosureEvidenceRef(),300),note=clean(i.explanation(),1500);
        if(i.driverDisclosureEffectiveOn()!=null && i.driverDisclosureEffectiveOn().isAfter(LocalDate.now().plusYears(20)))
            throw invalid("driverDisclosureEffectiveOn");
        return new Input(i.unrestrictedBevAccess(),i.restrictedToIdentifiedVehicles(),i.privateLand(),
            i.chargerType(),i.propulsion(),i.electricitySource(),i.associatedInfrastructure(),i.v2g(),
            owner,contract,i.driverDisclosureChannel(),disclosure,i.driverDisclosureEffectiveOn(),
            i.doubleClaimingCheck(),i.effectiveFrom(),note==null?"":note);
    }
    private static String clean(String value,int max) {
        if(value==null||value.isBlank())return null;
        String s=value.strip();
        if(s.length()>max || s.chars().anyMatch(Character::isISOControl))throw invalid("text");
        return s;
    }
    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("APPLICABILITY_INVALIDA",field,RULE,
            "Confira a declaração e as referências documentais.");
    }
}
