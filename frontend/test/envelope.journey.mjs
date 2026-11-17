import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {chromium, request} from 'playwright-core';

const url = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:18096';
const host = new URL(url);
assert.ok(['localhost', '127.0.0.1'].includes(host.hostname) && host.port !== '8080', 'Use o Compose descartável.');
const project = 'SIM-E8-012-CONTROLE';
const path = '/v1/projects/' + project;
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const day = process.env.CARBONFLOW_JOURNEY_DAY ?? '01';
assert.match(day, /^(0[1-9]|1[0-9]|2[0-8])$/);
const output = process.env.CARBONFLOW_JOURNEY_OUTPUT ?? '/tmp/carbonflow-e8-012-jornada.json';
const header = 'kind,sourceFactId,start,endExclusive,zone,siteId,chargerId,vehicleId,circuitId,meterId,quantity,unit';

function stamp() { return Math.round(performance.now()); }

async function call(api, route, data, expected, version) {
  const headers = {'Idempotency-Key': crypto.randomUUID()};
  if (version !== undefined) headers['If-Match'] = '"' + version + '"';
  const response = data === undefined ? await api.get(route) : await api.post(route, {headers, data});
  assert.equal(response.status(), expected, (await response.text()).slice(0, 350));
  return response.json();
}

async function actor(role) {
  const api = await request.newContext({baseURL: url});
  assert.equal((await api.post('/v1/session', {data: {identity: 'SIM-' + role, password: 'piloto-' + role.toLowerCase()}})).status(), 204);
  return api;
}

async function prepareGrant() {
  const prep = await actor('PREP');
  try {
    const sample = await call(prep, path + '/dossiers', {sample: 'E8-002@1'}, 201);
    const files = await call(prep, path + '/artifacts', undefined, 200);
    const {id, version} = files.find(file => file.dossierId === sample.id && file.variant === 'PUBLICA' && file.version === 1);
    const rev = await actor('REV');
    try {
      await call(rev, path + '/dossiers/' + sample.id + '/actions',
        {transitionId: 'TX-E3-08', details: {artifact: {id, version}}}, 200, 1);
    } finally {
      await rev.delete('/v1/session');
      await rev.dispose();
    }
    const pp = await actor('PP');
    try {
      const result = await call(pp, path + '/grants',
        {artifact: {id, version}, purpose: 'AUDITORIA_V1', recipient: 'SIM-VVB', expiresAt: '2031-01-01T00:00:00Z'}, 201);
      return result.grant;
    } finally {
      await pp.delete('/v1/session');
      await pp.dispose();
    }
  } finally {
    await prep.delete('/v1/session');
    await prep.dispose();
  }
}

async function activate(locator) {
  await locator.and(locator.page().locator(':enabled, summary')).waitFor();
  await locator.focus();
  await locator.press('Enter');
}

async function login(page, role) {
  const field = page.getByLabel('Identidade do piloto');
  await field.focus();
  await page.keyboard.insertText('SIM-' + role);
  await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => document.activeElement?.id), 'password');
  await page.keyboard.insertText('piloto-' + role.toLowerCase());
  await page.keyboard.press('Enter');
  await page.getByRole('button', {name: 'Sair', exact: true}).waitFor();
  await page.waitForFunction(() => document.activeElement?.tagName === 'H1');
}

async function logout(page) {
  await activate(page.getByRole('button', {name: 'Sair', exact: true}));
  await page.getByLabel('Identidade do piloto').waitFor();
  await page.waitForFunction(() => document.activeElement?.id === 'identity');
}

async function actionResponse(page, button, endsWith) {
  const [response] = await Promise.all([
    page.waitForResponse(result => new URL(result.url()).pathname.endsWith(endsWith) && result.request().method() === 'POST'),
    activate(button),
  ]);
  return {status: response.status(), value: await response.json()};
}

async function feedback(panel, message) {
  const status = panel.getByText(message, {exact: true});
  await status.waitFor();
  await status.page().waitForFunction(message => document.activeElement?.textContent.trim() === message, message);
}

async function checkSession(browser, index, output) {
  const grant = await prepareGrant();
  const page = await browser.newPage({viewport: {width: 1280, height: 900}});
  page.setDefaultTimeout(12000);
  const record = {session: index, tasks: [], surfaces: [], occurrences: [], ok: false};
  await page.route('**/v1/session', async route => {
    if (route.request().method() !== 'GET') return route.continue();
    const response = await route.fetch();
    if (!response.ok()) return route.fulfill({response});
    const body = await response.json();
    body.projects = body.projects.filter(item => item.project === project);
    await route.fulfill({response, json: body});
  });
  try {
    await page.goto(url);
    await login(page, 'PREP');
    const monitor = page.getByRole('region', {name: 'Períodos de monitoramento'});
    const stage = performance.now();
    await activate(monitor.getByRole('button', {name: 'Atualizar períodos e memórias'}));
    await monitor.getByRole('heading', {name: /^M1 · Coletando/}).waitFor();
    record.surfaces.push('TEL-E4-01');

    const imports = page.getByRole('region', {name: 'Importação ED/EC'});
    const period = imports.getByLabel('Período do lote', {exact: true});
    await period.focus();
    await period.press('End');
    assert.equal(await period.inputValue(), 'M2');
    const begin = String(12 + index).padStart(2, '0');
    const tag = crypto.randomUUID().slice(0, 8).toUpperCase();
    const bytes = Buffer.from(header + '\n' +
      `ED,SIM-E812-JORNADA-${tag},2029-02-${day}T${begin}:00:00Z,2029-02-${day}T${begin}:20:00Z,America/Sao_Paulo,SIM-PATIO-01,SIM-DCFC-01,SIM-BEV-01,,SIM-ED-01,0.1,kWh\n`);
    await imports.getByLabel('Arquivo ED/EC').setInputFiles({name: 'e812-jornada-' + tag + '.csv', mimeType: 'text/csv', buffer: bytes});
    const queued = await actionResponse(page, imports.getByRole('button', {name: 'Importar CSV', exact: true}), '/imports');
    assert.equal(queued.status, 202);
    await imports.getByText(/Concluído · 1 de 1 linhas/).waitFor();
    await activate(imports.getByRole('button', {name: 'Revisar linha 2', exact: true}));
    await page.waitForFunction(() => document.activeElement?.id === 'energy-review-reason');
    await page.keyboard.insertText('Ensaio de teclado E8-012.');
    const proposal = await actionResponse(page, imports.getByRole('button', {name: 'Registrar decisão', exact: true}), '/actions');
    assert.equal(proposal.value.line.decision, 'PROPOSTA');
    await feedback(imports, 'Decisão registrada.');
    const blocked = await page.request.post(url + path + '/observations/' + proposal.value.line.id + '/actions', {
      headers: {'Idempotency-Key': crypto.randomUUID(), 'If-Match': '"' + proposal.value.line.version + '"'},
      data: {transitionId: 'ADMITIR', reason: 'Ensaiar guarda do papel.'},
    });
    assert.equal(blocked.status(), 403);
    record.tasks.push({role: 'PREP', task: 'importar e propor', durationMs: stamp() - stage, ok: true});
    record.surfaces.push('TEL-E4-02');

    await logout(page);
    await login(page, 'REV');
    const review = stamp();
    await activate(imports.getByText(/^Lotes recentes \(/));
    await activate(imports.getByRole('button', {name: new RegExp(queued.value.jobId.slice(0, 8))}));
    await activate(imports.getByRole('button', {name: 'Revisar linha 2'}));
    const choices = imports.getByLabel('Decisão', {exact: true});
    await choices.focus();
    await choices.press('Home');
    await choices.press('ArrowDown');
    assert.equal(await choices.inputValue(), 'ADMITIR');
    await page.keyboard.press('Tab');
    await page.waitForFunction(() => document.activeElement?.id === 'energy-review-reason');
    await page.keyboard.insertText('Conferência independente de ensaio.');
    const accepted = await actionResponse(page, imports.getByRole('button', {name: 'Registrar decisão'}), '/actions');
    assert.equal(accepted.value.line.decision, 'ADMITIDA');
    await feedback(imports, 'Decisão registrada.');
    record.tasks.push({role: 'REV', task: 'revisar e admitir', durationMs: stamp() - review, ok: true});
    const dossiers = page.getByRole('region', {name: 'Dossiês PD e MR'});
    await activate(dossiers.getByRole('button', {name: 'Atualizar dossiês'}));
    record.surfaces.push('TEL-E4-03');

    await logout(page);
    await login(page, 'PP');
    const receipt = stamp();
    const access = page.getByRole('region', {name: 'Acesso a artefatos'});
    const current = access.locator('article.grant').filter({hasText: grant.artifact.id});
    const returned = await actionResponse(page, current.getByRole('button', {name: 'Capturar recibo simulado'}), '/receipts');
    assert.equal(returned.status, 200);
    await current.getByText('Emitente SIM-VVB-A · Captura SIM-PP').waitFor();
    record.tasks.push({role: 'PP', task: 'registrar comprovante simulado', durationMs: stamp() - receipt, ok: true});
    record.surfaces.push('TEL-E4-04');
    const issuance = page.getByRole('region', {name: 'Emissão e conciliação'});
    await activate(issuance.getByRole('button', {name: 'Atualizar emissão'}));
    await issuance.getByText(/Comprove a aprovação de V1 ou V2/).waitFor();
    assert.equal(await issuance.getByRole('button', {name: 'Criar pedido em preparo'}).count(), 0);
    record.surfaces.push('TEL-E4-05');
    await logout(page);
    record.ok = record.tasks.length === 3 && record.surfaces.length === 5 && record.tasks.every(task => task.durationMs <= 300000);
  } catch (error) {
    record.occurrences.push(String(error).slice(0, 800));
  } finally {
    await page.close();
    output.sessions.push(record);
  }
}

const browser = await chromium.launch({executablePath});
const result = {type: 'automação Chrome sintética, sem participantes humanos',
  viewport: {width: 1280, height: 900}, browser: browser.version(), sessions: []};
try {
  for (let index = 1; index <= 5; index++) await checkSession(browser, index, result);
} finally {
  await browser.close();
  await writeFile(output, JSON.stringify(result, null, 2) + '\n');
}
assert.equal(result.sessions.filter(run => run.ok).length, 5, JSON.stringify(result.sessions));
