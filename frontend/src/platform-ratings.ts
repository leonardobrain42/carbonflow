import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Model={id:string;organizationId:string;title:string;vehicleClass:string;propulsion:string};
type Source={id:string;organizationId:string;edition:string;sha256:string;state:string};
type Fleet={id:string;organizationId:string;year:number;denominator:number;coverageState:string};
type Candidate={row:number;key:string;metric:string;value:number|string;
    unit:string;year:number;geography:string;locator:string};
type Rating={id:string;organizationId:string;modelId:string;sourceObjectId:string;
  sourceRow:number;year:number;role:'BEV'|'FOSSIL';fuel:string;cycle:string;seats:number;
  originalValue:string|number;originalUnit:string;normalizedValue:string|number;
  normalizedUnit:string;locator:string;status:string;createdBy:string};
type Pair={id:string;organizationId:string;fleetSnapshotId:string;bevModelId:string;
  bevRatingId:string;fossilRatingId:string;status:string;version:number;
  reason:string;serviceDescription:string;proposedBy:string;
  reviewedBy:string|null;reviewReason:string|null};
type Snapshot={id:string;organizationId:string;fleetSnapshotId:string;
  year:number;cycle:string;afecKwh100mi:string|number|null;mpgUs:string|number|null;
  modelCount:number|null;status:string;usableForE8:boolean};
type Board={actorId:string;canManage:boolean;canReview:boolean;models:Model[];
  sources:Source[];fleets:Fleet[];ratings:Rating[];pairs:Pair[];snapshots:Snapshot[];
  guidance:string};
type Line={modelId:string;modelName:string;evr:number;pairId:string|null;
  kwh100mi:string|number|null;mpg:string|number|null;cycle:string|null;status:string};
type Composition={fleetSnapshotId:string;year:number;total:number|null;
  afecKwh100mi:string|number|null;mpgUs:string|number|null;cycle:string|null;
  lines:Line[];issues:string[];canSavePreparatory:boolean;usableForE8:boolean};

type HandoffOption={projectId:string;period:string;organizationId:string;
  energyBindingId:string;energyBindingVersion:number;year:number;
  currentSelectionVersion:number};
type HandoffResult={id:string;projectId:string;period:string;version:number;
  ratingSnapshotId:string;state:string;usableForE8:boolean};

@Component({
  selector:'carbonflow-platform-ratings',
  standalone:true,
  templateUrl:'./platform-ratings.html',
  styleUrl:'./platform-source-readiness.css',
})
export class PlatformRatingsComponent implements OnChanges {
  @Input({required:true}) methodology='';
  @Input({required:true}) edition='';
  @Input({required:true}) scenario='';
  private readonly http=inject(HttpClient);
  private epoch=0;
  readonly board=signal<Board|null>(null);
  readonly candidates=signal<Candidate[]>([]);
  readonly composition=signal<Composition|null>(null);
  readonly handoffOptions=signal<HandoffOption[]>([]);
  readonly selectedHandoff=signal('');
  readonly selectedHandoffRating=signal('');
  readonly handoffBusy=signal(false);
  readonly busy=signal(false);
  readonly error=signal('');
  readonly message=signal('');
  readonly validContext=()=>this.methodology==='VM0038' && this.edition==='1.1'
    && this.scenario==='OPEN_LDV_DCFC_SIN_BR';

  organization='';
  sourceId='';
  fleetId='';
  modelId='';
  rowIndex='';
  ratingRole:'BEV'|'FOSSIL'='BEV';
  fuel='ELECTRICITY';
  cycle='COMBINED';
  seats='5';
  cycleEvidence='';
  evRatingId='';
  fossilRatingId='';
  serviceDescription='';
  pairReason='';
  reviewReasons:Record<string,string>={};
  reviewDecision:Record<string,'ACCEPTED'|'REJECTED'>={};
  readonly cycles=['URBAN','HIGHWAY','COMBINED'];

  ngOnChanges(c:SimpleChanges){
    if(c['methodology']||c['edition']||c['scenario']){
      this.board.set(null);this.candidates.set([]);this.composition.set(null);
      ++this.epoch;
      if(this.validContext())void this.reload();
    }
  }

  async reload(){
    if(!this.validContext())return;
    const epoch=++this.epoch;
    this.busy.set(true);this.error.set('');
    try{
      const data=await firstValueFrom(this.http.get<Board>('/v1/platform/vehicle-ratings/board'));
      if(epoch!==this.epoch)return;
      this.board.set(data);
      if(data.canManage)await this.reloadHandoffOptions();
      if(!data.fleets.some(f=>f.id===this.fleetId))this.fleetId=data.fleets[0]?.id??'';
      if(!data.sources.some(s=>s.id===this.sourceId)){
        this.sourceId='';this.candidates.set([]);
      }
      if(!data.models.some(m=>m.id===this.modelId))this.modelId='';
      if(this.fleetId)await this.loadComposition();
    }catch(problem){if(epoch===this.epoch)this.error.set(this.issue(problem));}
    finally{if(epoch===this.epoch)this.busy.set(false);}
  }

  sourceChanged(value:string){this.sourceId=value;this.candidates.set([]);this.rowIndex='';}
  async loadCandidates(){
    if(!this.sourceId||this.busy())return;
    this.busy.set(true);this.error.set('');
    try{
      this.candidates.set(await firstValueFrom(this.http.get<Candidate[]>(
        '/v1/platform/vehicle-ratings/sources/'+encodeURIComponent(this.sourceId)+'/candidates')));
    }catch(problem){this.error.set(this.issue(problem));}
    finally{this.busy.set(false);}
  }
  async register(event:Event){
    event.preventDefault();
    if(!this.board()?.canManage||!this.sourceId||!this.modelId||this.busy())return;
    this.busy.set(true);this.error.set('');this.message.set('');
    try{
      const src=this.board()?.sources.find(x=>x.id===this.sourceId);
      if(!src)throw Error('source');
      await firstValueFrom(this.http.post<Rating>('/v1/platform/vehicle-ratings/ratings',{
        organizationId:src.organizationId,sourceObjectId:this.sourceId,modelId:this.modelId,
        sourceRow:Number(this.rowIndex),role:this.ratingRole,fuel:this.fuel,
        cycle:this.cycle,seats:Number(this.seats),cycleEvidence:this.cycleEvidence,
      },{headers:{'Idempotency-Key':crypto.randomUUID()}}));
      this.message.set('Rating candidato registrado com origem, unidade e conversão. Não é uma aprovação.');
      this.cycleEvidence='';
      await this.reload();
    }catch(problem){this.error.set(this.issue(problem));}
    finally{this.busy.set(false);}
  }

  roleChanged(value:string){
    this.ratingRole=value==='FOSSIL'?'FOSSIL':'BEV';
    this.fuel=this.ratingRole==='BEV'?'ELECTRICITY':'GASOLINE';
    this.modelId='';
  }

  fleetChanged(value:string){
    this.fleetId=value;this.composition.set(null);
    if(value)void this.loadComposition();
  }
  async propose(event:Event){
    event.preventDefault();
    if(!this.board()?.canManage||!this.fleetId||this.busy())return;
    const fleet=this.board()?.fleets.find(f=>f.id===this.fleetId);
    if(!fleet)return;
    this.busy.set(true);this.error.set('');this.message.set('');
    try{
      await firstValueFrom(this.http.post<Pair>('/v1/platform/vehicle-ratings/pairs',{
        organizationId:fleet.organizationId,fleetSnapshotId:this.fleetId,
        bevRatingId:this.evRatingId,fossilRatingId:this.fossilRatingId,
        serviceDescription:this.serviceDescription,reason:this.pairReason,
      },{headers:{'Idempotency-Key':crypto.randomUUID()}}));
      this.message.set('Par proposto. Outra pessoa habilitada deve revisar categoria, capacidade e ciclo.');
      this.evRatingId='';this.fossilRatingId='';this.pairReason='';
      await this.reload();
    }catch(problem){this.error.set(this.issue(problem));}
    finally{this.busy.set(false);}
  }
  async review(pair:Pair){
    if(!this.board()?.canReview||this.busy())return;
    const outcome=this.reviewDecision[pair.id]??'REJECTED';
    const reason=this.reviewReasons[pair.id]??'';
    this.busy.set(true);this.error.set('');this.message.set('');
    try{
      await firstValueFrom(this.http.post<Pair>(
        '/v1/platform/vehicle-ratings/pairs/'+encodeURIComponent(pair.id)+'/review',
        {outcome,reason},{headers:{'If-Match':'"'+pair.version+'"'}}));
      this.message.set(outcome==='ACCEPTED'
        ? 'Parecer de comparabilidade registrado. Ainda não autoriza o cálculo E8.'
        : 'Par rejeitado com justificativa preservada.');
      await this.reload();
    }catch(problem){this.error.set(this.issue(problem));}
    finally{this.busy.set(false);}
  }

  async loadComposition(){
    if(!this.fleetId)return;
    const id=this.fleetId;
    try{
      const result=await firstValueFrom(this.http.get<Composition>(
        '/v1/platform/vehicle-ratings/fleets/'+encodeURIComponent(id)+'/composition'));
      if(this.fleetId===id)this.composition.set(result);
    }catch(problem){this.error.set(this.issue(problem));}
  }

  async freeze(){
    const result=this.composition();
    if(!result?.canSavePreparatory||!this.board()?.canManage||this.busy())return;
    this.busy.set(true);this.error.set('');
    try{
      await firstValueFrom(this.http.post<Snapshot>(
        '/v1/platform/vehicle-ratings/fleets/'+encodeURIComponent(result.fleetSnapshotId)+'/snapshots',
        {},{headers:{'Idempotency-Key':crypto.randomUUID()}}));
      this.message.set('Snapshot preparatório congelado. E8 continua bloqueado.');
      await this.reload();
    }catch(problem){this.error.set(this.issue(problem));}
    finally{this.busy.set(false);}
  }

  async reloadHandoffOptions(){
    if(!this.board()?.canManage)return;
    try{
      const options=await firstValueFrom(this.http.get<HandoffOption[]>(
        '/v1/platform/e8-fleet-selections/options'));
      this.handoffOptions.set(options);
      if(!options.some(x=>x.energyBindingId===this.selectedHandoff))
        this.selectedHandoff='';
      this.selectedHandoffRating='';
    }catch(problem){this.error.set(this.issue(problem));}
  }
  chosenHandoff(){
    return this.handoffOptions().find(x=>x.energyBindingId===this.selectedHandoff);
  }
  matchingSnapshots(){
    const h=this.chosenHandoff();
    return (this.board()?.snapshots??[]).filter(s=>
      !!h&&s.organizationId===h.organizationId&&s.year===h.year&&
      s.status==='PREPARATORY');
  }
  async assignHandoff(event:Event){
    event.preventDefault();
    const h=this.chosenHandoff();
    const rating=this.matchingSnapshots().find(s=>s.id===this.selectedHandoffRating);
    if(!h||!rating||!this.board()?.canManage||this.handoffBusy())return;
    this.handoffBusy.set(true);this.error.set('');this.message.set('');
    try{
      const result=await firstValueFrom(this.http.post<HandoffResult>(
        '/v1/platform/e8-fleet-selections',{
          projectId:h.projectId,period:h.period,energyBindingId:h.energyBindingId,
          ratingSnapshotId:rating.id
        },{headers:{'Idempotency-Key':crypto.randomUUID(),
          'If-Match':'"'+h.currentSelectionVersion+'"'}}));
      if(result.usableForE8)throw new Error('Unexpected E8 admission');
      this.message.set('Referências EVR/PBEV publicadas no projeto como PREPARATÓRIAS. '+
        'Nenhum fator ou resultado E8 foi admitido.');
      await this.reloadHandoffOptions();
    }catch(problem){this.error.set(this.issue(problem));}
    finally{this.handoffBusy.set(false);}
  }

  modelTitle(modelId:string){
    return this.board()?.models.find(m=>m.id===modelId)?.title??'Modelo sem associação';
  }
  eligibleModels(){return (this.board()?.models??[]).filter(m=>
    m.organizationId===this.board()?.sources.find(s=>s.id===this.sourceId)?.organizationId &&
    m.vehicleClass==='LDV' && (this.ratingRole==='BEV'?m.propulsion==='BEV':m.propulsion==='OTHER'));
  }
  ratingsFor(role:'BEV'|'FOSSIL'){return (this.board()?.ratings??[]).filter(r=>
    r.role===role && this.board()?.fleets.find(f=>f.id===this.fleetId)?.organizationId===r.organizationId);}
  private issue(problem:unknown){
    if(problem instanceof HttpErrorResponse){
      if(problem.status===401)return 'Entre em uma conta autorizada.';
      if([403,404].includes(problem.status))return 'Sem direitos, papel interno ou acesso à fonte/organização.';
      if(problem.status===409)return 'Conflito de versões, rating, direitos ou comparabilidade. Revise as evidências.';
      if(problem.status===412)return 'O parecer foi alterado por outra pessoa. Atualize a lista.';
      if(problem.status===422)return 'Verifique ciclo, unidade, linha da fonte, modelo, assentos e fundamentação.';
    }
    return 'Não foi possível concluir a operação. Consulte os dados e tente novamente.';
  }
}
