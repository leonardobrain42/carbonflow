import {Component, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom, Observable} from 'rxjs';

const POWERS = {
  REPRESENT_PROJECT: 'Representar a organização neste projeto',
  SEND_INVITATIONS: 'Enviar convites para este projeto',
} as const;
type Power = keyof typeof POWERS;

interface Input {
  power: Power;
  representationBasis: string;
  evidenceReference: string;
}

interface AuthorityRequest {
  id: string;
  version: number;
  state: 'SUBMITTED' | 'WITHDRAWN';
  details: Input;
}

interface View {
  projectId: string;
  environment: string;
  requests: AuthorityRequest[];
}

@Component({
  selector: 'carbonflow-project-authority-requests',
  imports: [RouterLink],
  templateUrl: './project-authority-requests.html',
})
export class ProjectAuthorityRequestsComponent {
  private readonly http = inject(HttpClient);
  readonly projectId = inject(ActivatedRoute).snapshot.paramMap.get('projectId') ?? '';
  private readonly url = '/v1/projects/' + encodeURIComponent(this.projectId) + '/authority-requests';
  readonly powerLabel = POWERS;
  readonly powers = Object.keys(POWERS) as Power[];
  readonly view = signal<View | null>(null);
  readonly draft = signal<Input | null>(null);
  readonly withdrawing = signal<AuthorityRequest | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  private requestId = '';

  constructor() {
    void this.run(this.http.get<View>(this.url), '', () => {});
  }

  start() {
    this.requestId = crypto.randomUUID();
    this.error.set('');
    this.notice.set('');
    this.draft.set({power:'REPRESENT_PROJECT', representationBasis:'', evidenceReference:''});
  }

  cancel() {
    this.draft.set(null);
    this.withdrawing.set(null);
    this.error.set('');
  }

  update(input: Partial<Input>) {
    this.draft.update(previous => previous ? {...previous, ...input} : null);
  }

  submit(event: Event) {
    event.preventDefault();
    const input = this.draft();
    if (!input || this.busy()) return;
    void this.run(this.http.put<View>(this.url + '/' + this.requestId, input),
      'Solicitação registrada no sandbox.', () => this.draft.set(null));
  }

  withdraw() {
    const request = this.withdrawing();
    if (!request || this.busy()) return;
    const headers = {'If-Match':'"' + request.version + '"'};
    void this.run(this.http.post<View>(this.url + '/' + request.id + '/withdraw', null, {headers}),
      'Solicitação retirada. O histórico foi preservado.', () => this.withdrawing.set(null));
  }

  private async run(operation: Observable<View>, notice: string, finish: () => void) {
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const result = await firstValueFrom(operation);
      if (result.projectId !== this.projectId || result.environment !== 'SANDBOX') throw new Error('Projeto incorreto');
      this.view.set(result);
      finish();
      this.notice.set(notice);
    } catch (cause) {
      const code = cause instanceof HttpErrorResponse ? cause.error?.code : null;
      this.error.set(code === 'SOLICITACAO_EM_ABERTO' ? 'Já existe uma solicitação em aberto para esse poder.'
        : code === 'VERSAO_OBSOLETA' ? 'A solicitação mudou. Recarregue a página para consultar a versão atual.'
        : 'Não foi possível concluir a operação. Confira os dados e tente novamente.');
    } finally {
      this.busy.set(false);
    }
  }
}
