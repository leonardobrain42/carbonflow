import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const page_url = base + '/workspace/platform/licenses';
const HASH = 'ab'.repeat(32);

async function setup(page, {listStatus = 200, decisionStatus = 200, decisionCode = null} = {}) {
  const state = {verified: false, items: [], submits: [], decisions: [], revokes: []};
  await page.route('**/v1/compliance/session', async route => {
    if (route.request().method() === 'POST') state.verified = true;
    await route.fulfill({json:{verified:state.verified,expiresAt:state.verified ? new Date(Date.now()+60000).toISOString() : null}});
  });
  await page.route('**/v1/platform/source-licenses', async route => {
    const request = route.request();
    if (listStatus !== 200) return route.fulfill({status:listStatus,json:{message:'x'}});
    if (request.method() === 'GET') return route.fulfill({json:state.items});
    const body = request.postDataJSON();
    state.submits.push(body);
    state.items = [{id:'11111111-1111-4111-8111-111111111111',sourceId:body.sourceId,edition:body.edition,purpose:body.purpose,
      authorizationRef:body.authorizationRef,proofSha256:body.proofSha256,startsAt:body.startsAt,endsAt:body.endsAt,
      submittedBy:'x',decisionVersion:0,state:'PENDING',decidedBy:null,decidedAt:null,reason:null,operations:[],limits:'l'}];
    return route.fulfill({status:201,json:state.items[0]});
  });
  await page.route('**/v1/platform/source-licenses/*/decision', async route => {
    const request = route.request();
    state.decisions.push({ifMatch:request.headers()['if-match'],body:request.postDataJSON()});
    if (decisionStatus !== 200) return route.fulfill({status:decisionStatus,json:{code:decisionCode,message:'x'}});
    const body = request.postDataJSON();
    state.items = [{...state.items[0],decisionVersion:1,state:body.outcome,reason:body.reason,operations:body.operations,decidedBy:'y'}];
    return route.fulfill({json:state.items[0]});
  });
  await page.route('**/v1/platform/source-licenses/*/revoke', async route => {
    const request = route.request();
    state.revokes.push({ifMatch:request.headers()['if-match'],body:request.postDataJSON()});
    state.items = [{...state.items[0],decisionVersion:2,state:'REVOKED',reason:request.postDataJSON().reason,operations:[]}];
    return route.fulfill({json:state.items[0]});
  });
  return state;
}

async function unlock(page) {
  await page.goto(page_url);
  await page.getByRole('heading',{name:'Verificação da equipe interna'}).waitFor();
  await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
  await page.getByRole('button',{name:'Desbloquear licenças'}).click();
  await page.getByRole('heading',{name:'Autorizações registradas'}).waitFor();
}

test('CF-DATA-001: MFA, registro, aprovação com operações, revogação e avisos de limite', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    const state = await setup(page);
    await page.goto(page_url);
    await page.getByRole('heading',{name:'Verificação da equipe interna'}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Registrar autorização'}).count(),0);
    await unlock(page);
    await page.getByText('Nenhuma autorização registrada.').waitFor();

    await page.getByLabel('Fonte (código já registrado)').fill('VM0038');
    await page.getByLabel('Edição',{exact:true}).fill('1.1');
    await page.getByLabel('Referência da autorização (contrato ou termos)').fill('Contrato interno de teste');
    await page.getByLabel('SHA-256 do comprovante (64 caracteres hexadecimais)').fill(HASH.toUpperCase());
    await page.getByLabel('Início da vigência').fill('2026-01-01');
    await page.getByRole('button',{name:'Registrar autorização'}).click();
    await page.getByText('aguardando decisão de outra pessoa',{exact:false}).first().waitFor();
    assert.equal(state.submits[0].proofSha256,HASH);
    assert.equal(state.submits[0].startsAt,'2026-01-01T00:00:00Z');
    assert.equal(state.submits[0].endsAt,null);
    await page.getByText('Aguardando decisão',{exact:true}).waitFor();
    await page.getByText('declarado, não verificado',{exact:false}).waitFor();

    // Approve needs at least one operation, and sends the licence version in If-Match.
    await page.getByRole('button',{name:'Decidir licença de VM0038 1.1'}).click();
    const approve = page.getByRole('button',{name:'Aprovar operações marcadas'});
    assert.equal(await approve.isDisabled(),true);
    await page.getByLabel('Referenciar requisitos').check();
    await page.getByLabel('Extrair texto e dados').check();
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('Contrato conferido pelo responsável');
    await approve.click();
    await page.getByRole('status').getByText('Licença aprovada para as operações marcadas.').waitFor();
    assert.equal(state.decisions[0].ifMatch,'"0"');
    assert.deepEqual(state.decisions[0].body.operations.sort(),['EXTRACT_TEXT','REFERENCE_REQUIREMENT']);
    await page.getByText('Aprovada',{exact:true}).waitFor();
    await page.getByText('Extrair texto e dados').waitFor();
    // The screen states the limit: an approved licence enables no calculation, admission or external act.
    await page.getByText('não admite dados e não habilita cálculo nem atos externos',{exact:false}).waitFor();

    // Revoke: confirmation with a reason; the new version goes in If-Match.
    await page.getByRole('button',{name:'Revogar licença de VM0038 1.1'}).click();
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('Autorização encerrada pelo titular');
    await page.getByRole('button',{name:'Confirmar revogação'}).click();
    await page.getByRole('status').getByText('nenhuma operação segue autorizada',{exact:false}).waitFor();
    assert.equal(state.revokes[0].ifMatch,'"1"');
    await page.getByText('Revogada',{exact:true}).waitFor();
    assert.equal(await page.getByText('Operações autorizadas:').count(),0);
    assert.equal(await page.getByRole('button',{name:/Revogar licença/}).count(),0);
  } finally {
    await browser.close();
  }
});

test('CF-DATA-001: dupla custódia explicada e identidade sem privilégio não vê o formulário', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    const state = await setup(page, {decisionStatus:403, decisionCode:'DUPLA_CUSTODIA'});
    await unlock(page);
    await page.getByLabel('Fonte (código já registrado)').fill('VM0038');
    await page.getByLabel('Edição',{exact:true}).fill('1.1');
    await page.getByLabel('Referência da autorização (contrato ou termos)').fill('Contrato interno de teste');
    await page.getByLabel('SHA-256 do comprovante (64 caracteres hexadecimais)').fill(HASH);
    await page.getByLabel('Início da vigência').fill('2026-01-01');
    await page.getByRole('button',{name:'Registrar autorização'}).click();
    await page.getByRole('button',{name:'Decidir licença de VM0038 1.1'}).click();
    await page.getByLabel('Referenciar requisitos').check();
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('Tentativa da mesma pessoa');
    await page.getByRole('button',{name:'Aprovar operações marcadas'}).click();
    await page.getByRole('alert').getByText('Quem submeteu a autorização não pode aprová-la',{exact:false}).waitFor();
    assert.equal(state.decisions.length,1);
    await page.getByText('Aguardando decisão',{exact:true}).waitFor();

    const other = await browser.newPage({viewport:{width:1280,height:900}});
    await setup(other, {listStatus:404});
    await other.goto(page_url);
    await other.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await other.getByRole('button',{name:'Desbloquear licenças'}).click();
    await other.getByRole('alert').getByText('exclusiva da equipe interna autorizada',{exact:false}).waitFor();
    assert.equal(await other.getByRole('button',{name:'Registrar autorização'}).count(),0);
  } finally {
    await browser.close();
  }
});
