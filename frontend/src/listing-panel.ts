import {Component, computed, inject, input, OnInit, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {SessionProject} from './access-panel';

interface CodeRef {id: string; version: number}
interface Ref {id: string; revision: number}
interface Dossier extends Ref {state: string; command: {act: string; actDate: string}}
interface Channel {route: string; source: CodeRef; destination: string}
interface Options {channels: Channel[]; proofs: Record<string, string>; documentEdition: string; methodologyEdition: string; power: CodeRef}
interface Proof {officialId: string; target: string; evidence: CodeRef}
interface Capture {authority: string; proof: Proof; effectiveAt: string; capturedBy: string}
interface Attempt {
  id: string; version: number; kind: string; state: string; pack: {reference: Ref; actDate: string}; uncertain: boolean;
  target: string | null; lastAt: string | null; consultationId: string | null; publication: Capture | null;
  fees: {invoice: Capture; settlement: Capture | null} | null;
  check: {input: {dossier: Ref; channel: string; checkedAt: string; powerUntilExclusive: string | null}} | null;
  submission: {exportId: string; receipt: string; sha256: string; destination: string} | null;
}
interface Listing {attempt: Attempt; responseDue: string | null; rejectionReviewAt: string | null; lateResponse: boolean; channelStatus: string; nextStep: string}
interface Receipt {input: {exportId: string; dossier: Ref; destination: string; code: string; receivedAt: string}}
interface Consultation {id: string; version: number; state: string; target: string; opening: string; endExclusive: string}
interface CommentData {
  id: string; version: number; state: string; target: string; topic: string; window: string; body: string; responseDue: string | null;
  received: Capture; forwarded: Capture | null; vvb: string | null;
  responses: {version: number; body: string; late: boolean}[];
  evaluations: {result: string; reason: string; responseVersion: number; capture: Capture}[];
}
interface Comment {comment: CommentData; contact: string | null}
interface EventRow {id: string; action: string; actor: string; outcome: string; occurredAt: string}
interface Step {id: string; label: string; receipt?: boolean; reason?: boolean}

const steps: Record<string, Step> = {
  dev: {id: 'TX-E3-09', label: 'Vincular protocolo de desenvolvimento', receipt: true},
  val: {id: 'TX-E3-18', label: 'Vincular protocolo de validação', receipt: true},
  require: {id: 'TX-E3-10', label: 'Registrar exigência', reason: true},
  respond: {id: 'TX-E3-11', label: 'Vincular resposta à exigência', receipt: true, reason: true},
  inactive: {id: 'TX-E3-12', label: 'Registrar inativação', reason: true},
  reactivate: {id: 'TX-E3-13', label: 'Vincular solicitação de reativação', receipt: true, reason: true},
  publish: {id: 'TX-E3-14', label: 'Comprovar publicação'},
  suspend: {id: 'TX-E3-15', label: 'Registrar suspensão', reason: true},
  resume: {id: 'TX-E3-16', label: 'Registrar retomada', reason: true},
  reject: {id: 'TX-E3-17', label: 'Registrar rejeição', reason: true},
  invoice: {id: 'BOLETO', label: 'Registrar boleto'},
  settled: {id: 'QUITACAO', label: 'Registrar quitação'},
  uncertain: {id: 'ENVIO_INCERTO', label: 'Registrar retorno incerto', reason: true},
  retry: {id: 'CONFIRMAR_AUSENCIA', label: 'Comprovar conferência sem recibo', reason: true},
};

@Component({selector: 'carbonflow-listings', templateUrl: './listing-panel.html'})
export class ListingPanel implements OnInit {
  readonly project = input.required<SessionProject>();
  readonly canAct = computed(() => this.project().roles.includes('PP'));
  readonly isVvb = computed(() => this.project().roles.includes('VVB'));
  readonly visible = computed(() => this.project().roles.some(role => ['PP', 'PREP', 'REV', 'VVB'].includes(role)));
  readonly kinds = [{act: 'DESENVOLVIMENTO', label: 'Desenvolvimento'}, {act: 'LISTAGEM_VALIDACAO', label: 'Em validação'}];
  readonly options = signal<Options | null>(null);
  readonly listings = signal<Listing[]>([]);
  readonly dossiers = signal<Dossier[]>([]);
  readonly chosen = signal('');
  readonly current = computed(() => this.listings().find(item => item.attempt.id === this.chosen()) ?? null);
  readonly kindChoice = signal('DESENVOLVIMENTO');
  readonly channelChoice = signal('ARQUIVO');
  readonly actionChoice = signal('');
  readonly consultation = signal<Consultation | null>(null);
  readonly commentSteps = signal<Record<string,string>>({});
  readonly assigned = signal<Consultation[]>([]);
  readonly comments = signal<Comment[]>([]);
  readonly receipts = signal<Receipt[]>([]);
  readonly history = signal<EventRow[]>([]);
  readonly busy = signal(false);
  readonly error = signal(false);
  readonly feedback = signal('');

  private readonly http = inject(HttpClient);
  private readonly keys = new Map<string,string>();

  ngOnInit() { if (this.visible()) void this.refresh(); }

  refresh() { return this.perform(() => this.load()); }

  label(kind: string) { return this.kinds.find(item => item.act === kind)?.label ?? kind; }
  status(state: string) {
    return ({NOVO: 'Em preparo', PROTOCOLADO: 'Protocolada', PENDENTE: 'Com exigência', INATIVO: 'Inativa', PUBLICADO: 'Publicada',
      SUSPENSO: 'Suspensa', REJEITADO: 'Rejeitada', ABERTA: 'Aberta', ENCERRADA: 'Encerrada',
      CANAL_INDETERMINADO: 'Canal a confirmar', PODER_INDISPONIVEL: 'Poder pendente ou vencido',
      CONFERIDO_SIMULACAO: 'Canal conferido para simulação'} as Record<string, string>)[state] ?? state;
  }
  refName(ref: Ref) { return ref.id.slice(0,8) + ' · r' + ref.revision; }
  refValue(ref: Ref) { return ref.id + '@' + ref.revision; }
  packs(kind: string) {
    return this.dossiers().filter(item => item.state === 'CONGELADO' && item.command.act === kind
      && !this.dossiers().some(other => other.id === item.id && other.revision > item.revision));
  }
  canCheck(attempt: Attempt) { return ['NOVO','INATIVO','PENDENTE'].includes(attempt.state) && !attempt.uncertain; }
  validReceipts(attempt: Attempt) {
    const check = attempt.check?.input;
    const route = this.options()?.channels.find(item => item.route === check?.channel);
    if (!check || !route || check.channel === 'INDETERMINADO') return [];
    return this.receipts().filter(item => this.refValue(item.input.dossier) === this.refValue(check.dossier)
      && item.input.destination === route.destination && item.input.exportId !== attempt.submission?.exportId);
  }
  available(attempt: Attempt): Step[] {
    if (attempt.state === 'NOVO') return [attempt.kind === 'DESENVOLVIMENTO' ? steps['dev'] : steps['val'],
      attempt.uncertain ? steps['retry'] : steps['uncertain']];
    if (attempt.state === 'PROTOCOLADO') {
      const fee = !attempt.fees ? [steps['invoice']] : !attempt.fees.settlement ? [steps['settled']] : [];
      return [steps['publish'], ...fee, steps['require'], steps['suspend'], steps['reject']];
    }
    if (attempt.state === 'PENDENTE') return [steps['respond'], steps['inactive'], steps['suspend'], steps['reject'],
      attempt.uncertain ? steps['retry'] : steps['uncertain']];
    if (attempt.state === 'INATIVO') return [steps['invoice'], steps['settled'], steps['reactivate'], steps['reject'],
      attempt.uncertain ? steps['retry'] : steps['uncertain']];
    if (attempt.state === 'SUSPENSO') return [steps['resume'], steps['reject']];
    return [];
  }
  selectedStep(attempt: Attempt) {
    const choices = this.available(attempt);
    return choices.find(item => item.id === this.actionChoice()) ?? choices[0];
  }
  setCommentStep(id: string, step: string) { this.commentSteps.update(items => ({...items, [id]: step})); }
  commentStep(item: CommentData, value: string) {
    const explicit = this.commentSteps()[item.id];
    if (explicit && explicit === value) return explicit;
    if (this.isVvb()) return 'TX-E3-23';
    if (item.state === 'APRESENTADO') return 'TX-E3-21';
    if (['ENCAMINHADO','COM_ACHADO'].includes(item.state)) return 'TX-E3-22';
    return 'VINCULAR_VVB';
  }
  toField(value: string) {
    const milliseconds = Date.parse(value) - 3 * 3600000;
    return new Date(milliseconds).toISOString().slice(0,16);
  }
  displayTime(value: string) { return this.toField(value).replace('T', ' ') + ' BRT'; }
  publicUrl(consultation: Consultation) { return this.base() + '/consultations/' + consultation.id + '/public'; }

  select(id: string) {
    this.chosen.set(id);
    return this.perform(() => this.loadAttempt());
  }

  selectAssignment(id: string) {
    return this.perform(async () => {
      this.consultation.set(this.assigned().find(item => item.id === id) ?? null);
      await this.loadComments();
    });
  }

  create(event: Event, dossier: string, predecessor: string, admit: boolean) {
    event.preventDefault();
    const selected = this.dossiers().find(item => this.refValue(item) === dossier);
    if (!selected) return;
    return this.perform(async () => {
      const result = await this.post<Listing>('/listings', {kind: this.kindChoice(), dossier: this.ref(selected),
        predecessor: predecessor || null, admission: admit ? this.code('SIM-PROVA-ADMISSAO-NOVA-TENTATIVA') : null});
      this.chosen.set(result.attempt.id);
      await this.load();
      this.feedback.set('Tentativa preparada. Confira canal e poder.');
    });
  }

  check(event: Event, attempt: Attempt, dossier: string, checkedAt: string, until: string, granted: boolean) {
    event.preventDefault();
    const selected = this.dossiers().find(item => this.refValue(item) === dossier);
    const options = this.options();
    const channel = options?.channels.find(item => item.route === this.channelChoice());
    if (!selected || !options || !channel) return;
    return this.perform(async () => {
      await this.post('/listings/' + attempt.id + '/channel-checks', {dossier: this.ref(selected), channel: channel.route,
        documentEdition: options.documentEdition, methodologyEdition: options.methodologyEdition, checkedAt: this.time(checkedAt),
        powerUntilExclusive: granted ? this.time(until) : null, source: channel.source,
        power: granted ? options.power : null}, attempt.version);
      await this.load();
      this.feedback.set('Conferência registrada para a revisão escolhida.');
    });
  }

  evaluateDate(attempt: Attempt, at: string) {
    return this.perform(async () => {
      const result = await this.read<Listing>('/listings/' + attempt.id + '/readiness?effectiveAt=' + encodeURIComponent(this.time(at) ?? ''));
      this.listings.update(items => items.map(item => item.attempt.id === attempt.id ? result : item));
    });
  }

  act(event: Event, attempt: Attempt, step: Step, fields: {date: string; target: string; receipt: string; official: string;
    page: string; reason: string}) {
    event.preventDefault();
    const receipt = this.validReceipts(attempt).find(item => item.input.exportId === fields.receipt)?.input;
    const target = attempt.target ?? fields.target;
    const apiTarget = step.id === 'QUITACAO' ? attempt.fees?.invoice.proof.officialId ?? null : target;
    const proof = this.proof(step.id, fields.official, attempt.target ?? target);
    let support = null;
    if (step.id === 'CONFIRMAR_AUSENCIA') support = this.code('SIM-CONFERENCIA-SEM-RECIBO');
    if (step.id === 'TX-E3-13') support = this.code('SIM-PROVA-ADMISSAO-REATIVACAO');
    return this.perform(async () => {
      await this.post('/listings/' + attempt.id + '/actions', {transitionId: step.id, mode: 'SIMULACAO',
        effectiveAt: step.receipt && receipt ? receipt.receivedAt : this.time(fields.date), proof, target: apiTarget,
        captureChannel: proof ? 'CAPTURA_MANUAL' : null,
        exportId: step.receipt ? receipt?.exportId : null, dossier: step.receipt ? receipt?.dossier : null,
        page: step.id === 'TX-E3-14' ? fields.page : null, reason: step.reason ? fields.reason : null, support}, attempt.version);
      await this.load();
      this.feedback.set('Evento registrado na tentativa.');
    });
  }

  close(event: Event, consult: Consultation, at: string, official: string, noComments: boolean) {
    event.preventDefault();
    return this.perform(async () => {
      await this.post('/consultations/' + consult.id + '/actions', {effectiveAt: this.time(at),
        proof: this.proof('TX-E3-24', official, consult.target), summary: this.code('SIM-RESUMO-CONSULTA'),
        noInWindowComments: noComments}, consult.version);
      await this.load();
      this.feedback.set('Encerramento comprovado.');
    });
  }

  submit(event: Event, consult: Consultation, at: string, official: string, topic: string, body: string, contact: string) {
    event.preventDefault();
    return this.perform(async () => {
      await this.post('/consultations/' + consult.id + '/comments', {effectiveAt: this.time(at),
        proof: this.proof('TX-E3-20', official, consult.target), topic, body, contact});
      await this.loadComments();
      this.feedback.set('Manifestação vinculada. O contato fica reservado.');
    });
  }

  commentAction(event: Event, item: CommentData, step: string, at: string, official: string, assigned: boolean, body: string, result: string) {
    event.preventDefault();
    let support = null;
    if (step === 'TX-E3-22') support = this.code('SIM-PROVA-RESPOSTA-CONSULTA');
    if (step === 'VINCULAR_VVB') support = this.code('SIM-PROVA-VINCULO-VVB');
    return this.perform(async () => {
      await this.post('/comments/' + item.id + '/actions', {transitionId: step, effectiveAt: this.time(at),
        proof: this.proof(step, official, item.target), vvb: assigned ? 'SIM-VVB' : null,
        body: step === 'TX-E3-22' || step === 'TX-E3-23' ? body : null,
        result: step === 'TX-E3-23' ? result : null, support}, item.version);
      this.commentSteps.set({});
      await this.loadComments();
      this.feedback.set('Etapa da manifestação registrada.');
    });
  }

  showHistory(attempt: Attempt) {
    return this.perform(async () => this.history.set(await this.read<EventRow[]>('/listings/' + attempt.id + '/history')));
  }

  private base() { return '/v1/projects/' + encodeURIComponent(this.project().project); }
  private ref(ref: Ref) { return {id: ref.id, revision: ref.revision}; }
  private code(id: string): CodeRef { return {id, version: 1}; }
  private proof(step: string, officialId: string, target: string): Proof | null {
    const id = this.options()?.proofs[step];
    return id ? {officialId, target, evidence: this.code(id)} : null;
  }
  private time(value: string) { return value ? value + ':00-03:00' : null; }

  private async load() {
    this.options.set(await this.read<Options>('/listings/options'));
    if (this.isVvb()) {
      const assigned = await this.read<Consultation[]>('/consultations/assigned');
      this.assigned.set(assigned);
      this.consultation.set(assigned.find(item => item.id === this.consultation()?.id) ?? assigned[0] ?? null);
      await this.loadComments();
      return;
    }
    const [listings, dossiers] = await Promise.all([this.read<Listing[]>('/listings'), this.read<Dossier[]>('/dossiers')]);
    this.listings.set(listings);
    this.dossiers.set(dossiers);
    if (!this.current()) this.chosen.set(listings[0]?.attempt.id ?? '');
    await this.loadAttempt();
  }

  private async loadAttempt() {
    const attempt = this.current()?.attempt;
    this.consultation.set(null);
    this.comments.set([]);
    this.history.set([]);
    this.receipts.set([]);
    this.channelChoice.set(attempt?.check?.input.channel ?? 'ARQUIVO');
    this.actionChoice.set(attempt ? this.available(attempt)[0]?.id ?? '' : '');
    if (!attempt) return;
    const id = attempt.consultationId;
    const [consultation, receipts] = await Promise.all([
      id ? this.read<Consultation>('/consultations/' + id) : Promise.resolve(null),
      this.canAct() ? this.read<Receipt[]>('/dossiers/' + attempt.pack.reference.id + '/receipts') : Promise.resolve([]),
    ]);
    this.consultation.set(consultation);
    this.receipts.set(receipts);
    await this.loadComments();
  }

  private async loadComments() {
    const item = this.consultation();
    this.comments.set(item ? await this.read<Comment[]>('/consultations/' + item.id + '/comments') : []);
  }

  private read<T>(path: string) { return firstValueFrom(this.http.get<T>(this.base() + path)); }

  private async post<T>(path: string, body: object, version?: number) {
    const fingerprint = JSON.stringify([path, body, version]);
    const key = this.keys.get(fingerprint) ?? crypto.randomUUID();
    this.keys.set(fingerprint, key);
    const headers: Record<string, string> = {'Idempotency-Key': key};
    if (version !== undefined) headers['If-Match'] = '"' + version + '"';
    try {
      const value = await firstValueFrom(this.http.post<T>(this.base() + path, body, {headers}));
      this.keys.delete(fingerprint);
      return value;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) {
        this.keys.delete(fingerprint);
        if (error.status === 412) await this.load().catch(() => undefined);
      }
      throw error;
    }
  }

  private async perform(work: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(false);
    this.feedback.set('');
    try { await work(); }
    catch (error) {
      this.error.set(true);
      this.feedback.set(error instanceof HttpErrorResponse ? error.error?.message ?? 'Falha na etapa da listagem.'
        : 'Falha na etapa da listagem.');
    } finally { this.busy.set(false); }
  }
}
