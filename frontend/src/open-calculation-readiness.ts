import {NgTemplateOutlet} from '@angular/common';
import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {calculationGateTitle, equationTerm, plannedRole} from './calculation-language';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

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
}
interface RunListing {
  period:string;
  canRecord:boolean;
  runs:CalculationRun[];
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
  imports:[NgTemplateOutlet],
  templateUrl:'./open-calculation-readiness.html',
})
export class OpenCalculationReadinessComponent implements OnChanges {
  @Input({required:true}) projectId='';
  private readonly http=inject(HttpClient);
  readonly loading=signal(true);
  readonly error=signal('');
  readonly readiness=signal<OpenCalculationReadiness|null>(null);
  readonly runPeriod=signal('M1');
  readonly canRecord=signal(false);
  readonly latestRun=signal<CalculationRun|null>(null);
  readonly recording=signal(false);
  readonly runError=signal('');
  private requestId=0;
  private runKey='';

  ngOnChanges(changes:SimpleChanges) {
    if(changes['projectId']) {
      this.runKey='';
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
    if(current===this.requestId)void this.loadRun();
  }

  async loadRun() {
    const project=this.projectId;
    const period=await this.period();
    if(project!==this.projectId)return;
    this.runPeriod.set(period);
    try {
      const listing=await firstValueFrom(this.http.get<RunListing>(this.runUrl(project,period)));
      if(project!==this.projectId)return;
      if(listing.period!==period)throw new Error('Inconsistent period');
      this.canRecord.set(listing.canRecord);
      this.latestRun.set(listing.runs[0]??null);
      this.runError.set('');
    } catch (problem) {
      if(project!==this.projectId)return;
      this.canRecord.set(false);
      this.latestRun.set(null);
      this.runError.set(problem instanceof HttpErrorResponse && [401,403,404].includes(problem.status)
        ? '' : 'Não foi possível consultar a última tentativa.');
    }
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

  private async period() {
    try {
      const dossiers=await firstValueFrom(this.http.get<{reference?:{code?:string}}[]>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+'/technical/open-monitoring/dossiers'));
      const code=dossiers.find(item=>item.reference?.code)?.reference?.code;
      if(code)return code;
    } catch {
      // Quem vê o cálculo e não o monitoramento registra a tentativa em M1.
    }
    return 'M1';
  }

  private runUrl(project:string, period:string) {
    return '/v1/projects/'+encodeURIComponent(project)
      +'/technical/open-monitoring/dossiers/'+encodeURIComponent(period)+'/e8-runs';
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
