import {Component, computed, inject, input, OnInit, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {SessionProject} from './access-panel';

interface Vintage {start: string; endExclusive: string}
interface Line {vintage: Vintage; amount: number}
interface Position {
  id: string; vintage: Vintage; official: number; asOf: string; historical: boolean;
  pending: {reference: string | null; issuance: string | null; amount: number; status: string}[];
  capture: Capture;
}
interface Balance {position: Position | null; unreflected: number | null; available: number | null; hold: string | null}
interface Capture {effectiveAt: string; proof: {officialId: string}}
interface Verification {
  id: string; kind: string; requestTarget: string; approvalId: string; closureEligible: boolean;
  cuts: Vintage[]; closure: {period: string; revision: number};
  activation: {version: number; registryId: string; active: boolean; capture: Capture} | null;
  balances: Balance[];
}
interface Attempt {
  id: string; version: number; state: string; verification: string; predecessor: string | null;
  account: string; externalTarget: string | null; lastAt: string; releasedAt: string | null;
  lines: Line[]; check: {input: {route: string}} | null; invoice: {capture: Capture; amount: number} | null;
  payment: Capture | null; deposits: {capture: Capture; lines: Line[]}[];
  adjustments: {capture: Capture; corrects: string; line: Line}[];
  incidents: {id: string; code: string; reason: string; reopen: string | null; resolutionEvent: string | null}[];
  comparison: {id: string; preparedBy: string; lines: {vintage: Vintage; official: number; available: number; reserved: number}[]} | null;
}
interface Snapshot {attempt: Attempt; netDeposited: Line[]}
interface Ref {id: string; version: number}
interface Options {accounts: string[]; unit: string; routes: string[]; proofs: Record<string,string>; power: Ref; resolution: Ref}
interface RecoveryStatus {
  version: number;
  effects: {namespace: string; reference: string; status: string; binding: {verification: string; target: string} | null}[];
}
interface Trail {id: string; actor: string; action: string; outcome: string}

const steps: Record<string, string> = {
  'TX-E3-49': 'Vincular recibo do protocolo',
  'TX-E3-50': 'Registrar cobrança',
  'TX-E3-51': 'Comprovar quitação da cobrança',
  'TX-E3-52': 'Registrar depósito',
  'TX-E3-53': 'Conciliar após comparação',
  'TX-E3-54': 'Registrar divergência',
  'TX-E3-55': 'Comprovar retomada',
  'TX-E3-56': 'Comprovar encerramento sem emissão',
  'TX-E3-57': 'Comprovar quitação oficial ou dispensa',
  AJUSTE: 'Registrar ajuste de depósito',
  LIBERAR_REMANESCENTE: 'Comprovar encerramento do remanescente',
  COMPARAR: 'Comparar prova, depósito e posição',
};

const labels: Record<string, string> = {
  EM_PREPARO: 'Em preparo', PROTOCOLADO: 'Protocolado', AGUARDANDO_PAGAMENTO: 'Aguardando pagamento',
  AGUARDANDO_DEPOSITO: 'Aguardando depósito', DEPOSITO_REGISTRADO: 'Depósito registrado',
  COM_DIVERGENCIA: 'Com divergência', CONCILIADO: 'Conciliado', ENCERRADO_SEM_EMISSAO: 'Encerrado sem emissão',
  POSICAO_AUSENTE: 'Registre o primeiro extrato', CONFLITO_POSICAO: 'Aguardando extrato conclusivo',
  ORDEM_POSICAO_INDETERMINADA: 'Os extratos divergem e a ordem oficial está indefinida',
  REFLEXO_INCERTO: 'Confirme se a pendência já consta do saldo oficial',
  DIVERGENCIA_PENDENTE: 'Há divergência de emissão pendente', POSICAO_A_ATUALIZAR: 'Atualize a posição oficial',
  POSICAO_INSUFICIENTE: 'O extrato é insuficiente para as reservas registradas',
  ATO_CONFLITANTE: 'A identidade oficial já está vinculada a outro conteúdo ou alvo',
  ALVO_OU_CONTA_DIVERGENTE: 'O alvo ou a conta diverge do protocolo',
  DEPOSITO_DIVERGENTE: 'Confira etapa, quantidade e vintage do depósito', AJUSTE_DIVERGENTE: 'Confira o vínculo e a quantidade do ajuste',
  FECHAMENTO_REABERTO: 'Fechamento reaberto',
};

@Component({selector: 'carbonflow-issuance', templateUrl: './issuance-panel.html'})
export class IssuancePanel implements OnInit {
  readonly project = input.required<SessionProject>();
  readonly canCapture = computed(() => this.project().roles.includes('PP'));
  readonly canCompare = computed(() => this.project().roles.includes('PREP'));
  readonly canReview = computed(() => this.project().roles.includes('REV'));
  readonly enabled = computed(() => this.canCapture() || this.canCompare() || this.canReview());
  readonly options = signal<Options | null>(null);
  readonly verifications = signal<Verification[]>([]);
  readonly snapshots = signal<Snapshot[]>([]);
  readonly selected = signal('');
  readonly recovery = signal<RecoveryStatus | null>(null);
  readonly restoring = computed(() => (this.recovery()?.version ?? 3) < 3);
  readonly protocols = computed(() => this.recovery()?.effects.filter(effect => effect.namespace === 'SIM-RECIBOS-EMISSAO'
    && effect.status === 'A_CAPTURAR' && effect.binding?.verification === this.selection()?.activation?.registryId) ?? []);
  readonly selectedAttempt = signal('');
  readonly selection = computed(() => this.verifications().find(item => item.id === this.selected()));
  readonly attempts = computed(() => this.snapshots().filter(item => item.attempt.verification === this.selected()));
  readonly current = computed(() => this.attempts().find(item => item.attempt.id === this.selectedAttempt()));
  readonly predecessors = computed(() => this.attempts().filter(({attempt}) =>
    attempt.releasedAt && ['CONCILIADO', 'ENCERRADO_SEM_EMISSAO'].includes(attempt.state)));
  readonly orderedActions = computed(() => {
    const current = this.current();
    return current ? this.actions(current.attempt) : [];
  });
  readonly chosenAction = signal('');
  readonly action = computed(() => this.orderedActions().includes(this.chosenAction()) ? this.chosenAction() : this.orderedActions()[0] ?? '');
  readonly pendingCount = signal(0);
  readonly pendingIndexes = computed(() => Array.from({length: this.pendingCount()}, (_, index) => index));
  readonly addPending = (count: number) => count + 1;
  readonly removePending = (count: number) => count - 1;
  readonly positions = signal<Position[] | null>(null);
  readonly trail = signal<Trail[] | null>(null);
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly failed = signal(false);
  private readonly http = inject(HttpClient);
  private readonly keys = new Map<string, string>();

  ngOnInit() { if (this.enabled()) void this.refresh(); }
  refresh() { return this.perform(() => this.load()); }
  label(code: string) { return labels[code] ?? steps[code] ?? code; }
  cut(vintage: Vintage) { return vintage.start + ' a ' + vintage.endExclusive + ' (fim exclusivo)'; }
  date(instant: string) { return new Date(Date.parse(instant) - 3 * 3600000).toISOString().slice(0, 16); }
  hasAvailable(verification: Verification) { return verification.balances.some(balance => (balance.available ?? 0) > 0); }
  proofFor(action: string) { return this.options()?.proofs[action]; }
  needsAmounts(action: string) { return ['TX-E3-52', 'AJUSTE'].includes(action); }
  needsReason(action: string) { return ['TX-E3-54', 'TX-E3-55', 'TX-E3-56', 'LIBERAR_REMANESCENTE'].includes(action); }
  canResolve(action: string) { return ['TX-E3-55', 'TX-E3-57', 'LIBERAR_REMANESCENTE', 'COMPARAR'].includes(action); }

  chooseVerification(value: string) {
    this.selected.set(value);
    this.positions.set(null);
    this.pendingCount.set(0);
    this.chooseAttempt(this.attempts()[0]?.attempt.id ?? '');
  }

  chooseAttempt(value: string) {
    this.selectedAttempt.set(value);
    this.chosenAction.set('');
    this.trail.set(null);
  }

  activate(event: Event, verification: Verification) {
    const form = this.form(event);
    const registryId = this.value(form, 'registry');
    return this.update('/verifications/' + verification.id + '/activation-evidence', {
      registryId, approvalId: verification.approvalId, effectiveAt: this.at(form), active: this.value(form, 'active') === '1',
      proof: this.proof('ATIVACAO', form, verification.requestTarget),
    }, 'Situação da ativação registrada.', verification.activation?.version ?? 0);
  }

  savePosition(event: Event, verification: Verification) {
    const form = this.form(event);
    const pending = Array.from({length: this.pendingCount()}, (_, index) => {
      const origin = this.value(form, 'origin-' + index);
      return {
        reference: origin === 'external' ? this.value(form, 'reference-' + index) : null,
        issuance: origin === 'external' ? null : origin,
        amount: this.value(form, 'pending-' + index), status: this.value(form, 'status-' + index),
      };
    });
    const order = this.value(form, 'order');
    return this.perform(async () => {
      if (!verification.activation) throw new Error('Comprove primeiro a situação no registro.');
      const result = await this.post<{position: Position}>('/verifications/' + verification.id + '/positions', {
        vintage: verification.cuts[Number(form.get('cut'))], unit: this.options()?.unit, official: this.value(form, 'official-balance'),
        pending, asOf: this.at(form), order: order ? Number(order) : null,
        proof: this.proof('POSICAO', form, verification.activation.registryId),
      });
      this.positions.set(null);
      await this.load();
      this.feedback.set(result.position.historical ? 'Extrato guardado no histórico. A posição vigente segue exibida.' : 'Posição atualizada.');
    });
  }

  create(event: Event, verification: Verification) {
    const form = this.form(event);
    const lines = verification.balances.flatMap(balance => {
      const position = balance.position;
      const amount = position ? this.value(form, position.id) : '';
      return position && amount ? [{vintage: position.vintage, position: position.id, amount}] : [];
    });
    return this.perform(async () => {
      if (!lines.length) throw new Error('Informe a quantidade em pelo menos um recorte disponível.');
      const result = await this.post<Snapshot>('/issuances', {
        verification: verification.id, predecessor: this.value(form, 'predecessor') || null,
        account: this.value(form, 'account'), unit: this.options()?.unit, lines, effectiveAt: this.at(form),
        recoveryProtocol: this.value(form, 'recovery-protocol') || null,
      });
      await this.load();
      this.chooseAttempt(result.attempt.id);
      this.feedback.set('Pedido em preparo. Confira o canal e o poder antes de vincular o protocolo.');
    });
  }

  check(event: Event, attempt: Attempt) {
    const form = this.form(event);
    const route = this.value(form, 'route');
    return this.update('/issuances/' + attempt.id + '/channel-checks', {
      route, checkedAt: this.at(form), powerUntilExclusive: this.at(form, 'power-until'),
      power: this.options()?.power, source: this.ref('SIM-CANAL-' + route.replaceAll('_', '-')),
    }, 'Conferência do canal e do poder registrada.', attempt.version);
  }

  register(event: Event, attempt: Attempt, step: string) {
    const form = this.form(event);
    const target = attempt.externalTarget ?? this.value(form, 'target');
    const lines = step === 'TX-E3-50' ? attempt.lines.map(({vintage, amount}) => ({vintage, amount})) : this.needsAmounts(step)
      ? attempt.lines.flatMap((line, index) => {
        const amount = this.value(form, 'line-' + index);
        return amount ? [{vintage: line.vintage, amount}] : [];
      }) : null;
    const feeAmount = step === 'TX-E3-50' ? this.value(form, 'fee') : step === 'TX-E3-51' ? attempt.invoice?.amount : null;
    return this.update('/issuances/' + attempt.id + '/actions', {
      transitionId: step, mode: 'SIMULACAO', effectiveAt: this.at(form), target,
      account: this.value(form, 'account') || attempt.account, proof: this.proof(step, form, target), lines, feeAmount,
      currency: feeAmount ? 'BRL' : null, reason: this.value(form, 'reason') || null,
      referenceAct: step === 'TX-E3-51' ? attempt.invoice?.capture.proof.officialId : this.value(form, 'reference-act') || null,
      support: step === 'TX-E3-54' ? this.ref('SIM-PROVA-DIVERGENCIA-EMISSAO') : form.has('resolution') ? this.options()?.resolution : null,
      captureChannel: this.value(form, 'capture-channel') || null,
      comparison: step === 'TX-E3-53' ? attempt.comparison?.id : null,
    }, 'Etapa registrada. Confira o estado e a posição atualizados.', attempt.version);
  }

  showPositions(verification: Verification) {
    return this.perform(async () => this.positions.set(await this.read<Position[]>('/verifications/' + verification.id + '/positions')));
  }

  showTrail(attempt: Attempt) {
    return this.perform(async () => this.trail.set(await this.read<Trail[]>('/issuances/' + attempt.id + '/history')));
  }

  private actions(item: Attempt): string[] {
    const state = item.state;
    const ids: string[] = [];
    if (this.canCapture()) {
      if (state === 'EM_PREPARO' && item.check) ids.push('TX-E3-49');
      if (state === 'PROTOCOLADO') ids.push('TX-E3-50', 'TX-E3-57');
      if (state === 'AGUARDANDO_PAGAMENTO') ids.push('TX-E3-51');
      if (item.payment && !item.releasedAt && ['AGUARDANDO_DEPOSITO', 'DEPOSITO_REGISTRADO', 'COM_DIVERGENCIA', 'CONCILIADO'].includes(state))
        ids.push('TX-E3-52');
      if (item.deposits.length) {
        ids.push('AJUSTE');
        if (!item.releasedAt) ids.push('LIBERAR_REMANESCENTE');
      } else {
        if (state === 'COM_DIVERGENCIA') {
          if (!item.payment) ids.push('TX-E3-55');
          ids.push('TX-E3-57');
        }
        if (['PROTOCOLADO', 'AGUARDANDO_PAGAMENTO', 'AGUARDANDO_DEPOSITO', 'COM_DIVERGENCIA'].includes(state)) ids.push('TX-E3-56');
      }
    }
    if (this.canCompare() && item.deposits.length && ['DEPOSITO_REGISTRADO', 'COM_DIVERGENCIA'].includes(state)) ids.push('COMPARAR');
    if (this.canReview() && !['ENCERRADO_SEM_EMISSAO', 'EM_PREPARO'].includes(state)) {
      if (item.comparison && state !== 'CONCILIADO') ids.push('TX-E3-53');
      ids.push('TX-E3-54');
    }
    return ids;
  }

  private form(event: Event) { event.preventDefault(); return new FormData(event.currentTarget as HTMLFormElement); }
  private value(form: FormData, name: string) { return String(form.get(name) ?? '').trim(); }
  private at(form: FormData, name = 'date') { const value = this.value(form, name); return value ? value + ':00-03:00' : null; }
  private ref(id: string): Ref { return {id, version: 1}; }
  private proof(action: string, form: FormData, target: string) {
    const id = this.proofFor(action);
    return id ? {officialId: this.value(form, 'official-id'), target, evidence: this.ref(id)} : null;
  }
  private base() { return '/v1/projects/' + encodeURIComponent(this.project().project); }
  private read<T>(path: string) { return firstValueFrom(this.http.get<T>(this.base() + path)); }

  private async load() {
    const [options, verifications, attempts, recovery] = await Promise.all([
      this.read<Options>('/issuances/options'), this.read<Verification[]>('/verifications'), this.read<Snapshot[]>('/issuances'),
      this.read<RecoveryStatus | null>('/recovery'),
    ]);
    this.options.set(options);
    this.recovery.set(recovery);
    this.verifications.set(verifications);
    this.snapshots.set(attempts);
    if (!this.selection()) this.chooseVerification(verifications[0]?.id ?? '');
    if (!this.current()) this.chooseAttempt(this.attempts()[0]?.attempt.id ?? '');
  }

  private update(path: string, body: object, message: string, version?: number) {
    return this.perform(async () => {
      await this.post(path, body, version);
      this.trail.set(null);
      await this.load();
      this.chosenAction.set('');
      this.feedback.set(message);
    });
  }

  private async post<T>(path: string, body: object, version?: number) {
    const fingerprint = JSON.stringify([path, body, version]);
    const key = this.keys.get(fingerprint) ?? crypto.randomUUID();
    this.keys.set(fingerprint, key);
    const headers: Record<string,string> = {'Idempotency-Key': key};
    if (version !== undefined) headers['If-Match'] = '"' + version + '"';
    try {
      const result = await firstValueFrom(this.http.post<T>(this.base() + path, body, {headers}));
      this.keys.delete(fingerprint);
      return result;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) {
        this.keys.delete(fingerprint);
        if (error.status === 412 || error.error?.conflict) await this.load().catch(() => undefined);
      }
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
      this.feedback.set(error instanceof HttpErrorResponse
        ? error.error?.message ?? (error.error?.conflict ? this.label(error.error.conflict) : 'Falha ao registrar a etapa.')
        : error instanceof Error ? error.message : 'Falha na operação.');
    } finally { this.busy.set(false); }
  }
}
