import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type SubjectType='SITE'|'CHARGER';
type Review={version:number;outcome:'DOCUMENT_CHECKED_INTERNAL'|'REJECTED';
  reason:string;reviewer:string;reviewedAt:string};
type Document={
 id:string;subjectType:SubjectType;subjectCode:string;subjectVersion:number;
 purpose:string;evidenceReference:string;locator:string|null;fileName:string;
 sha256:string;bytesCount:number;submittedBy:string;submittedAt:string;
 review:Review|null;state:'PENDING_REVIEW'|'CHECKED_INTERNAL'|'REJECTED'|'STALE';
 stale:boolean;usableForE8:boolean;
};
type Catalog={subjectType:SubjectType;subjectCode:string;currentVersion:number;
 documents:Document[];canUpload:boolean;canReview:boolean};
const purposes:Record<SubjectType,Array<{id:string;label:string}>>={
 SITE:[
  {id:'LOCATION',label:'Localização e endereço'},
  {id:'COMMISSIONING',label:'Instalação e comissionamento'},
  {id:'PUBLIC_ACCESS',label:'Acesso público declarado'}
 ],
 CHARGER:[
  {id:'SPECIFICATION',label:'Ficha técnica e potência'},
  {id:'INSTALLATION',label:'Instalação do equipamento'},
  {id:'GRID_CONNECTION',label:'Conexão à rede SIN'}
 ]
};

/** Reuses encrypted evidence blobs (#98), never accepts a URL or typed text as proof. */
@Component({selector:'carbonflow-asset-evidence',standalone:true,templateUrl:'./asset-evidence.html'})
export class AssetEvidenceComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input({required:true}) subjectType:SubjectType='SITE';
  @Input({required:true}) subjectCode='';
  @Input({required:true}) subjectVersion=0;
  readonly availablePurposes=()=>purposes[this.subjectType];
  readonly details=signal<Catalog|null>(null);
  readonly purpose=signal('');
  readonly reference=signal('');
  readonly locator=signal('');
  readonly file=signal<File|null>(null);
  readonly reviewTarget=signal<Document|null>(null);
  readonly decision=signal<'DOCUMENT_CHECKED_INTERNAL'|'REJECTED'>('DOCUMENT_CHECKED_INTERNAL');
  readonly reason=signal('');
  readonly busy=signal(false);
  readonly loading=signal(true);
  readonly error=signal('');
  readonly notice=signal('');
  private readonly http=inject(HttpClient);
  private nonce=0;
  private retry='';
  private readonly MAX_FILE=2_000_000;
  private get root(){
    return '/v1/projects/'+encodeURIComponent(this.projectId)+
      '/technical/open-sites/evidence';
  }
  private get target(){
    return this.root+'/'+this.subjectType+'/'+encodeURIComponent(this.subjectCode);
  }
  ngOnChanges(changes:SimpleChanges){
    if(changes['projectId']||changes['subjectType']||changes['subjectCode']){
      this.file.set(null);this.reference.set('');this.locator.set('');this.purpose.set('');
      this.reviewTarget.set(null);this.reason.set('');this.retry='';
      void this.load();
    }else if(changes['subjectVersion'])void this.load();
  }
  canLeave(){
    if(this.busy())return false;
    return (!this.file()&&!this.reference().trim()&&!this.reason().trim())||
      confirm('Descartar a prova ou o parecer ainda não salvo?');
  }
  async load(){
    if(!this.subjectCode||!this.projectId)return;
    const id=++this.nonce;this.loading.set(true);this.error.set('');
    try{
      const result=await firstValueFrom(this.http.get<Catalog>(this.target));
      if(id!==this.nonce)return;
      if(result.subjectCode!==this.subjectCode||result.subjectType!==this.subjectType)
        throw new Error('Escopo inconsistente');
      this.details.set(result);
    }catch(cause){
      if(id===this.nonce){this.details.set(null);this.error.set(this.message(cause));}
    }finally{if(id===this.nonce)this.loading.set(false);}
  }
  pickFile(input:HTMLInputElement){
    const chosen=input.files?.item(0)??null;
    this.file.set(chosen);this.retry='';this.error.set('');this.notice.set('');
    if(chosen&&(chosen.size<16||chosen.size>this.MAX_FILE||!chosen.name.endsWith('.pdf')))
      this.error.set('Selecione um PDF de 16 bytes a 2 MB, com extensão .pdf.');
  }
  async upload(event:Event){
    event.preventDefault();
    const catalog=this.details(),document=this.file();
    if(!catalog?.canUpload||this.busy()||!document)return;
    if(this.subjectVersion!==catalog.currentVersion){
      this.error.set('O cadastro mudou. Reabra a versão atual para anexar.');return;
    }
    if(document.size<16||document.size>this.MAX_FILE||
      !/^[A-Za-z0-9][A-Za-z0-9._-]{4,115}\.pdf$/.test(document.name)||
      document.name.includes('..')){
      this.error.set('Use nome de arquivo simples terminado em .pdf, até 2 MB.');return;
    }
    if(!this.purpose()||this.reference().trim().length<8||
      this.reference().trim().length>300||
      this.locator().trim().length>160||
      this.locator().trim().length>0&&this.locator().trim().length<3){
      this.error.set('Informe o tipo de prova, referência e localizador válido.');return;
    }
    if(!this.retry)this.retry=crypto.randomUUID();
    this.busy.set(true);this.error.set('');this.notice.set('');
    try{
      await firstValueFrom(this.http.post<Document>(
        this.target+'/revisions/'+this.subjectVersion,document,{
          headers:{
            'Content-Type':'application/pdf',
            'X-Evidence-Purpose':this.purpose(),
            'X-Evidence-Reference':this.reference().trim(),
            'X-Evidence-Locator':this.locator().trim(),
            'X-File-Name':document.name,
            'Idempotency-Key':this.retry
          }
        }));
      this.file.set(null);this.purpose.set('');this.reference.set('');this.locator.set('');
      this.retry='';await this.load();
      this.notice.set('PDF recebido e protegido. Pendente de revisão independente; não é prova admitida.');
    }catch(cause){
      this.error.set(this.message(cause));
      if(cause instanceof HttpErrorResponse&&cause.status>=400&&cause.status<500)this.retry='';
    }finally{this.busy.set(false);}
  }
  beginReview(document:Document){
    this.reviewTarget.set(document);
    this.decision.set('DOCUMENT_CHECKED_INTERNAL');this.reason.set('');
    this.error.set('');this.notice.set('');
  }
  async review(){
    const target=this.reviewTarget();
    if(!target||!this.details()?.canReview||this.busy())return;
    if(this.reason().trim().length<15||this.reason().trim().length>2000){
      this.error.set('Informe justificativa de revisão entre 15 e 2.000 caracteres.');return;
    }
    this.busy.set(true);this.error.set('');
    try{
      await firstValueFrom(this.http.post<Review>(
        this.root+'/documents/'+encodeURIComponent(target.id)+'/review',
        {outcome:this.decision(),reason:this.reason().trim()},
        {headers:{'If-Match':'"'+(target.review?.version??0)+'"'}}));
      this.reviewTarget.set(null);this.reason.set('');
      await this.load();
      this.notice.set('Parecer interno registrado. Não representa admissão normativa ou verificação VVB.');
    }catch(cause){this.error.set(this.message(cause));}
    finally{this.busy.set(false);}
  }
  stateLabel(state:Document['state']){
    return {
      PENDING_REVIEW:'PDF recebido · aguardando revisão',
      CHECKED_INTERNAL:'Documento conferido internamente',
      REJECTED:'Documento rejeitado no parecer interno',
      STALE:'Versão anterior do cadastro · requer rechecagem'
    }[state];
  }
  purposeLabel(id:string){return this.availablePurposes().find(p=>p.id===id)?.label??id;}
  fileUrl(id:string){return this.root+'/documents/'+encodeURIComponent(id)+'/file';}
  private message(cause:unknown){
    if(cause instanceof HttpErrorResponse){
      if(cause.status===412)return 'O ativo mudou. Recarregue a estação ou o equipamento.';
      if(cause.status===409)return 'Conflito documental ou versão obsoleta. Consulte o histórico antes de tentar de novo.';
      if([401,403,404].includes(cause.status))return 'Evidência não disponível para esta conta.';
      if(cause.status===413)return 'O arquivo excede o tamanho máximo de 2 MB.';
      if(typeof cause.error?.message==='string')return cause.error.message;
    }
    return 'Falha ao consultar ou registrar prova. Confira a conexão e as permissões.';
  }
}
