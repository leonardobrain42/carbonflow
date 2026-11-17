import {Component, computed, inject, input, OnInit, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {SessionProject} from './access-panel';

interface Ref {id: string; revision: number}
interface Variant {artifactId: string; exposure: string; edition: string; sha256: string; manifestSha256: string}
interface CodeRef {id: string; version: number}
interface Piece {item: string; title: string; issuer: string; reference: CodeRef}
interface Condition {item: string; incidence: string; reason: string}
interface Checklist {rule: string; template: string; model: string; language: string; pieces: Piece[]; conditions: string[]}
interface Options {
  act: string; actDate: string; closure: {period: string; revision: number} | null; validatedPd: Ref | null;
  predecessor: Ref | null; pieces: {item: string; reference: CodeRef}[]; conditions: Condition[]; privateNote: string | null;
}
interface Dossier extends Ref {
  version: number; state: string; preparedBy: string; command: Options; variants: Variant[];
  findings: {code: string; item: string; message: string}[]; review: {actor: string; reason: string; expectedCorrection: string} | null;
}
interface Section {key: string; heading: string; text: string; table: string[][]}
interface Period {id: string; revision: number; state: string}
interface Export {id: string; dossier: Ref; exposure: string; edition: string; destination: string; sha256: string}
interface Receipt {input: {exportId: string; code: string}; issuer: string}

@Component({selector: 'carbonflow-dossiers', templateUrl: './dossier-panel.html'})
export class DossierPanel implements OnInit {
  readonly project = input.required<SessionProject>();
  readonly actor = input.required<string>();
  readonly enabled = computed(() => this.project().roles.some(role => ['PREP', 'REV', 'PP'].includes(role)));
  readonly canPrepare = computed(() => this.project().roles.includes('PREP'));
  readonly canReview = computed(() => this.project().roles.includes('REV'));
  readonly canExport = computed(() => this.project().roles.includes('PP'));
  readonly acts = [
    {key: 'DESENVOLVIMENTO', label: 'PD indicativo · desenvolvimento', date: '2027-08-02'},
    {key: 'LISTAGEM_VALIDACAO', label: 'PD completo · listagem em validação', date: '2027-12-01'},
    {key: 'REGISTRO', label: 'PD completo · registro', date: '2028-03-01'},
    {key: 'VERIFICACAO', label: 'MR · pedido de aprovação', date: '2028-12-01'},
  ];
  readonly dossiers = signal<Dossier[]>([]);
  readonly periods = signal<Period[]>([]);
  readonly current = signal<Dossier | null>(null);
  readonly predecessor = signal<Dossier | null>(null);
  readonly drafting = signal(false);
  readonly reviewStep = signal('TX-E3-08');
  readonly act = signal(this.acts[0].key);
  readonly period = signal('M1');
  readonly checklist = signal<Checklist | null>(null);
  readonly selectedPieces = signal<string[]>([]);
  readonly incidences = signal<Record<string, Condition>>({});
  readonly sections = signal<Section[] | null>(null);
  readonly exposure = signal('PUBLICA');
  readonly exports = signal<Export[]>([]);
  readonly receipts = signal<Receipt[]>([]);
  readonly busy = signal(false);
  readonly failed = signal(false);
  readonly feedback = signal('');

  private readonly http = inject(HttpClient);
  private readonly commandKeys = new Map<string, string>();

  ngOnInit() { if (this.enabled()) void this.refresh(); }

  display(ref: Ref) { return ref.id.slice(0, 8) + ' · r' + ref.revision; }
  identity(ref: Ref) { return ref.id + '@' + ref.revision; }
  label(act: string) { return this.acts.find(item => item.key === act)?.label ?? act; }
  date() {
    return this.act() === 'VERIFICACAO' && this.period() === 'M2' ? '2029-12-03' :
      this.acts.find(item => item.key === this.act())!.date;
  }
  registrar() { return this.dossiers().filter(item => item.command.act === 'REGISTRO' && item.state === 'CONGELADO'); }
  currentClosure() { return this.periods().find(item => item.id === this.period() && item.state === 'FECHADO'); }
  newest(dossier: Dossier) { return !this.dossiers().some(item => item.id === dossier.id && item.revision > dossier.revision); }
  manifestUrl(dossier: Dossier, variant: Variant) {
    return this.base() + '/' + dossier.id + '/versions/' + dossier.revision + '/manifest/' + variant.exposure + '/' + variant.edition;
  }
  downloadUrl(exported: Export) { return this.base() + '/' + exported.dossier.id + '/exports/' + exported.id + '/content'; }

  refresh() { return this.perform(() => this.load()); }

  select(value: string) {
    const record = this.dossiers().find(item => this.identity(item) === value) ?? null;
    this.current.set(record);
    this.reviewStep.set('TX-E3-08');
    this.sections.set(null);
    this.exports.set([]);
    this.receipts.set([]);
    if (record && this.canExport()) void this.perform(() => this.loadDelivery(record));
  }

  begin(previous: Dossier | null) {
    return this.perform(async () => {
      this.predecessor.set(previous);
      this.act.set(previous?.command.act ?? this.acts[0].key);
      this.period.set(previous?.command.closure?.period ?? 'M1');
      await this.loadChecklist(previous);
      this.drafting.set(true);
    });
  }

  change(act: string, period: string) {
    this.act.set(act);
    this.period.set(period);
    return this.perform(() => this.loadChecklist(null));
  }

  mark(piece: string, checked: boolean) {
    this.selectedPieces.update(items => checked ? [...items, piece] : items.filter(item => item !== piece));
  }

  incidence(item: string, incidence: string, reason: string) {
    this.incidences.update(values => ({...values, [item]: {item, incidence, reason}}));
  }

  create(event: Event, actDate: string, pd: string, reason: string, privateNote: string) {
    event.preventDefault();
    const checklist = this.checklist();
    if (!checklist) return;
    const previous = this.predecessor();
    const closure = this.act() === 'VERIFICACAO' ? this.currentClosure() : null;
    const validated = this.registrar().find(item => this.identity(item) === pd);
    return this.perform(async () => {
      const record = await this.post<Dossier>('', {document: {act: this.act(), actDate, model: checklist.model, language: checklist.language,
        closure: closure ? {period: closure.id, revision: closure.revision} : null,
        validatedPd: validated ? this.ref(validated) : null, predecessor: previous ? this.ref(previous) : null, reason,
        privateNote, pieces: checklist.pieces.filter(item => this.selectedPieces().includes(item.item))
          .map(({item, reference}) => ({item, reference})), conditions: Object.values(this.incidences())}}, previous?.version ?? 0);
      this.current.set(record);
      this.drafting.set(false);
      this.sections.set(null);
      await this.load();
      this.feedback.set('Rascunho registrado.');
    });
  }

  action(event: Event, dossier: Dossier, transitionId: string, reason: string, item: string, responsible: string, expectedCorrection: string) {
    event.preventDefault();
    return this.perform(async () => {
      const record = await this.post<Dossier>('/' + dossier.id + '/actions', {transitionId,
        dossier: this.ref(dossier), reason, item, responsible, expectedCorrection}, dossier.version);
      this.current.set(record);
      await this.load();
      this.feedback.set('Revisão registrada.');
    });
  }

  preview(dossier: Dossier, exposure: string) {
    this.exposure.set(exposure);
    return this.perform(async () => {
      this.sections.set(await this.read<Section[]>('/' + dossier.id + '/versions/' + dossier.revision + '/preview?exposure=' + exposure));
    });
  }

  export(event: Event, dossier: Dossier, key: string, destination: string) {
    event.preventDefault();
    const variant = dossier.variants.find(item => item.artifactId === key);
    if (!variant) return;
    return this.perform(async () => {
      await this.post<Export>('/' + dossier.id + '/exports', {revision: dossier.revision, exposure: variant.exposure,
        edition: variant.edition, destination, channel: destination === 'SIM-HUB' ? 'DIGITAL_HUMANO_SIMULADO' : 'ARQUIVO_SIMULADO'}, dossier.version);
      await this.loadDelivery(dossier);
      this.feedback.set('Exportação registrada. Baixe o ZIP na lista abaixo.');
    });
  }

  receipt(event: Event, dossier: Dossier, exportId: string, code: string, proof: string, receivedAt: string) {
    event.preventDefault();
    const exported = this.exports().find(item => item.id === exportId);
    if (!exported) return;
    return this.perform(async () => {
      await this.post('/' + dossier.id + '/receipts', {exportId, dossier: exported.dossier,
        sha256: exported.sha256, destination: exported.destination, code, proof, receivedAt: receivedAt + ':00-03:00'});
      await this.loadDelivery(dossier);
      this.feedback.set('Recibo simulado vinculado à exportação.');
    });
  }

  private ref(ref: Ref) { return {id: ref.id, revision: ref.revision}; }
  private base() { return '/v1/projects/' + encodeURIComponent(this.project().project) + '/dossiers'; }
  private read<T>(path: string) { return firstValueFrom(this.http.get<T>(this.base() + path)); }

  private async loadChecklist(previous: Dossier | null) {
    const periods = this.act() === 'VERIFICACAO' ? '&period=' + this.period() : '';
    const checklist = await this.read<Checklist>('/checklist?act=' + this.act() + periods + '&successor=' + (previous !== null));
    this.checklist.set(checklist);
    this.selectedPieces.set(previous?.command.pieces.map(piece => piece.item) ?? []);
    this.incidences.set(Object.fromEntries((previous?.command.conditions ?? []).map(item => [item.item, item])));
  }

  private async load() {
    const [dossiers, periods] = await Promise.all([this.read<Dossier[]>(''),
      firstValueFrom(this.http.get<Period[]>(this.base().replace(/dossiers$/, 'periods')))]);
    this.dossiers.set(dossiers);
    this.periods.set(periods);
    const current = this.current();
    if (current) this.current.set(dossiers.find(item => this.identity(item) === this.identity(current)) ?? null);
    else if (dossiers.length) this.current.set(dossiers[0]);
    if (this.current() && this.canExport()) await this.loadDelivery(this.current()!);
  }

  private async loadDelivery(dossier: Dossier) {
    const [exports, receipts] = await Promise.all([this.read<Export[]>('/' + dossier.id + '/exports'),
      this.read<Receipt[]>('/' + dossier.id + '/receipts')]);
    this.exports.set(exports);
    this.receipts.set(receipts);
  }

  private async post<T>(path: string, body: object, version?: number) {
    const fingerprint = JSON.stringify([path, body, version]);
    const key = this.commandKeys.get(fingerprint) ?? crypto.randomUUID();
    this.commandKeys.set(fingerprint, key);
    const headers: Record<string, string> = {'Idempotency-Key': key};
    if (version !== undefined) headers['If-Match'] = '"' + version + '"';
    try {
      const result = await firstValueFrom(this.http.post<T>(this.base() + path, body, {headers}));
      this.commandKeys.delete(fingerprint);
      return result;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) {
        this.commandKeys.delete(fingerprint);
        if (error.status === 412) await this.load().catch(() => undefined);
      }
      throw error;
    }
  }

  private async perform(work: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.failed.set(false);
    this.feedback.set('');
    try {
      await work();
    } catch (error) {
      this.failed.set(true);
      this.feedback.set(error instanceof HttpErrorResponse ? error.error?.message ?? 'Falha na operação do dossiê.'
        : 'Falha na operação do dossiê.');
    } finally {
      this.busy.set(false);
    }
  }
}
