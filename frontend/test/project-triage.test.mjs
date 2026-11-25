import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const projectId = '20000000-0000-4000-8000-000000000010';
const organizationId = '10000000-0000-4000-8000-000000000010';
const labels = ['Objetivo e metodologia','Atividade','Escopo e lacunas','Local','Resumo da triagem'];
const vmTitle = 'VM0038 v1.1 · Recarga de veículos elétricos';
const next = [
  {section:'rede',title:'Acesso à rede',detail:'Acesso aos carregadores, identificação da frota, origem da eletricidade e infraestrutura associada.'},
  {section:'estacoes',title:'Estações e fronteira',detail:'Estações, carregadores e veículos do projeto.'},
  {section:'monitorar',title:'Monitorar energia',detail:'Medidores e medição da energia entregue e consumida.'},
  {section:'linha-base',title:'Linha de base',detail:'Cenário sem o projeto e veículos comparáveis.'},
  {section:'adicionalidade',title:'Adicionalidade',detail:'Cronologia do investimento e da receita de carbono, com evidências.'},
  {section:'direitos',title:'Direitos do projeto',detail:'Titulares das reduções e documentos de titularidade.'},
];
const journeys = {
  GENERAL: {
    id:'GENERAL', candidate:null, title:'Triagem inicial', description:'Registre dúvidas sobre o escopo.',
    questions:[{key:'scopeNotes',label:'O que precisa ser confirmado sobre o escopo?',type:'text',options:[]}],
    activityQuestions:[], activities:[],
    presentation:{activityPlaceholder:'Descreva a atividade que pretende realizar',
      baselinePlaceholder:'Descreva como a atividade ocorreria sem o projeto',
      next:next.filter(item => ['linha-base','adicionalidade','direitos'].includes(item.section))},
  },
  VM0038: {
    id:'VM0038_V1_1', candidate:'VM0038', title:vmTitle, description:'O piloto usa veículos leves e estações DCFC.',
    activityQuestions:[{key:'installsChargers',label:'O projeto inclui instalar sistemas de recarga?',type:'choice',help:null,options:[
      {value:'YES',label:'Sim'},{value:'NO',label:'Não'},{value:'DESCONHECIDO',label:'Ainda não sei'},
    ]}],
    activities:['MOBILIDADE_ELETRICA'],
    presentation:{activityPlaceholder:'Descreva onde, para quem e como os carregadores serão instalados',
      baselinePlaceholder:'Ex.: viagens atendidas por veículos equivalentes a combustão', next},
    questions:[
      {key:'propulsion',label:'Propulsão da frota atendida',type:'choice',options:[
        {value:'BEV',label:'BEV'}, {value:'PHEV',label:'PHEV'},
      ]},
      {key:'chargerType',label:'Tipo de recarga',type:'choice',options:[
        {value:'DCFC',label:'DCFC'}, {value:'AC',label:'Recarga AC'},
      ]},
    ],
  },
};

// A second package that exists only in this test: the screen must follow its schema, not VM0038's.
journeys.TEST = {
  id:'TEST_ONLY_RESIDUES', candidate:'TEST-ONLY', title:'Pacote de teste · resíduos', description:'Pacote sintético.',
  questions:[{key:'siteType',label:'Tipo de área',type:'choice',options:[{value:'LANDFILL',label:'Aterro'}]}],
  activityQuestions:[{key:'gasCapture',label:'Há captura de gás?',type:'choice',help:null,options:[
    {value:'YES',label:'Sim'},{value:'NO',label:'Não'}]}],
  activities:['RESIDUOS'],
  presentation:{activityPlaceholder:'Descreva a área de resíduos', baselinePlaceholder:'Descreva a destinação atual',
    next:[{section:'residuos-base',title:'Linha de base de resíduos',detail:'Item de teste.'}]},
};

async function setup(page) {
  let countPilot = 0;
  const state = {version: 0, currentStep: 1, answers: {}, exampleKind: null, retiredAnswers: []};
  const view = () => ({
    projectId, environment: 'SANDBOX', version: state.version, currentStep: state.currentStep,
    analysisState: 'NAO_AVALIADO', exampleKind: state.exampleKind,
    answers: state.answers,
    journey: journeys[{'VM0038':'VM0038','TEST-ONLY':'TEST'}[state.answers['1']?.methodologyCandidate] ?? 'GENERAL'],
    candidateStatus: state.answers['1']?.methodologyCandidate === 'METHX V2.1'
      ? {state:'REGISTERED_NO_PACKAGE', methodology:'METHX', edition:'2.1', registryVersion:1} : undefined,
    methodologyPin: state.answers['1']?.methodologyCandidate === 'VM0038'
      ? {version:state.migrated?2:1,triageVersion:1,methodology:'VM0038',edition:'1.1',registryVersion:1,packageId:'VM0038_V1_1',
         packageVersion:state.migrated?2:1,pinnedAt:'2026-11-25T12:00:00Z',newerRegistryVersion:false,
         packageChanged:state.migrated?false:state.packageChanged === true,
         origin:state.migrated?'MIGRATION':'TRIAGE',migratedFrom:state.migrated?1:null}
      : state.answers['1']?.methodologyCandidate === 'METHX V2.1'
      ? {version:1,triageVersion:1,methodology:'METHX',edition:'2.1',registryVersion:1,packageId:null,
         packageVersion:null,pinnedAt:'2026-11-25T12:00:00Z',newerRegistryVersion:true,packageChanged:false}
      : null,
    capabilities: (() => {
      const axis = s => ({state:s});
      const pack = state.answers['1']?.methodologyCandidate === 'VM0038';
      const known = pack || state.answers['1']?.methodologyCandidate === 'METHX V2.1';
      return [
        {purpose:'TRIAGE',label:'Triagem guiada',code:axis(pack?'MET':'NOT_MET'),data:axis('NOT_REQUIRED'),licence:axis('NOT_REQUIRED'),
         effective:pack?'ENABLED':'NOT_ENABLED',reason:pack?'Pacote com schema de triagem em código.'
           :known?'Metodologia registrada sem pacote em código: candidata com lacuna, sem campos próprios.':'Sem metodologia candidata registrada.'},
        {purpose:'SOURCE_REFERENCE',label:'Referência a requisitos das fontes',code:axis(pack?'MET':'NOT_MET'),data:axis('NOT_MET'),
         licence:axis('NOT_MET'),effective:'NOT_ENABLED',reason:'Falta ao menos um dos três: pacote em código, metadados das fontes obrigatórias ou decisão de licença vigente.'},
        {purpose:'CALCULATION_ADMISSION',label:'Cálculo e admissão de dados',code:axis('NOT_REQUIRED'),data:axis('NOT_REQUIRED'),
         licence:axis('NOT_REQUIRED'),effective:'NOT_ENABLED',reason:'Gate próprio com revisão e decisão separadas; não deriva de código, dados ou licença.'},
        {purpose:'EXTERNAL_ACTS',label:'Atos externos',code:axis('NOT_REQUIRED'),data:axis('NOT_REQUIRED'),
         licence:axis('NOT_REQUIRED'),effective:'NOT_ENABLED',reason:'Gate próprio com revisão e decisão separadas; não deriva de código, dados ou licença.'},
      ];
    })(),
    catalog: {version:1, digest:'0'.repeat(64), activityTypes:[
      {value:'MOBILIDADE_ELETRICA',label:'Mobilidade elétrica e recarga'},{value:'RESIDUOS',label:'Resíduos e economia circular'},
      {value:'ENERGIA',label:'Energia e eficiência energética'},{value:'AGRICULTURA',label:'Agricultura, solo e florestas'},
      {value:'OUTRO',label:'Outra atividade'},{value:'DESCONHECIDO',label:'Preciso de orientação'}]},
    methodologies: [{value:'VM0038',label:vmTitle},{value:'TEST-ONLY',label:'Pacote de teste · resíduos'},{value:'METHX V2.1',label:'Metodologia registrada X'}],
    diagnosis: {
      ruleVersion:'REG-E2 / CF-IMP-010 v2',
      classification:state.answers['3']?.chargerType === 'AC' ? 'UNSUPPORTED_MVP' : 'UNKNOWN_REVIEW_REQUIRED',
      reason:'A configuração precisa de análise.',
      findings:[
        ...(!state.answers['4']?.referenceDate ? [{code:'REFERENCE_DATE',kind:'ANSWER',
          title:'Informe a data de referência pretendida',detail:'Registre a data.',step:4,fields:['referenceDate']}] : []),
        ...(state.answers['3']?.chargerType === 'AC' ? [{code:'OUTSIDE_MVP_chargerType',kind:'PILOT_SCOPE',
          title:null,detail:'O piloto cobre estações DCFC.',step:3,fields:['chargerType']}] : []),
        {code:'DATE',kind:'TECHNICAL',title:'Documente a cronologia do projeto',detail:'Reúna documentos.',step:0,fields:[]},
      ],
    },
    guidance: {
      state:'A_CONFIRMAR', outsideMethodology:[],
      outsidePilot:state.answers['3']?.chargerType === 'AC' ? ['O piloto cobre estações DCFC.'] : [],
    },
    stages: labels.map((title,i) => ({
      title: i === 2 && state.answers['1']?.methodologyCandidate === 'VM0038' ? 'Recorte inicial' : title, number: i+1, status: i === 4 ? 'RESUMO_SEM_PARECER'
        : state.answers[String(i+1)] && Object.keys(state.answers[String(i+1)]).length > 0
          ? 'PENDENTE' : 'NAO_INICIADO',
      missingFields: [],
    })),
    pending: [], nextAction: 'Revisar as respostas sem alegar elegibilidade.',
    retiredAnswers: state.retiredAnswers,
  });
  const metadata = {projectId, organizationId, environment:'SANDBOX', state:'DRAFT',
    version:1, title:'Projeto de recarga no DF', description:null};
  await page.route('**/v1/account-session', route => route.fulfill({status:200, json:{
    identity:{id:'00000000-0000-4000-8000-000000000010',name:'Pessoa de teste',email:'teste@example.invalid'},
    projects:[{projectId,organizationId,environment:'SANDBOX',state:'DRAFT',version:1}],
  }}));
  await page.route('**/v1/organizations', route => route.fulfill({status:200, json:[
    {id:organizationId, name:'Org teste', environment:'SANDBOX',role:'DRAFT_OWNER',version:1,status:'PRIVATE_DRAFT'},
  ]}));
  await page.route('**/v1/projects/*/metadata', route => route.fulfill({status:200,json:metadata}));
  await page.route('**/v1/projects/*/triage', route => route.fulfill({
    status:200, headers: {ETag:'"' + state.version + '"'},json:view(),
  }));
  await page.route('**/v1/projects/*/triage/steps/*', async route => {
    assert.equal(route.request().method(), 'PUT');
    assert.ok(route.request().headers()['idempotency-key']);
    assert.equal(route.request().headers()['if-match'], '"' + state.version + '"');
    const step = new URL(route.request().url()).pathname.split('/').pop();
    const command = route.request().postDataJSON();
    state.version++;
    state.currentStep = command.nextStep;
    state.answers[step] = command.answers;
    await route.fulfill({status:200,headers:{ETag:'"' + state.version + '"'},json:view()});
  });
  await page.route('**/v1/projects/*/triage/cursor', async route => {
    assert.equal(route.request().method(),'PATCH');
    assert.equal(route.request().headers()['if-match'], '"' + state.version + '"');
    state.version++;
    state.currentStep = route.request().postDataJSON().nextStep;
    await route.fulfill({status:200,json:view()});
  });
  await page.route('**/v1/projects/*/triage/examples/bev-df-2027', async route => {
    assert.equal(route.request().method(),'POST');
    assert.equal(route.request().headers()['if-match'], '"' + state.version + '"');
    state.version++;
    state.currentStep = 5;
    state.answers = {
      '1': {objective:'Eletrificar frota',activityType:'MOBILIDADE_ELETRICA',methodologyCandidate:'VM0038'},
      '2': {activityDescription:'Operação de BEVs',baselinePractice:'A comprovar'},
      '3': {vehicleClass:'LDV',propulsion:'BEV',chargerType:'DCFC'},
      '4': {country:'BR',state:'DF',locality:'Brasília',referenceDate:'2027-09-27'},
      '_example':'25_BEV_BRASILIA_DF_2027_09_27',
    };
    state.exampleKind = state.answers._example;
    await route.fulfill({status:200,json:view()});
  });
  await page.route('**/v1/projects/*/triage/methodology-pin/migrate', async route => {
    state.migrateCalls = [...(state.migrateCalls ?? []), {ifMatch:route.request().headers()['if-match'],
      body:route.request().postDataJSON()}];
    if (state.migrateStatus) return route.fulfill({status:state.migrateStatus,json:{message:'x'}});
    state.migrated = true;
    await route.fulfill({status:200,json:view()});
  });
  await page.route('**/v1/projects/PIL-SYN-BR-001/**', route => {
    countPilot++;
    return route.fulfill({status:500});
  });
  return {state, metadata, pilotRequests:()=>countPilot};
}

test('CF-IMP-009: navegação preserva respostas, versão e retomada após recarregar', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:900}});
    const {state,pilotRequests} = await setup(page);
    await page.goto(base + '/workspace');
    await page.getByLabel('Projetos disponíveis nesta conta').selectOption(projectId);
    await page.getByRole('link', {name:'Iniciar ou retomar triagem'}).click();
    await page.getByRole('heading',{name:'Conheça seu projeto'}).waitFor();
    await page.getByLabel('O que você quer alcançar com o projeto?').fill('Eletrificar a operação');
    await page.getByLabel('Qual atividade descreve melhor sua ideia?').selectOption('MOBILIDADE_ELETRICA');
    await page.getByRole('button',{name:'Salvar e avançar'}).click();
    await page.getByLabel('Como a atividade funcionará?').waitFor();
    assert.equal(state.version,1);
    assert.equal(state.currentStep,2);
    await page.getByLabel('Como a atividade funcionará?').fill('Operação com frota elétrica');
    await page.getByRole('button',{name:'Salvar e continuar depois'}).click();
    await page.getByText('Respostas salvas na revisão 2.').waitFor();
    await page.reload();
    await page.getByLabel('Como a atividade funcionará?').waitFor();
    assert.equal(await page.getByLabel('Como a atividade funcionará?').inputValue(),
      'Operação com frota elétrica');
    await page.getByRole('button',{name:'Objetivo e metodologia',exact:false}).click();
    await page.getByLabel('O que você quer alcançar com o projeto?').waitFor();
    assert.equal(await page.getByLabel('O que você quer alcançar com o projeto?').inputValue(),
      'Eletrificar a operação');
    assert.equal(state.currentStep,1);
    assert.equal(pilotRequests(),0);
  } finally {
    await browser.close();
  }
});

test('CF-IMP-009: exemplo 25 BEVs Brasília/DF em 27-09-2027 gera resumo sem ER ou VCU', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:900}});
    const {state,pilotRequests} = await setup(page);
    await page.goto(base + '/workspace/projects/' + projectId + '/triage');
    await page.getByRole('heading',{name:'Conheça seu projeto'}).waitFor();
    await page.getByText('Testar com um exemplo sintético',{exact:true}).click();
    await page.getByRole('button',{name:'Preencher exemplo de 25 BEVs'}).click();
    await page.getByText('Exemplo ilustrativo de 25 BEVs preenchido.',{exact:false}).waitFor();
    await page.getByText('Este cenário contém somente dados de demonstração:',{exact:false}).waitFor();
    assert.equal(state.answers['3'].propulsion,'BEV');
    assert.equal(state.answers['4'].referenceDate,'2027-09-27');
    assert.equal(state.answers['4'].locality,'Brasília');
    assert.equal(state.exampleKind,'25_BEV_BRASILIA_DF_2027_09_27');
    await page.getByText('Não há reduções de CO₂e verificadas nem VCUs emitidas.',{exact:false}).waitFor();
    assert.equal(pilotRequests(),0);
    await page.reload();
    await page.getByRole('status').filter({hasText:'EXEMPLO SINTÉTICO · 25 BEVs'}).waitFor();
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});

test('triagem só oferece recarga na VM0038 e mantém AC como resposta fora do piloto', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1360,height:850}});
    const {state} = await setup(page);
    await page.goto(base + '/workspace/projects/' + projectId + '/triage');
    await page.getByRole('button',{name:/Escopo e lacunas/}).click();
    await page.getByLabel('O que precisa ser confirmado sobre o escopo?').waitFor();
    assert.equal(await page.getByLabel('Tipo de recarga').count(),0);
    await page.getByRole('button',{name:/Objetivo e metodologia/}).click();
    await page.getByLabel('Metodologia candidata').selectOption('VM0038');
    await page.getByRole('button',{name:'Salvar e avançar'}).click();
    await page.getByLabel('O projeto inclui instalar sistemas de recarga?').selectOption('YES');
    await page.getByRole('button',{name:'Salvar e avançar'}).click();
    await page.getByLabel('Tipo de recarga').selectOption('AC');
    await page.getByRole('button',{name:/Resumo da triagem/}).click();
    await page.getByRole('heading',{name:'Respostas fora do recorte do piloto'}).waitFor();
    assert.equal(state.answers['3'].chargerType,'AC');
    await page.getByText('Configuração fora do recorte implementado',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Conferir · Tipo de recarga'}).click();
    const chargerType = page.getByLabel('Tipo de recarga');
    await page.locator('#cf-wz-chargerType:focus').waitFor();
    assert.equal(await chargerType.inputValue(),'AC');
    await page.getByRole('button',{name:'Salvar e voltar ao resumo'}).click();
    await page.getByRole('button',{name:'Revisar · Data de referência'}).click();
    await page.locator('#cf-wz-date:focus').waitFor();
    await page.getByLabel('Data de referência pretendida').fill('2027-09-27');
    await page.getByRole('button',{name:'Salvar e voltar ao resumo'}).click();
    await page.getByRole('heading',{name:'Diagnóstico preliminar'}).waitFor();
    assert.equal(state.answers['4'].referenceDate,'2027-09-27');
    assert.equal(await page.getByRole('button',{name:'Revisar · Data de referência'}).count(),0);
    await page.getByText('Documente a cronologia do projeto',{exact:true}).waitFor();
    assert.equal(await page.getByRole('link',{name:'Registrar observações na preparação técnica'}).getAttribute('href'),
      '/workspace/projects/' + projectId + '/technical?secao=preparar');
    await page.reload();
    await page.getByRole('button',{name:'Editar recorte inicial'}).click();
    assert.equal(await page.getByLabel('Tipo de recarga').inputValue(),'AC');
  } finally {
    await browser.close();
  }
});

test('D16: triagem pede só a hipótese, mostra respostas antigas só para leitura e o que será pedido depois', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1360,height:850}});
    const {state} = await setup(page);
    // A project answered before D16: older answers stay in the revision and are owned by technical screens.
    state.version = 4;
    state.currentStep = 3;
    state.answers = {
      '1': {objective:'Rede de recarga',activityType:'MOBILIDADE_ELETRICA',methodologyCandidate:'VM0038'},
      '2': {installsChargers:'YES',activityDescription:'Recarga rápida'},
      '3': {propulsion:'BEV',chargerType:'DCFC',network:'CLOSED',vehicleCount:25},
      '4': {country:'BR',state:'DF',locality:'Brasília',referenceDate:'2027-09-27',rightsStatus:'DECLARADO'},
    };
    state.retiredAnswers = [
      {step:3,key:'network',label:'Identificação da frota na rede',value:'CLOSED',valueLabel:'Fechada · frota identificada',section:'rede',sectionTitle:'Acesso à rede'},
      {step:3,key:'vehicleCount',label:'Quantidade estimada de veículos',value:25,valueLabel:'25',section:'estacoes',sectionTitle:'Estações e fronteira'},
      {step:4,key:'rightsStatus',label:'Situação de direitos e titularidade',value:'DECLARADO',valueLabel:'Declaro que possuo direitos, sem comprovação',section:'direitos',sectionTitle:'Direitos do projeto'},
    ];
    await page.goto(base + '/workspace/projects/' + projectId + '/triage');
    await page.getByRole('heading',{name:'Respostas antigas que agora ficam nas telas técnicas'}).waitFor();
    await page.getByText('Fechada · frota identificada',{exact:false}).waitFor();
    assert.equal(await page.getByLabel('Identificação da frota na rede').count(),0,'older answer is not editable');
    assert.equal(await page.getByRole('link',{name:'Abrir Acesso à rede'}).getAttribute('href'),
      '/workspace/projects/' + projectId + '/technical?secao=rede');
    await page.getByLabel('Tipo de recarga').selectOption('DCFC');
    await page.getByLabel('Propulsão da frota atendida').selectOption('PHEV');
    await page.getByRole('button',{name:'Salvar e avançar'}).click();
    await page.getByLabel('Data de referência pretendida').waitFor();
    assert.deepEqual(Object.keys(state.answers['3']).sort(),['chargerType','propulsion'],'retired answers are not sent');
    // Step 4 asks only the location; rights, evidence and investment are not asked again.
    for (const label of ['Situação de direitos e titularidade','Situação dos dados de origem',
      'Já houve compromisso de investimento, como contrato ou pedido firme?']) {
      assert.equal(await page.getByLabel(label).count(),0,label);
    }
    assert.equal(await page.getByRole('link',{name:'Abrir Direitos do projeto'}).getAttribute('href'),
      '/workspace/projects/' + projectId + '/technical?secao=direitos');
    await page.getByRole('button',{name:'Salvar e ver resumo'}).click();
    await page.getByRole('heading',{name:'O que será pedido na preparação técnica'}).waitFor();
    assert.deepEqual(Object.keys(state.answers['4']).sort(),['country','locality','referenceDate','state']);
    for (const [name,section] of [['Acesso à rede','rede'],['Estações e fronteira','estacoes'],['Monitorar energia','monitorar'],
      ['Adicionalidade','adicionalidade'],['Direitos do projeto','direitos']]) {
      assert.equal(await page.getByRole('link',{name,exact:true}).first().getAttribute('href'),
        '/workspace/projects/' + projectId + '/technical?secao=' + section, name);
    }
    await page.getByText('Respostas antigas, só para leitura:',{exact:false}).first().waitFor();
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});

test('A03: a triagem segue o schema do pacote escolhido, sem campos de recarga de outro pacote', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1360,height:850}});
    await setup(page);
    await page.goto(base + '/workspace/projects/' + projectId + '/triage');
    await page.getByLabel('Metodologia candidata').selectOption('TEST-ONLY');
    await page.getByRole('button',{name:'Salvar e avançar'}).click();
    await page.getByLabel('Há captura de gás?').waitFor();
    assert.equal(await page.getByLabel('O projeto inclui instalar sistemas de recarga?').count(),0);
    assert.ok(await page.getByPlaceholder('Descreva a área de resíduos').count() > 0);
    await page.getByRole('button',{name:/Resumo da triagem/}).click();
    await page.getByRole('heading',{name:'O que será pedido na preparação técnica'}).waitFor();
    await page.getByRole('link',{name:'Linha de base de resíduos'}).waitFor();
    assert.equal(await page.getByRole('link',{name:'Acesso à rede'}).count(),0);
  } finally {
    await browser.close();
  }
});

test('D18: metodologia registrada sem pacote é candidata com aviso de lacuna, sem campos de outro pacote', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1360,height:850}});
    await setup(page);
    await page.goto(base + '/workspace/projects/' + projectId + '/triage');
    await page.getByLabel('Metodologia candidata').selectOption('METHX V2.1');
    await page.getByRole('button',{name:'Salvar e avançar'}).click();
    await page.getByRole('button',{name:/Objetivo e metodologia/}).click();
    await page.getByText('ainda sem pacote executável',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Salvar e avançar'}).click();
    assert.equal(await page.getByLabel('O projeto inclui instalar sistemas de recarga?').count(),0);
  } finally {
    await browser.close();
  }
});

test('D18 fatia 3: a tela mostra a fixação de versão e a capacidade por finalidade, sem sugerir aprovação', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1360,height:850}});
    const {state} = await setup(page);
    await page.goto(base + '/workspace/projects/' + projectId + '/triage');
    // The summary is the last step: jump there with the example (no answers invented by the screen).
    await page.getByText('Testar com um exemplo sintético',{exact:true}).click();
    await page.getByRole('button',{name:'Preencher exemplo de 25 BEVs'}).click();
    await page.getByRole('heading',{name:'Situação da metodologia no CarbonFlow'}).waitFor();
    await page.getByText('Preparação fixada em VM0038 1.1',{exact:false}).waitFor();
    await page.getByText('pacote versão 1',{exact:false}).waitFor();
    assert.equal(await page.getByText('Há uma versão mais nova do registro',{exact:false}).count(),0);
    const list = page.locator('#cf-triage-capability').locator('..');
    await list.getByText('Triagem guiada',{exact:true}).waitFor();
    await list.getByText('Triagem guiada',{exact:true}).locator('..').getByText('disponível',{exact:false}).first().waitFor();
    await list.getByText('Referência a requisitos das fontes',{exact:true}).locator('..').getByText('ainda não disponível').waitFor();
    await list.getByText('Código: Atendido · Dados: Não atendido · Licença: Não atendido').waitFor();
    // Calculation, admission and external acts are closed gates, never a "missing feature".
    await list.getByText('Cálculo e admissão de dados',{exact:true}).locator('..').getByText('fechado, com decisão própria').waitFor();
    await list.getByText('Atos externos',{exact:true}).locator('..').getByText('fechado, com decisão própria').waitFor();
    assert.equal(await list.getByText(/aprovad|elegível|habilitad/i).count(),0);
    // A changed package is shown as a notice; the project stays on the pinned version.
    state.packageChanged = true;
    await page.reload();
    await page.getByText('O pacote desta metodologia mudou desde a fixação.',{exact:false}).waitFor();
    await page.getByText('continua na versão fixada até você migrar',{exact:false}).waitFor();
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});

test('D18 fatia 3: metodologia registrada sem pacote mostra a lacuna e a versão nova do registro', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1360,height:850}});
    const {state} = await setup(page);
    await page.goto(base + '/workspace/projects/' + projectId + '/triage');
    await page.getByText('Testar com um exemplo sintético',{exact:true}).click();
    await page.getByRole('button',{name:'Preencher exemplo de 25 BEVs'}).click();
    await page.getByRole('heading',{name:'Situação da metodologia no CarbonFlow'}).waitFor();
    // The server now reports a registered methodology without a package as the candidate.
    state.answers['1'].methodologyCandidate = 'METHX V2.1';
    await page.reload();
    await page.getByRole('heading',{name:'Situação da metodologia no CarbonFlow'}).waitFor();
    await page.getByText('ainda sem pacote executável)',{exact:false}).waitFor();
    await page.getByText('Há uma versão mais nova do registro desta metodologia.',{exact:false}).waitFor();
    await page.locator('#cf-triage-capability').locator('..').getByText('Triagem guiada',{exact:true}).locator('..').getByText('ainda não disponível').waitFor();
  } finally {
    await browser.close();
  }
});

test('D18 migração explícita: pede confirmação, envia a versão da fixação e mostra a nova fixação', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1360,height:850}});
    const {state} = await setup(page);
    state.packageChanged = true;
    await page.goto(base + '/workspace/projects/' + projectId + '/triage');
    await page.getByText('Testar com um exemplo sintético',{exact:true}).click();
    await page.getByRole('button',{name:'Preencher exemplo de 25 BEVs'}).click();
    await page.getByRole('heading',{name:'Situação da metodologia no CarbonFlow'}).waitFor();
    // Asking is not migrating: nothing is sent until the confirmation, and cancelling sends nothing.
    await page.getByRole('button',{name:'Migrar para a versão atual'}).click();
    await page.getByText('A fixação anterior continua registrada',{exact:false}).first().waitFor();
    await page.getByRole('button',{name:'Cancelar'}).click();
    assert.equal(state.migrateCalls, undefined);
    // A failed attempt (the pin changed elsewhere) explains itself and keeps the old pin.
    state.migrateStatus = 412;
    await page.getByRole('button',{name:'Migrar para a versão atual'}).click();
    await page.getByRole('button',{name:'Confirmar migração'}).click();
    await page.getByRole('alert').getByText('A fixação mudou em outra aba',{exact:false}).waitFor();
    await page.getByText('O pacote desta metodologia mudou desde a fixação.',{exact:false}).waitFor();
    // Confirmed: the pin version goes in If-Match, the body only confirms, and the new pin is shown.
    state.migrateStatus = 0;
    await page.getByRole('button',{name:'Migrar para a versão atual'}).click();
    await page.getByRole('button',{name:'Confirmar migração'}).click();
    await page.getByRole('status').getByText('Projeto migrado para a versão atual',{exact:false}).waitFor();
    const call = state.migrateCalls.at(-1);
    assert.equal(call.ifMatch,'"1"');
    assert.deepEqual(call.body,{confirm:true});
    await page.getByText('migrada da fixação 1',{exact:false}).waitFor();
    await page.getByText('pacote versão 2',{exact:false}).waitFor();
    assert.equal(await page.getByText('O pacote desta metodologia mudou desde a fixação.',{exact:false}).count(),0);
    assert.equal(await page.getByRole('button',{name:'Migrar para a versão atual'}).count(),0);
  } finally {
    await browser.close();
  }
});
