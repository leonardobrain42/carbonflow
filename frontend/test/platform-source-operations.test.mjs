import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const context = {
  methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
  title:'Brasil - rede aberta',country:'BR',networkMode:'OPEN',
  vehicleCategory:'LDV_BEV',chargerType:'DCFC',energySystem:'SIN',catalogState:'ACTIVE',
};
const needs = [
  {code:'OPEN-EVR',stage:'BASELINE',kind:'DATASET',title:'Estoque de BEVs por modelo',
   purpose:'Ponderar frota',sourceId:'BR-EV-STOCK',sourceEdition:'YEAR',officialUri:null,
   mandatory:true,conditionNote:'O PBEV nao traz EVR.',status:'MISSING_SOURCE_METADATA',
   nextAction:'Pesquisar fonte',blockedCapability:'BASELINE_BE',editionPolicy:'YEAR',
   responsibleParty:'CARBONFLOW',priority:'P0',observedEdition:null,
   rightsScope:'NO_ACTIVE_SCOPED_RIGHTS',referenceAllowedInStaffOrganization:false,
   extractionAllowedInStaffOrganization:false,ragAllowedInStaffOrganization:false,
   modelAllowedInStaffOrganization:false,technicalAdmissionConfirmed:false},
  {code:'OPEN-RIGHTS',stage:'APPLICABILITY',kind:'PROJECT_EVIDENCE',
   title:'Direitos de carbono',purpose:'Prova do titular',sourceId:null,sourceEdition:null,
   officialUri:null,mandatory:true,conditionNote:'Documento por projeto.',
   status:'PROJECT_SPECIFIC_EVIDENCE',nextAction:'Solicitar ao PP',blockedCapability:'APPLICABILITY',
   editionPolicy:'EXACT',responsibleParty:'PROPONENT',priority:'P0',observedEdition:null,
   rightsScope:'NO_ACTIVE_SCOPED_RIGHTS',referenceAllowedInStaffOrganization:false,
   extractionAllowedInStaffOrganization:false,ragAllowedInStaffOrganization:false,
   modelAllowedInStaffOrganization:false,technicalAdmissionConfirmed:false},
];

test('gestor pode solicitar fonte, manter histórico e visualizar impactos sem declarar aprovação',async () => {
  const browser=await chromium.launch({executablePath});
  try {
    const page=await browser.newPage({viewport:{width:390,height:844}});
    let verified=false;
    let work=null;
    let history=[];
    await page.route('**/v1/compliance/session',async route => {
      if(route.request().method()==='POST')verified=true;
      await route.fulfill({json:{verified,expiresAt:verified
        ? new Date(Date.now()+60000).toISOString():null}});
    });
    await page.route('**/v1/platform/source-readiness/contexts',route =>
      route.fulfill({json:[context]}));
    await page.route('**/v1/platform/source-readiness/VM0038?*',route =>
      route.fulfill({json:{
        methodology:'VM0038',edition:'1.1',scenario:context.scenario,
        asOf:'2026-11-19T18:00:00Z',normativeRuleStatus:'ONLY_SYNTHETIC_RULE_PACKS',
        dynamicCalculationStatus:'NOT_ENABLED_FOR_DYNAMIC_PROJECTS',normativeAiDisabled:true,
        missingSourceCount:1,missingReferenceRightsCount:0,reviewPendingCount:0,
        projectEvidenceCount:1,total:2,nextOffset:null,needs,
      }}));
    await page.route('**/v1/platform/source-work/assignees',route =>
      route.fulfill({json:[{id:'00000000-0000-4000-8000-000000000001',name:'Gestor de fontes'}]}));
    await page.route('**/v1/platform/source-work?*',route => route.fulfill({json:{
      canManage:true,work:work?[work]:[],impacts:[
        {capability:'BASELINE_BE',required:1,conditional:0,projectEvidence:0,
         requestsOpen:work?1:0},
        {capability:'APPLICABILITY',required:1,conditional:0,projectEvidence:1,
         requestsOpen:0},
      ],
    }}));
    await page.route('**/v1/platform/source-work/OPEN-EVR/revisions',route => {
      assert.equal(route.request().headers()['if-match'],String(work.version));
      const change=route.request().postDataJSON();
      work={...work,status:change.status,ownerId:change.ownerId,
        version:work.version+1,updatedAt:'2026-11-19T18:03:00Z'};
      history=[{version:work.version,status:work.status,ownerId:work.ownerId,
        ownerName:'Gestor de fontes',actorId:'00000000-0000-4000-8000-000000000001',
        actorName:'Gestor de fontes',note:change.note,recordedAt:'2026-11-19T18:03:00Z'},...history];
      return route.fulfill({json:{work,history}});
    });
    await page.route('**/v1/platform/source-work/OPEN-EVR',route => {
      if(route.request().method()==='PUT'){
        const request=route.request().postDataJSON();
        assert.ok(request.note.length>0);
        work={needCode:'OPEN-EVR',title:'Estoque de BEVs por modelo',kind:'DATASET',
          stage:'BASELINE',blockedCapability:'BASELINE_BE',priority:'P0',status:'REQUESTED',
          ownerId:request.ownerId,ownerName:'Gestor de fontes',version:1,
          createdAt:'2026-11-19T18:00:00Z',updatedAt:'2026-11-19T18:00:00Z'};
        history=[{version:1,status:'REQUESTED',ownerId:work.ownerId,
          ownerName:'Gestor de fontes',actorId:work.ownerId,actorName:'Gestor de fontes',
          note:request.note,recordedAt:work.createdAt}];
      }
      return route.fulfill({json:{work,history}});
    });
    await page.goto(base+'/workspace/platform/sources');
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear inventário'}).click();
    await page.getByRole('button',{name:'Alimentação e tarefas'}).click();
    await page.getByRole('button',{name:'Solicitar/acompanhar fonte'}).click();
    await page.getByLabel('Responsável interno').selectOption('00000000-0000-4000-8000-000000000001');
    await page.getByLabel('Objetivo da solicitação e evidência esperada')
      .fill('Identificar base anual oficial para EVR nacional por modelo.');
    await page.getByRole('button',{name:'Abrir solicitação'}).click();
    await page.getByText('Solicitação registrada.',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Histórico',exact:true}).click();
    await page.getByRole('button',{name:'Consultar histórico'}).click();
    await page.getByText('Identificar base anual oficial para EVR nacional por modelo.').waitFor();
    await page.getByRole('button',{name:'Impactos na jornada'}).click();
    await page.getByRole('heading',{name:'BASELINE_BE'}).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
    assert.equal(work.version,1);
  } finally {
    await browser.close();
  }
});
