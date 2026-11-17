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

interface DraftMetadata extends DraftProject {
  title: string;
  description: string | null;
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
  readonly createdProject = signal<DraftMetadata | null>(null);
  readonly selectedMetadata = signal<DraftMetadata | null>(null);
  readonly error = signal('');
  readonly notice = signal('');
  private creationKey = '';
  private projectCreationKey = '';
  private projectCreationPayload = '';
  private editKey = '';
  private editPayload = '';

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
      if (this.selectedProjectId()) {
        await this.loadMetadata(this.selectedProjectId());
      } else {
        this.selectedMetadata.set(null);
      }
    } catch (reason: unknown) {
      this.session.set(null);
      this.selectedProjectId.set('');
      this.selectedMetadata.set(null);
      this.createdProject.set(null);
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
      this.selectedMetadata.set(null);
      this.createdProject.set(null);
      this.organizations.set([]);
      this.notice.set('');
      this.organizationName.set('');
    } catch (reason: unknown) {
      this.error.set(this.errorMessage(reason, 'Não foi possível sair da conta.'));
    } finally {
      this.busy.set(false);
    }
  }

  async selectProject(event: Event) {
    const id = (event.target as HTMLSelectElement).value;
    this.selectedProjectId.set(this.session()?.projects.some(project => project.projectId === id) ? id : '');
    this.selectedMetadata.set(null);
    this.error.set('');
    if (this.selectedProjectId()) await this.loadMetadata(this.selectedProjectId());
  }

  private async loadMetadata(projectId: string) {
    try {
      const metadata = await firstValueFrom(this.http.get<DraftMetadata>(
        '/v1/projects/' + encodeURIComponent(projectId) + '/metadata'));
      if (this.selectedProjectId() === projectId) this.selectedMetadata.set(metadata);
    } catch (reason: unknown) {
      if (this.selectedProjectId() !== projectId) return;
      this.selectedMetadata.set(null);
      this.error.set(this.errorMessage(reason, 'Não foi possível consultar o projeto selecionado.'));
    }
  }

  async createProject(event: Event, organizationId: string, rawTitle: string, rawDescription: string) {
    event.preventDefault();
    if (this.busy()) return;
    const title = rawTitle.trim();
    const description = rawDescription.trim();
    if (!title || title.length > 160 || description.length > 2000) {
      this.error.set('Informe um título de até 160 caracteres e uma descrição de até 2.000.');
      return;
    }
    if (!this.organizations().some(org => org.id === organizationId && org.role === 'DRAFT_OWNER')) {
      this.error.set('Selecione um espaço organizacional autorizado.');
      return;
    }
    const body = {organizationId, title, description: description || null};
    const payload = JSON.stringify(body);
    if (!this.projectCreationKey || this.projectCreationPayload !== payload) {
      this.projectCreationKey = crypto.randomUUID();
      this.projectCreationPayload = payload;
    }
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    let created: DraftMetadata | null = null;
    try {
      created = await firstValueFrom(this.http.post<DraftMetadata>('/v1/projects', body, {
        headers: {'Idempotency-Key': this.projectCreationKey},
      }));
      this.projectCreationKey = '';
      this.projectCreationPayload = '';
      this.createdProject.set(created);
    } catch (reason: unknown) {
      this.error.set(this.errorMessage(reason, 'Não foi possível criar o rascunho. Tente novamente.'));
      if (reason instanceof HttpErrorResponse && reason.status >= 400 && reason.status < 500) {
        this.projectCreationKey = '';
      }
    } finally {
      this.busy.set(false);
    }
    if (created) {
      await this.refreshSession();
      this.notice.set('Rascunho criado e associado à sua organização. A triagem técnica será habilitada na próxima etapa.');
    }
  }

  async updateProject(event: Event, rawTitle: string, rawDescription: string) {
    event.preventDefault();
    const current = this.selectedMetadata();
    if (!current || this.busy()) return;
    const title = rawTitle.trim();
    const description = rawDescription.trim();
    if (!title || title.length > 160 || description.length > 2000) {
      this.error.set('Informe um título válido e uma descrição com até 2.000 caracteres.');
      return;
    }
    const body = {title, description: description || null};
    const payload = JSON.stringify({projectId: current.projectId, expected: current.version, ...body});
    if (!this.editKey || this.editPayload !== payload) {
      this.editKey = crypto.randomUUID();
      this.editPayload = payload;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      const updated = await firstValueFrom(this.http.patch<DraftMetadata>(
        '/v1/projects/' + encodeURIComponent(current.projectId) + '/metadata', body, {
          headers: {'Idempotency-Key': this.editKey, 'If-Match': '"' + current.version + '"'},
        }));
      if (this.selectedProjectId() === current.projectId) this.selectedMetadata.set(updated);
      this.editKey = '';
      this.editPayload = '';
      this.notice.set('Metadados do rascunho atualizados (versão ' + updated.version + ').');
      await this.refreshSession();
    } catch (reason: unknown) {
      this.error.set(this.errorMessage(reason, 'Não foi possível salvar. Recarregue a versão atual.'));
      if (reason instanceof HttpErrorResponse && reason.status >= 400 && reason.status < 500) {
        this.editKey = '';
        if (reason.status === 412) await this.loadMetadata(current.projectId);
      }
    } finally {
      this.busy.set(false);
    }
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
