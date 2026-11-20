import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type Preview = {reference:string;caseCode:string;formula:string;
  omWeight:string;bmWeight:string;omFactorTco2PerMwh:string;
  illustrativeCmTco2PerMwh:string;illustrativeCmTco2PerKwh:string;
  originalUnit:string;convertedUnit:string;usableForE8:boolean;qualification:string};
type Review = {version:number;outcome:'CHECKED_INTERNAL'|'RETURNED';
  rationale:string;evidenceReference:string;reviewerId:string;reviewedAt:string};
type Workbook = {id:string;period:string;version:number;efelCandidateId:string;
  efelReviewVersion:number;sourceSha256:string;vt0011Version:string;
  tool07Version:string;gridSystem:string;caseCode:string;omMethod:string;
  omVintageMode:string;omDataYear:number;omSourceReference:string;
  omDatasetSha256:string;omMethodJustification:string;gridBoundaryEvidence:string;
  sampleSelectionEvidence:string;importTreatmentEvidence:string;
  vintageEvidence:string;methodologyNote:string;contentSha256:string;
  authoredBy:string;authoredAt:string;preview:Preview;review:Review|null;
  state:string;stale:boolean;usableForE8:boolean;gates:string[]};

@Component({selector:'carbonflow-vt0011-workbooks',standalone:true,
  templateUrl:'./open-vt0011.html'})
export class OpenVt0011WorkbooksComponent implements OnChanges {
  @Input({required:true}) projectId='';
  @Input({required:true}) period='';
  @Input({required:true}) efelCandidateId='';
  @Input() sourceCurrent=false;
  @Input() canView=false;
  @Input() canSubmit=false;
  @Input() canReview=false;
  private readonly http=inject(HttpClient);

  readonly workbooks=signal<Workbook[]>([]);
  readonly selected=signal<Workbook|null>(null);
  readonly busy=signal(false);
  readonly saving=signal(false);
  readonly reviewing=signal(false);
  readonly error=signal('');
  readonly notice=signal('');
  readonly omMethod=signal('SIMPLE_OM');
  readonly vintageMode=signal('EX_POST');
  readonly dataYear=signal('');
  readonly omFactor=signal('');
  readonly sourceReference=signal('');
  readonly datasetSha=signal('');
  readonly methodJustification=signal('');
  readonly boundaryEvidence=signal('');
  readonly sampleEvidence=signal('');
  readonly importEvidence=signal('');
  readonly vintageEvidence=signal('');
  readonly methodologyNote=signal('');
  readonly reviewOutcome=signal<'CHECKED_INTERNAL'|'RETURNED'>('RETURNED');
  readonly reviewReason=signal('');
  readonly reviewReference=signal('');

  ngOnChanges(changes:SimpleChanges) {
    if(changes['projectId']||changes['period']||changes['efelCandidateId']) {
      this.workbooks.set([]);this.selected.set(null);
      this.error.set('');this.notice.set('');
      if(this.canView && this.efelCandidateId)void this.reload();
    }
  }
  private root(){
    return '/v1/projects/'+encodeURIComponent(this.projectId)+
      '/technical/open-monitoring/dossiers/'+encodeURIComponent(this.period)+
      '/efel-candidates/'+encodeURIComponent(this.efelCandidateId)+
      '/vt0011-workbooks';
  }
  async reload() {
    if(!this.canView)return;
    const project=this.projectId,period=this.period,source=this.efelCandidateId;
    this.busy.set(true);
    try {
      const values=await firstValueFrom(this.http.get<Workbook[]>(this.root()));
      if(this.projectId===project && this.period===period && this.efelCandidateId===source) {
        this.workbooks.set(values);
        const selected=this.selected();
        this.selected.set(selected?values.find(x=>x.id===selected.id)??null:null);
      }
    } catch(error){if(this.projectId===project)this.error.set(this.message(error));}
    finally {this.busy.set(false);}
  }
  select(item:Workbook){
    this.selected.set(item);
    this.reviewOutcome.set(item.review?.outcome??'RETURNED');
    this.reviewReason.set(item.review?.rationale??'');
    this.reviewReference.set(item.review?.evidenceReference??'');
  }
  async submit(event:Event) {
    event.preventDefault();
    if(!this.canSubmit||!this.sourceCurrent||this.saving())return;
    if(!/^\d+(\.\d{1,12})?$/.test(this.omFactor().trim())) {
      this.error.set('Informe OM em tCO2/MWh como decimal explícito, sem presumir valores.');
      return;
    }
    const project=this.projectId,period=this.period,source=this.efelCandidateId;
    const expected=this.workbooks().length?Math.max(...this.workbooks().map(x=>x.version)):0;
    this.saving.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.post<Workbook>(this.root(),{
        efelCandidateId:source,omMethod:this.omMethod(),
        omVintageMode:this.vintageMode(),omDataYear:Number(this.dataYear()),
        omFactorTco2PerMwh:this.omFactor().trim(),
        omSourceReference:this.sourceReference().trim(),
        omDatasetSha256:this.datasetSha().trim(),
        omMethodJustification:this.methodJustification().trim(),
        gridBoundaryEvidence:this.boundaryEvidence().trim(),
        sampleSelectionEvidence:this.sampleEvidence().trim(),
        importTreatmentEvidence:this.importEvidence().trim(),
        vintageEvidence:this.vintageEvidence().trim(),
        methodologyNote:this.methodologyNote().trim()
      },{headers:{'If-Match':'"'+expected+'"','Idempotency-Key':crypto.randomUUID()}}));
      if(this.projectId===project&&this.period===period&&this.efelCandidateId===source){
        await this.reload();
        this.notice.set('Caderno VT0011 registrado como NÃO ADMITIDO. OM e gases ainda exigem comprovação.');
      }
    } catch(error){if(this.projectId===project)this.error.set(this.message(error));}
    finally {this.saving.set(false);}
  }
  async review(event:Event) {
    event.preventDefault();
    const item=this.selected();
    if(!this.canReview||!item||this.reviewing()||item.stale)return;
    const project=this.projectId,source=this.efelCandidateId;
    this.reviewing.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.post<Workbook>(
        this.root()+'/'+item.id+'/reviews',{
          outcome:this.reviewOutcome(),rationale:this.reviewReason().trim(),
          evidenceReference:this.reviewReference().trim()
        },{headers:{
          'If-Match':'"'+(item.review?.version??0)+'"',
          'Idempotency-Key':crypto.randomUUID()
        }}));
      if(this.projectId===project&&this.efelCandidateId===source){
        await this.reload();
        this.notice.set('Parecer histórico registrado, sem aprovar fator OM ou E8.');
      }
    } catch(error){if(this.projectId===project)this.error.set(this.message(error));}
    finally {this.reviewing.set(false);}
  }
  private message(error:unknown){
    if(error instanceof HttpErrorResponse)
      return error.error?.detail??error.error?.message??
        'Operação indisponível ('+error.status+').';
    return 'Erro no caderno metodológico.';
  }
}
