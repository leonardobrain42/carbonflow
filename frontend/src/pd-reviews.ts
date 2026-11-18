import {Component, inject, input, signal} from '@angular/core';
import {DatePipe} from '@angular/common';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Action = 'SUBMITTED' | 'RETURNED' | 'APPROVED_INTERNAL' | 'FROZEN_INTERNAL' | 'REOPENED';
interface ReviewEvent {seq:number; state:Action; actor:string; actorName:string; at:string; reason:string; competenceVersion:number|null}
interface Item {
  id:string; title:string; section:{number:number; guideVersion:number; author:string; narrative:string; sha256:string};
  outdated:boolean; ownerOnly:boolean; authorName:string; history:ReviewEvent[]; actions:Action[];
}
@Component({selector:'carbonflow-pd-reviews', imports:[DatePipe], templateUrl:'./pd-reviews.html'})
export class PdReviewsComponent {
  readonly projectId = input.required<string>();
  private readonly http = inject(HttpClient);
  readonly items = signal<Item[] | null>(null);
  readonly selected = signal<Item | null>(null);
  readonly version = signal(0);
  readonly events = signal<ReviewEvent[]>([]);
  readonly reason = signal('');
  readonly previous = signal<{authorId:string;guideVersion:number;narrative:string}|null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly state = {SUBMITTED:'Em revisão',RETURNED:'Devolvida',APPROVED_INTERNAL:'Aprovada internamente',
    FROZEN_INTERNAL:'Congelada internamente',REOPENED:'Reaberta'};
  readonly verb = {SUBMITTED:'Encaminhar para revisão',RETURNED:'Devolver para ajustes',APPROVED_INTERNAL:'Aprovar internamente',
    FROZEN_INTERNAL:'Congelar versão',REOPENED:'Reabrir para edição'};

  get base() { return '/v1/projects/' + encodeURIComponent(this.projectId()) + '/technical'; }
  get reviewUrl() { return this.base + '/pd-reviews'; }
  canLeave() { return !this.busy() && (!this.reason().trim() || confirm('Descartar o parecer ainda não registrado?')); }

  async load() {
    if (!this.canLeave()) return;
    await this.run(async () => {
      const items = await firstValueFrom(this.http.get<Item[]>(this.reviewUrl));
      this.items.set(items);
      this.choose(items.find(i => i.id === this.selected()?.id) ?? items[0] ?? null);
    });
  }
  select(item:Item) { if (this.canLeave()) this.choose(item); }
  private choose(item:Item | null) {
    this.selected.set(item);
    this.previous.set(null);
    this.version.set(item?.section.number ?? 0);
    this.events.set(item?.history ?? []);
    this.reason.set('');
  }
  async history(version:number) {
    const item = this.selected();
    if (!item || !Number.isInteger(version) || version<1 || version>item.section.number || !this.canLeave()) return;
    await this.run(async () => {
      const [history,artifact] = await Promise.all([
        firstValueFrom(this.http.get<ReviewEvent[]>(this.reviewUrl+'/'+item.id+'/versions/'+version)),
        firstValueFrom(this.http.get<{authorId:string;guideVersion:number;narrative:string}>(this.base+'/pd-sections/'+item.id+'/versions/'+version+'/download'))
      ]);
      this.previous.set(artifact);
      this.events.set(history);
      this.version.set(version);
      this.reason.set('');
    });
  }
  async decide(action:Action) {
    const item = this.selected();
    if (!item || this.busy() || !this.reason().trim() || this.version() !== item.section.number) return;
    await this.run(async () => {
      const updated = await firstValueFrom(this.http.post<Item>(this.reviewUrl+'/'+item.id+'/versions/'+item.section.number,
        {state:action,reason:this.reason()}, {headers:{'If-Match':'"'+item.history.length+'"'}}));
      this.items.update(list => list?.map(old => old.id===item.id ? updated : old) ?? []);
      this.choose(updated);
    });
  }
  private async run(operation:()=>Promise<void>) {
    this.busy.set(true);
    this.error.set('');
    try { await operation(); }
    catch (cause) {
      if (cause instanceof HttpErrorResponse && [401,403,404].includes(cause.status)) {
        this.items.set(null);
        this.choose(null);
      }
      this.error.set(cause instanceof HttpErrorResponse && [409,412].includes(cause.status)
        ? 'A ação está indisponível nesta revisão. Copie seu parecer e atualize a fila.'
        : 'Não foi possível acessar esta revisão.');
    } finally { this.busy.set(false); }
  }
}
