/**
 * Suggestions taken from the owner's own triage answers. They are never saved by themselves:
 * the person applies them to empty fields and then saves the screen as usual. Only exact
 * equivalences are mapped; answers without an equivalent (e.g. combustion fleet) are left out.
 */
export type TriageAnswers = Record<string, Record<string, string | number | null> | string>;
export type Suggestion = {field: string; value: string; label: string};

export function triageValue(answers: TriageAnswers | null | undefined, step: number, key: string): string | null {
  const group = answers?.[String(step)];
  if (!group || typeof group !== 'object') return null;
  const value = group[key];
  return value == null || value === '' ? null : String(value);
}

const SAME = (allowed: string[]) => (value: string) => allowed.includes(value) ? value : null;

const NETWORK: {step: number; key: string; field: string; label: string;
  map: (value: string) => string | null; text: (value: string) => string}[] = [
  {step:3, key:'access', field:'unrestrictedBevAccess', label:'Acesso aos carregadores',
    map:v => v === 'PUBLIC' ? 'true' : v === 'PRIVATE' ? 'false' : null,
    text:v => v === 'PUBLIC' ? 'Aberto ao público' : 'Restrito a uma frota definida'},
  {step:3, key:'network', field:'restrictedToIdentifiedVehicles', label:'Identificação da frota',
    map:v => v === 'CLOSED' ? 'true' : v === 'OPEN' ? 'false' : null,
    text:v => v === 'CLOSED' ? 'Fechada, frota identificada' : 'Aberta, sem identificação individual'},
  {step:3, key:'chargerType', field:'chargerType', label:'Tipo de recarga',
    map:SAME(['DCFC','AC','MIXED']), text:v => v === 'MIXED' ? 'Combinação de tecnologias' : v},
  {step:3, key:'propulsion', field:'propulsion', label:'Propulsão',
    map:SAME(['BEV','PHEV','MIXED']), text:v => v === 'MIXED' ? 'Frota mista' : v},
  {step:3, key:'electricitySource', field:'electricitySource', label:'Origem da eletricidade',
    map:SAME(['SIN','OTHER_GRID','ON_SITE','MIXED']),
    text:v => ({SIN:'Somente SIN', OTHER_GRID:'Outra rede', ON_SITE:'Geração local', MIXED:'Mais de uma origem'} as Record<string,string>)[v] ?? v},
  {step:3, key:'associatedInfrastructure', field:'associatedInfrastructure', label:'Infraestrutura associada',
    map:v => v === 'YES' ? 'true' : v === 'NO' ? 'false' : null,
    text:v => v === 'YES' ? 'Sim' : 'Não'},
];

export function networkSuggestions(answers: TriageAnswers | null | undefined): Suggestion[] {
  const out: Suggestion[] = [];
  for (const item of NETWORK) {
    const raw = triageValue(answers, item.step, item.key);
    const value = raw == null ? null : item.map(raw);
    if (raw != null && value != null) out.push({field:item.field, value, label:item.label + ': ' + item.text(raw)});
  }
  return out;
}

const INTAKE: {step: number; key: string; field: string; label: string}[] = [
  {step:2, key:'activityDescription', field:'activity', label:'Atividade'},
  {step:4, key:'country', field:'country', label:'País'},
  {step:4, key:'state', field:'state', label:'Estado ou região'},
  {step:4, key:'locality', field:'locality', label:'Cidade/localidade'},
  {step:4, key:'referenceDate', field:'referenceActDate', label:'Data de referência'},
];

export function intakeSuggestions(answers: TriageAnswers | null | undefined): Suggestion[] {
  const out: Suggestion[] = [];
  for (const item of INTAKE) {
    const value = triageValue(answers, item.step, item.key);
    if (value != null) out.push({field:item.field, value, label:item.label + ': ' + value});
  }
  return out;
}
