import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

interface StaffSession {verified: boolean; expiresAt: string | null}
interface Review {outcome: string; basis: string; reason: string; decidedAt: string}
/** Mirrors GridSeries.Edition. The series is always a candidate: never admitted, never usable for E8. */
interface Edition {
  id: string; sourceId: string; edition: string; licenseBasis: 'OWNER_INTERNAL' | 'INDEPENDENT'; workbookSha256: string;
  gas: string; originalUnit: string; targetUnit: string; dataYear: number; timezone: string; hourConvention: string;
  timeConventionSource: string; rowCount: number; timeIssueCount: number; importedAt: string;
  quality: {months?: Record<string, {state: string; expectedCells: number; importedCells: number}>};
  internalReview: Review | null; independentReview: Review | null; state: string; usableForE8: boolean;
  eligibilityVt0011Tool07: string; v60Binding: {state: string; blocker: string}; limits: string;
}
interface Listing {canImport: boolean; canReviewInternal: boolean; canReviewIndependent: boolean; editions: Edition[]; limits: string}
interface SeriesValue {day: number; hourLabel: number; cell: string; valueOriginal: string; intervalStartUtc: string | null; timeIssue: string | null}

const OUTCOME: Record<string, string> = {CHECKED: 'Conferida', RETURNED: 'Devolvida'};
const BASIS: Record<string, string> = {OWNER_INTERNAL: 'interna do responsável', INDEPENDENT: 'independente'};
const LICENSE: Record<string, string> = {
  OWNER_INTERNAL: 'declaração interna do responsável (sem aprovação independente da licença)',
  INDEPENDENT: 'licença aprovada de forma independente',
};

/**
 * Administration of the platform hourly SIN series (roteiro #124 PR 2). It imports the output of the offline importer,
 * keeps the original values, shows that the time zone and hour convention were declared by people, and records reviews
 * on two separate bases. Nothing here admits a factor or binds the series to a project; the server decides every rule.
 */
@Component({
  selector: 'carbonflow-platform-grid-series',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './platform-grid-series.html',
  styleUrl: './platform-source-readiness.css',
})
export class PlatformGridSeriesComponent {
  private readonly http = inject(HttpClient);
  private readonly api = '/v1/platform/grid-series';
  readonly session = signal<StaffSession | null>(null);
  readonly listing = signal<Listing | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly feedback = signal('');
  readonly denied = signal(false);
  readonly values = signal<{id: string; month: number; items: SeriesValue[]} | null>(null);
  readonly unlocked = computed(() => {
    if (this.denied()) return false;
    const session = this.session();
    return !!session?.verified && !!session.expiresAt && Date.parse(session.expiresAt) > Date.now();
  });
  reviewBasis: 'OWNER_INTERNAL' | 'INDEPENDENT' = 'OWNER_INTERNAL'; reviewOutcome = 'CHECKED'; reviewReason = '';

  constructor() { void this.loadSession(); }

  licenseLabel(item: Edition): string { return LICENSE[item.licenseBasis] ?? item.licenseBasis; }
  reviewText(review: Review | null): string {
    return review ? (OUTCOME[review.outcome] ?? review.outcome) + ' (' + review.decidedAt.slice(0, 10) + ')' : 'não revisada';
  }
  basisLabel(value: string): string { return BASIS[value] ?? value; }
  months(item: Edition): {month: string; state: string; cells: string}[] {
    return Object.entries(item.quality?.months ?? {}).map(([month, q]) => ({month, state: q.state, cells: q.importedCells + ' de ' + q.expectedCells}));
  }

  private async loadSession() {
    this.loading.set(true);
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
      this.listing.set(await firstValueFrom(this.http.get<Listing>(this.api)));
    } catch (reason) {
      this.listing.set(null);
      if (reason instanceof HttpErrorResponse && reason.status === 404) this.denied.set(true);
      this.error.set(this.message(reason, 'Não foi possível consultar as séries.'));
    }
  }

  /** Reads the importer's JSON in the browser and sends it unchanged with the source and edition the person names. */
  async importFile(event: Event, sourceId: string, edition: string, file: HTMLInputElement) {
    event.preventDefault();
    if (this.saving()) return;
    const chosen = file.files?.[0];
    if (!chosen) { this.error.set('Escolha o arquivo JSON gerado pelo importador.'); return; }
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      const body = JSON.parse(await chosen.text());
      await firstValueFrom(this.http.post<Edition>(this.api, {...body, sourceId: sourceId.trim(), edition: edition.trim()}));
      this.feedback.set('Série importada como candidata: não admitida, sem vínculo com projeto.');
      await this.load();
    } catch (reason) {
      const code = reason instanceof HttpErrorResponse ? reason.error?.code : null;
      this.error.set(code === 'LICENCA_AUSENTE_PARA_OPERACAO' ? 'Não há licença efetiva para extrair os dados desta fonte e edição. Só esta operação está bloqueada; declare ou aprove a licença na tela de licenças.'
        : code === 'ARQUIVO_NAO_CORRESPONDE_A_EDICAO' ? 'O SHA-256 do arquivo importado não é o da edição registrada.'
        : code === 'SERIE_JA_IMPORTADA' ? 'Esta série, com os mesmos valores e parâmetros de tempo, já foi importada.'
        : code === 'FONTE_INEXISTENTE' ? 'A fonte e a edição informadas não estão registradas neste banco. Confira o código e a edição digitados (MCTI-SIN-DESPACHO e 2026-janout-7f8e2292, sem espaços); a fonte vem da migração V112, então o app local precisa estar atualizado.'
        : reason instanceof SyntaxError ? 'O arquivo não é um JSON válido.'
        : this.detail(reason) ?? this.message(reason, 'Não foi possível importar a série.'));
    } finally {
      this.saving.set(false);
    }
  }

  async showMonth(item: Edition, month: string) {
    this.error.set('');
    try {
      const items = await firstValueFrom(this.http.get<SeriesValue[]>(this.api + '/' + item.id + '/values?month=' + month));
      this.values.set({id: item.id, month: Number(month), items});
    } catch (reason) {
      this.error.set(this.message(reason, 'Não foi possível consultar os valores.'));
    }
  }

  async review(event: Event, item: Edition) {
    event.preventDefault();
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      await firstValueFrom(this.http.post<Edition>(this.api + '/' + item.id + '/reviews',
        {basis: this.reviewBasis, outcome: this.reviewOutcome, reason: this.reviewReason.trim()}));
      this.feedback.set('Revisão ' + this.basisLabel(this.reviewBasis) + ' registrada. A série continua não admitida.');
      this.reviewReason = '';
      await this.load();
    } catch (reason) {
      const code = reason instanceof HttpErrorResponse ? reason.error?.code : null;
      this.error.set(code === 'DUPLA_CUSTODIA' ? 'Quem importou a série não pode revisá-la como revisor independente.'
        : code === 'BASE_NAO_AUTORIZADA' ? 'Seu perfil não pode registrar revisão nesta base.'
        : this.message(reason, 'Não foi possível registrar a revisão.'));
    } finally {
      this.saving.set(false);
    }
  }

  /** Shows what the server said (code and offending field) instead of a generic hint. */
  private detail(reason: unknown): string | null {
    if (!(reason instanceof HttpErrorResponse) || reason.status !== 422) return null;
    const paths: string[] = (reason.error?.issues ?? []).map((i: {path?: string}) => i.path).filter(Boolean);
    const code = reason.error?.code;
    return code ? 'Recusado pelo servidor (' + code + (paths.length ? ', campo: ' + paths.join(', ') : '') + ').' : null;
  }

  private message(reason: unknown, fallback: string): string {
    const status = reason instanceof HttpErrorResponse ? reason.status : 0;
    if (status === 404) return 'As séries da rede são exclusivas da equipe interna autorizada. Solicite o privilégio específico.';
    if (status === 403) return 'Confirme o acesso com o autenticador ou use um perfil com este privilégio.';
    if (status === 422) return 'Confira a saída do importador: SHA-256, gás CO2, ano, fuso, convenção da hora, unidades e registros únicos.';
    return fallback;
  }
}
