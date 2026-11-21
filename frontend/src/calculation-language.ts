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

const MONITORING_TITLES: Record<string, string> = {
  CHARGER_MISSING: 'Carregador',
  CHARGER_TOPOLOGY: 'Topologia do carregador',
  CHARGER_POWER: 'Potência nominal',
  CHARGER_PROVENANCE: 'Origem do inventário',
  CHARGER_TIME_ZONE: 'Fuso da instalação',
  ED_METER_MISSING: 'Medidor de energia entregue',
  EC_METER_MISSING: 'Medidor de energia consumida',
  METER_ROLE_CONFLICT: 'Papel dos medidores',
  METER_BOUNDARY_CONFLICT: 'Fronteira dos medidores',
  CHARGER_CONFIGURATION_STALE: 'Configuração do carregador',
  METER_SOURCE_MISSING: 'Origem das leituras',
  METER_TIME_ZONE: 'Fuso do instrumento',
  CALIBRATION_MISSING: 'Calibração',
  CALIBRATION_PERIOD_UNSET: 'Período da calibração',
  CALIBRATION_NOT_COVERING: 'Cobertura da calibração',
  METER_CONFIGURATION_NOT_COVERING: 'Cobertura da configuração',
  CALIBRATION_NOT_REVIEWED: 'Revisão da calibração',
  ED_EC_OBSERVATIONS: 'Medições de energia',
  CHARGER_VERSION_STALE: 'Revisão do carregador',
  OUT_OF_SCOPE_TOPOLOGY: 'Topologia fora do ramo',
  NO_CHARGERS: 'Carregador',
  METER_VERSION_STALE: 'Revisão do medidor',
  QUARANTINE_UNRESOLVED: 'Leitura em quarentena',
  REVIEW_MISSING: 'Parecer da leitura',
  NON_HOURLY_READING: 'Intervalo da leitura',
  READING_INVALID: 'Leitura inválida',
  METER_CALIBRATION_STALE: 'Calibração da leitura',
  EC_BELOW_ED: 'Energia consumida e entregue',
  PENDING_MEASUREMENT: 'Medição do período',
  SNAPSHOT_QA_STALE: 'QA do período',
  SNAPSHOT_REVISIONS_CHANGED: 'Revisões posteriores',
  NO_MEASUREMENT_LINEAGE: 'Origem das medições',
  SUPPORT_DOCUMENT_MISSING: 'Documento de suporte',
  SUPPORT_REVIEW_PENDING: 'Parecer do documento',
  SUPPORT_DOCUMENT_REJECTED: 'Documento rejeitado',
  PD_MONITORING_INPUT_CHANGED: 'Vínculo do monitoramento no documento',
  METHOD_PROJECT_METHODOLOGY_CHANGED: 'Metodologia do projeto',
  METHOD_SNAPSHOT_STALE: 'Snapshot do parecer',
  METHOD_EVIDENCE_CHANGED: 'Evidências do parecer',
  METHOD_DOCUMENTARY_GAPS: 'Documentos do parecer',
  METHOD_REVIEW_PENDING: 'Parecer interno',
  METHOD_REVIEW_RETURNED: 'Correção do parecer',
};

/** Visible name for a monitoring gate. The code stays off the heading. */
export function monitoringGateTitle(code: string): string {
  if (MONITORING_TITLES[code]) return MONITORING_TITLES[code];
  if (code.startsWith('OPERATIONAL_')) return 'Ocorrência operacional';
  if (code.startsWith('HOUR_GAP_')) return 'Hora sem leitura';
  if (code.startsWith('HOUR_OVERLAP_')) return 'Horas sobrepostas';
  if (code.startsWith('MR_')) return 'Situação do período';
  if (code.startsWith('METHOD_')) return 'Parecer do método';
  return 'Situação ainda sem texto público';
}
