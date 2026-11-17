import {afterNextRender, Component, computed, ElementRef, inject, Injector, input, OnInit, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {SessionProject} from './access-panel';

interface Ref { id: string; version: number }
interface Evidence {
  ref: Ref;
  kind: string;
  source: string;
  date: string;
  details: Record<string, string>;
}
interface Reason { code: string; rule: string; message: string }
interface Profile {
  locality: string; state: string; country: string; closedNetwork: boolean; additionalInfrastructure: boolean;
  ecRoute: string;
}
interface Inventory {
  sites: string[];
  stations: {id: string; site: string; model: string; powerKw: string}[];
  models: {id: string; bev: string; comparable: string}[];
  vehicles: {id: string; model: string}[];
  ecCircuits: {id: string; station: string}[];
}
interface Candidate { profile: Profile; inventory: Inventory; referenceActDate: string; evidence: Ref }
interface Intake {
  candidate: Candidate;
  state: string;
  preparedBy: string;
  reviewedBy: string | null;
  reasons: Reason[];
  selection: {
    sourceCutoff: string; referenceActDate: string; matrix: string; program: string; packageEdition: string;
    methodology: string; additionality: string; gridRoute: string;
  } | null;
}
interface CaseRecord {
  id: string; version: number; recordedBy: string;
  input: {type: string; status: string; effectiveDate: string; evidence: Ref | null; justification: string};
}
interface MeterDetails {
  meterId: string; effectiveFrom: string; installation: Ref; diagram: Ref | null; calibration: Ref | null;
}
interface MeterView {
  revision: {circuit: string; revision: number; predecessor: number | null; details: MeterDetails;
    calibratedFrom: string | null; calibratedUntil: string | null};
  effectiveUntilExclusive: string | null;
}
interface Reading {
  raw: {originId: string; circuit: string; meterId: string; start: string; endExclusive: string; kwh: string};
  state: string; reasons: Reason[]; configurationVersion: number;
}
interface Snapshot {
  version: number; intake: Intake | null; records: CaseRecord[];
  commitment: {date: string; evidence: Ref} | null;
  operation: {start: string; creditStart: string; creditEndInclusive: string; evidence: Ref} | null;
}
interface Status {
  snapshot: Snapshot; meterWindows: MeterView[]; readings: Reading[]; realUseBlockers: Reason[];
}
interface Examples {
  version: string; sha256: string; candidate: Candidate; evidence: Evidence[];
  meters: {circuit: string; details: MeterDetails}[];
}
interface Revision {version: number; predecessor: number | null; eventId: string}

const label: Record<string, string> = {
  DIREITOS: 'Titularidade e operação', EXCEDENTE: 'Excedente regulatório', SOBREPOSICAO: 'Pesquisa de sobreposição',
  ENGAJAMENTO: 'Engajamento inicial', MECANISMO: 'Mecanismo de reclamações', ESG: 'Riscos e avaliação ESG',
  RECLAMACAO: 'Reclamação', ODS: 'Contribuição aos ODS', FPIC: 'Incidência de FPIC', REPARTICAO: 'Repartição de benefícios',
  TRIAGEM: 'Em triagem', ENQUADRADO: 'Enquadrado no sintético', PENDENTE: 'Pendente',
  FORA_DO_ESCOPO: 'Fora do recorte', COMPROVADO_SIMULACAO: 'Comprovado na simulação',
  NAO_APLICAVEL_JUSTIFICADO: 'Não aplicável com justificativa', COBERTURA_CONFERIDA: 'Cobertura conferida',
};
const prepKinds = ['ENGAJAMENTO', 'MECANISMO', 'ESG', 'ODS', 'FPIC', 'REPARTICAO'];
const ppKinds = ['DIREITOS', 'EXCEDENTE', 'SOBREPOSICAO', 'RECLAMACAO'];
const readingExamples = [
  {id: 'anterior', label: 'Antes da troca', meterId: 'SIM-MED-01', start: '2028-03-31T23:00:00-03:00', endExclusive: '2028-04-01T00:00:00-03:00'},
  {id: 'atravessa', label: 'Atravessa a troca', meterId: 'SIM-MED-01', start: '2028-03-31T23:30:00-03:00', endExclusive: '2028-04-01T00:30:00-03:00'},
  {id: 'sucessor', label: 'Após a troca', meterId: 'SIM-MED-03', start: '2028-04-01T00:00:00-03:00', endExclusive: '2028-04-01T01:00:00-03:00'},
  {id: 'calibracao', label: 'Após vencer a calibração', meterId: 'SIM-MED-03', start: '2028-10-02T00:00:00-03:00', endExclusive: '2028-10-02T01:00:00-03:00'},
];

@Component({selector: 'carbonflow-intake', templateUrl: './intake-panel.html'})
export class IntakePanel implements OnInit {
  readonly project = input.required<SessionProject>();
  readonly actor = input.required<string>();
  readonly active = computed(() => this.project().roles.some(role => ['PREP', 'REV', 'PP', 'OP'].includes(role)));
  readonly status = signal<Status | null>(null);
  readonly samples = signal<Examples | null>(null);
  readonly history = signal<Revision[] | null>(null);
  readonly busy = signal(false);
  readonly feedback = signal('');
  readonly failed = signal(false);
  readonly readingExamples = readingExamples;
  readonly recordProofs = computed(() => this.samples()?.evidence.filter(item =>
    (this.has('PREP') && prepKinds.includes(item.kind)) || (this.has('PP') && ppKinds.includes(item.kind))) ?? []);

  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  private readonly notice = viewChild<ElementRef<HTMLElement>>('notice');
  private readonly pendingKeys = new Map<string, string>();

  ngOnInit() {
    if (this.active()) void this.perform(async () => {
      const [status, examples] = await Promise.all([
        this.read<Status>(''), this.read<Examples>('/intake/examples'),
      ]);
      this.samples.set(examples);
      this.status.set(status);
    });
  }

  has(role: string) { return this.project().roles.includes(role); }
  text(value: string) { return label[value] ?? value; }

  propose(variant: string) {
    const sample = this.samples();
    if (!sample) return;
    const profile = {...sample.candidate.profile};
    if (variant === 'aberta') profile.closedNetwork = false;
    if (variant === 'infraestrutura') profile.additionalInfrastructure = true;
    if (variant === '923') profile.ecRoute = 'ESTIMADA_92_3';
    return this.action('/intake', 'TX-E3-01', {candidate: {...sample.candidate, profile}}, 'Triagem registrada.');
  }

  review() {
    return this.action('/intake', 'TX-E3-02', {scopeReview: this.ref('SIM-PARECER-ESCOPO'),
      vigency: this.ref('SIM-MATRIZ-VIGENCIA')}, 'Revisão registrada.');
  }

  record(event: Event, proofId: string) {
    event.preventDefault();
    const proof = this.samples()?.evidence.find(item => item.ref.id === proofId);
    if (!proof) return;
    const conditional = proof.kind === 'FPIC' || proof.kind === 'REPARTICAO';
    const justification = Object.values(proof.details).join(' · ');
    return this.mutate('/case-records', {type: proof.kind, effectiveDate: proof.date,
      status: conditional ? 'NAO_APLICAVEL_JUSTIFICADO' : 'COMPROVADO_SIMULACAO',
      justification, evidence: proof.ref}, 'Evidência inicial registrada.');
  }

  commit() {
    return this.action('/implantations', 'TX-E3-03', {date: '2027-07-15', evidence: this.ref('SIM-COMPRA-DCFC')},
      'Compromisso significativo registrado.');
  }

  operate() {
    return this.action('/implantations', 'TX-E3-04', {start: '2027-10-01', creditStart: '2027-10-01',
      evidence: this.ref('SIM-COMISSIONAMENTO')}, 'Operação inicial e início do crédito registrados.');
  }

  meter(event: Event, meterId: string, calibrated: boolean) {
    event.preventDefault();
    const meter = this.samples()?.meters.find(example => example.details.meterId === meterId);
    if (meter) return this.mutate('/inventory/' + meter.circuit + '/revisions', {
      ...meter.details, calibration: calibrated ? meter.details.calibration : null,
    }, 'Revisão do medidor registrada.');
  }

  reading(event: Event, exampleId: string, kwh: string) {
    event.preventDefault();
    const sample = readingExamples.find(item => item.id === exampleId);
    if (!sample) return;
    const {meterId, start, endExclusive} = sample;
    const fingerprint = JSON.stringify([exampleId, kwh]);
    return this.perform(async () => {
      const requestKey = this.pendingKeys.get(fingerprint) ?? crypto.randomUUID();
      this.pendingKeys.set(fingerprint, requestKey);
      const outcome = await this.post<Reading>('/inventory/readings', {
        originId: 'SIM-LEITURA-' + requestKey.toUpperCase(), circuit: 'SIM-EC-01', meterId, start, endExclusive, kwh,
        evidence: this.ref(meterId + '-LEITURA'),
      }, requestKey);
      this.pendingKeys.delete(fingerprint);
      this.feedback.set('Leitura preservada · ' + this.text(outcome.state) + '.');
      this.status.set(await this.read<Status>(''));
    });
  }

  loadHistory() {
    return this.perform(async () => this.history.set(await this.read<Revision[]>('/intake/history')));
  }

  versionLink(version: number) {
    return this.base() + '/intake/versions/' + version;
  }

  private action(path: string, transitionId: string, details: object, message: string) {
    return this.mutate(path + '/' + encodeURIComponent(this.project().project) + '/actions',
      {transitionId, details}, message);
  }

  private mutate(path: string, body: object, message: string) {
    const version = this.status()?.snapshot.version;
    if (version === undefined) return;
    return this.perform(async () => {
      const fingerprint = JSON.stringify([path, version, body]);
      const key = this.pendingKeys.get(fingerprint) ?? crypto.randomUUID();
      this.pendingKeys.set(fingerprint, key);
      await this.post(path, body, key, version);
      this.pendingKeys.delete(fingerprint);
      this.feedback.set(message);
      this.status.set(await this.read<Status>(''));
      if (this.history()) this.history.set(await this.read<Revision[]>('/intake/history'));
    });
  }

  private post<T>(path: string, body: object, key: string, version?: number) {
    const headers: Record<string, string> = {'Idempotency-Key': key};
    if (version !== undefined) headers['If-Match'] = '"' + version + '"';
    return firstValueFrom(this.http.post<T>(this.base() + path, body, {headers}));
  }

  private read<T>(path: string) { return firstValueFrom(this.http.get<T>(this.base() + path)); }
  private base() { return '/v1/projects/' + encodeURIComponent(this.project().project); }
  private ref(id: string): Ref { return {id, version: 1}; }

  private async perform(work: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.failed.set(false);
    this.feedback.set('');
    try {
      await work();
    } catch (error: unknown) {
      this.failed.set(true);
      const response = error instanceof HttpErrorResponse ? error : null;
      this.feedback.set(response?.error?.message ?? 'Falha ao concluir a operação.');
      if (response?.status === 412) this.status.set(await this.read<Status>('').catch(() => this.status()));
    } finally {
      this.busy.set(false);
      if (this.feedback()) afterNextRender(() => this.notice()?.nativeElement.focus(), {injector: this.injector});
    }
  }
}
