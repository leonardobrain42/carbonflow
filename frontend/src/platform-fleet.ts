import {Component, Input, OnChanges, SimpleChanges, inject, signal, computed} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Model = {
  id:string;organizationId:string;manufacturer:string;modelName:string;
  variant:string;vehicleClass:'LDV'|'HDV'|'UNKNOWN';
  propulsion:'BEV'|'PHEV'|'OTHER'|'UNKNOWN';recordedAt:string;
};
type Alias = {organizationId:string;observationYear:number;recordKey:string;modelId:string};
type Source = {
  id:string;organizationId:string;needCode:string;state:string;sourceId:string;
  edition:string;sha256:string;rightsActive:boolean;approvedForScope:boolean;uploadedAt:string;
};
type Snapshot = {
  id:string|null;ingestionId:string;organizationId:string;dataKind:string;
  year:number;geography:string;sha256:string;denominator:number|null;modelCount:number|null;
  state:string;coverageState:string;ratingState:string;createdAt:string;usableForE8:boolean;
};
type Board = {
  canManage:boolean;canReviewCoverage?:boolean;organizations:string[];models:Model[];aliases:Alias[];
  sources:Source[];snapshots:Snapshot[];missingNeed:string;guidance:string;
};
type CoverageDecision = {
  id:string;snapshotId:string;version:number;outcome:string;
  citedTotal:number|null;gapVehicles:number|null;reason:string;
  viewState:string;usableForE8:boolean;
};
type Line = {
  sourceRow:number;recordKey:string;quantity:string|number;year:number;metric:string;
  unit:string;geography:string;modelId:string|null;modelName:string|null;
  vehicleClass:string|null;propulsion:string|null;locator:string;
};
type Preview = {
  sourceObjectId:string;needCode:string;dataKind:'STOCK_IN_CIRCULATION'|'NEW_SALES';
  observationYear:number;geography:string;sourceAssessed:boolean;rightsActive:boolean;
  denominator:number;valid:boolean;canCreateSnapshot:boolean;rows:Line[];
  issues:string[];usableForE8:boolean;
};
type Detail = {snapshot:Snapshot;weights:{
  modelId:string;modelName:string;evr:number;weight:string|number;locator:string;
}[];coverageDecision?:CoverageDecision|null};

@Component({
  selector:'carbonflow-platform-fleet',
  standalone:true,
  templateUrl:'./platform-fleet.html',
  styleUrl:'./platform-source-readiness.css',
})
export class PlatformFleetComponent implements OnChanges {
  @Input({required:true}) methodology='';
  @Input({required:true}) edition='';
  @Input({required:true}) scenario='';
  private readonly http=inject(HttpClient);
  private epoch=0;

  readonly board=signal<Board|null>(null);
  readonly preview=signal<Preview|null>(null);
  readonly detail=signal<Detail|null>(null);
  readonly busy=signal(false);
  readonly error=signal('');
  readonly message=signal('');
  available(){return this.methodology==='VM0038' && this.edition==='1.1'
    && this.scenario==='OPEN_LDV_DCFC_SIN_BR';}
  readonly candidates= computed(()=>(this.board()?.sources??[]).filter(s=>s.rightsActive));
  readonly aliasesThisYear= computed(()=>this.board()?.aliases.filter(a=>a.observationYear===Number(this.year))??[]);
  readonly missingAliases= computed(()=>this.preview()?.rows.filter(row=>!row.modelId)??[]);

  organization='';
  manufacturer='';
  modelName='';
  variant='';
  vehicleClass:'LDV'|'HDV'|'UNKNOWN'='LDV';
  propulsion:'BEV'|'PHEV'|'OTHER'|'UNKNOWN'='BEV';
  recordKey='';
  aliasModel='';
  aliasKey='';
  aliasYear='';
  sourceId='';
  year='2024';
  coverageOutcome:'NOT_COVERED'|'NO_OFFICIAL_TOTAL'='NOT_COVERED';
  coverageReason='';
  coverageCited='';
  private coverageKey='';
  private coveragePayload='';
  readonly nil='';

  ngOnChanges(changes:SimpleChanges){
    if(changes['methodology']||changes['edition']||changes['scenario']){
      ++this.epoch;
      this.board.set(null);
      this.preview.set(null);
      this.detail.set(null);
      this.sourceId='';
      if(this.available())void this.refresh();
    }
  }

  async refresh(){
    const epoch=++this.epoch;
    this.busy.set(true);this.error.set('');
    try{
      const board=await firstValueFrom(this.http.get<Board>('/v1/platform/fleet/board'));
      if(epoch!==this.epoch)return;
      this.board.set(board);
      if(!board.organizations.includes(this.organization))
        this.organization=board.organizations[0]??'';
      if(!board.sources.some(s=>s.id===this.sourceId))this.sourceId='';
    }catch(cause){if(epoch===this.epoch)this.error.set(this.problem(cause));}
    finally{if(epoch===this.epoch)this.busy.set(false);}
  }

  chooseSource(value:string){
    this.sourceId=value;
    this.preview.set(null);
    this.detail.set(null);
    this.message.set('');
  }

  async register(event:Event){
    event.preventDefault();
    if(this.busy()||!this.board()?.canManage||!this.organization)return;
    this.busy.set(true);this.error.set('');this.message.set('');
    try{
      await firstValueFrom(this.http.post<Model>('/v1/platform/fleet/models',{
        organizationId:this.organization,manufacturer:this.manufacturer,modelName:this.modelName,
        variant:this.variant,vehicleClass:this.vehicleClass,propulsion:this.propulsion,
        aliasYear:this.recordKey?Number(this.year):null,
        sourceRecordKey:this.recordKey.trim()||null,
      },{headers:{'Idempotency-Key':crypto.randomUUID()}}));
      this.message.set('Identidade e alias registrados. Classificação declarada ainda requer prova da fonte.');
      this.manufacturer='';this.modelName='';this.variant='';this.recordKey='';
      await this.refresh();
    }catch(cause){this.error.set(this.problem(cause));}
    finally{this.busy.set(false);}
  }

  async mapAlias(event:Event){
    event.preventDefault();
    if(this.busy()||!this.board()?.canManage||!this.aliasModel||!this.organization)return;
    this.busy.set(true);this.error.set('');
    try{
      await firstValueFrom(this.http.post<Alias>(
        '/v1/platform/fleet/models/'+encodeURIComponent(this.aliasModel)+'/aliases',
        {organizationId:this.organization,year:Number(this.aliasYear),sourceRecordKey:this.aliasKey},
        {headers:{'Idempotency-Key':crypto.randomUUID()}}));
      this.message.set('Alias associado explicitamente ao ano. Nenhuma equivalência foi presumida.');
      this.aliasKey='';
      this.preview.set(null);
      await this.refresh();
    }catch(cause){this.error.set(this.problem(cause));}
    finally{this.busy.set(false);}
  }

  async analyze(){
    if(!this.sourceId||this.busy())return;
    this.busy.set(true);this.error.set('');this.message.set('');
    try{
      const view=await firstValueFrom(this.http.get<Preview>(
        '/v1/platform/fleet/sources/'+encodeURIComponent(this.sourceId)+'/preview',
        {params:{year:this.year}}));
      this.preview.set(view);this.detail.set(null);
      if(view.issues.length===0){
        this.message.set('Série dimensionalmente consistente. Cobertura nacional e ratings ainda exigem revisão.');
      }
    }catch(cause){this.error.set(this.problem(cause));}
    finally{this.busy.set(false);}
  }

  async snapshot(){
    const preview=this.preview();
    if(!preview?.canCreateSnapshot||!this.board()?.canManage||this.busy())return;
    this.busy.set(true);this.error.set('');
    try{
      const result=await firstValueFrom(this.http.post<Detail>('/v1/platform/fleet/imports',
        {sourceObjectId:preview.sourceObjectId,year:preview.observationYear},
        {headers:{'Idempotency-Key':crypto.randomUUID()}}));
      this.detail.set(result);
      this.message.set(preview.dataKind==='NEW_SALES'
        ? 'Vendas arquivadas em conjunto separado, sem gerar pesos EVR.'
        : 'Retrato EVR preparatório guardado. Continua indisponível para cálculo E8.');
      await this.refresh();
    }catch(cause){this.error.set(this.problem(cause));}
    finally{this.busy.set(false);}
  }

  coverageLabel(outcome:string){
    return outcome==='NO_OFFICIAL_TOTAL'
      ? 'Sem total oficial admissível para comparar'
      : 'O retrato não cobre o estoque BEV nacional';
  }

  async decideCoverage(event:Event){
    event.preventDefault();
    const selected=this.detail();
    if(!selected?.snapshot.ingestionId || !this.board()?.canReviewCoverage || this.busy())return;
    const cited=this.coverageCited.trim();
    const body={
      outcome:this.coverageOutcome,
      reason:this.coverageReason.trim(),
      citedTotal:this.coverageOutcome==='NOT_COVERED' && cited ? Number(cited) : null,
    };
    const payload=JSON.stringify(body);
    if(!this.coverageKey || this.coveragePayload!==payload){
      this.coverageKey=crypto.randomUUID();
      this.coveragePayload=payload;
    }
    this.busy.set(true);this.error.set('');
    try{
      const saved=await firstValueFrom(this.http.post<Detail>(
        '/v1/platform/fleet/imports/'+encodeURIComponent(selected.snapshot.ingestionId)+'/coverage',
        body,{headers:{'Idempotency-Key':this.coverageKey,'If-Match':'"0"'}}));
      this.detail.set(saved);
      this.coverageKey='';
      this.coveragePayload='';
      this.message.set('Decisão de cobertura registrada. A série nacional continua não admitida.');
    }catch(cause){this.error.set(this.problem(cause));}
    finally{this.busy.set(false);}
  }

  async inspect(ingestionId:string){
    if(this.busy())return;
    this.busy.set(true);this.error.set('');
    try{
      this.detail.set(await firstValueFrom(this.http.get<Detail>(
        '/v1/platform/fleet/imports/'+encodeURIComponent(ingestionId))));
    }catch(cause){this.detail.set(null);this.error.set(this.problem(cause));}
    finally{this.busy.set(false);}
  }

  labelKind(kind:string){return kind==='STOCK_IN_CIRCULATION'
    ? 'Estoque BEV em circulação (EVR)' : 'Vendas novas — não EVR';}

  private problem(cause:unknown):string{
    if(cause instanceof HttpErrorResponse){
      if(typeof cause.error?.message==='string')return cause.error.message;
      if(cause.status===401)return 'Entre na conta para acessar o inventário.';
      if(cause.status===403||cause.status===404)return 'Fonte indisponível, licença inativa ou organização sem permissão.';
      if(cause.status===409)return 'Conflito de alias, versão ou importação. Confira a série.';
      if(cause.status===422||cause.status===400)return 'Verifique ano, chaves, classificação e dados dimensionais.';
    }
    return 'Não foi possível concluir esta operação. Verifique sua sessão e os requisitos.';
  }
}
