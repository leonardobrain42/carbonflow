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
};
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
  private message(problem:unknown){
    if(problem instanceof HttpErrorResponse){
      if(problem.status===412)return 'Outra revisão foi salva. Recarregue para conferir as alterações.';
      if([401,403,404].includes(problem.status))return 'Sua sessão ou permissão de acesso a este projeto está indisponível.';
      if(problem.status===422)return 'Verifique os valores e referências de evidência informados.';
    }
    return 'Não foi possível carregar ou salvar a declaração de aplicabilidade.';
  }
}
