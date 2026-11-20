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
    const boundary=page.getByLabel('Estações na fronteira');
    try { await boundary.waitFor({timeout:5000}); }
    catch(error) {
      console.log('UX-07 legacy guide diagnostics:',(await page.locator('body').innerText()).slice(0,1800));
      throw error;
    }
    await boundary.fill('Estação de Brasília');
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

test('guia mostra a referência da regra, a edição consultada e o fallback após revogação', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const route = '/v1/projects/' + projectId;
    let edition = 1;
    let authorized = true;
    let related = true;
    const guide = {id:'baseline', title:'Linha de base', guidance:'Descreva a hipótese de referência.',
      fields:[{key:'scenario',label:'Cenário',type:'text',options:[]}],
      version:1, answers:{scenario:'Comparável inicial'}, missing:[], editable:false};
    const requirement = {code:'DEMO-PD-02',title:'Revisar a hipótese de linha de base do exemplo',
      field:'scenario',classification:'INDETERMINATE',status:'REVIEW_REQUIRED',reason:'Exige parecer humano.',
      dependencies:[],source:{id:'CF-ECA-DEMO',edition:'1'},locator:'DEMO-03',interpretation:true};
    await page.route('**' + route + '/metadata', r => r.fulfill({json:{projectId,environment:'SANDBOX',title:'Frota'}}));
    await page.route('**' + route + '/technical/capabilities', r => r.fulfill({json:{
      projectId,environment:'SANDBOX',owner:false,actions:['VIEW_GUIDES'],modules:{}
    }}));
    await page.route('**' + route + '/technical/guides', r => r.fulfill({json:{methodology:'VM0038_V1_1',guides:[guide]}}));
    await page.route('**' + route + '/sections/baseline/readiness?act=PD', r => related
      ? r.fulfill({json:{guideRevision:1,catalogState:authorized ? 'RESOLVED_SYNTHETIC' : 'SOURCE_USAGE_BLOCKED',
          catalogReason:'A autorização de uso precisa de revisão.', dateBasis:'ACT_DATE',dateUsed:'2029-09-01',
          window: authorized ? {from:'2020-01-01',until:'2030-01-01'} : null,
          packId:authorized ? 'CF-DEMO-'+edition : null,packEdition:authorized ? edition : null,
          requirements:authorized ? [requirement] : [],gates:[],acquisitionPlan:[]}})
      : r.fulfill({status:404,body:''}));
    await page.route('**' + route + '/source-policy', r => r.fulfill({json:{sources:[
      {id:'VM0038',edition:'1.1',category:'METHODOLOGY',title:'VM0038',rightsHolder:'Verra',
        origin:'EXTERNAL',officialUri:'https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/',
        requirementReferenceAvailable:false}
    ]}}));

    await page.goto(base + '/workspace/projects/' + projectId + '/technical?secao=guias&guia=baseline');
    await page.getByText('Requisitos e fontes desta etapa',{exact:true}).click();
    const check = page.getByRole('button',{name:'Consultar ou atualizar referências'});
    await check.click();
    await page.getByText('Avaliação humana pendente',{exact:true}).waitFor();
    assert.equal(await page.getByRole('link',{name:'Localizar campo no guia'}).getAttribute('href'),'#guide-scenario');
    await page.getByText('Glossário técnico',{exact:true}).click();
    await page.getByText('Emissões estimadas no cenário de referência',{exact:false}).waitFor();

    edition = 2;
    await check.click();
    await page.getByText('A edição consultada mudou da 1 para a 2',{exact:false}).waitFor();
    authorized = false;
    await check.click();
    await page.getByText('A autorização de uso precisa de revisão.',{exact:false}).waitFor();
    assert.equal(await page.getByText(requirement.title,{exact:true}).count(),0);
    await page.getByRole('link',{name:'Consultar origem de VM0038'}).waitFor();
    related = false;
    await check.click();
    await page.getByRole('alert').filter({hasText:'indisponíveis para esta conta'}).waitFor();
    assert.equal(await page.getByRole('link',{name:'Consultar origem de VM0038'}).count(),0);
  } finally { await browser.close(); }
});

test('registra solicitação a partir de lacuna e preserva a natureza da resposta', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const root = '/v1/projects/' + projectId;
    const planUrl = root + '/sections/baseline/acquisition/PD';
    const guide = {id:'baseline',title:'Linha de base',guidance:'Registre o cenário.',
      fields:[{key:'scenario',label:'Cenário',type:'text',options:[]}],
      version:0,answers:{},missing:['Cenário'],editable:true};
    let item;
    await page.route('**' + root + '/metadata', r => r.fulfill({json:{projectId,environment:'SANDBOX',title:'Frota'}}));
    await page.route('**' + root + '/technical/capabilities', r => r.fulfill({json:{
      projectId,environment:'SANDBOX',owner:false,actions:['VIEW_GUIDES','SAVE_GUIDE'],modules:{}
    }}));
    await page.route('**' + root + '/technical/guides', r => r.fulfill({json:{methodology:'VM0038_V1_1',guides:[guide]}}));
    await page.route('**' + planUrl, r => r.fulfill({json:{
      available:[{field:'scenario',label:'Cenário'}],requests:item?[item]:[],recipients:[],canCreate:true,
    }}));
    await page.route('**' + planUrl + '/scenario', r => {
      assert.equal(r.request().headers()['if-match'],'"0"');
      const setup = r.request().postDataJSON();
      assert.equal(setup.purpose,'Confirmar cenário de referência');
      assert.equal(setup.criteria.period,'2029');
      item = {request:{id:'request',act:'PD',field:'scenario'},canPlan:true,canReply:true,
        revision:{number:1,status:'OPEN',plan:{setup,label:'Cenário',reason:'Lacuna no guia'},reply:null}};
      return r.fulfill({json:item});
    });
    await page.route('**' + root + '/evidence-requests/request/replies', r => {
      assert.equal(r.request().headers()['if-match'],'"1"');
      const reply = r.request().postDataJSON();
      assert.equal(reply.nature,'ESTIMATED');
      item.revision = {...item.revision,number:2,status:reply.status,reply};
      return r.fulfill({json:item});
    });
    await page.goto(base + '/workspace/projects/' + projectId + '/technical?secao=guias&guia=baseline');
    await page.getByText('Solicitações de dados',{exact:true}).click();
    await page.getByRole('button',{name:'Consultar solicitações'}).click();
    await page.getByLabel('Lacuna para solicitar').selectOption('scenario');
    await page.getByRole('button',{name:'Planejar solicitação'}).click();
    await page.getByLabel('Finalidade').fill('Confirmar cenário de referência');
    await page.getByText('Critérios de aceite do dado',{exact:true}).click();
    await page.getByLabel('Período',{exact:true}).fill('2029');
    await page.getByRole('button',{name:'Salvar solicitação'}).click();
    await page.getByText('Aguardando resposta',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Responder',exact:true}).click();
    await page.getByLabel('Resposta',{exact:true}).selectOption('PARTIAL');
    await page.getByLabel('Natureza da informação parcial').selectOption('ESTIMATED');
    await page.getByLabel('Observações da resposta').fill('Fonte preliminar.');
    await page.getByRole('button',{name:'Registrar resposta'}).click();
    await page.getByText('Natureza informada · Estimada',{exact:true}).waitFor();
  } finally { await browser.close(); }
});

test('revisor decide uma proposta de fato cuja origem está fixada', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const root = '/v1/projects/' + projectId;
    const facts = root + '/sections/baseline/facts';
    const guide = {id:'baseline',title:'Linha de base',guidance:'Registre o cenário.',
      fields:[{key:'scenario',label:'Cenário',type:'text',options:[]}],
      version:2,answers:{scenario:'Referência corrigida'},missing:[],editable:false};
    let candidate = {id:'proposal',field:'scenario',value:'Referência corrigida',factVersion:null,currentVersion:1,
      divergent:true,canReview:true,requestId:null,review:null,
      evidence:{id:'source',guideVersion:2,sha256:'f'.repeat(64),locator:'/answers/scenario'}};
    await page.route('**' + root + '/metadata', r => r.fulfill({json:{projectId,environment:'SANDBOX',title:'Frota'}}));
    await page.route('**' + root + '/technical/capabilities', r => r.fulfill({json:{
      projectId,environment:'SANDBOX',owner:false,actions:['VIEW_GUIDES'],modules:{}
    }}));
    await page.route('**' + root + '/technical/guides', r => r.fulfill({json:{methodology:'VM0038_V1_1',guides:[guide]}}));
    await page.route('**' + facts, r => r.fulfill({json:{candidates:[candidate],canPropose:false}}));
    await page.route('**' + facts + '/proposal/decision', r => {
      assert.equal(r.request().headers()['if-match'],'"1"');
      const review = r.request().postDataJSON();
      assert.deepEqual(review,{outcome:'ACCEPTED',reason:'Origem conferida.'});
      candidate = {...candidate,review,factVersion:2,currentVersion:2,canReview:false};
      return r.fulfill({json:candidate});
    });
    await page.goto(base + '/workspace/projects/' + projectId + '/technical?secao=guias&guia=baseline');
    await page.getByText('Evidências e fatos',{exact:true}).click();
    await page.getByRole('button',{name:'Consultar propostas de fatos'}).click();
    await page.getByText('Divergência com o fato vigente',{exact:false}).waitFor();
    await page.getByText('Origem versionada',{exact:true}).click();
    await page.getByText('/answers/scenario',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Registrar parecer'}).click();
    await page.getByLabel('Motivo do parecer').fill('Origem conferida.');
    await page.getByRole('button',{name:'Confirmar parecer'}).click();
    await page.getByText('Fato interno vigente · versão 2',{exact:true}).waitFor();
  } finally { await browser.close(); }
});

test('formulário versionado rejeita ano inválido e preserva revisão e cabeçalho do schema', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const root = '/v1/projects/' + projectId;
    let saves = 0;
    const guide = {id:'quantification',title:'Quantificação',guidance:'Ano de referência.',
      definitionVersion:2,version:0,answers:{},legacyUnstructured:[],missing:['Ano de referência'],editable:true,
      fields:[{key:'vintage',label:'Ano de referência',type:'year',options:[],
        helpText:'Informe o ano do período analisado, sem converter uma data em prova.'}]};
    await page.route('**' + root + '/metadata', r => r.fulfill({json:{projectId,environment:'SANDBOX',title:'Recarga'}}));
    await page.route('**' + root + '/technical/capabilities', r => r.fulfill({json:{
      projectId,environment:'SANDBOX',owner:false,actions:['VIEW_GUIDES','SAVE_GUIDE'],modules:{}
    }}));
    await page.route('**' + root + '/technical/guides', r => r.fulfill({json:{methodology:'VM0038_V1_1',guides:[guide]}}));
    await page.route('**' + root + '/technical/guides/quantification', r => {
      saves++;
      assert.equal(r.request().headers()['if-match'],'"0"');
      assert.equal(r.request().headers()['x-guide-definition-version'],'2');
      assert.deepEqual(r.request().postDataJSON(),{vintage:'2027'});
      return r.fulfill({json:{...guide,version:1,answers:{vintage:'2027'},missing:[]}});
    });
    await page.goto(base + '/workspace/projects/' + projectId + '/technical?secao=guias&guia=quantification');
    await page.getByLabel('Ano de referência').fill('20xx');
    await page.getByRole('button',{name:'Salvar guia'}).click();
    await page.getByText('Corrija os campos destacados',{exact:false}).waitFor();
    assert.equal(saves,0);
    await page.getByLabel('Ano de referência').fill('2027');
    await page.getByRole('button',{name:'Salvar guia'}).click();
    await page.getByText('Revisão 1 salva.').waitFor();
    assert.equal(saves,1);
  } finally { await browser.close(); }
});
