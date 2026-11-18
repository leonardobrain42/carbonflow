import {Component, computed, inject, input, output, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {toObservable, toSignal} from '@angular/core/rxjs-interop';
import {catchError, forkJoin, map, of, startWith, switchMap} from 'rxjs';
import {TechnicalGlossaryComponent} from './technical-glossary';

type Act = 'PD' | 'MR';
interface Need {field: string; label: string; reason: string; editable: boolean; screen: string | null}
interface Requirement {
  code: string;
  title: string;
  field: string;
  classification: string;
  status: string;
  reason: string;
  dependencies: string[];
  source: {id: string; edition: string};
  locator: string;
  interpretation: boolean;
}
interface Gate {code: string; title: string; status: string}
interface Readiness {
  guideRevision: number;
  catalogState: 'RESOLVED_SYNTHETIC' | 'UNKNOWN_REVIEW_REQUIRED' | 'SOURCE_USAGE_BLOCKED';
  catalogReason: string;
  packId: string | null;
  packEdition: number | null;
  dateBasis: 'ACT_DATE' | 'PERIOD_START';
  dateUsed: string | null;
  window: {from: string; until: string | null} | null;
  requirements: Requirement[];
  gates: Gate[];
  acquisitionPlan: Need[];
}
interface Source {
  id: string;
  edition: string;
  category: string;
  title: string;
  rightsHolder: string;
  officialUri: string | null;
  origin: string;
  requirementReferenceAvailable: boolean;
}
interface SourcePolicy {sources: Source[]}
type Result =
  | {kind: 'idle' | 'loading' | 'error' | 'unavailable'; key: string}
  | {kind: 'loaded'; key: string; report: Readiness; sources: Source[]; previousEdition?: number};

@Component({
  selector: 'carbonflow-guide-references',
  imports: [TechnicalGlossaryComponent],
  templateUrl: './guide-references.html',
})
export class GuideReferencesComponent {
  readonly projectId = input.required<string>();
  readonly section = input.required<string>();
  readonly revision = input.required<number>();
  readonly canOpenIntake = input(false);
  readonly prepare = output<void>();
  private readonly http = inject(HttpClient);
  private readonly selectedAct = signal<Act | null>(null);
  private readonly requestNumber = signal(0);
  private readonly last = new Map<string, {packId: string; edition: number}>();
  readonly act = computed(() => this.selectedAct() ??
    (['monitoring', 'quantification'].includes(this.section()) ? 'MR' : 'PD'));
  private readonly context = computed(() => {
    const project = this.projectId();
    const section = this.section();
    const act = this.act();
    return {project, section, act, key: JSON.stringify([project, section, this.revision(), act])};
  });
  private readonly request = computed(() => ({...this.context(), number: this.requestNumber()}));
  private readonly response = toSignal(toObservable(this.request).pipe(
    switchMap(({project, section, act, key, number}) => {
      if (!number) return of<Result>({kind: 'idle', key});
      const base = '/v1/projects/' + encodeURIComponent(project);
      const editionKey = JSON.stringify([project, section, act]);
      return forkJoin({
        report: this.http.get<Readiness>(base + '/sections/' + encodeURIComponent(section) + '/readiness', {params: {act}}),
        sources: this.http.get<SourcePolicy>(base + '/source-policy'),
      }).pipe(
        map(({report, sources}): Result => {
          const previous = this.last.get(editionKey);
          if (report.packId && report.packEdition !== null) {
            this.last.set(editionKey, {packId: report.packId, edition: report.packEdition});
          } else {
            this.last.delete(editionKey);
          }
          return {kind: 'loaded', key, report, sources: sources.sources,
            previousEdition: previous && report.packId && previous.packId !== report.packId ? previous.edition : undefined};
        }),
        catchError((cause: unknown) => {
          this.last.delete(editionKey);
          const inaccessible = cause instanceof HttpErrorResponse && [401, 403, 404].includes(cause.status);
          return of<Result>({kind: inaccessible ? 'unavailable' : 'error', key});
        }),
        startWith<Result>({kind: 'loading', key}),
      );
    }),
  ));
  readonly state = computed(() => {
    const result = this.response();
    return result?.key === this.context().key ? result : {kind: 'idle', key: this.context().key} as const;
  });
  readonly requirementStatus: Record<string, string> = {
    COLLECT_DATA: 'Faltam dados', DATA_RECORDED: 'Dado registrado no rascunho',
    REVIEW_REQUIRED: 'Avaliação humana pendente', DEPENDENCY_PENDING: 'Dependência pendente',
    RECHECK_REQUIRED: 'Precisa de rechecagem', NOT_APPLICABLE: 'Sem incidência no exemplo',
  };
  readonly gateStatus: Record<string, string> = {
    RESOLVED_FOR_EXAMPLE: 'Enquadrado no exemplo', NEEDS_DATA: 'Pendências a resolver',
    DRAFT_DATA_RECORDED: 'Dados de rascunho registrados', NOT_EVALUATED: 'Aguardando avaliação',
  };
  readonly classification: Record<string, string> = {
    MANDATORY: 'Obrigatória no exemplo', CONDITIONAL: 'Condicional', OPTIONAL: 'Opcional',
    NOT_APPLICABLE: 'Não aplicável', INDETERMINATE: 'Incidência a confirmar',
  };

  selectAct(value: string) {
    this.selectedAct.set(value === 'MR' ? 'MR' : 'PD');
  }

  reload() { this.requestNumber.update(value => value + 1); }

  date(value: string) {
    const [year, month, day] = value.split('-');
    return day + '/' + month + '/' + year;
  }
}
