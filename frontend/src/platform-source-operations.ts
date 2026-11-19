import {Component, Input, OnChanges, SimpleChanges, computed, inject, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {HttpClient, HttpErrorResponse, HttpHeaders, HttpParams} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type WorkState = 'REQUESTED' | 'SOURCING' | 'WAITING_RIGHTS' | 'READY_FOR_HANDOFF' | 'ON_HOLD';
type WorkMode = 'acquisition' | 'history' | 'impacts';

interface WorkNeed {
  code: string;
  title: string;
  kind: 'NORMATIVE' | 'DATASET' | 'PROJECT_EVIDENCE';
  stage: string;
  status: string;
  priority: string;
  nextAction: string;
}
interface WorkItem {
  needCode: string;
  title: string;
  kind: string;
  stage: string;
  blockedCapability: string;
  priority: string;
  status: WorkState;
  ownerId: string | null;
  ownerName: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}
interface Impact {
  capability: string;
  required: number;
  conditional: number;
  projectEvidence: number;
  requestsOpen: number;
}
interface WorkBoard {canManage: boolean; work: WorkItem[]; impacts: Impact[]}
interface Assignee {id: string; name: string}
interface WorkEvent {
  version: number;
  status: WorkState;
  ownerId: string | null;
  ownerName: string;
  actorId: string;
  actorName: string;
  note: string;
  recordedAt: string;
}
interface Detail {work: WorkItem; history: WorkEvent[]}

@Component({
  selector: 'carbonflow-platform-source-operations',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './platform-source-operations.html',
  styleUrl: './platform-source-readiness.css',
})
export class PlatformSourceOperationsComponent implements OnChanges {
  @Input({required:true}) methodology = '';
  @Input({required:true}) edition = '';
  @Input({required:true}) scenario = '';
  @Input({required:true}) needs: WorkNeed[] = [];
  @Input() mode: WorkMode = 'acquisition';

  private readonly http = inject(HttpClient);
  private epoch = 0;
  readonly board = signal<WorkBoard | null>(null);
  readonly selectedCode = signal('');
  readonly detail = signal<Detail | null>(null);
  readonly assignees = signal<Assignee[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');

  newNote = '';
  editNote = '';
  selectedStatus: WorkState = 'REQUESTED';
  selectedOwner = '';

  readonly selectedNeed = computed(() =>
    this.needs.find(need => need.code === this.selectedCode()) ?? null);
  readonly existing = computed(() =>
    this.board()?.work.find(work => work.needCode === this.selectedCode()) ?? null);
  readonly statusOptions: {id: WorkState; label: string}[] = [
    {id:'REQUESTED',label:'Solicitada'},
    {id:'SOURCING',label:'Buscando fonte'},
    {id:'WAITING_RIGHTS',label:'Aguardando direitos de uso'},
    {id:'READY_FOR_HANDOFF',label:'Preparada para encaminhar à ingestão/revisão'},
    {id:'ON_HOLD',label:'Suspensa com justificativa'},
  ];

  ngOnChanges(changes: SimpleChanges) {
    if (changes['methodology'] || changes['edition'] || changes['scenario']) {
      this.selectedCode.set('');
      this.detail.set(null);
      void this.refresh();
    }
  }

  statusLabel(value: string): string {
    return this.statusOptions.find(status => status.id === value)?.label ?? value;
  }

  async refresh() {
    if (!this.methodology || !this.edition || !this.scenario) return;
    const current = ++this.epoch;
    this.loading.set(true);
    this.error.set('');
    try {
      const params = new HttpParams().set('methodology',this.methodology)
        .set('edition',this.edition).set('scenario',this.scenario);
      const result = await firstValueFrom(this.http.get<WorkBoard>('/v1/platform/source-work',{params}));
      if (current !== this.epoch) return;
      this.board.set(result);
      if (result.canManage) {
        const people = await firstValueFrom(this.http.get<Assignee[]>('/v1/platform/source-work/assignees'));
        if (current === this.epoch) this.assignees.set(people);
      } else {
        this.assignees.set([]);
      }
    } catch (problem) {
      if (current !== this.epoch) return;
      this.board.set(null);
      this.error.set(this.errorText(problem));
    } finally {
      if (current === this.epoch) this.loading.set(false);
    }
  }

  async selectNeed(code: string) {
    this.selectedCode.set(code);
    this.detail.set(null);
    this.error.set('');
    this.notice.set('');
    this.newNote = '';
    this.editNote = '';
    const work = this.board()?.work.find(item => item.needCode === code);
    this.selectedStatus = work?.status ?? 'REQUESTED';
    this.selectedOwner = work?.ownerId ?? '';
    if (!work) return;
    const current = ++this.epoch;
    this.loading.set(true);
    try {
      const selected = await firstValueFrom(this.http.get<Detail>(
        '/v1/platform/source-work/'+encodeURIComponent(code)));
      if (this.epoch === current) {
        this.detail.set(selected);
        this.selectedStatus = selected.work.status;
        this.selectedOwner = selected.work.ownerId ?? '';
      }
    } catch (reason) {
      if (this.epoch === current) this.error.set(this.errorText(reason));
    } finally {
      if (this.epoch === current) this.loading.set(false);
    }
  }

  async create() {
    const code = this.selectedCode();
    if (!code || !this.board()?.canManage || this.busy()) return;
    if (this.selectedNeed()?.kind === 'PROJECT_EVIDENCE') {
      this.error.set('Documentos de projeto são solicitados dentro da jornada do proponente/operador.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      const detail = await firstValueFrom(this.http.put<Detail>(
        '/v1/platform/source-work/'+encodeURIComponent(code),
        {note:this.newNote,ownerId:this.selectedOwner || null}));
      this.detail.set(detail);
      this.newNote = '';
      this.notice.set('Solicitação registrada. Isso não aprova a fonte nem libera o cálculo.');
      await this.refresh();
    } catch (reason) {
      this.error.set(this.errorText(reason));
    } finally {
      this.busy.set(false);
    }
  }

  async revise() {
    const code = this.selectedCode();
    const current = this.detail();
    if (!code || !current || !this.board()?.canManage || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const updated = await firstValueFrom(this.http.post<Detail>(
        '/v1/platform/source-work/'+encodeURIComponent(code)+'/revisions',
        {status:this.selectedStatus,ownerId:this.selectedOwner || null,note:this.editNote},
        {headers:new HttpHeaders({'If-Match':String(current.work.version)})}));
      this.detail.set(updated);
      this.editNote = '';
      this.notice.set('Histórico atualizado. Nenhum documento ou parâmetro foi aprovado.');
      await this.refresh();
    } catch (reason) {
      this.error.set(this.errorText(reason));
    } finally {
      this.busy.set(false);
    }
  }

  private errorText(problem: unknown): string {
    if (problem instanceof HttpErrorResponse) {
      if (problem.status === 409) return 'Outra pessoa alterou esta solicitação. Reabra-a para consultar a versão atual.';
      if (problem.status === 401 || problem.status === 403 || problem.status === 404)
        return 'Sessão ou autorização administrativa indisponível. Confira seu acesso.';
      if (problem.status === 422) return 'Verifique a justificativa, o responsável e a transição solicitada.';
    }
    return 'Não foi possível atualizar a fila de fontes. Tente novamente.';
  }
}
