import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';

async function browser(run){
  const instance=await chromium.launch({executablePath});
  try{
    const page=await instance.newPage({viewport:{width:1440,height:900}});
    await run(page);
  }finally{await instance.close();}
}

test('ENTRY-T01: raiz distingue projeto privado de simulação e abre cadastro sem terminal',async()=>{
  await browser(async page=>{
    await page.route('**/v1/account-session',route=>route.fulfill({status:401,json:{code:'SESSAO_NECESSARIA'}}));
    await page.goto(base+'/');
    await page.getByRole('heading',{name:'Comece um projeto de carbono com orientação técnica'}).waitFor();
    await page.getByRole('link',{name:'Explorar piloto sintético'}).first().waitFor();
    await page.getByRole('link',{name:'Iniciar meu projeto'}).click();
    await page.getByRole('heading',{name:'Entre na sua conta'}).waitFor();
    assert.ok(page.url().endsWith('/workspace/projects/new'));
    await page.getByRole('button',{name:'Criar conta'}).click();
    await page.getByRole('heading',{name:'Criar conta de teste'}).waitFor();
    assert.equal(await page.getByText('créditos emitidos',{exact:false}).count(),0);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  });
});

test('ENTRY-T02: cadastro, confirmação, reenvio e login usam endpoints reais e não expõem token em URL',async()=>{
  await browser(async page=>{
    const requests=[];
    let signedIn=false;
    await page.route('**/v1/account-session',route=>{
      if(route.request().method()==='GET')
        return route.fulfill(signedIn?{status:200,json:{identity:{id:'id1',email:'conta@example.test',name:'Conta'},projects:[]}}:{status:401,json:{}});
      if(route.request().method()==='POST'){signedIn=true;return route.fulfill({status:204});}
      return route.fulfill({status:204});
    });
    await page.route('**/v1/organizations',route=>route.fulfill({status:200,json:[]}));
    await page.route('**/v1/accounts**',route=>{
      if(!route.request().headers()['idempotency-key'])throw new Error('Chave idempotente ausente');
      requests.push({url:route.request().url(),body:JSON.parse(route.request().postData())});
      return route.fulfill({status:route.request().url().endsWith('/email-confirmations')?204:202});
    });
    await page.goto(base+'/workspace/projects/new');
    await page.getByRole('button',{name:'Criar conta'}).click();
    await page.getByLabel('Seu nome').fill('Conta Nova');
    await page.getByLabel('E-mail da conta').fill('conta@example.test');
    await page.getByRole('button',{name:'Solicitar cadastro'}).click();
    await page.getByRole('heading',{name:'Confirmar cadastro'}).waitFor();
    await page.getByRole('button',{name:'Solicitar novo código'}).click();
    await page.getByText('um novo código ficará disponível',{exact:false}).waitFor();
    await page.getByLabel('Código de uso único').fill('token-somente-para-teste');
    await page.getByLabel('Nova senha (12 a 128 caracteres)').fill('senha-de-teste-muito-forte');
    await page.getByLabel('Repita a nova senha').fill('senha-de-teste-muito-forte');
    await page.getByRole('button',{name:'Confirmar conta'}).click();
    await page.getByRole('heading',{name:'Entre na sua conta'}).waitFor();
    assert.equal(requests.length,3);
    assert.equal(requests[0].body.name,'Conta Nova');
    assert.equal(requests[1].body.email,'conta@example.test');
    assert.equal(requests[2].body.token,'token-somente-para-teste');
    assert.equal(page.url().includes('token-somente'),false);
    assert.equal(await page.evaluate(()=>localStorage.length),0);
    await page.getByLabel('E-mail da conta').fill('conta@example.test');
    await page.getByLabel('Senha',{exact:true}).fill('senha-de-teste-muito-forte');
    await page.getByRole('button',{name:'Entrar na conta'}).click();
    await page.getByRole('heading',{name:'Crie o espaço da organização'}).waitFor();
    assert.ok(page.url().endsWith('/workspace/projects/new'));
    assert.equal(await page.getByText('DRAFT_OWNER',{exact:false}).count()>0,true);
  });
});

test('ENTRY-T02: sandbox não configurado e token expirado geram feedback sem vazar conta',async()=>{
  await browser(async page=>{
    await page.route('**/v1/account-session',route=>route.fulfill({status:401,json:{}}));
    await page.route('**/v1/accounts',route=>route.fulfill({status:404,json:{code:'RECURSO_NAO_VISIVEL'}}));
    await page.route('**/v1/accounts/password-recovery',route=>route.fulfill({status:202}));
    await page.route('**/v1/accounts/password-resets',route=>route.fulfill({status:422,json:{code:'DESAFIO_INDISPONIVEL'}}));
    await page.goto(base+'/workspace');
    await page.getByRole('button',{name:'Criar conta'}).click();
    await page.getByLabel('Seu nome').fill('Teste');
    await page.getByLabel('E-mail da conta').fill('pessoa@example.test');
    await page.getByRole('button',{name:'Solicitar cadastro'}).click();
    await page.getByRole('alert').getByText('Cadastro e recuperação indisponíveis',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Voltar para entrar'}).click();
    await page.getByRole('button',{name:'Esqueci minha senha'}).click();
    await page.getByLabel('E-mail da conta').fill('pessoa@example.test');
    await page.getByRole('button',{name:'Solicitar recuperação'}).click();
    await page.getByRole('heading',{name:'Definir nova senha'}).waitFor();
    await page.getByLabel('Código de uso único').fill('expirado');
    await page.getByLabel('Nova senha (12 a 128 caracteres)').fill('senha-de-teste-muito-forte');
    await page.getByLabel('Repita a nova senha').fill('senha-de-teste-muito-forte');
    await page.getByRole('button',{name:'Redefinir senha'}).click();
    await page.getByRole('alert').getByText('Código inválido, expirado',{exact:false}).waitFor();
    assert.equal(await page.getByText('Conta encontrada',{exact:false}).count(),0);
  });
});
