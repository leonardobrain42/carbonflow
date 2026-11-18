import {afterNextRender, Component, computed, ElementRef, inject, Injector, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse, provideHttpClient} from '@angular/common/http';
import {bootstrapApplication} from '@angular/platform-browser';
import {provideRouter, RouterOutlet, Routes} from '@angular/router';
import {firstValueFrom} from 'rxjs';
import {AccessPanel, SessionProject} from './access-panel';
import {IntakePanel} from './intake-panel';
import {ImportPanel} from './import-panel';
import {SelectedCalculations} from './selected-calculations';
import {MonitoringPeriods} from './monitoring-periods';
import {DossierPanel} from './dossier-panel';
import {ListingPanel} from './listing-panel';
import {AuditPanel} from './audit-panel';
import {IssuancePanel} from './issuance-panel';
import {OperationsPanel} from './operations-panel';
import {WorkspaceComponent} from './workspace';
import {ProjectInvitationPlansComponent} from './project-invitation-plans';
import {ProjectExperienceComponent} from './project-experience';
import {ProjectTechnicalComponent} from './project-technical';
import {ProjectTriageComponent} from './project-triage';

interface Memory {
  be: string;
  pe: string;
  le: string;
  er: string;
  erDisplay: string;
}

interface Cut {
  vintage: number;
  start: string;
  endInclusive: string;
  edKwh: string;
  ecKwh: string;
  emissions: Memory;
}

interface Period {
  id: string;
  year: number;
  start: string;
  endInclusive: string;
  cuts: Cut[];
  emissions: Memory;
}

interface Checkpoint {
  effectiveAt: string;
  m1: string;
  m2: string;
  v1: string;
  i1: string;
}

interface Run {
  id: string;
  projectId: string;
  zone: string;
  factor: {id: string; value: string};
  provenance: {kitVersion: string; reference: {id: string; version: number}};
  results: {
    afecKwhPer100Mi: string;
    mpg: string;
    dy: string;
    periods: Period[];
  };
  checkpoints: Checkpoint[];
}

interface Session {
  identity: {id: string; organization: string; name: string};
  projects: SessionProject[];
}

const SESSION = '/v1/session';
const DEFAULT = 'E6-EXEMPLOS';
const MISSING_EC = 'E6-EXEMPLOS-SEM-EC';

@Component({
  selector: 'carbonflow-pilot',
  templateUrl: './pilot.html',
  imports: [AccessPanel, IntakePanel, ImportPanel, SelectedCalculations, MonitoringPeriods, DossierPanel, ListingPanel, AuditPanel, IssuancePanel, OperationsPanel],
})
class PilotComponent {
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  private readonly loginField = viewChild<ElementRef<HTMLInputElement>>('identity');
  private readonly mainHeading = viewChild<ElementRef<HTMLElement>>('mainHeading');
  private readonly dateTime = new Intl.DateTimeFormat('pt-BR', {
    dateStyle: 'short', timeStyle: 'short', timeZone: 'America/Sao_Paulo',
  });
  private readonly retryKeys = new Map<string, string>();

  readonly session = signal<Session | null>(null);
  readonly opening = signal(true);
  readonly signingIn = signal(false);
  readonly sessionError = signal('');
  readonly project = computed(() => this.session()?.projects[0]);
  readonly canReadRun = computed(() => this.project()?.roles.some(role => ['PREP', 'REV', 'PP'].includes(role)) ?? false);
  readonly canCalculate = computed(() => this.project()?.project === 'PIL-SYN-BR-001'
    && (this.project()?.roles.includes('PREP') ?? false));
  readonly run = signal<Run | null>(null);
  readonly busy = signal(true);
  readonly status = signal('');
  readonly error = signal('');
  readonly referenceFixture = DEFAULT;
  readonly invalidFixture = MISSING_EC;

  constructor() {
    void this.restoreSession();
  }

  private async restoreSession() {
    try {
      this.session.set(await firstValueFrom(this.http.get<Session>(SESSION)));
      await this.loadRuns();
    } catch (error: unknown) {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        this.sessionError.set('Falha ao consultar a sessão.');
      }
    } finally {
      this.opening.set(false);
    }
  }

  async login(event: Event, identity: string, password: string) {
    event.preventDefault();
    if (this.signingIn()) return;
    this.signingIn.set(true);
    this.sessionError.set('');
    try {
      await firstValueFrom(this.http.post(SESSION, {identity: identity.trim().toUpperCase(), password}));
      this.session.set(await firstValueFrom(this.http.get<Session>(SESSION)));
      afterNextRender(() => this.mainHeading()?.nativeElement.focus(), {injector: this.injector});
      await this.loadRuns();
    } catch {
      this.sessionError.set('Não foi possível entrar. Confira as credenciais do piloto.');
    } finally {
      this.signingIn.set(false);
    }
  }

  async logout() {
    try {
      await firstValueFrom(this.http.delete(SESSION));
      this.session.set(null);
      this.run.set(null);
      this.retryKeys.clear();
      this.error.set('');
      this.status.set('');
      this.sessionError.set('');
      afterNextRender(() => this.loginField()?.nativeElement.focus(), {injector: this.injector});
    } catch {
      this.sessionError.set('Falha ao encerrar a sessão. Tente novamente.');
    }
  }

  private async loadRuns() {
    if (!this.canReadRun()) {
      this.busy.set(false);
      return;
    }
    this.busy.set(true);
    const session = this.session();
    try {
      const run = await firstValueFrom(this.http.get<Run | null>(this.runsUrl() + '/latest'));
      if (this.session() === session) this.run.set(run);
    } catch {
      if (this.session() === session) this.error.set('Falha ao buscar a última execução.');
    } finally {
      if (this.session() === session) this.busy.set(false);
    }
  }

  private runsUrl() {
    return '/v1/projects/' + encodeURIComponent(this.project()?.project ?? '') + '/runs';
  }

  async execute(fixtureId: string) {
    if (this.busy() || !this.canCalculate()) return;

    const session = this.session();
    this.busy.set(true);
    this.error.set('');
    this.status.set('');
    const key = this.retryKeys.get(fixtureId) ?? crypto.randomUUID();
    this.retryKeys.set(fixtureId, key);
    try {
      const run = await firstValueFrom(this.http.post<Run>(this.runsUrl(), {fixture: {id: fixtureId, version: 1}}, {
        headers: {'Idempotency-Key': key},
      }));
      if (this.session() !== session) return;
      this.run.set(run);
      this.retryKeys.delete(fixtureId);
      this.status.set('Execução registrada e memória disponível.');
    } catch (error: unknown) {
      if (this.session() !== session) return;
      if (error instanceof HttpErrorResponse && error.status === 422) {
        this.error.set(error.error?.message ?? 'A massa foi recusada.');
        this.retryKeys.delete(fixtureId);
      } else {
        this.error.set('Falha ao confirmar a execução. Tente novamente.');
        if (error instanceof HttpErrorResponse && error.status >= 400 && error.status < 500) {
          this.retryKeys.delete(fixtureId);
        }
      }
    } finally {
      if (this.session() === session) this.busy.set(false);
    }
  }

  decimal(value: string) {
    const [integer, fraction] = value.split('.');
    const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
    return fraction === undefined ? grouped : grouped + ',' + fraction;
  }

  date(value: string) {
    const [year, month, day] = value.split('-');
    return day + '/' + month + '/' + year;
  }

  instant(value: string) {
    return this.dateTime.format(new Date(value));
  }

  state(value: string) {
    const names: Readonly<Record<string, string>> = {
      FECHADO: 'Fechado',
      EM_CONFERENCIA: 'Em conferência',
      COLETANDO: 'Em coleta',
      EM_REVISAO: 'Em revisão',
      NOVO: 'A iniciar',
      APROVADO: 'Aprovado',
      AGUARDANDO_DEPOSITO: 'Aguardando depósito',
    };
    return names[value] ?? value;
  }

  apiLink(id: string) {
    return this.runsUrl() + '/' + id;
  }
}

@Component({
  selector: 'carbonflow-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
})
class CarbonFlowRoot {}

const routes: Routes = [
  {path: '', pathMatch: 'full', redirectTo: 'pilot'},
  {path: 'pilot', component: PilotComponent},
  {path: 'workspace', component: WorkspaceComponent},
  {path: 'workspace/projects/new', component: WorkspaceComponent},
  {path: 'workspace/projects/:projectId/invitation-plans', component: ProjectInvitationPlansComponent},
  {path: 'workspace/projects/:projectId/experience', component: ProjectExperienceComponent},
  {path: 'workspace/projects/:projectId/technical', component: ProjectTechnicalComponent},
  {path: 'workspace/projects/:projectId/triage', component: ProjectTriageComponent},
  {path: '**', redirectTo: 'pilot'},
];

bootstrapApplication(CarbonFlowRoot, {
  providers: [provideHttpClient(), provideRouter(routes)],
}).catch(console.error);
