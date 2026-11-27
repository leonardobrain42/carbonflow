import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

interface StaffSession {verified: boolean; expiresAt: string | null}

/** Mirrors ContentLicenses.License. State is whatever the server derived from the latest decision. */
interface LicenseItem {
  id: string;
  sourceId: string;
  edition: string;
  purpose: string;
  authorizationRef: string;
  proofSha256: string;
  startsAt: string;
  endsAt: string | null;
  submittedBy: string;
  decisionVersion: number;
  state: 'PENDING' | 'DECLARED_INTERNAL' | 'APPROVED' | 'DENIED' | 'REVOKED';
  decidedBy: string | null;
  decidedAt: string | null;
  reason: string | null;
  operations: string[];
  limits: string;
  kind: 'PARTICULAR_AUTHORIZATION' | 'PUBLIC_LICENSE';
  licenseIdentifier: string | null;
  startsAtBasis: string;
  decisionBasis: 'OWNER_INTERNAL' | 'INDEPENDENT' | null;
}

interface Form {sourceId: string; edition: string; purpose: string; authorizationRef: string; proofSha256: string;
  startsAt: string; endsAt: string}

const EMPTY: Form = {sourceId: '', edition: '', purpose: 'REQUIREMENT_CATALOG', authorizationRef: '', proofSha256: '',
  startsAt: '', endsAt: ''};

export const OPERATIONS: {value: string; label: string}[] = [
  {value: 'REFERENCE_REQUIREMENT', label: 'Referenciar requisitos'},
  {value: 'STORE_ORIGINAL', label: 'Armazenar o documento original'},
  {value: 'EXTRACT_TEXT', label: 'Extrair texto e dados'},
  {value: 'SHOW_EXCERPTS', label: 'Exibir trechos'},
  {value: 'EXPORT_DERIVATIVES', label: 'Exportar dados derivados'},
  {value: 'INDEX_SEARCH', label: 'Indexar para busca'},
  {value: 'CREATE_EMBEDDINGS', label: 'Criar embeddings'},
  {value: 'RAG_RETRIEVE', label: 'Usar em recuperação (RAG)'},
  {value: 'MODEL_INFERENCE', label: 'Usar em inferência por IA'},
  {value: 'DRAFT_CONTENT', label: 'Redigir conteúdo'},
];
const PURPOSES: Record<string, string> = {
  REQUIREMENT_CATALOG: 'Catálogo de requisitos', CONTEXTUAL_HELP: 'Ajuda contextual',
  AUTHORIZED_PROCESSING: 'Processamento autorizado',
};
const STATES: Record<string, string> = {
  PENDING: 'Aguardando decisão', DECLARED_INTERNAL: 'Declarada internamente pelo responsável (sem aprovação independente)', APPROVED: 'Aprovada', DENIED: 'Negada', REVOKED: 'Revogada',
};

/**
 * Administration of source licences (CF-DATA-001, API in CF-IMP-029). It records a declared authorisation and an
 * independent decision. The proof is a declared SHA-256: nothing here verifies or stores the document, and an
 * approved licence does not enable calculation, data admission or external acts. The server decides every state.
 */
@Component({
  selector: 'carbonflow-platform-content-licenses',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './platform-content-licenses.html',
  styleUrl: './platform-source-readiness.css',
})
export class PlatformContentLicensesComponent {
  private readonly http = inject(HttpClient);
  private readonly api = '/v1/platform/source-licenses';
  readonly operations = OPERATIONS;
  readonly session = signal<StaffSession | null>(null);
  readonly items = signal<LicenseItem[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly feedback = signal('');
  readonly form = signal<Form>({...EMPTY});
  readonly denied = signal(false);
  /** The licence being decided or revoked, and what the person is doing with it. */
  readonly acting = signal<{id: string; mode: 'DECIDE' | 'REVOKE'} | null>(null);
  readonly chosen = signal<string[]>([]);
  /** Public licence declared by the product owner: each operation states whether a clause grants it or it is an interpretation. */
  readonly publicForm = signal<Record<string, string>>({sourceId: '', edition: '', licenseIdentifier: 'CC-BY-ND-3.0', legalTextSha256: '',
    evidenceUri: '', evidenceCapturedAt: '', evidenceSha256: '', startsAt: '', startsAtBasis: 'CAPTURE_DATE', reason: ''});
  readonly publicOps = signal<Record<string, {on: boolean; kind: string; grounds: string}>>({
    REFERENCE_REQUIREMENT: {on: false, kind: 'LICENSE_GRANT', grounds: ''}, STORE_ORIGINAL: {on: false, kind: 'LICENSE_GRANT', grounds: ''},
    SHOW_EXCERPTS: {on: false, kind: 'LICENSE_GRANT', grounds: ''}, EXTRACT_TEXT: {on: false, kind: 'LEGAL_INTERPRETATION', grounds: ''},
    EXPORT_DERIVATIVES: {on: false, kind: 'LEGAL_INTERPRETATION', grounds: ''}});
  readonly publicLicenses = [{value: 'CC-BY-ND-3.0', label: 'CC BY-ND 3.0 (Creative Commons, sem derivações)'},
    {value: 'PUBLIC-DOMAIN-DECLARED', label: 'Domínio público declarado pelo portal de dados abertos'}];
  readonly publicOpKeys = ['REFERENCE_REQUIREMENT', 'STORE_ORIGINAL', 'SHOW_EXCERPTS', 'EXTRACT_TEXT', 'EXPORT_DERIVATIVES'];
  readonly unlocked = computed(() => {
    if (this.denied()) return false;
    const session = this.session();
    return !!session?.verified && !!session.expiresAt && Date.parse(session.expiresAt) > Date.now();
  });

  constructor() { void this.loadSession(); }

  stateLabel(item: LicenseItem): string { return STATES[item.state] ?? item.state; }
  purposeLabel(item: LicenseItem): string { return PURPOSES[item.purpose] ?? item.purpose; }
  operationLabel(value: string): string { return OPERATIONS.find(o => o.value === value)?.label ?? value; }
  publicOpLabel(key: string): string {
    return ({REFERENCE_REQUIREMENT: 'Citar a fonte como referência', STORE_ORIGINAL: 'Guardar a cópia original',
      SHOW_EXCERPTS: 'Mostrar trechos com atribuição', EXTRACT_TEXT: 'Extrair dados da fonte',
      EXPORT_DERIVATIVES: 'Redistribuir derivados'} as Record<string, string>)[key] ?? key;
  }
  basisLabel(item: LicenseItem): string { return item.decisionBasis === 'OWNER_INTERNAL' ? 'declaração interna do responsável' : item.decisionBasis === 'INDEPENDENT' ? 'decisão independente' : ''; }
  shortHash(item: LicenseItem): string { return item.proofSha256.slice(0, 12); }

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
      this.items.set(await firstValueFrom(this.http.get<LicenseItem[]>(this.api)));
    } catch (reason) {
      this.items.set([]);
      if (reason instanceof HttpErrorResponse && reason.status === 404) this.denied.set(true);
      this.error.set(this.message(reason, 'Não foi possível consultar as licenças.'));
    }
  }

  edit(field: keyof Form, event: Event) {
    this.form.update(current => ({...current, [field]: (event.target as HTMLInputElement).value}));
  }

  editPublic(field: string, event: Event) {
    const value = (event.target as HTMLInputElement).value;
    this.publicForm.update(current => ({...current, [field]: value}));
  }

  editPublicOp(operation: string, field: 'on' | 'kind' | 'grounds', event: Event) {
    const target = event.target as HTMLInputElement;
    this.publicOps.update(current => ({...current, [operation]: {...current[operation],
      [field]: field === 'on' ? target.checked : target.value}}));
  }

  /**
   * Fills the form from a prepared declaration (docs/data/*-license-declaration.json). Nothing is sent: the owner reviews every
   * field and submits. Unknown operations, licences or malformed fields are refused instead of being guessed.
   */
  loadDeclaration(area: HTMLTextAreaElement) {
    this.error.set('');
    this.feedback.set('');
    let data: Record<string, unknown>;
    try {
      data = JSON.parse(area.value);
    } catch {
      this.error.set('O conteúdo não é um JSON válido. Cole uma declaração preparada (docs/data/*-license-declaration.json).');
      return;
    }
    const text = (key: string): string | null => typeof data[key] === 'string' ? data[key] as string : null;
    const fields = ['sourceId', 'edition', 'licenseIdentifier', 'legalTextSha256', 'evidenceUri', 'evidenceCapturedAt',
      'evidenceSha256', 'startsAt', 'startsAtBasis', 'reason'];
    const missing = fields.filter(key => text(key) === null);
    const known = new Set(this.publicOpKeys);
    const operations = Array.isArray(data['operations']) ? data['operations'] as Record<string, unknown>[] : [];
    const invalidOps = operations.filter(o => typeof o?.['operation'] !== 'string' || !known.has(o['operation'] as string)
      || !['LICENSE_GRANT', 'LEGAL_INTERPRETATION'].includes(o['groundKind'] as string) || typeof o['grounds'] !== 'string');
    if (missing.length > 0 || operations.length === 0 || invalidOps.length > 0
        || !this.publicLicenses.some(l => l.value === text('licenseIdentifier'))
        || !['CAPTURE_DATE', 'PROVEN_AVAILABILITY'].includes(text('startsAtBasis') ?? '')) {
      this.error.set('Declaração incompleta ou fora do que a plataforma modela (campos, licença, critério de vigência ou operações).');
      return;
    }
    this.publicForm.set(Object.fromEntries(fields.map(key => [key, text(key) as string])));
    const next: Record<string, {on: boolean; kind: string; grounds: string}> = {};
    for (const key of this.publicOpKeys) next[key] = {on: false, kind: this.publicOps()[key].kind, grounds: ''};
    for (const o of operations) next[o['operation'] as string] = {on: true, kind: o['groundKind'] as string, grounds: o['grounds'] as string};
    this.publicOps.set(next);
    area.value = '';
    this.feedback.set('Declaração carregada no formulário. Nada foi enviado: revise cada campo e clique em "Declarar licença pública".');
  }

  async declarePublic(event: Event) {
    event.preventDefault();
    if (this.saving()) return;
    const f = this.publicForm();
    const operations = this.publicOpKeys.filter(key => this.publicOps()[key].on)
      .map(key => ({operation: key, groundKind: this.publicOps()[key].kind, grounds: this.publicOps()[key].grounds.trim()}));
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      await firstValueFrom(this.http.post<LicenseItem>(this.api + '/public', {
        sourceId: f['sourceId'].trim(), edition: f['edition'].trim(), purpose: 'AUTHORIZED_PROCESSING',
        licenseIdentifier: f['licenseIdentifier'], legalTextSha256: f['legalTextSha256'].trim().toLowerCase(),
        evidenceUri: f['evidenceUri'].trim(), evidenceCapturedAt: f['evidenceCapturedAt'].trim(),
        evidenceSha256: f['evidenceSha256'].trim().toLowerCase(), startsAt: f['startsAt'].trim(),
        startsAtBasis: f['startsAtBasis'], endsAt: null, reason: f['reason'].trim(), operations}));
      this.feedback.set('Licença pública declarada internamente. Ela ainda precisa de aprovação independente para valer como aprovada.');
      await this.load();
    } catch (reason) {
      const code = reason instanceof HttpErrorResponse ? reason.error?.code : null;
      this.error.set(code === 'OPERACAO_FORA_DA_LICENCA' ? 'A licença não cobre uma das operações marcadas.'
        : code === 'FUNDAMENTO_NAO_E_CLAUSULA' ? 'Uma operação marcada como cláusula não consta da concessão da licença: declare-a como interpretação jurídica.'
        : code === 'OPERACAO_NAO_DECLARADA' ? String(reason instanceof HttpErrorResponse ? reason.error?.message : '')
        : code === 'FONTE_INEXISTENTE' ? 'A fonte e a versão informadas não estão registradas neste banco. Confira o código da fonte e a versão publicada (MCTI-SIN-DESPACHO, 2026-janout-7f8e2292); a fonte vem da migração V112.'
        : this.serverMessage(reason) ?? this.fieldProblem(reason) ?? this.message(reason, 'Não foi possível declarar a licença pública.'));
    } finally {
      this.saving.set(false);
    }
  }

  async submit(event: Event) {
    event.preventDefault();
    if (this.saving()) return;
    const value = this.form();
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      await firstValueFrom(this.http.post<LicenseItem>(this.api, {
        sourceId: value.sourceId.trim(), edition: value.edition.trim(), purpose: value.purpose,
        authorizationRef: value.authorizationRef.trim(), proofSha256: value.proofSha256.trim().toLowerCase(),
        startsAt: this.iso(value.startsAt), endsAt: value.endsAt ? this.iso(value.endsAt) : null,
      }));
      this.feedback.set('Autorização registrada e aguardando decisão de outra pessoa.');
      this.form.set({...EMPTY});
      await this.load();
    } catch (reason) {
      this.error.set(this.message(reason, 'Não foi possível registrar a autorização.'));
    } finally {
      this.saving.set(false);
    }
  }

  /** A date picked in the form is the start of that day in UTC; the server stores an instant. */
  private iso(day: string): string { return day + 'T00:00:00Z'; }

  /** A public licence is approved only for what the owner declared; the approver can narrow it, never widen it. */
  decidable(item: LicenseItem) {
    return item.kind === 'PUBLIC_LICENSE' ? this.operations.filter(o => item.operations.includes(o.value)) : this.operations;
  }

  act(item: LicenseItem, mode: 'DECIDE' | 'REVOKE') {
    this.acting.set({id: item.id, mode});
    this.chosen.set(mode === 'DECIDE' && item.kind === 'PUBLIC_LICENSE' ? [...item.operations] : []);
    this.error.set('');
    this.feedback.set('');
  }

  cancel() { this.acting.set(null); this.chosen.set([]); }

  toggle(operation: string, event: Event) {
    const on = (event.target as HTMLInputElement).checked;
    this.chosen.update(current => on ? [...new Set([...current, operation])] : current.filter(o => o !== operation));
  }

  async decide(item: LicenseItem, outcome: 'APPROVED' | 'DENIED', reason: string) {
    await this.send(item, '/decision', {outcome, reason: reason.trim(),
      operations: outcome === 'APPROVED' ? this.chosen() : []},
      outcome === 'APPROVED' ? 'Licença aprovada para as operações marcadas.' : 'Licença negada.');
  }

  async revoke(item: LicenseItem, reason: string) {
    await this.send(item, '/revoke', {reason: reason.trim()}, 'Licença revogada: nenhuma operação segue autorizada.');
  }

  private async send(item: LicenseItem, path: string, body: object, done: string) {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      await firstValueFrom(this.http.post<LicenseItem>(this.api + '/' + item.id + path, body,
        {headers: new HttpHeaders({'If-Match': '"' + item.decisionVersion + '"'})}));
      this.feedback.set(done);
      this.cancel();
      await this.load();
    } catch (reason) {
      const status = reason instanceof HttpErrorResponse ? reason.status : 0;
      const code = reason instanceof HttpErrorResponse ? reason.error?.code : null;
      this.error.set(status === 403 && code === 'DUPLA_CUSTODIA' ? 'Quem submeteu a autorização não pode aprová-la. Peça a outra pessoa.'
        : status === 412 ? 'A licença mudou desde que você abriu a lista. Atualize e tente de novo.'
        : status === 409 ? 'Esta licença já tem decisão. Para mudar, revogue (se aprovada) ou registre uma nova autorização.'
        : this.message(reason, 'Não foi possível registrar a decisão.'));
    } finally {
      this.saving.set(false);
    }
  }

  /** The server names the offending field(s) of a 422; show them instead of the generic hint. */
  /** The server states which rule failed; show that text instead of the generic list when it is specific enough. */
  private serverMessage(reason: unknown): string | null {
    if (!(reason instanceof HttpErrorResponse) || reason.status !== 422) return null;
    const text = reason.error?.message;
    return typeof text === 'string' && text.length >= 20 ? text : null;
  }

  private fieldProblem(reason: unknown): string | null {
    if (!(reason instanceof HttpErrorResponse) || reason.status !== 422) return null;
    const names: Record<string, string> = {
      licenca: 'campos obrigatórios (fonte, versão, justificativa, https da página, SHA-256 de 64 caracteres)',
      evidenceCapturedAt: 'data e hora da captura (ISO-8601 com Z, por exemplo 2026-11-25T23:44:00Z)',
      startsAt: 'vigência a partir de (com a base "Data da captura" deve ser idêntica à captura)',
      endsAt: 'fim da vigência', operations: 'operações (marque ao menos uma, com fundamento de 20 a 2000 caracteres)',
      licenseIdentifier: 'identificador da licença',
    };
    const paths: string[] = (reason.error?.issues ?? []).map((i: {path?: string}) => i.path).filter(Boolean);
    return paths.length ? 'Campo com problema: ' + paths.map(p => names[p] ?? p).join('; ') + '.' : null;
  }

  private message(reason: unknown, fallback: string): string {
    const status = reason instanceof HttpErrorResponse ? reason.status : 0;
    if (status === 404) return 'A gestão de licenças é exclusiva da equipe interna autorizada. Solicite o privilégio específico.';
    if (status === 403) return 'Confirme o acesso com o autenticador.';
    const specific = this.serverMessage(reason);
    if (specific) return specific;
    if (status === 422) return 'Confira fonte e edição já registradas, finalidade, referência, hash SHA-256 de 64 caracteres, datas, motivo (mín. 10 caracteres) e operações.';
    if (status === 428) return 'Informe a versão atual da licença.';
    return fallback;
  }
}
