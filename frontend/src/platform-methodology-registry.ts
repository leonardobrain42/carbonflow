import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

interface StaffSession {verified: boolean; expiresAt: string | null}

/** One registered methodology/edition, last version. Mirrors MethodologyRegistry.Entry. */
interface RegistryEntry {
  program: string;
  methodology: string;
  edition: string;
  version: number;
  title: string;
  rightsHolder: string;
  officialUri: string | null;
  origin: 'SEED' | 'ADMIN';
  registeredAt: string;
  digest: string;
  hasPackage: boolean;
  sourceNeeds: number;
  registeredSources: number;
}

interface Form {
  program: string;
  methodology: string;
  edition: string;
  title: string;
  rightsHolder: string;
  officialUri: string;
}

const EMPTY: Form = {program: 'VCS', methodology: '', edition: '', title: '', rightsHolder: '', officialUri: ''};

/**
 * Administration of the methodology registry (D18, slice 1). Metadata only: registering a methodology does not
 * create a package, admit data, grant a licence or enable calculation. The server decides every state shown here.
 */
@Component({
  selector: 'carbonflow-platform-methodology-registry',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './platform-methodology-registry.html',
  styleUrl: './platform-source-readiness.css',
})
export class PlatformMethodologyRegistryComponent {
  private readonly http = inject(HttpClient);
  private readonly api = '/v1/platform/methodology-registry';
  readonly session = signal<StaffSession | null>(null);
  readonly entries = signal<RegistryEntry[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly feedback = signal('');
  readonly form = signal<Form>({...EMPTY});
  /** The entry whose next version is being written; null when registering a new key. */
  readonly revising = signal<RegistryEntry | null>(null);
  /** True once the server refused the registry to this identity (no privilege): nothing else is shown. */
  readonly denied = signal(false);
  readonly unlocked = computed(() => {
    if (this.denied()) return false;
    const session = this.session();
    return !!session?.verified && !!session.expiresAt && Date.parse(session.expiresAt) > Date.now();
  });

  constructor() { void this.loadSession(); }

  stateLabel(entry: RegistryEntry): string {
    return entry.hasPackage ? 'Pacote em código' : 'Registrada, sem pacote executável';
  }

  shortDigest(entry: RegistryEntry): string {
    return entry.digest.slice(0, 12);
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
        : status === 403 ? 'Código inválido ou já utilizado.'
        : 'Não foi possível verificar o acesso.');
    } finally {
      this.loading.set(false);
    }
  }

  async load() {
    this.error.set('');
    this.denied.set(false);
    try {
      this.entries.set(await firstValueFrom(this.http.get<RegistryEntry[]>(this.api)));
    } catch (reason) {
      this.entries.set([]);
      if (reason instanceof HttpErrorResponse && reason.status === 404) this.denied.set(true);
      this.error.set(this.message(reason, 'Não foi possível consultar o registro.'));
    }
  }

  edit(field: keyof Form, event: Event) {
    this.form.update(current => ({...current, [field]: (event.target as HTMLInputElement).value}));
  }

  /** Prefills the key of an existing entry so the next version is written against its current version. */
  revise(entry: RegistryEntry) {
    this.revising.set(entry);
    this.feedback.set('');
    this.error.set('');
    this.form.set({program: entry.program, methodology: entry.methodology, edition: entry.edition,
      title: entry.title, rightsHolder: entry.rightsHolder, officialUri: entry.officialUri ?? ''});
  }

  cancel() {
    this.revising.set(null);
    this.form.set({...EMPTY});
  }

  async save(event: Event) {
    event.preventDefault();
    if (this.saving()) return;
    const value = this.form();
    const current = this.revising();
    this.saving.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      const response = await firstValueFrom(this.http.post<RegistryEntry>(this.api, {
        program: value.program.trim(), methodology: value.methodology.trim(), edition: value.edition.trim(),
        title: value.title.trim(), rightsHolder: value.rightsHolder.trim(),
        officialUri: value.officialUri.trim() || null,
      }, {observe: 'response', headers: new HttpHeaders({'If-Match': '"' + (current?.version ?? 0) + '"'})}));
      const saved = response.body;
      this.feedback.set(response.status === 201
        ? 'Registrado: ' + saved?.methodology + ' ' + saved?.edition + ', versão ' + saved?.version + '.'
        : 'Nenhuma alteração: o conteúdo é igual à última versão.');
      this.cancel();
      await this.load();
    } catch (reason) {
      const status = reason instanceof HttpErrorResponse ? reason.status : 0;
      this.error.set(status === 412 ? (current
          ? 'O registro mudou desde que você abriu a revisão. Atualize a lista e tente de novo.'
          : 'Já existe um registro para este programa, metodologia e edição. Use "Nova versão" na lista.')
        : this.message(reason, 'Não foi possível registrar.'));
    } finally {
      this.saving.set(false);
    }
  }

  private message(reason: unknown, fallback: string): string {
    const status = reason instanceof HttpErrorResponse ? reason.status : 0;
    if (status === 404) return 'O registro de metodologias é exclusivo da equipe interna autorizada. Solicite o privilégio específico.';
    if (status === 403) return 'Confirme o acesso com o autenticador.';
    if (status === 422) return 'Confira programa (maiúsculas), metodologia, edição, título, titular dos direitos e endereço oficial (https).';
    if (status === 428) return 'Informe a versão atual do registro.';
    return fallback;
  }
}
