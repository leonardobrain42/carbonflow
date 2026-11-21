import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base=process.env.CARBONFLOW_TEST_URL??'http://127.0.0.1:8080';
const executablePath=process.env.CARBONFLOW_CHROME??'/usr/bin/google-chrome';
const project='20000000-0000-4000-8000-000000000001';
const root='/v1/projects/'+project;

test('UX-03B: duas estações ligam PDFs privados distintos, status não admitido e revisão segregada no mobile',async()=>{
 const browser=await chromium.launch({executablePath});
 try {
  const page=await browser.newPage({viewport:{width:390,height:844}});
  const details=code=>({name:'Estação '+code,municipality:'Brasília',state:'DF',
    address:'Via '+code,operationalState:'PLANNED',plannedOn:'2027-01-01',
    commissionedOn:null,sourceReference:'REF-'+code});
  const sites=['ST-01','ST-02'].map(code=>({code,version:1,details:details(code),
    recordedAt:'2026-11-20T00:00:00Z'}));
  let reviewer=false;let seenUploads=0;const docs={ 'ST-01':[], 'ST-02':[] };
  await page.route('**'+root+'/metadata',r=>r.fulfill({json:{
    projectId:project,environment:'SANDBOX',organizationId:'10000000-0000-4000-8000-000000000001',
    title:'Rede aberta',state:'DRAFT',version:1}}));
  await page.route('**'+root+'/technical/capabilities',r=>r.fulfill({json:{
    projectId:project,environment:'SANDBOX',owner:!reviewer,
    actions:['VIEW_INTAKE','VIEW_OPEN_MONITORING',...(reviewer?[]:['SAVE_OPEN_MONITORING'])],
    modules:{}}}));
  await page.route('**'+root+'/technical/intake',r=>r.fulfill({json:{
    projectId:project,environment:'SANDBOX',version:1,state:'RASCUNHO',reviewState:'NAO_AVALIADO',
    data:{candidateMethodology:'VM0038'},
    journey:{id:'VM0038_V1_1',candidate:'VM0038',title:'VM0038',
      description:'Projeto aberto',technicalTopics:[]},
    methodologies:[],outstandingGates:[],triageCandidate:'VM0038'
  }}));
  await page.route('**'+root+'/technical/open-sites',r=>r.fulfill({json:{
    projectId:project,environment:'SANDBOX',sites,chargers:[],
    boundary:{version:0,selected:[],state:'NO_SELECTION',findings:[],usableForE8:false}
  }}));
  const ev=root+'/technical/open-sites/evidence';
  for(const code of Object.keys(docs)){
    await page.route('**'+ev+'/SITE/'+code,r=>{
      assert.equal(r.request().method(),'GET');
      return r.fulfill({json:{subjectType:'SITE',subjectCode:code,currentVersion:1,
        canUpload:!reviewer,canReview:reviewer,documents:docs[code]}});
    });
    await page.route('**'+ev+'/SITE/'+code+'/revisions/1',r=>{
      assert.equal(r.request().method(),'POST');
      assert.equal(r.request().headers()['content-type'],'application/pdf');
      assert.equal(r.request().headers()['x-evidence-purpose'],'LOCATION');
      assert.ok(r.request().headers()['idempotency-key']);
      const body=r.request().postDataBuffer();
      assert.ok(body.subarray(0,5).toString()==='%PDF-');
      seenUploads++;
      const doc={id:'20000000-0000-4000-8000-'+String(seenUploads).padStart(12,'0'),
        subjectType:'SITE',subjectCode:code,subjectVersion:1,purpose:'LOCATION',
        evidenceReference:r.request().headers()['x-evidence-reference'],
        locator:r.request().headers()['x-evidence-locator'],
        fileName:r.request().headers()['x-file-name'],sha256:'f'.repeat(64),
        bytesCount:body.length,submittedBy:'author',submittedAt:'2026-11-20T00:00:00Z',
        review:null,state:'PENDING_REVIEW',stale:false,usableForE8:false};
      docs[code].push(doc);return r.fulfill({status:201,json:doc});
    });
  }
  await page.route('**'+ev+'/documents/*/review',r=>{
    const id=r.request().url().split('/').at(-2);
    const doc=Object.values(docs).flat().find(d=>d.id===id);
    assert.ok(doc);assert.equal(r.request().headers()['if-match'],'"0"');
    const review=r.request().postDataJSON();
    assert.equal(review.outcome,'DOCUMENT_CHECKED_INTERNAL');
    assert.ok(review.reason.length>=15);
    doc.review={version:1,...review,reviewer:'reviewer',reviewedAt:'2026-11-20T00:01:00Z'};
    doc.state='CHECKED_INTERNAL';
    return r.fulfill({json:doc.review});
  });
  await page.goto(base+'/workspace/projects/'+project+'/technical?secao=estacoes');
  await page.getByText('Estação ST-01 · ST-01',{exact:false}).waitFor();
  await page.getByRole('button',{name:'Anexar ou consultar provas'}).first().click();
  await page.getByRole('heading',{name:'Comprovações de estação · ST-01'}).waitFor();
  await page.getByText('Nenhum PDF anexado a este ativo.',{exact:false}).waitFor({state:'visible'});
  assert.equal(await page.getByText('Nenhum PDF anexado a este ativo.',{exact:false}).count(),1);
  await page.getByLabel('Tipo de comprovação').selectOption('LOCATION');
  await page.getByLabel('Origem e identificação do documento').fill('Planta de referência da estação ST-01');
  await page.getByLabel('Localizador no documento (opcional)').fill('página 2');
  await page.getByLabel('Arquivo PDF (máximo 2 MB)').setInputFiles({
    name:'site-proof.pdf',mimeType:'application/pdf',
    buffer:Buffer.from('%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n')
  });
  await page.getByRole('button',{name:'Anexar PDF protegido'}).click();
  await page.getByText('PDF recebido e protegido.',{exact:false}).waitFor();
  assert.equal(docs['ST-01'].length,1);
  assert.equal(await page.getByText('PDF recebido · aguardando revisão',{exact:false}).count(),1);
  await page.getByRole('button',{name:'Anexar ou consultar provas'}).last().click();
  await page.getByRole('heading',{name:'Comprovações de estação · ST-02'}).waitFor();
  await page.getByText('Nenhum PDF anexado a este ativo.',{exact:false}).waitFor({state:'visible'});
  assert.equal(await page.getByText('Nenhum PDF anexado a este ativo.',{exact:false}).count(),1);
  await page.getByLabel('Tipo de comprovação').selectOption('LOCATION');
  await page.getByLabel('Origem e identificação do documento').fill('Planta de referência da estação ST-02');
  await page.getByLabel('Arquivo PDF (máximo 2 MB)').setInputFiles({
    name:'proof-south.pdf',mimeType:'application/pdf',
    buffer:Buffer.from('%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n')
  });
  await page.getByRole('button',{name:'Anexar PDF protegido'}).click();
  await page.getByText('PDF recebido e protegido.',{exact:false}).waitFor();
  assert.equal(docs['ST-02'].length,1);
  assert.notEqual(docs['ST-01'][0].id,docs['ST-02'][0].id);

  reviewer=true;await page.reload();
  await page.getByRole('button',{name:'Anexar ou consultar provas'}).first().click();
  await page.getByRole('button',{name:'Registrar parecer independente'}).click();
  await page.getByLabel('Justificativa e escopo da revisão')
    .fill('Arquivo conferido internamente; evidência ainda não foi admitida.');
  await page.getByRole('button',{name:'Salvar parecer interno'}).click();
  await page.getByText('Parecer interno registrado.',{exact:false}).waitFor();
  assert.equal(docs['ST-01'][0].state,'CHECKED_INTERNAL');
  assert.equal(docs['ST-01'][0].usableForE8,false);
  assert.equal(docs['ST-02'][0].state,'PENDING_REVIEW');
  assert.equal(await page.getByRole('button',{name:'Anexar PDF protegido'}).count(),0);
 } finally {await browser.close();}
});
