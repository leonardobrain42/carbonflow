import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

type ObjectState = 'QUARANTINED'|'NORMALIZED_CANDIDATE'|'SCHEMA_REJECTED'
  |'APPROVED_FOR_SCOPE'|'REJECTED_FOR_SCOPE'|'TECHNICAL_REVIEW_REQUIRED'|'SUSPENDED';
type Decision = 'APPROVED_FOR_SCOPE'|'REJECTED_FOR_SCOPE'|'NEEDS_INFORMATION';

interface SourceObject {
  id:string; submissionId:string; uploadedBy:string; filename:string; format:'CSV'|'XLSX';
  sha256:string; bytesCount:number; state:ObjectState; issues:string[]; parsedRows:number;
  uploadedAt:string; licenseActive:boolean; usableForE8:boolean;
}
interface Board {actorId:string;canUpload:boolean;canReview:boolean;files:SourceObject[]}
interface Sample {row:number; recordKey:string;year:number;metric:string;value:string|number;
  unit:string;geography:string;locator:string}
interface Preview {total:number;samples:Sample[]}
interface Assessment {id:string;outcome:Decision;metric:string;periodYear:number;
  geography:string;unit:string;reason:string}

@Component({
  selector:'carbonflow-source-file-quarantine',
  standalone:true,
  templateUrl:'./platform-source-files.html',
  styleUrl:'./platform-source-readiness.css',
})
export class PlatformSourceFilesComponent implements OnChanges {
  @Input({required:true}) submissionId='';
  private readonly http=inject(HttpClient);
  private epoch=0;
  readonly board=signal<Board|null>(null);
  readonly selected=signal<SourceObject|null>(null);
  readonly preview=signal<Preview|null>(null);
  readonly busy=signal(false);
  readonly error=signal('');
  readonly message=signal('');
  pendingFile:File|null=null;
  fileFormat:'CSV'|'XLSX'='CSV';
  reviewOutcome:Decision='NEEDS_INFORMATION';
  metric='';
  year='';
  geography='BR';
  unit='vehicles';
  evidenceLocator='';
  reason='';

  readonly units=['vehicles','units','kWh','MWh','km','MJ/km','kWh/km',
    'kWh/100km','km/L','mi/USgal','tCO2/MWh','tCO2/kWh','kgCO2e/km','tCO2e'];

  ngOnChanges(changes:SimpleChanges) {
    if(changes['submissionId']){
      this.board.set(null);
      this.selected.set(null);
      this.preview.set(null);
      this.pendingFile=null;
      void this.reload();
    }
  }

  async reload() {
    if(!this.submissionId)return;
    const epoch=++this.epoch;
    this.busy.set(true);
    this.error.set('');
    try {
      const result=await firstValueFrom(this.http.get<Board>(
        '/v1/platform/source-files/submissions/'+encodeURIComponent(this.submissionId)));
      if(epoch!==this.epoch)return;
      this.board.set(result);
      const updated=result.files.find(x=>x.id===this.selected()?.id);
      if(updated)this.selected.set(updated);
    } catch(problem) {
      if(epoch===this.epoch)this.error.set(this.errorText(problem));
    } finally {
      if(epoch===this.epoch)this.busy.set(false);
    }
  }

  chooseFile(event:Event) {
    const element=event.target as HTMLInputElement;
    this.pendingFile=element.files?.item(0)??null;
    if(this.pendingFile)this.fileFormat=this.pendingFile.name.toLowerCase().endsWith('.xlsx')?'XLSX':'CSV';
  }

  async upload() {
    const file=this.pendingFile;
    if(!file || !this.board()?.canUpload || this.busy())return;
    if(file.size<16 || file.size>2*1024*1024){
      this.error.set('Arquivo deve conter entre 16 bytes e 2 MiB.');
      return;
    }
    if(!/^[A-Za-z0-9][A-Za-z0-9._-]{3,99}$/.test(file.name) ||
        file.name.includes('..')){
      this.error.set('Renomeie o arquivo usando letras, números, ponto, traço ou sublinhado.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    try {
      const headers=new HttpHeaders({
        'Content-Type':'application/octet-stream',
        'X-CarbonFlow-File-Request':'1',
        'X-Source-Filename':file.name,
        'X-Source-Format':this.fileFormat,
      });
      const created=await firstValueFrom(this.http.post<SourceObject>(
        '/v1/platform/source-files/submissions/'+encodeURIComponent(this.submissionId),
        file,{headers}));
      this.selected.set(created);
      this.preview.set(null);
      this.pendingFile=null;
      this.message.set('Arquivo cifrado em quarentena e SHA-256 calculado. Ainda não foi analisado ou aprovado.');
      await this.reload();
    } catch(problem) {
      this.error.set(this.errorText(problem));
    } finally {
      this.busy.set(false);
    }
  }

  selectFile(item:SourceObject) {
    this.selected.set(item);
    this.preview.set(null);
    this.metric='';
    this.year='';
    this.geography='BR';
    this.unit='vehicles';
    this.evidenceLocator='';
    this.reason='';
    this.reviewOutcome='NEEDS_INFORMATION';
    this.error.set('');
  }

  async parse() {
    const chosen=this.selected();
    if(!chosen || !this.board()?.canUpload || this.busy())return;
    this.busy.set(true);
    this.error.set('');
    try {
      const result=await firstValueFrom(this.http.post<SourceObject>(
        '/v1/platform/source-files/objects/'+encodeURIComponent(chosen.id)+'/parse',{}));
      this.selected.set(result);
      this.preview.set(null);
      this.message.set(result.state==='NORMALIZED_CANDIDATE'
        ? 'Schema validado. Os valores são apenas candidatos aguardando parecer técnico.'
        : 'O arquivo permaneceu bloqueado: foram encontrados problemas nas colunas ou nas linhas.');
      await this.reload();
    } catch(problem) {this.error.set(this.errorText(problem));}
    finally {this.busy.set(false);}
  }

  async inspect() {
    const chosen=this.selected();
    if(!chosen || !this.board()?.canReview || this.busy())return;
    this.busy.set(true);this.error.set('');
    try {
      const preview=await firstValueFrom(this.http.get<Preview>(
        '/v1/platform/source-files/objects/'+encodeURIComponent(chosen.id)+'/candidates'));
      this.preview.set(preview);
      const first=preview.samples[0];
      if(first){
        this.metric=first.metric;this.year=String(first.year);
        this.geography=first.geography;this.unit=first.unit;
        this.evidenceLocator=first.locator;
      }
    } catch(problem){this.error.set(this.errorText(problem));}
    finally {this.busy.set(false);}
  }

  async assess(event:Event) {
    event.preventDefault();
    const chosen=this.selected();
    if(!chosen || !this.board()?.canReview || this.busy())return;
    if(this.reviewOutcome==='APPROVED_FOR_SCOPE' && !this.preview()){
      this.error.set('Para aprovar é obrigatório consultar a prévia sob licença SHOW_EXCERPTS.');
      return;
    }
    this.busy.set(true);this.error.set('');this.message.set('');
    try {
      const result=await firstValueFrom(this.http.post<Assessment>(
        '/v1/platform/source-files/objects/'+encodeURIComponent(chosen.id)+'/assess',
        {outcome:this.reviewOutcome,metric:this.metric,periodYear:Number(this.year),
         geography:this.geography,unit:this.unit,evidenceLocator:this.evidenceLocator,
         reason:this.reason}));
      this.message.set(result.outcome==='APPROVED_FOR_SCOPE'
        ? 'Parecer registrado para dimensões específicas. O E8 permanece desconectado.'
        : 'Parecer de pendência/rejeição registrado. Nenhum parâmetro foi admitido no E8.');
      this.preview.set(null);
      await this.reload();
    } catch(problem){this.error.set(this.errorText(problem));}
    finally {this.busy.set(false);}
  }

  stateLabel(s:ObjectState):string {
    return ({
      QUARANTINED:'Quarentena — sem extração',
      NORMALIZED_CANDIDATE:'Candidato validado — não admitido',
      SCHEMA_REJECTED:'Schema rejeitado — revisar planilha',
      TECHNICAL_REVIEW_REQUIRED:'Complementação técnica requerida',
      APPROVED_FOR_SCOPE:'Parecer técnico restrito — não liberado ao E8',
      REJECTED_FOR_SCOPE:'Rejeitado para o escopo',
      SUSPENDED:'Suspenso / licença não vigente',
    } as Record<ObjectState,string>)[s]??s;
  }

  private errorText(problem:unknown):string {
    if(problem instanceof HttpErrorResponse){
      if(problem.status===413)return 'Arquivo maior que o limite de 2 MiB.';
      if(problem.status===415)return 'Formato real do arquivo incompatível com CSV/XLSX.';
      if(problem.status===403 || problem.status===404)
        return 'Permissão insuficiente, licença expirada ou arquivo fora da sua organização.';
      if(problem.status===409)return 'Arquivo/parecer em conflito ou estado incompatível. Atualize.';
      if(problem.status===422)return 'Verifique nome do arquivo, SHA-256, schema e dimensões declaradas.';
      if(problem.status===503)return 'Chave de armazenamento criptografado ainda não configurada.';
    }
    return 'Não foi possível processar esta operação. Consulte o histórico e tente novamente.';
  }
}
