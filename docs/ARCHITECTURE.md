# Architecture

## H0 follow-up disposition — October 14, 2026

[Local follow-up evidence](evidence/reference/hosted/2026-10-14-h0-followup.md) records **116 backend/fixture, 31 frontend and 45 Chromium cases, zero failures/errors/skips/flaky results/retries**, retaining all earlier cases. Prepared hosted Origin/TLS/Secure-cookie policy, separate migration/runtime-role validation and synthetic encrypted recovery were locally verified. Development defaults, domain rules, browser behavior, migrations and existing dependency/toolchain pins remain intact. The 0.1 CPU diagnostic still failed the ordinary sign-in budget; one-CPU maximum export passed only within its explicitly local control scope. Confirmed OpenSSL/FreeType image patch gaps and a libpng feed/notice contradiction remain unresolved. H0's hosting exit gate is open; **H1 is unperformed/unapproved and deployment readiness is not established**.

The [revised conditional package](evidence/reference/hosted/2026-10-14-h0-followup.approval.md) keeps one designated operator (D-023), Singapore/Vercel CDN, accepted encrypted local/offline backup retention and January 1, 2027 shutdown (D-022). Exact operator/accounts/targets, image remediation/candidate capacity, real TLS/proxy/cache/preview transport and destination/key recovery/provider restore remain gates. No provider action, upload, billing, Git publication or M4 work occurred. Earlier dated preparation and accepted aggregate/native/manual evidence below retain their original scopes; no human versions or certification are invented.

Current local status — October 13, 2026: **Local M3 R0–R6 acceptance passed within recorded evidence scope: fresh 110 backend/fixture, 31 frontend and 45 Chromium cases, zero failures/errors/skips/flaky results/retries; accepted R3/R5 aggregate manual results and R5 native Google Sheets readback are reused. Human versions/per-check observations remain unspecified. Hosting H0/H1, full-reference M4 review/freeze, actual course/team evidence, student rebuild and release remain separate and incomplete.** See [R6 acceptance evidence](evidence/reference/m3/2026-10-13-r6.md). Earlier dated preparation/checkpoint wording retains its historical scope.

## Decision summary

Accepted: browser client → Java 21/Spring Boot REST API → PostgreSQL, local reference first, independent student rebuild later. Swing is dropped from v1 under D-016. Vercel is the frontend hosting direction (D-017); one Render Free Java API + Supabase Free PostgreSQL remains the recommended backend/database candidate under ₱0. Supabase supplies database hosting, not auth/domain logic. The local Java/browser reference is implemented; no hosted service is provisioned. See [STACK.md](STACK.md), [browser guide](BROWSER_DESIGN_IMPLEMENTATION.md) and [runbook](DEPLOYMENT_RUNBOOK.md).

## System view

~~~mermaid
flowchart LR
  B[Member or leader browser] -->|HTTPS| F[Vercel frontend]
  F -->|External API rewrite| S[Single Spring Boot API]
  S -->|TLS JDBC, migrations and transactions| D[(PostgreSQL)]
  S --> P[Membership, roles and task rules]
  P --> R[Completed points, workload and XLSX]
  P --> H[Safe server activity history]
~~~

Hosted topology is a candidate to verify, not a deployment result. Locally, Spring serves the built frontend and `/api/v1` on the same loopback origin; an optional development proxy must preserve that browser-visible origin. Do not use file:// as the application or rely on a Vercel preview contacting localhost. The browser never connects to PostgreSQL or Supabase Data/Auth APIs.

Ten-second visible/foreground project refresh plus manual refresh remains selected. Stop polling on sign-in/session end/hidden page and while a mutation is in flight; no offline writes or persistent project cache. Polls do not update human activity. Revalidate restored pages, guard late replies and use task versions to reject stale writes. WebSocket push remains deferred.

## Repository shape

Planned roots are absent: `ai-reference/` and independent `student-rebuild/`. Each owns its builds, dependencies, migrations/configuration and evidence; the rebuild must not inherit/copy reference source or implementation-specific tests. `concept/` remains fictional visual reference material.

- `server-api`: Maven Java controllers/DTOs, Spring Security sessions, domain services, SQL repositories, migrations and health.
- `web-client`: recommended HTML/CSS/JavaScript modules, API adapter, page/view models, semantic controls and browser tests. Exact frontend toolchain/build pins are an A0 gate; no framework is selected automatically.
- Root verification coordinates server integration tests and frontend/browser checks; Maven alone cannot certify browser behavior. Define actual reproducible commands before scaffold.

Keep persistence entities, API DTOs and browser presentation models distinct. Java services own domain invariants; browser validation supplies feedback only.

## Responsibilities

### Browser client

- Present Projects dashboard/picker, task workspace/inspector, review queue, summaries, history, templates/settings and leader workbook download.
- Use authenticated same-origin API requests with CSRF on unsafe methods; never store passwords/session IDs in JavaScript storage.
- Preserve recoverable drafts/focus/selection in memory; reject old session/project/view/read responses. Logout/confirmed expiry clear protected state across tabs and restored pages revalidate.
- Explain loading/empty/stale/permission/validation/conflict/unknown-write states. No automatic write replay or offline queue.
- Describe only observable generation/download outcomes; browser saving/overwrites are browser-managed.

### API service

- Authenticate and authorize every request using current membership/role; enforce six-member project limits atomically.
- Enforce task/terms/terminal/first-start rules and optimistic versions, generating safe actor/server-time events in the same transaction.
- Enforce human-inactivity expiry independently of polling using the specified browser activity protocol; tab close is not authoritative logout.
- Compute decimal completed points/workload from coherent database state and project-local dates.
- Authorize four-sheet professor XLSX export from one consistent snapshot with literal user text and bounded resources.
- Return stable safe errors; protect cookie/CSRF flows and mark protected responses non-cacheable through every proxy layer.

### PostgreSQL

- Persist accounts, memberships, invitations, projects/template snapshots, tasks/assignees/acceptance and events.
- Apply versioned migrations with separate migration-owner/runtime credentials and relational constraints.
- Serialize competing domain mutations and preserve restart durability; database credentials never reach the browser.

## Domain boundaries

- A project is the collaboration boundary. Members belong to a project; a project has exactly one current leader in v1.
- Templates provide data such as suggested categories, starter tasks, and default rule settings. Creating a project copies a template snapshot so later template changes do not rewrite existing projects.
- A task has a status, due date, effort points, and one or more assignees.
- The service versions the assignment-and-points agreement and requires each current assignee to accept that version before work starts. Changes after work starts pause progression until renewed acceptance. D-018 covers title/description/points/assignees; category/due date are logged metadata. Local M2 T-03 PATCH schemas are locked in API_CONTRACT.md.
- The service owns the activity log, contribution formula, workload policy, and role checks.
- Activity records are append-only through normal user operations. They are not cryptographic proof and do not prevent a database operator from changing stored data.

## Concurrency and synchronization

Every mutable task has a monotonically increasing version. A client update includes the version it last read. If the stored version has changed, return HTTP 409 STALE_VERSION using the safe error envelope and require the client to reload the authoritative board for the current version/terms. Apply a task mutation and its activity event in one database transaction.

Automatic refresh should pause when the app is backgrounded or the user is on a sign-in screen. Refresh intervals and backoff are configuration choices, not hard-coded into domain behavior.

## Failure behavior

- API unavailable: retain a clearly labeled last successful view, disable writes, and offer retry.
- Request timeout: do not assume success; reload before retrying non-idempotent changes.
- Authorization failure: show the action is unavailable and do not retry automatically.
- Conflict: reload the task and ask the user to reapply any still-needed change.
- Database migration failure: fail deployment health checks; never start against an incompatible schema.

## Architecture decisions still tracked

D-016–D-018 and the raw review answers settle platform and policy direction. [DECISION_REVIEW.md](DECISION_REVIEW.md) lists engineering contracts and genuinely missing values. Frontend framework/build pins, activity/expiry/multi-tab protocol, provider owner/region/quotas, proxy/cache behavior, export bounds and actual hosted checks remain gates. The recommended static Vercel frontend changes the earlier Thymeleaf suggestion; Thymeleaf/React/Next.js is not silently selected. Java OOP stays in the domain/service/rule/persistence boundaries and must be explainable in both implementations.

## Technology references

Checked 2026-10-03. The current Spring Boot system requirements include Java 21 in their supported range; choose a maintained Spring Boot release when the scaffold is pinned. See [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html). PostgreSQL supports each major version for five years; use a supported provider version and plan upgrades. See the [PostgreSQL versioning policy](https://www.postgresql.org/support/versioning/).

## Local M3 R0 engineering direction

The reference now exists under ai-reference with completed local M1/M2 evidence; earlier “no scaffold” and absent-root descriptions above are historical planning statements. Student-rebuild remains absent. [R0](M3_R0_SPECIFICATION.md) defines future data-driven templates, project-locked settings, shared Java decimal calculation/capture services and bounded four-sheet export; no dependent implementation occurred. Existing task-lock behavior stays intact. Immutable REPEATABLE_READ capture closes before workbook I/O; state-content tokens compare independent Board/report observations without claiming a global/history revision.

POI 5.5.1 is the R4 candidate pin verified in a cache-only dependency probe; it is not in the application POM/package. Preserve Jackson/Tomcat overrides and the fail-closed full verification gate. [Product approvals](M3_APPROVAL_PACKAGE.md) and actual R1–R6 tests remain required; local specification says nothing about free-host capacity or internet acceptance.

## M3 R0 approval disposition — 2026-10-13

[D-021](DECISIONS.md#m3-ac-approval-reconciliation--2026-10-13) accepts the exact [A–C package](M3_APPROVAL_PACKAGE.md), settling starter/single-selection/creator-pending-acceptance initialization, scale/numeric suggestions/bounds/legacy setup and complete-history export refusal budgets. Earlier R0 pending/proposal wording above is historical and does not reopen these choices. [R0 contracts](M3_R0_SPECIFICATION.md) now govern with those approved values. Product-policy gates are settled; application implementation, required runtime/security/race/browser/workbook/compatibility evidence and separate slice authorization remain pending. No unrun result passes through this approval.

## R1 implemented components

TemplateCatalog loads and validates immutable versioned classpath JSON; TemplateController exposes authenticated DTO reads. ProjectRules owns typed values/normalization and safe event summaries; ProjectSettingsService owns role/version/locking/no-op/transaction logic; SettingsController owns the bounded nested HTTP boundary. ProjectService atomically stores source/effective snapshots and delegates pending starter creation to the existing TaskService path. V4 stores rule columns on projects with independent settingsVersion and protects snapshot immutability; no new task subclass, domain server or dependency. Verification and full-M3 status are recorded separately; R2 calculations/R3 UI/R4–R5 export remain excluded.

## R2 implemented responsibilities

SnapshotService owns authorized read-only REPEATABLE_READ capture; ProjectSnapshot contains detached immutable inputs; CanonicalState owns compact sorted-key state/date token encoding. TaskService delegates only Board reads to capture, preserving existing task mutations and response fields. SummaryCalculator owns pure BigDecimal contribution/workload policy; SummaryService captures then calculates after the transaction, and SummaryController exposes strict membership-scoped JSON GETs. No persistent score, extra mutation lock, dependency, migration or browser-authoritative result is added. R3 browser summary/template/settings surfaces and R4–R5 workbook remain later slices.

## R4 execution checkpoint — 2026-10-13

**Local R4 server export gate passed 110/20/35 with zero test failures/errors/skips/flaky results; R5/R6/full M3 incomplete.** R4 extends SnapshotService only for bounded leader report capture/authorization and adds CaptureBudget, ReportBudget, ReportWorkspace, ReportWorkbook and ReportController. The transaction ends before workbook generation. SXSSFWorkbook uses a 100-row window, compressed sheets, bounded shared strings, fixed styles/fonts/widths and ZIP64 AsNeeded within the approved small output cap. All sheet/intermediate/final writes are metered; final ZIP entries/CRCs are verified before delivery. R2 SummaryCalculator supplies authoritative unrounded arithmetic and one-decimal display values. POI/OOXML/lite 5.5.1 and explicit Commons IO 2.21.0 preserve the reviewed graph, Jackson 3.1.7/Tomcat 11.0.26 and toolchain pins. No R5 control, provider integration or student source is added. See [R4 evidence](evidence/reference/m3/2026-10-13-r4.md).

## R6 local acceptance review — 2026-10-13

Local M3 R0–R6 acceptance passed within recorded evidence scope: fresh 110 backend/fixture, 31 frontend and 45 Chromium cases, zero failures/errors/skips/flaky results/retries; accepted R3/R5 aggregate manual results and R5 native Google Sheets readback are reused. Human versions/per-check observations remain unspecified. Hosting H0/H1, full-reference M4 review/freeze, actual course/team evidence, student rebuild and release remain separate and incomplete. The R6 slice reviewed contracts, exact catalogue rows, source/coverage/calculation examples, dependency/package/license/secret boundaries and preservation; no application/test/build/dependency behavior changed. Accepted manual evidence was reused without inventing versions or repeating checks solely for missing detail. The next separately authorized step is H0 hosting preparation, followed by H1 only after required target/security/provider approvals; later M4 full-reference review/freeze is separate. See [R6 record](evidence/reference/m3/2026-10-13-r6.md).
