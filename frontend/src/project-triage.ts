import {WorkspaceNavigationComponent} from './workspace-navigation';
import {JourneyCheckpointService} from './journey-checkpoint';
import {afterNextRender, Component, computed, effect, ElementRef, inject, Injector, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';
import {JourneyOption, JourneyPresentation, JourneyProfile, JourneyQuestion, methodologyOption, OTHER_METHODOLOGY} from './project-journey';

interface ProjectMetadata {
  projectId: string;
  organizationId: string;
  environment: string;
  state: string;
  title: string;
}

interface TriageStage {
  number: number;
  title: string;
  status: string;
  missingFields: string[];
}

interface Finding {
  code: string;
  kind?: 'ANSWER' | 'PILOT_SCOPE' | 'TECHNICAL';
  title: string | null;
  detail: string;
  step: number;
  fields: string[];
}

interface Diagnosis {
  ruleVersion: string;
  classification: 'SUPPORTED_MVP_CONFIGURATION' | 'UNSUPPORTED_MVP' | 'UNKNOWN_REVIEW_REQUIRED';
  reason: string;
  findings: Finding[];
}

/** D16: an older answer that the triage no longer asks; read-only, owned by a technical screen. */
export interface RetiredAnswer {
  step: number;
  key: string;
  label: string;
  value: string | number | null;
  valueLabel: string;
  section: string;
  sectionTitle: string;
}

/** D16: what the technical preparation asks next, in the screens that own it. */
export interface LaterItem {
  section: string;
  title: string;
  detail: string;
}

const DEFAULT_PRESENTATION: JourneyPresentation = {
  activityPlaceholder: 'Descreva a atividade que pretende realizar',
  baselinePlaceholder: 'Descreva como a atividade ocorreria sem o projeto',
  next: [],
};

interface TriageView {
  projectId: string;
  diagnosis?: Diagnosis;
  environment: string;
  version: number;
  currentStep: number;
  analysisState: string;
  exampleKind: string | null;
  answers: Record<string, Record<string, string | number | null> | string>;
  stages: TriageStage[];
  pending: string[];
  nextAction: string;
  journey: JourneyProfile;
  methodologies: JourneyOption[];
  /** Versioned catalog of packages and the activity vocabulary (A03/#90); the screen lists nothing by itself. */
  catalog?: {version: number; digest: string; activityTypes: JourneyOption[]};
  /** Derived by the server from the candidate: PACKAGE, REGISTERED_NO_PACKAGE, UNREGISTERED or NONE. */
  candidateStatus?: {state: string; methodology: string | null; edition: string | null; registryVersion: number | null};
  guidance: {state: string; outsideMethodology: string[]; outsidePilot: string[]};
  retiredAnswers?: RetiredAnswer[];
  /** D18 slice 3: the registry/package versions the project was prepared against (null until a candidate exists). */
  methodologyPin?: MethodologyPin | null;
  /** D18 slice 3: derived by the server per purpose from code, data and licence; the screen decides nothing. */
  capabilities?: CapabilityPurpose[];
}

interface MethodologyPin {
  version: number; triageVersion: number; methodology: string | null; edition: string | null;
  registryVersion: number | null; packageId: string | null; packageVersion: number | null;
  pinnedAt: string; newerRegistryVersion: boolean; packageChanged: boolean;
}
interface CapabilityAxis {state: 'MET' | 'NOT_MET' | 'NOT_REQUIRED'}
interface CapabilityPurpose {
  purpose: string; label: string; code: CapabilityAxis; data: CapabilityAxis; licence: CapabilityAxis;
  effective: 'ENABLED' | 'NOT_ENABLED'; reason: string;
}

const AXIS_LABEL: Record<string, string> = {MET: 'Atendido', NOT_MET: 'Não atendido', NOT_REQUIRED: 'Não se aplica'};

type Values = Record<string, string | number | null>;
type AnswerSet = Record<number, Values>;

@Component({
  selector: 'carbonflow-project-triage',
  imports: [RouterLink, WorkspaceNavigationComponent],
  templateUrl: './project-triage.html',
})
export class ProjectTriageComponent {
  private readonly http = inject(HttpClient);
  private readonly checkpoints = inject(JourneyCheckpointService);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('stepHeading');
  private readonly form = viewChild<ElementRef<HTMLFormElement>>('stepForm');
  readonly projectId = this.route.snapshot.paramMap.get('projectId') ?? '';
  private readonly base = '/v1/projects/' + encodeURIComponent(this.projectId);
  private readonly endpoint = this.base + '/triage';

  readonly project = signal<ProjectMetadata | null>(null);
  readonly view = signal<TriageView | null>(null);
  readonly draft = signal<AnswerSet>({});
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly dirty = signal(false);
  readonly step = computed(() => this.view()?.currentStep ?? 1);
  readonly editingFinding = signal(false);
  readonly findings = computed(() => {
    const items = this.view()?.diagnosis?.findings ?? [];
    return {
      answers: items.filter(item => item.step > 0 && item.kind !== 'PILOT_SCOPE'),
      scope: items.filter(item => item.kind === 'PILOT_SCOPE' || (item.step === 0 && !item.kind)),
      technical: items.filter(item => item.kind === 'TECHNICAL'),
    };
  });
  readonly otherMethodology = signal(false);
  readonly methodologyChoice = computed(() => {
    const current = this.view();
    return this.otherMethodology() ? OTHER_METHODOLOGY
      : methodologyOption(this.field(1, 'methodologyCandidate'), current?.methodologies ?? [], current?.journey);
  });
  readonly registeredWithoutPackage = computed(() => this.view()?.candidateStatus?.state === 'REGISTERED_NO_PACKAGE');
  readonly pin = computed(() => {
    const pin = this.view()?.methodologyPin;
    return pin && pin.methodology ? pin : null;
  });
  readonly capabilities = computed(() => this.view()?.capabilities ?? []);
  axisLabel(axis: CapabilityAxis): string { return AXIS_LABEL[axis.state] ?? axis.state; }
  /** Closed gates are not a missing feature: calculation, admission and external acts have their own decisions. */
  isGate(item: CapabilityPurpose): boolean {
    return item.purpose === 'CALCULATION_ADMISSION' || item.purpose === 'EXTERNAL_ACTS';
  }
  readonly activityTypes = computed(() => this.view()?.catalog?.activityTypes ?? []);
  /** Package schema for the activity step, read from the server (A03). */
  readonly activityQuestions = computed(() => this.view()?.journey.activityQuestions ?? []);
  readonly presentation = computed(() => this.view()?.journey.presentation ?? DEFAULT_PRESENTATION);
  readonly otherOption = OTHER_METHODOLOGY;
  readonly later = computed<LaterItem[]>(() => this.presentation().next);

  retired(step?: number): RetiredAnswer[] {
    const items = this.view()?.retiredAnswers ?? [];
    return step == null ? items : items.filter(item => item.step === step);
  }
  private pendingKey = '';
  private pendingPayload = '';

  constructor() {
    void this.load();
    effect(()=>{
      if(!this.loading()||!this.project()||!this.view())return;
      // The triage owns its current step on the server, not a guessed URL fragment.
      this.checkpoints.remember(this.projectId,{
        area:'triage',screen:null,guide:null,step:null
      });
    });
  }

  canLeave(){
    return !this.busy()&&(!this.dirty()||
      confirm('Descartar as respostas de triagem ainda não salvas?'));
  }

  async load() {
    if (this.busy()) return;
    this.loading.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const [project, view] = await Promise.all([
        firstValueFrom(this.http.get<ProjectMetadata>(this.base + '/metadata')),
        firstValueFrom(this.http.get<TriageView>(this.endpoint)),
      ]);
      if (project.projectId !== this.projectId || project.environment !== 'SANDBOX'
          || view.projectId !== this.projectId || view.environment !== 'SANDBOX') {
        throw new Error('O contexto solicitado não corresponde ao projeto autorizado.');
      }
      this.project.set(project);
      this.apply(view);
    } catch (reason: unknown) {
      this.project.set(null);
      this.view.set(null);
      this.error.set(this.message(reason, 'Não foi possível consultar a triagem. Verifique sua conta.'));
    } finally {
      this.loading.set(false);
    }
  }

  private apply(value: TriageView) {
    const answers: AnswerSet = {};
    for (let step = 1; step <= 4; step++) {
      const item = value.answers[String(step)];
      answers[step] = item && typeof item === 'object' ? {...item} : {};
    }
    this.draft.set(answers);
    this.view.set(value);
    this.dirty.set(false);
    this.editingFinding.set(false);
    this.otherMethodology.set(false);
    this.pendingKey = '';
    this.pendingPayload = '';
  }

  field(step: number, key: string): string {
    const value = this.draft()[step]?.[key];
    return value == null ? '' : String(value);
  }

  input(step: number, key: string, event: Event) {
    const node = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
    let value: string | number | null = node.value.trim();
    if (value === '') value = null;
    if (key === 'vehicleCount' || key === 'chargerCount') {
      value = value == null ? null : Number(value);
    }
    this.setField(step, key, value);
  }

  private setField(step: number, key: string, value: string | number | null) {
    this.draft.update(previous => ({
      ...previous, [step]: {...(previous[step] ?? {}), [key]: value},
    }));
    this.dirty.set(true);
  }

  chooseMethodology(value: string) {
    const previous = this.methodologyChoice();
    this.otherMethodology.set(value === OTHER_METHODOLOGY);
    if (value !== previous) this.setField(1, 'methodologyCandidate', value === OTHER_METHODOLOGY || !value ? null : value);
  }

  questions(step: number): string[] {
    const details = this.view()?.journey.questions ?? [];
    switch (step) {
      case 1: return ['objective', 'activityType', 'methodologyCandidate'];
      case 2: return [...this.activityQuestions().map(question => question.key),
        'activityDescription', 'baselinePractice', 'expectedBenefit'];
      case 3: return [...details.map(question => question.key),
        ...(this.field(3, 'notApplicableReason') ? ['notApplicableReason'] : [])];
      // D16: rights, evidence and investment are no longer asked here; older answers are kept by the server.
      default: return ['country', 'state', 'locality', 'referenceDate'];
    }
  }

  async submit(event: Event) {
    event.preventDefault();
    const current = this.view();
    if (!current || current.currentStep > 4) return;
    await this.save(current.currentStep, this.editingFinding() ? 5 : Math.min(current.currentStep + 1, 5));
  }

  async saveHere() {
    const current = this.view();
    if (!current || current.currentStep > 4) return;
    await this.save(current.currentStep, current.currentStep);
  }

  openFinding(item: Finding) {
    return this.navigate(item.step, item.fields[0]);
  }

  async navigate(destination: number, field?: string) {
    const current = this.view();
    if (!current || this.busy() || destination === current.currentStep || destination < 1 || destination > 5) return;
    if (current.currentStep <= 4 && this.dirty()) {
      await this.save(current.currentStep, destination);
    } else {
      await this.commit('PATCH', this.endpoint + '/cursor', {nextStep: destination}, current.version, field);
    }
  }

  private async save(step: number, nextStep: number) {
    const current = this.view();
    if (!current || this.busy()) return;
    const answers = Object.fromEntries(Object.entries(this.draft()[step] ?? {})
      .filter(([key]) => this.questions(step).includes(key)));
    await this.commit('PUT', this.endpoint + '/steps/' + step, {answers, nextStep}, current.version);
  }

  async applyExample() {
    const current = this.view();
    if (!current || this.busy()) return;
    if ((this.dirty() || Object.keys(current.answers).length > 0) && !confirm('Substituir as respostas da triagem pelo exemplo sintético de 25 BEVs?')) return;
    await this.commit('POST', this.endpoint + '/examples/bev-df-2027', {}, current.version);
  }

  private async commit(method: 'PUT' | 'PATCH' | 'POST', url: string, body: object, version: number, field?: string) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    const payload = JSON.stringify({method, url, body, version});
    if (!this.pendingKey || this.pendingPayload !== payload) {
      this.pendingKey = crypto.randomUUID();
      this.pendingPayload = payload;
    }
    try {
      const options = {
        headers: {'If-Match': '"' + version + '"', 'Idempotency-Key': this.pendingKey},
      };
      const result = await firstValueFrom(method === 'PUT'
        ? this.http.put<TriageView>(url, body, options)
        : method === 'PATCH' ? this.http.patch<TriageView>(url, body, options)
        : this.http.post<TriageView>(url, body, options));
      const changedStep = this.view()?.currentStep !== result.currentStep;
      const keepEditing = this.editingFinding() && !changedStep;
      this.apply(result);
      this.editingFinding.set(keepEditing || (changedStep && !!field));
      if (changedStep) {
        afterNextRender(() => {
          const control = field ? this.form()?.nativeElement.elements.namedItem(field) : null;
          if (control instanceof HTMLElement) {
            control.focus();
          } else {
            this.heading()?.nativeElement.focus();
          }
        }, {injector: this.injector});
      }
      this.notice.set(method === 'POST'
        ? 'Exemplo ilustrativo de 25 BEVs preenchido. Nenhuma redução foi calculada ou verificada.'
        : method === 'PUT' ? 'Respostas salvas na revisão ' + result.version + '.' : '');
    } catch (reason: unknown) {
      this.error.set(this.message(reason, 'Não foi possível salvar. Tente novamente.'));
      // Keep the same idempotency key for uncertain network/5xx outcomes.
      if (reason instanceof HttpErrorResponse && reason.status >= 400 && reason.status < 500) {
        this.pendingKey = '';
        this.pendingPayload = '';
        if (reason.status === 412) {
          this.error.set('O rascunho mudou em outra aba. Revise suas respostas antes de recarregar a versão atual.');
        }
      }
    } finally {
      this.busy.set(false);
    }
  }

  status(value: string) {
    const names: Record<string,string> = {
      NAO_INICIADO: 'Ainda não iniciado',
      PENDENTE: 'Informações pendentes',
      PREENCHIDO: 'Respostas registradas',
      NAO_APLICAVEL_JUSTIFICADO: 'Não aplicável (com justificativa)',
      RESUMO_SEM_PARECER: 'Resumo sem parecer',
    };
    return names[value] ?? value;
  }

  diagnosisLabel(value: Diagnosis['classification']) {
    return {
      SUPPORTED_MVP_CONFIGURATION: 'Configuração compatível com o recorte do piloto',
      UNSUPPORTED_MVP: 'Configuração fora do recorte implementado',
      UNKNOWN_REVIEW_REQUIRED: 'Requer mais informações ou revisão',
    }[value];
  }

  fieldLabel(key: string) {
    const labels: Record<string, string> = {
      objective: 'Objetivo', activityType: 'Tipo de atividade',
      installsChargers: 'Instalação de carregadores',
      investmentCommitted: 'Compromisso de investimento',
      carbonConsideredBeforeInvestment: 'Receita de carbono considerada antes do compromisso',
      methodologyCandidate: 'Metodologia candidata', activityDescription: 'Como funcionará',
      baselinePractice: 'Cenário atual', expectedBenefit: 'Benefício esperado',
      vehicleCount: 'Número de veículos', propulsion: 'Propulsão',
      chargerType: 'Tipo de recarga', chargerCount: 'Número de carregadores',
      electricitySource: 'Origem da eletricidade',
      notApplicableReason: 'Justificativa de não aplicabilidade',
      country: 'País', state: 'Estado ou região', locality: 'Localidade',
      referenceDate: 'Data de referência', evidenceStatus: 'Dados de origem',
      rightsStatus: 'Direitos e titularidade', rightsJustification: 'Justificativa sobre direitos',
    };
    return this.view()?.journey.questions.find(question => question.key === key)?.label
      ?? this.retired().find(item => item.key === key)?.label ?? labels[key]
      ?? this.activityQuestions().find(question => question.key === key)?.label ?? key;
  }

  missingLabels(fields: string[]) {
    return fields.map(field => this.fieldLabel(field)).join(', ');
  }

  legacyChoice(question: JourneyQuestion): string {
    const value = this.field(3, question.key);
    return value && !question.options.some(option => option.value === value) ? value : '';
  }

  valueLabel(value: string, key: string) {
    const values: Record<string, string> = {
      YES: 'Sim', NO: 'Não', DESCONHECIDO: 'Ainda não conhecido', DESCONHECIDA: 'Ainda não conhecida',
      DECLARADO: 'Declarado, ainda não comprovado',
      DISPONIVEL_DECLARADO: 'Disponibilidade declarada, sem conferência',
      EM_PREPARACAO: 'Em preparação',
      NAO_APLICAVEL_JUSTIFICADO: 'Não aplicável, com justificativa',
      MOBILIDADE_ELETRICA: 'Mobilidade elétrica',
      RESIDUOS: 'Resíduos', ENERGIA: 'Energia', AGRICULTURA: 'Agricultura',
    };
    const question = this.view()?.journey.questions.find(item => item.key === key);
    return question?.options.find(option => option.value === value)?.label ?? values[value] ?? value;
  }

  readonly example = computed(() => !!this.view()?.exampleKind);

  private message(reason: unknown, fallback: string) {
    if (reason instanceof HttpErrorResponse && typeof reason.error?.message === 'string') {
      return reason.error.field && reason.status === 400
        ? reason.error.message + ' Campo: ' + this.fieldLabel(reason.error.field) + '.'
        : reason.error.message;
    }
    return reason instanceof Error ? reason.message : fallback;
  }
}
