import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const page_url = base + '/workspace/platform/alias-candidates';
const SHA = 'cd'.repeat(32);

function seeded() {
  const common = {observationPeriod:'2026-09',pbevSourceSha256:SHA,senatranSourceSha256:'ab'.repeat(32),importedBy:'x',decidedBy:null,
    selectedPbevLine:null,decisionReason:null,decisionBasis:null,limits:'l',state:'PENDING'};
  const nameOnly = {code:'NAME_MATCH_ONLY',text:'A correspondência é só de nome.'};
  const modelRisks = [nameOnly,
    {code:'HOMONYM',text:'Risco de homônimo: o mesmo modelo pode existir com propulsão a combustão.'},
    {code:'DISTINCT_PROPULSION',text:'A fonte de frota não informa propulsão.'},
    {code:'VERSION_UNCONFIRMED',text:'A versão do nome da Senatran não consta do PBEV.'}];
  return [
    {...common,id:'11111111-1111-4111-8111-111111111111',literalName:'I/BYD DOLPHIN MINI GS5EV',recordKey:'I_BYD_DOLPHIN_MINI_GS5EV',
      level:'EXACT',risks:[nameOnly],reason:'Marca, modelo e versão do PBEV igualam o nome.',
      matches:[{brand:'BYD',model:'DOLPHIN MINI',version:'GS 5 EV',category:'Sub Compacto',pbevLine:62}]},
    {...common,id:'22222222-2222-4222-8222-222222222222',literalName:'I/RENAULT E-KWID TECHNO',recordKey:'I_RENAULT_E-KWID_TECHNO',
      level:'MODEL_ONLY',risks:modelRisks,reason:'Só marca e modelo coincidem.',
      matches:[{brand:'RENAULT',model:'E-KWID',version:'INTENS',category:'Sub Compacto',pbevLine:70},
               {brand:'RENAULT',model:'E-KWID',version:'CARGO',category:'Comercial',pbevLine:71}]},
  ];
}

async function setup(page, {listStatus = 200, decisionStatus = 200, decisionCode = null} = {}) {
  const state = {verified:false, items:seeded(), decisions:[], imports:[]};
  await page.route('**/v1/compliance/session', async route => {
    if (route.request().method() === 'POST') state.verified = true;
    await route.fulfill({json:{verified:state.verified,expiresAt:state.verified ? new Date(Date.now()+60000).toISOString() : null}});
  });
  await page.route('**/v1/platform/fleet/alias-candidates**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith('/decision')) {
      const id = url.pathname.split('/').at(-2);
      state.decisions.push({id, body:request.postDataJSON()});
      if (decisionStatus !== 200) return route.fulfill({status:decisionStatus,json:{code:decisionCode,message:'x'}});
      const body = request.postDataJSON();
      state.items = state.items.map(item => item.id !== id ? item : {...item,state:body.outcome,
        selectedPbevLine:body.selectedPbevLine,decisionReason:body.reason,decisionBasis:body.basis,decidedBy:'y'});
      return route.fulfill({json:state.items.find(item => item.id === id)});
    }
    if (listStatus !== 200) return route.fulfill({status:listStatus,json:{message:'x'}});
    if (url.pathname.endsWith('/dry-run')) {
      state.dryRuns = (state.dryRuns ?? 0) + 1;
      return route.fulfill({json:{importable:false,rightsGateEnabled:true,newItems:2,alreadyPresent:1,
        rights:[{role:'PBEV',registered:true},{role:'SENATRAN',registered:false}]}});
    }
    if (request.method() === 'POST') {
      state.imports.push(request.postDataJSON());
      return route.fulfill({json:{created:2,alreadyPresent:1}});
    }
    state.lastQuery = url.search;
    const wanted = url.searchParams.get('state');
    return route.fulfill({json:state.items.filter(item => !wanted || item.state === wanted)});
  });
  return state;
}

async function unlock(page) {
  await page.goto(page_url);
  await page.getByRole('heading',{name:'Verificação da equipe interna'}).waitFor();
  await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
  await page.getByRole('button',{name:'Desbloquear fila'}).click();
  await page.getByRole('heading',{name:'Candidatos',exact:true}).waitFor();
}

test('Fila de candidatos: MFA, evidência, aceitar com linha escolhida, recusar e avisos de limite', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    const state = await setup(page);
    await page.goto(page_url);
    await page.getByRole('heading',{name:'Verificação da equipe interna'}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Importar lote'}).count(),0);
    await unlock(page);
    assert.equal(state.lastQuery,'?state=PENDING');
    await page.getByText('I/BYD DOLPHIN MINI GS5EV',{exact:true}).waitFor();
    await page.getByText('Nome idêntico ao do PBEV').waitFor();
    await page.getByText('Só o modelo coincide; a versão fica a decidir').waitFor();
    await page.getByText('não cria modelo nem alias',{exact:false}).first().waitFor();

    // A single-line candidate preselects its only line; accepting sends that line and does not claim an alias.
    await page.getByRole('button',{name:'Revisar candidato I/BYD DOLPHIN MINI GS5EV'}).click();
    assert.equal(await page.getByLabel('Revisão independente',{exact:false}).isChecked(),true);
    await page.getByLabel('Decisão interna do responsável',{exact:false}).check();
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('Nome e versão conferidos no PBEV');
    await page.getByRole('button',{name:'Aceitar candidato'}).click();
    await page.getByRole('status').getByText('Nenhum modelo nem alias foi criado',{exact:false}).waitFor();
    assert.equal(state.decisions[0].body.outcome,'ACCEPTED');
    assert.equal(state.decisions[0].body.selectedPbevLine,62);
    assert.equal(state.decisions[0].body.basis,'OWNER_INTERNAL');

    // MODEL_ONLY shows its risks up front. Accepting is blocked until a line is chosen AND every risk is acknowledged;
    // rejecting needs neither.
    await page.getByRole('region',{name:'Riscos do casamento de I/RENAULT E-KWID TECHNO'}).getByText('Risco de homônimo',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Revisar candidato I/RENAULT E-KWID TECHNO'}).click();
    const accept = page.getByRole('button',{name:'Aceitar candidato'});
    assert.equal(await accept.isDisabled(),true);
    await page.getByLabel('Linha 70',{exact:false}).check();
    assert.equal(await accept.isDisabled(),true); // line chosen, risks still not acknowledged
    for (const box of await page.getByLabel('Reconheço:',{exact:false}).all()) await box.check();
    await page.locator('button:enabled',{hasText:'Aceitar candidato'}).waitFor(); // line chosen and every risk acknowledged
    await page.getByLabel('Reconheço:',{exact:false}).first().uncheck();
    await page.locator('button:disabled',{hasText:'Aceitar candidato'}).waitFor();
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('A versão Techno não existe no PBEV');
    await page.getByRole('button',{name:'Recusar'}).click();
    await page.getByRole('status').getByText('Candidato recusado.').waitFor();
    assert.equal(state.decisions[1].body.outcome,'REJECTED');
    assert.equal(state.decisions[1].body.selectedPbevLine,null);
    assert.equal(state.decisions[1].body.basis,'INDEPENDENT'); // the default basis never claims to be the owner's
    assert.equal(state.decisions[0].body.acknowledgedRisks.length,0); // EXACT does not demand acknowledgements

    // The filter shows decided candidates with the chosen line and the reason.
    await page.getByLabel('Situação').selectOption('ACCEPTED');
    await page.getByText('Linha escolhida: 62',{exact:false}).waitFor();
    await page.getByText('Base: Decisão interna do responsável (não é revisão independente)',{exact:false}).waitFor();
    await page.getByText('Aceito',{exact:true}).waitFor();
    assert.equal(await page.getByRole('button',{name:/Revisar candidato/}).count(),0);
  } finally {
    await browser.close();
  }
});

test('Fila de candidatos: importar lote, dupla custódia explicada e perfil sem privilégio', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    const state = await setup(page, {decisionStatus:403, decisionCode:'DUPLA_CUSTODIA'});
    await unlock(page);
    await page.getByLabel('Lote em JSON',{exact:false}).fill('isto não é json');
    await page.getByRole('button',{name:'Importar lote'}).click();
    await page.getByRole('alert').getByText('não é um JSON válido',{exact:false}).waitFor();
    assert.equal(state.imports.length,0);
    await page.getByLabel('Lote em JSON',{exact:false}).fill(JSON.stringify({observationPeriod:'2026-09',pbevSourceSha256:SHA,items:[]}));
    // A 422 shows the server's specific message (which field failed), not the generic list.
    await page.route('**/v1/platform/fleet/alias-candidates/dry-run', route => route.fulfill({status:422,
      json:{code:'CANDIDATO_INVALIDO',message:'Falta o SHA-256 da Senatran (senatranSourceSha256): 64 caracteres hexadecimais minúsculos.'}}));
    await page.getByRole('button',{name:'Simular importação'}).click();
    await page.getByRole('alert').getByText('Falta o SHA-256 da Senatran',{exact:false}).waitFor();
    await page.unroute('**/v1/platform/fleet/alias-candidates/dry-run');
    // The dry run writes nothing and says which source still lacks independently approved rights.
    await page.getByRole('button',{name:'Simular importação'}).click();
    await page.getByRole('status').getByText('Importação bloqueada: direitos sem aprovação independente vigente',{exact:false}).waitFor();
    await page.getByRole('status').getByText('SENATRAN',{exact:false}).waitFor();
    assert.equal(state.dryRuns,1);
    assert.equal(state.imports.length,0);
    await page.getByRole('button',{name:'Importar lote'}).click();
    await page.getByRole('status').getByText('2 novo(s) candidato(s), 1 já existente(s)',{exact:false}).waitFor();
    assert.equal(state.imports[0].observationPeriod,'2026-09');

    await page.getByRole('button',{name:'Revisar candidato I/BYD DOLPHIN MINI GS5EV'}).click();
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('Tentativa de quem importou o lote');
    await page.getByRole('button',{name:'Aceitar candidato'}).click();
    await page.getByRole('alert').getByText('Quem importou o candidato não pode decidir como revisor independente',{exact:false}).waitFor();
    await page.getByText('Aguardando revisão').first().waitFor();

    const other = await browser.newPage({viewport:{width:1280,height:900}});
    await setup(other, {listStatus:404});
    await other.goto(page_url);
    await other.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await other.getByRole('button',{name:'Desbloquear fila'}).click();
    await other.getByRole('alert').getByText('exclusiva da equipe interna autorizada',{exact:false}).waitFor();
    assert.equal(await other.getByRole('button',{name:'Importar lote'}).count(),0);
  } finally {
    await browser.close();
  }
});
