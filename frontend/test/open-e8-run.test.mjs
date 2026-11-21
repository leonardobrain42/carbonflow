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
    const blocked = {
      id:'30000000-0000-4000-8000-000000000097',
      projectId:project, period:'M1', version:1, state:'BLOCKED', stale:false,
      canIssueVcu:false, engine:null, energyBindingId:null, fleetSelectionId:null,
      inputSha256:'abc', gates:[{
        code:'IR', state:'MISSING',
        reason:'IR não tem valor normalizado armazenado. A tentativa não inventa 1.',
        nextAction:'Completar o vínculo e a admissão antes de executar', owner:'PREP/REV',
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
      actions:['VIEW_INTAKE','VIEW_OPEN_CALCULATION','RECORD_OPEN_E8_RUN'],
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
    await page.route('**/v1/projects/*/technical/open-calculation-readiness', route => route.fulfill({json:{
      projectId:project, environment:'SANDBOX', methodology:'VM0038', scenario:'OPEN',
      workflowState:'PREPARATION', calculationState:'BLOCKED_MISSING_DATA',
      canContinuePreparation:true, canRunE8:false, canIssueVcu:false,
      emissions:{beTco2e:null, peTco2e:null, leTco2e:null, erTco2e:null, erDisplay:null},
      gates:[{
        code:'EVR', state:'MISSING_BINDING',
        reason:'Não há snapshot nacional EVR por modelo/ano admitido e vinculado ao projeto.',
        nextAction:'Selecionar fonte EVR e avaliar cobertura', owner:'PREP',
      },{
        code:'E8_FLEET_SELECTION_M1', state:'NOT_ADMITTED',
        reason:'Há uma seleção preparatória de frota. Ela não está admitida.',
        nextAction:'Aguardar a avaliação da equipe', owner:'PREP',
      }], equationPlan:[{
        outputCode:'BE', ruleReference:'VM0038-1.1-EQ1', unit:'tCO2e',
        description:'Linha de base ainda sem execução.', requiredInputs:['ED'],
        blockedBy:['ED'], status:'NOT_CALCULATED',
        whyNotCalculated:'Não calculado. Não há energia entregue ED admitida por carregador e período.',
      }],
    }}));
    await page.route('**/v1/projects/*/technical/open-monitoring/dossiers', route => route.fulfill({json:[]}));
    await page.route('**/v1/projects/*/technical/open-monitoring/dossiers/M1/e8-runs', async route => {
      if (route.request().method() === 'POST') {
        posted = {
          body: route.request().postData(),
          key: route.request().headers()['idempotency-key'],
          match: route.request().headers()['if-match'],
        };
        await route.fulfill({status:201, json:blocked});
      } else {
        await route.fulfill({json:{projectId:project, period:'M1', canRecord:true, runs:[]}});
      }
    });
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=quantificar');
    await page.getByRole('heading', {name:'Última tentativa de cálculo · M1'}).waitFor();
    await page.getByText('Estoque nacional EVR · Fonte ainda não vinculada').waitFor();
    await page.getByText('Selecionar fonte EVR e avaliar cobertura · Papel previsto: preparação').waitFor();
    await page.getByText('Referência: EVR').waitFor();
    await page.getByText('Frota EVR e comparáveis · M1').waitFor();
    await page.getByText('Referência: E8_FLEET_SELECTION_M1', {exact:true}).waitFor();
    await page.getByText('BE · VM0038-1.1-EQ1').click();
    await page.getByText('Não há energia entregue ED admitida por carregador e período.').waitFor();
    await page.getByText('Energia entregue', {exact:true}).first().waitFor();
    await page.getByText('Referência: ED', {exact:true}).first().waitFor();
    await page.getByText('Nenhuma tentativa registrada neste período.').waitFor();
    await page.getByRole('button', {name:'Registrar tentativa de cálculo'}).click();
    await page.getByText('Bloqueado · v1').waitFor();
    await page.getByText('Não calculados').waitFor();
    await page.getByText('Melhoria tecnológica · Não informado').waitFor();
    await page.getByText('IR não tem valor normalizado armazenado.').waitFor();
    await page.getByText('Papel previsto: preparação e revisão').waitFor();
    assert.equal(posted.body, '{}');
    assert.equal(posted.match, '"0"');
    assert.match(posted.key, /^[0-9a-f-]{36}$/);
    assert.equal(await page.getByText('Habilitada', {exact:true}).count(), 0);
    assert.ok(await page.getByText('Não habilitada').count() >= 1);
  } finally {
    await browser.close();
  }
});
