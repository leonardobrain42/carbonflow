import {Component, inject, input, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

interface Guide {
  id:string; title:string; version:number; missing:string[]; answers:Record<string,string>;
  fields:{key:string;label:string}[];
}
interface Version {number:number;guideVersion:number;author:string;narrative:string;sha256:string}
interface Section {guide:Guide;latest:Version | null}

@Component({selector:'carbonflow-pd-sections',templateUrl:'./pd-sections.html'})
export class PdSectionsComponent {
  readonly projectId = input.required<string>();
  readonly editable = input(false);
  readonly items = signal<Section[] | null>(null);
  readonly current = signal<Section | null>(null);
  readonly text = signal('');
  readonly busy = signal(false);
  readonly message = signal('');
  private readonly http = inject(HttpClient);
  get url() { return '/v1/projects/' + encodeURIComponent(this.projectId()) + '/technical/pd-sections'; }
  canLeave() {
    return !this.busy() && (this.text() === (this.current()?.latest?.narrative ?? '') || confirm('Descartar a narrativa ainda não salva?'));
  }

  async load() {
    if (!this.canLeave()) return;
    this.busy.set(true);
    this.message.set('');
    try {
      const items = await firstValueFrom(this.http.get<Section[]>(this.url));
      this.items.set(items);
      this.show(items.find(item => item.guide.id === this.current()?.guide.id) ?? items[0] ?? null);
    } catch (cause) { this.failed(cause); }
    finally { this.busy.set(false); }
  }

  select(id:string) {
    if (this.canLeave()) this.show(this.items()?.find(item => item.guide.id === id) ?? null);
  }

  private show(item:Section | null) {
    this.current.set(item);
    this.text.set(item?.latest?.narrative ?? '');
    this.message.set('');
  }

  async save() {
    const item = this.current();
    if (!item || !this.editable() || this.busy()) return;
    this.busy.set(true);
    try {
      const saved = await firstValueFrom(this.http.put<Version>(this.url + '/' + item.guide.id,
        {guideVersion:item.guide.version,narrative:this.text()}, {headers:{'If-Match':'"' + (item.latest?.number ?? 0) + '"'}}));
      const next = {...item,latest:saved};
      this.items.update(items => items?.map(old => old.guide.id === item.guide.id ? next : old) ?? [next]);
      this.show(next);
      this.message.set('Seção salva na versão ' + saved.number + '.');
    } catch (cause) { this.failed(cause); }
    finally { this.busy.set(false); }
  }

  private failed(cause:unknown) {
    if (cause instanceof HttpErrorResponse && [401,404].includes(cause.status)) {
      this.items.set(null);
      this.show(null);
    }
    this.message.set(cause instanceof HttpErrorResponse && cause.status === 412
      ? 'A seção mudou. Copie sua narrativa e consulte a versão atual.'
      : cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string'
        ? cause.error.message : 'Não foi possível acessar a seção.');
  }
}
