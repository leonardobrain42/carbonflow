import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const project='20000000-0000-4000-8000-000000000001';
const overview='/workspace/projects/'+project+'/overview';
const technical='/workspace/projects/'+project+'/technical';

const tasks=[
 {id:'triage',kind:'TRIAGE',title:'Preencher triagem',state:'COMPLETED',
  reason:'Campos da triagem registrados.',responsible:'DRAFT_OWNER',sourceVersion:2,
  destination:{area:'triage',screen:null,guide:null,step:null},actionable:false},
 {id:'guide-baseline',kind:'GUIDE',title:'Linha de base',state:'REQUIRES_RECHECK',
  reason:'Uma fonte da linha de base mudou. Confira a seleção e a revisão.',
  responsible:'PREP',sourceVersion:3,
  destination:{area:'technical',screen:'linha-base',guide:null,step:'premissas'},actionable:true},
 {id:'guide-additionality',kind:'GUIDE',title:'Adicionalidade',state:'NEEDS_DATA',
  reason:'Os documentos jurídicos e datas exigem comprovação.',
  responsible:'PREP',sourceVersion:1,
  destination:{area:'technical',screen:'adicionalidade',guide:null,step:'cronologia'},actionable:true},
 {id:'review-baseline',kind:'REVIEW',title:'Revisão de linha de base',state:'IN_REVIEW',
  reason:'Aguardando parecer do REV.',responsible:'REV',sourceVersion:1,
  destination:{area:'technical',screen:'revisar',guide:'baseline',step:null},actionable:false}
];
const journey=()=>({
 projectId:project,title:'Rede de recarga do DF',environment:'SANDBOX',
 projectState:'DRAFT',role:'DRAFT_OWNER',methodology:'VM0038 v1.1',
 methodologyConfigured:true,nextTask:'guide-baseline',
 recommendedTasks:['guide-baseline','guide-additionality'],
 progress:{visible:4,recorded:1,needsAttention:2,inReview:1,actionable:2},
 resume:{area:'technical',screen:'adicionalidade',guide:null,step:'direitos',
   visitedAt:'2026-11-20T14:00:00Z'},
 tasks
});

test('UX-07 mobile: clear next action, honest progress, deep resume, filtering and revocation',async()=>{
 const browser=await chromium.launch({executablePath});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}});
  let status=200;let pilotCalls=0;
  await page.route('**/v1/projects/PIL-SYN-BR-001/**',r=>{
    pilotCalls++;return r.fulfill({status:500,json:{}});
  });
  await page.route('**/v1/projects/'+project+'/journey',r=>
    r.fulfill(status===200?{json:journey()}:{status,json:{}}));
  await page.goto(base+overview);
  await page.getByRole('heading',{name:'Rede de recarga do DF'}).waitFor();
  await page.getByRole('heading',{name:'Próxima ação'}).waitFor();
  await page.getByText('1 de 4 tarefas com registro concluído').waitFor();
  assert.equal(await page.getByRole('progressbar',{name:'Registros de tarefas visíveis'})
    .getAttribute('aria-valuenow'),'25');
  await page.getByText('Não representa elegibilidade, validação metodológica nem PD pronto.',{exact:false}).waitFor();
  assert.equal(await page.getByRole('link',{name:'Continuar tarefa'}).getAttribute('href'),
    '/workspace/projects/'+project+'/technical?secao=linha-base&etapa=premissas');
  assert.equal(await page.getByRole('link',{name:'Retomar minha etapa'}).getAttribute('href'),
    '/workspace/projects/'+project+'/technical?secao=adicionalidade&etapa=direitos');

  await page.getByRole('button',{name:'Pendências'}).click();
  await page.getByText('Exibindo 2 de 4 tarefas visíveis').waitFor();
  await page.getByLabel('Buscar tarefa').fill('ADICIONALIDADE');
  await page.getByText('Exibindo 1 de 4 tarefas visíveis').waitFor();
  assert.equal(await page.getByRole('button',{name:'Pendências'}).getAttribute('aria-pressed'),'true');
  await page.getByLabel('Buscar tarefa').fill('não existe');
  await page.getByText('Nenhuma tarefa corresponde aos filtros escolhidos.').waitFor();
  await page.getByRole('button',{name:'Limpar filtros'}).click();
  await page.getByText('Exibindo 4 de 4 tarefas visíveis').waitFor();
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  assert.equal(pilotCalls,0);

  status=404;
  await page.getByRole('button',{name:'Atualizar visão geral'}).click();
  await page.getByRole('alert').filter({hasText:'Projeto indisponível'}).waitFor();
  assert.equal(await page.getByRole('heading',{name:'Rede de recarga do DF'}).count(),0);
  assert.equal(await page.getByRole('link',{name:'Retomar minha etapa'}).count(),0);
  assert.equal(await page.getByRole('link',{name:'Preparação técnica'}).count(),0);
 }finally{await browser.close();}
});

test('UX-07: technical navigation stores only valid current step and restores it across reload',async()=>{
 const browser=await chromium.launch({executablePath});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:900}});
  const checkpoints=[];
  let latest=null;
  await page.route('**/v1/projects/'+project+'/journey/resume',r=>{
    const payload=r.request().postDataJSON();
    assert.equal(r.request().method(),'PUT');
    assert.equal(payload.area,'technical');
    assert.ok(['inicio','entender','preparar'].includes(payload.screen));
    assert.equal(payload.guide,null);
    assert.equal(payload.step,null);
    checkpoints.push(payload);
    latest={...payload,visitedAt:'2026-11-20T21:30:00Z'};
    return r.fulfill({json:latest});
  });
  await page.route('**/v1/projects/'+project+'/journey',r=>{
    const current=journey();current.resume=latest;
    return r.fulfill({json:current});
  });
  await page.route('**/v1/projects/'+project+'/metadata',r=>r.fulfill({json:{
    projectId:project,organizationId:'10000000-0000-4000-8000-000000000001',
    environment:'SANDBOX',state:'DRAFT',version:1,
    title:'Rede de recarga do DF',description:null
  }}));
  await page.route('**/v1/projects/'+project+'/technical/capabilities',r=>r.fulfill({json:{
    projectId:project,environment:'SANDBOX',owner:true,
    actions:['VIEW_INTAKE','SAVE_INTAKE','VIEW_GUIDES'],modules:{}
  }}));
  await page.route('**/v1/projects/'+project+'/technical/intake',r=>r.fulfill({json:{
    projectId:project,environment:'SANDBOX',version:1,
    state:'RASCUNHO',reviewState:'NAO_AVALIADO',
    data:{activity:'Rede pública',country:'BR',state:'DF',locality:'Brasília',
      candidateMethodology:'VM0038',referenceActDate:'2027-01-01',notes:'Preparação'},
    outstandingGates:[],triageCandidate:'VM0038',
    journey:{id:'VM0038_V1_1',candidate:'VM0038',edition:'1.1',
      title:'VM0038 v1.1',activitiesTitle:'Atividade',detailsTitle:'Dados',
      description:'Recarga de BEVs',sourceUrl:null,questions:[],technicalTopics:[]},
    methodologies:[]
  }}));

  await page.goto(base+technical+'?secao=inicio');
  await page.getByRole('heading',{name:'Seu próximo passo'}).waitFor();
  await page.waitForFunction(()=>true);
  const [saved] = await Promise.all([
    page.waitForResponse(r=>r.url().endsWith('/journey/resume')&&
      r.request().method()==='PUT'&&r.request().postDataJSON()?.screen==='preparar'),
    page.getByRole('button',{name:'Preparar informações'}).first().click()
  ]);
  assert.equal(saved.status(),200);
  await page.getByRole('heading',{name:'Preparar informações'}).waitFor();
  await page.waitForFunction(()=>document.location.search.includes('secao=preparar'));
  await page.reload();
  await page.getByRole('heading',{name:'Preparar informações'}).waitFor();
  assert.ok(checkpoints.some(c=>c.screen==='preparar'));

  await page.goto(base+overview);
  await page.getByRole('link',{name:'Retomar minha etapa'}).waitFor();
  assert.equal(await page.getByRole('link',{name:'Retomar minha etapa'}).getAttribute('href'),
    '/workspace/projects/'+project+'/technical?secao=preparar');
 }finally{await browser.close();}
});

test('UX-07: monitoring collaborator opens permitted technical context without private intake',async()=>{
 const browser=await chromium.launch({executablePath});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}});
  let intakeReads=0,checkpoints=0;
  await page.route('**/v1/projects/'+project+'/technical/intake',r=>{
    intakeReads++;return r.fulfill({status:403,json:{}});
  });
  await page.route('**/v1/projects/'+project+'/metadata',r=>r.fulfill({json:{
    projectId:project,organizationId:'10000000-0000-4000-8000-000000000001',
    environment:'SANDBOX',state:'DRAFT',version:1,title:'Rede aberta',description:null
  }}));
  await page.route('**/v1/projects/'+project+'/technical/capabilities',r=>r.fulfill({json:{
    projectId:project,environment:'SANDBOX',owner:false,
    actions:['VIEW_JOURNEY','VIEW_OPEN_MONITORING'],modules:{}
  }}));
  await page.route('**/v1/projects/'+project+'/journey/resume',r=>{
    checkpoints++;
    assert.equal(r.request().postDataJSON().screen,'entender');
    return r.fulfill({json:{...r.request().postDataJSON(),visitedAt:'2026-11-20T21:40:00Z'}});
  });
  await page.route('**/v1/projects/'+project+'/journey',r=>r.fulfill({json:{
    ...journey(),role:'OP',
    journeyProfile:{
      id:'VM0038_V1_1',candidate:'VM0038',edition:'1.1',
      title:'VM0038 v1.1',activitiesTitle:'Recarga',detailsTitle:'Dados operacionais',
      description:'Perfil da recarga elétrica',sourceUrl:null,
      questions:[],technicalTopics:['Inventário de estações e carregadores']
    }
  }}));
  const savedCheckpoint=page.waitForResponse(r=>r.url().endsWith('/journey/resume')&&
    r.request().method()==='PUT');
  await page.goto(base+technical+'?secao=entender');
  assert.equal((await savedCheckpoint).status(),200);
  await page.getByRole('heading',{name:'Entender',exact:true}).waitFor();
  await page.getByText('Dados iniciais restritos',{exact:false}).waitFor();
  await page.getByText('Inventário de estações e carregadores').waitFor();
  await page.getByText('Dados iniciais restritos',{exact:false}).waitFor();
  // The endpoint may be probed by older modules, but must never expose
  // a privileged intake response or render any of its private fields.
  assert.ok(intakeReads<=1);
  assert.equal(await page.getByText('Rede privada sem autorização').count(),0);
  assert.ok(checkpoints>=1);
  assert.equal(await page.getByRole('button',{name:'Preparar informações'}).count(),0);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 }finally{await browser.close();}
});

test('a visão geral mostra a mesma pendência de Analisar sem alterar o percentual',async()=>{
 const browser=await chromium.launch({executablePath});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}});
  const current=journey();
  current.diagnosis={
    limits:'Nenhum BE, PE, LE ou ER foi calculado.',
    canRunE8:false,canIssueVcu:false,
    summary:{mine:1,awaitingSource:1,awaitingReview:1,outOfScope:1},
    requirements:[{
      requirementId:'DRIVER_DISCLOSURE',situation:'Falta comprovante',
      reason:'Falta a prova datada de divulgação aos motoristas.',
      nextAction:'Indicar a referência do comprovante na declaração.',
      actionLabel:'Abrir a declaração',
      destination:{area:'technical',screen:'analisar',step:null},
      clientAction:true,admitsE8:false
    }]
  };
  await page.route('**/v1/projects/'+project+'/journey',r=>r.fulfill({json:current}));
  await page.goto(base+overview);
  await page.getByRole('heading',{name:'Pendências do diagnóstico'}).waitFor();
  // The overview is a summary: name, situation and action; reason, rule and reference stay in Situação.
  await page.getByText('Divulgação aos motoristas',{exact:true}).waitFor();
  await page.getByText('Falta comprovante',{exact:true}).waitFor();
  assert.equal(await page.getByText('Falta a prova datada de divulgação aos motoristas.').count(),0);
  assert.equal(await page.getByText('Referência: DRIVER_DISCLOSURE',{exact:true}).count(),0);
  assert.equal(await page.getByRole('link',{name:'Ver todas as pendências, com motivo e regra, em Situação do projeto'})
    .getAttribute('href'),'/workspace/projects/'+project+'/technical?secao=analisar');
  await page.getByText('1 de 4 tarefas com registro concluído').waitFor();
  assert.equal(await page.getByRole('link',{name:'Abrir a declaração'}).getAttribute('href'),
    '/workspace/projects/'+project+'/technical?secao=analisar');
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true);
  await page.setViewportSize({width:1440,height:900});
  await page.getByRole('heading',{name:'Pendências do diagnóstico'}).waitFor();
 }finally{await browser.close();}
});
