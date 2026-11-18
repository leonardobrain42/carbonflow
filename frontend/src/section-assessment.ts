import {Component, inject, input, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

interface Result {
  code: string;
  title: string;
  field: string;
  data: 'RECORDED' | 'MISSING';
  proof: 'UNLINKED' | 'PENDING' | 'REJECTED' | 'ACCEPTED_INTERNAL' | 'RECHECK_REQUIRED';
  humanReviewRequired: boolean;
  reason: string;
  evidence: {guideVersion: number; locator: string; factVersion: number | null} | null;
}

interface Report {
  sha256: string;
  assessment: {guideRevision: number; catalogReason: string; packEdition: number | null; results: Result[]};
}

@Component({
  selector: 'carbonflow-section-assessment',
  templateUrl: './section-assessment.html',
})
export class SectionAssessmentComponent {
  readonly projectId = input.required<string>();
  readonly section = input.required<string>();
  readonly revision = input.required<number>();
  readonly report = signal<Report | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly proofLabel: Record<Result['proof'], string> = {
    UNLINKED: 'Sem evidência de guia vinculada',
    PENDING: 'Evidência aguarda parecer',
    REJECTED: 'Proposta de fato rejeitada',
    ACCEPTED_INTERNAL: 'Fato aceito na revisão interna',
    RECHECK_REQUIRED: 'Origem de fato precisa de rechecagem',
  };
  private readonly http = inject(HttpClient);

  async evaluate() {
    this.busy.set(true);
    this.report.set(null);
    this.error.set('');
    const section = this.section();
    const revision = this.revision();
    const act = section === 'monitoring' ? 'MR' : 'PD';
    const root = '/v1/projects/' + encodeURIComponent(this.projectId()) + '/sections/';
    try {
      const report = await firstValueFrom(this.http.get<Report>(root + encodeURIComponent(section) + '/assessment', {
        params: {act},
      }));
      if (report.assessment.guideRevision !== revision || this.revision() !== revision) {
        this.error.set('A revisão do guia mudou. Reabra o guia e consulte novamente.');
      } else {
        this.report.set(report);
      }
    } catch {
      this.error.set('A avaliação interna está indisponível para esta seção.');
    } finally {
      this.busy.set(false);
    }
  }
}
