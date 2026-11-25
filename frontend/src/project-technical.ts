import {TechnicalGlossaryComponent} from './technical-glossary';
import {PdReviewsComponent} from './pd-reviews';
import {PdSectionsComponent} from './pd-sections';
import {ProjectGuidesComponent} from './project-guides';
import {OpenApplicabilityComponent} from './open-applicability';
import {OpenCalculationReadinessComponent} from './open-calculation-readiness';
import {ProjectRequirementsComponent} from './project-requirements';
import {OpenMonitoringComponent} from './open-monitoring';
import {ProjectStationsComponent} from './project-stations';
import {DecisionWorkbenchComponent} from './decision-workbench';
import {JourneyFocusComponent} from './journey-focus';
import {JourneyCheckpointService} from './journey-checkpoint';
import {TriageAnswers, intakeSuggestions} from './triage-suggestions';
import {WorkspaceNavigationComponent} from './workspace-navigation';
import {afterNextRender, Component, computed, effect, ElementRef, inject, Injector, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, ResolveFn, Router, RouterLink} from '@angular/router';
import {toSignal} from '@angular/core/rxjs-interop';
import {catchError, firstValueFrom, map, of} from 'rxjs';
import type {ExperienceMode} from './project-experience';
import {JourneyOption, JourneyProfile, methodologyOption, OTHER_METHODOLOGY} from './project-journey';

interface ProjectMetadata {
  projectId: string;
  organizationId: string;
  environment: string;
  state: string;
  version: number;
  title: string;
  description: string | null;
}

interface IntakeData {
  activity: string | null;
  country: string | null;
  state: string | null;
  locality: string | null;
  candidateMethodology: string | null;
  referenceActDate: string | null;
  notes: string | null;
}

interface Reason {
  code: string;
  rule: string;
  message: string;
}

interface TechnicalIntake {
  projectId: string;
  organizationId: string;
  environment: string;
  version: number;
  state: string;
  reviewState: string;
  data: IntakeData | null;
  outstandingGates: Reason[];
  triageCandidate: string | null;
  journey: JourneyProfile;
  methodologies: JourneyOption[];
  experienceMode?: ExperienceMode;
  applicability?: {revision:number; boundary:string | null; incidence:string | null; rights:string | null};
}

interface JourneyAccess {
  projectId:string;
  environment:string;
  journeyProfile:JourneyProfile;
}

interface PreliminaryDossier {
  id: string;
  revision: number;
  intakeVersion: number;
  state: string;
  documentKind: string;
  sourceTemplate: string;
  sourceEdition: string;
  sections: string[];
  blockers: Reason[];
  intake: IntakeData;
}

interface Capabilities {
  projectId: string;
  environment: string;
  modules: Record<string, string>;
  owner?: boolean;
  actions?: string[];
}

type TechnicalAccess = {capabilities: Capabilities} | {error: unknown};

export const resolveTechnicalAccess: ResolveFn<TechnicalAccess> = route => {
  const id = encodeURIComponent(route.paramMap.get('projectId') ?? '');
  return inject(HttpClient).get<Capabilities>('/v1/projects/' + id + '/technical/capabilities').pipe(
    map(capabilities => ({capabilities})),
    catchError(error => of({error})),
  );
};

/** Stable technical navigation: sections do not appear or disappear after each click.
 * The original URLs, labels and backend actions remain supported. */
const SECTIONS = [
  {id:'inicio',title:'Painel de trabalho',group:'Começar'},
  {id:'entender',title:'Entender',group:'Começar'},
  {id:'preparar',title:'Preparar informações',group:'Começar'},
  {id:'analisar',title:'Situação do projeto',group:'Aplicabilidade'},
  {id:'rede',title:'Acesso à rede',group:'Aplicabilidade'},
  {id:'direitos',title:'Direitos do projeto',group:'Aplicabilidade'},
  {id:'estacoes',title:'Estações e fronteira',group:'Aplicabilidade'},
  {id:'linha-base',title:'Linha de base',group:'Fundamentação'},
  {id:'adicionalidade',title:'Adicionalidade',group:'Fundamentação'},
  {id:'guias',title:'Guias técnicos',group:'Fundamentação'},
  {id:'monitorar',title:'Monitorar energia',group:'Medição'},
  {id:'evidencias',title:'Evidências',group:'Medição'},
  {id:'quantificar',title:'Quantificação',group:'Medição'},
  {id:'fontes',title:'Fontes e parâmetros',group:'Medição'},
  {id:'redigir',title:'Redigir',group:'Documentação'},
  {id:'revisar',title:'Revisar',group:'Documentação'},
] as const;
/** Screens that stay reachable by URL and by a link inside their parent, but no longer take a menu slot.
 * "Evidências" renders the same QA view as Monitorar, step 3; "Entender" and "Fontes" are reference pages. */
const MENU_ALIASES: Record<string,string> = {entender:'inicio',evidencias:'monitorar',fontes:'quantificar'};
type Section = (typeof SECTIONS)[number]['id'];
type IntakeForm = Record<keyof IntakeData, string>;

function formValues(data: IntakeData | null): IntakeForm {
  return {
    activity: data?.activity ?? '', country: data?.country ?? '', state: data?.state ?? '',
    locality: data?.locality ?? '', candidateMethodology: data?.candidateMethodology ?? '',
    referenceActDate: data?.referenceActDate ?? '', notes: data?.notes ?? '',
  };
}

@Component({
  selector: 'carbonflow-project-technical',
  imports: [RouterLink, WorkspaceNavigationComponent, ProjectGuidesComponent, OpenApplicabilityComponent, OpenCalculationReadinessComponent, OpenMonitoringComponent, ProjectStationsComponent, DecisionWorkbenchComponent, JourneyFocusComponent, PdSectionsComponent, PdReviewsComponent, TechnicalGlossaryComponent, ProjectRequirementsComponent],
  templateUrl: './project-technical.html',
})
export class ProjectTechnicalComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly checkpoints = inject(JourneyCheckpointService);
  private readonly injector = inject(Injector);
  private readonly guideEditor = viewChild(ProjectGuidesComponent);
  private readonly requirementsPanel = viewChild(ProjectRequirementsComponent);
  private readonly stationsEditor = viewChild(ProjectStationsComponent);
  private readonly decisionEditor = viewChild(DecisionWorkbenchComponent);
  private readonly pdEditor = viewChild(PdSectionsComponent);
  private readonly pdReview = viewChild(PdReviewsComponent);
  private readonly applicabilityEditor = viewChild(OpenApplicabilityComponent);
  // Internal tab changes keep the intake form mounted and must never discard drafts.
  readonly canLeaveGuides = () => (this.guideEditor()?.canLeave() ?? true) && (this.stationsEditor()?.canLeave() ?? true) && (this.decisionEditor()?.canLeave() ?? true) && (this.pdEditor()?.canLeave() ?? true) && (this.pdReview()?.canLeave() ?? true) && (this.applicabilityEditor()?.canLeave() ?? true);
  readonly canLeaveWorkspace = () => (!this.dirty() ||
    confirm('Sair da preparação e descartar as informações técnicas ainda não salvas?')) &&
    this.canLeaveGuides();
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('sectionHeading');
  private readonly query = toSignal(this.route.queryParamMap);
  readonly selectedGuide = computed(() => this.query()?.get('guia') ?? null);
  readonly calculationRun = computed(() => this.query()?.get('run') ?? null);
  readonly calculationPeriod = computed(() => this.query()?.get('period') ?? null);
  readonly projectId = this.route.snapshot.paramMap.get('projectId') ?? '';
  private readonly root = '/v1/projects/' + encodeURIComponent(this.projectId);
  private readonly technical = this.root + '/technical';
  readonly dossierDownload = this.technical + '/preliminary-dossier/download';

  readonly project = signal<ProjectMetadata | null>(null);
  private readonly access: TechnicalAccess = this.route.snapshot.data['technicalAccess'];
  readonly capabilities = signal<Capabilities | null>('capabilities' in this.access ? this.access.capabilities : null);
  readonly intake = signal<TechnicalIntake | null>(null);
  readonly dossier = signal<PreliminaryDossier | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly form = signal<IntakeForm>(formValues(null));
  /** Owner's own triage answers, offered for empty intake fields; never saved by themselves. */
  readonly triageAnswers = signal<TriageAnswers | null>(null);
  readonly intakeSuggestionsPending = computed(() => {
    const current = this.form() as Record<string, string>;
    return intakeSuggestions(this.triageAnswers()).filter(item => !current[item.field]?.trim());
  });
  readonly owner = computed(() => this.capabilities()?.owner !== false && !!this.capabilities());
  readonly sections = computed(() => SECTIONS.filter(item => {
    if (item.id === 'inicio') return this.can('VIEW_INTAKE');
    if (item.id === 'guias' || item.id === 'linha-base' || item.id === 'adicionalidade')
      return this.can('VIEW_GUIDES');
    if (item.id === 'analisar' || item.id === 'rede' || item.id === 'direitos') return this.can('VIEW_OPEN_APPLICABILITY') || this.can('VIEW_INTAKE');
    if (item.id === 'entender') return this.can('VIEW_JOURNEY');
    if (item.id === 'monitorar' || item.id === 'evidencias' || item.id === 'estacoes')
      return this.can('VIEW_OPEN_MONITORING');
    if (item.id === 'quantificar') return this.can('VIEW_OPEN_CALCULATION');
    if (item.id === 'redigir' || item.id === 'revisar') return this.can('VIEW_DOSSIER');
    return this.can('VIEW_INTAKE');
  }));
  /** Menu entries: an alias is hidden only while its parent is also available to this account. */
  readonly menu = computed(() => {
    const available = new Set<string>(this.sections().map(item => item.id));
    return this.sections().filter(item => {
      const parent = MENU_ALIASES[item.id];
      return !parent || !available.has(parent);
    });
  });
  isCurrent(id: string) {
    const current = this.section().id;
    return current === id || MENU_ALIASES[current] === id;
  }
  readonly queryEtapa = computed(() => this.query()?.get('etapa'));
  readonly monitoringView = computed(() => {
    const selected=this.query()?.get('etapa');
    return selected === 'leituras' ? 'readings' as const :
      selected === 'qa' ? 'qa' as const :
      selected === 'dossies' ? 'dossiers' as const : 'inventory' as const;
  });
  readonly monitoringSteps = [
    {id:'inventario',label:'1. Instalações e medidores'},
    {id:'leituras',label:'2. Leituras e ocorrências'},
    {id:'qa',label:'3. QA e evidências'},
    {id:'dossies',label:'4. Relatório, fator elétrico e caderno'},
  ] as const;
  readonly section = computed(() => {
    const allowed=this.sections();
    const target=allowed.find(item=>item.id===this.query()?.get('secao'));
    // Stable, role-aware landing: the first permitted domain module should not
    // silently replace the intended general guide landing after UX-BL/ADD.
    return target ?? allowed.find(item=>item.id==='inicio') ??
      allowed.find(item=>item.id==='guias') ?? allowed[0] ?? SECTIONS[0];
  });
  readonly dirty = computed(() => this.can('VIEW_INTAKE') &&
    JSON.stringify(formValues(this.intake()?.data ?? null)) !== JSON.stringify(this.form()));
  readonly otherMethodology = signal(false);
  readonly otherOption = OTHER_METHODOLOGY;
  readonly methodologyChoice = computed(() => {
    const intake = this.intake();
    return this.otherMethodology() ? OTHER_METHODOLOGY
      : methodologyOption(this.form().candidateMethodology, intake?.methodologies ?? [], intake?.journey);
  });
  private saveKey = '';
  private savePayload = '';
  private dossierKey = '';
  private dossierVersion = -1;

  constructor() {
    void this.load();
    effect(() => {
      const project=this.project();
      const intake=this.intake();
      if (!project || !intake || this.loading()) return;
      const section=this.section().id;
      const guide=['guias','redigir','revisar'].includes(section)?this.selectedGuide():null;
      const step=['monitorar','linha-base','adicionalidade'].includes(section)
        ?(this.queryEtapa()??null):null;
      this.checkpoints.remember(this.projectId,{
        area:'technical',screen:section,guide,step
      });
    });
  }

  can(action: string) {
    const capabilities = this.capabilities();
    return capabilities?.actions?.includes(action) ?? !!capabilities;
  }

  readonly canPrepare = () => {
    const current = this.intake();
    const dossier = this.dossier();
    return this.can('PREPARE_DOSSIER') && !!current && !this.dirty() && current.version > 0 && (!dossier || dossier.intakeVersion !== current.version);
  };

  private async load() {
    this.loading.set(true);
    this.error.set('');
    try {
      if ('error' in this.access) throw this.access.error;
      const project = await firstValueFrom(this.http.get<ProjectMetadata>(this.root + '/metadata'));
      if (project.projectId !== this.projectId || project.environment !== 'SANDBOX') {
        throw new Error('Contexto incorreto');
      }
      this.project.set(project);
      const [intake, dossier] = await Promise.all([
        this.can('VIEW_INTAKE') ? firstValueFrom(this.http.get<TechnicalIntake>(this.technical + '/intake')) : null,
        this.can('VIEW_DOSSIER') ? firstValueFrom(this.http.get<PreliminaryDossier | null>(this.technical + '/preliminary-dossier')) : null,
      ]);
      if (intake) {
        this.acceptIntake(intake);
      } else {
        // A collaborator can view monitoring or guides without VIEW_INTAKE.
        // Reuse the authorized method profile, NEVER private intake answers.
        let method:JourneyProfile|null=null;
        try {
          const journey=await firstValueFrom(this.http.get<JourneyAccess>(this.root+'/journey'));
          if (journey.projectId===this.projectId && journey.environment==='SANDBOX')
            method=journey.journeyProfile??null;
        }catch{
          // Legacy guide-scoped clients can lack the newer journey projection.
          // Use the package profile that the authorized guide catalog itself carries (A04): the client keeps no
          // methodology catalog of its own, never reads intake and never infers normative eligibility.
          if(this.can('VIEW_GUIDES')){
            try {
              const catalog=await firstValueFrom(this.http.get<{methodology:string;journeyProfile?:JourneyProfile}>(
                this.technical+'/guides'));
              method=catalog.journeyProfile??null;
            }catch{
              // A failed catalog read does not authorize any additional module.
            }
          }
        }
        if(!method){
          method={
            id:'GENERAL',candidate:null,edition:null,title:'Preparação autorizada',
            activitiesTitle:'Escopo',detailsTitle:'Registros disponíveis',
            description:'Consulte apenas os módulos concedidos para esta conta.',
            sourceUrl:null,technicalTopics:[],questions:[]
          };
        }
        this.acceptIntake({
          projectId:this.projectId,organizationId:project.organizationId,
          environment:'SANDBOX',version:0,state:'ACCESS_LIMITED',
          reviewState:'NOT_VISIBLE',data:null,outstandingGates:[],
          triageCandidate:method.candidate,journey:method,methodologies:[]
        });
      }
      this.dossier.set(dossier);
      if (this.owner() && this.can('SAVE_INTAKE')) void this.loadTriage();
      void this.loadPendingCounts();
    } catch (cause: unknown) {
      this.project.set(null);
      this.capabilities.set(null);
      this.intake.set(null);
      this.dossier.set(null);
      this.error.set(this.errorMessage(cause, 'Não foi possível abrir a preparação técnica do projeto.'));
    } finally {
      this.loading.set(false);
    }
  }

  /** Refresh the same backend-owned requirement projection after a canonical save. */
  refreshRequirements() {
    void this.requirementsPanel()?.load();
    void this.loadPendingCounts();
  }

  /** Actions this account can take, per technical screen, from the same requirement projection. */
  readonly pendingByScreen = signal<Record<string, number>>({});
  menuCount(id: string) {
    const counts = this.pendingByScreen();
    let total = counts[id] ?? 0;
    for (const [alias, parent] of Object.entries(MENU_ALIASES))
      if (parent === id && !this.menu().some(item => item.id === alias)) total += counts[alias] ?? 0;
    return total;
  }
  private pendingRead = 0;
  private async loadPendingCounts() {
    const read = ++this.pendingRead;
    try {
      const view = await firstValueFrom(this.http.get<{requirements?: {group:string; clientAction:boolean;
        admitsE8:boolean; destination:{area:string; screen:string} | null}[]}>(this.technical + '/requirements'));
      if (read !== this.pendingRead) return; // an older response never replaces a newer one
      const counts: Record<string, number> = {};
      for (const item of view.requirements ?? []) {
        if (item.group !== 'MINE' || !item.clientAction || item.admitsE8) continue;
        if (item.destination?.area !== 'technical' || !item.destination.screen) continue;
        counts[item.destination.screen] = (counts[item.destination.screen] ?? 0) + 1;
      }
      this.pendingByScreen.set(counts);
    } catch {
      if (read === this.pendingRead) this.pendingByScreen.set({});
    }
  }

  updateGuide(guide: {id:string; version:number; answers:Record<string,string>}) {
    if (guide.id !== 'applicability') return;
    const {boundary,incidence,rights} = guide.answers;
    this.intake.update(state => state && {...state,applicability:{revision:guide.version,
      boundary:boundary ?? null,incidence:incidence ?? null,rights:rights ?? null}});
  }

  /** Bookmarked monitoring screens share one secured project API and preserve history. */
  async openMonitoring(step:'inventario'|'leituras'|'qa'|'dossies') {
    if (this.saving() || !this.canLeaveGuides()) return;
    if (!this.can('VIEW_OPEN_MONITORING')) return;
    await this.router.navigate([], {relativeTo:this.route,
      queryParams:{secao:'monitorar',etapa:step,guia:null},
      queryParamsHandling:'merge'});
    afterNextRender(() => this.heading()?.nativeElement.focus(), {injector:this.injector});
  }

  async openWorkStep(step:string){
    if(this.saving() || !this.canLeaveGuides())return;
    if(!['linha-base','adicionalidade'].includes(this.section().id))return;
    if(await this.router.navigate([],{
      relativeTo:this.route,
      queryParams:{secao:this.section().id,etapa:step,guia:null},
      queryParamsHandling:'merge'
    })){
      afterNextRender(()=>this.heading()?.nativeElement.focus(),{injector:this.injector});
    }
  }

  async open(section: Section, guide: string | null = null) {
    if ((this.section().id===section && this.selectedGuide()===guide && !this.queryEtapa()) ||
        this.saving() || !this.canLeaveGuides()) return;
    this.notice.set('');
    if (await this.router.navigate([], {relativeTo: this.route, queryParams: {secao: section, guia: guide, etapa:null}, queryParamsHandling: 'merge'})) {
      afterNextRender(() => this.heading()?.nativeElement.focus(), {injector: this.injector});
    }
  }

  applyIntakeTriage() {
    for (const item of this.intakeSuggestionsPending()) this.update(item.field as keyof IntakeForm, item.value);
  }

  private async loadTriage() {
    try {
      const view = await firstValueFrom(this.http.get<{answers?: TriageAnswers}>(this.root + '/triage'));
      this.triageAnswers.set(view.answers ?? null);
    } catch { this.triageAnswers.set(null); }
  }

  update(field: keyof IntakeForm, value: string) {
    this.form.update(current => ({...current, [field]: value}));
  }

  chooseMethodology(value: string) {
    const previous = this.methodologyChoice();
    this.otherMethodology.set(value === OTHER_METHODOLOGY);
    if (value !== previous) this.update('candidateMethodology', value === OTHER_METHODOLOGY ? '' : value);
  }

  private acceptIntake(intake: TechnicalIntake) {
    const values = formValues(intake.data);
    values.candidateMethodology = intake.triageCandidate ?? values.candidateMethodology;
    this.intake.set(intake);
    this.form.set(values);
    this.otherMethodology.set(false);
  }

  async save(event: Event) {
    event.preventDefault();
    const current = this.intake();
    if (this.saving() || this.loading() || !current || !this.can('SAVE_INTAKE')) return;
    const draft = this.form();
    const input: IntakeData = {
      activity: draft.activity.trim() || null,
      country: draft.country.trim().toUpperCase() || null,
      state: draft.state.trim() || null,
      locality: draft.locality.trim() || null,
      candidateMethodology: draft.candidateMethodology.trim() || null,
      referenceActDate: draft.referenceActDate || null,
      notes: draft.notes.trim() || null,
    };
    if (Object.values(input).every(value => value === null)) {
      this.error.set('Registre pelo menos uma informação ou observação para salvar.');
      return;
    }
    const expected = current.version;
    const payload = JSON.stringify({project: this.projectId, expected, ...input});
    if (!this.saveKey || this.savePayload !== payload) {
      this.saveKey = crypto.randomUUID();
      this.savePayload = payload;
    }
    this.saving.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const result = await firstValueFrom(this.http.put<TechnicalIntake>(this.technical + '/intake', input, {
        headers: {'If-Match': '"' + expected + '"', 'Idempotency-Key': this.saveKey},
      }));
      this.acceptIntake(result);
      this.saveKey = '';
      this.savePayload = '';
      this.notice.set('Informações preliminares salvas (revisão ' + result.version + ').');
    } catch (cause: unknown) {
      this.error.set(this.errorMessage(cause, 'Não foi possível salvar as informações técnicas. Verifique os campos e tente novamente.'));
      if (cause instanceof HttpErrorResponse && cause.status >= 400 && cause.status < 500) {
        this.saveKey = '';
      }
    } finally {
      this.saving.set(false);
    }
  }

  async prepare() {
    const intake = this.intake();
    if (this.saving() || !intake || !this.canPrepare()) return;
    if (!this.dossierKey || this.dossierVersion !== intake.version) {
      this.dossierKey = crypto.randomUUID();
      this.dossierVersion = intake.version;
    }
    this.saving.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const prepared = await firstValueFrom(this.http.post<PreliminaryDossier>(
        this.technical + '/preliminary-dossier', {}, {
          headers: {'Idempotency-Key': this.dossierKey, 'If-Match': '"' + intake.version + '"'},
        }));
      this.dossier.set(prepared);
      this.dossierKey = '';
      this.dossierVersion = -1;
      this.notice.set('Prévia de dossiê criada. Ela não está pronta para auditoria ou registro.');
    } catch (cause: unknown) {
      this.error.set(this.errorMessage(cause, 'Falha ao preparar a prévia do dossiê.'));
      if (cause instanceof HttpErrorResponse && cause.status >= 400 && cause.status < 500) {
        this.dossierKey = '';
        this.dossierVersion = -1;
      }
    } finally {
      this.saving.set(false);
    }
  }

  private errorMessage(cause: unknown, fallback: string) {
    if (cause instanceof HttpErrorResponse && [401, 404].includes(cause.status) && this.capabilities()?.owner === false) {
      this.project.set(null);
      this.capabilities.set(null);
      this.intake.set(null);
      this.dossier.set(null);
      this.form.set(formValues(null));
      return 'Seu acesso a este projeto está indisponível.';
    }
    if (cause instanceof HttpErrorResponse && cause.status === 412) {
      return 'A preparação mudou em outra aba. Revise suas anotações antes de recarregar a página.';
    }
    return cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string'
      ? cause.error.message : fallback;
  }
}
