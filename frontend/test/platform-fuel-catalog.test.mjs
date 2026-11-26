import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const FUEL = '11111111-aaaa-4bbb-8ccc-000000000001';

function seeded() {
  return {
    canRegister: true, canReviewInternal: true, canReviewIndependent: false, admission: 'NOT_ADMITTED',
    limits: 'Catálogo de referência: nada aqui é admitido.',
    fuels: [
      {id: 'GASOLINE_C', name: 'Gasolina C (comum)', family: 'GASOLINE', role: 'BLEND', phase: 'LIQUID', pending: ['vigência após os 180 dias']},
      {id: 'ETHANOL_ANHYDROUS', name: 'Etanol anidro combustível', family: 'ETHANOL', role: 'PRODUCT', phase: 'LIQUID', pending: []},
    ],
    entries: [{id: FUEL, kind: 'DATUM', fuelId: 'GASOLINE_C', componentId: null, compositionBasis: null, nominal: null, remainder: false,
      parameter: 'EMISSION_FACTOR', gas: 'CO2_FOSSIL', emissionScope: 'TTW', gwpSet: null, value: '3', unit: 'kgCO2/L', vintageYear: 2024,
      validFrom: '2024-01-01', validTo: null, evidenceSource: 'Fonte sintética', evidenceLocator: 'tabela 1', readState: 'PRIMARY_READ',
      internalReview: null, independentReview: null, admission: 'NOT_ADMITTED',
      qaqc: {state: 'COMPARED_SAME_UNIT', relativeDeviation: '0.250000', note: 'n'}}],
  };
}

test('Catálogo de combustíveis: tudo NOT_ADMITTED, registro com evidência, revisão interna e conversão rastreável', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 900}});
    let verified = false;
    const calls = {register: [], review: [], convert: [], resolve: []};
    const catalog = seeded();
    await page.route('**/v1/compliance/session', route => {
      if (route.request().method() === 'POST') verified = true;
      return route.fulfill({json: {verified, expiresAt: verified ? new Date(Date.now() + 60000).toISOString() : null}});
    });
    await page.route('**/v1/platform/fuel-catalog**', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.pathname.endsWith('/entries') && request.method() === 'POST') {
        calls.register.push(request.postDataJSON());
        const body = request.postDataJSON();
        catalog.entries.push({...catalog.entries[0], id: FUEL.replace('0001', '0002'), parameter: body.parameter, value: body.value, unit: body.unit,
          gas: null, emissionScope: null, qaqc: null, vintageYear: body.vintageYear});
        return route.fulfill({json: catalog.entries.at(-1)});
      }
      if (url.pathname.endsWith('/reviews')) {
        calls.review.push(request.postDataJSON());
        catalog.entries[0].internalReview = {outcome: 'REVIEWED_OK', basis: 'OWNER_INTERNAL', reason: 'x', decidedAt: '2026-11-25T10:00:00Z'};
        return route.fulfill({json: catalog.entries[0]});
      }
      if (url.pathname.endsWith('/boundaries')) {
        return route.fulfill({json: [
          {id: 'CO2_ONLY', name: 'Somente CO2 na linha de base e no projeto', baselineGases: ['CO2'], projectGases: ['CO2'], note: 'n', source: 'VM0038 Tabela 1', status: 'CANDIDATE'},
          {id: 'CO2_CH4_N2O', name: 'CO2, CH4 e N2O na linha de base e no projeto', baselineGases: ['CO2', 'CH4', 'N2O'], projectGases: ['CO2', 'CH4', 'N2O'], note: 'n', source: 'VM0038 Tabela 1', status: 'CANDIDATE'}]});
      }
      if (url.pathname.endsWith('/boundary-resolution')) {
        calls.resolve.push(Object.fromEntries(url.searchParams));
        return route.fulfill({json: {boundary: 'CO2_ONLY', fuelId: 'GASOLINE_C', date: '2024-06-01', scope: 'TTW', state: 'UNRESOLVED', admission: 'NOT_ADMITTED',
          consistency: [], blockers: ['CO2: Só há CO2e agregado, que inclui CH4 e N2O e destoa de uma fronteira só de CO2.'],
          requirements: [{gas: 'CO2', state: 'MISSING', value: null, unit: null, review: null, methodUnit: null, methodValue: null, conversionBlocker: null,
            note: 'Só há CO2e agregado, que inclui CH4 e N2O e destoa de uma fronteira só de CO2.'}]}});
      }
      if (url.pathname.endsWith('/conversion')) {
        calls.convert.push(url.searchParams.get('targetUnit'));
        return route.fulfill({json: {value: '100', originalValue: '3', originalUnit: 'kgCO2/L', targetUnit: 'gCO2/MJ', admission: 'NOT_ADMITTED',
          steps: [{from: 'kg', to: 'g', factor: '1000', note: 'massa do numerador (exata)'}, {from: 'L', to: 'MJ', factor: '30', note: 'PCI'}]}});
      }
      return route.fulfill({json: catalog});
    });
    await page.goto(base + '/workspace/platform/fuel-catalog');
    await page.getByLabel('Código de seis dígitos do autenticador').fill('123456');
    await page.getByRole('button', {name: 'Desbloquear catálogo'}).click();
    await page.getByRole('heading', {name: 'Combustíveis e lacunas de evidência'}).waitFor();
    await page.getByText('vigência após os 180 dias').waitFor();
    await page.getByText('Catálogo de referência: nada aqui é admitido.').waitFor();
    const entries = page.getByRole('region', {name: 'Registros do catálogo'});
    await entries.getByRole('cell', {name: /Fator de emissão 3 kgCO2\/L · CO₂ fóssil · Tanque à roda \(TTW\) · vintage 2024/}).waitFor();
    await entries.getByRole('cell', {name: 'desvio 0.250000'}).waitFor();
    assert.equal(await entries.getByText('não revisado').count(), 2);

    // Register a density with evidence; the form carries the original unit and no gas for a property.
    await page.getByLabel('Combustível', {exact: true}).nth(1).selectOption('GASOLINE_C');
    await page.getByLabel('Parâmetro').selectOption('DENSITY');
    await page.getByLabel('Valor, como publicado').fill('0.74');
    await page.getByLabel('Unidade original (por exemplo, kgCO2/L)').fill('kg/L');
    await page.getByLabel('Ano de vintage').fill('2024');
    await page.getByLabel('Vigência: início (AAAA-MM-DD)').fill('2020-01-01');
    await page.getByLabel('Fonte', {exact: true}).fill('Fonte sintética');
    await page.getByLabel('Localizador na fonte (tabela, página, artigo)').fill('tabela 2');
    await page.getByRole('button', {name: 'Registrar', exact: true}).click();
    await page.getByText('Registro criado como NOT_ADMITTED.', {exact: false}).waitFor();
    assert.equal(calls.register[0].parameter, 'DENSITY');
    assert.equal(calls.register[0].gas, null);
    assert.equal(calls.register[0].unit, 'kg/L');
    assert.equal(calls.register[0].readState, 'PRIMARY_READ');

    // Internal review is a separate basis and says it is not independent.
    await entries.getByRole('button', {name: 'Revisar'}).first().click();
    await page.getByRole('option', {name: 'Interna do responsável (não é independente)'}).waitFor({state: 'attached'});
    assert.equal(await page.getByRole('option', {name: /Independente/}).count(), 0);
    await page.getByLabel('Motivo (mín. 10 caracteres)').fill('Conferido contra a fonte.');
    await page.getByRole('button', {name: 'Registrar revisão'}).click();
    await page.getByText('A admissão continua NOT_ADMITTED.', {exact: false}).waitFor();
    assert.deepEqual(calls.review[0], {basis: 'OWNER_INTERNAL', outcome: 'REVIEWED_OK', reason: 'Conferido contra a fonte.'});

    // Boundary candidate: CO2_ONLY by default, nothing admitted, and the aggregate does not stand in for CO2.
    await page.getByLabel('Combustível da linha de base').selectOption('GASOLINE_C');
    await page.getByLabel('Data de referência (AAAA-MM-DD)').fill('2024-06-01');
    assert.equal(await page.getByLabel('Base de GWP aceita para o agregado (por exemplo, AR5_100)').count(), 0);
    await page.getByRole('button', {name: 'Resolver fator candidato'}).click();
    await page.getByText('Fator candidato não resolvido').waitFor();
    await page.getByText('destoa de uma fronteira só de CO2', {exact: false}).first().waitFor();
    assert.deepEqual(calls.resolve[0], {boundary: 'CO2_ONLY', fuelId: 'GASOLINE_C', date: '2024-06-01', scope: 'TTW'});
    await page.getByLabel('Fronteira', {exact: true}).selectOption('CO2_CH4_N2O');
    await page.getByLabel('Base de GWP aceita para o agregado (por exemplo, AR5_100)').waitFor();

    // Traceable conversion: original preserved, steps listed.
    await page.getByLabel('Fator de emissão', {exact: true}).selectOption(FUEL);
    await page.getByLabel('Unidade de destino (por exemplo, gCO2/MJ)').fill('gCO2/MJ');
    await page.getByRole('button', {name: 'Converter'}).click();
    await page.getByText('100 gCO2/MJ', {exact: false}).waitFor();
    assert.equal(calls.convert[0], 'gCO2/MJ');
    await page.getByText('L → MJ: fator 30 (PCI)').waitFor();
  } finally {
    await browser.close();
  }
});
