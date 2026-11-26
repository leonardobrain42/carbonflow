import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

interface StaffSession {verified: boolean; expiresAt: string | null}
interface Fuel {id: string; name: string; family: string; role: string; phase: string; pending: string[]}
interface Review {outcome: string; basis: string; reason: string; decidedAt: string}
interface Qaqc {state: string; relativeDeviation: string | null; note: string}
/** Mirrors FuelCatalog.Entry. The catalog never admits anything: admission is always NOT_ADMITTED. */
interface Entry {
  id: string; kind: 'COMPOSITION' | 'DATUM'; fuelId: string; componentId: string | null; compositionBasis: string | null;
  nominal: string | null; remainder: boolean; parameter: string | null; gas: string | null; emissionScope: string | null;
  gwpSet: string | null; value: string | null; unit: string | null; vintageYear: number | null;
  validFrom: string; validTo: string | null; evidenceSource: string; evidenceLocator: string; readState: string;
  internalReview: Review | null; independentReview: Review | null; qaqc: Qaqc | null; admission: string;
}
interface Catalog {
  canRegister: boolean; canReviewInternal: boolean; canReviewIndependent: boolean;
  fuels: Fuel[]; entries: Entry[]; admission: string; limits: string;
}
interface Boundary {id: string; name: string; baselineGases: string[]; projectGases: string[]; note: string; source: string; status: string}
interface Requirement {gas: string; state: string; value: string | null; unit: string | null; review: string | null;
  methodUnit: string | null; methodValue: string | null; conversionBlocker: string | null; note: string | null}
interface Resolution {boundary: string; fuelId: string; date: string; scope: string; requirements: Requirement[];
  consistency: string[]; state: 'RESOLVED_CANDIDATE' | 'UNRESOLVED'; blockers: string[]; admission: string}
const REVIEW: Record<string, string> = {INDEPENDENT_OK: 'conferido por revisão independente', INTERNAL_OK: 'conferido só por revisão interna', NOT_REVIEWED_OK: 'sem conferência'};
interface Conversion {value: string; originalValue: string; originalUnit: string; targetUnit: string;
  steps: {from: string; to: string; factor: string; note: string}[]}

const GAS: Record<string, string> = {
  CO2_FOSSIL: 'CO₂ fóssil', CO2_BIOGENIC: 'CO₂ biogênico', CO2_UNSPECIFIED: 'CO₂ (origem não especificada)',
  CH4: 'CH₄', N2O: 'N₂O', CO2E_AGGREGATE: 'CO₂e agregado',
};
const PARAM: Record<string, string> = {EMISSION_FACTOR: 'Fator de emissão', DENSITY: 'Massa específica', LHV: 'PCI (poder calorífico inferior)'};
const SCOPE: Record<string, string> = {TTW: 'Tanque à roda (TTW)', WTW: 'Poço à roda (WTW)', UNSPECIFIED: 'Escopo não especificado'};
const OUTCOME: Record<string, string> = {REVIEWED_OK: 'Conferido', REJECTED: 'Recusado', NEEDS_INFORMATION: 'Pede informação'};
const BASIS: Record<string, string> = {OWNER_INTERNAL: 'interna do responsável', INDEPENDENT: 'independente'};
const FILTER_ALL = '';

/**
 * Administration of the fuel catalog (D19 slice 5, Q12). Registers fuels, blend compositions and data points with
 * evidence, validity, vintage, gas, scope and the ORIGINAL unit; reviews on two separate bases; converts units only
 * from reviewed density/LHV entries. Nothing here admits a factor and the server decides every rule.
 */
@Component({
  selector: 'carbonflow-platform-fuel-catalog',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './platform-fuel-catalog.html',
  styleUrl: './platform-source-readiness.css',
})
export class PlatformFuelCatalogComponent {
  private readonly http = inject(HttpClient);
  private readonly api = '/v1/platform/fuel-catalog';
  readonly session = signal<StaffSession | null>(null);
  readonly catalog = signal<Catalog | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly feedback = signal('');
  readonly denied = signal(false);
  readonly fuelFilter = signal(FILTER_ALL);
  readonly reviewing = signal<string | null>(null);
  readonly conversion = signal<Conversion | null>(null);
  readonly boundaries = signal<Boundary[]>([]);
  readonly resolution = signal<Resolution | null>(null);
  readonly unlocked = computed(() => {
    if (this.denied()) return false;
    const session = this.session();
    return !!session?.verified && !!session.expiresAt && Date.parse(session.expiresAt) > Date.now();
  });
  readonly entries = computed(() => {
    const all = this.catalog()?.entries ?? [];
    const filter = this.fuelFilter();
    return filter ? all.filter(entry => entry.fuelId === filter) : all;
  });
  // Form fields (the server validates; these only carry what people type).
  kind: 'DATUM' | 'COMPOSITION' = 'DATUM';
  fuelId = ''; parameter = 'EMISSION_FACTOR'; gas = 'CO2_FOSSIL'; scope = 'TTW'; gwpSet = ''; value = ''; unit = '';
  vintage = ''; validFrom = ''; validTo = ''; source = ''; locator = ''; readState = 'PRIMARY_READ';
  ipccReference = ''; ipccValue = ''; ipccUnit = '';
  componentId = ''; nominal = ''; remainder = false;
  reviewBasis: 'OWNER_INTERNAL' | 'INDEPENDENT' = 'OWNER_INTERNAL'; reviewOutcome = 'REVIEWED_OK'; reviewReason = '';
  convertEntry = ''; convertTarget = '';
  boundaryId = 'CO2_ONLY'; boundaryFuel = ''; boundaryDate = ''; boundaryScope = 'TTW'; boundaryGwp = 'AR5_100';

  constructor() { void this.loadSession(); }

  gasLabel(value: string | null): string { return value ? (GAS[value] ?? value) : ''; }
  paramLabel(value: string | null): string { return value ? (PARAM[value] ?? value) : ''; }
  scopeLabel(value: string | null): string { return value ? (SCOPE[value] ?? value) : ''; }
  reviewText(review: Review | null): string {
    return review ? (OUTCOME[review.outcome] ?? review.outcome) + ' (' + review.decidedAt.slice(0, 10) + ')' : 'não revisado';
  }
  reviewLabel(value: string | null): string { return value ? (REVIEW[value] ?? value) : ''; }
  basisLabel(value: string): string { return BASIS[value] ?? value; }
  fuelName(id: string | null): string { return id ? (this.catalog()?.fuels.find(f => f.id === id)?.name ?? id) : ''; }
  describe(entry: Entry): string {
    if (entry.kind === 'COMPOSITION') {
      return this.fuelName(entry.componentId) + ': ' + (entry.remainder ? 'complemento' : entry.nominal) + ' (' + entry.compositionBasis + ')';
    }
    return this.paramLabel(entry.parameter) + ' ' + entry.value + ' ' + entry.unit
      + (entry.gas ? ' · ' + this.gasLabel(entry.gas) + (entry.gwpSet ? ' ' + entry.gwpSet : '') + ' · ' + this.scopeLabel(entry.emissionScope) : '')
      + ' · vintage ' + entry.vintageYear;
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
      this.catalog.set(await firstValueFrom(this.http.get<Catalog>(this.api)));
      this.boundaries.set(await firstValueFrom(this.http.get<Boundary[]>(this.api + '/boundaries')));
    } catch (reason) {
      this.catalog.set(null);
      if (reason instanceof HttpErrorResponse && reason.status === 404) this.denied.set(true);
      this.error.set(this.message(reason, 'Não foi possível consultar o catálogo.'));
    }
  }

  setFilter(event: Event) { this.fuelFilter.set((event.target as HTMLSelectElement).value); }

  private text(value: string): string | null { const t = value.trim(); return t ? t : null; }

  async register(event: Event) {
    event.preventDefault();
    if (this.saving()) return;
    const common = {
      kind: this.kind, fuelId: this.fuelId, validFrom: this.validFrom, validTo: this.text(this.validTo),
      evidenceSource: this.source.trim(), evidenceLocator: this.locator.trim(), readState: this.readState,
    };
    const body = this.kind === 'COMPOSITION'
      ? {...common, componentId: this.componentId, compositionBasis: 'VOLUME_FRACTION',
         nominal: this.remainder ? null : this.text(this.nominal), remainder: this.remainder}
      : {...common, parameter: this.parameter, value: this.value.trim(), unit: this.unit.trim(),
         vintageYear: this.vintage ? Number(this.vintage) : null,
         gas: this.parameter === 'EMISSION_FACTOR' ? this.gas : null,
         emissionScope: this.parameter === 'EMISSION_FACTOR' ? this.scope : null,
         gwpSet: this.parameter === 'EMISSION_FACTOR' && this.gas === 'CO2E_AGGREGATE' ? this.text(this.gwpSet) : null,
         ipccReference: this.text(this.ipccReference), ipccValue: this.text(this.ipccValue), ipccUnit: this.text(this.ipccUnit)};
    await this.run(() => this.http.post<Entry>(this.api + '/entries', body),
      'Registro criado como NOT_ADMITTED. Falta a revisão; nada foi admitido.');
  }

  openReview(entry: Entry) {
    const c = this.catalog();
    this.reviewing.set(entry.id);
    this.reviewBasis = c?.canReviewInternal ? 'OWNER_INTERNAL' : 'INDEPENDENT';
    this.reviewOutcome = 'REVIEWED_OK';
    this.reviewReason = '';
    this.error.set('');
  }

  async submitReview(event: Event, entry: Entry) {
    event.preventDefault();
    if (this.saving()) return;
    await this.run(() => this.http.post<Entry>(this.api + '/entries/' + entry.id + '/reviews',
      {basis: this.reviewBasis, outcome: this.reviewOutcome, reason: this.reviewReason.trim()}),
      'Revisão ' + this.basisLabel(this.reviewBasis) + ' registrada. A admissão continua NOT_ADMITTED.');
    this.reviewing.set(null);
  }

  async resolve(event: Event) {
    event.preventDefault();
    if (this.saving() || !this.boundaryFuel) return;
    this.saving.set(true);
    this.error.set('');
    this.resolution.set(null);
    const query = new URLSearchParams({boundary: this.boundaryId, fuelId: this.boundaryFuel, date: this.boundaryDate.trim(), scope: this.boundaryScope});
    if (this.boundaryId !== 'CO2_ONLY' && this.boundaryGwp.trim()) query.set('gwpSet', this.boundaryGwp.trim());
    try {
      this.resolution.set(await firstValueFrom(this.http.get<Resolution>(this.api + '/boundary-resolution?' + query.toString())));
    } catch (reason) {
      this.error.set(this.message(reason, 'Não foi possível resolver a fronteira.'));
    } finally {
      this.saving.set(false);
    }
  }

  async convert(event: Event) {
    event.preventDefault();
    if (this.saving() || !this.convertEntry) return;
    this.saving.set(true);
    this.error.set('');
    this.conversion.set(null);
    try {
      this.conversion.set(await firstValueFrom(this.http.get<Conversion>(this.api + '/entries/' + this.convertEntry
        + '/conversion?targetUnit=' + encodeURIComponent(this.convertTarget.trim()))));
    } catch (reason) {
      this.error.set(this.message(reason, 'Não foi possível converter.'));
    } finally {
      this.saving.set(false);
    }
  }

  private async run(call: () => ReturnType<HttpClient['get']>, done: string) {
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      await firstValueFrom(call());
      this.feedback.set(done);
      await this.load();
    } catch (reason) {
      const status = reason instanceof HttpErrorResponse ? reason.status : 0;
      const code = reason instanceof HttpErrorResponse ? reason.error?.code : null;
      this.error.set(status === 403 && code === 'DUPLA_CUSTODIA' ? 'Quem registrou o dado não pode revisá-lo como revisor independente. Use a revisão interna do responsável, se este é o seu papel.'
        : status === 403 && code === 'BASE_NAO_AUTORIZADA' ? 'Seu perfil não está autorizado nesta base de revisão.'
        : status === 409 ? 'Conflito com um registro existente (vigência sobreposta ou componente repetido).'
        : this.message(reason, 'Não foi possível concluir a operação.'));
    } finally {
      this.saving.set(false);
    }
  }

  private message(reason: unknown, fallback: string): string {
    if (reason instanceof HttpErrorResponse) {
      if (reason.status === 401) return 'Entre novamente para continuar.';
      if (reason.status === 404) return 'Este perfil não tem acesso ao catálogo de combustíveis.';
      const detail = reason.error?.message ?? reason.error?.title;
      if (typeof detail === 'string' && detail) return detail;
    }
    return fallback;
  }
}
