import {afterNextRender, Component, computed, DestroyRef, ElementRef, inject, Injector, input, OnInit, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {SessionProject} from './access-panel';

interface Ref { id: string; version: number }
interface Options { sourceId: string; authorization: Ref; mappingProfile: Ref; maxBytes: number; maxLines: number }
interface Artifact extends Ref { originalName: string; sha256: string; bytes: number }
interface Job { id: string; period: string; state: string }
interface Report extends Job { version: number; artifact: Artifact; configurationVersion: number; total: number; processed: number;
  classifications: Record<string, number>; message: string | null }
interface Line extends Ref {
  line: number; original: string; classification: string; decision: string; reasons: {code: string; message: string}[];
  fact: {kind: string; kwh: string | null; sourceFactId: string | null} | null;
  proposal: {preparedBy: string; reason: string; predecessor: Ref | null} | null;
  resolution: {reviewedBy: string; reason: string; observation: Ref | null} | null;
}
interface Page { lines: Line[]; nextCursor: string | null }
interface Selection extends Ref { observations: number }
interface Active { ref: Ref; fact: NonNullable<Line['fact']> }

const statuses: Record<string, string> = {
  AGUARDANDO: 'Aguardando', PROCESSANDO: 'Processando', CONCLUIDO: 'Concluído', RECUSADO: 'Lote recusado',
  FALHA_RECUPERAVEL: 'Falha recuperável', CANDIDATA: 'Candidata', INVALIDA: 'Inválida', AUSENTE: 'Ausente',
  PENDENTE: 'Pendente', DUPLICATA: 'Duplicata', FORA_DO_ESCOPO: 'Fora do escopo', CONFLITO_ID: 'Conflito de ID',
  SOBREPOSICAO: 'Sobreposição', RECUSADAS_LOTE: 'Recusadas com o lote', EM_PREVIA: 'Em prévia', PROPOSTA: 'Proposta',
  ADMITIDA: 'Admitida', REJEITADA: 'Rejeitada', REPETIDA: 'Vinculada à observação existente',
};

@Component({selector: 'carbonflow-imports', templateUrl: './import-panel.html'})
export class ImportPanel implements OnInit {
  readonly project = input.required<SessionProject>();
  readonly actor = input.required<string>();
  readonly enabled = computed(() => ['OP', 'PREP', 'REV'].some(role => this.has(role)));
  readonly canUpload = computed(() => this.has('OP') || this.has('PREP'));
  readonly options = signal<Options | null>(null);
  readonly jobs = signal<Job[]>([]);
  readonly report = signal<Report | null>(null);
  readonly page = signal<Page | null>(null);
  readonly cursor = signal<string | undefined>(undefined);
  readonly selections = signal<Selection[]>([]);
  readonly active = signal<Active[]>([]);
  readonly reviewing = signal<Line | null>(null);
  readonly period = signal('M1');
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly failed = signal(false);
  readonly chosenFile = signal<File | null>(null);
  readonly received = signal<Artifact | null>(null);

  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  private readonly reasonInput = viewChild<ElementRef<HTMLInputElement>>('reason');
  private readonly notice = viewChild<ElementRef<HTMLElement>>('notice');
  private readonly retryKeys = new Map<string, string>();
  private timer?: ReturnType<typeof setTimeout>;
  private stopped = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.stopped = true;
      clearTimeout(this.timer);
    });
  }

  ngOnInit() {
    if (this.enabled()) void this.perform(async () => {
      this.options.set(await this.read<Options>('/imports/options'));
      await this.reloadJobs();
    });
  }

  has(role: string) { return this.project().roles.includes(role); }
  label(value: string) { return statuses[value] ?? value; }
  counts(report: Report) { return Object.entries(report.classifications); }
  choose(event: Event) { this.chosenFile.set((event.target as HTMLInputElement).files?.[0] ?? null); }
  sampleUrl() { return this.base() + '/imports/example'; }
  rawUrl(report: Report) { return this.base() + '/imports/files/' + report.artifact.id + '/versions/1/content'; }
  snapshotUrl(item: Selection) {
    return this.base() + '/periods/' + this.report()?.period + '/selections/' + item.id;
  }
  observationUrl(ref: Ref) { return this.base() + '/observations/' + ref.id + '/versions/' + ref.version; }

  sendFile() {
    const file = this.chosenFile();
    if (file) return this.perform(async () => this.upload(file));
  }

  sendSample() {
    return this.perform(async () => {
      const bytes = await firstValueFrom(this.http.get(this.sampleUrl(), {responseType: 'blob'}));
      await this.upload(new File([bytes], 'e8-004-ed-ec.csv', {type: 'text/csv'}));
    });
  }

  enqueueReceived() {
    const artifact = this.received();
    if (artifact) return this.perform(async () => this.enqueue(artifact));
  }

  open(id: string) { return this.perform(async () => this.loadReport(id)); }

  next(cursor?: string) {
    const id = this.report()?.id;
    if (id) return this.perform(async () => this.loadPage(id, cursor));
  }

  resume() {
    const report = this.report();
    if (report) return this.perform(async () => {
      await this.post('/imports/' + report.id + '/resume', null, report.version);
      await this.loadReport(report.id);
    });
  }

  beginReview(line: Line) {
    this.reviewing.set(line);
    afterNextRender(() => this.reasonInput()?.nativeElement.focus(), {injector: this.injector});
  }

  decide(event: Event, action: string, reason: string, predecessor: string) {
    event.preventDefault();
    const line = this.reviewing();
    if (!line) return;
    return this.perform(async () => {
      let corrected: Ref | null = null;
      if (predecessor.trim() && action === 'PROPOR') {
        const match = /^([0-9a-f-]{36})@([1-9][0-9]*)$/i.exec(predecessor.trim());
        if (!match) throw new Error('Informe a predecessora como ID@versão.');
        corrected = {id: match[1], version: Number(match[2])};
      }
      await this.post('/observations/' + line.id + '/actions', {
        transitionId: action, reason, predecessor: corrected,
      }, line.version);
      this.reviewing.set(null);
      await this.loadPage(this.report()!.id, this.cursor());
      await this.loadSelections(this.report()!.period);
      this.feedback.set('Decisão registrada.');
    });
  }

  freeze() {
    const report = this.report();
    if (report) return this.perform(async () => {
      await this.post('/periods/' + report.period + '/selections', null);
      await this.loadSelections(report.period);
      this.feedback.set('Versões admitidas preservadas no retrato.');
    });
  }

  private async upload(file: File) {
    const options = this.options()!;
    if (file.size === 0 || file.size > options.maxBytes) {
      throw new Error('Escolha um CSV com conteúdo e até 10 MB.');
    }
    const checksum = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer())))
      .map(value => value.toString(16).padStart(2, '0')).join('');
    const path = '/artifacts';
    const signature = JSON.stringify([path, file.name, checksum]);
    const key = this.retryKeys.get(signature) ?? crypto.randomUUID();
    this.retryKeys.set(signature, key);
    try {
      const response = await firstValueFrom(this.http.post<{artifact: Artifact}>(this.base() + path, file, {
        headers: {'Content-Type': 'text/csv', 'Idempotency-Key': key, 'X-File-Name': encodeURIComponent(file.name),
          'X-Source-Id': options.sourceId, 'X-Source-Authorization': options.authorization.id},
      }));
      this.received.set(response.artifact);
      this.retryKeys.delete(signature);
      await this.enqueue(response.artifact);
    } catch (error: unknown) {
      if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) this.retryKeys.delete(signature);
      throw error;
    }
  }

  private async enqueue(artifact: Artifact) {
    const options = this.options()!;
    const result = await this.post<{jobId: string; repeated: boolean}>('/imports', {
      artifact: {id: artifact.id, version: artifact.version}, sourceId: options.sourceId,
      mappingProfile: options.mappingProfile, period: {id: this.period(), version: 1},
    });
    await this.reloadJobs();
    await this.loadReport(result.jobId);
    this.received.set(null);
    this.feedback.set(result.repeated ? 'Arquivo vinculado ao lote anterior.' : 'Arquivo bruto recebido e prévia agendada.');
  }

  private async reloadJobs() { this.jobs.set(await this.read<Job[]>('/imports')); }

  private async loadReport(id: string) {
    clearTimeout(this.timer);
    this.reviewing.set(null);
    const report = await this.read<Report>('/imports/' + id);
    this.report.set(report);
    await this.loadPage(id);
    await this.loadSelections(report.period);
    if (['AGUARDANDO', 'PROCESSANDO'].includes(report.state)) {
      this.timer = setTimeout(() => {
        if (this.stopped || this.report()?.id !== id) return;
        if (this.busy()) {
          this.timer = setTimeout(() => void this.open(id), 500);
        } else {
          void this.open(id);
        }
      }, 650);
    } else {
      await this.reloadJobs();
    }
  }

  private async loadPage(id: string, cursor?: string) {
    const route = '/imports/' + id + '/rows' + (cursor ? '?cursor=' + encodeURIComponent(cursor) : '');
    this.page.set(await this.read<Page>(route));
    this.cursor.set(cursor);
  }

  private async loadSelections(period: string) {
    const [active, selections] = await Promise.all([
      this.read<Active[]>('/observations?period=' + period),
      this.read<Selection[]>('/periods/' + period + '/selections'),
    ]);
    this.active.set(active);
    this.selections.set(selections);
  }

  private async post<T>(path: string, body: object | null, version?: number) {
    const signature = JSON.stringify([path, body, version]);
    const key = this.retryKeys.get(signature) ?? crypto.randomUUID();
    this.retryKeys.set(signature, key);
    const headers: Record<string, string> = {'Idempotency-Key': key};
    if (version !== undefined) headers['If-Match'] = '"' + version + '"';
    try {
      const result = await firstValueFrom(this.http.post<T>(this.base() + path, body, {headers}));
      this.retryKeys.delete(signature);
      return result;
    } catch (error: unknown) {
      if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) this.retryKeys.delete(signature);
      if (error instanceof HttpErrorResponse && error.status === 412 && this.report()) {
        await this.loadReport(this.report()!.id).catch(() => undefined);
      }
      throw error;
    }
  }

  private read<T>(path: string) { return firstValueFrom(this.http.get<T>(this.base() + path)); }
  private base() { return '/v1/projects/' + encodeURIComponent(this.project().project); }

  private async perform(work: () => Promise<void>) {
    if (this.busy() || this.stopped) return;
    this.busy.set(true);
    this.feedback.set('');
    this.failed.set(false);
    try {
      await work();
    } catch (error: unknown) {
      this.failed.set(true);
      if (error instanceof HttpErrorResponse && error.status === 413) {
        this.feedback.set('Limite de 10 MB. O arquivo inteiro foi recusado.');
      } else if (error instanceof HttpErrorResponse) {
        this.feedback.set(error.error?.message ?? 'Falha ao concluir a operação.');
      } else {
        this.feedback.set(error instanceof Error ? error.message : 'Falha ao concluir a operação.');
      }
    } finally {
      this.busy.set(false);
      if (this.feedback() && !this.stopped) {
        afterNextRender(() => this.notice()?.nativeElement.focus(), {injector: this.injector});
      }
    }
  }
}
