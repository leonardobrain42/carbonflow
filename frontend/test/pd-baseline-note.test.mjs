import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const project = '20000000-0000-4000-8000-000000000095';
const organization = '10000000-0000-4000-8000-000000000095';

async function mockProject(page, baseline, extra = {}) {
    await page.route('**/v1/account-session', route => route.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000095', email:'teste@carbonflow.test', name:'Teste'},
      projects:[{projectId:project, organizationId:organization, environment:'SANDBOX', state:'DRAFT', version:1}],
    }}));
    await page.route('**/v1/organizations', route => route.fulfill({json:[{
      id:organization, name:'Teste', environment:'SANDBOX', status:'PRIVATE_DRAFT', role:'DRAFT_OWNER', version:1,
    }]}));
    await page.route('**/v1/projects/*/metadata', route => route.fulfill({json:{
      projectId:project, organizationId:organization, environment:'SANDBOX', state:'DRAFT',
      version:1, title:'Rede aberta', description:'Recarga',
    }}));
    await page.route('**/v1/projects/*/technical/capabilities', route => route.fulfill({json:{
      projectId:project, environment:'SANDBOX', owner:true,
      actions:['VIEW_INTAKE','VIEW_DOSSIER'], modules:{},
    }}));
    await page.route('**/v1/projects/*/technical/intake', route => route.fulfill({json:{
      projectId:project, organizationId:organization, environment:'SANDBOX', version:1,
      state:'RASCUNHO', reviewState:'NAO_AVALIADO', triageCandidate:'VM0038',
      journey:{id:'VM0038_V1_1', candidate:'VM0038', title:'VM0038 v1.1',
        description:'Rede aberta', technicalTopics:[]},
      methodologies:[], data:{activity:'Recarga', country:'BR', state:'DF', locality:'Brasília',
        candidateMethodology:'VM0038', referenceActDate:'2027-01-01', notes:null},
      outstandingGates:[],
    }}));
    await page.route('**/v1/projects/*/technical/preliminary-dossier', route => route.fulfill({status:204}));
    await page.route('**/v1/projects/*/journey/resume', route => route.fulfill({status:204}));
    await page.route('**/v1/projects/*/technical/pd-sections', route => route.fulfill({json:[{
      guide:{id:'baseline', title:'Linha de base e comparáveis', version:1, fields:[], answers:{}, missing:[]},
      latest:null, monitoringDependency:null, calculation:null, additionality:null, applicability:null,
      baseline, ...extra,
    }]}));
}

test('a seção de linha de base nomeia a referência sem copiar o rating', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440, height:900}});
    await mockProject(page, {
        period:'M1', situation:'REFERENCE', observationYear:2027, cycle:'COMBINED',
        declaredRating:'18 kWh por 100 milhas', declarationIsRating:false,
        admitsBaseline:false, copiesLicensedRatings:false, usableForE8:false,
        marksDocumentReady:false,
        text:'A seleção de frota deste período é uma referência preparatória. O PD não fica pronto.',
      });
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=redigir&guia=baseline');
    const section = page.getByRole('region', {name:'Seções da descrição do projeto'});
    await section.getByRole('heading', {name:'Linha de base nomeada nesta seção · M1'}).waitFor();
    await section.getByText('referência preparatória').waitFor();
    const year = section.locator('dt', {hasText:'Ano de observação'}).locator('xpath=..');
    assert.equal((await year.locator('dd').innerText()).trim(), '2027');
    const cycle = section.locator('dt', {hasText:'Ciclo'}).locator('xpath=..');
    assert.equal((await cycle.locator('dd').innerText()).trim(), 'COMBINED');
    const admits = section.locator('dt', {hasText:'Admite a linha de base'}).locator('xpath=..');
    assert.equal((await admits.locator('dd').innerText()).trim(), 'Não');
    await section.getByText('Esta seção não copia AFEC nem MPG.').waitFor();
    await section.getByText('Texto do guia: 18 kWh por 100 milhas. Não é o rating PBEV.').waitFor();
    await section.getByText('O documento não fica pronto.').waitFor();
    assert.equal(await section.getByText('20 kWh/100mi').count(), 0);
    await page.setViewportSize({width:390, height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  } finally {
    await browser.close();
  }
});

test('a seção nomeia a referência ex ante exata sem seleção monitorada e sem admiti-la', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440, height:900}});
    await mockProject(page, {
      period:'M1', situation:'ABSENT', observationYear:null, cycle:null, declaredRating:null,
      declarationIsRating:false, admitsBaseline:false, copiesLicensedRatings:false, usableForE8:false,
      marksDocumentReady:false,
      text:'Nenhuma seleção de frota está nomeada neste documento. Ausência não é consumo zero. O PD não fica pronto.',
      exAnte:{version:2, observationYear:2026, cycle:'COMBINED', selectionSha256:'d'.repeat(64),
        state:'PREPARATORY_NOT_ADMITTED', stale:false, usableForE8:false,
        text:'Referência de frota ex ante, versão 2, ano de observação 2026. É preparatória e exata por versão.'},
    });
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=redigir&guia=baseline');
    const section = page.getByRole('region', {name:'Seções da descrição do projeto'});
    await section.getByRole('heading', {name:'Frota de referência ex ante nomeada nesta seção'}).waitFor();
    await section.getByText('exata por versão').waitFor();
    const row = label => section.locator('dt', {hasText:label}).locator('xpath=..').locator('dd');
    assert.equal((await row('Versão da referência').innerText()).trim(), '2');
    assert.equal((await row('Situação').innerText()).trim(), 'Preparatória, não admitida');
    assert.equal((await row('Alimenta o E8').innerText()).trim(), 'Não');
    assert.equal((await row('Hash da seleção').innerText()).trim(), 'd'.repeat(64));
    await section.getByText('O documento não fica pronto.').first().waitFor();
    assert.equal(await section.getByText('Esta seção não copia AFEC nem MPG.').count(), 0);
    await page.setViewportSize({width:390, height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  } finally {
    await browser.close();
  }
});

test('a versão que cita referência ex ante superada é sinalizada e permanece íntegra', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440, height:900}});
    await mockProject(page, {
      period:'M1', situation:'ABSENT', observationYear:null, cycle:null, declaredRating:null,
      declarationIsRating:false, admitsBaseline:false, copiesLicensedRatings:false, usableForE8:false,
      marksDocumentReady:false, text:'Nenhuma seleção de frota está nomeada neste documento.',
    }, {baselineDependency:{
      state:'REFERENCE_SUPERSEDED', outdated:true, pinnedVersion:1, pinnedSelectionSha256:'a'.repeat(64),
      currentVersion:2, currentSelectionSha256:'b'.repeat(64),
      text:'A referência de frota ex ante mudou desde a redação desta versão (citava a versão 1; a vigente é a 2). Esta versão continua íntegra.',
    }});
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=redigir&guia=baseline');
    const section = page.getByRole('region', {name:'Seções da descrição do projeto'});
    await section.getByText('citava a versão 1; a vigente é a 2').waitFor();
    await section.getByRole('alert').getByText('não é mais a vigente').waitFor();
    await section.getByText('permanece íntegra no histórico').waitFor();
    await page.setViewportSize({width:390, height:844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
  } finally {
    await browser.close();
  }
});
