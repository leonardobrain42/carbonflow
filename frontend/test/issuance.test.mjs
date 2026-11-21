import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const url = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const project = process.env.CARBONFLOW_ISSUANCE_TEST_PROJECT;
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

test(project ? 'PP registra posição e depósito, PREP compara e REV concilia pelo Angular'
  : 'o painel orienta o PP a comprovar a primeira verificação aprovada', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport: {width: 1200, height: 900}});
    const panel = page.getByRole('region', {name: 'Emissão e conciliação'});
    const button = name => panel.getByRole('button', {name, exact: true});
    const label = name => panel.getByLabel(name, {exact: true});
    const text = value => panel.getByText(value, {exact: true});
    const success = () => panel.getByText('Etapa registrada. Confira o estado e a posição atualizados.', {exact: true}).waitFor();
    page.setDefaultTimeout(12000);

    if (project) {
      await page.route('**/v1/session', async route => {
        if (route.request().method() !== 'GET') return route.continue();
        const response = await route.fetch();
        if (!response.ok()) return route.fulfill({response});
        const session = await response.json();
        const fixture = session.projects.find(item => item.project === project);
        assert.ok(fixture, 'O papel precisa ter acesso ao projeto criado pela suíte Java.');
        session.projects = [fixture];
        await route.fulfill({response, json: session});
      });
    }

    async function enter(actor) {
      await page.getByLabel('Identidade do piloto').fill('SIM-' + actor);
      await page.getByLabel('Senha de demonstração').fill('piloto-' + actor.toLowerCase());
      await page.getByRole('button', {name: 'Entrar', exact: true}).click();
      await panel.waitFor();
      if (project) {
        const verification = label('Verificação aprovada');
        await verification.locator('option').filter({hasText: /^V2 /}).waitFor({state: 'attached'});
        await verification.selectOption({label: await verification.locator('option').filter({hasText: /^V2 /}).innerText()});
      }
    }

    async function leave() {
      await page.getByRole('button', {name: 'Sair', exact: true}).click();
      await page.getByLabel('Identidade do piloto').waitFor();
    }

    async function act(step, name, at, official) {
      await label('Etapa de emissão').selectOption(step);
      if (official) await label('Identificador oficial do comprovante').fill(official);
      await panel.locator('#issuance-action-date').fill(at);
      await button(name).click();
      await success();
    }

    await page.goto(url + '/pilot');
    await enter('PP');
    if (!project) {
      await panel.getByText(/Comprove a aprovação de V1 ou V2 em/).waitFor();
      assert.equal(await button('Criar pedido em preparo').count(), 0);
      return;
    }

    const suffix = crypto.randomUUID().slice(0, 8).toUpperCase();
    const target = 'SIM-EMISSAO-UI-' + suffix;
    const date = '2029-12-08T';
    const secondCut = '2029-01-01 a 2029-10-01 (fim exclusivo)';
    await panel.getByText('Registrar extrato oficial', {exact: true}).click();
    await label('Recorte do extrato').selectOption({label: secondCut});
    await label('Saldo oficial em unidades fictícias').fill('10');
    await label('Identificador oficial do extrato').fill('SIM-EXTRATO-UI-' + suffix);
    await label('Data e hora do extrato em Brasília').fill(date + '09:00');
    await button('Adicionar pendência').click();
    await label('Referência externa (se aplicável)').fill('SIM-PENDENCIA-UI');
    await label('Quantidade').fill('2');
    await button('Registrar posição').click();
    await text('Oficial 10 · A deduzir 2 · Disponível 8').waitFor();

    await panel.getByText('Preparar pedido de emissão', {exact: true}).click();
    const quantity = label('Quantidade · ' + secondCut);
    assert.equal(await quantity.getAttribute('max'), '8');
    await quantity.fill('8');
    await label('Data e hora do preparo em Brasília').fill(date + '10:00');
    await button('Criar pedido em preparo').click();
    const chooser = label('Tentativa de emissão');
    const draft = chooser.locator('option').filter({hasText: /Em preparo/});
    const draftId = await draft.getAttribute('value');
    await label('Fim exclusivo do poder em Brasília').fill('2031-01-01T00:00');
    await button('Registrar conferência de emissão').click();
    await text('Conferência do canal e do poder registrada.').waitFor();

    await label('Identificador do protocolo').fill(target);
    await act('TX-E3-49', 'Vincular recibo do protocolo', date + '10:30', 'SIM-RECIBO-UI-' + suffix);
    await label('Valor da cobrança em BRL').fill('80.00');
    await act('TX-E3-50', 'Registrar cobrança', date + '11:00', 'SIM-COBRANCA-UI-' + suffix);
    await act('TX-E3-51', 'Comprovar quitação da cobrança', date + '11:30', 'SIM-QUITACAO-UI-' + suffix);
    await panel.getByRole('heading', {name: 'Aguardando depósito'}).waitFor();
    await text(secondCut + ' · Solicitado 8 · Depositado líquido 0').waitFor();
    await panel.locator('#issuance-line-0').fill('8');
    await act('TX-E3-52', 'Registrar depósito', date + '12:00', 'SIM-DEPOSITO-UI-' + suffix);
    await text(secondCut + ' · Solicitado 8 · Depositado líquido 8').waitFor();

    await label('Saldo oficial em unidades fictícias').fill('0');
    await label('Identificador oficial do extrato').fill('SIM-EXTRATO-FINAL-UI-' + suffix);
    await label('Data e hora do extrato em Brasília').fill(date + '13:00');
    await label('Reflexo no saldo oficial').first().selectOption('REFLETIDA');
    await button('Adicionar pendência').click();
    await label('Origem da pendência 2').selectOption(draftId);
    await label('Quantidade').last().fill('8');
    await label('Reflexo no saldo oficial').last().selectOption('REFLETIDA');
    await button('Registrar posição').click();
    await text('Oficial 0 · A deduzir 0 · Disponível 0').waitFor();

    await leave();
    await enter('PREP');
    await chooser.selectOption(draftId);
    await act('COMPARAR', 'Comparar prova, depósito e posição', date + '14:00');
    await text('Comparação de SIM-PREP').waitFor();

    await leave();
    await enter('REV');
    await chooser.selectOption(draftId);
    await act('TX-E3-53', 'Conciliar após comparação', date + '15:00');
    await panel.getByRole('heading', {name: 'Conciliado'}).waitFor();
    await button('Consultar trilha da tentativa').click();
    await text('TX-E3-53 · PERMITIDO · SIM-REV').waitFor();
  } finally {
    await browser.close();
  }
});
