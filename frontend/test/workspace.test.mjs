import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

const identity = {id: '00000000-0000-4000-8000-000000000001', name: 'Conta de teste', email: 'conta@example.test'};
const organization = {
  id: '10000000-0000-4000-8000-000000000001', name: 'Organização teste',
  environment: 'SANDBOX', status: 'PRIVATE_DRAFT', version: 1, role: 'DRAFT_OWNER',
};

async function withBrowser(run) {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 900}});
    await run(page);
  } finally {
    await browser.close();
  }
}

async function stubAccount(page, state) {
  await page.route('**/v1/account-session', async route => {
    if (route.request().method() === 'GET') {
      await route.fulfill(state.signedIn ? {status: 200, json: {identity, projects: state.projects}} : {status: 401, json: {code: 'SESSAO_NECESSARIA'}});
    } else if (route.request().method() === 'POST') {
      state.signedIn = true;
      await route.fulfill({status: 204});
    } else if (route.request().method() === 'DELETE') {
      state.signedIn = false;
      await route.fulfill({status: 204});
    } else {
      await route.fulfill({status: 405});
    }
  });
  await page.route('**/v1/organizations', async route => {
    if (route.request().method() === 'GET') {
      await route.fulfill({status: 200, json: state.organizations});
    } else if (route.request().method() === 'POST') {
      assert.ok(route.request().headers()['idempotency-key'], 'create needs Idempotency-Key');
      assert.equal(JSON.parse(route.request().postData()).name, 'Organização teste');
      state.organizations.push(organization);
      await route.fulfill({status: 201, json: organization});
    } else {
      await route.fulfill({status: 405});
    }
  });
}

test('CF-IMP-006: conta sem projeto inicia organização sem alegar criação de projeto', async () => {
  await withBrowser(async page => {
    const state = {signedIn: true, projects: [], organizations: []};
    await stubAccount(page, state);
    await page.goto(base + '/workspace');
    await page.getByRole('heading', {name: 'Você ainda não tem projetos'}).waitFor();
    assert.equal(await page.getByText('SIM-PREP', {exact: true}).count(), 0);
    const begin = page.getByRole('link', {name: 'Criar meu primeiro projeto'});
    await begin.click();
    await page.getByRole('heading', {name: 'Crie o espaço da organização'}).waitFor();
    assert.ok(page.url().includes('/workspace/projects/new'));
    await page.getByLabel('Nome de trabalho da organização').fill('Organização teste');
    await page.getByRole('button', {name: 'Criar espaço privado'}).click();
    await page.getByRole('heading', {name: 'Espaço organizacional criado'}).waitFor();
    await page.getByText('A criação do projeto será habilitada na CF-IMP-007.', {exact: false}).waitFor();
    assert.equal(state.organizations.length, 1);
    assert.equal(state.projects.length, 0);
    await page.setViewportSize({width: 390, height: 844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
    await page.reload();
    await page.getByRole('heading', {name: 'Espaço organizacional criado'}).waitFor();
  });
});

test('CF-IMP-006: projeto precisa ser selecionado e pode ser retirado pela sessão', async () => {
  await withBrowser(async page => {
    const state = {signedIn: true, organizations: [organization], projects: [
      {projectId: 'DRAFT-ONE', organizationId: organization.id, environment: 'SANDBOX', state: 'DRAFT', version: 1},
      {projectId: 'DRAFT-TWO', organizationId: organization.id, environment: 'SANDBOX', state: 'DRAFT', version: 2},
    ]};
    await stubAccount(page, state);
    await page.goto(base + '/workspace');
    const selector = page.getByLabel('Projetos disponíveis nesta conta');
    await selector.waitFor();
    assert.equal(await selector.inputValue(), '');
    assert.equal(await page.getByText('Nenhum projeto selecionado.').count(), 1);
    assert.equal(await selector.locator('option').count(), 3);
    await selector.selectOption('DRAFT-TWO');
    await page.getByRole('heading', {name: 'Projeto selecionado'}).waitFor();
    await page.getByText('Estado: DRAFT · Ambiente: SANDBOX · Versão: 2').waitFor();
    state.projects = [state.projects[0]];
    await page.getByRole('button', {name: 'Atualizar lista'}).click();
    await page.getByText('Nenhum projeto selecionado.').waitFor();
    assert.equal(await selector.locator('option').count(), 2);
    assert.equal(await page.getByText('DRAFT-TWO').count(), 0);
    await page.setViewportSize({width: 390, height: 844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
  });
});

test('CF-IMP-006: login da conta não reutiliza credenciais ou cookies SIM-*', async () => {
  await withBrowser(async page => {
    const state = {signedIn: false, projects: [], organizations: []};
    await stubAccount(page, state);
    await page.goto(base + '/workspace');
    await page.getByRole('heading', {name: 'Entre na sua conta'}).waitFor();
    await page.getByLabel('E-mail da conta').fill('conta@example.test');
    await page.getByLabel('Senha', {exact: true}).fill('password-for-frontend-mock');
    await page.getByRole('button', {name: 'Entrar na conta'}).click();
    await page.getByRole('heading', {name: 'Você ainda não tem projetos'}).waitFor();
    await page.getByRole('button', {name: 'Sair'}).click();
    await page.getByRole('heading', {name: 'Entre na sua conta'}).waitFor();
    assert.equal(state.signedIn, false);
  });
});
