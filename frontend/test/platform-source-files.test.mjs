import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const chrome=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';

test('CSV autorizado fica em quarentena, passa pela validação e só revisor vê prévia',async()=>{
  const browser=await chromium.launch({executablePath:chrome});
  try {
    const page=await browser.newPage({viewport:{width:390,height:844}});
    let role='manager',verified=false;
    let original=null;
    let parsed=false;
    const manager='11111111-1111-4111-8111-111111111111';
    const reviewer='22222222-2222-4222-8222-222222222222';
    const sub='33333333-3333-4333-8333-333333333333';
    const object='44444444-4444-4444-8444-444444444444';
    const source={
      id:sub,needCode:'OPEN-EVR',sourceId:'BR-LDV-BEV-STOCK-BY-MODEL',edition:'2024',
      category:'EXTERNAL_SOURCE',title:'Frota teste 2024',rightsHolder:'Instituição exemplo',
      publisher:'Órgão exemplo',officialUri:'https://example.org/dados/2024',
      publicationDate:'2024-12-31',referenceYear:2024,locator:'Tabela 1',
      declaredOriginalSha256:null,state:'METADATA_ACCEPTED',version:2,
      submittedBy:manager,submittedByName:'Cadastro',reviewedBy:reviewer,
      createdAt:'2026-11-19T20:00:00Z',updatedAt:'2026-11-19T20:00:00Z',
    };
    const need={
      code:'OPEN-EVR',stage:'BASELINE',kind:'DATASET',title:'Frota BEV por modelo',
      sourceId:'BR-LDV-BEV-STOCK-BY-MODEL',sourceEdition:'YEAR',editionPolicy:'YEAR',
      officialUri:null,mandatory:true,conditionNote:'Teste',
      status:'VERSION_SELECTION_REQUIRED',nextAction:'Selecionar edição',
      blockedCapability:'BASELINE_BE',responsibleParty:'CARBONFLOW',priority:'P0',
      observedEdition:'2024',rightsScope:'NO_ACTIVE_SCOPED_RIGHTS',
      referenceAllowedInStaffOrganization:false,extractionAllowedInStaffOrganization:false,
      ragAllowedInStaffOrganization:false,modelAllowedInStaffOrganization:false,
      technicalAdmissionConfirmed:false,purpose:'Estoque nacional BEV',
    };
    function view(){
      return {
        id:object,submissionId:sub,uploadedBy:manager,filename:'source.csv',format:'CSV',
        sha256:'a'.repeat(64),bytesCount:original?.length??0,
        state:parsed?'NORMALIZED_CANDIDATE':'QUARANTINED',issues:[],
        parsedRows:parsed?1:0,uploadedAt:'2026-11-19T20:10:00Z',
        licenseActive:true,usableForE8:false,
      };
    }

    await page.route('**/v1/compliance/session',route=>{
      if(route.request().method()==='POST')verified=true;
      return route.fulfill({json:{verified,expiresAt:verified?
        new Date(Date.now()+60000).toISOString():null}});
    });
    await page.route('**/v1/platform/source-readiness/contexts',route=>route.fulfill({json:[{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
      title:'Brasil DCFC',country:'BR',networkMode:'OPEN',
      vehicleCategory:'LDV_BEV',chargerType:'DCFC',energySystem:'SIN',catalogState:'ACTIVE',
    }]}));
    await page.route('**/v1/platform/source-readiness/VM0038?*',route=>route.fulfill({json:{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
      asOf:'2026-11-19T20:10:00Z',normativeRuleStatus:'ONLY_SYNTHETIC_RULE_PACKS',
      dynamicCalculationStatus:'NOT_ENABLED_FOR_DYNAMIC_PROJECTS',normativeAiDisabled:true,
      missingSourceCount:0,missingReferenceRightsCount:1,reviewPendingCount:0,
      projectEvidenceCount:0,total:1,nextOffset:null,needs:[need],
    }}));
    await page.route('**/v1/platform/source-ingestion?*',route=>route.fulfill({json:{
      actorId:role==='manager'?manager:reviewer,canSubmit:role==='manager',
      canReview:role==='reviewer',submissions:[source],
    }}));
    await page.route('**/v1/platform/source-ingestion/*',route=>route.fulfill({json:{
      intake:source,history:[
        {version:2,state:'METADATA_ACCEPTED',actorName:'Revisor',
         reason:'Metadados conferidos',recordedAt:'2026-11-19T20:00:00Z'},
      ],
    }}));
    await page.route('**/v1/platform/source-files/**',async route=>{
      const request=route.request(),url=new URL(request.url());
      const path=url.pathname;
      if(path.endsWith('/candidates')){
        assert.equal(role,'reviewer');
        return route.fulfill({json:{total:1,samples:[{
          row:2,recordKey:'record1',year:2024,metric:'stock',
          value:15.5,unit:'vehicles',geography:'BR',locator:'CSV registro 1',
        }]}});
      }
      if(path.endsWith('/parse')){
        assert.equal(role,'manager');
        parsed=true;
        return route.fulfill({json:view()});
      }
      if(path.endsWith('/assess')){
        assert.equal(role,'reviewer');
        const payload=request.postDataJSON();
        assert.equal(payload.outcome,'APPROVED_FOR_SCOPE');
        assert.equal(payload.metric,'stock');
        assert.equal(payload.periodYear,2024);
        return route.fulfill({json:{
          id:'55555555-5555-4555-8555-555555555555',objectId:object,outcome:payload.outcome,
          metric:payload.metric,periodYear:2024,geography:'BR',unit:'vehicles',
          reason:payload.reason,
        }});
      }
      if(request.method()==='POST'){
        assert.equal(role,'manager');
        assert.equal(request.headers()['x-carbonflow-file-request'],'1');
        assert.equal(request.headers()['x-source-format'],'CSV');
        original=request.postDataBuffer();
        assert.ok(original.toString('utf8').includes('record_key,period_year'));
        return route.fulfill({json:view(),status:201});
      }
      return route.fulfill({json:{
        actorId:role==='manager'?manager:reviewer,
        canUpload:role==='manager',canReview:role==='reviewer',
        files:original?[view()]:[],
      }});
    });

    await page.goto(base+'/workspace/platform/sources');
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear inventário'}).click();
    await page.getByRole('button',{name:'Cadastro de fontes'}).click();
    await page.getByRole('button',{name:'Abrir proveniência e histórico'}).click();
    await page.getByRole('heading',{name:'Arquivo original protegido e conferência técnica'}).waitFor();
    await page.locator('#cf-source-original').setInputFiles({
      name:'source.csv',mimeType:'text/csv',
      buffer:Buffer.from('record_key,period_year,metric,value,unit,geography\nrecord1,2024,stock,15.5,vehicles,BR\n'),
    });
    await page.getByRole('button',{name:'Enviar para quarentena cifrada'}).click();
    await page.getByText('Arquivo cifrado em quarentena e SHA-256 calculado.',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Validar tabela e gerar candidatos'}).click();
    await page.getByText('Schema validado. Os valores são apenas candidatos',{exact:false}).waitFor();
    assert.equal(parsed,true);

    role='reviewer';
    await page.reload();
    await page.getByRole('button',{name:'Cadastro de fontes'}).click();
    await page.getByRole('button',{name:'Abrir proveniência e histórico'}).click();
    await page.getByRole('button',{name:'Verificar arquivo'}).click();
    await page.getByRole('button',{name:'Consultar amostras autorizadas'}).click();
    await page.getByText('record1',{exact:true}).waitFor();
    await page.getByLabel('Decisão para o escopo declarado').selectOption('APPROVED_FOR_SCOPE');
    await page.getByLabel('Fundamentação técnica').fill(
      'Revisor conferiu o indicador, a unidade e a fonte oficial; uso E8 pendente.');
    await page.getByRole('button',{name:'Registrar parecer dimensional'}).click();
    await page.getByText('Parecer registrado para dimensões específicas.',{exact:false}).waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth),false);
  } finally {await browser.close();}
});
