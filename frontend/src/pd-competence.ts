import {Component, inject, signal} from '@angular/core';
import {DatePipe} from '@angular/common';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

interface Target {projectId:string; projectTitle:string; reviewerId:string; reviewerName:string; sectionId:string; sectionTitle:string}
interface Entry {version:number; reference:string|null; validUntil:string|null; active:boolean}
@Component({selector:'carbonflow-pd-competence',imports:[DatePipe],templateUrl:'./pd-competence.html'})
export class PdCompetenceComponent {
  private readonly http = inject(HttpClient);
  readonly targets = signal<Target[]|null>(null);
  readonly selected = signal<Target|null>(null);
  readonly entry = signal<Entry|null>(null);
  readonly busy = signal(false);
  readonly message = signal('');
  private url(t:Target) {
    return '/v1/compliance/projects/'+encodeURIComponent(t.projectId)+'/reviewers/'+t.reviewerId+'/sections/'+encodeURIComponent(t.sectionId)+'/competence';
  }
  async load() {
    await this.run(async () => {
      this.selected.set(null);
      this.entry.set(null);
      this.targets.set(await firstValueFrom(this.http.get<Target[]>('/v1/compliance/pd-reviewers')));
    });
  }
  async open(target:Target) {
    await this.run(async () => {
      this.selected.set(target);
      this.entry.set(await firstValueFrom(this.http.get<Entry>(this.url(target))));
    });
  }
  async save(reference:string, until:string, revoke:boolean) {
    const target=this.selected(), entry=this.entry();
    if (!target || !entry) return;
    if (!reference.trim() || (!until && !revoke)) {
      this.message.set('Informe a referência e, para confirmar a competência, a data de vigência.');
      return;
    }
    await this.run(async () => {
      this.entry.set(await firstValueFrom(this.http.put<Entry>(this.url(target),
        {reference,validUntil:revoke ? null : new Date(until).toISOString(),revoke},
        {headers:{'If-Match':'"'+entry.version+'"'}})));
      this.message.set('Decisão registrada.');
    });
  }
  private async run(op:()=>Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.message.set('');
    try { await op(); }
    catch {
      this.targets.set(null); this.selected.set(null); this.entry.set(null);
      this.message.set('Confira o acesso, a vigência e a versão atual da competência.');
    } finally { this.busy.set(false); }
  }
}
