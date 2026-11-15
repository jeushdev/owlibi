package dev.owlibi.reference.summaries;

import dev.owlibi.reference.projects.ProjectRules.Settings;
import dev.owlibi.reference.snapshots.ProjectSnapshot.Metadata;
import java.time.LocalDate;
import java.util.*;

/** Response-only decimal strings; display values never feed authoritative calculations. */
public final class SummaryDtos {
    private SummaryDtos() {}
    public record Contribution(UUID projectId,Metadata snapshot,String projectZone,LocalDate projectToday,Settings rules,
        boolean empty,String totalCompletedPoints,String totalCompletedPointsDisplay,List<CompletedMember> members,
        List<Allocation> allocations,String formula,List<String> limitations) {}
    public record CompletedMember(UUID userId,String displayName,String completedPoints,String completedPointsDisplay,
        String sharePercent,String sharePercentDisplay) {}
    public record Allocation(UUID taskId,String title,String effortPoints,List<UUID> assigneeIds,String perAssigneePoints) {}
    public record Workload(UUID projectId,Metadata snapshot,String projectZone,LocalDate projectToday,Settings rules,
        String calculationKey,List<WorkloadMember> members,List<OpenTask> tasks,List<UUID> reviewTaskIds,
        Counts projectCounts,String formula,List<String> limitations) {}
    public record WorkloadMember(UUID userId,String displayName,String remainingPoints,String remainingPointsDisplay,
        boolean overloaded,List<UUID> openTaskIds,List<UUID> overdueTaskIds,List<UUID> nearDueTaskIds,int overdueCount,int nearDueCount) {}
    public record OpenTask(UUID taskId,String title,String status,LocalDate dueDate,String effortPoints,List<UUID> assigneeIds,
        String perAssigneeRemainingPoints,String dateState) {}
    public record Counts(int openTasks,int reviewTasks,int overdueTasks,int nearDueTasks) {}
}
