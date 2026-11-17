import {afterNextRender, Component, computed, ElementRef, inject, Injector, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {toSignal} from '@angular/core/rxjs-interop';
import {firstValueFrom} from 'rxjs';
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
}

const SECTIONS = [
  {id: 'entender', title: 'Entender'},
  {id: 'preparar', title: 'Preparar informações'},
  {id: 'analisar', title: 'Analisar'},
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
  imports: [RouterLink],
  templateUrl: './project-technical.html',
})
export class ProjectTechnicalComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('sectionHeading');
  private readonly query = toSignal(this.route.queryParamMap);
  readonly projectId = this.route.snapshot.paramMap.get('projectId') ?? '';
  private readonly root = '/v1/projects/' + encodeURIComponent(this.projectId);
  private readonly technical = this.root + '/technical';

  readonly project = signal<ProjectMetadata | null>(null);
  readonly capabilities = signal<Capabilities | null>(null);
  readonly intake = signal<TechnicalIntake | null>(null);
  readonly dossier = signal<PreliminaryDossier | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly menuOpen = signal(false);
  readonly form = signal<IntakeForm>(formValues(null));
  readonly sections = SECTIONS;
  readonly section = computed(() => SECTIONS.find(item => item.id === this.query()?.get('secao')) ?? SECTIONS[0]);
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

  readonly canPrepare = () => {
    const current = this.intake();
    const dossier = this.dossier();
    return !!current && !this.dirty() && current.version > 0 && (!dossier || dossier.intakeVersion !== current.version);
  };

  private async load() {
    this.loading.set(true);
    this.error.set('');
    try {
      const project = await firstValueFrom(this.http.get<ProjectMetadata>(this.root + '/metadata'));
      if (project.projectId !== this.projectId || project.environment !== 'SANDBOX') {
        throw new Error('Contexto incorreto');
      }
      this.project.set(project);
      const [capabilities, intake, dossier] = await Promise.all([
        firstValueFrom(this.http.get<Capabilities>(this.technical + '/capabilities')),
        firstValueFrom(this.http.get<TechnicalIntake>(this.technical + '/intake')),
        firstValueFrom(this.http.get<PreliminaryDossier | null>(this.technical + '/preliminary-dossier')),
      ]);
      this.capabilities.set(capabilities);
      this.acceptIntake(intake);
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

  async open(section: Section) {
    if (this.section().id === section || this.saving()) return;
    this.notice.set('');
    if (await this.router.navigate([], {relativeTo: this.route, queryParams: {secao: section}, queryParamsHandling: 'merge'})) {
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
    if (this.saving() || this.loading() || !current) return;
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
    if (cause instanceof HttpErrorResponse && cause.status === 412) {
      return 'A preparação mudou em outra aba. Revise suas anotações antes de recarregar a página.';
    }
    return cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string'
      ? cause.error.message : fallback;
  }
}
