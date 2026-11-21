import {afterNextRender, Component, ElementRef, EventEmitter, Input, Injector, Output, inject, signal, viewChild} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {RouterLink} from '@angular/router';
import {firstValueFrom} from 'rxjs';

type Mode = 'login'|'register'|'verify'|'recover'|'reset';

/**
 * Account onboarding is a UI over the existing /v1/accounts and /v1/account-session APIs.
 * Tokens and passwords stay in form controls only, never URL/query/localStorage.
 * The server alone issues sessions and enforces limits and permissions.
 */
@Component({
  selector: 'carbonflow-account-entry',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './account-entry.html',
})
export class AccountEntryComponent {
  @Input() createIntent = false;
  @Output() authenticated = new EventEmitter<void>();
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  private readonly heading = viewChild<ElementRef<HTMLHeadingElement>>('modeHeading');
  readonly mode = signal<Mode>('login');
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  email = '';
  private readonly retryKeys = new Map<string,string>();

  change(mode:Mode) {
    if(this.busy())return;
    this.retryKeys.clear();
    this.error.set('');
    this.notice.set('');
    this.mode.set(mode);
    this.focusHeading();
  }

  private focusHeading() {
    afterNextRender(() => this.heading()?.nativeElement.focus(), {injector: this.injector});
  }

  private key(action:string):string {
    const existing=this.retryKeys.get(action);
    if(existing)return existing;
    const next=crypto.randomUUID();
    this.retryKeys.set(action,next);
    return next;
  }

  private async execute(action:string, task:(key:string)=>Promise<unknown>, onSuccess:()=>void) {
    if(this.busy())return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      await task(this.key(action));
      this.retryKeys.delete(action);
      onSuccess();
    } catch(reason:unknown) {
      if(reason instanceof HttpErrorResponse && reason.status>=400 && reason.status<500)
        this.retryKeys.delete(action);
      this.error.set(this.message(reason));
    } finally {
      this.busy.set(false);
    }
  }

  async login(event:Event, rawEmail:string, password:string) {
    event.preventDefault();
    const email=rawEmail.trim().toLowerCase();
    if(!email||!password)return;
    this.email=email;
    await this.execute('login',async()=> {
      await firstValueFrom(this.http.post('/v1/account-session',{email,password}));
      (event.target as HTMLFormElement).reset();
    },()=>this.authenticated.emit());
  }

  async register(event:Event, rawName:string, rawEmail:string) {
    event.preventDefault();
    const email=rawEmail.trim().toLowerCase();
    const name=rawName.trim();
    if(!email || !name || name.length>120)return;
    this.email=email;
    await this.execute('register',key=>firstValueFrom(this.http.post('/v1/accounts',
      {name,email},{headers:{'Idempotency-Key':key}})),()=>{
      this.mode.set('verify');
      this.focusHeading();
      this.notice.set('Se o cadastro puder ser iniciado, o código estará na caixa de testes configurada pelo operador. Nenhuma mensagem foi enviada por e-mail real.');
    });
  }

  async resendVerification() {
    if(!this.email)return;
    await this.execute('verify-resend',key=>firstValueFrom(this.http.post(
      '/v1/accounts/email-verification',{email:this.email},
      {headers:{'Idempotency-Key':key}})),()=>{
      this.notice.set('Se houver cadastro pendente, um novo código ficará disponível na caixa de testes local. O código anterior poderá expirar.');
    });
  }

  async requestRecovery(event:Event,rawEmail:string) {
    event.preventDefault();
    this.email=rawEmail.trim().toLowerCase();
    if(!this.email)return;
    await this.execute('recover',key=>firstValueFrom(this.http.post(
      '/v1/accounts/password-recovery',{email:this.email},
      {headers:{'Idempotency-Key':key}})),()=>{
      this.mode.set('reset');
      this.focusHeading();
      this.notice.set('Se a conta estiver ativa, um código de recuperação estará na caixa local de testes. Não há envio real de e-mail.');
    });
  }

  async complete(event:Event, token:string, password:string, confirmation:string) {
    event.preventDefault();
    if(this.busy())return;
    if(password.length<12||password.length>128){
      this.error.set('A senha deve conter de 12 a 128 caracteres.');
      return;
    }
    if(password!==confirmation){
      this.error.set('As senhas não coincidem.');
      return;
    }
    const verify=this.mode()==='verify';
    const url=verify?'/v1/accounts/email-confirmations':'/v1/accounts/password-resets';
    await this.execute(verify?'confirm':'reset',key=>firstValueFrom(this.http.post(
      url,{token:token.trim(),password},{headers:{'Idempotency-Key':key}})),()=>{
      (event.target as HTMLFormElement).reset();
      this.mode.set('login');
      this.focusHeading();
      this.notice.set(verify?'Conta confirmada. Entre para continuar seu projeto.':'Senha redefinida. Entre novamente para continuar.');
    });
  }

  private message(reason:unknown) {
    if(reason instanceof HttpErrorResponse){
      if(reason.status===404||reason.status===503)
        return 'Cadastro e recuperação indisponíveis neste ambiente. A caixa local de testes precisa estar habilitada pelo operador; não existe envio de e-mail real integrado.';
      if(reason.status===429)return 'Muitas tentativas. Aguarde antes de solicitar outro código ou entrar.';
      if(reason.status===422)return 'Código inválido, expirado ou já utilizado. Solicite um novo código e tente novamente.';
      if(reason.status===409)return reason.error?.code==='CAPACIDADE_DE_SESSOES'
        ? 'Limite de sessões do sandbox atingido. Encerre outra sessão antes de tentar novamente.'
        : 'A solicitação mudou ou já foi processada. Confira o estado e tente novamente.';
      if(reason.status===401)return 'Não foi possível entrar com essas credenciais.';
      if(reason.status===400)return 'Confira os campos informados e tente novamente.';
    }
    return 'Não foi possível concluir a solicitação. Confira a conexão e tente novamente.';
  }
}
