import {Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {plainTerm, plannedRole} from './calculation-language';

type Destination = {area:string;screen:string;step:string|null};
type Requirement = {
  requirementId:string;methodologyId?:string|null;edition?:string|null;ruleRef?:string|null;
  state:string;situation:string;reason:string;ownerRole:string;
  nextAction:string;actionLabel:string|null;destination:Destination|null;group:string;
  clientAction:boolean;blocksExAntePd:boolean;admitsE8:boolean;affectedOutputs:string[];
};
type Summary = {mine:number;awaitingSource:number;awaitingReview:number;outOfScope:number};
type View = {
  projectId:string;methodology:string|null;openBranch:boolean;diagnosticVisible:boolean;
  canRunE8:boolean;canIssueVcu:boolean;exAnteRequiresMeasuredEnergy:boolean;
  limits:string;summary:Summary;requirements:Requirement[];
};

const GROUPS = [
  {id:'MINE', title:'Ações desta conta'},
  {id:'AWAITING_SOURCE', title:'Aguardando fontes'},
  {id:'AWAITING_REVIEW', title:'Aguardando revisão'},
  {id:'OUT_OF_SCOPE', title:'Fora do recorte'},
] as const;

@Component({
  selector:'carbonflow-project-requirements',
  standalone:true,
  templateUrl:'./project-requirements.html',
})
export class ProjectRequirementsComponent implements OnChanges {
  @Input({required:true}) projectId = '';
  @Input() canQuantify = false;
  @Output() readonly openSection = new EventEmitter<string>();
  private readonly http = inject(HttpClient);
  readonly groups = GROUPS;
  readonly roleLabel = plannedRole;

  requirementName(code:string):string {
    const label = plainTerm(code);
    return label === code ? '' : label;
  }

  editionLabel(item:Requirement):string {
    const id = item.methodologyId;
    if (!id || id === 'UNSET') return '';
    return item.edition ? id + ' v' + item.edition : id;
  }
  readonly loading = signal(true);
  readonly error = signal('');
  readonly view = signal<View|null>(null);

  ngOnChanges(changes:SimpleChanges) {
    if (changes['projectId']) void this.load();
  }

  items(group:string) {
    return this.view()?.requirements.filter(item => item.group === group) ?? [];
  }

  async load() {
    this.loading.set(true);
    this.error.set('');
    try {
      const view = await firstValueFrom(this.http.get<View>(
        '/v1/projects/' + encodeURIComponent(this.projectId) + '/technical/requirements'));
      if (view.projectId !== this.projectId || view.canRunE8 || view.canIssueVcu
          || view.exAnteRequiresMeasuredEnergy || view.requirements.some(item => item.admitsE8)) {
        throw new Error('Diagnóstico inconsistente');
      }
      this.view.set(view);
    } catch (cause:unknown) {
      this.view.set(null);
      this.error.set(cause instanceof HttpErrorResponse && cause.status === 404
        ? 'O diagnóstico não está disponível para esta conta.'
        : 'Não foi possível ler o diagnóstico do projeto.');
    } finally {
      this.loading.set(false);
    }
  }
}
