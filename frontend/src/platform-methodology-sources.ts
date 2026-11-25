import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpResponse} from '@angular/common/http';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

interface StaffSession {verified: boolean; expiresAt: string | null}
interface SourceContext {
  methodology: string; edition: string; scenario: string; title: string; country: string;
  networkMode: string | null; vehicleCategory: string | null; chargerType: string | null;
  energySystem: string | null; needCount: number; registeredSourceCount: number;
}

const STAGES: [string, string][] = [['APPLICABILITY', 'Aplicabilidade'], ['BASELINE', 'Linha de base'],
  ['ADDITIONALITY', 'Adicionalidade'], ['QUANTIFICATION', 'Quantificação'], ['MONITORING', 'Monitoramento'],
  ['DOCUMENTS', 'Documentos']];
const KINDS: [string, string][] = [['NORMATIVE', 'Norma'], ['DATASET', 'Conjunto de dados'],
  ['PROJECT_EVIDENCE', 'Evidência do projeto']];
const CAPABILITIES: [string, string][] = [['PREPARATION', 'Preparação'], ['APPLICABILITY', 'Aplicabilidade'],
  ['BASELINE_BE', 'Linha de base (BE)'], ['PROJECT_EMISSIONS_PE', 'Emissões do projeto (PE)'],
  ['EMISSION_REDUCTIONS_ER', 'Reduções (ER)'], ['ADDITIONALITY', 'Adicionalidade'], ['MONITORING', 'Monitoramento'],
  ['PD', 'Documento do projeto'], ['AI_SUPPORT', 'Apoio de IA']];
const POLICIES: [string, string][] = [['EXACT', 'Edição exata'], ['YEAR', 'Edição do ano'], ['LATEST', 'Mais recente']];
const PARTIES: [string, string][] = [['CARBONFLOW', 'CarbonFlow'], ['PROPONENT', 'Proponente'], ['OPERATOR', 'Operador']];

/**
 * Sources expected by one registered methodology (D18 slice 2). Metadata only: a need names what the methodology
 * expects. It does not register, license or admit the source and does not enable calculation.
 */
@Component({
  selector: 'carbonflow-platform-methodology-sources',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './platform-methodology-sources.html',
  styleUrl: './platform-source-readiness.css',
})
export class PlatformMethodologySourcesComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  readonly methodology = this.route.snapshot.paramMap.get('methodology') ?? '';
  readonly edition = this.route.snapshot.paramMap.get('edition') ?? '';
  private readonly api = '/v1/platform/methodology-registry/' + encodeURIComponent(this.methodology)
    + '/' + encodeURIComponent(this.edition) + '/sources';
  readonly stages = STAGES;
  readonly kinds = KINDS;
  readonly capabilities = CAPABILITIES;
  readonly policies = POLICIES;
  readonly parties = PARTIES;
  readonly session = signal<StaffSession | null>(null);
  readonly contexts = signal<SourceContext[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly feedback = signal('');
  /** Scenario whose need form is open. */
  readonly needFor = signal<string | null>(null);
  readonly kind = signal('NORMATIVE');
  readonly unlocked = computed(() => {
    const s = this.session();
    return !this.denied() && !!s?.verified && !!s.expiresAt && Date.parse(s.expiresAt) > Date.now();
  });

  constructor() { void this.loadSession(); }

  private async loadSession() {
    this.loading.set(true);
    try {
      const session = await firstValueFrom(this.http.get<StaffSession>('/v1/compliance/session'));
      this.session.set(session);
      if (session.verified) await this.load();
    } catch {
      this.session.set(null);
      this.error.set('Não foi possível consultar a sessão. Confira o acesso e tente novamente.');
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
      this.contexts.set(await firstValueFrom(this.http.get<SourceContext[]>(this.api)));
    } catch (reason) {
      this.contexts.set([]);
      this.fail(reason, 'Não foi possível consultar as fontes.');
    }
  }

  openNeed(scenario: string) {
    this.needFor.set(this.needFor() === scenario ? null : scenario);
    this.kind.set('NORMATIVE');
    this.feedback.set('');
  }

  onKind(event: Event) { this.kind.set((event.target as HTMLSelectElement).value); }

  private field(form: HTMLFormElement, name: string): string {
    return ((form.elements.namedItem(name) as HTMLInputElement | HTMLSelectElement | null)?.value ?? '').trim();
  }

  async saveContext(event: Event) {
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    const optional = (name: string) => this.field(form, name) || null;
    const ok = await this.post(this.api + '/contexts', {
      scenario: this.field(form, 'scenario'), title: this.field(form, 'title'), country: this.field(form, 'country'),
      networkMode: optional('networkMode'), vehicleCategory: optional('vehicleCategory'),
      chargerType: optional('chargerType'), energySystem: optional('energySystem'),
    }, 'Contexto registrado.');
    if (ok) form.reset();
  }

  async saveNeed(event: Event, scenario: string) {
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    const evidence = this.field(form, 'kind') === 'PROJECT_EVIDENCE';
    const policy = this.field(form, 'editionPolicy');
    const edition = policy === 'YEAR' ? 'YEAR' : policy === 'LATEST' ? 'LATEST' : this.field(form, 'sourceEdition');
    const ok = await this.post(this.api + '/contexts/' + encodeURIComponent(scenario) + '/needs', {
      code: this.field(form, 'code'), stage: this.field(form, 'stage'), kind: this.field(form, 'kind'),
      title: this.field(form, 'title'), purpose: this.field(form, 'purpose'),
      sourceId: evidence ? null : this.field(form, 'sourceId'), sourceEdition: evidence ? null : edition,
      officialUri: this.field(form, 'officialUri') || null, mandatory: this.field(form, 'mandatory') === 'true',
      conditionNote: this.field(form, 'conditionNote'), blockedCapability: this.field(form, 'blockedCapability'),
      editionPolicy: policy, responsibleParty: this.field(form, 'responsibleParty'), priority: this.field(form, 'priority'),
    }, 'Necessidade de fonte registrada.');
    if (ok) { form.reset(); this.needFor.set(null); }
  }

  private async post(url: string, body: unknown, success: string): Promise<boolean> {
    if (this.saving()) return false;
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      const response: HttpResponse<unknown> = await firstValueFrom(
        this.http.post(url, body, {observe: 'response'}));
      this.feedback.set(response.status === 201 ? success : 'Nenhuma alteração: o conteúdo já estava registrado.');
      await this.load();
      return true;
    } catch (reason) {
      this.fail(reason, 'Não foi possível registrar.');
      return false;
    } finally {
      this.saving.set(false);
    }
  }

  private fail(reason: unknown, fallback: string) {
    const status = reason instanceof HttpErrorResponse ? reason.status : 0;
    if (status === 404 && !this.contexts().length && reason instanceof HttpErrorResponse
        && reason.url?.endsWith('/sources')) this.denied.set(true);
    this.error.set(status === 404 ? 'Metodologia não registrada, contexto inexistente ou acesso exclusivo da equipe autorizada.'
      : status === 403 ? 'Confirme o acesso com o autenticador.'
      : status === 409 ? 'Já existe um registro com esta chave e conteúdo diferente. Registros só recebem acréscimos.'
      : status === 422 ? 'Confira os campos: códigos em maiúsculas, endereço https e edição compatível com a política.'
      : fallback);
  }
}
