import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

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
  templateUrl:'./open-monitoring.html',
})
export class OpenMonitoringComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input() editable=false;
  @Input() canSubmit=false;
  @Input() canReview=false;
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

  ngOnChanges(changes:SimpleChanges) {
    if(changes['projectId'])void this.load();
  }

  async load() {
    if(!this.projectId)return;
    const id=++this.sequence;
    this.loading.set(true);
    this.error.set('');
    try {
      const root='/v1/projects/'+encodeURIComponent(this.projectId)+'/technical/open-monitoring';
      const [result, batches, snapshots]=await Promise.all([
        firstValueFrom(this.http.get<View>(root)),
        firstValueFrom(this.http.get<MeasurementBatch[]>(root+'/batches')),
        firstValueFrom(this.http.get<QaSnapshot[]>(root+'/period-qa/snapshots'))
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
