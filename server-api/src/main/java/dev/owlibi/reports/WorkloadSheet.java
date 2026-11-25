package dev.owlibi.reports;

import dev.owlibi.snapshots.ProjectSnapshot;
import dev.owlibi.summaries.SummaryDtos;

/** Remaining work per member, project totals, the warning rules, and every open task. */
final class WorkloadSheet extends ReportSheet {
    private final SummaryDtos.Workload workload;

    WorkloadSheet(ProjectSnapshot snapshot, SummaryDtos.Workload workload) {
        super("Workload report", snapshot, new int[] {36, 19, 17, 17, 20, 45, 18, 39, 10}, 7);
        this.workload = workload;
    }

    @Override
    protected String subtitle() {
        return "Remaining points are separate from approved contributions. Review tasks still count toward date warnings.";
    }

    @Override
    protected void writeRows() {
        ProjectSnapshot snapshot = snapshot();
        int memberHeader = header("Member", "Remaining points", "Open tasks", "Overdue tasks", "Near-due tasks",
            "Workload warning", "Role", "Member UUID", "Part");
        for (SummaryDtos.WorkloadMember member : workload.members()) {
            String role = snapshot.members().stream()
                .filter(person -> person.id().equals(member.userId()))
                .findFirst()
                .orElseThrow()
                .role();
            row(member.displayName(),
                display(member.remainingPointsDisplay()),
                member.openTaskIds().size(),
                member.overdueCount(),
                member.nearDueCount(),
                member.overloaded() ? "Over threshold" : "None",
                role,
                member.userId());
        }
        filter(memberHeader, 9);

        SummaryDtos.Counts counts = workload.projectCounts();
        header("Project totals", "Open tasks", "In review", "Overdue", "Near due", "Part");
        row("Tasks", counts.openTasks(), counts.reviewTasks(), counts.overdueTasks(), counts.nearDueTasks());
        row("Overload threshold", estimate(snapshot.settings().overloadThreshold()));
        row("Near-due window (days)", snapshot.settings().nearDueDays());
        note("Formula", workload.formula());
        for (String limitation : workload.limitations()) {
            note("Limitation", limitation);
        }

        header("Task", "Status", "Due date", "Assigned effort points", "Remaining per assignee", "Assignees",
            "Due status", "Task UUID", "Part");
        for (SummaryDtos.OpenTask task : workload.tasks()) {
            row(task.title(),
                taskStatus(task.status()),
                task.dueDate(),
                estimate(task.effortPoints()),
                display(rounded(task.perAssigneeRemainingPoints())),
                assignees(task.assigneeIds()),
                dueStatus(task.dateState()),
                task.taskId());
        }
    }

    /** The readable name of a due-date state code. */
    private static String dueStatus(String state) {
        return switch (state) {
            case "OVERDUE" -> "Overdue";
            case "NEAR_DUE" -> "Near due";
            case "LATER" -> "Due later";
            case "NONE" -> "No due date";
            default -> state;
        };
    }
}
