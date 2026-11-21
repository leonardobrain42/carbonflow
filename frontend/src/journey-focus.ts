import {Component, Input, OnChanges, SimpleChanges, inject, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

interface Destination {area:'triage'|'technical';screen:string|null;guide:string|null;step?:string|null}
interface Task {id:string;title:string;reason:string;actionable:boolean;destination:Destination|null;
  state:string}
interface Journey {projectId:string;nextTask:string|null;tasks:Task[];progress?:{
  visible:number;recorded:number;needsAttention:number;inReview:number;actionable:number
}}

/** Small, live bridge between the dashboard and the actual technical work surface. */
@Component({
  selector:'carbonflow-journey-focus',standalone:true,
  imports:[RouterLink],templateUrl:'./journey-focus.html'
})
export class JourneyFocusComponent implements OnChanges {
  @Input({required:true}) projectId='';
  private readonly http=inject(HttpClient);
  readonly busy=signal(false);
  readonly journey=signal<Journey|null>(null);
  readonly error=signal(false);
  private seq=0;
  ngOnChanges(changes:SimpleChanges){
    if(changes['projectId'])void this.refresh();
  }
  async refresh(){
    if(!this.projectId)return;
    const current=++this.seq;
    this.busy.set(true);this.error.set(false);
    try{
      const result=await firstValueFrom(this.http.get<Journey>(
        '/v1/projects/'+encodeURIComponent(this.projectId)+'/journey'));
      if(current===this.seq)this.journey.set(result.projectId===this.projectId?result:null);
    }catch{
      if(current===this.seq){this.journey.set(null);this.error.set(true);}
    }finally{if(current===this.seq)this.busy.set(false);}
  }
  next(){
    const j=this.journey();
    return j?.tasks.find(task=>task.id===j.nextTask && task.actionable)??null;
  }
  link(task:Task){return ['/workspace/projects',this.projectId,task.destination?.area];}
  params(task:Task){
    const destination=task.destination;
    return {secao:destination?.screen??null,guia:destination?.guide??null,
      etapa:destination?.step??null};
  }
}
