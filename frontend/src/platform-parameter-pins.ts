import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Option = {projectId:string; title:string; triageVersion:number;
  pins:{parameterKind:string; vintageYear:number; version:number}[]};
type Factor = {id:string; periodYear:number; status:string; normalizedUnit:string};
type Board = {candidates:Factor[]};

/** Staff pin of a reviewed factor or an explicit IR/Dy. Not an E8 admission. */
@Component({selector:'carbonflow-platform-parameter-pins', standalone:true,
  templateUrl:'./platform-parameter-pins.html'})
export class PlatformParameterPinsComponent implements OnChanges {
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
      this.error.set(''); this.message.set('');
      if(this.validContext())void this.load();
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

  private problem(err:unknown){
    if(err instanceof HttpErrorResponse){
      const detail=err.error?.detail??err.error?.message;
      if(typeof detail==='string' && detail)return detail;
    }
    return 'Não foi possível gravar o pino.';
  }
}
