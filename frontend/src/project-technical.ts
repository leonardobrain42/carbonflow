import {TechnicalGlossaryComponent} from './technical-glossary';
import {PdReviewsComponent} from './pd-reviews';
import {PdSectionsComponent} from './pd-sections';
import {ProjectGuidesComponent} from './project-guides';
import {OpenApplicabilityComponent} from './open-applicability';
import {WorkspaceNavigationComponent} from './workspace-navigation';
import {afterNextRender, Component, computed, ElementRef, inject, Injector, signal, viewChild} from '@angular/core';
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

const SECTIONS = [
  {id: 'entender', title: 'Entender'},
  {id: 'preparar', title: 'Preparar informações'},
  {id: 'analisar', title: 'Analisar'},
  {id: 'guias', title: 'Guias técnicos'},
  {id: 'redigir', title: 'Redigir'},
  {id: 'revisar', title: 'Revisar'},
] as const;
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
  imports: [RouterLink, WorkspaceNavigationComponent, ProjectGuidesComponent, OpenApplicabilityComponent, PdSectionsComponent, PdReviewsComponent, TechnicalGlossaryComponent],
  templateUrl: './project-technical.html',
})
export class ProjectTechnicalComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly guideEditor = viewChild(ProjectGuidesComponent);
  private readonly pdEditor = viewChild(PdSectionsComponent);
  private readonly pdReview = viewChild(PdReviewsComponent);
  readonly canLeaveGuides = () => (this.guideEditor()?.canLeave() ?? true) && (this.pdEditor()?.canLeave() ?? true) && (this.pdReview()?.canLeave() ?? true);
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('sectionHeading');
  private readonly query = toSignal(this.route.queryParamMap);
  readonly selectedGuide = computed(() => this.query()?.get('guia') ?? null);
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
  readonly owner = computed(() => this.capabilities()?.owner !== false && !!this.capabilities());
  readonly sections = computed(() => SECTIONS.filter(item => {
    if (item.id === 'guias') return this.capabilities()?.actions?.includes('VIEW_GUIDES') === true;
    if (item.id === 'redigir' || item.id === 'revisar') return this.can('VIEW_DOSSIER');
    return this.can('VIEW_INTAKE');
  }));
  readonly section = computed(() => this.sections().find(item => item.id === this.query()?.get('secao')) ?? SECTIONS[0]);
  readonly dirty = computed(() => JSON.stringify(formValues(this.intake()?.data ?? null)) !== JSON.stringify(this.form()));
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
      if (intake) this.acceptIntake(intake);
      this.dossier.set(dossier);
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

  updateGuide(guide: {id:string; version:number; answers:Record<string,string>}) {
    if (guide.id !== 'applicability') return;
    const {boundary,incidence,rights} = guide.answers;
    this.intake.update(state => state && {...state,applicability:{revision:guide.version,
      boundary:boundary ?? null,incidence:incidence ?? null,rights:rights ?? null}});
  }

  async open(section: Section, guide: string | null = null) {
    if (this.section().id === section || this.saving() || !this.canLeaveGuides()) return;
    this.notice.set('');
    if (await this.router.navigate([], {relativeTo: this.route, queryParams: {secao: section, guia: guide}, queryParamsHandling: 'merge'})) {
      afterNextRender(() => this.heading()?.nativeElement.focus(), {injector: this.injector});
    }
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
