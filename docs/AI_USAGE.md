# AI usage disclosure

Owlibi · team 3 of a Kind · OOP 101

This file lists the AI tools we used, what we used them for, and each member's part of the project.

## Summary

- **We started by prototyping.** We first built an early version of Owlibi with AI coding assistants (**Codex** and **Claude Code**) to explore the idea: what a task agreement looks like, how review and approval should flow, and what the professor report needs. That prototype lives outside this repository, in `fairshare-java-draft/ai-reference/`.
- **Then we built this version ourselves**, using the prototype as a reference. We set up the repository, split the work by area, and brought each part in through small commits and pull requests. AI tools helped along the way, mainly for tests and for the refactor described below.
- No other AI tools were used.

## Where AI helped

| What | Tool | Notes |
|---|---|---|
| Early prototype used as a reference | Codex, Claude Code | Outside this repository |
| `ProjectRulesTest`, `ProjectRulesNormalizeTest`, `TemplateCatalogTest`, `CursorTest`, `FieldsTest`, `DatabaseReadinessServiceIT` | AI-assisted | Written by John |
| `ProjectSetupIT`, `ReportResourceIT`, `StaticAssetsIT`, `RoutingBoundaryIT` | AI-assisted | Finished by Jeush on the `jeushdev/finish` branch |
| Interactive `/mockup/` pages and the owl logo (`web-client/src/brand-logo.png`) | AI-generated | Design references |
| OOP refactor before the presentation (see below) | Claude Code | Directed by Jeush |

## Who did what

| Person | Area |
|---|---|
| Jeush Samuel Bantayaon (project leader) | Repository setup, Maven and PostgreSQL build files, app entry point, config, health, web helpers, accounts and sign-in security, snapshots, projects, hosting on Render and Supabase, `AuthBoundaryIT`, `ProjectBoundaryIT`, `ReadinessIT`, `HostedPreparationIT`, the README, and reviewing and merging pull requests. |
| John Allen Alvarez | Database migrations, template catalogue, security filters, tasks and summaries, the Excel professor report, test support and persistence tests, `SummaryIT`, `TaskBoundaryIT`, `TaskEditingIT`, `ProfessorReportIT`, and the first architecture, stack and deployment docs. |
| Julia Fernando | The browser app: build scripts, API client, session handling, view model, styles, UI rendering, the main script, and the web unit tests. |

## OOP refactor before the presentation (November 2026)

On the `jeushdev/oop-strengthen` branch, Jeush used **Claude Code** to strengthen the server code against the OOP criteria without changing what the app does. The same 155 tests pass before and after.

- Added the `TaskStatus`, `ProjectRole` and `DueState` enums and used them in place of status and role strings.
- Split the Excel report into the `ReportSection` interface, the abstract `ReportSheet` class and six sheet subclasses.
- Added the `Fields.text(value, field, limit)` overload.
- Reformatted the dense service code to one statement per line, split `TaskService.transition()` into named rule checks, and renamed the package from `dev.owlibi.reference` to `dev.owlibi`.
- Wrote [DESIGN.md](DESIGN.md), [UML.md](UML.md) and the updated OOP sections of the README.

## Who presents what

| Person | Files they explain |
|---|---|
| Jeush | Accounts and sign-in security, snapshots, projects, hosting |
| John | Tasks and summaries, the Excel report |
| Julia | `api.js`, `session.js`, `model.js`, `ui.js`, `main.js`, `styles.css` and the web unit tests |

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

## Notes

- The commit history in this repository starts when we began building this version. The earlier prototype work is not part of it.
- The prototype's own AI notes are in `fairshare-java-draft/ai-reference/AI_USAGE.md`, outside this repository.
