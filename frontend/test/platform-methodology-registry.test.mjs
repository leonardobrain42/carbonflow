import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const page_url = base + '/workspace/platform/methodologies';

function seeded() {
  return [{program:'VCS',methodology:'VM0038',edition:'1.1',version:1,title:'VM0038 · Sistemas de recarga de veículos elétricos',
    rightsHolder:'Verra',officialUri:'https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/',
    origin:'SEED',registeredAt:'2026-11-25T10:00:00Z',digest:'a'.repeat(64),hasPackage:true}];
}

async function setup(page, {verified = false, registryStatus = 200} = {}) {
  const state = {verified, entries: seeded(), posts: []};
  await page.route('**/v1/compliance/session', async route => {
    if (route.request().method() === 'POST') {
      assert.equal(route.request().postDataJSON().code, '123456');
      state.verified = true;
    }
    await route.fulfill({json:{verified:state.verified,expiresAt:state.verified ? new Date(Date.now()+60000).toISOString() : null}});
  });
  await page.route('**/v1/platform/methodology-registry', async route => {
    const request = route.request();
    if (registryStatus !== 200) return route.fulfill({status:registryStatus,json:{message:'x'}});
    if (request.method() === 'GET') return route.fulfill({json:state.entries});
    const body = request.postDataJSON();
    const ifMatch = request.headers()['if-match'];
    state.posts.push({body, ifMatch});
    const current = state.entries.find(e => e.methodology === body.methodology && e.edition === body.edition);
    if (current && ifMatch !== '"' + current.version + '"') return route.fulfill({status:412,json:{message:'obsoleto'}});
    if (!current && ifMatch !== '"0"') return route.fulfill({status:412,json:{message:'obsoleto'}});
    const same = current && current.title === body.title && current.rightsHolder === body.rightsHolder
      && (current.officialUri ?? null) === (body.officialUri ?? null);
    if (same) return route.fulfill({status:200,json:current});
    const entry = {program:body.program,methodology:body.methodology,edition:body.edition,
      version:current ? current.version + 1 : 1,title:body.title,rightsHolder:body.rightsHolder,
      officialUri:body.officialUri,origin:'ADMIN',registeredAt:'2026-11-25T12:00:00Z',digest:'b'.repeat(64),hasPackage:false};
    state.entries = [...state.entries.filter(e => e !== current), entry];
    return route.fulfill({status:201,json:entry});
  });
  return state;
}

test('D18: MFA, registro novo, nova versão e conflito, sem prometer pacote nem capacidade', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    const state = await setup(page);
    await page.goto(page_url);
    await page.getByRole('heading',{name:'Verificação da equipe interna'}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Registrar metodologia'}).count(),0);
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear registro'}).click();
    await page.getByRole('heading',{name:'Metodologias registradas'}).waitFor();
    await page.getByText('Pacote em código').waitFor();

    // New key: If-Match "0"; the result is registered WITHOUT a package.
    await page.getByLabel('Metodologia (código, em maiúsculas)').fill('METHX');
    await page.getByLabel('Edição',{exact:true}).fill('2.1');
    await page.getByLabel('Título').fill('Metodologia X');
    await page.getByLabel('Titular dos direitos').fill('Titular X');
    await page.getByRole('button',{name:'Registrar metodologia'}).click();
    await page.getByText('Registrado: METHX 2.1, versão 1.').waitFor();
    await page.getByText('Registrada, sem pacote executável').waitFor();
    assert.equal(state.posts[0].ifMatch,'"0"');
    assert.equal(state.posts[0].body.program,'VCS');
    assert.equal(state.posts[0].body.officialUri,null);

    // Registering an existing key as new is a conflict the user can understand.
    await page.getByLabel('Metodologia (código, em maiúsculas)').fill('METHX');
    await page.getByLabel('Edição',{exact:true}).fill('2.1');
    await page.getByLabel('Título').fill('Outro título');
    await page.getByLabel('Titular dos direitos').fill('Titular X');
    await page.getByRole('button',{name:'Registrar metodologia'}).click();
    await page.getByRole('alert').getByText('Use "Nova versão" na lista').waitFor();

    // New version: the key is locked and the current version goes in If-Match.
    await page.getByRole('button',{name:'Nova versão de METHX 2.1'}).click();
    await page.getByRole('heading',{name:/Nova versão de METHX 2.1 \(atual: 1\)/}).waitFor();
    assert.equal(await page.getByLabel('Metodologia (código, em maiúsculas)').getAttribute('readonly'),'');
    await page.getByLabel('Título').fill('Metodologia X revisada');
    await page.getByRole('button',{name:'Registrar nova versão'}).click();
    await page.getByText('Registrado: METHX 2.1, versão 2.').waitFor();
    assert.equal(state.posts.at(-1).ifMatch,'"1"');

    // Same content as the latest version: no new version.
    await page.getByRole('button',{name:'Nova versão de METHX 2.1'}).click();
    await page.getByRole('button',{name:'Registrar nova versão'}).click();
    await page.getByText('Nenhuma alteração').waitFor();
    assert.equal(state.entries.find(e => e.methodology === 'METHX').version,2);
  } finally {
    await browser.close();
  }
});

test('D18: staff sem o privilégio vê aviso claro e nenhum formulário', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    await setup(page,{verified:true,registryStatus:404});
    await page.goto(page_url);
    await page.getByRole('alert').getByText('exclusivo da equipe interna autorizada').waitFor();
    assert.equal(await page.getByRole('button',{name:'Registrar metodologia'}).count(),0);
    assert.equal(await page.getByRole('heading',{name:'Metodologias registradas'}).count(),0);
  } finally {
    await browser.close();
  }
});
