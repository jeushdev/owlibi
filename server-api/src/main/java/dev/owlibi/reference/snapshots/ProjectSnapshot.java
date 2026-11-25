package dev.owlibi.reference.snapshots;

import dev.owlibi.reference.projects.ProjectRules.Settings;
import dev.owlibi.reference.projects.ProjectService.Project;
import dev.owlibi.reference.tasks.TaskDtos;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable copy of one project's data, all read in a single database snapshot.
 *
 * <p>The board, the summaries and the professor's report are all computed from one of these, so their
 * numbers always agree. It holds no credentials, caller-dependent token inputs or caller secrets.
 * {@code history} is only filled in for a report capture.
 */
public record ProjectSnapshot(Project project, String templateJson, List<Member> members, Settings settings,
    List<Task> tasks, Metadata snapshot, LocalDate projectToday, History history) {

    public ProjectSnapshot {
        members = List.copyOf(members);
        tasks = List.copyOf(tasks);
    }

    /** A capture without activity history. */
    public ProjectSnapshot(Project project, String templateJson, List<Member> members, Settings settings, List<Task> tasks,
            Metadata snapshot, LocalDate projectToday) {
        this(project, templateJson, members, settings, tasks, snapshot, projectToday, null);
    }

    /** One activity event, with its change summary kept as the stored JSON text. */
    public record Event(UUID id, UUID projectId, UUID taskId, UUID actorId, String actorName, String eventType,
        Instant occurredAtUtc, String reason, String changeSummary) {}

    /** The full activity history of a report capture and its hash. */
    public record History(List<Event> events, int count, String hash, long capturedBytes) {
        public History {
            events = List.copyOf(events);
        }
    }

    /** The capture's state token and the moment it was taken. */
    public record Metadata(String stateToken, Instant snapshotAtUtc) {}

    /** One project member. */
    public record Member(UUID id, String displayName, String role, Instant joinedAtUtc) {}

    /** One assignee of a task, as stored. */
    public record Assignment(UUID taskId, UUID projectId, UUID userId, String displayName, Instant assignedAtUtc,
        Long acceptedAgreementVersion, Instant acceptedAtUtc) {}

    /** One task, as stored, with its assignments. */
    public record Task(UUID id, UUID projectId, String title, String description, String category, String status,
        LocalDate dueDate, String effortPoints, long agreementVersion, long version, Instant firstStartedAtUtc,
        UUID createdBy, Instant createdAtUtc, Instant updatedAtUtc, List<Assignment> assignees) {

        public Task {
            assignees = List.copyOf(assignees);
        }

        /** The API view of this task as seen by {@code actor}. */
        public TaskDtos.Task response(UUID actor) {
            var acceptances = assignees.stream().map(assignment -> {
                boolean accepted = Objects.equals(assignment.acceptedAgreementVersion(), agreementVersion);
                return new TaskDtos.Acceptance(assignment.userId(), assignment.displayName(), accepted,
                    accepted ? assignment.acceptedAtUtc() : null);
            }).toList();
            boolean actorAccepted = acceptances.stream()
                .anyMatch(acceptance -> acceptance.userId().equals(actor) && acceptance.accepted());
            List<UUID> assigneeIds = assignees.stream().map(Assignment::userId).toList();
            return new TaskDtos.Task(id, projectId, title, description, category, status, dueDate, effortPoints,
                agreementVersion, acceptances, actorAccepted, assigneeIds, version, firstStartedAtUtc, createdBy,
                createdAtUtc, updatedAtUtc);
        }
    }

    /** The board as seen by {@code actor}. */
    public TaskDtos.Board board(UUID actor) {
        var boardMembers = members.stream()
            .map(member -> new TaskDtos.Member(member.id(), member.displayName(), member.role()))
            .toList();
        var boardTasks = tasks.stream().map(task -> task.response(actor)).toList();
        return new TaskDtos.Board(project, boardMembers, boardTasks, true, tasks.size(), snapshot);
    }
}
