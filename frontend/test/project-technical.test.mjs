import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';
const projectId = '20000000-0000-4000-8000-000000000001';
const orgId = '10000000-0000-4000-8000-000000000001';
const methodologies = [{value:'VM0038', label:'VM0038 v1.1 · Recarga de veículos elétricos'}];
const general = {
  id:'GENERAL', candidate:null, title:'Triagem inicial', description:'Descreva a atividade e o escopo.',
  technicalTopics:['Dados e direitos a comprovar'],
};
const vm0038 = {
  id:'VM0038_V1_1', candidate:'VM0038', title:methodologies[0].label, description:'Piloto de veículos leves e estações DCFC.',
  technicalTopics:['Energia entregue ao veículo (ED) e consumida pelo sistema (EC)'],
};
const project = {
  projectId, organizationId: orgId, environment: 'SANDBOX', state: 'DRAFT',
  title: 'Recarga urbana', description: 'Hipótese de projeto', version: 1,
};

test('CF-IMP-008: rascunho da conta cruza intake e prévia sem adquirir autoridade E8', async () => {
  const browser = await chromium.launch({executablePath});
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 900}});
    const state = {intake: null, dossier: null};
    const initialExperience = {mode:'GUIDED', declaredProfile:null, declaredCompetence:null,
      responsibilities:{pp:null, prep:null, rev:null, op:null, vvb:null}};
    let experience = {projectId, environment:'SANDBOX', version:0, preferences:initialExperience};
    await page.route('**/v1/projects/*/experience', async route => {
      if (route.request().method() === 'PUT') {
        assert.equal(route.request().headers()['if-match'], '"' + experience.version + '"');
        experience = {...experience, version:experience.version + 1, preferences:route.request().postDataJSON()};
      }
      await route.fulfill({status:200,json:experience});
    });
    let pilotAccessAttempts = 0;

    await page.route('**/v1/account-session', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({status: 200, json: {
          identity: {id: '00000000-0000-4000-8000-000000000001', email: 'demo@example.test', name: 'Conta demo'},
          projects: [{projectId, organizationId: orgId, environment: 'SANDBOX', state: 'DRAFT', version: 1}],
        }});
      } else {
        await route.fulfill({status: 405});
      }
    });
    await page.route('**/v1/organizations', route => route.fulfill({status: 200, json: [{
      id: orgId, name: 'Organização', environment: 'SANDBOX', status: 'PRIVATE_DRAFT',
      role: 'DRAFT_OWNER', version: 1,
    }]}));
    await page.route('**/v1/projects/*/metadata', async route => {
      assert.equal(route.request().method(), 'GET');
      assert.ok(route.request().url().includes(projectId));
      await route.fulfill({status: 200, json: project});
    });
    await page.route('**/v1/projects/*/technical/capabilities', route => route.fulfill({
      status: 200, json: {projectId, environment: 'SANDBOX', modules: {
        intake: 'PREPARATORY_ONLY', projectDescription: 'PRELIMINARY_ONLY',
        evidence: 'NOT_ENABLED', calculation: 'NOT_ENABLED',
        monitoring: 'NOT_ENABLED', externalActs: 'NOT_ENABLED',
      }},
    }));
    await page.route('**/v1/projects/*/technical/intake', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({status: 200, headers: {ETag: '"' + (state.intake?.version ?? 0) + '"'}, json:
          state.intake ? {...state.intake,experienceMode:experience.preferences.mode} : {projectId, organizationId: orgId, environment: 'SANDBOX', version: 0,
            state: 'NAO_INICIADO', reviewState: 'NAO_AVALIADO', data: null,
            triageCandidate: null, journey:general, methodologies,
            outstandingGates: [{code: 'ANALISE_NAO_EXECUTADA', rule: 'CF-IMP-008',
              message: 'Cadastro ainda não avaliado.'}]},
        });
      } else if (route.request().method() === 'PUT') {
        assert.ok(route.request().headers()['idempotency-key']);
        assert.equal(route.request().headers()['if-match'], '"' + (state.intake?.version ?? 0) + '"');
        const data = JSON.parse(route.request().postData());
        assert.equal(data.candidateMethodology, 'VM0038');
        state.intake = {
          projectId, organizationId: orgId, environment: 'SANDBOX', version: 1,
          state: 'RASCUNHO', reviewState: 'NAO_AVALIADO', data,
          triageCandidate: null, journey:vm0038, methodologies,
          outstandingGates: [
            {code: 'ANALISE_NAO_EXECUTADA', rule: 'CF-IMP-008', message: 'Análise não executada.'},
            {code: 'PEN-004', rule: 'REG-E2-04', message: 'Direitos não comprovados.'},
          ],
        };
        await route.fulfill({status: 200, headers: {ETag: '"1"'}, json: state.intake});
      } else {
        await route.fulfill({status: 405});
      }
    });
    await page.route('**/v1/projects/*/technical/preliminary-dossier', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill(state.dossier ? {status: 200, json: state.dossier} : {status: 204});
      } else if (route.request().method() === 'POST') {
        assert.ok(route.request().headers()['idempotency-key']);
        assert.equal(route.request().headers()['if-match'], '"1"');
        assert.ok(state.intake);
        state.dossier = {
          id: '40000000-0000-4000-8000-000000000001', projectId, organizationId: orgId,
          environment: 'SANDBOX', revision: 1, intakeVersion: 1,
          state: 'RASCUNHO_PRELIMINAR_NAO_ADMISSIVEL', documentKind: 'PD_PREPARATORY_SCAFFOLD',
          sourceTemplate: 'SRC-007_PD_v5.0B.docx', sourceEdition: '5.0B',
          sections: ['Descrição do projeto', 'Linha de base', 'Salvaguardas'],
          blockers: [{code: 'PEN-004', message: 'Direitos não comprovados.', rule: 'REG-E2-04'}],
          intake: state.intake.data,
        };
        await route.fulfill({status: 201, json: state.dossier});
      } else {
        await route.fulfill({status: 405});
      }
    });
    await page.route('**/v1/projects/PIL-SYN-BR-001/**', route => {
      pilotAccessAttempts++;
      return route.fulfill({status: 500});
    });

    await page.goto(base + '/workspace');
    const selector = page.getByLabel('Projetos disponíveis nesta conta');
    await selector.waitFor();
    assert.equal(await selector.inputValue(), '');
    await selector.selectOption(projectId);
    await page.getByRole('heading', {name: 'Recarga urbana'}).waitFor();
    const menu = page.getByRole('navigation', {name:'Menu principal'});
    const menuItems = await menu.getByRole('link').allTextContents();
    assert.deepEqual(menuItems, ['Meus projetos', 'Novo projeto', 'Convites recebidos', 'Visão geral', 'Triagem guiada',
      'Preparação técnica', 'Preferências e equipe', 'Planos de convite', 'Comprovação de poderes', 'Piloto sintético']);
    await menu.getByText('Exemplo separado. Abrir não copia dados para este projeto.').waitFor();
    await page.getByRole('link', {name: 'Preparar informações técnicas'}).click();
    await page.waitForURL('**/workspace/projects/' + projectId + '/technical');
    await page.getByRole('heading', {name: 'Preparação técnica'}).waitFor();
    const sections = page.getByRole('navigation', {name:'Seções da preparação técnica'});
    await page.getByText('Rascunho técnico ainda não iniciado').waitFor();
    await page.getByRole('heading',{name:'Painel de trabalho'}).waitFor();
    const groups=await sections.locator('.cf-tech-module-group').allTextContents();
    assert.deepEqual(groups,['Começar','Fundamentação','Medição','Documentação']);
    const labels=await sections.getByRole('button').allTextContents();
    assert.ok(labels.includes('Linha de base'));
    assert.ok(labels.includes('Adicionalidade'));
    assert.ok(labels.includes('Evidências'));
    assert.ok(labels.includes('Quantificação'));
    assert.equal(await page.getByLabel('Atividade planejada').count(), 0);
    await sections.getByRole('button',{name:'Fontes e parâmetros'}).click();
    await page.getByRole('link',{name:'Abrir gestão de fontes (requer permissão staff)'}).waitFor();
    assert.equal(await page.getByRole('link',
      {name:'Abrir gestão de fontes (requer permissão staff)'}).getAttribute('href'),
      '/workspace/platform/sources');
    await sections.getByRole('button',{name:'Painel de trabalho'}).click();
    await page.getByRole('heading',{name:'Painel de trabalho'}).waitFor();
    await sections.getByRole('button', {name:'Preparar informações'}).click();

    await page.getByLabel('Atividade planejada').fill('Recarga elétrica em ambiente urbano');
    await page.getByLabel('País (código de duas letras)').fill('br');
    await page.getByLabel('Estado ou região').fill('DF');
    await page.getByLabel('Cidade/localidade').fill('Brasília');
    await page.getByLabel('Metodologia candidata').selectOption('VM0038');
    await page.getByLabel('Data de referência da atividade').fill('2027-09-27');
    await sections.getByRole('button',{name:'Redigir'}).click();
    assert.deepEqual(await menu.getByRole('link').allTextContents(), menuItems);
    assert.equal(await menu.getByRole('link', {name:'Preparação técnica'}).getAttribute('aria-current'), 'page');
    await page.getByRole('heading',{name:'Redigir'}).waitFor();
    assert.equal(await page.getByLabel('Atividade planejada').count(), 0);
    assert.equal(await page.getByRole('button',{name:'Preparar prévia da revisão atual'}).isDisabled(), true);
    await sections.getByRole('button',{name:'Preparar informações'}).click();
    assert.equal(await page.getByLabel('Atividade planejada').inputValue(), 'Recarga elétrica em ambiente urbano');
    await page.getByRole('button', {name: 'Salvar informações'}).click();
    await page.getByText('Informações preliminares salvas (revisão 1).').waitFor();
    await sections.getByRole('button',{name:'Analisar'}).click();
    await page.getByText('PEN-004', {exact: false}).waitFor();
    assert.equal(state.intake.version, 1);

    await sections.getByRole('button',{name:'Redigir'}).click();
    await page.getByRole('button', {name: 'Preparar prévia da revisão atual'}).click();
    await page.getByText('Prévia de dossiê criada.', {exact: false}).waitFor();
    await page.getByText('SRC-007_PD_v5.0B.docx').waitFor();
    assert.equal(state.dossier.state, 'RASCUNHO_PRELIMINAR_NAO_ADMISSIVEL');
    assert.equal(await page.getByRole('button', {name: 'Preparar prévia da revisão atual'}).isDisabled(), true);
    assert.equal(pilotAccessAttempts, 0);

    await page.reload();
    await page.getByRole('heading', {name: 'Preparação técnica'}).waitFor();
    await page.getByText('SRC-007_PD_v5.0B.docx').waitFor();
    assert.equal(await sections.getByRole('button',{name:'Redigir'}).getAttribute('aria-current'), 'page');
    await sections.getByRole('button',{name:'Revisar'}).click();
    await page.getByText('Prévia preliminar, aguardando comprovação.').waitFor();
    const draft = {id:'baseline',title:'Linha de base',outdated:false,section:{number:1,guideVersion:1,
      author:'autor-fixture',narrative:'Descrição da frota de referência',sha256:'sha-fixture'},history:[],actions:['SUBMITTED']};
    await page.route('**/technical/pd-reviews', route => route.fulfill({json:[draft]}));
    await page.route('**/technical/pd-reviews/baseline/versions/1', async route => {
      assert.equal(route.request().headers()['if-match'], '"0"');
      const payload=route.request().postDataJSON();
      assert.deepEqual(payload,{state:'SUBMITTED',reason:'Conferir a evidência da frota'});
      await route.fulfill({json:{...draft,actions:['REOPENED'],history:[{...payload,seq:1,actor:'autor-fixture',at:'2026-01-01T12:00:00Z'}]}});
    });
    await page.getByRole('button',{name:'Abrir fila de revisão'}).click();
    const submitReview=page.getByRole('button',{name:'Encaminhar para revisão'});
    await submitReview.waitFor();
    assert.equal(await submitReview.isDisabled(),true);
    await page.getByLabel('Parecer ou motivo da ação').fill('Conferir a evidência da frota');
    await submitReview.click();
    await page.getByRole('button',{name:'Reabrir para edição'}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Aprovar internamente'}).count(),0);
    await page.getByText('Conferir a evidência da frota',{exact:true}).waitFor();
    await page.route('**/technical/pd-sections', route => route.fulfill({json:[
      {guide:{id:'applicability',title:'Aplicabilidade',version:0,fields:[],answers:{},missing:[]},latest:null},
      {guide:{id:'baseline',title:draft.title,version:1,fields:[],answers:{},missing:[]},latest:draft.section}
    ]}));
    await page.getByRole('button',{name:'Abrir seção no editor'}).click();
    await page.getByLabel('Narrativa da seção').waitFor();
    assert.equal(await page.getByLabel('Narrativa da seção').inputValue(),draft.section.narrative);
    assert.equal(new URL(page.url()).searchParams.get('guia'),'baseline');
    await sections.getByRole('button',{name:'Preparar informações'}).click();
    assert.equal(await page.getByLabel('Atividade planejada').inputValue(),
      'Recarga elétrica em ambiente urbano');
    assert.equal(await page.getByLabel('País (código de duas letras)').inputValue(), 'BR');
    await page.getByRole('link',{name:'Preferências e equipe'}).click();
    await page.getByLabel('Modo de visualização').waitFor();
    assert.deepEqual(await menu.getByRole('link').allTextContents(), menuItems);
    assert.equal(await menu.getByRole('link', {name:'Preferências e equipe'}).getAttribute('aria-current'), 'page');
    await page.getByLabel('Modo de visualização').selectOption('TECHNICAL');
    await page.getByLabel('Preparação técnica (PREP)').fill('Equipe de engenharia');
    await page.getByRole('button',{name:'Salvar preferências'}).click();
    await page.getByText('Preferências e responsabilidades salvas.').waitFor();
    assert.equal(experience.preferences.responsibilities.prep, 'Equipe de engenharia');
    await page.reload();
    assert.equal(await page.getByLabel('Modo de visualização').inputValue(), 'TECHNICAL');
    await page.getByRole('link',{name:'Voltar à análise técnica'}).click();
    await page.getByText('Modo técnico',{exact:true}).waitFor();
    await page.getByText('PEN-004',{exact:false}).waitFor();
    await page.setViewportSize({width: 390, height: 844});
    const menuButton = page.getByRole('button', {name:'Menu',exact:true});
    assert.equal(await menu.isVisible(), false);
    await menuButton.click();
    await menu.waitFor();
    assert.equal(await menuButton.getAttribute('aria-expanded'), 'true');
    assert.deepEqual(await menu.getByRole('link').allTextContents(), menuItems);
    await menu.getByRole('link', {name:'Planos de convite'}).focus();
    await page.keyboard.press('Escape');
    await menu.waitFor({state:'hidden'});
    assert.equal(await menuButton.evaluate(button => document.activeElement === button), true);
    await menuButton.click();
    await menu.getByRole('link', {name:'Planos de convite'}).click();
    await page.getByRole('heading', {name:'Planos de convite'}).waitFor();
    assert.equal(await menu.isVisible(), false);
    await menuButton.click();
    await menu.waitFor();
    assert.deepEqual(await menu.getByRole('link').allTextContents(), menuItems);
    assert.equal(await menu.getByRole('link', {name:'Planos de convite'}).getAttribute('aria-current'), 'page');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);

    // A view from the monitoring rail must be bookmarkable and independently
    // rendered; underlying access and source data still come from the API.
    await page.route('**/v1/projects/*/technical/open-monitoring', route =>
      route.fulfill({json:{projectId,environment:'SANDBOX',chargers:[],
        meters:[],readiness:{state:'NOT_READY',canPrepare:false,
        canUseForE8:false,edKwh:null,ecKwh:null,gates:[]}}}));
    await page.route('**/v1/projects/*/technical/open-monitoring/**', route =>
      route.fulfill({json:[]}));
    await page.goto(base + '/workspace/projects/' + projectId +
      '/technical?secao=monitorar&etapa=leituras');
    const monitorSteps=page.getByRole('navigation',{name:'Etapas do monitoramento'});
    await monitorSteps.waitFor();
    assert.equal(await monitorSteps.getByRole('button',
      {name:'2. Leituras e ocorrências'}).getAttribute('aria-current'),'step');
    await page.getByRole('heading',
      {name:'Lotes de leituras recebidas (candidatos)'}).waitFor();
    assert.equal(await page.getByRole('heading',{name:'Inventário de carregadores'}).count(),0);
    await monitorSteps.getByRole('button',{name:'3. QA e evidências'}).click();
    await page.waitForURL('**/technical?secao=monitorar&etapa=qa**');
    await page.getByRole('heading',{name:'Reconciliação ED/EC por período'}).waitFor();
    assert.equal(await page.getByRole('heading',{name:'Lotes de leituras recebidas (candidatos)'}).count(),0);
    await page.reload();
    await page.getByRole('heading',{name:'Reconciliação ED/EC por período'}).waitFor();
    assert.equal(await monitorSteps.getByRole('button',
      {name:'3. QA e evidências'}).getAttribute('aria-current'),'step');
    await sections.getByRole('button',{name:'Painel de trabalho'}).click();
    await page.getByRole('heading',{name:'Painel de trabalho'}).waitFor();

    await page.route('**/v1/projects/*/technical/capabilities', route => route.fulfill({
      json:{projectId, environment:'SANDBOX', owner:false, actions:['VIEW_INTAKE'], modules:{}},
    }));
    await page.goto(base + '/workspace/projects/' + projectId + '/technical');
    await page.getByRole('heading', {name:'Preparação técnica'}).waitFor();
    await menuButton.click();
    await menu.waitFor();
    assert.deepEqual(await menu.getByRole('link').allTextContents(),
      ['Meus projetos', 'Novo projeto', 'Convites recebidos', 'Visão geral', 'Preparação técnica', 'Piloto sintético']);

  } finally {
    await browser.close();
  }
});
