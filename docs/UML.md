# Owlibi UML class diagrams

These diagrams cover the classes that carry the OOP design. Controllers, DTO records and configuration classes are left out to keep them readable. They are the source for Laboratory Activity 3. Redraw them in the tool and format the lab instructions ask for.

**Notation:** `+` public, `-` private, `#` protected, `~` package-private. A `*` after a method means abstract (italic in standard UML), and `$` means static (underlined in standard UML). Arrows:

- Hollow triangle, solid line: inheritance (`extends`).
- Hollow triangle, dashed line: an interface being implemented (`implements`).
- Solid arrow: a class keeps a reference to another (association).
- Dashed arrow: a class uses another only for a moment (dependency).

## 1. Task workflow

```mermaid
classDiagram
    direction TB
    class TaskService {
        -JdbcTemplate jdbc
        -Clock clock
        -SnapshotService snapshots
        +TaskService(JdbcTemplate, Clock, SnapshotService)
        +requireMember(UUID actor, UUID project) ProjectRole
        +requireLeader(UUID actor, UUID project) void
        +create(...) Task
        +edit(UUID actor, UUID project, UUID task, TaskPatch patch) Task
        +accept(UUID actor, UUID project, UUID task, Long agreement) Task
        +transition(UUID actor, UUID project, UUID task, String action, Long version, String reason) Task
        +board(UUID actor, UUID project) Board
        +activity(UUID actor, UUID project, String limit, String cursor) Activity
        -requireAllowedToRequest(TaskTransition, ProjectRole, UUID, List~Assigned~)$ void
        -requireEveryoneAccepted(Stored, List~Assigned~)$ void
        -recordTransition(...) void
        -load(UUID project, UUID task, boolean lock) Stored
    }
    class TaskTransition {
        <<abstract>>
        -String action
        -TaskStatus target
        -Set~TaskStatus~ sources
        #TaskTransition(String action, TaskStatus target, Set~TaskStatus~ sources)
        ~action() String
        ~next(TaskStatus current) Optional~TaskStatus~
        ~openToAssignees()* boolean
        ~requiresReason()* boolean
        ~requiresAcceptance()* boolean
        ~firstStart(Instant current, Instant now) Instant
        ~named(String action)$ Optional~TaskTransition~
    }
    class Start {
        ~openToAssignees() boolean
        ~requiresReason() boolean
        ~requiresAcceptance() boolean
        ~firstStart(Instant current, Instant now) Instant
    }
    class SubmitReview
    class Approve
    class Return
    class Reopen
    class Cancel
    class TaskStatus {
        <<enumeration>>
        TODO
        IN_PROGRESS
        REVIEW
        DONE
        CANCELLED
        -String label
        +label() String
        +isClosed() boolean
        +isOpen() boolean
        +countsAsRemaining() boolean
        +countsAsCompleted() boolean
        +from(String value)$ TaskStatus
    }
    class ProjectRole {
        <<enumeration>>
        LEADER
        MEMBER
        +isLeader() boolean
        +from(String value)$ ProjectRole
    }
    TaskTransition <|-- Start
    TaskTransition <|-- SubmitReview
    TaskTransition <|-- Approve
    TaskTransition <|-- Return
    TaskTransition <|-- Reopen
    TaskTransition <|-- Cancel
    TaskTransition --> TaskStatus : sources / target
    TaskService ..> TaskTransition : asks
    TaskService ..> ProjectRole : checks
    TaskService --> SnapshotService
    TaskController --> TaskService
```

`SubmitReview`, `Approve`, `Return`, `Reopen` and `Cancel` override the same three abstract methods as `Start`. Only `Start` also overrides `firstStart()`.

## 2. Professor report

```mermaid
classDiagram
    direction TB
    class ReportSection {
        <<interface>>
        +label() String
        +write(StreamingWorkbook book, ReportStyles styles, ReportBudget budget) void
    }
    class ReportSheet {
        <<abstract>>
        -String label
        -ProjectSnapshot snapshot
        -int[] columnWidths
        -int[] auditColumns
        -SXSSFSheet sheet
        -int nextRow
        #ReportSheet(String label, ProjectSnapshot snapshot, int[] widths, int... auditColumns)
        +label() String
        +write(StreamingWorkbook, ReportStyles, ReportBudget) void
        #subtitle()* String
        #writeRows()* void
        #title() String
        #tabColor() String
        #header(String... values) int
        #row(Object... values) void
        #note(String key, String value) void
    }
    class ProjectSummarySheet {
        #subtitle() String
        #writeRows() void
        #title() String
        #tabColor() String
    }
    class MemberContributionsSheet {
        #subtitle() String
        #writeRows() void
    }
    class TaskDetailsSheet
    class WorkloadSheet
    class ActivityHistorySheet
    class ProjectRulesSheet
    class ReportWorkbook {
        +generate(ProjectSnapshot, ReportWorkspace, ReportBudget) Path
        +verifyComplete(Path, ReportBudget) void
        -sections(...)$ List~ReportSection~
    }
    class ReportWorkspace {
        +Path directory
        -FileChannel owner
        -long quota
        -boolean closed
        +ReportWorkspace(ReportBudget deadline)
        +ReportWorkspace(Path parent, ReportBudget deadline, long quota)
        +file(String prefix, String suffix) Path
        +measure() long
        +close() void
    }
    class AutoCloseable {
        <<interface>>
        +close() void
    }
    ReportSection <|.. ReportSheet
    ReportSheet <|-- ProjectSummarySheet
    ReportSheet <|-- MemberContributionsSheet
    ReportSheet <|-- TaskDetailsSheet
    ReportSheet <|-- WorkloadSheet
    ReportSheet <|-- ActivityHistorySheet
    ReportSheet <|-- ProjectRulesSheet
    ReportWorkbook ..> ReportSection : writes each
    ReportController --> ReportWorkbook
    ReportController ..> ReportWorkspace : try-with-resources
    AutoCloseable <|.. ReportWorkspace
```

`ReportSheet` is declared `abstract sealed ... permits` the six sheets. `write()` is `final`: it is the Template Method that calls the abstract `subtitle()` and `writeRows()` hooks.

## 3. Errors and validation

```mermaid
classDiagram
    direction LR
    class RuntimeException
    class ApiFailure {
        -int status
        -ApiError error
        -Integer retryAfter
        +ApiFailure(int status, String code, String message)
        +ApiFailure(int status, String code, String message, Map fields, Integer retryAfter)
        +status() int
        +error() ApiError
        +malformed()$ ApiFailure
        +validation(String field)$ ApiFailure
    }
    class Fields {
        +text(String value, String field, int limit, boolean trim, boolean required)$ String
        +text(String value, String field, int limit)$ String
        +points(String value, String field)$ BigDecimal
    }
    class OncePerRequestFilter {
        <<abstract>>
        #doFilterInternal(request, response, chain)* void
    }
    RuntimeException <|-- ApiFailure
    OncePerRequestFilter <|-- SessionBoundaryFilter
    OncePerRequestFilter <|-- SessionEpochFilter
    OncePerRequestFilter <|-- HostedOriginFilter
    Fields ..> ApiFailure : throws
```

`ApiFailure` has two constructors, and `Fields` has two `text(...)` methods. Both are examples of method overloading.
