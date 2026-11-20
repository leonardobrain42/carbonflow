import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const chrome=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const org='aaa95000-0000-4000-8000-000000000001';
const fleet='aaa95000-0000-4000-8000-000000000002';
const source='aaa95000-0000-4000-8000-000000000003';
const manager='aaa95000-0000-4000-8000-000000000004';
const reviewer='aaa95000-0000-4000-8000-000000000005';
const models=[
  {id:'aaa95000-0000-4000-8000-000000000010',organizationId:org,title:'BEV A',
   vehicleClass:'LDV',propulsion:'BEV'},
  {id:'aaa95000-0000-4000-8000-000000000011',organizationId:org,title:'BEV B',
   vehicleClass:'LDV',propulsion:'BEV'},
  {id:'aaa95000-0000-4000-8000-000000000012',organizationId:org,title:'Fóssil A',
   vehicleClass:'LDV',propulsion:'OTHER'},
  {id:'aaa95000-0000-4000-8000-000000000013',organizationId:org,title:'Fóssil B',
   vehicleClass:'LDV',propulsion:'OTHER'},
];
const candidates=[
  {row:2,key:'A',metric:'ev_energy',value:18,unit:'kWh/100mi',year:2024,geography:'BR',locator:'PBEV tab 2'},
  {row:3,key:'B',metric:'ev_energy',value:23,unit:'kWh/100mi',year:2024,geography:'BR',locator:'PBEV tab 3'},
  {row:4,key:'C',metric:'fuel_economy',value:32,unit:'mi/USgal',year:2024,geography:'BR',locator:'PBEV tab 4'},
  {row:5,key:'D',metric:'fuel_economy',value:27,unit:'mi/USgal',year:2024,geography:'BR',locator:'PBEV tab 5'},
];

test('PBEV navegação mobile, revisão segregada e AFEC/MPG sem E8',async()=>{
  const browser=await chromium.launch({executablePath:chrome});
  try{
    const page=await browser.newPage({viewport:{width:390,height:844}});
    let verified=false,role='manager',snapshots=[];
    const ratings=[],pairs=[];
    await page.route('**/v1/compliance/session',route=>{
      if(route.request().method()==='POST')verified=true;
      return route.fulfill({json:{verified,expiresAt:verified?
        new Date(Date.now()+60000).toISOString():null}});
    });
    await page.route('**/v1/platform/source-readiness/contexts',route=>route.fulfill({json:[{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
      title:'Brasil aberta',country:'BR',networkMode:'OPEN',vehicleCategory:'LDV_BEV',
      chargerType:'DCFC',energySystem:'SIN',catalogState:'ACTIVE',
    }]}));
    await page.route('**/v1/platform/source-readiness/VM0038?*',route=>route.fulfill({json:{
      methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
      asOf:'2026-11-19T20:00:00Z',normativeRuleStatus:'ONLY_SYNTHETIC_RULE_PACKS',
      dynamicCalculationStatus:'NOT_ENABLED_FOR_DYNAMIC_PROJECTS',normativeAiDisabled:true,
      missingSourceCount:1,missingReferenceRightsCount:1,reviewPendingCount:1,
      projectEvidenceCount:0,total:0,nextOffset:null,needs:[],
    }}));
    await page.route('**/v1/platform/vehicle-ratings/**',async route=>{
      const request=route.request(),pathname=new URL(request.url()).pathname;
      if(pathname.endsWith('/board')){
        return route.fulfill({json:{
          actorId:role==='manager'?manager:reviewer,
          canManage:role==='manager',canReview:role==='reviewer',
          models,sources:[{
            id:source,organizationId:org,edition:'2024',sha256:'c'.repeat(64),
            state:'APPROVED_FOR_SCOPE',
          }],fleets:[{id:fleet,organizationId:org,year:2024,denominator:20,
            coverageState:'OFFICIAL_TOTAL_NOT_VERIFIED'}],
          ratings,pairs,snapshots,guidance:'E8 ainda bloqueado',
        }});
      }
      if(pathname.endsWith('/candidates'))return route.fulfill({json:candidates});
      if(pathname.endsWith('/ratings') && request.method()==='POST'){
        assert.ok(request.headers()['idempotency-key']);
        const input=request.postDataJSON();
        const c=candidates.find(row=>row.row===input.sourceRow);
        assert.ok(c);
        const rating={...input,id:'aaa95000-0000-4000-8000-'+String(ratings.length+100).padStart(12,'0'),
          sourceLicenseId:'aaa95000-0000-4000-8000-000000000020',
          year:2024,originalValue:c.value,originalUnit:c.unit,
          normalizedValue:c.value,normalizedUnit:input.role==='BEV'?'kWh/100mi':'mi/USgal',
          locator:c.locator,status:'CANDIDATE',createdBy:manager};
        ratings.push(rating);
        return route.fulfill({json:rating,status:201});
      }
      if(pathname.endsWith('/pairs') && request.method()==='POST'){
        const input=request.postDataJSON();
        assert.ok(request.headers()['idempotency-key']);
        const bev=ratings.find(r=>r.id===input.bevRatingId);
        const pair={...input,id:'aaa95000-0000-4000-8000-'+String(pairs.length+200).padStart(12,'0'),
          bevModelId:bev.modelId,status:'PROPOSED',version:1,
          proposedBy:manager,reviewedBy:null,reviewReason:null,
        };
        pairs.push(pair);
        return route.fulfill({json:pair,status:201});
      }
      if(pathname.endsWith('/review')){
        assert.equal(role,'reviewer');
        assert.equal(request.headers()['if-match'],'"1"');
        const decision=request.postDataJSON();
        const item=pairs.find(x=>pathname.includes(x.id));
        item.status=decision.outcome;item.version=2;
        item.reviewedBy=reviewer;item.reviewReason=decision.reason;
        return route.fulfill({json:item});
      }
      if(pathname.endsWith('/composition')){
        const accepted=pairs.filter(x=>x.status==='ACCEPTED');
        const ready=accepted.length===2;
        const lines=models.slice(0,2).map((m,i)=>{
          const pair=accepted.find(x=>x.bevModelId===m.id);
          return {modelId:m.id,modelName:m.title,evr:i===0?12:8,
            pairId:pair?.id??null,kwh100mi:pair?candidates[i].value:null,
            mpg:pair?candidates[i+2].value:null,cycle:pair?'COMBINED':null,
            status:pair?'ACCEPTED_INTERNAL':'PENDING_PAIR'};
        });
        return route.fulfill({json:{
          fleetSnapshotId:fleet,year:2024,total:ready?20:null,
          afecKwh100mi:ready?20:null,mpgUs:ready?30:null,
          cycle:ready?'COMBINED':null,lines,issues:ready?[]:['Pares ainda não aprovados'],
          canSavePreparatory:ready,usableForE8:false,
        }});
      }
      if(pathname.endsWith('/snapshots')&&request.method()==='POST'){
        const record={id:'aaa95000-0000-4000-8000-000000000555',
          organizationId:org,fleetSnapshotId:fleet,year:2024,cycle:'COMBINED',
          afecKwh100mi:20,mpgUs:30,modelCount:2,status:'PREPARATORY',usableForE8:false};
        snapshots=[record];
        return route.fulfill({json:record,status:201});
      }
      throw Error('Rota não simulada: '+pathname);
    });

    await page.goto(base+'/workspace/platform/sources');
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear inventário'}).click();
    await page.getByRole('button',{name:'Eficiências e comparáveis'}).click();
    await page.getByRole('heading',{name:'Inmetro/PBEV · ratings e comparáveis fósseis'}).waitFor();
    await page.getByText('PREPARATÓRIOS',{exact:false}).first().waitFor();
    await page.getByLabel('Arquivo PBEV licenciado e edição').selectOption(source);
    await page.getByRole('button',{name:'Consultar linhas autorizadas'}).click();
    await page.getByText('PBEV tab 2').first().waitFor();
    for(let i=0;i<4;i++){
      await page.getByLabel('Tipo de veículo no PBEV').selectOption(i<2?'BEV':'FOSSIL');
      await page.getByLabel('Identidade canônica do veículo').selectOption(models[i].id);
      await page.getByLabel('Linha exata da tabela autorizada').selectOption(String(i+2));
      await page.getByLabel('Referência verificável do ciclo e capacidade')
        .fill('PBEV test combined cycle reference '+(i+2));
      // The prior success notice remains visible; waiting for it again does
      // not prove that the next POST has finished. Synchronize on this POST.
      const postedRating=page.waitForResponse(response=>
        new URL(response.url()).pathname.endsWith('/ratings') &&
        response.request().method()==='POST' && response.status()===201);
      await page.getByRole('button',{name:'Registrar rating candidato'}).click();
      await postedRating;
      assert.equal(ratings.length,i+1);
      await page.getByText('Rating candidato registrado com origem',{exact:false}).waitFor();
    }
    for(let i=0;i<2;i++){
      await page.getByLabel('Rating BEV candidato').selectOption(ratings[i].id);
      await page.getByLabel('Rating combustível de comparação').selectOption(ratings[i+2].id);
      await page.getByLabel('Categoria, serviço e capacidade equivalente')
        .fill('LDV de passageiros, cinco lugares');
      await page.getByLabel('Justificativa conservadora para propor o par')
        .fill('Capacidade e categoria equivalente; mesmo ciclo combinado de teste.');
      const postedPair=page.waitForResponse(response=>
        new URL(response.url()).pathname.endsWith('/pairs') &&
        response.request().method()==='POST' && response.status()===201);
      await page.getByRole('button',{name:'Submeter par à revisão independente'}).click();
      await postedPair;
      assert.equal(pairs.length,i+1);
      await page.getByText('Par proposto. Outra pessoa habilitada',{exact:false}).waitFor();
    }
    assert.equal(pairs.filter(p=>p.status==='PROPOSED').length,2);
    role='reviewer';
    await page.reload();
    await page.getByRole('button',{name:'Eficiências e comparáveis'}).click();
    for(let i=0;i<2;i++){
      const pair=pairs[i];
      await page.locator('#cf-review-outcome-'+pair.id).selectOption('ACCEPTED');
      await page.locator('#cf-review-reason-'+pair.id)
        .fill('Conferência independente de categoria LDV, capacidade e ciclo combinado.');
      const record=page.locator('article.cf-platform-work-row').filter({has:page.locator('#cf-review-outcome-'+pair.id)});
      const reviewedPair=page.waitForResponse(response=>
        new URL(response.url()).pathname.endsWith('/review') &&
        response.request().method()==='POST' && response.status()===200);
      await record.getByRole('button',{name:'Registrar decisão da revisão'}).click();
      await reviewedPair;
      assert.equal(pair.status,'ACCEPTED');
      await page.getByText('Parecer de comparabilidade registrado.',{exact:false}).waitFor();
    }
    const afec=page.getByText('AFEC:',{exact:false}).first().locator('..');
    const mpg=page.getByText('MPG:',{exact:false}).first().locator('..');
    await afec.waitFor();await mpg.waitFor();
    assert.match(await afec.textContent(),/20/);
    assert.match(await mpg.textContent(),/30/);
    assert.equal(pairs.every(p=>p.status==='ACCEPTED'),true);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth),false);
  }finally{await browser.close();}
});
