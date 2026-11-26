import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const ID = '33333333-aaaa-4bbb-8ccc-000000000001';

function edition(extra = {}) {
  return {id: ID, sourceId: 'MCTI-SIN-DESPACHO', edition: '2026-janout-7f8e2292', licenseBasis: 'OWNER_INTERNAL', workbookSha256: '7'.repeat(64),
    gas: 'CO2', originalUnit: 'tCO2/MWh', targetUnit: 'tCO2/MWh', dataYear: 2026, timezone: 'UTC', hourConvention: 'HOUR_ENDING_1_24',
    timeConventionSource: 'DECLARED_BY_PEOPLE', rowCount: 48, timeIssueCount: 1, importedAt: '2026-11-25T12:00:00Z',
    quality: {months: {'1': {state: 'PARTIAL', expectedCells: 744, importedCells: 48}}}, internalReview: null, independentReview: null,
    state: 'CANDIDATE_NOT_ADMITTED', usableForE8: false, eligibilityVt0011Tool07: 'NOT_EVALUATED',
    v60Binding: {state: 'NOT_BOUND', blocker: 'A V60 é por projeto e fixa CO2e.'}, limits: 'l', ...extra};
}

test('Série horária do SIN: candidata, fuso declarado por pessoas, valores originais e revisão nas duas bases', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}});
    let verified = false, current = [], failImport = null;
    const calls = {imports: [], reviews: []};
    await page.route('**/v1/compliance/session', route => {
      if (route.request().method() === 'POST') verified = true;
      return route.fulfill({json: {verified, expiresAt: verified ? new Date(Date.now() + 60000).toISOString() : null}});
    });
    await page.route('**/v1/platform/grid-series**', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.pathname.endsWith('/values')) {
        return route.fulfill({json: [{month: 1, day: 1, hourLabel: 1, cell: 'C53', valueOriginal: '0.5439000000000001', valueTarget: '0.5439',
          intervalStartLocal: 'x', intervalStartUtc: '2026-01-01T00:00:00Z', intervalEndUtc: '2026-01-01T01:00:00Z', timeIssue: null},
          {month: 1, day: 2, hourLabel: 24, cell: 'C76', valueOriginal: '0.3', valueTarget: '0.3', intervalStartLocal: 'y',
            intervalStartUtc: null, intervalEndUtc: null, timeIssue: 'AMBIGUOUS'}]});
      }
      if (url.pathname.endsWith('/reviews')) {
        calls.reviews.push(request.postDataJSON());
        const body = request.postDataJSON();
        const review = {outcome: body.outcome, basis: body.basis, reason: body.reason, decidedAt: '2026-11-25T13:00:00Z'};
        current = [edition(body.basis === 'OWNER_INTERNAL' ? {internalReview: review} : {independentReview: review})];
        return route.fulfill({json: current[0]});
      }
      if (request.method() === 'POST') {
        calls.imports.push(request.postDataJSON());
        if (failImport) return route.fulfill({status: failImport.status, json: {code: failImport.code, message: 'x'}});
        current = [edition()];
        return route.fulfill({status: 201, json: current[0]});
      }
      return route.fulfill({json: {canImport: true, canReviewInternal: true, canReviewIndependent: false, editions: current, limits: 'Série candidata: nada aqui é admitido.'}});
    });
    await page.goto(base + '/workspace/platform/grid-series');
    await page.getByRole('heading', {name: 'Verificação da equipe interna'}).waitFor();
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button', {name: 'Desbloquear séries'}).click();
    await page.getByRole('heading', {name: 'Séries importadas'}).waitFor();
    await page.getByText('Nenhuma série importada.').waitFor();

    // Import: the importer's JSON goes unchanged with the source and edition typed; a missing licence blocks only this operation.
    const file = {name: 'saida.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({original: {fileSha256: '7'.repeat(64), gas: 'CO2', year: 2026}, parameters: {timezone: 'UTC'}, records: []}))};
    await page.getByLabel('Código da fonte (já registrada)').fill('MCTI-SIN-DESPACHO');
    await page.getByLabel('Edição da fonte').fill('2026-janout-7f8e2292');
    await page.getByLabel('Arquivo JSON do importador').setInputFiles(file);
    failImport = {status: 409, code: 'LICENCA_AUSENTE_PARA_OPERACAO'};
    await page.getByRole('button', {name: 'Importar série candidata'}).click();
    await page.getByText('Só esta operação está bloqueada', {exact: false}).waitFor();
    failImport = null;
    await page.getByRole('button', {name: 'Importar série candidata'}).click();
    await page.getByText('Série importada como candidata: não admitida, sem vínculo com projeto.').waitFor();
    assert.equal(calls.imports.at(-1).sourceId, 'MCTI-SIN-DESPACHO');
    assert.equal(calls.imports.at(-1).original.year, 2026);

    // What people must not misread.
    await page.getByText('Candidata, não admitida').waitFor();
    await page.getByText('declarados por pessoas', {exact: false}).first().waitFor();
    await page.getByText('declaração interna do responsável (sem aprovação independente da licença)', {exact: false}).waitFor();
    await page.getByText('não vinculada', {exact: false}).waitFor();
    await page.getByText('usável no E8: não', {exact: false}).waitFor();
    await page.getByText('(não CO₂e)', {exact: false}).waitFor();

    // Original values (literal, float artefact kept) and the hour without a UTC instant.
    await page.getByRole('button', {name: 'Ver valores do mês 1 de 2026-janout-7f8e2292'}).click();
    await page.getByText('0.5439000000000001').waitFor();
    await page.getByText('sem instante: AMBIGUOUS').waitFor();

    // Only the internal basis is offered to this profile, and the series stays a candidate afterwards.
    assert.equal(await page.getByRole('option', {name: 'Independente'}).count(), 0);
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('Conferi contra a planilha original.');
    await page.getByRole('button', {name: 'Registrar revisão'}).click();
    await page.getByText('Revisão interna do responsável registrada. A série continua não admitida.').waitFor();
    assert.deepEqual(calls.reviews[0], {basis: 'OWNER_INTERNAL', outcome: 'CHECKED', reason: 'Conferi contra a planilha original.'});
    await page.getByText('Revisão independente: não revisada').waitFor();
  } finally {
    await browser.close();
  }
});
