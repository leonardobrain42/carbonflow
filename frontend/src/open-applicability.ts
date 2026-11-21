import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {plainTerm} from './calculation-language';

type YesNo = boolean | null;
type Declaration = {
  unrestrictedBevAccess:YesNo;restrictedToIdentifiedVehicles:YesNo;privateLand:YesNo;
  chargerType:string|null;propulsion:string|null;electricitySource:string|null;
  associatedInfrastructure:YesNo;v2g:YesNo;chargingOwner:string|null;
  carbonRightsContractRef:string|null;driverDisclosureChannel:string|null;
  driverDisclosureEvidenceRef:string|null;driverDisclosureEffectiveOn:string|null;
  doubleClaimingCheck:string|null;effectiveFrom:string|null;explanation:string|null;
  rightsPartyCode:string|null;rightsContractCode:string|null;
  operationalEventCode:string|null;vcsEventCode:string|null;
  contractFileId:string|null;disclosureFileId:string|null;
};
type Party={code:string;version:number;kind:string;displayName:string};
type Contract={code:string;version:number;partyCode:string;partyVersion:number;label:string};
type RightsEvent={code:string;version:number;kind:string;eventOn:string;documentRef:string};
type RightsCatalog={parties:Party[];contracts:Contract[];events?:RightsEvent[];
  createsLegalPower:boolean;admitsRights:boolean};
type Finding={code:string;state:string;text:string};
type Readiness={category:string;network:string;rights:string;norm:string;
  canContinuePreparation:boolean;canUseForE8:boolean;stale:boolean;
  findings:Finding[];sourceDependencies:string[]};
type View={projectId:string;version:number;latestTriageVersion:number;
  latest:{version:number;triageVersion:number;answers:Declaration}|null;readiness:Readiness};

const EMPTY:Declaration={
  unrestrictedBevAccess:null,restrictedToIdentifiedVehicles:null,privateLand:null,
  chargerType:null,propulsion:null,electricitySource:null,associatedInfrastructure:null,
  v2g:null,chargingOwner:null,carbonRightsContractRef:null,
  driverDisclosureChannel:'NOT_DECLARED',driverDisclosureEvidenceRef:null,
  driverDisclosureEffectiveOn:null,doubleClaimingCheck:'UNKNOWN',effectiveFrom:null,explanation:null,
  rightsPartyCode:null,rightsContractCode:null,operationalEventCode:null,vcsEventCode:null,
  contractFileId:null,disclosureFileId:null,
};

@Component({
  selector:'carbonflow-open-applicability',
  standalone:true,
  templateUrl:'./open-applicability.html',
})
export class OpenApplicabilityComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input() editable=false;
  private readonly http=inject(HttpClient);
  readonly view=signal<View|null>(null);
  readonly form=signal<Declaration>({...EMPTY});
  readonly parties=signal<Party[]>([]);
  readonly contracts=signal<Contract[]>([]);
  readonly partyDraft=signal({kind:'ORGANIZATION',displayName:''});
  readonly contractLabel=signal('');
  readonly events=signal<RightsEvent[]>([]);
  readonly operationalDraft=signal({eventOn:'',documentRef:''});
  readonly vcsDraft=signal({eventOn:'',documentRef:''});
  readonly loading=signal(true);
  readonly term=plainTerm;
  readonly busy=signal(false);
  readonly error=signal('');
  readonly notice=signal('');
  readonly yesNo=[
    {value:'',label:'Ainda não sei'},
    {value:'true',label:'Sim'},
    {value:'false',label:'Não'},
  ];
  private retryKey='';

  ngOnChanges(changes:SimpleChanges){
    if(changes['projectId'])void this.load();
  }
  async load(){
    if(!this.projectId)return;
    this.loading.set(true);this.error.set('');
    try{
      const data=await firstValueFrom(this.http.get<View>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+'/technical/open-applicability'));
      this.view.set(data);
      this.form.set({...EMPTY,...data.latest?.answers});
      await this.loadRights();
      this.retryKey='';
    }catch(problem){this.error.set(this.message(problem));}
    finally{this.loading.set(false);}
  }
  update(field:keyof Declaration,value:string){
    let actual:unknown=value;
    if(['unrestrictedBevAccess','restrictedToIdentifiedVehicles','privateLand',
      'associatedInfrastructure','v2g'].includes(field)){
      actual=value===''?null:value==='true';
    }else if(value===''){actual=null;}
    this.form.update(current=>({...current,[field]:actual}));
  }
  bool(value:YesNo){return value===null?'':String(value);}
  eventsOf(kind:string){return this.events().filter(event=>event.kind===kind);}
  async uploadFile(subject:'CONTRACT'|'DISCLOSURE',input:HTMLInputElement){
    const file=input.files?.[0];
    const code=subject==='CONTRACT'?this.form().rightsContractCode:'DECLARATION';
    if(!file||!code||this.busy()){
      if(subject==='CONTRACT'&&!code)this.error.set('Registre o contrato antes de anexar o arquivo.');
      return;
    }
    this.busy.set(true);this.error.set('');
    try{
      const saved=await firstValueFrom(this.http.put<{id:string;state:string;usableForE8:boolean}>(
        this.rightsPath()+'/files/'+subject+'/'+encodeURIComponent(code),file,{
          headers:{'Content-Type':'application/pdf','X-File-Name':file.name,
            'Idempotency-Key':crypto.randomUUID()},
        }));
      if(saved.state!=='PENDING_REVIEW'||saved.usableForE8){
        this.error.set('O arquivo não pode ser tratado como prova admitida.');
        return;
      }
      this.update(subject==='CONTRACT'?'contractFileId':'disclosureFileId',saved.id);
    }catch(problem){this.error.set(this.message(problem));}
    finally{this.busy.set(false);}
  }
  async registerEvent(kind:'OPERATIONAL_START'|'VCS_START'){
    const draft=kind==='OPERATIONAL_START'?this.operationalDraft():this.vcsDraft();
    if(!draft.eventOn||!draft.documentRef.trim()||this.busy())return;
    const code=(kind==='OPERATIONAL_START'?'OP':'VCS')+crypto.randomUUID().slice(0,8).toUpperCase();
    await this.record('/events/'+code,{kind,eventOn:draft.eventOn,documentRef:draft.documentRef.trim()});
    if(!this.error()){
      this.update(kind==='OPERATIONAL_START'?'operationalEventCode':'vcsEventCode',code);
      if(kind==='OPERATIONAL_START')this.operationalDraft.set({eventOn:'',documentRef:''});
      else this.vcsDraft.set({eventOn:'',documentRef:''});
    }
  }
  contractsForParty(){
    const party=this.form().rightsPartyCode;
    return this.contracts().filter(contract=>contract.partyCode===party);
  }
  chooseParty(code:string){
    this.update('rightsPartyCode',code);
    const still=this.contractsForParty().some(contract=>contract.code===this.form().rightsContractCode);
    if(!still)this.update('rightsContractCode','');
  }
  async registerParty(){
    const draft=this.partyDraft();
    if(!draft.displayName.trim()||this.busy())return;
    const code='P'+crypto.randomUUID().slice(0,8).toUpperCase();
    await this.record('/parties/'+code,draft);
    if(!this.error()){
      this.chooseParty(code);
      this.partyDraft.set({kind:'ORGANIZATION',displayName:''});
    }
  }
  async registerContract(){
    const party=this.form().rightsPartyCode;
    const label=this.contractLabel().trim();
    if(!party||!label||this.busy())return;
    const code='C'+crypto.randomUUID().slice(0,8).toUpperCase();
    await this.record('/contracts/'+code,{partyCode:party,label});
    if(!this.error()){
      this.update('rightsContractCode',code);
      this.contractLabel.set('');
    }
  }
  async save(event:Event){
    event.preventDefault();
    const snapshot=this.view();
    if(!snapshot || !this.editable || this.busy())return;
    if(!this.retryKey)this.retryKey=crypto.randomUUID();
    this.busy.set(true);this.error.set('');this.notice.set('');
    try{
      const updated=await firstValueFrom(this.http.put<View>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+'/technical/open-applicability',
        this.form(),{headers:{'If-Match':'"'+snapshot.version+'"','Idempotency-Key':this.retryKey}}));
      this.view.set(updated);
      this.form.set({...EMPTY,...updated.latest?.answers});
      this.retryKey='';
      this.notice.set('Declaração versionada salva. A jornada pode continuar, mas nenhuma decisão técnica foi aprovada.');
    }catch(problem){
      this.error.set(this.message(problem));
      if(problem instanceof HttpErrorResponse && problem.status>=400 && problem.status<500)this.retryKey='';
    }finally{this.busy.set(false);}
  }
  private async loadRights(){
    try{
      const catalog=await firstValueFrom(this.http.get<RightsCatalog>(this.rightsPath()));
      this.acceptCatalog(catalog);
    }catch{this.parties.set([]);this.contracts.set([]);}
  }
  private async record(path:string,body:unknown){
    this.busy.set(true);this.error.set('');
    try{
      const catalog=await firstValueFrom(this.http.put<RightsCatalog>(this.rightsPath()+path,body,{
        headers:{'If-Match':'"0"','Idempotency-Key':crypto.randomUUID()},
      }));
      this.acceptCatalog(catalog);
    }catch(problem){this.error.set(this.message(problem));}
    finally{this.busy.set(false);}
  }
  private acceptCatalog(catalog:RightsCatalog){
    if(catalog.createsLegalPower||catalog.admitsRights){
      this.parties.set([]);this.contracts.set([]);
      this.error.set('O registro de titular não pode criar poder nem admitir o direito.');
      return;
    }
    this.parties.set(catalog.parties??[]);
    this.contracts.set(catalog.contracts??[]);
    this.events.set(catalog.events??[]);
  }
  private rightsPath(){
    return '/v1/projects/'+encodeURIComponent(this.projectId)+'/technical/open-rights';
  }
  private message(problem:unknown){
    if(problem instanceof HttpErrorResponse){
      if(problem.status===412)return 'Outra revisão foi salva. Recarregue para conferir as alterações.';
      if([401,403,404].includes(problem.status))return 'Sua sessão ou permissão de acesso a este projeto está indisponível.';
      if(problem.status===422)return 'Verifique os valores e referências de evidência informados.';
    }
    return 'Não foi possível carregar ou salvar a declaração de aplicabilidade.';
  }
}
