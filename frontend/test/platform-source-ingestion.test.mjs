import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

test('metadados são submetidos, revisados por outra conta e não admitem uso técnico',async () => {
  const browser=await chromium.launch({executablePath});
  try {
    const page=await browser.newPage({viewport:{width:390,height:844}});
    let verified=false;
    let role='manager';
    let sourceApiCalls=0;
    let submission=null;
    let history=[];
    const manager='11111111-1111-4111-8111-111111111111';
    const reviewer='22222222-2222-4222-8222-222222222222';
    const context={
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
      title:'Brasil DCFC rede aberta',country:'BR',networkMode:'OPEN',vehicleCategory:'LDV_BEV',
      chargerType:'DCFC',energySystem:'SIN',catalogState:'ACTIVE',
    };
    const need={
      code:'OPEN-EVR',stage:'BASELINE',kind:'DATASET',title:'Frota por modelo',
      sourceId:'BR-LDV-BEV-STOCK-BY-MODEL',sourceEdition:'YEAR',editionPolicy:'YEAR',
      officialUri:null,mandatory:true,conditionNote:'Dados nacionais',
      status:'MISSING_SOURCE_METADATA',nextAction:'Cadastrar',
      blockedCapability:'BASELINE_BE',responsibleParty:'CARBONFLOW',priority:'P0',
      observedEdition:null,rightsScope:'NO_ACTIVE_SCOPED_RIGHTS',
      referenceAllowedInStaffOrganization:false,extractionAllowedInStaffOrganization:false,
      ragAllowedInStaffOrganization:false,modelAllowedInStaffOrganization:false,
      technicalAdmissionConfirmed:false,purpose:'Ponderar veículos BEV',
    };
    await page.route('**/v1/compliance/session',route => {
      if(route.request().method()==='POST')verified=true;
      return route.fulfill({json:{verified,expiresAt:verified
        ? new Date(Date.now()+60000).toISOString():null}});
    });
    await page.route('**/v1/platform/source-readiness/contexts',route => route.fulfill({json:[context]}));
    await page.route('**/v1/platform/source-readiness/VM0038?*',route => route.fulfill({json:{
      methodology:'VM0038',edition:'1.1',scenario:context.scenario,
      asOf:'2026-11-19T20:00:00Z',normativeRuleStatus:'ONLY_SYNTHETIC_RULE_PACKS',
      dynamicCalculationStatus:'NOT_ENABLED_FOR_DYNAMIC_PROJECTS',normativeAiDisabled:true,
      missingSourceCount:1,missingReferenceRightsCount:0,reviewPendingCount:0,
      projectEvidenceCount:0,total:1,nextOffset:null,needs:[need],
    }}));
    await page.route('**/v1/platform/source-ingestion',route => {
      sourceApiCalls++;
      assert.equal(verified,true);
      assert.equal(route.request().method(),'POST');
      const input=route.request().postDataJSON();
      assert.equal(input.needCode,'OPEN-EVR');
      assert.equal(input.edition,'2024');
      assert.equal(input.officialUri,'https://example.org/publicacao/2024');
      submission={
        id:'33333333-3333-4333-8333-333333333333',needCode:'OPEN-EVR',
        sourceId:need.sourceId,edition:input.edition,category:input.category,
        title:input.title,rightsHolder:input.rightsHolder,publisher:input.publisher,
        officialUri:input.officialUri,publicationDate:null,referenceYear:2024,
        locator:input.locator,declaredOriginalSha256:null,state:'PENDING_REVIEW',
        version:1,submittedBy:manager,submittedByName:'Responsável pelo cadastro',
        reviewedBy:null,createdAt:'2026-11-19T20:00:00Z',updatedAt:'2026-11-19T20:00:00Z',
      };
      history=[{version:1,state:'PENDING_REVIEW',actorName:'Responsável pelo cadastro',
        reason:input.note,recordedAt:submission.createdAt}];
      return route.fulfill({json:{intake:submission,history},status:201});
    });
    await page.route('**/v1/platform/source-ingestion?*',route => {
      sourceApiCalls++;
      assert.equal(verified,true);
      return route.fulfill({json:{
        actorId:role==='manager'?manager:reviewer,canSubmit:role==='manager',
        canReview:role==='reviewer',submissions:submission?[submission]:[],
      }});
    });
    await page.route('**/v1/platform/source-ingestion/*',route => {
      sourceApiCalls++;
      assert.equal(verified,true);
      return route.fulfill({json:{intake:submission,history}});
    });
    await page.route('**/v1/platform/source-ingestion/*/reviews',route => {
      sourceApiCalls++;
      assert.equal(verified,true);
      assert.equal(role,'reviewer');
      assert.equal(route.request().headers()['if-match'],'1');
      const result=route.request().postDataJSON();
      assert.equal(result.outcome,'ACCEPT_METADATA');
      submission={...submission,state:'METADATA_ACCEPTED',version:2,reviewedBy:reviewer};
      history=[{version:2,state:'METADATA_ACCEPTED',actorName:'Revisor independente',
        reason:result.reason,recordedAt:'2026-11-19T20:02:00Z'},...history];
      return route.fulfill({json:{intake:submission,history}});
    });

    await page.goto(base+'/workspace/platform/sources');
    await page.getByRole('heading',{name:'Verificação da equipe interna'}).waitFor();
    assert.equal(sourceApiCalls,0);
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear inventário'}).click();
    await page.getByRole('button',{name:'Cadastro de fontes'}).click();
    await page.getByRole('heading',{name:'Nova submissão de metadados'}).waitFor();
    await page.getByLabel('Necessidade documental').selectOption('OPEN-EVR');
    await page.getByLabel('Edição concreta / ano').fill('2024');
    await page.getByLabel('Título oficial do documento ou conjunto de dados')
      .fill('Frota brasileira 2024 por modelo - teste');
    await page.getByLabel('Detentor dos direitos').fill('Órgão fictício');
    await page.getByLabel('Órgão ou editor responsável').fill('Publicador fictício');
    await page.getByLabel('URL oficial HTTPS (metadado, não autorização para copiar)')
      .fill('https://example.org/publicacao/2024');
    await page.getByLabel('Ano dos dados (se conhecido)').fill('2024');
    await page.getByLabel('Localizador verificável (seção, aba, tabela ou caminho)')
      .fill('Tabela de teste, página 1');
    await page.getByLabel('Justificativa da submissão')
      .fill('Fonte de teste; direitos e admissão técnica ainda pendentes');
    await page.getByRole('button',{name:'Submeter metadados à revisão'}).click();
    await page.getByText('Metadados submetidos. A revisão é obrigatória',{exact:false}).waitFor();
    await page.getByText('Aguardando outra identidade autorizada para revisão independente.',{exact:false}).waitFor();
    assert.equal(submission.state,'PENDING_REVIEW');

    role='reviewer';
    await page.getByRole('button',{name:'Atualizar',exact:true}).click();
    await page.getByLabel('Decisão sobre os metadados').waitFor();
    await page.getByLabel('Decisão sobre os metadados').selectOption('ACCEPT_METADATA');
    await page.getByLabel('Fundamentação do revisor independente')
      .fill('Metadados verificados. Os parâmetros e licenças seguem pendentes.');
    await page.getByRole('button',{name:'Registrar parecer'}).click();
    await page.getByText('Metadados registrados no catálogo; direitos',{exact:false}).waitFor();
    assert.equal(submission.state,'METADATA_ACCEPTED');
    assert.equal(history.length,2);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});
