import {Component, Input, OnChanges, OnDestroy, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

interface AnnualTotals {
  beTco2e:string;peTco2e:string;leTco2e:string;erTco2e:string;erDisplay:string;
}
interface AnnualConsolidation {
  projectId:string;projectYear:number;windowStart:string|null;windowEndExclusive:string|null;
  state:string;usableForE8:boolean;canIssueVcu:boolean;totals:AnnualTotals|null;manifestSha256:string;
  members:{period:string;runId:string|null;version:number;inputSha256:string|null;
    windowStart:string|null;windowEndExclusive:string|null;state:string;stale:boolean}[];
  issues:{code:string;period:string|null;from:string|null;until:string|null;detail:string}[];
}

@Component({
  selector:'carbonflow-open-e8-annual',
  standalone:true,
  templateUrl:'./open-e8-annual.html',
  styles:['code {overflow-wrap:anywhere}'],
})
export class OpenE8AnnualComponent implements OnChanges, OnDestroy {
  @Input({required:true}) projectId='';
  @Input() refreshKey='';
  private readonly http=inject(HttpClient);
  readonly year=signal(1);
  readonly loading=signal(false);
  readonly error=signal('');
  readonly result=signal<AnnualConsolidation|null>(null);
  private request=0;

  ngOnChanges(changes:SimpleChanges) {
    if(changes['projectId'])this.year.set(1);
    void this.load(this.year());
  }

  ngOnDestroy() { this.request++; }

  async load(year:number) {
    const request=++this.request;
    const project=this.projectId;
    this.result.set(null);
    this.error.set('');
    this.loading.set(false);
    if(!Number.isInteger(year)||year<1||year>100) {
      this.error.set('Informe um ano do projeto entre 1 e 100.');
      return;
    }
    this.year.set(year);
    if(!project)return;
    this.loading.set(true);
    try {
      const result=await firstValueFrom(this.http.get<AnnualConsolidation>(
        '/v1/projects/'+encodeURIComponent(project)+'/technical/open-calculation-years/'+year));
      if(request!==this.request||project!==this.projectId)return;
      if(result.projectId!==project||result.projectYear!==year||result.usableForE8||result.canIssueVcu
        ||result.totals&&result.state!=='REVIEWED_INTERNAL')throw new Error('Inconsistent annual result');
      this.result.set(result);
    } catch(problem) {
      if(request!==this.request||project!==this.projectId)return;
      this.error.set(problem instanceof HttpErrorResponse&&[401,403,404].includes(problem.status)
        ? 'Seu acesso à consolidação anual está indisponível.'
        : 'Não foi possível consultar a consolidação anual. Tente novamente.');
    } finally {
      if(request===this.request)this.loading.set(false);
    }
  }
}
