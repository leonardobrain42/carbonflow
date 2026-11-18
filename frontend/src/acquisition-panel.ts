import {Component, inject, input, output, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Act = 'PD' | 'MR';
type Status = 'OPEN' | 'UNKNOWN' | 'DECLINED' | 'PARTIAL';
interface Need {field: string; label: string}
interface Setup {
  kind: string; purpose: string; candidateSource: string | null; due: string | null; assignee: string | null;
  criteria: Record<string,string | null>;
}
interface Revision {
  number: number;
  status: Status;
  plan: {setup: Setup; label: string; reason: string; guideVersion: number; packId: string | null; requirementCode: string | null};
  reply: {status: Status; nature: string | null; note: string | null} | null;
}
interface Item {
  request: {id:string; act:Act; field:string};
  revision: Revision;
  canPlan: boolean;
  canReply: boolean;
}
interface View {
  available: Need[];
  requests: Item[];
  recipients: {id:string; name:string}[];
  canCreate: boolean;
}
type Editor = {field: string; version: number; setup?: Setup};

@Component({
  selector: 'carbonflow-acquisition-panel',
  templateUrl: './acquisition-panel.html',
})
export class AcquisitionPanelComponent {
  readonly projectId = input.required<string>();
  readonly section = input.required<string>();
  readonly evidence = output<{id:string;field:string}>();
  private readonly http = inject(HttpClient);
  readonly act = signal<Act>('PD');
  readonly view = signal<View|null>(null);
  readonly edit = signal<Editor|null>(null);
  readonly replyTo = signal<Item|null>(null);
  readonly history = signal<{label:string; revisions:Revision[]}|null>(null);
  readonly busy = signal(false);
  readonly problem = signal('');
  readonly statusLabel: Record<Status,string> = {
    OPEN:'Aguardando resposta', UNKNOWN:'Não sabe', DECLINED:'Resposta recusada', PARTIAL:'Informação parcial',
  };
  readonly natureLabel: Record<string,string> = {PLANNED:'Prevista',ESTIMATED:'Estimada',MEASURED:'Medida'};
  readonly criteria = [
    {key:'unit',label:'Unidade'}, {key:'period',label:'Período'}, {key:'boundary',label:'Fronteira'},
    {key:'origin',label:'Origem'}, {key:'disclosure',label:'Sigilo e compartilhamento'},
  ];
  private get projectRoot() { return '/v1/projects/' + encodeURIComponent(this.projectId()); }
  private get listUrl() { return this.projectRoot + '/sections/' + encodeURIComponent(this.section()) + '/acquisition/' + this.act(); }

  async selectAct(value: string) {
    this.act.set(value === 'MR' ? 'MR' : 'PD');
    await this.load();
  }

  async load() {
    this.edit.set(null);
    this.replyTo.set(null);
    this.history.set(null);
    this.view.set(null);
    await this.run(() => this.refresh());
  }

  recipientName(id: string) { return this.view()?.recipients.find(recipient => recipient.id === id)?.name; }

  planned(field:string) { return this.view()?.requests.some(item => item.request.field === field) ?? false; }

  editItem(item: Item) {
    this.replyTo.set(null);
    this.edit.set({field:item.request.field,version:item.revision.number,setup:item.revision.plan.setup});
  }
  create(field: string) {
    if (!field) return;
    this.replyTo.set(null);
    this.edit.set({field,version:0});
  }
  chooseReply(item: Item) {
    this.edit.set(null);
    this.replyTo.set(item);
  }
  async savePlan(event: Event) {
    event.preventDefault();
    const editor = this.edit();
    if (!editor) return;
    const fields = new FormData(event.currentTarget as HTMLFormElement);
    const take = (name:string) => String(fields.get(name) ?? '').trim() || null;
    const setup = {kind:take('kind'),purpose:take('purpose'),candidateSource:take('source'),due:take('due'),
      assignee:take('assignee'),criteria:Object.fromEntries(this.criteria.map(item => [item.key,take(item.key)]))};
    await this.run(async () => {
      await firstValueFrom(this.http.put(this.listUrl+'/'+encodeURIComponent(editor.field),setup,
        {headers:{'If-Match':'"'+editor.version+'"'}}));
      this.edit.set(null);
      await this.refresh();
    });
  }
  async saveReply(event: Event, status: string, nature: string, note: string) {
    event.preventDefault();
    const item = this.replyTo();
    if (!item) return;
    await this.run(async () => {
      await firstValueFrom(this.http.post(this.projectRoot+'/evidence-requests/'+item.request.id+'/replies',
        {status,nature:status === 'PARTIAL' ? nature || null : null,note},
        {headers:{'If-Match':'"'+item.revision.number+'"'}}));
      this.replyTo.set(null);
      await this.refresh();
    });
  }
  async showHistory(item: Item) {
    await this.run(async () => {
      const revisions = await firstValueFrom(this.http.get<Revision[]>(this.projectRoot+'/evidence-requests/'+item.request.id));
      this.history.set({label:item.revision.plan.label,revisions});
    });
  }
  private async refresh() { this.view.set(await firstValueFrom(this.http.get<View>(this.listUrl))); }
  private async run(task: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.problem.set('');
    try { await task(); }
    catch (cause) {
      const status = cause instanceof HttpErrorResponse ? cause.status : 0;
      if ([401,403,404].includes(status)) {
        this.view.set(null);
        this.edit.set(null);
        this.replyTo.set(null);
        this.history.set(null);
      }
      const message = cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string' ? cause.error.message : null;
      this.problem.set(status === 412 ? 'A solicitação mudou. Atualize a lista antes de salvar.' :
        message ?? 'Não foi possível consultar ou salvar a solicitação.');
    } finally { this.busy.set(false); }
  }
}
