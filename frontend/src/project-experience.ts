import {Component, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

export type ExperienceMode = 'GUIDED' | 'TECHNICAL';
type Role = 'pp' | 'prep' | 'rev' | 'op' | 'vvb';

interface Preferences {
  mode: ExperienceMode;
  declaredProfile: string | null;
  declaredCompetence: string | null;
  responsibilities: Record<Role, string | null>;
}

interface Experience {
  projectId: string;
  environment: 'SANDBOX';
  version: number;
  preferences: Preferences;
}

@Component({
  selector: 'carbonflow-project-experience',
  imports: [RouterLink],
  templateUrl: './project-experience.html',
})
export class ProjectExperienceComponent {
  private readonly http = inject(HttpClient);
  readonly projectId = inject(ActivatedRoute).snapshot.paramMap.get('projectId') ?? '';
  private readonly url = '/v1/projects/' + encodeURIComponent(this.projectId) + '/experience';
  readonly roles = [
    {id: 'pp', title: 'Proponente (PP)', description: 'Responde pelas decisões e obrigações do projeto.'},
    {id: 'prep', title: 'Preparação técnica (PREP)', description: 'Organiza dados, evidências e estudos.'},
    {id: 'rev', title: 'Revisão técnica (REV)', description: 'Revisa a análise e registra ressalvas.'},
    {id: 'op', title: 'Operação e monitoramento (OP)', description: 'Organiza as medições e os registros de operação.'},
    {id: 'vvb', title: 'Validação e verificação independente (VVB)', description: 'Avalia o projeto conforme o programa aplicável.'},
  ] as const;
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly experience = signal<Experience | null>(null);
  readonly draft = signal<Preferences | null>(null);

  constructor() {
    void this.load();
  }

  private async load() {
    try {
      this.accept(await firstValueFrom(this.http.get<Experience>(this.url)));
    } catch {
      this.error.set('Não foi possível abrir as preferências deste projeto.');
    } finally {
      this.loading.set(false);
    }
  }

  selectMode(mode: ExperienceMode) {
    this.update({mode});
  }

  update(values: Partial<Preferences>) {
    const current = this.draft();
    if (current) this.draft.set({...current, ...values});
  }

  assign(role: Role, value: string) {
    const current = this.draft();
    if (current) this.update({responsibilities: {...current.responsibilities, [role]: value}});
  }

  async save(event: Event) {
    event.preventDefault();
    const saved = this.experience();
    const draft = this.draft();
    if (!saved || !draft || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const headers = {'If-Match': '"' + saved.version + '"'};
      this.accept(await firstValueFrom(this.http.put<Experience>(this.url, draft, {headers})));
      this.notice.set('Preferências e responsabilidades salvas.');
    } catch (cause) {
      this.error.set(cause instanceof HttpErrorResponse && cause.status === 412
        ? 'As preferências foram atualizadas em outra sessão. Recarregue a página para consultar a versão atual.'
        : 'Não foi possível salvar suas preferências. Confira os dados e tente novamente.');
    } finally {
      this.saving.set(false);
    }
  }

  private accept(view: Experience) {
    if (view.projectId !== this.projectId || view.environment !== 'SANDBOX') throw new Error('Projeto incorreto');
    this.experience.set(view);
    this.draft.set(view.preferences);
  }
}
