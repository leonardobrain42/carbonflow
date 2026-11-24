import {OpenIrAssessmentsComponent} from './open-ir-assessments';
import {Component, Input, OnChanges, OnDestroy, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Option = {projectId:string; title:string; triageVersion:number;
  pins:{parameterKind:string; vintageYear:number; version:number}[]};
type Factor = {id:string; periodYear:number; status:string; normalizedUnit:string};
type Board = {candidates:Factor[]};
type DyAllocation = {method:string;totalTco2e:string;shareNumerator:string;shareDenominator:string;basisUnit:string;
  sourceId:string;sourceEdition:string;locator:string;rationale:string};
type DyAllocationView = DyAllocation & {program:string;registryProject:string;issuanceId:string;brazilTco2e:string};
type DyIssuance = {program:string;registryProject:string;issuanceId:string;creditKind:string;
  issuedOn:string;geography:string;creditsTco2e:string;sourceId:string;sourceEdition:string;locator:string;allocation?:DyAllocation};
type DyCoverage = {program:string;creditKind:string;territory:string;result:string;
  searchedFrom:string;searchedUntilExclusive:string;searchedOn:string;sourceId:string;sourceEdition:string;
  locator:string;limitations:string};
type DyLicensePin = {sourceId:string;sourceEdition:string;licenseId:string;decisionVersion?:number};
type DyReview = {outcome:string;assessmentSha256:string;ruleReference:string|null;
  evidenceReferences:string|null;reason:string|null;reviewedAt:string};
type DyOptions = {canRecord:boolean;canReview:boolean;projects:{projectId:string;title:string}[]};
type DyFirstYearContext = {creditStart:string|null;exploratoryFrom:string|null;exploratoryUntilExclusive:string|null;
  contextGate:string|null;policyVersion:string};
type DyAssessment = {id:string;projectId:string;projectYear:number;version:number;outcome:string;
  priorStart:string|null;priorEndExclusive:string|null;calendarGate:string|null;
  erc:string|null;erf:string|null;dyPreview:string|null;programs:string|null;
  projectReferences:string|null;evidenceLocator:string|null;coverageNote:string|null;
  assessmentSha256:string;recordedAt:string;state:string;stale:boolean;usableForE8:boolean;review?:DyReview|null;
  issuanceCount?:number;issuanceState?:string;issuances?:DyIssuance[];
  sourceRightsState?:string;sourceLicenses?:DyLicensePin[];
  coverageState?:string;coverageCount?:number;searchCoverage?:DyCoverage[];
  allocationState?:string;allocationCount?:number;allocations?:DyAllocationView[];firstYearContext?:DyFirstYearContext|null};
type DyReadinessGate = {code:string;status:string;detail:string};
type DyReadiness = {policyVersion:string;assessmentId:string|null;assessmentVersion:number;
  assessmentSha256:string|null;state:string;internalChecksComplete:boolean;usableForE8:boolean;gates:DyReadinessGate[]};
type DyHistory = {projectId:string;projectYear:number;latestVersion:number;
  priorStart:string|null;priorEndExclusive:string|null;calendarGate:string|null;
  assessments:DyAssessment[];firstYearContext?:DyFirstYearContext|null;readiness?:DyReadiness};

/** Staff pin of a reviewed factor or an explicit IR/Dy. Not an E8 admission. */
@Component({selector:'carbonflow-platform-parameter-pins', standalone:true,
  imports:[OpenIrAssessmentsComponent],templateUrl:'./platform-parameter-pins.html'})
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
  dyCoverage:DyCoverage[]=[];
  dyAllocate=false;
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
    this.dyPrograms='';this.dyReferences='';this.dyEvidence='';this.dyNote='';this.dyIssuances=[];this.dyCoverage=[];this.dyAllocate=false;this.dyBindLicenses=false;this.dyLicenseIds={};
    this.reviewOutcome='';this.reviewRule='';this.reviewEvidence='';this.reviewReason='';
  }

  dyValid(){
    const year=Number(this.dyYear);
    return this.validContext()&&!!this.dyOptions()?.projects.some(item=>item.projectId===this.dyProject)
      && Number.isInteger(year)&&year>=1&&year<=100;
  }

  dyOutcomeChanged(value:string){
    this.dyOutcome=value;this.dyErc='';this.dyErf='';this.dyIssuances=[];this.dyCoverage=[];this.dyAllocate=false;this.dyBindLicenses=false;this.dyLicenseIds={};
  }

  addDyIssuance(){
    if(this.dyBusy()||this.dyIssuances.length>=100||this.dyOutcome==='INCONCLUSIVE')return;
    this.dyIssuances=[...this.dyIssuances,{program:'',registryProject:'',issuanceId:'',
      creditKind:'ERC',issuedOn:'',geography:'BR',creditsTco2e:'',sourceId:'',sourceEdition:'',locator:'',allocation:this.emptyAllocation()}];
  }

  removeDyIssuance(index:number){
    if(!this.dyBusy()){
      this.dyIssuances=this.dyIssuances.filter((_,i)=>i!==index);
      if(!this.dyIssuances.length)this.dyAllocate=false;
    }
  }
  private emptyAllocation():DyAllocation{
    return {method:'',totalTco2e:'',shareNumerator:'',shareDenominator:'',basisUnit:'',sourceId:'',sourceEdition:'',locator:'',rationale:''};
  }
  setDyAllocate(enabled:boolean){
    this.dyAllocate=enabled;
    if(enabled){
      this.dyBindLicenses=true;
      for(const row of this.dyIssuances)row.allocation??=this.emptyAllocation();
    }
  }
  dyAllocations(){
    if(!this.dyAllocate)return [];
    return this.dyIssuances.map(row=>({program:row.program,registryProject:row.registryProject,
      issuanceId:row.issuanceId,...row.allocation!}));
  }
  dySourceRows(){return [...this.dyIssuances,...this.dyCoverage,...this.dyAllocations()];}

  dyCoverageAllowed(){
    const history=this.dyHistory();
    return !!history && (history.projectYear!==1 || (!!history.firstYearContext?.exploratoryFrom && !history.firstYearContext?.contextGate));
  }
  addDyCoverage(){
    if(this.dyBusy()||!this.dyOutcome||this.dyCoverage.length>=100||!this.dyCoverageAllowed())return;
    this.dyCoverage=[...this.dyCoverage,{program:'',creditKind:'',territory:'',result:'',
      searchedFrom:'',searchedUntilExclusive:'',searchedOn:'',sourceId:'',sourceEdition:'',locator:'',limitations:''}];
    this.dyBindLicenses=true;
  }
  removeDyCoverage(index:number){
    if(!this.dyBusy())this.dyCoverage=this.dyCoverage.filter((_,i)=>i!==index);
  }
  dySourceKey(sourceId:string,sourceEdition:string){return JSON.stringify([sourceId.trim(),sourceEdition.trim()]);}
  dySources(){
    const sources=new Map<string,{key:string;sourceId:string;sourceEdition:string}>();
    for(const row of this.dySourceRows()){
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
    const rows=this.dySourceRows();
    return (!this.dyBindLicenses&&!this.dyCoverage.length&&!this.dyAllocate)||(this.dyBindLicenses&&rows.length>0&&rows.every(r=>r.sourceId.trim()&&r.sourceEdition.trim())
      &&this.dyLicenses().every(r=>/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(r.licenseId)));
  }
  private dyPath(project:string){
    return '/v1/platform/open-dy-assessments/'+encodeURIComponent(project);
  }
  private readonly dyInternalGates=['CALENDAR','ASSESSMENT','FRESHNESS','SOURCE_RIGHTS','ARITHMETIC',
    'SEARCH_COVERAGE','ISSUANCE_INVENTORY','GEOGRAPHIC_ALLOCATION','INDEPENDENT_REVIEW'];
  private readonly dyExternalGates=['NORMATIVE_WINDOW','EXTERNAL_EVIDENCE','E8_ADMISSION'];
  dyGateLabel(code:string){
    const labels:Record<string,string>={CALENDAR:'Calendário',ASSESSMENT:'Avaliação vigente',FRESHNESS:'Atualidade',
      SOURCE_RIGHTS:'Direitos das fontes',ARITHMETIC:'Aritmética de Dy',SEARCH_COVERAGE:'Matriz de cobertura',
      ISSUANCE_INVENTORY:'Inventário de emissões',GEOGRAPHIC_ALLOCATION:'Atribuição geográfica',
      INDEPENDENT_REVIEW:'Parecer independente',NORMATIVE_WINDOW:'Interpretação normativa',
      EXTERNAL_EVIDENCE:'Comprovação externa',E8_ADMISSION:'Admissão E8'};
    return labels[code]??code;
  }
  dyGateStatus(status:string){
    const labels:Record<string,string>={SATISFIED_INTERNAL:'Satisfeito internamente',BLOCKED:'Pendente',
      NOT_APPLICABLE:'Não aplicável nesta declaração',NOT_EVALUATED:'Não avaliado'};
    return labels[status]??'Estado desconhecido';
  }
  private readinessMatches(history:DyHistory){
    // Allow an older server to show history, never synthesize a readiness report.
    const r=history.readiness;
    if(r===undefined)return true;
    if(!r||r.policyVersion!=='OPEN-DY-READINESS@1'||r.state!=='BLOCKED'||r.usableForE8!==false
      ||typeof r.internalChecksComplete!=='boolean'||r.assessmentVersion!==history.latestVersion
      ||r.assessmentId!==(history.assessments[0]?.id??null)
      ||r.assessmentSha256!==(history.assessments[0]?.assessmentSha256??null)
      ||!Array.isArray(r.gates))return false;
    const expected=[...this.dyInternalGates,...this.dyExternalGates];
    if(r.gates.length!==expected.length || r.gates.some((g,i)=>!g||g.code!==expected[i]
      ||!['SATISFIED_INTERNAL','BLOCKED','NOT_APPLICABLE','NOT_EVALUATED'].includes(g.status)
      ||typeof g.detail!=='string'||!g.detail.trim()))return false;
    const internallyComplete=r.gates.slice(0,this.dyInternalGates.length)
      .every(g=>g.status==='SATISFIED_INTERNAL'||g.status==='NOT_APPLICABLE');
    return internallyComplete===r.internalChecksComplete
      &&r.gates.slice(this.dyInternalGates.length).every(g=>g.status==='BLOCKED');
  }
  private dyMatches(history:DyHistory,project:string,year:number){
    return history.projectId===project&&history.projectYear===year
      && Number.isInteger(history.latestVersion)&&history.latestVersion>=0
      && Array.isArray(history.assessments)
      && (history.assessments[0]?.version??0)===history.latestVersion
      && history.assessments.every(row=>row.projectId===project&&row.projectYear===year
        &&row.usableForE8===false) && this.readinessMatches(history);
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
      ||(this.dyCoverage.length>0&&!this.dyCoverageAllowed())
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
        issuances:this.dyOutcome==='INCONCLUSIVE'?[]:this.dyIssuances.map(({allocation,...row})=>row),
        allocations:this.dyAllocations(),
        sourceLicenses:this.dyLicenses(),searchCoverage:this.dyCoverage,
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
