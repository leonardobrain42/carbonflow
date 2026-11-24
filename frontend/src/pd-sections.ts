import {Component, inject, input, output, OnInit, OnDestroy, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {calculationGateTitle, equationTerm, monitoringGateTitle, plainTerm} from './calculation-language';

interface Guide {
  id:string; title:string; version:number; missing:string[]; answers:Record<string,string>;
  fields:{key:string;label:string}[];
}
interface Version {number:number;guideVersion:number;author:string;narrative:string;sha256:string}
interface CalculationProvenance {
  sectionId:string;runId:string;runVersion:number;inputSha256:string;memoryHref:string;
  method:string;engine:string;reviewStatus:string;kernelStatus:string;recordedAt:string;
  timing:{sourceYear:number|null;projectYear:number|null;windowStart:string|null;windowEndExclusive:string|null}|null;
  inputs:{symbol:string;origin:string;unit:string|null;vintageYear:number|null;
    referenceId:string|null;version:number|null;href:string|null;access:string}[];
  references:{code:string;reference:string;unit:string;result:string}[];limits:string;
}
interface ParameterDetail {
  id:string;projectId:string;parameterKind:string;version:number;vintageYear:number;
  methodology:string;methodologyEdition:string;normalizedUnit:string|null;
  conversionFormula:string|null;valueKind:string;state:string;stale:boolean;usableForE8:boolean;
}
interface EnergyDetail {
  id:string;projectId:string;period:string;version:number;state:string;stale:boolean;usableForE8:boolean;
  hourCount:number;slotCount:number;createdAt:string;
  hours:{hour:string|null;edKwh:string|null;ecKwh:string|null}[];
}
interface FleetDetail {
  id:string;projectId:string;period:string;version:number;observationYear:number;
  cycle:string|null;state:string;stale:boolean;usableForE8:boolean;publishedAt:string;
}
interface MemoryDetail {
  id:string;projectId:string;period:string;version:number;inputSha256:string;state:string;
  stale:boolean;canIssueVcu:boolean;engine:string;
  memory:{engine:string;usableForE8:boolean;beTco2e:string;peTco2e:string;leTco2e:string;
    erTco2e:string;erDisplay:string;trace:{code:string;reference:string;unit:string;result:string}[];
    hourlyPe:{windowStart:string;windowEndExclusive:string;region:string;peTco2e:string;
      hours:{hourStart:string;peTco2e:string|null}[]}|null}|null;
}
interface CalculationNote {period:string;situation:string;causeCode:string|null;cause:string|null;
  beTco2e:string|null;peTco2e:string|null;leTco2e:string|null;erDisplay:string|null;
  usableForE8:boolean;marksDocumentReady:boolean;text:string;provenance?:CalculationProvenance|null}
interface OpinionCriterion {code:string;title:string;stateLabel:string;text:string;
  humanOpinion:boolean;concludes:boolean}
interface AdditionalityNote {declarationRoute:string|null;declarationIsOpinion:boolean;
  concludesAdditionality:boolean;usableForE8:boolean;marksDocumentReady:boolean;text:string;
  criteria:OpinionCriterion[]}
interface BaselineNote {period:string;situation:string;observationYear:number|null;cycle:string|null;
  declaredRating:string|null;declarationIsRating:boolean;admitsBaseline:boolean;
  copiesLicensedRatings:boolean;usableForE8:boolean;marksDocumentReady:boolean;text:string}
interface ApplicabilityNote {situation:string;citation:string;citedStockPercent:string;
  citedVintageYear:number;stockLimitPercent:string;salesLimitPercent:string;
  citationBelowStockLimit:boolean;stockSeriesLabel:string;salesSeriesLabel:string;
  closesApplicability:boolean;usableForE8:boolean;marksDocumentReady:boolean;text:string}
interface Section {guide:Guide;latest:Version | null;
  monitoringDependency:{state:string;outdated:boolean;
    gates:{code:string;detail:string}[]} | null;
  calculation?:CalculationNote|null;additionality?:AdditionalityNote|null;
  applicability?:ApplicabilityNote|null;baseline?:BaselineNote|null}

@Component({selector:'carbonflow-pd-sections',templateUrl:'./pd-sections.html'})
export class PdSectionsComponent implements OnInit, OnDestroy {
  readonly initialGuide = input<string|null>(null);
  readonly calculationRun = input<string|null>(null);
  readonly calculationPeriod = input<string|null>(null);
  readonly review = output<string>();
  ngOnInit() { if (this.initialGuide()) void this.load(); }
  readonly projectId = input.required<string>();
  readonly editable = input(false);
  readonly items = signal<Section[] | null>(null);
  readonly current = signal<Section | null>(null);
  readonly text = signal('');
  readonly busy = signal(false);
  readonly message = signal('');
  readonly pinDetail = signal<ParameterDetail|null>(null);
  readonly pinLoading = signal(false);
  readonly pinError = signal('');
  readonly energyDetail = signal<EnergyDetail|null>(null);
  readonly energyLoading = signal(false);
  readonly energyError = signal('');
  readonly fleetDetail = signal<FleetDetail|null>(null);
  readonly fleetLoading = signal(false);
  readonly fleetError = signal('');
  readonly memoryDetail = signal<MemoryDetail|null>(null);
  readonly memoryLoading = signal(false);
  readonly memoryError = signal('');
  private sourceRequest = 0;
  ngOnDestroy() { this.clearDetails(); }

  private clearDetails() {
    ++this.sourceRequest;
    this.pinDetail.set(null);
    this.pinLoading.set(false);
    this.pinError.set('');
    this.energyDetail.set(null);
    this.energyLoading.set(false);
    this.energyError.set('');
    this.fleetDetail.set(null);
    this.fleetLoading.set(false);
    this.fleetError.set('');
    this.memoryDetail.set(null);
    this.memoryLoading.set(false);
    this.memoryError.set('');
  }

  async loadPin(symbol:string, id:string|null) {
    const source=this.current()?.calculation?.provenance?.inputs.find(item =>
      item.symbol===symbol && item.referenceId===id && item.access==='PROJECT_METADATA_ONLY');
    if (!source || !id || !['EF_FUEL','IR','DY'].includes(symbol) || this.busy()) return;
    this.clearDetails();
    const request=this.sourceRequest;
    const project=this.projectId();
    this.pinLoading.set(true);
    try {
      const detail=await firstValueFrom(this.http.get<ParameterDetail>(
        '/v1/projects/'+encodeURIComponent(project)+'/technical/open-parameter-bindings/'+encodeURIComponent(id)));
      if (request!==this.sourceRequest || project!==this.projectId()) return;
      if (detail.id!==id || detail.projectId!==project || detail.parameterKind!==symbol
          || detail.version!==source.version || detail.usableForE8)
        throw new Error('Referência inconsistente');
      this.pinDetail.set(detail);
    } catch (cause) {
      if (request===this.sourceRequest) {
        this.pinDetail.set(null);
        this.pinError.set(cause instanceof HttpErrorResponse && [401,403,404].includes(cause.status)
          ? 'Detalhe protegido ou indisponível para esta conta.'
          : 'Não foi possível conferir o pino. Tente novamente.');
      }
    } finally {
      if (request===this.sourceRequest) this.pinLoading.set(false);
    }
  }
  async loadEnergy(id:string|null) {
    const calculation=this.current()?.calculation;
    const source=calculation?.provenance?.inputs.find(item => item.symbol==='ED'
      && item.referenceId===id && item.access==='PROJECT_METADATA_ONLY');
    if (!source || !id || !calculation || this.busy()) return;
    this.clearDetails();
    const request=this.sourceRequest;
    const project=this.projectId();
    const period=calculation.period;
    this.energyLoading.set(true);
    try {
      const detail=await firstValueFrom(this.http.get<EnergyDetail>(
        '/v1/projects/'+encodeURIComponent(project)+'/technical/open-monitoring/dossiers/'
        +encodeURIComponent(period)+'/e8-input-bindings/'+encodeURIComponent(id)));
      if (request!==this.sourceRequest || project!==this.projectId()) return;
      if (detail.id!==id || detail.projectId!==project || detail.period!==period
          || detail.version!==source.version || detail.usableForE8 || !Array.isArray(detail.hours))
        throw new Error('Referência inconsistente');
      this.energyDetail.set(detail);
    } catch (cause) {
      if (request===this.sourceRequest) {
        this.energyDetail.set(null);
        this.energyError.set(cause instanceof HttpErrorResponse && [401,403,404].includes(cause.status)
          ? 'Energia protegida ou indisponível para esta conta.'
          : 'Não foi possível conferir o vínculo de energia. Tente novamente.');
      }
    } finally {
      if (request===this.sourceRequest) this.energyLoading.set(false);
    }
  }
  async loadFleet(id:string|null) {
    const calculation=this.current()?.calculation;
    const source=calculation?.provenance?.inputs.find(item => item.symbol==='EVR'
      && item.referenceId===id && item.access==='PROJECT_METADATA_ONLY');
    if (!source || !id || !calculation || this.busy()) return;
    this.clearDetails();
    const request=this.sourceRequest;
    const project=this.projectId();
    const period=calculation.period;
    this.fleetLoading.set(true);
    try {
      const detail=await firstValueFrom(this.http.get<FleetDetail>(
        '/v1/projects/'+encodeURIComponent(project)+'/technical/open-monitoring/dossiers/'
        +encodeURIComponent(period)+'/e8-fleet-selections/'+encodeURIComponent(id)));
      if (request!==this.sourceRequest || project!==this.projectId()) return;
      if (detail.id!==id || detail.projectId!==project || detail.period!==period
          || detail.version!==source.version || detail.usableForE8
          || (source.vintageYear!==null && detail.observationYear!==source.vintageYear))
        throw new Error('Referência inconsistente');
      this.fleetDetail.set(detail);
    } catch (cause) {
      if (request===this.sourceRequest) {
        this.fleetDetail.set(null);
        this.fleetError.set(cause instanceof HttpErrorResponse && [401,403,404].includes(cause.status)
          ? 'Seleção de frota protegida ou indisponível para esta conta.'
          : 'Não foi possível conferir a seleção de frota. Tente novamente.');
      }
    } finally {
      if (request===this.sourceRequest) this.fleetLoading.set(false);
    }
  }
  async loadMemory() {
    const calculation=this.current()?.calculation;
    const source=calculation?.provenance;
    if (!source || calculation?.situation!=='MEMORY' || calculation.usableForE8
        || calculation.marksDocumentReady || this.busy()) return;
    this.clearDetails();
    const request=this.sourceRequest;
    const project=this.projectId();
    const period=calculation.period;
    this.memoryLoading.set(true);
    try {
      const detail=await firstValueFrom(this.http.get<MemoryDetail>(
        '/v1/projects/'+encodeURIComponent(project)+'/technical/open-monitoring/dossiers/'
        +encodeURIComponent(period)+'/e8-runs/'+encodeURIComponent(source.runId)));
      if (request!==this.sourceRequest || project!==this.projectId()) return;
      if (detail.id!==source.runId || detail.projectId!==project || detail.period!==period
          || detail.version!==source.runVersion || detail.inputSha256!==source.inputSha256)
        throw new Error('Snapshot inconsistente');
      if (detail.stale || detail.state!=='REVIEWED_INTERNAL' || !detail.memory) {
        this.memoryError.set('Memória indisponível ou desatualizada. Atualize as seções para revalidar a tentativa.');
        return;
      }
      const memory=detail.memory;
      if (detail.canIssueVcu || memory.usableForE8 || detail.engine!==source.engine
          || memory.engine!==source.engine || memory.beTco2e!==calculation.beTco2e
          || memory.peTco2e!==calculation.peTco2e || memory.leTco2e!==calculation.leTco2e
          || memory.erDisplay!==calculation.erDisplay)
        throw new Error('Memória inconsistente');
      this.memoryDetail.set(detail);
    } catch (cause) {
      if (request===this.sourceRequest) {
        this.memoryDetail.set(null);
        this.memoryError.set(cause instanceof HttpErrorResponse && [401,403,404].includes(cause.status)
          ? 'Memória protegida ou indisponível para esta conta.'
          : 'Não foi possível conferir a memória desta tentativa. Atualize as seções e tente novamente.');
      }
    } finally {
      if (request===this.sourceRequest) this.memoryLoading.set(false);
    }
  }
  readonly term = plainTerm;
  readonly gateTitle = monitoringGateTitle;
  readonly calculationTitle = calculationGateTitle;
  readonly inputTitle = equationTerm;
  private readonly http = inject(HttpClient);
  get url() { return '/v1/projects/' + encodeURIComponent(this.projectId()) + '/technical/pd-sections'; }
  canLeave() {
    return !this.busy() && (this.text() === (this.current()?.latest?.narrative ?? '') || confirm('Descartar a narrativa ainda não salva?'));
  }

  async load() {
    if (!this.canLeave()) return;
    this.busy.set(true);
    this.clearDetails();
    this.message.set('');
    try {
      const items = await firstValueFrom(this.http.get<Section[]>(this.url, {params:{
        ...(this.calculationRun() ? {run:this.calculationRun()!} : {}),
        ...(this.calculationPeriod() ? {period:this.calculationPeriod()!} : {}),
      }}));
      this.items.set(items);
      this.show(items.find(item => item.guide.id === (this.current()?.guide.id ?? this.initialGuide())) ?? items[0] ?? null);
    } catch (cause) { this.failed(cause); }
    finally { this.busy.set(false); }
  }

  select(id:string) {
    if (this.canLeave()) this.show(this.items()?.find(item => item.guide.id === id) ?? null);
  }

  private show(item:Section | null) {
    this.clearDetails();
    this.current.set(item);
    this.text.set(item?.latest?.narrative ?? '');
    this.message.set('');
  }

  async save() {
    const item = this.current();
    if (!item || !this.editable() || this.busy()) return;
    this.busy.set(true);
    try {
      const saved = await firstValueFrom(this.http.put<Version>(this.url + '/' + item.guide.id,
        {guideVersion:item.guide.version,narrative:this.text()}, {headers:{'If-Match':'"' + (item.latest?.number ?? 0) + '"'}}));
      const next = {...item,latest:saved,
        monitoringDependency:null};
      // Reload the live dependency status: a new PD version pins the
      // current MR manifest, which may still contain unresolved QA gates.
      this.items.update(items => items?.map(old => old.guide.id === item.guide.id ? next : old) ?? [next]);
      this.show(next);
      this.message.set('Seção salva na versão ' + saved.number + '.');
    } catch (cause) { this.failed(cause); }
    finally { this.busy.set(false); }
  }

  private failed(cause:unknown) {
    if (cause instanceof HttpErrorResponse && [401,404].includes(cause.status)) {
      this.items.set(null);
      this.show(null);
    }
    this.message.set(cause instanceof HttpErrorResponse && cause.status === 412
      ? 'A seção mudou. Copie sua narrativa e consulte a versão atual.'
      : cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string'
        ? cause.error.message : 'Não foi possível acessar a seção.');
  }
}
