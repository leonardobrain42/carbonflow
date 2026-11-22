import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const project = '20000000-0000-4000-8000-000000000099';
const organization = '10000000-0000-4000-8000-000000000099';

test('a seção de adicionalidade nomeia os pareceres humanos', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440, height:900}});
    await page.route('**/v1/account-session', route => route.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000099', email:'teste@carbonflow.test', name:'Teste'},
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
      guide:{id:'additionality', title:'Adicionalidade', version:1, fields:[], answers:{}, missing:[]},
      latest:null, monitoringDependency:null, calculation:null,
      additionality:{
        declarationRoute:'Lista positiva VMD0049 a verificar', declarationIsOpinion:false,
        marketTestClosesApplicability:false, concludesAdditionality:false,
        usableForE8:false, marksDocumentReady:false,
        text:'Os três pareceres continuam humanos. A rota escrita no guia não entra no documento como conclusão. O PD não fica pronto.',
        criteria:[
          {code:'POSITIVE_LIST', title:'Lista positiva', stateLabel:'Parecer humano',
            text:'A lista positiva é parecer humano.', humanOpinion:true, concludes:false},
          {code:'REGULATORY_SURPLUS', title:'Excedente regulatório', stateLabel:'Parecer humano',
            text:'O excedente regulatório é parecer humano.', humanOpinion:true, concludes:false},
          {code:'CHRONOLOGY', title:'Cronologia', stateLabel:'Parecer humano',
            text:'O compromisso declarado pede parecer humano.', humanOpinion:true, concludes:false},
        ],
      },
    }]}));
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=redigir&guia=additionality');
    const section = page.getByRole('region', {name:'Seções da descrição do projeto'});
    await section.getByRole('heading', {name:'Adicionalidade nomeada nesta seção'}).waitFor();
    await section.getByText('não entra no documento como conclusão').waitFor();
    await section.getByText('Rota escrita no guia: Lista positiva VMD0049 a verificar. Não é o parecer do documento.').waitFor();
    await section.getByText('Lista positiva', {exact:true}).waitFor();
    await section.getByText('Excedente regulatório', {exact:true}).waitFor();
    await section.getByText('Cronologia', {exact:true}).waitFor();
    await section.getByText('Esta seção não conclui a adicionalidade.').first().waitFor();
    assert.equal(await page.getByText('Adicionalidade aprovada', {exact:true}).count(), 0);
    assert.equal(await page.getByText('Referência: MARKET_ROUTE', {exact:true}).count(), 0);
    await page.setViewportSize({width:390, height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  } finally {
    await browser.close();
  }
});
