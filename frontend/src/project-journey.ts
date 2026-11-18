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
}

export const OTHER_METHODOLOGY = '__OTHER__';

export function methodologyOption(value: string, options: JourneyOption[],
                                  profile: JourneyProfile | null | undefined): string {
  if (!value) return '';
  if (options.some(option => option.value === value)) return value;
  const known = profile?.candidate;
  return known && [known, known + ' V' + profile?.edition].includes(value.toUpperCase()) ? known : OTHER_METHODOLOGY;
}
