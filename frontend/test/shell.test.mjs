import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

test('CF-IMP-005: shell responsivo, navegação e controle de sessão no piloto', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 900}});
    await page.goto(base + '/pilot');
    await page.getByRole('heading', {name: 'Entrar no piloto'}).waitFor();
    assert.equal(await page.getByRole('complementary', {name: 'Navegação lateral da demonstração'}).count(), 0);

    await page.getByLabel('Identidade do piloto').fill('SIM-PREP');
    await page.getByLabel('Senha de demonstração').fill('piloto-prep');
    await page.getByRole('button', {name: 'Entrar', exact: true}).click();

    const sidebar = page.getByRole('complementary', {name: 'Navegação lateral da demonstração'});
    await sidebar.waitFor();
    assert.equal(await page.getByRole('navigation', {name: 'Navegação pelas etapas do piloto'}).getByRole('link').count(), 5);
    await sidebar.getByRole('link', {name: 'Enquadramento e inventário'}).click();
    assert.ok(page.url().endsWith('#cf-intake'));
    // Angular may still be attaching the project workbench after the sidebar is rendered.
    // Wait for the anchor instead of reading a transient zero immediately after navigation.
    await page.locator('#cf-intake').waitFor({state: 'attached'});
    assert.equal(await page.locator('#cf-intake').count(), 1);

    await page.setViewportSize({width: 390, height: 844});
    assert.ok(await sidebar.evaluate(element => element.getBoundingClientRect().width <= window.innerWidth));

    await page.getByRole('button', {name: 'Sair', exact: true}).click();
    await page.getByRole('heading', {name: 'Entrar no piloto'}).waitFor();
    assert.equal(await sidebar.count(), 0);

    await page.goto(base + '/');
    await page.getByRole('heading', {name: 'Comece um projeto de carbono com orientação técnica'}).waitFor();
    assert.equal(new URL(page.url()).pathname, '/');
    await page.getByRole('link', {name: 'Explorar piloto sintético'}).first().click();
    await page.getByRole('heading', {name: 'Entrar no piloto'}).waitFor();
    const unknown = await page.request.get(base + '/rota-inexistente', {failOnStatusCode: false});
    assert.equal(unknown.status(), 404);
  } finally {
    await browser.close();
  }
});
