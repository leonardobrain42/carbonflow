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
    await page.getByText('Nenhum pino admite o cálculo nem emite crédito.').waitFor();
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
        return route.fulfill({json:{projectId:dyProject,projectYear:year,
          latestVersion:dyRows[0]?.version??0,priorStart:year===1?null:'2034-07-01T03:00:00Z',
          priorEndExclusive:year===1?null:'2035-07-01T03:00:00Z',
          calendarGate:year===1?'FIRST_YEAR_REVIEW_REQUIRED':null,assessments:dyRows}});
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
        issuanceCount:dyPost.body.issuances?.length??0,issuances:dyPost.body.issuances??[]};
      dyRows=[saved,...dyRows];
      return route.fulfill({status:201,json:saved});
    });
    await page.getByLabel('Projeto da avaliação',{exact:true}).selectOption(dyProject);
    await page.getByLabel('Ano relativo do projeto',{exact:true}).fill('2');
    await page.getByRole('button',{name:'Consultar avaliações de Dy'}).click();
    await page.getByText('Nenhuma avaliação registrada para este ano.').waitFor();
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
    await page.getByLabel('Programas pesquisados').fill('Programa fictício');
    await page.getByLabel('Projetos e categorias pesquisados').fill('Projeto fictício da frota');
    await page.getByLabel('Localizador da evidência').fill('TEST_ONLY página 1');
    await page.getByLabel('Cobertura, fontes e limitações da pesquisa').fill('Pesquisa declaratória de teste');
    await page.getByRole('button',{name:'Registrar avaliação de Dy'}).click();
    await page.getByText('Avaliação registrada em nova versão, sem admissão para E8.').waitFor();
    const firstDy=page.getByText('Versão 1 · FLEET_CREDITS_FOUND · Sem admissão');
    await firstDy.click();
    await page.getByText('0.75',{exact:true}).waitFor();
    assert.equal(dyPost.match,'"0"');
    assert.equal(dyPost.body.projectYear,2);
    assert.equal(dyPost.body.erc,'75');
    assert.equal(dyPost.body.erf,'25');
    assert.equal(dyPost.body.dyPreview,undefined);
    assert.equal(dyPost.body.issuances.length,2);
    assert.equal(dyPost.body.issuances[1].creditKind,'ERF');
    await page.getByText('Emissões declaradas · 2',{exact:true}).waitFor();
    await outcome.selectOption('INCONCLUSIVE');
    await page.getByLabel('ERC emitidos · tCO2e').waitFor({state:'detached'});
    await page.getByRole('button',{name:'Registrar avaliação de Dy'}).click();
    await page.getByText('Versão 2 · INCONCLUSIVE · Sem admissão').click();
    await page.getByText('Indisponível — ausência não é zero',{exact:true}).waitFor();
    assert.equal(dyPost.match,'"1"');
    assert.equal(dyPost.body.erc,null);
    assert.equal(dyPost.body.erf,null);
    assert.deepEqual(dyPost.body.issuances,[]);
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
    dyMode='DENIED';
    await page.getByRole('button',{name:'Consultar avaliações de Dy'}).click();
    await page.getByRole('alert').filter({hasText:'Avaliação indisponível para esta conta.'}).waitFor();
    await outcome.waitFor({state:'detached'});

    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth),false);
  } finally {
    await browser.close();
  }
});
