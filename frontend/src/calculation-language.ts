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

/** A planned role is a function, never a named person or a new permission. */
export function plannedRole(owner: string): string {
  const parts = owner.split('/').filter(part => part.length > 0)
    .map(part => ROLES[part] ?? part);
  if (parts.length === 0) return owner;
  if (parts.length === 1) return parts[0];
  if (parts.length === 2) return parts[0] + ' e ' + parts[1];
  return parts.slice(0, -1).join(', ') + ' e ' + parts.at(-1);
}
