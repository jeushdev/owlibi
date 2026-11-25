package dev.owlibi.reports;

import dev.owlibi.snapshots.ProjectSnapshot;
import dev.owlibi.summaries.SummaryDtos;

/** Each member's approved completed points and share, followed by how each completed task was split. */
final class MemberContributionsSheet extends ReportSheet {
    private final SummaryDtos.Contribution contribution;

    MemberContributionsSheet(ProjectSnapshot snapshot, SummaryDtos.Contribution contribution) {
        super("Member contributions", snapshot, new int[] {35, 18, 26, 45, 39, 10}, 4);
        this.contribution = contribution;
    }

    @Override
    protected String subtitle() {
        return "Approved Done tasks only. Blank shares mean the project has no completed points.";
    }

    @Override
    protected void writeRows() {
        int memberHeader = header("Member", "Completed points", "Completed effort-points share (%)", "State",
            "Member UUID", "Part");
        String state = contribution.empty() ? "No completed tasks yet" : "Completed effort-points share";
        for (SummaryDtos.CompletedMember member : contribution.members()) {
            row(member.displayName(),
                display(member.completedPointsDisplay()),
                display(member.sharePercentDisplay()),
                state,
                member.userId());
        }
        filter(memberHeader, 6);
        note("Formula", contribution.formula());
        for (String limitation : contribution.limitations()) {
            note("Limitation", limitation);
        }
        note("Allocation display values", "Rounded allocation displays may not sum to the authoritative total, "
            + "which uses unrounded service allocations.");

        header("Completed task", "Assigned effort points", "Per-assignee completed points", "Assignees",
            "Task UUID", "Part");
        for (SummaryDtos.Allocation allocation : contribution.allocations()) {
            row(allocation.title(),
                estimate(allocation.effortPoints()),
                display(rounded(allocation.perAssigneePoints())),
                assignees(allocation.assigneeIds()),
                allocation.taskId());
        }
    }
}
