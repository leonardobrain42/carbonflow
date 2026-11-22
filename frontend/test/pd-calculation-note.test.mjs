import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const project = '20000000-0000-4000-8000-000000000122';
const organization = '10000000-0000-4000-8000-000000000122';

test('a seção de quantificação nomeia a memória ou o bloqueio', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440, height:900}});
    let blocked = false;
    const guide = {id:'quantification', title:'Quantificação', version:1, fields:[], answers:{}, missing:[]};
    const memory = {
      period:'M1', situation:'MEMORY', causeCode:null, cause:null,
      beTco2e:'4.05', peTco2e:'1.5', leTco2e:'0', erDisplay:'2.55',
      usableForE8:false, marksDocumentReady:false,
      text:'Ensaio interno da memória do servidor. O PD não fica pronto.',
    };
    const block = {
      period:'M1', situation:'BLOCKED', causeCode:'IR',
      cause:'A melhoria tecnológica não tem valor normalizado armazenado.',
      beTco2e:null, peTco2e:null, leTco2e:null, erDisplay:null,
      usableForE8:false, marksDocumentReady:false,
      text:'O documento nomeia o bloqueio e não escreve zero. O PD não fica pronto.',
    };
    await page.route('**/v1/account-session', route => route.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000122', email:'teste@carbonflow.test', name:'Teste'},
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
      guide, latest:null, monitoringDependency:null, calculation:blocked ? block : memory,
    }]}));
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=redigir&guia=quantification');
    await page.getByRole('heading', {name:'Cálculo nomeado nesta seção · M1'}).waitFor();
    await page.getByText('4.05 tCO2e').waitFor();
    await page.getByText('Esta seção não recalcula.').waitFor();
    await page.getByText('O documento não fica pronto.').waitFor();
    assert.equal(await page.getByText('Habilitada', {exact:true}).count(), 0);
    await page.setViewportSize({width:390, height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
    blocked = true;
    await page.setViewportSize({width:1440, height:900});
    await page.getByRole('button', {name:'Atualizar seções'}).click();
    await page.getByText('Bloqueio: Melhoria tecnológica.').waitFor();
    await page.getByText('não escreve zero').waitFor();
    assert.equal(await page.getByText('4.05 tCO2e').count(), 0);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  } finally {
    await browser.close();
  }
});
