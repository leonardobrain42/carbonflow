import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {catchError, distinctUntilChanged, map, of, startWith, Subject, switchMap} from 'rxjs';
import {WorkspaceNavigationComponent} from './workspace-navigation';

type Role = 'DRAFT_OWNER' | 'PREP' | 'REV' | 'PP' | 'OP' | 'VVB';
type TaskState = 'NOT_STARTED' | 'NEEDS_DATA' | 'IN_PROGRESS' | 'COMPLETED' | 'IN_REVIEW' | 'RETURNED' | 'REQUIRES_RECHECK';
type TaskKind = 'TRIAGE' | 'INTAKE' | 'GUIDE' | 'DOSSIER' | 'SECTION' | 'REVIEW';
type Filter = 'all'|'actionable'|'attention'|'review';
interface Destination {area:'triage'|'technical';screen:string|null;guide:string|null;step?:string|null}
interface Checkpoint extends Destination {visitedAt:string}
interface Task {
  id:string;
  kind:TaskKind;
  title:string;
  state:TaskState;
  reason:string;
  responsible:Role;
  sourceVersion:number;
  destination:Destination|null;
  actionable:boolean;
}
interface Progress {
  visible:number;
  recorded:number;
  needsAttention:number;
  inReview:number;
  actionable:number;
}
interface DiagnosisItem {
  requirementId:string;
  situation:string;
  reason:string;
  nextAction:string;
  actionLabel:string|null;
  destination:{area:string;screen:string;step:string|null}|null;
  clientAction:boolean;
  admitsE8:boolean;
}
interface Diagnosis {
  limits:string;
  canRunE8:boolean;
  canIssueVcu:boolean;
  summary:{mine:number;awaitingSource:number;awaitingReview:number;outOfScope:number};
  requirements:DiagnosisItem[];
}
interface Journey {
  projectId:string;
  title:string;
  environment:string;
  projectState:string;
  role:Role;
  methodology:string|null;
  methodologyConfigured:boolean;
  nextTask:string|null;
  recommendedTasks?:string[];
  progress?:Progress;
  resume?:Checkpoint|null;
  tasks:Task[];
  diagnosis?:Diagnosis|null;
}
type LoadState =
  | {kind:'loading'}
  | {kind:'loaded';journey:Journey}
  | {kind:'unavailable'}
  | {kind:'error'};

/**
 * Project-specific home, fed only by /journey (server-side ACL, real revisions).
 * Counts represent visible work records, NOT VCS eligibility, approvals, or VCUs.
 */
@Component({
  selector:'carbonflow-project-overview',
  imports:[RouterLink, WorkspaceNavigationComponent],
  templateUrl:'./project-overview.html',
  styleUrl:'./project-overview.css'
})
export class ProjectOverviewComponent {
  private readonly route=inject(ActivatedRoute);
  private readonly http=inject(HttpClient);
  private readonly refresh=new Subject<void>();

  readonly filter=signal<Filter>('all');
  readonly term=signal('');
  readonly state=toSignal(this.route.paramMap.pipe(
    map(params=>params.get('projectId')??''),
    distinctUntilChanged(),
    switchMap(project=>this.refresh.pipe(
      startWith(undefined),
      switchMap(()=>this.http.get<Journey>(
        '/v1/projects/'+encodeURIComponent(project)+'/journey').pipe(
        map((journey):LoadState=>
          journey.projectId===project&&journey.environment==='SANDBOX'
            ? {kind:'loaded',journey} : {kind:'unavailable'}),
        catchError((cause:unknown)=>of<LoadState>({
          kind:cause instanceof HttpErrorResponse&&[401,403,404].includes(cause.status)
            ? 'unavailable':'error'
        })),
        startWith<LoadState>({kind:'loading'})
      ))
    ))
  ),{initialValue:{kind:'loading'} as LoadState});

  readonly journey=computed(()=>{
    const state=this.state();return state.kind==='loaded'?state.journey:null;
  });
  readonly next=computed(()=>{
    const journey=this.journey();
    return journey?.tasks.find(task=>task.id===journey.nextTask)??null;
  });
  readonly diagnosisActions=computed(()=>{
    const diagnosis=this.journey()?.diagnosis;
    if(!diagnosis||diagnosis.canRunE8||diagnosis.canIssueVcu)return [];
    return diagnosis.requirements.filter(item=>item.clientAction&&item.destination&&!item.admitsE8).slice(0,3);
  });
  readonly recommended=computed(()=>{
    const j=this.journey();if(!j)return [];
    const candidates=j.recommendedTasks??(j.nextTask?[j.nextTask]:[]);
    return candidates.map(id=>j.tasks.find(t=>t.id===id))
      .filter((t):t is Task=>!!t&&t.actionable&&!!t.destination).slice(0,3);
  });
  readonly summary=computed(()=>{
    const j=this.journey();
    if(!j)return {visible:0,recorded:0,needsAttention:0,inReview:0,actionable:0};
    return j.progress??{
      visible:j.tasks.length,
      recorded:j.tasks.filter(t=>t.state==='COMPLETED').length,
      needsAttention:j.tasks.filter(t=>this.isAttention(t)).length,
      inReview:j.tasks.filter(t=>t.state==='IN_REVIEW').length,
      actionable:j.tasks.filter(t=>t.actionable).length
    };
  });
  readonly percent=computed(()=>{
    const summary=this.summary();
    return summary.visible?Math.round(100*summary.recorded/summary.visible):0;
  });
  readonly attention=computed(()=>this.journey()?.tasks.filter(task=>this.isAttention(task))??[]);
  readonly resumed=computed(()=>{
    const j=this.journey();
    if(!j?.resume)return null;
    // Checkpoint is validated against current permissions by the server,
    // but the front only constructs a route from known area/screen enums.
    const p=j.resume;
    if(!['triage','technical'].includes(p.area))return null;
    return p;
  });
  readonly resumeTitle=computed(()=>{
    const r=this.resumed();if(!r)return '';
    if(r.area==='triage')return 'Triagem guiada';
    const names:Record<string,string>={
      inicio:'Painel de trabalho',entender:'Entender a metodologia',
      preparar:'Informações iniciais',analisar:'Aplicabilidade',
      estacoes:'Estações e fronteira', 'linha-base':'Linha de base',
      adicionalidade:'Adicionalidade', monitorar:'Monitoramento',
      evidencias:'Evidências',quantificar:'Quantificação',
      fontes:'Fontes e parâmetros',guias:'Guias técnicos',
      redigir:'Redação do PD',revisar:'Revisão do PD'
    };
    return names[r.screen??'']??'Preparação técnica';
  });
  readonly visibleTasks=computed(()=>{
    const j=this.journey();
    const query=this.term().normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLocaleLowerCase().trim();
    return (j?.tasks??[]).filter(task=>{
      if(this.filter()==='actionable'&&!task.actionable)return false;
      if(this.filter()==='attention'&&!this.isAttention(task))return false;
      if(this.filter()==='review'&&task.state!=='IN_REVIEW'&&task.kind!=='REVIEW')return false;
      if(!query)return true;
      return [task.title,task.reason,task.responsible,this.stateNames[task.state]]
        .some(value=>value.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLocaleLowerCase().includes(query));
    });
  });
  readonly groups=computed(()=>{
    const tasks=this.visibleTasks();
    return [
      {title:'Planejar e desenvolver',description:'Escopo, informações, fundamentação e guias.',
        items:tasks.filter(t=>['TRIAGE','INTAKE','GUIDE'].includes(t.kind))},
      {title:'Preparar documentos',description:'Prévia do dossiê e seções do documento do projeto.',
        items:tasks.filter(t=>['DOSSIER','SECTION'].includes(t.kind))},
      {title:'Revisar',description:'Pareceres internos registrados por atores autorizados.',
        items:tasks.filter(t=>t.kind==='REVIEW')}
    ].filter(g=>g.items.length).map(g=>({
      ...g,hasNext:g.items.some(task=>task.id===this.next()?.id)
    }));
  });
  readonly roleNames:Record<Role,string>={
    DRAFT_OWNER:'Titular do rascunho',
    PREP:'Preparação técnica (PREP)',
    REV:'Revisão interna (REV)',
    PP:'Proponente (PP)',
    OP:'Operação e monitoramento (OP)',
    VVB:'Validação ou verificação externa (VVB)'
  };
  readonly stateNames:Record<TaskState,string>={
    NOT_STARTED:'A iniciar',
    NEEDS_DATA:'Aguardando informações',
    IN_PROGRESS:'Em preparação',
    COMPLETED:'Registro concluído',
    IN_REVIEW:'Em revisão interna',
    RETURNED:'Devolvida para ajuste',
    REQUIRES_RECHECK:'Precisa de rechecagem'
  };
  readonly filters:{id:Filter;label:string}[]=[
    {id:'all',label:'Todas'},
    {id:'actionable',label:'Posso agir'},
    {id:'attention',label:'Pendências'},
    {id:'review',label:'Revisão'}
  ];

  private isAttention(task:Task) {
    return ['NEEDS_DATA','REQUIRES_RECHECK','RETURNED'].includes(task.state);
  }
  reload(){this.refresh.next();}
  clearFilters(){this.term.set('');this.filter.set('all');}
  routeFor(project:string,destination:Destination){
    return ['/workspace/projects',project,destination.area];
  }
  queryFor(destination:Destination){
    return destination.step
      ? {secao:destination.screen,guia:destination.guide,etapa:destination.step}
      : {secao:destination.screen,guia:destination.guide};
  }
  stateTone(state:TaskState){
    return ['RETURNED','REQUIRES_RECHECK'].includes(state)?'critical':
      ['NEEDS_DATA','IN_REVIEW'].includes(state)?'pending':
        state==='COMPLETED'?'recorded':'neutral';
  }
}
