import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {plainTerm, preparatoryNote} from './calculation-language';

type BindingHour = {hour:string;edKwh:string;ecKwh:string;
  gridFactorTco2ePerKwh:string;chargerCount:number};
type Binding = {id:string;projectId:string;period:string;version:number;
  energyCandidateId:string;efelCandidateId:string;energySha256:string;
  efelSha256:string;efelReviewVersion:number;inputSha256:string;
  hourCount:number;slotCount:number;state:string;stale:boolean;
  usableForE8:boolean;createdAt:string;gates:string[];hours:BindingHour[]};

type FleetSelection={id:string;projectId:string;period:string;version:number;
  energyBindingId:string;fleetSnapshotId:string;ratingSnapshotId:string;
  observationYear:number;cycle:string;fleetSourceSha256:string;
  ratingLineageSha256:string;selectionSha256:string;state:string;
  stale:boolean;usableForE8:boolean;publishedAt:string;gates:string[]};

/** Explains CANDIDATE bindings. Never performs any E8 arithmetic in Angular. */
@Component({selector:'carbonflow-open-e8-bindings',standalone:true,
  templateUrl:'./open-e8-bindings.html'})
export class OpenE8BindingsComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input({required:true}) period='';
  @Input({required:true}) energyCandidateId='';
  @Input({required:true}) efelCandidateId='';
  @Input() sourceCurrent=false;
  @Input() canView=false;
  @Input() canPrepare=false;
  private readonly http=inject(HttpClient);
  readonly loading=signal(false);
  readonly busy=signal(false);
  readonly entries=signal<Binding[]>([]);
  readonly fleetSelections=signal<FleetSelection[]>([]);
  readonly selectedFleet=signal<FleetSelection|null>(null);
  readonly chosen=signal<Binding|null>(null);
  readonly note=preparatoryNote;
  readonly term=plainTerm;
  readonly error=signal('');
  readonly notice=signal('');
  private seq=0;
  private root(){
    return '/v1/projects/'+encodeURIComponent(this.projectId)+
      '/technical/open-monitoring/dossiers/'+encodeURIComponent(this.period)+
      '/e8-input-bindings';
  }
  private fleetRoot(){
    return '/v1/projects/'+encodeURIComponent(this.projectId)+
      '/technical/open-monitoring/dossiers/'+encodeURIComponent(this.period)+
      '/e8-fleet-selections';
  }
  ngOnChanges(changes:SimpleChanges) {
    if(changes['projectId']||changes['period']||changes['energyCandidateId']||
       changes['efelCandidateId']||changes['canView']) {
      this.entries.set([]);this.chosen.set(null);
      this.fleetSelections.set([]);this.selectedFleet.set(null);
      this.error.set('');this.notice.set('');
      void this.reload();
    }
  }
  async reload() {
    const current=++this.seq;
    if(!this.canView||!this.projectId||!this.period) {
      this.loading.set(false);return;
    }
    const root=this.root(),factor=this.efelCandidateId,energy=this.energyCandidateId;
    this.loading.set(true);
    try {
      const [result,fleet]=await Promise.all([
        firstValueFrom(this.http.get<Binding[]>(root)),
        firstValueFrom(this.http.get<FleetSelection[]>(this.fleetRoot()))
      ]);
      if(current===this.seq&&root===this.root()&&factor===this.efelCandidateId&&
         energy===this.energyCandidateId){
        this.entries.set(result);
        this.fleetSelections.set(fleet);
        const previousFleet=this.selectedFleet();
        this.selectedFleet.set(previousFleet?
          fleet.find(s=>s.id===previousFleet.id)??null:null);
        const selected=this.chosen();
        this.chosen.set(selected?result.find(b=>b.id===selected.id)??null:null);
      }
    } catch(problem) {
      if(current===this.seq)this.error.set(this.message(problem));
    } finally {
      if(current===this.seq)this.loading.set(false);
    }
  }
  async prepare() {
    if(!this.canPrepare||!this.canView||!this.sourceCurrent||this.busy())return;
    this.error.set('');this.notice.set('');this.busy.set(true);
    const root=this.root(),factor=this.efelCandidateId,energy=this.energyCandidateId;
    try {
      const current=this.entries().reduce((max,row)=>Math.max(max,row.version),0);
      const created=await firstValueFrom(this.http.post<Binding>(root,{
        energyCandidateId:energy,efelCandidateId:factor
      },{headers:{'If-Match':'"'+current+'"',
        'Idempotency-Key':crypto.randomUUID()}}));
      if(root===this.root()&&factor===this.efelCandidateId&&
         energy===this.energyCandidateId){
        await this.reload();
        this.chosen.set(created);
        this.notice.set('Vínculo imutável criado. Esta entrada não está admitida para o cálculo.');
      }
    } catch(problem) {
      if(root===this.root())this.error.set(this.message(problem));
    } finally {
      this.busy.set(false);
    }
  }
  private message(err:unknown) {
    if(err instanceof HttpErrorResponse)
      return err.status===404?'Vínculo ou permissão indisponível.':
        err.status===412?'Versão mudou. Atualize os vínculos antes de repetir.':
        'Não foi possível vincular as fontes; confira a vigência, revisão e cobertura horária.';
    return 'Não foi possível vincular as fontes.';
  }
}
