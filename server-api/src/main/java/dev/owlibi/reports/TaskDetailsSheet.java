package dev.owlibi.reports;

import dev.owlibi.snapshots.ProjectSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Every task in the snapshot, in every status, with its agreement and acceptance details. */
final class TaskDetailsSheet extends ReportSheet {
    TaskDetailsSheet(ProjectSnapshot snapshot) {
        super("Task details", snapshot,
            new int[] {36, 17, 14, 16, 47, 21, 59, 15, 58, 14, 25, 46, 25, 25, 39, 17, 10}, 14, 15);
    }

    @Override
    protected String subtitle() {
        if (snapshot().tasks().isEmpty()) {
            return "No tasks in this snapshot.";
        }
        return "All statuses. Due dates use the project calendar. Approval records are in Activity history.";
    }

    @Override
    protected void writeRows() {
        int taskHeader = header("Title", "Status", "Due date", "Assigned effort points", "Assignees", "Category",
            "Description", "Agreement version", "Current acceptance", "Task version", "First started UTC",
            "Created by", "Created UTC", "Updated UTC", "Task UUID", "Status code", "Part");
        for (ProjectSnapshot.Task task : snapshot().tasks()) {
            List<UUID> assigneeIds = task.assignees().stream().map(ProjectSnapshot.Assignment::userId).toList();
            row(task.title(),
                taskStatus(task.status()),
                task.dueDate(),
                estimate(task.effortPoints()),
                assignees(assigneeIds),
                task.category(),
                task.description(),
                Long.toString(task.agreementVersion()),
                acceptance(task),
                Long.toString(task.version()),
                task.firstStartedAtUtc(),
                identity(task.createdBy()),
                task.createdAtUtc(),
                task.updatedAtUtc(),
                task.id(),
                task.status());
        }
        filter(taskHeader, 17);
    }

    /** One line per assignee: whether they accepted the task's current agreement version, and when. */
    private String acceptance(ProjectSnapshot.Task task) {
        List<String> lines = task.assignees().stream().map(assignment -> {
            boolean accepted = Objects.equals(assignment.acceptedAgreementVersion(), task.agreementVersion());
            String acceptedAt = text(accepted ? assignment.acceptedAtUtc() : null);
            return identity(assignment.userId()) + "; accepted=" + accepted + "; accepted UTC=" + acceptedAt;
        }).toList();
        return String.join("\n", lines);
    }
}
