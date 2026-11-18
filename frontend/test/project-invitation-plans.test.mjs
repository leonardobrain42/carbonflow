import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const projectId = '20000000-0000-4000-8000-000000000001';

test('plans PREP and REV and exposes conflicts after reopening', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const plans = [];
    await page.route('**/v1/projects/*/invitation-plans**', async route => {
      const request = route.request();
      if (request.method() === 'PUT') {
        assert.equal(request.headers()['if-match'], '"0"');
        const details = request.postDataJSON();
        plans.push({id:new URL(request.url()).pathname.split('/').pop(), version:1, details, state:'PLANNED',
          independence:'REVIEW_REQUIRED'});
        if (plans.length === 2) {
          assert.equal(plans[0].details.email, details.email);
          for (const plan of plans) plan.independence = 'SAME_PREPARER_AND_REVIEWER';
        }
      }
      await route.fulfill({status:200,json:{projectId,environment:'SANDBOX',plans}});
    });

    await page.goto(base + '/workspace/projects/' + projectId + '/invitation-plans');
    for (const role of ['PREP','REV']) {
      await page.getByRole('button',{name:'Novo plano de convite'}).click();
      await page.getByLabel('E-mail da pessoa').fill('externo@example.invalid');
      await page.getByLabel('Papel planejado').selectOption(role);
      await page.getByRole('checkbox',{name:'Linha de base'}).check();
      await page.getByRole('button',{name:'Salvar plano'}).click();
      await page.getByText('Plano de convite salvo como rascunho.').waitFor();
    }
    await page.reload();
    await page.getByRole('button',{name:'Editar plano de REV'}).waitFor();
    assert.equal(await page.getByText('Mesmo e-mail na preparação e na revisão', {exact:false}).count(),2);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});
