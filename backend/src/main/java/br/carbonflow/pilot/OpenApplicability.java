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
        String explanation,String rightsPartyCode,String rightsContractCode,
        String operationalEventCode,LocalDate operationalEventOn,String vcsEventCode,LocalDate vcsEventOn,
        String contractFileId,String disclosureFileId) {
        Input(Boolean unrestrictedBevAccess,Boolean restrictedToIdentifiedVehicles,Boolean privateLand,
            String chargerType,String propulsion,String electricitySource,Boolean associatedInfrastructure,
            Boolean v2g,String chargingOwner,String carbonRightsContractRef,String driverDisclosureChannel,
            String driverDisclosureEvidenceRef,LocalDate driverDisclosureEffectiveOn,String doubleClaimingCheck,
            LocalDate effectiveFrom,String explanation,String rightsPartyCode,String rightsContractCode,
            String operationalEventCode,LocalDate operationalEventOn,String vcsEventCode,LocalDate vcsEventOn) {
            this(unrestrictedBevAccess,restrictedToIdentifiedVehicles,privateLand,chargerType,propulsion,
                electricitySource,associatedInfrastructure,v2g,chargingOwner,carbonRightsContractRef,
                driverDisclosureChannel,driverDisclosureEvidenceRef,driverDisclosureEffectiveOn,
                doubleClaimingCheck,effectiveFrom,explanation,rightsPartyCode,rightsContractCode,
                operationalEventCode,operationalEventOn,vcsEventCode,vcsEventOn,null,null);
        }
        /** A typed name without a project record is the same as an empty holder. */
        Input(Boolean unrestrictedBevAccess,Boolean restrictedToIdentifiedVehicles,Boolean privateLand,
            String chargerType,String propulsion,String electricitySource,Boolean associatedInfrastructure,
            Boolean v2g,String chargingOwner,String carbonRightsContractRef,String driverDisclosureChannel,
            String driverDisclosureEvidenceRef,LocalDate driverDisclosureEffectiveOn,String doubleClaimingCheck,
            LocalDate effectiveFrom,String explanation) {
            this(unrestrictedBevAccess,restrictedToIdentifiedVehicles,privateLand,chargerType,propulsion,
                electricitySource,associatedInfrastructure,v2g,chargingOwner,carbonRightsContractRef,
                driverDisclosureChannel,driverDisclosureEvidenceRef,driverDisclosureEffectiveOn,
                doubleClaimingCheck,effectiveFrom,explanation,null,null,null,null,null,null);
        }
        Input(Boolean unrestrictedBevAccess,Boolean restrictedToIdentifiedVehicles,Boolean privateLand,
            String chargerType,String propulsion,String electricitySource,Boolean associatedInfrastructure,
            Boolean v2g,String chargingOwner,String carbonRightsContractRef,String driverDisclosureChannel,
            String driverDisclosureEvidenceRef,LocalDate driverDisclosureEffectiveOn,String doubleClaimingCheck,
            LocalDate effectiveFrom,String explanation,String rightsPartyCode,String rightsContractCode) {
            this(unrestrictedBevAccess,restrictedToIdentifiedVehicles,privateLand,chargerType,propulsion,
                electricitySource,associatedInfrastructure,v2g,chargingOwner,carbonRightsContractRef,
                driverDisclosureChannel,driverDisclosureEvidenceRef,driverDisclosureEffectiveOn,
                doubleClaimingCheck,effectiveFrom,explanation,rightsPartyCode,rightsContractCode,
                null,null,null,null);
        }
    }
    record Revision(int version,int triageVersion,UUID authorId,Input answers,OffsetDateTime recordedAt) {}
    record Finding(String code,String state,String text) {}
    record Readiness(String category,String network,String rights,String norm,
        boolean canContinuePreparation,boolean canUseForE8,boolean stale,List<Finding> findings,
        List<String> sourceDependencies) {}
    record Origin(String field,String version,String situation,String rule,String role,boolean concludes) {}
    record PdField(String key,String section,String gap,boolean copiedIntoDocument,boolean marksReady) {}
    record View(String projectId,int version,int latestTriageVersion,Revision latest,Readiness readiness,
        List<Origin> origins,List<PdField> pdFields) {}
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
            String owner=input.chargingOwner(),contract=input.carbonRightsContractRef();
            if(input.rightsPartyCode()!=null) {
                if(!OpenProjectRights.linked(connection,context.id(),
                        input.rightsPartyCode(),input.rightsContractCode()))
                    throw invalid("rightsContractCode");
                owner=OpenProjectRights.holderName(connection,context.id(),input.rightsPartyCode());
                contract=input.rightsContractCode();
            }
            OpenProjectRights.Event operational=dated(connection,context.id(),
                input.operationalEventCode(),"OPERATIONAL_START");
            OpenProjectRights.Event vcs=dated(connection,context.id(),input.vcsEventCode(),"VCS_START");
            int next=old.version()+1;
            update(connection, """
                INSERT INTO app_project_open_applicability_revision
                  (project_id,organization_id,environment,version,triage_version,author_id,
                   unrestricted_bev_access,restricted_to_identified_vehicles,private_land,
                   charger_type,propulsion,electricity_source,associated_infrastructure,v2g,
                   charging_owner,carbon_rights_contract_ref,driver_disclosure_channel,
                   driver_disclosure_evidence_ref,driver_disclosure_effective_on,
                   double_claiming_check,effective_from,explanation,
                   rights_party_code,rights_contract_code,
                   operational_event_code,operational_event_on,vcs_event_code,vcs_event_on,
                   contract_file_id,disclosure_file_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,context.id(),context.organization(),context.environment(),next,old.latestTriageVersion(),
                context.actor().id(),input.unrestrictedBevAccess(),input.restrictedToIdentifiedVehicles(),
                input.privateLand(),input.chargerType(),input.propulsion(),input.electricitySource(),
                input.associatedInfrastructure(),input.v2g(),owner,
                contract,input.driverDisclosureChannel(),input.driverDisclosureEvidenceRef(),
                input.driverDisclosureEffectiveOn(),input.doubleClaimingCheck(),input.effectiveFrom(),
                input.explanation(),input.rightsPartyCode(),input.rightsContractCode(),
                operational==null?null:operational.code(),operational==null?null:operational.eventOn(),
                vcs==null?null:vcs.code(),vcs==null?null:vcs.eventOn(),
                OpenProjectRights.acceptedFile(connection,context.id(),input.contractFileId(),
                    "CONTRACT",input.rightsContractCode()),
                OpenProjectRights.acceptedFile(connection,context.id(),input.disclosureFileId(),
                    "DISCLOSURE","DECLARATION"));
            update(connection, """
                INSERT INTO app_project_open_applicability_command(actor_id,command_key,project_id,fingerprint,revision)
                VALUES (?,?,?,?,?)
                """,context.actor().id(),command,project,fingerprint,next);
            return build(connection,project);
        });
    }

    /** Readiness already derived by the applicability owner. No second ledger. */
    static Readiness currentReadiness(Connection c,String project)throws SQLException {
        return build(c,project).readiness();
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
                   double_claiming_check,effective_from,explanation,recorded_at,
                   rights_party_code,rights_contract_code,
                   operational_event_code,operational_event_on,vcs_event_code,vcs_event_on,
                   contract_file_id::text,disclosure_file_id::text
            FROM app_project_open_applicability_revision WHERE project_id=?
            ORDER BY version DESC LIMIT 1
            """,r->new Revision(r.getInt(1),r.getInt(2),r.getObject(3,UUID.class),
                new Input((Boolean)r.getObject(4),(Boolean)r.getObject(5),
                    (Boolean)r.getObject(6),r.getString(7),r.getString(8),r.getString(9),
                    (Boolean)r.getObject(10),(Boolean)r.getObject(11),r.getString(12),
                    r.getString(13),r.getString(14),r.getString(15),
                    r.getObject(16,LocalDate.class),r.getString(17),
                    r.getObject(18,LocalDate.class),r.getString(19),
                    r.getString(21),r.getString(22),
                    r.getString(23),r.getObject(24,LocalDate.class),
                    r.getString(25),r.getObject(26,LocalDate.class),
                    r.getString(27),r.getString(28)),
                r.getObject(20,OffsetDateTime.class)),project);
        Readiness ready=evaluate(revision==null?null:revision.answers(),
            revision!=null && revision.triageVersion()!=triage);
        return new View(project,revision==null?0:revision.version(),triage,revision,ready,
            origins(c,project,revision==null?null:revision.answers()),pdMap());
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
            if(data.rightsPartyCode()==null || data.rightsContractCode()==null) {
                findings.add(new Finding("OWNER_CONTRACT","NEEDS_EVIDENCE",
                    "Registre o titular e o contrato neste projeto. "
                        + "Um nome digitado não cria o registro, não cria PP jurídico e não concede poder de representante."));
            } else if(data.contractFileId()==null) {
                findings.add(new Finding("OWNER_CONTRACT","NEEDS_EVIDENCE",
                    "Titular e contrato foram registrados, mas falta o arquivo. "
                        + "O registro não é prova admitida, não cria PP jurídico e não concede poder de representante."));
            } else {
                findings.add(new Finding("OWNER_CONTRACT","ATTACHED_NOT_ADMITTED",
                    "Arquivo do contrato recebido, aguardando revisão. "
                        + "Essa referência não é prova admitida, não cria PP jurídico e não concede poder de representante."));
            }
            boolean disclosureDeclared=data.driverDisclosureChannel()!=null
                && !"NOT_DECLARED".equals(data.driverDisclosureChannel())
                && data.driverDisclosureEvidenceRef()!=null
                && data.driverDisclosureEffectiveOn()!=null;
            if(!disclosureDeclared) {
                findings.add(new Finding("DRIVER_DISCLOSURE","NEEDS_EVIDENCE",
                    "É necessária prova datada de divulgação aos motoristas em rede aberta."));
            } else if(data.disclosureFileId()==null) {
                findings.add(new Finding("DRIVER_DISCLOSURE","NEEDS_EVIDENCE",
                    "Canal e data foram informados, mas falta o arquivo. "
                        + "Uma referência digitada não é o arquivo e não comprova a divulgação."));
            } else {
                findings.add(new Finding("DRIVER_DISCLOSURE","ATTACHED_NOT_ADMITTED",
                    "Arquivo da divulgação recebido, aguardando revisão. "
                        + "A referência não comprova a divulgação e não aprova direitos."));
            }
            LocalDate disclosed=data.driverDisclosureEffectiveOn();
            boolean afterOperational=disclosed!=null && data.operationalEventOn()!=null
                && disclosed.isAfter(data.operationalEventOn());
            boolean afterVcs=disclosed!=null && data.vcsEventOn()!=null
                && disclosed.isAfter(data.vcsEventOn());
            boolean legacy=disclosed!=null && data.operationalEventOn()==null && data.vcsEventOn()==null
                && data.effectiveFrom()!=null && disclosed.isAfter(data.effectiveFrom());
            if(afterOperational || afterVcs || legacy) {
                String which=afterOperational && afterVcs
                    ? "ao início operacional e ao início de crédito VCS"
                    : afterVcs ? "ao início de crédito VCS"
                    : afterOperational ? "ao início operacional"
                    : "à data de início declarada antes destes dois eventos";
                findings.add(new Finding("RIGHTS_DATE_GAP","NEEDS_REVIEW",
                    "A divulgação é posterior "+which+". "
                        + "A revisão continua pendente. Essa data não reprova o projeto."));
            }
            if(!"NO_OVERLAP_DECLARED".equals(data.doubleClaimingCheck())) {
                findings.add(new Finding("DOUBLE_CLAIMING","NEEDS_REVIEW",
                    "Conferir sobreposição de direitos e outros programas, não presumir exclusividade."));
            }
            if("OPEN_CANDIDATE".equals(network) && !outside && !incomplete) {
                // §4 market test, §8 positive list and regulatory surplus are distinct.
                // A saved declaration never concludes any of them.
                findings.add(new Finding("MARKET_ROUTE","PENDING_OFFICIAL_DATA",
                    "Teste de aplicabilidade de mercado da VM0038 §4 para rede aberta. "
                        + "Não é rota de adicionalidade. Dado oficial ausente não vale zero e não reprova o projeto. "
                        + "A declaração não conclui este teste."));
                findings.add(new Finding("VMD0049_ROUTE","PENDING_NORMATIVE_SOURCE",
                    "Lista positiva VMD0049 é hipótese de adicionalidade, distinta do teste de mercado e do excedente regulatório. "
                        + "Presença na tabela da edição não aprova a adicionalidade."));
                findings.add(new Finding("REGULATORY_SURPLUS","PENDING_LEGAL_REVIEW",
                    "Excedente regulatório exige parecer humano, separado da lista positiva. "
                        + "Preencher a declaração não aprova este teste."));
                findings.add(new Finding("ED_EC","PENDING_MEASUREMENT",
                    "ED e EC exigem instrumentos e registros distintos; nenhum valor é simulado. "
                        + "A falta deles não bloqueia o documento ex ante."));
            }
        }
        if(stale) findings.add(0,new Finding("TRIAGE_CHANGED","STALE",
            "A triagem foi revisada depois desta declaração. Reconfirme o recorte antes de análise."));
        boolean rightsMissing=findings.stream().anyMatch(f->
            Set.of("OWNER_CONTRACT","DRIVER_DISCLOSURE").contains(f.code())
                && "NEEDS_EVIDENCE".equals(f.state()));
        String rights=rightsMissing?"EVIDENCE_PENDING":"DECLARED_REVIEW_PENDING";
        return new Readiness(category,network,rights,"PENDING_NORMATIVE_REVIEW",
            true,false,stale,List.copyOf(findings),
            List.of("OPEN-METHOD","OPEN-POSITIVE","OPEN-EV-PENETRATION","OPEN-EV-SALES",
                    "OPEN-RIGHTS","OPEN-ED-EC"));
    }

    static List<PdField> pdMap() {
        String gap="O arquivo ou a data segue fora do documento congelado. "
            + "A resposta tipada não é copiada e esta prévia não marca o PD como pronto.";
        return List.of(
            new PdField("rightsParty","Titularidade","Falta admissão do titular. "+gap,false,false),
            new PdField("rightsContract","Titularidade","Falta admissão do contrato. "+gap,false,false),
            new PdField("contractFile","Titularidade","O arquivo do contrato aguarda revisão. "+gap,false,false),
            new PdField("disclosureFile","Divulgação aos motoristas","O arquivo da divulgação aguarda revisão. "+gap,false,false),
            new PdField("operationalStart","Início operacional","A data operacional não está comprovada. "+gap,false,false),
            new PdField("vcsStart","Início de crédito","A data de início VCS não está comprovada. "+gap,false,false));
    }

    static Origin described(String field, Integer version, String situation, String rule) {
        return new Origin(field, version==null ? "Ainda não registrado" : "v"+version,
            situation, rule,
            "Revisão interna. Este papel não designa uma pessoa nem cria permissão.", false);
    }

    private static List<Origin> origins(Connection c, String project, Input data) throws SQLException {
        if(data==null) return List.of();
        Integer party=version(c,"app_project_rights_party","party_code",project,data.rightsPartyCode());
        Integer contract=version(c,"app_project_rights_contract","contract_code",project,data.rightsContractCode());
        Integer operational=version(c,"app_project_rights_event","event_code",project,data.operationalEventCode());
        Integer vcs=version(c,"app_project_rights_event","event_code",project,data.vcsEventCode());
        return List.of(
            described("rightsParty", party, party==null
                ? "Ainda não informado" : "Registro do projeto, não admitido", "CF-IMP-013"),
            described("rightsContract", contract, data.contractFileId()==null
                ? "Falta o arquivo" : "Arquivo recebido, aguardando revisão", "CF-IMP-013"),
            described("contractFile", data.contractFileId()==null?null:1,
                data.contractFileId()==null ? "Ainda não informado" : "Arquivo recebido, aguardando revisão",
                "ECA-005"),
            described("disclosureFile", data.disclosureFileId()==null?null:1,
                data.disclosureFileId()==null ? "Ainda não informado" : "Arquivo recebido, aguardando revisão",
                "ECA-005"),
            described("operationalStart", operational, operational==null
                ? "Ainda não informado" : "Evento registrado, data não comprovada", "CF-OPEN-010"),
            described("vcsStart", vcs, vcs==null
                ? "Ainda não informado" : "Evento registrado, data não comprovada", "CF-OPEN-010"));
    }

    private static Integer version(Connection c, String table, String column, String project, String code)
            throws SQLException {
        if(code==null || !table.matches("app_project_rights_(party|contract|event)")) return null;
        return first(c,"SELECT max(version) FROM "+table+" WHERE project_id=? AND "+column+"=?",
            r->(Integer)r.getObject(1), project, code);
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
        String party=i.rightsPartyCode()==null?null:OpenProjectRights.code(i.rightsPartyCode(),"rightsPartyCode");
        String rightsContract=i.rightsContractCode()==null?null:OpenProjectRights.code(i.rightsContractCode(),"rightsContractCode");
        if((party==null)!=(rightsContract==null)) throw invalid("rightsContractCode");
        String operational=i.operationalEventCode()==null?null:OpenProjectRights.code(i.operationalEventCode(),"operationalEventCode");
        String vcs=i.vcsEventCode()==null?null:OpenProjectRights.code(i.vcsEventCode(),"vcsEventCode");
        if(i.driverDisclosureEffectiveOn()!=null && i.driverDisclosureEffectiveOn().isAfter(LocalDate.now().plusYears(20)))
            throw invalid("driverDisclosureEffectiveOn");
        return new Input(i.unrestrictedBevAccess(),i.restrictedToIdentifiedVehicles(),i.privateLand(),
            i.chargerType(),i.propulsion(),i.electricitySource(),i.associatedInfrastructure(),i.v2g(),
            owner,contract,i.driverDisclosureChannel(),disclosure,i.driverDisclosureEffectiveOn(),
            i.doubleClaimingCheck(),i.effectiveFrom(),note==null?"":note,party,rightsContract,
            operational,null,vcs,null,fileId(i.contractFileId(),"contractFileId"),
            fileId(i.disclosureFileId(),"disclosureFileId"));
    }
    private static OpenProjectRights.Event dated(Connection connection,String project,String code,String kind)
            throws java.sql.SQLException {
        if(code==null) return null;
        OpenProjectRights.Event event=OpenProjectRights.latestEvent(connection,project,code);
        if(event==null || !kind.equals(event.kind()))
            throw invalid("OPERATIONAL_START".equals(kind)?"operationalEventCode":"vcsEventCode");
        return event;
    }
    private static String fileId(String raw,String field) {
        if(raw==null||raw.isBlank()) return null;
        try { return UUID.fromString(raw.strip()).toString(); }
        catch(IllegalArgumentException ex) { throw invalid(field); }
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
