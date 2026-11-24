import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const project = '20000000-0000-4000-8000-000000000097';
const organization = '10000000-0000-4000-8000-000000000097';

test('a tentativa de cálculo registra o bloqueio sem enviar número', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440, height:900}});
    let posted = null;
    let secondPeriodMatch = null;
    let showRehearsal = false;
    let viewPd = false;
    let invalidSelection=false;
    const annualYears = [];
    const rehearsal = {
      id:'30000000-0000-4000-8000-000000000098',
      projectId:project, period:'M1', version:2, state:'REVIEWED_INTERNAL', stale:false,
      canIssueVcu:false, engine:'PURE_MATH_TEST_ONLY_NOT_ADMITTED',
      energyBindingId:null, fleetSelectionId:null, inputSha256:'def', gates:[],
      equationPlan:[], memory:{
        usableForE8:false, beTco2e:'4.05', peTco2e:'1.3', leTco2e:'0',
        erTco2e:'2.75', erDisplay:'2.75',
        hourlyPe:{windowStart:'2027-02-01T00:00:00Z',windowEndExclusive:'2027-02-01T02:00:00Z',
          region:'SIN',edKwh:'2700',ecKwh:'3000',peTco2e:'1.3',hours:[
            {hourStart:'2027-02-01T00:00:00Z',edKwh:'900',ecKwh:'1000',factorTco2ePerKwh:'0.0005',peTco2e:'0.5'},
            {hourStart:'2027-02-01T01:00:00Z',edKwh:'1800',ecKwh:'2000',factorTco2ePerKwh:'0.0004',peTco2e:'0.8'},
          ]},
      }, createdAt:'2026-11-21T00:00:00Z',
    };
    const blocked = {
      id:'30000000-0000-4000-8000-000000000097',
      projectId:project, period:'M1', version:1, state:'BLOCKED', stale:false,
      canIssueVcu:false, engine:null, energyBindingId:null, fleetSelectionId:null,
      inputSha256:'abc', gates:[{
        code:'IR', state:'MISSING',
        reason:'A melhoria tecnológica não tem valor normalizado armazenado. A tentativa não inventa 1.',
        nextAction:'Completar o vínculo e a admissão antes de executar', owner:'PREP/REV',
      },{
        code:'HOURLY_EFEL_NOT_UNIFORM', state:'REVIEW_REQUIRED',
        reason:'As horas do vínculo não compartilham um único fator elétrico. O motor não calcula média.',
        nextAction:'Manter um único fator elétrico horário ou esperar o cálculo por hora', owner:'PREP/REV',
      }], equationPlan:[], memory:null, createdAt:'2026-11-21T00:00:00Z',
    };
    await page.route('**/v1/account-session', route => route.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000097', email:'teste@carbonflow.test', name:'Teste'},
      projects:[{projectId:project, organizationId:organization, environment:'SANDBOX', state:'DRAFT', version:1}],
    }}));
    await page.route('**/v1/organizations', route => route.fulfill({json:[{
      id:organization, name:'Teste', environment:'SANDBOX', status:'PRIVATE_DRAFT', role:'DRAFT_OWNER', version:1,
    }]}));
    await page.route('**/v1/projects/*/metadata', route => route.fulfill({json:{
      projectId:project, organizationId:organization, environment:'SANDBOX', state:'DRAFT',
      version:1, title:'Rede aberta de teste', description:'Recarga DCFC',
    }}));
    await page.route('**/v1/projects/*/technical/capabilities', route => route.fulfill({json:{
      projectId:project, environment:'SANDBOX', owner:true,
      modules:{intake:'PREPARATORY_ONLY', calculation:'NOT_ENABLED'},
      actions:['VIEW_INTAKE','VIEW_OPEN_CALCULATION','RECORD_OPEN_E8_RUN',...(viewPd ? ['VIEW_DOSSIER'] : [])],
    }}));
    await page.route('**/v1/projects/*/technical/intake', route => route.fulfill({json:{
      projectId:project, organizationId:organization, environment:'SANDBOX', version:1,
      state:'RASCUNHO', reviewState:'NAO_AVALIADO', triageCandidate:'VM0038',
      journey:{id:'VM0038_V1_1', candidate:'VM0038', title:'VM0038 v1.1',
        description:'Rede aberta', technicalTopics:[]},
      methodologies:[{value:'VM0038', label:'VM0038 v1.1'}],
      data:{activity:'Recarga', country:'BR', state:'DF', locality:'Brasília',
        candidateMethodology:'VM0038', referenceActDate:'2027-01-01', notes:null},
      outstandingGates:[],
    }}));
    await page.route('**/v1/projects/*/technical/preliminary-dossier', route => route.fulfill({status:204}));
    await page.route('**/v1/projects/*/technical/open-applicability', route => route.fulfill({status:404}));
    await page.route('**/v1/projects/*/technical/open-parameter-bindings', route => route.fulfill({json:[]}));
    await page.route('**/v1/projects/*/technical/open-calculation-years/*', route => {
      const projectYear=Number(route.request().url().split('/').at(-1));
      annualYears.push(projectYear);
      const complete=showRehearsal&&projectYear===1;
      return route.fulfill({json:{
        projectId:project,projectYear,state:complete?'REVIEWED_INTERNAL':'BLOCKED',
        windowStart:'2027-02-01T03:00:00Z',windowEndExclusive:'2028-02-01T03:00:00Z',
        usableForE8:false,canIssueVcu:false,manifestSha256:'a'.repeat(64),
        totals:complete?{beTco2e:'12',peTco2e:'4',leTco2e:'0',erTco2e:'5.5',erDisplay:'5.50'}:null,
        members:[{period:'M1',runId:rehearsal.id,version:2,inputSha256:'b'.repeat(64),
          windowStart:'2027-02-01T03:00:00Z',windowEndExclusive:'2027-08-01T03:00:00Z',
          state:'REVIEWED_INTERNAL',stale:false}],
        issues:complete?[]:[{code:'COVERAGE_GAP',period:'M2',from:'2027-08-01T03:00:00Z',
          until:'2028-02-01T03:00:00Z',detail:'Falta uma janela de monitoramento neste intervalo do ano do projeto.'}],
      }});
    });
    await page.route('**/v1/projects/*/technical/open-calculation-readiness', route => route.fulfill({json:{
      projectId:project, environment:'SANDBOX', methodology:'VM0038', scenario:'OPEN',
      workflowState:'PREPARATION', calculationState:'BLOCKED_MISSING_DATA',
      canContinuePreparation:true, canRunE8:false, canIssueVcu:false,
      emissions:{beTco2e:null, peTco2e:null, leTco2e:null, erTco2e:null, erDisplay:null},
      gates:[{
        code:'EVR', state:'MISSING_BINDING',
        reason:'Não há estoque nacional por modelo e ano admitido e vinculado ao projeto.',
        nextAction:'Selecionar fonte EVR e avaliar cobertura', owner:'PREP',
      },{
        code:'E8_FLEET_SELECTION_M1', state:'NOT_ADMITTED',
        reason:'Há uma seleção preparatória de frota. Ela não está admitida.',
        nextAction:'Aguardar a avaliação da equipe', owner:'PREP',
      }], equationPlan:[{
        outputCode:'BE', ruleReference:'VM0038-1.1-EQ1', unit:'tCO2e',
        description:'Linha de base ainda sem execução.', requiredInputs:['ED'],
        blockedBy:['ED'], status:'NOT_CALCULATED',
        whyNotCalculated:'Não calculado. Não há energia entregue admitida por carregador e período.',
      }],
    }}));
    await page.route('**/v1/projects/*/technical/open-monitoring/dossiers', route => route.fulfill({json:[
      {reference:{code:'M1'}}, {reference:{code:'M2'}},
    ]}));
    const reference=(code,basis,id=null,state='MISSING')=>({code,basis,id,
      version:id?2:null,sha256:id?'c'.repeat(64):null,state});
    const selection=(period)=>({
      policyVersion:'OPEN-E8-INPUT-SELECTION@2',projectId:project,
      period:invalidSelection?'WRONG':period,usableForE8:false,canIssueVcu:false,
      timing:period==='M1'?{windowStart:null,windowEndExclusive:null,projectYear:null,sourceYear:null}:
        {windowStart:'2029-02-01T00:00:00Z',windowEndExclusive:'2029-02-01T02:00:00Z',projectYear:2,sourceYear:2029},
      timingGate:period==='M1'?'RUN_WINDOW_MISSING':null,
      inputs:[
        reference('ED_EC_EFEL','HOURLY_PERIOD_BINDING'),
        reference('EVR_RATINGS','FLEET_PERIOD_SELECTION'),
        reference('EF_FUEL','SOURCE_YEAR_PIN',null,period==='M1'?'TIMING_UNRESOLVED':'MISSING_FOR_SOURCE_YEAR'),
        reference('IR','STRUCTURED_PROJECT_SOURCE_YEAR',period==='M1'?null:'30000000-0000-4000-8000-000000000099',
          period==='M1'?'TIMING_UNRESOLVED':'ANNUAL_EVIDENCE_RECHECK_REQUIRED'),
        reference('DY','LEGACY_SOURCE_YEAR_PIN',null,period==='M1'?'TIMING_UNRESOLVED':'MISSING_FOR_SOURCE_YEAR')
      ]
    });
    const secondPeriod = {
      ...blocked, period:'M2', version:4, gates:[],
      timing:{projectYear:2, sourceYear:2029},
      irContext:{assessmentId:'30000000-0000-4000-8000-000000000099',version:2,
        assessmentSha256:'c'.repeat(64),contextSha256:'d'.repeat(64),state:'ANNUAL_EVIDENCE_RECHECK_REQUIRED'},
    };
    await page.route('**/v1/projects/*/technical/open-monitoring/dossiers/M2/e8-runs', async route => {
      if (route.request().method() === 'POST') {
        secondPeriodMatch = route.request().headers()['if-match'];
        await route.fulfill({status:201, json:{...secondPeriod, version:5}});
      } else {
        await route.fulfill({json:{projectId:project, period:'M2', canRecord:true, runs:[secondPeriod],currentSelection:selection('M2')}});
      }
    });
    await page.route('**/v1/projects/*/technical/open-monitoring/dossiers/M1/e8-runs', async route => {
      if (route.request().method() === 'POST') {
        posted = {
          body: route.request().postData(),
          key: route.request().headers()['idempotency-key'],
          match: route.request().headers()['if-match'],
        };
        await route.fulfill({status:201, json:blocked});
      } else {
        await route.fulfill({json:{
          projectId:project, period:'M1', canRecord:true,
          runs:showRehearsal ? [rehearsal] : [],currentSelection:selection('M1'),
        }});
      }
    });
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=quantificar');
    await page.getByText('Falta Estoque nacional EVR.').waitFor();
    await page.getByText('Não há BE, PE, LE nem ER. Ausência não é zero.').waitFor();
    // Every blocking gate is listed in one place, missing data first, with next action and planned role.
    const why=page.getByRole('region',{name:'Por que está bloqueado?'});
    await why.getByText('2 pendências impedem o resultado no diagnóstico atual.',{exact:false}).waitFor();
    const blockers=why.getByRole('list',{name:'Pendências que impedem o resultado'}).getByRole('listitem');
    assert.equal(await blockers.count(),2);
    assert.match(await blockers.nth(0).innerText(),/Estoque nacional EVR[\s\S]*Selecionar fonte EVR e avaliar cobertura/);
    assert.match(await blockers.nth(1).innerText(),/Frota EVR e comparáveis · M1[\s\S]*Aguardar a avaliação da equipe/);
    await page.getByRole('heading', {name:'Última tentativa de cálculo · M1'}).waitFor();
    await page.getByRole('heading', {name:'Consolidação por ano do projeto'}).waitFor();
    await page.getByText('Total anual indisponível · ano 1', {exact:true}).waitFor();
    await page.getByText('Referência: COVERAGE_GAP', {exact:true}).waitFor();
    await page.getByText('Outro ator, que não seja o autor dos vínculos').waitFor();
    await page.getByText('Ainda não há pino de fator fóssil, fator elétrico, IR ou Dy neste projeto.').waitFor();
    await page.getByText('fator elétrico, estoque nacional EVR e ratings PBEV').waitFor();
    await page.getByText('Estoque nacional EVR · Fonte ainda não vinculada').waitFor();
    await page.getByText('Próxima ação: Selecionar fonte EVR e avaliar cobertura').waitFor();
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await page.setViewportSize({width:1440,height:900});
    const evr=page.locator('li').filter({hasText:'Estoque nacional EVR'});
    await evr.getByText('Papel e referência',{exact:true}).click();
    await evr.getByText('Referência: EVR',{exact:true}).waitFor();
    await evr.getByText('Papel previsto: preparação',{exact:true}).waitFor();
    const fleet=page.locator('li').filter({hasText:'Frota EVR e comparáveis · M1'});
    await fleet.getByText('Papel e referência',{exact:true}).click();
    await fleet.getByText('Referência: E8_FLEET_SELECTION_M1',{exact:true}).waitFor();
    await page.getByText('Linha de base', {exact:true}).click();
    await page.getByText('Referência: BE · VM0038-1.1-EQ1', {exact:true}).waitFor();
    await page.getByText('Não há energia entregue admitida por carregador e período.').waitFor();
    await page.getByText('Energia entregue', {exact:true}).first().waitFor();
    await page.getByText('Referência: ED', {exact:true}).first().waitFor();
    await page.getByText('Nenhuma tentativa registrada neste período.').waitFor();
    const selectedInputs=page.getByRole('region',{name:'Seleção contextual de entradas'});
    await selectedInputs.getByText('Calendário não resolvido: RUN_WINDOW_MISSING.',{exact:false}).waitFor();
    await selectedInputs.getByRole('rowheader',{name:'Dy — pino escalar legado'}).waitFor();
    assert.equal(await selectedInputs.getByText('Versão 2',{exact:true}).count(),0);
    await page.getByRole('button', {name:'Registrar tentativa de cálculo'}).click();
    await page.getByText('Falta Melhoria tecnológica.').waitFor();
    await page.getByRole('region',{name:'Por que está bloqueado?'})
      .getByText('2 pendências impedem o resultado na última tentativa · M1.',{exact:false}).waitFor();
    await page.getByText('Bloqueado · v1').waitFor();
    await page.getByText('Não calculados').waitFor();
    await page.getByText('Melhoria tecnológica · Não informado').waitFor();
    await page.getByText('A melhoria tecnológica não tem valor normalizado armazenado. A tentativa não inventa 1.').waitFor();
    await page.getByText('Próxima ação: Completar o vínculo e a admissão antes de executar').waitFor();
    const ir=page.locator('li').filter({hasText:'Melhoria tecnológica'});
    await ir.getByText('Papel e referência',{exact:true}).click();
    await ir.getByText('Referência: IR',{exact:true}).waitFor();
    await ir.getByText('Papel previsto: preparação e revisão').waitFor();
    const hourly=page.locator('li').filter({hasText:'Fator elétrico das horas'});
    await hourly.getByText('Fator elétrico das horas · Verificação técnica pendente').waitFor();
    await hourly.getByText('Papel e referência',{exact:true}).click();
    await hourly.getByText('Referência: HOURLY_EFEL_NOT_UNIFORM',{exact:true}).waitFor();
    assert.equal(posted.body, '{}');
    assert.equal(posted.match, '"0"');
    assert.match(posted.key, /^[0-9a-f-]{36}$/);
    assert.equal(await page.getByText('Habilitada', {exact:true}).count(), 0);
    assert.ok(await page.getByText('Não habilitada').count() >= 1);
    showRehearsal = true;
    await page.getByRole('button', {name:'Atualizar diagnóstico'}).click();
    await page.getByRole('heading', {name:'Memória do ensaio interno · M1'}).waitFor();
    await page.getByText('Esta tela não aplica fórmula.').waitFor();
    assert.equal(await page.getByText('Não há execução de BE, PE, LE ou ER neste projeto.').count(), 0);
    await page.getByText('Ensaio interno — crédito não habilitado').waitFor();
    await page.getByText('Emissões do projeto', {exact:true}).waitFor();
    await page.getByText('Fuga', {exact:true}).waitFor();
    await page.getByText('Redução de emissões', {exact:true}).waitFor();
    await page.getByText('Referência: BE', {exact:true}).waitFor();
    await page.getByText('Referência: PE', {exact:true}).waitFor();
    await page.getByText('Referência: LE', {exact:true}).waitFor();
    await page.getByText('Referência: ER', {exact:true}).waitFor();
    await page.getByText('4.05 tCO2e').waitFor();
    const pdLink=page.getByRole('link', {name:'Consultar vínculo com a seção Quantificação do PD'});
    assert.equal(await pdLink.count(), 0);
    viewPd = true;
    await page.reload();
    await pdLink.waitFor();
    assert.equal(await pdLink.getAttribute('href'),
      '/workspace/projects/'+project+'/technical?secao=redigir&guia=quantification&run='+rehearsal.id+'&period=M1');
    await page.getByText('A seção consultará esta tentativa e este período, com os grants e licenças atuais.').waitFor();
    await page.getByText('Ensaio interno consolidado · ano 1', {exact:true}).waitFor();
    await page.getByText('5.50 tCO2e', {exact:true}).waitFor();
    await page.getByText('Janelas e referências da consolidação · 1 períodos', {exact:true}).click();
    await page.getByText('SHA-256 desta consulta:', {exact:false}).waitFor();
    assert.equal(await page.getByText('Habilitada', {exact:true}).count(), 0);

    await page.getByText('Emissões por hora · 2 horas · SIN', {exact:true}).click();
    const hourlyTable = page.getByRole('table', {name:'Memória do PE horário'});
    await hourlyTable.getByText('0.0004', {exact:true}).waitFor();
    assert.equal(await hourlyTable.locator('tbody tr').count(), 2);
    await page.getByText('EC total 3000 kWh · PE total 1.3 tCO2e.', {exact:true}).waitFor();
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await page.setViewportSize({width:1440,height:900});

    const annualYear=page.getByLabel('Ano do projeto a consolidar', {exact:true});
    await annualYear.fill('2');
    await page.getByRole('button', {name:'Consultar ano do projeto',exact:true}).click();
    await page.getByText('Total anual indisponível · ano 2', {exact:true}).waitFor();
    assert.equal(await page.getByText('5.50 tCO2e', {exact:true}).count(),0);
    assert.ok(annualYears.includes(2));
    await annualYear.fill('0');
    await page.getByRole('button', {name:'Consultar ano do projeto',exact:true}).click();
    await page.getByText('Informe um ano do projeto entre 1 e 100.', {exact:true}).waitFor();
    assert.equal(annualYears.includes(0),false);
    await annualYear.fill('1');
    await page.getByRole('button', {name:'Consultar ano do projeto',exact:true}).click();
    await page.getByText('Ensaio interno consolidado · ano 1', {exact:true}).waitFor();

    const period = page.getByLabel('Período de monitoramento', {exact:true});
    await period.selectOption('M2');
    await page.getByRole('heading', {name:'Última tentativa de cálculo · M2'}).waitFor();
    await page.getByText('Bloqueado · v4').waitFor();
    await page.getByText('2029', {exact:true}).waitFor();
    await selectedInputs.getByText('Ano relativo do projeto: 2 · Ano das fontes: 2029.',{exact:true}).waitFor();
    await selectedInputs.getByText('Prova anual requer rechecagem',{exact:true}).waitFor();
    await selectedInputs.getByText('Versão 2',{exact:true}).click();
    await page.getByText('Referência de IR congelada na tentativa · Prova anual requer rechecagem',{exact:true}).click();
    await page.getByText('SHA-256 do contexto: '+'d'.repeat(64),{exact:true}).waitFor();
    await selectedInputs.getByText('30000000-0000-4000-8000-000000000099',{exact:true}).waitFor();
    assert.equal(await page.getByRole('heading', {name:'Memória do ensaio interno · M1'}).count(), 0);
    await page.getByRole('button', {name:'Registrar tentativa de cálculo'}).click();
    await page.getByText('Bloqueado · v5').waitFor();
    assert.equal(secondPeriodMatch, '"4"');

    await page.getByRole('button', {name:'Atualizar diagnóstico'}).click();
    await page.getByText('Bloqueado · v4').waitFor();
    assert.equal(await period.inputValue(), 'M2');
    await period.selectOption('M1');
    await page.getByRole('heading', {name:'Memória do ensaio interno · M1'}).waitFor();
    await selectedInputs.getByText('Calendário não resolvido: RUN_WINDOW_MISSING.',{exact:false}).waitFor();
    assert.equal(await selectedInputs.getByText('30000000-0000-4000-8000-000000000099',{exact:true}).count(),0);
    invalidSelection=true;
    await page.getByRole('button',{name:'Atualizar diagnóstico'}).click();
    await page.getByRole('alert').filter({hasText:'Não foi possível consultar a última tentativa.'}).waitFor();
    await selectedInputs.getByText('Seleção contextual indisponível;', {exact:false}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Registrar tentativa de cálculo'}).count(),0);
  } finally {
    await browser.close();
  }
});
