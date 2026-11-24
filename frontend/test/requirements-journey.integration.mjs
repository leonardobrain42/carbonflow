import assert from 'node:assert/strict';
import {createHash, randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {chromium} from 'playwright-core';

// Run against the opt-in, disposable Compose sandbox. All application requests
// reach Quarkus/PostgreSQL; no routes, identities, grants or diagnoses are mocked.
const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:18088';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const accounts=new URL('../../.carbonflow-demo/accounts/',import.meta.url);
const finding=(view,id)=>view.requirements.find(item=>item.requirementId===id);

async function login(context,email){
  assert.ok(['127.0.0.1','localhost'].includes(new URL(base).hostname),
    'This integration test only writes to a local sandbox');
  const name=createHash('sha256').update(email).digest('hex')+'.json';
  const credentials=JSON.parse(await readFile(new URL(name,accounts),'utf8'));
  const response=await context.request.post(base+'/v1/account-session',{
    data:{email:credentials.email,password:credentials.password},
  });
  assert.equal(response.status(),204,'Provisioned sandbox account must authenticate');
}

async function get(context,path){
  const response=await context.request.get(base+path);
  assert.equal(response.status(),200,'GET '+path);
  assert.match(response.headers()['cache-control'],/no-store/);
  return response.json();
}

async function createProject(context){
  const list=await context.request.get(base+'/v1/organizations');
  assert.equal(list.status(),200);
  let [organization]=await list.json();
  if(!organization){
    const created=await context.request.post(base+'/v1/organizations',{
      headers:{'Idempotency-Key':randomUUID()},data:{name:'UX-08 integration TEST_ONLY'},
    });
    assert.equal(created.status(),201);
    organization=await created.json();
  }
  const response=await context.request.post(base+'/v1/projects',{
    headers:{'Idempotency-Key':randomUUID()},
    data:{organizationId:organization.id,title:'UX-08 TEST_ONLY '+randomUUID(),
      description:'Disposable integration fixture; no official data or credit.'},
  });
  assert.equal(response.status(),201);
  const {projectId}=await response.json();
  const intake=await context.request.put(base+'/v1/projects/'+projectId+'/technical/intake',{
    headers:{'Idempotency-Key':randomUUID(),'If-Match':'"0"'},
    data:{activity:'Recarga aberta TEST_ONLY',country:'BR',state:'DF',locality:'Brasília',
      candidateMethodology:'VM0038'},
  });
  assert.equal(intake.status(),200);
  return projectId;
}

for(const viewport of [{width:390,height:844},{width:1440,height:900}]){
  test(`UX-08 real ${viewport.width}: save, shared diagnosis, resume, conflict and ACL`,
    {timeout:120_000},async()=>{
    const browser=await chromium.launch({executablePath});
    const context=await browser.newContext({viewport});
    const outsider=await browser.newContext();
    try{
      await login(context,'demo@carbonflow.test');
      const project=await createProject(context);
      const root='/v1/projects/'+project;
      const requirements=root+'/technical/requirements';
      const declaration=root+'/technical/open-applicability';
      const technical='/workspace/projects/'+project+'/technical?secao=analisar';
      const network='/workspace/projects/'+project+'/technical?secao=rede';
      const rights='/workspace/projects/'+project+'/technical?secao=direitos';
      const before=await get(context,requirements);
      assert.equal(finding(before,'NO_DECLARATION').state,'ABSENT');

      const page=await context.newPage();
      let reads=0;
      page.on('request',request=>{
        if(new URL(request.url()).pathname===requirements)reads++;
      });
      const diagnosis=page.locator('carbonflow-project-requirements');
      const checkpoint=page.waitForResponse(response=>
        new URL(response.url()).pathname===root+'/journey/resume'
        &&response.request().method()==='PUT');
      await page.goto(base+technical);
      assert.equal((await checkpoint).status(),200);
      await diagnosis.getByText(finding(before,'NO_DECLARATION').reason,{exact:true}).waitFor();

      await page.getByRole('button',{name:'Preencher acesso à rede'}).click();
      await page.getByLabel('Qualquer BEV compatível pode utilizar?').selectOption('true');
      await page.getByLabel('Restringe o uso a veículos identificados?').selectOption('false');
      await page.getByLabel('Tecnologia de recarga',{exact:true}).selectOption('DCFC');
      await page.getByLabel('Veículos atendidos',{exact:true}).selectOption('BEV');
      await page.getByLabel('Energia do sistema',{exact:true}).selectOption('SIN');
      await page.getByLabel('Existe armazenamento/geração associada?').selectOption('false');
      await page.getByLabel('O carregador devolve energia à rede (V2G)?').selectOption('false');
      const save=page.waitForResponse(response=>
        new URL(response.url()).pathname===declaration&&response.request().method()==='PUT');
      await page.getByRole('button',{name:'Salvar acesso sem aprovação automática'}).click();
      assert.equal((await save).status(),200);
      // The status screen reads the server diagnosis again when it opens.
      const readsBeforeStatus=reads;
      const reread=page.waitForResponse(response=>
        new URL(response.url()).pathname===requirements&&response.request().method()==='GET');
      const statusCheckpoint=page.waitForResponse(response=>
        new URL(response.url()).pathname===root+'/journey/resume'
        &&response.request().method()==='PUT');
      await page.getByRole('navigation',{name:'Seções da preparação técnica'})
        .getByRole('button',{name:'Situação do projeto'}).click();
      assert.equal((await statusCheckpoint).status(),200);
      const refreshed=await reread;
      assert.equal(refreshed.status(),200);
      const after=await refreshed.json();
      assert.ok(reads>readsBeforeStatus,'The status screen must read the server diagnosis');
      assert.equal(finding(after,'NO_DECLARATION'),undefined);
      assert.equal(finding(after,'DRIVER_DISCLOSURE').state,'NEEDS_EVIDENCE');
      assert.equal(after.canRunE8,false);
      assert.equal(after.canIssueVcu,false);
      assert.equal(after.exAnteRequiresMeasuredEnergy,false);
      assert.ok(after.requirements.every(item=>!item.admitsE8));
      await diagnosis.getByText(finding(after,'DRIVER_DISCLOSURE').reason,{exact:true}).waitFor();
      assert.equal(await diagnosis.getByText(finding(before,'NO_DECLARATION').reason,{exact:true}).count(),0);
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);

      // Both screens consume the same persisted owner state, including IDs,
      // groups, authorized destinations and the checkpoint recorded by the UI.
      const journey=await get(context,root+'/journey');
      assert.deepEqual(journey.diagnosis,after);
      assert.equal(journey.resume.screen,'analisar');
      await page.goto(base+'/workspace/projects/'+project+'/overview');
      await page.getByRole('heading',{name:'Pendências do diagnóstico'}).waitFor();
      const disclosure=finding(after,'DRIVER_DISCLOSURE');
      await page.getByText(disclosure.reason,{exact:true}).waitFor();
      const resume=page.getByRole('link',{name:'Retomar minha etapa'});
      const resumeUrl=new URL(await resume.getAttribute('href'),base);
      assert.equal(resumeUrl.pathname,'/workspace/projects/'+project+'/technical');
      assert.equal(resumeUrl.searchParams.get('secao'),'analisar');
      await resume.click();
      await diagnosis.getByText(disclosure.reason,{exact:true}).waitFor();
      const action=diagnosis.getByRole('button',{name:'Abrir as estações'});
      await action.focus();
      await page.keyboard.press('Enter');
      await page.getByRole('heading',{name:'Estações e fronteira',exact:true}).waitFor();
      await page.goto(base+technical);
      await diagnosis.getByText(disclosure.reason,{exact:true}).waitFor();
      // The pending item leads straight to the screen that resolves it.
      await diagnosis.getByRole('button',{name:'Abrir direitos do projeto'}).first().click();
      await page.getByRole('heading',{name:'Titular, contrato e divulgação'}).waitFor();

      // A competing real write leaves the mounted form stale. Its rejected
      // If-Match must neither refresh the diagnosis as a success nor overwrite it.
      await page.goto(base+network);
      await page.getByLabel('Qualquer BEV compatível pode utilizar?').waitFor();
      const current=await get(context,declaration);
      const concurrent=await context.request.put(base+declaration,{
        headers:{'Idempotency-Key':randomUUID(),'If-Match':'"'+current.version+'"'},
        data:{...current.latest.answers,explanation:'Concurrent revision TEST_ONLY'},
      });
      assert.equal(concurrent.status(),200);
      const committed=await concurrent.json();
      const readsBeforeConflict=reads;
      const conflict=page.waitForResponse(response=>
        new URL(response.url()).pathname===declaration&&response.request().method()==='PUT');
      await page.getByRole('button',{name:'Salvar acesso sem aprovação automática'}).click();
      assert.equal((await conflict).status(),412);
      await page.getByRole('alert').filter({hasText:'Outra revisão foi salva.'}).waitFor();
      assert.equal(reads,readsBeforeConflict,'A failed save must not emit success');
      assert.equal((await get(context,declaration)).version,committed.version);
      await page.goto(base+rights);
      await page.getByLabel('Observações e limitações da declaração').waitFor();
      assert.equal(await page.getByLabel('Observações e limitações da declaração').inputValue(),
        'Concurrent revision TEST_ONLY');

      await login(outsider,'segunda@carbonflow.test');
      for(const path of [requirements,root+'/journey']){
        const denied=await outsider.request.get(base+path);
        assert.equal(denied.status(),404,'A different account cannot read the project');
        assert.ok(!(await denied.text()).includes('DRIVER_DISCLOSURE'));
      }
      const deniedCheckpoint=await outsider.request.put(base+root+'/journey/resume',{
        data:{area:'technical',screen:'analisar',guide:null,step:null},
      });
      assert.equal(deniedCheckpoint.status(),404);
      const deniedSave=await outsider.request.put(base+declaration,{
        headers:{'Idempotency-Key':randomUUID(),'If-Match':'"'+committed.version+'"'},
        data:committed.latest.answers,
      });
      assert.equal(deniedSave.status(),404);
      const foreignPage=await outsider.newPage();
      await foreignPage.goto(base+technical);
      await foreignPage.getByRole('alert').waitFor();
      assert.equal(await foreignPage.getByRole('heading',{name:'O que este projeto ainda precisa'}).count(),0);
      assert.equal(await foreignPage.getByRole('button',{name:'Salvar declaração sem aprovação automática'}).count(),0);
    }finally{
      await context.request.delete(base+'/v1/account-session').catch(()=>{});
      await outsider.request.delete(base+'/v1/account-session').catch(()=>{});
      await browser.close();
    }
  });
}
