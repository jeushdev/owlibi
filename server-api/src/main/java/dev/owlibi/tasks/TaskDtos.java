package dev.owlibi.tasks;

import dev.owlibi.projects.ProjectService.Project;
import dev.owlibi.snapshots.ProjectSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The JSON shapes the task endpoints return. Response-only: no identity credentials, summaries or
 * workload calculations.
 *
 * <p>Statuses and roles are plain strings here (the {@link TaskStatus} and
 * {@link dev.owlibi.projects.ProjectRole} names) because these records are the public API.
 */
public final class TaskDtos {
    private TaskDtos() {}

    /** Whether one assignee accepted the task's current agreement, and when. */
    public record Acceptance(UUID userId, String displayName, boolean accepted, Instant acceptedAtUtc) {}

    /** A task as the caller sees it. */
    public record Task(UUID id, UUID projectId, String title, String description, String category, String status,
        LocalDate dueDate, String effortPoints, long agreementVersion, List<Acceptance> assigneeAcceptances,
        boolean currentUserAcceptedAgreement, List<UUID> assigneeIds, long version, Instant firstStartedAtUtc,
        UUID createdBy, Instant createdAtUtc, Instant updatedAtUtc) {}

    /** A project member shown on the board. */
    public record Member(UUID id, String displayName, String role) {}

    /** The whole board, read from one snapshot. */
    public record Board(Project project, List<Member> members, List<Task> tasks, boolean complete, int totalTasks,
        ProjectSnapshot.Metadata snapshot) {}

    /** One activity log entry. */
    public record Event(UUID id, UUID projectId, UUID taskId, UUID actorId, String actorName, String eventType,
        Instant occurredAtUtc, String reason, JsonNode changeSummary) {}

    /** One page of the activity log. */
    public record Activity(List<Event> items, String nextCursor) {}
}
