import {Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpHeaders, HttpParams} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

interface Need {
  code: string;
  kind: string;
  title: string;
  sourceId: string | null;
  sourceEdition: string | null;
  editionPolicy: string;
}
type State = 'PENDING_REVIEW'|'METADATA_ACCEPTED'|'NEEDS_CORRECTION'|'REJECTED';
interface Submission {
  id: string; needCode: string; sourceId: string; edition: string; category: string;
  title: string; rightsHolder: string; publisher: string; officialUri: string;
  publicationDate: string|null; referenceYear: number|null; locator: string;
  declaredOriginalSha256: string|null; state: State; version: number;
  submittedBy: string; submittedByName: string; reviewedBy: string|null;
  createdAt: string; updatedAt: string;
}
interface Event {version: number; state: State; actorName: string; reason: string; recordedAt: string}
interface Detail {intake: Submission; history: Event[]}
interface Board {actorId: string; canSubmit: boolean; canReview: boolean; submissions: Submission[]}
type ReviewOutcome = 'ACCEPT_METADATA'|'REQUEST_CORRECTION'|'REJECT';

@Component({
  selector: 'carbonflow-platform-source-ingestion',
  standalone:true,
  templateUrl:'./platform-source-ingestion.html',
  styleUrl:'./platform-source-readiness.css',
})
export class PlatformSourceIngestionComponent implements OnChanges {
  @Input({required:true}) methodology = '';
  @Input({required:true}) edition = '';
  @Input({required:true}) scenario = '';
  @Input({required:true}) needs: Need[] = [];
  @Output() published = new EventEmitter<void>();

  private readonly http = inject(HttpClient);
  private sequence = 0;
  readonly board = signal<Board|null>(null);
  readonly selected = signal<Detail|null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly feedback = signal('');
  readonly draftNeed = signal('');
  readonly candidateNeeds = signal<Need[]>([]);
  readonly categories = [
    {value:'EXTERNAL_SOURCE',label:'Base externa / dataset'},
    {value:'METHODOLOGY',label:'Metodologia'},
    {value:'MODULE',label:'Módulo'},
    {value:'TOOL',label:'Ferramenta'},
    {value:'PROGRAM_DOCUMENT',label:'Documento do programa'},
    {value:'TEMPLATE',label:'Modelo documental'},
  ];
  readonly outcomes: {value: ReviewOutcome; label:string}[] = [
    {value:'ACCEPT_METADATA',label:'Aceitar apenas os metadados'},
    {value:'REQUEST_CORRECTION',label:'Solicitar correção'},
    {value:'REJECT',label:'Rejeitar a submissão'},
  ];
  title = '';
  sourceEdition = '';
  category = 'EXTERNAL_SOURCE';
  rightsHolder = '';
  publisher = '';
  officialUri = '';
  publicationDate = '';
  referenceYear = '';
  locator = '';
  declaredOriginalSha256 = '';
  note = '';
  reviewOutcome: ReviewOutcome = 'REQUEST_CORRECTION';
  reviewReason = '';

  ngOnChanges(changes:SimpleChanges) {
    if (changes['methodology'] || changes['edition'] || changes['scenario'] || changes['needs']) {
      this.candidateNeeds.set(this.needs.filter(x => x.kind !== 'PROJECT_EVIDENCE' && !!x.sourceId));
      if (!this.candidateNeeds().some(x => x.code === this.draftNeed())) {
        this.draftNeed.set('');
      }
      if (changes['methodology'] || changes['edition'] || changes['scenario']) {
        this.selected.set(null);
        this.board.set(null);
        void this.load();
      }
    }
  }

  stateLabel(state:string):string {
    return ({
      PENDING_REVIEW:'Aguardando revisão independente',
      METADATA_ACCEPTED:'Metadados aceitos — dados e direitos pendentes',
      NEEDS_CORRECTION:'Correção solicitada',
      REJECTED:'Submissão rejeitada',
    } as Record<string,string>)[state] ?? state;
  }

  selectNeed(code:string) {
    this.draftNeed.set(code);
    const need = this.candidateNeeds().find(n => n.code === code);
    if (!need) return;
    this.title = need.title;
    this.sourceEdition = need.editionPolicy === 'EXACT' ? (need.sourceEdition ?? '') : '';
    this.category = need.kind === 'DATASET' ? 'EXTERNAL_SOURCE'
      : need.sourceId?.startsWith('VMD') ? 'MODULE'
      : need.sourceId?.startsWith('VT') || need.sourceId?.startsWith('TOOL') ? 'TOOL'
      : need.sourceId?.startsWith('VCS') ? 'PROGRAM_DOCUMENT' : 'METHODOLOGY';
    this.officialUri = '';
    this.rightsHolder = '';
    this.publisher = '';
    this.locator = '';
    this.note = '';
    this.declaredOriginalSha256 = '';
    this.publicationDate = '';
    this.referenceYear = '';
  }

  async load() {
    if (!this.methodology || !this.edition || !this.scenario) return;
    const sequence=++this.sequence;
    this.busy.set(true);
    this.error.set('');
    try {
      const params = new HttpParams().set('methodology',this.methodology)
        .set('edition',this.edition).set('scenario',this.scenario);
      const board = await firstValueFrom(this.http.get<Board>('/v1/platform/source-ingestion',{params}));
      if (sequence === this.sequence) this.board.set(board);
    } catch(reason) {
      if (sequence === this.sequence) {
        this.board.set(null);
        this.error.set(this.message(reason));
      }
    } finally {
      if (sequence === this.sequence) this.busy.set(false);
    }
  }

  async open(id:string) {
    this.error.set('');
    this.feedback.set('');
    this.selected.set(null);
    const sequence=++this.sequence;
    this.busy.set(true);
    try {
      const result = await firstValueFrom(this.http.get<Detail>(
        '/v1/platform/source-ingestion/'+encodeURIComponent(id)));
      if (sequence === this.sequence) this.selected.set(result);
    } catch(reason) {
      if (sequence === this.sequence) this.error.set(this.message(reason));
    } finally {
      if (sequence === this.sequence) this.busy.set(false);
    }
  }

  async submit(event:Event) {
    event.preventDefault();
    if (!this.board()?.canSubmit || !this.draftNeed() || this.busy()) return;
    this.busy.set(true);
    this.feedback.set('');
    this.error.set('');
    try {
      const payload = {
        needCode:this.draftNeed(),edition:this.sourceEdition,category:this.category,
        title:this.title,rightsHolder:this.rightsHolder,publisher:this.publisher,
        officialUri:this.officialUri,publicationDate:this.publicationDate || null,
        referenceYear:this.referenceYear ? Number(this.referenceYear) : null,
        locator:this.locator,declaredOriginalSha256:this.declaredOriginalSha256 || null,note:this.note,
      };
      const saved = await firstValueFrom(this.http.post<Detail>('/v1/platform/source-ingestion',payload));
      this.selected.set(saved);
      this.note = '';
      this.feedback.set('Metadados submetidos. A revisão é obrigatória; não ocorreu upload nem admissão técnica.');
      await this.load();
    } catch(reason) {
      this.error.set(this.message(reason));
    } finally {
      this.busy.set(false);
    }
  }

  async review(event:Event) {
    event.preventDefault();
    const selected=this.selected();
    if (!selected || !this.board()?.canReview || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.feedback.set('');
    try {
      const detail=await firstValueFrom(this.http.post<Detail>(
        '/v1/platform/source-ingestion/'+encodeURIComponent(selected.intake.id)+'/reviews',
        {outcome:this.reviewOutcome,reason:this.reviewReason},
        {headers:new HttpHeaders({'If-Match':String(selected.intake.version)})}));
      this.selected.set(detail);
      this.reviewReason='';
      this.feedback.set(detail.intake.state === 'METADATA_ACCEPTED'
        ? 'Metadados registrados no catálogo; direitos, valores, regras e E8 continuam pendentes.'
        : 'Parecer independente registrado no histórico.');
      if(detail.intake.state === 'METADATA_ACCEPTED') this.published.emit();
      await this.load();
    } catch(reason) {
      this.error.set(this.message(reason));
    } finally {
      this.busy.set(false);
    }
  }

  private message(error:unknown):string {
    if (error instanceof HttpErrorResponse) {
      if (error.status===409) return 'A edição já existe com dados diferentes ou o registro foi atualizado. Recarregue antes de decidir.';
      if (error.status===401 || error.status===403 || error.status===404)
        return 'Sessão expirada ou permissão insuficiente para a operação solicitada.';
      if (error.status===422) return 'Confira os campos: edição concreta, URL HTTPS, proveniência, órgão e justificativa.';
    }
    return 'O processamento dos metadados falhou. Confira a rede e tente novamente.';
  }
}
