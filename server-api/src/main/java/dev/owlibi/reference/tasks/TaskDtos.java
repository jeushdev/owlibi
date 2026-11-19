package dev.owlibi.reference.tasks;

import dev.owlibi.reference.projects.ProjectService.Project;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Response-only domain data; no identity credentials, summaries or workload calculations. */
public final class TaskDtos {
    private TaskDtos() {}
    public record Acceptance(UUID userId,String displayName,boolean accepted,Instant acceptedAtUtc) {}
    public record Task(UUID id,UUID projectId,String title,String description,String category,String status,
        LocalDate dueDate,String effortPoints,long agreementVersion,List<Acceptance> assigneeAcceptances,
        boolean currentUserAcceptedAgreement,List<UUID> assigneeIds,long version,Instant firstStartedAtUtc,
        UUID createdBy,Instant createdAtUtc,Instant updatedAtUtc) {}
    public record Member(UUID id,String displayName,String role) {}
    public record Board(Project project,List<Member> members,List<Task> tasks,boolean complete,int totalTasks,
        dev.owlibi.reference.snapshots.ProjectSnapshot.Metadata snapshot) {}
    public record Event(UUID id,UUID projectId,UUID taskId,UUID actorId,String actorName,String eventType,
        Instant occurredAtUtc,String reason,JsonNode changeSummary) {}
    public record Activity(List<Event> items,String nextCursor) {}
}
