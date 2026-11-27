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

test('D19 estendida: licença pública declarada pelo responsável não aparece como aprovada', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    const state = await setup(page);
    const posts = [];
    await page.route('**/v1/platform/source-licenses/public', async route => {
      const body = route.request().postDataJSON();
      posts.push(body);
      if (body.operations.some(o => o.operation === 'EXPORT_DERIVATIVES'))
        return route.fulfill({status:422,json:{code:'OPERACAO_FORA_DA_LICENCA',message:'x'}});
      state.items = [{id:'22222222-2222-4222-8222-222222222222',sourceId:body.sourceId,edition:body.edition,purpose:body.purpose,
        authorizationRef:'Licença pública CC-BY-ND-3.0',proofSha256:body.evidenceSha256,startsAt:body.startsAt,endsAt:null,
        submittedBy:'x',decisionVersion:1,state:'DECLARED_INTERNAL',decidedBy:'x',decidedAt:null,reason:body.reason,
        operations:body.operations.map(o => o.operation),limits:'l',kind:'PUBLIC_LICENSE',licenseIdentifier:'CC-BY-ND-3.0',
        startsAtBasis:body.startsAtBasis,decisionBasis:'OWNER_INTERNAL'}];
      return route.fulfill({status:201,json:state.items[0]});
    });
    await unlock(page);
    await page.getByLabel('Código da fonte licenciada').fill('MCTI-SIN-DESPACHO');
    await page.getByLabel('Versão publicada da fonte').fill('2026-janout-7f8e2292');
    await page.getByLabel('Endereço da página com o aviso de licença (https)').fill('https://www.gov.br/mcti/pagina');
    await page.getByLabel('Data e hora da captura', {exact:false}).fill('2026-11-25T20:44:00Z');
    await page.getByLabel('SHA-256 da captura (64 caracteres)').fill('ab'.repeat(32));
    await page.getByLabel('SHA-256 do texto jurídico da licença (64 caracteres)').fill('cd'.repeat(32));
    await page.getByLabel('Vigência a partir de (ISO-8601)').fill('2026-11-25T20:44:00Z');
    await page.getByLabel('Justificativa da declaração (mín. 10 caracteres)').fill('Rodapé do site declara a licença pública.');
    await page.getByRole('checkbox',{name:'Guardar a cópia original'}).check();
    await page.getByLabel('Fundamento de Guardar a cópia original', {exact:true}).fill('Cláusula 3: reproduzir a obra.');
    await page.getByRole('checkbox',{name:'Redistribuir derivados'}).check();
    await page.getByLabel('Fundamento de Redistribuir derivados', {exact:true}).fill('Tentativa de exportar derivados.');
    await page.getByRole('button',{name:'Declarar licença pública'}).click();
    await page.getByText('A licença não cobre uma das operações marcadas.').waitFor();
    await page.getByRole('checkbox',{name:'Redistribuir derivados'}).uncheck();
    await page.getByRole('button',{name:'Declarar licença pública'}).click();
    await page.getByText('Ela ainda precisa de aprovação independente').waitFor();
    assert.equal(posts.at(-1).purpose, 'AUTHORIZED_PROCESSING');
    assert.equal(posts.at(-1).startsAtBasis, 'CAPTURE_DATE');
    assert.equal(posts.at(-1).endsAt, null);
    await page.getByText('Declarada internamente pelo responsável (sem aprovação independente)').waitFor();
    assert.equal(await page.getByText('Aprovada', {exact:true}).count(), 0);
    // Independent approval remains available (the server refuses the same person).
    await page.getByRole('button',{name:'Decidir licença de MCTI-SIN-DESPACHO 2026-janout-7f8e2292'}).waitFor();
  } finally {
    await browser.close();
  }
});

test('Licença pública: carregar declaração preparada preenche o formulário, não envia e recusa o que a plataforma não modela', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    const state = await setup(page);
    const posts = [];
    await page.route('**/v1/platform/source-licenses/public', async route => {
      posts.push(route.request().postDataJSON());
      return route.fulfill({status:422,json:{code:'FONTE_INEXISTENTE',message:'x'}});
    });
    await unlock(page);
    const declaration = {sourceId:'SENATRAN-RENAVAM-FROTA',edition:'2026-09-005963d4',purpose:'AUTHORIZED_PROCESSING',
      licenseIdentifier:'PUBLIC-DOMAIN-DECLARED',legalTextSha256:'df'.repeat(32),evidenceUri:'https://dados.transportes.gov.br/dataset/x',
      evidenceCapturedAt:'2026-11-27T01:11:00Z',evidenceSha256:'df'.repeat(32),startsAt:'2026-11-27T01:11:00Z',
      startsAtBasis:'CAPTURE_DATE',endsAt:null,reason:'Declaração oficial do portal (other-pd).',
      operations:[{operation:'STORE_ORIGINAL',groundKind:'LICENSE_GRANT',grounds:'Domínio público declarado pelo portal.'},
                  {operation:'EXTRACT_TEXT',groundKind:'LICENSE_GRANT',grounds:'Extração dos fatos agregados com proveniência.'}]};
    // Malformed, unknown licence and unknown operation are refused; nothing changes in the form.
    await page.getByLabel('Carregar declaração preparada (JSON)').fill('não é json');
    await page.getByRole('button',{name:'Carregar no formulário'}).click();
    await page.getByRole('alert').getByText('não é um JSON válido',{exact:false}).waitFor();
    await page.getByLabel('Carregar declaração preparada (JSON)').fill(JSON.stringify({...declaration,licenseIdentifier:'CC-BY-4.0'}));
    await page.getByRole('button',{name:'Carregar no formulário'}).click();
    await page.getByRole('alert').getByText('fora do que a plataforma modela',{exact:false}).waitFor();
    await page.getByLabel('Carregar declaração preparada (JSON)').fill(JSON.stringify({...declaration,
      operations:[{operation:'MODEL_INFERENCE',groundKind:'LICENSE_GRANT',grounds:'Operação que não foi solicitada aqui.'}]}));
    await page.getByRole('button',{name:'Carregar no formulário'}).click();
    await page.getByRole('alert').getByText('fora do que a plataforma modela',{exact:false}).waitFor();
    assert.equal(await page.getByLabel('Código da fonte licenciada').inputValue(),'');

    // A valid declaration fills every field, selects the licence and marks only its operations; nothing is posted.
    await page.getByLabel('Carregar declaração preparada (JSON)').fill(JSON.stringify(declaration));
    await page.getByRole('button',{name:'Carregar no formulário'}).click();
    await page.getByRole('status').getByText('Nada foi enviado',{exact:false}).waitFor();
    assert.equal(await page.getByLabel('Código da fonte licenciada').inputValue(),'SENATRAN-RENAVAM-FROTA');
    assert.equal(await page.getByLabel('Licença pública',{exact:true}).inputValue(),'PUBLIC-DOMAIN-DECLARED');
    assert.equal(await page.getByRole('checkbox',{name:'Guardar a cópia original'}).isChecked(),true);
    assert.equal(await page.getByRole('checkbox',{name:'Extrair dados da fonte'}).isChecked(),true);
    assert.equal(await page.getByRole('checkbox',{name:'Redistribuir derivados'}).isChecked(),false);
    assert.equal(posts.length,0);

    // Submitting sends exactly the loaded licence and operations.
    await page.getByRole('button',{name:'Declarar licença pública'}).click();
    await page.getByRole('alert').getByText('não estão registradas neste banco',{exact:false}).waitFor();
    assert.equal(posts.length,1);
    assert.equal(posts[0].licenseIdentifier,'PUBLIC-DOMAIN-DECLARED');
    assert.deepEqual(posts[0].operations.map(o => o.operation).sort(),['EXTRACT_TEXT','STORE_ORIGINAL']);
  } finally {
    await browser.close();
  }
});
