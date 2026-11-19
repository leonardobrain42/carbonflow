import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

test('staff MFA vê as fontes e necessidades de projetos, sem aprovação fictícia', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    let verified = false;
    let sourceCalls = 0;
    await page.route('**/v1/compliance/session', async route => {
      if (route.request().method() === 'POST') {
        assert.equal(route.request().postDataJSON().code, '123456');
        verified = true;
      }
      await route.fulfill({json:{verified,expiresAt:verified ? new Date(Date.now()+60000).toISOString() : null}});
    });
    await page.route('**/v1/platform/source-readiness/VM0038', async route => {
      sourceCalls++;
      assert.equal(verified, true, 'api deve ser requisitada só depois de autenticação');
      await route.fulfill({json:{
        methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
        asOf:'2026-11-19T14:00:00Z',normativeRuleStatus:'ONLY_SYNTHETIC_RULE_PACKS',
        missingSourceCount:1,missingReferenceRightsCount:1,reviewPendingCount:0,projectEvidenceCount:1,
        needs:[
          {code:'OPEN-EVR',stage:'BASELINE',kind:'DATASET',title:'Estoque BEV por modelo (EVR)',
            purpose:'Composição nacional',sourceId:'BR-LDV-BEV-STOCK-BY-MODEL',sourceEdition:'YEAR',
            officialUri:null,mandatory:true,conditionNote:'Não confundir vendas com estoque.',
            status:'MISSING_SOURCE_METADATA',nextAction:'Cadastrar fonte.'},
          {code:'OPEN-METHOD',stage:'APPLICABILITY',kind:'NORMATIVE',title:'VM0038 v1.1',
            purpose:'Regras do método',sourceId:'VM0038',sourceEdition:'1.1',
            officialUri:'https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/',
            mandatory:true,conditionNote:'Só metadados.',status:'METADATA_ONLY',
            nextAction:'Revisar licença.'},
          {code:'OPEN-RIGHTS',stage:'APPLICABILITY',kind:'PROJECT_EVIDENCE',title:'Divulgação aos motoristas',
            purpose:'Direitos sobre reduções',sourceId:null,sourceEdition:null,
            officialUri:null,mandatory:true,conditionNote:'Prova do projeto.',
            status:'PROJECT_SPECIFIC_EVIDENCE',nextAction:'Solicitar ao proponente.'},
        ],
      }});
    });
    await page.goto(base+'/workspace/platform/sources');
    await page.getByRole('heading',{name:'Verificação da equipe interna'}).waitFor();
    assert.equal(sourceCalls,0);
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear inventário'}).click();
    await page.getByRole('heading',{name:'VM0038 · edição 1.1'}).waitFor();
    await page.getByText('Estoque BEV por modelo (EVR)').waitFor();
    await page.getByText('Divulgação aos motoristas').waitFor();
    assert.match(await page.getByRole('status').allTextContents().then(x=>x.join(' ')),/sintético/i);
    await page.getByLabel('Etapa da jornada').selectOption('BASELINE');
    assert.equal(await page.getByRole('heading',{name:'Estoque BEV por modelo (EVR)'}).count(),1);
    assert.equal(await page.getByRole('heading',{name:'VM0038 v1.1'}).count(),0);
    await page.getByLabel('Situação da fonte').selectOption('METADATA_ONLY');
    await page.getByText('Nenhuma necessidade corresponde aos filtros').waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});
