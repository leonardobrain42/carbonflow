import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

interface AccountIdentity {
  id: string;
  name: string;
  email: string;
}

interface DraftProject {
  projectId: string;
  organizationId: string;
  environment: string;
  state: string;
  version: number;
}

interface AccountSession {
  identity: AccountIdentity;
  projects: DraftProject[];
}

interface WorkspaceOrganization {
  id: string;
  name: string;
  environment: string;
  status: string;
  version: number;
  role: string;
}

@Component({
  selector: 'carbonflow-workspace',
  imports: [RouterLink],
  templateUrl: './workspace.html',
})
export class WorkspaceComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  readonly creationFlow = this.route.snapshot.routeConfig?.path === 'workspace/projects/new';

  readonly session = signal<AccountSession | null>(null);
  readonly organizations = signal<WorkspaceOrganization[]>([]);
  readonly selectedProjectId = signal('');
  readonly selectedProject = computed(() => this.session()?.projects.find(
    project => project.projectId === this.selectedProjectId()) ?? null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly organizationName = signal('');
  readonly error = signal('');
  readonly notice = signal('');
  private creationKey = '';

  constructor() {
    void this.refreshSession();
  }

  async refreshSession() {
    if (this.busy()) return;
    this.loading.set(true);
    this.error.set('');
    try {
      const current = await firstValueFrom(this.http.get<AccountSession>('/v1/account-session'));
      const previous = this.session();
      const sameIdentity = previous?.identity.id === current.identity.id;
      const candidate = sameIdentity ? this.selectedProjectId() : '';
      this.selectedProjectId.set(current.projects.some(project => project.projectId === candidate) ? candidate : '');
      this.session.set(current);
      this.organizations.set(await firstValueFrom(this.http.get<WorkspaceOrganization[]>('/v1/organizations')));
    } catch (reason: unknown) {
      this.session.set(null);
      this.selectedProjectId.set('');
      this.organizations.set([]);
      if (!(reason instanceof HttpErrorResponse) || reason.status !== 401) {
        this.error.set('Não foi possível consultar o espaço da conta. Tente novamente.');
      }
    } finally {
      this.loading.set(false);
    }
  }

  async login(event: Event, email: string, password: string) {
    event.preventDefault();
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      await firstValueFrom(this.http.post('/v1/account-session', {email: email.trim(), password}));
    } catch (reason: unknown) {
      this.error.set(this.errorMessage(reason, 'Não foi possível entrar. Confira o e-mail e a senha.'));
      this.busy.set(false);
      return;
    }
    this.busy.set(false);
    await this.refreshSession();
  }

  async logout() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(this.http.delete('/v1/account-session'));
      this.session.set(null);
      this.selectedProjectId.set('');
      this.organizations.set([]);
      this.notice.set('');
      this.organizationName.set('');
    } catch (reason: unknown) {
      this.error.set(this.errorMessage(reason, 'Não foi possível sair da conta.'));
    } finally {
      this.busy.set(false);
    }
  }

  selectProject(event: Event) {
    const id = (event.target as HTMLSelectElement).value;
    this.selectedProjectId.set(this.session()?.projects.some(project => project.projectId === id) ? id : '');
    this.error.set('');
  }

  async createOrganization(event: Event, value: string) {
    event.preventDefault();
    if (this.busy() || this.organizations().length > 0) return;
    const name = value.trim();
    if (!name || name.length > 120) {
      this.error.set('Informe um nome de trabalho com até 120 caracteres.');
      return;
    }
    if (!this.creationKey || this.organizationName() !== name) {
      this.creationKey = crypto.randomUUID();
      this.organizationName.set(name);
    }
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(this.http.post('/v1/organizations', {name}, {
        headers: {'Idempotency-Key': this.creationKey},
      }));
      this.organizations.set(await firstValueFrom(this.http.get<WorkspaceOrganization[]>('/v1/organizations')));
      this.notice.set('Espaço privado criado. Nenhum projeto foi criado ainda.');
      this.creationKey = '';
    } catch (reason: unknown) {
      this.error.set(this.errorMessage(reason, 'Não foi possível criar o espaço de trabalho.'));
      if (reason instanceof HttpErrorResponse && reason.status >= 400 && reason.status < 500) {
        this.creationKey = '';
      }
    } finally {
      this.busy.set(false);
    }
  }

  private errorMessage(reason: unknown, fallback: string) {
    return reason instanceof HttpErrorResponse && typeof reason.error?.message === 'string'
      ? reason.error.message : fallback;
  }
}
