package dev.owlibi.reference.projects;

import dev.owlibi.reference.security.RateLimits;
import dev.owlibi.reference.tasks.TaskService;
import dev.owlibi.reference.templates.TemplateCatalog;
import dev.owlibi.reference.web.ApiFailure;
import dev.owlibi.reference.web.Cursor;
import dev.owlibi.reference.web.Fields;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates projects and manages who belongs to them.
 *
 * <p>A project has one leader and at most six people in total. New members join with a one-use
 * invitation token that expires; only a SHA-256 hash of the token is stored.
 */
@Service
public class ProjectService {
    /** One project as the caller sees it, including the caller's own role. */
    public record Project(UUID id, String name, String description, String role, Instant createdAtUtc) {}

    /** One page of projects and the cursor for the next page, or {@code null} at the end. */
    public record Page(List<Project> items, String nextCursor) {}

    /** The response to creating a project. */
    public record Created(UUID id, String name, String description, String role, Instant createdAtUtc,
        TemplateCatalog.Ref templateRef, ProjectRules.Settings settings, List<UUID> starterTaskIds) {}

    /** A new invitation token, shown to the leader once. */
    public record Invitation(String token, Instant expiresAtUtc) {}

    private record StoredInvitation(UUID id, UUID projectId, Instant expires, boolean consumed) {}

    private static final int MAXIMUM_MEMBERS = 6;
    private static final int MAXIMUM_OUTSTANDING_INVITATIONS = 100;
    private static final int TOKEN_BYTES = 32;
    /** PostgreSQL error codes for a timestamp outside its supported range. */
    private static final Set<String> TIMESTAMP_OUT_OF_RANGE = Set.of("22007", "22008");

    private final JdbcTemplate jdbc;
    private final TemplateCatalog templates;
    private final ProjectSettingsService settings;
    private final TaskService tasks;
    private final Clock clock;
    private final RateLimits rates;
    private final Duration invitationTtl;
    private final SecureRandom random = new SecureRandom();

    public ProjectService(JdbcTemplate jdbc, Clock clock, RateLimits rates, TemplateCatalog templates,
            ProjectSettingsService settings, TaskService tasks, @Value("${owlibi.invitation-ttl:PT24H}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Invitation TTL must be positive");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.rates = rates;
        this.invitationTtl = ttl;
        this.templates = templates;
        this.settings = settings;
        this.tasks = tasks;
    }

    /** The caller's projects, newest first. */
    @Transactional(readOnly = true)
    public Page list(UUID actor, String limitValue, String cursorValue) {
        int limit = Cursor.limit(limitValue);
        Cursor cursor = Cursor.decode(cursorValue);
        String sql = "SELECT p.id,p.name,p.description,m.role,p.created_at_utc FROM projects p "
            + "JOIN project_memberships m ON m.project_id=p.id WHERE m.user_id=?";
        var parameters = new ArrayList<Object>();
        parameters.add(actor);
        if (cursor != null) {
            sql += " AND (p.created_at_utc,p.id)<(?,?)";
            parameters.add(Timestamp.from(cursor.time()));
            parameters.add(cursor.id());
        }
        sql += " ORDER BY p.created_at_utc DESC,p.id DESC LIMIT ?";
        // Ask for one extra row to learn whether another page exists.
        parameters.add(limit + 1);

        List<Project> rows;
        try {
            rows = jdbc.query(sql, (row, rowNumber) -> new Project(
                    row.getObject("id", UUID.class),
                    row.getString("name"),
                    row.getString("description"),
                    row.getString("role"),
                    row.getTimestamp("created_at_utc").toInstant()),
                parameters.toArray());
        } catch (DataAccessException failure) {
            // An otherwise canonical cursor may contain a timestamp outside PostgreSQL's range.
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
            next = new Cursor(last.createdAtUtc(), last.id()).encode();
        }
        return new Page(List.copyOf(rows), next);
    }

    /** Allows each account five project creations per minute. */
    public void creationAttempt(UUID actor) {
        rates.attempt("project:" + actor, 5);
    }

    /** Allows each network address twenty invitation redemptions per minute. */
    public void redemptionAttempt(String peer) {
        rates.attempt("redeem:" + peer, 20);
    }

    /** Checks that the actor leads the project, then allows twenty invitations per minute. */
    public void invitationAttempt(UUID actor, UUID project) {
        settings.requireLeader(actor, project);
        rates.attempt("invite:" + actor + ":" + project, 20);
    }

    /** Creates a project led by the actor, with the template's starter tasks and the given rules. */
    @Transactional
    public Created create(UUID actor, String name, String description, TemplateCatalog.Selection selection, ProjectRules.Values rules) {
        name = Fields.text(name, "name", 200, true, true);
        description = Fields.text(description, "description", 4000);
        var template = templates.forCreation(selection);
        if (rules == null && !template.id().equals("blank")) {
            throw ApiFailure.validation("settings");
        }
        if (rules != null) {
            rules = ProjectRules.validated(rules);
        }

        Instant now = now();
        UUID id = UUID.randomUUID();
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("template", template);
        snapshot.put("effectiveSettings", rules);
        jdbc.update(
            "INSERT INTO projects (id,name,description,leader_id,template_snapshot,created_at_utc) VALUES (?,?,?,?,?::jsonb,?)",
            id, name, description, actor, JsonMapper.shared().writeValueAsString(snapshot), Timestamp.from(now));
        addMember(id, actor, ProjectRole.LEADER, now);
        var chosen = rules == null ? null : settings.initialize(id, rules, now);

        var templateRef = new TemplateCatalog.Ref(template.id(), template.version());
        var after = new LinkedHashMap<String, Object>();
        after.put("name", name);
        after.put("description", description);
        after.put("leaderId", actor);
        after.put("memberCount", 1);
        after.put("templateRef", templateRef);
        after.put("settings", chosen == null ? null : ProjectRules.summary(chosen));
        event(id, actor, "PROJECT_CREATED", now, Map.of("before", Map.of(), "after", after));

        var starterIds = new ArrayList<UUID>();
        for (var starter : template.starterTasks()) {
            var task = tasks.createStarter(actor, id, starter.title(), starter.description(), starter.category(), starter.effortPoints(), now);
            starterIds.add(task.id());
        }
        return new Created(id, name, description, ProjectRole.LEADER.name(), now, templateRef, chosen, List.copyOf(starterIds));
    }

    /** Creates an invitation token for a project that still has room. Leader only. */
    @Transactional
    public Invitation invite(UUID actor, UUID project) {
        settings.requireLeader(actor, project);
        lockProject(project);
        if (memberCount(project) >= MAXIMUM_MEMBERS) {
            throw full();
        }
        Instant now = now();
        int outstanding = jdbc.queryForObject(
            "SELECT count(*) FROM project_invitations WHERE project_id=? AND accepted_at_utc IS NULL AND expires_at_utc>?",
            Integer.class, project, Timestamp.from(now));
        if (outstanding >= MAXIMUM_OUTSTANDING_INVITATIONS) {
            throw new ApiFailure(422, "INVITATION_LIMIT", "Outstanding invitation limit reached");
        }

        byte[] tokenBytes = new byte[TOKEN_BYTES];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        UUID id = UUID.randomUUID();
        Instant expires = expiryAfter(now);
        jdbc.update(
            "INSERT INTO project_invitations (id,project_id,token_hash,created_by,created_at_utc,expires_at_utc) VALUES (?,?,?,?,?,?)",
            id, project, hash(tokenBytes), actor, Timestamp.from(now), Timestamp.from(expires));
        event(project, actor, "INVITATION_CREATED", now,
            Map.of("before", Map.of(), "after", Map.of("invitationId", id, "expiresAtUtc", expires.toString())));
        return new Invitation(token, expires);
    }

    /** {@code now} plus the invitation lifetime, rounded up to the database's microsecond precision. */
    private Instant expiryAfter(Instant now) {
        Instant requestedExpiry = now.plus(invitationTtl);
        Instant expires = requestedExpiry.truncatedTo(ChronoUnit.MICROS);
        // Round up to the database's precision so every representable positive TTL can expire.
        if (expires.isBefore(requestedExpiry)) {
            expires = expires.plusNanos(1000);
        }
        return expires;
    }

    /** Uses an invitation token to add the actor to its project as a member, and returns the project id. */
    @Transactional
    public UUID accept(UUID actor, String token) {
        byte[] tokenBytes = decodeToken(token);
        // Lock invitation, then project for both one-use and capacity. No path acquires these in reverse.
        var found = jdbc.query(
            "SELECT id,project_id,expires_at_utc,accepted_at_utc FROM project_invitations WHERE token_hash=? FOR UPDATE",
            (row, rowNumber) -> new StoredInvitation(
                row.getObject("id", UUID.class),
                row.getObject("project_id", UUID.class),
                row.getTimestamp("expires_at_utc").toInstant(),
                row.getTimestamp("accepted_at_utc") != null),
            hash(tokenBytes));
        if (found.isEmpty()) {
            throw unavailable();
        }
        var invitation = found.getFirst();
        UUID project = invitation.projectId();
        lockProject(project);

        Instant now = now();
        boolean expired = !now.isBefore(invitation.expires());
        if (invitation.consumed() || expired || isMember(actor, project)) {
            throw unavailable();
        }
        int count = memberCount(project);
        if (count >= MAXIMUM_MEMBERS) {
            throw full();
        }
        addMember(project, actor, ProjectRole.MEMBER, now);
        jdbc.update("UPDATE project_invitations SET accepted_at_utc=?,accepted_by=? WHERE id=?",
            Timestamp.from(now), actor, invitation.id());
        var after = Map.<String, Object>of(
            "memberId", actor,
            "role", ProjectRole.MEMBER.name(),
            "memberCount", count + 1,
            "invitationId", invitation.id());
        event(project, actor, "MEMBER_JOINED", now, Map.of("before", Map.of("memberCount", count), "after", after));
        return project;
    }

    /** Checks the token's exact shape (43 URL-safe characters for 32 bytes) and returns its bytes. */
    private static byte[] decodeToken(String token) {
        if (token == null) {
            throw ApiFailure.validation("token");
        }
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            throw unavailable();
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException bad) {
            throw unavailable();
        }
        boolean canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token);
        if (bytes.length != TOKEN_BYTES || !canonical) {
            throw unavailable();
        }
        return bytes;
    }

    private void addMember(UUID project, UUID user, ProjectRole role, Instant now) {
        jdbc.update("INSERT INTO project_memberships (project_id,user_id,role,joined_at_utc) VALUES (?,?,?,?)",
            project, user, role.name(), Timestamp.from(now));
    }

    private boolean isMember(UUID actor, UUID project) {
        return jdbc.queryForObject("SELECT count(*) FROM project_memberships WHERE project_id=? AND user_id=?",
            Integer.class, project, actor) > 0;
    }

    private void lockProject(UUID project) {
        jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, project);
    }

    private int memberCount(UUID project) {
        return jdbc.queryForObject("SELECT count(*) FROM project_memberships WHERE project_id=?", Integer.class, project);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void event(UUID project, UUID actor, String type, Instant now, Map<String, Object> summary) {
        jdbc.update(
            "INSERT INTO activity_events (id,project_id,actor_id,event_type,occurred_at_utc,change_summary) VALUES (?,?,?,?,?,?::jsonb)",
            UUID.randomUUID(), project, actor, type, Timestamp.from(now), JsonMapper.shared().writeValueAsString(summary));
    }

    private static ApiFailure unavailable() {
        return new ApiFailure(409, "INVITATION_UNAVAILABLE", "Invitation is unavailable");
    }

    private static ApiFailure full() {
        return new ApiFailure(409, "PROJECT_FULL", "Project has reached its member limit");
    }
}
