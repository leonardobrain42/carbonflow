import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const project='20000000-0000-4000-8000-000000000001';
const root='/v1/projects/'+project;
const typedFieldsBaseline=[
  {key:'scenario',label:'Alternativa ao projeto',type:'TEXTAREA',unit:null,helpText:'Descrição'},
  {key:'vehicleClass',label:'Classe de veículo',type:'ENUM',unit:null,helpText:'LDV'}
];
const typedFieldsAdditionality=[
  {key:'route',label:'Rota candidata',type:'ENUM',unit:null,helpText:'Revisão'},
  {key:'considered',label:'Consideração da receita de carbono',type:'DATE',unit:null,helpText:'Preliminar'},
  {key:'decision',label:'Decisão de investimento',type:'DATE',unit:null,helpText:'Preliminar'},
  {key:'commitment',label:'Compromisso vinculante',type:'DATE',unit:null,helpText:'Preliminar'}
];
const guide=(id)=>id==='baseline'?{
  id,title:'Linha de base',version:2,definitionVersion:1,edition:'1.1',
  methodology:'VM0038_V1_1',fields:typedFieldsBaseline,
  answers:{scenario:'Rede de postos públicos contrafactual',vehicleClass:'LDV'},
  state:'FORM_COMPLETED_DECLARED',missing:[],legacyNeedsReview:[],normativeApproved:false
}:{
  id,title:'Adicionalidade',version:1,definitionVersion:1,edition:'1.1',
  methodology:'VM0038_V1_1',fields:typedFieldsAdditionality,
  answers:{route:'Incidência a verificar',considered:'2027-01-01',
    decision:'2027-02-10',commitment:'2026-12-20'},
  state:'INCOMPLETE_DECLARED',missing:[],legacyNeedsReview:[],normativeApproved:false
};
const app={
 projectId:project,version:2,
 latest:{answers:{
  chargingOwner:'Organização declarada, sem procuração',
  carbonRightsContractRef:'DOC-DIREITO-DECLARADO',
  driverDisclosureChannel:'NOT_DECLARED',driverDisclosureEvidenceRef:null,
  driverDisclosureEffectiveOn:null,effectiveFrom:'2027-05-05',
  doubleClaimingCheck:'UNKNOWN'
 }},
 readiness:{network:'OPEN_CANDIDATE',rights:'EVIDENCE_PENDING',norm:'REVIEW_REQUIRED',
  stale:false,canUseForE8:false,findings:[
   {code:'VMD0049_ROUTE',state:'PENDING_NORMATIVE_SOURCE',
    text:'Lista positiva é hipótese dependente de edição válida.'},
   {code:'REGULATORY_SURPLUS',state:'PENDING_LEGAL_REVIEW',
    text:'Excedente regulatório exige revisão independente.'},
   {code:'OWNER_CONTRACT',state:'NEEDS_EVIDENCE',
    text:'A referência não constitui contrato jurídico verificado.'},
   {code:'DRIVER_DISCLOSURE',state:'NEEDS_EVIDENCE',
    text:'Divulgação aos motoristas ainda não comprovada.'}
  ]}
};
const calc={
 projectId:project,environment:'SANDBOX',calculationState:'BLOCKED_MISSING_DATA',canRunE8:false,
 gates:[
  {code:'E8_FLEET_SELECTION_P-2027',state:'NOT_ADMITTED',
   reason:'Composição EVR/PBEV disponível apenas como ligação preparatória.',
   nextAction:'Obter validação das fontes EVR',owner:'PREP/REV'},
  {code:'E8_EFEL_P-2027',state:'MISSING',
   reason:'Fator elétrico horário pendente.',nextAction:'Solicitar dados ao operador',owner:'OP'}
 ]
};
const pdSections=['baseline','additionality'].map(id=>({
 guide:{id,title:id==='baseline'?'Linha de base':'Adicionalidade',
  version:id==='baseline'?4:1,missing:[]},
 latest:{number:2,guideVersion:id==='baseline'?3:1,sha256:'f'.repeat(64)},
 monitoringDependency:null
}));
const baselineSelections=[{
 id:'c0000000-0000-4000-8000-000000000001',
 projectId:project,period:'P-2027',version:2,observationYear:2027,
 cycle:'PBEV',state:'PREPARATORY_NOT_ADMITTED',stale:false,usableForE8:false,
 selectionSha256:'a'.repeat(64),gates:['EVR_AND_RATINGS_NOT_NORMATIVELY_ADMITTED']
}];

async function mock(page,actions,observed){
 await page.route('**'+root+'/metadata',r=>r.fulfill({json:{
  projectId:project,environment:'SANDBOX',title:'Rede aberta',state:'DRAFT',version:1
 }}));
 await page.route('**'+root+'/technical/capabilities',r=>r.fulfill({json:{
  projectId:project,environment:'SANDBOX',owner:false,actions,modules:{}
 }}));
 await page.route('**'+root+'/technical/intake',r=>r.fulfill({json:{
  projectId:project,environment:'SANDBOX',version:1,state:'RASCUNHO',
  reviewState:'NAO_AVALIADO',data:{candidateMethodology:'VM0038'},
  journey:{id:'VM0038_V1_1',candidate:'VM0038',title:'VM0038 v1.1',
    description:'Rede aberta DCFC',technicalTopics:[]},methodologies:[],
  outstandingGates:[],triageCandidate:'VM0038'
 }}));
 await page.route('**'+root+'/technical/preliminary-dossier',r=>r.fulfill({json:null}));
 const routes=[
  ['/typed-guides', {methodology:'VM0038_V1_1',guides:[guide('baseline'),guide('additionality')]}],
  ['/open-applicability', app],
  ['/open-calculation-readiness', calc],
  ['/open-monitoring/dossiers', [{reference:{code:'P-2027',version:1},
    state:'READY_FOR_NORMATIVE_REVIEW',stale:false,gates:[]}]],
  ['/open-monitoring/dossiers/P-2027/e8-fleet-selections',baselineSelections],
  ['/pd-sections',pdSections]
 ];
 for(const [path,json] of routes){
  await page.route('**'+root+'/technical'+path,r=>{
    observed.push(path);
    return r.fulfill({json});
  });
 }
}

test('UX-BL-01: baseline presents actual period selection, factor blockers and PD revision (mobile)',async()=>{
 const browser=await chromium.launch({executablePath});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}});
  const observed=[];
  await mock(page,['VIEW_INTAKE','VIEW_GUIDES','VIEW_OPEN_APPLICABILITY',
    'VIEW_OPEN_CALCULATION','VIEW_OPEN_MONITORING','VIEW_OPEN_E8_FLEET_SELECTION',
    'VIEW_DOSSIER'],observed);
  await page.goto(base+'/workspace/projects/'+project+'/technical?secao=linha-base&etapa=frota');
  await page.getByRole('heading',{name:/2\. EVR e comparáveis/}).waitFor();
  await page.getByText('Seleção v2 · ano 2027').waitFor();
  await page.getByText('Snapshot preparatório — não admitido').waitFor();
  await page.getByText('Estoque EVR e ratings continuam sem admissão normativa.').waitFor();
  await page.getByText('Referência: EVR_AND_RATINGS_NOT_NORMATIVELY_ADMITTED').waitFor();
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  assert.equal(await page.getByText('E8 não liberado para este projeto.').count(),0);
  assert.ok(observed.includes('/open-monitoring/dossiers/P-2027/e8-fleet-selections'));
  await page.getByRole('button',{name:'3. Fatores e E8'}).click();
  await page.getByText('Fator elétrico do período P-2027 · Dado faltante').waitFor();
  await page.getByText('Vínculo de frota do período P-2027 · Ainda não admitido').waitFor();
  await page.getByText('Papel previsto: operação').waitFor();
  await page.getByText('Papel previsto: preparação e revisão').waitFor();
  await page.getByText('Referência: E8_EFEL_P-2027').waitFor();
  await page.getByText('Cálculo: Aguardando dados e vínculos.').waitFor();
  await page.getByText('Fator elétrico horário pendente.').waitFor();
  assert.ok(page.url().includes('etapa=fatores'));
  await page.getByRole('button',{name:'6. Vínculo ao PD'}).click();
  await page.getByRole('heading',{name:'Como isso entrará no PD?'}).waitFor();
  await page.getByText('Guia textual teve nova revisão',{exact:false}).waitFor();
  await page.getByText('Rede de postos públicos contrafactual',{exact:false}).waitFor();
  await page.getByText('ainda não estão inseridas automaticamente',{exact:false}).waitFor();
  await page.getByRole('button',{name:'Abrir editor e histórico da seção correspondente'}).click();
  assert.ok(page.url().includes('secao=redigir'));
  assert.ok(page.url().includes('guia=baseline'));
 }finally{await browser.close();}
});

test('UX-ADD-01: declared rights, separate chronology and conditional route never assert approved',async()=>{
 const browser=await chromium.launch({executablePath});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:900}});
  const observed=[];
  await mock(page,['VIEW_INTAKE','VIEW_GUIDES','VIEW_OPEN_APPLICABILITY',
    'VIEW_DOSSIER'],observed);
  await page.goto(base+'/workspace/projects/'+project+'/technical?secao=adicionalidade&etapa=direitos');
  await page.getByRole('heading',{name:/3\. Direitos/}).waitFor();
  await page.getByText('Organização declarada, sem procuração').waitFor();
  await page.getByText('Divulgação aos motoristas ainda não comprovada.').waitFor();
  await page.getByRole('button',{name:'2. Cronologia'}).click();
  await page.getByRole('heading',{name:/2\. Cronologia/}).waitFor();
  await page.getByText('2027-01-01',{exact:false}).first().waitFor();
  await page.getByText('Compromisso anterior à consideração da receita de carbono.',{exact:false}).waitFor();
  assert.equal(observed.some(x=>x.includes('calculation')||x.includes('fleet')),false);
  await page.getByRole('button',{name:'1. Enquadramento'}).click();
  await page.getByText('Referência: VMD0049_ROUTE',{exact:true}).waitFor();
  await page.getByText('Lista positiva é hipótese dependente de edição válida.').waitFor();
  await page.getByText('Excedente regulatório exige revisão independente.').waitFor();
  assert.equal(await page.getByText('Adicionalidade aprovada',{exact:true}).count(),0);
  await page.getByRole('button',{name:'5. Vínculo ao PD'}).click();
  await page.getByText('seção correspondente da descrição de projeto',{exact:false}).waitFor();
 }finally{await browser.close();}
});

test('UX-ADD/PD: restricted collaborator does not query private rights, calculation, MR or PD',async()=>{
 const browser=await chromium.launch({executablePath});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}});
  const observed=[];
  await mock(page,['VIEW_INTAKE','VIEW_GUIDES'],observed);
  await page.goto(base+'/workspace/projects/'+project+'/technical?secao=adicionalidade&etapa=direitos');
  await page.getByRole('heading',{name:/3\. Direitos/}).waitFor();
  await page.getByText('Informações de direitos não acessíveis ou não declaradas.').waitFor();
  assert.equal(observed.includes('/open-applicability'),false);
  assert.equal(observed.includes('/open-calculation-readiness'),false);
  assert.equal(observed.includes('/pd-sections'),false);
  await page.getByRole('button',{name:'5. Vínculo ao PD'}).click();
  await page.getByText('não possui permissão para consultar a seção do PD').waitFor();
  assert.equal(observed.includes('/pd-sections'),false);
  await page.reload();
  await page.getByRole('heading',{name:/5\. Vínculo ao PD/}).waitFor();
  assert.equal(observed.includes('/pd-sections'),false);
 }finally{await browser.close();}
});

test('UX-RES-01: a premissa tipada permanece na etapa e a recusa conserva o rascunho',async()=>{
 const browser=await chromium.launch({executablePath});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}});
  const observed=[];
  const resumes=[];
  await mock(page,['VIEW_INTAKE','VIEW_GUIDES','SAVE_GUIDE','VIEW_OPEN_APPLICABILITY',
    'VIEW_OPEN_CALCULATION','VIEW_OPEN_MONITORING','VIEW_OPEN_E8_FLEET_SELECTION',
    'VIEW_DOSSIER'],observed);
  await page.route('**/journey/resume',route=>{
    resumes.push(route.request().postData()??'');
    return route.fulfill({status:204});
  });
  await page.route('**'+root+'/technical/typed-guides/baseline',route=>{
    if(route.request().method()==='PUT')
      return route.fulfill({status:412,contentType:'application/json',body:'{}'});
    return route.fallback();
  });
  await page.route('**'+root+'/technical/typed-guides',route=>{
    if(route.request().method()!=='GET')return route.fallback();
    const baseline=guide('baseline');
    baseline.editable=true;
    baseline.fields=baseline.fields.map(field=>field.key==='vehicleClass'
      ?{...field,options:['LDV']}:{...field,options:field.options??[]});
    const additionality=guide('additionality');
    additionality.editable=true;
    return route.fulfill({json:{methodology:'VM0038_V1_1',guides:[baseline,additionality]}});
  });
  await page.goto(base+'/workspace/projects/'+project+'/technical?secao=linha-base&etapa=premissas');
  await page.getByRole('heading',{name:/4\. Premissas/}).waitFor();
  await page.getByText('Papel previsto: preparação').waitFor();
  await page.getByText('a premissa tipada está declarada e não aprova a linha de base.').waitFor();
  assert.equal(await page.getByRole('button',{name:'Consultar notas textuais legadas e fontes do guia'}).count(),0);
  assert.ok(page.url().includes('etapa=premissas'));
  const field=page.getByLabel('Alternativa ao projeto');
  const draft='Premissa ainda não aceita pelo servidor';
  await field.fill(draft);
  await page.getByRole('button',{name:'Salvar revisão estruturada'}).click();
  await page.getByText('Outra revisão foi salva. Copie as alterações e recarregue.').waitFor();
  assert.equal(await field.inputValue(),draft);
  assert.ok(page.url().includes('secao=linha-base'));
  assert.ok(page.url().includes('etapa=premissas'));
  assert.ok(resumes.some(body=>body.includes('"screen":"linha-base"')&&body.includes('"step":"premissas"')));
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  await page.goto(base+'/workspace/projects/'+project+'/technical?secao=adicionalidade&etapa=cronologia');
  await page.getByRole('heading',{name:/2\. Cronologia/}).waitFor();
  assert.equal(await page.getByRole('button',{name:'Consultar histórico e solicitações do guia'}).count(),0);
  await page.getByText('não provam o ato nem a adicionalidade.').waitFor();
  await page.setViewportSize({width:1440,height:900});
  await page.getByRole('heading',{name:/2\. Cronologia/}).waitFor();
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
 }finally{await browser.close();}
});
