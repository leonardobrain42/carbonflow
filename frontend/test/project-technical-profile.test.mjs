import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const project = '20000000-0000-4000-8000-000000000002';
const root = '/v1/projects/' + project;

async function open(page, {journeyStatus, profile, withProfile = true}) {
  let intakeCalls = 0;
  await page.route('**' + root + '/metadata', r => r.fulfill({json:{
    projectId:project, environment:'SANDBOX', title:'Projeto com acesso limitado', version:1, state:'DRAFT',
    organizationId:'10000000-0000-4000-8000-000000000001'}}));
  await page.route('**' + root + '/technical/capabilities', r => r.fulfill({json:{
    projectId:project, environment:'SANDBOX', owner:false, actions:['VIEW_GUIDES'], modules:{}}}));
  await page.route('**' + root + '/technical/intake', r => { intakeCalls++; return r.fulfill({status:403, json:{}}); });
  await page.route('**' + root + '/journey', r => r.fulfill({status:journeyStatus, json:{}}));
  await page.route('**' + root + '/technical/guides', r => r.fulfill({json:{
    methodology:profile.id, ...(withProfile ? {journeyProfile:profile} : {}),
    guides:[{id:'applicability',title:'Guia de aplicabilidade',guidance:'Anotação',
      fields:[], version:1, definitionVersion:1, answers:{}, missing:[], editable:false}],
  }}));
  await page.route('**' + root + '/technical/typed-guides', r => r.fulfill({json:{methodology:profile.id, guides:[]}}));
  await page.goto(base + '/workspace/projects/' + project + '/technical');
  return () => intakeCalls;
}

test('A04: sem a rota de jornada, o cliente usa o perfil que o servidor envia e não guarda catálogo próprio', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    const intake = await open(page, {journeyStatus:404, profile:{
      id:'PACOTE_DE_TESTE', candidate:'PKG-TESTE', edition:'9.9', title:'Perfil enviado pelo servidor (teste)',
      activitiesTitle:'Escopo', detailsTitle:'Dados', description:'Descrição vinda do servidor.',
      sourceUrl:null, technicalTopics:[], questions:[]}});
    await page.getByText('Perfil enviado pelo servidor (teste)').first().waitFor();
    // Nothing of the old embedded VM0038 profile, and no read of private intake answers.
    assert.equal(await page.getByText('Guia técnico de recarga').count(), 0);
    assert.equal(intake(), 0);
  } finally {
    await browser.close();
  }
});

test('A04: sem perfil do servidor, o cliente cai no perfil neutro e não em um de metodologia', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    await open(page, {journeyStatus:404, profile:{id:'VM0038_V1_1'}, withProfile:false});
    await page.getByText('Preparação autorizada').first().waitFor();
    assert.equal(await page.getByText('Guia técnico de recarga').count(), 0);
  } finally {
    await browser.close();
  }
});
