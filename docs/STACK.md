# Owlibi stack and repository structure

Summary of the tools this repository uses and why. Versions come from the POM files, the Maven wrapper properties, `web-client/package.json`, `compose.yaml` and `hosting/Dockerfile.render`; those files are authoritative. This is not a test report. The code was copied from an AI-assisted reference application (see [AI_USAGE.md](AI_USAGE.md)). Setup and run steps are in the [README](../README.md).

## Stack

| Area | Choice | Why |
|---|---|---|
| Language / JDK | Java 21 (`maven.compiler.release` 21); the enforcer plugin requires JDK 21 or newer | The OOP-focused backend of the course project. |
| API | Spring Boot 4.1.1, `spring-boot-starter-webmvc` | REST controllers, one service for site and API. |
| Auth | Spring Security (Boot BOM), BCrypt, server-side sessions, HttpOnly cookie, CSRF token | The API is authoritative for identity and permissions. |
| Persistence | Spring JDBC with parameterized SQL, Flyway migrations (`flyway-database-postgresql`), PostgreSQL JDBC driver | Plain SQL keeps domain rules visible; no ORM layer. |
| Database | PostgreSQL 17.11 (Docker image pinned by digest in `compose.yaml`) | Local development and tests; Supabase Free PostgreSQL when hosted. |
| JSON | Jackson 3 BOM 3.1.7 | Overrides the version Boot manages. |
| Workbook | Apache POI `poi-ooxml` 5.5.1, Commons IO 2.21.0 | Builds the four-sheet professor `.xlsx` report. |
| Web client | Plain HTML, CSS and JavaScript ES modules in `web-client/src`, assembled by `node web-client/scripts/build.mjs` (Node 24+, no `npm install`) | No framework; Spring serves the built files. |
| Build | Maven 3.9.16 through the Maven Wrapper (3.3.4) | `./mvnw` downloads Maven; no global install. |
| Tests | JUnit and Spring Boot test (Boot BOM), Testcontainers PostgreSQL (needs Docker), Node's built-in `node:test` | Integration tests run against a real database. |
| Hosting | One Render Free Docker web service for site and API, Supabase Free PostgreSQL | ₱0 budget. See the [deployment guide](DEPLOY_RENDER_SUPABASE.md). |

`web-client/package.json` still declares a Playwright dev dependency and a `test:browser` script, but this repository has no browser test folder and no `npm install` step, so browser tests are not part of the current checks.

Not used: Swing, a native installer, React or Next.js, a Node business backend, browser-to-database access, custom cryptography, native mobile apps, offline writes, Google API integration and WebSockets.

## Structure

```text
owlibi/
  README.md, pom.xml, mvnw, mvnw.cmd, compose.yaml, render.yaml
  docs/                  architecture, stack, deployment, AI usage
  hosting/               Render Dockerfile, start script, Supabase bootstrap SQL
  scripts/db/            database role bootstrap for local PostgreSQL
  server-api/            DTO/controller -> service -> SQL -> PostgreSQL
    src/main/java/dev/owlibi/reference/<feature packages>
    src/main/resources/  application*.yaml, db/migration, templates
    src/test/java/       unit and integration tests
  web-client/            browser API/CSRF adapter, view model, HTML/CSS
    src/, scripts/, tests/unit/
```

Use feature packages with small controller, service and repository boundaries. The server owns domain rules; the browser validates only for feedback. Keep persistence entities separate from API DTOs and browser presentation models. Templates are data (`catalogue.json`), not one Task subclass per template. [ARCHITECTURE.md](ARCHITECTURE.md) explains how the packages talk to each other.

## Dependency security note

Spring Boot 4.1.1 manages Tomcat 11.0.24, which has published security advisories. `pom.xml` therefore sets `owlibi.tomcat.version` to 11.0.26 for the three embedded Tomcat artifacts, and imports the Jackson BOM at 3.1.7. Keep these overrides when upgrading, and check current advisories again before relying on them for a new deployment. A pinned version or a passing build does not prove the absence of vulnerabilities.

Official sources: [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Spring support policy](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions), [PostgreSQL versioning](https://www.postgresql.org/support/versioning/), [Apache POI spreadsheets](https://poi.apache.org/components/spreadsheet/), [Render free limits](https://render.com/docs/free), [Tomcat 11 security](https://tomcat.apache.org/security-11.html).
