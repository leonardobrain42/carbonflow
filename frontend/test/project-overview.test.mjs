import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const projectId = '20000000-0000-4000-8000-000000000001';
const overview = '/workspace/projects/' + projectId + '/overview';

test('visão geral prioriza o parecer autorizado e descarta o contexto após revogação', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport: {width: 390, height: 844}});
    const body = {
      projectId, title: 'Recarga monitorada', environment: 'SANDBOX', projectState: 'DRAFT', role: 'REV',
      methodology: 'VM0038 v1.1', methodologyConfigured: true, nextTask: 'review-baseline',
      tasks: [{
        id: 'review-baseline', kind: 'REVIEW', title: 'Revisão · Linha de base', state: 'IN_REVIEW',
        reason: 'A versão foi encaminhada e aguarda parecer interno.', responsible: 'REV', sourceVersion: 2,
        destination: {area: 'technical', screen: 'revisar', guide: 'baseline'}, actionable: true,
      }],
    };
    let response = 500;
    let pilotCalls = 0;
    await page.route('**/v1/projects/PIL-SYN-BR-001/**', route => {
      pilotCalls++;
      return route.fulfill({status: 500});
    });
    await page.route('**/v1/projects/' + projectId + '/journey', route =>
      route.fulfill(response === 200 ? {json: body} : {status: response, json: {}}));

    await page.goto(base + overview);
    await page.getByRole('alert').filter({hasText: 'Não foi possível consultar'}).waitFor();
    response = 200;
    await page.getByRole('button', {name: 'Tentar novamente'}).click();
    await page.getByRole('heading', {name: body.title}).waitFor();
    assert.equal(await page.getByText('Revisão interna (REV)', {exact: false}).count(), 2);
    const next = page.getByRole('link', {name: 'Continuar tarefa'});
    assert.equal(await next.getAttribute('href'),
      '/workspace/projects/' + projectId + '/technical?secao=revisar&guia=baseline');
    assert.equal(await page.getByRole('link', {name: 'Triagem guiada'}).count(), 0);
    assert.equal(await page.getByRole('link', {name: 'Preferências e equipe'}).count(), 0);
    assert.equal(await page.getByText('tCO2e', {exact: false}).count(), 0);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth === innerWidth), true);
    assert.equal(pilotCalls, 0);

    response = 404;
    await page.getByRole('button', {name: 'Atualizar visão geral'}).click();
    await page.getByRole('alert').filter({hasText: 'Projeto indisponível'}).waitFor();
    assert.equal(await page.getByRole('heading', {name: body.title}).count(), 0);
    assert.equal(await next.count(), 0);
    assert.equal(await page.getByRole('link', {name: 'Preparação técnica'}).count(), 0);
  } finally {
    await browser.close();
  }
});
