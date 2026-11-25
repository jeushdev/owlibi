package dev.owlibi.snapshots;

import dev.owlibi.projects.ProjectRole;
import dev.owlibi.projects.ProjectRules.Settings;
import dev.owlibi.projects.ProjectService.Project;
import dev.owlibi.projects.ProjectSettingsService;
import dev.owlibi.reports.ReportBudget;
import dev.owlibi.snapshots.ProjectSnapshot.Assignment;
import dev.owlibi.snapshots.ProjectSnapshot.Event;
import dev.owlibi.snapshots.ProjectSnapshot.History;
import dev.owlibi.snapshots.ProjectSnapshot.Member;
import dev.owlibi.snapshots.ProjectSnapshot.Metadata;
import dev.owlibi.snapshots.ProjectSnapshot.Task;
import dev.owlibi.web.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one project's data into a {@link ProjectSnapshot}, all inside one REPEATABLE READ transaction so
 * every part comes from the same moment.
 *
 * <p>A report capture passes a {@link CaptureBudget}: it is leader-only, also reads the activity history,
 * and checks time and size limits before every statement and row. A normal capture passes {@code null}.
 */
@Service
public class SnapshotService {
    private static final int MAXIMUM_TASKS = 1000;
    private static final int EVENT_BATCH_SIZE = 100;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Manila");
    /** Keys that must never appear in an exported change summary (compared lowercase, without underscores). */
    private static final Set<String> SENSITIVE_KEYS = Set.of("email", "password", "passwordhash", "token", "tokenhash",
        "invitationtoken", "session", "sessionid", "csrf", "secret");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProjectSettingsService settings;

    public SnapshotService(JdbcTemplate jdbc, Clock clock, ProjectSettingsService settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
    }

    /** The project row with the caller's role, plus the stored template JSON. */
    private record Selected(Project project, String template) {}

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** A snapshot for any member, used by the board and the summaries. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectSnapshot capture(UUID actor, UUID project) {
        return selectedCapture(actor, project, null);
    }

    /** A leader-only snapshot with full history for the professor's report, within {@code budget}. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public ProjectSnapshot captureReport(UUID actor, UUID project, CaptureBudget budget) {
        return selectedCapture(actor, project, budget);
    }

    /** Checks that the actor leads the project before a report is prepared. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public void authorizeReport(UUID actor, UUID project, CaptureBudget budget) {
        budget.statement(jdbc);
        var roles = jdbc.queryForList("SELECT role FROM project_memberships WHERE project_id=? AND user_id=?",
            String.class, project, actor);
        if (roles.isEmpty()) {
            throw missing();
        }
        if (!ProjectRole.from(roles.getFirst()).isLeader()) {
            throw forbidden();
        }
        budget.check();
    }

    private ProjectSnapshot selectedCapture(UUID actor, UUID project, CaptureBudget budget) {
        boolean report = budget != null;
        beforeStatement(budget);
        // First domain SQL statement establishes the MVCC snapshot and current membership before sampling Clock.
        Selected selected = selectProject(actor, project);
        if (report && !ProjectRole.from(selected.project().role()).isLeader()) {
            throw forbidden();
        }
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);

        beforeStatement(budget);
        requireWithinTaskLimit(project, report);
        if (report) {
            budget.statement(jdbc);
            if (jdbc.queryForObject("SELECT count(*) FROM activity_events WHERE project_id=?", Long.class, project) > ReportBudget.EVENTS) {
                throw ReportBudget.tooLarge();
            }
            var domain = new LinkedHashMap<String, Object>();
            domain.put("project", selected.project());
            domain.put("templateSnapshot", JsonMapper.shared().readTree(selected.template()));
            budget.add("project", domain);
            budget.statement(jdbc);
        }

        List<Member> members = members(project, budget);
        beforeStatement(budget);
        Settings rules = settings.captureSettings(project);
        if (report) {
            if (!rules.configured()) {
                throw new ApiFailure(409, "SETTINGS_REQUIRED", "The leader must configure project rules");
            }
            budget.add("settings", rules);
            budget.statement(jdbc);
        }
        Map<UUID, List<Assignment>> assignments = assignments(project);
        beforeStatement(budget);
        List<Task> tasks = tasks(project, assignments, budget);
        History history = report ? history(project, tasks, budget) : null;

        LocalDate today = LocalDate.ofInstant(at, PROJECT_ZONE);
        // The token is a hash of the capture's own content, so build it once without a token first.
        var draft = new ProjectSnapshot(selected.project(), selected.template(), members, rules, tasks, new Metadata(null, at), today);
        var metadata = new Metadata(CanonicalState.token(draft), at);
        return new ProjectSnapshot(selected.project(), selected.template(), members, rules, tasks, metadata, today, history);
    }

    /** Sets the budget's timeouts before the next statement of a report capture; does nothing otherwise. */
    private void beforeStatement(CaptureBudget budget) {
        if (budget != null) {
            budget.statement(jdbc);
        }
    }

    private Selected selectProject(UUID actor, UUID project) {
        // BoardSnapshotProbe (a test) recognizes this statement by its "WHERE p.id=? AND m.user_id=?" text.
        var selected = jdbc.query(
            "SELECT p.*,m.role AS caller_role FROM projects p JOIN project_memberships m ON m.project_id=p.id WHERE p.id=? AND m.user_id=?",
            (row, rowNumber) -> new Selected(
                new Project(
                    row.getObject("id", UUID.class),
                    row.getString("name"),
                    row.getString("description"),
                    row.getString("caller_role"),
                    instant(row, "created_at_utc")),
                row.getString("template_snapshot")),
            project, actor);
        if (selected.isEmpty()) {
            throw missing();
        }
        return selected.getFirst();
    }

    private void requireWithinTaskLimit(UUID project, boolean report) {
        int count = jdbc.queryForObject("SELECT count(*) FROM tasks WHERE project_id=?", Integer.class, project);
        if (count > MAXIMUM_TASKS) {
            if (report) {
                throw ReportBudget.tooLarge();
            }
            throw new ApiFailure(422, "TASK_LIMIT", "Project task limit exceeded");
        }
    }

    private List<Member> members(UUID project, CaptureBudget budget) {
        return jdbc.query(
            "SELECT u.id,u.display_name,m.role,m.joined_at_utc FROM project_memberships m JOIN users u ON u.id=m.user_id "
                + "WHERE m.project_id=? ORDER BY m.joined_at_utc,u.id",
            (row, rowNumber) -> {
                var member = new Member(
                    row.getObject("id", UUID.class),
                    row.getString("display_name"),
                    row.getString("role"),
                    instant(row, "joined_at_utc"));
                if (budget != null) {
                    budget.add("members", member);
                }
                return member;
            },
            project);
    }

    private Map<UUID, List<Assignment>> assignments(UUID project) {
        var byTask = new HashMap<UUID, List<Assignment>>();
        var rows = jdbc.query(
            "SELECT a.*,u.display_name FROM task_assignees a JOIN users u ON u.id=a.user_id WHERE a.project_id=? ORDER BY a.task_id,a.user_id",
            (row, rowNumber) -> new Assignment(
                row.getObject("task_id", UUID.class),
                row.getObject("project_id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getString("display_name"),
                instant(row, "assigned_at_utc"),
                row.getObject("accepted_agreement_version", Long.class),
                instant(row, "accepted_at_utc")),
            project);
        for (var assignment : rows) {
            byTask.computeIfAbsent(assignment.taskId(), unused -> new ArrayList<>()).add(assignment);
        }
        return byTask;
    }

    private List<Task> tasks(UUID project, Map<UUID, List<Assignment>> assignments, CaptureBudget budget) {
        return jdbc.query(
            "SELECT * FROM tasks WHERE project_id=? ORDER BY created_at_utc,id LIMIT 1001",
            (row, rowNumber) -> {
                UUID id = row.getObject("id", UUID.class);
                var task = new Task(
                    id,
                    row.getObject("project_id", UUID.class),
                    row.getString("title"),
                    row.getString("description"),
                    row.getString("category"),
                    row.getString("status"),
                    row.getObject("due_date", LocalDate.class),
                    row.getBigDecimal("effort_points").setScale(2).toPlainString(),
                    row.getLong("agreement_version"),
                    row.getLong("version"),
                    instant(row, "first_started_at_utc"),
                    row.getObject("created_by", UUID.class),
                    instant(row, "created_at_utc"),
                    instant(row, "updated_at_utc"),
                    assignments.getOrDefault(id, List.of()));
                if (budget != null) {
                    budget.add("tasks", task);
                }
                return task;
            },
            project);
    }

    /** The full activity history for a report, fetched in batches that respect the text budget. */
    private History history(UUID project, List<Task> tasks, CaptureBudget budget) {
        if (tasks.size() > ReportBudget.TASKS) {
            throw ReportBudget.tooLarge();
        }
        budget.statement(jdbc);
        // Payload lengths are checked in the same snapshot BEFORE fetching their text.
        var lengths = jdbc.queryForList(
            "SELECT id,octet_length(change_summary::text) AS bytes FROM activity_events WHERE project_id=? ORDER BY occurred_at_utc,id LIMIT 10001",
            project);
        if (lengths.size() > ReportBudget.EVENTS) {
            throw ReportBudget.tooLarge();
        }
        for (var length : lengths) {
            budget.rawRow(payloadBytes(length));
        }
        List<Event> events = fetchEvents(project, lengths, budget);
        if (events.size() != lengths.size()) {
            throw ReportBudget.unavailable();
        }
        budget.check();
        return new History(events, events.size(), historyHash(events, budget), budget.bytes());
    }

    private static long payloadBytes(Map<String, Object> length) {
        return ((Number) length.get("bytes")).longValue();
    }

    private List<Event> fetchEvents(UUID project, List<Map<String, Object>> lengths, CaptureBudget budget) {
        var events = new ArrayList<Event>();
        int start = 0;
        while (start < lengths.size()) {
            budget.statement(jdbc);
            var ids = new ArrayList<Object>();
            long batchBytes = 0;
            // Batches never fetch more raw payload than the remaining captured-text budget.
            for (int index = start; index < lengths.size() && ids.size() < EVENT_BATCH_SIZE; index++) {
                long size = payloadBytes(lengths.get(index));
                budget.rawRow(size);
                if (!ids.isEmpty() && batchBytes + size > ReportBudget.TEXT - budget.bytes()) {
                    break;
                }
                ids.add(lengths.get(index).get("id"));
                batchBytes += size;
            }
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            var parameters = new ArrayList<Object>();
            parameters.add(project);
            parameters.addAll(ids);
            RowCallbackHandler collect = row -> {
                String payload = row.getString("change_summary");
                safePayload(JsonMapper.shared().readTree(payload));
                var event = new Event(
                    row.getObject("id", UUID.class),
                    row.getObject("project_id", UUID.class),
                    row.getObject("task_id", UUID.class),
                    row.getObject("actor_id", UUID.class),
                    row.getString("display_name"),
                    row.getString("event_type"),
                    instant(row, "occurred_at_utc"),
                    row.getString("reason"),
                    payload);
                budget.add("events", event);
                events.add(event);
            };
            jdbc.query(
                "SELECT e.id,e.project_id,e.task_id,e.actor_id,u.display_name,e.event_type,e.occurred_at_utc,e.reason,"
                    + "e.change_summary::text FROM activity_events e JOIN users u ON u.id=e.actor_id "
                    + "WHERE e.project_id=? AND e.id IN (" + placeholders + ") ORDER BY e.occurred_at_utc,e.id",
                collect,
                parameters.toArray());
            start += ids.size();
        }
        return events;
    }

    /** SHA-256 of the events as one canonical JSON array, computed piece by piece to avoid one huge string. */
    private static String historyHash(List<Event> events, CaptureBudget budget) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        digest.update((byte) '[');
        boolean first = true;
        for (var event : events) {
            budget.check();
            if (!first) {
                digest.update((byte) ',');
            }
            first = false;
            digest.update(CanonicalState.json(event).getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) ']');
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Refuses to export a change summary that contains a credential-like key anywhere inside it. */
    private static void safePayload(JsonNode node) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                String key = entry.getKey().replace("_", "").toLowerCase(Locale.ROOT);
                if (SENSITIVE_KEYS.contains(key)) {
                    throw ReportBudget.unavailable();
                }
                safePayload(entry.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(SnapshotService::safePayload);
        }
    }

    private static ApiFailure missing() {
        return new ApiFailure(404, "NOT_FOUND", "Resource unavailable");
    }

    private static ApiFailure forbidden() {
        return new ApiFailure(403, "FORBIDDEN", "Action unavailable");
    }
}
