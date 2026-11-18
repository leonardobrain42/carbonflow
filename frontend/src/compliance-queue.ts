import {Component, DestroyRef, inject, signal} from '@angular/core';
import {DatePipe} from '@angular/common';
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

type Outcome = 'NEEDS_INFORMATION' | 'APPROVED' | 'REJECTED' | 'REVOKED';
interface Decision {version: number; decision: Outcome; reason: string; validUntil: string | null; recordedAt: string}

@Component({
  selector: 'carbonflow-compliance-queue',
  imports: [RouterLink, DatePipe],
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
  readonly history = signal<Decision[]>([]);
  readonly decision = signal<Outcome | null>(null);
  readonly decisionLabel = {
    NEEDS_INFORMATION: 'Complemento solicitado',
    APPROVED: 'Poder concedido',
    REJECTED: 'Solicitação indeferida',
    REVOKED: 'Poder revogado',
  } as const;
  private command = '';
  private previousPayload = '';
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
      this.decision.set(null);
      this.history.set(await this.fetch(this.http.get<Decision[]>(this.decisionsUrl(item))));
      if (this.history()[0]?.decision !== 'REVOKED') {
        this.documents.set(await this.fetch(this.http.get<Document[]>(this.documentUrl(item))));
      }
    });
  }

  documentUrl(item: Case, document?: Document) {
    const root = '/v1/compliance/projects/' + encodeURIComponent(item.projectId)
      + '/requests/' + encodeURIComponent(item.requestId) + '/documents';
    return document ? root + '/' + encodeURIComponent(document.id) : root;
  }

  choose(decision: Outcome) {
    this.decision.set(decision);
    this.command = '';
    this.previousPayload = '';
  }

  saveDecision(event: Event, reason: string, proof: string, expiry: string) {
    event.preventDefault();
    const item = this.selected();
    const decision = this.decision();
    if (!item || !decision) return;
    const approval = decision === 'APPROVED';
    const date = approval && expiry ? new Date(expiry) : null;
    if (approval && (!proof || !date || Number.isNaN(date.getTime()))) {
      this.error.set('Selecione o comprovante e informe até quando o poder terá vigência.');
      return;
    }
    const payload = {decision, reason, documentId:approval ? proof : null, validUntil:date?.toISOString() ?? null};
    const version = this.history()[0]?.version ?? 0;
    const body = JSON.stringify(payload);
    if (this.previousPayload !== body || !this.command) {
      this.previousPayload = body;
      this.command = crypto.randomUUID();
    }
    const headers = {'If-Match':'"' + version + '"', 'Idempotency-Key':this.command};
    void this.run(async () => {
      const history = await this.fetch(this.http.post<Decision[]>(this.decisionsUrl(item), payload, {headers}));
      this.history.set(history);
      if (history[0]?.decision === 'REVOKED') this.documents.set([]);
      this.decision.set(null);
    });
  }

  private decisionsUrl(item: Case) {
    return '/v1/compliance/projects/' + encodeURIComponent(item.projectId)
      + '/requests/' + encodeURIComponent(item.requestId) + '/decisions';
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
    this.history.set([]);
    this.decision.set(null);
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
        : code === 'VERSAO_OBSOLETA' ? 'A solicitação mudou. Volte à fila e abra o protocolo novamente.'
        : code === 'DECISAO_INVALIDA' ? 'Confira o parecer, o comprovante e a vigência.'
        : 'Não foi possível concluir a operação de conformidade.');
    } finally {
      this.busy.set(false);
    }
  }
}
