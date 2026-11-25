<p align="center">
  <img src="docs/images/owlibi.png" width="80" alt="Owlibi's owl with a magnifying glass">
</p>

<h1 align="center">𐔌 .⋮ Owlibi .ᐟ ₊ ꒱</h1>
<p align="center"><strong>A group-project alibi, one approved task at a time.</strong></p>
<p align="center">
  <strong>OOP 101 · CS2103</strong><br>
  3 of a Kind<br>
  John Allen P. Alvarez · Jeush Samuel L. Bantayaon · Julia M. Fernando
</p>

## ‧₊˚ ┊ Overview

When a professor asks who did what, the answer can be scattered across a group chat. Owlibi gives student teams a shared record of who agreed to each task, what was approved, and how the completed points were split.

```text
Accept the task → Start work → Submit for review → Leader approves → Points count
```

### Problem and Objectives

**The problem.** In most student group projects, tasks are handed out in a group chat. Nobody records who agreed to what, work gets called "done" without anyone checking it, and when the deadline comes the team argues over who contributed. The professor only sees the final output and has no fair way to tell the members' shares apart.

**Who it is for.** Student teams of two to six members, led by one project leader. The professor receives the exported report and does not need an account.

**Objectives.**
1. Record each task's agreement (who is assigned and how many effort points it is worth) before work starts.
2. Count only work that the leader has reviewed and approved.
3. Warn the team early about uneven workloads and approaching deadlines.
4. Give the professor a clear, exportable record of each member's completed work.

**Out of scope for this version.** Instructor accounts, grading integrations, offline editing, native mobile apps, and live push updates.

## ‧₊˚ ┊ Project Features

1. **Set up the project.** Start from scratch or use an editable template. Invite up to six people total, including the project leader.
2. **Agree before starting.** Add a task, assign one or more members, and set its effort points. Each assignee accepts the current agreement before work starts. If the effort points or assignees change, every current assignee must accept the revised agreement before the task can advance.
3. **Review before counting.** Assignees submit work for review. The project leader can approve the task or send it back for another pass.
4. **Check the workload.** See where open effort is concentrated and which deadlines are approaching. The activity history records changes such as task edits and approvals.
5. **Share the record.** Completed points are split equally among a task’s assignees. The leader can export an Excel professor report with a project summary, member contributions, task details, workload, activity history, and project rules.

> If two members share an approved 8-point task, each gets 4 completed points. The percentage on **Completed points** is that member’s share of the project’s approved effort points. The team supplies those estimates.

## ‧₊˚ ┊ OOP Principles Applied

### 🦉 Why Object-Oriented Programming

- **The problem is made of things with rules.** Projects, members, tasks, agreements, and reports each have their own data and their own rules. Objects let each concept keep its data and rules together instead of spreading checks across the whole program.
- **The rules must not be bypassed.** Because task changes are encapsulated in `TaskService`, the browser cannot skip review or approve its own work. Every request goes through the same checks.
- **Similar jobs share one skeleton.** Every move a task can make (start, submit, approve, return, reopen, cancel) extends one abstract class, `TaskTransition`. Every sheet of the Excel report extends another, `ReportSheet`. Each subclass supplies only what makes it different, which replaces long `switch` and `if` chains with inheritance and polymorphism.
- **Fixed sets of values are types, not strings.** Task statuses, member roles, and due-date states are enums (`TaskStatus`, `ProjectRole`, `DueState`) that carry their own behaviour, so a typo is a compile error instead of a hidden bug.
- **Changes stay local.** The report layout, the project templates, and the database code each live behind their own class, so one can change without breaking the others.

The problem analysis, use cases, and the design decisions we made (with the options we turned down) are in [docs/DESIGN.md](docs/DESIGN.md).

### 💡 Abstraction

`TaskTransition` is an **abstract class** we wrote for the task workflow. It holds what every move between statuses has in common (an action name, the statuses it can start from, and the status it leads to). It also declares three `abstract` methods that each move must answer: `openToAssignees()`, `requiresReason()`, and `requiresAcceptance()`. `TaskService` only works with the abstract type, so it never needs to know which of the six moves it is handling. [TaskTransition.java](server-api/src/main/java/dev/owlibi/tasks/TaskTransition.java#L22-L65)

The Excel report uses abstraction twice. `ReportSection` is an **interface**: the contract "a part of the report that has a label and can write itself". `ReportWorkbook` keeps a `List<ReportSection>` and never sees Apache POI cells, styles, or sheet layouts. `ReportSheet` is the **abstract class** that implements it and declares the two `abstract` hooks every sheet must fill in, `subtitle()` and `writeRows()`. [ReportSection.java](server-api/src/main/java/dev/owlibi/reports/ReportSection.java) · [ReportSheet.java](server-api/src/main/java/dev/owlibi/reports/ReportSheet.java#L29-L100)

### 💊 Encapsulation

`TaskService` keeps its collaborators in `private final` fields and the database shape of tasks and assignments in `private record`s (`Stored`, `Assigned`) that no other class can see. Other classes can change a task only through public methods such as `accept()` and `transition()`. Inside, `transition()` runs one private check per business rule, in order: `requireAllowedToRequest`, `requireCurrentVersion`, then `requireEveryoneAccepted`. It then saves the new status and its activity entry in one transaction. Because the rules live behind these methods, no controller can move a task to Done without passing them. [TaskService.java](server-api/src/main/java/dev/owlibi/tasks/TaskService.java#L334-L376)

`TaskTransition` and `ReportSheet` protect their own data the same way. Their fields are `private final`, and their key methods (`next()`, `write()`) are `final`, so no subclass can change the shared logic. Enums such as `TaskStatus` keep their `label` private behind a getter, and immutable `record`s such as [`Cursor`](server-api/src/main/java/dev/owlibi/web/Cursor.java) and [`ApiError`](server-api/src/main/java/dev/owlibi/web/ApiError.java) cannot change after they are created. Input is validated on the server by [`Fields`](server-api/src/main/java/dev/owlibi/web/Fields.java) before any object is built.

### 🧬 Inheritance

We wrote two class hierarchies. Six classes extend `TaskTransition`, one for each move a task can make:

```java
abstract sealed class TaskTransition ...
final class Start        extends TaskTransition  // To do → In progress   (assignees may request it)
final class SubmitReview extends TaskTransition  // In progress → Review  (assignees may request it)
final class Approve      extends TaskTransition  // Review → Done         (leader only)
final class Return       extends TaskTransition  // Review → In progress  (leader, reason required)
final class Reopen       extends TaskTransition  // Done → In progress    (leader, reason required)
final class Cancel       extends TaskTransition  // not Done → Cancelled  (leader, reason required)
```

Six more extend `ReportSheet`, one for each tab of the professor report: `ProjectSummarySheet`, `MemberContributionsSheet`, `TaskDetailsSheet`, `WorkloadSheet`, `ActivityHistorySheet`, and `ProjectRulesSheet`. Each one passes its own column widths to the parent constructor and inherits the title band, headers, alternating row colours, and row limit.

Both parents are `sealed`, so these are the only subclasses Java allows. [TaskTransition.java](server-api/src/main/java/dev/owlibi/tasks/TaskTransition.java#L68-L138) · [WorkloadSheet.java](server-api/src/main/java/dev/owlibi/reports/WorkloadSheet.java)

We also inherit from library classes where that is what they are designed for. `SessionBoundaryFilter`, `SessionEpochFilter`, and `HostedOriginFilter` extend Spring's `OncePerRequestFilter`. [`ApiFailure`](server-api/src/main/java/dev/owlibi/web/ApiFailure.java) extends `RuntimeException`, so a broken rule can be thrown like any Java exception while still carrying an HTTP status and an error code.

### 🎭 Polymorphism

**Method overriding (runtime).** In `TaskService.transition()`, the variable `move` has the type `TaskTransition`. When the service calls `move.openToAssignees()`, `move.requiresAcceptance()`, or `move.firstStart(...)`, Java runs the version from the actual object at runtime. `Approve` answers that assignees may not request it, while `Start` answers that they may. `Start` is also the only class that overrides `firstStart()`, which records when work on the task first began. The other five inherit the default, which leaves that time unchanged. One method call gives six different behaviours, with no `switch` on action names. [TaskService.java](server-api/src/main/java/dev/owlibi/tasks/TaskService.java#L334-L356)

The report works the same way. `ReportWorkbook` loops over `List<ReportSection>` and calls `section.write(...)` on each. The inherited `final write()` is a **Template Method**: it always writes the title and headers, then calls `writeRows()`, and Java runs the version from that sheet's own class. [ReportWorkbook.java](server-api/src/main/java/dev/owlibi/reports/ReportWorkbook.java#L72-L75)

**Method overloading (compile time).** `Fields` has two `text(...)` methods with the same name and different parameter lists. The five-argument version controls trimming and whether the field is required. The three-argument version is the common case for optional free text such as a task description. The compiler picks the right one from the arguments. [Fields.java](server-api/src/main/java/dev/owlibi/web/Fields.java#L41-L69) `ApiFailure` also has overloaded constructors.

**Interfaces.** `ReportSheet` implements our own `ReportSection` interface. [`ReportWorkspace`](server-api/src/main/java/dev/owlibi/reports/ReportWorkspace.java) `implements AutoCloseable`, so `try (var workspace = new ReportWorkspace(...))` in `ReportController` deletes the report's temporary files automatically, the same way Java closes a file or a stream.

### 🏗️ Overall OOP Design

- **One package per feature.** `projects`, `tasks`, `summaries`, `reports`, and `security` each follow controller → service → SQL, so every class has one job.
- **Inheritance only for real "is-a" relationships.** Every `Approve` *is a* `TaskTransition`, and every `WorkloadSheet` *is a* `ReportSheet`. Elsewhere we use composition: services receive what they need through their constructors (`TaskService` gets a database template, a `Clock`, and a `SnapshotService`), and injecting the `Clock` lets tests control time.
- **Interface, abstract class, or enum, each where it fits.** An interface (`ReportSection`) is a pure contract. An abstract class (`TaskTransition`, `ReportSheet`) adds shared fields and code. An enum (`TaskStatus`, `ProjectRole`, `DueState`) is a fixed set of values with behaviour.
- **Open to extension.** A new kind of task move means one new `TaskTransition` subclass, and a new report tab means one new `ReportSheet` subclass, without editing the code that uses them.
- **Data, not subclasses, where behaviour is the same.** Project templates differ only in their data, so they live in `catalogue.json` instead of one class per template.
- **Separate shapes for separate layers.** API objects (`TaskDtos`, `SummaryDtos`), database rows (private records), and browser view models are kept apart, so changing one layer does not break the others.

## ‧₊˚ ┊ Project Structure

```text
owlibi/
├── server-api/                        Java 21 + Spring Boot server
│   └── src/
│       ├── main/java/dev/owlibi/
│       │   ├── tasks/                 TaskTransition and its 6 moves, TaskStatus, TaskService
│       │   ├── reports/               ReportSection, ReportSheet and its 6 sheets, ReportWorkbook
│       │   ├── projects/              Projects, invitations, project rules, ProjectRole
│       │   ├── summaries/             Completed points and workload, DueState
│       │   ├── snapshots/             One consistent read of a project for summaries and reports
│       │   ├── security/              Sign-in, sessions, and request filters
│       │   ├── templates/             Project templates loaded from JSON
│       │   ├── web/                   Validation (Fields), errors (ApiFailure), JSON bodies
│       │   └── config/ health/ hosting/
│       ├── main/resources/            Settings, SQL migrations V1–V4, template catalogue
│       └── test/java/                 JUnit and Testcontainers tests
├── web-client/
│   ├── src/                           HTML, CSS, and JavaScript
│   ├── scripts/                       Browser asset build
│   └── tests/unit/                    Web unit tests
├── docs/                              Design notes, UML, architecture, AI usage
├── hosting/                           Render and Supabase setup
└── compose.yaml                       Local PostgreSQL setup
```

### 🗺️ UML Class Diagram

A simplified view of the main classes. Hollow triangles show inheritance, dashed lines with hollow triangles show an interface being implemented, and plain arrows show which class uses which. The full diagrams, with fields, methods and visibility, are in [docs/UML.md](docs/UML.md).

```mermaid
classDiagram
    direction LR
    class TaskTransition {
        <<abstract>>
        -String action
        -TaskStatus target
        -Set~TaskStatus~ sources
        +next(TaskStatus) Optional~TaskStatus~
        +firstStart(current, now) Instant
        +openToAssignees()* boolean
        +requiresReason()* boolean
        +requiresAcceptance()* boolean
    }
    class TaskStatus {
        <<enumeration>>
        TODO
        IN_PROGRESS
        REVIEW
        DONE
        CANCELLED
        -String label
        +isClosed() boolean
        +countsAsRemaining() boolean
        +label() String
    }
    class TaskService {
        -JdbcTemplate jdbc
        -Clock clock
        -SnapshotService snapshots
        +accept()
        +transition()
    }
    class ReportSection {
        <<interface>>
        +label() String
        +write(book, styles, budget)
    }
    class ReportSheet {
        <<abstract>>
        -String label
        -int[] columnWidths
        +write(book, styles, budget)
        #subtitle()* String
        #writeRows()*
    }
    TaskTransition <|-- Start
    TaskTransition <|-- SubmitReview
    TaskTransition <|-- Approve
    TaskTransition <|-- Return
    TaskTransition <|-- Reopen
    TaskTransition <|-- Cancel
    TaskTransition --> TaskStatus
    TaskController --> TaskService
    TaskService ..> TaskTransition
    TaskService --> ProjectRole
    TaskService --> SnapshotService
    ReportSection <|.. ReportSheet
    ReportSheet <|-- ProjectSummarySheet
    ReportSheet <|-- MemberContributionsSheet
    ReportSheet <|-- TaskDetailsSheet
    ReportSheet <|-- WorkloadSheet
    ReportSheet <|-- ActivityHistorySheet
    ReportSheet <|-- ProjectRulesSheet
    ReportController --> ReportWorkbook
    ReportWorkbook ..> ReportSection
    RuntimeException <|-- ApiFailure
    AutoCloseable <|.. ReportWorkspace
```

## ‧₊˚ ┊ External Libraries and Tools

| Library or tool | Version | What Owlibi uses it for |
| --- | --- | --- |
| Java | 21 | Language and runtime for the server |
| Spring Boot (Web MVC, Security, JDBC) | 4.1.1 | REST API, sign-in sessions, CSRF protection, database access |
| PostgreSQL and its JDBC driver | 17 | Stores accounts, projects, tasks, and activity history |
| Flyway | Managed by Spring Boot | Versioned database migrations (`V1` to `V4`) |
| Jackson | 3.1.7 | Converts Java objects to and from JSON |
| Apache POI (`poi-ooxml`) | 5.5.1 | Builds the Excel professor report |
| Apache Commons IO | 2.21.0 | File helpers used while writing the report |
| Embedded Apache Tomcat | 11.0.26 | Web server inside the Spring Boot app |
| JUnit and Testcontainers | Managed by Spring Boot | Unit and integration tests against a real PostgreSQL |
| Node.js | 24 | Builds the browser files and runs the web unit tests (no npm packages) |
| Maven Wrapper, Docker, Render, Supabase | | Build, local database, and hosting |

## ‧₊˚ ┊ How to Run

Install Git, JDK 21+, Node.js 24+, and Docker with Compose. The repository includes the Maven Wrapper.

```bash
git clone https://github.com/jeushdev/owlibi.git
cd owlibi
cp .env.example .env
```

Set distinct values for `OWLIBI_ADMIN_PASSWORD`, `OWLIBI_MIGRATION_PASSWORD`, and `OWLIBI_RUNTIME_PASSWORD` in `.env` before starting PostgreSQL for the first time.

```bash
docker compose --env-file .env up -d db
node web-client/scripts/build.mjs
set -a
. ./.env
set +a
./mvnw -f server-api/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
```

Open [Owlibi on localhost](http://127.0.0.1:8080/) using the default port. On Windows, load the `.env` values into PowerShell and use `.\mvnw.cmd` for the Maven command.

## ‧₊˚ ┊ Sample Output and Highlights

**Highlights**

- 🔁 **Two class hierarchies we wrote ourselves:** 6 task moves under `TaskTransition` and 6 report sheets under `ReportSheet`.
- 🧩 **One interface, three enums:** `ReportSection`, `TaskStatus`, `ProjectRole`, and `DueState`.
- 🛡️ **Rules the browser cannot skip:** every task move is checked on the server, and every change is logged in the same transaction.
- 📊 **A six-sheet Excel report** the professor can open without an account.
- ✅ **188 automated tests:** 155 Java tests (many against a real PostgreSQL database) and 33 web unit tests.

**Tasks · The team’s working list**

The team used Owlibi to track its own OOP final project. The Tasks table shows what was assigned, who accepted, and what was due.

<img src="docs/images/screen-tasks.png" width="100%" alt="Owlibi Tasks table showing the OOP final project, acceptance, assignees, effort points, and due dates">

**Completed points · The approved work**

Three approved tasks add up to 12 completed points in this screenshot. The allocation table shows where the totals come from. The account shown as `jeushdev` has a 5-point task and a 2-point task, giving it 7 of 12 points (58.3%). The project leader can export the professor report from this view.

<img src="docs/images/screen-completed-points.png" width="100%" alt="Completed points dashboard showing 12 points across three approved tasks and the professor report export button">

**Members · Who was on the project**

The roster shows the project leader, the members, and how many places are available.

<img src="docs/images/screen-members.png" width="100%" alt="Owlibi Members page showing three team members and their project roles">

## ‧₊˚ ┊ Thank You

Thank you for reading all the way down here! 🦉💌

Owlibi started from a question every group has heard: *"So… who actually did what?"* We built a small owl to keep the receipts, so teams can spend less time arguing about credit and more time making things together.

If Owlibi ever settles a group-chat debate for you, it has done its job. Hoot hoot! ✨

## ‧₊˚ ┊ Team Members

<table>
  <tr>
    <th>Photo</th>
    <th>Team member</th>
    <th>Project role and area</th>
  </tr>
  <tr>
    <td><img src="docs/images/allen.jpg" width="96" height="96" alt="John Allen Alvarez"></td>
    <td><strong>John Allen P. Alvarez</strong><br><a href="https://github.com/jhnllnlvrz">@jhnllnlvrz</a></td>
    <td>Member · Tasks, summaries, and Excel reports</td>
  </tr>
  <tr>
    <td><img src="docs/images/jeush.jpg" width="96" height="96" alt="Jeush Samuel Bantayaon"></td>
    <td><strong>Jeush Samuel L. Bantayaon</strong><br><a href="https://github.com/jeushdev">@jeushdev</a></td>
    <td>Project leader · Repository setup, accounts, projects, and hosting</td>
  </tr>
  <tr>
    <td><img src="docs/images/julia.jpg" width="96" height="96" alt="Julia Fernando"></td>
    <td><strong>Julia M. Fernando</strong><br><a href="https://github.com/molii-julia">@molii-julia</a></td>
    <td>Member · Browser client and frontend tests</td>
  </tr>
</table>

## ‧₊˚ ┊ Acknowledgment

Thank you to **Ms. Fatima Marie Agdon** for guiding us through OOP 101 and to our classmates for their feedback during the project.

---

**AI usage.** We first built an early version of Owlibi with AI tools (Codex and Claude Code), then used it as a reference while building this version ourselves. [docs/AI_USAGE.md](docs/AI_USAGE.md) lists the tools and each member's part of the project.
