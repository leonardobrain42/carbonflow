import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const project = '20000000-0000-4000-8000-000000000093';
const organization = '10000000-0000-4000-8000-000000000093';

test('a seção de aplicabilidade nomeia a citação sem fechar o teste', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440, height:900}});
    await page.route('**/v1/account-session', route => route.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000093', email:'teste@carbonflow.test', name:'Teste'},
      projects:[{projectId:project, organizationId:organization, environment:'SANDBOX', state:'DRAFT', version:1}],
    }}));
    await page.route('**/v1/organizations', route => route.fulfill({json:[{
      id:organization, name:'Teste', environment:'SANDBOX', status:'PRIVATE_DRAFT', role:'DRAFT_OWNER', version:1,
    }]}));
    await page.route('**/v1/projects/*/metadata', route => route.fulfill({json:{
      projectId:project, organizationId:organization, environment:'SANDBOX', state:'DRAFT',
      version:1, title:'Rede aberta', description:'Recarga',
    }}));
    await page.route('**/v1/projects/*/technical/capabilities', route => route.fulfill({json:{
      projectId:project, environment:'SANDBOX', owner:true,
      actions:['VIEW_INTAKE','VIEW_DOSSIER'], modules:{},
    }}));
    await page.route('**/v1/projects/*/technical/intake', route => route.fulfill({json:{
      projectId:project, organizationId:organization, environment:'SANDBOX', version:1,
      state:'RASCUNHO', reviewState:'NAO_AVALIADO', triageCandidate:'VM0038',
      journey:{id:'VM0038_V1_1', candidate:'VM0038', title:'VM0038 v1.1',
        description:'Rede aberta', technicalTopics:[]},
      methodologies:[], data:{activity:'Recarga', country:'BR', state:'DF', locality:'Brasília',
        candidateMethodology:'VM0038', referenceActDate:'2027-01-01', notes:null},
      outstandingGates:[],
    }}));
    await page.route('**/v1/projects/*/technical/preliminary-dossier', route => route.fulfill({status:204}));
    await page.route('**/v1/projects/*/journey/resume', route => route.fulfill({status:204}));
    await page.route('**/v1/projects/*/technical/pd-sections', route => route.fulfill({json:[{
      guide:{id:'applicability', title:'Aplicabilidade', version:1, fields:[], answers:{}, missing:[]},
      latest:null, monitoringDependency:null, calculation:null, additionality:null,
      applicability:{
        situation:'CITATION', citation:'VMD0049 v1.1 Tabela 1', citedStockPercent:'0,7',
        citedVintageYear:2024, stockLimitPercent:'7,5', salesLimitPercent:'30',
        citationBelowStockLimit:true,
        stockSeriesLabel:'Citação da lista positiva. Não é a série oficial do teste.',
        salesSeriesLabel:'Ausente. Ausência não é zero.',
        closesApplicability:false, usableForE8:false, marksDocumentReady:false,
        text:'A citação de 0,7% de estoque em 2024 na VMD0049 não fecha o teste de 7,5% e 30%. O PD não fica pronto.',
      },
    }]}));
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=redigir&guia=applicability');
    const section = page.getByRole('region', {name:'Seções da descrição do projeto'});
    await section.getByRole('heading', {name:'Aplicabilidade nomeada nesta seção'}).waitFor();
    await section.getByText('0,7% de estoque em 2024 · VMD0049 v1.1 Tabela 1').waitFor();
    await section.getByText('7,5%', {exact:true}).waitFor();
    await section.getByText('30% na média trienal').waitFor();
    await section.getByText('não fecha o teste').waitFor();
    await section.getByText('Ausente. Ausência não é zero.').waitFor();
    const closes = section.locator('dt', {hasText:'Fecha a aplicabilidade'}).locator('xpath=..');
    assert.equal((await closes.locator('dd').innerText()).trim(), 'Não');
    await section.getByText('O documento não fica pronto.').waitFor();
    await page.setViewportSize({width:390, height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  } finally {
    await browser.close();
  }
});
