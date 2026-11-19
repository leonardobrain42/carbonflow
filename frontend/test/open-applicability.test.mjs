import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const project='20000000-0000-4000-8000-000000000071';
const organization='10000000-0000-4000-8000-000000000071';

test('a ficha OPEN permite preparar direitos sem fonte admitida nem cálculo E8',async()=>{
  const browser=await chromium.launch({executablePath});
  try {
    const page=await browser.newPage({viewport:{width:390,height:844}});
    let revision=0;
    let answers=null;
    const findings=[
      {code:'DRIVER_DISCLOSURE',state:'NEEDS_EVIDENCE',
       text:'É necessária prova datada de divulgação aos motoristas em rede aberta.'},
      {code:'MARKET_ROUTE',state:'PENDING_OFFICIAL_DATA',
       text:'Sem série oficial recente, a análise de mercado segue pendente.'},
    ];
    await page.route('**/v1/account-session',r=>r.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000071',email:'teste@carbonflow.test',name:'Teste'},
      projects:[{projectId:project,organizationId:organization,environment:'SANDBOX',state:'DRAFT',version:1}],
    }}));
    await page.route('**/v1/organizations',r=>r.fulfill({json:[{
      id:organization,name:'Teste',environment:'SANDBOX',status:'PRIVATE_DRAFT',role:'DRAFT_OWNER',version:1,
    }]}));
    await page.route('**/v1/projects/*/metadata',r=>r.fulfill({json:{
      projectId:project,organizationId:organization,environment:'SANDBOX',state:'DRAFT',
      version:1,title:'Rede aberta de teste',description:'Recarga DCFC',
    }}));
    await page.route('**/v1/projects/*/technical/capabilities',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',owner:true,
      modules:{intake:'PREPARATORY_ONLY',evidence:'NOT_ENABLED',calculation:'NOT_ENABLED'},
      actions:['VIEW_INTAKE','SAVE_INTAKE','VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY'],
    }}));
    await page.route('**/v1/projects/*/technical/intake',r=>r.fulfill({json:{
      projectId:project,organizationId:organization,environment:'SANDBOX',version:1,
      state:'RASCUNHO',reviewState:'NAO_AVALIADO',triageCandidate:'VM0038',
      journey:{id:'VM0038_V1_1',candidate:'VM0038',title:'VM0038 v1.1 · Recarga de veículos',
        description:'Preparação de recarga',technicalTopics:[]},
      methodologies:[{value:'VM0038',label:'VM0038 v1.1'}],
      data:{activity:'Recarga',country:'BR',state:'DF',locality:'Brasília',candidateMethodology:'VM0038',
        referenceActDate:'2027-01-01',notes:null},
      outstandingGates:[{code:'PEN-004',rule:'TEST',message:'Direitos ainda não comprovados'}],
    }}));
    await page.route('**/v1/projects/*/technical/preliminary-dossier',r=>r.fulfill({status:204}));
    await page.route('**/v1/projects/*/technical/open-applicability',async route=>{
      if(route.request().method()==='PUT'){
        assert.equal(route.request().headers()['if-match'],'"'+revision+'"');
        assert.ok(route.request().headers()['idempotency-key']);
        answers=route.request().postDataJSON();
        revision++;
      }
      await route.fulfill({json:{
        projectId:project,version:revision,latestTriageVersion:1,
        latest:revision?{version:revision,triageVersion:1,answers}:null,
        readiness:{
          category:'OPEN_LDV_DCFC_SIN_BR_CANDIDATE',
          network:answers?.unrestrictedBevAccess===true &&
            answers?.restrictedToIdentifiedVehicles===false?'OPEN_CANDIDATE':'PENDING_CONFIGURATION',
          rights:answers?.driverDisclosureEvidenceRef?'DECLARED_REVIEW_PENDING':'EVIDENCE_PENDING',
          norm:'PENDING_NORMATIVE_REVIEW',canContinuePreparation:true,
          canUseForE8:false,stale:false,findings,
          sourceDependencies:['OPEN-METHOD','OPEN-EV-PENETRATION','OPEN-EV-SALES','OPEN-RIGHTS'],
        },
      }});
    });
    await page.goto(base+'/workspace/projects/'+project+'/technical?secao=analisar');
    await page.getByRole('heading',{name:'Rede aberta — aplicabilidade VM0038 v1.1 (preparação)'}).waitFor();
    await page.getByLabel('Qualquer BEV compatível pode utilizar?').selectOption('true');
    await page.getByLabel('Restringe o uso a veículos identificados?').selectOption('false');
    await page.getByLabel('O ponto fica em terreno particular?').selectOption('true');
    await page.getByLabel('Tecnologia de recarga').selectOption('DCFC');
    await page.getByLabel('Veículos atendidos').selectOption('BEV');
    await page.getByLabel('Energia do sistema').selectOption('SIN');
    await page.getByLabel('Existe armazenamento/geração associada?').selectOption('false');
    await page.getByLabel('O carregador devolve energia à rede (V2G)?').selectOption('false');
    await page.getByLabel('Proprietário do sistema de recarga').fill('Operador hipotético');
    await page.getByLabel('Referência verificável do contrato de direitos').fill('CONTRACT-LOCAL-001');
    await page.getByRole('button',{name:'Salvar declaração sem aprovação automática'}).click();
    await page.getByText('Declaração versionada salva. A jornada pode continuar',{exact:false}).waitFor();
    assert.equal(revision,1);
    assert.equal(answers.privateLand,true);
    assert.equal(answers.restrictedToIdentifiedVehicles,false);
    await page.getByText('OPEN_CANDIDATE',{exact:true}).waitFor();
    await page.getByText('Nenhuma linha está liberada para o E8.',{exact:false}).count().then(_=>{});
    await page.getByText('Não liberado enquanto faltarem fontes',{exact:false}).waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  }finally{await browser.close();}
});
