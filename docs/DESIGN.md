# Owlibi design notes

Problem analysis, the reasons behind the object-oriented design, and the decisions we made along the way, including the options we turned down. The [README](../README.md) shows how each OOP principle appears in the code. This file explains *why* the code has that shape.

## 1. Problem analysis

### Who uses Owlibi

| Actor | Goal | Account? |
| --- | --- | --- |
| **Project leader** | Split the work fairly, review what members hand in, and show the professor who did what. | Yes, exactly one per project |
| **Member** | Know what they agreed to do, and get credit only for work that was approved. | Yes, up to five per project |
| **Professor** | Read a clear record of each member's approved contribution. | No, they receive the exported Excel report |

### Use cases

| # | Use case | Actor | Rule the system enforces |
| --- | --- | --- | --- |
| UC1 | Create a project from scratch or from a template | Leader | Template data is copied, so later template edits never change an existing project. |
| UC2 | Invite members | Leader | At most six people in total. The invitation token is stored only as a SHA-256 hash and expires. |
| UC3 | Create or edit a task (title, points, assignees, due date) | Leader | Changing the title, description, points or assignees creates a new *agreement version*, and every assignee must accept again. |
| UC4 | Accept the task agreement | Member | A member can accept only the current version of a task assigned to them. |
| UC5 | Start work → submit for review | Member (assignee) | Allowed only after every assignee accepted the current agreement. |
| UC6 | Approve, return, reopen or cancel | Leader | Return, reopen and cancel need a written reason. Only *approved* (Done) tasks count toward points. |
| UC7 | View completed points and workload | Everyone in the project | Points are split equally among a task's assignees. A member is flagged as overloaded when their open points go above the project's threshold. |
| UC8 | Export the professor report | Leader | All six sheets are built from one consistent database snapshot. |
| UC9 | Read the activity history | Everyone in the project | Each change is logged in the same transaction as the change itself, so the history cannot drift from the data. |

### What makes the problem hard

1. **Rules have to hold no matter who calls the API.** A member could send a hand-crafted request to approve their own task. The rules therefore live on the server, inside objects the browser cannot reach.
2. **One action means different things in different states.** "Approve" is valid only in Review, and only for the leader. "Start" is valid only in To do, and only after acceptance. Spread across `if` statements, this quickly becomes unreadable.
3. **Two people can edit at the same time.** Each task carries a version number, and a stale write gets `409 STALE_VERSION` instead of silently overwriting the other person's change.

## 2. Why an object-oriented approach

| Need | Procedural answer | Object-oriented answer we chose |
| --- | --- | --- |
| Every task move has its own permission and requirement rules | One `switch (action)` repeated in every check | One `TaskTransition` subclass per move. The service asks the object, and Java picks the right answer. |
| Statuses and roles must never be misspelled | String constants compared with `.equals("DONE")` | `TaskStatus` and `ProjectRole` enums with behaviour (`isClosed()`, `isLeader()`). A typo is a compile error. |
| Six report sheets share a layout but differ in content | One 400-line method full of sheet-specific branches | The `ReportSection` interface, the abstract `ReportSheet` class, and one subclass per sheet. |
| Rules must not be bypassed | Any code can update the `tasks` table | Only `TaskService` methods change tasks. Their helpers and row records are `private`. |
| Report temporary files must always be deleted | Manual cleanup in every `catch` | `ReportWorkspace implements AutoCloseable`, used in try-with-resources. |

## 3. Design decisions

| Decision | Options considered | What we chose and why |
| --- | --- | --- |
| How to model the six task moves | (a) `switch` on the action name, (b) an enum with fields, (c) an abstract class with subclasses | **(c)** `abstract sealed class TaskTransition`. The moves share data (name, source statuses, target status) *and* differ in behaviour. `Start` alone overrides `firstStart()`. An abstract class can hold the shared fields and a `final next()` method, which an interface cannot. `sealed` limits the subclasses to exactly the six moves. |
| How to model statuses and roles | Strings or enums | **Enums.** The set of values is fixed, and the enum can carry behaviour, for example whether a status still counts toward remaining work. The database stores `name()`, so the schema did not change. |
| How to structure the Excel report | One class with a `SheetSpec` enum, or an interface plus an abstract base plus subclasses | **Interface plus abstract base plus subclasses.** The interface is the contract the workbook loops over. The abstract class shares the table-writing code (Template Method pattern). Each subclass supplies only its own columns and rows. |
| How to store project templates | One subclass per template, or data in `catalogue.json` | **Data.** Templates differ only in their values, not their behaviour, so subclasses would be inheritance without a reason. |
| Inheritance or composition for services | A base `Service` class, or injected collaborators | **Composition.** `TaskService` *has a* `JdbcTemplate`, a `Clock` and a `SnapshotService`, passed in through its constructor. Injecting the `Clock` lets tests fix the time. |
| How to talk to the database | JPA/Hibernate entities, or Spring JDBC with SQL | **JDBC.** The rules stay visible in our own Java instead of being hidden in ORM annotations, and every query is parameterized. |
| Where validation happens | In the browser only, or on the server | **On the server**, through `Fields`, `TaskFields` and `ProjectRules`. Browser checks are for convenience only, and the server still rejects bad input with field-level errors. |
| Error handling | Return error codes, or throw an exception | `ApiFailure extends RuntimeException` carries an HTTP status and a stable error code. One `ApiExceptionHandler` turns it into a safe JSON response. |
| Desktop app or web app | Java Swing, or a browser client with a Spring Boot API | **Web.** Team members on different laptops need to share one live project. That needs a server and a shared database, which Swing alone does not give us. |

## 4. Security choices

| Risk | What we did |
| --- | --- |
| Stolen or guessed passwords | BCrypt password hashes and rate limits on sign-in. |
| Cross-site request forgery | Spring Security CSRF tokens on every request that changes data. |
| SQL injection | Only parameterized `JdbcTemplate` queries. No user text is concatenated into SQL. |
| Members acting outside their role | Every service method checks project membership and role first (`requireMember`, `requireLeader`). |
| Leaked invitation links | Tokens are random, stored only as hashes, single-use and time-limited. |
| Database privileges | Separate migration and runtime database users. The runtime user cannot change the schema. |
