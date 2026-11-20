import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const project='20000000-0000-4000-8000-000000000001';
const root='/v1/projects/'+project;
const profile={id:'VM0038_V1_1',candidate:'VM0038',title:'VM0038 v1.1',
  description:'Recarga',technicalTopics:[]};

test('UX-03A: duas estações com DCFC separados e seleção de fronteira persiste no mobile',async()=>{
  const browser=await chromium.launch({executablePath});
  try{
    const page=await browser.newPage({viewport:{width:390,height:844}});
    const sites=[],chargers=[];
    let boundary={version:0,selected:[],state:'NO_SELECTION',
      findings:['Nenhum carregador está na fronteira.'],usableForE8:false};
    await page.route('**'+root+'/metadata',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',organizationId:'10000000-0000-4000-8000-000000000001',
      title:'Rede aberta',state:'DRAFT',version:1}}));
    await page.route('**'+root+'/technical/capabilities',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',owner:true,
      actions:['VIEW_INTAKE','VIEW_OPEN_MONITORING','SAVE_OPEN_MONITORING'],modules:{}}}));
    await page.route('**'+root+'/technical/intake',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',version:1,state:'RASCUNHO',
      reviewState:'NAO_AVALIADO',data:{candidateMethodology:'VM0038'},
      journey:profile,methodologies:[],outstandingGates:[],triageCandidate:'VM0038'
    }}));
    await page.route('**'+root+'/technical/open-sites',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',sites,chargers,boundary
    }}));
    await page.route('**'+root+'/technical/open-sites/sites/*',r=>{
      assert.equal(r.request().method(),'PUT');
      const code=r.request().url().split('/').at(-1);
      const details=r.request().postDataJSON();
      assert.equal(r.request().headers()['if-match'],'"0"');
      assert.ok(r.request().headers()['idempotency-key']);
      assert.ok(details.name.length>3);
      assert.equal(details.operationalState,'PLANNED');
      assert.equal(details.state,'DF');
      const site={code,version:1,details,recordedAt:'2026-11-20T00:00:00Z'};
      sites.push(site);return r.fulfill({json:site});
    });
    await page.route('**'+root+'/technical/open-monitoring/chargers/*',r=>{
      assert.equal(r.request().method(),'PUT');
      assert.equal(r.request().headers()['if-match'],'"0"');
      const input=r.request().postDataJSON();
      assert.equal(input.chargerKind,'DCFC');assert.equal(input.grid,'SIN');
      assert.equal(input.topology,'GRID_ONLY');
      assert.ok(sites.some(x=>x.code===input.siteCode));
      const charger={code:r.request().url().split('/').at(-1),version:1,
        siteCode:input.siteCode,kind:input.chargerKind,powerKw:input.ratedPowerKw,
        grid:input.grid,topology:input.topology};
      chargers.push(charger);return r.fulfill({json:charger});
    });
    await page.route('**'+root+'/technical/open-sites/boundary',r=>{
      assert.equal(r.request().method(),'PUT');
      assert.equal(r.request().headers()['if-match'],'"0"');
      assert.ok(r.request().headers()['idempotency-key']);
      const data=r.request().postDataJSON();
      assert.equal(data.chargers.length,2);
      assert.ok(data.chargers.every(x=>x.version===1));
      boundary={version:1,selected:data.chargers.map(x=>{
        const charger=chargers.find(ch=>ch.code===x.code);
        return {chargerCode:charger.code,chargerVersion:1,siteCode:charger.siteCode,siteVersion:1};
      }),state:'DRAFT_REVIEW_REQUIRED',findings:['Revisão pendente'],usableForE8:false};
      return r.fulfill({json:boundary});
    });

    await page.goto(base+'/workspace/projects/'+project+'/technical?secao=estacoes');
    await page.getByRole('heading',{name:'Estações e fronteira'}).waitFor();
    await page.getByLabel('Código único da estação').fill('ST-01');
    await page.getByLabel('Nome da estação').fill('Estação Norte');
    await page.getByLabel('Município').fill('Brasília');
    await page.getByLabel('Endereço (opcional no rascunho)').fill('Via Norte, 100');
    await page.getByLabel('Referência documental (não é anexo validado)').fill('DOC-N-1');
    await page.getByRole('button',{name:'Salvar versão da estação'}).click();
    await page.getByText('Estação cadastrada em nova revisão.',{exact:false}).waitFor();

    await page.getByLabel('Código único da estação').fill('ST-02');
    await page.getByLabel('Nome da estação').fill('Estação Sul');
    await page.getByLabel('Município').fill('Brasília');
    await page.getByLabel('Endereço (opcional no rascunho)').fill('Via Sul, 200');
    await page.getByLabel('Referência documental (não é anexo validado)').fill('DOC-S-2');
    await page.getByRole('button',{name:'Salvar versão da estação'}).click();
    assert.equal(sites.length,2);

    await page.getByRole('button',{name:'2. Carregadores DCFC'}).click();
    await page.getByLabel('Identificador do equipamento').fill('CH-01');
    await page.getByLabel('Estação vinculada').selectOption('ST-01');
    await page.getByLabel('Potência nominal (kW) — pode ficar pendente').fill('150');
    await page.getByLabel('Referência técnica da ficha ou comissionamento').fill('DOC-C-1');
    await page.getByRole('button',{name:'Salvar carregador'}).click();
    await page.getByText('Carregador DCFC registrado no inventário canônico.',{exact:false}).waitFor();

    await page.getByLabel('Identificador do equipamento').fill('CH-02');
    await page.getByLabel('Estação vinculada').selectOption('ST-02');
    await page.getByLabel('Potência nominal (kW) — pode ficar pendente').fill('180');
    await page.getByLabel('Referência técnica da ficha ou comissionamento').fill('DOC-C-2');
    await page.getByRole('button',{name:'Salvar carregador'}).click();
    assert.equal(chargers.length,2);
    assert.notEqual(chargers[0].powerKw,chargers[1].powerKw);

    await page.getByRole('button',{name:'3. Fronteira'}).click();
    await page.getByLabel(/CH-01 · ST-01/).check();
    await page.getByLabel(/CH-02 · ST-02/).check();
    await page.getByRole('button',{name:'Salvar seleção versionada'}).click();
    await page.getByText('Fronteira versionada salva.',{exact:false}).waitFor();
    assert.equal(boundary.version,1);
    assert.equal(boundary.selected.length,2);
    assert.equal(boundary.usableForE8,false);
    await page.reload();
    await page.getByRole('button',{name:'3. Fronteira'}).click();
    assert.equal(await page.getByLabel(/CH-01 · ST-01/).isChecked(),true);
    assert.equal(await page.getByLabel(/CH-02 · ST-02/).isChecked(),true);
    assert.equal(await page.getByRole('button',{name:'Salvar seleção versionada'}).isDisabled(),true);
  }finally{await browser.close();}
});
