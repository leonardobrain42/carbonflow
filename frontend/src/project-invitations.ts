export const INVITATION_STATUS = {
  SENT: 'Enviado · aguardando aceite',
  ACCEPTED: 'Acesso ativo',
  REVOKED: 'Vínculo revogado',
  EXPIRED: 'Prazo encerrado',
} as const;

export interface ProjectInvitation {
  id: string;
  projectId: string;
  projectTitle: string;
  planId: string;
  planVersion: number;
  recipient: string;
  role: 'PREP' | 'REV';
  activities: string[];
  state: keyof typeof INVITATION_STATUS;
  acceptUntil: string;
  accessUntil: string;
}
