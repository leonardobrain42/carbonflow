import {Component, computed, inject, input, OnInit, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {SessionProject} from './access-panel';

interface Ref {id: string; version: number}
interface Finding {code: string; channel: string | null; from: string | null; until: string | null; row: string | null}
interface Coverage {complete: boolean; findings: number; examples: Finding[]; expectedChannels: number}
interface Dependent {input: {kind: string; reference: string; version: number; status: string}; event: string}
interface Period extends Ref {
  revision: number; state: string; effectiveAt: string | null; actor: string; reason: string;
  closureSha256: string | null; coverage: Coverage | null;
  impact: {affectedRevision: number; dependents: Dependent[]; divergenceId: string | null; state: string} | null;
}
interface Run extends Ref {period: string; state: string; result: {status: string; totals: {er: string | null}} | null}
interface Action {id: string; label: string}
interface Window {id: string; start: string; endExclusive: string; endInclusive: string}

@Component({selector: 'carbonflow-monitoring', templateUrl: './monitoring-periods.html'})
export class MonitoringPeriods implements OnInit {
  readonly project = input.required<SessionProject>();
  readonly windows: Window[] = [
    {id: 'M1', start: '2027-10-01', endExclusive: '2028-10-01', endInclusive: '2028-09-30'},
    {id: 'M2', start: '2028-10-01', endExclusive: '2029-10-01', endInclusive: '2029-09-30'},
  ];
  readonly enabled = computed(() => this.project().roles.some(role => ['PREP', 'REV', 'OP', 'PP'].includes(role)));
  readonly periods = signal<Period[]>([]);
  readonly runs = signal<Run[]>([]);
  readonly selected = signal('M1');
  readonly current = computed(() => this.periods().find(item => item.id === this.selected()));
  readonly window = computed(() => this.windows.find(item => item.id === this.selected())!);
  readonly history = signal<Period[]>([]);
  readonly related = signal<Dependent[]>([]);
  readonly report = signal<Coverage | null>(null);
  readonly busy = signal(false);
  readonly error = signal(false);
  readonly feedback = signal('');

  private readonly http = inject(HttpClient);
  private readonly commandKeys = new Map<string, string>();

  ngOnInit() { if (this.enabled()) void this.refresh(); }

  has(role: string) { return this.project().roles.includes(role); }
  windowUrl(suffix: string) { return this.base() + '/periods/' + this.selected() + suffix; }
  sampleUrl(period: string) { return this.base() + '/imports/example/monitoring/' + period; }
  actions(period: Period): Action[] {
    if (this.has('OP') && period.state === 'PREVISTO') return [{id: 'TX-E3-41', label: 'Abrir coleta'}];
    if (this.has('OP') && period.state === 'COLETANDO') return [{id: 'TX-E3-42', label: 'Encerrar coleta'}];
    if (this.has('REV') && period.state === 'EM_CONFERENCIA') return [
      {id: 'TX-E3-45', label: 'Fechar período'}, {id: 'TX-E3-43', label: 'Registrar lacunas'},
    ];
    if (this.has('PREP') && period.state === 'COM_LACUNAS') return [{id: 'TX-E3-44', label: 'Comprovar recuperação'}];
    if (this.has('REV') && period.state === 'FECHADO') return [{id: 'TX-E3-46', label: 'Reabrir período'}];
    return [];
  }
  label(state: string) {
    return ({PREVISTO: 'Previsto', COLETANDO: 'Coletando', EM_CONFERENCIA: 'Em conferência',
      COM_LACUNAS: 'Com lacunas', FECHADO: 'Fechado', INTERVALO_AUSENTE: 'Intervalo ausente',
      LINHA_SEM_DECISAO: 'Linha sem decisão', INTERVALO_SOBREPOSTO: 'Sobreposição',
      IMPORTACAO_INCOMPLETA: 'Importação incompleta', LEITURA_SEM_PROVA_ATUAL: 'Prova da leitura insuficiente',
      MEMORIA_INCOMPLETA: 'Memória incompleta', SELECAO_OU_CONFIGURACAO_DESATUALIZADA: 'Seleção ou ficha desatualizada',
    } as Record<string, string>)[state] ?? state;
  }
  date(value: string) { return value.slice(0, 10).split('-').reverse().join('/'); }
  effective(period: Period) {
    const boundary = period.state === 'PREVISTO' ? this.window().start : this.window().endExclusive;
    return period.state === 'COLETANDO' || !period.effectiveAt ? boundary + 'T00:00:00-03:00' : period.effectiveAt;
  }
  memories() { return this.runs().filter(run => run.period === this.selected() && run.result); }

  refresh() { return this.perform(() => this.load()); }

  choose(period: string) {
    this.selected.set(period);
    this.report.set(null);
    this.history.set([]);
    this.related.set([]);
    if (this.current()?.state === 'FECHADO') void this.perform(() => this.loadRelated());
  }

  propose() {
    return this.perform(async () => {
      const {id, start, endExclusive} = this.window();
      await this.post('/periods', {id, start, endExclusive, reason: 'Janela sintética do piloto'}, 0);
      await this.load();
      this.feedback.set('Janela prevista.');
    });
  }

  act(event: Event, period: Period, action: string, effectiveAt: string, reason: string, runId: string, artifact: string) {
    event.preventDefault();
    const run = this.memories().find(item => item.id === runId);
    return this.perform(async () => {
      await this.post('/periods/' + period.id + '/actions', {transitionId: action, effectiveAt, reason,
        run: run ? {id: run.id, version: run.version} : null, recoveryArtifact: artifact.trim() || null}, period.version);
      this.report.set(null);
      this.history.set([]);
      await this.load();
      this.feedback.set('Transição registrada.');
    });
  }

  assess(runId: string) {
    const run = this.memories().find(item => item.id === runId);
    if (!run) return;
    return this.perform(async () => {
      this.report.set(await this.read<Coverage>('/periods/' + this.selected() + '/coverage?run=' + run.id + '&version=' + run.version));
    });
  }

  showHistory() {
    return this.perform(async () => this.history.set(await this.read<Period[]>('/periods/' + this.selected() + '/history')));
  }

  link(event: Event, period: Period, choice: string, packageRef: string) {
    event.preventDefault();
    const [reference, status, proof] = choice.split('|');
    const [packageId, version] = packageRef.trim().split('@');
    const body = choice === 'PACOTE' ? {kind: 'PACOTE', reference: packageId, version: Number(version), status: 'VINCULADO'} :
      {kind: proof ? 'ATO_EXTERNO' : 'DECISAO', reference, version: 1, status,
        proof: proof ? {id: proof, version: 1} : null};
    return this.perform(async () => {
      await this.post('/periods/' + period.id + '/dependents', body, period.version);
      await this.loadRelated();
      this.feedback.set('Referência vinculada ao fechamento.');
    });
  }

  split(event: Event, original: string, segmentRefs: string, proof: string, reason: string) {
    event.preventDefault();
    const parse = (value: string) => {
      const [id, version] = value.trim().split('@');
      return {id, version: Number(version)};
    };
    const parent = parse(original);
    return this.perform(async () => {
      await this.post('/observations/' + encodeURIComponent(parent.id) + '/measured-split',
        {segments: segmentRefs.trim().split(/\s+/).map(parse), proof: proof.trim(), reason}, parent.version);
      this.feedback.set('Segmentos reconciliados. PREP e REV podem seguir com a admissão.');
    });
  }

  private base() { return '/v1/projects/' + encodeURIComponent(this.project().project); }
  private read<T>(path: string) { return firstValueFrom(this.http.get<T>(this.base() + path)); }

  private async load() {
    const [periods, runs] = await Promise.all([this.read<Period[]>('/periods'),
      this.has('OP') && !this.has('PREP') && !this.has('REV') && !this.has('PP') ? Promise.resolve([]) : this.read<Run[]>('/runs')]);
    this.periods.set(periods);
    this.runs.set(runs);
    await this.loadRelated();
  }

  private async loadRelated() {
    const current = this.current();
    this.related.set(current?.state === 'FECHADO' ? await this.read<Dependent[]>('/periods/' + current.id + '/closures/'
      + current.revision + '/dependents') : []);
  }

  private async post(path: string, body: object, version: number) {
    const fingerprint = JSON.stringify([path, body, version]);
    const key = this.commandKeys.get(fingerprint) ?? crypto.randomUUID();
    this.commandKeys.set(fingerprint, key);
    try {
      await firstValueFrom(this.http.post(this.base() + path, body,
        {headers: {'Idempotency-Key': key, 'If-Match': '"' + version + '"'}}));
      this.commandKeys.delete(fingerprint);
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
    this.error.set(false);
    this.feedback.set('');
    try {
      await work();
    } catch (error) {
      this.error.set(true);
      this.feedback.set(error instanceof HttpErrorResponse ? error.error?.message ?? 'Falha na operação do período.'
        : 'Falha na operação do período.');
    } finally {
      this.busy.set(false);
    }
  }
}
