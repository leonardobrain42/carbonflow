# CF-IMP-001 — CI and baseline regression gates

The GitHub Actions workflow at `.github/workflows/ci.yml` tests the existing synthetic CarbonFlow code on each PR and on `main`:

1. **Angular**: Node 24, lockfile `npm ci`, Angular production build.
2. **Quarkus**: Java 21, a disposable PostgreSQL 16 service, `./mvnw clean verify` with `CARBONFLOW_TEST_DB_URL` pointing only to `carbonflow_test`, and Surefire reports.
3. **Browser**: isolated Docker Compose project/volume, Playwright Chromium matching `frontend/package-lock.json`, all `test/*.test.mjs` journeys executed sequentially (shared scenario state), test transcript retained for seven days.

The workflow never targets production DBs, never uses project secrets, does not index Verra documents, and only removes the uniquely named CI Compose environment. The current code contains synthetic fixtures with M1 **6.00** and M2 **5.30 tCO₂e**; the existing Java/browser suites, not this document, verify these values.

## Verification and acceptance

- Observe all three green jobs in a real GitHub Actions run before merging.
- Investigate a failing browser journey without disabling it or marking the stage green.
- These jobs complement (and do not replace) the recovery and operational checks from E8-011/012 or later performance/security suites CF-IMP-026.
- Runner requirements: GitHub-hosted Ubuntu runner with Docker; Node 24 and Java 21 installed by setup actions. No secret required.
- Source of truth: `backend/pom.xml`, `backend/src/test/`, `frontend/package.json`, `frontend/test/`, `compose.yaml`. The GitHub Actions configuration is new and may require repo Actions permissions for workflows.
