import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {OpenEfelCandidatesComponent} from './open-efel';

type ChargerInput = {
  siteCode:string; chargerKind:string; ratedPowerKw:number|null;
  grid:string; topology:string; timeZone:string; sourceReference:string|null;
};
type Charger = {code:string; version:number; details:ChargerInput; recordedAt:string};
type MeterInput = {
  chargerCode:string; energyKind:string; meterPosition:string; sourceSystem:string|null;
  calibrationEvidenceRef:string|null; calibrationValidFrom:string|null;
  calibrationValidUntil:string|null; configuredFrom:string|null;
  configuredUntil:string|null; timeZone:string;
};
type Meter = {code:string; version:number; chargerVersion:number; details:MeterInput; recordedAt:string};
type Gate = {code:string; state:string; reason:string; chargerCode:string|null; meterCode:string|null};
type Readiness = {
  state:string; canPrepare:boolean; canUseForE8:boolean;
  edKwh:string|null; ecKwh:string|null; gates:Gate[];
};
type View = {projectId:string; environment:string; chargers:Charger[]; meters:Meter[]; readiness:Readiness};
type MeasurementRow = {line:number;meterCode:string|null;sequence:string|null;kind:string|null;
  start:string|null;end:string|null;zone:string|null;kwh:string|null;state:string;reasons:string[]};
type MeasurementBatch = {id:string;projectId:string;source:string;fileName:string;sha256:string;
  rowCount:number;status:'CANDIDATE'|'QUARANTINED';submittedAt:string;rows:MeasurementRow[]};
type QaReview = {batchId:string;line:number;version:number;outcome:'ACCEPTED_FOR_QA'|'REJECTED';
  reason:string;reviewedBy:string;reviewedAt:string};
type QaGate = {code:string;detail:string;charger:string|null;meter:string|null;hour:string|null};
type QaPeriod = {state:string;readyForInternalSnapshot:boolean;canRunE8:boolean;
  edKwh:string|null;ecKwh:string|null;gates:QaGate[]};
type QaSnapshot = {id:string;projectId:string;from:string;until:string;
  edKwh:string;ecKwh:string;sourceSha256:string;status:string;usableForE8:boolean;createdAt:string};
type SupportReview = {version:number;outcome:'DOCUMENT_CHECKED_INTERNAL'|'REJECTED';
  reason:string;reviewedBy:string;reviewedAt:string};
type SupportDocument = {id:string;snapshotId:string;kind:string;subjectCode:string;
  evidenceReference:string;fileName:string;sha256:string;bytesCount:number;
  submittedBy:string;submittedAt:string;review:SupportReview|null};
type SupportGate = {code:string;subject:string|null;detail:string};
type SupportReadiness = {snapshotId:string;state:string;stale:boolean;
  documentaryComplete:boolean;usableForE8:boolean;historicalEdKwh:string;
  historicalEcKwh:string;gates:SupportGate[];documents:SupportDocument[]};
type OperationalEventReview = {version:number;outcome:'CONFIRMED_BLOCKING'|'CLEARED_FOR_INTERNAL_QA';
  reason:string;evidenceReference:string;reviewedBy:string;reviewedAt:string};
type OperationalEvent = {id:string;projectId:string;kind:string;subjectType:string;
  subjectCode:string;from:string;until:string;incidentReference:string;
  description:string;submittedBy:string;submittedAt:string;review:OperationalEventReview|null};
type EventForm = {kind:string;subjectType:string;subjectCode:string;
  from:string;until:string;incidentReference:string;description:string};
const NEW_EVENT:EventForm = {kind:'SOURCE_OUTAGE',subjectType:'SOURCE',
  subjectCode:'',from:'',until:'',incidentReference:'',description:''};

type MrReference = {code:string;version:number;snapshotId:string;snapshotSha256:string;
  note:string;authoredBy:string;createdAt:string};
type MrGate = {code:string;detail:string};
type MrDossier = {reference:MrReference;state:string;stale:boolean;
  canUseForE8:boolean;gates:MrGate[]};
type MethodCriterion = 'BOUNDARY_DCFC_SIN'|'METER_CALIBRATION'|'SOURCE_RIGHTS'|
  'HOURLY_ED_EC'|'CLOCK_GAPS_MAINTENANCE'|'DOUBLE_CLAIMING';
type MethodCheck = {requirement:MethodCriterion;finding:'SUPPORTED_INTERNAL'|'OPEN';
  evidenceReference:string;reason:string};
type MethodReview = {version:number;outcome:'CHECKED_INTERNAL'|'RETURNED';
  rationale:string;checks:MethodCheck[];evidenceSha256:string;
  reviewedBy:string;reviewedAt:string};
type MethodView = {period:string;dossierVersion:number;snapshotId:string;
  methodology:string;methodologyEdition:string;state:string;stale:boolean;
  canUseForE8:boolean;gates:{code:string;detail:string}[];review:MethodReview|null};
const METHOD_CRITERIA:MethodCriterion[]=[
  'BOUNDARY_DCFC_SIN','METER_CALIBRATION','SOURCE_RIGHTS',
  'HOURLY_ED_EC','CLOCK_GAPS_MAINTENANCE','DOUBLE_CLAIMING'
];
const METHOD_LABELS:Record<MethodCriterion,string>={
  BOUNDARY_DCFC_SIN:'Fronteira VM0038 v1.1, DCFC/SIN e topologia elegível',
  METER_CALIBRATION:'Calibração, período, identificação e posição dos medidores',
  SOURCE_RIGHTS:'Direito de usar as leituras e evidências de origem',
  HOURLY_ED_EC:'Cobertura horária real com ED e EC separados',
  CLOCK_GAPS_MAINTENANCE:'Relógio, lacunas, manutenção e reconciliação',
  DOUBLE_CLAIMING:'Titularidade, exclusividade e possível dupla contagem'
};
const emptyMethodChecks=():MethodCheck[]=>METHOD_CRITERIA.map(requirement=>({
  requirement,finding:'OPEN' as const,evidenceReference:'',reason:''
}));

type EnergyHour = {chargerCode:string;from:string;until:string;edKwh:string;ecKwh:string;
  edBatchId:string;edLine:number;edReview:number;ecBatchId:string;ecLine:number;ecReview:number};
type EnergyCandidate = {id:string;projectId:string;period:string;dossierVersion:number;
  methodReviewVersion:number;snapshotId:string;sourceSha256:string;edKwh:string;ecKwh:string;
  hourCount:number;slotCount:number;state:'NOT_ADMITTED'|'STALE_NOT_ADMITTED';
  stale:boolean;usableForE8:boolean;createdAt:string;gates:string[];hours:EnergyHour[]};
type ChargerForm = {code:string; siteCode:string; chargerKind:string; ratedPowerKw:string;
                    grid:string; topology:string; timeZone:string; sourceReference:string};
type MeterForm = {code:string; chargerCode:string; energyKind:string; sourceSystem:string;
  calibrationEvidenceRef:string;calibrationValidFrom:string;calibrationValidUntil:string;
  configuredFrom:string;configuredUntil:string;timeZone:string};

const NEW_CHARGER:ChargerForm = {
  code:'',siteCode:'',chargerKind:'DCFC',ratedPowerKw:'',
  grid:'SIN',topology:'GRID_ONLY',timeZone:'America/Sao_Paulo',sourceReference:'',
};
const NEW_METER:MeterForm = {
  code:'',chargerCode:'',energyKind:'ED',sourceSystem:'',
  calibrationEvidenceRef:'',calibrationValidFrom:'',calibrationValidUntil:'',
  configuredFrom:'',configuredUntil:'',timeZone:'America/Sao_Paulo',
};

@Component({
  selector:'carbonflow-open-monitoring',
  standalone:true,
  imports:[OpenEfelCandidatesComponent],
  templateUrl:'./open-monitoring.html',
})
export class OpenMonitoringComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input() editable=false;
  @Input() canSubmit=false;
  @Input() canReview=false;
  @Input() canReportEvent=false;
  @Input() canReviewEvent=false;
  @Input() canViewMethod=false;
  @Input() canReviewMethod=false;
  @Input() canViewEnergy=false;
  @Input() canPrepareEnergy=false;
  @Input() canViewEfel=false;
  @Input() canSubmitEfel=false;
  @Input() canReviewEfel=false;
  @Input() canViewVt0011=false;
  @Input() canSubmitVt0011=false;
  @Input() canReviewVt0011=false;
  @Input() canViewSimpleOm=false;
  @Input() canPrepareSimpleOm=false;
  private readonly http=inject(HttpClient);
  readonly data=signal<View|null>(null);
  readonly charger=signal<ChargerForm>({...NEW_CHARGER});
  readonly meter=signal<MeterForm>({...NEW_METER});
  readonly chargerVersion=signal(0);
  readonly meterVersion=signal(0);
  readonly loading=signal(true);
  readonly saving=signal(false);
  readonly uploading=signal(false);
  readonly batches=signal<MeasurementBatch[]>([]);
  readonly reviews=signal<Record<string,QaReview>>({});
  readonly qaSnapshots=signal<QaSnapshot[]>([]);
  readonly mrDossiers=signal<MrDossier[]>([]);
  readonly mrCode=signal('M1');
  readonly mrSnapshot=signal('');
  readonly mrNote=signal('');
  readonly mrBusy=signal(false);
  readonly energyPeriod=signal<string|null>(null);
  readonly energyHistory=signal<EnergyCandidate[]>([]);
  readonly energyBusy=signal(false);
  readonly energyPreparing=signal(false);
  readonly methodSelected=signal<MethodView|null>(null);
  readonly methodLoading=signal(false);
  readonly methodBusy=signal(false);
  readonly methodChecks=signal<MethodCheck[]>(emptyMethodChecks());
  readonly methodOutcome=signal<'CHECKED_INTERNAL'|'RETURNED'>('RETURNED');
  readonly methodRationale=signal('');
  readonly methodLabels=METHOD_LABELS;
  readonly operationalEvents=signal<OperationalEvent[]>([]);
  readonly eventForm=signal<EventForm>({...NEW_EVENT});
  readonly eventSubmitting=signal(false);
  readonly eventReviewId=signal<string|null>(null);
  readonly eventReviewOutcome=signal<'CONFIRMED_BLOCKING'|'CLEARED_FOR_INTERNAL_QA'>('CONFIRMED_BLOCKING');
  readonly eventReviewReason=signal('');
  readonly eventReviewEvidence=signal('');
  readonly eventReviewSaving=signal(false);
  readonly proofSnapshot=signal<string|null>(null);
  readonly proofReadiness=signal<SupportReadiness|null>(null);
  readonly proofLoading=signal(false);
  readonly proofKind=signal<'METER_CALIBRATION'|'SOURCE_RIGHTS'>('METER_CALIBRATION');
  readonly proofSubject=signal('');
  readonly proofReference=signal('');
  readonly proofFile=signal<File|null>(null);
  readonly proofUploading=signal(false);
  readonly proofReviewId=signal<string|null>(null);
  readonly proofReviewOutcome=signal<'DOCUMENT_CHECKED_INTERNAL'|'REJECTED'>('REJECTED');
  readonly proofReviewReason=signal('');
  readonly proofReviewSaving=signal(false);
  readonly qaPreview=signal<QaPeriod|null>(null);
  readonly qaFrom=signal('');
  readonly qaUntil=signal('');
  readonly reviewing=signal(false);
  readonly qaLoading=signal(false);
  readonly selectedReview=signal<{batch:string;line:number}|null>(null);
  readonly reviewReason=signal('');
  readonly reviewOutcome=signal<'ACCEPTED_FOR_QA'|'REJECTED'>('REJECTED');
  readonly selectedCsv=signal<File|null>(null);
  readonly uploadSource=signal('');
  readonly error=signal('');
  readonly notice=signal('');
  private sequence=0;
  private chargerRetry='';
  private meterRetry='';
  private uploadRetry='';
  private eventRetry='';
  private proofRetry='';

  ngOnChanges(changes:SimpleChanges) {
    if(changes['projectId']) {
      // Never display a previous project's energy inputs or evidence while
      // a different project is loading, even if both use period "M1".
      this.energyPeriod.set(null);
      this.energyHistory.set([]);
      this.methodSelected.set(null);
      this.proofSnapshot.set(null);
      this.proofReadiness.set(null);
      void this.load();
    }
  }

  async load() {
    if(!this.projectId)return;
    const id=++this.sequence;
    this.loading.set(true);
    this.error.set('');
    try {
      const root='/v1/projects/'+encodeURIComponent(this.projectId)+'/technical/open-monitoring';
      const [result, batches, snapshots, operationalEvents, dossiers]=await Promise.all([
        firstValueFrom(this.http.get<View>(root)),
        firstValueFrom(this.http.get<MeasurementBatch[]>(root+'/batches')),
        firstValueFrom(this.http.get<QaSnapshot[]>(root+'/period-qa/snapshots')),
        firstValueFrom(this.http.get<OperationalEvent[]>(root+'/events')),
        firstValueFrom(this.http.get<MrDossier[]>(root+'/dossiers'))
      ]);
      const reviewGroups=await Promise.all(batches.map(batch=>
        firstValueFrom(this.http.get<QaReview[]>(root+'/batches/'+batch.id+'/reviews'))));
      const indexed:Record<string,QaReview>={};
      reviewGroups.flat().forEach(r=>indexed[r.batchId+'/'+r.line]=r);
      if(id===this.sequence) {
        if(result.projectId!==this.projectId||result.environment!=='SANDBOX' ||
           batches.some(batch=>batch.projectId!==this.projectId))
          throw new Error('Incompatible scope');
        this.data.set(result);
        this.batches.set(batches);
        this.qaSnapshots.set(snapshots);
        this.operationalEvents.set(operationalEvents);
        this.mrDossiers.set(dossiers);
        if(this.proofSnapshot() && !snapshots.some(x=>x.id===this.proofSnapshot())) {
          this.proofSnapshot.set(null);
          this.proofReadiness.set(null);
        }
        this.reviews.set(indexed);
      }
    } catch(problem) {
      if(id===this.sequence)this.error.set(this.message(problem));
    } finally {
      if(id===this.sequence)this.loading.set(false);
    }
  }

  updateCharger(field:keyof ChargerForm,value:string) {
    this.chargerRetry='';
    this.charger.update(current=>({...current,[field]:value}));
  }
  updateMeter(field:keyof MeterForm,value:string) {
    this.meterRetry='';
    this.meter.update(current=>({...current,[field]:value}));
  }

  chooseCharger(item:Charger) {
    this.charger.set({
      code:item.code,siteCode:item.details.siteCode,chargerKind:item.details.chargerKind,
      ratedPowerKw:item.details.ratedPowerKw?.toString()??'',
      grid:item.details.grid,topology:item.details.topology,
      timeZone:item.details.timeZone,sourceReference:item.details.sourceReference??'',
    });
    this.chargerVersion.set(item.version);
    this.chargerRetry='';
    this.notice.set('Revisando carregador '+item.code+'. A edição criará nova versão.');
  }

  chooseMeter(item:Meter) {
    this.meter.set({
      code:item.code,chargerCode:item.details.chargerCode,
      energyKind:item.details.energyKind,sourceSystem:item.details.sourceSystem??'',
      calibrationEvidenceRef:item.details.calibrationEvidenceRef??'',
      calibrationValidFrom:item.details.calibrationValidFrom??'',
      calibrationValidUntil:item.details.calibrationValidUntil??'',
      configuredFrom:item.details.configuredFrom??'',
      configuredUntil:item.details.configuredUntil??'',
      timeZone:item.details.timeZone,
    });
    this.meterVersion.set(item.version);
    this.meterRetry='';
    this.notice.set('Revisando medidor '+item.code+'. A edição criará nova versão.');
  }
  newCharger() {
    this.charger.set({...NEW_CHARGER});
    this.chargerVersion.set(0);
    this.chargerRetry='';
  }
  newMeter() {
    this.meter.set({...NEW_METER});
    this.meterVersion.set(0);
    this.meterRetry='';
  }

  async saveCharger(event:Event) {
    event.preventDefault();
    if(!this.editable||this.saving())return;
    const current=this.charger();
    const code=current.code.trim();
    const power=current.ratedPowerKw.trim()?Number(current.ratedPowerKw):null;
    if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(code) ||
        (power!==null && (!Number.isFinite(power)||power<=0))) {
      this.error.set('Confira código e potência nominal do carregador.');
      return;
    }
    const payload:ChargerInput={
      siteCode:current.siteCode.trim(),chargerKind:current.chargerKind,
      ratedPowerKw:power,grid:current.grid,topology:current.topology,
      timeZone:current.timeZone,sourceReference:current.sourceReference.trim()||null,
    };
    if(!this.chargerRetry)this.chargerRetry=crypto.randomUUID();
    this.saving.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.put(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/chargers/'+encodeURIComponent(code),
        payload,{headers:{'If-Match':'"'+this.chargerVersion()+'"','Idempotency-Key':this.chargerRetry}}));
      this.newCharger();
      await this.load();
      this.notice.set('Inventário versionado salvo. Não representa medição aprovada.');
    } catch(problem) {
      this.error.set(this.message(problem));
      if(problem instanceof HttpErrorResponse&&problem.status>=400&&problem.status<500)this.chargerRetry='';
    } finally {this.saving.set(false);}
  }

  async saveMeter(event:Event) {
    event.preventDefault();
    if(!this.editable||this.saving())return;
    const current=this.meter();
    const code=current.code.trim();
    if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(code) || !current.chargerCode) {
      this.error.set('Informe o código do medidor e selecione um carregador.');
      return;
    }
    const position=current.energyKind==='ED'?'DOWNSTREAM':'UPSTREAM';
    const payload:MeterInput={
      chargerCode:current.chargerCode,energyKind:current.energyKind,meterPosition:position,
      sourceSystem:current.sourceSystem.trim()||null,
      calibrationEvidenceRef:current.calibrationEvidenceRef.trim()||null,
      calibrationValidFrom:current.calibrationValidFrom.trim()||null,
      calibrationValidUntil:current.calibrationValidUntil.trim()||null,
      configuredFrom:current.configuredFrom.trim()||null,
      configuredUntil:current.configuredUntil.trim()||null,
      timeZone:current.timeZone,
    };
    if(!this.meterRetry)this.meterRetry=crypto.randomUUID();
    this.saving.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.put(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/meters/'+encodeURIComponent(code),
        payload,{headers:{'If-Match':'"'+this.meterVersion()+'"','Idempotency-Key':this.meterRetry}}));
      this.newMeter();
      await this.load();
      this.notice.set('Configuração versionada salva; a calibração exige revisão independente.');
    } catch(problem) {
      this.error.set(this.message(problem));
      if(problem instanceof HttpErrorResponse&&problem.status>=400&&problem.status<500)this.meterRetry='';
    } finally {this.saving.set(false);}
  }

  pickCsv(event:Event) {
    const input=event.target as HTMLInputElement;
    this.selectedCsv.set(input.files?.[0]??null);
    this.uploadRetry='';
  }

  changeSource(source:string) {
    this.uploadSource.set(source);
    this.uploadRetry='';
  }

  async uploadCsv(event:Event) {
    event.preventDefault();
    if(!this.canSubmit||this.uploading())return;
    const file=this.selectedCsv();
    const source=this.uploadSource().trim();
    if(!file || file.size<=0 || file.size>2_000_000 ||
        !/^[A-Za-z0-9][A-Za-z0-9._-]{4,115}\.csv$/.test(file.name) ||
        !/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(source)) {
      this.error.set('Escolha CSV UTF-8 válido, até 2 MB, e informe a origem cadastrada do medidor.');
      return;
    }
    if(!this.uploadRetry)this.uploadRetry=crypto.randomUUID();
    this.error.set('');this.notice.set('');this.uploading.set(true);
    try {
      const result=await firstValueFrom(this.http.post<MeasurementBatch>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+'/technical/open-monitoring/batches',
        new Blob([file],{type:'text/csv'}),{headers:{
          'Content-Type':'text/csv','X-Source-Id':source,'X-File-Name':file.name,
          'Idempotency-Key':this.uploadRetry
        }}));
      this.selectedCsv.set(null);
      this.uploadRetry='';
      await this.load();
      this.notice.set(result.status==='QUARANTINED'
        ? 'Lote preservado em quarentena. Confira as causas por linha; nenhum dado entrou no E8.'
        : 'CSV preservado como candidato. Ainda exige revisão independente e não alimenta o E8.');
    } catch(problem) {
      this.error.set(this.message(problem));
      if(problem instanceof HttpErrorResponse&&problem.status>=400&&problem.status<500)
        this.uploadRetry='';
    } finally {this.uploading.set(false);}
  }

  reviewFor(batch:string,line:number) {
    return this.reviews()[batch+'/'+line];
  }

  selectReview(batch:string,line:number) {
    const previous=this.reviewFor(batch,line);
    this.selectedReview.set({batch,line});
    this.reviewOutcome.set(previous?.outcome??'REJECTED');
    this.reviewReason.set(previous?.reason??'');
  }

  async saveReview(event:Event) {
    event.preventDefault();
    if(!this.canReview||this.reviewing())return;
    const selected=this.selectedReview(),reason=this.reviewReason().trim();
    if(!selected||reason.length<15||reason.length>2000) {
      this.error.set('O parecer exige justificativa entre 15 e 2000 caracteres.');
      return;
    }
    this.reviewing.set(true);this.error.set('');
    try {
      const current=this.reviewFor(selected.batch,selected.line);
      await firstValueFrom(this.http.post<QaReview>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/batches/'+selected.batch+
        '/rows/'+selected.line+'/reviews',
        {outcome:this.reviewOutcome(),reason},
        {headers:{'If-Match':'"'+(current?.version??0)+'"'}}));
      this.selectedReview.set(null);
      this.qaPreview.set(null);
      await this.load();
      this.notice.set('Parecer versionado registrado. Não representa aprovação de VVB ou Verra.');
    } catch(problem) {
      this.error.set(this.message(problem));
    } finally {this.reviewing.set(false);}
  }

  async checkPeriod(event:Event) {
    event.preventDefault();
    if(this.qaLoading())return;
    const from=this.qaFrom().trim(),until=this.qaUntil().trim();
    if(!from||!until) {
      this.error.set('Informe início e fim com fuso horário explícito.');
      return;
    }
    this.qaLoading.set(true);this.error.set('');this.qaPreview.set(null);
    try {
      const root='/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/period-qa';
      const result=await firstValueFrom(this.http.get<QaPeriod>(
        root+'?from='+encodeURIComponent(from)+'&until='+encodeURIComponent(until)));
      this.qaPreview.set(result);
    } catch(problem) {this.error.set(this.message(problem));}
    finally {this.qaLoading.set(false);}
  }

  async freezePeriod() {
    if(!this.canReview||this.qaLoading()||
      !this.qaPreview()?.readyForInternalSnapshot)return;
    this.qaLoading.set(true);this.error.set('');
    try {
      const snapshot=await firstValueFrom(this.http.post<QaSnapshot>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/period-qa/snapshots',
        {from:this.qaFrom().trim(),until:this.qaUntil().trim()}));
      await this.load();
      this.qaPreview.set(null);
      this.notice.set('Snapshot '+snapshot.id+' preservado para QA interno. Não alimenta E8.');
    } catch(problem) {this.error.set(this.message(problem));}
    finally {this.qaLoading.set(false);}
  }

  async inspectSnapshot(snapshot:string) {
    this.proofSnapshot.set(snapshot);
    this.proofLoading.set(true);
    this.error.set('');
    try {
      const state=await firstValueFrom(this.http.get<SupportReadiness>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/period-qa/snapshots/'+snapshot+'/admissibility'));
      if(this.proofSnapshot()===snapshot && state.snapshotId===snapshot)
        this.proofReadiness.set(state);
    } catch(problem) {this.error.set(this.message(problem));}
    finally {this.proofLoading.set(false);}
  }

  chooseProofFile(event:Event) {
    const input=event.target as HTMLInputElement;
    this.proofFile.set(input.files?.[0]??null);
    this.proofRetry='';
  }

  async uploadProof(event:Event) {
    event.preventDefault();
    const snapshot=this.proofSnapshot(),file=this.proofFile();
    const subject=this.proofSubject().trim(),ref=this.proofReference().trim();
    if(!this.canSubmit||!snapshot||!file||this.proofUploading())return;
    if(file.size<16||file.size>2_000_000||!/^[A-Za-z0-9][A-Za-z0-9._-]{4,115}\.pdf$/.test(file.name) ||
      !/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(subject) ||
      ref.length<8||ref.length>300) {
      this.error.set('Informe tipo, identificador, referência e PDF de até 2 MB.');
      return;
    }
    this.proofUploading.set(true);this.error.set('');
    if(!this.proofRetry)this.proofRetry=crypto.randomUUID();
    try {
      await firstValueFrom(this.http.post(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/period-qa/snapshots/'+snapshot+'/support',
        new Blob([file],{type:'application/pdf'}),{headers:{
          'Content-Type':'application/pdf','X-Evidence-Kind':this.proofKind(),
          'X-Subject-Code':subject,'X-Evidence-Reference':ref,'X-File-Name':file.name,
          'Idempotency-Key':this.proofRetry}}));
      this.proofRetry='';this.proofFile.set(null);
      await this.inspectSnapshot(snapshot);
      this.notice.set('Documento cifrado anexado. Aguarda conferência independente; E8 bloqueado.');
    } catch(problem) {
      this.error.set(this.message(problem));
      if(problem instanceof HttpErrorResponse && problem.status>=400&&problem.status<500)
        this.proofRetry='';
    } finally {this.proofUploading.set(false);}
  }

  selectProofReview(document:SupportDocument) {
    this.proofReviewId.set(document.id);
    this.proofReviewReason.set(document.review?.reason??'');
    this.proofReviewOutcome.set(document.review?.outcome??'REJECTED');
  }

  async saveProofReview(event:Event) {
    event.preventDefault();
    const doc=this.proofReadiness()?.documents.find(d=>d.id===this.proofReviewId());
    const snapshot=this.proofSnapshot(),reason=this.proofReviewReason().trim();
    if(!this.canReview||!doc||!snapshot||this.proofReviewSaving())return;
    if(reason.length<15||reason.length>2000) {
      this.error.set('Registre uma justificativa entre 15 e 2000 caracteres.');
      return;
    }
    this.proofReviewSaving.set(true);this.error.set('');
    try {
      await firstValueFrom(this.http.post(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/period-qa/support/'+doc.id+'/reviews',
        {outcome:this.proofReviewOutcome(),reason},
        {headers:{'If-Match':'"'+(doc.review?.version??0)+'"'}}));
      this.proofReviewId.set(null);
      await this.inspectSnapshot(snapshot);
      this.notice.set('Parecer documental interno versionado. Não equivale a admissão normativa.');
    } catch(problem){this.error.set(this.message(problem));}
    finally {this.proofReviewSaving.set(false);}
  }

  updateEvent(field:keyof EventForm,value:string) {
    this.eventRetry='';
    this.eventForm.update(current=>({...current,[field]:value}));
  }

  chooseEventKind(kind:string) {
    const subjectType=kind==='SOURCE_OUTAGE'?'SOURCE':
      kind==='DATA_GAP'?'SOURCE':'METER';
    this.eventRetry='';
    this.eventForm.update(current=>({...current,kind,subjectType,subjectCode:''}));
  }

  async reportEvent(event:Event) {
    event.preventDefault();
    if(!this.canReportEvent||this.eventSubmitting())return;
    const body=this.eventForm();
    if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(body.subjectCode) ||
       body.incidentReference.trim().length<8 || body.description.trim().length<15 ||
       !body.from.trim()||!body.until.trim()) {
      this.error.set('Informe ativo, horário com fuso, referência e descrição da ocorrência.');
      return;
    }
    if(!this.eventRetry)this.eventRetry=crypto.randomUUID();
    this.eventSubmitting.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.post(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/events',
        {...body,subjectCode:body.subjectCode.trim(),
          incidentReference:body.incidentReference.trim(),
          description:body.description.trim()},
        {headers:{'Idempotency-Key':this.eventRetry}}));
      this.eventRetry='';this.eventForm.set({...NEW_EVENT});
      this.qaPreview.set(null);
      await this.load();
      if(this.proofSnapshot())await this.inspectSnapshot(this.proofSnapshot()!);
      this.notice.set('Ocorrência registrada. Períodos afetados exigem revisão; snapshots antigos são reavaliados.');
    } catch(problem) {
      this.error.set(this.message(problem));
      if(problem instanceof HttpErrorResponse&&problem.status>=400&&problem.status<500)
        this.eventRetry='';
    } finally {this.eventSubmitting.set(false);}
  }

  selectEventReview(incident:OperationalEvent) {
    this.eventReviewId.set(incident.id);
    this.eventReviewOutcome.set(incident.review?.outcome??'CONFIRMED_BLOCKING');
    this.eventReviewReason.set(incident.review?.reason??'');
    this.eventReviewEvidence.set(incident.review?.evidenceReference??'');
  }

  async saveEventReview(event:Event) {
    event.preventDefault();
    const incident=this.operationalEvents().find(e=>e.id===this.eventReviewId());
    if(!this.canReviewEvent||!incident||this.eventReviewSaving())return;
    const reason=this.eventReviewReason().trim();
    const evidence=this.eventReviewEvidence().trim();
    if(reason.length<15||reason.length>2000||evidence.length<8||evidence.length>300) {
      this.error.set('Registre motivo (15–2000 caracteres) e referência de evidência (8–300).');
      return;
    }
    this.eventReviewSaving.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.post(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/events/'+incident.id+'/reviews',
        {outcome:this.eventReviewOutcome(),reason,evidenceReference:evidence},
        {headers:{'If-Match':'"'+(incident.review?.version??0)+'"'}}));
      this.eventReviewId.set(null);
      this.qaPreview.set(null);
      await this.load();
      if(this.proofSnapshot())await this.inspectSnapshot(this.proofSnapshot()!);
      this.notice.set('Parecer de ocorrência versionado. Liberação para QA não valida dados nem ressuscita snapshots.');
    } catch(problem){this.error.set(this.message(problem));}
    finally {this.eventReviewSaving.set(false);}
  }

  chooseMr(item:MrDossier) {
    this.mrCode.set(item.reference.code);
    this.mrSnapshot.set(item.reference.snapshotId);
    this.mrNote.set(item.reference.note);
  }

  async saveMrDossier(event:Event) {
    event.preventDefault();
    if(!this.editable||this.mrBusy())return;
    const code=this.mrCode().trim(),snapshotId=this.mrSnapshot();
    const note=this.mrNote().trim();
    if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/.test(code) ||
      !snapshotId||note.length<15||note.length>2000) {
      this.error.set('Informe código do período, snapshot e justificativa entre 15 e 2000 caracteres.');
      return;
    }
    const current=this.mrDossiers().find(d=>d.reference.code===code);
    this.mrBusy.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.put<MrDossier>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/dossiers/'+encodeURIComponent(code),
        {snapshotId,note},
        {headers:{'If-Match':'"'+(current?.reference.version??0)+'"'}}));
      await this.load();
      this.methodSelected.set(null);
      this.notice.set('Vínculo MR versionado. O dossiê permanece sem admissão metodológica e sem E8.');
    } catch(problem){this.error.set(this.message(problem));}
    finally {this.mrBusy.set(false);}
  }

  async inspectMethod(period:string) {
    if(!this.canViewMethod)return;
    const project=this.projectId;
    this.methodLoading.set(true);this.error.set('');
    try {
      const view=await firstValueFrom(this.http.get<MethodView>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/dossiers/'+encodeURIComponent(period)+'/method-review'));
      if(this.projectId!==project)return;
      this.methodSelected.set(view);
      this.methodChecks.set(view.review?.checks.map(c=>({...c}))??emptyMethodChecks());
      this.methodOutcome.set(view.review?.outcome??'RETURNED');
      this.methodRationale.set(view.review?.rationale??'');
    } catch(problem){this.error.set(this.message(problem));}
    finally {this.methodLoading.set(false);}
  }

  updateMethodCheck(requirement:MethodCriterion, field:keyof Omit<MethodCheck,'requirement'>,
                    value:string) {
    this.methodChecks.update(all=>all.map(item=>item.requirement===requirement?
      {...item,[field]:value}:item));
  }

  async saveMethodReview(event:Event) {
    event.preventDefault();
    const selected=this.methodSelected();
    if(!this.canReviewMethod||!selected||this.methodBusy())return;
    const checks=this.methodChecks().map(x=>({
      ...x,evidenceReference:x.evidenceReference.trim(),reason:x.reason.trim()
    }));
    const rationale=this.methodRationale().trim();
    if(rationale.length<15||rationale.length>2000 ||
       checks.length!==METHOD_CRITERIA.length ||
       checks.some(c=>c.evidenceReference.length<8||c.reason.length<15) ||
       this.methodOutcome()==='CHECKED_INTERNAL' &&
         checks.some(c=>c.finding!=='SUPPORTED_INTERNAL')) {
      this.error.set('Para concluir a conferência, fundamente os seis critérios e informe referências verificáveis.');
      return;
    }
    this.methodBusy.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.post<MethodView>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/dossiers/'+encodeURIComponent(selected.period)+'/method-review',
        {dossierVersion:selected.dossierVersion,outcome:this.methodOutcome(),rationale,checks},
        {headers:{
          'If-Match':'"'+(selected.review?.version??0)+'"',
          'Idempotency-Key':crypto.randomUUID()
        }}));
      await this.inspectMethod(selected.period);
      this.notice.set('Parecer metodológico interno versionado. E8, VVB e VCU continuam bloqueados.');
    } catch(problem){this.error.set(this.message(problem));}
    finally {this.methodBusy.set(false);}
  }

  async inspectEnergy(period:string) {
    if(!this.canViewEnergy)return;
    const project=this.projectId;
    this.energyPeriod.set(period);
    this.energyBusy.set(true);this.error.set('');
    try {
      const records=await firstValueFrom(this.http.get<EnergyCandidate[]>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+
        '/technical/open-monitoring/dossiers/'+encodeURIComponent(period)+
        '/e8-energy-candidates'));
      if(this.projectId===project && this.energyPeriod()===period)
        this.energyHistory.set(records);
    } catch(problem){this.error.set(this.message(problem));}
    finally {this.energyBusy.set(false);}
  }

  async prepareEnergy(item:MrDossier) {
    if(!this.canPrepareEnergy || this.energyPreparing())return;
    const project=this.projectId;
    this.energyPreparing.set(true);this.error.set('');this.notice.set('');
    const root='/v1/projects/'+encodeURIComponent(this.projectId)+
      '/technical/open-monitoring/dossiers/'+encodeURIComponent(item.reference.code);
    try {
      const current=await firstValueFrom(this.http.get<MethodView>(root+'/method-review'));
      if(this.projectId!==project)return;
      if(current.state!=='CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION' ||
          !current.review || current.dossierVersion!==item.reference.version) {
        this.error.set('O MR e a conferência interna precisam estar vigentes. Confira as pendências.');
        return;
      }
      await firstValueFrom(this.http.post<EnergyCandidate>(root+'/e8-energy-candidates',
        {dossierVersion:item.reference.version,methodReviewVersion:current.review.version},
        {headers:{'If-Match':'"'+current.review.version+'"',
          'Idempotency-Key':crypto.randomUUID()}}));
      if(this.projectId===project) {
        await this.inspectEnergy(item.reference.code);
        this.notice.set('Pacote ED/EC histórico preparado. NOT_ADMITTED: ainda não pode entrar no cálculo E8.');
      }
    } catch(problem){this.error.set(this.message(problem));}
    finally {this.energyPreparing.set(false);}
  }

  stateName(code:string) {
    const labels:Record<string,string>={
      MISSING:'A informar',STALE:'Rever revisão',
      BLOCKED:'Cobertura insuficiente',NEEDS_REVIEW:'Revisão necessária',
      PENDING_REVIEW:'Aguardando revisão',PENDING_MEASUREMENT:'Sem medição admitida',
      OUT_OF_SCOPE:'Fora do ramo inicial',BLOCKED_OUT_OF_SCOPE:'Ramo incompatível',
    };
    return labels[code]??code;
  }

  private message(problem:unknown) {
    if(problem instanceof HttpErrorResponse) {
      if(problem.status===412)return 'O registro foi atualizado por outra pessoa. Recarregue antes de editar.';
      if(problem.status===428)return 'É necessária a versão atual do registro.';
      if([401,403,404].includes(problem.status))return 'Seu acesso ao monitoramento está indisponível.';
      if(problem.status===409)return 'Conflito: revise evidências, versões e pendências do QA antes de tentar novamente.';
      if(problem.status===503)return 'Armazenamento cifrado indisponível. A equipe deve configurar a chave de evidências.';
      if(problem.status===400||problem.status===422)return 'Confira campos, datas com fuso, unidade e referência de origem/calibração.';
    }
    return 'Não foi possível consultar ou salvar o monitoramento.';
  }
}
