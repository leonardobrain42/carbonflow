export interface JourneyOption {
  value: string;
  label: string;
}

export interface JourneyQuestion {
  key: string;
  label: string;
  type: 'choice' | 'number' | 'text';
  help: string | null;
  options: JourneyOption[];
}

export interface JourneyNextSection {
  section: string;
  title: string;
  detail: string;
}

/** Texts and the technical sections announced by the package; the screen holds no per-methodology branch. */
export interface JourneyPresentation {
  activityPlaceholder: string;
  baselinePlaceholder: string;
  next: JourneyNextSection[];
}

export interface JourneyProfile {
  id: string;
  candidate: string | null;
  edition: string | null;
  title: string;
  activitiesTitle: string;
  detailsTitle: string;
  description: string;
  sourceUrl: string | null;
  technicalTopics: string[];
  questions: JourneyQuestion[];
  /** Questions the package adds to the activity step. */
  activityQuestions?: JourneyQuestion[];
  activities?: string[];
  presentation?: JourneyPresentation;
}

export const OTHER_METHODOLOGY = '__OTHER__';

export function methodologyOption(value: string, options: JourneyOption[],
                                  profile: JourneyProfile | null | undefined): string {
  if (!value) return '';
  if (options.some(option => option.value === value)) return value;
  const known = profile?.candidate;
  return known && [known, known + ' V' + profile?.edition].includes(value.toUpperCase()) ? known : OTHER_METHODOLOGY;
}
