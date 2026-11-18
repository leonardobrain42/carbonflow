import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

test('abre fila de conformidade após MFA e consulta os comprovantes', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    await page.route('**/v1/compliance/session', async route => {
      const verified = route.request().method() === 'POST';
      if (verified) assert.equal(route.request().postDataJSON().code,'123456');
      await route.fulfill({json:{verified,expiresAt:verified ? new Date(Date.now()+60000).toISOString() : null}});
    });
    await page.route('**/v1/compliance/queue?*', route => route.fulfill({json:{nextOffset:null,cases:[{
      projectId:'project-a', projectTitle:'Recarga em Brasília', organizationId:'org-a', organizationName:'Organização A',
      requestId:'request-a', power:'SEND_INVITATIONS', basis:'Ata de indicação.', reference:'DOC-01',
    }]}}));
    await page.route('**/v1/compliance/projects/*/requests/*/documents', route => route.fulfill({json:[
      {id:'document-a',bytesCount:80},
    ]}));
    await page.goto(base+'/workspace/compliance');
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear fila'}).click();
    await page.getByRole('button',{name:'Abrir solicitação'}).click();
    await page.getByText('Ata de indicação.',{exact:true}).waitFor();
    assert.match(await page.getByRole('link',{name:'Baixar comprovante 1'}).getAttribute('href'), /document-a$/);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});
