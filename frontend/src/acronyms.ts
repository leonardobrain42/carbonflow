import {Component, computed, input} from '@angular/core';
import {equationTerm} from './calculation-language';

/**
 * One list of the acronyms shown on the technical screens. Calculation codes take their name from the
 * same dictionaries the screens already use (equationTerm / calculationGateTitle), so a code never has
 * two names. Only the remaining acronyms carry their own expansion here, in the wording already used
 * by the triage and the technical screens. These are reading aids, not normative definitions.
 */
const OWN: Record<string, string> = {
  VM0038: 'Metodologia Verra para sistemas de recarga de veículos elétricos (edição usada: v1.1)',
  VCS: 'Verified Carbon Standard, o programa da Verra',
  OPEN: 'Rede aberta: frota sem identificação individual',
  BR: 'Brasil',
  LDV: 'Veículos leves',
  HDV: 'Veículos pesados',
  BEV: 'Veículo totalmente elétrico a bateria',
  PHEV: 'Veículo híbrido plug-in',
  DCFC: 'Recarga rápida em corrente contínua',
  AC: 'Recarga em corrente alternada (nível 1 ou 2)',
  SIN: 'Sistema Interligado Nacional brasileiro',
  V2G: 'Carregador que devolve energia à rede',
  QA: 'Controle de qualidade dos dados medidos',
  PP: 'Proponente do projeto',
  E8: 'Rótulo interno do CarbonFlow para o cálculo com dados admitidos',
  EFel: 'Fator de emissão da eletricidade',
  EFfuel: 'Fator de emissão do combustível fóssil',
};

/** Codes whose name comes from the calculation dictionaries. */
const FROM_CALCULATION = ['BE', 'PE', 'LE', 'ER', 'ED', 'EC', 'EVR', 'IR', 'DY', 'ERC', 'ERF', 'AFEC', 'MPG'];

export const ACRONYM_CODES: readonly string[] = [...FROM_CALCULATION, ...Object.keys(OWN)];

/** The plain expansion of an acronym, or null when the code is not a known acronym. */
export function acronym(code: string): string | null {
  if (OWN[code]) return OWN[code];
  if (!FROM_CALCULATION.includes(code)) return null;
  const term = equationTerm(code);
  return term !== code ? term : null;
}

/** Display label: Dy is written in mixed case on every screen. */
export function acronymLabel(code: string): string {
  return code === 'DY' ? 'Dy' : code;
}

/** An acronym with its expansion for pointer and screen-reader users; unknown codes show as written. */
@Component({
  selector: 'carbonflow-abbr',
  template: `@if (expansion(); as text) {<abbr [attr.title]="text">{{ label() }}</abbr><span class="cf-sr-only"> ({{ text }})</span>} @else { {{ code() }} }`,
})
export class AbbrComponent {
  readonly code = input.required<string>();
  readonly label = computed(() => acronymLabel(this.code()));
  readonly expansion = computed(() => acronym(this.code()));
}
