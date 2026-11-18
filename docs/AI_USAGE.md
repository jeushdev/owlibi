# AI usage disclosure

Owlibi · team 3 of a Kind · OOP 101

This file says what was written by AI, what was copied, and who did what. It is based on the repository and its Git history. Lines marked `TODO(Jeush): confirm` hold facts only Jeush knows and must be filled in or corrected before submission.

## Summary

- The application code in this repository was **not written from scratch by the team**. It was copied, in small commits, from a reference application that was **generated with AI coding assistants** and lives outside this repository, in `fairshare-java-draft/ai-reference/`.
- The reference's own disclosure names Codex for several slices, and Claude Code was also used. TODO(Jeush): confirm which AI tools were used and for what.
- Copying was done with AI assistance as well. Files were taken from the reference at the same relative path, with only the files listed in the team's plan.
- The test commands in the [README](../README.md) check that the copied code still builds and passes. They do not make the code the team's own work.

## What was copied from the AI reference

| Area | Where |
|---|---|
| Spring Boot REST API (controllers, services, security, snapshots, summaries, reports, hosting) | `server-api/src/main/java/` |
| Database migrations V1 to V4, configuration, template catalogue | `server-api/src/main/resources/` |
| Integration and unit tests | `server-api/src/test/java/` |
| Browser app (HTML, CSS, JavaScript) and its unit tests | `web-client/` |
| Third-party license files and the interactive `/mockup/` pages (AI-generated in the reference) | `server-api/src/main/resources/licenses/`, `server-api/src/main/resources/mockup/` |
| Maven wrapper and POMs, `compose.yaml`, Render and Supabase files | repository root, `hosting/`, `scripts/db/` |

`web-client/src/brand-logo.png` is an AI-generated owl-with-magnifier image chosen for the reference.

## Who did what

Names are the Git authors of the commits (`git log --format='%an' --no-merges`). "Copied" means the file came from the reference; nobody on the team wrote the logic.

| Person | Work in this repository |
|---|---|
| Jeush Samuel Bantayaon | Repository setup, Maven and PostgreSQL build files, app entry point, config, health, web helpers, account and auth security, snapshots, projects, hosting and Render files, `AuthBoundaryIT`, `ProjectBoundaryIT`, `ReadinessIT`, `HostedPreparationIT`, README, this file, and merging pull requests. |
| John Allen Alvarez | Database migrations, template catalogue, security filters, task and summary code, Excel report code, test support and persistence tests, `SummaryIT`, `TaskBoundaryIT`, `TaskEditingIT`, `ProfessorReportIT`, and the first versions of the architecture, stack and deployment docs. |
| Julia Fernando | The browser app: build scripts, API client, session, model, styles, UI rendering, main script and the web unit tests. |

Pieces that were still owed were added by **Jeush** on the `jeushdev/finish` branch, with AI assistance, copied from the reference: `ProjectSetupIT` (two commits), `ReportResourceIT` and, in Julia's area, `StaticAssetsIT` and `RoutingBoundaryIT`. `ReportResourceIT` needs the license files, and `RoutingBoundaryIT` needs the mockup pages, so those were copied too. Jeush did this on Julia's behalf; the commits carry Jeush's name. Not yet in the repository: Julia's own mockup screens in `docs/mockup/` and the live screenshots in `docs/screenshots/`. TODO(Jeush): update this paragraph when those land, and confirm that Julia agrees with how it is described.

### Tests written by John

`ProjectRulesTest`, `ProjectRulesNormalizeTest`, `TemplateCatalogTest`, `CursorTest`, `FieldsTest` and `DatabaseReadinessServiceIT` are not in the reference. They were written by John. TODO(Jeush): confirm with John whether AI assisted them.

## Who can explain what

Each member must be able to explain, in about a minute, the files they present.

| Person | Files they can explain |
|---|---|
| Jeush | TODO(Jeush): confirm |
| John | TODO(Jeush): confirm |
| Julia | TODO(Jeush): confirm |

## Libraries and tools

Versions are in `pom.xml`, `server-api/pom.xml`, `web-client/package.json`, `compose.yaml` and `hosting/Dockerfile.render`.

| Library or tool | Used for |
|---|---|
| Spring Boot 4.1.1 (web MVC, Security, JDBC, test) | REST API, sessions, CSRF, database access, tests |
| Flyway with the PostgreSQL module | Schema migrations |
| PostgreSQL 17 and its JDBC driver | Database |
| Jackson 3 (BOM 3.1.7) | JSON |
| Apache POI (`poi-ooxml` 5.5.1) and Commons IO | Excel professor report |
| Apache Tomcat embedded 11.0.26 | Web server, version pinned for security fixes |
| JUnit, Testcontainers PostgreSQL | Tests that need Docker |
| Apache Maven Wrapper (Maven 3.9.16) | Build |
| Node.js 24 built-in test runner | Web unit tests; the web app has no npm dependencies to install |
| Docker, Render, Supabase | Local database, hosting, hosted database |
| Eclipse Temurin JDK/JRE 21 and Node 24 images | Docker build and runtime image |

## Limits of this disclosure

- Nothing here claims the app is fully accessible or has been tested by real users. The live deployment and its smoke test are still to be done.
- The history in this repository starts on the day the team began copying. Commit dates show when files were added here, not when the reference code was written.
- The reference application is outside this repository; its full AI disclosure is in `fairshare-java-draft/ai-reference/AI_USAGE.md`.
