package dev.owlibi.reference.reports;

import dev.owlibi.reference.snapshots.ProjectSnapshot;

/** The project's effective rules: effort scale, warning thresholds, time zone and size examples. */
final class ProjectRulesSheet extends ReportSheet {
    ProjectRulesSheet(ProjectSnapshot snapshot) {
        super("Project rules", snapshot, new int[] {30, 38, 62, 10});
    }

    @Override
    protected String subtitle() {
        return "Effective project settings in this snapshot. Effort points describe relative task size.";
    }

    @Override
    protected void writeRows() {
        var settings = snapshot().settings();
        int ruleHeader = header("Rule", "Effective value", "Meaning", "Part");
        row("Effort scale", settings.effortScaleName(), "The project's scale for estimating task size.");
        row("Overload threshold", estimate(settings.overloadThreshold()),
            "Warning when unrounded remaining points exceed this value.");
        row("Near-due days", settings.nearDueDays(), "Includes today through today plus this many days.");
        row("Time zone", settings.projectZone(), "Used to determine the project date and due-date warnings.");
        filter(ruleHeader, 4);

        header("Effort points", "Task size example", "Part");
        for (var example : settings.effortScaleExamples()) {
            row(estimate(example.points()), example.label());
        }
    }
}
