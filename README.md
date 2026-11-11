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
- Maven 3.9+ (or the Maven Wrapper, once added)
- PostgreSQL 17, or Docker to run it in a container
- A modern browser (Chrome or Edge recommended)

Check your setup:

```bash
java -version
mvn -version
git --version
docker --version   # only if using Docker for PostgreSQL
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
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://127.0.0.1:5432/owlibi` |
| `DB_USER` | Database user |
| `DB_PASSWORD` | Database password |
| `SERVER_PORT` | App port (default `8080`) |

### 3. Start PostgreSQL

With Docker:

```bash
docker run --name owlibi-db -e POSTGRES_DB=owlibi \
  -e POSTGRES_USER=owlibi -e POSTGRES_PASSWORD=[choose-a-password] \
  -p 5432:5432 -d postgres:17
```

### 4. Run the app

```bash
mvn spring-boot:run
```

Open <http://127.0.0.1:8080/>. Press `Ctrl+C` in the terminal to stop it. Your database data is kept.

### 5. Run the tests

```bash
mvn test
```

## Project structure (planned)

```text
owlibi/
  README.md
  pom.xml
  .env.example
  docs/                 requirements, decisions, API contract, data model
  src/main/java/        controller -> service -> repository (feature packages)
  src/main/resources/
    db/migration/       schema migrations
    static/             HTML, CSS, JavaScript
  src/test/java/        unit and integration tests
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

- [ ] Project setup, build, database connection
- [ ] Accounts and sign-in
- [ ] Projects and membership
- [ ] Tasks, assignment, and acceptance
- [ ] Review and Done flow
- [ ] Templates and settings
- [ ] Contribution summary and workload warnings
- [ ] `.xlsx` professor report export
- [ ] Testing, accessibility pass, and release

## Academic integrity

AI tools and third-party libraries may be used as the instructor's policy allows. Every team member must be able to explain the code they submit. Disclose AI and library use in `docs/AI_USAGE.md`.

## Course details

- Team: [team name]
- Members: [names]
- Course / section: OOP 101, [section]
- Instructor: [instructor]