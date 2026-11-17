import {Component, computed, inject, input, OnInit, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {SessionProject} from './access-panel';

interface Job {
  id: string; kind: 'IMPORTACAO' | 'CALCULO'; period: string; version: number; state: string;
  processed: number; total: number; reason: string | null;
}
interface Attempt {
  generation: number; jobVersion: number; state: string; claimedAt: string; expiresAt: string;
  resultVersion: number | null; reason: string | null; eventId: string;
}
interface Retention {
  asOf: string; creditEnd: {date: string; version: number; source: string};
  storage: {usedBytes: number; limitBytes: number}; holds: string[];
  categories: {name: string; records: number | null; floorInclusive: string | null; status: string}[];
}
interface Recovery {
  recovery: {id: string; checkpoint: string; observedThrough: string; checkedBy: string | null; releasedAt: string | null; gapSha: string};
  version: number; unsettledJobs: number; rpoSeconds: number; rtoSeconds: number | null;
  runs: {id: string; period: string; verified: boolean; sha256: string}[];
  effects: {kind: string; source: string; namespace: string; reference: string; status: string;
    binding: {verification: string; target: string; account: string;
      lines: {amount: number; vintage: {start: string; endExclusive: string}}[]} | null}[];
}

const labels: Record<string, string> = {
  IMPORTACAO: 'Importação', CALCULO: 'Cálculo', EM_EXECUCAO: 'Em execução', CONFIRMADA: 'Confirmada',
  EXPIRADA: 'Expirada', FALHA_RECUPERAVEL: 'Falha recuperável', COM_LACUNAS: 'Com lacunas',
  AGUARDANDO: 'Aguardando', PROCESSANDO: 'Processando', CALCULADA: 'Calculada', CONCLUIDO: 'Concluído', RECUSADO: 'Recusado',
  MONITORAMENTO: 'Monitoramento', VALIDACAO_VERIFICACAO: 'Validação e verificação', PROVAS_DE_ATOS: 'Provas de atos',
  LOG_OPERACIONAL: 'Registro operacional', GUARDA_MINIMA: 'Guarda mínima', GUARDA_POR_PENDENCIA: 'Guarda por pendência',
  REAVALIAR_RETENCAO: 'Reavaliar prazo', POLITICA_PENDENTE: 'Política pendente', COMISSIONAMENTO: 'Comissionamento',
  MASSA_SINTETICA: 'Massa sintética', AUDITORIA: 'Auditoria em curso', REVISAO_DOCUMENTAL: 'Revisão documental', DIVERGENCIA_EMISSAO: 'Divergência de emissão',
  RECUPERACAO: 'Recuperação', CONFERIDO: 'Conferido', A_REPOR: 'A recompor', A_CAPTURAR: 'A capturar',
  A_CONCILIAR: 'A conciliar', HASH_DIVERGENTE: 'Hash divergente', POSICAO_DIVERGENTE: 'Posição divergente',
  ATO_EXTERNO: 'Ato externo', FATO_ED_EC: 'Fato ED/EC', BRUTO: 'Bruto ED/EC', RECIBO: 'Recibo',
};

@Component({selector: 'carbonflow-operations', templateUrl: './operations-panel.html'})
export class OperationsPanel implements OnInit {
  readonly project = input.required<SessionProject>();
  readonly jobs = signal<Job[] | null>(null);
  readonly attempts = signal<{job: Job; values: Attempt[]} | null>(null);
  readonly retention = signal<Retention | null>(null);
  readonly recovery = signal<Recovery | null>(null);
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly failed = signal(false);
  readonly period = signal('');
  readonly canOperate = computed(() => this.project().roles.includes('OP'));
  readonly canReview = computed(() => this.project().roles.includes('REV'));
  readonly canReplay = computed(() => this.canReview() || this.project().roles.includes('PREP'));
  readonly enabled = computed(() => this.project().roles.some(role => ['OP', 'PREP', 'REV', 'PP'].includes(role)));
  private readonly http = inject(HttpClient);
  private readonly retryKeys = new Map<string, string>();

  ngOnInit() { if (this.enabled()) void this.refresh(); }
  label(value: string) { return labels[value] ?? value; }
  number(value: number) { return Intl.NumberFormat('pt-BR').format(value); }
  date(value: string) { return value.split('-').reverse().join('/'); }
  instant(value: string) { return new Date(value).toLocaleString('pt-BR', {timeZone: 'America/Sao_Paulo'}); }

  refresh() {
    return this.perform(async () => {
      const [retention, recovery] = await Promise.all([
        this.read<Retention>('/retention'), this.read<Recovery | null>('/recovery'),
      ]);
      this.retention.set(retention);
      this.recovery.set(recovery);
      if (this.jobs()) await this.loadJobs();
    });
  }

  filterJobs(value: string) {
    this.period.set(value);
    this.attempts.set(null);
    return this.perform(() => this.loadJobs());
  }

  openAttempts(job: Job) {
    return this.perform(async () => {
      this.attempts.set({job, values: await this.read<Attempt[]>('/jobs/' + job.id + '/attempts')});
    });
  }

  canRetry(job: Job) {
    return job.state === 'FALHA_RECUPERAVEL' && (job.kind === 'CALCULO' ? this.canReview()
      : this.canOperate() || this.project().roles.includes('PREP'));
  }

  retry(job: Job) {
    return this.perform(async () => {
      await this.post('/jobs/' + job.id + '/retry', {reason: 'Retomada da última etapa registrada na trilha de operações.'}, job.version);
      await this.loadJobs();
      this.attempts.set(null);
      this.feedback.set('Retomada solicitada. Atualize os trabalhos para acompanhar a execução.');
    });
  }

  filterRetention(event: Event, date: string) {
    event.preventDefault();
    return this.perform(async () => {
      this.retention.set(await this.read<Retention>('/retention' + (date ? '?asOf=' + encodeURIComponent(date) : '')));
    });
  }

  correct(event: Event, current: Retention, endsOn: string, reason: string) {
    event.preventDefault();
    return this.perform(async () => {
      await this.post('/retention/credit-end', {endsOn, reason, proof: {id: 'SIM-FIM-CREDITO', version: 1}}, current.creditEnd.version);
      this.retention.set(await this.read<Retention>('/retention?asOf=' + current.asOf));
      this.feedback.set('Fim de crédito e piso de guarda revisados.');
    });
  }

  replay(run: Recovery['runs'][number]) {
    return this.perform(async () => {
      const result = await this.post<{matches: boolean}>('/runs/' + run.id + '/reexecute', {});
      this.recovery.set(await this.read<Recovery>('/recovery'));
      if (!result.matches) throw new Error('A memória recalculada divergiu do snapshot salvo.');
      this.feedback.set('Memória reexecutada e conferida.');
    });
  }

  check(event: Event, state: Recovery, reason: string, release: boolean) {
    event.preventDefault();
    return this.perform(async () => {
      this.recovery.set(await this.post<Recovery>(
        '/recovery/' + state.recovery.id + (release ? '/release' : '/check'), {gapSha: state.recovery.gapSha, reason}, state.version,
      ));
      this.feedback.set(release ? 'Recuperação conferida. A guarda temporária de emissão foi retirada.' : 'Conferência registrada. Aguarde a decisão do REV.');
    });
  }

  private async loadJobs() {
    this.jobs.set(await this.read<Job[]>('/jobs' + (this.period() ? '?period=' + this.period() : '')));
  }

  private base() { return '/v1/projects/' + encodeURIComponent(this.project().project); }
  private read<T>(path: string) { return firstValueFrom(this.http.get<T>(this.base() + path)); }

  private async post<T>(path: string, body: object, version?: number) {
    const fingerprint = JSON.stringify([path, body, version]);
    const key = this.retryKeys.get(fingerprint) ?? crypto.randomUUID();
    this.retryKeys.set(fingerprint, key);
    const headers: Record<string, string> = {'Idempotency-Key': key};
    if (version !== undefined) headers['If-Match'] = '"' + version + '"';
    try {
      const result = await firstValueFrom(this.http.post<T>(this.base() + path, body, {headers}));
      this.retryKeys.delete(fingerprint);
      return result;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) this.retryKeys.delete(fingerprint);
      throw error;
    }
  }

  private async perform(work: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.failed.set(false);
    this.feedback.set('');
    try { await work(); }
    catch (error) {
      this.failed.set(true);
      this.feedback.set(error instanceof HttpErrorResponse ? error.error?.message ?? 'Falha na operação.'
        : error instanceof Error ? error.message : 'Falha na operação.');
    } finally { this.busy.set(false); }
  }
}
