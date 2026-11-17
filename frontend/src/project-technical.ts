import {Component, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

interface ProjectMetadata {
  projectId: string;
  organizationId: string;
  environment: string;
  state: string;
  version: number;
  title: string;
  description: string | null;
}

interface IntakeData {
  activity: string;
  country: string | null;
  state: string | null;
  locality: string | null;
  candidateMethodology: string | null;
  referenceActDate: string | null;
  notes: string | null;
}

interface Reason {
  code: string;
  rule: string;
  message: string;
}

interface TechnicalIntake {
  projectId: string;
  organizationId: string;
  environment: string;
  version: number;
  state: string;
  reviewState: string;
  data: IntakeData | null;
  outstandingGates: Reason[];
}

interface PreliminaryDossier {
  id: string;
  revision: number;
  intakeVersion: number;
  state: string;
  documentKind: string;
  sourceTemplate: string;
  sourceEdition: string;
  sections: string[];
  blockers: Reason[];
  intake: IntakeData;
}

interface Capabilities {
  projectId: string;
  environment: string;
  modules: Record<string, string>;
}

@Component({
  selector: 'carbonflow-project-technical',
  imports: [RouterLink],
  templateUrl: './project-technical.html',
})
export class ProjectTechnicalComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  readonly projectId = this.route.snapshot.paramMap.get('projectId') ?? '';
  private readonly root = '/v1/projects/' + encodeURIComponent(this.projectId);
  private readonly technical = this.root + '/technical';

  readonly project = signal<ProjectMetadata | null>(null);
  readonly capabilities = signal<Capabilities | null>(null);
  readonly intake = signal<TechnicalIntake | null>(null);
  readonly dossier = signal<PreliminaryDossier | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  private saveKey = '';
  private savePayload = '';
  private dossierKey = '';
  private dossierVersion = -1;

  constructor() {
    void this.load();
  }

  readonly canPrepare = () => {
    const current = this.intake();
    const dossier = this.dossier();
    return !!current && current.version > 0 && (!dossier || dossier.intakeVersion !== current.version);
  };

  private async load() {
    this.loading.set(true);
    this.error.set('');
    try {
      const project = await firstValueFrom(this.http.get<ProjectMetadata>(this.root + '/metadata'));
      if (project.projectId !== this.projectId || project.environment !== 'SANDBOX') {
        throw new Error('Contexto incorreto');
      }
      this.project.set(project);
      const capabilities = await firstValueFrom(this.http.get<Capabilities>(this.technical + '/capabilities'));
      this.capabilities.set(capabilities);
      const intake = await firstValueFrom(this.http.get<TechnicalIntake>(this.technical + '/intake'));
      this.intake.set(intake);
      this.dossier.set(await firstValueFrom(this.http.get<PreliminaryDossier | null>(
        this.technical + '/preliminary-dossier')));
    } catch (cause: unknown) {
      this.project.set(null);
      this.capabilities.set(null);
      this.intake.set(null);
      this.dossier.set(null);
      this.error.set(this.errorMessage(cause, 'Não foi possível abrir a preparação técnica do projeto.'));
    } finally {
      this.loading.set(false);
    }
  }

  async save(event: Event, activity: string, country: string, state: string, locality: string,
             candidateMethodology: string, referenceActDate: string, notes: string) {
    event.preventDefault();
    if (this.saving() || this.loading() || !this.intake()) return;
    if (!activity.trim() || activity.trim().length > 400) {
      this.error.set('Descreva a atividade planejada com até 400 caracteres.');
      return;
    }
    const input: IntakeData = {
      activity: activity.trim(),
      country: country.trim().toUpperCase() || null,
      state: state.trim() || null,
      locality: locality.trim() || null,
      candidateMethodology: candidateMethodology.trim() || null,
      referenceActDate: referenceActDate || null,
      notes: notes.trim() || null,
    };
    const expected = this.intake()!.version;
    const payload = JSON.stringify({project: this.projectId, expected, ...input});
    if (!this.saveKey || this.savePayload !== payload) {
      this.saveKey = crypto.randomUUID();
      this.savePayload = payload;
    }
    this.saving.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const result = await firstValueFrom(this.http.put<TechnicalIntake>(this.technical + '/intake', input, {
        headers: {'If-Match': '"' + expected + '"', 'Idempotency-Key': this.saveKey},
      }));
      this.intake.set(result);
      this.saveKey = '';
      this.savePayload = '';
      this.notice.set('Informações preliminares salvas (revisão ' + result.version + ').');
    } catch (cause: unknown) {
      this.error.set(this.errorMessage(cause, 'Falha ao salvar o intake. Verifique os dados e tente novamente.'));
      if (cause instanceof HttpErrorResponse && cause.status >= 400 && cause.status < 500) {
        this.saveKey = '';
      }
    } finally {
      this.saving.set(false);
    }
  }

  async prepare() {
    const intake = this.intake();
    if (this.saving() || !intake || !this.canPrepare()) return;
    if (!this.dossierKey || this.dossierVersion !== intake.version) {
      this.dossierKey = crypto.randomUUID();
      this.dossierVersion = intake.version;
    }
    this.saving.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const prepared = await firstValueFrom(this.http.post<PreliminaryDossier>(
        this.technical + '/preliminary-dossier', {}, {
          headers: {'Idempotency-Key': this.dossierKey, 'If-Match': '"' + intake.version + '"'},
        }));
      this.dossier.set(prepared);
      this.dossierKey = '';
      this.dossierVersion = -1;
      this.notice.set('Prévia de dossiê criada. Ela não está pronta para auditoria ou registro.');
    } catch (cause: unknown) {
      this.error.set(this.errorMessage(cause, 'Falha ao preparar a prévia do dossiê.'));
      if (cause instanceof HttpErrorResponse && cause.status >= 400 && cause.status < 500) {
        this.dossierKey = '';
        this.dossierVersion = -1;
      }
    } finally {
      this.saving.set(false);
    }
  }

  private errorMessage(cause: unknown, fallback: string) {
    return cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string'
      ? cause.error.message : fallback;
  }
}
