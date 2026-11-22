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

  await page.route('**/v1/projects', async route => {
    if (route.request().method() !== 'POST') {
      await route.fulfill({status: 405});
      return;
    }
    assert.ok(route.request().headers()['idempotency-key']);
    const body = JSON.parse(route.request().postData());
    assert.equal(body.organizationId, organization.id);
    const projectId = '10000000-0000-4000-8000-0000000000' + String(state.projects.length + 10);
    const project = {
      projectId, organizationId: organization.id, environment: 'SANDBOX',
      state: 'DRAFT', version: 1, title: body.title, description: body.description,
    };
    state.projects.push(project);
    await route.fulfill({status: 201, headers: {ETag: '"1"'}, json: project});
  });
  await page.route('**/v1/projects/*/metadata', async route => {
    const parts = new URL(route.request().url()).pathname.split('/');
    const project = state.projects.find(p => p.projectId === parts[3]);
    if (!project) {
      await route.fulfill({status: 404, json: {code: 'RECURSO_NAO_VISIVEL'}});
      return;
    }
    if (route.request().method() === 'GET') {
      await route.fulfill({status: 200, headers: {ETag: '"' + project.version + '"'}, json: {
        ...project, title: project.title ?? project.projectId, description: project.description ?? null,
      }});
    } else if (route.request().method() === 'PATCH') {
      assert.ok(route.request().headers()['idempotency-key']);
      assert.equal(route.request().headers()['if-match'], '"' + project.version + '"');
      Object.assign(project, JSON.parse(route.request().postData()));
      project.version++;
      await route.fulfill({status: 200, headers: {ETag: '"' + project.version + '"'}, json: project});
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
    await page.getByRole('heading', {name: 'Descreva seu projeto'}).waitFor();
    assert.equal(state.organizations.length, 1);
    assert.equal(state.projects.length, 0);
    await page.setViewportSize({width: 390, height: 844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
    await page.reload();
    await page.getByRole('heading', {name: 'Descreva seu projeto'}).waitFor();
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

test('CF-IMP-013: segunda conta vê o grant de quantificação sem editar o rascunho', async () => {
  await withBrowser(async page => {
    const project = {
      projectId: 'DRAFT-CALC', organizationId: organization.id, environment: 'SANDBOX',
      state: 'DRAFT', version: 1, access: 'CALCULATION', title: 'Rede da segunda conta',
      description: 'Diagnóstico compartilhado',
    };
    const state = {signedIn: true, organizations: [organization], projects: [project]};
    await stubAccount(page, state);
    await page.goto(base + '/workspace');
    await page.getByText('Grant de quantificação · SANDBOX').waitFor();
    await page.getByLabel('Projetos disponíveis nesta conta').selectOption('DRAFT-CALC');
    await page.getByRole('heading', {name: 'Rede da segunda conta'}).waitFor();
    await page.getByText('Grant de quantificação nesta conta.').waitFor();
    const open = page.getByRole('link', {name: 'Abrir quantificação'});
    assert.ok((await open.getAttribute('href')).includes('secao=quantificar'));
    assert.equal(await page.getByRole('button', {name: 'Salvar alterações'}).count(), 0);
    await page.setViewportSize({width: 390, height: 844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
  });
});

test('CF-IMP-007: conta cria rascunho persistente e atualiza metadados sem calcular ER', async () => {
  await withBrowser(async page => {
    const state = {signedIn: true, projects: [], organizations: [organization]};
    await stubAccount(page, state);
    await page.goto(base + '/workspace/projects/new');
    await page.getByRole('heading', {name: 'Descreva seu projeto'}).waitFor();
    await page.getByLabel('Nome de trabalho do projeto').fill('Infraestrutura de recarga DF');
    await page.getByLabel('Descrição inicial (opcional)').fill('Rascunho para estudo');
    await page.getByRole('button', {name: 'Salvar rascunho'}).click();
    await page.getByRole('heading', {name: 'Projeto criado no sandbox'}).waitFor();
    assert.equal(state.projects.length, 1);
    await page.getByText('Rascunho para estudo', {exact: false}).count();
    assert.equal(await page.getByText('VCUs emitidas', {exact: false}).count(), 0);
    await page.getByRole('link', {name: 'Ver meus projetos'}).click();
    const selector = page.getByLabel('Projetos disponíveis nesta conta');
    await selector.waitFor();
    await selector.selectOption(state.projects[0].projectId);
    await page.getByRole('heading', {name: 'Infraestrutura de recarga DF'}).waitFor();
    await page.getByLabel('Nome de trabalho do projeto').fill('Recarga DF — revisão');
    await page.getByRole('button', {name: 'Salvar alterações'}).click();
    await page.getByText('Metadados do rascunho atualizados (versão 2).').waitFor();
    assert.equal(state.projects[0].version, 2);
    await page.reload();
    const second = page.getByLabel('Projetos disponíveis nesta conta');
    await second.waitFor();
    assert.equal(await second.inputValue(), '');
    await second.selectOption(state.projects[0].projectId);
    await page.getByRole('heading', {name: 'Recarga DF — revisão'}).waitFor();
    await page.setViewportSize({width: 390, height: 844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
  });
});
