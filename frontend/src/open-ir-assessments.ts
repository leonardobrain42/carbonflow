import {Component, Input, OnChanges, OnDestroy, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Options={canRecord:boolean;canReview:boolean;projects:{projectId:string;title:string}[]};
type Period={period:string;projectYear:number;sourceYear:number;latestVersion:number;
  fleetSelectionId:string;fuelPinId:string};
type InitialPlan={evr:string;afec:string;mpg:string;fuel:string};
type Metric=keyof InitialPlan;
type PlanLink={id:string;version:number;sha256:string};
type PlannedMetric={source:string;responsible:string;procedure:string};
type ExAnteOption=PlanLink&{plan:Record<Metric,PlannedMetric>&{rationale:string}};
type ReviewReferences={fleetSnapshotId:string;ratingSnapshotId:string;fuelCandidateId:string};
type Assessment={id:string;projectId:string;projectYear:number;sourceYear:number;version:number;
  period:string;fleetSelectionId:string;fuelPinId:string;frequency:string;basis:string;
  rationale:string|null;state:string;assessmentSha256:string;candidate:string|null;stale:boolean;
  usableForE8:boolean;recordedAt:string;review:{outcome:string;annualUpdateBasis:string;
    evidenceLocator:string|null;reason:string|null;reviewedAt:string;
    initialContext?:{followupOn:string;planSha256:string;plan:InitialPlan|null;exAntePlan?:PlanLink|null}|null}|null;
  initialLinkState?:string|null;exAnteOption?:ExAnteOption|null;reviewReferences?:ReviewReferences|null;
  annualEvidence:{priorAssessmentId:string|null;evidenceSha256:string|null;state:string;policy:string}|null};

@Component({selector:'carbonflow-open-ir-assessments',standalone:true,
  templateUrl:'./open-ir-assessments.html'})
export class OpenIrAssessmentsComponent implements OnChanges,OnDestroy {
  @Input() projectId='';
  @Input() staff=false;
  private readonly http=inject(HttpClient);
  readonly options=signal<Options|null>(null);
  readonly periods=signal<Period[]>([]);
  readonly rows=signal<Assessment[]>([]);
  readonly busy=signal(false);
  readonly loaded=signal(false);
  readonly error=signal('');
  readonly message=signal('');
  private generation=0;
  private pending:{fingerprint:string;key:string}|null=null;
  selectedProject='';
  selectedPeriod='';
  frequency='';
  basis='';
  rationale='';
  reviewId='';
  outcome='';
  annualBasis='';
  priorId='';
  locator='';
  reason='';
  initialPlan:InitialPlan={evr:'',afec:'',mpg:'',fuel:''};
  initialDecision:InitialPlan={evr:'',afec:'',mpg:'',fuel:''};
  readonly metrics:{key:Metric;label:string}[]=[{key:'evr',label:'EVR'},{key:'afec',label:'AFEC'},{key:'mpg',label:'MPG'},{key:'fuel',label:'EFfuel'}];
  actualReference(row:Assessment,key:Metric){return key==='evr'?row.reviewReferences?.fleetSnapshotId:key==='fuel'?row.reviewReferences?.fuelCandidateId:row.reviewReferences?.ratingSnapshotId;}

  ngOnChanges(){this.options.set(null);this.selectedProject=this.staff?'':this.projectId;
    this.reset();void (this.staff?this.loadOptions():this.load());}
  ngOnDestroy(){this.generation++;}
  private reset(){
    this.generation++;this.rows.set([]);this.periods.set([]);this.loaded.set(false);
    this.busy.set(false);this.error.set('');this.message.set('');this.pending=null;
    this.selectedPeriod='';this.frequency='';this.basis='';this.rationale='';
    this.clearReview();
  }
  private clearReview(){
    this.reviewId='';this.outcome='';this.annualBasis='';this.priorId='';this.locator='';this.reason='';
    this.initialPlan={evr:'',afec:'',mpg:'',fuel:''};
    this.initialDecision={evr:'',afec:'',mpg:'',fuel:''};
  }
  private root(project=this.selectedProject){
    const id=encodeURIComponent(project);
    return this.staff?'/v1/platform/open-ir-assessments/'+id:
      '/v1/projects/'+id+'/technical/open-ir-assessments';
  }
  private problem(err:unknown){
    if(err instanceof HttpErrorResponse){
      if(err.status===401||err.status===403||err.status===404)return 'Acesso ou referência indisponível.';
      if(err.status===412)return 'A versão mudou. Atualize o histórico antes de tentar novamente.';
      const detail=err.error?.detail??err.error?.message;
      if(typeof detail==='string'&&detail)return detail;
    }
    return 'Não foi possível concluir a consulta ou operação de IR.';
  }
  async loadOptions(){
    if(!this.staff||this.busy())return;
    const epoch=++this.generation;this.busy.set(true);this.error.set('');
    try{
      const result=await firstValueFrom(this.http.get<Options>('/v1/platform/open-ir-assessments/options'));
      if(epoch!==this.generation)return;
      this.options.set(result);
      if(!result.projects.some(p=>p.projectId===this.selectedProject)){
        this.selectedProject='';this.rows.set([]);this.periods.set([]);this.loaded.set(false);
      }
    }catch(e){if(epoch===this.generation){this.options.set(null);this.rows.set([]);this.periods.set([]);
      this.loaded.set(false);this.error.set(this.problem(e));}}
    finally{if(epoch===this.generation)this.busy.set(false);}
    if(epoch===this.generation&&this.selectedProject&&this.options())await this.load();
  }
  chooseProject(project:string){
    this.selectedProject=project;this.reset();void this.load();
  }
  async load(){
    if(!this.selectedProject||this.busy())return;
    const epoch=++this.generation;this.busy.set(true);this.error.set('');
    this.loaded.set(false);this.rows.set([]);this.periods.set([]);
    try{
      const path=this.root();
      const [rows,periods]=await Promise.all([
        firstValueFrom(this.http.get<Assessment[]>(path)),
        this.staff&&this.options()?.canRecord
          ?firstValueFrom(this.http.get<Period[]>(path+'/period-options')):Promise.resolve([]),
      ]);
      if(epoch!==this.generation)return;
      this.rows.set(rows);this.periods.set(periods);this.loaded.set(true);
      if(!periods.some(p=>p.period===this.selectedPeriod))this.selectedPeriod='';
      this.clearReview();
    }catch(e){if(epoch===this.generation)this.error.set(this.problem(e));}
    finally{if(epoch===this.generation)this.busy.set(false);}
  }
  period(){return this.periods().find(p=>p.period===this.selectedPeriod);}
  canRecord(){return this.staff&&!!this.options()?.canRecord&&this.loaded()&&!this.busy()&&
    !!this.period()&&!!this.frequency&&!!this.basis&&this.rationale.trim().length>=15;}
  canReview(row:Assessment){return this.staff&&!!this.options()?.canReview&&this.loaded()&&
    !row.stale&&!row.review&&['PENDING_METHOD','PENDING_REVIEW'].includes(row.state);}
  beginReview(row:Assessment){if(!this.canReview(row)||this.busy())return;this.clearReview();this.reviewId=row.id;}
  priors(row:Assessment){return this.rows().filter(p=>p.id!==row.id&&!p.stale&&
    p.projectYear===row.projectYear-1&&p.sourceYear===row.sourceYear-1&&p.frequency==='ANNUAL'&&
    p.basis==='PROJECT_SPECIFIC'&&(p.projectYear===1?p.initialLinkState==='LINKED_CURRENT':
      (!p.review||p.review.outcome==='CHECKED_INTERNAL')));}
  reviewValid(row:Assessment){
    return this.canReview(row)&&!this.busy()&&!!this.outcome&&!!this.annualBasis&&
      this.locator.trim().length>=3&&this.reason.trim().length>=15&&
      (this.outcome!=='CHECKED_INTERNAL'||(row.projectYear>1&&row.state==='PENDING_REVIEW'&&this.annualBasis==='OBSERVED_UPDATE'&&
        this.priors(row).some(p=>p.id===this.priorId)))&&
      (this.outcome!=='INITIAL_BASELINE_CHECKED'||(row.projectYear===1&&row.state==='PENDING_REVIEW'&&this.annualBasis==='PLAN_ONLY'&&
        !!row.exAnteOption&&!!row.reviewReferences&&this.metrics.every(({key})=>
          ['MATCH','JUSTIFIED_DEVIATION'].includes(this.initialDecision[key])&&this.initialPlan[key].trim().length>=15&&this.initialPlan[key].trim().length<=1950)));
  }
  private headers(path:string,version:number,body:object){
    const fingerprint=JSON.stringify([path,version,body]);
    if(this.pending?.fingerprint!==fingerprint)this.pending={fingerprint,key:crypto.randomUUID()};
    return new HttpHeaders({'Idempotency-Key':this.pending.key,'If-Match':'"'+version+'"'});
  }
  async record(event:Event){
    event.preventDefault();
    const period=this.period();if(!period||!this.canRecord())return;
    await this.submit(this.root(),period.latestVersion,{period:period.period,frequency:this.frequency,
      basis:this.basis,rationale:this.rationale.trim()},'Avaliação registrada. Nenhum parâmetro foi admitido.');
  }
  async review(event:Event,row:Assessment){
    event.preventDefault();if(!this.reviewValid(row))return;
    await this.submit(this.root()+'/'+encodeURIComponent(row.id)+'/review',row.version,{
      assessmentSha256:row.assessmentSha256,outcome:this.outcome,annualUpdateBasis:this.annualBasis,
      evidenceLocator:this.locator.trim(),reason:this.reason.trim(),
      priorAssessmentId:this.outcome==='CHECKED_INTERNAL'?this.priorId:null,
      ...(this.outcome==='INITIAL_BASELINE_CHECKED'&&row.exAnteOption?{
        initialPlan:Object.fromEntries(this.metrics.map(({key})=>[key,this.initialDecision[key]+': '+this.initialPlan[key].trim()])),
        exAntePlanId:row.exAnteOption.id,exAntePlanVersion:row.exAnteOption.version,exAntePlanSha256:row.exAnteOption.sha256}:{})
    },'Parecer registrado. A conferência interna não libera o E8.');
  }
  private async submit(path:string,version:number,body:object,success:string){
    const epoch=++this.generation;this.busy.set(true);this.error.set('');this.message.set('');
    try{
      await firstValueFrom(this.http.post<Assessment>(path,body,{headers:this.headers(path,version,body)}));
      if(epoch!==this.generation)return;
      this.pending=null;this.message.set(success);this.rationale='';this.clearReview();
    }catch(e){if(epoch===this.generation)this.error.set(this.problem(e));return;}
    finally{if(epoch===this.generation)this.busy.set(false);}
    if(epoch===this.generation)await this.load();
  }
  label(state:string){
    return ({
      PENDING_METHOD:'Fundamentação metodológica pendente',
      PENDING_REVIEW:'Aguardando parecer técnico',
      CHECKED_INTERNAL_NOT_ADMITTED:'Conferido internamente — não admitido',
      INITIAL_BASELINE_CHECKED_NOT_ADMITTED:'Plano inicial conferido — não admitido',
      INITIAL_BASELINE_CHECKED:'Plano inicial conferido',
      INITIAL_LINK_RECHECK_REQUIRED:'Vínculo inicial requer rechecagem',
      LINKED_CURRENT:'Planejamento vinculado e vigente',LINK_STALE:'Planejamento obsoleto',LEGACY_LINK_MISSING:'Parecer legado sem vínculo ex ante',
      NEEDS_INFORMATION_NOT_ADMITTED:'Complementação necessária',
      REJECTED_NOT_ADMITTED:'Rejeitado — não admitido',
      STALE_NOT_ADMITTED:'Obsoleto — reavaliar',
      ANNUAL_EVIDENCE_RECHECK_REQUIRED:'Prova anual requer rechecagem',
      CONSECUTIVE_REFERENCES_NOT_ADMITTED:'Referências consecutivas — não admitidas',
      LEGACY_EVIDENCE_MISSING:'Parecer anterior sem vínculo anual',
      STALE_EVIDENCE_NOT_ADMITTED:'Prova anual obsoleta',
      ANNUAL:'Anual',NON_ANNUAL:'Não anual',UNKNOWN:'Indeterminada',
      PROJECT_SPECIFIC:'Dados específicos do projeto',DEFAULT_VALUES:'Valores padrão',
      CHECKED_INTERNAL:'Conferido internamente',NEEDS_INFORMATION:'Solicitada complementação',
      REJECTED:'Rejeitado',PLAN_ONLY:'Somente plano',OBSERVED_UPDATE:'Atualização observada',
      INSUFFICIENT:'Insuficiente'
    } as Record<string,string>)[state]??state;
  }
}
