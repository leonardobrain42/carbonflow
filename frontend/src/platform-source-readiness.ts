import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpParams} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';
import {PlatformSourceOperationsComponent} from './platform-source-operations';
import {PlatformSourceIngestionComponent} from './platform-source-ingestion';
import {PlatformFleetComponent} from './platform-fleet';
import {PlatformRatingsComponent} from './platform-ratings';
import {PlatformFossilFactorsComponent} from './platform-fossil-factors';
import {PlatformParameterPinsComponent} from './platform-parameter-pins';

type SourceTab = 'overview' | 'normative' | 'datasets' | 'fleet' | 'ratings' | 'fossil' | 'parameters' | 'project' | 'admissibility' | 'acquisition' | 'ingestion' | 'history' | 'impacts';

type SourceStatus = 'MISSING_SOURCE_METADATA' | 'METADATA_ONLY'
  | 'SCOPED_REFERENCE_NOT_ADMITTED' | 'VERSION_SELECTION_REQUIRED' | 'PROJECT_SPECIFIC_EVIDENCE';
type Stage = 'APPLICABILITY' | 'BASELINE' | 'ADDITIONALITY'
  | 'QUANTIFICATION' | 'MONITORING' | 'DOCUMENTS';

interface StaffSession {verified: boolean; expiresAt: string | null}
interface SourceContext {
  methodology: string; edition: string; scenario: string; title: string; country: string;
  networkMode: string; vehicleCategory: string; chargerType: string; energySystem: string;
  catalogState: 'ACTIVE' | 'SUSPENDED';
}
interface SourceNeed {
  code: string;
  stage: Stage;
  kind: 'NORMATIVE' | 'DATASET' | 'PROJECT_EVIDENCE';
  title: string;
  purpose: string;
  sourceId: string | null;
  sourceEdition: string | null;
  officialUri: string | null;
  mandatory: boolean;
  conditionNote: string;
  status: SourceStatus;
  nextAction: string;
  blockedCapability: string;
  editionPolicy: string;
  responsibleParty: string;
  priority: string;
  observedEdition: string | null;
  rightsScope: string;
  referenceAllowedInStaffOrganization: boolean;
  extractionAllowedInStaffOrganization: boolean;
  ragAllowedInStaffOrganization: boolean;
  modelAllowedInStaffOrganization: boolean;
  technicalAdmissionConfirmed: boolean;
}
interface SourceReadiness {
  methodology: string;
  edition: string;
  scenario: string;
  asOf: string;
  normativeRuleStatus: string;
  dynamicCalculationStatus: string;
  normativeAiDisabled: boolean;
  missingSourceCount: number;
  missingReferenceRightsCount: number;
  reviewPendingCount: number;
  projectEvidenceCount: number;
  total: number;
  nextOffset: number | null;
  needs: SourceNeed[];
}

@Component({
  selector: 'carbonflow-platform-source-readiness',
  standalone: true,
  imports: [RouterLink, PlatformSourceOperationsComponent, PlatformSourceIngestionComponent, PlatformFleetComponent, PlatformRatingsComponent, PlatformFossilFactorsComponent, PlatformParameterPinsComponent],
  templateUrl: './platform-source-readiness.html',
  styleUrl: './platform-source-readiness.css',
})
export class PlatformSourceReadinessComponent {
  private readonly http = inject(HttpClient);
  private requestEpoch = 0;
  readonly session = signal<StaffSession | null>(null);
  readonly contexts = signal<SourceContext[]>([]);
  readonly selectedKey = signal('');
  readonly readiness = signal<SourceReadiness | null>(null);
  readonly loading = signal(false);
  readonly loadingReadiness = signal(false);
  readonly error = signal('');
  readonly stage = signal('ALL');
  readonly statusFilter = signal('ALL');
  readonly pageOffset = signal(0);
  readonly tab = signal<SourceTab>('overview');
  readonly tabs: {id: SourceTab; label: string}[] = [
    {id:'overview',label:'Visão geral'},
    {id:'normative',label:'Documentos normativos'},
    {id:'datasets',label:'Bases de dados'},
    {id:'fleet',label:'Frota EVR'},
    {id:'ratings',label:'Eficiências e comparáveis'},
    {id:'fossil',label:'Fatores fósseis EFfuel'},
    {id:'parameters',label:'Pinos do projeto'},
    {id:'project',label:'Documentos do projeto'},
    {id:'admissibility',label:'Admissibilidade'},
    {id:'acquisition',label:'Alimentação e tarefas'},
    {id:'ingestion',label:'Cadastro de fontes'},
    {id:'history',label:'Histórico'},
    {id:'impacts',label:'Impactos na jornada'},
  ];
  readonly pageLimit = 10;

  readonly stages: {id: Stage; label: string}[] = [
    {id:'APPLICABILITY',label:'Aplicabilidade'},
    {id:'BASELINE',label:'Linha de base'},
    {id:'ADDITIONALITY',label:'Adicionalidade'},
    {id:'QUANTIFICATION',label:'Quantificação'},
    {id:'MONITORING',label:'Monitoramento'},
    {id:'DOCUMENTS',label:'Documentos'},
  ];
  readonly statuses: {id: SourceStatus; label: string}[] = [
    {id:'MISSING_SOURCE_METADATA',label:'Fonte ainda não cadastrada'},
    {id:'METADATA_ONLY',label:'Apenas metadados / direitos não disponíveis'},
    {id:'VERSION_SELECTION_REQUIRED',label:'Fonte encontrada, edição por selecionar'},
    {id:'SCOPED_REFERENCE_NOT_ADMITTED',label:'Direito restrito; admissão técnica pendente'},
    {id:'PROJECT_SPECIFIC_EVIDENCE',label:'Documento a obter em cada projeto'},
  ];
  readonly selectedContext = computed(() => this.contexts().find(c => this.key(c) === this.selectedKey()) ?? null);
  // Optimistic display while the canonical server-filtered page is fetched.
  readonly visibleNeeds = computed(() => (this.readiness()?.needs ?? []).filter(need =>
    (this.stage() === 'ALL' || this.stage() === need.stage)
    && (this.statusFilter() === 'ALL' || this.statusFilter() === need.status)));
  readonly tabNeeds = computed(() => this.visibleNeeds().filter(need => {
    switch (this.tab()) {
      case 'normative': return need.kind === 'NORMATIVE';
      case 'datasets': return need.kind === 'DATASET';
      case 'project': return need.kind === 'PROJECT_EVIDENCE';
      case 'admissibility': return need.kind !== 'PROJECT_EVIDENCE';
      default: return true;
    }
  }));
  readonly canPrevious = computed(() => this.pageOffset() > 0);
  readonly canNext = computed(() => this.readiness()?.nextOffset != null);

  constructor() { void this.loadSession(); }

  selectTab(tab: SourceTab) {
    const before=this.tab();
    this.tab.set(tab);
    if (this.stage() !== 'ALL' || this.statusFilter() !== 'ALL' ||
        this.pageOffset() !== 0 || before === 'ingestion' || tab === 'ingestion') {
      this.stage.set('ALL');
      this.statusFilter.set('ALL');
      this.pageOffset.set(0);
      void this.loadReadiness();
    }
  }

  key(value: SourceContext): string {
    return [value.methodology,value.edition,value.scenario].join('|');
  }

  private async loadSession() {
    this.loading.set(true);
    this.error.set('');
    this.readiness.set(null);
    this.contexts.set([]);
    this.requestEpoch++;
    try {
      const session = await firstValueFrom(this.http.get<StaffSession>('/v1/compliance/session'));
      this.session.set(session);
      if (session.verified && session.expiresAt && Date.parse(session.expiresAt) > Date.now()) {
        await this.loadContexts();
      }
    } catch (error) {
      this.session.set(null);
      this.readiness.set(null);
      const httpStatus = error instanceof HttpErrorResponse ? error.status : 0;
      this.error.set(httpStatus === 404 || httpStatus === 403
        ? 'O inventário é exclusivo da equipe interna autorizada. Solicite a permissão de leitura específica.'
        : 'Não foi possível consultar o inventário. Confira sua sessão e tente novamente.');
    } finally {
      this.loading.set(false);
    }
  }

  private async loadContexts() {
    const contexts = await firstValueFrom(this.http.get<SourceContext[]>('/v1/platform/source-readiness/contexts'));
    this.contexts.set(contexts);
    if (!contexts.some(context => this.key(context) === this.selectedKey())) {
      this.selectedKey.set(contexts[0] ? this.key(contexts[0]) : '');
    }
    this.pageOffset.set(0);
    if (this.selectedKey()) await this.loadReadiness();
  }

  async verify(event: Event, code: HTMLInputElement) {
    event.preventDefault();
    if (this.loading()) return;
    const value = code.value;
    code.value = '';
    this.loading.set(true);
    this.error.set('');
    try {
      const result = await firstValueFrom(this.http.post<StaffSession>(
        '/v1/compliance/session', {code: value}));
      this.session.set(result);
      if (result.verified) await this.loadContexts();
    } catch (error) {
      this.readiness.set(null);
      this.session.set({verified:false,expiresAt:null});
      const status = error instanceof HttpErrorResponse ? error.status : 0;
      this.error.set(status === 429 ? 'Limite de tentativas. Tente novamente após a janela de segurança.'
        : status === 403 ? 'Código inválido ou já utilizado.'
        : 'Não foi possível verificar o acesso ou obter autorização para o inventário.');
    } finally {
      this.loading.set(false);
    }
  }

  refresh() {
    if (this.loading()) return;
    void this.loadSession();
  }

  async loadReadiness() {
    const target = this.selectedContext();
    if (!target) {this.readiness.set(null); return;}
    this.error.set('');
    this.loadingReadiness.set(true);
    const request = ++this.requestEpoch;
    try {
      let params = new HttpParams()
        .set('edition',target.edition).set('scenario',target.scenario)
        .set('limit',this.tab() === 'ingestion' ? 100 : this.pageLimit).set('offset',this.pageOffset());
      if (this.stage() !== 'ALL') params = params.set('stage',this.stage());
      if (this.statusFilter() !== 'ALL') params = params.set('status',this.statusFilter());
      const result = await firstValueFrom(this.http.get<SourceReadiness>(
        '/v1/platform/source-readiness/' + encodeURIComponent(target.methodology),{params}));
      if (request === this.requestEpoch) this.readiness.set(result);
    } catch (reason) {
      if (request !== this.requestEpoch) return;
      const status = reason instanceof HttpErrorResponse ? reason.status : 0;
      this.error.set(status === 403 || status === 404
        ? 'Acesso indisponível ou sessão administrativa expirada. Verifique novamente.'
        : 'Falha ao consultar as necessidades. Tente novamente.');
    } finally {
      if (request === this.requestEpoch) this.loadingReadiness.set(false);
    }
  }

  onContext(event: Event) {
    this.selectedKey.set((event.target as HTMLSelectElement).value);
    this.pageOffset.set(0);
    this.readiness.set(null);
    void this.loadReadiness();
  }

  onStage(event: Event) {
    this.stage.set((event.target as HTMLSelectElement).value);
    this.pageOffset.set(0);
    void this.loadReadiness();
  }

  onStatus(event: Event) {
    this.statusFilter.set((event.target as HTMLSelectElement).value);
    this.pageOffset.set(0);
    void this.loadReadiness();
  }

  next() {
    const nextOffset = this.readiness()?.nextOffset;
    if (nextOffset == null) return;
    this.pageOffset.set(nextOffset);
    void this.loadReadiness();
  }

  previous() {
    if (this.pageOffset() === 0) return;
    this.pageOffset.update(offset => Math.max(0,offset-this.pageLimit));
    void this.loadReadiness();
  }

  stageLabel(value: string): string {
    return this.stages.find(stage => stage.id === value)?.label ?? value;
  }

  statusLabel(value: string): string {
    return this.statuses.find(status => status.id === value)?.label ?? value;
  }

  currentData(): SourceReadiness | null {
    const session = this.session();
    if (!session?.verified || !session.expiresAt || Date.parse(session.expiresAt) <= Date.now()) return null;
    return this.readiness();
  }
}
