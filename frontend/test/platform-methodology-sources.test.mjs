import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const API = '**/v1/platform/methodology-registry/METHS/1.0/sources**';

test('D18 fatia 2: contexto e necessidade por metodologia, sem prometer admissão', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    let verified = false; const contexts = []; const posts = [];
    await page.route('**/v1/compliance/session', async route => {
      if (route.request().method() === 'POST') verified = true;
      await route.fulfill({json:{verified,expiresAt:verified ? new Date(Date.now()+60000).toISOString() : null}});
    });
    await page.route(API, async route => {
      const r = route.request(); const url = new URL(r.url());
      if (r.method() === 'GET') return route.fulfill({json:contexts});
      const body = r.postDataJSON(); posts.push({path:url.pathname,body});
      if (url.pathname.endsWith('/contexts')) {
        contexts.push({methodology:'METHS',edition:'1.0',scenario:body.scenario,title:body.title,country:body.country,
          networkMode:body.networkMode,vehicleCategory:null,chargerType:null,energySystem:null,needCount:0,registeredSourceCount:0});
        return route.fulfill({status:201,json:contexts.at(-1)});
      }
      if (body.code === 'METHS-DUP') return route.fulfill({status:409,json:{}});
      contexts[0].needCount++;
      return route.fulfill({status:201,json:{}});
    });
    await page.goto(base + '/workspace/platform/methodologies/METHS/1.0/sources');
    await page.getByRole('heading',{name:'Verificação da equipe interna'}).waitFor();
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear'}).click();
    await page.getByRole('heading',{name:'Fontes de METHS 1.0'}).waitFor();
    await page.getByText('Nenhum contexto registrado').waitFor();

    await page.getByLabel('Cenário (código, em maiúsculas)').fill('GENERIC_SCENARIO');
    await page.getByLabel('Título').fill('Contexto genérico');
    await page.getByLabel('País (2 letras)').fill('BR');
    await page.getByRole('button',{name:'Registrar contexto'}).click();
    await page.getByText('Contexto registrado.').waitFor();
    assert.equal(posts[0].body.vehicleCategory,null);

    await page.getByRole('button',{name:'Nova necessidade de fonte em GENERIC_SCENARIO'}).click();
    await page.getByLabel('Código da necessidade (global, em maiúsculas)').fill('METHS-NORM');
    await page.getByLabel('Título',{exact:true}).last().fill('Norma da metodologia');
    await page.getByLabel('Finalidade').fill('Regras do método');
    await page.getByLabel('Identificador da fonte').fill('METHS');
    await page.getByLabel(/^Edição da fonte/).fill('1.0');
    await page.getByRole('button',{name:'Registrar necessidade'}).click();
    await page.getByText('Necessidade de fonte registrada.').waitFor();
    const need = posts.at(-1);
    assert.match(need.path,/\/contexts\/GENERIC_SCENARIO\/needs$/);
    assert.equal(need.body.sourceEdition,'1.0');
    assert.equal(need.body.editionPolicy,'EXACT');
    await page.getByText('1 necessidade(s) de fonte').waitFor();

    // Evidence needs no source; a duplicate code with other content is explained.
    await page.getByRole('button',{name:'Nova necessidade de fonte em GENERIC_SCENARIO'}).click();
    await page.getByLabel('Tipo',{exact:true}).selectOption('PROJECT_EVIDENCE');
    assert.equal(await page.getByLabel('Identificador da fonte').count(),0);
    await page.getByLabel('Código da necessidade (global, em maiúsculas)').fill('METHS-DUP');
    await page.getByLabel('Título',{exact:true}).last().fill('Prova do projeto');
    await page.getByLabel('Finalidade').fill('Evidência do proponente');
    await page.getByRole('button',{name:'Registrar necessidade'}).click();
    await page.getByRole('alert').getByText('conteúdo diferente').waitFor();
    assert.equal(posts.at(-1).body.sourceId,null);
  } finally { await browser.close(); }
});

test('D18 fatia 2: sem privilégio ou metodologia inexistente mostra só o aviso', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    await page.route('**/v1/compliance/session', r => r.fulfill({json:{verified:true,expiresAt:new Date(Date.now()+60000).toISOString()}}));
    await page.route(API, r => r.fulfill({status:404,json:{}}));
    await page.goto(base + '/workspace/platform/methodologies/METHS/1.0/sources');
    await page.getByRole('alert').getByText('Metodologia não registrada').waitFor();
    assert.equal(await page.getByRole('button',{name:'Registrar contexto'}).count(),0);
  } finally { await browser.close(); }
});
