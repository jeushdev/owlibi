# Owlibi stack and repository structure

Current local tool policy (November 8, 2026): JDK 21+, Node 24+, npm 11+ through `PATH`/standard `JAVA_HOME`; no exact vendor or patch requirement. Java source targets release 21. Maven Wrapper and application/database/browser dependency locks remain. Historical baseline versions below describe earlier evidence. See [current setup](TEAMMATE_RUN.md).

Operating-policy acceptance — October14,2026: **D-025 accepts daily18:00–20:00 Asia/Manila support,19:30 backup and a separate encrypted offline key with another trusted team member.** D-024's24hRPO/one-dayRTO/pre-migration backup targets remain accepted. [Supplement](evidence/reference/hosted/2026-10-14-h0-operating.md) records the human answer separately from prior executed evidence. An account label was supplied privately; names/provider-account existence/free eligibility/DNS/TLS, exact tooling/passphrase custody/storage access and actual feasibility remain unverified. No backup job, real-drive/provider action or new application check was performed; H0 remains open/H1 unapproved.

## H0 final local preparation — October 14, 2026

The [fresh local record](evidence/reference/hosted/2026-10-14-h0-final.md) passed **116 backend/fixture, 31 frontend and 45 Chromium cases**, zero failures/errors/skips/flaky results/retries. A **separate diagnostic ARM64 image**, retaining the original baseline and exact JRE/application pins, applies the reviewed Ubuntu OpenSSL/FreeType/libpng fixes. Local 1 CPU/2 GiB maximum/refusal/busy controls and remediated TLS migration/encrypted-key restore passed. These are local measurements, not OCI/Render capacity, whole-image clearance, actual provider security or real-destination recovery acceptance.

**H0 remains open; H1 is unperformed/unapproved.** Operator/account/slot/target/DNS/TLS, tooling and named key/passphrase custody remain unresolved; D-024/D-025 accept the recovery targets and operating times/custodian role. The [conditional proposal](evidence/reference/hosted/2026-10-14-h0-final.approval.md) and [recovery choices](evidence/reference/hosted/2026-10-14-h0-final.recovery-proposal.md) preserve D-022/D-023. No provider action, publication, upload, payment, Git staging/commit/push or M4 work occurred. Earlier dated findings retain their original baseline/evidence scopes; no accepted aggregate manual result is repeated or promoted to certification.

## H0 follow-up disposition — October 14, 2026

[Local follow-up evidence](evidence/reference/hosted/2026-10-14-h0-followup.md) records **116 backend/fixture, 31 frontend and 45 Chromium cases, zero failures/errors/skips/flaky results/retries**, retaining all earlier cases. Prepared hosted Origin/TLS/Secure-cookie policy, separate migration/runtime-role validation and synthetic encrypted recovery were locally verified. Development defaults, domain rules, browser behavior, migrations and existing dependency/toolchain pins remain intact. The 0.1 CPU diagnostic still failed the ordinary sign-in budget; one-CPU maximum export passed only within its explicitly local control scope. Confirmed OpenSSL/FreeType image patch gaps and a libpng feed/notice contradiction remain unresolved. H0's hosting exit gate is open; **H1 is unperformed/unapproved and deployment readiness is not established**.

The [revised conditional package](evidence/reference/hosted/2026-10-14-h0-followup.approval.md) keeps one designated operator (D-023), Singapore/Vercel CDN, accepted encrypted local/offline backup retention and January 1, 2027 shutdown (D-022). Exact operator/accounts/targets, image remediation/candidate capacity, real TLS/proxy/cache/preview transport and destination/key recovery/provider restore remain gates. No provider action, upload, billing, Git publication or M4 work occurred. Earlier dated preparation and accepted aggregate/native/manual evidence below retain their original scopes; no human versions or certification are invented.

## H0 hosting preparation — October 14, 2026

H0 supplies a [concrete candidate proposal and local preparation record](evidence/reference/hosted/2026-10-14-h0.md), [allowlisted diagnostic container/build instructions](../ai-reference/hosting/README.md) and [conditional H1 approval package](evidence/reference/hosted/2026-10-14-h0.approval.md). It creates no provider accounts, resources or public endpoints. The diagnostic entrypoint refuses hosted execution. Published provider capabilities, local constrained measurements and required H1 observations are kept separate; H0 does not establish deployment readiness, hosted FR-09, recovery acceptance or full-reference completion. Exact targets/owner/account slots, hosted Origin/proxy/cookie/TLS integration, capacity and independent recovery remain stop gates. D-015–D-017/D-021 and all existing toolchain/security pins remain unchanged. D-023 replaces the initial D-022 three-equal-operator proposal with one designated team operator/account owner/support contact. D-022 retains Singapore, the accepted encrypted local/offline backup retention and January 1, 2027 shutdown; named targets, eligibility and security/capacity/recovery remain unresolved. Earlier R6 and aggregate R3/R5 manual/native evidence is preserved without invented versions or certification.

Next: resolve the recorded H0 prerequisites and review the exact H1 target/action package before any external action. H1 is unperformed and unapproved; M4 full-reference review/freeze remains later work. Existing local startup remains `sh ai-reference/scripts/start-local.sh` on Mac and `powershell -ExecutionPolicy Bypass -File ai-reference/scripts/start-local.ps1` on Windows (from repository root), API8080/PostgreSQL55580.

Reviewed: 2026-10-09 (Asia/Manila). This is a planning summary, not a dependency installation or successful build. Accepted behavior comes from [DECISIONS.md](DECISIONS.md); exact local encoding comes from [API_CONTRACT.md](API_CONTRACT.md). Historical runtime probes are in [M1 evidence](evidence/local-m1/VERIFICATION.md). The [local runtime lock](LOCAL_FIRST_MILESTONE_PLAN.md#proposed-runtime-and-repository-layout) remains the detailed pin record.

## Local reference baseline

| Area | Selected or planned choice | Evidence and remaining gate |
|---|---|---|
| Language/JDK | Java 21; Eclipse Temurin 21.0.12.1+1-LTS, Windows x64 | Compiler/runtime/checksums passed October 5. Recheck on execution machine; system Java was 26 and must not be used accidentally. |
| Browser frontend | Vercel direction; plain HTML/CSS/JavaScript ES modules; Node 24.21.0/npm 11.19.0 for checks/build only | Runtime archive checksum verified locally October 9. Node built-in `node:test`; `@playwright/test` 1.64.0 with bundled Chromium 156.0.8078.4; copy-only static asset build. Package lock/scripts and application build remain A1. |
| Browser verification | Recommend Windows Chrome/Edge + NVDA; representative viewports, zoom/reflow | Pin versions/matrix before A4/release. Native Swing dimensions/scaling are superseded; no tested support/compliance claim. |
| API | Java 21 REST service; Spring Boot 4.1.1 approved October 9 | Official requirements support Java 21/Maven 3.9. Use `spring-boot-starter-webmvc`; resolve the full tree and verify Security/Jackson migration APIs in A1. |
| Auth | Spring Security 7.1.1 via Boot 4.1.1 BOM; BCrypt strength 12; transient server sessions | T-02 cookie/CSRF/activity/epoch contract specified. Custom JSON login must invoke session strategy and save SecurityContext. Verify actual Security 7.1 filter chain before A2. |
| Persistence | Spring JDBC + parameterized SQL; Flyway 12.4.0 core/PostgreSQL module from Boot 4.1.1 BOM | Full resolved dependency tree, migration/runtime role setup, schema constraints and migration verification pending A1. |
| Database | PostgreSQL 17.11; official Linux Docker image | Index digest `sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f`; pulled and started in a tmpfs-only disposable container October 9. Use loopback 55458 on this machine because 55432 is OS-excluded. Migration/runtime roles and app integration remain pending. |
| Driver/JSON | PostgreSQL JDBC 42.7.13; Jackson 3 BOM overridden uniformly to fixed 3.1.7 (Boot 4.1.1 manages 3.1.5 by default) | OSV direct-coordinate check found five advisories affecting Jackson Databind 3.1.5, all fixed by 3.1.7; queried other direct candidates had no matches. Verify complete transitive tree and Jackson 3 strict-parser APIs in A1. Jackson 2 compatibility is not planned. Browser gets public DTOs/assets only; Jackson remains server-side. |
| Build | Maven 3.9.16; wrapper plugin 3.3.4 | Maven checksum/runtime passed October 5; wrapper artifact probed, wrapper generation pending A1. |
| Packaging | Boot executable server JAR/container plus built browser assets | Shade client JAR/jpackage/bundled client JRE are superseded. Pin frontend build/test commands; verify fresh local setup and cache-safe web deployment. |
| Automated checks | JUnit 6.0.3 and Testcontainers 2.0.5 from Boot 4.1.1 BOM; Node built-in tests; Playwright 1.64.0 | Same pinned DB image, test-owned containers/random ports/credentials, no development volumes. `ai-reference/scripts/verify.ps1` must fail if required integration tests cannot run; browser checks are added at A4. |
| Point storage | BigDecimal / NUMERIC(10,2), 0.01–99999999.99, max two decimals | Adopted for local M1. Plain JSON decimal strings; reject excess precision/overflow without rounding. Estimation scale and summary division/display policy remain M3 choices. |
| Synchronization | Ten-second foreground refresh + manual; complete board capped at 1000 tasks/project locally | Selected local baseline. Bounded membership/list/body behavior and measured Board performance still require engineering contracts. No offline writes or automatic replay. |
| Professor workbook | `.xlsx`, four worksheets, leader export, one server snapshot | Accepted FR-14. Apache POI XSSF is recommended; version/transitives/licenses and exact export contract remain unselected until M3. No library has been installed. |
| Hosted service | Vercel frontend direction + recommended Render Free Dockerized API + Supabase Free PostgreSQL | ₱0 budget accepted (D-015); provider recommendation unprovisioned. Current quota/memory/traffic fit, owner/region, HTTPS/proxy/session/rate limits, independent backup/restore and lifecycle remain gates. See [runbook](DEPLOYMENT_RUNBOOK.md). |

Swing, a native installer, React/Next.js, a Node business backend, browser-to-database access, custom crypto, native mobile apps, offline merging, Google API integration and WebSocket infrastructure are not required by accepted v1 scope. The browser frontend is in scope; Boot 4.1.1 is approved, while full dependency resolution/build verification remains an A1 gate. No dependency was installed by this review.

## Structure and responsibility

```text
fairshare-java-draft/
  AGENTS.md, REQUIREMENTS.md, PROJECT_PROPOSAL.md
  PRODUCT.md, DESIGN.md, README.md
  docs/                  contracts, decision review, milestones and evidence
  concept/               fictional HTML visual/interaction reference
  ai-reference/          future independent Maven root for AI reference
    server-api/          DTO/controller -> service -> SQL repository -> PostgreSQL
    web-client/          browser API/CSRF adapter -> view model -> semantic HTML/CSS
  student-rebuild/        future team-written implementation, its own build/evidence
```

The last two roots are planned, absent directories. Reserve Java package names and exact file inventory at A1; this review does not scaffold them. Use feature packages with small controller/service/repository boundaries. The server owns domain rules; the browser can validate for feedback but cannot become the authoritative rule implementation. Keep persistence entities separate from API DTOs and browser presentation models. Avoid a shared mutable entity module and per-template Task subclasses.

One reference workflow coordinates the Maven server build and the separately specified frontend build/browser checks; Maven alone is not browser evidence. The independent rebuild owns its own backend/frontend builds and configuration, without inherited reference source/assets/tests except separately licensed permitted third-party resources. Tests use requirement scenarios but are independently authored for the rebuild.

## Readiness sequence

1. A0 contracts now pin frontend tooling and specify T-02 session/request behavior; see [DECISION_REVIEW.md](DECISION_REVIEW.md) and API_CONTRACT.
2. Recheck current runtime/artifacts/ports and verify a test-owned PostgreSQL lifecycle. The prior Docker startup issue was repaired only by preserving/renaming its runtime socket directory; the unrelated engine state/volumes were not reset.
3. Confirm backend support/advisories, migration/runtime role bootstrap, source inventory and reproducible root verification before A1. A toolchain pin, POM/image digest or healthy daemon alone does not prove application build readiness.
4. Proceed only with the authorized milestone slice and exact file inventory. M2/M3 policy gates remain separate; hosted/release actions require their own approval.

Official sources checked October 8: [Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Spring support policy](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions), [Security 6.5 session persistence](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html), [PostgreSQL versioning](https://www.postgresql.org/support/versioning/), [FlatLaf](https://www.formdev.com/flatlaf/), [POI spreadsheet formats](https://poi.apache.org/components/spreadsheet/), [Render limitations](https://render.com/docs/free). These support compatibility/features; they do not establish a resolved build, absence of vulnerabilities, hosted capacity or license clearance. Published version/availability alone does not establish Spring maintenance eligibility; check exact support/entitlement and dependency advisories at A0 before scaffold and again before hosted use. If a pin needs replacement, report the discrepancy and revise contracts explicitly.

## A1 dependency security reconciliation — 2026-10-10

The current Mac implementation resolved Boot 4.1.1's full application/test dependency tree and found that its managed Tomcat **11.0.24** is affected by OSV advisories `GHSA-9xv2-5v5q-p794`, `GHSA-gcx9-497g-6cp6` and `GHSA-h3x4-894j-xpx5`. [Apache's official security page](https://tomcat.apache.org/security-11.html) also records additional fixes through **11.0.26**. The bounded A1 engineering adjustment explicitly manages `org.apache.tomcat.embed:tomcat-embed-core`, `tomcat-embed-el` and `tomcat-embed-websocket` at **11.0.26**, before the Boot BOM. Merely setting a property while importing a BOM would not override its managed versions. Java 21, Boot 4.1.1, Jackson 3.1.7 and the accepted architecture remain unchanged. This is a documented security patch within Tomcat 11.0.x, not a new framework or product-policy choice.

Earlier dated availability/direct-coordinate checks remain historical; they did not prove the complete tree. A1 verification now checks the resolved/effective versions and scans every application/test Maven coordinate plus the npm lockfile. Scan failure or an advisory prevents the automated gate/start from succeeding. No-match results do not certify absence of all vulnerabilities; review publisher advisories again before hosted use. Current-machine commands, results and remaining gates are recorded in [the new A0/A1 evidence](evidence/reference/m1/2026-10-10-macos-a0-a1.md).
