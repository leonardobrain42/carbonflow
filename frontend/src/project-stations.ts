import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type SiteDetails={name:string;municipality:string;state:string;address:string|null;
  operationalState:'PLANNED'|'INSTALLED'|'ACTIVE';plannedOn:string|null;
  commissionedOn:string|null;sourceReference:string|null};
type Site={code:string;version:number;details:SiteDetails;recordedAt:string};
type Charger={code:string;version:number;siteCode:string;kind:string;powerKw:number|null;
  grid:string;topology:string;timeZone:string;sourceReference:string|null};
type Selection={chargerCode:string;chargerVersion:number;siteCode:string;siteVersion:number};
type Boundary={version:number;selected:Selection[];state:string;findings:string[];usableForE8:boolean};
type Catalog={projectId:string;environment:string;sites:Site[];chargers:Charger[];boundary:Boundary};
type SiteDraft={code:string;name:string;municipality:string;state:string;address:string;
  operationalState:'PLANNED'|'INSTALLED'|'ACTIVE';plannedOn:string;
  commissionedOn:string;sourceReference:string};
type ChargerDraft={code:string;siteCode:string;powerKw:string;timeZone:string;sourceReference:string};
const NEW_SITE:SiteDraft={code:'',name:'',municipality:'',state:'DF',address:'',
  operationalState:'PLANNED',plannedOn:'',commissionedOn:'',sourceReference:''};
const NEW_CHARGER:ChargerDraft={code:'',siteCode:'',powerKw:'',timeZone:'America/Sao_Paulo',sourceReference:''};

/** UX-03A: guided inputs backed by V67 sites and V52 canonical chargers, not guide text. */
@Component({selector:'carbonflow-project-stations',templateUrl:'./project-stations.html'})
export class ProjectStationsComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input() editable=false;
  private readonly http=inject(HttpClient);
  readonly data=signal<Catalog|null>(null);
  readonly mode=signal<'sites'|'chargers'|'boundary'>('sites');
  readonly site=signal<SiteDraft>({...NEW_SITE});
  readonly siteVersion=signal(0);
  readonly charger=signal<ChargerDraft>({...NEW_CHARGER});
  readonly chargerVersion=signal(0);
  readonly selected=signal<string[]>([]);
  readonly dirtySite=signal(false);
  readonly dirtyCharger=signal(false);
  readonly loading=signal(true);
  readonly busy=signal(false);
  readonly error=signal('');
  readonly notice=signal('');
  private requestId=0;
  private siteRetry='';
  private chargerRetry='';
  private boundaryRetry='';
  private get root(){return '/v1/projects/'+encodeURIComponent(this.projectId)+'/technical';}

  ngOnChanges(changes:SimpleChanges){
    if(changes['projectId']){
      this.data.set(null);
      this.resetForms();
      void this.load();
    }
  }
  canLeave(){
    if(this.busy())return false;
    if(!this.dirtySite()&&!this.dirtyCharger()&&!this.boundaryDirty())return true;
    return confirm('Descartar as alterações não salvas das estações ou da fronteira?');
  }
  chooseMode(next:'sites'|'chargers'|'boundary'){
    if(next===this.mode()||!this.canLeave())return;
    this.resetForms();
    this.selected.set(this.data()?.boundary.selected.map(x=>x.chargerCode)??[]);
    this.mode.set(next);
    this.error.set('');
    this.notice.set('');
  }
  resetSite(){this.site.set({...NEW_SITE});this.siteVersion.set(0);this.dirtySite.set(false);this.siteRetry='';}
  resetCharger(){this.charger.set({...NEW_CHARGER});this.chargerVersion.set(0);this.dirtyCharger.set(false);this.chargerRetry='';}
  private resetForms(){
    this.site.set({...NEW_SITE});this.siteVersion.set(0);this.dirtySite.set(false);this.siteRetry='';
    this.charger.set({...NEW_CHARGER});this.chargerVersion.set(0);this.dirtyCharger.set(false);this.chargerRetry='';
    this.boundaryRetry='';
  }
  async load(){
    if(!this.projectId)return;
    const id=++this.requestId;
    this.loading.set(true);this.error.set('');
    try{
      const state=await firstValueFrom(this.http.get<Catalog>(this.root+'/open-sites'));
      if(id!==this.requestId)return;
      if(state.projectId!==this.projectId||state.environment!=='SANDBOX')throw new Error('Escopo inválido');
      this.data.set(state);
      this.selected.set(state.boundary.selected.map(x=>x.chargerCode));
      this.resetForms();
    }catch(reason){
      if(id===this.requestId){
        this.data.set(null);
        this.error.set(this.message(reason));
      }
    }finally{if(id===this.requestId)this.loading.set(false);}
  }
  changeSite(field:keyof SiteDraft,value:string){
    this.siteRetry='';
    this.site.update(old=>({...old,[field]:value}));
    this.dirtySite.set(true);this.notice.set('');this.error.set('');
  }
  changeCharger(field:keyof ChargerDraft,value:string){
    this.chargerRetry='';
    this.charger.update(old=>({...old,[field]:value}));
    this.dirtyCharger.set(true);this.notice.set('');this.error.set('');
  }
  pickSite(item:Site){
    this.site.set({code:item.code,name:item.details.name,municipality:item.details.municipality,
      state:item.details.state,address:item.details.address??'',operationalState:item.details.operationalState,
      plannedOn:item.details.plannedOn??'',commissionedOn:item.details.commissionedOn??'',
      sourceReference:item.details.sourceReference??''});
    this.siteVersion.set(item.version);this.siteRetry='';this.dirtySite.set(false);
  }
  pickCharger(item:Charger){
    this.charger.set({code:item.code,siteCode:item.siteCode,powerKw:item.powerKw?.toString()??'',
      timeZone:item.timeZone,sourceReference:item.sourceReference??''});
    this.chargerVersion.set(item.version);this.chargerRetry='';this.dirtyCharger.set(false);
    this.notice.set('Você está editando a configuração canônica. Confirme o fuso e a referência documental antes de salvar nova revisão.');
  }
  eligible(item:Charger){
    return item.kind==='DCFC'&&item.grid==='SIN'&&item.topology==='GRID_ONLY'&&
      !!this.data()?.sites.some(x=>x.code===item.siteCode);
  }
  chosen(code:string){return this.selected().includes(code);}
  toggle(code:string,yes:boolean){
    this.boundaryRetry='';
    this.selected.update(old=>yes?[...old.filter(x=>x!==code),code].sort():old.filter(x=>x!==code));
    this.notice.set('');this.error.set('');
  }
  boundaryDirty(){
    const current=this.data()?.boundary.selected.map(x=>x.chargerCode).sort()??[];
    return JSON.stringify(this.selected())!==JSON.stringify(current);
  }
  async saveSite(event:Event){
    event.preventDefault();if(!this.editable||this.busy())return;
    const input=this.site();const code=input.code.trim();
    if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(code) ||
      input.name.trim().length<3||input.municipality.trim().length<2||
      !/^[A-Za-z]{2}$/.test(input.state) ||
      (input.operationalState==='ACTIVE'&&!input.commissionedOn)){
      this.error.set('Confira código, nome, município, UF e data de operação para estado ativo.');return;
    }
    if(!this.siteRetry)this.siteRetry=crypto.randomUUID();
    this.busy.set(true);this.error.set('');this.notice.set('');
    try{
      const payload:SiteDetails={name:input.name.trim(),municipality:input.municipality.trim(),
        state:input.state.toUpperCase(),address:input.address.trim()||null,
        operationalState:input.operationalState,plannedOn:input.plannedOn||null,
        commissionedOn:input.commissionedOn||null,sourceReference:input.sourceReference.trim()||null};
      await firstValueFrom(this.http.put<Site>(this.root+'/open-sites/sites/'+encodeURIComponent(code),
        payload,{headers:{'If-Match':'"'+this.siteVersion()+'"','Idempotency-Key':this.siteRetry}}));
      await this.load();
      this.notice.set('Estação cadastrada em nova revisão. Localização e situação são declarações pendentes de comprovação.');
    }catch(problem){this.error.set(this.message(problem));if(this.clientError(problem))this.siteRetry='';}
    finally{this.busy.set(false);}
  }
  async saveCharger(event:Event){
    event.preventDefault();if(!this.editable||this.busy())return;
    const input=this.charger();const code=input.code.trim();
    const power=input.powerKw.trim()?Number(input.powerKw):null;
    if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(code) ||
      !this.data()?.sites.some(x=>x.code===input.siteCode)||
      power!==null&&(!Number.isFinite(power)||power<=0||power>10000)){
      this.error.set('Selecione uma estação cadastrada e informe código/potência válidos.');return;
    }
    if(!this.chargerRetry)this.chargerRetry=crypto.randomUUID();
    this.busy.set(true);this.error.set('');this.notice.set('');
    try{
      await firstValueFrom(this.http.put(this.root+'/open-monitoring/chargers/'+encodeURIComponent(code),
        {siteCode:input.siteCode,chargerKind:'DCFC',ratedPowerKw:power,
          grid:'SIN',topology:'GRID_ONLY',timeZone:input.timeZone,
          sourceReference:input.sourceReference.trim()||null},
        {headers:{'If-Match':'"'+this.chargerVersion()+'"','Idempotency-Key':this.chargerRetry}}));
      await this.load();
      this.notice.set('Carregador DCFC registrado no inventário canônico. Não é medição nem elegibilidade aprovada.');
    }catch(problem){this.error.set(this.message(problem));if(this.clientError(problem))this.chargerRetry='';}
    finally{this.busy.set(false);}
  }
  async saveBoundary(){
    const data=this.data();if(!data||!this.editable||this.busy())return;
    if(!this.boundaryDirty()&&data.boundary.state!=='STALE')return;
    if(this.selected().length<data.boundary.selected.length &&
      !confirm('Remover carregadores da fronteira? A revisão anterior continuará preservada.'))return;
    const choices=this.selected().map(code=>data.chargers.find(item=>item.code===code))
      .filter((item):item is Charger=>!!item).map(item=>({code:item.code,version:item.version}));
    if(choices.length!==this.selected().length){this.error.set('Recarregue: carregador ausente.');return;}
    if(!this.boundaryRetry)this.boundaryRetry=crypto.randomUUID();
    this.busy.set(true);this.error.set('');this.notice.set('');
    try{
      await firstValueFrom(this.http.put(this.root+'/open-sites/boundary',{chargers:choices},
        {headers:{'If-Match':'"'+data.boundary.version+'"','Idempotency-Key':this.boundaryRetry}}));
      await this.load();
      this.notice.set('Fronteira versionada salva. Ainda requer evidências, revisão e enquadramento normativo.');
    }catch(problem){this.error.set(this.message(problem));if(this.clientError(problem))this.boundaryRetry='';}
    finally{this.busy.set(false);}
  }
  private clientError(problem:unknown){return problem instanceof HttpErrorResponse&&problem.status>=400&&problem.status<500;}
  private message(problem:unknown){
    if(problem instanceof HttpErrorResponse){
      if(problem.status===412)return 'Outra revisão foi salva. Copie seus dados e recarregue antes de prosseguir.';
      if([401,403,404].includes(problem.status))return 'Acesso indisponível para esta conta e projeto.';
      if(problem.status===409)return 'A configuração mudou ou falta cadastrar a estação. Verifique o inventário e recarregue.';
      if(problem.status===422||problem.status===400)return typeof problem.error?.message==='string'
        ?problem.error.message:'Confira as informações estruturadas e os equipamentos selecionados.';
    }
    return 'Não foi possível carregar ou salvar estações e fronteira.';
  }
}
