import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash, randomBytes, randomUUID} from 'node:crypto';
import {mkdir} from 'node:fs/promises';
import test from 'node:test';
import {chromium} from 'playwright-core';

const base = process.env.CARBONFLOW_TEST_URL ?? 'http://127.0.0.1:8080';
const composeProject = process.env.COMPOSE_PROJECT_NAME ?? process.env.CARBONFLOW_DEMO_PROJECT;
const fixtureActors = [];
const executablePath = process.env.CARBONFLOW_CHROME ?? '/usr/bin/google-chrome';

// Like the Quarkus integration fixtures, seed only identities and sessions.
// Projects, declarations, diagnoses and checkpoints use real HTTP handlers.
function sql(statement) {
  return execFileSync('docker', [
    'compose', '-p', composeProject, 'exec', '-T', 'db',
    'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'carbonflow', '-d', 'carbonflow',
  ], {input: statement, encoding: 'utf8', cwd: new URL('../../', import.meta.url)}).trim();
}

function actor() {
  const id = randomUUID();
  const token = randomBytes(32).toString('base64url');
  const hash = createHash('sha256').update(token).digest('hex');
  fixtureActors.push(id);
  sql(`
    INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
      VALUES ('${id}','${id}@example.invalid','Jornada de teste','ACTIVE',statement_timestamp());
    INSERT INTO app_account_session(token_hash,identity_id,expires_at)
      VALUES (decode('${hash}','hex'),'${id}',statement_timestamp()+interval '1 hour');
  `);
  return {id, token};
}

async function context(browser, identity) {
  const result = await browser.newContext({viewport: {width: 1440, height: 900}});
  await result.addCookies([{name: 'carbonflow_account_session', value: identity.token, url: base}]);
  return result;
}

async function json(response, status = 200) {
  assert.equal(response.status(), status, await response.text());
  return response.json();
}

function mutation(version, data) {
  return {data, headers: {'If-Match': '"' + version + '"', 'Idempotency-Key': randomUUID()}};
}

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return {promise, resolve};
}

test('UX-08: respostas fora de ordem, seletores persistidos, conflito e revogação reais', {
  timeout: 120_000,
}, async () => {
  assert.match(composeProject ?? '', /^(carbonflow-demo-ci-|cf-ux08-test-)[a-z0-9-]+$/);
  assert.ok(['127.0.0.1', 'localhost'].includes(new URL(base).hostname));
  const browser = await chromium.launch({executablePath});
  const oldRead = deferred(), releaseOldRead = deferred(), oldDelivered = deferred();
  try {
    const pilotBefore = sql("SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY id)::text,'[]') FROM synthetic_run s;");
    const owner = actor(), outsider = actor(), calculator = actor();
    const session = await context(browser, owner);
    const foreign = await context(browser, outsider);
    const limited = await context(browser, calculator);
    const organization = await json(await session.request.post(base + '/v1/organizations', {
      data: {name: 'Organização da jornada UX-08'}, headers: {'Idempotency-Key': randomUUID()},
    }), 201);
    const otherOrganization = await json(await foreign.request.post(base + '/v1/organizations', {
      data: {name: 'Outra organização UX-08'}, headers: {'Idempotency-Key': randomUUID()},
    }), 201);
    assert.notEqual(organization.id, otherOrganization.id);
    const project = await json(await session.request.post(base + '/v1/projects', {
      data: {organizationId: organization.id, title: 'Jornada persistida UX-08'},
      headers: {'Idempotency-Key': randomUUID()},
    }), 201);
    const root = base + '/v1/projects/' + project.projectId;
    await json(await session.request.put(root + '/technical/intake', mutation(0, {
      activity: 'Recarga DCFC de teste', country: 'BR', candidateMethodology: 'VM0038',
    })));
    const applicability = root + '/technical/open-applicability';
    const requirements = root + '/technical/requirements';
    await json(await session.request.put(root + '/technical/open-rights/parties/HOLDER-UX08',
      mutation(0, {kind: 'ORGANIZATION', displayName: 'Titular de teste UX-08'})));
    await json(await session.request.put(root + '/technical/open-rights/contracts/CONTRACT-UX08',
      mutation(0, {partyCode: 'HOLDER-UX08', label: 'Contrato de teste UX-08'})));
    const page = await session.newPage();
    const pilotRequests = [];
    page.on('request', request => {
      if (request.url().includes('/v1/projects/PIL-SYN-BR-001/')) pilotRequests.push(request.url());
    });
    const panel = page.locator('carbonflow-project-requirements');
    const closedNetwork = panel.locator('li').filter({hasText: 'Referência: CLOSED_NETWORK'});
    const technical = base + '/workspace/projects/' + project.projectId + '/technical?secao=';
    // The form lives in dedicated screens now: network and rights each persist a complete revision.
    await page.goto(technical + 'rede');
    await page.getByLabel('Qualquer BEV compatível pode utilizar?').waitFor();
    await page.getByLabel('Qualquer BEV compatível pode utilizar?').selectOption('false');
    await page.getByLabel('Restringe o uso a veículos identificados?').selectOption('true');
    const networkSaved = page.waitForResponse(response =>
      response.url() === applicability && response.request().method() === 'PUT');
    await page.getByRole('button', {name: 'Salvar acesso sem aprovação automática'}).click();
    assert.equal((await json(await networkSaved)).version, 1);
    await page.goto(technical + 'direitos');
    await page.getByLabel('Titular registrado', {exact: true}).selectOption('HOLDER-UX08');
    await page.getByLabel('Contrato registrado', {exact: true}).selectOption('CONTRACT-UX08');
    const savedResponse = page.waitForResponse(response =>
      response.url() === applicability && response.request().method() === 'PUT');
    await page.getByRole('button', {name: 'Salvar direitos sem aprovação automática'}).click();
    const saved = await json(await savedResponse);
    assert.equal(saved.version, 2);
    assert.equal(saved.latest.answers.unrestrictedBevAccess, false);
    assert.equal(saved.latest.answers.rightsContractCode, 'CONTRACT-UX08');
    // The diagnosis is read fresh when the status screen opens: no pre-save answer may appear.
    await page.goto(technical + 'analisar');
    await closedNetwork.waitFor();
    assert.equal(await closedNetwork.count(), 1, 'A situação do projeto deve refletir a revisão atual');
    assert.equal(await panel.locator('li').filter({hasText: 'Referência: NO_DECLARATION'}).count(), 0);

    const canonical = await json(await session.request.get(requirements));
    const journey = await json(await session.request.get(root + '/journey'));
    assert.deepEqual(journey.diagnosis, canonical);
    assert.equal(await panel.locator('li').count(), canonical.requirements.length);
    assert.equal(canonical.canRunE8, false);
    assert.equal(canonical.canIssueVcu, false);
    assert.equal(sql(`SELECT count(*) FROM app_project_open_applicability_revision
      WHERE project_id='${project.projectId}' AND version=2 AND unrestricted_bev_access=false;`), '1');

    // A second editor persists v3. The stale tab must keep its draft and report 412.
    await page.goto(technical + 'rede');
    await page.getByLabel('O ponto fica em terreno particular?').waitFor();
    const concurrent = await json(await session.request.put(applicability,
      mutation(2, {...saved.latest.answers, privateLand: true})));
    assert.equal(concurrent.latest.answers.privateLand, true);
    await page.getByLabel('O ponto fica em terreno particular?').selectOption('false');
    const conflictResponse = page.waitForResponse(response =>
      response.url() === applicability && response.request().method() === 'PUT');
    await page.getByRole('button', {name: 'Salvar acesso sem aprovação automática'}).click();
    assert.equal((await conflictResponse).status(), 412);
    await page.getByRole('alert').filter({hasText: 'Outra revisão foi salva'}).waitFor();
    assert.equal(await page.getByLabel('O ponto fica em terreno particular?').inputValue(), 'false');
    assert.equal((await json(await session.request.get(applicability))).version, concurrent.version);
    await page.reload();
    assert.equal(await page.getByLabel('O ponto fica em terreno particular?').inputValue(), 'true');
    assert.equal(await page.getByLabel('Qualquer BEV compatível pode utilizar?').inputValue(), 'false');
    assert.equal(await page.getByLabel('Restringe o uso a veículos identificados?').inputValue(), 'true');
    await page.goto(technical + 'direitos');
    assert.equal(await page.getByLabel('Titular registrado', {exact: true}).inputValue(), 'HOLDER-UX08');
    assert.equal(await page.getByLabel('Contrato registrado', {exact: true}).inputValue(), 'CONTRACT-UX08');
    await page.goto(technical + 'analisar');
    await closedNetwork.waitFor();
    await page.getByRole('region', {name: 'Aplicabilidade de rede aberta'})
      .getByText('Revisão 3', {exact: true}).waitFor();

    // Real checkpoint, route transition, page reload and return from the overview.
    const checkpoint = await json(await session.request.get(root + '/journey'));
    assert.equal(checkpoint.resume.screen, 'analisar');
    await page.goto(base + '/workspace/projects/' + project.projectId + '/overview');
    await page.getByRole('link', {name: 'Retomar minha etapa'}).click();
    await closedNetwork.waitFor();
    await page.reload();
    await closedNetwork.waitFor();
    assert.deepEqual((await json(await session.request.get(root + '/journey'))).diagnosis,
      await json(await session.request.get(requirements)));
    for (const viewport of [{width: 390, height: 844}, {width: 1440, height: 900}]) {
      await page.setViewportSize(viewport);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      if (process.env.CARBONFLOW_TEST_SCREENSHOTS) {
        await mkdir(process.env.CARBONFLOW_TEST_SCREENSHOTS, {recursive: true});
        await panel.scrollIntoViewIfNeeded();
        await page.screenshot({path: process.env.CARBONFLOW_TEST_SCREENSHOTS + '/ux08-' + viewport.width + '.png'});
      }
    }
    assert.equal((await foreign.request.get(requirements)).status(), 404);
    assert.equal((await foreign.request.put(applicability, mutation(3, saved.latest.answers))).status(), 404);
    const foreignPage = await foreign.newPage();
    await foreignPage.goto(base + '/workspace/projects/' + project.projectId + '/technical?secao=analisar');
    await foreignPage.getByRole('link', {name: 'Voltar a meus projetos'}).waitFor();
    assert.equal(await foreignPage.locator('carbonflow-open-applicability').count(), 0);
    const grant = randomUUID();
    sql(`INSERT INTO app_project_collaborator_grant
      (id,project_id,organization_id,environment,identity_id,granted_by,role,activities,valid_until)
      VALUES ('${grant}','${project.projectId}','${organization.id}','SANDBOX',
        '${calculator.id}','${owner.id}','PREP',ARRAY['CALCULATION']::text[],statement_timestamp()+interval '1 hour');`);
    const restricted = await json(await limited.request.get(requirements));
    assert.ok(!restricted.requirements.some(item => ['CLOSED_NETWORK', 'OWNER_CONTRACT', 'DRIVER_DISCLOSURE'].includes(item.requirementId)));
    assert.equal((await limited.request.get(applicability)).status(), 404);
    assert.equal((await limited.request.put(applicability, mutation(2, saved.latest.answers))).status(), 404);
    assert.equal((await limited.request.put(root + '/journey/resume', {
      data: {area: 'technical', screen: 'analisar', guide: null, step: null},
    })).status(), 422);
    sql(`UPDATE app_project_collaborator_grant SET revoked_at=statement_timestamp(),
      revoked_by='${owner.id}' WHERE id='${grant}';`);
    for (const path of [requirements, root + '/journey', applicability]) {
      assert.equal((await limited.request.get(path)).status(), 404);
    }
    sql(`UPDATE app_organization_membership SET revoked_at=statement_timestamp()
      WHERE organization_id='${organization.id}' AND identity_id='${owner.id}';`);
    await page.reload();
    await page.getByRole('link', {name: 'Voltar a meus projetos'}).waitFor();
    assert.equal(await panel.count(), 0);
    assert.equal((await session.request.put(applicability, mutation(2, saved.latest.answers))).status(), 404);
    assert.equal(sql(`SELECT count(*) FROM app_project_open_applicability_revision
      WHERE project_id='${project.projectId}';`), '3');
    assert.deepEqual(pilotRequests, []);
    assert.equal(sql("SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY id)::text,'[]') FROM synthetic_run s;"), pilotBefore);
  } finally {
    releaseOldRead.resolve();
    await browser.close();
    // The sandbox limits active sessions globally. Do not consume the slots
    // needed by the provisioning smoke or the other real-account scenarios.
    if (fixtureActors.length) sql(`DELETE FROM app_account_session
      WHERE identity_id IN (${fixtureActors.map(id => "'" + id + "'").join(',')});`);
  }
});
