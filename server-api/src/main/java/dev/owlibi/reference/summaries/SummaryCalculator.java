package dev.owlibi.reference.summaries;

import dev.owlibi.reference.snapshots.*;
import dev.owlibi.reference.web.ApiFailure;
import static dev.owlibi.reference.summaries.SummaryDtos.*;
import java.math.*;
import java.util.*;

/** Pure domain arithmetic over a detached authorized capture, reusable by the later workbook. */
public final class SummaryCalculator {
    private SummaryCalculator() {}
    private static final MathContext MC=MathContext.DECIMAL128;
    public static final String CONTRIBUTION_FORMULA="Completed effort-points share = member's sum of equal Done-task allocations / sum of Done-task effort points × 100. No completed tasks yet when the total is zero.";
    public static final List<String> CONTRIBUTION_LIMITATIONS=List.of(
        "Relative estimates and task-splitting choices affect results.","Not measured effort, quality, fairness, grades or proof of individual work.",
        "Small positive shares may display 0.0%.","Independently rounded percentages may total 99.9% or 100.1%.");
    public static final String WORKLOAD_FORMULA="Remaining points = equal shares of TODO and IN_PROGRESS effort points; overload means unrounded remaining points > the configured threshold. REVIEW has zero remaining points but remains date-visible. Overdue is before projectToday; near-due includes projectToday through projectToday + nearDueDays.";
    public static final List<String> WORKLOAD_LIMITATIONS=List.of("Project-scoped relative estimates, not hours or a fairness verdict.",
        "Dates use the server Clock and Asia/Manila; undated tasks still contribute qualifying remaining effort.",
        "Pending revised acceptance does not remove remaining effort. Done and Cancelled tasks are excluded.",
        "Independent snapshots and dates may differ; compare their tokens and date basis before combining results.");
    public static ApiFailure unavailable() { return new ApiFailure(503,"SUMMARY_UNAVAILABLE","Summary temporarily unavailable"); }
    private static BigDecimal points(ProjectSnapshot.Task task) {
        try {
            var p=new BigDecimal(task.effortPoints());
            if(p.compareTo(new BigDecimal("0.01"))<0 || p.compareTo(new BigDecimal("99999999.99"))>0 || p.scale()!=2) throw unavailable();
            return p;
        } catch(NumberFormatException invalid) { throw unavailable(); }
    }
    private static List<UUID> assignees(UUID projectId,Set<UUID> memberIds,ProjectSnapshot.Task task,boolean completed) {
        var ids=new ArrayList<UUID>();
        for(var a:task.assignees()) {
            if(!memberIds.contains(a.userId()) || !a.taskId().equals(task.id()) || !a.projectId().equals(projectId) || ids.contains(a.userId()) ||
                (completed && (!Objects.equals(a.acceptedAgreementVersion(),task.agreementVersion()) || a.acceptedAtUtc()==null))) throw unavailable();
            ids.add(a.userId());
        }
        if(ids.isEmpty() || ids.size()>6) throw unavailable();return List.copyOf(ids);
    }
    private static String display(BigDecimal value) { return value.setScale(1,RoundingMode.HALF_UP).toPlainString(); }
    public static Contribution contribution(ProjectSnapshot capture) {
        var sums=new LinkedHashMap<UUID,BigDecimal>();capture.members().forEach(m -> sums.put(m.id(),BigDecimal.ZERO));
        var allocations=new ArrayList<Allocation>();BigDecimal total=new BigDecimal("0.00");
        for(var task:capture.tasks()) if(task.status().equals("DONE")) {
            var ids=assignees(capture.project().id(),sums.keySet(),task,true);var p=points(task);var share=p.divide(BigDecimal.valueOf(ids.size()),MC);
            total=total.add(p);
            for(var id:ids) sums.put(id,sums.get(id).add(share));
            allocations.add(new Allocation(task.id(),task.title(),task.effortPoints(),ids,share.toPlainString()));
        }
        boolean empty=total.signum()==0;var members=new ArrayList<CompletedMember>();
        for(var member:capture.members()) {
            var sum=sums.get(member.id());var percent=empty?null:sum.divide(total,MC).multiply(new BigDecimal("100"),MC);
            members.add(new CompletedMember(member.id(),member.displayName(),sum.toPlainString(),display(sum),
                percent==null?null:percent.toPlainString(),percent==null?null:display(percent)));
        }
        return new Contribution(capture.project().id(),capture.snapshot(),capture.settings().projectZone(),capture.projectToday(),
            capture.settings().configured()?capture.settings():null,empty,total.toPlainString(),display(total),List.copyOf(members),
            List.copyOf(allocations),CONTRIBUTION_FORMULA,CONTRIBUTION_LIMITATIONS);
    }
    private static final class Remaining {
        BigDecimal points=BigDecimal.ZERO;
        final List<UUID> open=new ArrayList<>(),overdue=new ArrayList<>(),near=new ArrayList<>();
    }
    public static Workload workload(ProjectSnapshot capture) {
        if(!capture.settings().configured()) throw new ApiFailure(409,"SETTINGS_REQUIRED","The leader must configure project rules");
        var sums=new LinkedHashMap<UUID,Remaining>();capture.members().forEach(m -> sums.put(m.id(),new Remaining()));
        var tasks=new ArrayList<OpenTask>();var review=new ArrayList<UUID>();int openCount=0,overdueCount=0,nearCount=0;
        var nearDueCutoff=capture.projectToday().plusDays(capture.settings().nearDueDays());
        for(var task:capture.tasks()) if(Set.of("TODO","IN_PROGRESS","REVIEW").contains(task.status())) {
            var ids=assignees(capture.project().id(),sums.keySet(),task,false);var p=points(task);boolean open=!task.status().equals("REVIEW");
            var share=open?p.divide(BigDecimal.valueOf(ids.size()),MC):BigDecimal.ZERO;
            String date=task.dueDate()==null?"NONE":task.dueDate().isBefore(capture.projectToday())?"OVERDUE":
                !task.dueDate().isAfter(nearDueCutoff)?"NEAR_DUE":"LATER";
            if(open) openCount++;else review.add(task.id());if(date.equals("OVERDUE")) overdueCount++;if(date.equals("NEAR_DUE")) nearCount++;
            for(var id:ids) {
                var sum=sums.get(id);sum.points=sum.points.add(share);if(open) sum.open.add(task.id());
                if(date.equals("OVERDUE")) sum.overdue.add(task.id());if(date.equals("NEAR_DUE")) sum.near.add(task.id());
            }
            tasks.add(new OpenTask(task.id(),task.title(),task.status(),task.dueDate(),task.effortPoints(),ids,share.toPlainString(),date));
        }
        var threshold=new BigDecimal(capture.settings().overloadThreshold());var members=new ArrayList<WorkloadMember>();
        for(var member:capture.members()) {
            var sum=sums.get(member.id());members.add(new WorkloadMember(member.id(),member.displayName(),sum.points.toPlainString(),display(sum.points),
                sum.points.compareTo(threshold)>0,List.copyOf(sum.open),List.copyOf(sum.overdue),List.copyOf(sum.near),sum.overdue.size(),sum.near.size()));
        }
        return new Workload(capture.project().id(),capture.snapshot(),capture.settings().projectZone(),capture.projectToday(),capture.settings(),
            CanonicalState.calculationKey(capture),List.copyOf(members),List.copyOf(tasks),List.copyOf(review),
            new Counts(openCount,review.size(),overdueCount,nearCount),WORKLOAD_FORMULA,WORKLOAD_LIMITATIONS);
    }
}
