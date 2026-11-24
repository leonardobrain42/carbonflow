import {Component, Input, OnChanges, OnDestroy, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Option = {projectId:string; title:string; triageVersion:number;
  pins:{parameterKind:string; vintageYear:number; version:number}[]};
type Factor = {id:string; periodYear:number; status:string; normalizedUnit:string};
type Board = {candidates:Factor[]};
type DyIssuance = {program:string;registryProject:string;issuanceId:string;creditKind:string;
  issuedOn:string;geography:string;creditsTco2e:string;sourceId:string;sourceEdition:string;locator:string};
type DyLicensePin = {sourceId:string;sourceEdition:string;licenseId:string;decisionVersion?:number};
type DyReview = {outcome:string;assessmentSha256:string;ruleReference:string|null;
  evidenceReferences:string|null;reason:string|null;reviewedAt:string};
type DyOptions = {canRecord:boolean;canReview:boolean;projects:{projectId:string;title:string}[]};
type DyAssessment = {id:string;projectId:string;projectYear:number;version:number;outcome:string;
  priorStart:string|null;priorEndExclusive:string|null;calendarGate:string|null;
  erc:string|null;erf:string|null;dyPreview:string|null;programs:string|null;
  projectReferences:string|null;evidenceLocator:string|null;coverageNote:string|null;
  assessmentSha256:string;recordedAt:string;state:string;stale:boolean;usableForE8:boolean;review?:DyReview|null;
  issuanceCount?:number;issuanceState?:string;issuances?:DyIssuance[];
  sourceRightsState?:string;sourceLicenses?:DyLicensePin[]};
type DyHistory = {projectId:string;projectYear:number;latestVersion:number;
  priorStart:string|null;priorEndExclusive:string|null;calendarGate:string|null;
  assessments:DyAssessment[]};

/** Staff pin of a reviewed factor or an explicit IR/Dy. Not an E8 admission. */
@Component({selector:'carbonflow-platform-parameter-pins', standalone:true,
  templateUrl:'./platform-parameter-pins.html'})
export class PlatformParameterPinsComponent implements OnChanges, OnDestroy {
  @Input({required:true}) methodology='';
  @Input({required:true}) edition='';
  @Input({required:true}) scenario='';
  private readonly http=inject(HttpClient);
  readonly options=signal<Option[]>([]);
  readonly fuel=signal<Factor[]>([]);
  readonly grid=signal<Factor[]>([]);
  readonly error=signal('');
  readonly message=signal('');
  readonly busy=signal(false);
  readonly dyOptions=signal<DyOptions|null>(null);
  private dyOptionsRequest=0;
  readonly dyHistory=signal<DyHistory|null>(null);
  readonly dyBusy=signal(false);
  readonly dyError=signal('');
  readonly dyMessage=signal('');
  dyProject='';
  dyYear='';
  dyOutcome='';
  dyErc='';
  dyErf='';
  dyPrograms='';
  dyReferences='';
  dyEvidence='';
  dyNote='';
  dyIssuances:DyIssuance[]=[];
  dyBindLicenses=false;
  dyLicenseIds:Record<string,string>={};
  reviewOutcome='';
  reviewRule='';
  reviewEvidence='';
  reviewReason='';
  private dyRequest=0;
  projectId='';
  kind='EF_FUEL';
  year='';
  candidateId='';
  declaredValue='';
  ruleReference='';
  evidenceLocator='';
  readonly validContext=()=>this.methodology==='VM0038' && this.edition==='1.1'
    && this.scenario==='OPEN_LDV_DCFC_SIN_BR';
  ngOnChanges(changes:SimpleChanges){
    if(changes['methodology']||changes['edition']||changes['scenario']){
      this.options.set([]); this.fuel.set([]); this.grid.set([]);
      this.dyOptionsRequest++; this.dyOptions.set(null);
      this.dyProject=''; this.dyYear=''; this.resetDy();
      this.error.set(''); this.message.set('');
      if(this.validContext()){void this.load();void this.loadDyOptions();}
    }
  }

  async load(){
    if(!this.validContext())return;
    this.busy.set(true); this.error.set('');
    try{
      const [options, fuel, grid]=await Promise.all([
        firstValueFrom(this.http.get<Option[]>('/v1/platform/open-parameter-bindings/options')),
        firstValueFrom(this.http.get<Board>('/v1/platform/open-fossil-factors')),
        firstValueFrom(this.http.get<Board>('/v1/platform/open-grid-factors')),
      ]);
      this.options.set(options);
      this.fuel.set(fuel.candidates.filter(item=>item.status==='CHECKED_INTERNAL_NOT_ADMITTED'));
      this.grid.set(grid.candidates.filter(item=>item.status==='CHECKED_INTERNAL_NOT_ADMITTED'));
      if(!options.some(item=>item.projectId===this.projectId))this.projectId='';

    }catch(err){this.error.set(this.problem(err));}
    finally{this.busy.set(false);}
  }

  candidates(){return this.kind==='EF_GRID'?this.grid():this.fuel();}

  async pin(event:Event){
    event.preventDefault();
    if(this.busy()||!this.projectId||!this.year)return;
    const option=this.options().find(item=>item.projectId===this.projectId);
    const current=option?.pins.find(item=>item.parameterKind===this.kind
      && item.vintageYear===Number(this.year))?.version??0;
    this.busy.set(true); this.error.set(''); this.message.set('');
    try{
      await firstValueFrom(this.http.post('/v1/platform/open-parameter-bindings',{
        projectId:this.projectId, parameterKind:this.kind, vintageYear:Number(this.year),
        candidateId:this.kind==='IR'||this.kind==='DY'?null:this.candidateId||null,
        declaredValue:this.kind==='IR'||this.kind==='DY'?this.declaredValue:null,
        ruleReference:this.kind==='IR'||this.kind==='DY'?this.ruleReference:null,
        evidenceLocator:this.kind==='IR'||this.kind==='DY'?this.evidenceLocator:null,
      },{headers:new HttpHeaders({
        'Idempotency-Key':crypto.randomUUID(),
        'If-Match':'"'+current+'"',
      })}));
      this.message.set('Pino gravado como preparação. Ele não admite o cálculo nem preenche 1 por omissão.');
      await this.load();
    }catch(err){this.error.set(this.problem(err));}
    finally{this.busy.set(false);}
  }

  ngOnDestroy(){this.dyRequest++;this.dyOptionsRequest++;}

  async loadDyOptions(){
    if(!this.validContext())return;
    const request=++this.dyOptionsRequest;
    try{
      const result=await firstValueFrom(this.http.get<DyOptions>('/v1/platform/open-dy-assessments/options'));
      if(request!==this.dyOptionsRequest)return;
      if(!Array.isArray(result.projects)||typeof result.canRecord!=='boolean'||typeof result.canReview!=='boolean'
        ||(!result.canRecord&&!result.canReview))throw new Error('Opções inconsistentes');
      this.dyOptions.set(result);
      if(this.dyProject&&!result.projects.some(item=>item.projectId===this.dyProject)){
        this.dyProject='';this.resetDy();
      }
    }catch(err){
      if(request!==this.dyOptionsRequest)return;
      this.dyOptions.set(null);this.dyProject='';this.resetDy();
      this.dyError.set(this.dyProblem(err));
    }
  }

  resetDy(){
    this.dyRequest++;
    this.dyHistory.set(null);this.dyBusy.set(false);
    this.dyMessage.set('');this.dyError.set('');
    this.dyOutcome='';this.dyErc='';this.dyErf='';
    this.dyPrograms='';this.dyReferences='';this.dyEvidence='';this.dyNote='';this.dyIssuances=[];this.dyBindLicenses=false;this.dyLicenseIds={};
    this.reviewOutcome='';this.reviewRule='';this.reviewEvidence='';this.reviewReason='';
  }

  dyValid(){
    const year=Number(this.dyYear);
    return this.validContext()&&!!this.dyOptions()?.projects.some(item=>item.projectId===this.dyProject)
      && Number.isInteger(year)&&year>=1&&year<=100;
  }

  dyOutcomeChanged(value:string){
    this.dyOutcome=value;this.dyErc='';this.dyErf='';this.dyIssuances=[];this.dyBindLicenses=false;this.dyLicenseIds={};
  }

  addDyIssuance(){
    if(this.dyBusy()||this.dyIssuances.length>=100||this.dyOutcome==='INCONCLUSIVE')return;
    this.dyIssuances=[...this.dyIssuances,{program:'',registryProject:'',issuanceId:'',
      creditKind:'ERC',issuedOn:'',geography:'BR',creditsTco2e:'',sourceId:'',sourceEdition:'',locator:''}];
  }

  removeDyIssuance(index:number){
    if(!this.dyBusy())this.dyIssuances=this.dyIssuances.filter((_,i)=>i!==index);
  }

  dySourceKey(sourceId:string,sourceEdition:string){return JSON.stringify([sourceId.trim(),sourceEdition.trim()]);}
  dySources(){
    const sources=new Map<string,{key:string;sourceId:string;sourceEdition:string}>();
    for(const row of this.dyIssuances){
      const sourceId=row.sourceId.trim(),sourceEdition=row.sourceEdition.trim();
      if(!sourceId||!sourceEdition)continue;
      const key=this.dySourceKey(sourceId,sourceEdition);
      sources.set(key,{key,sourceId,sourceEdition});
    }
    return [...sources.values()];
  }
  dyLicenses():DyLicensePin[]{
    if(!this.dyBindLicenses)return [];
    return this.dySources().map(({key,sourceId,sourceEdition})=>({
      sourceId,sourceEdition,licenseId:(this.dyLicenseIds[key]??'').trim()
    }));
  }
  dyRightsReady(){
    return !this.dyBindLicenses||(this.dyIssuances.length>0&&this.dyIssuances.every(r=>r.sourceId.trim()&&r.sourceEdition.trim())
      &&this.dyLicenses().every(r=>/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(r.licenseId)));
  }
  private dyPath(project:string){
    return '/v1/platform/open-dy-assessments/'+encodeURIComponent(project);
  }
  private dyMatches(history:DyHistory,project:string,year:number){
    return history.projectId===project&&history.projectYear===year
      && Number.isInteger(history.latestVersion)&&history.latestVersion>=0
      && Array.isArray(history.assessments)
      && (history.assessments[0]?.version??0)===history.latestVersion
      && history.assessments.every(row=>row.projectId===project&&row.projectYear===year
        &&row.usableForE8===false);
  }

  async loadDy(){
    if(!this.dyValid()||this.dyBusy())return;
    const request=++this.dyRequest,project=this.dyProject,year=Number(this.dyYear);
    this.dyHistory.set(null);this.dyError.set('');this.dyBusy.set(true);
    try{
      const history=await firstValueFrom(this.http.get<DyHistory>(this.dyPath(project)+'/'+year));
      if(request!==this.dyRequest)return;
      if(!this.dyMatches(history,project,year))throw new Error('Histórico inconsistente');
      this.dyHistory.set(history);
    }catch(err){
      if(request===this.dyRequest)this.dyError.set(this.dyProblem(err));
    }finally{
      if(request===this.dyRequest)this.dyBusy.set(false);
    }
  }

  async saveDy(event:Event){
    event.preventDefault();
    const history=this.dyHistory();
    if(!this.dyOptions()?.canRecord||this.dyBusy()||!this.dyValid()||!history||!this.dyOutcome||!this.dyRightsReady()
      ||history.projectId!==this.dyProject||history.projectYear!==Number(this.dyYear)
      ||(history.calendarGate&&this.dyOutcome!=='INCONCLUSIVE'))return;
    const request=++this.dyRequest,project=this.dyProject,year=Number(this.dyYear);
    this.dyBusy.set(true);this.dyError.set('');this.dyMessage.set('');
    try{
      const saved=await firstValueFrom(this.http.post<DyAssessment>(this.dyPath(project),{
        projectYear:year,outcome:this.dyOutcome,
        erc:this.dyOutcome==='INCONCLUSIVE'?null:this.dyErc,
        erf:this.dyOutcome==='INCONCLUSIVE'?null:this.dyErf,
        programs:this.dyPrograms,projectReferences:this.dyReferences,
        evidenceLocator:this.dyEvidence,coverageNote:this.dyNote,
        issuances:this.dyOutcome==='INCONCLUSIVE'?[]:this.dyIssuances,
        sourceLicenses:this.dyLicenses(),
      },{headers:new HttpHeaders({'Idempotency-Key':crypto.randomUUID(),
        'If-Match':'"'+history.latestVersion+'"'})}));
      if(request!==this.dyRequest)return;
      if(saved.projectId!==project||saved.projectYear!==year||saved.usableForE8!==false
        ||saved.version!==history.latestVersion+1)throw new Error('Avaliação inconsistente');
      this.dyMessage.set('Avaliação registrada em nova versão, sem admissão para E8.');
      this.dyBusy.set(false);
      await this.loadDy();
    }catch(err){
      if(request===this.dyRequest){
        this.dyHistory.set(null);
        this.dyError.set(this.dyProblem(err));
      }
    }finally{
      if(request===this.dyRequest)this.dyBusy.set(false);
    }
  }

  async saveReview(event:Event, item:DyAssessment){
    event.preventDefault();
    const history=this.dyHistory();
    if(!this.dyOptions()?.canReview||!history||this.dyBusy()||!this.dyValid()||!this.reviewOutcome
      ||item.version!==history.latestVersion||item.id!==history.assessments[0]?.id
      ||item.stale||item.review||item.projectId!==this.dyProject||item.projectYear!==Number(this.dyYear)
      ||(this.reviewOutcome==='CHECKED_INTERNAL'&&item.outcome==='INCONCLUSIVE'))return;
    const request=++this.dyRequest,project=this.dyProject,year=Number(this.dyYear);
    const outcome=this.reviewOutcome;
    this.dyBusy.set(true);this.dyError.set('');this.dyMessage.set('');
    try{
      const saved=await firstValueFrom(this.http.post<DyAssessment>(
        this.dyPath(project)+'/'+encodeURIComponent(item.id)+'/review',{
          assessmentSha256:item.assessmentSha256,outcome,
          ruleReference:this.reviewRule,evidenceReferences:this.reviewEvidence,reason:this.reviewReason,
        },{headers:new HttpHeaders({'Idempotency-Key':crypto.randomUUID(),
          'If-Match':'"'+history.latestVersion+'"'})}));
      if(request!==this.dyRequest)return;
      if(saved.id!==item.id||saved.projectId!==project||saved.projectYear!==year
        ||saved.version!==item.version||saved.assessmentSha256!==item.assessmentSha256
        ||saved.usableForE8!==false||saved.review?.assessmentSha256!==item.assessmentSha256
        ||saved.review?.outcome!==outcome)throw new Error('Parecer inconsistente');
      this.reviewOutcome='';this.reviewRule='';this.reviewEvidence='';this.reviewReason='';
      this.dyMessage.set('Parecer interno registrado, sem admissão para E8.');
      this.dyBusy.set(false);await this.loadDy();
    }catch(err){
      if(request===this.dyRequest){this.dyHistory.set(null);this.dyError.set(this.dyProblem(err));}
    }finally{if(request===this.dyRequest)this.dyBusy.set(false);}
  }

  private dyProblem(err:unknown){
    if(err instanceof HttpErrorResponse){
      if([401,403,404].includes(err.status))return 'Avaliação indisponível para esta conta.';
      if(err.status===412)return 'A avaliação mudou. Consulte o histórico novamente antes de salvar.';
      const detail=err.error?.detail??err.error?.message;
      if(typeof detail==='string'&&detail)return detail;
    }
    return 'Não foi possível consultar ou registrar a avaliação de Dy.';
  }

  private problem(err:unknown){
    if(err instanceof HttpErrorResponse){
      const detail=err.error?.detail??err.error?.message;
      if(typeof detail==='string' && detail)return detail;
    }
    return 'Não foi possível gravar o pino.';
  }
}
