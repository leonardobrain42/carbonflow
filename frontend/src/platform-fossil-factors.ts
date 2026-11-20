import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Source={id:string;organizationId:string;edition:string;year:number;sha256:string};
type Row={rowIndex:number;recordKey:string;year:number;geography:string;
  unit:string;value:string|number;locator:string};
type Factor={id:string;organizationId:string;sourceObjectId:string;
  sourceLicenseId:string;sourceRow:number;sourceSha256:string;
  sourceLocator:string|null;periodYear:number;fuel:string;gas:string;
  originalUnit:string;originalValue:string|number|null;
  normalizedUnit:string;normalizedValue:string|number|null;
  conversionFormula:string;status:string;reviewOutcome:string|null;
  reviewRule:string|null;reviewEvidence:string|null;reviewReason:string|null;
  rightsActive:boolean;usableForE8:boolean};
type Board={actorId:string;canManage:boolean;canReview:boolean;
  sources:Source[];candidates:Factor[]};

/** Admin-only preparation, not a project or real E8 calculation view. */
@Component({selector:'carbonflow-platform-fossil-factors',standalone:true,
  templateUrl:'./platform-fossil-factors.html'})
export class PlatformFossilFactorsComponent implements OnChanges {
  @Input({required:true}) methodology='';
  @Input({required:true}) edition='';
  @Input({required:true}) scenario='';
  private readonly http=inject(HttpClient);
  private readonly root='/v1/platform/open-fossil-factors';
  private epoch=0;
  readonly board=signal<Board|null>(null);
  readonly rows=signal<Row[]>([]);
  readonly error=signal('');
  readonly message=signal('');
  readonly busy=signal(false);
  sourceId='';
  rowIndex='';
  reviewReason:Record<string,string>={};
  reviewRule:Record<string,string>={};
  reviewEvidence:Record<string,string>={};
  reviewOutcome:Record<string,string>={};
  readonly validContext=()=>this.methodology==='VM0038' &&
    this.edition==='1.1' && this.scenario==='OPEN_LDV_DCFC_SIN_BR';

  ngOnChanges(changes:SimpleChanges){
    if(changes['methodology']||changes['edition']||changes['scenario']){
      ++this.epoch;this.board.set(null);this.rows.set([]);
      this.sourceId='';this.rowIndex='';this.error.set('');this.message.set('');
      if(this.validContext())void this.load();
    }
  }
  async load(){
    if(!this.validContext())return;
    const current=++this.epoch;
    this.busy.set(true);this.error.set('');
    try{
      const result=await firstValueFrom(this.http.get<Board>(this.root));
      if(current===this.epoch){this.board.set(result);this.rows.set([]);
        if(!result.sources.some(x=>x.id===this.sourceId))this.sourceId='';}
    }catch(err){if(current===this.epoch)this.error.set(this.problem(err));}
    finally{if(current===this.epoch)this.busy.set(false);}
  }
  async chooseSource(value:string){
    this.sourceId=value;this.rowIndex='';this.rows.set([]);
    const current=++this.epoch;
    if(!value)return;
    this.busy.set(true);this.error.set('');
    try{
      const data=await firstValueFrom(this.http.get<Row[]>(
        this.root+'/sources/'+encodeURIComponent(value)+'/rows'));
      if(current===this.epoch && value===this.sourceId)this.rows.set(data);
    }catch(err){if(current===this.epoch)this.error.set(this.problem(err));}
    finally{if(current===this.epoch)this.busy.set(false);}
  }
  async register(event:Event){
    event.preventDefault();
    if(!this.board()?.canManage||!this.sourceId||!this.rowIndex||this.busy())return;
    this.busy.set(true);this.error.set('');this.message.set('');
    try{
      await firstValueFrom(this.http.post(this.root,{
        sourceObjectId:this.sourceId,rowIndex:Number(this.rowIndex)}));
      this.message.set('Linha registrada como fator FÓSSIL CANDIDATO. '+
        'A fonte exige revisão independente e NÃO está admitida para E8.');
      await this.load();
    }catch(err){this.error.set(this.problem(err));}
    finally{this.busy.set(false);}
  }
  async review(event:Event,id:string){
    event.preventDefault();
    if(!this.board()?.canReview||this.busy())return;
    this.busy.set(true);this.error.set('');this.message.set('');
    try{
      await firstValueFrom(this.http.post(this.root+'/'+encodeURIComponent(id)+'/reviews',{
        outcome:this.reviewOutcome[id]||'NEEDS_INFORMATION',
        ruleReference:this.reviewRule[id],
        evidenceLocator:this.reviewEvidence[id],
        reason:this.reviewReason[id]
      }));
      this.message.set('Parecer INTERNO registrado. Este fator NÃO foi admitido '+
        'normativamente nem vinculado a qualquer projeto.');
      await this.load();
    }catch(err){this.error.set(this.problem(err));}
    finally{this.busy.set(false);}
  }
  private problem(err:unknown){
    if(err instanceof HttpErrorResponse){
      if([401,403,404].includes(err.status))return 'Fonte ou permissão indisponível.';
      if(err.status===409)return 'Esta linha já foi registrada ou avaliada; use outra edição.';
    }
    return 'Não foi possível concluir. Verifique a fonte, unidade, ano, licença e decisão.';
  }
}
