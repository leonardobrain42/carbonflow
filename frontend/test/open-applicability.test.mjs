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
      actions:['VIEW_INTAKE','SAVE_INTAKE','VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY',
        'VIEW_OPEN_MONITORING','SAVE_OPEN_MONITORING'],
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
    let parties=[];
    let contracts=[];
    let events=[];
    const catalog=()=>({parties,contracts,events,createsLegalPower:false,admitsRights:false});
    await page.route('**/v1/projects/*/technical/open-rights**',async route=>{
      const url=route.request().url();
      if(route.request().method()==='PUT' && url.includes('/files/')){
        const id=url.includes('/CONTRACT/')
          ?'33333333-3333-4333-8333-333333333333'
          :'44444444-4444-4444-8444-444444444444';
        await route.fulfill({status:201,json:{id,state:'PENDING_REVIEW',usableForE8:false}});
        return;
      }
      if(route.request().method()==='PUT' && url.includes('/parties/')){
        const body=route.request().postDataJSON();
        const code=decodeURIComponent(url.split('/parties/')[1]);
        parties=[...parties,{code,version:1,kind:body.kind,displayName:body.displayName}];
      }
      if(route.request().method()==='PUT' && url.includes('/events/')){
        const body=route.request().postDataJSON();
        const code=decodeURIComponent(url.split('/events/')[1]);
        events=[...events,{code,version:1,kind:body.kind,eventOn:body.eventOn,documentRef:body.documentRef}];
      }
      if(route.request().method()==='PUT' && url.includes('/contracts/')){
        const body=route.request().postDataJSON();
        const code=decodeURIComponent(url.split('/contracts/')[1]);
        contracts=[...contracts,{code,version:1,partyCode:body.partyCode,partyVersion:1,label:body.label}];
      }
      await route.fulfill({json:catalog()});
    });
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
          market:{
            citation:'VMD0049 v1.1 Tabela 1',citedStockPercent:'0,7',citedVintageYear:2024,
            stockLimitPercent:'7,5',salesLimitPercent:'30',citationBelowStockLimit:true,
            stockSeries:'CITATION_NOT_SERIES',salesSeries:'ABSENT',
            stockSeriesLabel:'Citação da lista positiva. Não é a série oficial do teste.',
            salesSeriesLabel:'Ausente. Ausência não é zero.',
            outcome:'PENDING_OFFICIAL_DATA',closesApplicability:false,usableForE8:false,
            reason:'A citação de 0,7% de estoque em 2024 na VMD0049 v1.1 não fecha o teste de aplicabilidade da VM0038 §4 (1)(b).',
          },
          sourceDependencies:['OPEN-METHOD','OPEN-EV-PENETRATION','OPEN-EV-SALES','OPEN-RIGHTS'],
        },
        origins:[{field:'rightsParty',version:'v1',situation:'Registro do projeto, não admitido',
          rule:'CF-IMP-013',role:'Revisão interna. Este papel não designa uma pessoa nem cria permissão.',
          concludes:false}],
        pdFields:[{key:'rightsParty',section:'Titularidade',
          gap:'A resposta tipada não é copiada e esta prévia não marca o PD como pronto.',
          copiedIntoDocument:false,marksReady:false}],
      }});
    });
    let requirementReads=0;
    await page.route('**/v1/projects/*/technical/requirements',r=>{
      requirementReads++;
      return r.fulfill({json:{
      projectId:project,methodology:'VM0038',openBranch:true,diagnosticVisible:true,
      canRunE8:false,canIssueVcu:false,exAnteRequiresMeasuredEnergy:false,
      limits:'Nenhum BE, PE, LE ou ER foi calculado.',
      summary:{mine:1,awaitingSource:0,awaitingReview:1,outOfScope:1},
      requirements:[{
        requirementId:'DRIVER_DISCLOSURE',state:'NEEDS_EVIDENCE',situation:revision>0?'Atualizado após salvar declaração':'Falta comprovante',
        reason:'É necessária prova datada de divulgação aos motoristas.',ownerRole:'PREP',
        nextAction:'Indicar a referência do comprovante.',actionLabel:'Abrir a declaração',
        destination:{area:'technical',screen:'analisar',step:null},group:'MINE',
        clientAction:true,blocksExAntePd:false,admitsE8:false,affectedOutputs:['direitos'],
      }],
    }});
    });
    await page.route('**/v1/projects/*/journey/resume',r=>r.fulfill({status:204}));
    await page.route('**/v1/projects/*/triage',r=>r.fulfill({json:{version:3,answers:{
      '1':{objective:'Rede aberta',activityType:'MOBILIDADE_ELETRICA',methodologyCandidate:'VM0038'},
      '3':{access:'PUBLIC',network:'OPEN',chargerType:'DCFC',propulsion:'BEV',
           electricitySource:'SIN',associatedInfrastructure:'NO',vehicleClass:'LDV'},
    }}}));
    await page.route('**/v1/projects/*/technical/open-sites',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',sites:[],chargers:[],
      boundary:{version:0,selected:[],state:'NO_SELECTION',findings:[],usableForE8:false},
    }}));
    const nav=page.getByRole('navigation',{name:'Seções da preparação técnica'});
    const saved=async label=>{
      await page.getByRole('button',{name:label}).click();
      await page.getByText('Declaração versionada salva. A jornada pode continuar',{exact:false}).waitFor();
    };
    // 1. Network access: its own screen, one persisted revision.
    await page.goto(base+'/workspace/projects/'+project+'/technical?secao=rede');
    await page.getByRole('heading',{name:'Acesso aos carregadores'}).waitFor();
    // The menu counts this account's own actions per screen, from the requirement projection.
    await nav.getByRole('button',{name:/Situação do projeto.*1 ação sua/}).waitFor();
    assert.equal(await nav.getByRole('button',{name:/Acesso à rede.*ação/}).count(),0);
    assert.equal(await page.getByLabel('Nome do titular neste projeto').count(),0,'rights do not belong to the network screen');
    // The owner's triage answers are offered for empty fields; nothing is saved until the person saves.
    const fromTriage=page.getByRole('group',{name:'Respostas da triagem'});
    await fromTriage.getByText('Tipo de recarga: DCFC').waitFor();
    await fromTriage.getByText('Acesso aos carregadores: Aberto ao público').waitFor();
    assert.equal(revision,0);
    await fromTriage.getByRole('button',{name:'Usar respostas da triagem'}).click();
    assert.equal(revision,0,'applying suggestions does not save');
    await fromTriage.waitFor({state:'detached'});
    assert.equal(await page.getByLabel('Qualquer BEV compatível pode utilizar?').inputValue(),'true');
    assert.equal(await page.getByLabel('Restringe o uso a veículos identificados?').inputValue(),'false');
    assert.equal(await page.getByLabel('Tecnologia de recarga').inputValue(),'DCFC');
    assert.equal(await page.getByLabel('Veículos atendidos').inputValue(),'BEV');
    assert.equal(await page.getByLabel('Energia do sistema').inputValue(),'SIN');
    assert.equal(await page.getByLabel('Existe armazenamento/geração associada?').inputValue(),'false');
    await page.getByLabel('O ponto fica em terreno particular?').selectOption('true');
    await page.getByLabel('O carregador devolve energia à rede (V2G)?').selectOption('false');
    await saved('Salvar acesso sem aprovação automática');
    assert.equal(answers.chargerType,'DCFC');
    assert.equal(answers.unrestrictedBevAccess,true);
    assert.equal(answers.associatedInfrastructure,false);
    assert.equal(revision,1);

    // 2. Rights: holder, contract, files and disclosure, without the network questions.
    await nav.getByRole('button',{name:'Direitos do projeto'}).click();
    await page.getByRole('heading',{name:'Titular, contrato e divulgação'}).waitFor();
    assert.equal(await page.getByLabel('Qualquer BEV compatível pode utilizar?').count(),0,'network questions do not belong to the rights screen');
    await page.getByLabel('Nome do titular neste projeto').fill('Operador hipotético');
    await page.getByRole('button',{name:'Registrar titular'}).click();
    await page.getByLabel('Titular registrado').selectOption({label:'Operador hipotético'});
    await page.getByLabel('Rótulo do contrato').fill('Contrato local');
    await page.getByRole('button',{name:'Registrar contrato'}).click();
    await page.getByLabel('Contrato registrado').selectOption({label:'Contrato local'});
    const contractFile=page.waitForResponse(response=>response.url().includes('/files/CONTRACT/'));
    await page.getByLabel('Arquivo do contrato').setInputFiles({
      name:'contrato.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\n%EOF\n'),
    });
    await contractFile;
    const disclosureFile=page.waitForResponse(response=>response.url().includes('/files/DISCLOSURE/'));
    await page.getByLabel('Arquivo da divulgação').setInputFiles({
      name:'divulgacao.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\n%EOF\n'),
    });
    await disclosureFile;
    await saved('Salvar direitos sem aprovação automática');
    assert.equal(revision,2);
    await page.getByText('Como estes campos entrariam no PD',{exact:true}).click();
    await page.getByText('não é copiada',{exact:false}).waitFor();
    await page.getByText('O PD não fica pronto.',{exact:false}).waitFor();
    await page.getByText('Ver origem do titular',{exact:true}).click();
    await page.getByText('CF-IMP-013',{exact:true}).waitFor();
    await page.getByText('não designa uma pessoa',{exact:false}).waitFor();

    // 3. Dates live with stations and boundary.
    await nav.getByRole('button',{name:'Estações e fronteira'}).click();
    await page.getByRole('heading',{name:'Datas do projeto'}).waitFor();
    assert.equal(await page.getByLabel('Nome do titular neste projeto').count(),0);
    await page.getByLabel('Data do início operacional da estação').fill('2027-02-01');
    await page.getByLabel('Referência do documento do início operacional').fill('ATO-OP-1');
    const operationalEvent=page.waitForResponse(response=>
      response.url().includes('/events/') && response.request().method()==='PUT');
    await page.getByRole('button',{name:'Registrar início operacional',exact:true}).click();
    await operationalEvent;
    await page.getByLabel('Data do início de crédito VCS').fill('2027-03-01');
    await page.getByLabel('Referência do documento do início de crédito VCS').fill('ATO-VCS-1');
    const vcsEvent=page.waitForResponse(response=>
      response.url().includes('/events/') && response.request().method()==='PUT');
    await page.getByRole('button',{name:'Registrar início de crédito VCS',exact:true}).click();
    await vcsEvent;
    await saved('Salvar datas sem aprovação automática');
    assert.equal(revision,3);

    // 4. The status screen only reads: no form, one diagnosis, rereads after the saves.
    await nav.getByRole('button',{name:'Situação do projeto'}).click();
    await page.getByText('Atualizado após salvar declaração',{exact:true}).waitFor();
    assert.ok(requirementReads>=1,'A projeção de requisitos deve ser lida ao abrir a situação do projeto');
    assert.equal(await page.getByLabel('Qualquer BEV compatível pode utilizar?').count(),0);
    assert.equal(await page.getByLabel('Nome do titular neste projeto').count(),0);
    assert.equal(await page.getByRole('button',{name:/^Salvar /}).count(),0,'a tela de situação não grava nada');
    assert.equal(answers.privateLand,true);
    assert.equal(answers.restrictedToIdentifiedVehicles,false);
    assert.equal(answers.rightsPartyCode,parties[0].code);
    assert.equal(answers.rightsContractCode,contracts[0].code);
    assert.equal(answers.operationalEventCode,events.find(event=>event.kind==='OPERATIONAL_START').code);
    assert.equal(answers.vcsEventCode,events.find(event=>event.kind==='VCS_START').code);
    assert.equal(answers.contractFileId,'33333333-3333-4333-8333-333333333333');
    assert.equal(answers.disclosureFileId,'44444444-4444-4444-8444-444444444444');
    assert.equal(answers.chargingOwner,null);
    const ficha=page.getByRole('region',{name:'Aplicabilidade de rede aberta'});
    await ficha.getByText('Rede aberta candidata',{exact:false}).waitFor();
    await ficha.getByText('Referência: OPEN_CANDIDATE',{exact:true}).waitFor();
    // One list: the declaration findings live in the pending list, not repeated inside the ficha.
    assert.equal(await ficha.getByText('O que ainda precisa ser comprovado?').count(),0);
    await page.locator('carbonflow-project-requirements').getByText('Divulgação aos motoristas',{exact:true}).waitFor();
    await ficha.getByRole('heading',{name:'Teste de mercado §4 e citação da lista positiva'}).waitFor();
    await ficha.getByText('0,7% de estoque em 2024 · VMD0049 v1.1 Tabela 1').waitFor();
    await ficha.getByText('7,5%',{exact:true}).waitFor();
    await ficha.getByText('30% na média trienal').waitFor();
    await ficha.getByText('não fecha o teste').waitFor();
    await ficha.getByText('Ausente. Ausência não é zero.').waitFor();
    const closes=ficha.locator('dt',{hasText:'Fecha a aplicabilidade'}).locator('xpath=..');
    assert.equal((await closes.locator('dd').innerText()).trim(),'Não');
    await page.getByText('Não liberado enquanto faltarem fontes',{exact:false}).waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.setViewportSize({width:1440,height:900});
    await ficha.getByRole('heading',{name:'Teste de mercado §4 e citação da lista positiva'}).waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  }finally{await browser.close();}
});
