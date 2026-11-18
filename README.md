# Owlibi

Owlibi is a browser-based app for student teams to plan and track class projects. Teams start from a blank setup or a project template, assign tasks, review work, and export a report for their professor.

The contribution view shows each member's share of agreed effort points from tasks the leader has marked **Done**. It is a transparent summary of recorded work. It is not a verified measure of effort, a fairness verdict, or a grade.

## Features (v1 scope)

- Create projects from a blank setup or an editable, data-driven template
- Up to 6 members per project; one account can belong to multiple projects
- Task flow: To Do → In Progress → Review → Done (only the leader can mark Done)
- Assignees accept their assignment and agreed points before work starts
- Shared-task points are split equally among assignees; only Done tasks count
- Workload warnings based on open effort and due dates
- Leader exports a four-worksheet `.xlsx` professor report
- Activity history for material changes (a record, not tamper-proof evidence)

**Out of scope for v1:** instructor accounts, grading integrations, offline writes, native mobile apps, WebSockets.

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot REST API |
| Database | PostgreSQL |
| Frontend | Plain HTML / CSS / JavaScript (served by the Java app) |
| Build | Maven |
| Auth | HttpOnly session cookies, CSRF protection |

The backend is the source of truth for identity, permissions, task transitions, timestamps, and score calculations. The browser never talks to the database directly.

## Prerequisites

- Git
- JDK 21 or newer
- Node.js 24 or newer (builds the web files; no `npm install` needed)
- Docker (runs PostgreSQL; the integration tests also need it)
- A modern browser (Chrome or Edge recommended)

Maven is not needed: the included `mvnw` wrapper downloads it.

Check your setup:

```bash
java -version
node -v
git --version
docker --version
```

## Getting started

### 1. Clone

```bash
git clone https://github.com/jeushdev/owlibi.git
cd owlibi
```

### 2. Configure environment

Copy the example file and fill in your own values. Never commit `.env`.

```bash
cp .env.example .env
```

| Variable | Purpose |
|---|---|
| `OWLIBI_ADMIN_PASSWORD` | Password of the database admin user (used only to create the database) |
| `OWLIBI_MIGRATION_PASSWORD` | Password of the role that runs schema migrations |
| `OWLIBI_RUNTIME_PASSWORD` | Password of the limited role the app uses |
| `OWLIBI_DB_PORT` | Optional. Database port on your computer (default `55580`) |
| `OWLIBI_API_PORT` | Optional. App port (default `8080`) |

Use three different random passwords. They are only read the first time the database is created, so keep `.env` with the database volume.

### 3. Start PostgreSQL

```bash
docker compose --env-file .env up -d db
```

### 4. Build the web files and run the app

```bash
node web-client/scripts/build.mjs
set -a; . ./.env; set +a
./mvnw -f server-api/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
```

On Windows use `mvnw.cmd` and set the `.env` values as environment variables in PowerShell first. Open <http://127.0.0.1:8080/>. `http://127.0.0.1:8080/api/v1/health` should answer `{"status":"UP"}`. Press `Ctrl+C` to stop the app; `docker compose --env-file .env stop db` stops the database. Your data is kept.

### 5. Run the tests

```bash
node --test web-client/tests/unit
./mvnw -f server-api/pom.xml verify
```

## Project structure

```text
owlibi/
  README.md
  pom.xml, mvnw, mvnw.cmd   Maven parent build and wrapper (no global Maven needed)
  compose.yaml              local PostgreSQL (and API) containers
  render.yaml               Render Blueprint for the hosted service
  server-api/               Spring Boot REST API
    src/main/java/          controller -> service -> SQL (feature packages)
    src/main/resources/     config, db/migration (Flyway), templates catalogue
    src/test/java/          unit and integration tests
  web-client/               browser app (plain HTML, CSS, JavaScript)
    src/                    source files built into the Spring static resources
    scripts/                build.mjs and check.mjs
    tests/unit/             Node unit tests
  hosting/                  Render Dockerfile, start script, Supabase bootstrap SQL
  scripts/db/               local database role bootstrap
  docs/                     architecture, stack, deployment, AI usage, mockup, screenshots
```

Design rules:

- Keep controllers, services, and repositories small and separate.
- Keep API DTOs separate from database entities.
- Keep templates in data, not one Task subclass per template.
- Enforce permissions and domain rules on the server, not only in the UI.
- Use parameterized SQL and versioned migrations. Never hand-edit data to fix a bug.

## Workflow

- `main` is always in a working state. Do work on feature branches and merge through pull requests.
- Commit small, focused changes with clear messages.
- Run the tests before opening a pull request.
- Use synthetic accounts and data only. No real student credentials.
- Never commit secrets, `.env`, or database credentials.

## Roadmap

- [x] Project setup, build, database connection
- [x] Accounts and sign-in
- [x] Projects and membership
- [x] Tasks, assignment, and acceptance
- [x] Review and Done flow
- [x] Templates and settings
- [x] Contribution summary and workload warnings
- [x] `.xlsx` professor report export
- [ ] Testing, accessibility pass, and release

## Academic integrity

The code in this repository was copied, in small commits, from a reference application built with Claude Code (an AI assistant). It was not written from scratch by the team. [docs/AI_USAGE.md](docs/AI_USAGE.md) says what was copied, who did what, and which libraries are used. Every team member must be able to explain the code they submit.

## Course details

- Team: 3 of a Kind
- Members: Alvarez, John Allen P., Bantayaon, Jeush Samuel L., Fernando, Julia M.
- Course / section: OOP 101, CS2103
- Instructor: Ms. Fatima Marie Agdon