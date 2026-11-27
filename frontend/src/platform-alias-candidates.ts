import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

interface StaffSession {verified: boolean; expiresAt: string | null}
interface PbevMatch {brand: string; model: string; version: string | null; category: string; pbevLine: number}
interface Risk {code: string; text: string}
interface RightsCheck {role: string; sha256: string; operation: string; registered: boolean}
interface DryRun {importable: boolean; rightsGateEnabled: boolean; rights: RightsCheck[]; newItems: number; alreadyPresent: number}

/** Mirrors FleetAliasCandidates.Candidate. The state is what the server derived from the single decision. */
interface Candidate {
  id: string;
  observationPeriod: string;
  literalName: string;
  recordKey: string;
  level: 'EXACT' | 'MODEL_ONLY' | 'AMBIGUOUS';
  pbevSourceSha256: string;
  matches: PbevMatch[];
  reason: string;
  importedBy: string;
  state: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  decidedBy: string | null;
  selectedPbevLine: number | null;
  decisionReason: string | null;
  decisionBasis: 'INDEPENDENT' | 'OWNER_INTERNAL' | null;
  senatranSourceSha256: string | null;
  risks: Risk[];
  limits: string;
}

const LEVELS: Record<string, string> = {
  EXACT: 'Nome idêntico ao do PBEV', MODEL_ONLY: 'Só o modelo coincide; a versão fica a decidir',
  AMBIGUOUS: 'Mais de uma opção equivalente',
};
const BASES: Record<string, string> = {
  INDEPENDENT: 'Revisão independente', OWNER_INTERNAL: 'Decisão interna do responsável (não é revisão independente)',
};
const STATES: Record<string, string> = {PENDING: 'Aguardando revisão', ACCEPTED: 'Aceito', REJECTED: 'Recusado'};
const FILTERS: {value: string; label: string}[] = [
  {value: '', label: 'Todos'}, {value: 'PENDING', label: 'Pendentes'},
  {value: 'ACCEPTED', label: 'Aceitos'}, {value: 'REJECTED', label: 'Recusados'},
];

/**
 * Review queue of alias candidates between literal SENATRAN names and electric rows of the Inmetro PBEV table
 * (#94/#95). A candidate is evidence for a reviewer, never an alias: accepting does not create a model or an alias,
 * does not classify the vehicle and does not admit data or enable calculation. The server decides every state.
 */
@Component({
  selector: 'carbonflow-platform-alias-candidates',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './platform-alias-candidates.html',
  styleUrl: './platform-source-readiness.css',
})
export class PlatformAliasCandidatesComponent {
  private readonly http = inject(HttpClient);
  private readonly api = '/v1/platform/fleet/alias-candidates';
  readonly filters = FILTERS;
  readonly session = signal<StaffSession | null>(null);
  readonly items = signal<Candidate[]>([]);
  readonly filter = signal('PENDING');
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly feedback = signal('');
  readonly denied = signal(false);
  readonly deciding = signal<string | null>(null);
  readonly line = signal<number | null>(null);
  readonly basis = signal<'INDEPENDENT' | 'OWNER_INTERNAL'>('INDEPENDENT');
  readonly acknowledged = signal<string[]>([]);
  readonly dryRun = signal<DryRun | null>(null);
  readonly unlocked = computed(() => {
    if (this.denied()) return false;
    const session = this.session();
    return !!session?.verified && !!session.expiresAt && Date.parse(session.expiresAt) > Date.now();
  });

  constructor() { void this.loadSession(); }

  levelLabel(item: Candidate): string { return LEVELS[item.level] ?? item.level; }
  stateLabel(item: Candidate): string { return STATES[item.state] ?? item.state; }
  basisLabel(item: Candidate): string { return item.decisionBasis ? (BASES[item.decisionBasis] ?? item.decisionBasis) : ''; }
  shortHash(item: Candidate): string { return item.pbevSourceSha256.slice(0, 12); }
  /** EXACT only informs; MODEL_ONLY and AMBIGUOUS need every risk acknowledged before accepting (the server enforces it too). */
  risksAcknowledged(item: Candidate): boolean {
    return item.level === 'EXACT' || item.risks.every(risk => this.acknowledged().includes(risk.code));
  }
  toggleRisk(code: string, checked: boolean) {
    this.acknowledged.update(list => checked ? [...new Set([...list, code])] : list.filter(entry => entry !== code));
  }
  chosen(item: Candidate): PbevMatch | undefined {
    return item.matches.find(match => match.pbevLine === item.selectedPbevLine);
  }

  private async loadSession() {
    this.loading.set(true);
    this.error.set('');
    try {
      const session = await firstValueFrom(this.http.get<StaffSession>('/v1/compliance/session'));
      this.session.set(session);
      if (session.verified) await this.load();
    } catch (reason) {
      this.session.set(null);
      this.error.set(this.message(reason, 'Não foi possível consultar a sessão. Confira o acesso e tente novamente.'));
    } finally {
      this.loading.set(false);
    }
  }

  async verify(event: Event, code: HTMLInputElement) {
    event.preventDefault();
    if (this.loading()) return;
    const value = code.value;
    code.value = '';
    this.loading.set(true);
    this.error.set('');
    try {
      const result = await firstValueFrom(this.http.post<StaffSession>('/v1/compliance/session', {code: value}));
      this.session.set(result);
      if (result.verified) await this.load();
    } catch (reason) {
      this.session.set({verified: false, expiresAt: null});
      const status = reason instanceof HttpErrorResponse ? reason.status : 0;
      this.error.set(status === 429 ? 'Limite de tentativas. Tente novamente após a janela de segurança.'
        : status === 403 ? 'Código inválido ou já utilizado.' : 'Não foi possível verificar o acesso.');
    } finally {
      this.loading.set(false);
    }
  }

  async load() {
    this.error.set('');
    this.denied.set(false);
    try {
      const query = this.filter() ? '?state=' + this.filter() : '';
      this.items.set(await firstValueFrom(this.http.get<Candidate[]>(this.api + query)));
    } catch (reason) {
      this.items.set([]);
      if (reason instanceof HttpErrorResponse && reason.status === 404) this.denied.set(true);
      this.error.set(this.message(reason, 'Não foi possível consultar os candidatos.'));
    }
  }

  async setFilter(event: Event) {
    this.filter.set((event.target as HTMLSelectElement).value);
    this.cancel();
    await this.load();
  }

  async importBatch(event: Event, area: HTMLTextAreaElement) {
    event.preventDefault();
    if (this.saving()) return;
    let body: unknown;
    try {
      body = JSON.parse(area.value);
    } catch {
      this.error.set('O lote não é um JSON válido. Cole a saída de match_senatran_pbev.py --emit-import.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    this.dryRun.set(null);
    try {
      const result = await firstValueFrom(this.http.post<{created: number; alreadyPresent: number}>(this.api, body));
      this.feedback.set('Lote importado: ' + result.created + ' novo(s) candidato(s), ' + result.alreadyPresent
        + ' já existente(s). Nenhum alias foi criado.');
      area.value = '';
      await this.load();
    } catch (reason) {
      const blocked = reason instanceof HttpErrorResponse && reason.status === 403 && reason.error?.code === 'DIREITOS_NAO_REGISTRADOS';
      this.error.set(blocked ? String(reason.error.message) : this.message(reason, 'Não foi possível importar o lote.'));
    } finally {
      this.saving.set(false);
    }
  }

  /** Writes nothing: reports new/existing items and whether both sources have independently approved rights. */
  async simulate(area: HTMLTextAreaElement) {
    if (this.saving()) return;
    let body: unknown;
    try {
      body = JSON.parse(area.value);
    } catch {
      this.error.set('O lote não é um JSON válido. Cole a saída de prepare_alias_review.py prepare.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      this.dryRun.set(await firstValueFrom(this.http.post<DryRun>(this.api + '/dry-run', body)));
    } catch (reason) {
      this.dryRun.set(null);
      this.error.set(this.message(reason, 'Não foi possível simular a importação.'));
    } finally {
      this.saving.set(false);
    }
  }

  open(item: Candidate) {
    this.deciding.set(item.id);
    this.line.set(item.matches.length === 1 ? item.matches[0].pbevLine : null);
    this.basis.set('INDEPENDENT');
    this.acknowledged.set([]);
    this.error.set('');
    this.feedback.set('');
  }

  cancel() { this.deciding.set(null); this.line.set(null); }

  pick(value: number) { this.line.set(value); }

  setBasis(value: 'INDEPENDENT' | 'OWNER_INTERNAL') { this.basis.set(value); }

  async decide(item: Candidate, outcome: 'ACCEPTED' | 'REJECTED', reason: string) {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      await firstValueFrom(this.http.post<Candidate>(this.api + '/' + item.id + '/decision', {
        outcome, reason: reason.trim(), selectedPbevLine: outcome === 'ACCEPTED' ? this.line() : null, basis: this.basis(),
        acknowledgedRisks: outcome === 'ACCEPTED' ? this.acknowledged() : [],
      }));
      this.feedback.set(outcome === 'ACCEPTED'
        ? 'Candidato aceito. Nenhum modelo nem alias foi criado: o cadastro segue pelas rotas do catálogo.'
        : 'Candidato recusado.');
      this.cancel();
      await this.load();
    } catch (reason) {
      const status = reason instanceof HttpErrorResponse ? reason.status : 0;
      const code = reason instanceof HttpErrorResponse ? reason.error?.code : null;
      this.error.set(status === 403 && code === 'DUPLA_CUSTODIA' ? 'Quem importou o candidato não pode decidir como revisor independente. Peça a outra pessoa ou, se este é o seu papel, use a decisão interna do responsável.'
        : status === 403 && code === 'BASE_NAO_AUTORIZADA' ? 'Seu perfil não está autorizado a decidir nesta base. Escolha a outra base ou peça o privilégio.'
        : status === 409 ? 'Este candidato já tem decisão. Atualize a lista.'
        : status === 422 && code === 'RISCOS_NAO_RECONHECIDOS' ? 'Reconheça cada risco do casamento por nome antes de aceitar.'
        : this.message(reason, 'Não foi possível registrar a decisão.'));
    } finally {
      this.saving.set(false);
    }
  }

  private message(reason: unknown, fallback: string): string {
    const status = reason instanceof HttpErrorResponse ? reason.status : 0;
    if (status === 404) return 'A fila de candidatos é exclusiva da equipe interna autorizada. Solicite o privilégio específico.';
    if (status === 403) return 'Confirme o acesso com o autenticador.';
    if (status === 422) return 'Confira o lote (período AAAA-MM, SHA-256 do PBEV, nomes, chaves, níveis e linhas) ou, na decisão, a linha escolhida e o motivo (mín. 10 caracteres).';
    return fallback;
  }
}
