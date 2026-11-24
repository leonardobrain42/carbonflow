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
    let memoryMode='OK';
    let releaseMemory;
    let memoryArrived;
    await page.route('**/technical/open-monitoring/dossiers/*/e8-runs/*', async route => {
      if (memoryMode==='DELAY') await new Promise(resolve => {
        releaseMemory=resolve;
        if (memoryArrived) memoryArrived();
      });
      if (memoryMode==='DENIED') return route.fulfill({status:404,json:{message:'Indisponível'}});
      const hours=memoryMode==='EMPTY' ? [] : [
        {hourStart:'2027-02-01T00:00:00Z',peTco2e:'0.5',factorTco2ePerKwh:'0.999999'},
        {hourStart:'2027-02-01T01:00:00Z',peTco2e:'1',factorTco2ePerKwh:'0.999999'},
      ];
      if (memoryMode==='LARGE') while (hours.length<25) hours.push({...hours[0]});
      return route.fulfill({json:{
        id:'30000000-0000-4000-8000-000000000122',projectId:project,period:'M1',version:2,
        inputSha256:memoryMode==='HASH' ? 'other-snapshot' : 'abcdef0123456789'.repeat(4),
        engine:'vm0038-open-grid-arithmetic@2-hourly',state:memoryMode==='STALE' ? 'STALE' : 'REVIEWED_INTERNAL',
        stale:memoryMode==='STALE',canIssueVcu:memoryMode==='ADMITTED',
        memory:{engine:'vm0038-open-grid-arithmetic@2-hourly',usableForE8:false,
          beTco2e:'4.05',peTco2e:memoryMode==='MISMATCH' ? '9' : '1.5',leTco2e:'0',erTco2e:'2.5500',erDisplay:'2.55',
          afecKwhPer100Miles:'23.456789',mpgUs:'31.234567',
          trace:[{code:'AFEC',reference:'PRIVATE-RATING-STEP',unit:'kWh/100 mi',result:'23.456789'},
            {code:'BE',reference:'VM0038-1.1-EQ1',unit:'tCO2e',result:'4.05'},
            {code:'PE',reference:'VM0038-1.1-EQ4',unit:'tCO2e',result:'1.5'},
            {code:'LE',reference:'VM0038-1.1-SECTION-9.3',unit:'tCO2e',result:'0'},
            {code:'ER',reference:'VM0038-1.1-EQ7',unit:'tCO2e',result:'2.55'}],
          hourlyPe:memoryMode==='NO_HOURS' ? null : {windowStart:'2027-02-01T00:00:00Z',
            windowEndExclusive:'2027-02-01T02:00:00Z',region:'SIN',peTco2e:'1.5',hours},
        },
      }});
    });
    let annualMode='OK';
    let annualRequests=0;
    let releaseAnnual;
    let annualArrived;
    const yearlyRun='30000000-0000-4000-8000-000000000122';
    const yearlyHash='abcdef0123456789'.repeat(4);
    await page.route('**/technical/open-calculation-years/*', async route => {
      annualRequests++;
      assert.equal(new URL(route.request().url()).pathname,
        '/v1/projects/'+project+'/technical/open-calculation-years/1');
      const mode=annualMode;
      if (mode==='DELAY') await new Promise(resolve => {
        releaseAnnual=resolve;
        if (annualArrived) annualArrived();
      });
      if (mode==='DENIED') return route.fulfill({status:404,json:{message:'Indisponível'}});
      const blocked=mode==='BLOCKED'||mode==='BLOCKED_TOTAL';
      const selected={
        period:'M1',runId:mode==='REPLACED'?'new-run':yearlyRun,
        version:mode==='VERSION'?3:2,inputSha256:mode==='HASH'?'other-snapshot':yearlyHash,
        projectYear:1,windowStart:'2027-02-01T00:00:00Z',windowEndExclusive:'2027-02-01T01:00:00Z',
        state:'REVIEWED_INTERNAL',stale:mode==='STALE',
        totals:{beTco2e:'4.05',peTco2e:mode==='MISMATCH'?'8':'1.5',leTco2e:'0',erTco2e:'2.5500',erDisplay:'2.55'},
      };
      const second={period:'M2',runId:'30000000-0000-4000-8000-000000000123',version:1,
        inputSha256:'0'.repeat(64),projectYear:1,windowStart:'2027-02-01T01:00:00Z',
        windowEndExclusive:'2028-02-01T00:00:00Z',state:'REVIEWED_INTERNAL',stale:false,
        totals:{beTco2e:'7',peTco2e:'3',leTco2e:'0',erTco2e:'4.0050',erDisplay:'4.00'}};
      const totals={beTco2e:'11.05',peTco2e:'4.5',leTco2e:'0',erTco2e:'6.5550',erDisplay:'6.56'};
      return route.fulfill({json:{
        projectId:mode==='PROJECT'?'other-project':project,projectYear:mode==='YEAR'?2:1,
        engine:'vm0038-open-annual-consolidation@1',
        state:blocked?'BLOCKED':'REVIEWED_INTERNAL',usableForE8:mode==='ADMITTED',canIssueVcu:mode==='ISSUED',
        windowStart:'2027-02-01T00:00:00Z',windowEndExclusive:'2028-02-01T00:00:00Z',
        manifestSha256:'b'.repeat(64),
        totals:mode==='BLOCKED'?null:totals,
        members:mode==='DUPLICATE'?[selected,selected]:[selected,second],
        issues:blocked?[{code:'COVERAGE_GAP',period:'M2',from:'2027-03-01T00:00:00Z',
          until:'2027-04-01T00:00:00Z',detail:'Falta uma janela de monitoramento.'}]:[],
        privateRating:'PRIVATE-ANNUAL-RATING',
      }});
    });
    let fleetMode='OK';
    let fleetRequests=0;
    let releaseFleet;
    let fleetArrived;
    await page.route('**/technical/open-monitoring/dossiers/*/e8-fleet-selections/*', async route => {
      fleetRequests++;
      if (fleetMode==='DELAY') await new Promise(resolve => {
        releaseFleet=resolve;
        if (fleetArrived) fleetArrived();
      });
      if (fleetMode==='DENIED') return route.fulfill({status:404,json:{message:'Indisponível'}});
      return route.fulfill({json:{
        id:'60000000-0000-4000-8000-000000000122',projectId:project,
        period:fleetMode==='INVALID' ? 'M2' : 'M1',version:4,
        observationYear:fleetMode==='YEAR' ? 2026 : 2027,cycle:'COMBINED',
        stale:fleetMode==='STALE',state:fleetMode==='STALE' ? 'STALE_NOT_ADMITTED' : 'PREPARATORY_NOT_ADMITTED',
        usableForE8:fleetMode==='ADMITTED',publishedAt:'2026-11-23T12:00:00Z',
        models:['FLEET-PRIVATE-MODEL'],afecKwhPer100Miles:'23.456789',evrTotal:987654321,
        fleetSourceSha256:'SOURCE-HASH-NOT-FOR-CONTEXT',
      }});
    });
    let energyMode='OK';
    let releaseEnergy;
    let energyArrived;
    await page.route('**/technical/open-monitoring/dossiers/*/e8-input-bindings/*', async route => {
      if (energyMode==='DELAY') await new Promise(resolve => {
        releaseEnergy=resolve;
        if (energyArrived) energyArrived();
      });
      if (energyMode==='DENIED') return route.fulfill({status:404,json:{message:'Indisponível'}});
      const hours=energyMode==='EMPTY' ? [] : [
        {hour:'2027-02-01T00:00:00Z',edKwh:'2700',ecKwh:'3000',gridFactorTco2ePerKwh:'0.999999'},
        {hour:'2027-02-01T01:00:00Z',edKwh:'0',ecKwh:null,gridFactorTco2ePerKwh:'0.999999'},
      ];
      if (energyMode==='LARGE') {
        while (hours.length<25) hours.push({...hours[0]});
      }
      return route.fulfill({json:{
        id:'40000000-0000-4000-8000-000000000122',projectId:project,
        period:energyMode==='INVALID' ? 'M2' : 'M1',version:3,
        stale:energyMode==='STALE',state:energyMode==='STALE' ? 'STALE_NOT_ADMITTED' : 'CANDIDATE_NOT_ADMITTED',
        usableForE8:false,hourCount:hours.length,slotCount:hours.length,createdAt:'2026-11-23T12:00:00Z',hours,
      }});
    });
    let pinMode='OK';
    let releasePin;
    let pinCompleted;
    await page.route('**/technical/open-parameter-bindings/*', async route => {
      if (pinMode==='DELAY') {
        await new Promise(resolve => {releasePin=resolve;});
      }
      if (pinMode==='DENIED') return route.fulfill({status:404,json:{message:'Indisponível'}});
      await route.fulfill({json:{
        id:'50000000-0000-4000-8000-000000000122',projectId:project,parameterKind:'EF_FUEL',
        version:1,vintageYear:2027,methodology:'VM0038',methodologyEdition:'1.1',
        normalizedUnit:'tCO2e/USgal',conversionFormula:'identity',valueKind:'REFERENCE',
        state:pinMode==='STALE' ? 'STALE_NOT_ADMITTED' : 'PINNED_NOT_ADMITTED',
        stale:pinMode==='STALE',usableForE8:pinMode==='INVALID',
      }});
      if (pinCompleted) pinCompleted();
    });
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
        engine:'vm0038-open-grid-arithmetic@2-hourly',reviewStatus:'REVIEWED_INTERNAL',
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
    const memoryButton=page.getByRole('button', {name:'Conferir memória nesta seção'});
    const memoryPanel=page.getByRole('region', {name:'Memória canônica consultada'});
    const annualButton=page.getByRole('button', {name:'Conferir consolidação anual nesta seção'});
    const annualPanel=page.getByRole('region', {name:'Consolidação anual consultada'});
    assert.equal(annualRequests,0,'Annual projection must be explicitly requested');
    await annualButton.focus();
    await page.keyboard.press('Enter');
    await annualPanel.getByRole('heading',{name:'Consolidação atual · ano 1 do projeto'}).waitFor();
    for (const value of ['11.05','4.5','6.5550','6.56']) {
      await annualPanel.getByText(value+' tCO2e',{exact:true}).waitFor();
    }
    await annualPanel.getByText('Períodos e versões da consulta anual · 2').click();
    await annualPanel.getByRole('rowheader',{name:'M1 · desta seção'}).waitFor();
    await annualPanel.getByRole('rowheader',{name:'M2',exact:true}).waitFor();
    await annualPanel.getByText(yearlyHash,{exact:false}).waitFor();
    assert.equal(await annualPanel.getByText('PRIVATE-ANNUAL-RATING',{exact:false}).count(),0);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    annualMode='BLOCKED';
    await annualButton.click();
    await annualPanel.getByText('Total anual indisponível. Ausência não é zero.',{exact:true}).waitFor();
    await annualPanel.getByText('Referência: COVERAGE_GAP',{exact:true}).waitFor();
    assert.equal(await annualPanel.getByText('6.56 tCO2e',{exact:true}).count(),0);
    for (const mode of ['REPLACED','VERSION','HASH','DUPLICATE']) {
      annualMode=mode;
      await annualButton.click();
      await page.getByRole('alert').filter({hasText:'não contém a tentativa exata'}).waitFor();
      assert.equal(await annualPanel.count(),0);
    }
    for (const mode of ['PROJECT','YEAR','ADMITTED','ISSUED','MISMATCH','STALE','BLOCKED_TOTAL']) {
      annualMode=mode;
      await annualButton.click();
      await page.getByRole('alert').filter({hasText:'Não foi possível conferir a consolidação anual'}).waitFor();
      assert.equal(await annualPanel.count(),0);
    }
    annualMode='DENIED';
    await annualButton.click();
    await page.getByRole('alert').filter({hasText:'Consolidação anual protegida ou indisponível'}).waitFor();
    assert.equal(await annualPanel.count(),0);
    annualMode='DELAY';
    const annualArrival=new Promise(resolve=>{annualArrived=resolve;});
    await annualButton.click();
    await annualArrival;
    const annualResponse=page.waitForResponse(response=>response.url().includes('/open-calculation-years/'));
    await memoryButton.click();
    await memoryPanel.getByRole('heading',{name:'Resultados registrados pelo motor · M1'}).waitFor();
    releaseAnnual();
    await annualResponse;
    assert.equal(await annualPanel.count(),0);
    assert.equal(await page.getByText('Conferindo a consolidação anual autorizada…',{exact:true}).count(),0);
    const timing=memory.provenance.timing;
    memory.provenance.timing=null;
    await page.getByRole('button',{name:'Atualizar seções'}).click();
    await page.getByText('Ano do projeto não registrado nesta tentativa.',{exact:false}).waitFor();
    assert.equal(await annualButton.count(),0);
    memory.provenance.timing=timing;
    await page.getByRole('button',{name:'Atualizar seções'}).click();
    await annualButton.waitFor();
    await memoryButton.focus();
    await page.keyboard.press('Enter');
    await memoryPanel.getByRole('heading', {name:'Resultados registrados pelo motor · M1'}).waitFor();
    await memoryPanel.getByText('2.5500 tCO2e', {exact:true}).waitFor();
    await memoryPanel.getByText('PE total registrado: 1.5 tCO2e.', {exact:false}).waitFor();
    await memoryPanel.getByRole('cell', {name:'0.5',exact:true}).waitFor();
    await memoryPanel.getByRole('cell', {name:'1',exact:true}).waitFor();
    for (const secret of ['23.456789','31.234567','PRIVATE-RATING-STEP','0.999999'])
      assert.equal(await memoryPanel.getByText(secret, {exact:false}).count(),0);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1),false);
    memoryMode='NO_HOURS';
    await memoryButton.click();
    await memoryPanel.getByText('Sem memória horária registrada nesta tentativa.', {exact:false}).waitFor();
    memoryMode='EMPTY';
    await memoryButton.click();
    await memoryPanel.getByText('Sem parcelas horárias nesta resposta. Ausência não é zero.', {exact:true}).waitFor();
    memoryMode='LARGE';
    await memoryButton.click();
    await memoryPanel.getByText('Esta prévia limita PE a 24 horas.', {exact:false}).waitFor();
    assert.equal(await memoryPanel.getByRole('table').last().locator('tbody tr').count(),24);
    memoryMode='STALE';
    await memoryButton.click();
    await page.getByRole('alert').filter({hasText:'Memória indisponível ou desatualizada'}).waitFor();
    assert.equal(await memoryPanel.count(),0);
    memoryMode='DENIED';
    await memoryButton.click();
    await page.getByRole('alert').filter({hasText:'Memória protegida ou indisponível'}).waitFor();
    assert.equal(await memoryPanel.count(),0);
    for (const mode of ['HASH','MISMATCH','ADMITTED']) {
      memoryMode=mode;
      await memoryButton.click();
      await page.getByRole('alert').filter({hasText:'Não foi possível conferir a memória desta tentativa'}).waitFor();
      assert.equal(await memoryPanel.count(),0);
    }
    await page.getByText('Conferir origem, unidades e revisão da memória', {exact:true}).click();
    await page.getByText('vm0038-open-grid-arithmetic@2-hourly', {exact:true}).waitFor();
    await page.getByText('VM0038@1.1', {exact:true}).waitFor();
    await page.getByText('BE · VM0038-1.1-EQ1 · tCO2e', {exact:true}).waitFor();
    await page.getByText('Referência protegida ou indisponível', {exact:true}).waitFor();
    await page.getByText('Unidade normalizada: tCO2e/USgal. Vintage: 2027.').waitFor();
    assert.equal(await page.getByRole('link', {name:'Consultar origem de ED'}).getAttribute('href'),
      memory.provenance.inputs[0].href);
    assert.equal(await page.getByRole('link', {name:'Consultar origem de EVR'}).count(), 0);
    const fleetButton=page.getByRole('button', {name:'Conferir seleção de frota nesta seção'});
    const fleetPanel=page.getByRole('region', {name:'Seleção de frota consultada'});
    assert.equal(await fleetButton.count(),0);
    assert.equal(fleetRequests,0);
    memory.provenance.inputs[2]={symbol:'EVR',origin:'Estoque EVR · seleção de frota e ratings',
      unit:'veículos',vintageYear:2027,referenceId:'60000000-0000-4000-8000-000000000122',version:4,
      href:'/v1/projects/'+project+'/technical/open-monitoring/dossiers/M1/e8-fleet-selections/60000000-0000-4000-8000-000000000122',
      access:'PROJECT_METADATA_ONLY'};
    await page.getByRole('button', {name:'Atualizar seções'}).click();
    await fleetButton.waitFor();
    await fleetButton.focus();
    await page.keyboard.press('Enter');
    await fleetPanel.getByRole('heading', {name:'Frota e ratings por referência · M1'}).waitFor();
    await fleetPanel.getByText('COMBINED', {exact:true}).waitFor();
    await fleetPanel.getByText('2027', {exact:true}).waitFor();
    await fleetPanel.getByText('Seleção preparatória, sem admissão normativa', {exact:false}).waitFor();
    for (const secret of ['FLEET-PRIVATE-MODEL','23.456789','987654321','SOURCE-HASH-NOT-FOR-CONTEXT'])
      assert.equal(await page.getByText(secret, {exact:false}).count(),0);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1),false);
    fleetMode='STALE';
    await fleetButton.click();
    await fleetPanel.getByText('Seleção desatualizada — requer rechecagem', {exact:false}).waitFor();
    fleetMode='DENIED';
    await fleetButton.click();
    await page.getByRole('alert').filter({hasText:'Seleção de frota protegida ou indisponível'}).waitFor();
    assert.equal(await fleetPanel.count(),0);
    for (const mode of ['INVALID','YEAR','ADMITTED']) {
      fleetMode=mode;
      await fleetButton.click();
      await page.getByRole('alert').filter({hasText:'Não foi possível conferir a seleção de frota'}).waitFor();
      assert.equal(await fleetPanel.count(),0);
    }
    fleetMode='DELAY';
    const fleetArrival=new Promise(resolve => {fleetArrived=resolve;});
    await fleetButton.click();
    await fleetArrival;
    const fleetResponse=page.waitForResponse(response => response.url().includes('/e8-fleet-selections/'));
    await page.getByRole('button', {name:'Conferir energia ED/EC nesta seção'}).click();
    await page.getByRole('region', {name:'Energia do vínculo consultado'}).waitFor();
    releaseFleet();
    await fleetResponse;
    assert.equal(await fleetPanel.count(),0);
    memoryMode='DELAY';
    const memoryArrival=new Promise(resolve => {memoryArrived=resolve;});
    await memoryButton.click();
    await memoryArrival;
    const memoryResponse=page.waitForResponse(response => response.url().includes('/e8-runs/'));
    await page.getByRole('button', {name:'Conferir energia ED/EC nesta seção'}).click();
    await page.getByRole('region', {name:'Energia do vínculo consultado'}).waitFor();
    releaseMemory();
    await memoryResponse;
    assert.equal(await memoryPanel.count(),0);
    const energyButton=page.getByRole('button', {name:'Conferir energia ED/EC nesta seção'});
    const energyPanel=page.getByRole('region', {name:'Energia do vínculo consultado'});
    await energyButton.focus();
    await page.keyboard.press('Enter');
    await energyPanel.getByRole('heading', {name:'Energia entregue e consumida · M1'}).waitFor();
    await energyPanel.getByRole('cell', {name:'2700',exact:true}).waitFor();
    await energyPanel.getByRole('cell', {name:'3000',exact:true}).waitFor();
    await energyPanel.getByRole('cell', {name:'0',exact:true}).waitFor();
    await energyPanel.getByRole('cell', {name:'Não registrada',exact:true}).waitFor();
    assert.equal(await energyPanel.getByText('0.999999', {exact:false}).count(),0);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1),false);
    energyMode='STALE';
    await energyButton.click();
    await energyPanel.getByText('Histórico desatualizado — requer rechecagem', {exact:false}).waitFor();
    energyMode='EMPTY';
    await energyButton.click();
    await energyPanel.getByText('Sem leituras nesta resposta. Ausência não é zero.', {exact:true}).waitFor();
    energyMode='LARGE';
    await energyButton.click();
    await energyPanel.getByText('Esta prévia limita a tabela a 24 horas.', {exact:false}).waitFor();
    assert.equal(await energyPanel.locator('tbody tr').count(),24);
    energyMode='DENIED';
    await energyButton.click();
    await page.getByRole('alert').filter({hasText:'Energia protegida ou indisponível'}).waitFor();
    assert.equal(await energyPanel.count(),0);
    energyMode='INVALID';
    await energyButton.click();
    await page.getByRole('alert').filter({hasText:'Não foi possível conferir o vínculo de energia'}).waitFor();
    assert.equal(await energyPanel.count(),0);
    energyMode='DELAY';
    const arrived=new Promise(resolve => {energyArrived=resolve;});
    await energyButton.click();
    await arrived;
    const energyResponse=page.waitForResponse(response => response.url().includes('/e8-input-bindings/'));
    await page.getByRole('button', {name:'Conferir pino EF_FUEL nesta seção'}).click();
    await page.getByRole('heading', {name:'Pino consultado · EF_FUEL'}).waitFor();
    releaseEnergy();
    await energyResponse;
    assert.equal(await energyPanel.count(),0);
    const pinLink=page.getByRole('link', {name:'Consultar origem de EF_FUEL'});
    assert.equal(await pinLink.getAttribute('href'),memory.provenance.inputs[1].href);
    await pinLink.focus();
    assert.equal(await pinLink.evaluate(element => document.activeElement === element),true);
    const pinButton=page.getByRole('button', {name:'Conferir pino EF_FUEL nesta seção'});
    await pinButton.focus();
    await page.keyboard.press('Enter');
    const panel=page.getByRole('region', {name:'Detalhe do pino consultado'});
    await panel.getByRole('heading', {name:'Pino consultado · EF_FUEL'}).waitFor();
    await panel.getByText('Vinculado, sem admissão normativa', {exact:false}).waitFor();
    await panel.getByText('identity', {exact:true}).waitFor();
    assert.equal(await panel.getByText('0.007570823568', {exact:false}).count(),0);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1),false);
    pinMode='STALE';
    await pinButton.click();
    await panel.getByText('Desatualizado — requer rechecagem', {exact:false}).waitFor();
    pinMode='DENIED';
    await pinButton.click();
    await page.getByRole('alert').filter({hasText:'Detalhe protegido ou indisponível'}).waitFor();
    assert.equal(await panel.count(),0);
    pinMode='INVALID';
    await pinButton.click();
    await page.getByRole('alert').filter({hasText:'Não foi possível conferir o pino'}).waitFor();
    assert.equal(await panel.count(),0);
    pinMode='DELAY';
    await pinButton.click();
    await page.getByText('Conferindo o pino autorizado…', {exact:true}).waitFor();
    const completed=new Promise(resolve => {pinCompleted=resolve;});
    const responseSeen=page.waitForResponse(response => response.url().includes('/open-parameter-bindings/'));
    await page.getByRole('button', {name:'Atualizar seções'}).click();
    await page.getByRole('button', {name:'Atualizar seções'}).waitFor({state:'visible'});
    releasePin();
    await completed;
    await responseSeen;
    assert.equal(await panel.count(),0);
    assert.equal(await page.getByText('Conferindo o pino autorizado…', {exact:true}).count(),0);
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
    assert.equal(await annualButton.count(),0);
    assert.equal(await annualPanel.count(),0);
    assert.equal(await page.getByText('vm0038-open-grid-arithmetic@2-hourly', {exact:true}).count(), 0);
    situation = 'RESTRICTED';
    await page.getByRole('button', {name:'Atualizar seções'}).click();
    await page.getByText('Memória protegida.', {exact:false}).waitFor();
    assert.equal(await memoryLink.count(), 0);
    assert.equal(await page.getByText('4.05 tCO2e').count(), 0);
    assert.equal(await page.locator('#pd-narrative').inputValue(), '');
    assert.equal(await annualButton.count(),0);
    assert.equal(await annualPanel.count(),0);
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
