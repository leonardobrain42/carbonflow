import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const project='20000000-0000-4000-8000-000000000001';
const root='/v1/projects/'+project;
const field=(key,label,type,options=[],more={})=>({
  key,label,type,options,helpText:'Declaração preliminar sem aprovação',
  required:false,showWhenField:null,showWhenValue:null,requiredWhenField:null,
  requiredWhenValue:null,unit:null,min:null,max:null,optionParents:{},...more
});
test('UX-02B: guia tipado condicional, refs versionadas, boolean real, rascunho e recarga mobile',async()=>{
  const browser=await chromium.launch({executablePath});
  try{
    const page=await browser.newPage({viewport:{width:390,height:844}});
    let revision=0,answers={},puts=0;
    const fields=[
      field('incidence','Incidência candidata do critério','ENUM',
        ['A confirmar','Aplicável','Não aplicável']),
      field('justification','Justificativa da não aplicabilidade','TEXTAREA',[],{
        showWhenField:'incidence',showWhenValue:'Não aplicável',
        requiredWhenField:'incidence',requiredWhenValue:'Não aplicável'
      }),
      field('stationCode','Estação já cadastrada','STATION_REF',['ST-01']),
      field('chargerCode','Equipamento da estação','EQUIPMENT_REF',['CH-01'],{
        showWhenField:'stationCode',showWhenValue:'SET',optionParents:{'CH-01':'ST-01'}
      }),
      field('unrestrictedAccess','Recarga aberta a qualquer BEV compatível?','BOOLEAN')
    ];
    const current=()=>({
      id:'applicability',title:'Aplicabilidade e fronteira',guidance:'Formulário guiado',
      methodology:'VM0038_V1_1',edition:'1.1',branch:'OPEN_BR_BEV_LDV_DCFC_SIN',
      definitionVersion:1,version:revision,fields,answers,legacyAnswers:{boundary:'texto antigo'},
      legacyNeedsReview:revision?[]:['boundary'],missing:[],editable:true,
      state:revision?'FORM_COMPLETED_DECLARED':'LEGACY_UNSTRUCTURED_REVIEW_REQUIRED',
      normativeApproved:false
    });
    await page.route('**'+root+'/metadata',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',title:'Rede aberta',version:1,state:'DRAFT',
      organizationId:'10000000-0000-4000-8000-000000000001'}}));
    await page.route('**'+root+'/technical/capabilities',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',owner:true,
      actions:['VIEW_INTAKE','VIEW_GUIDES','SAVE_GUIDE'],modules:{}
    }}));
    await page.route('**'+root+'/technical/intake',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',version:1,state:'RASCUNHO',
      reviewState:'NAO_AVALIADO',data:{candidateMethodology:'VM0038'},
      journey:{id:'VM0038_V1_1',candidate:'VM0038',title:'VM0038',description:'Projeto',technicalTopics:[]},
      methodologies:[],outstandingGates:[],triageCandidate:'VM0038'
    }}));
    await page.route('**'+root+'/technical/guides',r=>r.fulfill({json:{
      methodology:'VM0038_V1_1',
      guides:[{id:'applicability',title:'Aplicabilidade e fronteira',guidance:'Anotação',
        fields:[{key:'boundary',label:'Texto legado da fronteira',type:'text',options:[]}],
        version:1,definitionVersion:2,answers:{boundary:'texto antigo'},missing:[],editable:true}]
    }}));
    await page.route('**'+root+'/technical/typed-guides',r=>r.fulfill({json:{
      methodology:'VM0038_V1_1',guides:[current()]
    }}));
    await page.route('**'+root+'/technical/typed-guides/applicability',r=>{
      assert.equal(r.request().headers()['if-match'],'"'+revision+'"');
      assert.equal(r.request().headers()['x-guide-definition-version'],'1');
      assert.ok(r.request().headers()['idempotency-key']);
      puts++;answers=r.request().postDataJSON();revision++;
      if(revision===1){
        assert.equal(answers.incidence,'Não aplicável');
        assert.equal(answers.justification,'Avaliação interna pendente');
        assert.equal(answers.stationCode,'ST-01');
        assert.equal(answers.chargerCode,'CH-01');
        assert.equal(answers.unrestrictedAccess,true);
      }else{
        assert.equal(answers.incidence,'Aplicável');
        assert.equal('justification' in answers,false);
      }
      return r.fulfill({json:current()});
    });
    await page.goto(base+'/workspace/projects/'+project+'/technical?secao=guias&guia=applicability');
    await page.getByRole('heading',{name:'Formulário estruturado · 1.1'}).waitFor();
    const justification=page.getByLabel('Justificativa da não aplicabilidade');
    assert.equal(await justification.count(),0);
    await page.getByLabel('Incidência candidata do critério').selectOption('Não aplicável');
    assert.equal(await justification.count(),1);
    assert.equal(await page.getByText('Contexto desta resposta',{exact:true}).count(),1);
    assert.equal(await page.getByText('VM0038_V1_1 · 1.1 · definição v1').isVisible(),false);
    await page.getByText('Contexto desta resposta',{exact:true}).click();
    await page.getByText('VM0038_V1_1 · 1.1 · definição v1').first().waitFor();
    await page.getByText('não designa uma pessoa nem cria permissão.').first().waitFor();
    await page.getByText('não é copiada para a versão do documento.').first().waitFor();
    await page.getByText('Declaração desta revisão. Não é medição, contrato nem parecer.').first().waitFor();
    assert.equal(await page.getByLabel('Incidência candidata do critério').inputValue(),'Não aplicável');
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await page.getByLabel('Estação já cadastrada').selectOption('ST-01');
    await page.getByLabel('Equipamento da estação').selectOption('CH-01');
    await page.getByLabel('Recarga aberta a qualquer BEV compatível?').selectOption('true');
    await page.getByRole('button',{name:'Salvar revisão estruturada'}).click();
    await page.getByText('Corrija os campos assinalados',{exact:false}).waitFor();
    assert.equal(puts,0);
    await justification.fill('Avaliação interna pendente');
    await page.getByRole('button',{name:'Salvar revisão estruturada'}).click();
    await page.getByText('Revisão estruturada 1 salva',{exact:false}).waitFor();
    assert.equal(puts,1);
    assert.equal(await page.getByRole('button',{name:'Salvar guia'}).count(),0);
    assert.equal(await page.getByLabel('Texto legado da fronteira').count(),0);
    await page.getByText('Histórico textual anterior (não conclui esta informação)').click();
    await page.getByText('não são a resposta tipada').waitFor();
    await page.getByText('texto antigo').waitFor();
    // The summary click schedules a render. Wait for it before changing the select,
    // otherwise a late pass can restore the previous option and keep the conditional field.
    await page.evaluate(() => new Promise(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.getByLabel('Incidência candidata do critério').selectOption('Aplicável');
    await page.waitForFunction(() => {
      const select = document.querySelector('#typed-incidence');
      return select instanceof HTMLSelectElement && select.value === 'Aplicável'
        && !document.querySelector('#typed-justification');
    });
    await page.getByRole('button',{name:'Salvar revisão estruturada'}).click();
    await page.getByText('Revisão estruturada 2 salva',{exact:false}).waitFor();
    assert.equal(puts,2);
    await page.reload();
    await page.getByRole('heading',{name:'Formulário estruturado · 1.1'}).waitFor();
    assert.equal(await page.getByLabel('Incidência candidata do critério').inputValue(),'Aplicável');
    assert.equal(await page.getByLabel('Recarga aberta a qualquer BEV compatível?').inputValue(),'true');
    assert.equal(await justification.count(),0);
    assert.equal(await page.getByText('Campos estruturados informados — sem aprovação').count(),1);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await page.setViewportSize({width:1440,height:900});
    await page.getByRole('heading',{name:'Formulário estruturado · 1.1'}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Salvar guia'}).count(),0);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  }finally{await browser.close();}
});
