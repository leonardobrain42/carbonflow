import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const url = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const chromiumPath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

test('triagem, evidências, comissionamento e troca de medidor no Compose', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1024, height: 850}});
    page.setDefaultTimeout(10000);
    const ficha = page.getByRole('region', {name: 'Enquadramento e inventário'});
    let version = 0;

    async function enter(actor) {
      await page.getByLabel('Identidade do piloto').fill('SIM-' + actor);
      await page.getByLabel('Senha de demonstração').fill('piloto-' + actor.toLowerCase());
      await page.getByRole('button', {name: 'Entrar', exact: true}).click();
      await ficha.getByText('Ficha v' + version + ' · Simulação', {exact: true}).waitFor();
    }

    async function leave() {
      await page.getByRole('button', {name: 'Sair', exact: true}).click();
      await page.getByLabel('Identidade do piloto').waitFor();
    }

    async function next(button) {
      await ficha.getByRole('button', {name: button, exact: true}).click();
      await ficha.getByText('Ficha v' + (++version) + ' · Simulação', {exact: true}).waitFor();
    }

    async function record(id) {
      await ficha.getByLabel('Evidência sintética').selectOption(id);
      await next('Registrar evidência');
    }

    await page.goto(url + '/pilot');
    await enter('PREP');
    await ficha.getByLabel('Configuração de ensaio').selectOption('923');
    await next('Registrar triagem');
    await ficha.getByText('Fora do recorte', {exact: true}).waitFor();
    await ficha.getByText(/A rota de 92,3% fica fora do piloto/).waitFor();

    await ficha.getByLabel('Configuração de ensaio').selectOption('padrao');
    await next('Registrar triagem');
    for (const proof of ['ENGAJAMENTO', 'MECANISMO', 'ESG', 'FPIC', 'REPARTICAO']) await record('SIM-' + proof);
    await leave();

    await enter('PP');
    for (const proof of ['DIREITOS', 'EXCEDENTE', 'SOBREPOSICAO']) await record('SIM-' + proof);
    await leave();

    await enter('REV');
    await next('Revisar enquadramento');
    await ficha.getByText('Enquadrado no sintético', {exact: true}).waitFor();
    await ficha.getByText(/VM0038 v1.1/).waitFor();
    await leave();

    await enter('PP');
    await next('Registrar compromisso');
    await ficha.getByText('2027-07-15', {exact: true}).waitFor();
    await leave();

    await enter('OP');
    for (const meter of ['SIM-MED-01', 'SIM-MED-02']) {
      await ficha.getByLabel('Medidor da amostra').selectOption(meter);
      await next('Registrar revisão');
    }
    await next('Registrar comissionamento');
    await ficha.getByText('2027-10-01 a 2032-09-30', {exact: true}).waitFor();
    await ficha.getByLabel('Medidor da amostra').selectOption('SIM-MED-03');
    await next('Registrar revisão');
    await ficha.getByRole('rowheader', {name: 'SIM-EC-01 / SIM-MED-01'}).waitFor();
    await ficha.getByRole('rowheader', {name: 'SIM-EC-01 / SIM-MED-03'}).waitFor();

    await ficha.getByLabel('Intervalo de teste (EC)').selectOption('atravessa');
    const [response] = await Promise.all([
      page.waitForResponse(value => value.url().endsWith('/inventory/readings') && value.request().method() === 'POST'),
      ficha.getByRole('button', {name: 'Pré-checar leitura', exact: true}).click(),
    ]);
    assert.equal(response.status(), 201);
    const reading = await response.json();
    assert.equal(reading.state, 'PENDENTE');
    assert.equal(reading.raw.kwh, '12.50');
    assert.equal(reading.raw.start, '2028-03-31T23:30:00-03:00');
    assert.equal(reading.raw.endExclusive, '2028-04-01T00:30:00-03:00');
    await ficha.getByText(/O medidor e sua revisão não cobrem todo o intervalo/).waitFor();

    await ficha.getByText('Uso real bloqueado', {exact: true}).click();
    await ficha.getByText('PEN-004', {exact: true}).waitFor();
    assert.ok(version > 12);
  } finally {
    await browser.close();
  }
});

// A jornada anterior conclui o comissionamento no projeto Compose descartável.
test('bruto, prévia, correção e seleção preservada pela interface da E8-004', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}});
    page.setDefaultTimeout(10000);
    const panel = page.getByRole('region', {name: 'Importação ED/EC'});
    const project = '/v1/projects/PIL-SYN-BR-001';

    async function enter(actor) {
      await page.getByLabel('Identidade do piloto').fill('SIM-' + actor);
      await page.getByLabel('Senha de demonstração').fill('piloto-' + actor.toLowerCase());
      await page.getByRole('button', {name: 'Entrar', exact: true}).click();
      await panel.waitFor();
    }

    async function leave() {
      await page.getByRole('button', {name: 'Sair', exact: true}).click();
      await page.getByLabel('Identidade do piloto').waitFor();
    }

    async function openFirst() {
      await panel.getByText('Lotes recentes (1)', {exact: true}).click();
      await panel.getByRole('button', {name: /^M1 · Concluído/}).click();
      await panel.getByText(/Concluído · 11 de 11 linhas/).waitFor();
    }

    async function decide(line, action, reference) {
      await panel.getByRole('button', {name: 'Revisar linha ' + line, exact: true}).click();
      await panel.getByLabel('Decisão', {exact: true}).selectOption(action);
      await panel.getByLabel('Motivo', {exact: true}).fill('Conferência do CSV sintético');
      if (reference) await panel.getByLabel('Predecessora (somente correção)').fill(reference);
      const [response] = await Promise.all([
        page.waitForResponse(item => item.url().endsWith('/actions') && item.request().method() === 'POST'),
        panel.getByRole('button', {name: 'Registrar decisão', exact: true}).click(),
      ]);
      assert.equal(response.status(), 200, await response.text());
      await panel.getByText('Decisão registrada.', {exact: true}).waitFor();
      return (await response.json()).line;
    }

    await page.goto(url + '/pilot');
    await enter('OP');
    const [queued] = await Promise.all([
      page.waitForResponse(item => item.url().endsWith('/imports') && item.request().method() === 'POST'),
      panel.getByRole('button', {name: 'Importar amostra em M1/M2', exact: true}).click(),
    ]);
    assert.equal(queued.status(), 202);
    const {jobId} = await queued.json();
    await panel.getByText(/Concluído · 11 de 11 linhas/).waitFor();
    await panel.getByText(/A energia está ausente e permanece sem valor/).waitFor();
    await panel.getByText(/Zero medido exige prova/).waitFor();
    assert.equal(await panel.getByRole('button', {name: /^Revisar linha/}).count(), 0);
    const [repeat] = await Promise.all([
      page.waitForResponse(item => item.url().endsWith('/imports') && item.request().method() === 'POST'),
      panel.getByRole('button', {name: 'Importar amostra em M1/M2', exact: true}).click(),
    ]);
    assert.equal((await repeat.json()).jobId, jobId);
    await panel.getByText('Arquivo vinculado ao lote anterior.', {exact: true}).waitFor();
    await leave();

    await enter('PREP');
    await openFirst();
    await decide(3, 'PROPOR');
    await leave();

    await enter('REV');
    await openFirst();
    const accepted = await decide(3, 'ADMITIR');
    const initial = accepted.resolution.observation;
    await panel.getByRole('button', {name: 'Guardar seleção atual', exact: true}).click();
    const snapshot = await panel.getByRole('link', {name: /^Seleção /}).getAttribute('href');
    const before = await (await page.request.get(url + snapshot)).json();
    assert.equal(before.observations[0].fact.kwh, '30');
    await leave();

    await enter('PREP');
    await openFirst();
    await decide(11, 'PROPOR', initial.id + '@1');
    await leave();

    await enter('REV');
    await openFirst();
    const corrected = await decide(11, 'ADMITIR');
    assert.equal(corrected.resolution.observation.version, 2);
    assert.equal(corrected.resolution.observation.id, initial.id);
    assert.deepEqual(await (await page.request.get(url + snapshot)).json(), before);
    const current = await (await page.request.get(url + project + '/observations?period=M1')).json();
    assert.equal(current.length, 1);
    assert.equal(current[0].fact.kwh, '25');
  } finally {
    await browser.close();
  }
});

// O retrato salvo na jornada anterior contém apenas EC, condição de amostra para E8-005.
test('cálculo por retrato ED/EC com revisão, lacuna visível e reexecução', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}});
    page.setDefaultTimeout(10000);
    const panel = page.getByRole('region', {name: 'Cálculo das leituras admitidas'});
    async function enter(role) {
      await page.getByLabel('Identidade do piloto').fill('SIM-' + role);
      await page.getByLabel('Senha de demonstração').fill('piloto-' + role.toLowerCase());
      await page.getByRole('button', {name: 'Entrar', exact: true}).click();
      await panel.waitFor();
    }

    await page.goto(url + '/pilot');
    await enter('PREP');
    await panel.getByRole('button', {name: 'Propor cálculo', exact: true}).click();
    await panel.getByRole('heading', {name: 'M1 · Aguardando revisão'}).waitFor();
    assert.equal(await panel.getByRole('button', {name: 'Confirmar e calcular'}).count(), 0);
    await page.getByRole('button', {name: 'Sair', exact: true}).click();
    await page.getByLabel('Identidade do piloto').waitFor();
    await enter('REV');
    const linked = await panel.getByRole('link', {name: 'Entradas fixadas em JSON'}).getAttribute('href');
    const inputs = await (await page.request.get(url + linked)).json();
    assert.equal(inputs.factor.reference.id, 'EF-SYN-v1');
    assert.equal(inputs.discount.evidence.id, 'SIM-SOBREPOSICAO');
    await panel.getByLabel('Motivo da revisão').fill('Conferência da seleção de EC');
    await panel.getByRole('button', {name: 'Confirmar e calcular', exact: true}).click();
    await panel.getByRole('heading', {name: 'M1 · Memória com lacunas'}).waitFor();
    const row = panel.getByRole('row').filter({hasText: /^2028 ·/});
    await row.getByText('Ausente', {exact: true}).waitFor();
    await row.getByText('30', {exact: true}).waitFor();
    await row.getByText('0,009', {exact: true}).waitFor();
    await panel.getByRole('button', {name: 'Reexecutar snapshot', exact: true}).click();
    await panel.getByText('Reexecução idêntica à memória salva.', {exact: true}).waitFor();
    const href = await panel.getByRole('link', {name: 'Baixar snapshot'}).getAttribute('href');
    const saved = await (await page.request.get(url + href)).json();
    assert.equal(saved.observations, 1);
    assert.equal(saved.cuts[1].ecKwh, '30');
    assert.equal(saved.cuts[1].edKwh, null);
  } finally {
    await browser.close();
  }
});

test('encerra M1 e abre M2 na mesma fronteira, com lacuna de M1 visível ao REV', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}});
    page.setDefaultTimeout(10000);
    const periods = page.getByRole('region', {name: 'Períodos de monitoramento'});
    const choose = period => periods.getByLabel('Janela', {exact: true}).selectOption(period);
    const heading = (id, state) => periods.getByRole('heading', {name: id + ' · ' + state + ' · revisão 1'});
    async function enter(role) {
      await page.getByLabel('Identidade do piloto').fill('SIM-' + role);
      await page.getByLabel('Senha de demonstração').fill('piloto-' + role.toLowerCase());
      await page.getByRole('button', {name: 'Entrar', exact: true}).click();
      await periods.waitFor();
    }
    async function leave() {
      await page.getByRole('button', {name: 'Sair', exact: true}).click();
      await page.getByLabel('Identidade do piloto').waitFor();
    }
    async function step(reason) {
      await periods.getByLabel('Motivo da transição').fill(reason);
      await periods.getByRole('button', {name: 'Registrar transição', exact: true}).click();
    }

    await page.goto(url + '/pilot');
    await enter('PREP');
    await periods.getByRole('button', {name: 'Propor M1', exact: true}).click();
    await heading('M1', 'Previsto').waitFor();
    await choose('M2');
    await periods.getByRole('button', {name: 'Propor M2', exact: true}).click();
    await heading('M2', 'Previsto').waitFor();
    await leave();

    await enter('OP');
    await heading('M1', 'Previsto').waitFor();
    await step('Comissionamento e abertura de M1');
    await heading('M1', 'Coletando').waitFor();
    assert.equal(await periods.getByLabel('Instante efetivo simulado').inputValue(), '2028-10-01T00:00:00-03:00');
    await step('Fim exclusivo de M1');
    await heading('M1', 'Em conferência').waitFor();
    await choose('M2');
    await heading('M2', 'Previsto').waitFor();
    assert.equal(await periods.getByLabel('Instante efetivo simulado').inputValue(), '2028-10-01T00:00:00-03:00');
    await step('Abertura da janela de M2');
    await heading('M2', 'Coletando').waitFor();
    const sample = periods.getByRole('link', {name: 'Baixar CSV de cobertura M2'});
    assert.match(await (await page.request.get(url + await sample.getAttribute('href'))).text(), /SIM-MED-04/);
    await leave();

    await enter('REV');
    await heading('M1', 'Em conferência').waitFor();
    await periods.getByLabel('Memória revisada', {exact: true}).selectOption({index: 1});
    await periods.getByRole('button', {name: 'Conferir cobertura', exact: true}).click();
    await periods.getByText(/pendência\(s\) · 4 canais esperados/).waitFor();
    await step('Tentativa de fechamento com lacuna');
    await periods.getByRole('status').getByText(/A conferência encontrou/).waitFor();
    await periods.getByLabel('Ação de monitoramento').selectOption({label: 'Registrar lacunas'});
    await step('Falta cobertura de ED e EC');
    await heading('M1', 'Com lacunas').waitFor();
    await choose('M2');
    await heading('M2', 'Coletando').waitFor();
    await periods.getByRole('button', {name: 'Consultar histórico do período'}).click();
    await periods.getByText(/v2 · Coletando · SIM-OP/).waitFor();
  } finally {
    await browser.close();
  }
});

test('prepara PD indicativo, confere a prévia pública, congela e vincula o recibo', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}, acceptDownloads: true});
    page.setDefaultTimeout(10000);
    const dossier = page.getByRole('region', {name: 'Dossiês PD e MR'});
    const note = 'Anexo reservado com contato fictício doc-private@example.invalid';
    async function enter(role) {
      await page.getByLabel('Identidade do piloto').fill('SIM-' + role);
      await page.getByLabel('Senha de demonstração').fill('piloto-' + role.toLowerCase());
      await page.getByRole('button', {name: 'Entrar', exact: true}).click();
      await dossier.waitFor();
    }
    async function leave() {
      await page.getByRole('button', {name: 'Sair', exact: true}).click();
      await page.getByLabel('Identidade do piloto').waitFor();
    }
    async function decide(reason) {
      await dossier.getByLabel('Justificativa da revisão').fill(reason);
      await dossier.getByRole('button', {name: 'Registrar etapa do dossiê'}).click();
    }
    await page.goto(url + '/pilot');
    await enter('PREP');
    await dossier.getByRole('button', {name: 'Preparar dossiê'}).click();
    for (const [name, reason] of [
      ['Acordo de comunicação', 'A hipótese sintética é a atuação direta do proponente.'],
      ['Prova de tradução', 'O documento do ensaio é gerado em inglês.'],
      ['Representação de listagem', 'O ensaio guarda a incidência como não aplicável a este pacote.'],
    ]) {
      const group = dossier.getByRole('group', {name, exact: true});
      await group.getByLabel('Incidência', {exact: true}).selectOption('NAO_INCIDE');
      await group.getByLabel('Justificativa da incidência', {exact: true}).fill(reason);
    }
    await dossier.getByLabel('Nota reservada (opcional)').fill(note);
    await dossier.getByRole('button', {name: 'Salvar rascunho'}).click();
    await dossier.getByRole('heading', {name: /PD indicativo.*RASCUNHO/}).waitFor();
    await dossier.getByRole('button', {name: 'Ver prévia pública'}).click();
    await dossier.getByText('Method and baseline', {exact: true}).waitFor();
    assert.equal(await dossier.getByText(note, {exact: true}).count(), 0);
    await dossier.getByRole('button', {name: 'Ver prévia reservada'}).click();
    await dossier.getByText(note, {exact: true}).waitFor();
    await decide('Checklist e fontes para revisão interna.');
    await dossier.getByRole('heading', {name: /PD indicativo.*EM_REVISAO/}).waitFor();
    await leave();

    await enter('REV');
    await dossier.getByRole('heading', {name: /PD indicativo.*EM_REVISAO/}).waitFor();
    await decide('Versões, incidência e exposição conferidas.');
    await dossier.getByRole('heading', {name: /PD indicativo.*CONGELADO/}).waitFor();
    await dossier.getByText('Manifestos de variantes', {exact: true}).click();
    const manifestUrl = await dossier.getByRole('link', {name: 'PUBLICA · LIMPA'}).getAttribute('href');
    const manifest = await (await page.request.get(url + manifestUrl)).json();
    assert.equal(manifest.template.edition, '5.0B');
    assert.equal(manifest.environment, 'SIMULACAO');
    assert.equal(manifest.files[0].filename, 'document.docx');
    await leave();

    await enter('PP');
    await dossier.getByRole('heading', {name: /PD indicativo.*CONGELADO/}).waitFor();
    await dossier.getByRole('button', {name: 'Registrar exportação'}).click();
    const zip = dossier.getByRole('link', {name: /Baixar ZIP.*PUBLICA.*LIMPA/});
    await zip.waitFor();
    const [download] = await Promise.all([page.waitForEvent('download'), zip.click()]);
    assert.equal(download.suggestedFilename(), 'dossie-simulacao.zip');
    await dossier.getByLabel('Código do recibo simulado').fill('SIM-RECIBO-CHROME');
    await dossier.getByLabel('Referência da prova simulada').fill('SIM-PROVA-CHROME');
    await dossier.getByRole('button', {name: 'Vincular recibo'}).click();
    await dossier.getByText('Recibo simulado vinculado à exportação.', {exact: true}).waitFor();
    await dossier.getByText('SIM-RECIBO-CHROME · SIM-REGISTRY', {exact: true}).waitFor();
  } finally {
    await browser.close();
  }
});

test('confere canal, vincula recibo e comprova a publicação de desenvolvimento sem abrir consulta', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}});
    page.setDefaultTimeout(10000);
    const listings = page.getByRole('region', {name: 'Listagens e consulta pública'});
    await page.goto(url + '/pilot');
    await page.getByLabel('Identidade do piloto').fill('SIM-PP');
    await page.getByLabel('Senha de demonstração').fill('piloto-pp');
    await page.getByRole('button', {name: 'Entrar', exact: true}).click();
    await listings.getByText('Preparar tentativa de listagem', {exact: true}).click();
    await listings.getByLabel('Dossiê congelado da listagem').selectOption({index: 1});
    await listings.getByRole('button', {name: 'Preparar listagem', exact: true}).click();
    await listings.getByRole('heading', {name: 'Desenvolvimento · Em preparo'}).waitFor();
    await listings.getByText('Conferir canal e poder', {exact: true}).click();
    await listings.getByLabel('Canal conferido').selectOption('INDETERMINADO');
    await listings.getByLabel('Poder simulado comprovado').check();
    await listings.getByRole('button', {name: 'Salvar conferência'}).click();
    await listings.getByRole('status').getByText('Conferência registrada para a revisão escolhida.').waitFor();
    await listings.getByText(/Pacote .*Canal a confirmar/).waitFor();
    await listings.getByLabel('Canal conferido').selectOption('ARQUIVO');
    await listings.getByRole('button', {name: 'Salvar conferência'}).click();
    await listings.getByText(/Pacote .*Canal conferido para simulação/).waitFor();

    await listings.getByLabel('Identificador externo da tentativa').fill('SIM-EXTERNO-DEV-CHROME');
    await listings.getByLabel('Recibo da versão e do canal').selectOption({index: 1});
    await listings.getByRole('button', {name: 'Registrar evento da listagem'}).click();
    await listings.getByRole('heading', {name: 'Desenvolvimento · Protocolada'}).waitFor();
    assert.equal(await listings.getByRole('heading', {name: /Consulta pública ·/}).count(), 0);
    async function official(step, id, date) {
      await listings.getByLabel('Ação da tentativa').selectOption(step);
      await listings.getByLabel('Data efetiva em Brasília').fill(date);
      await listings.getByLabel('Identificador do ato comprovado').fill(id);
      await listings.getByRole('button', {name: 'Registrar evento da listagem'}).click();
      await listings.getByRole('status').getByText('Evento registrado na tentativa.').waitFor();
    }
    await official('BOLETO', 'SIM-BOLETO-CHROME', '2027-08-02T16:00');
    await listings.getByText(/SIM-BOLETO-CHROME.*Em aberto/).waitFor();
    await official('QUITACAO', 'SIM-QUITACAO-CHROME', '2027-08-03T09:00');
    await listings.getByText(/Quitação comprovada/).waitFor();
    await official('TX-E3-14', 'SIM-PUB-DEV-CHROME', '2027-08-03T10:00');
    await listings.getByRole('heading', {name: 'Desenvolvimento · Publicada'}).waitFor();
    assert.equal(await listings.getByRole('heading', {name: /Consulta pública ·/}).count(), 0);
    await listings.getByRole('button', {name: 'Consultar trilha da tentativa'}).click();
    await listings.getByText(/TX-E3-14 · PERMITIDO · SIM-PP/).waitFor();
  } finally {
    await browser.close();
  }
});

test('acompanha manifestação reservada da consulta até avaliação independente no painel', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}});
    page.setDefaultTimeout(10000);
    const project = '/v1/projects/PIL-SYN-BR-001';
    const listing = page.getByRole('region', {name: 'Listagens e consulta pública'});
    const code = id => ({id, version: 1});
    async function enter(role) {
      await page.getByLabel('Identidade do piloto').fill('SIM-' + role);
      await page.getByLabel('Senha de demonstração').fill('piloto-' + role.toLowerCase());
      await page.getByRole('button', {name: 'Entrar', exact: true}).click();
      await listing.waitFor();
    }
    async function leave() {
      await page.getByRole('button', {name: 'Sair', exact: true}).click();
      await page.getByLabel('Identidade do piloto').waitFor();
    }
    async function get(path) {
      const response = await page.request.get(url + project + path);
      assert.equal(response.status(), 200, await response.text());
      return response.json();
    }
    async function post(path, data, version, status = 200) {
      const headers = {'Idempotency-Key': crypto.randomUUID()};
      if (version !== undefined) headers['If-Match'] = '"' + version + '"';
      const response = await page.request.post(url + project + path, {headers, data});
      assert.equal(response.status(), status, await response.text());
      return response.json();
    }

    await page.goto(url + '/pilot');
    await enter('PREP');
    const {snapshot} = await get('');
    await post('/case-records', {type: 'ODS', status: 'COMPROVADO_SIMULACAO', effectiveDate: '2027-06-30',
      justification: 'Metas sintéticas', evidence: code('SIM-ODS')}, snapshot.version, 201);
    const checklist = await get('/dossiers/checklist?act=LISTAGEM_VALIDACAO');
    const draft = await post('/dossiers', {document: {act: 'LISTAGEM_VALIDACAO', actDate: '2027-12-01', model: '5.0B', language: 'en',
      pieces: checklist.pieces.map(({item, reference}) => ({item, reference})),
      conditions: checklist.conditions.map(item => ({item, incidence: 'INCIDE', reason: 'Aplicável neste ensaio.'}))}}, 0, 201);
    const pack = {id: draft.id, revision: draft.revision};
    const toReview = await post('/dossiers/' + draft.id + '/actions',
      {transitionId: 'TX-E3-06', dossier: pack, reason: 'Pronto para revisão.'}, draft.version);
    await leave();

    await enter('REV');
    const frozen = await post('/dossiers/' + draft.id + '/actions',
      {transitionId: 'TX-E3-08', dossier: pack, reason: 'Edição e exposição conferidas.'}, toReview.version);
    await leave();

    await enter('PP');
    const exportPath = '/dossiers/' + draft.id;
    const exported = await post(exportPath + '/exports', {revision: pack.revision, exposure: 'PUBLICA', edition: 'LIMPA',
      destination: 'SIM-HUB', channel: 'DIGITAL_HUMANO_SIMULADO'}, frozen.version, 201);
    const receivedAt = '2027-12-01T13:00:00Z';
    await post(exportPath + '/receipts', {exportId: exported.id, dossier: pack, sha256: exported.sha256, destination: 'SIM-HUB',
      code: 'SIM-RECIBO-VAL-CHROME', proof: 'SIM-PROVA-RECIBO-VAL-CHROME', receivedAt}, undefined, 201);
    const created = await post('/listings', {kind: 'LISTAGEM_VALIDACAO', dossier: pack}, undefined, 201);
    const attempt = '/listings/' + created.attempt.id;
    const channel = await post(attempt + '/channel-checks', {dossier: pack, channel: 'DIGITAL_HUMANO', documentEdition: '5.0B',
      methodologyEdition: 'VM0038 v1.1', checkedAt: '2027-12-01T12:00:00Z', powerUntilExclusive: '2030-01-01T03:00:00Z',
      source: code('SIM-CANAL-DIGITAL-HUMANO'), power: code('SIM-PODER-LISTAGEM')}, created.attempt.version);
    const target = 'SIM-EXTERNO-VAL-CHROME';
    const protocol = await post(attempt + '/actions', {transitionId: 'TX-E3-18', mode: 'SIMULACAO', effectiveAt: receivedAt,
      target, exportId: exported.id, dossier: pack}, channel.attempt.version);
    const publication = await post(attempt + '/actions', {transitionId: 'TX-E3-14', mode: 'SIMULACAO', effectiveAt: '2027-12-05T13:00:00Z',
      proof: {officialId: 'SIM-PUB-VAL-CHROME', target, evidence: code('SIM-PROVA-PUBLICACAO-01')},
      page: 'https://registry.example.invalid/listagens/' + target}, protocol.attempt.version);

    await listing.getByRole('button', {name: 'Atualizar listagens'}).click();
    await listing.getByLabel('Tentativa de listagem').selectOption(publication.attempt.id);
    await listing.getByRole('heading', {name: 'Consulta pública · Aberta'}).waitFor();
    await listing.getByText('Capturar manifestação do Hub', {exact: true}).click();
    const privateContact = 'consultation-private@example.invalid';
    await listing.getByLabel('Recibo simulado do Hub').fill('SIM-MANIFESTACAO-CHROME');
    await listing.getByLabel('Conteúdo reservado da manifestação').fill('Solicitação fictícia de melhoria de segurança');
    await listing.getByLabel('Contato reservado', {exact: true}).fill(privateContact);
    await listing.getByRole('button', {name: 'Registrar manifestação', exact: true}).click();
    await listing.getByRole('heading', {name: /APRESENTADO/}).waitFor();
    await listing.getByLabel('Identificador do ato de manifestação').fill('SIM-ENCAMINHAMENTO-CHROME');
    await listing.getByLabel('Vincular SIM-VVB').check();
    await listing.getByRole('button', {name: 'Registrar etapa da manifestação'}).click();
    await listing.getByRole('heading', {name: /ENCAMINHADO/}).waitFor();
    await listing.getByLabel('Resposta ou avaliação').fill('Resposta do proponente sobre a medida simulada');
    await listing.getByRole('button', {name: 'Registrar etapa da manifestação'}).click();
    await listing.getByRole('heading', {name: /RESPONDIDO/}).waitFor();
    const publicLink = await listing.getByRole('link', {name: 'Ver resumo público'}).getAttribute('href');
    const summary = await (await page.request.get(url + publicLink)).text();
    assert.equal(JSON.parse(summary).inWindow, 1);
    assert.ok(!summary.includes(privateContact) && !summary.includes('Solicitação fictícia'));
    await leave();

    await enter('VVB');
    await listing.getByRole('heading', {name: /RESPONDIDO/}).waitFor();
    assert.equal(await listing.getByText('Contato reservado', {exact: true}).count(), 0);
    await listing.getByLabel('Identificador do ato de manifestação').fill('SIM-AVALIACAO-CHROME');
    await listing.getByLabel('Resposta ou avaliação').fill('Avaliação independente da resposta simulada');
    await listing.getByRole('button', {name: 'Registrar etapa da manifestação'}).click();
    await listing.getByText(/SIM-VVB-A · SUFICIENTE/).waitFor();
  } finally {
    await browser.close();
  }
});

test('contrata VVB, vincula a concessão e registra visita e parecer na sessão do organismo', async () => {
  const browser = await chromium.launch({executablePath: chromiumPath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}});
    page.setDefaultTimeout(10000);
    const project = '/v1/projects/PIL-SYN-BR-001';
    const audit = page.getByRole('region', {name: 'Auditorias e pedidos REG, V1 e V2'});
    const ref = id => ({id, version: 1});
    async function enter(role) {
      await page.getByLabel('Identidade do piloto').fill('SIM-' + role);
      await page.getByLabel('Senha de demonstração').fill('piloto-' + role.toLowerCase());
      await page.getByRole('button', {name: 'Entrar', exact: true}).click();
      await audit.waitFor();
    }
    async function leave() {
      await page.getByRole('button', {name: 'Sair', exact: true}).click();
      await page.getByLabel('Identidade do piloto').waitFor();
    }
    async function get(path) {
      const response = await page.request.get(url + project + path);
      assert.equal(response.status(), 200, await response.text());
      return response.json();
    }
    async function post(path, data, version, status = 200) {
      const headers = {'Idempotency-Key': crypto.randomUUID()};
      if (version !== undefined) headers['If-Match'] = '"' + version + '"';
      const response = await page.request.post(url + project + path, {headers, data});
      assert.equal(response.status(), status, await response.text());
      return response.json();
    }

    await page.goto(url + '/pilot');
    await enter('PREP');
    const checklist = await get('/dossiers/checklist?act=REGISTRO');
    const draft = await post('/dossiers', {document: {act: 'REGISTRO', actDate: '2028-03-01', model: '5.0B', language: 'en',
      pieces: checklist.pieces.map(({item, reference}) => ({item, reference})),
      conditions: checklist.conditions.map(item => ({item, incidence: 'INCIDE', reason: 'Aplicável ao ensaio.'}))}}, 0, 201);
    const pack = {id: draft.id, revision: draft.revision};
    const submitted = await post('/dossiers/' + draft.id + '/actions',
      {transitionId: 'TX-E3-06', dossier: pack, reason: 'Dossiê para auditoria.'}, draft.version);
    await leave();
    await enter('REV');
    const frozen = await post('/dossiers/' + draft.id + '/actions',
      {transitionId: 'TX-E3-08', dossier: pack, reason: 'Versões confirmadas.'}, submitted.version);
    await leave();

    await enter('PP');
    const validation = (await get('/listings')).find(item => item.attempt.consultationId).attempt;
    const consultation = await get('/consultations/' + validation.consultationId);
    await post('/consultations/' + consultation.id + '/actions', {effectiveAt: consultation.endExclusive,
      proof: {officialId:'SIM-FIM-CONSULTA-CHROME', target: consultation.target, evidence: ref('SIM-PROVA-FIM-CONSULTA')},
      noInWindowComments: false, summary: ref('SIM-RESUMO-CONSULTA')}, consultation.version);
    await audit.getByText('Contratar VVB', {exact:true}).click();
    await audit.getByLabel('Pacote congelado da auditoria').selectOption(draft.id+'@1');
    await audit.getByLabel('Consulta da validação').selectOption(consultation.id);
    await audit.getByLabel('Escopo contratado').fill('Validação do projeto sintético');
    await audit.getByRole('button', {name:'Registrar contratação'}).click();
    await audit.getByRole('heading', {name:'VAL · PREVISTA'}).waitFor();
    const variant = frozen.variants.find(item => item.exposure === 'RESERVADA' && item.edition === 'LIMPA');
    const granted = await post('/grants', {artifact:{id:variant.artifactId,version:frozen.revision},purpose:'AUDITORIA_VAL',
      recipient:'SIM-VVB',expiresAt:new Date(Date.now()+86400000).toISOString()}, undefined, 201);
    await post('/grants/'+granted.grant.id+'/receipts',null,granted.grant.version);
    await audit.getByLabel('ID da concessão da variante reservada').fill(granted.grant.id);
    await audit.getByRole('button', {name:'Registrar etapa da auditoria'}).click();
    await audit.getByRole('status').getByText('Etapa da auditoria registrada.').waitFor();
    await leave();

    await enter('VVB');
    async function step(action,id,date,reason=''){
      await audit.getByLabel('Ação da auditoria').selectOption(action);
      await audit.getByLabel('Data efetiva da auditoria').fill(date);
      await audit.getByLabel('Identificador do ato do VVB').fill(id);
      if(reason)await audit.getByLabel('Justificativa ou descrição da prova').fill(reason);
      await audit.getByRole('button', {name:'Registrar etapa da auditoria'}).click();
      await audit.getByRole('status').getByText('Etapa da auditoria registrada.').waitFor();
    }
    await step('TX-E3-26','SIM-ABRE-AUD-CHROME','2027-12-05T10:00');
    await audit.getByRole('heading', {name:'VAL · EM_ANALISE'}).waitFor();
    await step('VISITA','SIM-VISITA-AUD-CHROME','2027-12-20T10:00','Visita sintética comprovada');
    await audit.getByText(/Visita comprovada/).waitFor();
    await step('TX-E3-32','SIM-PARECER-AUD-CHROME','2028-03-01T10:00','Escopo, visita e respostas revisados');
    await audit.getByRole('heading', {name:'VAL · PARECER_POSITIVO'}).waitFor();
  } finally {
    await browser.close();
  }
});
