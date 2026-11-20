import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {OpenVt0011WorkbooksComponent} from './open-vt0011';
import {OpenE8BindingsComponent} from './open-e8-bindings';

export type E8FactorEnergy = {id:string;period:string;state:string;
  hours:{from:string;chargerCode:string}[]};
type EfelHour = {hour:string;originalValue:string;normalizedValue:string};
type EfelReview = {version:number;outcome:'CHECKED_INTERNAL'|'RETURNED';rationale:string;
  evidenceReference:string;reviewerId:string;reviewedAt:string};
type EfelCandidate = {id:string;period:string;energyCandidateId:string;version:number;
  sourceTitle:string;sourcePublisher:string;sourceUri:string;sourceEdition:string;
  claimedProcedure:string;publicationDate:string;sourceVintage:string;
  sourceSha256Claimed:string;sourceRightsReference:string;evidenceNote:string;
  originalUnit:string;normalizedUnit:string;contentSha256:string;hourCount:number;
  state:string;stale:boolean;usableForE8:boolean;submittedBy:string;submittedAt:string;
  review:EfelReview|null;hours:EfelHour[];gates:string[]};

@Component({selector:'carbonflow-efel-candidates',standalone:true,
  imports:[OpenVt0011WorkbooksComponent,OpenE8BindingsComponent],
  templateUrl:'./open-efel.html'})
export class OpenEfelCandidatesComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input({required:true}) energy!:E8FactorEnergy;
  @Input() canView=false;
  @Input() canSubmit=false;
  @Input() canReview=false;
  @Input() canViewVt0011=false;
  @Input() canSubmitVt0011=false;
  @Input() canReviewVt0011=false;
  @Input() canViewSimpleOm=false;
  @Input() canPrepareSimpleOm=false;
  private readonly http=inject(HttpClient);
  readonly loading=signal(false);
  readonly sending=signal(false);
  readonly reviewing=signal(false);
  readonly candidates=signal<EfelCandidate[]>([]);
  readonly selected=signal<EfelCandidate|null>(null);
  readonly error=signal('');
  readonly notice=signal('');
  readonly title=signal('');
  readonly publisher=signal('');
  readonly uri=signal('');
  readonly edition=signal('');
  readonly procedure=signal('');
  readonly published=signal('');
  readonly vintage=signal('');
  readonly sha=signal('');
  readonly rights=signal('');
  readonly note=signal('');
  readonly unit=signal('tCO2e/MWh');
  readonly hourCsv=signal('');
  readonly reviewOutcome=signal<'CHECKED_INTERNAL'|'RETURNED'>('RETURNED');
  readonly reviewReason=signal('');
  readonly reviewReference=signal('');

  ngOnChanges(changes:SimpleChanges) {
    if(changes['energy']||changes['projectId']) {
      this.candidates.set([]);this.selected.set(null);
      this.hourCsv.set(this.expectedHours().map(hour=>hour+',').join('\n'));
      this.error.set('');this.notice.set('');
      if(this.canView&&this.energy?.id)void this.reload();
    }
  }
  private root() {
    return '/v1/projects/'+encodeURIComponent(this.projectId)+
      '/technical/open-monitoring/dossiers/'+encodeURIComponent(this.energy.period)+
      '/efel-candidates';
  }
  expectedHours() {
    return Array.from(new Set(this.energy?.hours?.map(row=>
      new Date(row.from).toISOString().replace('.000Z','Z'))??[])).sort();
  }
  async reload() {
    if(!this.canView || !this.energy?.id)return;
    const project=this.projectId,energyId=this.energy.id;
    this.loading.set(true);
    try {
      const data=await firstValueFrom(this.http.get<EfelCandidate[]>(this.root()));
      if(this.projectId===project && this.energy.id===energyId) {
        const filtered=data.filter(c=>c.energyCandidateId===energyId);
        this.candidates.set(filtered);
        const selected=this.selected();
        this.selected.set(selected?filtered.find(c=>c.id===selected.id)??null:null);
      }
    } catch(error) {if(this.projectId===project)this.error.set(this.message(error));}
    finally {this.loading.set(false);}
  }
  select(item:EfelCandidate) {
    this.selected.set(item);
    this.reviewOutcome.set(item.review?.outcome??'RETURNED');
    this.reviewReason.set(item.review?.rationale??'');
    this.reviewReference.set(item.review?.evidenceReference??'');
  }
  private hoursForSubmit() {
    const seen=new Set<string>();
    const rows=this.hourCsv().trim().split(/\r?\n/).filter(Boolean);
    if(rows.length!==this.expectedHours().length)
      throw new Error('É obrigatório informar uma linha para cada hora de EC.');
    const hours:{hour:string;value:string}[]=[];
    for(const line of rows) {
      const parts=line.split(',');
      if(parts.length!==2)throw new Error('Use exatamente hora ISO 8601,valor por linha.');
      const hour=parts[0].trim(),value=parts[1].trim();
      if(!/^\d{4}-\d{2}-\d{2}T\d{2}:00:00Z$/.test(hour) ||
        !/^\d+(\.\d{1,12})?$/.test(value) || seen.has(hour))
        throw new Error('Horário duplicado/inválido ou valor não decimal; não há preenchimento automático.');
      seen.add(hour);hours.push({hour,value});
    }
    if(this.expectedHours().some(hour=>!seen.has(hour)))
      throw new Error('As horas devem coincidir exatamente com as medições EC.');
    return hours;
  }
  async submit(event:Event) {
    event.preventDefault();
    if(!this.canSubmit||this.sending()||!this.energy?.id)return;
    this.error.set('');this.notice.set('');
    let hours:{hour:string;value:string}[];
    try{hours=this.hoursForSubmit();}
    catch(error){this.error.set(error instanceof Error?error.message:'Série inválida.');return;}
    const existing=this.candidates();
    const last=existing.length?Math.max(...existing.map(x=>x.version)):0;
    this.sending.set(true);
    const project=this.projectId,energyId=this.energy.id;
    try {
      await firstValueFrom(this.http.post<EfelCandidate>(this.root(),{
        energyCandidateId:energyId,sourceTitle:this.title().trim(),
        sourcePublisher:this.publisher().trim(),sourceUri:this.uri().trim(),
        sourceEdition:this.edition().trim(),claimedProcedure:this.procedure().trim(),
        publicationDate:this.published(),sourceVintage:this.vintage().trim(),
        sourceSha256:this.sha().trim(),sourceRightsReference:this.rights().trim(),
        evidenceNote:this.note().trim(),unit:this.unit(),hours
      },{headers:{'If-Match':'"'+last+'"','Idempotency-Key':crypto.randomUUID()}}));
      if(this.projectId===project && this.energy.id===energyId) {
        await this.reload();
        this.notice.set('Série EFel registrada como candidata. Fonte ainda não admitida para E8.');
      }
    } catch(error){if(this.projectId===project)this.error.set(this.message(error));}
    finally {this.sending.set(false);}
  }
  async review(event:Event) {
    event.preventDefault();
    const candidate=this.selected();
    if(!this.canReview||!candidate||this.reviewing())return;
    const project=this.projectId,energyId=this.energy.id;
    this.reviewing.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.post<EfelCandidate>(
        this.root()+'/'+candidate.id+'/reviews',{
          outcome:this.reviewOutcome(),rationale:this.reviewReason().trim(),
          evidenceReference:this.reviewReference().trim()
        },{headers:{'If-Match':'"'+(candidate.review?.version??0)+'"',
          'Idempotency-Key':crypto.randomUUID()}}));
      if(this.projectId===project&&this.energy.id===energyId) {
        await this.reload();
        this.notice.set('Parecer interno registrado. A série continua sem admissão normativa.');
      }
    } catch(error){if(this.projectId===project)this.error.set(this.message(error));}
    finally {this.reviewing.set(false);}
  }
  private message(error:unknown) {
    if(error instanceof HttpErrorResponse)
      return error.error?.detail||error.error?.message||
        'Operação indisponível ('+error.status+').';
    return 'Falha ao processar a fonte EFel.';
  }
}
