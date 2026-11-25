package dev.owlibi.reference.summaries;

import dev.owlibi.reference.projects.ProjectRules.Settings;
import dev.owlibi.reference.snapshots.ProjectSnapshot.Metadata;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The JSON shapes the summary endpoints return.
 *
 * <p>Decimal amounts are strings so no precision is lost. The {@code ...Display} values are rounded for
 * people to read and never feed back into a calculation.
 */
public final class SummaryDtos {
    private SummaryDtos() {}

    /** The contribution summary of one project. */
    public record Contribution(UUID projectId, Metadata snapshot, String projectZone, LocalDate projectToday, Settings rules,
        boolean empty, String totalCompletedPoints, String totalCompletedPointsDisplay, List<CompletedMember> members,
        List<Allocation> allocations, String formula, List<String> limitations) {}

    /** One member's completed points and share of the total. */
    public record CompletedMember(UUID userId, String displayName, String completedPoints, String completedPointsDisplay,
        String sharePercent, String sharePercentDisplay) {}

    /** How one done task's points were split between its assignees. */
    public record Allocation(UUID taskId, String title, String effortPoints, List<UUID> assigneeIds, String perAssigneePoints) {}

    /** The workload summary of one project. */
    public record Workload(UUID projectId, Metadata snapshot, String projectZone, LocalDate projectToday, Settings rules,
        String calculationKey, List<WorkloadMember> members, List<OpenTask> tasks, List<UUID> reviewTaskIds,
        Counts projectCounts, String formula, List<String> limitations) {}

    /** One member's remaining points and due-date pressure. */
    public record WorkloadMember(UUID userId, String displayName, String remainingPoints, String remainingPointsDisplay,
        boolean overloaded, List<UUID> openTaskIds, List<UUID> overdueTaskIds, List<UUID> nearDueTaskIds, int overdueCount,
        int nearDueCount) {}

    /** One open task with its remaining share per assignee and its {@link DueState} name. */
    public record OpenTask(UUID taskId, String title, String status, LocalDate dueDate, String effortPoints,
        List<UUID> assigneeIds, String perAssigneeRemainingPoints, String dateState) {}

    /** Project-wide task counts for the workload summary. */
    public record Counts(int openTasks, int reviewTasks, int overdueTasks, int nearDueTasks) {}
}
