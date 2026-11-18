import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const projectId = '20000000-0000-4000-8000-000000000001';

test('preparador retoma o guia e a revisão vê o conteúdo somente para leitura', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const route = '/v1/projects/' + projectId;
    let editable = true;
    const guide = {id:'applicability', title:'Aplicabilidade e fronteira', guidance:'Descreva as estações.',
      fields:[{key:'boundary',label:'Estações na fronteira',type:'text',options:[]}],
      version:0, answers:{}, missing:['Estações na fronteira']};
    await page.route('**' + route + '/metadata', r => r.fulfill({json:{projectId,environment:'SANDBOX',title:'Frota'}}));
    await page.route('**' + route + '/technical/capabilities', r => r.fulfill({json:{
      projectId,environment:'SANDBOX',owner:false,actions:['VIEW_GUIDES',...(editable ? ['SAVE_GUIDE'] : [])],modules:{}
    }}));
    await page.route('**' + route + '/technical/guides', r => r.fulfill({json:{methodology:'VM0038_V1_1',guides:[{...guide,editable}]}}));
    await page.route('**' + route + '/technical/guides/applicability', r => {
      assert.equal(r.request().method(),'PUT');
      assert.equal(r.request().headers()['if-match'],'"0"');
      guide.answers = r.request().postDataJSON();
      guide.version = 1;
      guide.missing = [];
      return r.fulfill({json:{...guide,editable}});
    });
    await page.goto(base + '/workspace/projects/' + projectId + '/technical');
    await page.getByLabel('Estações na fronteira').fill('Estação de Brasília');
    await page.getByRole('button',{name:'Salvar guia'}).click();
    await page.getByText('Revisão 1 salva.').waitFor();
    editable = false;
    await page.reload();
    const field = page.getByLabel('Estações na fronteira');
    assert.equal(await field.inputValue(),'Estação de Brasília');
    assert.equal(await field.isDisabled(),true);
    assert.equal(await page.getByRole('button',{name:'Salvar guia'}).count(),0);
  } finally { await browser.close(); }
});
