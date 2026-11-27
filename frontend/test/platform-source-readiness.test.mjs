import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

test('staff MFA vê as fontes e necessidades de projetos, sem aprovação fictícia', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    let verified = false;
    let sourceCalls = 0;
    await page.route('**/v1/compliance/session', async route => {
      if (route.request().method() === 'POST') {
        assert.equal(route.request().postDataJSON().code, '123456');
        verified = true;
      }
      await route.fulfill({json:{verified,expiresAt:verified ? new Date(Date.now()+60000).toISOString() : null}});
    });
    await page.route('**/v1/platform/source-readiness/contexts', route => route.fulfill({json:[
      {methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',title:'Brasil - DCFC',
       country:'BR',networkMode:'OPEN',vehicleCategory:'LDV_BEV',chargerType:'DCFC',
       energySystem:'SIN',catalogState:'ACTIVE'},
    ]}));
    await page.route('**/v1/platform/source-readiness/VM0038?*', async route => {
      sourceCalls++;
      assert.equal(verified,true);
      const params = new URL(route.request().url()).searchParams;
      const all = [
          {code:'OPEN-EVR',stage:'BASELINE',kind:'DATASET',title:'Estoque BEV por modelo (EVR)',
            purpose:'Composição nacional',sourceId:'BR-LDV-BEV-STOCK-BY-MODEL',sourceEdition:'YEAR',
            officialUri:null,mandatory:true,conditionNote:'Não confundir vendas com estoque.',
            status:'MISSING_SOURCE_METADATA',nextAction:'Cadastrar fonte.'},
          {code:'OPEN-METHOD',stage:'APPLICABILITY',kind:'NORMATIVE',title:'VM0038 v1.1',
            purpose:'Regras do método',sourceId:'VM0038',sourceEdition:'1.1',
            officialUri:'https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/',
            mandatory:true,conditionNote:'Só metadados.',status:'METADATA_ONLY',
            nextAction:'Revisar licença.'},
          {code:'OPEN-RIGHTS',stage:'APPLICABILITY',kind:'PROJECT_EVIDENCE',title:'Divulgação aos motoristas',
            purpose:'Direitos sobre reduções',sourceId:null,sourceEdition:null,
            officialUri:null,mandatory:true,conditionNote:'Prova do projeto.',
            status:'PROJECT_SPECIFIC_EVIDENCE',nextAction:'Solicitar ao proponente.'},
      ].map(n => ({priority:'P0',blockedCapability:'PREPARATION',responsibleParty:'CARBONFLOW',
        editionPolicy:'EXACT',observedEdition:null,rightsScope:'NO_ACTIVE_SCOPED_RIGHTS',
        referenceAllowedInStaffOrganization:false,extractionAllowedInStaffOrganization:false,
        ragAllowedInStaffOrganization:false,modelAllowedInStaffOrganization:false,
        technicalAdmissionConfirmed:false,...n}));
      const filtered = all.filter(n => (!params.has('stage') || n.stage === params.get('stage'))
        && (!params.has('status') || n.status === params.get('status')));
      const offset = Number(params.get('offset') ?? 0);
      const limit = Number(params.get('limit') ?? 10);
      await route.fulfill({json:{
        methodology:'VM0038',edition:'1.1',scenario:'OPEN_LDV_DCFC_SIN_BR',
        asOf:'2026-11-19T14:00:00Z',normativeRuleStatus:'ONLY_SYNTHETIC_RULE_PACKS',
        dynamicCalculationStatus:'NOT_ENABLED_FOR_DYNAMIC_PROJECTS',normativeAiDisabled:true,
        missingSourceCount:1,missingReferenceRightsCount:1,reviewPendingCount:0,
        projectEvidenceCount:1,total:filtered.length,
        nextOffset:offset+limit<filtered.length?offset+limit:null,
        needs:filtered.slice(offset,offset+limit),
      }});
    });
    await page.goto(base+'/workspace/platform/sources');
    await page.getByRole('heading',{name:'Verificação da equipe interna'}).waitFor();
    assert.equal(sourceCalls,0);
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button',{name:'Desbloquear inventário'}).click();
    await page.getByRole('heading',{name:'VM0038 · edição 1.1'}).waitFor();
    await page.getByText('Estoque BEV por modelo (EVR)').waitFor();
    await page.getByText('Divulgação aos motoristas').waitFor();
    assert.match(await page.getByRole('status').allTextContents().then(x=>x.join(' ')),/sintétic/i);
    await page.getByLabel('Etapa da jornada').selectOption('BASELINE');
    assert.equal(await page.getByRole('heading',{name:'Estoque BEV por modelo (EVR)'}).count(),1);
    assert.equal(await page.getByRole('heading',{name:'VM0038 v1.1'}).count(),0);
    await page.getByLabel('Situação da fonte').selectOption('METADATA_ONLY');
    await page.getByText('Nenhuma necessidade corresponde aos filtros').waitFor();
    let posted = null;
    await page.route('**/v1/platform/open-parameter-bindings/options', route => route.fulfill({json:[{
      projectId:'20000000-0000-4000-8000-000000000097', title:'Rede aberta',
      triageVersion:1, pins:[],
    }]}));
    await page.route('**/v1/platform/open-fossil-factors', route => route.fulfill({json:{candidates:[{
      id:'30000000-0000-4000-8000-000000000001', periodYear:2036,
      status:'CHECKED_INTERNAL_NOT_ADMITTED', normalizedUnit:'tCO2e/USgal',
    }]}}));
    await page.route('**/v1/platform/open-grid-factors', route => route.fulfill({json:{candidates:[]}}));
    const catalogResolutions=[];
    await page.route(url => new URL(url).pathname === '/v1/platform/fuel-catalog', route => route.fulfill({json:{
      fuels:[{id:'GASOLINE_C',name:'Gasolina C'},{id:'ETHANOL_HYDRATED',name:'Etanol hidratado'}],
      entries:[{fuelId:'GASOLINE_C',parameter:'EMISSION_FACTOR',gas:'CO2_FOSSIL'},
               {fuelId:'ETHANOL_HYDRATED',parameter:'DENSITY'}]}}));
    await page.route(url => new URL(url).pathname === '/v1/platform/fuel-catalog/boundary-resolution', route => {
      const params = new URL(route.request().url()).searchParams;
      catalogResolutions.push(Object.fromEntries(params));
      return route.fulfill({json:{state:'RESOLVED_CANDIDATE',admission:'NOT_ADMITTED',
        requirements:[{review:'INTERNAL_OK',methodValue:'0.005693',methodUnit:'tCO2e/USgal'}]}});
    });
    await page.route(url => new URL(url).pathname === '/v1/platform/open-parameter-bindings', async route => {
      posted = {
        body: route.request().postDataJSON(),
        match: route.request().headers()['if-match'],
      };
      await route.fulfill({status:201, json:{version:1, usableForE8:false}});
    });
    let dyCanRecord=true,dyCanReview=false;
    await page.route('**/v1/platform/open-dy-assessments/options',route=>route.fulfill({json:{
      canRecord:dyCanRecord,canReview:dyCanReview,
      projects:[{projectId:'20000000-0000-4000-8000-000000000097',title:'Rede aberta'}],
    }}));
    const irProject='20000000-0000-4000-8000-000000000097';
    const irRoot='/v1/platform/open-ir-assessments/'+irProject;
    let irPost=null;
    const plannedMetric={source:'Fonte prevista',responsible:'Equipe técnica',procedure:'Revisar fontes anualmente'};
    const exAnteOption={id:'ex-ante-plan',version:3,sha256:'d'.repeat(64),plan:{evr:plannedMetric,afec:plannedMetric,mpg:plannedMetric,fuel:plannedMetric,rationale:'Plano ex ante para o primeiro ano'}};
    const actualSource=[{sourceId:'actual-source',edition:'2035',title:'Fonte efetiva sintética',referenceYear:2035}];
    const temporalWindow={yearFrom:'2036-01-01',yearUntil:'2037-01-01',periodFrom:'2036-02-01',periodUntil:'2036-03-01',reviewedOn:'2036-07-01'};
    let irRows=[{id:'ir-prior',projectId:irProject,projectYear:1,sourceYear:2035,version:1,
      period:'M1',fleetSelectionId:'fleet-prior',fuelPinId:'fuel-prior',frequency:'ANNUAL',
      basis:'PROJECT_SPECIFIC',rationale:'Referência anual anterior',state:'PENDING_REVIEW',
      assessmentSha256:'a'.repeat(64),candidate:'1',stale:false,usableForE8:false,
      recordedAt:'2035-01-01T00:00:00Z',review:null,annualEvidence:null,exAnteOption,
      reviewReferences:{fleetSnapshotId:'fleet-snapshot',ratingSnapshotId:'rating-snapshot',fuelCandidateId:'fuel-candidate',evr:actualSource,afec:actualSource,mpg:actualSource,fuel:actualSource}}];
    await page.route('**/v1/platform/open-ir-assessments/options',r=>r.fulfill({json:{
      canRecord:true,canReview:true,projects:[{projectId:irProject,title:'Rede aberta'}]}}));
    await page.route('**'+irRoot+'/period-options',r=>r.fulfill({json:[{
      period:'M2',projectYear:2,sourceYear:2036,latestVersion:irRows[0].projectYear===2?1:0,
      fleetSelectionId:'fleet-current',fuelPinId:'fuel-current'}]}));
    await page.route('**'+irRoot,async r=>{
      if(r.request().method()==='GET')return r.fulfill({json:irRows});
      irPost={body:r.request().postDataJSON(),match:r.request().headers()['if-match']};
      const saved={...irRows[0],...irPost.body,id:'ir-current',projectYear:2,sourceYear:2036,
        fleetSelectionId:'fleet-current',fuelPinId:'fuel-current',assessmentSha256:'b'.repeat(64),temporalWindow};
      irRows=[saved,...irRows];return r.fulfill({status:201,json:saved});
    });
    let initialReview=null;
    await page.route('**'+irRoot+'/ir-prior/review',async r=>{
      initialReview={body:r.request().postDataJSON(),match:r.request().headers()['if-match']};
      const saved={...irRows.find(p=>p.id==='ir-prior'),state:'INITIAL_BASELINE_CHECKED_NOT_ADMITTED',
        candidate:null,initialLinkState:'LINKED_CURRENT',exAnteOption:null,review:{...initialReview.body,reviewedAt:'2035-01-01T00:00:00Z',
          initialContext:{followupOn:'2036-01-01',planSha256:'e'.repeat(64),plan:initialReview.body.initialPlan,
            exAntePlan:{id:exAnteOption.id,version:exAnteOption.version,sha256:exAnteOption.sha256}}}};
      irRows=irRows.map(p=>p.id==='ir-prior'?saved:p);return r.fulfill({status:201,json:saved});
    });
    let irReview=null;
    await page.route('**'+irRoot+'/ir-current/review',async r=>{
      irReview={body:r.request().postDataJSON(),match:r.request().headers()['if-match']};
      const saved={...irRows[0],state:'CHECKED_INTERNAL_NOT_ADMITTED',
        review:{...irReview.body,reviewedAt:'2036-07-01T00:00:00Z'},
        annualEvidence:{priorAssessmentId:'ir-prior',evidenceSha256:'c'.repeat(64),
          state:'CONSECUTIVE_REFERENCES_NOT_ADMITTED',policy:'OPEN-IR-ANNUAL-EVIDENCE@1',window:temporalWindow,
          temporalItems:irReview.body.temporalEvidence.map(evidence=>({evidence,referenceSha256:'f'.repeat(64)}))}};
      irRows=[saved,...irRows.slice(1)];return r.fulfill({status:201,json:saved});
    });
    await page.getByRole('button', {name:'Pinos do projeto'}).click();
    await page.getByRole('heading', {name:'Pino de parâmetros do projeto'}).waitFor();
    await page.getByLabel('Projeto', {exact:true}).selectOption({label:'Rede aberta'});
    await page.getByLabel('Parâmetro', {exact:true}).selectOption('IR');
    await page.getByLabel('Ano', {exact:true}).fill('2036');
    await page.getByLabel('Valor declarado').fill('1');
    await page.getByLabel('Regra').fill('VM0038-1.1 regra explícita do projeto');
    await page.getByLabel('Prova').fill('prova ficticia do parametro');
    await page.getByRole('button', {name:'Gravar pino'}).click();
    await page.getByText('Pino gravado como preparação').waitFor();
    assert.equal(posted.body.parameterKind, 'IR');
    assert.equal(posted.body.declaredValue, '1');
    assert.equal(posted.body.candidateId, null);
    assert.equal(posted.body.useDefault, undefined);
    assert.equal(posted.match, '"0"');
    // UX-08D.1: the fossil factor is pinned by reference to the fuel catalog (CO2_ONLY), previewed read-only first.
    await page.getByLabel('Parâmetro', {exact:true}).selectOption('EF_FUEL');
    await page.getByLabel('Ano', {exact:true}).fill('2026');
    await page.getByLabel('Origem do fator').waitFor();
    assert.equal(await page.getByLabel('Combustível', {exact:true}).locator('option').allTextContents().then(x=>x.join('|')),
      'Selecione o combustível|Gasolina C'); // only fuels with a fossil-CO2 factor are offered
    await page.getByLabel('Combustível', {exact:true}).selectOption('GASOLINE_C');
    await page.getByLabel('Data de referência').fill('2026-09-01');
    await page.getByText('Resolvido com revisão do catálogo. Continua não admitido para o cálculo.').waitFor();
    assert.match(await page.getByRole('status').allTextContents().then(x=>x.join(' ')), /0\.005693 tCO2e\/USgal/);
    assert.deepEqual(catalogResolutions.at(-1), {fuelId:'GASOLINE_C', scope:'TTW', boundary:'CO2_ONLY', date:'2026-09-01'});
    posted = null;
    const catalogPinned = page.waitForResponse(response => new URL(response.url()).pathname === '/v1/platform/open-parameter-bindings'
      && response.request().method() === 'POST');
    await page.getByRole('button', {name:'Gravar pino'}).click();
    await catalogPinned;
    assert.equal(posted.body.parameterKind, 'EF_FUEL');
    assert.equal(posted.body.candidateId, null);
    assert.equal(posted.body.declaredValue, null);
    assert.deepEqual(posted.body.catalog, {fuelId:'GASOLINE_C', boundary:'CO2_ONLY', scope:'TTW', date:'2026-09-01'});
    // The legacy file-candidate path stays available and never carries a catalog reference.
    await page.getByLabel('Origem do fator').selectOption('CANDIDATE');
    await page.getByLabel('Fator revisado internamente').selectOption('30000000-0000-4000-8000-000000000001');
    posted = null;
    const legacyPinned = page.waitForResponse(response => new URL(response.url()).pathname === '/v1/platform/open-parameter-bindings'
      && response.request().method() === 'POST');
    await page.getByRole('button', {name:'Gravar pino'}).click();
    await legacyPinned;
    assert.equal(posted.body.candidateId, '30000000-0000-4000-8000-000000000001');
    assert.equal(posted.body.catalog, undefined);
    await page.getByText('Nenhum pino admite o cálculo nem emite crédito.').waitFor();
    const irPanel=page.getByRole('region',{name:'Avaliações de melhoria tecnológica (IR)'});
    await irPanel.getByLabel('Projeto da avaliação de IR').selectOption(irProject);
    await irPanel.getByLabel('Período com referências disponíveis').selectOption('M2');
    await irPanel.getByLabel('Frequência de atualização da linha de base').selectOption('ANNUAL');
    await irPanel.getByLabel('Fundamentação dos parâmetros').selectOption('PROJECT_SPECIFIC');
    await irPanel.getByLabel('Justificativa metodológica').fill('Atualização anual das referências canônicas.');
    await irPanel.getByRole('button',{name:'Registrar avaliação de IR'}).click();
    await irPanel.getByText('Avaliação registrada. Nenhum parâmetro foi admitido.').waitFor();
    assert.equal(irPost.match,'"0"');
    assert.deepEqual(irPost.body,{period:'M2',frequency:'ANNUAL',basis:'PROJECT_SPECIFIC',
      rationale:'Atualização anual das referências canônicas.'});
    const initial=irPanel.locator('details').filter({hasText:'Ano 1 · fonte 2035'});
    await initial.locator('summary').click();
    await initial.getByRole('button',{name:'Preparar parecer de IR'}).click();
    await initial.getByLabel('Decisão técnica').selectOption('INITIAL_BASELINE_CHECKED');
    await initial.getByLabel('Tipo de evidência anual').selectOption('PLAN_ONLY');
    await initial.getByLabel('Localizador da evidência').fill('INITIAL-PLAN');
    await initial.getByLabel('Fundamentação do parecer').fill('Conferência independente do plano inicial anual.');
    assert.equal(await initial.getByRole('button',{name:'Registrar parecer de IR'}).isDisabled(),true);
    for(const [label,text] of [['EVR','Fonte de frota e responsável pela atualização anual'],
      ['AFEC','Fonte de consumo e responsável pela atualização anual'],
      ['MPG','Fonte de comparáveis e responsável pela atualização anual'],
      ['EFfuel','Fonte fóssil e responsável pela atualização anual']]){
      await initial.getByLabel('Conciliação '+label,{exact:true}).selectOption(label==='EFfuel'?'JUSTIFIED_DEVIATION':'MATCH');
      await initial.getByLabel('Justificativa da conciliação '+label,{exact:true}).fill(text);
    }
    await initial.getByRole('button',{name:'Registrar parecer de IR'}).click();
    await initial.locator('summary').filter({hasText:'Plano inicial conferido — não admitido'}).waitFor();
    assert.equal(initialReview.match,'"1"');
    assert.equal(initialReview.body.priorAssessmentId,null);
    assert.equal(initialReview.body.annualUpdateBasis,'PLAN_ONLY');
    assert.ok(initialReview.body.initialPlan.fuel.startsWith('JUSTIFIED_DEVIATION:'));
    assert.ok(initialReview.body.initialPlan.evr.startsWith('MATCH:'));
    assert.equal(initialReview.body.exAntePlanId,exAnteOption.id);
    assert.equal(initialReview.body.exAntePlanVersion,3);
    assert.equal(initialReview.body.exAntePlanSha256,exAnteOption.sha256);
    const current=irPanel.locator('details').filter({hasText:'Ano 2 · fonte 2036'});
    await current.getByRole('button',{name:'Preparar parecer de IR'}).click();
    await current.getByLabel('Decisão técnica').selectOption('CHECKED_INTERNAL');
    await current.getByLabel('Tipo de evidência anual').selectOption('OBSERVED_UPDATE');
    await current.getByLabel('Localizador da evidência').fill('TEST_ONLY');
    await current.getByLabel('Fundamentação do parecer').fill('Conferência de dois conjuntos anuais consecutivos.');
    assert.equal(await current.getByRole('button',{name:'Registrar parecer de IR'}).isDisabled(),true);
    await current.getByLabel('Avaliação do ano anterior').selectOption('ir-prior');
    const annualSubmit=current.getByRole('button',{name:'Registrar parecer de IR'});
    assert.equal(await annualSubmit.isDisabled(),true);
    for(const label of ['EVR','AFEC','MPG','EFfuel']){
      await current.getByLabel('Data de atualização '+label,{exact:true}).fill('2036-02-15');
      await current.getByLabel('Vigência inicial '+label,{exact:true}).fill('2036-01-01');
      await current.getByLabel('Vigência final exclusiva '+label,{exact:true}).fill('2037-01-01');
      await current.getByLabel('Localizador da atualização '+label,{exact:true}).fill('DOC-'+label);
    }
    await current.getByLabel('Data de atualização EVR',{exact:true}).fill('2036-08-01');
    assert.equal(await annualSubmit.isDisabled(),true);
    await current.getByLabel('Data de atualização EVR',{exact:true}).fill('2036-02-15');
    await annualSubmit.click();
    await irPanel.getByText('Continuidade anual: Referências consecutivas — não admitidas.').waitFor();
    assert.equal(irReview.match,'"1"');
    assert.equal(irReview.body.priorAssessmentId,'ir-prior');
    assert.equal(irReview.body.assessmentSha256,'b'.repeat(64));
    assert.deepEqual(irReview.body.temporalEvidence.map(e=>e.metric),['EVR','AFEC','MPG','EF_FUEL']);
    assert.equal(irReview.body.temporalEvidence[0].updatedOn,'2036-02-15');
    const dyProject='20000000-0000-4000-8000-000000000097';
    let dyRows=[];
    let dyPost=null;
    let dyMode='OPEN';
    await page.route('**/v1/platform/open-dy-assessments/**',async route=>{
      const path=new URL(route.request().url()).pathname;
      if(path.endsWith('/options'))return route.fallback();
      if(route.request().method()==='GET'){
        if(dyMode==='DENIED')return route.fulfill({status:404,json:{}});
        const year=Number(path.split('/').at(-1));
        const latest=dyRows[0];
        const codes=['CALENDAR','ASSESSMENT','FRESHNESS','SOURCE_RIGHTS','ARITHMETIC',
          'SEARCH_COVERAGE','ISSUANCE_INVENTORY','GEOGRAPHIC_ALLOCATION','INDEPENDENT_REVIEW',
          'NORMATIVE_WINDOW','EXTERNAL_EVIDENCE','E8_ADMISSION'];
        const readiness={policyVersion:'OPEN-DY-READINESS@1',assessmentId:dyMode==='WRONG_REPORT'?'other-run':latest?.id??null,
          assessmentVersion:latest?.version??0,assessmentSha256:latest?.assessmentSha256??null,
          state:'BLOCKED',internalChecksComplete:false,usableForE8:dyMode==='ADMITTED',
          gates:codes.map((code,i)=>({code,status:i===0&&year!==1?'SATISFIED_INTERNAL':
            i===1&&latest?'SATISFIED_INTERNAL':i>1&&i<9&&!latest?'NOT_EVALUATED':'BLOCKED',
            detail:'Diagnóstico de teste '+code}))};
        return route.fulfill({json:{projectId:dyProject,projectYear:year,readiness,
          latestVersion:dyRows[0]?.version??0,priorStart:year===1?null:'2034-07-01T03:00:00Z',
          priorEndExclusive:year===1?null:'2035-07-01T03:00:00Z',
          calendarGate:year===1?'FIRST_YEAR_REVIEW_REQUIRED':null,assessments:dyRows,
          firstYearContext:year===1?{creditStart:dyMode==='MISSING'?null:'2034-07-01',
            exploratoryFrom:dyMode==='MISSING'?null:'2033-07-01',exploratoryUntilExclusive:dyMode==='MISSING'?null:'2034-07-01',
            contextGate:dyMode==='MISSING'?'CREDIT_START_MISSING':null,policyVersion:'FIRST_YEAR_EXPLORATORY@1'}:null}});
      }
      dyPost={body:route.request().postDataJSON(),match:route.request().headers()['if-match']};
      if(path.endsWith('/review')){
        const current=dyRows[0];
        assert.equal(path.split('/').at(-2),current.id);
        const review={...dyPost.body,reviewedAt:'2026-11-24T12:00:00Z'};
        const saved={...current,review,state:'NEEDS_INFORMATION_NOT_ADMITTED'};
        dyRows=[saved,...dyRows.slice(1)];
        return route.fulfill({status:201,json:saved});
      }
      const saved={id:'dy-test-'+(dyRows.length+1),projectId:dyProject,projectYear:dyPost.body.projectYear,
        version:dyRows.length+1,outcome:dyPost.body.outcome,erc:dyPost.body.erc,erf:dyPost.body.erf,
        dyPreview:dyPost.body.outcome==='INCONCLUSIVE'?null:'0.75',
        priorStart:'2034-07-01T03:00:00Z',priorEndExclusive:'2035-07-01T03:00:00Z',
        programs:dyPost.body.programs,projectReferences:dyPost.body.projectReferences,
        evidenceLocator:dyPost.body.evidenceLocator,coverageNote:dyPost.body.coverageNote,
        assessmentSha256:'a'.repeat(64),state:'DECLARED_NOT_ADMITTED',stale:false,usableForE8:false,
        issuanceCount:dyPost.body.issuances?.length??0,issuances:dyPost.body.issuances??[],
        sourceRightsState:dyPost.body.sourceLicenses?.length?'LICENSE_DECISIONS_CURRENT':'NO_LICENSE_PIN',
        sourceLicenses:(dyPost.body.sourceLicenses??[]).map(pin=>({...pin,decisionVersion:1})),
        coverageCount:dyPost.body.searchCoverage?.length??0,searchCoverage:dyPost.body.searchCoverage??[],
        coverageState:dyPost.body.searchCoverage?.length?(dyPost.body.outcome==='INCONCLUSIVE'?'SEARCH_INCONCLUSIVE':'DECLARED_MATRIX_COMPLETE'):'NO_SEARCH_MATRIX',
        allocationCount:dyPost.body.allocations?.length??0,
        allocationState:dyPost.body.allocations?.length?'DECLARED_ALLOCATION_RECONCILED':'NO_STRUCTURED_ALLOCATION',
        allocations:(dyPost.body.allocations??[]).map((a,i)=>({...a,brazilTco2e:dyPost.body.issuances[i].creditsTco2e}))};
      dyRows=[saved,...dyRows];
      return route.fulfill({status:201,json:saved});
    });
    await page.getByLabel('Projeto da avaliação',{exact:true}).selectOption(dyProject);
    await page.getByLabel('Ano relativo do projeto',{exact:true}).fill('2');
    await page.getByRole('button',{name:'Consultar avaliações de Dy'}).click();
    await page.getByText('Nenhuma avaliação registrada para este ano.').waitFor();
    const diagnosis=page.getByRole('region',{name:'Prontidão consolidada de Dy'});
    await diagnosis.getByText('Bloqueado para E8',{exact:true}).waitFor();
    await diagnosis.getByText('Nenhuma avaliação vigente para diagnosticar.',{exact:true}).waitFor();
    await diagnosis.getByText('Avaliação vigente — Pendente',{exact:true}).waitFor();
    const outcome=page.getByLabel('Resultado da pesquisa',{exact:true});
    await outcome.selectOption('FLEET_CREDITS_FOUND');
    await page.getByLabel('ERC emitidos · tCO2e').fill('75');
    await page.getByLabel('ERF emitidos · tCO2e').fill('25');
    for (const [i,kind,amount] of [[1,'ERC','75'],[2,'ERF','25']]) {
      await page.getByRole('button',{name:'Adicionar emissão declarada'}).click();
      await page.getByLabel('Programa da emissão '+i,{exact:true}).fill('TEST-PROGRAM');
      await page.getByLabel('ID do projeto no registro '+i,{exact:true}).fill('PROJECT-'+i);
      await page.getByLabel('ID da emissão '+i,{exact:true}).fill('ISS-'+i);
      await page.getByLabel('Categoria da emissão '+i,{exact:true}).selectOption(kind);
      await page.getByLabel('Data da emissão '+i,{exact:true}).fill('2034-08-01');
      await page.getByLabel('Quantidade da emissão '+i+' · tCO2e',{exact:true}).fill(amount);
      await page.getByLabel('ID da fonte '+i,{exact:true}).fill('TEST-REGISTRY');
      await page.getByLabel('Edição da fonte '+i,{exact:true}).fill('1');
      await page.getByLabel('Localizador da emissão '+i,{exact:true}).fill('TEST_ONLY item '+i);
    }
    await page.getByLabel('Fundamentar atribuição geográfica das emissões').check();
    for (const [i,total,num,den,method] of [[1,'100','3','4','DOCUMENTED_SHARE'],[2,'25','1','1','DIRECT_BR']]) {
      await page.getByLabel('Modalidade da atribuição '+i,{exact:true}).selectOption(method);
      await page.getByLabel('Total original da emissão '+i+' · tCO2e',{exact:true}).fill(total);
      await page.getByLabel('Numerador da base '+i,{exact:true}).fill(num);
      await page.getByLabel('Denominador da base '+i,{exact:true}).fill(den);
      await page.getByLabel('Unidade da base '+i,{exact:true}).fill('TEST_ONLY activity');
      await page.getByLabel('ID da fonte da atribuição '+i,{exact:true}).fill('TEST-REGISTRY');
      await page.getByLabel('Edição da fonte da atribuição '+i,{exact:true}).fill('1');
      await page.getByLabel('Localizador da atribuição '+i,{exact:true}).fill('TEST_ONLY split '+i);
      await page.getByLabel('Fundamentação da atribuição '+i,{exact:true}).fill('Declared territorial attribution fixture');
    }
    for (const [i,kind] of [[1,'ERC'],[2,'ERF']]) {
      await page.getByRole('button',{name:'Adicionar consulta de cobertura'}).click();
      await page.getByLabel('Programa consultado '+i,{exact:true}).fill('TEST-PROGRAM');
      await page.getByLabel('Categoria consultada '+i,{exact:true}).selectOption(kind);
      await page.getByLabel('Abrangência da consulta '+i,{exact:true}).selectOption('NATIONAL_BR');
      await page.getByLabel('Resultado da consulta '+i,{exact:true}).selectOption('FOUND');
      await page.getByLabel('Início da janela consultada '+i,{exact:true}).fill('2034-07-01');
      await page.getByLabel('Fim exclusivo da janela consultada '+i,{exact:true}).fill('2035-07-01');
      await page.getByLabel('Data da consulta '+i,{exact:true}).fill('2035-07-02');
      await page.getByLabel('ID da fonte da consulta '+i,{exact:true}).fill('TEST-REGISTRY');
      await page.getByLabel('Edição da fonte da consulta '+i,{exact:true}).fill('1');
      await page.getByLabel('Localizador da consulta '+i,{exact:true}).fill('TEST_ONLY query '+i);
      await page.getByLabel('Limites e filtros da consulta '+i,{exact:true}).fill('Universo declarado fictício');
    }
    assert.equal(await page.getByLabel('Vincular licenças das fontes').isChecked(),true);
    assert.equal(await page.getByLabel('Vincular licenças das fontes').isDisabled(),true);
    await page.getByLabel('ID da licença · TEST-REGISTRY / 1',{exact:true}).fill('10000000-0000-4000-8000-000000000085');
    await page.getByLabel('Programas pesquisados').fill('Programa fictício');
    await page.getByLabel('Projetos e categorias pesquisados').fill('Projeto fictício da frota');
    await page.getByLabel('Localizador da evidência').fill('TEST_ONLY página 1');
    await page.getByLabel('Cobertura, fontes e limitações da pesquisa').fill('Pesquisa declaratória de teste');
    await page.getByRole('button',{name:'Registrar avaliação de Dy'}).click();
    await page.getByText('Avaliação registrada em nova versão, sem admissão para E8.').waitFor();
    const firstDy=page.getByText('Versão 1 · FLEET_CREDITS_FOUND · Sem admissão');
    await diagnosis.getByText('Diagnóstico da versão vigente 1.',{exact:false}).waitFor();
    await diagnosis.getByText('Admissão E8 — Pendente',{exact:true}).waitFor();
    await firstDy.click();
    await page.getByText('0.75',{exact:true}).waitFor();
    assert.equal(dyPost.match,'"0"');
    assert.equal(dyPost.body.projectYear,2);
    assert.equal(dyPost.body.erc,'75');
    assert.equal(dyPost.body.erf,'25');
    assert.equal(dyPost.body.dyPreview,undefined);
    assert.equal(dyPost.body.issuances.length,2);
    assert.equal(dyPost.body.issuances[1].creditKind,'ERF');
    assert.deepEqual(dyPost.body.sourceLicenses,[{sourceId:'TEST-REGISTRY',sourceEdition:'1',licenseId:'10000000-0000-4000-8000-000000000085'}]);
    await page.getByText('Decisões de licença vigentes na consulta, sem admissão normativa.',{exact:true}).waitFor();
    await page.getByText('Emissões declaradas · 2',{exact:true}).waitFor();
    await page.getByText('Matriz de cobertura · 2 consultas',{exact:true}).waitFor();
    assert.equal(dyPost.body.searchCoverage.length,2);
    assert.equal(dyPost.body.searchCoverage[1].creditKind,'ERF');
    await page.getByText('Atribuição geográfica · 2 emissões',{exact:true}).waitFor();
    assert.equal(dyPost.body.allocations.length,2);
    assert.equal(dyPost.body.allocations[0].shareNumerator,'3');
    assert.equal(dyPost.body.issuances[0].allocation,undefined);
    await outcome.selectOption('INCONCLUSIVE');
    await page.getByLabel('ERC emitidos · tCO2e').waitFor({state:'detached'});
    await page.getByRole('button',{name:'Registrar avaliação de Dy'}).click();
    await page.getByText('Versão 2 · INCONCLUSIVE · Sem admissão').click();
    await page.getByText('Indisponível — ausência não é zero',{exact:true}).waitFor();
    assert.equal(dyPost.match,'"1"');
    assert.equal(dyPost.body.erc,null);
    assert.equal(dyPost.body.erf,null);
    assert.deepEqual(dyPost.body.issuances,[]);
    assert.deepEqual(dyPost.body.sourceLicenses,[]);
    assert.deepEqual(dyPost.body.searchCoverage,[]);
    assert.deepEqual(dyPost.body.allocations,[]);
    dyCanRecord=false;dyCanReview=true;
    await page.getByRole('button',{name:'Atualizar projetos de Dy'}).click();
    await outcome.waitFor({state:'detached'});
    const latestSummary=page.getByText('Versão 2 · INCONCLUSIVE · Sem admissão');
    if(!(await page.getByLabel('Parecer técnico interno').isVisible()))await latestSummary.click();
    const opinion=page.getByLabel('Parecer técnico interno');
    await opinion.waitFor();
    assert.equal(await opinion.locator('option[value="CHECKED_INTERNAL"]').isDisabled(),true);
    await opinion.selectOption('NEEDS_INFORMATION');
    await page.getByLabel('Regra revisada',{exact:true}).fill('VM0038-1.1 EQ8');
    await page.getByLabel('Referências consultadas no parecer').fill('TEST_ONLY registro fictício');
    await page.getByLabel('Fundamentação do parecer').fill('Pesquisa inconclusiva exige informações adicionais');
    await page.getByRole('button',{name:'Registrar parecer interno de Dy'}).click();
    await page.getByText('Parecer interno registrado, sem admissão para E8.').waitFor();
    await latestSummary.click();
    await page.getByText('Parecer interno desta versão',{exact:true}).waitFor();
    assert.equal(dyPost.match,'"2"');
    assert.equal(dyPost.body.assessmentSha256,'a'.repeat(64));
    assert.equal(dyPost.body.outcome,'NEEDS_INFORMATION');
    dyCanRecord=true;dyCanReview=false;
    await page.getByRole('button',{name:'Atualizar projetos de Dy'}).click();
    await outcome.waitFor();
    dyRows=[];
    await page.getByLabel('Ano relativo do projeto',{exact:true}).fill('1');
    await firstDy.waitFor({state:'detached'});
    await page.getByRole('button',{name:'Consultar avaliações de Dy'}).click();
    await page.getByText('Janela anterior não resolvida:',{exact:false}).waitFor();
    assert.equal(await outcome.locator('option[value="FLEET_CREDITS_FOUND"]').isDisabled(),true);
    assert.equal(await outcome.locator('option[value="NO_FLEET_CREDITS_DECLARED"]').isDisabled(),true);
    await page.getByText('Janela exploratória pré-início: 2033-07-01 até 2034-07-01 (fim exclusivo).',{exact:true}).waitFor();
    await outcome.selectOption('INCONCLUSIVE');
    const addQuery=page.getByRole('button',{name:'Adicionar consulta de cobertura'});
    await addQuery.waitFor();
    assert.equal(await addQuery.isEnabled(),true);
    assert.equal(await page.getByLabel('ERC emitidos · tCO2e').count(),0);
    dyMode='MISSING';
    await page.getByRole('button',{name:'Consultar avaliações de Dy'}).click();
    await page.getByText('Início não resolvido: CREDIT_START_MISSING.',{exact:false}).waitFor();
    assert.equal(await addQuery.isDisabled(),true);
    for (const mode of ['WRONG_REPORT','ADMITTED']) {
      dyMode=mode;
      await page.getByRole('button',{name:'Consultar avaliações de Dy'}).click();
      await diagnosis.waitFor({state:'detached'});
      await page.getByRole('alert').filter({hasText:'Não foi possível consultar ou registrar a avaliação de Dy.'}).waitFor();
    }
    dyMode='DENIED';
    await page.getByRole('button',{name:'Consultar avaliações de Dy'}).click();
    await page.getByRole('alert').filter({hasText:'Avaliação indisponível para esta conta.'}).waitFor();
    await outcome.waitFor({state:'detached'});

    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});
