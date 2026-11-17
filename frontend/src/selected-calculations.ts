import {Component, computed, DestroyRef, inject, input, OnInit, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {SessionProject} from './access-panel';

interface Ref {id: string; version: number}
interface Selection extends Ref {observations: number}
interface Factor {
  reference: Ref; value: string; unit: string; grid: string; sourceYears: number[]; sourceYearsNote: string;
  validFrom: string; validUntilExclusive: string; source: string; published: boolean; state: string; reason: string | null;
}
interface Catalogue {baseline: Ref; dy: Ref; factors: Factor[]}
interface Emissions {be: string | null; pe: string | null; le: string; er: string | null}
interface Difference {estimate: string; difference: string | null; percent: string | null; note: string}
interface Cut {start: string; endExclusive: string; vintage: number; inputs: {edKwh: string | null; ecKwh: string | null};
  emissions: Emissions; comparison: Difference}
interface Result {factorPerMwh: string; factorPerKwh: string; factorConversion: string; afec: string; mpg: string; evrTotal: number;
  dy: string; cuts: Cut[]; totals: Emissions; comparison: Difference; coverageNote: string}
interface Run extends Ref {state: string; period: string; selectionId: string; configurationVersion: number;
  proposedBy: string; reviewedBy: string | null; snapshotSha256: string | null; result: Result | null}

@Component({selector: 'carbonflow-calculations', templateUrl: './selected-calculations.html'})
export class SelectedCalculations implements OnInit {
  readonly project = input.required<SessionProject>();
  readonly actor = input.required<string>();
  readonly enabled = computed(() => this.roles().some(role => ['PREP', 'REV', 'PP'].includes(role)));
  readonly canPrepare = computed(() => this.roles().includes('PREP'));
  readonly canReview = computed(() => this.roles().includes('REV'));
  readonly period = signal('M1');
  readonly catalogue = signal<Catalogue | null>(null);
  readonly selections = signal<Selection[]>([]);
  readonly runs = signal<Run[]>([]);
  readonly selectedId = signal<string | null>(null);
  readonly selected = computed(() => this.runs().find(run => run.id === this.selectedId()) ?? null);
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly failed = signal(false);

  private readonly http = inject(HttpClient);
  private readonly commandKeys = new Map<string, string>();
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private destroyed = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      clearTimeout(this.refreshTimer);
    });
  }

  ngOnInit() {
    if (this.enabled()) void this.refresh();
  }

  refresh() {
    return this.perform(async () => {
      const [catalogue, selections] = await Promise.all([
        this.read<Catalogue>('/parameters'),
        this.read<Selection[]>('/periods/' + this.period() + '/selections'),
      ]);
      this.catalogue.set(catalogue);
      this.selections.set(selections);
      await this.loadRuns();
    });
  }

  changePeriod(period: string) {
    this.period.set(period);
    return this.perform(async () => this.selections.set(await this.read<Selection[]>('/periods/' + period + '/selections')));
  }

  propose(event: Event, selectionId: string) {
    event.preventDefault();
    const catalogue = this.catalogue();
    const factor = catalogue?.factors.find(item => item.state === 'FIXADO');
    const selection = this.selections().find(item => item.id === selectionId);
    if (!catalogue || !factor || !selection) return;
    return this.perform(async () => {
      const run = await this.post<Run>('/runs', {proposal: {
        period: this.period(), selection: {id: selection.id, version: selection.version},
        baseline: catalogue.baseline, factor: factor.reference, dy: catalogue.dy,
      }});
      this.selectedId.set(run.id);
      await this.loadRuns();
      this.feedback.set('Proposta registrada. Entre como REV para conferir as entradas e confirmar.');
    });
  }

  review(event: Event, run: Run, reason: string) {
    event.preventDefault();
    return this.perform(async () => {
      await this.post('/runs/' + run.id + '/actions',
        {transitionId: run.state === 'PROPOSTA' ? 'CONFIRMAR' : 'RETOMAR', reason}, run.version);
      await this.loadRuns();
      this.feedback.set('Cálculo agendado.');
    });
  }

  reexecute(run: Run) {
    return this.perform(async () => {
      const replay = await this.post<{matches: boolean}>('/runs/' + run.id + '/reexecute', null);
      this.feedback.set(replay.matches ? 'Reexecução idêntica à memória salva.' : 'A reexecução apresentou divergência.');
      this.failed.set(!replay.matches);
    });
  }

  proposeFactor(event: Event, data: {code: string; value: string; unit: string; grid: string; years: string;
    from: string; until: string; source: string; published: boolean}) {
    event.preventDefault();
    return this.perform(async () => {
      const {code, value, unit, grid, years, from, until, source, published} = data;
      const latest = Math.max(0, ...(this.catalogue()?.factors ?? []).filter(item => item.reference.id === code)
        .map(item => item.reference.version));
      await this.post('/parameters', {code, value, unit, grid, sourceYears: years.split(',').map(year => Number(year.trim())),
        validFrom: from, validUntilExclusive: until, source, published}, latest);
      this.catalogue.set(await this.read<Catalogue>('/parameters'));
      this.feedback.set('Candidato registrado. EF-SYN-v1 continua fixado.');
    });
  }

  exclude(event: Event, factor: Factor, reason: string) {
    event.preventDefault();
    return this.perform(async () => {
      await this.post('/parameters/' + factor.reference.id + '/actions', {transitionId: 'EXCLUIR', reason}, factor.reference.version);
      this.catalogue.set(await this.read<Catalogue>('/parameters'));
      this.feedback.set('Exclusão registrada na versão do candidato.');
    });
  }

  label(state: string) {
    return ({PROPOSTA: 'Aguardando revisão', AGUARDANDO: 'Cálculo agendado', CALCULADA: 'Memória calculada',
      COM_LACUNAS: 'Memória com lacunas', FALHA_RECUPERAVEL: 'Falha recuperável'} as Record<string,string>)[state] ?? state;
  }

  number(value: string | null, missing = '—') { return value === null ? missing : value.replace('.', ','); }
  runUrl(run: Run, suffix: string) { return this.base() + '/runs/' + run.id + suffix; }
  selectionUrl(run: Run) { return this.base() + '/periods/' + run.period + '/selections/' + run.selectionId; }

  private roles() { return this.project().roles; }
  private base() { return '/v1/projects/' + encodeURIComponent(this.project().project); }

  private async loadRuns() {
    clearTimeout(this.refreshTimer);
    const runs = await this.read<Run[]>('/runs');
    this.runs.set(runs);
    if (!this.selectedId() && runs.length) this.selectedId.set(runs[0].id);
    if (runs.some(run => run.state === 'AGUARDANDO')) {
      const poll = () => {
        if (this.destroyed) return;
        if (this.busy()) {
          this.refreshTimer = setTimeout(poll, 600);
        } else {
          void this.perform(async () => this.loadRuns());
        }
      };
      this.refreshTimer = setTimeout(poll, 600);
    }
  }

  private read<T>(path: string) { return firstValueFrom(this.http.get<T>(this.base() + path)); }

  private async post<T>(path: string, body: object | null, version?: number) {
    const fingerprint = JSON.stringify([path, body, version]);
    const command = this.commandKeys.get(fingerprint) ?? crypto.randomUUID();
    this.commandKeys.set(fingerprint, command);
    const headers: Record<string, string> = {'Idempotency-Key': command};
    if (version !== undefined) headers['If-Match'] = '"' + version + '"';
    try {
      const result = await firstValueFrom(this.http.post<T>(this.base() + path, body, {headers}));
      this.commandKeys.delete(fingerprint);
      return result;
    } catch (error: unknown) {
      if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) {
        this.commandKeys.delete(fingerprint);
        if (error.status === 412) await this.loadRuns().catch(() => undefined);
      }
      throw error;
    }
  }

  private async perform(work: () => Promise<void>) {
    if (this.busy() || this.destroyed) return;
    this.busy.set(true);
    this.failed.set(false);
    this.feedback.set('');
    try {
      await work();
    } catch (error: unknown) {
      this.failed.set(true);
      this.feedback.set(error instanceof HttpErrorResponse ? error.error?.message ?? 'Falha ao consultar o cálculo.'
        : 'Falha ao concluir a operação.');
    } finally {
      this.busy.set(false);
    }
  }
}
