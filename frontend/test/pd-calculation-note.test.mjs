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
    let situation = 'MEMORY';
    let mutations = 0;
    const selectedRequests=[];
    page.on('request', request => {
      if (request.url().includes('/technical/pd-sections') && request.method() !== 'GET') mutations++;
    });
    const guide = {id:'quantification', title:'Quantificação', version:1, fields:[], answers:{}, missing:[]};
    const memory = {
      period:'M1', situation:'MEMORY', causeCode:null, cause:null,
      beTco2e:'4.05', peTco2e:'1.5', leTco2e:'0', erDisplay:'2.55',
      usableForE8:false, marksDocumentReady:false,
      text:'Ensaio interno da memória do servidor. O PD não fica pronto.',
      provenance:{
        mappingVersion:'OPEN-PD-CALCULATION-PROVENANCE@1', sectionId:'quantification',
        runId:'30000000-0000-4000-8000-000000000122',runVersion:2,
        inputSha256:'abcdef0123456789'.repeat(4),method:'VM0038@1.1',
        memoryHref:'/v1/projects/'+project+'/technical/open-monitoring/dossiers/M1/e8-runs/30000000-0000-4000-8000-000000000122',
        engine:'vm0038-open-grid-arithmetic@1',reviewStatus:'REVIEWED_INTERNAL',
        kernelStatus:'PURE_MATH_TEST_ONLY_NOT_ADMITTED',recordedAt:'2026-11-23T12:00:00Z',
        timing:{sourceYear:2027,projectYear:1,windowStart:'2027-02-01T00:00:00Z',windowEndExclusive:'2027-02-01T01:00:00Z'},
        inputs:[
          {symbol:'ED',origin:'Medição de energia entregue · vínculo do período',unit:'kWh',
            vintageYear:null,referenceId:'40000000-0000-4000-8000-000000000122',version:3,
            href:'/v1/projects/'+project+'/technical/open-monitoring/dossiers/M1/e8-input-bindings/40000000-0000-4000-8000-000000000122',
            access:'PROJECT_METADATA_ONLY'},
          {symbol:'EF_FUEL',origin:'Referência de catálogo · pino publicado no projeto',unit:'tCO2e/USgal',
            vintageYear:2027,referenceId:'50000000-0000-4000-8000-000000000122',version:1,
            href:'/v1/projects/'+project+'/technical/open-parameter-bindings/50000000-0000-4000-8000-000000000122',access:'PROJECT_METADATA_ONLY'},
          {symbol:'EVR',origin:'Referência protegida ou indisponível',unit:null,
            vintageYear:null,referenceId:null,version:null,href:null,access:'RESTRICTED_OR_UNAVAILABLE'},
        ],
        references:[{code:'BE',reference:'VM0038-1.1-EQ1',unit:'tCO2e',result:'4.05'}],
        limits:'Prévia atual vinculada à memória, sem cópia para a narrativa ou versão congelada. O PD não fica pronto.',
      },
    };
    const block = {
      period:'M1', situation:'BLOCKED', causeCode:'IR',
      cause:'A melhoria tecnológica não tem valor normalizado armazenado.',
      beTco2e:null, peTco2e:null, leTco2e:null, erDisplay:null,
      usableForE8:false, marksDocumentReady:false,
      text:'O documento nomeia o bloqueio e não escreve zero. O PD não fica pronto.',
    };
    const restricted = {...block,period:null,situation:'RESTRICTED',causeCode:null,cause:null,
      text:'Memória protegida. Esta conta não tem acesso ao cálculo. O PD não fica pronto.'};
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
    await page.route('**/v1/projects/*/technical/pd-sections*', route => {
      selectedRequests.push(new URL(route.request().url()).searchParams);
      return route.fulfill({json:[{
      guide, latest:null, monitoringDependency:null,
      calculation:situation === 'MEMORY' ? memory : situation === 'RESTRICTED' ? restricted : block,
    }]}); });
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=redigir&guia=quantification&run='+memory.provenance.runId+'&period=M1');
    await page.getByRole('heading', {name:'Cálculo nomeado nesta seção · M1'}).waitFor();
    await page.getByText('4.05 tCO2e').waitFor();
    await page.getByText('Esta seção não recalcula.').waitFor();
    await page.getByText('O documento não fica pronto.').waitFor();
    assert.equal(await page.getByText('Habilitada', {exact:true}).count(), 0);
    const memoryLink=page.getByRole('link', {name:'Ver memória canônica desta tentativa'});
    assert.equal(await memoryLink.getAttribute('href'), memory.provenance.memoryHref);
    await page.getByText('Conferir origem, unidades e revisão da memória', {exact:true}).click();
    await page.getByText('vm0038-open-grid-arithmetic@1', {exact:true}).waitFor();
    await page.getByText('VM0038@1.1', {exact:true}).waitFor();
    await page.getByText('BE · VM0038-1.1-EQ1 · tCO2e', {exact:true}).waitFor();
    await page.getByText('Referência protegida ou indisponível', {exact:true}).waitFor();
    await page.getByText('Unidade normalizada: tCO2e/USgal. Vintage: 2027.').waitFor();
    assert.equal(await page.getByRole('link', {name:'Consultar origem de ED'}).getAttribute('href'),
      memory.provenance.inputs[0].href);
    assert.equal(await page.getByRole('link', {name:'Consultar origem de EVR'}).count(), 0);
    const pinLink=page.getByRole('link', {name:'Consultar origem de EF_FUEL'});
    assert.equal(await pinLink.getAttribute('href'),memory.provenance.inputs[1].href);
    await pinLink.focus();
    assert.equal(await pinLink.evaluate(element => document.activeElement === element),true);
    await memoryLink.focus();
    assert.equal(await memoryLink.evaluate(element => document.activeElement === element), true);
    assert.equal(await page.locator('#pd-narrative').inputValue(), '');
    await page.setViewportSize({width:390, height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
    situation = 'BLOCKED';
    await page.setViewportSize({width:1440, height:900});
    await page.getByRole('button', {name:'Atualizar seções'}).click();
    await page.getByText('Bloqueio: Melhoria tecnológica.').waitFor();
    await page.getByText('não escreve zero').waitFor();
    assert.equal(await page.getByText('4.05 tCO2e').count(), 0);
    assert.equal(await memoryLink.count(), 0);
    assert.equal(await page.getByText('vm0038-open-grid-arithmetic@1', {exact:true}).count(), 0);
    situation = 'RESTRICTED';
    await page.getByRole('button', {name:'Atualizar seções'}).click();
    await page.getByText('Memória protegida.', {exact:false}).waitFor();
    assert.equal(await memoryLink.count(), 0);
    assert.equal(await page.getByText('4.05 tCO2e').count(), 0);
    assert.equal(await page.locator('#pd-narrative').inputValue(), '');
    assert.equal(mutations, 0);
    assert.ok(selectedRequests.length >= 3);
    for (const params of selectedRequests) {
      assert.equal(params.get('run'),memory.provenance.runId);
      assert.equal(params.get('period'),'M1');
    }
    await page.getByText('Consulta específica da memória, inclusive histórica.').waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  } finally {
    await browser.close();
  }
});
