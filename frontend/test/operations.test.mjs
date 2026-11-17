import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const url = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';

test('consulta trabalhos e confere o piso inclusivo de retenção na interface', async () => {
  const browser = await chromium.launch({executablePath: process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome'});
  try {
    const page = await browser.newPage();
    await page.goto(url);
    await page.getByLabel('Identidade do piloto').fill('SIM-OP');
    await page.getByLabel('Senha de demonstração').fill('piloto-op');
    await page.getByRole('button', {name: 'Entrar', exact: true}).click();

    const panel = page.getByRole('region', {name: 'Trabalhos, retenção e recuperação'});
    await panel.getByText('Trabalhos duráveis', {exact: true}).click();
    const filter = panel.getByLabel('Período do trabalho', {exact: true});
    await filter.selectOption('M2');
    const [report] = await Promise.all([
      page.waitForResponse(response => response.url().endsWith('/jobs?period=M2')),
      panel.getByRole('button', {name: 'Consultar trabalhos', exact: true}).click(),
    ]);
    assert.equal(report.status(), 200);
    assert.ok((await report.json()).every(job => job.period === 'M2' && job.kind === 'IMPORTACAO'));

    await panel.getByText('Retenção e cota de evidências', {exact: true}).click();
    const date = panel.getByLabel('Conferir guarda em uma data de ensaio', {exact: true});
    await date.fill('2034-09-30');
    await panel.getByRole('button', {name: 'Consultar retenção', exact: true}).click();
    await panel.getByText('Monitoramento · Guarda mínima · Guarda até 30/09/2034, inclusive', {exact: true}).waitFor();
    await date.fill('2034-10-01');
    await panel.getByRole('button', {name: 'Consultar retenção', exact: true}).click();
    await panel.getByText('Monitoramento · Reavaliar prazo · Guarda até 30/09/2034, inclusive', {exact: true}).waitFor();
    assert.equal(await panel.getByRole('button', {name: 'Revisar fim de crédito', exact: true}).count(), 0);
  } finally {
    await browser.close();
  }
});
