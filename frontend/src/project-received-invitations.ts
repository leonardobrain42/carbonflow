import {Component, inject, signal} from '@angular/core';
import {DatePipe} from '@angular/common';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';
import {INVITATION_STATUS, ProjectInvitation} from './project-invitations';

@Component({
  selector: 'carbonflow-project-received-invitations',
  imports: [RouterLink, DatePipe],
  templateUrl: './project-received-invitations.html',
})
export class ProjectReceivedInvitationsComponent {
  private readonly http = inject(HttpClient);
  private readonly url = '/v1/project-invitations';
  readonly items = signal<ProjectInvitation[] | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly status = INVITATION_STATUS;
  readonly names: Record<string, string> = {INTAKE: 'Aplicabilidade e fronteira', DOCUMENTATION: 'Documentação do projeto'};

  constructor() {
    void this.load();
  }

  private async load() {
    this.busy.set(true);
    try {
      this.items.set(await firstValueFrom(this.http.get<ProjectInvitation[]>(this.url)));
    } catch (cause) {
      this.error.set(this.message(cause, 'Não foi possível consultar os convites recebidos.'));
    } finally {
      this.busy.set(false);
    }
  }

  async accept(invitation: ProjectInvitation) {
    if (this.busy() || invitation.state !== 'SENT') return;
    this.busy.set(true);
    this.error.set('');
    try {
      const accepted = await firstValueFrom(this.http.post<ProjectInvitation>(
        this.url + '/' + encodeURIComponent(invitation.id) + '/accept', null));
      this.items.update(items => items?.map(item => item.id === accepted.id ? accepted : item) ?? [accepted]);
    } catch (cause) {
      this.error.set(this.message(cause, 'Não foi possível aceitar o convite.'));
    } finally {
      this.busy.set(false);
    }
  }

  private message(cause: unknown, fallback: string) {
    if (!(cause instanceof HttpErrorResponse)) return fallback;
    if (cause.status === 401) return 'Entre com a conta de e-mail verificado que recebeu o convite.';
    return typeof cause.error?.message === 'string' ? cause.error.message : fallback;
  }
}
