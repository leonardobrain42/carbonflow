import {Injectable, inject} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom} from 'rxjs';

export interface ProjectJourneyPosition {
  area:'triage'|'technical';
  screen:string|null;
  guide:string|null;
  step:string|null;
}

/**
 * Serializes navigation checkpoint PUTs across the account/project pages.
 * A failed checkpoint never blocks navigation or changes the canonical revision.
 * The server always enforces project-level permissions and validates every step.
 */
@Injectable({providedIn:'root'})
export class JourneyCheckpointService {
  private readonly http=inject(HttpClient);
  private tail:Promise<void>=Promise.resolve();
  private lastRequested='';
  remember(projectId:string,position:ProjectJourneyPosition):void {
    if(!projectId)return;
    const payload=JSON.stringify(position);
    const signature=projectId+'|'+payload;
    if(signature===this.lastRequested)return;
    this.lastRequested=signature;
    this.tail=this.tail.catch(()=>undefined).then(async()=>{
      try{
        await firstValueFrom(this.http.put(
          '/v1/projects/'+encodeURIComponent(projectId)+'/journey/resume',
          position));
      }catch{
        // Never block the work surface. The Overview simply omits the checkpoint
        // if no permitted location exists. Retry on a later route transition.
        if(this.lastRequested===signature)this.lastRequested='';
      }
    });
  }
}
