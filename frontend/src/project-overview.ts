import {Component, computed, inject} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {catchError, distinctUntilChanged, map, of, startWith, Subject, switchMap} from 'rxjs';
import {WorkspaceNavigationComponent} from './workspace-navigation';

type Role = 'DRAFT_OWNER' | 'PREP' | 'REV';
type TaskState = 'NOT_STARTED' | 'NEEDS_DATA' | 'IN_PROGRESS' | 'COMPLETED' | 'IN_REVIEW' | 'RETURNED' | 'REQUIRES_RECHECK';
interface Destination {area: 'triage' | 'technical'; screen: string | null; guide: string | null}
interface Task {
  id: string;
  kind: 'TRIAGE' | 'INTAKE' | 'GUIDE' | 'DOSSIER' | 'SECTION' | 'REVIEW';
  title: string;
  state: TaskState;
  reason: string;
  responsible: Role;
  sourceVersion: number;
  destination: Destination | null;
  actionable: boolean;
}
interface Journey {
  projectId: string;
  title: string;
  environment: string;
  projectState: string;
  role: Role;
  methodology: string | null;
  methodologyConfigured: boolean;
  nextTask: string | null;
  tasks: Task[];
}
type LoadState =
  | {kind: 'loading'}
  | {kind: 'loaded'; journey: Journey}
  | {kind: 'unavailable'}
  | {kind: 'error'};

@Component({
  selector: 'carbonflow-project-overview',
  imports: [RouterLink, WorkspaceNavigationComponent],
  templateUrl: './project-overview.html',
  styleUrl: './project-overview.css',
})
export class ProjectOverviewComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly http = inject(HttpClient);
  private readonly refresh = new Subject<void>();

  readonly state = toSignal(this.route.paramMap.pipe(
    map(params => params.get('projectId') ?? ''),
    distinctUntilChanged(),
    switchMap(project => this.refresh.pipe(
      startWith(undefined),
      switchMap(() => this.http.get<Journey>('/v1/projects/' + encodeURIComponent(project) + '/journey').pipe(
        map((journey): LoadState => ({kind: 'loaded', journey})),
        catchError((cause: unknown) => of<LoadState>({
          kind: cause instanceof HttpErrorResponse && [401, 403, 404].includes(cause.status) ? 'unavailable' : 'error',
        })),
        startWith<LoadState>({kind: 'loading'}),
      )),
    )),
  ), {initialValue: {kind: 'loading'} as LoadState});

  readonly journey = computed(() => {
    const state = this.state();
    return state.kind === 'loaded' ? state.journey : null;
  });
  readonly next = computed(() => {
    const journey = this.journey();
    return journey?.tasks.find(task => task.id === journey.nextTask) ?? null;
  });
  readonly attention = computed(() => this.journey()?.tasks.filter(task =>
    ['NEEDS_DATA', 'REQUIRES_RECHECK', 'RETURNED'].includes(task.state)) ?? []);

  readonly groups = computed(() => {
    const tasks = this.journey()?.tasks ?? [];
    return [
      {title: 'Desenvolvimento', items: tasks.filter(task => ['TRIAGE', 'INTAKE', 'GUIDE'].includes(task.kind))},
      {title: 'Documentos', items: tasks.filter(task => ['DOSSIER', 'SECTION'].includes(task.kind))},
      {title: 'Revisão interna', items: tasks.filter(task => task.kind === 'REVIEW')},
    ].filter(group => group.items.length).map(group => ({
      ...group, hasNext: group.items.some(task => task.id === this.next()?.id),
    }));
  });
  readonly roleNames = {DRAFT_OWNER: 'Titular do rascunho', PREP: 'Preparação técnica (PREP)', REV: 'Revisão interna (REV)'};
  readonly stateNames: Record<TaskState, string> = {
    NOT_STARTED: 'A iniciar',
    NEEDS_DATA: 'Aguardando dados',
    IN_PROGRESS: 'Em preparação',
    COMPLETED: 'Registrada',
    IN_REVIEW: 'Em revisão interna',
    RETURNED: 'Devolvida para ajuste',
    REQUIRES_RECHECK: 'Precisa de rechecagem',
  };

  reload() { this.refresh.next(); }

  routeFor(project: string, destination: Destination) {
    return ['/workspace/projects', project, destination.area];
  }

  queryFor(destination: Destination) {
    return {secao: destination.screen, guia: destination.guide};
  }
}
