package dev.owlibi.reference.projects;

import dev.owlibi.reference.projects.ProjectRules.Example;
import dev.owlibi.reference.projects.ProjectRules.Settings;
import dev.owlibi.reference.projects.ProjectRules.Values;
import dev.owlibi.reference.web.ApiFailure;
import dev.owlibi.reference.web.JsonBody;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and changes a project's rules. Only the leader may change them, and each change must name the
 * version it was based on, so two leaders' tabs cannot silently overwrite each other.
 */
@Service
public class ProjectSettingsService {
    private static final String SELECT_SETTINGS = "SELECT id, project_zone, settings_version, effort_scale_name, "
        + "effort_scale_examples::text, overload_threshold, near_due_days, settings_updated_at_utc FROM projects WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProjectSettingsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Throws 404 when the actor is not in the project, or 403 when they are not its leader. */
    public void requireLeader(UUID actor, UUID project) {
        if (!requireMember(actor, project).isLeader()) {
            throw new ApiFailure(403, "FORBIDDEN", "Action unavailable");
        }
    }

    private ProjectRole requireMember(UUID actor, UUID project) {
        var roles = jdbc.queryForList(
            "SELECT role FROM project_memberships WHERE project_id = ? AND user_id = ?", String.class, project, actor);
        if (roles.isEmpty()) {
            throw new ApiFailure(404, "NOT_FOUND", "Resource unavailable");
        }
        return ProjectRole.from(roles.getFirst());
    }

    /** The project's rules, for any member. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Settings read(UUID actor, UUID project) {
        requireMember(actor, project);
        return stored(project);
    }

    /** Stores the first rules of a new project, inside the transaction that creates it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settings initialize(UUID project, Values values, Instant at) {
        if (stored(project).configured()) {
            throw new IllegalStateException("Project rules already initialized");
        }
        Values normalized = ProjectRules.validated(values);
        write(project, normalized, 1, at);
        return stored(project);
    }

    /** Applies the leader's edit to the rules and records which fields changed. */
    @Transactional
    public Settings edit(UUID actor, UUID project, JsonNode body) {
        requireLeader(actor, project);
        jdbc.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", UUID.class, project);
        JsonBody.validate(body, ProjectRules.PATCH);

        Settings before = stored(project);
        long requestedVersion = ProjectRules.version(JsonBody.string(body, "settingsVersion"));
        if (requestedVersion != Long.parseLong(before.settingsVersion())) {
            throw new ApiFailure(409, "STALE_SETTINGS_VERSION", "Reload the current project rules");
        }

        Values prior = before.values();
        Values after = ProjectRules.normalize(body, prior);
        if (after.equals(prior)) {
            return before;
        }
        if (requestedVersion == Long.MAX_VALUE) {
            throw new ApiFailure(409, "VERSION_EXHAUSTED", "Project rule version cannot advance");
        }

        List<String> changed = changedFields(prior, after);
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        write(project, after, requestedVersion + 1, at);
        Settings result = stored(project);

        var summary = Map.of(
            "before", ProjectRules.summary(before),
            "after", ProjectRules.summary(result),
            "changedFields", changed);
        jdbc.update(
            "INSERT INTO activity_events (id, project_id, actor_id, event_type, occurred_at_utc, change_summary) "
                + "VALUES (?, ?, ?, 'SETTINGS_CHANGED', ?, ?::jsonb)",
            UUID.randomUUID(), project, actor, Timestamp.from(at), JsonMapper.shared().writeValueAsString(summary));
        return result;
    }

    /** The rule fields that differ, in {@link ProjectRules#ORDER}. Every field counts as changed the first time. */
    private static List<String> changedFields(Values prior, Values after) {
        var changed = new ArrayList<String>();
        if (prior == null || !prior.effortScaleName().equals(after.effortScaleName())) {
            changed.add("effortScaleName");
        }
        if (prior == null || !prior.effortScaleExamples().equals(after.effortScaleExamples())) {
            changed.add("effortScaleExamples");
        }
        if (prior == null || !prior.overloadThreshold().equals(after.overloadThreshold())) {
            changed.add("overloadThreshold");
        }
        if (prior == null || prior.nearDueDays() != after.nearDueDays()) {
            changed.add("nearDueDays");
        }
        return changed;
    }

    private void write(UUID project, Values values, long version, Instant at) {
        jdbc.update(
            "UPDATE projects SET settings_version = ?, effort_scale_name = ?, effort_scale_examples = ?::jsonb, "
                + "overload_threshold = ?, near_due_days = ?, settings_updated_at_utc = ? WHERE id = ?",
            version,
            values.effortScaleName(),
            JsonMapper.shared().writeValueAsString(values.effortScaleExamples()),
            new BigDecimal(values.overloadThreshold()),
            values.nearDueDays(),
            Timestamp.from(at),
            project);
    }

    /** The rules inside an existing snapshot transaction, without a membership check of their own. */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Settings captureSettings(UUID project) {
        return stored(project);
    }

    private Settings stored(UUID project) {
        return jdbc.queryForObject(SELECT_SETTINGS, (row, rowNumber) -> settingsFrom(row), project);
    }

    private static Settings settingsFrom(ResultSet row) throws SQLException {
        long version = row.getLong("settings_version");
        boolean configured = version > 0;
        List<Example> examples = null;
        if (configured) {
            var values = new ArrayList<Example>();
            for (var item : JsonMapper.shared().readTree(row.getString("effort_scale_examples"))) {
                values.add(new Example(item.get("points").asText(), item.get("label").asText()));
            }
            examples = List.copyOf(values);
        }
        var updated = row.getTimestamp("settings_updated_at_utc");
        var threshold = row.getBigDecimal("overload_threshold");
        return new Settings(
            row.getObject("id", UUID.class),
            configured,
            Long.toString(version),
            row.getString("project_zone"),
            row.getString("effort_scale_name"),
            examples,
            threshold == null ? null : threshold.toPlainString(),
            row.getObject("near_due_days", Integer.class),
            updated == null ? null : updated.toInstant());
    }
}
