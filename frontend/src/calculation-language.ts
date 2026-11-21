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
  MR_VERSION_CHANGED:
    'A versão do monitoramento mudou desde este pacote.',
  METHOD_REVIEW_CHANGED:
    'O parecer do método mudou desde este pacote.',
  SNAPSHOT_OR_SUPPORT_STALE:
    'O snapshot ou os documentos de suporte ficaram desatualizados.',
  SOURCE_REVISIONS_CHANGED:
    'As revisões de origem mudaram desde este pacote.',
  CANDIDATE_LINEAGE_CHANGED:
    'A linhagem do pacote horário mudou.',
  ENERGY_CANDIDATE_CHANGED_OR_STALE:
    'O pacote de energia mudou ou ficou desatualizado.',
  EFEL_HOUR_COVERAGE_CHANGED:
    'A cobertura horária do fator elétrico mudou.',
  EFEL_SOURCE_VERSION_SUPERSEDED:
    'Há uma versão mais recente do fator elétrico.',
  EFEL_SOURCE_REVIEW_REQUIRED:
    'O fator elétrico ainda aguarda parecer interno.',
  NORMATIVE_SOURCE_ADMISSION_REQUIRED:
    'A fonte ainda não tem admissão normativa.',
  SOURCE_OR_REVIEW_STALE:
    'A fonte ou o parecer do caderno ficou desatualizado.',
  WORKBOOK_VERSION_SUPERSEDED:
    'Há um caderno mais recente.',
  INDEPENDENT_INTERNAL_REVIEW_PENDING:
    'O caderno ainda aguarda conferência interna independente.',
  INTERNAL_REVIEW_RETURNED:
    'A conferência interna devolveu o caderno.',
  TOOL07_OM_CALCULATION_NOT_VERIFIED:
    'O cálculo da margem de operação ainda não foi verificado.',
  SOURCE_DATASET_HASH_NOT_AUTHENTICATED:
    'O hash declarado do conjunto não autentica a fonte.',
  CO2_TO_CO2E_AND_HOURLY_EFEL_BRIDGE_MISSING:
    'Ainda não há ponte entre CO2 e a série horária em CO2e.',
  NORMATIVE_ADMISSION_AND_E8_BLOCKED:
    'A admissão normativa e a execução do cálculo continuam fechadas.',
  LCMR_5YEAR_SCREEN_NOT_PASSED:
    'A triagem de cinco anos não passou.',
  LCMR_CLASSIFICATION_NOT_AUTHENTICATED:
    'A classificação LCMR ainda não está autenticada.',
  PLANT_AND_FUEL_INPUTS_NOT_AUTHENTICATED:
    'Usinas e combustíveis ainda não estão autenticados.',
  IMPORTS_AND_GRID_BOUNDARY_NOT_VERIFIED:
    'Importações e fronteira da rede ainda não foram verificadas.',
  OPTION_A1_NCV_AND_CO2_FACTORS_NOT_VERIFIED:
    'Poder calorífico e fatores de CO2 ainda não foram verificados.',
  VT0011_DATA_VINTAGE_AND_OTHER_METHOD_CHECKS_PENDING:
    'Vintage e demais conferências do método continuam pendentes.',
  NO_CO2_TO_CO2E_HOURLY_BRIDGE:
    'Ainda não há ponte horária de CO2 para CO2e.',
  E8_NORMATIVE_ADMISSION_NOT_GRANTED:
    'A admissão normativa do cálculo não foi concedida.',
  WORKBOOK_OR_EFEL_STALE:
    'O caderno ou o fator elétrico ficou desatualizado.',
  OM_RUN_VERSION_SUPERSEDED:
    'Há uma apuração mais recente.',
  OM_RESULT_DIFFERS_FROM_PREVIOUSLY_DECLARED_FACTOR:
    'O resultado difere do fator declarado antes.',
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

const TERMS: Record<string, string> = {
  OPEN_CANDIDATE: 'Rede aberta candidata',
  PENDING_CONFIGURATION: 'Configuração pendente',
  EVIDENCE_PENDING: 'Prova ainda pendente',
  DECLARED_REVIEW_PENDING: 'Declaração aguardando revisão',
  PENDING_NORMATIVE_REVIEW: 'Revisão normativa pendente',
  PENDING_NORMATIVE_SOURCE: 'Fonte normativa pendente',
  PENDING_OFFICIAL_DATA: 'Dados oficiais pendentes',
  PENDING_LEGAL_REVIEW: 'Revisão jurídica pendente',
  NEEDS_EVIDENCE: 'Prova necessária',
  NEEDS_REVIEW: 'Revisão necessária',
  OUTSIDE_FIRST_SCOPE: 'Fora do recorte inicial',
  PENDING: 'Pendente',
  STALE: 'Exige rechecagem',
  REVIEW_REQUIRED: 'Revisão necessária',
  NOT_ADMITTED: 'Ainda não admitido',
  STALE_NOT_ADMITTED: 'Desatualizado e não admitido',
  SUPERSEDED_NOT_ADMITTED: 'Substituído e não admitido',
  CHECKED_INTERNAL_NOT_ADMITTED: 'Conferido internamente e não admitido',
  PENDING_INTERNAL_REVIEW_NOT_ADMITTED: 'Aguarda conferência interna e não está admitido',
  RETURNED_NOT_ADMITTED: 'Devolvido e não admitido',
  CANDIDATE_NOT_ADMITTED: 'Candidato e não admitido',
  PREPARATORY_NOT_ADMITTED: 'Preparatório e não admitido',
  LCMR_SCREEN_PASSED_PRELIMINARY_NOT_ADMITTED: 'Triagem preliminar passou e não está admitida',
  LCMR_SCREEN_FAILED_PRELIMINARY_NOT_ADMITTED: 'Triagem preliminar não passou e não está admitida',
  BINDING_MISSING: 'Vínculo ainda não preparado',
  FLEET_SELECTION_MISSING: 'Seleção de frota ainda não publicada',
  CANDIDATE_MISSING: 'Pacote ainda não preparado',
  EFEL_SOURCE_MISSING: 'Fator elétrico ainda não declarado',
  BLOCKED_MONITORING_REQUIREMENTS: 'Monitoramento ainda bloqueia esta seção',
  READY_FOR_INTERNAL_PD_REVIEW: 'Pronta para revisão interna do documento',
  INTERNAL_QA_ONLY: 'Somente QA interno',
  CANDIDATE: 'Candidato',
  QUARANTINED: 'Quarentena',
  CHECKED_INTERNAL: 'Conferido internamente',
  RETURNED: 'Devolvido para correção',
  ACCEPTED_FOR_QA: 'Aceito para QA interno',
  REJECTED: 'Rejeitado',
  DOCUMENT_CHECKED_INTERNAL: 'Conferido para análise normativa',
  CONFIRMED_BLOCKING: 'Impedimento confirmado',
  CLEARED_FOR_INTERNAL_QA: 'Liberado para QA interno',
  OPEN: 'Pendente',
  SUPPORTED_INTERNAL: 'Sustentado na conferência interna',
  METER_CALIBRATION: 'Calibração do medidor',
  SOURCE_RIGHTS: 'Direitos de utilização dos dados',
  SOURCE_OUTAGE: 'Indisponibilidade da fonte de dados',
  METER_MAINTENANCE: 'Manutenção do medidor',
  CLOCK_DRIFT: 'Divergência de relógio do medidor',
  DATA_GAP: 'Lacuna de medição',
  SOURCE: 'Origem dos dados',
  METER: 'Medidor',
  CHARGER: 'Carregador',
  ED: 'Energia entregue',
  EC: 'Energia consumida',
  DCFC: 'Recarga rápida',
  AC: 'Recarga em corrente alternada',
  SIN: 'Sistema Interligado Nacional',
  OTHER: 'Outra rede',
  GRID_ONLY: 'Somente rede elétrica',
  ON_SITE: 'Geração local',
  STORAGE: 'Armazenamento associado',
  V2G: 'Devolução à rede',
  UNKNOWN: 'A confirmar',
  DOWNSTREAM: 'A jusante',
  UPSTREAM: 'A montante',
  NOT_DECLARED: 'Ainda não informado',
  ON_CHARGER: 'No carregador',
  APP_OR_WEBSITE: 'Aplicativo ou site',
  TERMS_OF_SERVICE: 'Termos de uso',
  NO_OVERLAP_DECLARED: 'Sem sobreposição declarada',
  NO_DECLARATION: 'Declaração',
  CLOSED_NETWORK: 'Rede fechada',
  ACCESS_UNDETERMINED: 'Acesso à rede',
  CONFIGURATION_BRANCH: 'Ramo técnico',
  CONFIGURATION_PENDING: 'Configuração',
  OWNER_CONTRACT: 'Contrato do titular',
  DRIVER_DISCLOSURE: 'Divulgação aos motoristas',
  RIGHTS_DATE_GAP: 'Datas dos direitos',
  DOUBLE_CLAIMING: 'Duplo pleito',
  VMD0049_ROUTE: 'Lista positiva',
  MARKET_ROUTE: 'Rota de mercado',
  REGULATORY_SURPLUS: 'Excedente regulatório',
  ED_EC: 'Energia entregue e consumida',
  TRIAGE_CHANGED: 'Triagem alterada',
  CONTORNO_A_COMPROVAR: 'Contorno a comprovar',
  OPERACAO_PENDENTE: 'Operação pendente',
  ORIGEM_PENDENTE: 'Origem pendente',
  REVISAO_SEM_COBERTURA: 'Revisão sem cobertura',
  EC_NAO_SEGREGADA: 'Energia consumida não segregada',
  CALIBRACAO_SEM_COBERTURA: 'Calibração sem cobertura',
  LEITURA_SEM_SEGMENTACAO: 'Leitura sem segmentação',
  CSV_COLUMNS_INVALID: 'Colunas do arquivo',
  METER_CODE_INVALID: 'Código do medidor',
  SEQUENCE_INVALID: 'Sequência da leitura',
  KIND_INVALID: 'Tipo de energia',
  TIME_INTERVAL_INVALID: 'Intervalo de tempo',
  NON_HOURLY_INTERVAL: 'Intervalo que não é de uma hora',
  TIME_ZONE_INVALID: 'Fuso horário',
  ENERGY_MISSING: 'Energia ausente',
  ENERGY_DECIMAL_INVALID: 'Valor de energia',
  NEGATIVE_ENERGY: 'Energia negativa',
  ZERO_REQUIRES_EVIDENCE: 'Zero exige evidência',
  ENERGY_UNIT_INVALID: 'Unidade de energia',
  ENERGY_PRECISION_EXCEEDED: 'Precisão da energia',
  METER_NOT_REGISTERED: 'Medidor não cadastrado',
  METER_KIND_MISMATCH: 'Tipo do medidor',
  SOURCE_SYSTEM_MISMATCH: 'Sistema de origem',
  UNSUPPORTED_TOPOLOGY: 'Topologia não suportada',
  METER_ZONE_MISMATCH: 'Fuso do medidor',
  DUPLICATE_SOURCE_INTERVAL: 'Intervalo repetido',
  'OPEN-METHOD': 'Método',
  'OPEN-POSITIVE': 'Lista positiva',
  'OPEN-EV-PENETRATION': 'Penetração de elétricos',
  'OPEN-EV-SALES': 'Vendas de elétricos',
  'OPEN-RIGHTS': 'Direitos',
  'OPEN-ED-EC': 'Energia entregue e consumida',
};

/**
 * Short Portuguese label for a stored status, kind or finding.
 * Unknown codes stay visible: identifiers such as PEN-004 are not statuses.
 */
export function plainTerm(code: string): string {
  if (!code) return '';
  if (TERMS[code]) return TERMS[code];
  if (MONITORING_TITLES[code]) return MONITORING_TITLES[code];
  if (GATE_NAMES[code]) return GATE_NAMES[code];
  if (code.startsWith('PENDENCIA_')) return 'Pendência de evidência';
  return code;
}
