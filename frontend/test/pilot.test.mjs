import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const url = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const chromiumPath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

test('demonstra os quatro recortes, a coleta de M2 e a recusa sem EC', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 896}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(url);
    await page.getByLabel('Identidade do piloto').fill('SIM-PREP');
    await page.getByLabel('Senha de demonstração').fill('piloto-prep');
    await page.getByRole('button', {name: 'Entrar', exact: true}).click();
    await page.getByRole('heading', {name: 'Monitoramento de emissões'}).waitFor();
    assert.equal(await page.getByText('SIMULAÇÃO', {exact: true}).count(), 1);

    const [created] = await Promise.all([
      page.waitForResponse(response => response.request().method() === 'POST' && response.status() === 201),
      page.getByRole('button', {name: 'Executar cenário'}).click(),
    ]);
    const run = await created.json();

    const m1 = page.locator('[data-period="M1"]');
    const m2 = page.locator('[data-period="M2"]');
    await m1.getByText('6,00', {exact: true}).waitFor();
    await m2.getByText('5,30', {exact: true}).waitFor();
    for (const [period, amounts] of [[m1, ['1,80', '4,20']], [m2, ['1,14', '4,16']]]) {
      for (const amount of amounts) {
        await period.getByText(amount + ' tCO₂e', {exact: true}).waitFor();
      }
    }
    assert.equal(await page.getByText('Em coleta', {exact: true}).count(), 3);
    await page.getByText('Aguardando depósito', {exact: true}).waitFor();
    await page.getByText('EF-SYN-v1 · 0,30 tCO₂/MWh', {exact: true}).waitFor();
    assert.deepEqual(await page.getByRole('rowheader').allTextContents(),
      ['M1 · 2027', 'M1 · 2028', 'M2 · 2028', 'M2 · 2029']);

    const [rejected] = await Promise.all([
      page.waitForResponse(response => response.request().method() === 'POST' && response.status() === 422),
      page.getByRole('button', {name: 'Testar massa sem EC'}).click(),
    ]);
    assert.equal((await rejected.json()).code, 'LACUNA_OBRIGATORIA');
    await page.getByRole('alert').waitFor();
    await m2.getByText('5,30', {exact: true}).waitFor();
    const reportLink = page.getByRole('link', {name: 'Consultar execução em JSON'});
    assert.ok((await reportLink.getAttribute('href')).endsWith('/' + run.id));

    await page.reload();
    await page.getByText('Aguardando depósito', {exact: true}).waitFor();
    assert.ok((await reportLink.getAttribute('href')).endsWith('/' + run.id));
    assert.deepEqual(errors, []);

    await page.setViewportSize({width: 375, height: 812});
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  } finally {
    await browser.close();
  }
});
