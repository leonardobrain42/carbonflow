import {Component, Input, OnChanges, OnDestroy, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type ParameterPlan={source:string;responsible:string;procedure:string};
type Parameter='evr'|'afec'|'mpg'|'fuel';
type Plan=Record<Parameter,ParameterPlan>&{rationale:string};
type Revision={id:string;version:number;plan:Plan;planSha256:string;contextSha256:string;
  state:string;stale:boolean;recordedAt:string;usableForE8:boolean};
type View={projectId:string;context:{triageVersion:number;intakeVersion:number;
  applicabilityVersion:number;state:string;sha256:string};latestVersion:number;editable:boolean;revisions:Revision[]};
const blank=():ParameterPlan=>({source:'',responsible:'',procedure:''});
const empty=():Plan=>({evr:blank(),afec:blank(),mpg:blank(),fuel:blank(),rationale:''});

@Component({selector:'carbonflow-open-ir-ex-ante-plan',standalone:true,templateUrl:'./open-ir-ex-ante-plan.html'})
export class OpenIrExAntePlanComponent implements OnChanges,OnDestroy {
  @Input({required:true}) projectId='';
  @Input() editable=false;
  private readonly http=inject(HttpClient);
  readonly view=signal<View|null>(null);
  readonly busy=signal(false);
  readonly error=signal('');
  readonly message=signal('');
  readonly fields:{key:Parameter;label:string}[]=[
    {key:'evr',label:'EVR — estoque de veículos elétricos'},
    {key:'afec',label:'AFEC — consumo energético do BEV'},
    {key:'mpg',label:'MPG — eficiência dos comparáveis fósseis'},
    {key:'fuel',label:'EFfuel — fator fóssil'}
  ];
  form=empty();
  private epoch=0;
  private pending:{fingerprint:string;key:string}|null=null;
  ngOnChanges(){this.epoch++;this.view.set(null);this.busy.set(false);this.form=empty();
    this.pending=null;this.message.set('');this.error.set('');void this.load();}
  ngOnDestroy(){this.epoch++;}
  private path(){return '/v1/projects/'+encodeURIComponent(this.projectId)+'/technical/open-ir-ex-ante-plan';}
  private problem(e:unknown){
    if(e instanceof HttpErrorResponse){
      if(e.status===404||e.status===403||e.status===401)return 'Planejamento indisponível para esta conta.';
      if(e.status===412)return 'A versão mudou. Atualize antes de salvar novamente.';
      if(e.status===409)return 'O recorte ou a chave de registro precisa de revisão.';
    }
    return 'Não foi possível consultar ou registrar o planejamento.';
  }
  async load(){
    if(!this.projectId||this.busy())return;
    const generation=++this.epoch;this.busy.set(true);this.error.set('');
    try{
      const view=await firstValueFrom(this.http.get<View>(this.path()));
      if(generation!==this.epoch)return;
      this.view.set(view);
      const latest=view.revisions[0]?.plan;
      this.form=latest?structuredClone(latest):empty();
    }catch(e){if(generation===this.epoch){this.view.set(null);this.error.set(this.problem(e));}}
    finally{if(generation===this.epoch)this.busy.set(false);}
  }
  valid(){
    return this.editable&&!!this.view()?.editable&&!this.busy()&&
      this.form.rationale.trim().length>=15&&this.form.rationale.trim().length<=2500&&
      this.fields.every(({key})=>{
        const p=this.form[key];return p.source.trim().length>=3&&p.source.trim().length<=500&&
          p.responsible.trim().length>=3&&p.responsible.trim().length<=500&&
          p.procedure.trim().length>=15&&p.procedure.trim().length<=2000;
      });
  }
  async save(event:Event){
    event.preventDefault();const view=this.view();if(!view||!this.valid())return;
    const body=structuredClone(this.form);
    const fingerprint=JSON.stringify([this.projectId,view.latestVersion,body]);
    if(this.pending?.fingerprint!==fingerprint)this.pending={fingerprint,key:crypto.randomUUID()};
    const headers=new HttpHeaders({'If-Match':'"'+view.latestVersion+'"','Idempotency-Key':this.pending.key});
    const generation=++this.epoch;this.busy.set(true);this.error.set('');this.message.set('');
    try{
      await firstValueFrom(this.http.post<Revision>(this.path(),body,{headers}));
      if(generation!==this.epoch)return;
      this.pending=null;this.message.set('Plano registrado como preparação, sem admissão normativa.');
    }catch(e){if(generation===this.epoch)this.error.set(this.problem(e));return;}
    finally{if(generation===this.epoch)this.busy.set(false);}
    if(generation===this.epoch)await this.load();
  }
  label(state:string){
    return ({OPEN_CANDIDATE:'Recorte OPEN candidato',SCOPE_PENDING:'Recorte ainda a confirmar',
      METHOD_OUTSIDE_SCOPE:'Metodologia fora deste planejamento',BRANCH_OUTSIDE_SCOPE:'Configuração fora do recorte',
      STALE_PLAN:'Histórico — reconfirmar',PLANNED_NOT_REVIEWED:'Planejado — sem parecer'} as Record<string,string>)[state]??state;
  }
}
