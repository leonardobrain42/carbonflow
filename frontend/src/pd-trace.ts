import {Component, Input, output} from '@angular/core';

/**
 * Read-only provenance preview for #43. An existing PD section is an actual
 * saved artifact; a typed guide is a declared input, not incorporated into
 * that artifact until the normative, versioned #43 mapping exists.
 */
type Field={key:string;label:string;type:string;unit:string|null;helpText:string|null};
type Guide={id:string;title:string;version:number;definitionVersion:number;edition:string;
  methodology:string;fields:Field[];answers:Record<string,unknown>;state:string;
  missing:string[];legacyNeedsReview:string[];normativeApproved:boolean};
type Section={guide:{id:string;title:string;version:number;missing:string[]};
  latest:{number:number;guideVersion:number;sha256:string}|null;
  monitoringDependency:{state:string;outdated:boolean}|null};

@Component({selector:'carbonflow-pd-trace',standalone:true,templateUrl:'./pd-trace.html'})
export class PdTraceComponent {
  @Input({required:true}) kind='';
  @Input() guide:Guide|null=null;
  @Input() section:Section|null=null;
  @Input() canViewPd=false;
  readonly openDocument=output<string>();
  entries(){return (this.guide?.fields??[])
    .filter(f=>this.guide?.answers[f.key]!==undefined &&
      this.guide.answers[f.key]!==null)
    .map(f=>({id:f.key,label:f.label,unit:f.unit,value:this.render(this.guide?.answers[f.key])}));}
  private render(v:unknown):string{
    if(Array.isArray(v))return v.join(', ');
    if(v===true)return 'Sim';if(v===false)return 'Não';
    return v===null||v===undefined?'Não informado':String(v);
  }
  /** The legacy PD authoring API is pinned to legacy guide revisions, not typed answers. */
  typedIncludedInExistingPd(){return false;}
  legacyOutdated(){return !!this.section?.latest &&
    this.section.latest.guideVersion!==this.section.guide.version;}
}
