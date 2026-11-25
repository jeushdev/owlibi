package dev.owlibi.reference.reports;

import dev.owlibi.reference.snapshots.ProjectSnapshot;
import dev.owlibi.reference.summaries.SummaryDtos;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The first sheet: project facts, task counts, the report's scope and limits, and the member list. */
final class ProjectSummarySheet extends ReportSheet {
    private static final List<String> STATUSES = List.of("TODO", "IN_PROGRESS", "REVIEW", "DONE", "CANCELLED");

    private final SummaryDtos.Contribution contribution;

    ProjectSummarySheet(ProjectSnapshot snapshot, SummaryDtos.Contribution contribution) {
        super("Project summary", snapshot, new int[] {30, 91, 15, 10});
        this.contribution = contribution;
    }

    /** The first sheet carries the report's own title instead of the tab name. */
    @Override
    protected String title() {
        return "Owlibi  |  Professor report";
    }

    /** A darker tab marks where the report starts. */
    @Override
    protected String tabColor() {
        return "142B49";
    }

    @Override
    protected String subtitle() {
        return "Project overview, approved completed points, and the scope of this snapshot.";
    }

    @Override
    protected void writeRows() {
        ProjectSnapshot snapshot = snapshot();
        int informationHeader = header("Project information", "Value", "Part");
        row("Project name", snapshot.project().name());
        row("Project description", snapshot.project().description());
        ProjectSnapshot.Member leader = snapshot.members().stream()
            .filter(member -> member.role().equals("LEADER"))
            .findFirst()
            .orElseThrow(ReportBudget::unavailable);
        row("Leader", leader.displayName());
        row("Completed total", display(contribution.totalCompletedPointsDisplay()));
        row("Contribution status", contribution.empty() ? "No completed tasks yet" : "Based on approved Done tasks");
        for (String status : STATUSES) {
            long count = snapshot.tasks().stream().filter(task -> task.status().equals(status)).count();
            row("Task count " + taskStatus(status), count);
        }
        row("Created UTC", snapshot.project().createdAtUtc());
        row("Project UUID", snapshot.project().id());
        row("Leader UUID", leader.id());
        JsonNode stored = JsonMapper.shared().readTree(snapshot.templateJson());
        JsonNode template = stored.has("template") ? stored.get("template") : stored;
        row("Template ID", template.get("id").asText());
        row("Template version", template.get("version").asText());
        row("History count", snapshot.history().count());
        row("History hash", snapshot.history().hash());
        row("Scope", "All tasks and all safe activity visible in one read-only PostgreSQL REPEATABLE_READ snapshot. "
            + "Snapshot UTC is an observation time, not a commit timestamp cutoff.");
        row("Limits", "1000 tasks; 10000 events; 100000 data rows per sheet; 32 MiB captured JSON; 16 MiB XLSX; "
            + "64 MiB live workspace; 10 seconds capture; 20 seconds generation. Complete history or explicit refusal.");
        row("Formula", contribution.formula());
        for (String limitation : contribution.limitations()) {
            row("Limitation", limitation);
        }
        row("Effective project rules", "See the Project rules worksheet for the scale, examples and thresholds.");
        row("Audit columns", "Unhide the columns before Part on Member contributions, Task details, Workload report, "
            + "or Activity history to see the original UUIDs. For split records, read the parts in order.");
        filter(informationHeader, 3);

        header("Member", "Member UUID", "Role", "Part");
        for (ProjectSnapshot.Member member : snapshot.members()) {
            row(member.displayName(), member.id(), member.role());
        }
    }
}
