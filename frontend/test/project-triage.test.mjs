import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const projectId = '20000000-0000-4000-8000-000000000010';
const organizationId = '10000000-0000-4000-8000-000000000010';
const labels = ['Planejamento','Atividade','Escopo e lacunas','Local, dados e direitos','Resumo da triagem'];
const vmTitle = 'VM0038 v1.1 · Recarga de veículos elétricos';
const journeys = {
  GENERAL: {
    id:'GENERAL', candidate:null, title:'Triagem inicial', description:'Registre dúvidas sobre o escopo.',
    questions:[{key:'scopeNotes',label:'O que precisa ser confirmado sobre o escopo?',type:'text',options:[]}],
  },
  VM0038: {
    id:'VM0038_V1_1', candidate:'VM0038', title:vmTitle, description:'O piloto usa veículos leves e estações DCFC.',
    questions:[
      {key:'vehicleCount',label:'Quantidade estimada de veículos',type:'number',options:[]},
      {key:'chargerType',label:'Tipo de recarga',type:'choice',options:[
        {value:'DCFC',label:'DCFC'}, {value:'AC',label:'Recarga AC'},
      ]},
    ],
  },
};

async function setup(page) {
  let countPilot = 0;
  const state = {version: 0, currentStep: 1, answers: {}, exampleKind: null};
  const view = () => ({
    projectId, environment: 'SANDBOX', version: state.version, currentStep: state.currentStep,
    analysisState: 'NAO_AVALIADO', exampleKind: state.exampleKind,
    answers: state.answers,
    journey: journeys[state.answers['1']?.methodologyCandidate === 'VM0038' ? 'VM0038' : 'GENERAL'],
    methodologies: [{value:'VM0038',label:vmTitle}],
    diagnosis: {
      ruleVersion:'REG-E2 / CF-IMP-010 v1',
      classification:state.answers['3']?.chargerType === 'AC' ? 'UNSUPPORTED_MVP' : 'UNKNOWN_REVIEW_REQUIRED',
      reason:'A configuração precisa de análise.',
      findings:[{code:'DATE',title:'Documente a cronologia do projeto',detail:'Reúna documentos.',step:4,fields:['referenceDate']}],
    },
    guidance: {
      state:'A_CONFIRMAR', outsideMethodology:[],
      outsidePilot:state.answers['3']?.chargerType === 'AC' ? ['O piloto cobre estações DCFC.'] : [],
    },
    stages: labels.map((title,i) => ({
      title: i === 2 && state.answers['1']?.methodologyCandidate === 'VM0038' ? 'Frota, recarga e medição' : title, number: i+1, status: i === 4 ? 'RESUMO_SEM_PARECER'
        : state.answers[String(i+1)] && Object.keys(state.answers[String(i+1)]).length > 0
          ? 'PENDENTE' : 'NAO_INICIADO',
      missingFields: [],
    })),
    pending: [], nextAction: 'Revisar as respostas sem alegar elegibilidade.',
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
      '3': {vehicleCount:25,propulsion:'BEV',chargerType:'DCFC',chargerCount:1,electricitySource:'DESCONHECIDA'},
      '4': {country:'BR',state:'DF',locality:'Brasília',referenceDate:'2027-09-27',
        evidenceStatus:'DESCONHECIDO',rightsStatus:'DESCONHECIDO'},
      '_example':'25_BEV_BRASILIA_DF_2027_09_27',
    };
    state.exampleKind = state.answers._example;
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
    await page.getByRole('button',{name:'Planejamento',exact:false}).click();
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
    assert.equal(state.answers['3'].vehicleCount,25);
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
    await page.getByRole('button',{name:/Planejamento/}).click();
    await page.getByLabel('Metodologia candidata').selectOption('VM0038');
    await page.getByRole('button',{name:'Salvar e avançar'}).click();
    await page.getByLabel('O projeto inclui instalar sistemas de recarga?').selectOption('YES');
    await page.getByRole('button',{name:'Salvar e avançar'}).click();
    await page.getByLabel('Tipo de recarga').selectOption('AC');
    await page.getByRole('button',{name:/Resumo da triagem/}).click();
    await page.getByRole('heading',{name:'Respostas fora do recorte do piloto'}).waitFor();
    assert.equal(state.answers['3'].chargerType,'AC');
    await page.getByText('Configuração fora do recorte implementado',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Abrir etapa · Documente a cronologia do projeto'}).click();
    await page.getByLabel('Data de referência pretendida').waitFor();
    await page.getByRole('button',{name:/Resumo da triagem/}).click();
    await page.getByRole('heading',{name:'Diagnóstico preliminar'}).waitFor();
    await page.reload();
    await page.getByRole('button',{name:'Editar frota, recarga e medição'}).click();
    assert.equal(await page.getByLabel('Tipo de recarga').inputValue(),'AC');
  } finally {
    await browser.close();
  }
});
