import {Component, computed, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

type SourceStatus = 'MISSING_SOURCE_METADATA' | 'METADATA_ONLY'
  | 'REFERENCE_AUTHORIZED_NOT_ADMITTED' | 'PROJECT_SPECIFIC_EVIDENCE';
type Stage = 'APPLICABILITY' | 'BASELINE' | 'ADDITIONALITY'
  | 'QUANTIFICATION' | 'MONITORING' | 'DOCUMENTS';

interface StaffSession {verified: boolean; expiresAt: string | null}
interface SourceNeed {
  code: string;
  stage: Stage;
  kind: 'NORMATIVE' | 'DATASET' | 'PROJECT_EVIDENCE';
  title: string;
  purpose: string;
  sourceId: string | null;
  sourceEdition: string | null;
  officialUri: string | null;
  mandatory: boolean;
  conditionNote: string;
  status: SourceStatus;
  nextAction: string;
}
interface SourceReadiness {
  methodology: string;
  edition: string;
  scenario: string;
  asOf: string;
  normativeRuleStatus: string;
  missingSourceCount: number;
  missingReferenceRightsCount: number;
  reviewPendingCount: number;
  projectEvidenceCount: number;
  needs: SourceNeed[];
}

@Component({
  selector: 'carbonflow-platform-source-readiness',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './platform-source-readiness.html',
  styleUrl: './platform-source-readiness.css',
})
export class PlatformSourceReadinessComponent {
  private readonly http = inject(HttpClient);
  readonly session = signal<StaffSession | null>(null);
  readonly readiness = signal<SourceReadiness | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly stage = signal('ALL');
  readonly statusFilter = signal('ALL');

  readonly stages: {id: Stage; label: string}[] = [
    {id:'APPLICABILITY',label:'Aplicabilidade'},
    {id:'BASELINE',label:'Linha de base'},
    {id:'ADDITIONALITY',label:'Adicionalidade'},
    {id:'QUANTIFICATION',label:'Quantificação'},
    {id:'MONITORING',label:'Monitoramento'},
    {id:'DOCUMENTS',label:'Documentos'},
  ];
  readonly statuses: {id: SourceStatus; label: string}[] = [
    {id:'MISSING_SOURCE_METADATA',label:'Fonte ainda não cadastrada'},
    {id:'METADATA_ONLY',label:'Apenas metadados / sem autorização de referência'},
    {id:'REFERENCE_AUTHORIZED_NOT_ADMITTED',label:'Autorização restrita a algum escopo; admissão pendente'},
    {id:'PROJECT_SPECIFIC_EVIDENCE',label:'Documento a obter em cada projeto'},
  ];
  readonly filtered = computed(() => (this.readiness()?.needs ?? []).filter(
    need => (this.stage() === 'ALL' || this.stage() === need.stage)
      && (this.statusFilter() === 'ALL' || this.statusFilter() === need.status)));

  constructor() { void this.loadSession(); }

  private async loadSession() {
    this.loading.set(true);
    this.error.set('');
    try {
      const result = await firstValueFrom(this.http.get<StaffSession>('/v1/compliance/session'));
      this.session.set(result);
      if (result.verified && result.expiresAt && Date.parse(result.expiresAt) > Date.now()) {
        await this.loadReadiness();
      } else {
        this.readiness.set(null);
      }
    } catch {
      this.session.set(null);
      this.readiness.set(null);
      this.error.set('A sessão administrativa não está disponível. Entre na conta e confira o acesso da equipe.');
    } finally {
      this.loading.set(false);
    }
  }

  async verify(event: Event, code: HTMLInputElement) {
    event.preventDefault();
    if (this.loading()) return;
    const value = code.value;
    code.value = '';
    this.loading.set(true);
    this.error.set('');
    try {
      const result = await firstValueFrom(this.http.post<StaffSession>(
        '/v1/compliance/session', {code: value}));
      this.session.set(result);
      if (result.verified) await this.loadReadiness();
    } catch (reason) {
      this.readiness.set(null);
      this.session.set({verified:false,expiresAt:null});
      const status = reason instanceof HttpErrorResponse ? reason.status : 0;
      this.error.set(status === 429 ? 'Limite de tentativas. Tente novamente após a janela de segurança.'
        : status === 403 ? 'Código inválido ou já utilizado.'
        : 'Não foi possível verificar o acesso administrativo.');
    } finally {
      this.loading.set(false);
    }
  }

  refresh() {
    if (this.loading()) return;
    void this.loadSession();
  }

  private async loadReadiness() {
    const result = await firstValueFrom(this.http.get<SourceReadiness>(
      '/v1/platform/source-readiness/VM0038'));
    this.readiness.set(result);
  }

  onStage(event: Event) { this.stage.set((event.target as HTMLSelectElement).value); }
  onStatus(event: Event) { this.statusFilter.set((event.target as HTMLSelectElement).value); }

  stageLabel(value: string): string {
    return this.stages.find(stage => stage.id === value)?.label ?? value;
  }

  statusLabel(value: string): string {
    return this.statuses.find(status => status.id === value)?.label ?? value;
  }

  // Server-side authorization is always required: this guard only prevents stale client rendering.
  currentData(): SourceReadiness | null {
    if (!this.session()?.verified || !this.session()?.expiresAt
        || Date.parse(this.session()!.expiresAt!) <= Date.now()) return null;
    return this.readiness();
  }
}
