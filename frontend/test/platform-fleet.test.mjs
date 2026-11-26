import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const chrome=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const org='30300000-0000-4000-8000-000000000001';
const source='30300000-0000-4000-8000-000000000002';
const rows=[
  {sourceRow:2,recordKey:'MODEL_A',quantity:25,year:2024,metric:'stock',unit:'vehicles',
   geography:'BR',locator:'CSV linha 2'},
  {sourceRow:3,recordKey:'MODEL_B',quantity:75,year:2024,metric:'stock',unit:'vehicles',
   geography:'BR',locator:'CSV linha 3'},
];

test('EVR no admin: modelos/aliases explícitos, ausência sem peso, snapshot sem E8',async()=>{
  const browser=await chromium.launch({executablePath:chrome});
  try {
    const page=await browser.newPage({viewport:{width:390,height:844}});
    let verified=false;
    const models=[],aliases=[];
    let snapshot=null;
    await page.route('**/v1/compliance/session',route=>{
      if(route.request().method()==='POST')verified=true;
      return route.fulfill({json:{verified,expiresAt:verified?
        new Date(Date.now()+60000).toISOString():null}});
    });
    await page.route('**/v1/platform/source-readiness/contexts',route=>route.fulfill({json:[{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
      title:'Brasil · rede aberta',country:'BR',networkMode:'OPEN',
      vehicleCategory:'LDV_BEV',chargerType:'DCFC',energySystem:'SIN',catalogState:'ACTIVE',
    }]}));
    await page.route('**/v1/platform/source-readiness/VM0038?*',route=>route.fulfill({json:{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
      asOf:'2026-11-19T20:10:00Z',normativeRuleStatus:'ONLY_SYNTHETIC_RULE_PACKS',
      dynamicCalculationStatus:'NOT_ENABLED_FOR_DYNAMIC_PROJECTS',normativeAiDisabled:true,
      missingSourceCount:0,missingReferenceRightsCount:1,reviewPendingCount:0,
      projectEvidenceCount:0,total:1,nextOffset:null,needs:[],
    }}));
    const sourceView={id:source,organizationId:org,needCode:'OPEN-EVR',
      state:'APPROVED_FOR_SCOPE',sourceId:'BR-LDV-BEV-STOCK-BY-MODEL',
      edition:'2024',sha256:'a'.repeat(64),rightsActive:true,
      approvedForScope:true,uploadedAt:'2026-11-19T00:00:00Z'};
    await page.route('**/v1/platform/fleet/**',async route=>{
      const request=route.request(),url=new URL(request.url());
      if(url.pathname.endsWith('/board'))return route.fulfill({json:{
        canManage:true,organizations:[org],models:[...models],aliases:[...aliases],
        sources:[sourceView],snapshots:snapshot?[snapshot.snapshot]:[],
        missingNeed:'OPEN-EVR',
        guidance:'Sem validação nacional não é possível admitir pesos reais.',
      }});
      if(url.pathname.endsWith('/preview')){
        const year=Number(url.searchParams.get('year'));
        assert.equal(year,2024);
        const resolved=rows.map(row=>{
          const alias=aliases.find(a=>a.recordKey===row.recordKey && a.observationYear===year);
          const model=models.find(m=>m.id===alias?.modelId);
          return {...row,modelId:model?.id??null,
            modelName:model?model.manufacturer+' '+model.modelName:null,
            vehicleClass:model?.vehicleClass??null,propulsion:model?.propulsion??null};
        });
        const valid=resolved.every(x=>x.modelId!=null);
        return route.fulfill({json:{
          sourceObjectId:source,needCode:'OPEN-EVR',dataKind:'STOCK_IN_CIRCULATION',
          observationYear:year,geography:'BR',sourceAssessed:true,rightsActive:true,
          denominator:valid?100:0,valid,canCreateSnapshot:valid,rows:resolved,
          issues:valid?[]:['CSV linha: alias/modelo não classificado'],
          usableForE8:false,
        }});
      }
      if(url.pathname.endsWith('/models') && request.method()==='POST'){
        assert.ok(request.headers()['idempotency-key']);
        const input=request.postDataJSON();
        const model={...input,id:'30300000-0000-4000-8000-'+String(models.length+10).padStart(12,'0'),
          recordedAt:'2026-11-19T20:10:00Z'};
        models.push(model);
        if(input.sourceRecordKey)aliases.push({organizationId:org,observationYear:input.aliasYear,
          recordKey:input.sourceRecordKey,modelId:model.id});
        return route.fulfill({status:201,json:model});
      }
      if(url.pathname.endsWith('/imports') && request.method()==='POST'){
        assert.ok(request.headers()['idempotency-key']);
        assert.deepEqual(request.postDataJSON(),{sourceObjectId:source,year:2024});
        const weights=rows.map((r,i)=>({
          modelId:models[i].id,modelName:models[i].modelName,evr:r.quantity,
          weight:r.quantity/100,locator:r.locator,
        }));
        snapshot={
          snapshot:{
            id:'30300000-0000-4000-8000-000000000099',
            ingestionId:'30300000-0000-4000-8000-000000000098',
            organizationId:org,dataKind:'STOCK_IN_CIRCULATION',year:2024,
            geography:'BR',sha256:'a'.repeat(64),denominator:100,modelCount:2,
            state:'PREPARATORY',coverageState:'OFFICIAL_TOTAL_NOT_VERIFIED',
            ratingState:'RATINGS_NOT_LINKED',createdAt:'2026-11-19T20:10:00Z',
            usableForE8:false,
          },weights,
        };
        return route.fulfill({status:201,json:snapshot});
      }
      if(url.pathname.includes('/imports/'))return route.fulfill({json:snapshot});
      throw Error('unexpected route '+url.pathname);
    });

    await page.goto(base+'/workspace/platform/sources');
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear inventário'}).click();
    await page.getByRole('button',{name:'Frota EVR'}).click();
    await page.getByRole('heading',{name:'Frota nacional BEV · EVR por modelo e ano'}).waitFor();
    await page.getByText('Nenhuma composição é liberada ao E8 nesta fatia.',{exact:false}).waitFor();
    await page.getByLabel('Fonte e edição em quarentena ou revisão').selectOption(source);
    await page.getByRole('button',{name:'Pré-validar valores e modelos por fonte'}).click();
    await page.getByText('Aliases sem identidade associada: 2.',{exact:false}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Guardar retrato EVR preparatório com pesos'}).isDisabled(),true);

    for(const [key,name] of [['MODEL_A','A'],['MODEL_B','B']]){
      await page.getByLabel('Fabricante').fill('Marca exemplo');
      await page.getByLabel('Modelo',{exact:true}).fill('Modelo '+name);
      await page.getByLabel('Versão do modelo').fill('Teste');
      await page.getByLabel('Alias literal presente na fonte (opcional)').fill(key);
      await page.getByRole('button',{name:'Registrar modelo e alias do ano'}).click();
      await page.getByText('Identidade e alias registrados.',{exact:false}).waitFor();
    }
    await page.getByRole('button',{name:'Pré-validar valores e modelos por fonte'}).click();
    await page.getByText('Série dimensionalmente consistente.',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Guardar retrato EVR preparatório com pesos'}).click();
    await page.getByText('Retrato EVR preparatório guardado.',{exact:false}).waitFor();
    assert.equal(snapshot.snapshot.denominator,100);
    assert.equal(snapshot.snapshot.usableForE8,false);
    await page.getByRole('heading',{name:/Snapshot 30300000/}).waitFor();
    await page.getByText('0.25',{exact:true}).waitFor();
    await page.getByText('RATINGS_NOT_LINKED',{exact:false}).waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  }finally{await browser.close();}
});

test('decisão de cobertura do estoque BEV não admite a série',async()=>{
  const browser=await chromium.launch({executablePath:chrome});
  try{
    const page=await browser.newPage({viewport:{width:390,height:844}});
    const ingestion='30300000-0000-4000-8000-000000000098';
    const detail={
      snapshot:{
        id:'30300000-0000-4000-8000-000000000099',ingestionId:ingestion,organizationId:org,
        dataKind:'STOCK_IN_CIRCULATION',year:2024,geography:'BR',sha256:'a'.repeat(64),
        denominator:100,modelCount:2,state:'PREPARATORY',
        coverageState:'OFFICIAL_TOTAL_NOT_VERIFIED',ratingState:'RATINGS_NOT_LINKED',
        createdAt:'2026-11-19T20:10:00Z',usableForE8:false,
      },
      weights:[{modelId:'m1',modelName:'Modelo A',evr:25,weight:'0.25',locator:'CSV linha 2'}],
      coverageDecision:null,
    };
    let verified=false;
    await page.route('**/v1/compliance/session',route=>{
      if(route.request().method()==='POST')verified=true;
      return route.fulfill({json:{verified,expiresAt:verified?
        new Date(Date.now()+60000).toISOString():null}});
    });
    await page.route('**/v1/platform/source-readiness/contexts',route=>route.fulfill({json:[{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
      title:'Brasil · rede aberta',country:'BR',networkMode:'OPEN',
      vehicleCategory:'LDV_BEV',chargerType:'DCFC',energySystem:'SIN',catalogState:'ACTIVE',
    }]}));
    await page.route('**/v1/platform/source-readiness/VM0038?*',route=>route.fulfill({json:{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
      asOf:'2026-11-19T20:10:00Z',normativeRuleStatus:'ONLY_SYNTHETIC_RULE_PACKS',
      dynamicCalculationStatus:'NOT_ENABLED_FOR_DYNAMIC_PROJECTS',normativeAiDisabled:true,
      missingSourceCount:0,missingReferenceRightsCount:1,reviewPendingCount:0,
      projectEvidenceCount:0,total:0,nextOffset:null,needs:[],
    }}));
    await page.route('**/v1/platform/fleet/**',async route=>{
      const request=route.request(),url=new URL(request.url());
      if(url.pathname.endsWith('/board'))return route.fulfill({json:{
        canManage:false,canReviewCoverage:true,organizations:[org],models:[],aliases:[],
        sources:[],snapshots:[detail.snapshot],missingNeed:'OPEN-EVR',
        guidance:'Sem validação nacional não é possível admitir pesos reais.',
      }});
      if(url.pathname.endsWith('/coverage') && request.method()==='POST'){
        assert.equal(request.headers()['if-match'],'"0"');
        assert.ok(request.headers()['idempotency-key']);
        const body=request.postDataJSON();
        assert.equal(body.outcome,'NOT_COVERED');
        assert.equal(body.citedTotal,180);
        detail.coverageDecision={
          id:'30300000-0000-4000-8000-000000000097',snapshotId:detail.snapshot.id,
          version:1,outcome:'NOT_COVERED',citedTotal:180,gapVehicles:80,reason:body.reason,
          viewState:'CURRENT',usableForE8:false,
        };
        return route.fulfill({status:201,json:detail});
      }
      if(url.pathname.includes('/imports/'))return route.fulfill({json:detail});
      return route.fulfill({status:404,json:{}});
    });
    await page.goto(base+'/workspace/platform/sources');
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear inventário'}).click();
    await page.getByRole('button',{name:'Frota EVR'}).click();
    await page.getByRole('button',{name:'Inspecionar pesos e locators'}).click();
    await page.getByText('Ainda sem decisão de cobertura.',{exact:false}).waitFor();
    await page.getByLabel('Resultado da comparação').selectOption('NOT_COVERED');
    await page.getByLabel('Justificativa').fill('O retrato fictício não alcança o total nacional citado.');
    await page.getByLabel('Total oficial citado, em veículos inteiros').fill('180');
    await page.getByRole('button',{name:'Registrar decisão de cobertura'}).click();
    await page.getByText('Diferença inteira: 80.',{exact:false}).waitFor();
    await page.getByText('não admite a série nacional',{exact:false}).waitFor();
    assert.equal(detail.coverageDecision.usableForE8,false);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  }finally{await browser.close();}
});

test('Catálogo de modelos: pendente explícito, filtro por recorte, classificação com evidência sem admitir',async()=>{
  const browser=await chromium.launch({executablePath:chrome});
  try {
    const page=await browser.newPage({viewport:{width:1280,height:900}});
    let verified=false;
    const posts=[];
    const models=[
      {id:'30300000-0000-4000-8000-000000000021',organizationId:org,manufacturer:'Marca X',modelName:'Alfa',variant:'Base',
       vehicleClass:'UNKNOWN',propulsion:'UNKNOWN',recordedAt:'2026-11-19T20:10:00Z',classificationVersion:0,scope:'PENDING_CLASSIFICATION'},
      {id:'30300000-0000-4000-8000-000000000022',organizationId:org,manufacturer:'Marca X',modelName:'Beta',variant:'Base',
       vehicleClass:'LDV',propulsion:'BEV',recordedAt:'2026-11-19T20:10:00Z',classificationVersion:1,scope:'CANDIDATE_INITIAL_SCOPE'},
      {id:'30300000-0000-4000-8000-000000000023',organizationId:org,manufacturer:'Marca Y',modelName:'Moto',variant:'Base',
       vehicleClass:'OTHER',propulsion:'BEV',recordedAt:'2026-11-19T20:10:00Z',classificationVersion:0,scope:'OUTSIDE_INITIAL_SCOPE'},
    ];
    await page.route('**/v1/compliance/session',route=>{
      if(route.request().method()==='POST')verified=true;
      return route.fulfill({json:{verified,expiresAt:verified?new Date(Date.now()+60000).toISOString():null}});
    });
    await page.route('**/v1/platform/source-readiness/contexts',route=>route.fulfill({json:[{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',title:'Brasil · rede aberta',country:'BR',
      networkMode:'OPEN',vehicleCategory:'LDV_BEV',chargerType:'DCFC',energySystem:'SIN',catalogState:'ACTIVE'}]}));
    await page.route('**/v1/platform/source-readiness/VM0038?*',route=>route.fulfill({json:{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',asOf:'2026-11-19T20:10:00Z',
      normativeRuleStatus:'ONLY_SYNTHETIC_RULE_PACKS',dynamicCalculationStatus:'NOT_ENABLED_FOR_DYNAMIC_PROJECTS',
      normativeAiDisabled:true,missingSourceCount:0,missingReferenceRightsCount:1,reviewPendingCount:0,
      projectEvidenceCount:0,total:1,nextOffset:null,needs:[]}}));
    await page.route('**/v1/platform/fleet/**',async route=>{
      const request=route.request(),url=new URL(request.url());
      if(url.pathname.endsWith('/board'))return route.fulfill({json:{canManage:true,organizations:[org],
        models:models.map(m=>({...m})),aliases:[],sources:[],snapshots:[],missingNeed:'OPEN-EVR',guidance:'x'}});
      if(url.pathname.endsWith('/classification')){
        assert.ok(request.headers()['idempotency-key']);
        const id=url.pathname.split('/').at(-2);
        const body=request.postDataJSON();
        posts.push({id,body});
        const m=models.find(x=>x.id===id);
        Object.assign(m,{vehicleClass:body.vehicleClass,propulsion:body.propulsion,classificationVersion:(m.classificationVersion??0)+1,
          scope:body.vehicleClass==='UNKNOWN'?'PENDING_CLASSIFICATION':(body.vehicleClass==='LDV'&&body.propulsion==='BEV'?'CANDIDATE_INITIAL_SCOPE':'OUTSIDE_INITIAL_SCOPE')});
        return route.fulfill({json:m});
      }
      throw Error('unexpected route '+url.pathname);
    });
    await page.goto(base+'/workspace/platform/sources');
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear inventário'}).click();
    await page.getByRole('button',{name:'Frota EVR'}).click();
    await page.getByRole('heading',{name:'Catálogo de modelos canônicos'}).waitFor();
    const catalog=page.getByRole('region',{name:'Catálogo de modelos canônicos'});
    await catalog.getByRole('cell',{name:/Classificação pendente/}).first().waitFor();
    assert.equal(await catalog.getByRole('row').count(),4); // header + 3 models
    await catalog.getByLabel('Mostrar').selectOption('PENDING_CLASSIFICATION');
    assert.equal(await catalog.getByRole('row').count(),2);
    await catalog.getByRole('cell',{name:'Alfa',exact:true}).waitFor();
    await catalog.getByLabel('Mostrar').selectOption('');

    // Known classification demands evidence and says it does not admit the model.
    await page.getByLabel('Modelo canônico').first().selectOption('30300000-0000-4000-8000-000000000021');
    await page.getByLabel('Evidência (fonte e localizador)').fill('PBEV 2026, linha 62');
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('Conferido na ficha do fabricante.');
    await page.getByRole('button',{name:'Registrar classificação'}).click();
    await page.getByText('Isso não admite o modelo no recorte inicial.',{exact:false}).waitFor();
    assert.deepEqual(posts[0].body,{vehicleClass:'LDV',propulsion:'BEV',reason:'Conferido na ficha do fabricante.',evidence:'PBEV 2026, linha 62'});
    await catalog.getByRole('cell',{name:/Candidato ao recorte inicial \(LDV BEV\), não admitido.*classificação v1/}).first().waitFor();

    // Back to pending needs only a reason and sends UNKNOWN for both fields and no evidence.
    await page.getByLabel('Modelo canônico').first().selectOption('30300000-0000-4000-8000-000000000021');
    await page.getByLabel('Classe do veículo').selectOption('UNKNOWN');
    assert.equal(await page.getByLabel('Evidência (fonte e localizador)').count(),0);
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('Evidência anterior contestada.');
    await page.getByRole('button',{name:'Registrar classificação'}).click();
    await page.getByText('Modelo marcado como pendente de classificação.').waitFor();
    assert.deepEqual(posts[1].body,{vehicleClass:'UNKNOWN',propulsion:'UNKNOWN',reason:'Evidência anterior contestada.',evidence:null});
  } finally {
    await browser.close();
  }
});

