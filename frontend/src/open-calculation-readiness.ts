import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
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
  templateUrl:'./open-calculation-readiness.html',
})
export class OpenCalculationReadinessComponent implements OnChanges {
  @Input({required:true}) projectId='';
  private readonly http=inject(HttpClient);
  readonly loading=signal(true);
  readonly error=signal('');
  readonly readiness=signal<OpenCalculationReadiness|null>(null);
  private requestId=0;

  ngOnChanges(changes:SimpleChanges) {
    if(changes['projectId'])void this.load();
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
    };
    return labels[value]??value;
  }
}
