# Architecture

Design summary of the Owlibi app in this repository. The code was copied from an AI-assisted reference application (see [AI_USAGE.md](AI_USAGE.md)); this file explains how it is organized and is not a test report. Verify behavior with the commands in the [README](../README.md).

## Decision summary

Accepted: browser client → Java 21/Spring Boot REST API → PostgreSQL. Swing is dropped from v1. One Spring Boot service serves both the website and `/api/v1`; for hosting, that service runs on Render Free with Supabase Free PostgreSQL under the ₱0 budget (see the [deployment guide](DEPLOY_RENDER_SUPABASE.md)). Supabase supplies database hosting, not auth or domain logic. See [STACK.md](STACK.md).

## System view

~~~mermaid
flowchart LR
  B[Member or leader browser] -->|HTTPS| S[Single Spring Boot service: website and API]
  S -->|TLS JDBC, migrations and transactions| D[(PostgreSQL)]
  S --> P[Membership, roles and task rules]
  P --> R[Completed points, workload and XLSX]
  P --> H[Safe server activity history]
~~~

Locally and on Render, Spring serves the built frontend and `/api/v1` from the same origin. Do not use file:// as the application. The browser never connects to PostgreSQL or Supabase Data/Auth APIs.

Ten-second visible/foreground project refresh plus manual refresh remains selected. Stop polling on sign-in/session end/hidden page and while a mutation is in flight; no offline writes or persistent project cache. Polls do not update human activity. Revalidate restored pages, guard late replies and use task versions to reject stale writes. WebSocket push remains deferred.

## Repository shape

The repository root is one Maven build plus the web client and hosting files:

- `server-api`: Maven Java controllers/DTOs, Spring Security sessions, domain services, SQL repositories, migrations and health.
- `web-client`: plain HTML/CSS/JavaScript modules, API adapter, page/view models and unit tests, assembled by `web-client/scripts/build.mjs`; no framework.
- `hosting`: Render Docker image, start script and Supabase bootstrap SQL.
- Root verification is `node --test web-client/tests/unit` and `./mvnw -f server-api/pom.xml verify`; Maven alone does not test the browser code.

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
- The service versions the assignment-and-points agreement and requires each current assignee to accept that version before work starts. Changes after work starts pause progression until renewed acceptance. Title, description, points and assignees changes bump the agreement version; category and due date are logged metadata edits.
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

The platform is settled (browser client, Spring Boot API, PostgreSQL; one Render service for site and API). Remaining engineering gates are live-deployment checks: real TLS and cookie behavior, free-tier capacity and an accessibility pass. Java OOP stays in the domain/service/rule/persistence boundaries and must be explainable by every team member.

## Technology references

Checked 2026-10-03. The current Spring Boot system requirements include Java 21 in their supported range; choose a maintained Spring Boot release when the scaffold is pinned. See [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html). PostgreSQL supports each major version for five years; use a supported provider version and plan upgrades. See the [PostgreSQL versioning policy](https://www.postgresql.org/support/versioning/).

## R1 implemented components

TemplateCatalog loads and validates immutable versioned classpath JSON; TemplateController exposes authenticated DTO reads. ProjectRules owns typed values/normalization and safe event summaries; ProjectSettingsService owns role/version/locking/no-op/transaction logic; SettingsController owns the bounded nested HTTP boundary. ProjectService atomically stores source/effective snapshots and delegates pending starter creation to the existing TaskService path. V4 stores rule columns on projects with independent settingsVersion and protects snapshot immutability; no new task subclass, domain server or dependency.

## R2 implemented responsibilities

SnapshotService owns authorized read-only REPEATABLE_READ capture; ProjectSnapshot contains detached immutable inputs; CanonicalState owns compact sorted-key state/date token encoding. TaskService delegates only Board reads to capture, preserving existing task mutations and response fields. SummaryCalculator owns pure BigDecimal contribution/workload policy; SummaryService captures then calculates after the transaction, and SummaryController exposes strict membership-scoped JSON GETs. No persistent score, extra mutation lock, dependency, migration or browser-authoritative result is added.

## R4 report export

R4 extends SnapshotService only for bounded leader report capture/authorization and adds CaptureBudget, ReportBudget, ReportWorkspace, ReportWorkbook and ReportController. The transaction ends before workbook generation. SXSSFWorkbook uses a 100-row window, compressed sheets, bounded shared strings, fixed styles/fonts/widths and ZIP64 AsNeeded within the approved small output cap. All sheet/intermediate/final writes are metered; final ZIP entries/CRCs are verified before delivery. R2 SummaryCalculator supplies authoritative unrounded arithmetic and one-decimal display values. POI/OOXML/lite 5.5.1 and explicit Commons IO 2.21.0 preserve the reviewed graph, Jackson 3.1.7/Tomcat 11.0.26 and toolchain pins.
