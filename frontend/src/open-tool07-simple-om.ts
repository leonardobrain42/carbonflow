import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';
import {plainTerm, preparatoryNote} from './calculation-language';

type WorkbookRef = {id:string;period:string;omDataYear:number;
  omMethod:string;omVintageMode:string;state:string;review:{version:number}|null};
type Fuel = {fuelCode:string;consumptionTonnes:string;ncvGjPerTonne:string;
  efTco2PerGj:string;sourceSha256:string};
type Plant = {plantCode:string;netGenerationMwh:string;fuels:Fuel[]};
type Share = {year:number;totalGridMwh:string;lcmrMwh:string};
type ShareCalculation = Share & {annualShare:string};
type FuelCalculation = {fuelCode:string;consumptionTonnes:string;
  ncvGjPerTonne:string;efTco2PerGj:string;emissionsTco2:string;
  declaredSourceSha256:string;equationRef:string};
type Preview = {reference:string;approach:string;dataYear:number;
  shareApproach1:string;shareApproach2:string;selectedShare:string;
  fiveYearLcmrScreenPassed:boolean;includedGenerationMwh:string;
  totalEmissionsTco2:string;illustrativeOmTco2PerMwh:string;
  illustrativeOmTco2PerKwh:string;plantCalculations:{
    plantCode:string;netGenerationMwh:string;emissionsTco2:string;
    efTco2PerMwh:string;fuelCalculations:FuelCalculation[]}[];
  shareCalculations:ShareCalculation[];gates:string[];usableForE8:boolean};
type Run = {id:string;period:string;workbookId:string;
  workbookReviewVersion:number;version:number;state:string;stale:boolean;
  usableForE8:boolean;contentSha256:string;createdAt:string;
  inventorySourceReference:string;inventorySha256:string;
  fiveYearSourceReference:string;fiveYearSha256:string;
  fuelSourceExplanation:string;systemBoundaryNote:string;importsExplanation:string;
  preview:Preview;shares:Share[];plants:Plant[];gates:string[]};

@Component({selector:'carbonflow-tool07-simple-om-a1',standalone:true,
  templateUrl:'./open-tool07-simple-om.html'})
export class OpenTool07SimpleOmComponent implements OnChanges {
  readonly term=plainTerm;
  readonly note=preparatoryNote;
  @Input({required:true}) projectId='';
  @Input({required:true}) workbook!:WorkbookRef;
  @Input() canView=false;
  @Input() canPrepare=false;

  private readonly http=inject(HttpClient);
  readonly runs=signal<Run[]>([]);
  readonly selected=signal<Run|null>(null);
  readonly loading=signal(false);
  readonly saving=signal(false);
  readonly error=signal('');
  readonly notice=signal('');
  readonly shareApproach=signal('');
  readonly shareCsv=signal('');
  readonly plantCsv=signal('');
  readonly inventoryRef=signal('');
  readonly inventorySha=signal('');
  readonly fiveYearRef=signal('');
  readonly fiveYearSha=signal('');
  readonly fuelNote=signal('');
  readonly boundaryNote=signal('');
  readonly importsNote=signal('');

  ngOnChanges(changes:SimpleChanges) {
    if(changes['workbook']||changes['projectId']) {
      this.runs.set([]);this.selected.set(null);this.error.set('');this.notice.set('');
      if(this.workbook?.omDataYear) {
        const years=Array.from({length:5},(_,i)=>this.workbook.omDataYear-4+i);
        this.shareCsv.set(years.map(y=>y+',,').join('\n'));
      }
      if(this.canView&&this.workbook?.id)void this.reload();
    }
  }
  get readyForCandidate() {
    return this.workbook?.omMethod==='SIMPLE_OM' &&
      this.workbook?.omVintageMode==='EX_POST' &&
      this.workbook?.state==='CHECKED_INTERNAL_NOT_ADMITTED' &&
      (this.workbook?.review?.version??0)>0;
  }
  private root() {
    return '/v1/projects/'+encodeURIComponent(this.projectId)+
      '/technical/open-monitoring/dossiers/'+encodeURIComponent(this.workbook.period)+
      '/tool07-simple-om-a1/'+encodeURIComponent(this.workbook.id)+'/runs';
  }
  auditUrl(runId:string) {
    // The server checks the same project/role scope as the run list.
    return this.root()+'/'+encodeURIComponent(runId);
  }

  async reload(){
    if(!this.canView||!this.workbook?.id)return;
    const project=this.projectId,source=this.workbook.id;
    this.loading.set(true);
    try {
      const results=await firstValueFrom(this.http.get<Run[]>(this.root()));
      if(this.projectId===project&&this.workbook.id===source) {
        this.runs.set(results);
        const selected=this.selected();
        this.selected.set(selected?results.find(r=>r.id===selected.id)??null:null);
      }
    } catch(err) {if(this.projectId===project)this.error.set(this.message(err));}
    finally {this.loading.set(false);}
  }
  private number(raw:string) {
    const s=raw.trim();
    if(!/^\d+(\.\d{1,12})?$/.test(s))
      throw new Error('Use valores numéricos explícitos e ponto decimal (até 12 casas).');
    return s;
  }
  private sha(raw:string) {
    const s=raw.trim();
    if(!/^[0-9a-fA-F]{64}$/.test(s))
      throw new Error('SHA-256 deve conter 64 caracteres hexadecimais reais da fonte declarada.');
    return s.toLowerCase();
  }
  private parseShares():Share[]{
    const lines=this.shareCsv().trim().split(/\r?\n/).filter(Boolean);
    if(lines.length!==5)throw new Error('Preencha cinco anos contíguos de geração total e LCMR.');
    return lines.map((line,i)=>{
      const parts=line.split(',');
      if(parts.length!==3||!Number.isInteger(Number(parts[0]))||
         Number(parts[0])!==this.workbook.omDataYear-4+i)
        throw new Error('Os cinco anos devem ir de y−4 até y, em ordem.');
      return {year:Number(parts[0]),totalGridMwh:this.number(parts[1]),
        lcmrMwh:this.number(parts[2])};
    });
  }
  private parsePlants():Plant[]{
    const lines=this.plantCsv().trim().split(/\r?\n/).filter(Boolean);
    if(!lines.length||lines.length>1000)
      throw new Error('Preencha linhas por unidade/combustível; limite 1000 linhas.');
    const byPlant=new Map<string,Plant>();
    for(const line of lines) {
      const p=line.split(',');
      if(p.length!==7 || !/^[A-Za-z0-9_.-]{1,64}$/.test(p[0].trim()) ||
         !/^[A-Za-z0-9_.-]{1,64}$/.test(p[2].trim()))
        throw new Error('Cada linha exige unidade,geração,combustível,consumo,NCV,EF,SHA-256.');
      const plantCode=p[0].trim(),generation=this.number(p[1]);
      const fuel:Fuel={fuelCode:p[2].trim(),consumptionTonnes:this.number(p[3]),
        ncvGjPerTonne:this.number(p[4]),efTco2PerGj:this.number(p[5]),
        sourceSha256:this.sha(p[6])};
      const existing=byPlant.get(plantCode);
      if(existing){
        if(Number(existing.netGenerationMwh)!==Number(generation)||
           existing.fuels.some(f=>f.fuelCode===fuel.fuelCode))
          throw new Error('Unidade com geração divergente ou combustível duplicado.');
        existing.fuels.push(fuel);
      } else byPlant.set(plantCode,{plantCode,netGenerationMwh:generation,fuels:[fuel]});
    }
    return [...byPlant.values()];
  }
  async submit(event:Event){
    event.preventDefault();
    if(!this.canPrepare||!this.readyForCandidate||this.saving())return;
    let shares:Share[],plants:Plant[];
    try{shares=this.parseShares();plants=this.parsePlants();
      this.sha(this.inventorySha());this.sha(this.fiveYearSha());
      if(!this.shareApproach())throw new Error('Selecione a abordagem LCMR 1 ou 2.');
    }catch(err){this.error.set(err instanceof Error?err.message:'Dados inválidos.');return;}
    const project=this.projectId,workbook=this.workbook.id;
    const version=this.runs().length?Math.max(...this.runs().map(r=>r.version)):0;
    this.saving.set(true);this.error.set('');this.notice.set('');
    try {
      await firstValueFrom(this.http.post<Run>(this.root(),{
        workbookId:workbook,workbookReviewVersion:this.workbook.review!.version,
        inventorySourceReference:this.inventoryRef().trim(),
        inventorySha256:this.inventorySha().trim(),
        fiveYearSourceReference:this.fiveYearRef().trim(),
        fiveYearSha256:this.fiveYearSha().trim(),
        fuelSourceExplanation:this.fuelNote().trim(),
        systemBoundaryNote:this.boundaryNote().trim(),
        importsExplanation:this.importsNote().trim(),
        calculation:{dataYear:this.workbook.omDataYear,
          shareApproach:this.shareApproach(),shares,plants}
      },{headers:{'If-Match':'"'+version+'"','Idempotency-Key':crypto.randomUUID()}}));
      if(this.projectId===project&&this.workbook.id===workbook){
        await this.reload();
        this.notice.set('Apuração preliminar registrada. Não é fator admitido para E8.');
      }
    } catch(err) {if(this.projectId===project)this.error.set(this.message(err));}
    finally {this.saving.set(false);}
  }
  private message(err:unknown){
    if(err instanceof HttpErrorResponse)
      return err.error?.detail??err.error?.message??'Falha '+err.status;
    return 'Falha na apuração de Simple OM.';
  }
}
