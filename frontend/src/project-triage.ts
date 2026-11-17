import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

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

interface TriageView {
  projectId: string;
  environment: string;
  version: number;
  currentStep: number;
  analysisState: string;
  exampleKind: string | null;
  answers: Record<string, Record<string, string | number | null> | string>;
  stages: TriageStage[];
  pending: string[];
  nextAction: string;
}

type Values = Record<string, string | number | null>;
type AnswerSet = Record<number, Values>;

@Component({
  selector: 'carbonflow-project-triage',
  imports: [RouterLink],
  templateUrl: './project-triage.html',
})
export class ProjectTriageComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
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
  private pendingKey = '';
  private pendingPayload = '';

  constructor() { void this.load(); }

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
    this.draft.update(previous => ({
      ...previous, [step]: {...(previous[step] ?? {}), [key]: value},
    }));
    this.dirty.set(true);
  }

  async submit(event: Event) {
    event.preventDefault();
    const current = this.view();
    if (!current || current.currentStep > 4) return;
    await this.save(current.currentStep, Math.min(current.currentStep + 1, 5));
  }

  async saveHere() {
    const current = this.view();
    if (!current || current.currentStep > 4) return;
    await this.save(current.currentStep, current.currentStep);
  }

  async navigate(destination: number) {
    const current = this.view();
    if (!current || this.busy() || destination === current.currentStep || destination < 1 || destination > 5) return;
    if (current.currentStep <= 4) {
      await this.save(current.currentStep, destination);
    } else {
      await this.commit('PATCH', this.endpoint + '/cursor', {nextStep: destination}, current.version);
    }
  }

  private async save(step: number, nextStep: number) {
    const current = this.view();
    if (!current || this.busy()) return;
    const answers = {...(this.draft()[step] ?? {})};
    await this.commit('PUT', this.endpoint + '/steps/' + step, {answers, nextStep}, current.version);
  }

  async applyExample() {
    const current = this.view();
    if (!current || this.busy()) return;
    if (this.dirty() && !confirm('Substituir as respostas da triagem pelo exemplo sintético de 25 BEVs?')) return;
    await this.commit('POST', this.endpoint + '/examples/bev-df-2027', {}, current.version);
  }

  private async commit(method: 'PUT' | 'PATCH' | 'POST', url: string, body: object, version: number) {
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
      this.apply(result);
      this.notice.set(method === 'POST'
        ? 'Exemplo ilustrativo de 25 BEVs preenchido. Nenhuma redução foi calculada ou verificada.'
        : 'Respostas salvas na revisão ' + result.version + '.');
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

  readonly example = computed(() => !!this.view()?.exampleKind);

  private message(reason: unknown, fallback: string) {
    if (reason instanceof HttpErrorResponse && typeof reason.error?.message === 'string') {
      return reason.error.message;
    }
    return reason instanceof Error ? reason.message : fallback;
  }
}
