import {SectionAssessmentComponent} from './section-assessment';
import {EvidenceRequestLink, GuideFactsPanelComponent} from './guide-facts-panel';
import {AcquisitionPanelComponent} from './acquisition-panel';
import {Component, inject, input, OnInit, output, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {GuideReferencesComponent} from './guide-references';
import {TypedGuideComponent} from './typed-guide';

interface Field { key: string; label: string; type: string; options: string[]; helpText?: string | null }
interface Guide {
  id: string; title: string; guidance: string; fields: Field[]; version: number;
  answers: Record<string,string>; missing: string[]; editable: boolean;
  definitionVersion?: number; legacyUnstructured?: string[];
}

@Component({
  selector: 'carbonflow-project-guides',
  imports: [GuideReferencesComponent, AcquisitionPanelComponent, GuideFactsPanelComponent, SectionAssessmentComponent, TypedGuideComponent],
  templateUrl: './project-guides.html',
})
export class ProjectGuidesComponent implements OnInit {
  readonly projectId = input.required<string>();
  readonly initialGuide = input<string|null>(null);
  readonly canOpenIntake = input(false);
  readonly prepare = output<void>();
  readonly canOpenDocument = input(false);
  readonly document = output<string>();
  readonly saved = output<Guide>();
  readonly guides = signal<Guide[]>([]);
  readonly evidenceRequest = signal<EvidenceRequestLink|null>(null);
  readonly sources = signal<Guide[] | null>(null);
  readonly historyOpen = signal(false);
  readonly historical = signal<Guide | null>(null);
  readonly current = signal<Guide | null>(null);
  readonly answers = signal<Record<string,string>>({});
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly dirty = signal(false);
  private readonly typedEditor = viewChild(TypedGuideComponent);
  private readonly http = inject(HttpClient);
  private get url() { return '/v1/projects/' + encodeURIComponent(this.projectId()) + '/technical/guides'; }

  ngOnInit() { void this.load(); }

  async load() {
    this.loading.set(true);
    this.error.set('');
    try {
      const catalog = await firstValueFrom(this.http.get<{guides: Guide[]}>(this.url));
      this.guides.set(catalog.guides);
      this.select(catalog.guides.find(item => item.id === (this.current()?.id ?? this.initialGuide())) ?? catalog.guides[0] ?? null);
    } catch {
      this.error.set('Não foi possível consultar os guias deste projeto.');
    } finally { this.loading.set(false); }
  }

  typedMode(): 'loading' | 'structured' | 'textual' {
    const editor = this.typedEditor();
    if (!editor || editor.loading()) return 'loading';
    return editor.guide() ? 'structured' : 'textual';
  }

  canLeave() {
    return !this.busy() && (this.typedEditor()?.canLeave() ?? true) &&
      (!this.dirty() || confirm('Descartar as alterações deste guia?'));
  }

  select(guide: Guide | null) {
    if (!this.canLeave()) return;
    this.display(guide);
  }

  private display(guide: Guide | null) {
    this.evidenceRequest.set(null);
    this.historyOpen.set(false);
    this.sources.set(null);
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
    this.error.set('');
    this.notice.set('');
  }

  fieldError(field: Field): string {
    const value = this.answers()[field.key]?.trim();
    if (!value) return '';
    if (field.type === 'year' && !/^(19\d{2}|20\d{2}|2100)$/.test(value)) {
      return 'Informe um ano com quatro dígitos, de 1900 a 2100.';
    }
    if (['select','enum'].includes(field.type) && !field.options.includes(value)) {
      return 'Escolha uma opção válida.';
    }
    if (value.length > 1600) return 'O campo excedeu 1.600 caracteres.';
    return '';
  }

  async showInputs(version: number) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      this.sources.set(await firstValueFrom(this.http.get<Guide[]>(this.url + '/quantification/revisions/' + version + '/inputs')));
    } catch {
      this.sources.set(null);
      this.error.set('As entradas desta revisão estão indisponíveis para esta conta.');
    } finally { this.busy.set(false); }
  }

  async showRevision(version: number) {
    const guide = this.current();
    if (!guide || this.busy() || version < 1 || version >= guide.version) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const state = await firstValueFrom(this.http.get<Guide>(this.url + '/' + guide.id + '/revisions/' + version));
      this.sources.set(null);
      this.historical.set(state);
      this.historyOpen.set(true);
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
    if (guide.fields.some(field => !!this.fieldError(field))) {
      this.error.set('Corrija os campos destacados antes de salvar. Respostas legadas ambíguas não são convertidas automaticamente.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      const headers: Record<string,string> = {'If-Match':'"' + guide.version + '"'};
      if (guide.definitionVersion != null) headers['X-Guide-Definition-Version'] = String(guide.definitionVersion);
      const saved = await firstValueFrom(this.http.put<Guide>(this.url + '/' + guide.id, this.answers(), {headers}));
      this.guides.update(items => items.map(item => item.id === saved.id ? saved : item));
      this.dirty.set(false);
      this.display(saved);
      this.saved.emit(saved);
      this.notice.set('Revisão ' + saved.version + ' salva.');
    } catch (cause) {
      if (cause instanceof HttpErrorResponse && [401,404].includes(cause.status)) {
        this.dirty.set(false);
        this.guides.set([]);
        this.display(null);
      }
      this.error.set(cause instanceof HttpErrorResponse && cause.status === 412
        ? 'Outra pessoa alterou este guia. Copie suas alterações e recarregue a versão atual.'
        : cause instanceof HttpErrorResponse && cause.status === 409
          ? 'A definição do formulário mudou. Copie seu rascunho e recarregue antes de salvar.'
        : cause instanceof HttpErrorResponse && typeof cause.error?.message === 'string'
          ? cause.error.message : 'Não foi possível salvar o guia.');
    } finally { this.busy.set(false); }
  }
}
