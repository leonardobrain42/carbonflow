import {afterNextRender, Component, computed, ElementRef, inject, Injector, input, OnInit, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

export interface SessionProject {
  project: string;
  organization: string;
  environment: string;
  roles: string[];
}

interface Artifact {
  id: string;
  version: number;
  dossierId: string | null;
  variant: 'PUBLICA' | 'RESERVADA';
  purpose: string;
  title: string;
  mediaType: string;
  author: string | null;
  approvedBy: string | null;
  state: 'EM_REVISAO' | 'APROVADO';
}

interface Preview {
  artifact: Artifact;
  text: string;
}

interface Grant {
  id: string;
  version: number;
  artifact: {id: string; version: number};
  purpose: string;
  recipient: string;
  expiresAt: string;
  revokedAt: string | null;
  receipt: {issuer: string; capturedBy: string} | null;
}

interface AccessEvent {
  id: string;
  actor: string | null;
  action: string;
  outcome: string;
  target: string | null;
  occurredAt: string;
}

@Component({
  selector: 'carbonflow-access',
  templateUrl: './access-panel.html',
})
export class AccessPanel implements OnInit {
  readonly project = input.required<SessionProject>();
  readonly actor = input.required<string>();

  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  private readonly notice = viewChild<ElementRef<HTMLElement>>('notice');
  private readonly previewTitle = viewChild<ElementRef<HTMLElement>>('previewTitle');
  private readonly retryKeys = new Map<string, string>();

  readonly artifacts = signal<Artifact[]>([]);
  readonly showHistory = signal(false);
  readonly hasHistory = computed(() => {
    const items = this.artifacts();
    const newest = items[0]?.dossierId;
    return !!newest && items.some(item => item.dossierId !== newest);
  });
  readonly visibleArtifacts = computed(() => {
    const items = this.artifacts();
    const newest = items[0]?.dossierId;
    return this.showHistory() || !newest ? items : items.filter(item => item.dossierId === newest);
  });
  readonly grants = signal<Grant[]>([]);
  readonly trail = signal<AccessEvent[] | null>(null);
  readonly preview = signal<Preview | null>(null);
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly problem = signal('');
  readonly canPrepare = computed(() => this.project().roles.includes('PREP'));
  readonly canReview = computed(() => this.project().roles.includes('REV'));
  readonly isVvb = computed(() => this.project().roles.includes('VVB'));
  readonly canGrant = computed(() => this.project().roles.includes('PP'));
  readonly enabled = computed(() => this.project().roles.some(role => ['PREP', 'REV', 'PP', 'VVB'].includes(role)));

  readonly recipient = signal('SIM-VVB');
  readonly expiry = signal(this.defaultExpiry());

  private term = '';

  ngOnInit() {
    if (this.enabled()) void this.search('');
  }

  async search(term: string) {
    this.term = term;
    this.preview.set(null);
    await this.perform(async () => {
      await this.refresh();
      this.showHistory.set(!!term.trim());
    });
  }

  async prepare() {
    await this.perform(async () => {
      await this.post('/dossiers', {sample: 'E8-002@1'});
      this.term = '';
      await this.refresh();
      this.preview.set(null);
      this.showHistory.set(false);
    }, 'Amostra sintética encaminhada para revisão.');
  }

  async approve(item: Artifact) {
    await this.perform(async () => {
      await this.post('/dossiers/' + item.dossierId + '/actions', {
        transitionId: 'TX-E3-08', details: {artifact: this.reference(item)},
      }, 1);
      await this.refresh();
    }, 'Versão aprovada e congelada.');
  }

  async openPreview(item: Artifact) {
    this.preview.set(null);
    await this.perform(async () => {
      const preview = await firstValueFrom(this.http.get<Preview>(this.artifactUrl(item) + '/preview'));
      this.preview.set(preview);
      afterNextRender(() => this.previewTitle()?.nativeElement.focus(), {injector: this.injector});
    });
  }

  toggleHistory() {
    this.preview.set(null);
    this.showHistory.update(visible => !visible);
  }

  closePreview(trigger: HTMLButtonElement) {
    this.preview.set(null);
    trigger.focus();
  }

  async share(item: Artifact, localExpiry: string) {
    await this.perform(async () => {
      const expiresAt = new Date(localExpiry);
      if (Number.isNaN(expiresAt.valueOf())) throw new Error('Informe o fim da concessão.');
      await this.post('/grants', {
        artifact: this.reference(item), purpose: item.purpose, recipient: this.recipient(), expiresAt: expiresAt.toISOString(),
      });
      await this.refresh();
    }, 'Acesso concedido à versão selecionada.');
  }

  async actOnGrant(grant: Grant, action: 'revoke' | 'receipts') {
    await this.perform(async () => {
      await this.post('/grants/' + grant.id + '/' + action, null, grant.version);
      await this.refresh();
    }, action === 'revoke' ? 'Acesso revogado.' : 'Recibo sintético registrado.');
  }

  async showTrail() {
    await this.perform(async () => {
      this.trail.set(await firstValueFrom(this.http.get<AccessEvent[]>(this.base() + '/access-events')));
    });
  }

  artifactUrl(item: Pick<Artifact, 'id' | 'version'>) {
    return this.base() + '/artifacts/' + item.id + '/versions/' + item.version;
  }

  date(instant: string) {
    return new Intl.DateTimeFormat('pt-BR', {dateStyle: 'short', timeStyle: 'short'}).format(new Date(instant));
  }

  grantState(grant: Grant) {
    if (grant.revokedAt) return 'Revogada';
    return Date.parse(grant.expiresAt) <= Date.now() ? 'Expirada' : 'Vigente';
  }

  private base() {
    return '/v1/projects/' + encodeURIComponent(this.project().project);
  }

  private reference(item: Artifact) {
    return {id: item.id, version: item.version};
  }

  private defaultExpiry() {
    const tomorrow = new Date(Date.now() + 24 * 60 * 60 * 1000);
    return new Date(tomorrow.getTime() - tomorrow.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  }

  private async refresh() {
    const artifacts = await firstValueFrom(this.http.get<Artifact[]>(this.base() + '/artifacts', {params: {q: this.term}}));
    this.artifacts.set(artifacts);
    if (this.canGrant()) {
      this.grants.set(await firstValueFrom(this.http.get<Grant[]>(this.base() + '/grants')));
    }
  }

  private async post(path: string, body: unknown, version?: number) {
    const request = JSON.stringify([path, body, version]);
    const key = this.retryKeys.get(request) ?? crypto.randomUUID();
    this.retryKeys.set(request, key);
    const headers: Record<string, string> = {'Idempotency-Key': key};
    if (version !== undefined) headers['If-Match'] = '"' + version + '"';
    try {
      await firstValueFrom(this.http.post(this.base() + path, body, {headers}));
      this.retryKeys.delete(request);
    } catch (error: unknown) {
      if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) this.retryKeys.delete(request);
      throw error;
    }
  }

  private async perform(work: () => Promise<void>, success = '') {
    if (this.busy()) return;
    this.busy.set(true);
    this.problem.set('');
    this.feedback.set('');
    try {
      await work();
      this.feedback.set(success);
    } catch (error: unknown) {
      const message = error instanceof HttpErrorResponse ? error.error?.message : undefined;
      this.problem.set(message ?? (error instanceof Error && !(error instanceof HttpErrorResponse)
        ? error.message : 'Falha ao concluir a operação.'));
    } finally {
      this.busy.set(false);
      if (this.problem() || success) {
        afterNextRender(() => this.notice()?.nativeElement.focus(), {injector: this.injector});
      }
    }
  }
}
