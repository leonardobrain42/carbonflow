import {Component, DestroyRef, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom, Observable} from 'rxjs';

type Power = 'REPRESENT_PROJECT' | 'SEND_INVITATIONS';
interface Case {
  projectId: string;
  projectTitle: string;
  organizationId: string;
  organizationName: string;
  requestId: string;
  power: Power;
  basis: string;
  reference: string;
}

interface Queue {cases: Case[]; nextOffset: number | null}
interface StaffSession {verified: boolean; expiresAt: string | null}
interface Document {id: string; bytesCount: number}

@Component({
  selector: 'carbonflow-compliance-queue',
  imports: [RouterLink],
  templateUrl: './compliance-queue.html',
})
export class ComplianceQueueComponent {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private timer?: ReturnType<typeof setTimeout>;
  readonly session = signal<StaffSession | null>(null);
  readonly queue = signal<Queue | null>(null);
  readonly selected = signal<Case | null>(null);
  readonly documents = signal<Document[]>([]);
  readonly offset = signal(0);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly power = {
    REPRESENT_PROJECT: 'Representação no projeto',
    SEND_INVITATIONS: 'Envio de convites',
  } as const;

  constructor() {
    this.destroy.onDestroy(() => clearTimeout(this.timer));
    void this.run(async () => this.applySession(await this.fetch(this.http.get<StaffSession>('/v1/compliance/session'))));
  }

  verify(event: Event, code: HTMLInputElement) {
    event.preventDefault();
    const value = code.value;
    code.value = '';
    void this.run(async () => this.applySession(await this.fetch(this.http.post<StaffSession>('/v1/compliance/session', {code:value}))));
  }

  showQueue(offset: number) {
    void this.run(() => this.loadQueue(offset));
  }

  open(item: Case) {
    void this.run(async () => {
      this.selected.set(item);
      this.documents.set([]);
      this.documents.set(await this.fetch(this.http.get<Document[]>(this.documentUrl(item))));
    });
  }

  documentUrl(item: Case, document?: Document) {
    const root = '/v1/compliance/projects/' + encodeURIComponent(item.projectId)
      + '/requests/' + encodeURIComponent(item.requestId) + '/documents';
    return document ? root + '/' + encodeURIComponent(document.id) : root;
  }

  private async applySession(session: StaffSession) {
    this.session.set(session);
    if (session.verified && session.expiresAt) {
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.clearSensitive(), Math.max(0, Date.parse(session.expiresAt) - Date.now()));
      await this.loadQueue(0);
    }
  }

  private async loadQueue(offset: number) {
    this.selected.set(null);
    this.documents.set([]);
    const result = await this.fetch(this.http.get<Queue>('/v1/compliance/queue', {params:{offset}}));
    if (!this.session()?.verified) return;
    this.queue.set(result);
    this.offset.set(offset);
  }

  private clearSensitive() {
    this.session.set({verified:false, expiresAt:null});
    this.queue.set(null);
    this.selected.set(null);
    this.documents.set([]);
  }

  private fetch<T>(operation: Observable<T>): Promise<T> {
    return firstValueFrom(operation);
  }

  private async run(action: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await action();
    } catch (cause) {
      const code = cause instanceof HttpErrorResponse ? cause.error?.code : null;
      if (code === 'MFA_NECESSARIO' || (cause instanceof HttpErrorResponse && cause.status === 401)) this.clearSensitive();
      this.error.set(code === 'MFA_CODIGO_INVALIDO' ? 'Código inválido ou já utilizado.'
        : code === 'MFA_LIMITE' ? 'Aguarde alguns minutos antes de tentar novamente.'
        : 'Não foi possível consultar os dados de conformidade.');
    } finally {
      this.busy.set(false);
    }
  }
}
