import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const project='20000000-0000-4000-8000-000000000124';
const organization='10000000-0000-4000-8000-000000000124';

test('analisar mostra ação, espera e recorte sem concluir as quatro pendências',async()=>{
  const browser=await chromium.launch({executablePath});
  try {
    const page=await browser.newPage({viewport:{width:390,height:844}});
    await page.route('**/v1/account-session',r=>r.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000124',email:'teste@carbonflow.test',name:'Teste'},
      projects:[{projectId:project,organizationId:organization,environment:'SANDBOX',state:'DRAFT',version:1}],
    }}));
    await page.route('**/v1/organizations',r=>r.fulfill({json:[{
      id:organization,name:'Teste',environment:'SANDBOX',status:'PRIVATE_DRAFT',role:'DRAFT_OWNER',version:1,
    }]}));
    await page.route('**/v1/projects/*/metadata',r=>r.fulfill({json:{
      projectId:project,organizationId:organization,environment:'SANDBOX',state:'DRAFT',
      version:1,title:'Rede do diagnóstico',description:'Recarga',
    }}));
    await page.route('**/v1/projects/*/technical/capabilities',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',owner:true,
      modules:{intake:'PREPARATORY_ONLY',evidence:'NOT_ENABLED',calculation:'NOT_ENABLED'},
      actions:['VIEW_INTAKE','SAVE_INTAKE','VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY','VIEW_OPEN_CALCULATION'],
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
    await page.route('**/v1/projects/*/technical/open-applicability',r=>r.fulfill({json:{
      projectId:project,version:0,latestTriageVersion:1,latest:null,
      readiness:{category:'PENDING',network:'PENDING_CONFIGURATION',rights:'EVIDENCE_PENDING',
        norm:'PENDING_NORMATIVE_REVIEW',canContinuePreparation:true,canUseForE8:false,
        stale:false,findings:[],sourceDependencies:[]},
    }}));
    await page.route('**/v1/projects/*/technical/requirements',r=>r.fulfill({json:{
      projectId:project,methodology:'VM0038',openBranch:true,diagnosticVisible:true,
      canRunE8:false,canIssueVcu:false,exAnteRequiresMeasuredEnergy:false,
      limits:'Nenhum BE, PE, LE ou ER foi calculado. Este diagnóstico não aprova adicionalidade, direitos, fonte ou crédito.',
      summary:{mine:1,awaitingSource:1,awaitingReview:1,outOfScope:1},
      requirements:[
        {requirementId:'BOUNDARY',methodologyId:'VM0038',edition:'1.1',ruleRef:'CF-OPEN-009',
          state:'ABSENT',situation:'Ainda não informado',
          reason:'A fronteira ainda não seleciona carregador DCFC do SIN. Estação planejada não produz energia medida, e a falta de leitura não vale zero.',
          ownerRole:'PREP',nextAction:'Selecionar as estações e os carregadores da fronteira.',
          actionLabel:'Abrir as estações',destination:{area:'technical',screen:'estacoes',step:null},
          group:'MINE',clientAction:true,blocksExAntePd:false,admitsE8:false,affectedOutputs:['monitoramento']},
        {requirementId:'ED_METER',methodologyId:'VM0038',edition:'1.1',ruleRef:'CF-OPEN-009',
          state:'ABSENT',situation:'Ainda não informado',
          reason:'Falta o medidor da energia entregue (ED), a jusante, distinto da energia consumida.',
          ownerRole:'OP',nextAction:'Cadastrar o instrumento de ED na fronteira atual.',
          actionLabel:'Abrir o monitoramento',destination:{area:'technical',screen:'monitorar',step:null},
          group:'MINE',clientAction:true,blocksExAntePd:false,admitsE8:false,affectedOutputs:['monitoramento']},
        {requirementId:'DRIVER_DISCLOSURE',methodologyId:'VM0038',edition:'1.1',ruleRef:'CF-OPEN-004',
          state:'NEEDS_EVIDENCE',situation:'Falta comprovante',
          reason:'Falta a prova datada de divulgação aos motoristas.',ownerRole:'PREP',
          nextAction:'Indicar a referência do comprovante na declaração.',actionLabel:'Abrir a declaração',
          destination:{area:'technical',screen:'analisar',step:null},group:'MINE',clientAction:true,
          blocksExAntePd:false,admitsE8:false,affectedOutputs:['direitos']},
        {requirementId:'MARKET_ROUTE',state:'AWAITING_NORMATIVE_EVALUATION',situation:'Aguardando avaliação da equipe',
          reason:'O teste de mercado da VM0038 §4 aguarda dados oficiais e a avaliação da equipe.',
          ownerRole:'equipe CarbonFlow',nextAction:'Aguardar a equipe CarbonFlow.',actionLabel:null,
          destination:null,group:'AWAITING_SOURCE',clientAction:false,blocksExAntePd:false,admitsE8:false,
          affectedOutputs:['aplicabilidade']},
        {requirementId:'REGULATORY_SURPLUS',state:'AWAITING_NORMATIVE_EVALUATION',situation:'Aguardando avaliação da equipe',
          reason:'Preencher a declaração não aprova o excedente regulatório.',ownerRole:'REV',
          nextAction:'Aguardar parecer humano.',actionLabel:null,destination:null,group:'AWAITING_REVIEW',
          clientAction:false,blocksExAntePd:false,admitsE8:false,affectedOutputs:['adicionalidade']},
        {requirementId:'ED_EC',state:'NOT_INCIDENT',situation:'Não se aplica a este ato',
          reason:'ED e EC pertencem ao monitoramento e não são condição do documento ex ante.',
          ownerRole:'OP',nextAction:'Nenhuma ação agora.',actionLabel:null,destination:null,
          group:'OUT_OF_SCOPE',clientAction:false,blocksExAntePd:false,admitsE8:false,
          affectedOutputs:['monitoramento']},
      ],
    }}));
    await page.goto(base+'/workspace/projects/'+project+'/technical?secao=analisar');
    await page.getByRole('heading',{name:'O que este projeto ainda precisa'}).waitFor();
    await page.getByRole('heading',{name:'Ações desta conta'}).waitFor();
    await page.getByRole('heading',{name:'Aguardando fontes'}).waitFor();
    await page.getByRole('heading',{name:'Aguardando revisão'}).waitFor();
    await page.getByRole('heading',{name:'Fora do recorte'}).waitFor();
    await page.getByText('Nenhum BE, PE, LE ou ER foi calculado.').waitFor();
    await page.getByText('Divulgação aos motoristas',{exact:true}).waitFor();
    await page.getByText('Fronteira do projeto',{exact:true}).waitFor();
    await page.getByText('Medidor da energia entregue',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Abrir as estações'}).waitFor();
    await page.getByText('Próximo passo: Indicar a referência do comprovante na declaração.').waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    const notes=page.getByText('Papel, regra e referência',{exact:true});
    assert.equal(await notes.count(),6);
    for (let i=0;i<6;i++) await notes.nth(i).click();
    await page.getByText('Não bloqueia o documento ex ante.').waitFor();
    await page.getByText('Metodologia aplicável: VM0038 v1.1',{exact:true}).first().waitFor();
    await page.getByText('Regra: CF-OPEN-004',{exact:true}).waitFor();
    await page.getByText('Regra: CF-OPEN-009',{exact:true}).first().waitFor();
    await page.getByText('Referência: DRIVER_DISCLOSURE',{exact:true}).waitFor();
    await page.getByText('Referência: BOUNDARY',{exact:true}).waitFor();
    await page.getByText('Papel previsto: preparação').first().waitFor();
    await page.getByText('Papel previsto: revisão').waitFor();
    await page.getByText('Papel previsto: operação').first().waitFor();
    await page.getByText('Papel previsto: equipe CarbonFlow').waitFor();
    await page.getByText('Nenhuma pessoa foi designada por este diagnóstico.').first().waitFor();
    assert.equal(await page.getByText('PENDING_OFFICIAL_DATA').count(),0);
    assert.equal(await page.getByText('Pré-cálculo de emissões',{exact:false}).count(),0);
    assert.equal(await page.getByRole('heading',{name:'Prontidão para quantificação · rede aberta'}).count(),0);
    const action=page.getByRole('button',{name:'Abrir a declaração'});
    await action.focus();
    assert.equal(await action.evaluate(node=>document.activeElement===node),true);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await page.setViewportSize({width:1440,height:900});
    await page.getByRole('heading',{name:'Aguardando fontes'}).waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  } finally {
    await browser.close();
  }
});

test('diagnóstico recusa cálculo liberado e anuncia a falha de leitura',async()=>{
  const browser=await chromium.launch({executablePath});
  try {
    const page=await browser.newPage({viewport:{width:390,height:844}});
    await page.route('**/v1/account-session',r=>r.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000124',email:'teste@carbonflow.test',name:'Teste'},
      projects:[{projectId:project,organizationId:organization,environment:'SANDBOX',state:'DRAFT',version:1}],
    }}));
    await page.route('**/v1/organizations',r=>r.fulfill({json:[{
      id:organization,name:'Teste',environment:'SANDBOX',status:'PRIVATE_DRAFT',role:'DRAFT_OWNER',version:1,
    }]}));
    await page.route('**/v1/projects/*/metadata',r=>r.fulfill({json:{
      projectId:project,organizationId:organization,environment:'SANDBOX',state:'DRAFT',
      version:1,title:'Rede do diagnóstico',description:'Recarga',
    }}));
    await page.route('**/v1/projects/*/technical/capabilities',r=>r.fulfill({json:{
      projectId:project,environment:'SANDBOX',owner:true,
      modules:{intake:'PREPARATORY_ONLY',evidence:'NOT_ENABLED',calculation:'NOT_ENABLED'},
      actions:['VIEW_INTAKE','VIEW_OPEN_APPLICABILITY','VIEW_OPEN_CALCULATION'],
    }}));
    await page.route('**/v1/projects/*/technical/intake',r=>r.fulfill({json:{
      projectId:project,organizationId:organization,environment:'SANDBOX',version:1,
      state:'RASCUNHO',reviewState:'NAO_AVALIADO',triageCandidate:'VM0038',
      journey:{id:'VM0038_V1_1',candidate:'VM0038',title:'VM0038 v1.1',
        description:'Preparação',technicalTopics:[]},
      methodologies:[{value:'VM0038',label:'VM0038 v1.1'}],
      data:{activity:'Recarga',country:'BR',state:'DF',locality:'Brasília',candidateMethodology:'VM0038',
        referenceActDate:'2027-01-01',notes:null},
      outstandingGates:[],
    }}));
    await page.route('**/v1/projects/*/technical/preliminary-dossier',r=>r.fulfill({status:204}));
    await page.route('**/v1/projects/*/journey/resume',r=>r.fulfill({status:204}));
    await page.route('**/v1/projects/*/technical/open-applicability',r=>r.fulfill({json:{
      projectId:project,version:0,latestTriageVersion:1,latest:null,
      readiness:{category:'PENDING',network:'PENDING_CONFIGURATION',rights:'EVIDENCE_PENDING',
        norm:'PENDING_NORMATIVE_REVIEW',canContinuePreparation:true,canUseForE8:false,
        stale:false,findings:[],sourceDependencies:[]},
    }}));
    await page.route('**/v1/projects/*/technical/requirements',r=>r.fulfill({json:{
      projectId:project,methodology:'VM0038',openBranch:true,diagnosticVisible:true,
      canRunE8:true,canIssueVcu:false,exAnteRequiresMeasuredEnergy:false,
      limits:'não deve aparecer',summary:{mine:0,awaitingSource:0,awaitingReview:0,outOfScope:0},
      requirements:[],
    }}));
    await page.goto(base+'/workspace/projects/'+project+'/technical?secao=analisar');
    const alert=page.getByRole('alert');
    await alert.waitFor();
    assert.match(await alert.innerText(),/Não foi possível ler o diagnóstico/);
    assert.equal(await page.getByText('não deve aparecer').count(),0);
    assert.equal(await page.getByRole('button',{name:'Abrir a declaração'}).count(),0);
    const retry=page.getByRole('button',{name:'Tentar novamente'});
    await retry.focus();
    assert.equal(await retry.evaluate(node=>document.activeElement===node),true);
    await page.keyboard.press('Enter');
    await alert.waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await page.setViewportSize({width:1440,height:900});
    await alert.waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  } finally {
    await browser.close();
  }
});
