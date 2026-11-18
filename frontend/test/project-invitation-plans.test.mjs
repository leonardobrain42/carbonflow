import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const projectId = '20000000-0000-4000-8000-000000000001';
const planId = '20000000-0000-4000-8000-000000000002';
const invitation = {
  id: '20000000-0000-4000-8000-000000000003', projectId, projectTitle: 'Recarga de veículos',
  planId, planVersion:1, recipient:'externo@example.invalid', role:'REV', activities:['DOCUMENTATION'],
  state:'SENT', acceptUntil:'2027-01-18T12:00:00Z', accessUntil:'2027-02-18T12:00:00Z'
};

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

test('shows the send command after approval and revokes a sent invitation', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const path = '/v1/projects/' + projectId + '/invitations';
    let canSend = false;
    await page.route('**/v1/projects/*/invitation-plans', route => route.fulfill({json:{
      projectId, environment:'SANDBOX', plans:[{
        id:planId, version:1, state:'PLANNED', independence:'NONE_DECLARED',
        details:{email:invitation.recipient, role:invitation.role, activities:invitation.activities}
      }]
    }}));
    await page.route('**' + path, route => route.fulfill({json:{canSend,invitations:[]}}));
    await page.route('**' + path + '/plans/' + planId + '/send', route => {
      assert.equal(route.request().method(), 'POST');
      assert.equal(route.request().headers()['if-match'], '"1"');
      assert.ok(route.request().headers()['idempotency-key']);
      return route.fulfill({json:invitation});
    });
    await page.route('**' + path + '/' + invitation.id + '/revoke', route => {
      assert.equal(route.request().method(), 'POST');
      return route.fulfill({json:{canSend,invitations:[{...invitation,state:'REVOKED'}]}});
    });
    await page.goto(base + '/workspace/projects/' + projectId + '/invitation-plans');
    await page.getByRole('link',{name:'Consultar comprovação de poderes'}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Enviar convite'}).count(),0);
    canSend = true;
    await page.reload();
    await page.getByRole('button',{name:'Enviar convite'}).click();
    await page.getByText('Enviado · aguardando aceite').waitFor();
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button',{name:'Revogar vínculo'}).click();
    await page.getByText('Vínculo revogado',{exact:true}).waitFor();
  } finally {
    await browser.close();
  }
});

test('recipient sees scope before acceptance and can then open technical preparation', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    await page.route('**/v1/project-invitations', route => route.fulfill({json:[invitation]}));
    await page.route('**/v1/project-invitations/' + invitation.id + '/accept', route => {
      assert.equal(route.request().method(), 'POST');
      return route.fulfill({json:{...invitation,state:'ACCEPTED'}});
    });
    await page.goto(base + '/workspace/invitations');
    await page.getByText('Documentação do projeto').waitFor();
    assert.equal(await page.getByRole('link',{name:'Abrir preparação técnica'}).count(),0);
    await page.getByRole('button',{name:'Aceitar convite'}).click();
    const link = page.getByRole('link',{name:'Abrir preparação técnica'});
    await link.waitFor();
    assert.equal(await link.getAttribute('href'), '/workspace/projects/' + projectId + '/technical');
    assert.equal(await page.getByRole('button',{name:'Aceitar convite'}).count(),0);
  } finally {
    await browser.close();
  }
});
