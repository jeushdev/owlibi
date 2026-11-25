package dev.owlibi.tasks;

import dev.owlibi.projects.ProjectRole;
import dev.owlibi.snapshots.SnapshotService;
import dev.owlibi.tasks.TaskDtos.Acceptance;
import dev.owlibi.tasks.TaskDtos.Activity;
import dev.owlibi.tasks.TaskDtos.Board;
import dev.owlibi.tasks.TaskDtos.Event;
import dev.owlibi.tasks.TaskDtos.Task;
import dev.owlibi.web.ApiFailure;
import dev.owlibi.web.Cursor;
import dev.owlibi.web.Fields;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The task rules of a project: creating and editing tasks, accepting the task agreement, moving tasks
 * through their statuses, and reading the activity log.
 *
 * <p>Every change locks the task row, checks the version the client last saw, and records an activity
 * event in the same transaction, so the log always matches the data.
 */
@Service
public class TaskService {
    private static final int MAXIMUM_TASKS = 1000;
    private static final int DEFAULT_ACTIVITY_LIMIT = 50;
    /** Editing any of these fields changes what assignees agreed to, so they must accept again. */
    private static final Set<String> AGREEMENT_FIELDS = Set.of("title", "description", "effortPoints", "assigneeIds");
    /** PostgreSQL error codes for a timestamp outside its supported range. */
    private static final Set<String> TIMESTAMP_OUT_OF_RANGE = Set.of("22007", "22008");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SnapshotService snapshots;

    public TaskService(JdbcTemplate jdbc, Clock clock, SnapshotService snapshots) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.snapshots = snapshots;
    }

    /** A task row as stored. */
    private record Stored(UUID id, UUID project, String title, String description, String category, TaskStatus status,
        LocalDate due, String points, long agreement, long version, Instant firstStart, UUID creator, Instant created,
        Instant updated) {}

    /** One assignee of a task and the agreement version they last accepted, if any. */
    private record Assigned(UUID task, UUID user, String name, Long agreement, Instant time) {
        /** Whether this assignee accepted the task's current agreement version. */
        boolean accepted(long currentAgreement) {
            return Objects.equals(agreement, currentAgreement);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var time = row.getTimestamp(column);
        return time == null ? null : time.toInstant();
    }

    private static final RowMapper<Stored> TASK_ROW = (row, rowNumber) -> new Stored(
        row.getObject("id", UUID.class),
        row.getObject("project_id", UUID.class),
        row.getString("title"),
        row.getString("description"),
        row.getString("category"),
        TaskStatus.from(row.getString("status")),
        row.getObject("due_date", LocalDate.class),
        row.getBigDecimal("effort_points").toPlainString(),
        row.getLong("agreement_version"),
        row.getLong("version"),
        instant(row, "first_started_at_utc"),
        row.getObject("created_by", UUID.class),
        instant(row, "created_at_utc"),
        instant(row, "updated_at_utc"));

    private static final RowMapper<Assigned> ASSIGNEE_ROW = (row, rowNumber) -> new Assigned(
        row.getObject("task_id", UUID.class),
        row.getObject("user_id", UUID.class),
        row.getString("display_name"),
        row.getObject("accepted_agreement_version", Long.class),
        instant(row, "accepted_at_utc"));

    private static final String ASSIGNEES = "SELECT a.*,u.display_name FROM task_assignees a JOIN users u ON u.id=a.user_id ";

    // ---------------------------------------------------------------- membership checks

    /** The actor's role in the project. Throws 404 when they are not a member. */
    public ProjectRole requireMember(UUID actor, UUID project) {
        var roles = jdbc.queryForList("SELECT role FROM project_memberships WHERE project_id=? AND user_id=?",
            String.class, project, actor);
        if (roles.isEmpty()) {
            throw missing();
        }
        return ProjectRole.from(roles.getFirst());
    }

    /** Throws 404 when the actor is not a member, or 403 when they are not the leader. */
    public void requireLeader(UUID actor, UUID project) {
        if (!requireMember(actor, project).isLeader()) {
            throw forbidden();
        }
    }

    // ---------------------------------------------------------------- creating tasks

    /** Creates a task in the TODO status. Leader only. */
    @Transactional
    public Task create(UUID actor, UUID project, String title, String description, String category, String dueDate,
            String effortPoints, List<String> assigneeIds) {
        return createTask(actor, project, title, description, category, dueDate, effortPoints, assigneeIds, null);
    }

    /** Creates a template's starter task, assigned to the leader, inside the transaction that creates the project. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Task createStarter(UUID actor, UUID project, String title, String description, String category,
            String effortPoints, Instant createdAt) {
        return createTask(actor, project, title, description, category, null, effortPoints, List.of(actor.toString()), createdAt);
    }

    private Task createTask(UUID actor, UUID project, String title, String description, String category, String dueDate,
            String effortPoints, List<String> assigneeIds, Instant initialTime) {
        requireLeader(actor, project);
        title = Fields.text(title, "title", 200, true, true);
        description = Fields.text(description, "description", 4000);
        category = Fields.text(category, "category", 100);
        LocalDate due = TaskFields.date(dueDate);
        BigDecimal points = Fields.points(effortPoints, "effortPoints");
        List<UUID> assignees = TaskFields.assignees(assigneeIds);

        // Shared with invitation capacity locking. READ_COMMITTED sees a predecessor's committed insertion after this lock.
        jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, project);
        requireProjectMembers(project, assignees);
        if (taskCount(project) >= MAXIMUM_TASKS) {
            throw taskLimit();
        }

        UUID id = UUID.randomUUID();
        Instant now = initialTime == null ? now() : initialTime;
        jdbc.update(
            "INSERT INTO tasks (id,project_id,title,description,category,status,due_date,effort_points,agreement_version,"
                + "version,created_by,created_at_utc,updated_at_utc) VALUES (?,?,?,?,?,?,?,?,1,0,?,?,?)",
            id, project, title, description, category, TaskStatus.TODO.name(), due, points, actor,
            Timestamp.from(now), Timestamp.from(now));
        for (UUID user : assignees) {
            addAssignee(id, project, user, now);
        }

        var after = new LinkedHashMap<String, Object>();
        after.put("title", title);
        after.put("description", description);
        after.put("category", category);
        after.put("dueDate", due == null ? null : due.toString());
        after.put("effortPoints", points.toPlainString());
        after.put("assigneeIds", assignees);
        after.put("status", TaskStatus.TODO.name());
        after.put("agreementVersion", 1);
        after.put("version", 0);
        event(project, id, actor, "TASK_CREATED", now, null, Map.of("before", Map.of(), "after", after));
        return response(load(project, id, false), actor, assigned(id));
    }

    // ---------------------------------------------------------------- editing tasks

    /**
     * Applies the leader's edit to an open task. Fields left out of the patch keep their value.
     *
     * <p>Changing the title, description, points or assignees changes the agreement, so every assignee
     * must accept again. Once work has started, such a change also needs a reason.
     */
    @Transactional
    public Task edit(UUID actor, UUID project, UUID task, TaskPatch patch) {
        requireLeader(actor, project);
        Stored stored = load(project, task, true);
        List<Assigned> assignees = assigned(task);
        long version = requireCurrentVersion(patch.version(), stored);
        if (stored.status().isClosed()) {
            throw invalidTransition();
        }

        String title = patch.title().supplied()
            ? Fields.text(patch.title().value(), "title", 200, true, true)
            : stored.title();
        String description = patch.description().supplied()
            ? Fields.text(patch.description().value(), "description", 4000)
            : stored.description();
        String category = patch.category().supplied()
            ? Fields.text(patch.category().value(), "category", 100)
            : stored.category();
        LocalDate due = patch.dueDate().supplied()
            ? TaskFields.date(patch.dueDate().value())
            : stored.due();
        BigDecimal points = patch.effortPoints().supplied()
            ? Fields.points(patch.effortPoints().value(), "effortPoints")
            : new BigDecimal(stored.points());
        List<UUID> previousIds = assignees.stream().map(Assigned::user).toList();
        List<UUID> assigneeIds = patch.assigneeIds().supplied()
            ? TaskFields.assignees(patch.assigneeIds().value())
            : previousIds;
        requireProjectMembers(project, assigneeIds);

        List<String> changed = new ArrayList<>();
        if (!title.equals(stored.title())) {
            changed.add("title");
        }
        if (!description.equals(stored.description())) {
            changed.add("description");
        }
        if (!category.equals(stored.category())) {
            changed.add("category");
        }
        if (!Objects.equals(due, stored.due())) {
            changed.add("dueDate");
        }
        if (points.compareTo(new BigDecimal(stored.points())) != 0) {
            changed.add("effortPoints");
        }
        if (!new HashSet<>(assigneeIds).equals(new HashSet<>(previousIds))) {
            changed.add("assigneeIds");
        }
        boolean revisesAgreement = changed.stream().anyMatch(AGREEMENT_FIELDS::contains);
        boolean workStarted = stored.firstStart() != null;
        String reason = optionalReason(patch.reason(), revisesAgreement && workStarted);
        if (changed.isEmpty()) {
            return response(stored, actor, assignees);
        }

        Instant now = now();
        long agreement = revisesAgreement ? stored.agreement() + 1 : stored.agreement();
        var before = editSummary(stored.title(), stored.description(), stored.category(), stored.due(), stored.points(),
            previousIds, stored.status(), stored.version(), stored.agreement());
        jdbc.update(
            "UPDATE tasks SET title=?,description=?,category=?,due_date=?,effort_points=?,agreement_version=?,"
                + "version=version+1,updated_at_utc=? WHERE id=?",
            title, description, category, due, points, agreement, Timestamp.from(now), task);
        for (UUID user : previousIds) {
            if (!assigneeIds.contains(user)) {
                jdbc.update("DELETE FROM task_assignees WHERE task_id=? AND user_id=?", task, user);
            }
        }
        for (UUID user : assigneeIds) {
            if (!previousIds.contains(user)) {
                addAssignee(task, project, user, now);
            }
        }
        if (revisesAgreement) {
            jdbc.update("UPDATE task_assignees SET accepted_agreement_version=NULL,accepted_at_utc=NULL WHERE task_id=?", task);
        }
        var after = editSummary(title, description, category, due, points.toPlainString(), assigneeIds, stored.status(),
            version + 1, agreement);
        event(project, task, actor, "TASK_EDITED", now, reason, Map.of("before", before, "after", after, "changedFields", changed));
        return response(load(project, task, false), actor, assigned(task));
    }

    private static Map<String, Object> editSummary(String title, String description, String category, LocalDate due,
            String points, List<UUID> assigneeIds, TaskStatus status, long version, long agreement) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("title", title);
        summary.put("description", description);
        summary.put("category", category);
        summary.put("dueDate", due == null ? null : due.toString());
        summary.put("effortPoints", points);
        summary.put("assigneeIds", assigneeIds.stream().sorted().toList());
        summary.put("status", status.name());
        summary.put("version", version);
        summary.put("agreementVersion", agreement);
        return summary;
    }

    // ---------------------------------------------------------------- accepting the agreement

    /** Records that the actor, an assignee, accepts the task's current agreement version. */
    @Transactional
    public Task accept(UUID actor, UUID project, UUID task, Long agreementValue) {
        requireMember(actor, project);
        Stored stored = load(project, task, true);
        List<Assigned> assignees = assigned(task);
        Assigned mine = assignees.stream()
            .filter(assignee -> assignee.user().equals(actor))
            .findFirst()
            .orElseThrow(TaskService::forbidden);
        long agreement = TaskFields.version(agreementValue, "agreementVersion", 1);
        if (agreement != stored.agreement()) {
            throw new ApiFailure(409, "STALE_AGREEMENT", "Reload the current agreement");
        }
        if (mine.accepted(agreement)) {
            return response(stored, actor, assignees);
        }
        if (stored.status().isClosed()) {
            throw invalidTransition();
        }

        Instant now = now();
        jdbc.update("UPDATE task_assignees SET accepted_agreement_version=?,accepted_at_utc=? WHERE task_id=? AND user_id=?",
            agreement, Timestamp.from(now), task, actor);
        jdbc.update("UPDATE tasks SET version=version+1,updated_at_utc=? WHERE id=?", Timestamp.from(now), task);
        var before = Map.of("accepted", false, "version", stored.version());
        var after = Map.of("userId", actor, "accepted", true, "agreementVersion", agreement, "version", stored.version() + 1);
        event(project, task, actor, "AGREEMENT_ACCEPTED", now, null, Map.of("before", before, "after", after));
        return response(load(project, task, false), actor, assigned(task));
    }

    // ---------------------------------------------------------------- moving between statuses

    /**
     * Moves a task to its next status by the named action, such as {@code START} or {@code APPROVE}.
     *
     * <p>The checks run in this order, and the first that fails decides the error: the action exists,
     * the actor may request it, the client saw the current version, a reason is present when required,
     * the task is in a status the action starts from, and every assignee accepted when that is required.
     */
    @Transactional
    public Task transition(UUID actor, UUID project, UUID task, String action, Long versionValue, String reasonValue) {
        ProjectRole role = requireMember(actor, project);
        Stored stored = load(project, task, true);
        List<Assigned> assignees = assigned(task);

        // Each action is a TaskTransition subclass; the checks below ask it questions instead of
        // comparing action names, so Java picks the right answer for the actual transition at runtime.
        TaskTransition move = TaskTransition.named(action).orElseThrow(() -> ApiFailure.validation("action"));
        requireAllowedToRequest(move, role, actor, assignees);
        requireCurrentVersion(versionValue, stored);
        String reason = optionalReason(reasonValue, move.requiresReason());
        TaskStatus next = move.next(stored.status()).orElseThrow(TaskService::invalidTransition);
        if (move.requiresAcceptance()) {
            requireEveryoneAccepted(stored, assignees);
        }

        Instant now = now();
        Instant firstStart = move.firstStart(stored.firstStart(), now);
        jdbc.update("UPDATE tasks SET status=?,version=version+1,updated_at_utc=?,first_started_at_utc=? WHERE id=?",
            next.name(), Timestamp.from(now), firstStart == null ? null : Timestamp.from(firstStart), task);
        recordTransition(move, stored, next, firstStart, actor, reason, now);
        return response(load(project, task, false), actor, assignees);
    }

    /** The leader may request any transition; an assignee only those open to assignees; anyone else none. */
    private static void requireAllowedToRequest(TaskTransition move, ProjectRole role, UUID actor, List<Assigned> assignees) {
        if (role.isLeader()) {
            return;
        }
        boolean isAssignee = assignees.stream().anyMatch(assignee -> assignee.user().equals(actor));
        if (!move.openToAssignees() || !isAssignee) {
            throw forbidden();
        }
    }

    /** A task with no assignees, or with anyone who has not accepted the current agreement, cannot move. */
    private static void requireEveryoneAccepted(Stored stored, List<Assigned> assignees) {
        boolean everyoneAccepted = !assignees.isEmpty()
            && assignees.stream().allMatch(assignee -> assignee.accepted(stored.agreement()));
        if (!everyoneAccepted) {
            throw new ApiFailure(422, "ACCEPTANCE_REQUIRED", "Every current assignee must accept");
        }
    }

    /** Logs the status change under the action's name, with the status, version and first-start time before and after. */
    private void recordTransition(TaskTransition move, Stored stored, TaskStatus next, Instant firstStart, UUID actor,
            String reason, Instant now) {
        var before = new LinkedHashMap<String, Object>();
        before.put("status", stored.status().name());
        before.put("version", stored.version());
        before.put("firstStartedAtUtc", stored.firstStart() == null ? null : stored.firstStart().toString());
        var after = new LinkedHashMap<String, Object>();
        after.put("status", next.name());
        after.put("version", stored.version() + 1);
        after.put("firstStartedAtUtc", firstStart == null ? null : firstStart.toString());
        event(stored.project(), stored.id(), actor, move.action(), now, reason, Map.of("before", before, "after", after));
    }

    // ---------------------------------------------------------------- reading

    /** The whole board (project, members and tasks) from one consistent snapshot. */
    public Board board(UUID actor, UUID project) {
        return snapshots.capture(actor, project).board(actor);
    }

    /** The project's activity log, newest first, one page at a time. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Activity activity(UUID actor, UUID project, String limitValue, String cursorValue) {
        requireMember(actor, project);
        int limit = limitValue == null ? DEFAULT_ACTIVITY_LIMIT : Cursor.limit(limitValue);
        Cursor cursor = Cursor.decode(cursorValue);
        String sql = "SELECT e.*,u.display_name FROM activity_events e JOIN users u ON u.id=e.actor_id WHERE e.project_id=?";
        var parameters = new ArrayList<Object>();
        parameters.add(project);
        if (cursor != null) {
            sql += " AND (e.occurred_at_utc,e.id)<(?,?)";
            parameters.add(Timestamp.from(cursor.time()));
            parameters.add(cursor.id());
        }
        sql += " ORDER BY e.occurred_at_utc DESC,e.id DESC LIMIT ?";
        // Ask for one extra row to learn whether another page exists.
        parameters.add(limit + 1);

        List<Event> rows;
        try {
            rows = jdbc.query(sql, (row, rowNumber) -> new Event(
                    row.getObject("id", UUID.class),
                    row.getObject("project_id", UUID.class),
                    row.getObject("task_id", UUID.class),
                    row.getObject("actor_id", UUID.class),
                    row.getString("display_name"),
                    row.getString("event_type"),
                    instant(row, "occurred_at_utc"),
                    row.getString("reason"),
                    JsonMapper.shared().readTree(row.getString("change_summary"))),
                parameters.toArray());
        } catch (DataAccessException failure) {
            if (cursor != null && failure.getRootCause() instanceof SQLException cause
                    && TIMESTAMP_OUT_OF_RANGE.contains(cause.getSQLState())) {
                throw ApiFailure.malformed();
            }
            throw failure;
        }

        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            var last = rows.getLast();
            next = new Cursor(last.occurredAtUtc(), last.id()).encode();
        }
        return new Activity(List.copyOf(rows), next);
    }

    // ---------------------------------------------------------------- shared helpers

    /** Checks the version the client sent matches the stored one, so stale edits are refused. */
    private static long requireCurrentVersion(Long versionValue, Stored stored) {
        long version = TaskFields.version(versionValue, "version", 0);
        if (version != stored.version()) {
            throw new ApiFailure(409, "STALE_VERSION", "Reload the current task");
        }
        return version;
    }

    /** Validates a reason of up to 1000 characters; an empty reason is stored as {@code null}. */
    private static String optionalReason(String value, boolean required) {
        String reason = Fields.text(value, "reason", 1000, true, required);
        return reason.isEmpty() ? null : reason;
    }

    /** Every assignee must be a member of the project. */
    private void requireProjectMembers(UUID project, List<UUID> users) {
        for (UUID user : users) {
            int memberships = jdbc.queryForObject("SELECT count(*) FROM project_memberships WHERE project_id=? AND user_id=?",
                Integer.class, project, user);
            if (memberships != 1) {
                throw ApiFailure.validation("assigneeIds");
            }
        }
    }

    private void addAssignee(UUID task, UUID project, UUID user, Instant now) {
        jdbc.update("INSERT INTO task_assignees (task_id,project_id,user_id,assigned_at_utc) VALUES (?,?,?,?)",
            task, project, user, Timestamp.from(now));
    }

    private Stored load(UUID project, UUID task, boolean lock) {
        String sql = "SELECT * FROM tasks WHERE project_id=? AND id=?" + (lock ? " FOR UPDATE" : "");
        var rows = jdbc.query(sql, TASK_ROW, project, task);
        if (rows.isEmpty()) {
            throw missing();
        }
        return rows.getFirst();
    }

    private List<Assigned> assigned(UUID task) {
        return jdbc.query(ASSIGNEES + "WHERE a.task_id=? ORDER BY a.user_id", ASSIGNEE_ROW, task);
    }

    /** The API view of a task as seen by {@code actor}, including who has accepted the current agreement. */
    private Task response(Stored stored, UUID actor, List<Assigned> assigned) {
        var acceptances = assigned.stream().map(assignee -> {
            boolean accepted = assignee.accepted(stored.agreement());
            return new Acceptance(assignee.user(), assignee.name(), accepted, accepted ? assignee.time() : null);
        }).toList();
        boolean actorAccepted = acceptances.stream()
            .anyMatch(acceptance -> acceptance.userId().equals(actor) && acceptance.accepted());
        List<UUID> assigneeIds = assigned.stream().map(Assigned::user).toList();
        return new Task(stored.id(), stored.project(), stored.title(), stored.description(), stored.category(),
            stored.status().name(), stored.due(), stored.points(), stored.agreement(), acceptances, actorAccepted,
            assigneeIds, stored.version(), stored.firstStart(), stored.creator(), stored.created(), stored.updated());
    }

    private void event(UUID project, UUID task, UUID actor, String type, Instant now, String reason, Map<String, Object> summary) {
        jdbc.update(
            "INSERT INTO activity_events (id,project_id,task_id,actor_id,event_type,occurred_at_utc,reason,change_summary) "
                + "VALUES (?,?,?,?,?,?,?,?::jsonb)",
            UUID.randomUUID(), project, task, actor, type, Timestamp.from(now), reason,
            JsonMapper.shared().writeValueAsString(summary));
    }

    private int taskCount(UUID project) {
        return jdbc.queryForObject("SELECT count(*) FROM tasks WHERE project_id=?", Integer.class, project);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiFailure missing() {
        return new ApiFailure(404, "NOT_FOUND", "Resource unavailable");
    }

    private static ApiFailure forbidden() {
        return new ApiFailure(403, "FORBIDDEN", "Action unavailable");
    }

    private static ApiFailure invalidTransition() {
        return new ApiFailure(422, "INVALID_TRANSITION", "Action unavailable in the current state");
    }

    private static ApiFailure taskLimit() {
        return new ApiFailure(422, "TASK_LIMIT", "Project task limit exceeded");
    }
}
