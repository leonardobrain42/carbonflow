import {Component, inject, input, OnInit, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

interface Field { key: string; label: string; type: string; options: string[] }
interface Guide {
  id: string; title: string; guidance: string; fields: Field[]; version: number;
  answers: Record<string,string>; missing: string[]; editable: boolean;
}

@Component({
  selector: 'carbonflow-project-guides',
  templateUrl: './project-guides.html',
})
export class ProjectGuidesComponent implements OnInit {
  readonly projectId = input.required<string>();
  readonly guides = signal<Guide[]>([]);
  readonly historical = signal<Guide | null>(null);
  readonly current = signal<Guide | null>(null);
  readonly answers = signal<Record<string,string>>({});
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly dirty = signal(false);
  private readonly http = inject(HttpClient);
  private get url() { return '/v1/projects/' + encodeURIComponent(this.projectId()) + '/technical/guides'; }

  ngOnInit() { void this.load(); }

  async load() {
    this.loading.set(true);
    this.error.set('');
    try {
      const catalog = await firstValueFrom(this.http.get<{guides: Guide[]}>(this.url));
      this.guides.set(catalog.guides);
      this.select(catalog.guides.find(item => item.id === this.current()?.id) ?? catalog.guides[0] ?? null);
    } catch {
      this.error.set('Não foi possível consultar os guias deste projeto.');
    } finally { this.loading.set(false); }
  }

  canLeave() {
    return !this.busy() && (!this.dirty() || confirm('Descartar as alterações deste guia?'));
  }

  select(guide: Guide | null) {
    if (!this.canLeave()) return;
    this.display(guide);
  }

  private display(guide: Guide | null) {
    this.historical.set(null);
    this.current.set(guide);
    this.answers.set({...guide?.answers});
    this.dirty.set(false);
    this.notice.set('');
    this.error.set('');
  }

  change(key: string, value: string) {
    this.answers.update(data => ({...data, [key]:value}));
    this.dirty.set(true);
    this.notice.set('');
  }

  async showRevision(version: number) {
    const guide = this.current();
    if (!guide || this.busy() || version < 1 || version >= guide.version) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const state = await firstValueFrom(this.http.get<Guide>(this.url + '/' + guide.id + '/revisions/' + version));
      this.historical.set(state);
    } catch (cause) {
      if (cause instanceof HttpErrorResponse && [401,404].includes(cause.status)) {
        this.dirty.set(false);
        this.guides.set([]);
        this.display(null);
      }
      this.error.set('Não foi possível consultar esta revisão.');
    } finally { this.busy.set(false); }
  }

  async save() {
    const guide = this.current();
    if (!guide?.editable || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const saved = await firstValueFrom(this.http.put<Guide>(this.url + '/' + guide.id, this.answers(),
        {headers:{'If-Match':'"' + guide.version + '"'}}));
      this.guides.update(items => items.map(item => item.id === saved.id ? saved : item));
      this.dirty.set(false);
      this.display(saved);
      this.notice.set('Revisão ' + saved.version + ' salva.');
    } catch (cause) {
      if (cause instanceof HttpErrorResponse && [401,404].includes(cause.status)) {
        this.dirty.set(false);
        this.guides.set([]);
        this.display(null);
      }
      this.error.set(cause instanceof HttpErrorResponse && cause.status === 412
        ? 'Outra pessoa alterou este guia. Copie suas alterações e recarregue a versão atual.'
        : cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string'
          ? cause.error.message : 'Não foi possível salvar o guia.');
    } finally { this.busy.set(false); }
  }
}
