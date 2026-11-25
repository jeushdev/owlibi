package dev.owlibi.summaries;

import dev.owlibi.snapshots.CanonicalState;
import dev.owlibi.snapshots.ProjectSnapshot;
import dev.owlibi.summaries.SummaryDtos.Allocation;
import dev.owlibi.summaries.SummaryDtos.CompletedMember;
import dev.owlibi.summaries.SummaryDtos.Contribution;
import dev.owlibi.summaries.SummaryDtos.Counts;
import dev.owlibi.summaries.SummaryDtos.OpenTask;
import dev.owlibi.summaries.SummaryDtos.Workload;
import dev.owlibi.summaries.SummaryDtos.WorkloadMember;
import dev.owlibi.tasks.TaskStatus;
import dev.owlibi.web.ApiFailure;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The contribution and workload arithmetic, computed from one detached project snapshot.
 *
 * <p>It has no database access and no state, so the same snapshot always gives the same numbers. The
 * professor's workbook reuses it. A task's points are split equally between its assignees, and all
 * division uses 34 significant digits ({@link MathContext#DECIMAL128}) so shares are not rounded early.
 */
public final class SummaryCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MINIMUM_POINTS = new BigDecimal("0.01");
    private static final BigDecimal MAXIMUM_POINTS = new BigDecimal("99999999.99");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int MAXIMUM_ASSIGNEES = 6;

    public static final String CONTRIBUTION_FORMULA = "Completed effort-points share = member's sum of equal Done-task "
        + "allocations / sum of Done-task effort points × 100. No completed tasks yet when the total is zero.";
    public static final List<String> CONTRIBUTION_LIMITATIONS = List.of(
        "Relative estimates and task-splitting choices affect results.",
        "Not measured effort, quality, fairness, grades or proof of individual work.",
        "Small positive shares may display 0.0%.",
        "Independently rounded percentages may total 99.9% or 100.1%.");
    public static final String WORKLOAD_FORMULA = "Remaining points = equal shares of TODO and IN_PROGRESS effort points; "
        + "overload means unrounded remaining points > the configured threshold. REVIEW has zero remaining points but "
        + "remains date-visible. Overdue is before projectToday; near-due includes projectToday through projectToday + nearDueDays.";
    public static final List<String> WORKLOAD_LIMITATIONS = List.of(
        "Project-scoped relative estimates, not hours or a fairness verdict.",
        "Dates use the server Clock and Asia/Manila; undated tasks still contribute qualifying remaining effort.",
        "Pending revised acceptance does not remove remaining effort. Done and Cancelled tasks are excluded.",
        "Independent snapshots and dates may differ; compare their tokens and date basis before combining results.");

    private SummaryCalculator() {}

    /** The error returned when the snapshot holds data the calculation cannot trust. */
    public static ApiFailure unavailable() {
        return new ApiFailure(503, "SUMMARY_UNAVAILABLE", "Summary temporarily unavailable");
    }

    // ---------------------------------------------------------------- contribution

    /**
     * Each member's share of completed (DONE) effort points.
     *
     * <p>Every done task's points are split equally between its assignees. A member's percentage is
     * their total divided by the total points of all done tasks.
     */
    public static Contribution contribution(ProjectSnapshot capture) {
        UUID projectId = capture.project().id();
        var completedByMember = new LinkedHashMap<UUID, BigDecimal>();
        capture.members().forEach(member -> completedByMember.put(member.id(), BigDecimal.ZERO));

        var allocations = new ArrayList<Allocation>();
        BigDecimal total = new BigDecimal("0.00");
        for (var task : capture.tasks()) {
            if (!statusOf(task).countsAsCompleted()) {
                continue;
            }
            List<UUID> assigneeIds = assignees(projectId, completedByMember.keySet(), task, true);
            BigDecimal points = points(task);
            BigDecimal share = points.divide(BigDecimal.valueOf(assigneeIds.size()), MC);
            total = total.add(points);
            for (UUID id : assigneeIds) {
                completedByMember.put(id, completedByMember.get(id).add(share));
            }
            allocations.add(new Allocation(task.id(), task.title(), task.effortPoints(), assigneeIds, share.toPlainString()));
        }

        boolean empty = total.signum() == 0;
        var members = new ArrayList<CompletedMember>();
        for (var member : capture.members()) {
            BigDecimal completed = completedByMember.get(member.id());
            BigDecimal percent = empty ? null : completed.divide(total, MC).multiply(ONE_HUNDRED, MC);
            members.add(new CompletedMember(
                member.id(),
                member.displayName(),
                completed.toPlainString(),
                display(completed),
                percent == null ? null : percent.toPlainString(),
                percent == null ? null : display(percent)));
        }

        var settings = capture.settings();
        return new Contribution(projectId, capture.snapshot(), settings.projectZone(), capture.projectToday(),
            settings.configured() ? settings : null, empty, total.toPlainString(), display(total), List.copyOf(members),
            List.copyOf(allocations), CONTRIBUTION_FORMULA, CONTRIBUTION_LIMITATIONS);
    }

    // ---------------------------------------------------------------- workload

    /** One member's running workload totals while the tasks are counted. */
    private static final class MemberWorkload {
        BigDecimal remainingPoints = BigDecimal.ZERO;
        final List<UUID> openTaskIds = new ArrayList<>();
        final List<UUID> overdueTaskIds = new ArrayList<>();
        final List<UUID> nearDueTaskIds = new ArrayList<>();
    }

    /**
     * Each member's remaining effort and due-date pressure over the open tasks.
     *
     * <p>TODO and IN_PROGRESS points are split equally between assignees as remaining work. REVIEW tasks
     * add no remaining points but still count toward overdue and near-due lists. A member is overloaded
     * when their remaining points exceed the project's threshold. Requires configured project rules.
     */
    public static Workload workload(ProjectSnapshot capture) {
        var settings = capture.settings();
        if (!settings.configured()) {
            throw new ApiFailure(409, "SETTINGS_REQUIRED", "The leader must configure project rules");
        }
        UUID projectId = capture.project().id();
        LocalDate today = capture.projectToday();
        LocalDate nearDueCutoff = today.plusDays(settings.nearDueDays());

        var workloadByMember = new LinkedHashMap<UUID, MemberWorkload>();
        capture.members().forEach(member -> workloadByMember.put(member.id(), new MemberWorkload()));
        var openTasks = new ArrayList<OpenTask>();
        var reviewTaskIds = new ArrayList<UUID>();
        int remainingCount = 0;
        int overdueCount = 0;
        int nearDueCount = 0;

        for (var task : capture.tasks()) {
            TaskStatus status = statusOf(task);
            if (!status.isOpen()) {
                continue;
            }
            List<UUID> assigneeIds = assignees(projectId, workloadByMember.keySet(), task, false);
            BigDecimal points = points(task);
            boolean remaining = status.countsAsRemaining();
            BigDecimal share = remaining ? points.divide(BigDecimal.valueOf(assigneeIds.size()), MC) : BigDecimal.ZERO;
            DueState dueState = DueState.classify(task.dueDate(), today, nearDueCutoff);

            if (remaining) {
                remainingCount++;
            } else {
                reviewTaskIds.add(task.id());
            }
            if (dueState == DueState.OVERDUE) {
                overdueCount++;
            }
            if (dueState == DueState.NEAR_DUE) {
                nearDueCount++;
            }
            for (UUID id : assigneeIds) {
                MemberWorkload member = workloadByMember.get(id);
                member.remainingPoints = member.remainingPoints.add(share);
                if (remaining) {
                    member.openTaskIds.add(task.id());
                }
                if (dueState == DueState.OVERDUE) {
                    member.overdueTaskIds.add(task.id());
                }
                if (dueState == DueState.NEAR_DUE) {
                    member.nearDueTaskIds.add(task.id());
                }
            }
            openTasks.add(new OpenTask(task.id(), task.title(), task.status(), task.dueDate(), task.effortPoints(),
                assigneeIds, share.toPlainString(), dueState.name()));
        }

        var threshold = new BigDecimal(settings.overloadThreshold());
        var members = new ArrayList<WorkloadMember>();
        for (var member : capture.members()) {
            MemberWorkload totals = workloadByMember.get(member.id());
            boolean overloaded = totals.remainingPoints.compareTo(threshold) > 0;
            members.add(new WorkloadMember(
                member.id(),
                member.displayName(),
                totals.remainingPoints.toPlainString(),
                display(totals.remainingPoints),
                overloaded,
                List.copyOf(totals.openTaskIds),
                List.copyOf(totals.overdueTaskIds),
                List.copyOf(totals.nearDueTaskIds),
                totals.overdueTaskIds.size(),
                totals.nearDueTaskIds.size()));
        }

        var counts = new Counts(remainingCount, reviewTaskIds.size(), overdueCount, nearDueCount);
        return new Workload(projectId, capture.snapshot(), settings.projectZone(), today, settings,
            CanonicalState.calculationKey(capture), List.copyOf(members), List.copyOf(openTasks), List.copyOf(reviewTaskIds),
            counts, WORKLOAD_FORMULA, WORKLOAD_LIMITATIONS);
    }

    // ---------------------------------------------------------------- input checks

    private static TaskStatus statusOf(ProjectSnapshot.Task task) {
        try {
            return TaskStatus.from(task.status());
        } catch (IllegalArgumentException unknown) {
            throw unavailable();
        }
    }

    /** The task's points, which must be a two-decimal amount in the allowed range. */
    private static BigDecimal points(ProjectSnapshot.Task task) {
        try {
            var points = new BigDecimal(task.effortPoints());
            boolean inRange = points.compareTo(MINIMUM_POINTS) >= 0 && points.compareTo(MAXIMUM_POINTS) <= 0;
            if (!inRange || points.scale() != 2) {
                throw unavailable();
            }
            return points;
        } catch (NumberFormatException invalid) {
            throw unavailable();
        }
    }

    /**
     * The task's assignee ids, after checking each assignment belongs to this task, project and a current
     * member, with no duplicates and one to six assignees. A completed task also needs every assignee to
     * have accepted its final agreement.
     */
    private static List<UUID> assignees(UUID projectId, Set<UUID> memberIds, ProjectSnapshot.Task task, boolean completed) {
        var ids = new ArrayList<UUID>();
        for (var assignment : task.assignees()) {
            boolean consistent = memberIds.contains(assignment.userId())
                && assignment.taskId().equals(task.id())
                && assignment.projectId().equals(projectId)
                && !ids.contains(assignment.userId());
            boolean acceptedFinalAgreement = Objects.equals(assignment.acceptedAgreementVersion(), task.agreementVersion())
                && assignment.acceptedAtUtc() != null;
            if (!consistent || (completed && !acceptedFinalAgreement)) {
                throw unavailable();
            }
            ids.add(assignment.userId());
        }
        if (ids.isEmpty() || ids.size() > MAXIMUM_ASSIGNEES) {
            throw unavailable();
        }
        return List.copyOf(ids);
    }

    /** One decimal place for display, rounding halves up. */
    private static String display(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
