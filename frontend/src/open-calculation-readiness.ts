import {NgTemplateOutlet} from '@angular/common';
import {OpenE8AnnualComponent} from './open-e8-annual';
import {AbbrComponent} from './acronyms';
import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {calculationGateTitle, equationTerm, plannedRole} from './calculation-language';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

interface ParameterPin {
  id:string;
  parameterKind:string;
  vintageYear:number;
  normalizedUnit:string|null;
  valueKind:string;
  declaredValue:string|null;
  explicitOne:boolean;
  state:string;
  usableForE8:boolean;
}
interface CalculationGate {
  code:string;
  state:string;
  reason:string;
  nextAction:string;
  owner:string;
}
interface EquationStep {
  outputCode:string;
  ruleReference:string;
  unit:string;
  description:string;
  requiredInputs:string[];
  blockedBy:string[];
  status:string;
  whyNotCalculated?:string;
}
interface RunGate {
  code:string;
  state:string;
  reason:string;
  nextAction:string;
  owner:string;
}
interface RunMemory {
  usableForE8:boolean;
  beTco2e:string;
  peTco2e:string;
  leTco2e:string;
  erTco2e:string;
  erDisplay:string;
  hourlyPe?:{
    windowStart:string;windowEndExclusive:string;region:string;
    edKwh:string;ecKwh:string;peTco2e:string;
    hours:{hourStart:string;edKwh:string;ecKwh:string;factorTco2ePerKwh:string;peTco2e:string}[];
  }|null;
}
interface CalculationRun {
  id:string;
  period:string;
  version:number;
  state:string;
  stale:boolean;
  canIssueVcu:boolean;
  gates:RunGate[];
  memory:RunMemory|null;
  irContext?:{assessmentId:string|null;version:number|null;assessmentSha256:string|null;contextSha256:string;state:string}|null;
  timing?:{windowStart:string|null;windowEndExclusive:string|null;
    projectYear:number|null;sourceYear:number|null}|null;
}
interface InputReference {
  code:string; basis:string; id:string|null; version:number|null; sha256:string|null; state:string;
}
interface CurrentSelection {
  policyVersion:string;projectId:string;period:string;
  timing:{windowStart:string|null;windowEndExclusive:string|null;projectYear:number|null;sourceYear:number|null};
  timingGate:string|null;inputs:InputReference[];usableForE8:boolean;canIssueVcu:boolean;
}
interface RunListing {
  period:string;
  canRecord:boolean;
  runs:CalculationRun[];
  currentSelection?:CurrentSelection;
}
interface OpenCalculationReadiness {
  projectId:string;
  environment:string;
  methodology:string|null;
  scenario:string;
  workflowState:string;
  calculationState:string;
  canContinuePreparation:boolean;
  canRunE8:boolean;
  canIssueVcu:boolean;
  emissions:{
    beTco2e:string|null;peTco2e:string|null;leTco2e:string|null;
    erTco2e:string|null;erDisplay:string|null;
  };
  gates:CalculationGate[];
  equationPlan:EquationStep[];
}

@Component({
  selector:'carbonflow-open-calculation-readiness',
  standalone:true,
  imports:[NgTemplateOutlet,OpenE8AnnualComponent,AbbrComponent],
  templateUrl:'./open-calculation-readiness.html',
})
export class OpenCalculationReadinessComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input() canViewPd=false;
  private readonly http=inject(HttpClient);
  readonly loading=signal(true);
  readonly error=signal('');
  readonly readiness=signal<OpenCalculationReadiness|null>(null);
  readonly pins=signal<ParameterPin[]>([]);
  readonly runPeriod=signal('M1');
  readonly runPeriods=signal<string[]>([]);
  readonly runLoading=signal(false);
  readonly canRecord=signal(false);
  readonly latestRun=signal<CalculationRun|null>(null);
  readonly currentSelection=signal<CurrentSelection|null>(null);
  readonly recording=signal(false);
  readonly runError=signal('');
  private requestId=0;
  private runRequestId=0;
  private runKey='';

  ngOnChanges(changes:SimpleChanges) {
    if(changes['projectId']) {
      this.runKey='';
      this.runRequestId++;
      this.runPeriod.set('M1');
      this.runPeriods.set([]);
      this.latestRun.set(null);
      this.currentSelection.set(null);
      this.canRecord.set(false);
      this.recording.set(false);
      void this.load();
    }
  }

  async load() {
    const project=this.projectId;
    if(!project)return;
    const current=++this.requestId;
    this.loading.set(true);
    this.error.set('');
    this.readiness.set(null);
    try {
      const response=await firstValueFrom(this.http.get<OpenCalculationReadiness>(
        '/v1/projects/'+encodeURIComponent(project)+'/technical/open-calculation-readiness'
      ));
      if(current===this.requestId && project===this.projectId) {
        if(response.projectId!==project || response.environment!=='SANDBOX')
          throw new Error('Inconsistent project context');
        this.readiness.set(response);
        void this.loadPins(project, current);
      }
    } catch (problem) {
      if(current===this.requestId) {
        this.error.set(problem instanceof HttpErrorResponse && [401,403,404].includes(problem.status)
          ? 'Seu acesso ao diagnóstico de cálculo está indisponível.'
          : 'Não foi possível consultar o pré-cálculo. Tente novamente.');
      }
    } finally {
      if(current===this.requestId)this.loading.set(false);
    }
    if(current===this.requestId)void this.loadRunPeriods(project,current);
  }

  private async loadPins(project:string, current:number) {
    try {
      const pins=await firstValueFrom(this.http.get<ParameterPin[]>(
        '/v1/projects/'+encodeURIComponent(project)+'/technical/open-parameter-bindings'));
      if(current===this.requestId && project===this.projectId)this.pins.set(pins);
    } catch {
      if(current===this.requestId)this.pins.set([]);
    }
  }

  async loadRun(period=this.runPeriod()) {
    const project=this.projectId;
    const current=++this.runRequestId;
    this.runPeriod.set(period);
    this.runLoading.set(true);
    this.latestRun.set(null);
    this.currentSelection.set(null);
    this.canRecord.set(false);
    this.runError.set('');
    try {
      const listing=await firstValueFrom(this.http.get<RunListing>(this.runUrl(project,period)));
      if(project!==this.projectId || current!==this.runRequestId)return;
      if(listing.period!==period)throw new Error('Inconsistent period');
      if(listing.currentSelection!==undefined &&
        !this.selectionMatches(listing.currentSelection,project,period))throw new Error('Inconsistent selection');
      this.currentSelection.set(listing.currentSelection??null);
      this.canRecord.set(listing.canRecord);
      this.latestRun.set(listing.runs[0]??null);
    } catch (problem) {
      if(project!==this.projectId || current!==this.runRequestId)return;
      this.runError.set(problem instanceof HttpErrorResponse && [401,403,404].includes(problem.status)
        ? '' : 'Não foi possível consultar a última tentativa.');
    } finally {
      if(current===this.runRequestId)this.runLoading.set(false);
    }
  }

  private selectionMatches(report:CurrentSelection,project:string,period:string){
    if(!report||!['OPEN-E8-INPUT-SELECTION@1','OPEN-E8-INPUT-SELECTION@2'].includes(report.policyVersion)||report.projectId!==project
      ||report.period!==period||report.usableForE8!==false||report.canIssueVcu!==false||!report.timing
      ||!Array.isArray(report.inputs))return false;
    const t=report.timing;
    const resolved=Number.isInteger(t.projectYear)&&Number(t.projectYear)>=1&&Number(t.projectYear)<=100
      &&Number.isInteger(t.sourceYear)&&Number(t.sourceYear)>=1;
    if(report.timingGate===null?!resolved||!t.windowStart||!t.windowEndExclusive:
      typeof report.timingGate!=='string'||!report.timingGate||t.projectYear!==null||t.sourceYear!==null)return false;
    const expected=[['ED_EC_EFEL','HOURLY_PERIOD_BINDING'],['EVR_RATINGS','FLEET_PERIOD_SELECTION'],
      ['EF_FUEL','SOURCE_YEAR_PIN'],['IR',report.policyVersion==='OPEN-E8-INPUT-SELECTION@2'?'STRUCTURED_PROJECT_SOURCE_YEAR':'SOURCE_YEAR_PIN'],['DY','LEGACY_SOURCE_YEAR_PIN']];
    const irStates=['FIRST_YEAR_REVIEW_REQUIRED','MISSING_FOR_PROJECT_YEAR','PERIOD_REFERENCE_MISMATCH',
      'ANNUAL_EVIDENCE_RECHECK_REQUIRED','CHECKED_INTERNAL_NOT_ADMITTED','FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED'];
    return report.inputs.length===expected.length&&report.inputs.every((ref,i)=>{
      if(!ref||ref.code!==expected[i][0]||ref.basis!==expected[i][1]
        ||!['MISSING','MISSING_FOR_SOURCE_YEAR','TIMING_UNRESOLVED','STALE','REVIEW_REQUIRED','NOT_ADMITTED',
          ...(i===3&&report.policyVersion==='OPEN-E8-INPUT-SELECTION@2'?irStates:[])].includes(ref.state))return false;
      if(i>=2&&!resolved&&(ref.id!==null||ref.state!=='TIMING_UNRESOLVED'))return false;
      if(ref.id===null)return ref.version===null&&ref.sha256===null&&
        ['MISSING','MISSING_FOR_SOURCE_YEAR','TIMING_UNRESOLVED',
          ...(i===3&&report.policyVersion==='OPEN-E8-INPUT-SELECTION@2'?['FIRST_YEAR_REVIEW_REQUIRED','MISSING_FOR_PROJECT_YEAR']:[])].includes(ref.state);
      return typeof ref.id==='string'&&/^[0-9a-f-]{36}$/i.test(ref.id)&&Number.isInteger(ref.version)
        &&Number(ref.version)>0&&typeof ref.sha256==='string'&&/^[0-9a-f]{64}$/.test(ref.sha256)
        &&['NOT_ADMITTED','STALE','TIMING_UNRESOLVED','REVIEW_REQUIRED',
          ...(i===3&&report.policyVersion==='OPEN-E8-INPUT-SELECTION@2'?irStates.filter(s=>s!=='MISSING_FOR_PROJECT_YEAR'):[])].includes(ref.state);
    });
  }

  selectionLabel(code:string){
    return ({ED_EC_EFEL:'ED / EC / EFel horário',EVR_RATINGS:'EVR e ratings comparáveis',
      EF_FUEL:'EFfuel — fator fóssil',IR:'IR — melhoria tecnológica',DY:'Dy — pino escalar legado'} as Record<string,string>)[code]??code;
  }

  async record() {
    if(this.recording() || !this.canRecord())return;
    const project=this.projectId;
    const period=this.runPeriod();
    const version=this.latestRun()?.version??0;
    if(!this.runKey)this.runKey=crypto.randomUUID();
    this.recording.set(true);
    this.runError.set('');
    try {
      const saved=await firstValueFrom(this.http.post<CalculationRun>(this.runUrl(project,period),{},{
        headers:{'Idempotency-Key':this.runKey,'If-Match':'"'+version+'"'},
      }));
      if(project!==this.projectId)return;
      if(saved.canIssueVcu || saved.memory?.usableForE8)
        throw new Error('Unexpected E8 admission');
      this.runKey='';
      this.latestRun.set(saved);
    } catch (problem) {
      if(project!==this.projectId)return;
      this.runKey='';
      if(problem instanceof HttpErrorResponse && problem.status===412) {
        await this.loadRun();
        this.runError.set('A tentativa mudou. Consulte de novo antes de registrar.');
      } else if(problem instanceof HttpErrorResponse && [401,403,404].includes(problem.status)) {
        this.canRecord.set(false);
        this.runError.set('Seu acesso não permite registrar a tentativa.');
      } else {
        this.runError.set('Não foi possível registrar a tentativa.');
      }
    } finally {
      if(project===this.projectId)this.recording.set(false);
    }
  }

  private async loadRunPeriods(project:string, current:number) {
    let periods:string[]=[];
    try {
      const dossiers=await firstValueFrom(this.http.get<{reference?:{code?:string}}[]>(
        '/v1/projects/'+encodeURIComponent(project)+'/technical/open-monitoring/dossiers'));
      periods=dossiers.map(item=>item.reference?.code).filter((code):code is string=>!!code);
    } catch {
      // Quem vê o cálculo e não o monitoramento registra a tentativa em M1.
    }
    if(project!==this.projectId || current!==this.requestId)return;
    this.runPeriods.set(periods);
    const selected=this.runPeriod();
    await this.loadRun(periods.includes(selected)?selected:periods[0]??'M1');
  }

  private runUrl(project:string, period:string) {
    return '/v1/projects/'+encodeURIComponent(project)
      +'/technical/open-monitoring/dossiers/'+encodeURIComponent(period)+'/e8-runs';
  }

  rehearsal() {
    const run=this.latestRun();
    if(!run || run.state!=='REVIEWED_INTERNAL' || !run.memory || run.memory.usableForE8 || run.canIssueVcu)
      return null;
    return run.memory;
  }

  /** The quantity that explains why there is no result. Names come from the server gate. */
  blockingCause():CalculationGate|null {
    return this.blockers()[0]??null;
  }

  /**
   * Every server gate keeping this period without a result, missing data first, then review, then the rest.
   * Taken from the latest attempt when it has gates, otherwise from the current diagnosis; never recomputed here.
   */
  blockers():CalculationGate[] {
    if(this.rehearsal())return [];
    const gates=this.blockersFromRun()
      ? this.latestRun()!.gates
      : (this.readiness()?.gates??[]);
    const rank=(state:string)=>state==='MISSING'||state==='MISSING_BINDING'?0
      :state==='REVIEW_REQUIRED'?1:2;
    const seen=new Set<string>();
    return [...gates].sort((left,right)=>rank(left.state)-rank(right.state))
      .filter(gate=>!seen.has(gate.code)&&!!seen.add(gate.code));
  }

  blockersFromRun():boolean {
    return !!this.latestRun()?.gates?.length;
  }

  blockerTitle(gate:CalculationGate):string {
    return gate.code.startsWith('E8_INPUT_BINDING_')||gate.code.startsWith('E8_FLEET_SELECTION_')
      ? this.preparatoryTitle(gate) : this.gateTitle(gate);
  }

  pinLabel(pin:ParameterPin) {
    const names:Record<string,string>={
      EF_FUEL:'Fator fóssil', EF_GRID:'Fator elétrico',
      IR:'Melhoria tecnológica', DY:'Desconto por outro crédito',
    };
    return names[pin.parameterKind]??pin.parameterKind;
  }

  pinState(pin:ParameterPin) {
    if(pin.usableForE8)return 'Estado inconsistente';
    if(pin.state==='STALE_NOT_ADMITTED')return 'Desatualizado e não admitido';
    if(pin.valueKind==='DECLARED_ZERO')return 'Zero declarado, não admitido';
    if(pin.explicitOne)return '1 declarado com regra, não é default e não está admitido';
    if(pin.valueKind==='DECLARED_EXPLICIT')return 'Valor explícito, não admitido';
    return 'Referência vinculada, não admitida';
  }

  preparatoryGates():CalculationGate[] {
    return this.readiness()?.gates.filter(g=>g.code.startsWith('E8_INPUT_BINDING_')||
      g.code.startsWith('E8_FLEET_SELECTION_'))??[];
  }

  otherGates():CalculationGate[] {
    return this.readiness()?.gates.filter(g=>!g.code.startsWith('E8_INPUT_BINDING_')&&
      !g.code.startsWith('E8_FLEET_SELECTION_'))??[];
  }

  preparatoryTitle(gate:CalculationGate):string {
    const period=gate.code.split('_').at(-1)??'';
    return (gate.code.startsWith('E8_INPUT_BINDING_')?
      'Energia e fator elétrico':'Frota EVR e comparáveis')+' · '+period;
  }

  gateTitle(gate:CalculationGate):string {
    return calculationGateTitle(gate.code);
  }

  inputName(code:string):string {
    return equationTerm(code);
  }

  roleLabel(owner:string):string {
    return plannedRole(owner);
  }

  stateLabel(value:string) {
    const labels:Record<string,string>={
      BLOCKED_MISSING_DATA:'Aguardando dados e vínculos',
      BLOCKED_NOT_ADMISSIBLE:'Fora do recorte ou precisa de reavaliação',
      MISSING:'Não informado',
      MISSING_FOR_SOURCE_YEAR:'Ausente no ano das fontes',
      TIMING_UNRESOLVED:'Calendário não resolvido',
      FIRST_YEAR_REVIEW_REQUIRED:'Primeiro ano / ex ante — fundamentação pendente',
      FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED:'Primeiro ano — estimativa ex ante conferida, não admitida',
      MISSING_FOR_PROJECT_YEAR:'Avaliação estruturada ausente para os anos aplicáveis',
      PERIOD_REFERENCE_MISMATCH:'Referências incompatíveis com o período',
      ANNUAL_EVIDENCE_RECHECK_REQUIRED:'Prova anual requer rechecagem',
      CHECKED_INTERNAL_NOT_ADMITTED:'Conferido internamente — não admitido',
      STALE:'Exige rechecagem',
      OUT_OF_SCOPE:'Fora do recorte inicial',
      NOT_ADMITTED:'Sem admissão normativa',
      MISSING_BINDING:'Fonte ainda não vinculada',
      PENDING_MEASUREMENT:'Medição pendente',
      NOT_CALCULATED:'Não calculado — aguardando dados admitidos',
      REVIEW_REQUIRED:'Verificação técnica pendente',
      BLOCKED:'Bloqueado',
      REVIEWED_INTERNAL:'Ensaio interno',
    };
    return labels[value]??value;
  }
}
