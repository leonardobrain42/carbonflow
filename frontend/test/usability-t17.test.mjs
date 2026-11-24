import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const project='20000000-0000-4000-8000-000000000171';
const organization='10000000-0000-4000-8000-000000000171';

test('o roteiro observado acha a ação, cadastra a estação, anexa o arquivo, indica o direito e lê a lacuna do PD',async()=>{
  const browser=await chromium.launch({executablePath});
  try {
    const page=await browser.newPage({viewport:{width:1280,height:800}});
    const seen=[];
    let parties=[];
    let contracts=[];
    let sites=[];
    await page.route('**/v1/account-session',r=>r.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000171',email:'teste@carbonflow.test',name:'Teste'},
      projects:[{projectId:project,organizationId:organization,environment:'SANDBOX',state:'DRAFT',version:1}],
    }}));
    await page.route('**/v1/organizations',r=>r.fulfill({json:[{
      id:organization,name:'Teste',environment:'SANDBOX',status:'PRIVATE_DRAFT',role:'DRAFT_OWNER',version:1,
    }]}));
    await page.route('**/v1/projects/*/metadata',r=>r.fulfill({json:{
      projectId:project,organizationId:organization,environment:'SANDBOX',state:'DRAFT',
      version:1,title:'Rede do roteiro',description:'Recarga',
    }}));
    await page.route('**/v1/projects/*/technical/capabilities',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',owner:true,
      modules:{intake:'PREPARATORY_ONLY',evidence:'NOT_ENABLED',calculation:'NOT_ENABLED'},
      actions:['VIEW_INTAKE','SAVE_INTAKE','VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY',
        'VIEW_OPEN_MONITORING','SAVE_OPEN_MONITORING'],
    }}));
    await page.route('**/v1/projects/*/technical/intake',r=>r.fulfill({json:{
      projectId:project,organizationId:organization,environment:'SANDBOX',version:1,
      state:'RASCUNHO',reviewState:'NAO_AVALIADO',triageCandidate:'VM0038',
      journey:{id:'VM0038_V1_1',candidate:'VM0038',title:'VM0038 v1.1 · Recarga de veículos',
        description:'Preparação',technicalTopics:[]},
      methodologies:[{value:'VM0038',label:'VM0038 v1.1'}],
      data:{activity:'Recarga',country:'BR',state:'DF',locality:'Brasília',candidateMethodology:'VM0038',
        referenceActDate:'2027-01-01',notes:null},
      outstandingGates:[],
    }}));
    await page.route('**/v1/projects/*/technical/preliminary-dossier',r=>r.fulfill({status:204}));
    await page.route('**/v1/projects/*/journey/resume',r=>r.fulfill({status:204}));
    await page.route('**/v1/projects/*/technical/requirements',r=>r.fulfill({json:{
      projectId:project,methodology:'VM0038',openBranch:true,diagnosticVisible:true,
      canRunE8:false,canIssueVcu:false,exAnteRequiresMeasuredEnergy:false,
      limits:'Nenhum BE, PE, LE ou ER foi calculado. Este diagnóstico não aprova adicionalidade, direitos, fonte ou crédito.',
      summary:{mine:2,awaitingSource:0,awaitingReview:0,outOfScope:0},
      requirements:[
        {requirementId:'BOUNDARY',state:'ABSENT',situation:'Ainda não informado',
          reason:'A fronteira ainda não seleciona carregador DCFC do SIN.',
          ownerRole:'PREP',nextAction:'Selecionar as estações e os carregadores da fronteira.',
          actionLabel:'Abrir as estações',destination:{area:'technical',screen:'estacoes',step:null},
          group:'MINE',clientAction:true,blocksExAntePd:false,admitsE8:false,affectedOutputs:['monitoramento']},
        {requirementId:'DRIVER_DISCLOSURE',state:'NEEDS_EVIDENCE',situation:'Falta comprovante',
          reason:'Falta a prova datada de divulgação aos motoristas.',ownerRole:'PREP',
          nextAction:'Indicar a referência do comprovante na declaração.',actionLabel:'Abrir a declaração',
          destination:{area:'technical',screen:'analisar',step:null},group:'MINE',clientAction:true,
          blocksExAntePd:false,admitsE8:false,affectedOutputs:['direitos']},
      ],
    }}));
    await page.route('**/v1/projects/*/technical/open-sites',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',sites,chargers:[],
      boundary:{version:0,selected:[],state:'NO_SELECTION',findings:[],usableForE8:false},
    }}));
    await page.route('**/v1/projects/*/technical/open-sites/sites/*',r=>{
      assert.equal(r.request().method(),'PUT');
      const details=r.request().postDataJSON();
      const site={code:decodeURIComponent(r.request().url().split('/').at(-1)),version:1,details,
        recordedAt:'2026-11-21T23:00:00Z'};
      sites=[...sites,site];
      seen.push('station');
      return r.fulfill({json:site});
    });
    await page.route('**/v1/projects/*/technical/open-rights**',async route=>{
      const url=route.request().url();
      if(route.request().method()==='PUT' && url.includes('/files/CONTRACT/')){
        seen.push('file');
        await route.fulfill({status:201,json:{
          id:'55555555-5555-4555-8555-555555555555',state:'PENDING_REVIEW',usableForE8:false}});
        return;
      }
      if(route.request().method()==='PUT' && url.includes('/parties/')){
        const body=route.request().postDataJSON();
        parties=[...parties,{code:decodeURIComponent(url.split('/parties/')[1]),version:1,
          kind:body.kind,displayName:body.displayName}];
        seen.push('party');
      }
      if(route.request().method()==='PUT' && url.includes('/contracts/')){
        const body=route.request().postDataJSON();
        contracts=[...contracts,{code:decodeURIComponent(url.split('/contracts/')[1]),version:1,
          partyCode:body.partyCode,partyVersion:1,label:body.label}];
        seen.push('contract');
      }
      await route.fulfill({json:{parties,contracts,events:[],createsLegalPower:false,admitsRights:false}});
    });
    await page.route('**/v1/projects/*/technical/open-applicability',r=>r.fulfill({json:{
      projectId:project,version:0,latestTriageVersion:1,latest:null,
      readiness:{category:'PENDING',network:'PENDING_CONFIGURATION',rights:'EVIDENCE_PENDING',
        norm:'PENDING_NORMATIVE_REVIEW',canContinuePreparation:true,canUseForE8:false,
        stale:false,findings:[],sourceDependencies:[]},
      origins:[],
      pdFields:[{key:'rightsParty',section:'Titularidade',
        gap:'A resposta tipada não é copiada e esta prévia não marca o PD como pronto.',
        copiedIntoDocument:false,marksReady:false}],
    }}));

    await page.goto(base+'/workspace/projects/'+project+'/technical?secao=analisar');
    await page.getByRole('heading',{name:'O que este projeto ainda precisa'}).waitFor();
    await page.getByText('Próximo passo: Selecionar as estações e os carregadores da fronteira.').waitFor();
    await page.getByText('Próximo passo: Indicar a referência do comprovante na declaração.').waitFor();
    await page.getByRole('button',{name:'Abrir as estações'}).click();
    await page.getByRole('heading',{name:'Estações e fronteira'}).waitFor();
    await page.getByLabel('Código único da estação').fill('ST-01');
    await page.getByLabel('Nome da estação').fill('Estação Norte');
    await page.getByLabel('Município').fill('Brasília');
    await page.getByRole('button',{name:'Salvar versão da estação'}).click();
    await page.getByText('Estação cadastrada em nova revisão.',{exact:false}).waitFor();
    await page.getByRole('navigation',{name:'Seções da preparação técnica'})
      .getByRole('button',{name:'Direitos do projeto'}).click();
    await page.getByRole('heading',{name:'Titular, contrato e divulgação'}).waitFor();
    await page.getByLabel('Nome do titular neste projeto').fill('Operador hipotético');
    await page.getByRole('button',{name:'Registrar titular'}).click();
    await page.getByLabel('Titular registrado').selectOption({label:'Operador hipotético'});
    await page.getByLabel('Rótulo do contrato').fill('Contrato local');
    await page.getByRole('button',{name:'Registrar contrato'}).click();
    await page.getByLabel('Contrato registrado').selectOption({label:'Contrato local'});
    const uploaded=page.waitForResponse(response=>response.url().includes('/files/CONTRACT/'));
    await page.getByLabel('Arquivo do contrato').setInputFiles({
      name:'contrato.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\n%EOF\n'),
    });
    await uploaded;
    await page.getByText('Como estes campos entrariam no PD',{exact:true}).click();
    await page.getByText('não é copiada',{exact:false}).waitFor();
    await page.getByText('O PD não fica pronto.',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Ver situação do projeto'}).click();
    await page.getByText('Não liberado enquanto faltarem fontes',{exact:false}).waitFor();
    assert.deepEqual(seen,['station','party','contract','file']);
    assert.equal(sites[0].details.name,'Estação Norte');
    assert.equal(sites[0].details.operationalState,'PLANNED');
  } finally {await browser.close();}
});
