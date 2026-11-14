package dev.owlibi.reference.snapshots;

import dev.owlibi.reference.reports.ReportBudget;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Count compact sorted-key safe domain JSON incrementally, before retaining each row. */
public final class CaptureBudget {
    private final ReportBudget deadline;
    // [project,members,settings,tasks,events]: brackets, four commas, three empty array pairs.
    private long bytes=12;
    private final Set<String> nonempty=new HashSet<>();
    public CaptureBudget(ReportBudget deadline) { this.deadline=deadline; }
    public void statement(JdbcTemplate jdbc) {
        deadline.check();int remaining=deadline.remainingMillis();
        int network=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) connection -> connection.getNetworkTimeout());
        // Leave time for PostgreSQL cancellation before an earlier driver socket timeout closes the connection.
        int ms=network>0?Math.min(remaining,Math.max(1,network-500)):remaining;
        jdbc.execute("SET LOCAL statement_timeout = '"+ms+"ms'");
        jdbc.execute("SET LOCAL lock_timeout = '"+ms+"ms'");
    }
    public void add(String section,Object row) {
        deadline.check();long size=CanonicalState.json(row).getBytes(StandardCharsets.UTF_8).length;
        if(Set.of("members","tasks","events").contains(section) && !nonempty.add(section)) size++;
        if(size>ReportBudget.TEXT-bytes) throw ReportBudget.tooLarge();bytes+=size;
    }
    public void rawRow(long size) { deadline.check();if(size>ReportBudget.TEXT-bytes) throw ReportBudget.tooLarge(); }
    public void check() { deadline.check(); }
    public long bytes() { return bytes; }
}
