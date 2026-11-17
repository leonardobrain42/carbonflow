import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const projectId = '20000000-0000-4000-8000-000000000001';
const orgId = '10000000-0000-4000-8000-000000000001';
const project = {
  projectId, organizationId: orgId, environment: 'SANDBOX', state: 'DRAFT',
  title: 'Recarga urbana', description: 'Hipótese de projeto', version: 1,
};

test('CF-IMP-008: rascunho da conta cruza intake e prévia sem adquirir autoridade E8', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 900}});
    const state = {intake: null, dossier: null};
    const technical = '/v1/projects/' + projectId + '/technical';
    let pilotAccessAttempts = 0;

    await page.route('**/v1/account-session', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({status: 200, json: {
          identity: {id: '00000000-0000-4000-8000-000000000001', email: 'demo@example.test', name: 'Conta demo'},
          projects: [{projectId, organizationId: orgId, environment: 'SANDBOX', state: 'DRAFT', version: 1}],
        }});
      } else {
        await route.fulfill({status: 405});
      }
    });
    await page.route('**/v1/organizations', route => route.fulfill({status: 200, json: [{
      id: orgId, name: 'Organização', environment: 'SANDBOX', status: 'PRIVATE_DRAFT',
      role: 'DRAFT_OWNER', version: 1,
    }]}));
    await page.route('**/v1/projects/*/metadata', async route => {
      assert.equal(route.request().method(), 'GET');
      assert.ok(route.request().url().includes(projectId));
      await route.fulfill({status: 200, json: project});
    });
    await page.route('**/v1/projects/*/technical/capabilities', route => route.fulfill({
      status: 200, json: {projectId, environment: 'SANDBOX', modules: {
        intake: 'PREPARATORY_ONLY', projectDescription: 'PRELIMINARY_ONLY',
        evidence: 'NOT_ENABLED', calculation: 'NOT_ENABLED',
        monitoring: 'NOT_ENABLED', externalActs: 'NOT_ENABLED',
      }},
    }));
    await page.route('**/v1/projects/*/technical/intake', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({status: 200, headers: {ETag: '"' + (state.intake?.version ?? 0) + '"'}, json:
          state.intake ?? {projectId, organizationId: orgId, environment: 'SANDBOX', version: 0,
            state: 'NAO_INICIADO', reviewState: 'NAO_AVALIADO', data: null,
            outstandingGates: [{code: 'ANALISE_NAO_EXECUTADA', rule: 'CF-IMP-008',
              message: 'Cadastro ainda não avaliado.'}]},
        });
      } else if (route.request().method() === 'PUT') {
        assert.ok(route.request().headers()['idempotency-key']);
        assert.equal(route.request().headers()['if-match'], '"' + (state.intake?.version ?? 0) + '"');
        const data = JSON.parse(route.request().postData());
        assert.equal(data.candidateMethodology, 'VM0038');
        state.intake = {
          projectId, organizationId: orgId, environment: 'SANDBOX', version: 1,
          state: 'RASCUNHO', reviewState: 'NAO_AVALIADO', data,
          outstandingGates: [
            {code: 'ANALISE_NAO_EXECUTADA', rule: 'CF-IMP-008', message: 'Análise não executada.'},
            {code: 'PEN-004', rule: 'REG-E2-04', message: 'Direitos não comprovados.'},
          ],
        };
        await route.fulfill({status: 200, headers: {ETag: '"1"'}, json: state.intake});
      } else {
        await route.fulfill({status: 405});
      }
    });
    await page.route('**/v1/projects/*/technical/preliminary-dossier', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill(state.dossier ? {status: 200, json: state.dossier} : {status: 204});
      } else if (route.request().method() === 'POST') {
        assert.ok(route.request().headers()['idempotency-key']);
        assert.equal(route.request().headers()['if-match'], '"1"');
        assert.ok(state.intake);
        state.dossier = {
          id: '40000000-0000-4000-8000-000000000001', projectId, organizationId: orgId,
          environment: 'SANDBOX', revision: 1, intakeVersion: 1,
          state: 'RASCUNHO_PRELIMINAR_NAO_ADMISSIVEL', documentKind: 'PD_PREPARATORY_SCAFFOLD',
          sourceTemplate: 'SRC-007_PD_v5.0B.docx', sourceEdition: '5.0B',
          sections: ['Descrição do projeto', 'Linha de base', 'Salvaguardas'],
          blockers: [{code: 'PEN-004', message: 'Direitos não comprovados.', rule: 'REG-E2-04'}],
          intake: state.intake.data,
        };
        await route.fulfill({status: 201, json: state.dossier});
      } else {
        await route.fulfill({status: 405});
      }
    });
    await page.route('**/v1/projects/PIL-SYN-BR-001/**', route => {
      pilotAccessAttempts++;
      return route.fulfill({status: 500});
    });

    await page.goto(base + '/workspace');
    const selector = page.getByLabel('Projetos disponíveis nesta conta');
    await selector.waitFor();
    assert.equal(await selector.inputValue(), '');
    await selector.selectOption(projectId);
    await page.getByRole('heading', {name: 'Recarga urbana'}).waitFor();
    await page.getByRole('link', {name: 'Preparar informações técnicas'}).click();
    assert.ok(page.url().includes('/workspace/projects/' + projectId + '/technical'));
    await page.getByRole('heading', {name: 'Preparação técnica'}).waitFor();
    await page.getByText('NAO_INICIADO', {exact: false}).waitFor();
    await page.getByText('Nenhum resultado de carbono foi calculado.', {exact: false}).waitFor();

    await page.getByLabel('Atividade planejada').fill('Recarga elétrica em ambiente urbano');
    await page.getByLabel('País (sigla de duas letras, se conhecido)').fill('br');
    await page.getByLabel('Estado ou região (se conhecido)').fill('DF');
    await page.getByLabel('Localidade (se conhecida)').fill('Brasília');
    await page.getByLabel('Metodologia candidata (opcional)').fill('VM0038');
    await page.getByLabel('Data de referência da atividade (se conhecida)').fill('2027-09-27');
    await page.getByRole('button', {name: 'Salvar informações'}).click();
    await page.getByText('Informações preliminares salvas (revisão 1).').waitFor();
    await page.getByText('PEN-004', {exact: false}).waitFor();
    assert.equal(state.intake.version, 1);

    await page.getByRole('button', {name: 'Preparar prévia da revisão atual'}).click();
    await page.getByText('Prévia de dossiê criada.', {exact: false}).waitFor();
    await page.getByText('SRC-007_PD_v5.0B.docx').waitFor();
    assert.equal(state.dossier.state, 'RASCUNHO_PRELIMINAR_NAO_ADMISSIVEL');
    assert.equal(await page.getByRole('button', {name: 'Preparar prévia da revisão atual'}).isDisabled(), true);
    assert.equal(pilotAccessAttempts, 0);

    await page.reload();
    await page.getByRole('heading', {name: 'Preparação técnica'}).waitFor();
    await page.getByText('SRC-007_PD_v5.0B.docx').waitFor();
    assert.equal(await page.getByLabel('Atividade planejada').inputValue(),
      'Recarga elétrica em ambiente urbano');
    assert.equal(await page.getByLabel('País (sigla de duas letras, se conhecido)').inputValue(), 'BR');
    await page.setViewportSize({width: 390, height: 844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
  } finally {
    await browser.close();
  }
});
