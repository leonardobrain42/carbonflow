import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Value=string|boolean|number|string[];
type Field={
  key:string;label:string;type:string;options:string[];helpText:string|null;
  required:boolean;showWhenField:string|null;showWhenValue:string|null;
  requiredWhenField:string|null;requiredWhenValue:string|null;
  unit:string|null;min:number|null;max:number|null;optionParents:Record<string,string>;
};
type Guide={
  id:string;title:string;methodology:string;edition:string;branch:string;
  definitionVersion:number;version:number;fields:Field[];
  answers:Record<string,Value>;legacyAnswers:Record<string,string>;
  legacyNeedsReview:string[];missing:string[];editable:boolean;state:string;
  normativeApproved:boolean;
};
type Catalog={methodology:string;guides:Guide[]};

/** Separate, versioned typed answer stream. Never reinterpret old plain-text evidence. */
@Component({selector:'carbonflow-typed-guide',standalone:true,templateUrl:'./typed-guide.html'})
export class TypedGuideComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input({required:true}) guideId='';
  @Input() editable=false;
  private readonly http=inject(HttpClient);
  readonly guide=signal<Guide|null>(null);
  readonly answers=signal<Record<string,Value>>({});
  readonly dirty=signal(false);
  readonly busy=signal(false);
  readonly loading=signal(true);
  readonly error=signal('');
  readonly notice=signal('');
  readonly history=signal<Guide|null>(null);
  readonly historyVersion=signal(1);
  private sequence=0;
  private retry='';
  private get url(){return '/v1/projects/'+encodeURIComponent(this.projectId)+'/technical/typed-guides';}

  ngOnChanges(changes:SimpleChanges){
    if(changes['projectId']||changes['guideId']){
      this.guide.set(null);this.answers.set({});this.history.set(null);this.dirty.set(false);
      this.retry='';void this.load();
    }
  }
  canLeave(){
    if(this.busy())return false;
    return !this.dirty()||confirm('Descartar as respostas estruturadas ainda não salvas?');
  }
  async load(){
    if(!this.projectId||!this.guideId)return;
    const id=++this.sequence;
    this.loading.set(true);this.error.set('');
    try{
      const response=await firstValueFrom(this.http.get<Catalog>(this.url));
      if(id!==this.sequence)return;
      const guide=response.guides.find(item=>item.id===this.guideId)??null;
      this.guide.set(guide);this.answers.set({...guide?.answers});
      this.dirty.set(false);this.history.set(null);this.retry='';
    }catch(problem){
      if(id===this.sequence){
        this.guide.set(null);this.error.set(this.message(problem));
      }
    }finally{if(id===this.sequence)this.loading.set(false);}
  }
  readonly stateName=(name:string)=>{
    if(name==='LEGACY_UNSTRUCTURED_REVIEW_REQUIRED')return 'Revisões textuais exigem reconciliação manual';
    if(name==='FORM_COMPLETED_DECLARED')return 'Campos estruturados informados — sem aprovação';
    if(name==='INCOMPLETE_DECLARED')return 'Rascunho estruturado incompleto';
    return 'Ainda não iniciado';
  };
  private condition(field:string|null,expected:string|null,data:Record<string,Value>){
    if(!field)return true;
    const value=data[field];
    if(value===undefined||value===null||value===''||Array.isArray(value)&&!value.length)return false;
    if(expected==='SET')return true;
    return String(value)===expected;
  }
  active(field:Field){return this.condition(field.showWhenField,field.showWhenValue,this.answers());}
  mustHave(field:Field){
    return field.required||!!field.requiredWhenField&&
      this.condition(field.requiredWhenField,field.requiredWhenValue,this.answers());
  }
  fieldOptions(field:Field){
    if(field.type!=='EQUIPMENT_REF')return field.options??[];
    return (field.options??[]).filter(code=>field.optionParents?.[code]===this.answers()['stationCode']);
  }
  labelValue(field:Field){
    const value=this.answers()[field.key];
    if(value===undefined||value===null)return '';
    if(Array.isArray(value))return value.join(', ');
    return String(value);
  }
  selected(field:Field,option:string){
    const value=this.answers()[field.key];
    return Array.isArray(value)&&value.includes(option);
  }
  change(field:Field,raw:string){
    let value:Value|undefined=raw;
    if(!raw.trim())value=undefined;
    else if(field.type==='BOOLEAN')value=raw==='true';
    else if(field.type==='YEAR'&&/^\d{4}$/.test(raw))value=Number(raw);
    else if(field.type==='DECIMAL'&&/^\d+(?:\.\d+)?$/.test(raw))value=Number(raw);
    this.mutate(field.key,value);
  }
  changeMulti(field:Field,select:HTMLSelectElement){
    const values=Array.from(select.selectedOptions).map(o=>o.value);
    this.mutate(field.key,values.length?values:undefined);
  }
  private mutate(key:string,value:Value|undefined){
    this.answers.update(existing=>{
      const next={...existing};
      if(value===undefined)delete next[key];else next[key]=value;
      const active:Field[]=this.guide()?.fields??[];
      // When a controlling answer changes, clear values for dependent fields.
      for(let i=0;i<active.length;i++){
        const f=active[i];
        if(f.showWhenField&&!this.condition(f.showWhenField,f.showWhenValue,next))
          delete next[f.key];
      }
      // Changing selected station also invalidates equipment from another station.
      const equip=active.find(f=>f.key==='chargerCode');
      if(equip&&next['chargerCode']&&
        equip.optionParents?.[String(next['chargerCode'])]!==next['stationCode'])
        delete next['chargerCode'];
      return next;
    });
    this.retry='';this.dirty.set(true);this.error.set('');this.notice.set('');
  }
  fieldError(field:Field){
    if(!this.active(field))return '';
    const raw=this.answers()[field.key];
    if(raw===undefined||raw===null||raw==='')return this.mustHave(field)?'Necessário nesta condição.':'';
    if(['ENUM','STATION_REF','EQUIPMENT_REF'].includes(field.type) &&
      !this.fieldOptions(field).includes(String(raw)))return 'Selecione uma opção pertencente a este projeto.';
    if(field.type==='YEAR'&&(!Number.isInteger(raw)||Number(raw)<1900||Number(raw)>2100))
      return 'Ano inteiro de 1900 a 2100.';
    if(field.type==='DECIMAL'&&
      (typeof raw!=='number'||!Number.isFinite(raw)||raw<=0||
        raw.toString().split('.')[1]?.length>3||
        field.min!=null&&raw<field.min||field.max!=null&&raw>field.max))
      return 'Informe número positivo com até 3 casas decimais e dentro dos limites.';
    if(field.type==='MULTI_SELECT'&&
      (!Array.isArray(raw)||raw.some(x=>!field.options.includes(x))))
      return 'Valores fora das opções permitidas.';
    if((field.type==='TEXT'||field.type==='TEXTAREA')&&String(raw).length>1600)
      return 'Texto muito longo.';
    if(field.type==='DATE'&&!/^\d{4}-\d{2}-\d{2}$/.test(String(raw)))
      return 'Informe uma data completa.';
    return '';
  }
  async save(){
    const current=this.guide();
    if(!current||!current.editable||!this.editable||this.busy()||!this.dirty())return;
    if(current.fields.some(f=>!!this.fieldError(f)&&!!this.answers()[f.key])||
       current.fields.some(f=>this.active(f)&&this.mustHave(f)&&
         (this.answers()[f.key]===undefined||this.answers()[f.key]===''))){
      this.error.set('Corrija os campos assinalados; justificativa condicional não pode ficar vazia.');return;
    }
    if(!this.retry)this.retry=crypto.randomUUID();
    this.busy.set(true);this.error.set('');this.notice.set('');
    try{
      const result=await firstValueFrom(this.http.put<Guide>(
        this.url+'/'+encodeURIComponent(current.id),this.answers(),
        {headers:{'If-Match':'"'+current.version+'"',
          'X-Guide-Definition-Version':String(current.definitionVersion),
          'Idempotency-Key':this.retry}}));
      this.guide.set(result);this.answers.set({...result.answers});
      this.dirty.set(false);this.retry='';
      this.notice.set('Revisão estruturada '+result.version+' salva como declaração. Sem aprovação metodológica.');
    }catch(problem){
      this.error.set(this.message(problem));
      if(problem instanceof HttpErrorResponse&&problem.status>=400&&problem.status<500)this.retry='';
    }finally{this.busy.set(false);}
  }
  async showRevision(){
    const current=this.guide();const version=this.historyVersion();
    if(!current||this.busy()||version<1||version>=current.version)return;
    this.busy.set(true);this.error.set('');
    try{this.history.set(await firstValueFrom(this.http.get<Guide>(
      this.url+'/'+encodeURIComponent(current.id)+'/revisions/'+version)));}
    catch(problem){this.error.set(this.message(problem));}
    finally{this.busy.set(false);}
  }
  private message(problem:unknown){
    if(problem instanceof HttpErrorResponse){
      if(problem.status===412)return 'Outra revisão foi salva. Copie as alterações e recarregue.';
      if(problem.status===409)return 'O esquema ou a seleção mudou. Recarregue antes de salvar.';
      if([401,403,404].includes(problem.status))return 'Guia não disponível para esta conta.';
      if([400,422].includes(problem.status))return typeof problem.error?.message==='string'
        ?problem.error.message:'Tipo, condição ou referência inválidos. Confira os campos.';
    }
    return 'Não foi possível carregar ou salvar o formulário estruturado.';
  }
}
