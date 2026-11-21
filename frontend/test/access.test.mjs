import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {chromium} from 'playwright-core';

const url = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const chromiumPath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const pilot = '/v1/projects/PIL-SYN-BR-001';

test('autorização de artefato por teclado, com segregação e revogação', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}, acceptDownloads: true});
    page.setDefaultTimeout(8000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(url + '/pilot');
    await page.getByRole('heading', {name: 'Entrar no piloto'}).waitFor();

    async function press(locator) {
      await locator.and(page.locator(':not(:disabled)')).waitFor();
      await locator.focus();
      await page.keyboard.press('Enter');
    }

    async function login(role) {
      const identity = page.getByLabel('Identidade do piloto');
      await identity.waitFor();
      await identity.focus();
      await identity.fill('SIM-' + role);
      await page.keyboard.press('Tab');
      assert.equal(await page.evaluate(() => document.activeElement?.id), 'password');
      await page.keyboard.type('piloto-' + role.toLowerCase());
      await page.keyboard.press('Enter');
      await page.getByRole('button', {name: 'Sair', exact: true}).waitFor();
      await page.getByRole('heading', {name: 'Acesso a artefatos'}).waitFor();
      assert.equal(await page.evaluate(() => document.activeElement?.tagName), 'H1');
    }

    async function logout() {
      await press(page.getByRole('button', {name: 'Sair', exact: true}));
      await page.getByLabel('Identidade do piloto').waitFor();
      assert.equal(await page.evaluate(() => document.activeElement?.id), 'identity');
    }

    const artifactTitle = 'Memória pública de ensaio, versão 1';
    await login('PREP');
    const older = await page.request.post(url + pilot + '/dossiers', {
      headers: {'Idempotency-Key': crypto.randomUUID()},
      data: {sample: 'E8-002@1'},
    });
    assert.equal(older.status(), 201);
    await press(page.getByRole('button', {name: 'Preparar amostra'}));
    await page.getByRole('status').filter({hasText: 'Amostra sintética encaminhada'}).waitFor();
    const versions = page.locator('#artifact-list > article');
    assert.equal(await versions.count(), 4);
    await press(page.getByRole('button', {name: 'Ver amostras anteriores'}));
    await versions.nth(4).waitFor();
    await press(page.getByRole('button', {name: 'Mostrar só a mais recente'}));
    await versions.nth(4).waitFor({state: 'detached'});
    const latest = page.getByRole('article', {name: artifactTitle, exact: true}).first();
    const content = await latest.getByRole('link', {name: 'Baixar'}).getAttribute('href');
    const reference = content.split('/')[5];
    const card = () => page.getByRole('article', {name: artifactTitle, exact: true}).filter({
      has: page.locator('a[href="' + content + '"]'),
    });

    await logout();
    await login('REV');
    await press(card().getByRole('button', {name: 'Aprovar versão'}));
    await page.getByRole('status').filter({hasText: 'Versão aprovada e congelada'}).waitFor();

    await logout();
    await login('PP');
    const [created] = await Promise.all([
      page.waitForResponse(response => response.request().method() === 'POST'
        && response.url().endsWith('/grants') && response.status() === 201),
      press(card().getByRole('button', {name: 'Conceder acesso'})),
    ]);
    const {grant} = await created.json();
    const grantCard = () => page.locator('article.grant').filter({hasText: reference}).first();
    assert.equal(grant.artifact.id, reference);
    await press(grantCard().getByRole('button', {name: 'Capturar recibo simulado'}));
    await grantCard().getByText('Emitente SIM-VVB-A · Captura SIM-PP').waitFor();

    await logout();
    await login('VVB');
    const search = page.getByRole('searchbox', {name: 'Buscar por título'});
    await page.getByRole('button', {name: 'Buscar', exact: true}).and(page.locator(':enabled')).waitFor();
    await search.focus();
    await search.fill('Memória pública');
    await page.keyboard.press('Tab');
    assert.equal(await page.evaluate(() => document.activeElement?.textContent.trim()), 'Buscar');
    await Promise.all([
      page.waitForResponse(response => {
        const searchUrl = new URL(response.url());
        return searchUrl.pathname.endsWith('/artifacts') && searchUrl.searchParams.get('q') === 'Memória pública';
      }),
      page.keyboard.press('Enter'),
    ]);
    await card().waitFor();
    await press(card().getByRole('button', {name: 'Ver prévia'}));
    const preview = card().getByRole('region', {name: 'Memória pública de ensaio · versão 1'});
    await preview.waitFor();
    assert.equal(await page.evaluate(() => document.activeElement?.id), 'artifact-preview-title');
    await press(preview.getByRole('button', {name: 'Fechar prévia'}));
    await preview.waitFor({state: 'detached'});
    assert.equal(await page.evaluate(() => document.activeElement?.textContent.trim()), 'Ver prévia');
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      press(card().getByRole('link', {name: 'Baixar'})),
    ]);
    const text = await readFile(await download.path(), 'utf8');
    assert.match(text, /SIMULAÇÃO/);
    assert.doesNotMatch(text, /SIM-ANEXO-RESERVADO/);
    assert.equal((await page.request.get(url + content.replace('/versions/1/', '/versions/2/'))).status(), 404);

    await logout();
    await login('PP');
    await press(grantCard().getByRole('button', {name: 'Revogar acesso'}));
    await page.getByRole('status').filter({hasText: 'Acesso revogado'}).waitFor();
    await grantCard().getByText(/Revogada/).waitFor();
    await grantCard().getByText('Emitente SIM-VVB-A · Captura SIM-PP').waitFor();

    await logout();
    await login('VVB');
    assert.equal((await page.request.get(url + content)).status(), 404);
    assert.equal((await page.request.get(url + content.replace('/content', '/preview'))).status(), 404);
    const list = await (await page.request.get(url + pilot + '/artifacts')).json();
    assert.ok(!list.some(item => item.id === reference));

    await logout();
    await login('PREP-OUTRA');
    assert.equal((await page.request.get(url + pilot + '/artifacts')).status(), 404);
    assert.equal((await page.request.get(url + content)).status(), 404);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});
