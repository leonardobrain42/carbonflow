import {WorkspaceNavigationComponent} from './workspace-navigation';
import {Component, inject, signal} from '@angular/core';
import {DatePipe} from '@angular/common';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';
import {INVITATION_STATUS, ProjectInvitation} from './project-invitations';

const ACTIVITIES = {
  INTAKE: 'Aplicabilidade e fronteira',
  EVIDENCE: 'Dados e evidências',
  BASELINE: 'Linha de base',
  ADDITIONALITY: 'Adicionalidade',
  CALCULATION: 'Quantificação',
  MONITORING: 'Monitoramento',
  DOCUMENTATION: 'Documentação do projeto',
} as const;
type Activity = keyof typeof ACTIVITIES;
type Role = 'PREP' | 'REV';
type DeclaredConflict = 'UNKNOWN' | 'NONE_DECLARED' | 'DECLARED';

interface Input {
  email: string;
  role: Role;
  activities: Activity[];
  deadline: string | null;
  competence: string | null;
  declaredConflict: DeclaredConflict;
  conflictNote: string | null;
}

interface Plan {
  id: string;
  version: number;
  details: Input;
  state: 'PLANNED';
  independence: keyof typeof INDEPENDENCE;
}

interface View {
  projectId: string;
  environment: string;
  plans: Plan[];
}

interface ActiveInvitations {
  canSend: boolean;
  invitations: ProjectInvitation[];
}

const INDEPENDENCE = {
  REVIEW_REQUIRED: 'Independência a confirmar',
  NONE_DECLARED: 'Nenhum conflito declarado · a conferir',
  DECLARED_CONFLICT: 'Conflito declarado · revisão impedida',
  SAME_PREPARER_AND_REVIEWER: 'Mesmo e-mail na preparação e na revisão · revisão impedida',
} as const;

@Component({
  selector: 'carbonflow-project-invitation-plans',
  imports: [RouterLink, WorkspaceNavigationComponent, DatePipe],
  templateUrl: './project-invitation-plans.html',
})
export class ProjectInvitationPlansComponent {
  private readonly http = inject(HttpClient);
  readonly projectId = inject(ActivatedRoute).snapshot.paramMap.get('projectId') ?? '';
  private readonly root = '/v1/projects/' + encodeURIComponent(this.projectId);
  private readonly url = this.root + '/invitation-plans';
  private readonly invitationUrl = this.root + '/invitations';
  readonly active = signal<ActiveInvitations | null>(null);
  readonly inviteStatus = INVITATION_STATUS;
  private readonly commands = new Map<string, string>();
  readonly activities = Object.keys(ACTIVITIES) as Activity[];
  readonly activityLabel = ACTIVITIES;
  readonly independenceLabel = INDEPENDENCE;
  readonly view = signal<View | null>(null);
  readonly draft = signal<Input | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  private planId = '';
  private version = 0;

  constructor() {
    void this.load();
  }

  async load() {
    this.busy.set(true);
    this.error.set('');
    try {
      this.accept(await firstValueFrom(this.http.get<View>(this.url)));
      await this.loadInvitations();
    } catch {
      this.error.set('Não foi possível consultar os planos de convite deste projeto.');
    } finally {
      this.busy.set(false);
    }
  }

  edit(plan?: Plan) {
    this.planId = plan?.id ?? crypto.randomUUID();
    this.version = plan?.version ?? 0;
    this.draft.set(plan?.details ?? {
      email: '', role: 'PREP', activities: [], deadline: null, competence: null,
      declaredConflict: 'UNKNOWN', conflictNote: null,
    });
    this.notice.set('');
    this.error.set('');
  }

  cancel() {
    this.draft.set(null);
    this.error.set('');
  }

  update(values: Partial<Input>) {
    this.draft.update(current => current ? {...current, ...values} : null);
  }

  setActivity(activity: Activity, selected: boolean) {
    const current = this.draft();
    if (!current) return;
    const activities = selected
      ? [...new Set([...current.activities, activity])]
      : current.activities.filter(item => item !== activity);
    this.update({activities});
  }

  async save(event: Event) {
    event.preventDefault();
    const draft = this.draft();
    if (this.busy() || !draft) return;
    if (!draft.activities.length) {
      this.error.set('Selecione pelo menos uma atividade para o plano.');
      return;
    }
    this.error.set('');
    this.busy.set(true);
    try {
      this.accept(await firstValueFrom(this.http.put<View>(this.url + '/' + this.planId, draft, {
        headers: {'If-Match': '"' + this.version + '"'},
      })));
      this.draft.set(null);
      this.notice.set('Plano de convite salvo como rascunho.');
    } catch (cause) {
      this.error.set(cause instanceof HttpErrorResponse && cause.status === 412
        ? 'O plano mudou em outra sessão. Consulte a versão atual antes de editar novamente.'
        : 'Não foi possível salvar. Confira o e-mail, as atividades e a declaração de conflito.');
    } finally {
      this.busy.set(false);
    }
  }

  canSend(plan: Plan) {
    return this.active()?.canSend === true
      && plan.details.activities.every(activity => activity === 'INTAKE' || activity === 'DOCUMENTATION')
      && ['REVIEW_REQUIRED', 'NONE_DECLARED'].includes(plan.independence)
      && !this.active()?.invitations.some(item => item.planId === plan.id && ['SENT', 'ACCEPTED'].includes(item.state));
  }

  async send(plan: Plan) {
    if (this.busy() || !this.canSend(plan)) return;
    this.busy.set(true);
    this.error.set('');
    const request = plan.id + ':' + plan.version;
    const key = this.commands.get(request) ?? crypto.randomUUID();
    this.commands.set(request, key);
    try {
      const invitation = await firstValueFrom(this.http.post<ProjectInvitation>(
        this.invitationUrl + '/plans/' + encodeURIComponent(plan.id) + '/send', null,
        {headers:{'Idempotency-Key':key, 'If-Match':'"' + plan.version + '"'}}));
      this.active.update(view => view ? {...view,
        invitations:[invitation, ...view.invitations.filter(item => item.id !== invitation.id)]} : view);
      this.commands.delete(request);
      this.notice.set('Convite enviado à caixa do sandbox.');
    } catch (cause) {
      this.error.set(this.invitationError(cause, 'Não foi possível enviar o convite.'));
      if (cause instanceof HttpErrorResponse && cause.status === 403) await this.loadInvitations();
    } finally {
      this.busy.set(false);
    }
  }

  async revoke(invitation: ProjectInvitation) {
    if (this.busy() || !confirm('Revogar este vínculo?')) return;
    this.busy.set(true);
    this.error.set('');
    try {
      this.active.set(await firstValueFrom(this.http.post<ActiveInvitations>(
        this.invitationUrl + '/' + encodeURIComponent(invitation.id) + '/revoke', null)));
      this.notice.set('Vínculo revogado. O histórico do convite foi preservado.');
    } catch (cause) {
      this.error.set(this.invitationError(cause, 'Não foi possível revogar o vínculo.'));
    } finally {
      this.busy.set(false);
    }
  }

  private async loadInvitations() {
    try {
      this.active.set(await firstValueFrom(this.http.get<ActiveInvitations>(this.invitationUrl)));
    } catch {
      this.active.set(null);
    }
  }

  private invitationError(cause: unknown, fallback: string) {
    return cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string' ? cause.error.message : fallback;
  }

  private accept(view: View) {
    if (view.projectId !== this.projectId || view.environment !== 'SANDBOX') throw new Error('Projeto incorreto');
    this.view.set(view);
  }
}
