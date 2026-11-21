import {Component, Input, OnChanges, SimpleChanges, computed, inject, output, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {TypedGuideComponent} from './typed-guide';
import {PdTraceComponent} from './pd-trace';
import {calculationGateTitle, plannedRole} from './calculation-language';

export type WorkbenchKind='baseline'|'additionality';
type Field={key:string;label:string;type:string;unit:string|null;helpText:string|null};
type Guide={id:string;title:string;version:number;definitionVersion:number;edition:string;
  methodology:string;fields:Field[];answers:Record<string,unknown>;state:string;
  missing:string[];legacyNeedsReview:string[];normativeApproved:boolean};
type TypedCatalog={methodology:string;guides:Guide[]};
type Finding={code:string;state:string;text:string};
type AppView={projectId:string;version:number;readiness:{network:string;rights:string;
  norm:string;stale:boolean;canUseForE8:boolean;findings:Finding[]};
  latest:{answers:{chargingOwner:string|null;carbonRightsContractRef:string|null;
    driverDisclosureChannel:string|null;driverDisclosureEvidenceRef:string|null;
    driverDisclosureEffectiveOn:string|null;effectiveFrom:string|null;
    doubleClaimingCheck:string|null}}|null};
type Gate={code:string;state:string;reason:string;nextAction:string;owner:string};
type Calc={projectId:string;environment:string;calculationState:string;canRunE8:boolean;
  gates:Gate[]};
type Dossier={reference:{code:string;version:number};state:string;stale:boolean;gates:{code:string;detail:string}[]};
type Selection={id:string;projectId:string;period:string;version:number;observationYear:number;
  cycle:string;state:string;stale:boolean;usableForE8:boolean;selectionSha256:string;gates:string[]};
export type PdSection={guide:{id:string;title:string;version:number;missing:string[]};
  latest:{number:number;guideVersion:number;sha256:string}|null;
  monitoringDependency:{state:string;outdated:boolean}|null};
type SourceStatus='not_requested'|'loading'|'ready'|'restricted'|'error';
type SourceName='typed'|'applicability'|'calculation'|'monitoring'|'fleet'|'pd';

const STEPS:Record<WorkbenchKind,{id:string;label:string;detail:string}[]>={
  baseline:[
    {id:'contexto',label:'1. Cenário',detail:'Fronteira, modalidade e situação da linha de base'},
    {id:'frota',label:'2. EVR e comparáveis',detail:'Snapshots vinculados ao projeto e lacunas de frota'},
    {id:'fatores',label:'3. Fatores e E8',detail:'Dependências de fatores, período e cálculo'},
    {id:'premissas',label:'4. Premissas',detail:'Respostas tipadas, unidades e justificativa'},
    {id:'revisao',label:'5. Revisão',detail:'Pendências por fonte, responsável e próximo passo'},
    {id:'pd',label:'6. Vínculo ao PD',detail:'Prévia de proveniência e estado do documento'}
  ],
  additionality:[
    {id:'enquadramento',label:'1. Enquadramento',detail:'Rota candidata, critérios e excedente regulatório'},
    {id:'cronologia',label:'2. Cronologia',detail:'Datas distintas e suas pendências documentais'},
    {id:'direitos',label:'3. Direitos',detail:'Titularidade, divulgação e duplo pleito'},
    {id:'revisao',label:'4. Revisão',detail:'Condições, fontes e pareceres pendentes'},
    {id:'pd',label:'5. Vínculo ao PD',detail:'Prévia de proveniência e estado do documento'}
  ]
};

/**
 * Read-only orchestration of existing project-scoped owner APIs. The only editor
 * is the canonical typed guide. No EVR, rating, normative opinion, rights or PD
 * document is reconstructed or persisted by this component.
 */
@Component({
  selector:'carbonflow-decision-workbench',standalone:true,
  imports:[TypedGuideComponent,PdTraceComponent],templateUrl:'./decision-workbench.html'
})
export class DecisionWorkbenchComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input({required:true}) kind:WorkbenchKind='baseline';
  @Input() requestedStep:string|null=null;
  @Input() canViewApplicability=false;
  @Input() canViewCalculation=false;
  @Input() canViewMonitoring=false;
  @Input() canViewFleet=false;
  @Input() canViewPd=false;
  @Input() canSaveGuide=false;

  readonly navigate=output<string>();
  readonly openSection=output<string>();
  readonly openGuide=output<string>();
  readonly openMonitoring=output<void>();
  readonly openApplicability=output<void>();
  readonly openCalculation=output<void>();
  readonly pd=output<string>();
  readonly steps=computed(()=>STEPS[this.kind]);
  readonly step=computed(()=>STEPS[this.kind].find(s=>s.id===this.currentStep())??STEPS[this.kind][0]);
  readonly currentStep=signal('contexto');
  readonly typed=signal<TypedCatalog|null>(null);
  readonly applicability=signal<AppView|null>(null);
  readonly calculation=signal<Calc|null>(null);
  readonly dossiers=signal<Dossier[]>([]);
  readonly fleet=signal<Selection[]>([]);
  readonly pdSections=signal<PdSection[]>([]);
  readonly status=signal<Record<SourceName,SourceStatus>>({
    typed:'not_requested',applicability:'not_requested',calculation:'not_requested',
    monitoring:'not_requested',fleet:'not_requested',pd:'not_requested'
  });
  readonly failures=signal<Partial<Record<SourceName,string>>>({});
  readonly loading=signal(false);
  readonly guide=computed(()=>this.typed()?.guides.find(g=>g.id===this.kind)??null);
  readonly currentPd=computed(()=>this.pdSections().find(s=>s.guide.id===this.kind)??null);
  readonly periodCodes=computed(()=>this.dossiers().map(x=>x.reference.code));
  readonly fleetGates=computed(()=>this.calculation()?.gates.filter(g=>g.code.startsWith('E8_FLEET_SELECTION_'))??[]);
  readonly baselineGates=computed(()=>this.calculation()?.gates.filter(g=>
    /FLEET|RATING|FOSSIL|FUEL|EFEL|TOOL07|VT0011|INPUT_BINDING|MARKET|MR_PERIOD/.test(g.code))??[]);
  readonly additionalityGates=computed(()=>this.applicability()?.readiness.findings.filter(g=>
    /VMD0049|MARKET_ROUTE|REGULATORY_SURPLUS|OWNER_CONTRACT|DRIVER_DISCLOSURE|RIGHTS_DATE_GAP|DOUBLE_CLAIMING|TRIAGE_CHANGED/.test(g.code))??[]);
  readonly facts=computed(()=>{
    const g=this.guide();return (g?.fields??[]).filter(f=>g?.answers[f.key]!==undefined &&
      g.answers[f.key]!==null);
  });
  readonly chronologicalFacts=computed(()=>{
    const g=this.guide();
    return ['considered','decision','commitment'].map(key=>{
      const f=g?.fields.find(f=>f.key===key);const value=g?.answers[key];
      return {key,label:f?.label??key,date:typeof value==='string'?value:null};
    });
  });
  private readonly http=inject(HttpClient);
  private readonly editor=viewChild(TypedGuideComponent);
  private sequence=0;

  ngOnChanges(changes:SimpleChanges){
    const valid=STEPS[this.kind].some(s=>s.id===this.requestedStep);
    this.currentStep.set(valid?this.requestedStep!:STEPS[this.kind][0].id);
    if(changes['kind']||changes['projectId']||changes['canViewApplicability']||
      changes['canViewCalculation']||changes['canViewMonitoring']||changes['canViewFleet']||
      changes['canViewPd'])void this.load();
  }
  canLeave(){return this.editor()?.canLeave()??true;}
  selectStep(id:string){
    if(!STEPS[this.kind].some(s=>s.id===id)||!this.canLeave())return;
    this.currentStep.set(id);
    this.navigate.emit(id);
  }
  hasFleetFor(period:string){return this.fleet().some(item=>item.period===period);}
  editingStep(){
    return this.step().id==='premissas'||this.step().id==='cronologia';
  }
  nextStepId(){
    const all=STEPS[this.kind];
    return all[Math.min(all.findIndex(item=>item.id===this.currentStep())+1,all.length-1)].id;
  }
  failureEntries(){
    return (Object.entries(this.failures()) as [SourceName,string][])
      .filter(([,message])=>!!message)
      .map(([name,message])=>({name,message}));
  }
  statusOf(source:SourceName){return this.status()[source];}
  readable(source:SourceName){return this.statusOf(source)==='ready';}
  async load(){
    const id=++this.sequence;
    const project=this.projectId;
    if(!project)return;
    this.loading.set(true);
    this.typed.set(null);this.applicability.set(null);this.calculation.set(null);
    this.dossiers.set([]);this.fleet.set([]);this.pdSections.set([]);
    this.failures.set({});
    const active:Record<SourceName,boolean>={
      typed:true,applicability:this.canViewApplicability,
      calculation:this.kind==='baseline'&&this.canViewCalculation,
      monitoring:this.kind==='baseline'&&this.canViewMonitoring,
      fleet:this.kind==='baseline'&&this.canViewFleet&&this.canViewMonitoring,
      pd:this.canViewPd
    };
    this.status.set({
      typed:'loading',
      applicability:active.applicability?'loading':'restricted',
      calculation:active.calculation?'loading':'restricted',
      monitoring:active.monitoring?'loading':'restricted',
      fleet:active.fleet?'loading':'restricted',
      pd:active.pd?'loading':'restricted'
    });
    const base='/v1/projects/'+encodeURIComponent(project)+'/technical';
    const get=async<T>(source:SourceName,url:string,apply:(v:T)=>void)=>{
      try{
        const value=await firstValueFrom(this.http.get<T>(url));
        if(id!==this.sequence)return;
        apply(value);
        this.status.update(s=>({...s,[source]:'ready'}));
      }catch(error){
        if(id!==this.sequence)return;
        const unauthorized=error instanceof HttpErrorResponse&&
          [401,403,404].includes(error.status);
        this.status.update(s=>({...s,[source]:unauthorized?'restricted':'error'}));
        this.failures.update(s=>({...s,[source]:unauthorized?'Acesso não concedido a esta fonte.':
          'Falha na leitura da fonte. Atualize para tentar novamente.'}));
      }
    };
    const actions:Promise<void>[]=[
      get<TypedCatalog>('typed',base+'/typed-guides',value=>this.typed.set(value))
    ];
    if(active.applicability)actions.push(get<AppView>('applicability',base+'/open-applicability',
      value=>this.applicability.set(value)));
    if(active.calculation)actions.push(get<Calc>('calculation',base+'/open-calculation-readiness',
      value=>{if(value.projectId!==project||value.environment!=='SANDBOX')
        throw new Error('Contexto de projeto incorreto');this.calculation.set(value);}));
    if(active.pd)actions.push(get<PdSection[]>('pd',base+'/pd-sections',
      value=>this.pdSections.set(value)));
    if(active.monitoring){
      actions.push(get<Dossier[]>('monitoring',base+'/open-monitoring/dossiers',
        value=>this.dossiers.set(value)).then(async()=>{
        if(id!==this.sequence||!active.fleet)return;
        const periods=this.dossiers().map(d=>d.reference.code).slice(0,20);
        if(!periods.length){this.status.update(s=>({...s,fleet:'ready'}));return;}
        const selections=await Promise.all(periods.map(async period=>{
          try{
            return await firstValueFrom(this.http.get<Selection[]>(
              base+'/open-monitoring/dossiers/'+encodeURIComponent(period)+'/e8-fleet-selections'));
          }catch(e){
            if(id===this.sequence){
              this.failures.update(s=>({...s,fleet:'Alguns períodos não puderam ser consultados; não presumimos ausência de vínculo.'}));
            }
            return [] as Selection[];
          }
        }));
        if(id!==this.sequence)return;
        this.fleet.set(selections.flat().filter(s=>s.projectId===project));
        this.status.update(s=>({...s,fleet:this.failures().fleet?'error':'ready'}));
      }));
    }
    await Promise.all(actions);
    if(id===this.sequence)this.loading.set(false);
  }
  summary(value:unknown):string{
    if(Array.isArray(value))return value.join(', ');
    if(value===true)return 'Sim (declarado)';
    if(value===false)return 'Não (declarado)';
    if(value===null||value===undefined||value==='')return 'Não informado';
    return String(value);
  }
  gateName(code:string){return calculationGateTitle(code);}
  roleLabel(owner:string){return plannedRole(owner);}
  labelState(value:string){
    const map:Record<string,string>={
      FORM_COMPLETED_DECLARED:'Campos declarados, sem comprovação',
      INCOMPLETE_DECLARED:'Campos incompletos',
      LEGACY_UNSTRUCTURED_REVIEW_REQUIRED:'Legado exige reconciliação',
      STALE_REFERENCE_REVIEW_REQUIRED:'Referência alterada — rechecagem',
      NOT_STARTED:'Não iniciado',OPEN_CANDIDATE:'Rede aberta candidata',
      PREPARATORY_NOT_ADMITTED:'Snapshot preparatório — não admitido',
      STALE_NOT_ADMITTED:'Vínculo obsoleto — não admitido',
      MISSING:'Dado faltante',NOT_ADMITTED:'Ainda não admitido',
      REVIEW_REQUIRED:'Revisão necessária',
      BLOCKED_MISSING_DATA:'Aguardando dados e vínculos',
      BLOCKED_NOT_ADMISSIBLE:'Fora do recorte ou precisa de reavaliação',
      MISSING_BINDING:'Fonte ainda não vinculada',
      PENDING_MEASUREMENT:'Medição pendente',
      READY_FOR_NORMATIVE_REVIEW:'Pronto para revisão normativa',
      EVIDENCE_PENDING:'Prova ainda pendente',
      STALE:'Exige rechecagem',
      OUT_OF_SCOPE:'Fora do recorte inicial',
    };
    return map[value]??value;
  }
}
