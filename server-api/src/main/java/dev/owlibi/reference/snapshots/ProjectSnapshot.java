package dev.owlibi.reference.snapshots;

import dev.owlibi.reference.projects.ProjectRules.Settings;
import dev.owlibi.reference.projects.ProjectService.Project;
import dev.owlibi.reference.tasks.TaskDtos;
import java.time.*;
import java.util.*;

/** Detached immutable domain capture. No credentials, caller-dependent token inputs or caller secrets. */
public record ProjectSnapshot(Project project,String templateJson,List<Member> members,Settings settings,
    List<Task> tasks,Metadata snapshot,LocalDate projectToday,History history) {
    public ProjectSnapshot(Project project,String templateJson,List<Member> members,Settings settings,List<Task> tasks,Metadata snapshot,LocalDate projectToday) {
        this(project,templateJson,members,settings,tasks,snapshot,projectToday,null);
    }
    public record Event(UUID id,UUID projectId,UUID taskId,UUID actorId,String actorName,String eventType,Instant occurredAtUtc,String reason,String changeSummary) {}
    public record History(List<Event> events,int count,String hash,long capturedBytes) { public History { events=List.copyOf(events); } }
    public ProjectSnapshot { members=List.copyOf(members); tasks=List.copyOf(tasks); }
    public record Metadata(String stateToken,Instant snapshotAtUtc) {}
    public record Member(UUID id,String displayName,String role,Instant joinedAtUtc) {}
    public record Assignment(UUID taskId,UUID projectId,UUID userId,String displayName,Instant assignedAtUtc,
        Long acceptedAgreementVersion,Instant acceptedAtUtc) {}
    public record Task(UUID id,UUID projectId,String title,String description,String category,String status,
        LocalDate dueDate,String effortPoints,long agreementVersion,long version,Instant firstStartedAtUtc,
        UUID createdBy,Instant createdAtUtc,Instant updatedAtUtc,List<Assignment> assignees) {
        public Task { assignees=List.copyOf(assignees); }
        public TaskDtos.Task response(UUID actor) {
            var acceptances=assignees.stream().map(a -> new TaskDtos.Acceptance(a.userId(),a.displayName(),
                Objects.equals(a.acceptedAgreementVersion(),agreementVersion),
                Objects.equals(a.acceptedAgreementVersion(),agreementVersion)?a.acceptedAtUtc():null)).toList();
            return new TaskDtos.Task(id,projectId,title,description,category,status,dueDate,effortPoints,agreementVersion,
                acceptances,acceptances.stream().anyMatch(a -> a.userId().equals(actor) && a.accepted()),
                assignees.stream().map(Assignment::userId).toList(),version,firstStartedAtUtc,createdBy,createdAtUtc,updatedAtUtc);
        }
    }
    public TaskDtos.Board board(UUID actor) {
        return new TaskDtos.Board(project,members.stream().map(m -> new TaskDtos.Member(m.id(),m.displayName(),m.role())).toList(),
            tasks.stream().map(t -> t.response(actor)).toList(),true,tasks.size(),snapshot);
    }
}
