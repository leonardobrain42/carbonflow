import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const projectId = '20000000-0000-4000-8000-000000000001';

test('registers and withdraws a request without treating registration as authority', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const requests = [];
    await page.route('**/v1/projects/*/authority-requests**', async route => {
      const request = route.request();
      if (request.method() === 'PUT') {
        const details = request.postDataJSON();
        assert.equal(details.power,'SEND_INVITATIONS');
        requests.push({id:new URL(request.url()).pathname.split('/').pop(),version:1,state:'SUBMITTED',details});
      } else if (request.method() === 'POST') {
        assert.equal(request.headers()['if-match'],'"1"');
        requests[0] = {...requests[0],version:2,state:'WITHDRAWN'};
      }
      await route.fulfill({status:200,json:{projectId,environment:'SANDBOX',requests}});
    });

    await page.goto(base + '/workspace/projects/' + projectId + '/authority-requests');
    await page.getByRole('button',{name:'Nova solicitação'}).click();
    await page.getByLabel('Poder solicitado').selectOption('SEND_INVITATIONS');
    await page.getByLabel('Fundamento da representação').fill('Nomeação em ata.');
    await page.getByLabel('Referência do documento').fill('Ata de 15/10/2026');
    await page.getByRole('button',{name:'Registrar solicitação'}).click();
    await page.getByText('Registrada · comprovação pendente',{exact:true}).waitFor();
    await page.reload();
    await page.getByRole('button',{name:'Retirar solicitação'}).click();
    await page.getByRole('button',{name:'Confirmar retirada'}).click();
    await page.getByText('Retirada pelo solicitante',{exact:true}).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});
