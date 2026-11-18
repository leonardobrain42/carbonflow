import {Component, computed, inject, input, output, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

export interface EvidenceRequestLink {id: string; field: string}
interface Candidate {
  id: string;
  field: string;
  value: string;
  currentVersion: number;
  factVersion: number | null;
  divergent: boolean;
  canReview: boolean;
  requestId: string | null;
  requestVersion: number | null;
  evidence: {id:string; guideVersion:number; sha256:string; locator:string; author:string; savedAt:string};
  review: {outcome:'ACCEPTED'|'REJECTED'; reason:string; reviewer:string; at:string} | null;
}
interface View {candidates:Candidate[]; canPropose:boolean}

@Component({
  selector:'carbonflow-guide-facts-panel',
  templateUrl:'./guide-facts-panel.html',
})
export class GuideFactsPanelComponent {
  readonly projectId = input.required<string>();
  readonly section = input.required<string>();
  readonly version = input.required<number>();
  readonly fields = input.required<{key:string; label:string}[]>();
  readonly answers = input.required<Record<string,string>>();
  readonly editable = input.required<boolean>();
  readonly request = input<EvidenceRequestLink|null>(null);
  readonly clearRequest = output<void>();
  readonly origin = output<number>();
  private readonly http = inject(HttpClient);
  readonly view = signal<View|null>(null);
  readonly reviewing = signal<Candidate|null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly availableFields = computed(() => this.fields().filter(field => !!this.answers()[field.key]));
  readonly linkedField = computed(() => this.availableFields().find(field => field.key === this.request()?.field) ?? null);
  private get url() {
    return '/v1/projects/'+encodeURIComponent(this.projectId())+'/sections/'+encodeURIComponent(this.section())+'/facts';
  }

  label(key:string) { return this.fields().find(field => field.key === key)?.label ?? key; }
  async load() { await this.run(() => this.refresh()); }
  async propose(event: Event, field: string, requestId?: string) {
    event.preventDefault();
    if (!field || !this.editable()) return;
    await this.run(async () => {
      await firstValueFrom(this.http.post<Candidate>(this.url,{guideVersion:this.version(),field,requestId:requestId ?? null}));
      this.clearRequest.emit();
      await this.refresh();
    });
  }
  async decide(event: Event, outcome: string, reason: string) {
    event.preventDefault();
    const candidate = this.reviewing();
    if (!candidate) return;
    await this.run(async () => {
      await firstValueFrom(this.http.post<Candidate>(this.url+'/'+candidate.id+'/decision',{outcome,reason},
        {headers:{'If-Match':'"'+candidate.currentVersion+'"'}}));
      await this.refresh();
    });
  }
  private async refresh() {
    this.view.set(await firstValueFrom(this.http.get<View>(this.url)));
    this.reviewing.set(null);
  }
  private async run(task: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try { await task(); }
    catch (cause) {
      const status = cause instanceof HttpErrorResponse ? cause.status : 0;
      if ([401,403,404].includes(status)) {
        this.view.set(null);
        this.reviewing.set(null);
        this.clearRequest.emit();
      }
      const message = cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string' ? cause.error.message : null;
      this.error.set(status === 412 ? 'O fato mudou. Atualize as propostas antes de registrar o parecer.' :
        message ?? 'Não foi possível consultar ou salvar a proposta.');
    } finally { this.busy.set(false); }
  }
}
