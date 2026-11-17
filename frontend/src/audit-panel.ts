import {Component, computed, inject, input, OnInit, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {SessionProject} from './access-panel';

interface Ref {id: string; revision: number}
interface Capture {authority: string; capturedBy: string; effectiveAt: string; proof: {officialId: string}}
interface Bound {pack: {reference: Ref; actDate: string}; closure: {period: string; revision: number} | null; reservedArtifact: string}
interface Dossier extends Ref {state: string; command: {act: string; actDate: string; closure: {period: string} | null}}
interface Audit {
  id: string; version: number; contract: {kind: string; vvb: string; scope: string}; issuer: string; pack: Bound; state: string;
  grantId: string | null; proposed: Ref | null; lastAt: string | null;
  visit: {capture: Capture} | null; risk: {reason: string; waived: boolean} | null;
  findings: {id: string; state: string; criterion: string; reason: string}[];
  opinions: {result: string; capture: Capture; vintages: string[]; dossier: Ref}[];
}
interface AuditView {audit: Audit; target: string}
interface Request {
  id: string; version: number; kind: string; audit: string; state: string; target: string | null; lastAt: string | null;
  pack: Bound; predecessor: string | null; submission: {receipt: string; sha256: string} | null;
  check: {input: {dossier: Ref; channel: string}} | null; rounds: {number: number; finding: string; report: Capture; response: Capture | null}[];
  decision: Capture | null; registration: Capture | null;
}
interface RequestView {request: Request; responseDue: string | null; reuseUntil: string | null; overdue: boolean}
interface Options {auditSources: Record<string,string>; requestSources: Record<string,string>;
  channels: {channels: {route: string; source: {id: string; version: number}}[]}}
interface ListingView {attempt: {kind: string; consultationId: string | null; target: string | null}}
interface Receipt {input: {exportId: string; code: string; receivedAt: string; dossier: Ref; destination: string}}
interface Export {id: string; exposure: string; destination: string; dossier: Ref}
interface EventRow {id: string; actor: string; action: string; outcome: string}
interface Step {id: string; label: string}

const labels: Record<string,string> = {
  'VINCULAR_CONCESSAO': 'Vincular concessão com recibo', 'TX-E3-26': 'Abrir auditoria', VISITA: 'Registrar visita',
  RISCO: 'Avaliar risco e visita de M2', COBERTURA: 'Confirmar cobertura após visita antecipada', 'TX-E3-27': 'Abrir achado do VVB',
  'TX-E3-28':'Entregar resposta ao VVB', 'TX-E3-29': 'Avaliar resposta ao achado', 'TX-E3-30':'Emitir parecer negativo',
  'TX-E3-31':'Retomar após parecer negativo', 'TX-E3-32':'Emitir parecer positivo', REVISAR_SUCESSORA: 'Analisar revisão sucessora',
  'TX-E3-33':'Vincular protocolo do pedido', 'TX-E3-34':'Registrar início da revisão Verra', 'TX-E3-35':'Registrar rodada da Verra',
  'TX-E3-36':'Registrar resposta da rodada pelo VVB', 'TX-E3-37':'Comprovar aprovação', 'TX-E3-38':'Registrar devolução',
  'TX-E3-39':'Registrar rejeição', 'TX-E3-40':'Vincular protocolo sucessor', REGISTRO_COMPROVADO:'Comprovar registro efetivo',
};

@Component({selector:'carbonflow-audits',templateUrl:'./audit-panel.html'})
export class AuditPanel implements OnInit {
  readonly project=input.required<SessionProject>();
  readonly pp=computed(()=>this.project().roles.includes('PP'));
  readonly vvb=computed(()=>this.project().roles.includes('VVB'));
  readonly enabled=computed(()=>this.project().roles.some(role=>['PREP','REV','PP','VVB'].includes(role)));
  readonly options=signal<Options|null>(null);
  readonly audits=signal<AuditView[]>([]);
  readonly requests=signal<RequestView[]>([]);
  readonly dossiers=signal<Dossier[]>([]);
  readonly consultations=signal<ListingView[]>([]);
  readonly chosenAudit=signal('');
  readonly chosenRequest=signal('');
  readonly currentAudit=computed(()=>this.audits().find(item=>item.audit.id===this.chosenAudit())??null);
  readonly currentRequest=computed(()=>this.requests().find(item=>item.request.id===this.chosenRequest())??null);
  readonly auditKind=signal('VAL');
  readonly auditStep=signal('');
  readonly requestStep=signal('');
  readonly events=signal<EventRow[]>([]);
  readonly receipts=signal<Receipt[]>([]);
  readonly exports=signal<Export[]>([]);
  readonly busy=signal(false);
  readonly failed=signal(false);
  readonly feedback=signal('');
  private readonly http=inject(HttpClient);
  private readonly keys=new Map<string,string>();

  ngOnInit(){if(this.enabled())void this.refresh();}
  refresh(){return this.perform(()=>this.load());}
  ref(ref:Ref){return {id:ref.id,revision:ref.revision};}
  address(ref:Ref){return ref.id+'@'+ref.revision;}
  short(ref:Ref){return ref.id.slice(0,8)+' · r'+ref.revision;}
  label(step:string){return labels[step]??step;}
  date(instant:string){return new Date(Date.parse(instant)-3*3600000).toISOString().slice(0,16);}
  requestKind(kind:string){return ({VAL:'REG',M1:'V1',M2:'V2'} as Record<string,string>)[kind]??kind;}
  packOptions(kind:string){
    return this.dossiers().filter(d=>d.state==='CONGELADO' && (kind==='VAL'?d.command.act==='REGISTRO':d.command.closure?.period===kind)
      && !this.dossiers().some(candidate=>candidate.id===d.id&&candidate.revision>d.revision));
  }
  availableAudit(a:Audit):Step[]{
    let ids:string[]=[];
    if(this.pp()){
      if(a.state==='COM_ACHADO')ids.push('TX-E3-28');
      ids.push('VINCULAR_CONCESSAO');
    }
    if(this.vvb()){
      if(a.state==='PREVISTA')ids=a.grantId?['TX-E3-26']:[];
      if(a.state==='EM_ANALISE')ids=['VISITA',...(a.contract.kind==='M2'?['RISCO']:[]),
        ...(a.contract.kind!=='VAL'&&a.visit?['COBERTURA']:[]),'TX-E3-27','TX-E3-32','TX-E3-30'];
      if(a.state==='COM_ACHADO')ids=['TX-E3-27','TX-E3-30'];
      if(a.state==='RESPOSTA_ENVIADA')ids=['TX-E3-29','TX-E3-30'];
      if(a.state==='PARECER_NEGATIVO')ids=['TX-E3-31'];
      if(a.state==='PARECER_POSITIVO')ids=['REVISAR_SUCESSORA'];
    }
    return ids.map(id=>({id,label:this.label(id)}));
  }
  availableRequest(r:Request):Step[]{
    let ids:string[]=[];
    if(this.pp()){
      if(r.state==='NOVO')ids=[r.predecessor?'TX-E3-40':'TX-E3-33'];
      else if(['PROTOCOLADO','EM_REVISAO','COM_ACHADO'].includes(r.state)){
        ids=[...(r.state==='PROTOCOLADO'?['TX-E3-34']:r.state==='EM_REVISAO'?['TX-E3-35']:[]),'TX-E3-37','TX-E3-38','TX-E3-39'];
      }else if(r.kind==='REG'&&r.state==='APROVADO'&&!r.registration)ids=['REGISTRO_COMPROVADO'];
    }else if(this.vvb()&&r.state==='COM_ACHADO')ids=['TX-E3-36'];
    return ids.map(id=>({id,label:this.label(id)}));
  }
  selectedAudit(a:Audit){const options=this.availableAudit(a);return options.find(s=>s.id===this.auditStep())??options[0];}
  selectedRequest(r:Request){const options=this.availableRequest(r);return options.find(s=>s.id===this.requestStep())??options[0];}
  auditRef(a:Audit,step:string){
    if(step==='TX-E3-29'&&a.proposed)return this.address(a.proposed);
    const prior=a.pack.pack.reference;
    return this.address(['TX-E3-28','TX-E3-31','REVISAR_SUCESSORA'].includes(step)?{...prior,revision:prior.revision+1}:prior);
  }
  isAuditSuccessor(step:string){return ['TX-E3-28','TX-E3-29','TX-E3-31','REVISAR_SUCESSORA'].includes(step);}
  auditGrantNeeded(step:string){return step==='TX-E3-26'||step==='VINCULAR_CONCESSAO'||this.isAuditSuccessor(step);}
  hasRequestReceipt(step:string){return ['TX-E3-33','TX-E3-40','TX-E3-36'].includes(step);}
  download(e:Export){return '/v1/projects/'+encodeURIComponent(this.project().project)+'/dossiers/'+e.dossier.id+'/exports/'+e.id+'/content';}

  chooseAudit(id:string){this.chosenAudit.set(id);this.auditStep.set('');this.events.set([]);}
  chooseRequest(id:string){this.chosenRequest.set(id);this.requestStep.set('');this.events.set([]);
    if(this.pp())void this.perform(()=>this.loadDelivery());
  }

  contract(event:Event,pack:string,vvb:string,consultation:string,scope:string,contracted:string,accredited:string){
    event.preventDefault();
    const kind=this.auditKind();
    return this.perform(async()=>{
      const made=await this.post<AuditView>('/audits',{kind,dossier:this.parse(pack),vvb,consultation:kind==='VAL'?consultation||null:null,
        scope,contractedAt:this.at(contracted),accreditedUntil:this.at(accredited),contract:this.code('SIM-CONTRATO'),accreditation:this.code('SIM-ACREDITACAO')});
      this.chosenAudit.set(made.audit.id);await this.load();this.feedback.set('Contratação registrada.');
    });
  }
  auditAction(event:Event,view:AuditView,step:string,data:{date:string;pack:string;official:string;grant:string;reason:string;
    finding:string;criterion:string;mandatory:boolean;waive:boolean;prior:string;coverage:string;receipt:string;accepted:boolean}){
    event.preventDefault();const a=view.audit;
    const opinion=['TX-E3-30','TX-E3-32'].includes(step);
    return this.perform(async()=>{
      await this.post('/audits/'+a.id+'/actions',{transitionId:step,effectiveAt:this.at(data.date),dossier:this.parse(data.pack),
        proof:this.proof(this.options()?.auditSources[step],data.official,view.target),grantId:data.grant||null,
        finding:data.finding||null, reason:data.reason,criterion:data.criterion,mandatoryVisit:data.mandatory,waiveVisit:data.waive,
        previousGrant:data.prior||null,closureSha256:data.coverage,exportId:data.receipt||null,accepted:data.accepted,
        report:opinion?this.code('SIM-RELATORIO-PARECER'):null,representation:opinion?this.code('SIM-REPRESENTACAO-PARECER'):null},a.version);
      this.auditStep.set('');await this.load();this.feedback.set('Etapa da auditoria registrada.');
    });
  }

  prepareRequest(event:Event,auditId:string,predecessor:string,reason:string){
    event.preventDefault();const audit=this.audits().find(item=>item.audit.id===auditId)?.audit;
    if(!audit)return;
    return this.perform(async()=>{
      const made=await this.post<RequestView>('/requests',{kind:this.requestKind(audit.contract.kind),audit:audit.id,
        dossier:this.ref(audit.pack.pack.reference),predecessor:predecessor||null,reason});
      this.chosenRequest.set(made.request.id);await this.load();this.feedback.set('Pedido preparado.');
    });
  }
  check(event:Event,request:Request,channel:string,pack:string,date:string,until:string,power:boolean){
    event.preventDefault();const source=this.options()?.channels.channels.find(item=>item.route===channel)?.source;
    return this.perform(async()=>{
      await this.post('/requests/'+request.id+'/channel-checks',{channel,dossier:this.parse(pack),documentEdition:'5.0B',
        methodologyEdition:'VM0038 v1.1',checkedAt:this.at(date),powerUntilExclusive:power?this.at(until):null,source,
        power:power?this.code('SIM-PODER-LISTAGEM'):null},request.version);
      await this.load();this.feedback.set('Canal conferido para a revisão.');
    });
  }
  requestAction(event:Event,r:Request,step:string,data:{date:string;target:string;official:string;receipt:string;grant:string;reason:string;
    fee:boolean;reuse:boolean;admission:boolean}){
    event.preventDefault();const receipt=this.receipts().find(item=>item.input.exportId===data.receipt)?.input;
    const pack=step==='TX-E3-36'?r.check?.input.dossier??r.pack.pack.reference:r.pack.pack.reference;
    const target=r.target??data.target;
    return this.perform(async()=>{
      await this.post('/requests/'+r.id+'/actions',{transitionId:step,mode:'SIMULACAO',dossier:this.ref(pack),target,
        effectiveAt:this.hasRequestReceipt(step)&&receipt?receipt.receivedAt:this.at(data.date),
        proof:this.proof(this.options()?.requestSources[step],data.official,target),exportId:data.receipt||null,grantId:data.grant||null,
        reason:data.reason,fee:data.fee?this.code('SIM-PROVA-TAXA-PEDIDO'):null,reuse:data.reuse?this.code('SIM-PROVA-REUSO-180'):null,
        admission:data.admission?this.code('SIM-PROVA-NOVA-TENTATIVA-PEDIDO'):null},r.version);
      this.requestStep.set('');await this.load();this.feedback.set('Etapa do pedido registrada.');
    });
  }

  inspect(path:string){return this.perform(async()=>this.events.set(await this.read<EventRow[]>(path+'/history')));}
  asOf(value:string){
    return this.perform(async()=>{
      const query=encodeURIComponent(this.at(value)??'');
      this.requests.set(await this.read<RequestView[]>('/requests?asOf='+query));
    });
  }

  private parse(value:string){const [id,revision]=value.trim().split('@');return {id,revision:Number(revision)};}
  private at(value:string){return value?value+':00-03:00':null;}
  private code(id:string){return {id,version:1};}
  private proof(source:string|undefined,officialId:string,target:string){return source?{officialId,target,evidence:this.code(source)}:null;}
  private base(){return '/v1/projects/'+encodeURIComponent(this.project().project);}
  private read<T>(path:string){return firstValueFrom(this.http.get<T>(this.base()+path));}

  private async load(){
    const [options,audits,requests]=await Promise.all([this.read<Options>('/audits/options'),this.read<AuditView[]>('/audits'),this.read<RequestView[]>('/requests')]);
    this.options.set(options);this.audits.set(audits);this.requests.set(requests);
    if(!this.currentAudit())this.chosenAudit.set(audits[0]?.audit.id??'');
    if(!this.currentRequest())this.chosenRequest.set(requests[0]?.request.id??'');
    if(!this.vvb()){
      const [dossiers,consultations]=await Promise.all([this.read<Dossier[]>('/dossiers'),this.read<ListingView[]>('/listings')]);
      this.dossiers.set(dossiers);this.consultations.set(consultations.filter(item=>item.attempt.consultationId));
      if(this.pp())await this.loadDelivery();
    }
  }
  private async loadDelivery(){
    const selected=this.currentRequest()?.request;
    if(!selected){this.receipts.set([]);this.exports.set([]);return;}
    const path='/dossiers/'+selected.pack.pack.reference.id;
    const [receipts,exports]=await Promise.all([this.read<Receipt[]>(path+'/receipts'),this.read<Export[]>(path+'/exports')]);
    this.receipts.set(receipts);this.exports.set(exports);
  }
  private async post<T>(path:string,body:object,version?:number){
    const fingerprint=JSON.stringify([path,body,version]);const key=this.keys.get(fingerprint)??crypto.randomUUID();
    this.keys.set(fingerprint,key);const headers:Record<string,string>={'Idempotency-Key':key};
    if(version!==undefined)headers['If-Match']='"'+version+'"';
    try{const result=await firstValueFrom(this.http.post<T>(this.base()+path,body,{headers}));this.keys.delete(fingerprint);return result;}
    catch(error){
      if(error instanceof HttpErrorResponse&&error.status>=400&&error.status<500){this.keys.delete(fingerprint);if(error.status===412)await this.load().catch(()=>undefined);}
      throw error;
    }
  }
  private async perform(work:()=>Promise<void>){
    if(this.busy())return;
    this.busy.set(true);this.failed.set(false);this.feedback.set('');
    try{await work();}catch(error){
      this.failed.set(true);this.feedback.set(error instanceof HttpErrorResponse?error.error?.message??'Falha na etapa.'
        :'Falha na etapa.');
    }finally{this.busy.set(false);}
  }
}
