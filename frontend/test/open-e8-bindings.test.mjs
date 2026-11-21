import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const project = '20000000-0000-4000-8000-000000000098';
const organization = '10000000-0000-4000-8000-000000000098';
const sha = digit => digit.repeat(64);

test('o vínculo mostra a energia e os hashes como declaração', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1440, height:900}});
    const dossier = {
      reference:{code:'M1', version:1, snapshotId:'snap-1', snapshotSha256:sha('a'),
        note:'Período de teste', authoredBy:'prep', createdAt:'2026-11-21T00:00:00Z'},
      state:'NOT_READY', stale:false, canUseForE8:false, gates:[],
    };
    const energy = [{
      id:'energy-1', projectId:project, period:'M1', dossierVersion:1, methodReviewVersion:1,
      snapshotId:'snap-1', sourceSha256:sha('b'), edKwh:'10', ecKwh:'12',
      hourCount:1, slotCount:1, state:'NOT_ADMITTED', stale:false, usableForE8:false,
      createdAt:'2026-11-21T00:00:00Z', gates:[],
      hours:[{chargerCode:'CH-01', from:'2027-01-01T00:00:00Z', until:'2027-01-01T01:00:00Z',
        edKwh:'10', ecKwh:'12', edBatchId:'b1', edLine:1, edReview:1,
        ecBatchId:'b1', ecLine:2, ecReview:1}],
    }];
    const factors = [{
      id:'efel-1', period:'M1', energyCandidateId:'energy-1', version:1,
      sourceTitle:'Série horária de teste', sourcePublisher:'Fonte de teste',
      sourceUri:'https://example.test/fator', sourceEdition:'2027',
      claimedProcedure:'Série horária declarada para conferência.',
      publicationDate:'2027-01-01', sourceVintage:'2027',
      sourceSha256Claimed:sha('c'), sourceRightsReference:'Direito declarado de teste.',
      evidenceNote:'Nota de teste.', originalUnit:'tCO2e/MWh', normalizedUnit:'tCO2e/kWh',
      contentSha256:sha('d'), hourCount:1, state:'CANDIDATE_NOT_ADMITTED', stale:false,
      usableForE8:false, submittedBy:'prep', submittedAt:'2026-11-21T00:00:00Z',
      review:null, hours:[], gates:[],
    }];
    const bindings = [{
      id:'bind-1', projectId:project, period:'M1', version:1,
      energyCandidateId:'energy-1', efelCandidateId:'efel-1',
      energySha256:sha('e'), efelSha256:sha('f'), efelReviewVersion:1,
      inputSha256:sha('1'), hourCount:1, slotCount:1, state:'CANDIDATE_NOT_ADMITTED',
      stale:false, usableForE8:false, createdAt:'2026-11-21T00:00:00Z', gates:[],
      hours:[{hour:'2027-01-01T00:00:00Z', edKwh:'10', ecKwh:'12',
        gridFactorTco2ePerKwh:'0.0001', chargerCount:1}],
    }];
    const fleet = [{
      id:'fleet-1', projectId:project, period:'M1', version:1,
      energyBindingId:'bind-1', fleetSnapshotId:'fleet-snap', ratingSnapshotId:'rating-snap',
      observationYear:2027, cycle:'COMBINED', fleetSourceSha256:sha('2'),
      ratingLineageSha256:sha('3'), selectionSha256:sha('4'), state:'NOT_ADMITTED',
      stale:false, usableForE8:false, publishedAt:'2026-11-21T00:00:00Z', gates:[],
    }];
    await page.route('**/v1/account-session', route => route.fulfill({json:{
      identity:{id:'00000000-0000-4000-8000-000000000098', email:'teste@carbonflow.test', name:'Teste'},
      projects:[{projectId:project, organizationId:organization, environment:'SANDBOX', state:'DRAFT', version:1}],
    }}));
    await page.route('**/v1/organizations', route => route.fulfill({json:[{
      id:organization, name:'Teste', environment:'SANDBOX', status:'PRIVATE_DRAFT', role:'DRAFT_OWNER', version:1,
    }]}));
    await page.route('**/v1/projects/*/metadata', route => route.fulfill({json:{
      projectId:project, organizationId:organization, environment:'SANDBOX', state:'DRAFT',
      version:1, title:'Rede aberta de teste', description:'Recarga DCFC',
    }}));
    await page.route('**/v1/projects/*/technical/capabilities', route => route.fulfill({json:{
      projectId:project, environment:'SANDBOX', owner:true,
      modules:{intake:'PREPARATORY_ONLY', calculation:'NOT_ENABLED'},
      actions:['VIEW_INTAKE','VIEW_OPEN_MONITORING','VIEW_OPEN_E8_ENERGY_CANDIDATE','VIEW_OPEN_EFEL_CANDIDATE'],
    }}));
    await page.route('**/v1/projects/*/technical/intake', route => route.fulfill({json:{
      projectId:project, organizationId:organization, environment:'SANDBOX', version:1,
      state:'RASCUNHO', reviewState:'NAO_AVALIADO', triageCandidate:'VM0038',
      journey:{id:'VM0038_V1_1', candidate:'VM0038', title:'VM0038 v1.1',
        description:'Rede aberta', technicalTopics:[]},
      methodologies:[{value:'VM0038', label:'VM0038 v1.1'}],
      data:{activity:'Recarga', country:'BR', state:'DF', locality:'Brasília',
        candidateMethodology:'VM0038', referenceActDate:'2027-01-01', notes:null},
      outstandingGates:[],
    }}));
    await page.route('**/v1/projects/*/technical/open-monitoring**', route => {
      const url = route.request().url();
      if (url.endsWith('/e8-input-bindings')) return route.fulfill({json:bindings});
      if (url.endsWith('/e8-fleet-selections')) return route.fulfill({json:fleet});
      if (url.endsWith('/efel-candidates')) return route.fulfill({json:factors});
      if (url.endsWith('/e8-energy-candidates')) return route.fulfill({json:energy});
      if (url.endsWith('/dossiers')) return route.fulfill({json:[dossier]});
      if (url.endsWith('/open-monitoring')) return route.fulfill({json:{
        projectId:project, environment:'SANDBOX', chargers:[], meters:[],
        readiness:{state:'NOT_READY', canPrepare:false, canUseForE8:false,
          edKwh:null, ecKwh:null, gates:[]},
      }});
      return route.fulfill({json:[]});
    });
    await page.goto(base + '/workspace/projects/' + project + '/technical?secao=monitorar&etapa=dossies');
    await page.getByRole('heading', {name:'Dossiês de monitoramento e rechecagem'}).waitFor();
    await page.getByRole('button', {name:'4. Relatório, fator elétrico e caderno'}).waitFor();
    await page.getByRole('button', {name:'Consultar pacotes horários de energia'}).click();
    await page.getByText('SHA-256 declarado da proveniência:', {exact:false}).waitFor();
    await page.getByText('Detecta alteração deste pacote. Não autentica a fonte nem admite o cálculo.').waitFor();
    await page.getByText('Energia entregue 10 kWh · energia consumida 12 kWh,', {exact:false}).waitFor();
    await page.getByRole('button', {name:'Examinar proveniência e parecer'}).click();
    await page.getByText('SHA-256 declarado do conjunto:', {exact:false}).waitFor();
    await page.getByRole('button', {name:'Ver versões, integridade e horas'}).click();
    await page.getByText('SHA-256 declarado da energia:', {exact:false}).waitFor();
    await page.getByText('SHA-256 declarado do fator elétrico:', {exact:false}).waitFor();
    await page.getByText('Estes SHA-256 detectam alteração desta versão. Não autenticam a fonte nem liberam o cálculo.').waitFor();
    await page.getByText('Referência: energy-1', {exact:true}).waitFor();
    await page.getByRole('button', {name:'Ver referências e pendências'}).click();
    await page.getByText('SHA-256 declarado da linhagem:', {exact:false}).waitFor();
  } finally {
    await browser.close();
  }
});
