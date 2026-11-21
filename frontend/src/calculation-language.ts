const GATE_NAMES: Record<string, string> = {
  METHODOLOGY: 'Metodologia',
  COUNTRY: 'País',
  VEHICLE_CLASS: 'Classe de veículos',
  OPEN_APPLICABILITY: 'Declaração de rede aberta',
  NETWORK_MODE: 'Modo da rede',
  TECHNICAL_BRANCH: 'Ramo técnico',
  NORMATIVE_PACK: 'Regra e edição',
  CARBON_RIGHTS: 'Direitos sobre as reduções',
  EVR: 'Estoque nacional EVR',
  AFEC_MPG: 'Ratings e comparáveis',
  EF_FUEL: 'Fator fóssil',
  IR: 'Melhoria tecnológica',
  DY: 'Desconto por outro crédito',
  ED: 'Energia entregue',
  EC: 'Energia consumida',
  EF_GRID: 'Fator elétrico da rede',
  MR_PERIOD_DEPENDENCY: 'Período de monitoramento',
};

const GATE_PREFIXES: [string, string][] = [
  ['MR_DEPENDENCY_', 'Monitoramento do período'],
  ['METHOD_REVIEW_', 'Parecer do método no período'],
  ['E8_ENERGY_INPUT_', 'Energia do período'],
  ['E8_EFEL_', 'Fator elétrico do período'],
  ['E8_VT0011_CASE2_', 'Caderno elétrico do período'],
  ['E8_TOOL07_SIMPLE_OM_', 'Apuração preliminar do período'],
  ['E8_INPUT_BINDING_', 'Vínculo de energia do período'],
  ['E8_FLEET_SELECTION_', 'Vínculo de frota do período'],
];

const ROLES: Record<string, string> = {
  PREP: 'preparação',
  REV: 'revisão',
  PP: 'proponente',
  OP: 'operação',
  GESTOR_FONTES: 'gestão de fontes',
};

export function calculationGateTitle(code: string): string {
  if (GATE_NAMES[code]) return GATE_NAMES[code];
  const period = code.split('_').at(-1) ?? '';
  for (const [prefix, title] of GATE_PREFIXES) {
    if (code.startsWith(prefix)) return title + ' ' + period;
  }
  return code;
}

const PREPARATORY_NOTES: Record<string, string> = {
  EVR_AND_RATINGS_NOT_NORMATIVELY_ADMITTED:
    'Estoque EVR e ratings continuam sem admissão normativa.',
  EF_FUEL_IR_AND_DY_NOT_ADMITTED:
    'Fator fóssil, melhoria tecnológica e desconto continuam sem admissão.',
  BE_PE_ER_EXECUTION_NOT_ENABLED:
    'BE, PE e ER continuam sem execução.',
  ENERGY_AND_GRID_BINDING_STALE:
    'O vínculo de energia e fator elétrico deste período ficou desatualizado.',
  ENERGY_VERSION_SUPERSEDED:
    'Há um vínculo de energia mais recente que o usado nesta seleção.',
  PERIOD_YEAR_CHANGED:
    'O ano do período deixou de coincidir com o ano da seleção.',
  FLEET_RATING_SOURCE_CHANGED:
    'A origem do estoque ou dos ratings mudou desde esta seleção.',
  FLEET_RATING_LICENSE_EXPIRED_OR_REVOKED:
    'A licença da origem de ratings venceu ou foi revogada.',
  FLEET_RATING_SOURCE_SUSPENDED_OR_STALE:
    'A origem de estoque ou ratings está suspensa ou desatualizada.',
  SELECTION_VERSION_SUPERSEDED:
    'Há uma seleção mais recente para este período.',
  SOURCE_BINDING_CHANGED:
    'A energia ou o fator elétrico mudou desde este vínculo.',
  ENERGY_SOURCE_STALE:
    'A energia deste vínculo ficou desatualizada.',
  EFEL_SOURCE_STALE:
    'O fator elétrico deste vínculo ficou desatualizado.',
  EFEL_REVIEW_CHANGED:
    'O parecer interno do fator elétrico mudou desde este vínculo.',
  EVR_AND_COMPARABLES_NOT_ADMITTED:
    'Estoque EVR e comparáveis continuam sem admissão.',
  FOSSIL_FACTOR_IR_AND_DY_NOT_ADMITTED:
    'Fator fóssil, melhoria tecnológica e desconto continuam sem admissão.',
  NORMATIVE_VT0011_RIGHTS_NOT_ADMITTED:
    'Caderno elétrico e direitos da série continuam sem admissão.',
  E8_EXECUTION_NOT_ENABLED:
    'A execução do cálculo continua fechada.',
};

/** Visible sentence for a stored preparatory gate. The code stays off the sentence. */
export function preparatoryNote(code: string): string {
  return PREPARATORY_NOTES[code] ?? 'Situação ainda sem texto público.';
}

/** A planned role is a function, never a named person or a new permission. */
export function plannedRole(owner: string): string {
  const parts = owner.split('/').filter(part => part.length > 0)
    .map(part => ROLES[part] ?? part);
  if (parts.length === 0) return owner;
  if (parts.length === 1) return parts[0];
  if (parts.length === 2) return parts[0] + ' e ' + parts[1];
  return parts.slice(0, -1).join(', ') + ' e ' + parts.at(-1);
}
